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

import io.swagger.v3.core.util.Yaml;
import org.testng.annotations.Test;

import java.util.Map;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class SchemaDialectDetectorTest {
    @Test
    public void detectsDocumentAndPerSchemaDialects() throws Exception {
        String source = "openapi: 3.1.0\n"
                + "jsonSchemaDialect: https://json-structure.org/meta/extended/v0/#\n"
                + "components:\n"
                + "  schemas:\n"
                + "    Person:\n"
                + "      type: object\n"
                + "    Legacy:\n"
                + "      $schema: https://spec.openapis.org/oas/3.1/dialect/base\n"
                + "      type: object\n";

        Map<String, SchemaDialect> dialects =
                SchemaDialectDetector.componentSchemaDialects(Yaml.mapper().readTree(source));

        assertEquals(dialects.get("Person"), SchemaDialect.JSON_STRUCTURE_EXTENDED);
        assertEquals(dialects.get("Legacy"), SchemaDialect.OAS);
        assertTrue(SchemaDialectDetector.containsJsonStructureSchemas(Yaml.mapper().readTree(source)));
    }

    @Test
    public void defaultsToOasDialect() throws Exception {
        String source = "openapi: 3.1.0\n"
                + "components:\n"
                + "  schemas:\n"
                + "    Person:\n"
                + "      type: object\n";

        Map<String, SchemaDialect> dialects =
                SchemaDialectDetector.componentSchemaDialects(Yaml.mapper().readTree(source));

        assertEquals(dialects.get("Person"), SchemaDialect.OAS);
    }

    @Test
    public void treatsComponentRefWrappersAsOasUnderJsonStructureDefault() throws Exception {
        String source = "openapi: 3.1.0\n"
                + "jsonSchemaDialect: https://json-structure.org/meta/core/v0/#\n"
                + "components:\n"
                + "  schemas:\n"
                + "    Alias:\n"
                + "      $ref: '#/components/schemas/Person'\n"
                + "    Person:\n"
                + "      name: Person\n"
                + "      type: object\n";

        Map<String, SchemaDialect> dialects =
                SchemaDialectDetector.componentSchemaDialects(Yaml.mapper().readTree(source));

        assertEquals(dialects.get("Alias"), SchemaDialect.OAS);
        assertEquals(dialects.get("Person"), SchemaDialect.JSON_STRUCTURE_CORE);
    }

    @Test
    public void givesExplicitSchemaPrecedenceOnRefWrappers() throws Exception {
        String source = "openapi: 3.1.0\n"
                + "components:\n"
                + "  schemas:\n"
                + "    ExplicitJsonStructure:\n"
                + "      $schema: https://json-structure.org/meta/core/v0/#\n"
                + "      $ref: '#/components/schemas/Person'\n"
                + "    Unknown:\n"
                + "      $schema: https://example.com/meta/unknown\n"
                + "      $ref: '#/components/schemas/Person'\n";

        Map<String, SchemaDialect> dialects =
                SchemaDialectDetector.componentSchemaDialects(Yaml.mapper().readTree(source));

        assertEquals(dialects.get("ExplicitJsonStructure"), SchemaDialect.JSON_STRUCTURE_CORE);
        assertEquals(dialects.get("Unknown"), SchemaDialect.UNKNOWN);
    }

    @Test
    public void detectsInlineJsonStructureButExcludesComponentsSchemasAndRefWrappers() throws Exception {
        String inline = "openapi: 3.1.0\n"
                + "jsonSchemaDialect: https://json-structure.org/meta/core/v0/#\n"
                + "paths:\n"
                + "  /people:\n"
                + "    post:\n"
                + "      requestBody:\n"
                + "        content:\n"
                + "          application/json:\n"
                + "            schema: { name: Person, type: object }\n";
        String componentsOnly = "openapi: 3.1.0\n"
                + "jsonSchemaDialect: https://json-structure.org/meta/core/v0/#\n"
                + "paths:\n"
                + "  /people:\n"
                + "    get:\n"
                + "      responses:\n"
                + "        '200':\n"
                + "          description: ok\n"
                + "          content:\n"
                + "            application/json:\n"
                + "              schema: { $ref: '#/components/schemas/Person' }\n"
                + "components:\n"
                + "  schemas:\n"
                + "    Person: { name: Person, type: object }\n";

        assertTrue(SchemaDialectDetector.containsInlineJsonStructureSchemas(
                Yaml.mapper().readTree(inline)));
        assertFalse(SchemaDialectDetector.containsInlineJsonStructureSchemas(
                Yaml.mapper().readTree(componentsOnly)));
    }

    @Test
    public void requiresByteExactCanonicalUrisAndExplicitCustomMappings() {
        assertEquals(
                SchemaDialectDetector.fromUri(
                        "https://json-structure.org/meta/core/v0/"),
                SchemaDialect.UNKNOWN);
        assertEquals(
                SchemaDialectDetector.fromUri(
                        "https://json-structure.org/meta/core/v0/#fragment"),
                SchemaDialect.UNKNOWN);

        Map<String, SchemaDialect> configured = Map.of(
                "https://example.com/meta/custom",
                SchemaDialect.JSON_STRUCTURE_EXTENDED);
        assertEquals(
                SchemaDialectDetector.fromUri(
                        "https://example.com/meta/custom", configured),
                SchemaDialect.JSON_STRUCTURE_EXTENDED);
        assertEquals(
                SchemaDialectDetector.fromUri(
                        "https://example.com/meta/Custom", configured),
                SchemaDialect.UNKNOWN);
    }

    @Test
    public void detectsUnknownInlineDialectWithoutTreatingItAsOas() throws Exception {
        String source = "openapi: 3.1.0\n"
                + "paths:\n"
                + "  /value:\n"
                + "    get:\n"
                + "      responses:\n"
                + "        '200':\n"
                + "          description: ok\n"
                + "          content:\n"
                + "            application/json:\n"
                + "              schema:\n"
                + "                $schema: https://example.com/meta/unknown\n"
                + "                type: object\n";

        assertTrue(SchemaDialectDetector.containsInlineUnknownSchemaDialects(
                Yaml.mapper().readTree(source), Map.of()));
        assertFalse(SchemaDialectDetector.containsInlineJsonStructureSchemas(
                Yaml.mapper().readTree(source), Map.of()));
    }

    @Test
    public void detectsExplicitInlineDialectOnRefWrappers() throws Exception {
        String source = "openapi: 3.1.0\n"
                + "paths:\n"
                + "  /value:\n"
                + "    get:\n"
                + "      responses:\n"
                + "        '200':\n"
                + "          description: ok\n"
                + "          content:\n"
                + "            application/json:\n"
                + "              schema:\n"
                + "                $schema: https://example.com/meta/unknown\n"
                + "                $ref: '#/components/schemas/Value'\n";

        assertTrue(SchemaDialectDetector.containsInlineUnknownSchemaDialects(
                Yaml.mapper().readTree(source), Map.of()));
    }
}
