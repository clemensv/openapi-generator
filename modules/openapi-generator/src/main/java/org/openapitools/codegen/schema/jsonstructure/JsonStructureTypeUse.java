/*
 * Copyright 2026 OpenAPI-Generator Contributors (https://openapi-generator.tech)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.openapitools.codegen.schema.jsonstructure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * A use of a JSON Structure type. Alternatives remain in source order because
 * Core draft-04 requires the first matching union member to win.
 */
public final class JsonStructureTypeUse {
    private final List<JsonStructureTypeAlternative> alternatives;
    private final Integer precision;
    private final Integer scale;
    private final Integer maxLength;
    private final String description;
    private final List<Object> examples;
    private final List<Object> enumValues;
    private final boolean hasConst;
    private final Object constValue;
    private final String contentEncoding;
    private final String contentCompression;
    private final String contentMediaType;

    private JsonStructureTypeUse(
            List<JsonStructureTypeAlternative> alternatives,
            Integer precision,
            Integer scale,
            Integer maxLength,
            String description,
            List<Object> examples,
            List<Object> enumValues,
            boolean hasConst,
            Object constValue,
            String contentEncoding,
            String contentCompression,
            String contentMediaType) {
        this.alternatives = List.copyOf(alternatives);
        this.precision = precision;
        this.scale = scale;
        this.maxLength = maxLength;
        this.description = description;
        this.examples = Collections.unmodifiableList(new ArrayList<>(examples));
        this.enumValues = Collections.unmodifiableList(new ArrayList<>(enumValues));
        this.hasConst = hasConst;
        this.constValue = constValue;
        this.contentEncoding = contentEncoding;
        this.contentCompression = contentCompression;
        this.contentMediaType = contentMediaType;
    }

    public static JsonStructureTypeUse of(
            List<JsonStructureTypeAlternative> alternatives,
            Integer precision,
            Integer scale,
            Integer maxLength,
            String description,
            List<Object> examples) {
        if (alternatives.isEmpty()) {
            throw new IllegalArgumentException("A type use needs at least one alternative");
        }
        return new JsonStructureTypeUse(
                alternatives, precision, scale, maxLength, description, examples,
                List.of(), false, null, null, null, null);
    }

    public static JsonStructureTypeUse primitives(List<JsonStructureTypeKind> alternatives) {
        return primitives(alternatives, null, null, null);
    }

    public static JsonStructureTypeUse primitives(
            List<JsonStructureTypeKind> alternatives,
            Integer precision,
            Integer scale,
            Integer maxLength) {
        List<JsonStructureTypeAlternative> ordered = alternatives.stream()
                .map(kind -> JsonStructureTypeAlternative.primitive(kind, canonicalName(kind)))
                .collect(Collectors.toList());
        return of(ordered, precision, scale, maxLength, null, List.of());
    }

    public static JsonStructureTypeUse reference(QualifiedTypeName reference) {
        return reference(reference, null, null, List.of());
    }

    public static JsonStructureTypeUse reference(
            QualifiedTypeName reference,
            String referenceDescription,
            String description,
            List<Object> examples) {
        return of(
                List.of(JsonStructureTypeAlternative.reference(
                        Objects.requireNonNull(reference), referenceDescription)),
                null, null, null, description, examples);
    }

    public static JsonStructureTypeUse union(
            List<JsonStructureTypeKind> primitiveAlternatives,
            List<QualifiedTypeName> referenceAlternatives,
            Integer precision,
            Integer scale,
            Integer maxLength) {
        List<JsonStructureTypeAlternative> ordered = new ArrayList<>();
        primitiveAlternatives.forEach(kind -> ordered.add(
                JsonStructureTypeAlternative.primitive(kind, canonicalName(kind))));
        referenceAlternatives.forEach(reference -> ordered.add(
                JsonStructureTypeAlternative.reference(reference, null)));
        return of(ordered, precision, scale, maxLength, null, List.of());
    }

    public JsonStructureTypeUse withValueConstraints(
            List<Object> values, boolean constPresent, Object constant) {
        return copy(values, constPresent, constant, contentEncoding, contentCompression, contentMediaType);
    }

    public JsonStructureTypeUse withBinaryAnnotations(
            String encoding, String compression, String mediaType) {
        return copy(enumValues, hasConst, constValue, encoding, compression, mediaType);
    }

    private JsonStructureTypeUse copy(
            List<Object> values,
            boolean constPresent,
            Object constant,
            String encoding,
            String compression,
            String mediaType) {
        return new JsonStructureTypeUse(
                alternatives, precision, scale, maxLength, description, examples,
                values, constPresent, constant, encoding, compression, mediaType);
    }

    public List<JsonStructureTypeAlternative> getAlternatives() {
        return alternatives;
    }

    public List<JsonStructureTypeKind> getPrimitiveAlternatives() {
        return alternatives.stream()
                .filter(JsonStructureTypeAlternative::isPrimitive)
                .map(JsonStructureTypeAlternative::getPrimitiveKind)
                .collect(Collectors.toUnmodifiableList());
    }

    public List<QualifiedTypeName> getReferenceAlternatives() {
        return alternatives.stream()
                .filter(JsonStructureTypeAlternative::isReference)
                .map(JsonStructureTypeAlternative::getReference)
                .collect(Collectors.toUnmodifiableList());
    }

    public QualifiedTypeName getReference() {
        return isReference() ? alternatives.get(0).getReference() : null;
    }

    public String getReferenceDescription() {
        return isReference() ? alternatives.get(0).getReferenceDescription() : null;
    }

    public boolean isReference() {
        return alternatives.size() == 1 && alternatives.get(0).isReference();
    }

    public boolean isUnion() {
        return alternatives.size() > 1;
    }

    public boolean hasReferences() {
        return alternatives.stream().anyMatch(JsonStructureTypeAlternative::isReference);
    }

    public boolean isNullable() {
        return getPrimitiveAlternatives().contains(JsonStructureTypeKind.NULL);
    }

    public List<String> getOrderedTypeIdentities() {
        return alternatives.stream()
                .map(JsonStructureTypeAlternative::identity)
                .collect(Collectors.toUnmodifiableList());
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

    public String getDescription() {
        return description;
    }

    public List<Object> getExamples() {
        return examples;
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


    private static String canonicalName(JsonStructureTypeKind kind) {
        return kind == JsonStructureTypeKind.JSON_POINTER
                ? "jsonpointer"
                : kind.name().toLowerCase(java.util.Locale.ROOT);
    }
}
