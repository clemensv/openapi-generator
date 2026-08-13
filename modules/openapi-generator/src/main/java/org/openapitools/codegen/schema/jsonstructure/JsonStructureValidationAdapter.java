/*
 * Copyright 2026 OpenAPI-Generator Contributors (https://openapi-generator.tech)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.openapitools.codegen.schema.jsonstructure;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * Boundary between generator-specific ingestion and the JSON Structure SDK's
 * general-purpose schema validation.
 */
public interface JsonStructureValidationAdapter {
    /**
     * Validates materialized JSON Structure resources before generator ingestion.
     *
     * @param resources component name to standalone JSON Structure resource
     */
    void validate(Map<String, JsonNode> resources);

    /**
     * Returns whether a concrete SDK validator is available.
     *
     * @return true when validation is active
     */
    boolean isAvailable();

    /**
     * Discovers the official Java SDK without creating a hard Java-version dependency.
     *
     * @return an SDK-backed adapter, or an explicitly unavailable adapter
     */
    static JsonStructureValidationAdapter discoverSdk() {
        return ReflectiveJsonStructureSdkValidationAdapter.discover();
    }
}
