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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.stream.Collectors;

public final class JsonStructureSchemaMapper {
    public static final String X_SCHEMA_DIALECT = "x-openapi-generator-schema-dialect";
    public static final String X_LOGICAL_TYPE = "x-json-structure-logical-type";
    public static final String X_WIRE_TYPE = "x-json-structure-wire-type";
    public static final String X_ORIGIN = "x-json-structure-origin";
    public static final String X_EFFECTIVE_ID = "x-json-structure-effective-id";
    public static final String X_NAMESPACE = "x-json-structure-namespace";
    public static final String X_REFERENCE_IDENTITIES = "x-json-structure-reference-identities";
    public static final String X_REQUIRED_ALTERNATIVES = "x-json-structure-required-alternatives";
    public static final String X_TUPLE_ORDER = "x-json-structure-tuple-order";
    public static final String X_BASES = "x-json-structure-bases";
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
            case UNION:
                schema = typeUseSchema(declaration.getDeclaredType(), graph, catalog);
                break;
            default:
                schema = primitiveSchema(declaration.getKind());
                applyValueConstraints(schema, declaration.getDeclaredType());
                break;
        }
        annotate(schema, declaration.getKind().name().toLowerCase(), declaration.getWireKind().name().toLowerCase());
        schema.addExtension(X_ORIGIN, declaration.getOrigin().toString());
        schema.addExtension(X_EFFECTIVE_ID, declaration.getName().toString());
        schema.addExtension(X_NAMESPACE, declaration.getName().getNamespace());
        if (declaration.getPrecision() != null) {
            schema.addExtension("x-json-structure-precision", declaration.getPrecision());
        }
        if (declaration.getScale() != null) {
            schema.addExtension("x-json-structure-scale", declaration.getScale());
        }
        return schema;
    }

    private Schema<?> objectSchema(
            JsonStructureTypeDeclaration declaration,
            JsonStructureTypeGraph graph,
            JsonStructureModelCatalog catalog) {
        ObjectSchema own = new ObjectSchema();
        effectiveProperties(declaration, graph, new LinkedHashSet<>()).forEach((name, type) ->
                own.addProperty(name, typeUseSchema(type, graph, catalog)));
        if (declaration.getAdditionalPropertiesType() != null) {
            own.setAdditionalProperties(typeUseSchema(
                    declaration.getAdditionalPropertiesType(),
                    graph,
                    catalog));
        } else if (declaration.getAdditionalPropertiesAllowed() != null) {
            own.setAdditionalProperties(declaration.getAdditionalPropertiesAllowed());
        }
        List<List<String>> requiredAlternatives =
                effectiveRequiredAlternatives(declaration, graph, new LinkedHashSet<>());
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
        effectiveProperties(declaration, graph, new LinkedHashSet<>()).forEach((name, type) ->
                tuple.addProperty(name, typeUseSchema(type, graph, catalog)));
        List<String> tupleOrder = effectiveTupleOrder(declaration, graph, new LinkedHashSet<>());
        tuple.setRequired(tupleOrder);
        tuple.addExtension(X_TUPLE_ORDER, tupleOrder);
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

    private Map<String, JsonStructureTypeUse> effectiveProperties(
            JsonStructureTypeDeclaration declaration,
            JsonStructureTypeGraph graph,
            Set<QualifiedTypeName> visiting) {
        if (!visiting.add(declaration.getName())) {
            throw new IllegalStateException("Cyclic JSON Structure inheritance at " + declaration.getName());
        }
        Map<String, JsonStructureTypeUse> properties = new LinkedHashMap<>();
        for (QualifiedTypeName base : declaration.getBases()) {
            JsonStructureTypeDeclaration baseDeclaration = graph.getDeclarations().get(base);
            effectiveProperties(baseDeclaration, graph, visiting)
                    .forEach(properties::putIfAbsent);
        }
        properties.putAll(declaration.getProperties());
        visiting.remove(declaration.getName());
        return properties;
    }

    private List<List<String>> effectiveRequiredAlternatives(
            JsonStructureTypeDeclaration declaration,
            JsonStructureTypeGraph graph,
            Set<QualifiedTypeName> visiting) {
        if (!visiting.add(declaration.getName())) {
            throw new IllegalStateException("Cyclic JSON Structure inheritance at " + declaration.getName());
        }
        List<List<String>> result = List.of(List.of());
        for (QualifiedTypeName base : declaration.getBases()) {
            JsonStructureTypeDeclaration baseDeclaration = graph.getDeclarations().get(base);
            result = combineRequired(
                    result,
                    effectiveRequiredAlternatives(baseDeclaration, graph, visiting));
        }
        result = combineRequired(result, declaration.getRequiredAlternatives());
        visiting.remove(declaration.getName());
        if (result.size() == 1 && result.get(0).isEmpty()) {
            return List.of();
        }
        return result;
    }

    private List<String> effectiveTupleOrder(
            JsonStructureTypeDeclaration declaration,
            JsonStructureTypeGraph graph,
            Set<QualifiedTypeName> visiting) {
        if (!visiting.add(declaration.getName())) {
            throw new IllegalStateException("Cyclic JSON Structure inheritance at " + declaration.getName());
        }
        Set<String> order = new LinkedHashSet<>();
        for (QualifiedTypeName base : declaration.getBases()) {
            JsonStructureTypeDeclaration baseDeclaration = graph.getDeclarations().get(base);
            order.addAll(effectiveTupleOrder(baseDeclaration, graph, visiting));
        }
        order.addAll(declaration.getTupleOrder());
        visiting.remove(declaration.getName());
        return List.copyOf(order);
    }

    private List<List<String>> combineRequired(
            List<List<String>> left,
            List<List<String>> right) {
        if (right.isEmpty()) {
            return left;
        }
        List<List<String>> result = new ArrayList<>();
        for (List<String> leftAlternative : left) {
            for (List<String> rightAlternative : right) {
                Set<String> combined = new LinkedHashSet<>(leftAlternative);
                combined.addAll(rightAlternative);
                result.add(List.copyOf(combined));
            }
        }
        return result;
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
            throw new IllegalArgumentException("JSON Structure compound type is missing its element type");
        }
        if (use.isReference()) {
            JsonStructureTypeDeclaration target = graph.getDeclarations().get(use.getReference());
            Schema<?> reference = new Schema<>().$ref(componentRef(catalog.modelName(use.getReference())));
            annotate(reference, target.getKind().name().toLowerCase(), target.getWireKind().name().toLowerCase());
            return reference;
        }

        List<JsonStructureTypeKind> nonNull = new ArrayList<>();
        for (JsonStructureTypeKind kind : use.getPrimitiveAlternatives()) {
            if (kind != JsonStructureTypeKind.NULL) {
                nonNull.add(kind);
            }
        }
        Schema<?> schema;
        int alternativeCount = nonNull.size() + use.getReferenceAlternatives().size();
        if (alternativeCount <= 1 && use.getReferenceAlternatives().isEmpty()) {
            JsonStructureTypeKind kind = nonNull.isEmpty() ? JsonStructureTypeKind.NULL : nonNull.get(0);
            schema = primitiveSchema(kind);
            annotate(schema, kind.name().toLowerCase(), kind.wireKind().name().toLowerCase());
        } else {
            ComposedSchema union = new ComposedSchema();
            nonNull.forEach(kind -> union.addOneOfItem(primitiveSchema(kind)));
            use.getReferenceAlternatives().forEach(reference ->
                    union.addOneOfItem(new Schema<>().$ref(componentRef(catalog.modelName(reference)))));
            schema = union;
            annotate(schema, "union", JsonWireKind.ANY.name().toLowerCase());
        }
        if (use.isNullable()) {
            schema.setNullable(true);
        }
        if (use.getPrecision() != null) {
            schema.addExtension("x-json-structure-precision", use.getPrecision());
        }
        if (use.getScale() != null) {
            schema.addExtension("x-json-structure-scale", use.getScale());
        }
        if (use.getMaxLength() != null) {
            schema.setMaxLength(use.getMaxLength());
        }
        applyValueConstraints(schema, use);
        return schema;
    }

    @SuppressWarnings("unchecked")
    private void applyValueConstraints(Schema<?> schema, JsonStructureTypeUse use) {
        if (use == null) {
            return;
        }
        if (!use.getEnumValues().isEmpty()) {
            ((Schema<Object>) schema).setEnum(use.getEnumValues());
        }
        if (use.hasConst()) {
            schema.setConst(use.getConstValue());
        }
        if (use.getContentEncoding() != null) {
            schema.setContentEncoding(use.getContentEncoding());
        }
        if (use.getContentCompression() != null) {
            schema.addExtension("x-json-structure-content-compression", use.getContentCompression());
        }
        if (use.getContentMediaType() != null) {
            schema.setContentMediaType(use.getContentMediaType());
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
            case URI:
                schema.setFormat("uri");
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
