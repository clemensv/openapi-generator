/*
 * Copyright 2026 OpenAPI-Generator Contributors (https://openapi-generator.tech)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.openapitools.codegen.schema.jsonstructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.openapitools.codegen.schema.SchemaDialect;
import org.openapitools.codegen.schema.SchemaDialectDetector;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Resolves JSON Structure resources embedded by the OpenAPI binding.
 *
 * <p>The normative baseline is Core draft-vasters-json-structure-core-04
 * (core checkout 7371ab9, tag {@code draft-vasters-json-structure-core-04},
 * content commit 395e80e) and the current Core v0 meta-schema
 * (meta commit c5efa13).
 * Where those artifacts disagree, the draft controls structural semantics.
 * The OpenAPI binding controls materialized identity and root-name behavior.
 * Narrow choices for known draft/meta inconsistencies are documented at the
 * relevant checks below.</p>
 *
 * <p>This class is a generator graph resolver, not a schema or instance
 * validator. Validation is delegated through {@link JsonStructureValidationAdapter}
 * to the official SDK when available. Local checks protect graph construction
 * and codegen invariants only.</p>
 */
public final class JsonStructureResolver {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");


    private static final Set<String> STANDARD_ADD_INS = Set.of(
            "JSONStructureAlternateNames",
            "JSONStructureUnits",
            "JSONStructureImport",
            "JSONStructureConditionalComposition",
            "JSONStructureValidation");

    private final JsonStructureValidationAdapter validationAdapter;

    public JsonStructureResolver() {
        this(JsonStructureValidationAdapter.discoverSdk());
    }

    public JsonStructureResolver(JsonStructureValidationAdapter validationAdapter) {
        this.validationAdapter = java.util.Objects.requireNonNull(validationAdapter);
    }

    public boolean isSchemaValidationAvailable() {
        return validationAdapter.isAvailable();
    }

    public JsonStructureTypeGraph resolve(JsonNode openApiDocument) {
        return resolve(openApiDocument, null, new JsonStructureResolutionOptions());
    }

    public JsonStructureTypeGraph resolve(JsonNode openApiDocument, URI retrievalUri) {
        return resolve(openApiDocument, retrievalUri, new JsonStructureResolutionOptions());
    }

    public JsonStructureTypeGraph resolve(
            JsonNode openApiDocument,
            URI retrievalUri,
            JsonStructureResolutionOptions options) {
        Map<String, SchemaDialect> customMetaSchemas = options.getVerifiedCustomMetaSchemas();
        Map<String, Set<String>> customMetaSchemaOffers =
                options.getVerifiedCustomMetaSchemaOffers();
        Map<String, SchemaDialect> dialects = SchemaDialectDetector.componentSchemaDialects(
                openApiDocument, customMetaSchemas);
        List<String> unknown = dialects.entrySet().stream()
                .filter(entry -> entry.getValue() == SchemaDialect.UNKNOWN)
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toList());
        if (!unknown.isEmpty()) {
            throw new JsonStructureResolutionException(
                    "Unknown schema dialect for components " + unknown
                            + "; refusing OAS/JSON Schema fallback");
        }
        JsonNode schemas = openApiDocument.path("components").path("schemas");
        URI baseUri = descriptionBaseUri(openApiDocument, retrievalUri, options);
        requireMaterializableIdentities(schemas, dialects, baseUri);
        Map<String, JsonNode> materializedResources = materializeForValidation(
                openApiDocument, schemas, dialects, baseUri);
        Map<String, JsonNode> externalResources = options.getExternalResources();
        Map<String, JsonNode> validationResources = new LinkedHashMap<>(materializedResources);
        externalResources.forEach(
                (id, resource) -> validationResources.put("external:" + id, resource));
        validationAdapter.validate(validationResources);
        Map<String, ResourceDraft> resources = new LinkedHashMap<>();
        Map<String, ResourceDraft> resourcesById = new LinkedHashMap<>();
        Map<String, ResourceDraft> importableResourcesById = new LinkedHashMap<>();

        dialects.forEach((componentName, dialect) -> {
            if (!dialect.isJsonStructure()) {
                return;
            }
            JsonNode resourceNode = schemas.get(componentName);
            String metaSchemaUri = SchemaDialectDetector.effectiveDialectUri(
                    openApiDocument, resourceNode);
            ResourceDraft resource = parseResource(
                    componentName, resourceNode, baseUri, dialect, metaSchemaUri,
                    customMetaSchemas, customMetaSchemaOffers);
            ResourceDraft previous = resourcesById.putIfAbsent(resource.id.toString(), resource);
            if (previous != null) {
                throw new JsonStructureResolutionException(
                        "Duplicate JSON Structure $id " + resource.id
                                + " in components " + previous.componentName + " and " + componentName);
            }
            if (resource.explicitId) {
                importableResourcesById.put(resource.id.toString(), resource);
            }
            resources.put(componentName, resource);
        });

        externalResources.forEach((id, node) -> {
            String metaSchemaUri = requiredText(node, "$schema", "external resource " + id);
            SchemaDialect dialect = SchemaDialectDetector.fromUri(metaSchemaUri, customMetaSchemas);
            if (!dialect.isJsonStructure()) {
                throw new JsonStructureResolutionException(
                        "External resource " + id + " has an unknown JSON Structure dialect");
            }
            ResourceDraft resource = parseResource(
                    id,
                    node,
                    null,
                    dialect,
                    metaSchemaUri,
                    customMetaSchemas,
                    customMetaSchemaOffers);
            ResourceDraft duplicate = resourcesById.putIfAbsent(resource.id.toString(), resource);
            if (duplicate != null) {
                throw new JsonStructureResolutionException(
                        "Duplicate JSON Structure $id " + resource.id);
            }
            importableResourcesById.put(resource.id.toString(), resource);
        });

        Map<QualifiedTypeName, JsonStructureTypeDeclaration> declarations = new LinkedHashMap<>();
        Map<String, QualifiedTypeName> roots = new LinkedHashMap<>();
        for (ResourceDraft resource : resources.values()) {
            resolveResource(resource, importableResourcesById, new ArrayDeque<>());
            declarations.putAll(resource.resolvedDeclarations);
            if (resource.root != null) {
                roots.put(resource.componentName, resource.root);
            }
        }

        Map<QualifiedTypeName, Set<String>> addInNames = resolveAddIns(resources.values(), declarations);
        validateReferences(declarations);
        validateRoots(resources.values(), declarations);
        validateInheritance(declarations, addInNames);
        validateAddInConflicts(resources.values(), declarations);

        Map<String, String> componentById = new LinkedHashMap<>();
        resources.values().forEach(
                resource -> componentById.put(resource.id.toString(), resource.componentName));
        Map<String, JsonStructureResource> resourceModels = new LinkedHashMap<>();
        resources.values().forEach(resource -> resourceModels.put(
                resource.componentName, toResourceModel(resource)));
        return new JsonStructureTypeGraph(
                declarations, roots, componentById, resourceModels,
                validationAdapter.isAvailable());
    }

    private void requireMaterializableIdentities(
            JsonNode schemas, Map<String, SchemaDialect> dialects, URI baseUri) {
        if (baseUri != null) {
            return;
        }
        List<String> missingIds = new ArrayList<>();
        dialects.forEach((componentName, dialect) -> {
            if (!dialect.isJsonStructure()) {
                return;
            }
            JsonNode schema = schemas.get(componentName);
            if (schema == null
                    || !schema.isObject()
                    || !schema.path("$id").isTextual()) {
                missingIds.add(componentName);
            }
        });
        if (!missingIds.isEmpty()) {
            throw new JsonStructureResolutionException(
                    "JSON Structure components " + missingIds
                            + " require explicit $id because no OpenAPI base URI is available");
        }
    }

    private Map<String, JsonNode> materializeForValidation(
            JsonNode document, JsonNode schemas,
            Map<String, SchemaDialect> dialects, URI baseUri) {
        Map<String, JsonNode> resources = new LinkedHashMap<>();
        dialects.forEach((componentName, dialect) -> {
            if (!dialect.isJsonStructure()) {
                return;
            }
            JsonNode source = schemas.get(componentName);
            JsonNode materialized = source == null ? null : source.deepCopy();
            if (materialized instanceof ObjectNode) {
                ObjectNode object = (ObjectNode) materialized;
                if (!object.has("$schema")) {
                    String effectiveUri = SchemaDialectDetector.effectiveDialectUri(document, source);
                    object.put("$schema", effectiveUri == null ? dialectUri(dialect) : effectiveUri);
                }
                if (!object.has("$id") && baseUri != null) {
                    object.put("$id", defaultId(baseUri, componentName).toString());
                }
            }
            resources.put(componentName, materialized);
        });
        return resources;
    }

    private ResourceDraft parseResource(
            String componentName, JsonNode node, URI baseUri, SchemaDialect dialect,
            String metaSchemaUri, Map<String, SchemaDialect> customMetaSchemas,
            Map<String, Set<String>> customMetaSchemaOffers) {
        if (node == null || !node.isObject()) {
            throw new JsonStructureResolutionException(
                    "JSON Structure component " + componentName + " must be an object");
        }

        if (node.has("$schema")) {
            String declaredSchema = requiredText(node, "$schema", componentName);
            parseAbsoluteUri(declaredSchema, componentName + "/$schema");
            if (SchemaDialectDetector.fromUri(declaredSchema, customMetaSchemas) != dialect) {
                throw new JsonStructureResolutionException(
                        "$schema of " + componentName + " does not match its effective dialect");
            }
        }

        String idValue = optionalText(node, "$id", componentName);
        if (idValue == null && baseUri == null) {
            throw new JsonStructureResolutionException(
                    "JSON Structure component " + componentName
                            + " must declare $id when the OpenAPI Description has no base URI");
        }
        URI id = idValue == null
                ? defaultId(baseUri, componentName)
                : parseAbsoluteUri(idValue, componentName + "/$id");
        ResourceDraft resource = new ResourceDraft(
                componentName,
                id,
                dialect,
                metaSchemaUri,
                customMetaSchemaOffers.getOrDefault(metaSchemaUri, Set.of()));
        resource.explicitId = idValue != null;
        resource.documentName = optionalText(node, "name", componentName);
        if (resource.documentName != null) {
            validateIdentifier(resource.documentName, "Document");
        }
        resource.description = optionalText(node, "description", componentName);
        resource.examples = examples(node, componentName);
        parseUses(resource, node);
        parseOffers(resource, node);
        addImport(resource, List.of(), node);

        JsonNode definitions = node.get("definitions");
        if (definitions != null) {
            if (!definitions.isObject()) {
                throw new JsonStructureResolutionException(
                        "definitions in " + componentName + " must be an object");
            }
            parseDefinitions(resource, definitions, List.of());
        }

        boolean hasRoot = node.has("$root");
        boolean hasType = node.has("type");
        if (hasRoot && hasType) {
            throw new JsonStructureResolutionException(
                    "JSON Structure component " + componentName + " cannot declare both type and $root");
        }
        if (hasRoot) {
            String rootPointer = requiredText(node, "$root", componentName);
            resource.root = pointerToName(id, rootPointer);
        } else if (hasType) {
            // The OAS binding requires a name for every extracted root type, not
            // only for object/tuple as the stale v0 meta-schema suggests.
            String rootName = requiredText(node, "name", componentName);
            validateIdentifier(rootName, "Root type");
            JsonNode rootType = node.get("type");
            if (rootType.isArray()) {
                throw new JsonStructureResolutionException(
                        "A root type union must be declared in definitions and selected with $root");
            }
            if (rootType.isObject()) {
                throw new JsonStructureResolutionException(
                        "$ref is not permitted inside type at a JSON Structure document root");
            }
            resource.root = parseDeclaration(
                    resource, List.of(), rootName, node, true, false, true);
        }

        return resource;
    }


    private void parseDefinitions(
            ResourceDraft resource, JsonNode definitions, List<String> namespace) {
        addImport(resource, namespace, definitions);
        definitions.fields().forEachRemaining(entry -> {
            String name = entry.getKey();
            if ("$import".equals(name) || "$importdefs".equals(name)) {
                return;
            }
            if (name.startsWith("$")) {
                return;
            }
            validateIdentifier(name, "Definition");
            JsonNode value = entry.getValue();
            if (!value.isObject()) {
                throw new JsonStructureResolutionException(
                        "Definition " + qualified(namespace, name) + " in " + resource.componentName
                                + " must be an object");
            }
            if (value.has("type")) {
                parseDeclaration(resource, namespace, name, value, false, false, false);
            } else {
                parseDefinitions(resource, value, append(namespace, name));
            }
        });
    }


    private void addImport(ResourceDraft resource, List<String> namespace, JsonNode node) {
        String importUri = optionalText(node, "$import", qualified(namespace, resource.componentName));
        String importDefinitionsUri = optionalText(
                node, "$importdefs", qualified(namespace, resource.componentName));
        if (importUri != null && importDefinitionsUri != null) {
            throw new JsonStructureResolutionException(
                    "Namespace " + qualified(namespace, resource.componentName)
                            + " cannot declare both $import and $importdefs");
        }
        if (importUri != null || importDefinitionsUri != null) {
            if (!resource.effectiveUses.contains("JSONStructureImport")) {
                throw new JsonStructureResolutionException(
                        "$import and $importdefs require the JSON StructureImport add-in");
            }
            URI importId = parseAbsoluteUri(
                    importUri != null ? importUri : importDefinitionsUri,
                    qualified(namespace, resource.componentName));
            resource.imports.add(new ImportRequest(
                    namespace, withoutFragment(importId), importUri != null));
        }
    }

    private void parseUses(ResourceDraft resource, JsonNode node) {
        if (!node.has("$uses")) {
            setEffectiveUses(resource);
            return;
        }
        JsonNode uses = node.get("$uses");
        if (!uses.isArray()) {
            throw new JsonStructureResolutionException(
                    "$uses of " + resource.componentName + " must be an array of unique strings");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (JsonNode value : uses) {
            if (!value.isTextual() || value.textValue().isEmpty()) {
                throw new JsonStructureResolutionException(
                        "$uses of " + resource.componentName + " must contain non-empty strings");
            }
            String token = value.textValue();
            if (!unique.add(token)) {
                throw new JsonStructureResolutionException(
                        "$uses of " + resource.componentName + " must be a set; duplicate " + token);
            }
            resource.declaredUses.add(token);
            if (token.startsWith("#")) {
                // Pointer-form uses address add-in definitions in the selected
                // meta-schema, not declarations in this schema resource.
            } else if (!resource.metaSchemaOffers.contains(token)
                    && (!STANDARD_ADD_INS.contains(token)
                            || resource.dialect == SchemaDialect.JSON_STRUCTURE_CORE)) {
                throw new JsonStructureResolutionException(
                        "Add-in " + token + " is not offered by " + resource.metaSchemaUri);
            }
        }
        setEffectiveUses(resource);
    }

    private void setEffectiveUses(ResourceDraft resource) {
        Set<String> effective = new LinkedHashSet<>();
        if (resource.dialect == SchemaDialect.JSON_STRUCTURE_EXTENDED) {
            effective.add("JSONStructureImport");
        } else if (resource.dialect == SchemaDialect.JSON_STRUCTURE_VALIDATION) {
            effective.addAll(List.of(
                    "JSONStructureImport",
                    "JSONStructureAlternateNames",
                    "JSONStructureUnits",
                    "JSONStructureConditionalComposition",
                    "JSONStructureValidation"));
        }
        effective.addAll(resource.declaredUses);
        resource.effectiveUses = List.copyOf(effective);
    }

    private void parseOffers(ResourceDraft resource, JsonNode node) {
        if (!node.has("$offers")) {
            return;
        }
        JsonNode offers = node.get("$offers");
        if (!offers.isObject()) {
            throw new JsonStructureResolutionException(
                    "$offers of " + resource.componentName + " must be an object");
        }
        offers.fields().forEachRemaining(entry -> {
            String offerName = entry.getKey();
            if (offerName.isEmpty()) {
                throw new JsonStructureResolutionException("$offers names must not be empty");
            }
            List<QualifiedTypeName> pointers = new ArrayList<>();
            JsonNode value = entry.getValue();
            if (value.isTextual()) {
                pointers.add(pointerToName(resource.id, value.textValue()));
            } else if (value.isArray()) {
                Set<QualifiedTypeName> unique = new LinkedHashSet<>();
                for (JsonNode pointer : value) {
                    if (!pointer.isTextual()) {
                        throw new JsonStructureResolutionException(
                                "$offers/" + offerName + " must contain JSON Pointer strings");
                    }
                    QualifiedTypeName target = pointerToName(resource.id, pointer.textValue());
                    if (!unique.add(target)) {
                        throw new JsonStructureResolutionException(
                                "$offers/" + offerName + " contains a duplicate add-in target");
                    }
                    pointers.add(target);
                }
            } else {
                throw new JsonStructureResolutionException(
                        "$offers/" + offerName + " must be a JSON Pointer or an array of pointers");
            }
            resource.rawOffers.put(offerName, List.copyOf(pointers));
        });
    }

    private QualifiedTypeName parseDeclaration(
            ResourceDraft resource,
            List<String> namespace,
            String name,
            JsonNode node,
            boolean rootDeclaration,
            boolean synthetic,
            boolean rootOwned) {
        validateIdentifier(name, "Type");
        QualifiedTypeName qualifiedName = new QualifiedTypeName(resource.id, namespace, name);
        if (resource.localDeclarations.containsKey(qualifiedName)) {
            throw new JsonStructureResolutionException("Duplicate declaration " + qualifiedName);
        }

        JsonStructureTypeKind kind = declarationKind(node, qualifiedName.toString());
        validateDeclarationName(qualifiedName, name, node, rootDeclaration);

        JsonStructureTypeUse declaredType = isStructuralCompound(kind)
                ? null
                : parseTypeUse(resource, namespace, name, node, rootOwned);

        Map<String, JsonStructureTypeUse> properties = new LinkedHashMap<>();
        JsonNode propertyNodes = node.get("properties");
        if (propertyNodes != null) {
            if (!propertyNodes.isObject()) {
                throw new JsonStructureResolutionException(
                        "properties of " + qualifiedName + " must be an object");
            }
            propertyNodes.fields().forEachRemaining(property -> {
                validateIdentifier(property.getKey(), "Property");
                properties.put(
                        property.getKey(),
                        parseTypeUse(
                                resource, append(namespace, name), property.getKey(),
                                property.getValue(), rootOwned));
            });
        }

        JsonStructureTypeUse items = node.has("items")
                ? parseTypeUse(
                        resource, append(namespace, name), "Items", node.get("items"), rootOwned)
                : null;
        JsonStructureTypeUse values = node.has("values")
                ? parseTypeUse(
                        resource, append(namespace, name), "Values", node.get("values"), rootOwned)
                : null;

        Boolean additionalPropertiesAllowed = null;
        JsonStructureTypeUse additionalPropertiesType = null;
        JsonNode additionalProperties = node.get("additionalProperties");
        if (additionalProperties != null) {
            if (additionalProperties.isBoolean()) {
                additionalPropertiesAllowed = additionalProperties.booleanValue();
            } else if (additionalProperties.isObject()) {
                additionalPropertiesType = parseTypeUse(
                        resource, append(namespace, name), "AdditionalProperty",
                        additionalProperties, rootOwned);
            } else {
                throw new JsonStructureResolutionException(
                        "additionalProperties of " + qualifiedName + " must be a boolean or schema");
            }
        }

        Map<String, JsonStructureTypeUse> choices = new LinkedHashMap<>();
        JsonNode choiceNodes = node.get("choices");
        if (choiceNodes != null) {
            if (!choiceNodes.isObject()) {
                throw new JsonStructureResolutionException(
                        "choices of " + qualifiedName + " must be an object");
            }
            choiceNodes.fields().forEachRemaining(choice -> {
                validateIdentifier(choice.getKey(), "Choice");
                choices.put(
                        choice.getKey(),
                        parseTypeUse(
                                resource, append(namespace, name), choice.getKey(),
                                choice.getValue(), rootOwned));
            });
        }

        List<List<String>> required = requiredAlternatives(
                node.get("required"), qualifiedName.toString());
        List<String> tupleOrder = node.has("tuple")
                ? stringList(node.get("tuple"), "tuple of " + qualifiedName)
                : List.of();
        List<QualifiedTypeName> bases = referenceList(
                resource.id, node.get("$extends"), qualifiedName.toString());
        boolean abstractType = node.has("abstract")
                && requiredBoolean(node, "abstract", qualifiedName.toString());

        validateStructure(
                qualifiedName, kind, node, properties, required, choices, items, values,
                tupleOrder, bases, abstractType, additionalPropertiesAllowed, additionalPropertiesType);

        String description = optionalText(node, "description", qualifiedName.toString());
        List<Object> declarationExamples = examples(node, qualifiedName.toString());
        JsonStructureTypeDeclaration declaration = new JsonStructureTypeDeclaration(
                qualifiedName, qualifiedName, kind, kind.wireKind(), abstractType, synthetic, rootOwned,
                description, declarationExamples, properties, required, bases, choices,
                optionalText(node, "selector", qualifiedName.toString()),
                items, values, tupleOrder,
                declaredType == null ? null : declaredType.getPrecision(),
                declaredType == null ? null : declaredType.getScale(),
                declaredType == null ? null : declaredType.getMaxLength(),
                declaredType, additionalPropertiesAllowed, additionalPropertiesType);
        resource.localDeclarations.put(qualifiedName, declaration);
        return qualifiedName;
    }


    private JsonStructureTypeUse parseTypeUse(
            ResourceDraft resource,
            List<String> namespace,
            String syntheticName,
            JsonNode schemaNode,
            boolean rootOwned) {
        String location = qualified(namespace, syntheticName);
        if (schemaNode == null || !schemaNode.isObject()) {
            throw new JsonStructureResolutionException(
                    "Schema " + location + " must be an object and declare type");
        }
        if (!schemaNode.has("type")) {
            throw new JsonStructureResolutionException(
                    "Schema " + location + " must declare type");
        }
        if (schemaNode.has("$ref")) {
            throw new JsonStructureResolutionException(
                    "$ref must occur only inside type at " + location);
        }

        String description = optionalText(schemaNode, "description", location);
        List<Object> useExamples = examples(schemaNode, location);
        JsonNode typeNode = schemaNode.get("type");
        if (typeNode.isTextual()) {
            JsonStructureTypeKind kind = typeKind(typeNode.textValue(), location);
            if (isStructuralCompound(kind)) {
                String nestedName = optionalText(schemaNode, "name", location);
                QualifiedTypeName nested = parseDeclaration(
                        resource,
                        namespace,
                        nestedName == null ? syntheticName : nestedName,
                        schemaNode,
                        false,
                        true,
                        rootOwned);
                return JsonStructureTypeUse.reference(nested, null, description, useExamples);
            }
            return primitiveUse(kind, typeNode.textValue(), schemaNode, description, useExamples, location);
        }

        if (typeNode.isArray()) {
            if (typeNode.isEmpty()) {
                throw new JsonStructureResolutionException("Type union cannot be empty at " + location);
            }
            List<JsonStructureTypeAlternative> alternatives = new ArrayList<>();
            Set<String> identities = new LinkedHashSet<>();
            for (JsonNode value : typeNode) {
                JsonStructureTypeAlternative alternative;
                if (value.isTextual()) {
                    JsonStructureTypeKind kind = typeKind(value.textValue(), location);
                    if (!isPrimitive(kind)) {
                        throw new JsonStructureResolutionException(
                                "Type union " + location
                                        + " may contain only primitive names or definitions references");
                    }
                    alternative = JsonStructureTypeAlternative.primitive(kind, value.textValue());
                } else if (value.isObject()) {
                    alternative = typeReferenceAlternative(resource.id, value, location + "/type");
                } else {
                    throw new JsonStructureResolutionException(
                            "Type union " + location
                                    + " may contain only primitive names or definitions references");
                }
                String semanticIdentity = alternative.isPrimitive()
                        ? "primitive:" + alternative.getPrimitiveKind()
                        : "reference:" + alternative.getReference();
                if (!identities.add(semanticIdentity)) {
                    throw new JsonStructureResolutionException(
                            "Type union alternatives must be unique at " + location);
                }
                alternatives.add(alternative);
            }
            return readValueAnnotations(
                    JsonStructureTypeUse.of(
                            alternatives, null, null, null, description, useExamples),
                    schemaNode,
                    location);
        }

        if (typeNode.isObject()) {
            JsonStructureTypeAlternative reference = typeReferenceAlternative(
                    resource.id, typeNode, location + "/type");
            return readValueAnnotations(
                    JsonStructureTypeUse.of(
                            List.of(reference), null, null, null, description, useExamples),
                    schemaNode,
                    location);
        }
        throw new JsonStructureResolutionException(
                "Unsupported type expression at " + location
                        + "; type must be a string, ordered union, or $ref object");
    }

    private JsonStructureTypeAlternative typeReferenceAlternative(
            URI resourceId, JsonNode node, String location) {
        if (!node.has("$ref")) {
            // The draft contains one contradictory inline-map union example;
            // the normative union rule and the v0 meta-schema both prohibit it.
            throw new JsonStructureResolutionException(
                    "A type-reference object must contain $ref at " + location);
        }
        String pointer = requiredText(node, "$ref", location);
        // Core first says a reference has a single member, while its type-reference
        // rule and current meta-schema explicitly permit this contextual description.
        String referenceDescription = optionalText(node, "description", location);
        return JsonStructureTypeAlternative.reference(
                pointerToName(resourceId, pointer), referenceDescription);
    }

    private JsonStructureTypeUse primitiveUse(
            JsonStructureTypeKind kind,
            String sourceName,
            JsonNode schemaNode,
            String description,
            List<Object> useExamples,
            String location) {
        Integer precision = null;
        Integer scale = null;
        if (kind == JsonStructureTypeKind.DECIMAL) {
            precision = schemaNode.has("precision")
                    ? integer(schemaNode.get("precision"), location + "/precision")
                    : 34;
            scale = schemaNode.has("scale")
                    ? integer(schemaNode.get("scale"), location + "/scale")
                    : 7;
        } else if (kind == JsonStructureTypeKind.NUMBER) {
            precision = schemaNode.has("precision")
                    ? integer(schemaNode.get("precision"), location + "/precision")
                    : null;
            scale = schemaNode.has("scale")
                    ? integer(schemaNode.get("scale"), location + "/scale")
                    : null;
        }
        Integer maxLength = schemaNode.has("maxLength")
                ? integer(schemaNode.get("maxLength"), location + "/maxLength")
                : null;
        JsonStructureTypeUse use = JsonStructureTypeUse.of(
                List.of(JsonStructureTypeAlternative.primitive(kind, sourceName)),
                precision, scale, maxLength, description, useExamples);
        use = applyBinaryAnnotations(use, kind, schemaNode, location);
        return readValueAnnotations(use, schemaNode, location);
    }


    private JsonStructureTypeUse applyBinaryAnnotations(
            JsonStructureTypeUse use,
            JsonStructureTypeKind kind,
            JsonNode schemaNode,
            String location) {
        if (kind != JsonStructureTypeKind.BINARY) {
            return use;
        }
        String contentEncoding = optionalText(schemaNode, "contentEncoding", location);
        String contentCompression = optionalText(schemaNode, "contentCompression", location);
        String contentMediaType = optionalText(schemaNode, "contentMediaType", location);
        return use.withBinaryAnnotations(
                contentEncoding == null ? "base64" : contentEncoding,
                contentCompression,
                contentMediaType);
    }

    private JsonStructureTypeUse readValueAnnotations(
            JsonStructureTypeUse use, JsonNode schemaNode, String location) {
        JsonNode enumNode = schemaNode.get("enum");
        boolean hasConst = schemaNode.has("const");
        if (enumNode == null && !hasConst) {
            return use;
        }
        List<Object> enumValues = new ArrayList<>();
        if (enumNode != null) {
            if (!enumNode.isArray()) {
                throw new JsonStructureResolutionException(
                        "Cannot ingest a non-array enum at " + location);
            }
            enumNode.forEach(value -> enumValues.add(plainValue(value)));
        }
        Object constValue = hasConst ? plainValue(schemaNode.get("const")) : null;
        return use.withValueConstraints(enumValues, hasConst, constValue);
    }

    private void validateStructure(
            QualifiedTypeName name,
            JsonStructureTypeKind kind,
            JsonNode node,
            Map<String, JsonStructureTypeUse> properties,
            List<List<String>> required,
            Map<String, JsonStructureTypeUse> choices,
            JsonStructureTypeUse items,
            JsonStructureTypeUse values,
            List<String> tupleOrder,
            List<QualifiedTypeName> bases,
            boolean abstractType,
            Boolean additionalPropertiesAllowed,
            JsonStructureTypeUse additionalPropertiesType) {
        if (kind == JsonStructureTypeKind.OBJECT
                && properties.isEmpty()
                && bases.isEmpty()) {
            throw new JsonStructureResolutionException(
                    "Object " + name + " must declare or inherit at least one property");
        }
        if ((kind == JsonStructureTypeKind.ARRAY || kind == JsonStructureTypeKind.SET)
                && items == null) {
            throw new JsonStructureResolutionException(
                    kind.name().toLowerCase(java.util.Locale.ROOT)
                            + " " + name + " must declare items");
        }
        if (kind == JsonStructureTypeKind.MAP && values == null) {
            throw new JsonStructureResolutionException("Map " + name + " must declare values");
        }
        if (kind == JsonStructureTypeKind.TUPLE) {
            if (!node.has("tuple")) {
                throw new JsonStructureResolutionException("Tuple " + name + " must declare tuple order");
            }
            if (properties.isEmpty() && bases.isEmpty()) {
                throw new JsonStructureResolutionException(
                        "Tuple " + name + " must declare or inherit properties");
            }
            if (tupleOrder.size() != properties.size()
                    || new LinkedHashSet<>(tupleOrder).size() != tupleOrder.size()
                    || !properties.keySet().equals(new LinkedHashSet<>(tupleOrder))) {
                throw new JsonStructureResolutionException(
                        "Tuple order of " + name + " must list every locally declared property exactly once");
            }
        }
        if (kind == JsonStructureTypeKind.CHOICE && choices.isEmpty()) {
            throw new JsonStructureResolutionException("Choice " + name + " must declare choices");
        }
        for (List<String> alternative : required) {
            if (new LinkedHashSet<>(alternative).size() != alternative.size()) {
                throw new JsonStructureResolutionException(
                        "Required property sets of " + name + " must contain unique names");
            }
            alternative.forEach(property -> validateIdentifier(property, "Required property"));
        }
        if (abstractType && (additionalPropertiesAllowed != null || additionalPropertiesType != null)) {
            throw new JsonStructureResolutionException(
                    "Abstract declarations implicitly allow additional properties and must not declare "
                            + "additionalProperties: " + name);
        }
    }

    private void validateDeclarationName(
            QualifiedTypeName qualifiedName,
            String addressName,
            JsonNode node,
            boolean rootDeclaration) {
        String declaredName = optionalText(node, "name", qualifiedName.toString());
        if (rootDeclaration && declaredName == null) {
            throw new JsonStructureResolutionException(
                    "Root declaration " + qualifiedName + " must declare name");
        }
        // Embedded schemas get their identity from their property/definition key.
        // This follows the draft schema-element rule and avoids imposing the
        // stale meta-schema omissions/inconsistent object prose on definitions.
        if (declaredName != null) {
            validateIdentifier(declaredName, "Type");
            if (!declaredName.equals(addressName)) {
                throw new JsonStructureResolutionException(
                        "Declared name " + declaredName
                                + " does not match its schema address name " + addressName);
            }
        }
    }

    private void resolveResource(
            ResourceDraft resource,
            Map<String, ResourceDraft> resourcesById,
            Deque<String> stack) {
        if (resource.resolved) {
            return;
        }
        String resourceIdentity = resource.id.toString();
        if (stack.contains(resourceIdentity)) {
            throw new JsonStructureResolutionException(
                    "Cyclic JSON Structure import: " + stack + " -> " + resource.id);
        }
        stack.addLast(resourceIdentity);

        Map<QualifiedTypeName, JsonStructureTypeDeclaration> resolved = new LinkedHashMap<>();
        for (ImportRequest request : resource.imports) {
            ResourceDraft source = resourcesById.get(request.resourceId);
            if (source == null) {
                throw new JsonStructureResolutionException(
                        "External JSON Structure import was not supplied by the caller: "
                                + request.resourceId);
            }
            resolveResource(source, resourcesById, stack);
            Map<QualifiedTypeName, QualifiedTypeName> rebindings = importRebindings(
                    resource, request.namespace, source.resolvedDeclarations.values(), resolved);
            for (JsonStructureTypeDeclaration sourceDeclaration : source.resolvedDeclarations.values()) {
                if (!request.includeRoot && sourceDeclaration.isRootOwned()) {
                    continue;
                }
                JsonStructureTypeDeclaration imported = rebindImportedDeclaration(
                        sourceDeclaration, rebindings);
                JsonStructureTypeDeclaration existing = resolved.putIfAbsent(
                        imported.getName(), imported);
                if (existing != null && !existing.getOrigin().equals(imported.getOrigin())) {
                    throw new JsonStructureResolutionException(
                            "Conflicting imported declarations for " + imported.getName()
                                    + " from " + existing.getOrigin() + " and " + imported.getOrigin());
                }
            }
        }
        // The Import companion explicitly permits complete local shadowing.
        resolved.putAll(resource.localDeclarations);
        resource.resolvedDeclarations = Collections.unmodifiableMap(resolved);
        resource.resolved = true;
        stack.removeLast();
    }

    private Map<QualifiedTypeName, QualifiedTypeName> importRebindings(
            ResourceDraft destination,
            List<String> importNamespace,
            Iterable<JsonStructureTypeDeclaration> sourceDeclarations,
            Map<QualifiedTypeName, JsonStructureTypeDeclaration> alreadyResolved) {
        Map<QualifiedTypeName, QualifiedTypeName> result = new LinkedHashMap<>();
        for (JsonStructureTypeDeclaration source : sourceDeclarations) {
            QualifiedTypeName visible = rebind(
                    destination.id, importNamespace, source.getName());
            if (!destination.localDeclarations.containsKey(visible)) {
                result.put(source.getName(), visible);
                continue;
            }
            // Import shadowing is legal, but imported cross-references must stay
            // bound to the imported graph rather than resolving against the local
            // declaration. Keep the shadowed imported node under an internal, valid
            // namespace and rewrite all references in the import batch to it.
            String discriminator = "_imported_"
                    + Integer.toUnsignedString(source.getOrigin().toString().hashCode(), 16);
            List<String> hiddenNamespace = new ArrayList<>(importNamespace);
            hiddenNamespace.add(discriminator);
            hiddenNamespace.addAll(source.getName().getNamespace());
            QualifiedTypeName hidden = new QualifiedTypeName(
                    destination.id, hiddenNamespace, source.getName().getLocalName());
            int suffix = 2;
            while (destination.localDeclarations.containsKey(hidden)
                    || alreadyResolved.containsKey(hidden)
                    || result.containsValue(hidden)) {
                hiddenNamespace.set(
                        importNamespace.size(), discriminator + "_" + suffix++);
                hidden = new QualifiedTypeName(
                        destination.id, hiddenNamespace, source.getName().getLocalName());
            }
            result.put(source.getName(), hidden);
        }
        return result;
    }

    private JsonStructureTypeDeclaration rebindImportedDeclaration(
            JsonStructureTypeDeclaration source,
            Map<QualifiedTypeName, QualifiedTypeName> rebindings) {
        QualifiedTypeName reboundName = rebindings.get(source.getName());
        return new JsonStructureTypeDeclaration(
                reboundName,
                source.getOrigin(),
                source.getKind(),
                source.getWireKind(),
                source.isAbstractType(),
                source.isSynthetic(),
                false,
                source.getDescription(),
                source.getExamples(),
                remapUses(rebindings, source.getProperties()),
                source.getRequiredAlternatives(),
                remapNames(rebindings, source.getBases()),
                remapUses(rebindings, source.getChoices()),
                source.getSelector(),
                remapUse(rebindings, source.getItems()),
                remapUse(rebindings, source.getValues()),
                source.getTupleOrder(),
                source.getPrecision(),
                source.getScale(),
                source.getMaxLength(),
                remapUse(rebindings, source.getDeclaredType()),
                source.getAdditionalPropertiesAllowed(),
                remapUse(rebindings, source.getAdditionalPropertiesType()));
    }

    private Map<String, JsonStructureTypeUse> remapUses(
            Map<QualifiedTypeName, QualifiedTypeName> rebindings,
            Map<String, JsonStructureTypeUse> uses) {
        Map<String, JsonStructureTypeUse> result = new LinkedHashMap<>();
        uses.forEach((name, use) -> result.put(name, remapUse(rebindings, use)));
        return result;
    }

    private JsonStructureTypeUse remapUse(
            Map<QualifiedTypeName, QualifiedTypeName> rebindings,
            JsonStructureTypeUse use) {
        if (use == null || !use.hasReferences()) {
            return use;
        }
        List<JsonStructureTypeAlternative> alternatives = new ArrayList<>();
        for (JsonStructureTypeAlternative alternative : use.getAlternatives()) {
            if (alternative.isPrimitive()) {
                alternatives.add(JsonStructureTypeAlternative.primitive(
                        alternative.getPrimitiveKind(), alternative.getPrimitiveName()));
            } else {
                QualifiedTypeName rebound = rebindings.get(alternative.getReference());
                if (rebound == null) {
                    throw new JsonStructureResolutionException(
                            "Imported reference escaped its source graph: "
                                    + alternative.getReference());
                }
                alternatives.add(JsonStructureTypeAlternative.reference(
                        rebound, alternative.getReferenceDescription()));
            }
        }
        return JsonStructureTypeUse.of(
                alternatives, use.getPrecision(), use.getScale(), use.getMaxLength(),
                use.getDescription(), use.getExamples())
                .withValueConstraints(use.getEnumValues(), use.hasConst(), use.getConstValue())
                .withBinaryAnnotations(
                        use.getContentEncoding(),
                        use.getContentCompression(),
                        use.getContentMediaType());
    }

    private List<QualifiedTypeName> remapNames(
            Map<QualifiedTypeName, QualifiedTypeName> rebindings,
            List<QualifiedTypeName> names) {
        List<QualifiedTypeName> result = new ArrayList<>();
        for (QualifiedTypeName name : names) {
            QualifiedTypeName rebound = rebindings.get(name);
            if (rebound == null) {
                throw new JsonStructureResolutionException(
                        "Imported $extends escaped its source graph: " + name);
            }
            result.add(rebound);
        }
        return result;
    }

    private QualifiedTypeName rebind(
            URI destinationResource, List<String> importNamespace, QualifiedTypeName source) {
        List<String> reboundNamespace = new ArrayList<>(importNamespace);
        reboundNamespace.addAll(source.getNamespace());
        return new QualifiedTypeName(
                destinationResource, reboundNamespace, source.getLocalName());
    }

    private Map<QualifiedTypeName, Set<String>> resolveAddIns(
            Iterable<ResourceDraft> resources,
            Map<QualifiedTypeName, JsonStructureTypeDeclaration> declarations) {
        Map<QualifiedTypeName, Set<String>> addInNames = new LinkedHashMap<>();
        for (ResourceDraft resource : resources) {
            Map<String, List<QualifiedTypeName>> resolvedOffers = new LinkedHashMap<>();
            resource.rawOffers.forEach((offerName, pointers) -> {
                for (QualifiedTypeName pointer : pointers) {
                    if (!declarations.containsKey(pointer)) {
                        throw new JsonStructureResolutionException(
                                "$offers/" + offerName + " does not resolve to a declaration: " + pointer);
                    }
                    addInNames.computeIfAbsent(pointer, ignored -> new LinkedHashSet<>())
                            .add(offerName);
                }
                resolvedOffers.put(offerName, pointers);
            });
            resource.resolvedOffers = Collections.unmodifiableMap(resolvedOffers);
        }
        return addInNames;
    }

    private void validateReferences(
            Map<QualifiedTypeName, JsonStructureTypeDeclaration> declarations) {
        declarations.values().forEach(declaration -> {
            declaration.getProperties().values().forEach(use ->
                    validateUse(declarations, declaration, use));
            declaration.getChoices().values().forEach(use ->
                    validateUse(declarations, declaration, use));
            validateUse(declarations, declaration, declaration.getItems());
            validateUse(declarations, declaration, declaration.getValues());
            validateUse(declarations, declaration, declaration.getDeclaredType());
            validateUse(declarations, declaration, declaration.getAdditionalPropertiesType());
        });
    }

    private void validateUse(
            Map<QualifiedTypeName, JsonStructureTypeDeclaration> declarations,
            JsonStructureTypeDeclaration owner,
            JsonStructureTypeUse use) {
        if (use == null) {
            return;
        }
        for (QualifiedTypeName reference : use.getReferenceAlternatives()) {
            JsonStructureTypeDeclaration target = declarations.get(reference);
            if (target == null) {
                throw new JsonStructureResolutionException(
                        "Unresolved type reference " + reference + " from " + owner.getName());
            }
            if (target.isAbstractType()) {
                throw new JsonStructureResolutionException(
                        "Abstract type cannot be referenced directly: " + reference);
            }
        }
    }

    private void validateRoots(
            Iterable<ResourceDraft> resources,
            Map<QualifiedTypeName, JsonStructureTypeDeclaration> declarations) {
        for (ResourceDraft resource : resources) {
            if (resource.root == null) {
                continue;
            }
            JsonStructureTypeDeclaration root = declarations.get(resource.root);
            if (root == null) {
                throw new JsonStructureResolutionException(
                        "Unresolved $root " + resource.root + " in " + resource.componentName);
            }
            if (root.isAbstractType()) {
                throw new JsonStructureResolutionException(
                        "$root cannot designate abstract type " + resource.root);
            }
        }
    }

    private void validateInheritance(
            Map<QualifiedTypeName, JsonStructureTypeDeclaration> declarations,
            Map<QualifiedTypeName, Set<String>> addInNames) {
        Set<QualifiedTypeName> visiting = new LinkedHashSet<>();
        Set<QualifiedTypeName> visited = new LinkedHashSet<>();
        declarations.keySet().forEach(name ->
                visitInheritance(name, declarations, visiting, visited));

        declarations.values().forEach(declaration -> {
            boolean addIn = isAddIn(declaration, addInNames);
            for (QualifiedTypeName base : declaration.getBases()) {
                JsonStructureTypeDeclaration target = declarations.get(base);
                validateBaseKind(declaration, target);
                if (declaration.getKind() != JsonStructureTypeKind.CHOICE
                        && !target.isAbstractType()
                        && !addIn) {
                    throw new JsonStructureResolutionException(
                            "$extends target must be abstract unless the extending declaration "
                                    + "is an offered add-in: " + base);
                }
            }

            Map<String, JsonStructureTypeUse> inherited = inheritedProperties(
                    declaration, declarations);
            for (String property : declaration.getProperties().keySet()) {
                if (inherited.containsKey(property)) {
                    throw new JsonStructureResolutionException(
                            declaration.getName() + " redefines inherited property " + property);
                }
            }
            Map<String, JsonStructureTypeUse> effective = new LinkedHashMap<>(inherited);
            effective.putAll(declaration.getProperties());
            if ((declaration.getKind() == JsonStructureTypeKind.OBJECT
                    || declaration.getKind() == JsonStructureTypeKind.TUPLE)
                    && effective.isEmpty()) {
                throw new JsonStructureResolutionException(
                        declaration.getName() + " must have at least one effective property");
            }
            declaration.getRequiredAlternatives().forEach(alternative ->
                    alternative.forEach(property -> {
                        if (!effective.containsKey(property)) {
                            throw new JsonStructureResolutionException(
                                    "Required property " + property
                                            + " is not declared by " + declaration.getName());
                        }
                    }));
            if (declaration.getKind() == JsonStructureTypeKind.TUPLE) {
                List<String> order = effectiveTupleOrder(
                        declaration, declarations, new LinkedHashSet<>());
                if (order.size() != effective.size()
                        || !effective.keySet().equals(new LinkedHashSet<>(order))) {
                    throw new JsonStructureResolutionException(
                            "Effective tuple order of " + declaration.getName()
                                    + " must contain every effective property once");
                }
            }
            validateChoice(declarations, declaration);
        });
    }

    private boolean isAddIn(
            JsonStructureTypeDeclaration declaration,
            Map<QualifiedTypeName, Set<String>> addInNames) {
        return addInNames.containsKey(declaration.getName())
                || addInNames.containsKey(declaration.getOrigin());
    }

    private void validateBaseKind(
            JsonStructureTypeDeclaration declaration, JsonStructureTypeDeclaration target) {
        if (target == null) {
            throw new JsonStructureResolutionException(
                    "Unresolved $extends target from " + declaration.getName());
        }
        boolean valid = (declaration.getKind() == JsonStructureTypeKind.OBJECT
                        && target.getKind() == JsonStructureTypeKind.OBJECT)
                || (declaration.getKind() == JsonStructureTypeKind.TUPLE
                        && target.getKind() == JsonStructureTypeKind.TUPLE)
                || (declaration.getKind() == JsonStructureTypeKind.CHOICE
                        && target.getKind() == JsonStructureTypeKind.OBJECT);
        if (!valid) {
            throw new JsonStructureResolutionException(
                    declaration.getName() + " cannot extend " + target.getName()
                            + " because their Core structural kinds are incompatible");
        }
    }

    private void validateChoice(
            Map<QualifiedTypeName, JsonStructureTypeDeclaration> declarations,
            JsonStructureTypeDeclaration declaration) {
        if (declaration.getKind() != JsonStructureTypeKind.CHOICE) {
            return;
        }
        if (declaration.getBases().isEmpty()) {
            if (declaration.getSelector() != null) {
                throw new JsonStructureResolutionException(
                        "Tagged choice must not declare selector: " + declaration.getName());
            }
            return;
        }
        if (declaration.getBases().size() != 1) {
            throw new JsonStructureResolutionException(
                    "Inline choice must extend exactly one abstract object base: "
                            + declaration.getName());
        }
        if (declaration.getSelector() == null) {
            throw new JsonStructureResolutionException(
                    "Inline choice must declare selector: " + declaration.getName());
        }
        validateIdentifier(declaration.getSelector(), "Choice selector");
        QualifiedTypeName base = declaration.getBases().get(0);
        JsonStructureTypeDeclaration baseDeclaration = declarations.get(base);
        if (!baseDeclaration.isAbstractType()) {
            throw new JsonStructureResolutionException(
                    "Inline choice base must be abstract: " + base);
        }
        JsonStructureTypeUse selectorType = findProperty(
                baseDeclaration, declaration.getSelector(), declarations, new LinkedHashSet<>());
        if (selectorType != null && !isStringType(selectorType, declarations, new LinkedHashSet<>())) {
            throw new JsonStructureResolutionException(
                    "Inline choice selector must be a string when it shadows a base property: "
                            + declaration.getSelector());
        }
        for (Map.Entry<String, JsonStructureTypeUse> choice : declaration.getChoices().entrySet()) {
            if (!choice.getValue().isReference()) {
                throw new JsonStructureResolutionException(
                        "Inline choice alternative " + choice.getKey()
                                + " must reference an extending object type");
            }
            JsonStructureTypeDeclaration target = declarations.get(choice.getValue().getReference());
            if (target == null
                    || target.getKind() != JsonStructureTypeKind.OBJECT
                    || target.isAbstractType()
                    || !extendsType(target, base, declarations, new LinkedHashSet<>())) {
                throw new JsonStructureResolutionException(
                        "Inline choice alternative " + choice.getKey() + " does not extend " + base);
            }
            JsonStructureTypeUse targetSelector = findProperty(
                    target, declaration.getSelector(), declarations, new LinkedHashSet<>());
            if (targetSelector != null && !isStringType(targetSelector, declarations, new LinkedHashSet<>())) {
                throw new JsonStructureResolutionException(
                        "Inline choice selector must remain string in " + target.getName());
            }
        }
    }

    private boolean isStringType(
            JsonStructureTypeUse use,
            Map<QualifiedTypeName, JsonStructureTypeDeclaration> declarations,
            Set<QualifiedTypeName> visiting) {
        if (!use.hasReferences()
                && use.getPrimitiveAlternatives().equals(List.of(JsonStructureTypeKind.STRING))) {
            return true;
        }
        if (!use.isReference() || !visiting.add(use.getReference())) {
            return false;
        }
        JsonStructureTypeDeclaration target = declarations.get(use.getReference());
        return target != null
                && target.getDeclaredType() != null
                && isStringType(target.getDeclaredType(), declarations, visiting);
    }

    private JsonStructureTypeUse findProperty(
            JsonStructureTypeDeclaration declaration,
            String propertyName,
            Map<QualifiedTypeName, JsonStructureTypeDeclaration> declarations,
            Set<QualifiedTypeName> visited) {
        if (declaration == null || !visited.add(declaration.getName())) {
            return null;
        }
        JsonStructureTypeUse local = declaration.getProperties().get(propertyName);
        if (local != null) {
            return local;
        }
        for (QualifiedTypeName base : declaration.getBases()) {
            JsonStructureTypeUse inherited = findProperty(
                    declarations.get(base), propertyName, declarations, visited);
            if (inherited != null) {
                return inherited;
            }
        }
        return null;
    }

    private boolean extendsType(
            JsonStructureTypeDeclaration declaration,
            QualifiedTypeName expectedBase,
            Map<QualifiedTypeName, JsonStructureTypeDeclaration> declarations,
            Set<QualifiedTypeName> visited) {
        if (!visited.add(declaration.getName())) {
            return false;
        }
        if (declaration.getBases().contains(expectedBase)) {
            return true;
        }
        for (QualifiedTypeName base : declaration.getBases()) {
            JsonStructureTypeDeclaration baseDeclaration = declarations.get(base);
            if (baseDeclaration != null
                    && extendsType(baseDeclaration, expectedBase, declarations, visited)) {
                return true;
            }
        }
        return false;
    }

    private void validateAddInConflicts(
            Iterable<ResourceDraft> resources,
            Map<QualifiedTypeName, JsonStructureTypeDeclaration> declarations) {
        Set<QualifiedTypeName> addIns = new LinkedHashSet<>();
        for (ResourceDraft resource : resources) {
            resource.resolvedOffers.values().forEach(addIns::addAll);
            addIns.addAll(resource.localUsePointers);
        }

        Map<QualifiedTypeName, Map<String, QualifiedTypeName>> propertiesByTarget =
                new LinkedHashMap<>();
        for (QualifiedTypeName addInName : addIns) {
            JsonStructureTypeDeclaration addIn = declarations.get(addInName);
            if (!addIn.isAbstractType()
                    || (addIn.getKind() != JsonStructureTypeKind.OBJECT
                    && addIn.getKind() != JsonStructureTypeKind.TUPLE)
                    || addIn.getBases().isEmpty()) {
                throw new JsonStructureResolutionException(
                        "Offered/activated add-in must be an abstract object or tuple with $extends: "
                                + addInName);
            }
            Set<QualifiedTypeName> concreteTargets = concreteAddInTargets(
                    addIn, declarations, new LinkedHashSet<>());
            if (concreteTargets.size() != 1) {
                throw new JsonStructureResolutionException(
                        "Add-in " + addInName
                                + " must ultimately replace exactly one non-abstract base type");
            }
            QualifiedTypeName targetName = concreteTargets.iterator().next();
            JsonStructureTypeDeclaration target = declarations.get(targetName);
            Map<String, JsonStructureTypeUse> additions = effectiveProperties(
                    addIn, declarations, new LinkedHashSet<>());
            effectiveProperties(target, declarations, new LinkedHashSet<>())
                    .keySet().forEach(additions::remove);
            Map<String, QualifiedTypeName> seen = propertiesByTarget.computeIfAbsent(
                    targetName, ignored -> new LinkedHashMap<>());
            for (String property : additions.keySet()) {
                QualifiedTypeName previous = seen.putIfAbsent(property, addInName);
                if (previous != null && !previous.equals(addInName)) {
                    throw new JsonStructureResolutionException(
                            "Add-ins " + previous + " and " + addInName
                                    + " conflict on property " + property + " for " + targetName);
                }
            }
        }
    }

    private Set<QualifiedTypeName> concreteAddInTargets(
            JsonStructureTypeDeclaration declaration,
            Map<QualifiedTypeName, JsonStructureTypeDeclaration> declarations,
            Set<QualifiedTypeName> visiting) {
        if (!visiting.add(declaration.getName())) {
            throw new JsonStructureResolutionException(
                    "Cyclic add-in replacement chain at " + declaration.getName());
        }
        Set<QualifiedTypeName> targets = new LinkedHashSet<>();
        for (QualifiedTypeName base : declaration.getBases()) {
            JsonStructureTypeDeclaration target = declarations.get(base);
            if (target.isAbstractType()) {
                targets.addAll(concreteAddInTargets(target, declarations, visiting));
            } else {
                targets.add(base);
            }
        }
        visiting.remove(declaration.getName());
        return targets;
    }

    private Map<String, JsonStructureTypeUse> inheritedProperties(
            JsonStructureTypeDeclaration declaration,
            Map<QualifiedTypeName, JsonStructureTypeDeclaration> declarations) {
        Map<String, JsonStructureTypeUse> inherited = new LinkedHashMap<>();
        for (QualifiedTypeName base : declaration.getBases()) {
            effectiveProperties(declarations.get(base), declarations, new LinkedHashSet<>())
                    .forEach(inherited::putIfAbsent);
        }
        return inherited;
    }

    private Map<String, JsonStructureTypeUse> effectiveProperties(
            JsonStructureTypeDeclaration declaration,
            Map<QualifiedTypeName, JsonStructureTypeDeclaration> declarations,
            Set<QualifiedTypeName> visiting) {
        if (!visiting.add(declaration.getName())) {
            throw new JsonStructureResolutionException(
                    "Cyclic inheritance at " + declaration.getName());
        }
        Map<String, JsonStructureTypeUse> effective = new LinkedHashMap<>();
        for (QualifiedTypeName base : declaration.getBases()) {
            effectiveProperties(declarations.get(base), declarations, visiting)
                    .forEach(effective::putIfAbsent);
        }
        effective.putAll(declaration.getProperties());
        visiting.remove(declaration.getName());
        return effective;
    }

    private List<String> effectiveTupleOrder(
            JsonStructureTypeDeclaration declaration,
            Map<QualifiedTypeName, JsonStructureTypeDeclaration> declarations,
            Set<QualifiedTypeName> visiting) {
        if (!visiting.add(declaration.getName())) {
            throw new JsonStructureResolutionException(
                    "Cyclic tuple inheritance at " + declaration.getName());
        }
        Set<String> order = new LinkedHashSet<>();
        for (QualifiedTypeName base : declaration.getBases()) {
            order.addAll(effectiveTupleOrder(declarations.get(base), declarations, visiting));
        }
        order.addAll(declaration.getTupleOrder());
        visiting.remove(declaration.getName());
        return List.copyOf(order);
    }

    private void visitInheritance(
            QualifiedTypeName name,
            Map<QualifiedTypeName, JsonStructureTypeDeclaration> declarations,
            Set<QualifiedTypeName> visiting,
            Set<QualifiedTypeName> visited) {
        if (visited.contains(name)) {
            return;
        }
        JsonStructureTypeDeclaration declaration = declarations.get(name);
        if (declaration == null) {
            throw new JsonStructureResolutionException("Unresolved $extends target " + name);
        }
        if (!visiting.add(name)) {
            throw new JsonStructureResolutionException("Cyclic $extends chain at " + name);
        }
        for (QualifiedTypeName base : declaration.getBases()) {
            visitInheritance(base, declarations, visiting, visited);
        }
        visiting.remove(name);
        visited.add(name);
    }

    private JsonStructureTypeKind declarationKind(JsonNode node, String location) {
        JsonNode type = node.get("type");
        if (type == null) {
            throw new JsonStructureResolutionException(
                    "Named declaration must have type at " + location);
        }
        if (type.isTextual()) {
            return typeKind(type.textValue(), location);
        }
        if (type.isArray()) {
            return JsonStructureTypeKind.UNION;
        }
        if (type.isObject() && type.has("$ref")) {
            return JsonStructureTypeKind.ALIAS;
        }
        throw new JsonStructureResolutionException(
                "Named declaration must have a concrete, union, or reference type at " + location);
    }

    private JsonStructureTypeKind typeKind(String name, String location) {
        try {
            return JsonStructureTypeKind.fromName(name);
        } catch (IllegalArgumentException ex) {
            throw new JsonStructureResolutionException(
                    "Unknown JSON Structure type " + name + " at " + location);
        }
    }

    private boolean isStructuralCompound(JsonStructureTypeKind kind) {
        return kind == JsonStructureTypeKind.OBJECT
                || kind == JsonStructureTypeKind.ARRAY
                || kind == JsonStructureTypeKind.SET
                || kind == JsonStructureTypeKind.MAP
                || kind == JsonStructureTypeKind.TUPLE
                || kind == JsonStructureTypeKind.CHOICE;
    }

    private boolean isPrimitive(JsonStructureTypeKind kind) {
        return !isStructuralCompound(kind)
                && kind != JsonStructureTypeKind.ANY
                && kind != JsonStructureTypeKind.ALIAS
                && kind != JsonStructureTypeKind.UNION;
    }

    private List<List<String>> requiredAlternatives(JsonNode required, String location) {
        if (required == null) {
            return List.of();
        }
        if (!required.isArray()) {
            throw new JsonStructureResolutionException(
                    "required must be an array at " + location);
        }
        if (required.isEmpty()) {
            return List.of();
        }
        boolean nested = required.get(0).isArray();
        List<List<String>> alternatives = new ArrayList<>();
        if (nested) {
            Set<Set<String>> uniqueAlternatives = new LinkedHashSet<>();
            for (JsonNode value : required) {
                if (!value.isArray()) {
                    throw new JsonStructureResolutionException(
                            "required must not mix property names and alternative arrays at " + location);
                }
                List<String> alternative = stringList(value, "required at " + location);
                Set<String> identity = new LinkedHashSet<>(alternative);
                if (identity.size() != alternative.size()) {
                    throw new JsonStructureResolutionException(
                            "required alternatives must contain unique property names at " + location);
                }
                if (!uniqueAlternatives.add(identity)) {
                    throw new JsonStructureResolutionException(
                            "required alternatives must be distinct at " + location);
                }
                alternatives.add(alternative);
            }
            return List.copyOf(alternatives);
        }
        for (JsonNode value : required) {
            if (value.isArray()) {
                throw new JsonStructureResolutionException(
                        "required must not mix property names and alternative arrays at " + location);
            }
        }
        return List.of(stringList(required, "required at " + location));
    }

    private List<QualifiedTypeName> referenceList(
            URI resourceId, JsonNode node, String location) {
        if (node == null) {
            return List.of();
        }
        List<QualifiedTypeName> result = new ArrayList<>();
        if (node.isTextual()) {
            result.add(pointerToName(resourceId, node.textValue()));
        } else if (node.isArray()) {
            if (node.isEmpty()) {
                throw new JsonStructureResolutionException(
                        "$extends array must not be empty at " + location);
            }
            for (JsonNode value : node) {
                if (!value.isTextual()) {
                    throw new JsonStructureResolutionException(
                            "$extends entries must be JSON Pointer strings at " + location);
                }
                result.add(pointerToName(resourceId, value.textValue()));
            }
        } else {
            throw new JsonStructureResolutionException(
                    "$extends must be a JSON Pointer string or array at " + location);
        }
        if (new LinkedHashSet<>(result).size() != result.size()) {
            throw new JsonStructureResolutionException(
                    "$extends targets must be unique at " + location);
        }
        return List.copyOf(result);
    }

    private QualifiedTypeName pointerToName(URI resourceId, String pointer) {
        if (pointer == null || !pointer.startsWith("#")) {
            throw new JsonStructureResolutionException(
                    "JSON Structure references must be local JSON Pointer fragments: " + pointer);
        }
        final String fragment;
        try {
            URI pointerUri = new URI(pointer);
            if (pointerUri.isAbsolute()
                    || pointerUri.getRawFragment() == null
                    || pointerUri.getRawQuery() != null) {
                throw new URISyntaxException(pointer, "not a local fragment");
            }
            fragment = URLDecoder.decode(
                    pointerUri.getRawFragment().replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException | URISyntaxException ex) {
            throw new JsonStructureResolutionException(
                    "Invalid JSON Pointer fragment: " + pointer);
        }
        String prefix = "/definitions/";
        if (!fragment.startsWith(prefix)) {
            throw new JsonStructureResolutionException(
                    "JSON Structure references must target #/definitions/...: " + pointer);
        }
        String remainder = fragment.substring(prefix.length());
        String[] segments = remainder.split("/", -1);
        if (segments.length == 0) {
            throw new JsonStructureResolutionException(
                    "Invalid JSON Structure reference: " + pointer);
        }
        List<String> decoded = new ArrayList<>();
        for (String segment : segments) {
            String value = unescapePointerToken(segment, pointer);
            if (value.isEmpty()) {
                throw new JsonStructureResolutionException(
                        "JSON Structure reference contains an empty name: " + pointer);
            }
            validateIdentifier(value, "Referenced type or namespace");
            decoded.add(value);
        }
        return new QualifiedTypeName(
                resourceId,
                decoded.subList(0, decoded.size() - 1),
                decoded.get(decoded.size() - 1));
    }

    private String unescapePointerToken(String token, String pointer) {
        StringBuilder decoded = new StringBuilder();
        for (int index = 0; index < token.length(); index++) {
            char character = token.charAt(index);
            if (character != '~') {
                decoded.append(character);
                continue;
            }
            if (++index >= token.length()) {
                throw new JsonStructureResolutionException(
                        "Malformed JSON Pointer escape in " + pointer);
            }
            char escape = token.charAt(index);
            if (escape == '0') {
                decoded.append('~');
            } else if (escape == '1') {
                decoded.append('/');
            } else {
                throw new JsonStructureResolutionException(
                        "Malformed JSON Pointer escape in " + pointer);
            }
        }
        return decoded.toString();
    }

    private URI parseAbsoluteUri(String value, String location) {
        try {
            URI uri = new URI(value);
            if (!uri.isAbsolute()) {
                throw new URISyntaxException(value, "URI is not absolute");
            }
            return uri;
        } catch (URISyntaxException ex) {
            throw new JsonStructureResolutionException(
                    "JSON Structure URI at " + location + " must be absolute: " + value);
        }
    }

    private String withoutFragment(URI uri) {
        String value = uri.toString();
        int fragment = value.indexOf('#');
        return fragment < 0 ? value : value.substring(0, fragment);
    }

    private URI descriptionBaseUri(
            JsonNode document, URI retrievalUri, JsonStructureResolutionOptions options) {
        URI fallback = options.getEncapsulatingEntityBaseUri();
        if (fallback == null) {
            fallback = retrievalUri;
        }
        if (fallback == null) {
            fallback = options.getApplicationDefaultBaseUri();
        }
        if (fallback != null && !fallback.isAbsolute()) {
            throw new JsonStructureResolutionException(
                    "OpenAPI base URI must be absolute: " + fallback);
        }
        if (document != null && document.has("$self")) {
            JsonNode self = document.get("$self");
            if (!self.isTextual()) {
                throw new JsonStructureResolutionException("OpenAPI $self must be a URI string");
            }
            try {
                URI selfUri = new URI(self.textValue());
                if (selfUri.isAbsolute()) {
                    return selfUri;
                }
                if (fallback == null) {
                    throw new JsonStructureResolutionException(
                            "Relative OpenAPI $self requires an absolute encapsulating, retrieval, "
                                    + "or application-default base URI");
                }
                return fallback.resolve(selfUri);
            } catch (URISyntaxException ex) {
                throw new JsonStructureResolutionException(
                        "OpenAPI $self must be a valid URI reference: " + self.textValue());
            }
        }
        return fallback;
    }

    private URI defaultId(URI baseUri, String componentName) {
        String base = withoutFragment(baseUri);
        String pointerName = componentName.replace("~", "~0").replace("/", "~1");
        return URI.create(base + "#/components/schemas/" + pointerName);
    }

    private String optionalText(JsonNode node, String property, String location) {
        if (node == null || !node.has(property)) {
            return null;
        }
        JsonNode value = node.get(property);
        if (!value.isTextual()) {
            throw new JsonStructureResolutionException(
                    property + " must be a string at " + location);
        }
        return value.textValue();
    }

    private String requiredText(JsonNode node, String property, String location) {
        String value = optionalText(node, property, location);
        if (value == null) {
            throw new JsonStructureResolutionException(
                    property + " is required and must be a string at " + location);
        }
        return value;
    }

    private boolean requiredBoolean(JsonNode node, String property, String location) {
        JsonNode value = node.get(property);
        if (value == null || !value.isBoolean()) {
            throw new JsonStructureResolutionException(
                    property + " must be a boolean at " + location);
        }
        return value.booleanValue();
    }

    private Integer integer(JsonNode node, String location) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
            throw new JsonStructureResolutionException(
                    "Expected a 32-bit integer at " + location);
        }
        return node.intValue();
    }

    private List<String> stringList(JsonNode node, String location) {
        if (node == null || !node.isArray()) {
            throw new JsonStructureResolutionException(
                    "Expected an array of strings at " + location);
        }
        List<String> result = new ArrayList<>();
        for (JsonNode value : node) {
            if (!value.isTextual()) {
                throw new JsonStructureResolutionException(
                        "Expected an array of strings at " + location);
            }
            result.add(value.textValue());
        }
        return List.copyOf(result);
    }

    private List<Object> examples(JsonNode node, String location) {
        if (node == null || !node.has("examples")) {
            return List.of();
        }
        JsonNode values = node.get("examples");
        if (!values.isArray()) {
            throw new JsonStructureResolutionException(
                    "examples must be an array at " + location);
        }
        List<Object> result = new ArrayList<>();
        values.forEach(value -> result.add(plainValue(value)));
        return Collections.unmodifiableList(result);
    }

    private Object plainValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isArray()) {
            List<Object> values = new ArrayList<>();
            node.forEach(value -> values.add(plainValue(value)));
            return values;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry ->
                values.put(entry.getKey(), plainValue(entry.getValue())));
        return values;
    }

    private List<String> append(List<String> values, String value) {
        List<String> result = new ArrayList<>(values);
        result.add(value);
        return List.copyOf(result);
    }

    private String qualified(List<String> namespace, String name) {
        return namespace.isEmpty() ? name : String.join(".", namespace) + "." + name;
    }

    private void validateIdentifier(String value, String kind) {
        if (!IDENTIFIER.matcher(value).matches()) {
            throw new JsonStructureResolutionException(
                    kind + " name must match [A-Za-z_][A-Za-z0-9_]*: " + value);
        }
    }

    private String dialectUri(SchemaDialect dialect) {
        switch (dialect) {
            case JSON_STRUCTURE_CORE:
                return SchemaDialectDetector.JSON_STRUCTURE_CORE;
            case JSON_STRUCTURE_EXTENDED:
                return SchemaDialectDetector.JSON_STRUCTURE_EXTENDED;
            case JSON_STRUCTURE_VALIDATION:
                return SchemaDialectDetector.JSON_STRUCTURE_VALIDATION;
            default:
                throw new IllegalArgumentException("Not a JSON Structure dialect: " + dialect);
        }
    }

    private JsonStructureResource toResourceModel(ResourceDraft resource) {
        return new JsonStructureResource(
                resource.componentName,
                resource.id,
                resource.explicitId,
                resource.dialect,
                resource.metaSchemaUri,
                resource.documentName,
                resource.description,
                resource.examples,
                resource.root,
                resource.resolvedOffers,
                resource.declaredUses,
                resource.effectiveUses,
                resource.localUsePointers);
    }

    private static final class ResourceDraft {
        private final String componentName;
        private final URI id;
        private final SchemaDialect dialect;
        private final String metaSchemaUri;
        private final Set<String> metaSchemaOffers;
        private final Map<QualifiedTypeName, JsonStructureTypeDeclaration> localDeclarations =
                new LinkedHashMap<>();
        private final List<ImportRequest> imports = new ArrayList<>();
        private final Map<String, List<QualifiedTypeName>> rawOffers = new LinkedHashMap<>();
        private final List<String> declaredUses = new ArrayList<>();
        private final List<QualifiedTypeName> localUsePointers = new ArrayList<>();
        private QualifiedTypeName root;
        private boolean explicitId;
        private String documentName;
        private String description;
        private List<Object> examples = List.of();
        private List<String> effectiveUses = List.of();
        private Map<String, List<QualifiedTypeName>> resolvedOffers = Map.of();
        private Map<QualifiedTypeName, JsonStructureTypeDeclaration> resolvedDeclarations = Map.of();
        private boolean resolved;

        private ResourceDraft(
                String componentName,
                URI id,
                SchemaDialect dialect,
                String metaSchemaUri,
                Set<String> metaSchemaOffers) {
            this.componentName = componentName;
            this.id = id;
            this.dialect = dialect;
            this.metaSchemaUri = metaSchemaUri;
            this.metaSchemaOffers =
                    Collections.unmodifiableSet(new LinkedHashSet<>(metaSchemaOffers));
        }
    }

    private static final class ImportRequest {
        private final List<String> namespace;
        private final String resourceId;
        private final boolean includeRoot;

        private ImportRequest(List<String> namespace, String resourceId, boolean includeRoot) {
            this.namespace = List.copyOf(namespace);
            this.resourceId = resourceId;
            this.includeRoot = includeRoot;
        }
    }
}
