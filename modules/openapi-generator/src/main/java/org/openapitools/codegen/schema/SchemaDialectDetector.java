/*
 * Copyright 2026 OpenAPI-Generator Contributors (https://openapi-generator.tech)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.openapitools.codegen.schema;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SchemaDialectDetector {
    public static final String JSON_STRUCTURE_CORE = "https://json-structure.org/meta/core/v0/#";
    public static final String JSON_STRUCTURE_EXTENDED = "https://json-structure.org/meta/extended/v0/#";
    public static final String JSON_STRUCTURE_VALIDATION = "https://json-structure.org/meta/validation/v0/#";
    public static final String OAS_31_BASE = "https://spec.openapis.org/oas/3.1/dialect/base";
    public static final String JSON_SCHEMA_2020_12 = "https://json-schema.org/draft/2020-12/schema";

    private SchemaDialectDetector() {
    }

    public static Map<String, SchemaDialect> componentSchemaDialects(JsonNode document) {
        if (document == null) {
            return Collections.emptyMap();
        }

        String documentDialect = textValue(document.get("jsonSchemaDialect"));
        JsonNode schemas = document.path("components").path("schemas");
        if (!schemas.isObject()) {
            return Collections.emptyMap();
        }

        Map<String, SchemaDialect> result = new LinkedHashMap<>();
        schemas.fields().forEachRemaining(entry -> {
            JsonNode schema = entry.getValue();
            if (schema.isObject() && schema.has("$ref")) {
                result.put(entry.getKey(), SchemaDialect.OAS);
            } else {
                String schemaDialect = schema.isObject() ? textValue(schema.get("$schema")) : null;
                result.put(entry.getKey(), fromUri(schemaDialect != null ? schemaDialect : documentDialect));
            }
        });
        return Collections.unmodifiableMap(result);
    }

    public static SchemaDialect fromUri(String dialectUri) {
        if (dialectUri == null || dialectUri.isBlank()) {
            return SchemaDialect.OAS;
        }
        switch (dialectUri) {
            case JSON_STRUCTURE_CORE:
                return SchemaDialect.JSON_STRUCTURE_CORE;
            case JSON_STRUCTURE_EXTENDED:
                return SchemaDialect.JSON_STRUCTURE_EXTENDED;
            case JSON_STRUCTURE_VALIDATION:
                return SchemaDialect.JSON_STRUCTURE_VALIDATION;
            case OAS_31_BASE:
            case JSON_SCHEMA_2020_12:
                return SchemaDialect.OAS;
            default:
                return SchemaDialect.UNKNOWN;
        }
    }

    public static boolean containsJsonStructureSchemas(JsonNode document) {
        return componentSchemaDialects(document).values().stream().anyMatch(SchemaDialect::isJsonStructure);
    }

    public static boolean containsInlineJsonStructureSchemas(JsonNode document) {
        if (document == null) {
            return false;
        }
        return containsInlineJsonStructureSchemas(
                document,
                textValue(document.get("jsonSchemaDialect")),
                false,
                false);
    }

    private static boolean containsInlineJsonStructureSchemas(
            JsonNode node,
            String documentDialect,
            boolean schemaValue,
            boolean componentsObject) {
        if (node == null) {
            return false;
        }
        if (schemaValue && node.isObject() && !node.has("$ref")) {
            String dialect = textValue(node.get("$schema"));
            if (fromUri(dialect != null ? dialect : documentDialect).isJsonStructure()) {
                return true;
            }
        }
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> entry : iterable(node.fields())) {
                if (componentsObject && "schemas".equals(entry.getKey())) {
                    continue;
                }
                if (containsInlineJsonStructureSchemas(
                        entry.getValue(),
                        documentDialect,
                        "schema".equals(entry.getKey()),
                        "components".equals(entry.getKey()))) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                if (containsInlineJsonStructureSchemas(child, documentDialect, false, false)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static <T> Iterable<T> iterable(java.util.Iterator<T> iterator) {
        return () -> iterator;
    }

    private static String textValue(JsonNode node) {
        return node != null && node.isTextual() ? node.textValue() : null;
    }
}
