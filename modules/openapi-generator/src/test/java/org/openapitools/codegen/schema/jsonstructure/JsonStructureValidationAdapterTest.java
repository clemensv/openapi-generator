/*
 * Copyright 2026 OpenAPI-Generator Contributors (https://openapi-generator.tech)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.openapitools.codegen.schema.jsonstructure;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.core.util.Yaml;
import io.swagger.v3.oas.models.media.Schema;
import org.testng.annotations.Test;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.expectThrows;
import static org.testng.Assert.assertTrue;

public class JsonStructureValidationAdapterTest {
    @Test
    public void materializesEmbeddedResourcesAndDelegatesValidationBeforeResolution() throws Exception {
        String source = "openapi: 3.1.0\n"
                + "jsonSchemaDialect: https://json-structure.org/meta/core/v0/#\n"
                + "components:\n"
                + "  schemas:\n"
                + "    Event:\n"
                + "      name: Event\n"
                + "      type: object\n"
                + "      properties:\n"
                + "        timestamp: { type: datetime, const: not-an-rfc3339-value }\n";
        RecordingAdapter adapter = new RecordingAdapter();
        JsonStructureTypeGraph graph = new JsonStructureResolver(adapter).resolve(
                Yaml.mapper().readTree(source),
                URI.create("https://example.com/openapi.yaml"));

        assertTrue(adapter.isAvailable());
        assertTrue(graph.isSdkValidated());
        assertEquals(adapter.resources.size(), 1);
        JsonNode materialized = adapter.resources.get("Event");
        assertEquals(
                materialized.path("$schema").textValue(),
                "https://json-structure.org/meta/core/v0/#");
        assertEquals(
                materialized.path("$id").textValue(),
                "https://example.com/openapi.yaml#/components/schemas/Event");
        JsonStructureModelCatalog catalog = new JsonStructureModelCatalog(graph);
        Schema<?> mapped = new JsonStructureSchemaMapper().toSchema(
                catalog.declaration("Event"), graph, catalog);
        assertEquals(
                mapped.getExtensions().get(JsonStructureSchemaMapper.X_SDK_VALIDATED),
                Boolean.TRUE);

        assertEquals(
                graph.getComponentRoots().get("Event").getResourceId().toString(),
                materialized.path("$id").textValue());
        assertEquals(
                graph.getDeclarations().get(graph.getComponentRoots().get("Event"))
                        .getProperties().get("timestamp").getConstValue(),
                "not-an-rfc3339-value");
    }

    @Test
    public void propagatesSdkValidationFailuresWithoutFallbackValidation() throws Exception {
        String source = "openapi: 3.1.0\n"
                + "components:\n"
                + "  schemas:\n"
                + "    Event:\n"
                + "      $schema: https://json-structure.org/meta/core/v0/#\n"
                + "      $id: https://example.com/event\n"
                + "      name: Event\n"
                + "      type: string\n";
        JsonStructureValidationAdapter rejecting = new JsonStructureValidationAdapter() {
            @Override
            public void validate(Map<String, JsonNode> resources) {
                throw new JsonStructureResolutionException("SDK contract failure");
            }

            @Override
            public boolean isAvailable() {
                return true;
            }
        };

        JsonStructureResolutionException error = expectThrows(
                JsonStructureResolutionException.class,
                () -> new JsonStructureResolver(rejecting).resolve(Yaml.mapper().readTree(source)));
        assertEquals(error.getMessage(), "SDK contract failure");
    }

    private static final class RecordingAdapter implements JsonStructureValidationAdapter {
        private final Map<String, JsonNode> resources = new LinkedHashMap<>();

        @Override
        public void validate(Map<String, JsonNode> resources) {
            resources.forEach((name, resource) -> this.resources.put(name, resource.deepCopy()));
        }

        @Override
        public boolean isAvailable() {
            return true;
        }
    }
}
