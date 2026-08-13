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

import java.util.List;
import java.util.Locale;
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
                + "      $schema: https://json-structure.org/meta/extended/v0/#\n"
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
        assertEquals(
                model.getJsonStructureMetaSchemaUri(),
                "https://json-structure.org/meta/extended/v0/#");
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
                + "      $schema: https://json-structure.org/meta/extended/v0/#\n"
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
        assertEquals(
                model.getJsonStructureBaseTypes(),
                List.of("https://api.example.com/event#Base"));
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

        assertTrue(catalog.modelNames().stream()
                .anyMatch(name -> name.toLowerCase(Locale.ROOT).contains("payload")));
    }

    @Test
    public void exposesEffectiveNamespacesAndOriginalIdentitiesToGenerators() throws Exception {
        String source = "openapi: 3.1.0\n"
                + "components:\n"
                + "  schemas:\n"
                + "    Shared:\n"
                + "      $schema: https://json-structure.org/meta/extended/v0/#\n"
                + "      $id: https://api.example.com/shared\n"
                + "      definitions:\n"
                + "        Geometry:\n"
                + "          Point:\n"
                + "            name: Point\n"
                + "            type: object\n"
                + "            properties:\n"
                + "              x: { type: double }\n"
                + "    Consumer:\n"
                + "      $schema: https://json-structure.org/meta/extended/v0/#\n"
                + "      $id: https://api.example.com/consumer\n"
                + "      $root: '#/definitions/Envelope'\n"
                + "      definitions:\n"
                + "        Common:\n"
                + "          $importdefs: https://api.example.com/shared\n"
                + "        Envelope:\n"
                + "          name: Envelope\n"
                + "          type: object\n"
                + "          properties:\n"
                + "            point:\n"
                + "              type: { $ref: '#/definitions/Common/Geometry/Point' }\n";

        JsonStructureTypeGraph graph = new JsonStructureResolver().resolve(Yaml.mapper().readTree(source));
        JsonStructureModelCatalog catalog = new JsonStructureModelCatalog(graph);
        DefaultCodegen codegen = new DefaultCodegen();
        codegen.setOpenAPI(new OpenAPI().openapi("3.1.0").components(new Components()));
        codegen.prepareJsonStructureTypes(graph, catalog);

        String pointModelName = "Consumer_Common_Geometry_Point";
        CodegenModel pointModel = codegen.fromJsonStructureType(
                pointModelName, catalog.declaration(pointModelName), graph, catalog);
        CodegenModel consumerModel = codegen.fromJsonStructureType(
                "Consumer", catalog.declaration("Consumer"), graph, catalog);
        CodegenProperty pointProperty = consumerModel.getVars().stream()
                .filter(property -> "point".equals(property.getBaseName()))
                .findFirst()
                .orElseThrow();

        assertEquals(pointModel.getSchemaResourceId(), "https://api.example.com/consumer");
        assertEquals(pointModel.getSchemaSourceId(), "https://api.example.com/shared");
        assertEquals(pointModel.getSchemaNamespace(), "Common.Geometry");
        assertEquals(
                pointModel.getSchemaQualifiedName(),
                "https://api.example.com/consumer#Common.Geometry.Point");
        assertEquals(
                pointModel.getVendorExtensions().get(JsonStructureSchemaMapper.X_ORIGIN),
                "https://api.example.com/shared#Geometry.Point");

        assertEquals(pointProperty.getSchemaResourceId(), "https://api.example.com/consumer");
        assertEquals(pointProperty.getSchemaSourceId(), "https://api.example.com/shared");
        assertEquals(pointProperty.getSchemaNamespace(), "Common.Geometry");
        assertEquals(
                pointProperty.getSchemaQualifiedName(),
                "https://api.example.com/consumer#Common.Geometry.Point");
        assertEquals(
                pointProperty.getVendorExtensions().get(JsonStructureSchemaMapper.X_ORIGIN),
                "https://api.example.com/shared#Geometry.Point");
    }

    @Test
    public void preservesCoreUnionDocumentationAndAddInsThroughCodegen() throws Exception {
        String source = "openapi: 3.1.0\n"
                + "components:\n"
                + "  schemas:\n"
                + "    Envelope:\n"
                + "      $schema: https://json-structure.org/meta/extended/v0/#\n"
                + "      $id: https://api.example.com/envelope\n"
                + "      $uses: [JSONStructureUnits]\n"
                + "      name: EnvelopeDocument\n"
                + "      $root: '#/definitions/Envelope'\n"
                + "      description: Envelope resource\n"
                + "      examples: [{ amount: '12.50' }]\n"
                + "      $offers: { Audit: '#/definitions/Audit' }\n"
                + "      definitions:\n"
                + "        Record:\n"
                + "          type: object\n"
                + "          properties: { id: { type: uuid } }\n"
                + "        Envelope:\n"
                + "          type: object\n"
                + "          additionalProperties: false\n"
                + "          properties:\n"
                + "            amount:\n"
                + "              type: decimal\n"
                + "              precision: 12\n"
                + "              scale: 2\n"
                + "              description: Exact amount\n"
                + "              examples: ['12.50', '0.00']\n"
                + "            value:\n"
                + "              type: [number, int32, 'null']\n"
                + "            status: { type: string, enum: [open, closed] }\n"
                + "            version: { type: int32, const: 1 }\n"
                + "            record:\n"
                + "              description: Outer record\n"
                + "              type:\n"
                + "                $ref: '#/definitions/Record'\n"
                + "                description: Reference context\n"
                + "        Audit:\n"
                + "          type: object\n"
                + "          abstract: true\n"
                + "          $extends: '#/definitions/Envelope'\n"
                + "          properties: { auditId: { type: uuid } }\n";

        JsonStructureTypeGraph graph = new JsonStructureResolver().resolve(Yaml.mapper().readTree(source));
        JsonStructureModelCatalog catalog = new JsonStructureModelCatalog(graph);
        DefaultCodegen codegen = new DefaultCodegen();
        codegen.setJsonStructureCompatibilityMode(true);
        codegen.setOpenAPI(new OpenAPI().openapi("3.1.0").components(new Components()));
        codegen.prepareJsonStructureTypes(graph, catalog);

        CodegenModel model = codegen.fromJsonStructureType(
                "Envelope", catalog.declaration("Envelope"), graph, catalog);
        Map<String, CodegenProperty> properties = model.getVars().stream()
                .collect(Collectors.toMap(CodegenProperty::getBaseName, value -> value));

        assertEquals(model.getOfferedAddIns().get("Audit").size(), 1);
        assertEquals(model.getJsonStructureDocumentName(), "EnvelopeDocument");
        assertEquals(model.getJsonStructureResourceDescription(), "Envelope resource");
        assertEquals(
                model.getJsonStructureResourceExamples(),
                List.of(Map.of("amount", "12.50")));
        assertEquals(model.getUsedAddIns(), List.of("JSONStructureUnits"));
        assertTrue(model.getEffectiveAddIns().contains("JSONStructureImport"));
        assertEquals(model.getJsonStructureAdditionalPropertiesAllowed(), Boolean.FALSE);

        CodegenProperty amount = properties.get("amount");
        assertEquals(amount.getJsonStructurePrecision(), Integer.valueOf(12));
        assertEquals(amount.getJsonStructureScale(), Integer.valueOf(2));
        assertEquals(amount.getSchemaExamples(), List.of("12.50", "0.00"));
        assertEquals(amount.getDescription(), "Exact amount");

        CodegenProperty value = properties.get("value");
        assertEquals(value.getUnionTypeOrder(), List.of("number", "int32", "null"));
        assertEquals(value.getUnionMatchingSemantics(), "first-match");

        assertEquals(
                properties.get("status").getJsonStructureEnumValues(),
                List.of("open", "closed"));
        assertTrue(properties.get("version").isJsonStructureHasConst());
        assertEquals(properties.get("version").getJsonStructureConstValue(), 1);

        CodegenProperty record = properties.get("record");
        assertEquals(record.getReferenceDescription(), "Reference context");
        assertEquals(record.getDescription(), "Outer record");
    }
}
