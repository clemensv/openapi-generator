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
import java.util.function.Predicate;

public final class SchemaDialectDetector {
    public static final String JSON_STRUCTURE_CORE = "https://json-structure.org/meta/core/v0/#";
    public static final String JSON_STRUCTURE_EXTENDED = "https://json-structure.org/meta/extended/v0/#";
    public static final String JSON_STRUCTURE_VALIDATION = "https://json-structure.org/meta/validation/v0/#";
    public static final String OAS_31_BASE = "https://spec.openapis.org/oas/3.1/dialect/base";
    public static final String JSON_SCHEMA_2020_12 = "https://json-schema.org/draft/2020-12/schema";

    private SchemaDialectDetector() {
    }

    public static Map<String, SchemaDialect> componentSchemaDialects(JsonNode document) {
        return componentSchemaDialects(document, Map.of());
    }

    public static Map<String, SchemaDialect> componentSchemaDialects(
            JsonNode document, Map<String, SchemaDialect> verifiedCustomMetaSchemas) {
        if (document == null) {
            return Collections.emptyMap();
        }

        JsonNode documentDialect = document.get("jsonSchemaDialect");
        JsonNode schemas = document.path("components").path("schemas");
        if (!schemas.isObject()) {
            return Collections.emptyMap();
        }

        Map<String, SchemaDialect> result = new LinkedHashMap<>();
        schemas.fields().forEachRemaining(entry -> {
            JsonNode schema = entry.getValue();
            if (schema.isObject() && schema.has("$schema")) {
                result.put(
                        entry.getKey(),
                        fromNode(schema.get("$schema"), verifiedCustomMetaSchemas));
                return;
            }
            if (schema.isObject() && schema.has("$ref")) {
                result.put(entry.getKey(), SchemaDialect.OAS);
                return;
            }
            result.put(entry.getKey(), fromNode(documentDialect, verifiedCustomMetaSchemas));
        });
        return Collections.unmodifiableMap(result);
    }

    public static String effectiveDialectUri(JsonNode document, JsonNode schema) {
        JsonNode selected = schema != null && schema.isObject() && schema.has("$schema")
                ? schema.get("$schema")
                : document == null ? null : document.get("jsonSchemaDialect");
        return textValue(selected);
    }

    public static SchemaDialect fromUri(String dialectUri) {
        return fromUri(dialectUri, Map.of());
    }

    public static SchemaDialect fromUri(
            String dialectUri, Map<String, SchemaDialect> verifiedCustomMetaSchemas) {
        if (dialectUri == null) {
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
                return verifiedCustomMetaSchemas.getOrDefault(
                        dialectUri, SchemaDialect.UNKNOWN);
        }
    }

    public static boolean containsJsonStructureSchemas(JsonNode document) {
        return containsJsonStructureSchemas(document, Map.of());
    }

    public static boolean containsJsonStructureSchemas(
            JsonNode document, Map<String, SchemaDialect> verifiedCustomMetaSchemas) {
        return componentSchemaDialects(document, verifiedCustomMetaSchemas)
                .values().stream().anyMatch(SchemaDialect::isJsonStructure);
    }

    public static boolean containsUnknownSchemaDialects(
            JsonNode document, Map<String, SchemaDialect> verifiedCustomMetaSchemas) {
        return componentSchemaDialects(document, verifiedCustomMetaSchemas)
                .containsValue(SchemaDialect.UNKNOWN);
    }

    public static boolean containsInlineJsonStructureSchemas(JsonNode document) {
        return containsInlineJsonStructureSchemas(document, Map.of());
    }

    public static boolean containsInlineJsonStructureSchemas(
            JsonNode document, Map<String, SchemaDialect> verifiedCustomMetaSchemas) {
        return containsInlineDialect(
                document,
                document == null ? null : document.get("jsonSchemaDialect"),
                false,
                false,
                verifiedCustomMetaSchemas,
                SchemaDialect::isJsonStructure);
    }

    public static boolean containsInlineUnknownSchemaDialects(
            JsonNode document, Map<String, SchemaDialect> verifiedCustomMetaSchemas) {
        return containsInlineDialect(
                document,
                document == null ? null : document.get("jsonSchemaDialect"),
                false,
                false,
                verifiedCustomMetaSchemas,
                dialect -> dialect == SchemaDialect.UNKNOWN);
    }

    private static boolean containsInlineDialect(
            JsonNode node,
            JsonNode documentDialect,
            boolean schemaValue,
            boolean componentsObject,
            Map<String, SchemaDialect> verifiedCustomMetaSchemas,
            Predicate<SchemaDialect> predicate) {
        if (node == null) {
            return false;
        }
        if (schemaValue && node.isObject() && (node.has("$schema") || !node.has("$ref"))) {
            JsonNode selected = node.has("$schema")
                    ? node.get("$schema")
                    : documentDialect;
            if (predicate.test(fromNode(selected, verifiedCustomMetaSchemas))) {
                return true;
            }
        }
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> entry : iterable(node.fields())) {
                if (componentsObject && "schemas".equals(entry.getKey())) {
                    continue;
                }
                if (containsInlineDialect(
                        entry.getValue(),
                        documentDialect,
                        "schema".equals(entry.getKey()),
                        "components".equals(entry.getKey()),
                        verifiedCustomMetaSchemas,
                        predicate)) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                if (containsInlineDialect(
                        child, documentDialect, false, false,
                        verifiedCustomMetaSchemas, predicate)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static SchemaDialect fromNode(
            JsonNode dialect, Map<String, SchemaDialect> verifiedCustomMetaSchemas) {
        if (dialect == null) {
            return SchemaDialect.OAS;
        }
        if (!dialect.isTextual()) {
            return SchemaDialect.UNKNOWN;
        }
        return fromUri(dialect.textValue(), verifiedCustomMetaSchemas);
    }

    private static <T> Iterable<T> iterable(java.util.Iterator<T> iterator) {
        return () -> iterator;
    }

    private static String textValue(JsonNode node) {
        return node != null && node.isTextual() ? node.textValue() : null;
    }
}
