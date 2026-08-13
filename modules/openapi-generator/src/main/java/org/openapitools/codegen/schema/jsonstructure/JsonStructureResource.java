/*
 * Copyright 2026 OpenAPI-Generator Contributors (https://openapi-generator.tech)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.openapitools.codegen.schema.jsonstructure;

import org.openapitools.codegen.schema.SchemaDialect;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Document-level Core semantics for one embedded JSON Structure resource. */
public final class JsonStructureResource {
    private final String componentName;
    private final URI id;
    private final boolean explicitId;
    private final SchemaDialect dialect;
    private final String metaSchemaUri;
    private final String documentName;
    private final String description;
    private final List<Object> examples;
    private final QualifiedTypeName root;
    private final Map<String, List<QualifiedTypeName>> offers;
    private final List<String> declaredUses;
    private final List<String> effectiveUses;
    private final List<QualifiedTypeName> localUsePointers;

    public JsonStructureResource(
            String componentName,
            URI id,
            boolean explicitId,
            SchemaDialect dialect,
            String metaSchemaUri,
            String documentName,
            String description,
            List<Object> examples,
            QualifiedTypeName root,
            Map<String, List<QualifiedTypeName>> offers,
            List<String> declaredUses,
            List<String> effectiveUses,
            List<QualifiedTypeName> localUsePointers) {
        this.componentName = Objects.requireNonNull(componentName);
        this.id = Objects.requireNonNull(id);
        this.explicitId = explicitId;
        this.dialect = Objects.requireNonNull(dialect);
        this.metaSchemaUri = Objects.requireNonNull(metaSchemaUri);
        this.documentName = documentName;
        this.description = description;
        this.examples = Collections.unmodifiableList(new ArrayList<>(examples));
        this.root = root;
        Map<String, List<QualifiedTypeName>> copied = new LinkedHashMap<>();
        offers.forEach((name, declarations) -> copied.put(name, List.copyOf(declarations)));
        this.offers = Collections.unmodifiableMap(copied);
        this.declaredUses = List.copyOf(declaredUses);
        this.effectiveUses = List.copyOf(effectiveUses);
        this.localUsePointers = List.copyOf(localUsePointers);
    }

    public String getComponentName() {
        return componentName;
    }

    public URI getId() {
        return id;
    }

    public boolean isExplicitId() {
        return explicitId;
    }

    public SchemaDialect getDialect() {
        return dialect;
    }

    public String getMetaSchemaUri() {
        return metaSchemaUri;
    }

    public String getDocumentName() {
        return documentName;
    }

    public String getDescription() {
        return description;
    }

    public List<Object> getExamples() {
        return examples;
    }

    public QualifiedTypeName getRoot() {
        return root;
    }

    public Map<String, List<QualifiedTypeName>> getOffers() {
        return offers;
    }

    public List<String> getDeclaredUses() {
        return declaredUses;
    }

    public List<String> getEffectiveUses() {
        return effectiveUses;
    }

    public List<QualifiedTypeName> getLocalUsePointers() {
        return localUsePointers;
    }
}
