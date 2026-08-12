/*
 * Copyright 2026 OpenAPI-Generator Contributors (https://openapi-generator.tech)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.openapitools.codegen.schema.jsonstructure;

import io.swagger.v3.core.util.Yaml;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import org.openapitools.codegen.CodegenModel;
import org.openapitools.codegen.CodegenProperty;
import org.openapitools.codegen.DefaultCodegen;
import org.testng.annotations.Test;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

public class JsonStructureCodegenTest {
    @Test
    public void exposesLogicalAndWireTypesToGenerators() throws Exception {
        String source = "openapi: 3.1.0\n"
                + "components:\n"
                + "  schemas:\n"
                + "    Measurement:\n"
                + "      $schema: https://json-structure.org/meta/core/v0/#\n"
                + "      $id: https://api.example.com/schemas/measurement\n"
                + "      name: Measurement\n"
                + "      type: object\n"
                + "      required: [sequence, reading]\n"
                + "      properties:\n"
                + "        sequence: { type: int64 }\n"
                + "        reading: { type: decimal, precision: 18, scale: 4 }\n"
                + "        tags:\n"
                + "          type: set\n"
                + "          items: { type: string }\n";

        JsonStructureTypeGraph graph = new JsonStructureResolver().resolve(Yaml.mapper().readTree(source));
        JsonStructureModelCatalog catalog = new JsonStructureModelCatalog(graph);
        DefaultCodegen codegen = new DefaultCodegen();
        codegen.setJsonStructureCompatibilityMode(true);
        codegen.setOpenAPI(new OpenAPI().openapi("3.1.0").components(new Components()));
        codegen.prepareJsonStructureTypes(graph, catalog);

        CodegenModel model = codegen.fromJsonStructureType(
                "Measurement", catalog.declaration("Measurement"), graph, catalog);
        Map<String, CodegenProperty> properties = model.getVars().stream()
                .collect(Collectors.toMap(CodegenProperty::getBaseName, value -> value));

        assertEquals(model.getSchemaDialect(), "json-structure");
        assertEquals(model.getLogicalType(), "object");
        assertEquals(model.getWireType(), "object");
        assertEquals(properties.get("sequence").getLogicalType(), "int64");
        assertEquals(properties.get("sequence").getWireType(), "string");
        assertEquals(properties.get("sequence").dataType, "Long");
        assertEquals(properties.get("reading").dataType, "BigDecimal");
        assertTrue(properties.get("tags").isContainer);
        assertEquals(properties.get("tags").getContainerType(), "set");
    }

    @Test
    public void requiresExplicitCompatibilityModeForLossyWireMappings() throws Exception {
        String source = "openapi: 3.1.0\n"
                + "components:\n"
                + "  schemas:\n"
                + "    Measurement:\n"
                + "      $schema: https://json-structure.org/meta/core/v0/#\n"
                + "      $id: https://api.example.com/measurement\n"
                + "      name: Measurement\n"
                + "      type: object\n"
                + "      properties:\n"
                + "        sequence: { type: int64 }\n";
        JsonStructureTypeGraph graph = new JsonStructureResolver().resolve(Yaml.mapper().readTree(source));
        JsonStructureModelCatalog catalog = new JsonStructureModelCatalog(graph);
        DefaultCodegen codegen = new DefaultCodegen();
        codegen.setOpenAPI(new OpenAPI().openapi("3.1.0").components(new Components()));

        assertThrows(
                IllegalArgumentException.class,
                () -> codegen.prepareJsonStructureTypes(graph, catalog));
    }

    @Test
    public void avoidsMixedDialectModelNameCollisions() throws Exception {
        String source = "openapi: 3.1.0\n"
                + "components:\n"
                + "  schemas:\n"
                + "    Types:\n"
                + "      $schema: https://json-structure.org/meta/core/v0/#\n"
                + "      $id: https://api.example.com/types\n"
                + "      definitions:\n"
                + "        Address:\n"
                + "          name: Address\n"
                + "          type: object\n"
                + "          properties:\n"
                + "            street: { type: string }\n"
                + "    Types_Address:\n"
                + "      type: object\n"
                + "      properties:\n"
                + "        legacy: { type: string }\n";

        JsonStructureTypeGraph graph = new JsonStructureResolver().resolve(Yaml.mapper().readTree(source));
        JsonStructureModelCatalog catalog = new JsonStructureModelCatalog(graph, Set.of("Types", "Types_Address"));
        QualifiedTypeName address = new QualifiedTypeName(
                java.net.URI.create("https://api.example.com/types"),
                java.util.List.of(),
                "Address");

        assertEquals(catalog.modelName(address), "Types_Address_2");
    }

    @Test
    public void materializesInheritedPropertiesWithoutOasAllOf() throws Exception {
        String source = "openapi: 3.1.0\n"
                + "components:\n"
                + "  schemas:\n"
                + "    Event:\n"
                + "      $schema: https://json-structure.org/meta/core/v0/#\n"
                + "      $id: https://api.example.com/event\n"
                + "      $root: '#/definitions/Created'\n"
                + "      definitions:\n"
                + "        Base:\n"
                + "          name: Base\n"
                + "          type: object\n"
                + "          abstract: true\n"
                + "          required: [id]\n"
                + "          properties:\n"
                + "            id: { type: uuid }\n"
                + "        Created:\n"
                + "          name: Created\n"
                + "          type: object\n"
                + "          $extends: '#/definitions/Base'\n"
                + "          properties:\n"
                + "            value: { type: string }\n";

        JsonStructureTypeGraph graph = new JsonStructureResolver().resolve(Yaml.mapper().readTree(source));
        JsonStructureModelCatalog catalog = new JsonStructureModelCatalog(graph);
        DefaultCodegen codegen = new DefaultCodegen();
        codegen.setJsonStructureCompatibilityMode(true);
        codegen.setOpenAPI(new OpenAPI().openapi("3.1.0").components(new Components()));
        codegen.prepareJsonStructureTypes(graph, catalog);

        CodegenModel model = codegen.fromJsonStructureType(
                "Event", catalog.declaration("Event"), graph, catalog);
        Map<String, CodegenProperty> properties = model.getVars().stream()
                .collect(Collectors.toMap(CodegenProperty::getBaseName, value -> value));

        assertTrue(properties.containsKey("id"));
        assertTrue(properties.get("id").required);
        assertEquals(properties.get("id").getLogicalType(), "uuid");
        assertTrue(model.getParentModel() == null);
    }

    @Test
    public void preservesBinaryAnnotationsAndRejectsUnsupportedWireBehavior() throws Exception {
        String source = "openapi: 3.1.0\n"
                + "components:\n"
                + "  schemas:\n"
                + "    Document:\n"
                + "      $schema: https://json-structure.org/meta/core/v0/#\n"
                + "      $id: https://api.example.com/document\n"
                + "      name: Document\n"
                + "      type: object\n"
                + "      properties:\n"
                + "        data:\n"
                + "          type: binary\n"
                + "          contentEncoding: base32\n"
                + "          contentCompression: gzip\n"
                + "          contentMediaType: application/octet-stream\n";
        JsonStructureTypeGraph graph = new JsonStructureResolver().resolve(Yaml.mapper().readTree(source));
        JsonStructureModelCatalog catalog = new JsonStructureModelCatalog(graph);
        Schema<?> schema = new JsonStructureSchemaMapper().toSchema(
                catalog.declaration("Document"),
                graph,
                catalog);
        Schema<?> data = (Schema<?>) schema.getProperties().get("data");

        assertEquals(data.getContentEncoding(), "base32");
        assertEquals(data.getContentMediaType(), "application/octet-stream");
        assertEquals(data.getExtensions().get("x-json-structure-content-compression"), "gzip");

        DefaultCodegen strictCodegen = new DefaultCodegen();
        strictCodegen.setOpenAPI(new OpenAPI().openapi("3.1.0").components(new Components()));
        assertThrows(
                IllegalArgumentException.class,
                () -> strictCodegen.prepareJsonStructureTypes(graph, catalog));
    }

    @Test
    public void generatesReferencedSyntheticObjectDeclarations() throws Exception {
        String source = "openapi: 3.1.0\n"
                + "components:\n"
                + "  schemas:\n"
                + "    Envelope:\n"
                + "      $schema: https://json-structure.org/meta/core/v0/#\n"
                + "      $id: https://api.example.com/envelope\n"
                + "      name: Envelope\n"
                + "      type: object\n"
                + "      properties:\n"
                + "        payload:\n"
                + "          type: object\n"
                + "          name: Payload\n"
                + "          properties:\n"
                + "            value: { type: string }\n";

        JsonStructureTypeGraph graph = new JsonStructureResolver().resolve(Yaml.mapper().readTree(source));
        JsonStructureModelCatalog catalog = new JsonStructureModelCatalog(graph);

        assertTrue(catalog.modelNames().stream().anyMatch(name -> name.toLowerCase().contains("payload")));
    }
}
