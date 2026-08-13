/*
 * Copyright 2026 OpenAPI-Generator Contributors (https://openapi-generator.tech)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.openapitools.codegen.schema.jsonstructure;

import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Discriminator;
import io.swagger.v3.oas.models.media.MapSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class JsonStructureSchemaMapper {
    public static final String X_SCHEMA_DIALECT = "x-openapi-generator-schema-dialect";
    public static final String X_SDK_VALIDATED = "x-json-structure-sdk-validated";
    public static final String X_META_SCHEMA_URI = "x-json-structure-meta-schema-uri";
    public static final String X_LOGICAL_TYPE = "x-json-structure-logical-type";
    public static final String X_WIRE_TYPE = "x-json-structure-wire-type";
    public static final String X_ORIGIN = "x-json-structure-origin";
    public static final String X_EFFECTIVE_ID = "x-json-structure-effective-id";
    public static final String X_NAMESPACE = "x-json-structure-namespace";
    public static final String X_REFERENCE_IDENTITIES = "x-json-structure-reference-identities";
    public static final String X_REQUIRED_ALTERNATIVES = "x-json-structure-required-alternatives";
    public static final String X_TUPLE_ORDER = "x-json-structure-tuple-order";
    public static final String X_BASES = "x-json-structure-bases";
    public static final String X_PRECISION = "x-json-structure-precision";
    public static final String X_SCALE = "x-json-structure-scale";
    public static final String X_ENUM_VALUES = "x-json-structure-enum-values";
    public static final String X_CONST_VALUE = "x-json-structure-const-value";
    public static final String X_HAS_CONST = "x-json-structure-has-const";
    public static final String X_CONTENT_COMPRESSION = "x-json-structure-content-compression";
    public static final String X_UNION_ORDER = "x-json-structure-union-order";
    public static final String X_UNION_MATCHING = "x-json-structure-union-matching";
    public static final String X_REFERENCE_DESCRIPTION = "x-json-structure-reference-description";
    public static final String X_REFERENCE_ALTERNATIVES = "x-json-structure-reference-alternatives";
    public static final String X_OFFERS = "x-json-structure-offers";
    public static final String X_USES = "x-json-structure-uses";
    public static final String X_EFFECTIVE_USES = "x-json-structure-effective-uses";
    public static final String X_DOCUMENT_NAME = "x-json-structure-document-name";
    public static final String X_RESOURCE_DESCRIPTION = "x-json-structure-resource-description";
    public static final String X_RESOURCE_EXAMPLES = "x-json-structure-resource-examples";
    public static final String X_ADDITIONAL_PROPERTIES = "x-json-structure-additional-properties";
    public static final String X_CHOICE_ORDER = "x-json-structure-choice-order";
    private final boolean compatibilityMode;

    public JsonStructureSchemaMapper() {
        this(false);
    }

    public JsonStructureSchemaMapper(boolean compatibilityMode) {
        this.compatibilityMode = compatibilityMode;
    }

    public Schema<?> toSchema(
            JsonStructureTypeDeclaration declaration,
            JsonStructureTypeGraph graph,
            JsonStructureModelCatalog catalog) {
        Schema<?> schema;
        switch (declaration.getKind()) {
            case OBJECT:
                schema = objectSchema(declaration, graph, catalog);
                break;
            case ARRAY:
                schema = new ArraySchema().items(typeUseSchema(declaration.getItems(), graph, catalog));
                break;
            case SET:
                schema = new ArraySchema()
                        .items(typeUseSchema(declaration.getItems(), graph, catalog))
                        .uniqueItems(true);
                break;
            case MAP:
                schema = new MapSchema()
                        .additionalProperties(typeUseSchema(declaration.getValues(), graph, catalog));
                break;
            case TUPLE:
                schema = tupleSchema(declaration, graph, catalog);
                break;
            case CHOICE:
                schema = choiceSchema(declaration, graph, catalog);
                break;
            case ALIAS:
            case UNION:
                schema = typeUseSchema(declaration.getDeclaredType(), graph, catalog);
                break;
            default:
                schema = typeUseSchema(declaration.getDeclaredType(), graph, catalog);
                break;
        }
        annotate(
                schema,
                declaration.getLogicalTypeName(),
                graph.effectiveWireKind(declaration.getName()).name().toLowerCase(Locale.ROOT));
        applyDocumentation(schema, declaration.getDescription(), declaration.getExamples());
        schema.addExtension(X_SDK_VALIDATED, graph.isSdkValidated());
        schema.addExtension(X_ORIGIN, declaration.getOrigin().toString());
        schema.addExtension(X_EFFECTIVE_ID, declaration.getName().toString());
        schema.addExtension(X_NAMESPACE, declaration.getName().getNamespace());
        schema.addExtension("x-json-structure-abstract", declaration.isAbstractType());
        if (!declaration.getBases().isEmpty()) {
            schema.addExtension(X_BASES, declaration.getBases().stream()
                    .map(QualifiedTypeName::toString)
                    .collect(Collectors.toList()));
        }
        if (declaration.getPrecision() != null) {
            schema.addExtension(X_PRECISION, declaration.getPrecision());
        }
        if (declaration.getScale() != null) {
            schema.addExtension(X_SCALE, declaration.getScale());
        }
        if (declaration.getKind() == JsonStructureTypeKind.OBJECT) {
            Object additional = declaration.getAdditionalPropertiesType() != null
                    ? "typed"
                    : declaration.getAdditionalPropertiesAllowed();
            schema.addExtension(X_ADDITIONAL_PROPERTIES,
                    additional == null ? "default-open" : additional);
        }

        JsonStructureResource resource = graph.resource(declaration.getName());
        if (resource != null) {
            schema.addExtension(X_META_SCHEMA_URI, resource.getMetaSchemaUri());
        }
        if (resource != null && declaration.getName().equals(resource.getRoot())) {
            Map<String, List<String>> offers = new LinkedHashMap<>();
            resource.getOffers().forEach((name, types) -> offers.put(
                    name,
                    types.stream().map(QualifiedTypeName::toString).collect(Collectors.toList())));
            schema.addExtension(X_OFFERS, offers);
            schema.addExtension(X_USES, resource.getDeclaredUses());
            schema.addExtension(X_EFFECTIVE_USES, resource.getEffectiveUses());
            if (resource.getDocumentName() != null) {
                schema.addExtension(X_DOCUMENT_NAME, resource.getDocumentName());
            }
            if (resource.getDescription() != null) {
                schema.addExtension(X_RESOURCE_DESCRIPTION, resource.getDescription());
            }
            if (!resource.getExamples().isEmpty()) {
                schema.addExtension(X_RESOURCE_EXAMPLES, resource.getExamples());
            }
        }
        return schema;
    }

    private Schema<?> objectSchema(
            JsonStructureTypeDeclaration declaration,
            JsonStructureTypeGraph graph,
            JsonStructureModelCatalog catalog) {
        ObjectSchema own = new ObjectSchema();
        graph.effectiveProperties(declaration.getName()).forEach((name, type) ->
                own.addProperty(name, typeUseSchema(type, graph, catalog)));
        if (declaration.getAdditionalPropertiesType() != null) {
            own.setAdditionalProperties(typeUseSchema(
                    declaration.getAdditionalPropertiesType(),
                    graph,
                    catalog));
        } else {
            own.setAdditionalProperties(
                    graph.effectiveAdditionalPropertiesAllowed(declaration.getName()));
        }
        List<List<String>> requiredAlternatives =
                graph.effectiveRequiredAlternatives(declaration.getName());
        if (requiredAlternatives.size() == 1) {
            own.setRequired(requiredAlternatives.get(0));
        } else if (requiredAlternatives.size() > 1) {
            own.setRequired(requiredIntersection(requiredAlternatives));
            own.addExtension(X_REQUIRED_ALTERNATIVES, requiredAlternatives);
        }
        if (!declaration.getBases().isEmpty()) {
            own.addExtension(X_BASES, declaration.getBases().stream()
                    .map(QualifiedTypeName::displayName)
                    .collect(Collectors.toList()));
        }
        return own;
    }

    private Schema<?> tupleSchema(
            JsonStructureTypeDeclaration declaration,
            JsonStructureTypeGraph graph,
            JsonStructureModelCatalog catalog) {
        ObjectSchema tuple = new ObjectSchema();
        graph.effectiveProperties(declaration.getName()).forEach((name, type) ->
                tuple.addProperty(name, typeUseSchema(type, graph, catalog)));
        List<String> tupleOrder = graph.effectiveTupleOrder(declaration.getName());
        tuple.setRequired(tupleOrder);
        tuple.addExtension(X_TUPLE_ORDER, tupleOrder);
        Map<String, List<String>> tupleTypes = new LinkedHashMap<>();
        graph.effectiveProperties(declaration.getName()).forEach((name, type) ->
                tupleTypes.put(name, type.getOrderedTypeIdentities()));
        tuple.addExtension("x-json-structure-tuple-types", tupleTypes);
        if (!declaration.getBases().isEmpty()) {
            tuple.addExtension(X_BASES, declaration.getBases().stream()
                    .map(QualifiedTypeName::displayName)
                    .collect(Collectors.toList()));
        }
        return tuple;
    }

    private Schema<?> choiceSchema(
            JsonStructureTypeDeclaration declaration,
            JsonStructureTypeGraph graph,
            JsonStructureModelCatalog catalog) {
        if (compatibilityMode) {
            ObjectSchema choice = new ObjectSchema();
            choice.addExtension(X_CHOICE_ORDER, List.copyOf(declaration.getChoices().keySet()));
            declaration.getChoices().forEach((name, type) ->
                    choice.addProperty(name, typeUseSchema(type, graph, catalog)));
            choice.addExtension(
                    X_REQUIRED_ALTERNATIVES,
                    declaration.getChoices().keySet().stream()
                            .map(List::of)
                            .collect(Collectors.toList()));
            return choice;
        }
        ComposedSchema choice = new ComposedSchema();
        choice.addExtension(X_CHOICE_ORDER, List.copyOf(declaration.getChoices().keySet()));
        Map<String, String> mappings = new LinkedHashMap<>();
        declaration.getChoices().forEach((name, type) -> {
            Schema<?> option = typeUseSchema(type, graph, catalog);
            if (declaration.getBases().isEmpty()) {
                ObjectSchema tagged = new ObjectSchema();
                tagged.addProperty(name, option);
                tagged.setRequired(List.of(name));
                tagged.setAdditionalProperties(false);
                choice.addOneOfItem(tagged);
            } else {
                choice.addOneOfItem(option);
                if (option.get$ref() != null) {
                    mappings.put(name, option.get$ref());
                }
            }
        });
        if (declaration.getSelector() != null) {
            Discriminator discriminator = new Discriminator().propertyName(declaration.getSelector());
            mappings.forEach(discriminator::mapping);
            choice.setDiscriminator(discriminator);
        }
        return choice;
    }

    private List<String> requiredIntersection(List<List<String>> alternatives) {
        Set<String> intersection = new LinkedHashSet<>(alternatives.get(0));
        alternatives.subList(1, alternatives.size()).forEach(intersection::retainAll);
        return List.copyOf(intersection);
    }

    private Schema<?> typeUseSchema(
            JsonStructureTypeUse use,
            JsonStructureTypeGraph graph,
            JsonStructureModelCatalog catalog) {
        if (use == null) {
            throw new IllegalArgumentException(
                    "JSON Structure compound type is missing its element type");
        }

        Schema<?> schema;
        if (use.getAlternatives().size() == 1) {
            schema = alternativeSchema(use.getAlternatives().get(0), graph, catalog);
        } else {
            ComposedSchema union = new ComposedSchema();
            List<Map<String, Object>> alternatives = new ArrayList<>();
            for (int index = 0; index < use.getAlternatives().size(); index++) {
                JsonStructureTypeAlternative alternative = use.getAlternatives().get(index);
                if (!(compatibilityMode
                        && alternative.isPrimitive()
                        && alternative.getPrimitiveKind() == JsonStructureTypeKind.NULL)) {
                    union.addAnyOfItem(alternativeSchema(alternative, graph, catalog));
                }
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("index", index);
                metadata.put("identity", alternative.identity());
                metadata.put("kind", alternative.isPrimitive() ? "primitive" : "reference");
                if (alternative.getReferenceDescription() != null) {
                    metadata.put("description", alternative.getReferenceDescription());
                }
                alternatives.add(metadata);
            }
            schema = union;
            annotate(schema, "union", JsonWireKind.ANY.name().toLowerCase(Locale.ROOT));
            schema.addExtension(X_UNION_ORDER, use.getOrderedTypeIdentities());
            schema.addExtension(X_UNION_MATCHING, "first-match");
            schema.addExtension(X_REFERENCE_ALTERNATIVES, alternatives);
            if (compatibilityMode && use.isNullable()) {
                schema.setNullable(true);
            }
        }

        if (use.getPrecision() != null) {
            schema.addExtension(X_PRECISION, use.getPrecision());
        }
        if (use.getScale() != null) {
            schema.addExtension(X_SCALE, use.getScale());
        }
        if (use.getMaxLength() != null) {
            schema.setMaxLength(use.getMaxLength());
        }
        applyDocumentation(schema, use.getDescription(), use.getExamples());
        applyValueConstraints(schema, use);
        return schema;
    }

    private Schema<?> alternativeSchema(
            JsonStructureTypeAlternative alternative,
            JsonStructureTypeGraph graph,
            JsonStructureModelCatalog catalog) {
        if (alternative.isReference()) {
            QualifiedTypeName name = alternative.getReference();
            JsonStructureTypeDeclaration target = graph.getDeclarations().get(name);
            Schema<?> reference = new Schema<>().$ref(componentRef(catalog.modelName(name)));
            annotate(
                    reference,
                    target.getLogicalTypeName(),
                    graph.effectiveWireKind(name).name().toLowerCase(Locale.ROOT));
            if (alternative.getReferenceDescription() != null) {
                reference.setDescription(alternative.getReferenceDescription());
                reference.addExtension(
                        X_REFERENCE_DESCRIPTION, alternative.getReferenceDescription());
            }
            reference.addExtension(X_EFFECTIVE_ID, name.toString());
            reference.addExtension(X_ORIGIN, target.getOrigin().toString());
            return reference;
        }
        JsonStructureTypeKind kind = alternative.getPrimitiveKind();
        Schema<?> primitive = primitiveSchema(kind);
        annotate(
                primitive,
                alternative.getPrimitiveName(),
                kind.wireKind().name().toLowerCase(Locale.ROOT));
        return primitive;
    }

    @SuppressWarnings("unchecked")
    private void applyValueConstraints(Schema<?> schema, JsonStructureTypeUse use) {
        if (use == null) {
            return;
        }
        if (!use.getEnumValues().isEmpty()) {
            ((Schema<Object>) schema).setEnum(use.getEnumValues());
            schema.addExtension(X_ENUM_VALUES, use.getEnumValues());
        }
        if (use.hasConst()) {
            schema.setConst(use.getConstValue());
            schema.addExtension(X_HAS_CONST, true);
            schema.addExtension(X_CONST_VALUE, use.getConstValue());
        }
        if (use.getContentEncoding() != null) {
            schema.setContentEncoding(use.getContentEncoding());
        }
        if (use.getContentCompression() != null) {
            schema.addExtension(X_CONTENT_COMPRESSION, use.getContentCompression());
        }
        if (use.getContentMediaType() != null) {
            schema.setContentMediaType(use.getContentMediaType());
        }
        if (use.getContentEncoding() != null
                && !"base64".equals(use.getContentEncoding())
                && "byte".equals(schema.getFormat())) {
            schema.setFormat(null);
        }
    }

    @SuppressWarnings("unchecked")
    private void applyDocumentation(
            Schema<?> schema, String description, List<Object> examples) {
        if (description != null) {
            schema.setDescription(description);
        }
        if (!examples.isEmpty()) {
            ((Schema<Object>) schema).setExamples(examples);
        }
    }

    private Schema<?> primitiveSchema(JsonStructureTypeKind kind) {
        Schema<?> schema = new Schema<>();
        switch (kind.wireKind()) {
            case STRING:
                schema.setType("string");
                break;
            case NUMBER:
                schema.setType(kind == JsonStructureTypeKind.FLOAT
                        || kind == JsonStructureTypeKind.DOUBLE
                        || kind == JsonStructureTypeKind.FLOAT8
                        || kind == JsonStructureTypeKind.NUMBER ? "number" : "integer");
                break;
            case BOOLEAN:
                schema.setType("boolean");
                break;
            case NULL:
                schema.setType("null");
                break;
            case OBJECT:
                schema.setType("object");
                break;
            case ARRAY:
                schema.setType("array");
                break;
            case ANY:
                break;
            default:
                throw new IllegalStateException("Unsupported wire kind");
        }
        switch (kind) {
            case FLOAT:
                schema.setFormat("float");
                break;
            case DOUBLE:
                schema.setFormat("double");
                break;
            case DATE:
                schema.setFormat("date");
                break;
            case DATETIME:
                schema.setFormat("date-time");
                break;
            case UUID:
                schema.setFormat("uuid");
                break;
            case TIME:
                schema.setFormat("time");
                break;
            case DURATION:
                schema.setFormat("duration");
                break;
            case JSON_POINTER:
                schema.setFormat("json-pointer");
                break;
            case URI:
                schema.setFormat("uri-reference");
                break;
            case BINARY:
                schema.setFormat("byte");
                break;
            default:
                break;
        }
        return schema;
    }

    private void annotate(Schema<?> schema, String logicalType, String wireType) {
        schema.addExtension(X_SCHEMA_DIALECT, "json-structure");
        schema.addExtension(X_LOGICAL_TYPE, logicalType);
        schema.addExtension(X_WIRE_TYPE, wireType);
    }

    private String componentRef(String modelName) {
        return "#/components/schemas/" + modelName;
    }
}
