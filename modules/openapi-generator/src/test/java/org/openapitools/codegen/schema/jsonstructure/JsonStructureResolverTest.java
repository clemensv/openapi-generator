/*
 * Copyright 2026 OpenAPI-Generator Contributors (https://openapi-generator.tech)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.openapitools.codegen.schema.jsonstructure;

import io.swagger.v3.core.util.Yaml;
import org.testng.annotations.Test;

import java.net.URI;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

public class JsonStructureResolverTest {
    @Test
    public void resolvesCoreTypesInheritanceChoicesAndInDescriptionImports() throws Exception {
        String source = "openapi: 3.1.0\n"
                + "components:\n"
                + "  schemas:\n"
                + "    CommonTypes:\n"
                + "      $schema: https://json-structure.org/meta/extended/v0/#\n"
                + "      $id: https://api.example.com/schemas/common\n"
                + "      definitions:\n"
                + "        BaseMessage:\n"
                + "          name: BaseMessage\n"
                + "          abstract: true\n"
                + "          type: object\n"
                + "          properties:\n"
                + "            messageId: { type: uuid }\n"
                + "            sequence: { type: uint64 }\n"
                + "        GeoPoint:\n"
                + "          name: GeoPoint\n"
                + "          type: tuple\n"
                + "          properties:\n"
                + "            lat: { type: double }\n"
                + "            long: { type: double }\n"
                + "          tuple: [lat, long]\n"
                + "    TelemetryMessage:\n"
                + "      $schema: https://json-structure.org/meta/extended/v0/#\n"
                + "      $id: https://api.example.com/schemas/telemetry\n"
                + "      $root: '#/definitions/TelemetryMessage'\n"
                + "      definitions:\n"
                + "        Common:\n"
                + "          $importdefs: https://api.example.com/schemas/common\n"
                + "        TelemetryMessage:\n"
                + "          name: TelemetryMessage\n"
                + "          type: object\n"
                + "          $extends: '#/definitions/Common/BaseMessage'\n"
                + "          properties:\n"
                + "            location:\n"
                + "              type: { $ref: '#/definitions/Common/GeoPoint' }\n"
                + "            reading: { type: decimal, precision: 18, scale: 4 }\n"
                + "        Payload:\n"
                + "          name: Payload\n"
                + "          type: choice\n"
                + "          choices:\n"
                + "            telemetry:\n"
                + "              type: { $ref: '#/definitions/TelemetryMessage' }\n";

        JsonStructureTypeGraph graph = new JsonStructureResolver().resolve(Yaml.mapper().readTree(source));

        QualifiedTypeName telemetryName = new QualifiedTypeName(
                URI.create("https://api.example.com/schemas/telemetry"),
                List.of(),
                "TelemetryMessage");
        JsonStructureTypeDeclaration telemetry = graph.getDeclarations().get(telemetryName);
        assertNotNull(telemetry);
        assertEquals(telemetry.getBases().get(0).displayName(), "Common.BaseMessage");
        assertEquals(telemetry.getProperties().get("location").getReference().displayName(), "Common.GeoPoint");
        assertEquals(telemetry.getProperties().get("reading").getPrecision(), Integer.valueOf(18));
        assertEquals(telemetry.getProperties().get("reading").getScale(), Integer.valueOf(4));

        JsonStructureTypeDeclaration importedBase = graph.getDeclarations().get(new QualifiedTypeName(
                URI.create("https://api.example.com/schemas/telemetry"),
                List.of("Common"),
                "BaseMessage"));
        assertNotNull(importedBase);
        assertEquals(importedBase.getOrigin().getResourceId(), URI.create("https://api.example.com/schemas/common"));
        assertEquals(importedBase.getProperties().get("sequence").getPrimitiveAlternatives().get(0),
                JsonStructureTypeKind.UINT64);

        JsonStructureTypeDeclaration commonBase = graph.getDeclarations().get(new QualifiedTypeName(
                URI.create("https://api.example.com/schemas/common"),
                List.of(),
                "BaseMessage"));
        assertEquals(commonBase.getWireKind(), JsonWireKind.OBJECT);

        JsonStructureTypeDeclaration payload = graph.getDeclarations().get(new QualifiedTypeName(
                URI.create("https://api.example.com/schemas/telemetry"),
                List.of(),
                "Payload"));
        assertTrue(payload.getChoices().get("telemetry").isReference());
    }

    @Test
    public void rejectsExternalImports() throws Exception {
        String source = "openapi: 3.1.0\n"
                + "components:\n"
                + "  schemas:\n"
                + "    Example:\n"
                + "      $schema: https://json-structure.org/meta/extended/v0/#\n"
                + "      $id: https://api.example.com/schemas/example\n"
                + "      type: object\n"
                + "      name: Example\n"
                + "      definitions:\n"
                + "        External:\n"
                + "          $importdefs: https://types.example.com/external\n";

        assertThrows(
                JsonStructureResolutionException.class,
                () -> new JsonStructureResolver().resolve(Yaml.mapper().readTree(source)));
    }

    @Test
    public void constructsDefaultIdFromRetrievalUri() throws Exception {
        String source = "openapi: 3.1.0\n"
                + "components:\n"
                + "  schemas:\n"
                + "    Person:\n"
                + "      $schema: https://json-structure.org/meta/core/v0/#\n"
                + "      name: Person\n"
                + "      type: object\n"
                + "      properties:\n"
                + "        id: { type: uuid }\n";

        JsonStructureTypeGraph graph = new JsonStructureResolver().resolve(
                Yaml.mapper().readTree(source),
                URI.create("https://api.example.com/openapi.yaml"));

        assertEquals(
                graph.getComponentRoots().get("Person").getResourceId(),
                URI.create("https://api.example.com/openapi.yaml#/components/schemas/Person"));
    }

    @Test
    public void usesSelfBeforeRetrievalUriForDefaultId() throws Exception {
        String source = "openapi: 3.1.0\n"
                + "$self: https://api.example.com/descriptions/service.yaml\n"
                + "components:\n"
                + "  schemas:\n"
                + "    Person:\n"
                + "      $schema: https://json-structure.org/meta/core/v0/#\n"
                + "      name: Person\n"
                + "      type: object\n"
                + "      properties:\n"
                + "        id: { type: uuid }\n";

        JsonStructureTypeGraph graph = new JsonStructureResolver().resolve(
                Yaml.mapper().readTree(source),
                URI.create("https://wrong.example.com/openapi.yaml"));

        assertEquals(
                graph.getComponentRoots().get("Person").getResourceId(),
                URI.create("https://api.example.com/descriptions/service.yaml#/components/schemas/Person"));
    }

    @Test
    public void supportsDefinitionOnlyLibrariesAndRootLevelImports() throws Exception {
        String source = "openapi: 3.1.0\n"
                + "components:\n"
                + "  schemas:\n"
                + "    Common:\n"
                + "      $schema: https://json-structure.org/meta/extended/v0/#\n"
                + "      $id: https://api.example.com/common\n"
                + "      definitions:\n"
                + "        Identifier:\n"
                + "          type: uuid\n"
                + "    Person:\n"
                + "      $schema: https://json-structure.org/meta/extended/v0/#\n"
                + "      $id: https://api.example.com/person\n"
                + "      $importdefs: https://api.example.com/common#ignored\n"
                + "      name: Person\n"
                + "      type: object\n"
                + "      properties:\n"
                + "        id:\n"
                + "          type: { $ref: '#/definitions/Identifier' }\n";

        JsonStructureTypeGraph graph = new JsonStructureResolver().resolve(Yaml.mapper().readTree(source));

        assertTrue(!graph.getComponentRoots().containsKey("Common"));
        assertNotNull(graph.getDeclarations().get(new QualifiedTypeName(
                URI.create("https://api.example.com/person"),
                List.of(),
                "Identifier")));
    }

    @Test
    public void preservesSdkValidatedBinaryAnnotations() throws Exception {
        String source = "openapi: 3.1.0\n"
                + "components:\n"
                + "  schemas:\n"
                + "    Document:\n"
                + "      $schema: https://json-structure.org/meta/core/v0/#\n"
                + "      $id: https://api.example.com/document\n"
                + "      name: Document\n"
                + "      type: object\n"
                + "      properties:\n"
                + "        defaultData: { type: binary }\n"
                + "        compressedData:\n"
                + "          type: binary\n"
                + "          contentEncoding: base32hex\n"
                + "          contentCompression: gzip\n"
                + "          contentMediaType: application/octet-stream\n";

        JsonStructureTypeGraph graph = new JsonStructureResolver().resolve(Yaml.mapper().readTree(source));
        JsonStructureTypeDeclaration document = graph.getDeclarations().get(new QualifiedTypeName(
                URI.create("https://api.example.com/document"),
                List.of(),
                "Document"));

        assertEquals(document.getProperties().get("defaultData").getContentEncoding(), "base64");
        assertEquals(document.getProperties().get("compressedData").getContentEncoding(), "base32hex");
        assertEquals(document.getProperties().get("compressedData").getContentCompression(), "gzip");
        assertEquals(
                document.getProperties().get("compressedData").getContentMediaType(),
                "application/octet-stream");

    }

    @Test
    public void keepsExplicitIdsWithFragmentsDistinct() throws Exception {
        String source = "openapi: 3.1.0\n"
                + "components:\n"
                + "  schemas:\n"
                + "    Plain:\n"
                + "      $schema: https://json-structure.org/meta/core/v0/#\n"
                + "      $id: https://api.example.com/types\n"
                + "      definitions: {}\n"
                + "    Fragmented:\n"
                + "      $schema: https://json-structure.org/meta/core/v0/#\n"
                + "      $id: https://api.example.com/types#version-2\n"
                + "      definitions: {}\n";

        JsonStructureTypeGraph graph = new JsonStructureResolver().resolve(Yaml.mapper().readTree(source));

        assertEquals(graph.getComponentByResourceId().get("https://api.example.com/types"), "Plain");
        assertEquals(
                graph.getComponentByResourceId().get("https://api.example.com/types#version-2"),
                "Fragmented");
    }

    @Test
    public void keepsByteDistinctResourceIdsDistinct() throws Exception {
        String source = "openapi: 3.1.0\n"
                + "components:\n"
                + "  schemas:\n"
                + "    Upper:\n"
                + "      $schema: https://json-structure.org/meta/core/v0/#\n"
                + "      $id: https://EXAMPLE.com/types\n"
                + "      definitions: {}\n"
                + "    Lower:\n"
                + "      $schema: https://json-structure.org/meta/core/v0/#\n"
                + "      $id: https://example.com/types\n"
                + "      definitions: {}\n";

        JsonStructureTypeGraph graph =
                new JsonStructureResolver().resolve(Yaml.mapper().readTree(source));

        assertEquals(graph.getComponentByResourceId().get("https://EXAMPLE.com/types"), "Upper");
        assertEquals(graph.getComponentByResourceId().get("https://example.com/types"), "Lower");
    }

    @Test
    public void rejectsDuplicateIdsAndCycles() throws Exception {
        String duplicateIds = "openapi: 3.1.0\n"
                + "components:\n"
                + "  schemas:\n"
                + "    One:\n"
                + "      $schema: https://json-structure.org/meta/core/v0/#\n"
                + "      $id: https://api.example.com/same\n"
                + "      definitions: {}\n"
                + "    Two:\n"
                + "      $schema: https://json-structure.org/meta/core/v0/#\n"
                + "      $id: https://api.example.com/same\n"
                + "      definitions: {}\n";
        assertThrows(
                JsonStructureResolutionException.class,
                () -> new JsonStructureResolver().resolve(Yaml.mapper().readTree(duplicateIds)));

        String importCycle = "openapi: 3.1.0\n"
                + "components:\n"
                + "  schemas:\n"
                + "    One:\n"
                + "      $schema: https://json-structure.org/meta/extended/v0/#\n"
                + "      $id: https://api.example.com/one\n"
                + "      $importdefs: https://api.example.com/two\n"
                + "    Two:\n"
                + "      $schema: https://json-structure.org/meta/extended/v0/#\n"
                + "      $id: https://api.example.com/two\n"
                + "      $importdefs: https://api.example.com/one\n";
        assertThrows(
                JsonStructureResolutionException.class,
                () -> new JsonStructureResolver().resolve(Yaml.mapper().readTree(importCycle)));

        String extendsCycle = "openapi: 3.1.0\n"
                + "components:\n"
                + "  schemas:\n"
                + "    Types:\n"
                + "      $schema: https://json-structure.org/meta/core/v0/#\n"
                + "      $id: https://api.example.com/types\n"
                + "      definitions:\n"
                + "        One:\n"
                + "          name: One\n"
                + "          type: object\n"
                + "          abstract: true\n"
                + "          $extends: '#/definitions/Two'\n"
                + "          properties: { one: { type: string } }\n"
                + "        Two:\n"
                + "          name: Two\n"
                + "          type: object\n"
                + "          abstract: true\n"
                + "          $extends: '#/definitions/One'\n"
                + "          properties: { two: { type: string } }\n";
        assertThrows(
                JsonStructureResolutionException.class,
                () -> new JsonStructureResolver().resolve(Yaml.mapper().readTree(extendsCycle)));
    }

    @Test
    public void rejectsInvalidReferencesRootsAndStructuralDeclarations() throws Exception {
        assertResolutionFails(""
                + "name: Person\n"
                + "type: object\n"
                + "properties:\n"
                + "  id: { $ref: '#/definitions/Id' }\n"
                + "definitions:\n"
                + "  Id: { type: uuid }\n");
        assertResolutionFails(""
                + "$root: '#/definitions/Base'\n"
                + "definitions:\n"
                + "  Base:\n"
                + "    name: Base\n"
                + "    type: object\n"
                + "    abstract: true\n"
                + "    properties: { id: { type: uuid } }\n");
        assertResolutionFails(""
                + "name: Coordinates\n"
                + "type: tuple\n"
                + "properties:\n"
                + "  lat: { type: double }\n"
                + "  long: { type: double }\n"
                + "tuple: [lat, lat]\n");
        assertResolutionFails(""
                + "name: Person\n"
                + "type: object\n"
                + "properties:\n"
                + "  id: { type: uuid }\n"
                + "required: [missing]\n");
        assertResolutionFails(""
                + "$root: '#/definitions/Child'\n"
                + "definitions:\n"
                + "  Base:\n"
                + "    name: Base\n"
                + "    type: object\n"
                + "    abstract: true\n"
                + "    properties: { id: { type: uuid } }\n"
                + "  Child:\n"
                + "    name: Child\n"
                + "    type: object\n"
                + "    $extends: '#/definitions/Base'\n"
                + "    properties: { id: { type: string } }\n");
        assertResolutionFails(""
                + "$root: '#/definitions/Event'\n"
                + "definitions:\n"
                + "  Base:\n"
                + "    name: Base\n"
                + "    type: object\n"
                + "    abstract: true\n"
                + "    properties: { kind: { type: string } }\n"
                + "  Created:\n"
                + "    name: Created\n"
                + "    type: object\n"
                + "    properties: { id: { type: uuid } }\n"
                + "  Event:\n"
                + "    name: Event\n"
                + "    type: choice\n"
                + "    $extends: '#/definitions/Base'\n"
                + "    selector: kind\n"
                + "    choices:\n"
                + "      created: { type: { $ref: '#/definitions/Created' } }\n");
    }

    @Test
    public void preservesReusableMixedTypeUnions() throws Exception {
        String source = "openapi: 3.1.0\n"
                + "components:\n"
                + "  schemas:\n"
                + "    Value:\n"
                + "      $schema: https://json-structure.org/meta/core/v0/#\n"
                + "      $id: https://api.example.com/value\n"
                + "      $root: '#/definitions/Value'\n"
                + "      definitions:\n"
                + "        Record:\n"
                + "          name: Record\n"
                + "          type: object\n"
                + "          properties:\n"
                + "            id: { type: uuid }\n"
                + "        Value:\n"
                + "          type:\n"
                + "            - string\n"
                + "            - { $ref: '#/definitions/Record' }\n"
                + "            - 'null'\n";

        JsonStructureTypeGraph graph = new JsonStructureResolver().resolve(Yaml.mapper().readTree(source));
        JsonStructureTypeDeclaration union = graph.getDeclarations().get(new QualifiedTypeName(
                URI.create("https://api.example.com/value"),
                List.of(),
                "Value"));

        assertEquals(union.getKind(), JsonStructureTypeKind.UNION);
        assertTrue(union.getDeclaredType().isNullable());
        assertEquals(union.getDeclaredType().getReferenceAlternatives().size(), 1);
        assertEquals(
                union.getDeclaredType().getPrimitiveAlternatives(),
                List.of(JsonStructureTypeKind.STRING, JsonStructureTypeKind.NULL));
    }

    @Test
    public void preservesPrimitiveEnumAndConstConstraints() throws Exception {
        String source = "openapi: 3.1.0\n"
                + "components:\n"
                + "  schemas:\n"
                + "    State:\n"
                + "      $schema: https://json-structure.org/meta/core/v0/#\n"
                + "      $id: https://api.example.com/state\n"
                + "      name: State\n"
                + "      type: object\n"
                + "      properties:\n"
                + "        status:\n"
                + "          type: string\n"
                + "          enum: [open, closed]\n"
                + "        version:\n"
                + "          type: int32\n"
                + "          const: 1\n";

        JsonStructureTypeGraph graph = new JsonStructureResolver().resolve(Yaml.mapper().readTree(source));
        JsonStructureTypeDeclaration state = graph.getDeclarations().get(new QualifiedTypeName(
                URI.create("https://api.example.com/state"),
                List.of(),
                "State"));

        assertEquals(state.getProperties().get("status").getEnumValues(), List.of("open", "closed"));
        assertTrue(state.getProperties().get("version").hasConst());
        assertEquals(state.getProperties().get("version").getConstValue(), 1);
    }

    private void assertResolutionFails(String schema) throws Exception {
        String source = "openapi: 3.1.0\n"
                + "components:\n"
                + "  schemas:\n"
                + "    Example:\n"
                + "      $schema: https://json-structure.org/meta/core/v0/#\n"
                + "      $id: https://api.example.com/example\n"
                + schema.lines()
                        .map(line -> "      " + line + "\n")
                        .reduce("", String::concat);
        assertThrows(
                JsonStructureResolutionException.class,
                () -> new JsonStructureResolver().resolve(Yaml.mapper().readTree(source)));
    }
}
