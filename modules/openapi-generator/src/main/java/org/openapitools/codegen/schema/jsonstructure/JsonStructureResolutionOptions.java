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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** OpenAPI-binding context that is not carried by the OpenAPI document itself. */
public final class JsonStructureResolutionOptions {
    private URI encapsulatingEntityBaseUri;
    private URI applicationDefaultBaseUri;
    private final Map<String, SchemaDialect> verifiedCustomMetaSchemas = new LinkedHashMap<>();
    private final Map<String, Set<String>> verifiedCustomMetaSchemaOffers = new LinkedHashMap<>();
    private final Map<String, JsonNode> externalResources = new LinkedHashMap<>();

    public URI getEncapsulatingEntityBaseUri() {
        return encapsulatingEntityBaseUri;
    }

    public JsonStructureResolutionOptions setEncapsulatingEntityBaseUri(URI value) {
        this.encapsulatingEntityBaseUri = requireAbsolute(value, "encapsulating entity base URI");
        return this;
    }

    public URI getApplicationDefaultBaseUri() {
        return applicationDefaultBaseUri;
    }

    public JsonStructureResolutionOptions setApplicationDefaultBaseUri(URI value) {
        this.applicationDefaultBaseUri = requireAbsolute(value, "application default base URI");
        return this;
    }

    /**
     * Registers a custom meta-schema after the caller has resolved it offline and
     * verified which exact canonical meta-schema it imports.
     * No URI inference or network lookup is performed here.
     *
     * @param customMetaSchemaUri exact custom meta-schema URI
     * @param canonicalMetaSchemaUri exact canonical meta-schema URI
     * @return this options object
     */
    public JsonStructureResolutionOptions addVerifiedCustomMetaSchema(
            String customMetaSchemaUri, String canonicalMetaSchemaUri) {
        SchemaDialect dialect;
        if (SchemaDialectDetector.JSON_STRUCTURE_CORE
                .equals(canonicalMetaSchemaUri)) {
            dialect = SchemaDialect.JSON_STRUCTURE_CORE;
        } else if (SchemaDialectDetector.JSON_STRUCTURE_EXTENDED
                .equals(canonicalMetaSchemaUri)) {
            dialect = SchemaDialect.JSON_STRUCTURE_EXTENDED;
        } else if (SchemaDialectDetector.JSON_STRUCTURE_VALIDATION
                .equals(canonicalMetaSchemaUri)) {
            dialect = SchemaDialect.JSON_STRUCTURE_VALIDATION;
        } else {
            throw new IllegalArgumentException(
                    "Canonical mapping target must be one of the three exact JSON Structure URIs");
        }
        return addVerifiedCustomMetaSchema(customMetaSchemaUri, dialect);
    }

    public JsonStructureResolutionOptions setVerifiedCustomMetaSchemas(
            Map<String, String> mappings) {
        verifiedCustomMetaSchemas.clear();
        mappings.forEach(this::addVerifiedCustomMetaSchema);
        return this;
    }

    public JsonStructureResolutionOptions addVerifiedCustomMetaSchema(
            String customMetaSchemaUri, SchemaDialect dialect) {
        requireAbsolute(URI.create(customMetaSchemaUri), "custom meta-schema URI");
        if (dialect == null || !dialect.isJsonStructure()) {
            throw new IllegalArgumentException(
                    "A custom JSON Structure meta-schema must extend a canonical JSON Structure dialect");
        }
        verifiedCustomMetaSchemas.put(customMetaSchemaUri, dialect);
        return this;
    }

    public Map<String, SchemaDialect> getVerifiedCustomMetaSchemas() {
        return Collections.unmodifiableMap(verifiedCustomMetaSchemas);
    }

    public JsonStructureResolutionOptions addVerifiedCustomMetaSchemaOffer(
            String customMetaSchemaUri, String addInName) {
        if (!verifiedCustomMetaSchemas.containsKey(customMetaSchemaUri)) {
            throw new IllegalArgumentException(
                    "Register the custom meta-schema before registering its offers");
        }
        if (addInName == null || addInName.isEmpty()) {
            throw new IllegalArgumentException("Custom add-in name must not be empty");
        }
        verifiedCustomMetaSchemaOffers
                .computeIfAbsent(customMetaSchemaUri, ignored -> new LinkedHashSet<>())
                .add(addInName);
        return this;
    }

    public Map<String, Set<String>> getVerifiedCustomMetaSchemaOffers() {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        verifiedCustomMetaSchemaOffers.forEach(
                (uri, offers) -> result.put(uri, Collections.unmodifiableSet(offers)));
        return Collections.unmodifiableMap(result);
    }

    public JsonStructureResolutionOptions addExternalResource(JsonNode resource) {
        if (resource == null
                || !resource.isObject()
                || !resource.path("$id").isTextual()
                || !resource.path("$schema").isTextual()) {
            throw new IllegalArgumentException(
                    "An external JSON Structure resource must declare textual $id and $schema");
        }
        URI id = requireAbsolute(
                URI.create(resource.path("$id").textValue()), "external resource $id");
        if (externalResources.putIfAbsent(id.toString(), resource.deepCopy()) != null) {
            throw new IllegalArgumentException(
                    "Duplicate external JSON Structure $id: " + id);
        }
        return this;
    }

    public Map<String, JsonNode> getExternalResources() {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        externalResources.forEach((id, resource) -> result.put(id, resource.deepCopy()));
        return Collections.unmodifiableMap(result);
    }

    private URI requireAbsolute(URI value, String label) {
        if (value != null && !value.isAbsolute()) {
            throw new IllegalArgumentException(label + " must be absolute: " + value);
        }
        return value;
    }
}
