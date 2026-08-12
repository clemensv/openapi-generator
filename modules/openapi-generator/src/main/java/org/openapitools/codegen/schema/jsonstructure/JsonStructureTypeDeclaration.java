/*
 * Copyright 2026 OpenAPI-Generator Contributors (https://openapi-generator.tech)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.openapitools.codegen.schema.jsonstructure;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class JsonStructureTypeDeclaration {
    private final QualifiedTypeName name;
    private final QualifiedTypeName origin;
    private final JsonStructureTypeKind kind;
    private final JsonWireKind wireKind;
    private final boolean abstractType;
    private final boolean synthetic;
    private final Map<String, JsonStructureTypeUse> properties;
    private final List<List<String>> requiredAlternatives;
    private final List<QualifiedTypeName> bases;
    private final Map<String, JsonStructureTypeUse> choices;
    private final String selector;
    private final JsonStructureTypeUse items;
    private final JsonStructureTypeUse values;
    private final List<String> tupleOrder;
    private final Integer precision;
    private final Integer scale;
    private final Integer maxLength;
    private final JsonStructureTypeUse declaredType;
    private final Boolean additionalPropertiesAllowed;
    private final JsonStructureTypeUse additionalPropertiesType;

    public JsonStructureTypeDeclaration(
            QualifiedTypeName name,
            QualifiedTypeName origin,
            JsonStructureTypeKind kind,
            JsonWireKind wireKind,
            boolean abstractType,
            boolean synthetic,
            Map<String, JsonStructureTypeUse> properties,
            List<List<String>> requiredAlternatives,
            List<QualifiedTypeName> bases,
            Map<String, JsonStructureTypeUse> choices,
            String selector,
            JsonStructureTypeUse items,
            JsonStructureTypeUse values,
            List<String> tupleOrder,
            Integer precision,
            Integer scale,
            Integer maxLength,
            JsonStructureTypeUse declaredType,
            Boolean additionalPropertiesAllowed,
            JsonStructureTypeUse additionalPropertiesType) {
        this.name = Objects.requireNonNull(name);
        this.origin = Objects.requireNonNull(origin);
        this.kind = Objects.requireNonNull(kind);
        this.wireKind = Objects.requireNonNull(wireKind);
        this.abstractType = abstractType;
        this.synthetic = synthetic;
        this.properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
        this.requiredAlternatives = List.copyOf(requiredAlternatives);
        this.bases = List.copyOf(bases);
        this.choices = Collections.unmodifiableMap(new LinkedHashMap<>(choices));
        this.selector = selector;
        this.items = items;
        this.values = values;
        this.tupleOrder = List.copyOf(tupleOrder);
        this.precision = precision;
        this.scale = scale;
        this.maxLength = maxLength;
        this.declaredType = declaredType;
        this.additionalPropertiesAllowed = additionalPropertiesAllowed;
        this.additionalPropertiesType = additionalPropertiesType;
    }

    public QualifiedTypeName getName() {
        return name;
    }

    public QualifiedTypeName getOrigin() {
        return origin;
    }

    public JsonStructureTypeKind getKind() {
        return kind;
    }

    public JsonWireKind getWireKind() {
        return wireKind;
    }

    public boolean isAbstractType() {
        return abstractType;
    }

    public boolean isSynthetic() {
        return synthetic;
    }

    public Map<String, JsonStructureTypeUse> getProperties() {
        return properties;
    }

    public List<List<String>> getRequiredAlternatives() {
        return requiredAlternatives;
    }

    public List<QualifiedTypeName> getBases() {
        return bases;
    }

    public Map<String, JsonStructureTypeUse> getChoices() {
        return choices;
    }

    public String getSelector() {
        return selector;
    }

    public JsonStructureTypeUse getItems() {
        return items;
    }

    public JsonStructureTypeUse getValues() {
        return values;
    }

    public List<String> getTupleOrder() {
        return tupleOrder;
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

    public JsonStructureTypeUse getDeclaredType() {
        return declaredType;
    }

    public Boolean getAdditionalPropertiesAllowed() {
        return additionalPropertiesAllowed;
    }

    public JsonStructureTypeUse getAdditionalPropertiesType() {
        return additionalPropertiesType;
    }
}
