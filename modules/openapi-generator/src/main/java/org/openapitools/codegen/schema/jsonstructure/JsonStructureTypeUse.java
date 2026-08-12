/*
 * Copyright 2026 OpenAPI-Generator Contributors (https://openapi-generator.tech)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.openapitools.codegen.schema.jsonstructure;

import java.util.List;
import java.util.Objects;

public final class JsonStructureTypeUse {
    private final List<JsonStructureTypeKind> primitiveAlternatives;
    private final List<QualifiedTypeName> referenceAlternatives;
    private final Integer precision;
    private final Integer scale;
    private final Integer maxLength;
    private final List<Object> enumValues;
    private final boolean hasConst;
    private final Object constValue;
    private final String contentEncoding;
    private final String contentCompression;
    private final String contentMediaType;

    private JsonStructureTypeUse(
            List<JsonStructureTypeKind> primitiveAlternatives,
            List<QualifiedTypeName> referenceAlternatives,
            Integer precision,
            Integer scale,
            Integer maxLength,
            List<Object> enumValues,
            boolean hasConst,
            Object constValue,
            String contentEncoding,
            String contentCompression,
            String contentMediaType) {
        this.primitiveAlternatives = List.copyOf(primitiveAlternatives);
        this.referenceAlternatives = List.copyOf(referenceAlternatives);
        this.precision = precision;
        this.scale = scale;
        this.maxLength = maxLength;
        this.enumValues = List.copyOf(enumValues);
        this.hasConst = hasConst;
        this.constValue = constValue;
        this.contentEncoding = contentEncoding;
        this.contentCompression = contentCompression;
        this.contentMediaType = contentMediaType;
    }

    public static JsonStructureTypeUse primitives(List<JsonStructureTypeKind> alternatives) {
        return primitives(alternatives, null, null, null);
    }

    public static JsonStructureTypeUse primitives(
            List<JsonStructureTypeKind> alternatives,
            Integer precision,
            Integer scale,
            Integer maxLength) {
        return new JsonStructureTypeUse(
                alternatives,
                List.of(),
                precision,
                scale,
                maxLength,
                List.of(),
                false,
                null,
                null,
                null,
                null);
    }

    public static JsonStructureTypeUse reference(QualifiedTypeName reference) {
        return new JsonStructureTypeUse(
                List.of(),
                List.of(Objects.requireNonNull(reference)),
                null,
                null,
                null,
                List.of(),
                false,
                null,
                null,
                null,
                null);
    }

    public static JsonStructureTypeUse union(
            List<JsonStructureTypeKind> primitiveAlternatives,
            List<QualifiedTypeName> referenceAlternatives,
            Integer precision,
            Integer scale,
            Integer maxLength) {
        return new JsonStructureTypeUse(
                primitiveAlternatives,
                referenceAlternatives,
                precision,
                scale,
                maxLength,
                List.of(),
                false,
                null,
                null,
                null,
                null);
    }

    public JsonStructureTypeUse withValueConstraints(
            List<Object> enumValues,
            boolean hasConst,
            Object constValue) {
        return new JsonStructureTypeUse(
                primitiveAlternatives,
                referenceAlternatives,
                precision,
                scale,
                maxLength,
                enumValues,
                hasConst,
                constValue,
                contentEncoding,
                contentCompression,
                contentMediaType);
    }

    public JsonStructureTypeUse withBinaryAnnotations(
            String contentEncoding,
            String contentCompression,
            String contentMediaType) {
        return new JsonStructureTypeUse(
                primitiveAlternatives,
                referenceAlternatives,
                precision,
                scale,
                maxLength,
                enumValues,
                hasConst,
                constValue,
                contentEncoding,
                contentCompression,
                contentMediaType);
    }

    public List<JsonStructureTypeKind> getPrimitiveAlternatives() {
        return primitiveAlternatives;
    }

    public QualifiedTypeName getReference() {
        return isReference() ? referenceAlternatives.get(0) : null;
    }

    public List<QualifiedTypeName> getReferenceAlternatives() {
        return referenceAlternatives;
    }

    public boolean isReference() {
        return primitiveAlternatives.isEmpty() && referenceAlternatives.size() == 1;
    }

    public boolean hasReferences() {
        return !referenceAlternatives.isEmpty();
    }

    public boolean isNullable() {
        return primitiveAlternatives.contains(JsonStructureTypeKind.NULL);
    }

    public Integer getPrecision() {
        return precision;
    }

    public Integer getScale() {
        return scale;
    }

    public Integer getMaxLength() {
        return maxLength;
    }

    public List<Object> getEnumValues() {
        return enumValues;
    }

    public boolean hasConst() {
        return hasConst;
    }

    public Object getConstValue() {
        return constValue;
    }

    public String getContentEncoding() {
        return contentEncoding;
    }

    public String getContentCompression() {
        return contentCompression;
    }

    public String getContentMediaType() {
        return contentMediaType;
    }
}
