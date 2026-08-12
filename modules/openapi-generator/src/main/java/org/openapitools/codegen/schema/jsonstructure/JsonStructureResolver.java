/*
 * Copyright 2026 OpenAPI-Generator Contributors (https://openapi-generator.tech)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.openapitools.codegen.schema.jsonstructure;

import com.fasterxml.jackson.databind.JsonNode;
import org.openapitools.codegen.schema.SchemaDialect;
import org.openapitools.codegen.schema.SchemaDialectDetector;

import java.net.URI;
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

public final class JsonStructureResolver {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern MEDIA_TYPE = Pattern.compile(
            "[!#$%&'*+.^_`|~0-9A-Za-z-]+/[!#$%&'*+.^_`|~0-9A-Za-z-]+");

    public JsonStructureTypeGraph resolve(JsonNode openApiDocument) {
        return resolve(openApiDocument, null);
    }

    public JsonStructureTypeGraph resolve(JsonNode openApiDocument, URI retrievalUri) {
        Map<String, SchemaDialect> dialects = SchemaDialectDetector.componentSchemaDialects(openApiDocument);
        JsonNode schemas = openApiDocument.path("components").path("schemas");
        URI baseUri = descriptionBaseUri(openApiDocument, retrievalUri);
        Map<String, ResourceDraft> resources = new LinkedHashMap<>();
        Map<String, ResourceDraft> resourcesById = new LinkedHashMap<>();
        Map<String, ResourceDraft> importableResourcesById = new LinkedHashMap<>();

        dialects.forEach((componentName, dialect) -> {
            if (!dialect.isJsonStructure()) {
                return;
            }
            JsonNode resourceNode = schemas.get(componentName);
            ResourceDraft resource = parseResource(componentName, resourceNode, baseUri, dialect);
            ResourceDraft previous = resourcesById.putIfAbsent(resource.id.toString(), resource);
            if (previous != null) {
                throw new JsonStructureResolutionException(
                        "Duplicate JSON Structure $id " + resource.id
                                + " in components " + previous.componentName + " and " + componentName);
            }
            if (resource.explicitId) {
                String importId = resource.id.toString();
                ResourceDraft previousImportTarget = importableResourcesById.putIfAbsent(importId, resource);
                if (previousImportTarget != null) {
                    throw new JsonStructureResolutionException(
                            "Duplicate JSON Structure import identity " + importId
                                    + " in components " + previousImportTarget.componentName
                                    + " and " + componentName);
                }
            }
            resources.put(componentName, resource);
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

        validateReferences(declarations);
        validateRoots(resources.values(), declarations);
        validateInheritance(declarations);

        Map<URI, String> componentById = new LinkedHashMap<>();
        resources.values().forEach(resource -> componentById.put(resource.id, resource.componentName));
        return new JsonStructureTypeGraph(declarations, roots, componentById);
    }

    private ResourceDraft parseResource(
            String componentName,
            JsonNode node,
            URI baseUri,
            SchemaDialect dialect) {
        if (node == null || !node.isObject()) {
            throw new JsonStructureResolutionException(
                    "JSON Structure component " + componentName + " must be an object");
        }
        String idValue = text(node, "$id");
        if (idValue == null && baseUri == null) {
            throw new JsonStructureResolutionException(
                    "JSON Structure component " + componentName
                            + " must declare $id when the OpenAPI Description has no base URI");
        }
        URI id = idValue == null
                ? defaultId(baseUri, componentName)
                : parseAbsoluteUri(idValue, componentName);
        ResourceDraft resource = new ResourceDraft(componentName, id, dialect);
        resource.explicitId = idValue != null;

        addImport(resource, List.of(), node);

        JsonNode definitions = node.get("definitions");
        if (definitions != null) {
            if (!definitions.isObject()) {
                throw new JsonStructureResolutionException(
                        "definitions in " + componentName + " must be an object");
            }
            parseDefinitions(resource, definitions, List.of());
        }

        String rootPointer = text(node, "$root");
        if (rootPointer != null && node.has("type")) {
            throw new JsonStructureResolutionException(
                    "JSON Structure component " + componentName + " cannot declare both type and $root");
        } else if (rootPointer != null) {
            resource.root = pointerToName(id, rootPointer);
            resource.rootIsDefinition = true;
        } else if (node.has("type")) {
            String rootName = text(node, "name");
            if (rootName == null) {
                throw new JsonStructureResolutionException(
                        "Root type in JSON Structure component " + componentName + " must declare name");
            }
            resource.root = parseDeclaration(resource, List.of(), rootName, node, true, false);
        }

        return resource;
    }

    private void parseDefinitions(ResourceDraft resource, JsonNode definitions, List<String> namespace) {
        addImport(resource, namespace, definitions);
        definitions.fields().forEachRemaining(entry -> {
            String name = entry.getKey();
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
            if (isDeclaration(value)) {
                parseDeclaration(resource, namespace, name, value, false, false);
            } else {
                List<String> childNamespace = append(namespace, name);
                parseDefinitions(resource, value, childNamespace);
            }
        });
    }

    private void addImport(ResourceDraft resource, List<String> namespace, JsonNode node) {
        String importUri = text(node, "$import");
        String importDefinitionsUri = text(node, "$importdefs");
        if (importUri != null && importDefinitionsUri != null) {
            throw new JsonStructureResolutionException(
                    "Namespace " + qualified(namespace, resource.componentName)
                            + " cannot declare both $import and $importdefs");
        }
        if (importUri != null || importDefinitionsUri != null) {
            if (resource.dialect == SchemaDialect.JSON_STRUCTURE_CORE) {
                throw new JsonStructureResolutionException(
                        "$import and $importdefs require the JSON Structure extended or validation dialect");
            }
            resource.imports.add(new ImportRequest(
                    namespace,
                    withoutFragment(parseAbsoluteUri(
                            importUri != null ? importUri : importDefinitionsUri,
                            resource.componentName)),
                    importUri != null));
        }
    }

    private QualifiedTypeName parseDeclaration(
            ResourceDraft resource,
            List<String> namespace,
            String name,
            JsonNode node,
            boolean rootDeclaration,
            boolean synthetic) {
        validateIdentifier(name, "Type");
        QualifiedTypeName qualifiedName = new QualifiedTypeName(resource.id, namespace, name);
        if (resource.localDeclarations.containsKey(qualifiedName)) {
            throw new JsonStructureResolutionException("Duplicate declaration " + qualifiedName);
        }

        JsonStructureTypeKind kind = declarationKind(node);
        JsonStructureTypeUse declaredType = !isCompound(kind)
                ? parseTypeUse(resource, namespace, name, node)
                : null;
        validateDeclarationName(qualifiedName, name, node, kind);
        Map<String, JsonStructureTypeUse> properties = new LinkedHashMap<>();
        JsonNode propertyNodes = node.get("properties");
        if (propertyNodes != null) {
            if (!propertyNodes.isObject()) {
                throw new JsonStructureResolutionException("properties of " + qualifiedName + " must be an object");
            }
            propertyNodes.fields().forEachRemaining(property -> {
                validateIdentifier(property.getKey(), "Property");
                properties.put(
                property.getKey(),
                parseTypeUse(resource, append(namespace, name), property.getKey(), property.getValue()));
            });
        }

        JsonStructureTypeUse items = node.has("items")
                ? parseTypeUse(resource, append(namespace, name), "Items", node.get("items"))
                : null;
        JsonStructureTypeUse values = node.has("values")
                ? parseTypeUse(resource, append(namespace, name), "Values", node.get("values"))
                : null;
        Boolean additionalPropertiesAllowed = null;
        JsonStructureTypeUse additionalPropertiesType = null;
        JsonNode additionalProperties = node.get("additionalProperties");
        if (additionalProperties != null) {
            if (additionalProperties.isBoolean()) {
                additionalPropertiesAllowed = additionalProperties.booleanValue();
            } else if (additionalProperties.isObject()) {
                additionalPropertiesType = parseTypeUse(
                        resource,
                        append(namespace, name),
                        "AdditionalProperty",
                        additionalProperties);
            } else {
                throw new JsonStructureResolutionException(
                        "additionalProperties of " + qualifiedName + " must be a boolean or schema");
            }
        }

        Map<String, JsonStructureTypeUse> choices = new LinkedHashMap<>();
        JsonNode choiceNodes = node.get("choices");
        if (choiceNodes != null) {
            if (!choiceNodes.isObject()) {
                throw new JsonStructureResolutionException("choices of " + qualifiedName + " must be an object");
            }
            choiceNodes.fields().forEachRemaining(choice -> {
                validateIdentifier(choice.getKey(), "Choice");
                choices.put(
                        choice.getKey(),
                        parseTypeUse(resource, append(namespace, name), choice.getKey(), choice.getValue()));
            });
        }

        List<List<String>> required = requiredAlternatives(node.get("required"));
        List<String> tupleOrder = stringList(node.get("tuple"));
        validateStructure(
                qualifiedName,
                kind,
                node,
                properties,
                required,
                choices,
                items,
                values,
                tupleOrder,
                additionalPropertiesAllowed,
                additionalPropertiesType);
        JsonStructureTypeDeclaration declaration = new JsonStructureTypeDeclaration(
                qualifiedName,
                qualifiedName,
                kind,
                kind.wireKind(),
                node.path("abstract").asBoolean(false),
                synthetic,
                properties,
                required,
                referenceList(resource.id, node.get("$extends")),
                choices,
                text(node, "selector"),
                items,
                values,
                tupleOrder,
                integer(node.get("precision")),
                integer(node.get("scale")),
                integer(node.get("maxLength")),
                declaredType,
                additionalPropertiesAllowed,
                additionalPropertiesType);
        resource.localDeclarations.put(qualifiedName, declaration);
        return qualifiedName;
    }

    private JsonStructureTypeUse parseTypeUse(
            ResourceDraft resource,
            List<String> namespace,
            String syntheticName,
            JsonNode schemaNode) {
        if (schemaNode == null || !schemaNode.isObject() || !schemaNode.has("type")) {
            throw new JsonStructureResolutionException(
                    "Schema " + qualified(namespace, syntheticName) + " must declare type");
        }
        if (schemaNode.has("$ref")) {
            throw new JsonStructureResolutionException(
                    "$ref must occur only inside type at " + qualified(namespace, syntheticName));
        }
        JsonNode typeNode = schemaNode.get("type");
        if (typeNode == null) {
            throw new JsonStructureResolutionException(
                    "Schema " + qualified(namespace, syntheticName) + " must declare type");
        }
        if (typeNode.isTextual()) {
            JsonStructureTypeKind kind = JsonStructureTypeKind.fromName(typeNode.textValue());
            if (isCompound(kind) && schemaNode != null && schemaNode.isObject()) {
                String nestedName = text(schemaNode, "name");
                if ((kind == JsonStructureTypeKind.OBJECT
                        || kind == JsonStructureTypeKind.TUPLE
                        || kind == JsonStructureTypeKind.CHOICE)
                        && nestedName == null) {
                    throw new JsonStructureResolutionException(
                            kind.name().toLowerCase()
                                    + " declaration " + qualified(namespace, syntheticName)
                                    + " must declare name");
                }
                QualifiedTypeName nested = parseDeclaration(
                        resource,
                        namespace,
                        nestedName == null ? syntheticName : nestedName,
                        schemaNode,
                        false,
                        true);
                return JsonStructureTypeUse.reference(nested);
            }
            JsonStructureTypeUse use = JsonStructureTypeUse.primitives(
                    List.of(kind),
                    schemaNode != null && schemaNode.isObject() ? integer(schemaNode.get("precision")) : null,
                    schemaNode != null && schemaNode.isObject() ? integer(schemaNode.get("scale")) : null,
                    schemaNode != null && schemaNode.isObject() ? integer(schemaNode.get("maxLength")) : null);
            validateTypeUseConstraints(use, qualified(namespace, syntheticName));
            return applyTypeAnnotations(use, schemaNode, qualified(namespace, syntheticName));
        }
        if (typeNode.isArray()) {
            List<JsonStructureTypeKind> alternatives = new ArrayList<>();
            List<QualifiedTypeName> references = new ArrayList<>();
            typeNode.forEach(value -> {
                if (value.isTextual()) {
                    JsonStructureTypeKind kind = JsonStructureTypeKind.fromName(value.textValue());
                    if (isCompound(kind)) {
                        throw new JsonStructureResolutionException(
                                "Compound type unions require named references: "
                                        + qualified(namespace, syntheticName));
                    }
                    alternatives.add(kind);
                } else if (value.isObject()
                        && value.size() == 1
                        && value.has("$ref")
                        && value.get("$ref").isTextual()) {
                    references.add(pointerToName(resource.id, text(value, "$ref")));
                } else {
                    throw new JsonStructureResolutionException(
                            "Type union " + qualified(namespace, syntheticName)
                                    + " may contain only primitive names or type references");
                }
            });
            if (alternatives.isEmpty() && references.isEmpty()) {
                throw new JsonStructureResolutionException("Type union cannot be empty");
            }
            JsonStructureTypeUse use = JsonStructureTypeUse.union(
                    alternatives,
                    references,
                    schemaNode != null && schemaNode.isObject() ? integer(schemaNode.get("precision")) : null,
                    schemaNode != null && schemaNode.isObject() ? integer(schemaNode.get("scale")) : null,
                    schemaNode != null && schemaNode.isObject() ? integer(schemaNode.get("maxLength")) : null);
            validateTypeUseConstraints(use, qualified(namespace, syntheticName));
            return applyTypeAnnotations(use, schemaNode, qualified(namespace, syntheticName));
        }
        if (typeNode.isObject() && typeNode.has("$ref")) {
            if (typeNode.size() != 1 || !typeNode.get("$ref").isTextual()) {
                throw new JsonStructureResolutionException(
                        "A JSON Structure type reference must contain only a textual $ref at "
                                + qualified(namespace, syntheticName));
            }
            return applyTypeAnnotations(
                    JsonStructureTypeUse.reference(pointerToName(resource.id, text(typeNode, "$ref"))),
                    schemaNode,
                    qualified(namespace, syntheticName));
        }
        throw new JsonStructureResolutionException(
                "Unsupported type expression at " + qualified(namespace, syntheticName));
    }

    private void resolveResource(
            ResourceDraft resource,
            Map<String, ResourceDraft> resourcesById,
            Deque<URI> stack) {
        if (resource.resolved) {
            return;
        }
        if (stack.contains(resource.id)) {
            throw new JsonStructureResolutionException("Cyclic JSON Structure import: " + stack + " -> " + resource.id);
        }
        stack.addLast(resource.id);

        Map<QualifiedTypeName, JsonStructureTypeDeclaration> resolved = new LinkedHashMap<>();
        for (ImportRequest request : resource.imports) {
            ResourceDraft source = resourcesById.get(request.resourceId);
            if (source == null) {
                throw new JsonStructureResolutionException(
                        "External JSON Structure import is not enabled: " + request.resourceId);
            }
            resolveResource(source, resourcesById, stack);
            for (JsonStructureTypeDeclaration sourceDeclaration : source.resolvedDeclarations.values()) {
                if (!request.includeRoot
                        && !source.rootIsDefinition
                        && sourceDeclaration.getName().equals(source.root)) {
                    continue;
                }
                JsonStructureTypeDeclaration imported =
                        rebindImportedDeclaration(resource.id, request.namespace, sourceDeclaration);
                JsonStructureTypeDeclaration existing = resolved.putIfAbsent(imported.getName(), imported);
                if (existing != null && !existing.getOrigin().equals(imported.getOrigin())) {
                    throw new JsonStructureResolutionException(
                            "Conflicting imported declarations for " + imported.getName()
                                    + " from " + existing.getOrigin() + " and " + imported.getOrigin());
                }
            }
        }
        resolved.putAll(resource.localDeclarations);
        resource.resolvedDeclarations = resolved;
        resource.resolved = true;
        stack.removeLast();
    }

    private JsonStructureTypeDeclaration rebindImportedDeclaration(
            URI destinationResource,
            List<String> importNamespace,
            JsonStructureTypeDeclaration source) {
        QualifiedTypeName reboundName = rebind(destinationResource, importNamespace, source.getName());
        return new JsonStructureTypeDeclaration(
                reboundName,
                source.getOrigin(),
                source.getKind(),
                source.getWireKind(),
                source.isAbstractType(),
                source.isSynthetic(),
                remapUses(destinationResource, importNamespace, source.getProperties()),
                source.getRequiredAlternatives(),
                remapNames(destinationResource, importNamespace, source.getBases()),
                remapUses(destinationResource, importNamespace, source.getChoices()),
                source.getSelector(),
                remapUse(destinationResource, importNamespace, source.getItems()),
                remapUse(destinationResource, importNamespace, source.getValues()),
                source.getTupleOrder(),
                source.getPrecision(),
                source.getScale(),
                source.getMaxLength(),
                remapUse(destinationResource, importNamespace, source.getDeclaredType()),
                source.getAdditionalPropertiesAllowed(),
                remapUse(destinationResource, importNamespace, source.getAdditionalPropertiesType()));
    }

    private Map<String, JsonStructureTypeUse> remapUses(
            URI resourceId,
            List<String> namespace,
            Map<String, JsonStructureTypeUse> uses) {
        Map<String, JsonStructureTypeUse> result = new LinkedHashMap<>();
        uses.forEach((name, use) -> result.put(name, remapUse(resourceId, namespace, use)));
        return result;
    }

    private JsonStructureTypeUse remapUse(
            URI resourceId,
            List<String> namespace,
            JsonStructureTypeUse use) {
        if (use == null || !use.hasReferences()) {
            return use;
        }
        List<QualifiedTypeName> references = new ArrayList<>();
        use.getReferenceAlternatives().forEach(reference ->
                references.add(rebind(resourceId, namespace, reference)));
        return JsonStructureTypeUse.union(
                use.getPrimitiveAlternatives(),
                references,
                use.getPrecision(),
                use.getScale(),
                use.getMaxLength())
                .withValueConstraints(use.getEnumValues(), use.hasConst(), use.getConstValue())
                .withBinaryAnnotations(
                        use.getContentEncoding(),
                        use.getContentCompression(),
                        use.getContentMediaType());
    }

    private List<QualifiedTypeName> remapNames(
            URI resourceId,
            List<String> namespace,
            List<QualifiedTypeName> names) {
        List<QualifiedTypeName> result = new ArrayList<>();
        names.forEach(name -> result.add(rebind(resourceId, namespace, name)));
        return result;
    }

    private QualifiedTypeName rebind(
            URI destinationResource,
            List<String> importNamespace,
            QualifiedTypeName source) {
        List<String> reboundNamespace = new ArrayList<>(importNamespace);
        reboundNamespace.addAll(source.getNamespace());
        return new QualifiedTypeName(destinationResource, reboundNamespace, source.getLocalName());
    }

    private void validateReferences(Map<QualifiedTypeName, JsonStructureTypeDeclaration> declarations) {
        declarations.values().forEach(declaration -> {
            declaration.getProperties().values().forEach(use -> validateUse(declarations, declaration, use));
            declaration.getChoices().values().forEach(use -> validateUse(declarations, declaration, use));
            validateUse(declarations, declaration, declaration.getItems());
            validateUse(declarations, declaration, declaration.getValues());
            validateUse(declarations, declaration, declaration.getDeclaredType());
            validateUse(declarations, declaration, declaration.getAdditionalPropertiesType());
            declaration.getBases().forEach(base -> {
                JsonStructureTypeDeclaration target = declarations.get(base);
                if (target == null) {
                    throw new JsonStructureResolutionException(
                            "Unresolved $extends " + base + " from " + declaration.getName());
                }
                if (!target.isAbstractType()) {
                    throw new JsonStructureResolutionException(
                            "$extends target must be abstract: " + base);
                }
            });
            validateChoice(declarations, declaration);
        });
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
                    "Inline choice must extend exactly one abstract base: " + declaration.getName());
        }
        if (declaration.getSelector() == null) {
            throw new JsonStructureResolutionException(
                    "Inline choice must declare selector: " + declaration.getName());
        }
        validateIdentifier(declaration.getSelector(), "Choice selector");
        QualifiedTypeName base = declaration.getBases().get(0);
        JsonStructureTypeUse selectorType = findProperty(
                declarations.get(base),
                declaration.getSelector(),
                declarations,
                new LinkedHashSet<>());
        if (selectorType != null
                && (selectorType.hasReferences()
                || selectorType.getPrimitiveAlternatives().size() != 1
                || selectorType.getPrimitiveAlternatives().get(0) != JsonStructureTypeKind.STRING)) {
            throw new JsonStructureResolutionException(
                    "Inline choice selector must be a string property when declared by its base: "
                            + declaration.getSelector());
        }
        for (Map.Entry<String, JsonStructureTypeUse> choice : declaration.getChoices().entrySet()) {
            if (!choice.getValue().isReference()) {
                throw new JsonStructureResolutionException(
                        "Inline choice alternative " + choice.getKey() + " must reference an extending type");
            }
            JsonStructureTypeDeclaration target = declarations.get(choice.getValue().getReference());
            if (target == null || !extendsType(target, base, declarations, new LinkedHashSet<>())) {
                throw new JsonStructureResolutionException(
                        "Inline choice alternative " + choice.getKey() + " does not extend " + base);
            }
        }
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
            JsonStructureTypeUse inherited =
                    findProperty(declarations.get(base), propertyName, declarations, visited);
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
            if (baseDeclaration != null && extendsType(baseDeclaration, expectedBase, declarations, visited)) {
                return true;
            }
        }
        return false;
    }

    private void validateUse(
            Map<QualifiedTypeName, JsonStructureTypeDeclaration> declarations,
            JsonStructureTypeDeclaration owner,
            JsonStructureTypeUse use) {
        if (use != null) {
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

    private void validateInheritance(Map<QualifiedTypeName, JsonStructureTypeDeclaration> declarations) {
        Set<QualifiedTypeName> visiting = new LinkedHashSet<>();
        Set<QualifiedTypeName> visited = new LinkedHashSet<>();
        declarations.keySet().forEach(name -> visitInheritance(name, declarations, visiting, visited));
        declarations.values().forEach(declaration -> {
            Set<String> inheritedProperties = new LinkedHashSet<>();
            for (QualifiedTypeName base : declaration.getBases()) {
                collectInheritedProperties(base, declarations, inheritedProperties);
            }
            for (String property : declaration.getProperties().keySet()) {
                if (inheritedProperties.contains(property)) {
                    throw new JsonStructureResolutionException(
                            declaration.getName() + " redefines inherited property " + property);
                }
            }
            Set<String> effectiveProperties = new LinkedHashSet<>(inheritedProperties);
            effectiveProperties.addAll(declaration.getProperties().keySet());
            declaration.getRequiredAlternatives().forEach(alternative ->
                    alternative.forEach(property -> {
                        if (!effectiveProperties.contains(property)) {
                            throw new JsonStructureResolutionException(
                                    "Required property " + property
                                            + " is not declared by " + declaration.getName());
                        }
                    }));
        });
    }

    private void collectInheritedProperties(
            QualifiedTypeName name,
            Map<QualifiedTypeName, JsonStructureTypeDeclaration> declarations,
            Set<String> properties) {
        JsonStructureTypeDeclaration declaration = declarations.get(name);
        if (declaration == null) {
            return;
        }
        properties.addAll(declaration.getProperties().keySet());
        declaration.getBases().forEach(base -> collectInheritedProperties(base, declarations, properties));
    }

    private void visitInheritance(
            QualifiedTypeName name,
            Map<QualifiedTypeName, JsonStructureTypeDeclaration> declarations,
            Set<QualifiedTypeName> visiting,
            Set<QualifiedTypeName> visited) {
        if (visited.contains(name)) {
            return;
        }
        if (!visiting.add(name)) {
            throw new JsonStructureResolutionException("Cyclic $extends chain at " + name);
        }
        for (QualifiedTypeName base : declarations.get(name).getBases()) {
            visitInheritance(base, declarations, visiting, visited);
        }
        visiting.remove(name);
        visited.add(name);
    }

    private JsonStructureTypeKind declarationKind(JsonNode node) {
        JsonNode type = node.get("type");
        if (type != null && type.isArray()) {
            return JsonStructureTypeKind.UNION;
        }
        if (type == null || !type.isTextual()) {
            throw new JsonStructureResolutionException("Named declarations must have one concrete type");
        }
        return JsonStructureTypeKind.fromName(type.textValue());
    }

    private void validateDeclarationName(
            QualifiedTypeName qualifiedName,
            String addressName,
            JsonNode node,
            JsonStructureTypeKind kind) {
        String declaredName = text(node, "name");
        if (kind == JsonStructureTypeKind.OBJECT
                || kind == JsonStructureTypeKind.TUPLE
                || kind == JsonStructureTypeKind.CHOICE) {
            if (declaredName == null) {
                throw new JsonStructureResolutionException(
                        kind.name().toLowerCase() + " declaration " + qualifiedName + " must declare name");
            }
        }
        if (declaredName != null) {
            validateIdentifier(declaredName, "Type");
            if (!declaredName.equals(addressName)) {
                throw new JsonStructureResolutionException(
                        "Declared name " + declaredName + " does not match definition name " + addressName);
            }
        }
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
            Boolean additionalPropertiesAllowed,
            JsonStructureTypeUse additionalPropertiesType) {
        if (!isCompound(kind) && kind != JsonStructureTypeKind.UNION) {
            validateTypeUseConstraints(
                    JsonStructureTypeUse.primitives(
                            List.of(kind),
                            integer(node.get("precision")),
                            integer(node.get("scale")),
                            integer(node.get("maxLength"))),
                    name.toString());
        }
        if (kind == JsonStructureTypeKind.OBJECT && properties.isEmpty()) {
            throw new JsonStructureResolutionException("Object " + name + " must declare properties");
        }
        if ((kind == JsonStructureTypeKind.ARRAY || kind == JsonStructureTypeKind.SET) && items == null) {
            throw new JsonStructureResolutionException(kind.name().toLowerCase() + " " + name + " must declare items");
        }
        if (kind == JsonStructureTypeKind.MAP && values == null) {
            throw new JsonStructureResolutionException("Map " + name + " must declare values");
        }
        if (kind == JsonStructureTypeKind.TUPLE) {
            if (properties.isEmpty()) {
                throw new JsonStructureResolutionException("Tuple " + name + " must declare properties");
            }
            if (tupleOrder.size() != properties.size()
                    || new LinkedHashSet<>(tupleOrder).size() != tupleOrder.size()
                    || !properties.keySet().equals(new LinkedHashSet<>(tupleOrder))) {
                throw new JsonStructureResolutionException(
                        "Tuple order of " + name + " must list every property exactly once");
            }
        }
        if (kind == JsonStructureTypeKind.CHOICE && choices.isEmpty()) {
            throw new JsonStructureResolutionException("Choice " + name + " must declare choices");
        }
        if (node.has("required") && kind != JsonStructureTypeKind.OBJECT) {
            throw new JsonStructureResolutionException("required is only valid on object declarations: " + name);
        }
        for (List<String> alternative : required) {
            if (alternative.isEmpty() || new LinkedHashSet<>(alternative).size() != alternative.size()) {
                throw new JsonStructureResolutionException(
                        "Required property sets of " + name + " must be non-empty and unique");
            }
            for (String property : alternative) {
                validateIdentifier(property, "Required property");
            }
        }
        if (node.path("abstract").asBoolean(false)
                && kind != JsonStructureTypeKind.OBJECT
                && kind != JsonStructureTypeKind.TUPLE) {
            throw new JsonStructureResolutionException(
                    "abstract is only valid on object and tuple declarations: " + name);
        }
        if (node.has("$extends")
                && kind != JsonStructureTypeKind.OBJECT
                && kind != JsonStructureTypeKind.TUPLE
                && kind != JsonStructureTypeKind.CHOICE) {
            throw new JsonStructureResolutionException(
                    "$extends is only valid on object, tuple, and inline choice declarations: " + name);
        }
        if ((node.has("enum") || node.has("const")) && isCompound(kind)) {
            throw new JsonStructureResolutionException(
                    "enum and const are only valid on primitive declarations: " + name);
        }
        if ((additionalPropertiesAllowed != null || additionalPropertiesType != null)
                && kind != JsonStructureTypeKind.OBJECT) {
            throw new JsonStructureResolutionException(
                    "additionalProperties is only valid on object declarations: " + name);
        }
        if (node.path("abstract").asBoolean(false)
                && (additionalPropertiesAllowed != null || additionalPropertiesType != null)) {
            throw new JsonStructureResolutionException(
                    "Abstract object declarations must not declare additionalProperties: " + name);
        }
    }

    private boolean isDeclaration(JsonNode node) {
        return node.has("type") || node.has("abstract") || node.has("$extends");
    }

    private boolean isCompound(JsonStructureTypeKind kind) {
        return kind == JsonStructureTypeKind.OBJECT
                || kind == JsonStructureTypeKind.ARRAY
                || kind == JsonStructureTypeKind.SET
                || kind == JsonStructureTypeKind.MAP
                || kind == JsonStructureTypeKind.TUPLE
                || kind == JsonStructureTypeKind.CHOICE;
    }

    private void validateTypeUseConstraints(JsonStructureTypeUse use, String location) {
        if (use.getPrecision() != null || use.getScale() != null) {
            if (use.getPrimitiveAlternatives().size() != 1
                    || use.getPrimitiveAlternatives().get(0) != JsonStructureTypeKind.DECIMAL
                    || use.hasReferences()) {
                throw new JsonStructureResolutionException(
                        "precision and scale are only valid on decimal at " + location);
            }
            if (use.getPrecision() == null || use.getPrecision() <= 0) {
                throw new JsonStructureResolutionException(
                        "decimal precision must be a positive integer at " + location);
            }
            if (use.getScale() != null
                    && (use.getScale() < 0 || use.getScale() > use.getPrecision())) {
                throw new JsonStructureResolutionException(
                        "decimal scale must be between zero and precision at " + location);
            }
        }

        if (use.getMaxLength() != null) {
            if (use.getMaxLength() < 0
                    || use.getPrimitiveAlternatives().size() != 1
                    || use.getPrimitiveAlternatives().get(0) != JsonStructureTypeKind.STRING
                    || use.hasReferences()) {
                throw new JsonStructureResolutionException(
                        "maxLength is only valid as a non-negative constraint on string at " + location);
            }
        }
    }

    private JsonStructureTypeUse applyValueConstraints(
            JsonStructureTypeUse use,
            JsonNode schemaNode,
            String location) {
        JsonNode enumNode = schemaNode.get("enum");
        boolean hasConst = schemaNode.has("const");
        if (enumNode == null && !hasConst) {
            return use;
        }
        if (use.hasReferences()
                || use.getPrimitiveAlternatives().size() != 1
                || isCompound(use.getPrimitiveAlternatives().get(0))
                || use.getPrimitiveAlternatives().get(0) == JsonStructureTypeKind.UNION) {
            throw new JsonStructureResolutionException(
                    "enum and const require one primitive type at " + location);
        }
        JsonStructureTypeKind kind = use.getPrimitiveAlternatives().get(0);
        List<Object> enumValues = new ArrayList<>();
        if (enumNode != null) {
            if (!enumNode.isArray() || enumNode.isEmpty()) {
                throw new JsonStructureResolutionException(
                        "enum must be a non-empty array at " + location);
            }
            Set<Object> unique = new LinkedHashSet<>();
            enumNode.forEach(value -> {
                Object scalar = scalarValue(value, location);
                validateValueKind(kind, scalar, location);
                if (!unique.add(scalar)) {
                    throw new JsonStructureResolutionException(
                            "enum values must be unique at " + location);
                }
                enumValues.add(scalar);
            });
        }
        Object constValue = null;
        if (hasConst) {
            constValue = scalarValue(schemaNode.get("const"), location);
            validateValueKind(kind, constValue, location);
        }
        return use.withValueConstraints(enumValues, hasConst, constValue);
    }

    private JsonStructureTypeUse applyTypeAnnotations(
            JsonStructureTypeUse use,
            JsonNode schemaNode,
            String location) {
        JsonStructureTypeUse constrained = applyValueConstraints(use, schemaNode, location);
        String contentEncoding = text(schemaNode, "contentEncoding");
        String contentCompression = text(schemaNode, "contentCompression");
        String contentMediaType = text(schemaNode, "contentMediaType");
        boolean binary = !constrained.hasReferences()
                && constrained.getPrimitiveAlternatives().size() == 1
                && constrained.getPrimitiveAlternatives().get(0) == JsonStructureTypeKind.BINARY;
        if (!binary && contentEncoding == null && contentCompression == null && contentMediaType == null) {
            return constrained;
        }
        if (!binary) {
            throw new JsonStructureResolutionException(
                    "Binary content annotations are only valid on binary at " + location);
        }
        if (contentEncoding == null) {
            contentEncoding = "base64";
        }
        if (contentEncoding != null
                && !Set.of("base64", "base64url", "base16", "base32", "base32hex")
                .contains(contentEncoding)) {
            throw new JsonStructureResolutionException(
                    "Unsupported binary contentEncoding at " + location + ": " + contentEncoding);
        }
        if (contentCompression != null
                && !Set.of("gzip", "deflate", "zlib", "brotli").contains(contentCompression)) {
            throw new JsonStructureResolutionException(
                    "Unsupported binary contentCompression at " + location + ": " + contentCompression);
        }
        if (contentMediaType != null && !MEDIA_TYPE.matcher(contentMediaType).matches()) {
            throw new JsonStructureResolutionException(
                    "Invalid binary contentMediaType at " + location + ": " + contentMediaType);
        }
        return constrained.withBinaryAnnotations(
                contentEncoding,
                contentCompression,
                contentMediaType);
    }

    private Object scalarValue(JsonNode node, String location) {
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
        throw new JsonStructureResolutionException(
                "enum and const values must be primitive JSON values at " + location);
    }

    private void validateValueKind(JsonStructureTypeKind kind, Object value, String location) {
        boolean valid;
        switch (kind.wireKind()) {
            case STRING:
                valid = value instanceof String;
                break;
            case NUMBER:
                valid = value instanceof Number;
                break;
            case BOOLEAN:
                valid = value instanceof Boolean;
                break;
            case NULL:
                valid = value == null;
                break;
            case ANY:
                valid = true;
                break;
            default:
                valid = false;
                break;
        }
        if (!valid) {
            throw new JsonStructureResolutionException(
                    "Value does not match " + kind.name().toLowerCase() + " at " + location);
        }
    }

        private List<List<String>> requiredAlternatives(JsonNode required) {
        if (required == null) {
            return List.of();
        }
        if (!required.isArray()) {
            throw new JsonStructureResolutionException("required must be an array");
        }
        if (required.isEmpty()) {
            return List.of();
        }
        if (required.get(0).isArray()) {
            List<List<String>> alternatives = new ArrayList<>();
            required.forEach(value -> alternatives.add(stringList(value)));
            return alternatives;
        }
        return List.of(stringList(required));
    }

    private List<QualifiedTypeName> referenceList(URI resourceId, JsonNode node) {
        if (node == null) {
            return List.of();
        }
        if (node.isTextual()) {
            return List.of(pointerToName(resourceId, node.textValue()));
        }
        if (node.isArray()) {
            List<QualifiedTypeName> result = new ArrayList<>();
            node.forEach(value -> {
                if (!value.isTextual()) {
                    throw new JsonStructureResolutionException("$extends entries must be JSON Pointers");
                }
                result.add(pointerToName(resourceId, value.textValue()));
            });
            return result;
        }
        throw new JsonStructureResolutionException("$extends must be a JSON Pointer or array");
    }

    private QualifiedTypeName pointerToName(URI resourceId, String pointer) {
        if (pointer == null || !pointer.startsWith("#/definitions/")) {
            throw new JsonStructureResolutionException(
                    "JSON Structure references must target local definitions: " + pointer);
        }
        String[] segments = pointer.substring("#/definitions/".length()).split("/");
        if (segments.length == 0) {
            throw new JsonStructureResolutionException("Invalid JSON Structure reference: " + pointer);
        }
        List<String> decoded = new ArrayList<>();
        for (String segment : segments) {
            decoded.add(segment.replace("~1", "/").replace("~0", "~"));
        }
        return new QualifiedTypeName(
                resourceId,
                decoded.subList(0, decoded.size() - 1),
                decoded.get(decoded.size() - 1));
    }

    private URI parseAbsoluteUri(String value, String componentName) {
        try {
            URI uri = URI.create(value);
            if (!uri.isAbsolute()) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new JsonStructureResolutionException(
                    "JSON Structure URI in " + componentName + " must be absolute: " + value);
        }
    }

    private String withoutFragment(URI uri) {
        String value = uri.toString();
        int fragment = value.indexOf('#');
        return fragment < 0 ? value : value.substring(0, fragment);
    }

    private URI descriptionBaseUri(JsonNode document, URI retrievalUri) {
        String self = text(document, "$self");
        if (self != null) {
            return parseAbsoluteUri(self, "OpenAPI $self");
        }
        return retrievalUri;
    }

    private URI defaultId(URI baseUri, String componentName) {
        String base = baseUri.toString();
        int fragment = base.indexOf('#');
        if (fragment >= 0) {
            base = base.substring(0, fragment);
        }
        String pointerName = componentName.replace("~", "~0").replace("/", "~1");
        return URI.create(base + "#/components/schemas/" + pointerName);
    }

    private String text(JsonNode node, String property) {
        JsonNode value = node.get(property);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private Integer integer(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            throw new JsonStructureResolutionException("Expected a 32-bit integer constraint value");
        }
        return node.intValue();
    }

    private List<String> stringList(JsonNode node) {
        if (node == null) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new JsonStructureResolutionException("Expected an array of strings");
        }
        List<String> result = new ArrayList<>();
        node.forEach(value -> {
            if (!value.isTextual()) {
                throw new JsonStructureResolutionException("Expected an array of strings");
            }
            result.add(value.textValue());
        });
        return result;
    }

    private List<String> append(List<String> values, String value) {
        List<String> result = new ArrayList<>(values);
        result.add(value);
        return result;
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

    private static final class ResourceDraft {
        private final String componentName;
        private final URI id;
        private final SchemaDialect dialect;
        private final Map<QualifiedTypeName, JsonStructureTypeDeclaration> localDeclarations = new LinkedHashMap<>();
        private final List<ImportRequest> imports = new ArrayList<>();
        private QualifiedTypeName root;
        private boolean rootIsDefinition;
        private boolean explicitId;
        private Map<QualifiedTypeName, JsonStructureTypeDeclaration> resolvedDeclarations = Collections.emptyMap();
        private boolean resolved;

        private ResourceDraft(String componentName, URI id, SchemaDialect dialect) {
            this.componentName = componentName;
            this.id = id;
            this.dialect = dialect;
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
