/*
 * Copyright 2026 OpenAPI-Generator Contributors (https://openapi-generator.tech)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.openapitools.codegen.schema.jsonstructure;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.core.util.Yaml;
import org.openapitools.codegen.ClientOptInput;
import org.openapitools.codegen.CodegenConstants;
import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.CodegenConfigurator;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public class JsonStructureFocusedGenerationTest {
    private static final String RESOURCE_ROOT = "src/test/resources/3_1/";

    @DataProvider(name = "strictGenerationMatrix")
    public Object[][] strictGenerationMatrix() {
        String[] generators = {
                "java",
                "csharp",
                "go",
                "python",
                "typescript-fetch",
                "kotlin",
                "rust",
                "php"
        };
        Object[][] fixtures = {
                {"json-structure-safe.yaml", "PrimitiveRecord"},
                {"json-structure-containers.yaml", "Catalog"},
                {"json-structure-mixed.yaml", "Event"},
                {"json-structure-namespaces.yaml", "Consumer"}
        };
        Object[][] result = new Object[generators.length * fixtures.length][3];
        int index = 0;
        for (String generator : generators) {
            for (Object[] fixture : fixtures) {
                result[index++] = new Object[]{generator, fixture[0], fixture[1]};
            }
        }
        return result;
    }

    @DataProvider(name = "namespaceGenerationMatrix")
    public Object[][] namespaceGenerationMatrix() {
        return new Object[][]{
                {"java", "src/main/java/org/openapitools/client/model/Consumer/Consumer.java",
                        "src/main/java/org/openapitools/client/model/Consumer/ConsumerCommonGeometryPoint.java",
                        "src/main/java/org/openapitools/client/model/Consumer/ConsumerCommonMetadataPoint.java"},
                {"csharp", "src/Org.OpenAPITools/Model/Consumer.cs",
                        "src/Org.OpenAPITools/Model/ConsumerCommonGeometryPoint.cs",
                        "src/Org.OpenAPITools/Model/ConsumerCommonMetadataPoint.cs"},
                {"go", "model_consumer.go",
                        "model_consumer_common_geometry_point.go",
                        "model_consumer_common_metadata_point.go"},
                {"python", "openapi_client/models/consumer.py",
                        "openapi_client/models/consumer_common_geometry_point.py",
                        "openapi_client/models/consumer_common_metadata_point.py"},
                {"typescript-fetch", "models/Consumer.ts",
                        "models/ConsumerCommonGeometryPoint.ts",
                        "models/ConsumerCommonMetadataPoint.ts"},
                {"kotlin", "src/main/kotlin/org/openapitools/client/models/Consumer.kt",
                        "src/main/kotlin/org/openapitools/client/models/ConsumerCommonGeometryPoint.kt",
                        "src/main/kotlin/org/openapitools/client/models/ConsumerCommonMetadataPoint.kt"},
                {"rust", "src/models/consumer.rs",
                        "src/models/consumer_common_geometry_point.rs",
                        "src/models/consumer_common_metadata_point.rs"},
                {"php", "lib/Model/Consumer.php",
                        "lib/Model/ConsumerCommonGeometryPoint.php",
                        "lib/Model/ConsumerCommonMetadataPoint.php"}
        };
    }

    @DataProvider(name = "advancedGenerationMatrix")
    public Object[][] advancedGenerationMatrix() {
        return new Object[][]{
                {"java"},
                {"csharp"},
                {"go"},
                {"python"},
                {"typescript-fetch"},
                {"kotlin"},
                {"rust"},
                {"php"}
        };
    }

    @Test(dataProvider = "namespaceGenerationMatrix")
    public void preservesQualifiedNamespacesInGeneratedModelNames(
            String generatorName,
            String rootModelPath,
            String geometryModelPath,
            String metadataModelPath) throws IOException {
        Path target = Files.createTempDirectory("json-structure-namespaces-" + generatorName);
        try {
            ClientOptInput input = new CodegenConfigurator()
                    .setGeneratorName(generatorName)
                    .setInputSpec(RESOURCE_ROOT + "json-structure-namespaces.yaml")
                    .setOutputDir(target.toAbsolutePath().toString())
                    .toClientOptInput();

            new DefaultGenerator().opts(input).generate();

            Path rootModel = target.resolve(rootModelPath);
            Assert.assertTrue(Files.isRegularFile(rootModel), generatorName + " did not generate the root model");
            Assert.assertTrue(
                    Files.isRegularFile(target.resolve(geometryModelPath)),
                    generatorName + " lost the Common.Geometry namespace");
            Assert.assertTrue(
                    Files.isRegularFile(target.resolve(metadataModelPath)),
                    generatorName + " lost the Common.Metadata namespace");
            String rootSource = Files.readString(rootModel);
            String geometryType = "ConsumerCommonGeometryPoint";
            String metadataType = "ConsumerCommonMetadataPoint";
            String nestedGeometryType = "ConsumerCommonGeometryVector";
            Assert.assertTrue(
                    rootSource.contains(geometryType),
                    generatorName + " root model does not reference the Common.Geometry type");
            Assert.assertTrue(
                    rootSource.contains(metadataType),
                    generatorName + " root model does not reference the Common.Metadata type");
            Assert.assertTrue(
                    rootSource.contains(nestedGeometryType),
                    generatorName + " root model does not reference the nested Common.Geometry type");
            if ("typescript-fetch".equals(generatorName)) {
                String geometrySource = Files.readString(target.resolve(geometryModelPath));
                Assert.assertFalse(
                        geometrySource.contains("from './number'"),
                        "TypeScript primitive fields must not import a synthetic number model");
            }
        } finally {
            target.toFile().deleteOnExit();
        }
    }

    @Test(dataProvider = "strictGenerationMatrix")
    public void generatesStrictSafeSchemasAcrossRepresentativeGenerators(
            String generatorName,
            String fixture,
            String expectedModel) throws IOException {
        Path target = Files.createTempDirectory("json-structure-focused-" + generatorName);
        try {
            ClientOptInput input = new CodegenConfigurator()
                    .setGeneratorName(generatorName)
                    .setInputSpec(RESOURCE_ROOT + fixture)
                    .setOutputDir(target.toAbsolutePath().toString())
                    .toClientOptInput();

            List<File> generated = new DefaultGenerator().opts(input).generate();

            Assert.assertFalse(generated.isEmpty(), generatorName + " did not generate files for " + fixture);
            Assert.assertTrue(
                    generated.stream()
                            .map(File::getName)
                            .anyMatch(name -> name.toLowerCase(Locale.ROOT)
                                    .contains(expectedModel.toLowerCase(Locale.ROOT))),
                    generatorName + " did not generate model " + expectedModel + " for " + fixture);
        } finally {
            target.toFile().deleteOnExit();
        }
    }

    @Test
    public void preservesJsonStructureSourceDocumentInGeneratedJavaClient() throws IOException {
        Path target = Files.createTempDirectory("json-structure-source-spec");
        Path source = Path.of(RESOURCE_ROOT + "json-structure-namespaces.yaml");
        try {
            ClientOptInput input = new CodegenConfigurator()
                    .setGeneratorName("java")
                    .setInputSpec(source.toString())
                    .setOutputDir(target.toAbsolutePath().toString())
                    .toClientOptInput();
            new DefaultGenerator().opts(input).generate();

            Path generatedSpec = target.resolve("api/openapi.yaml");
            Assert.assertTrue(Files.isRegularFile(generatedSpec), "Java client did not include api/openapi.yaml");
            JsonNode sourceDocument = Yaml.mapper().readTree(source.toFile());
            JsonNode generatedDocument = Yaml.mapper().readTree(generatedSpec.toFile());
            Assert.assertEquals(
                    generatedDocument,
                    sourceDocument,
                    "Generated Java client must preserve the JSON Structure source document");
            Assert.assertTrue(
                    Files.isRegularFile(target.resolve(
                            "src/main/java/org/openapitools/client/model/Consumer/ConsumerCommonGeometryPoint.java")),
                    "Imported definitions must be emitted under the importing resource namespace");
            Assert.assertFalse(
                    Files.exists(target.resolve(
                            "src/main/java/org/openapitools/client/model/Shared/SharedGeometryPoint.java")),
                    "A definition-only import resource must not emit a duplicate source-namespace model");
        } finally {
            target.toFile().deleteOnExit();
        }
    }

    @Test
    public void mapsAggregateResourceNamespacesToJavaModelPackages() throws IOException {
        Path target = Files.createTempDirectory("json-structure-java-packages");
        Path source = target.resolve("pet-namespaces.yaml");
        Files.writeString(source, "openapi: 3.1.0\n"
                + "info: { title: Pet namespaces, version: 1.0.0 }\n"
                + "components:\n"
                + "  schemas:\n"
                + "    Pet:\n"
                + "      $schema: https://json-structure.org/meta/core/v0/#\n"
                + "      $id: https://example.com/pet\n"
                + "      name: Pet\n"
                + "      type: object\n"
                + "      properties: { id: { type: uuid } }\n"
                + "    PetListResponse:\n"
                + "      $schema: https://json-structure.org/meta/core/v0/#\n"
                + "      $id: https://example.com/pet-list-response\n"
                + "      name: PetListResponse\n"
                + "      type: object\n"
                + "      properties:\n"
                + "        pets:\n"
                + "          type: array\n"
                + "          items: { type: { $ref: '#/definitions/Pet' } }\n"
                + "      definitions:\n"
                + "        Pet:\n"
                + "          name: Pet\n"
                + "          type: object\n"
                + "          properties: { id: { type: uuid }, tags: { type: array, items: { type: string } } }\n");
        try {
            ClientOptInput input = new CodegenConfigurator()
                    .setGeneratorName("java")
                    .setInputSpec(source.toString())
                    .setOutputDir(target.toAbsolutePath().toString())
                    .toClientOptInput();
            JsonStructureModelCatalog catalog =
                    new JsonStructureModelCatalog(
                            input.getJsonStructureTypeGraph(),
                            input.getOpenAPI().getComponents().getSchemas().keySet());
            Assert.assertTrue(
                    catalog.modelNames().contains("PetListResponse_Pet"),
                    catalog.modelNames().toString());

            new DefaultGenerator().opts(input).generate();

            Path pet = target.resolve("src/main/java/org/openapitools/client/model/Pet/Pet.java");
            Path nestedPet =
                    target.resolve(
                            "src/main/java/org/openapitools/client/model/PetListResponse/PetListResponsePet.java");
            Assert.assertTrue(Files.isRegularFile(pet));
            Assert.assertTrue(Files.isRegularFile(nestedPet));
            Assert.assertTrue(
                    Files.readString(pet).contains("package org.openapitools.client.model.Pet;"));
            Assert.assertTrue(
                    Files.readString(nestedPet)
                            .contains("package org.openapitools.client.model.PetListResponse;"));
        } finally {
            target.toFile().deleteOnExit();
        }
    }

    @Test
    public void escapesJavaKeywordsInResourcePackages() throws IOException {
        Path target = Files.createTempDirectory("json-structure-java-keyword-package");
        Path source = target.resolve("keyword-package.yaml");
        Files.writeString(source, "openapi: 3.1.0\n"
                + "info: { title: Keyword package, version: 1.0.0 }\n"
                + "components:\n"
                + "  schemas:\n"
                + "    class:\n"
                + "      $schema: https://json-structure.org/meta/core/v0/#\n"
                + "      type: object\n"
                + "      properties: { value: { type: string } }\n");
        try {
            ClientOptInput input = new CodegenConfigurator()
                    .setGeneratorName("java")
                    .setInputSpec(source.toString())
                    .setOutputDir(target.toAbsolutePath().toString())
                    .toClientOptInput();

            new DefaultGenerator().opts(input).generate();

            Path packageFolder =
                    target.resolve("src/main/java/org/openapitools/client/model/_class");
            Assert.assertTrue(Files.isDirectory(packageFolder));
            try (java.util.stream.Stream<Path> models = Files.list(packageFolder)) {
                Path model = models.filter(path -> path.toString().endsWith(".java"))
                        .findFirst()
                        .orElseThrow();
                Assert.assertTrue(
                        Files.readString(model)
                                .contains("package org.openapitools.client.model._class;"));
            }
        } finally {
            target.toFile().deleteOnExit();
        }
    }

    @Test(dataProvider = "advancedGenerationMatrix")
    public void rejectsAdvancedWireShapesInStrictMode(String generatorName) throws IOException {
        Path target = Files.createTempDirectory("json-structure-wire-strict-" + generatorName);
        try {
            ClientOptInput input = new CodegenConfigurator()
                    .setGeneratorName(generatorName)
                    .setInputSpec(RESOURCE_ROOT + "json-structure-wire-shapes.yaml")
                    .setOutputDir(target.toAbsolutePath().toString())
                    .toClientOptInput();

            IllegalArgumentException exception = Assert.expectThrows(
                    IllegalArgumentException.class,
                    () -> new DefaultGenerator().opts(input).generate());

            Assert.assertTrue(exception.getMessage().contains("tuple wire encoding"), exception.getMessage());
            Assert.assertTrue(exception.getMessage().contains("choice wire encoding"), exception.getMessage());
            Assert.assertTrue(exception.getMessage().contains("reusable union encoding"), exception.getMessage());
            Assert.assertTrue(exception.getMessage().contains("binary encoding"), exception.getMessage());
            Assert.assertTrue(exception.getMessage().contains("binary compression"), exception.getMessage());
        } finally {
            target.toFile().deleteOnExit();
        }
    }

    @Test(dataProvider = "advancedGenerationMatrix")
    public void allowsAdvancedWireShapesOnlyInCompatibilityMode(String generatorName) throws IOException {
        Path target = Files.createTempDirectory("json-structure-wire-compatibility-" + generatorName);
        try {
            ClientOptInput input = new CodegenConfigurator()
                    .setGeneratorName(generatorName)
                    .setInputSpec(RESOURCE_ROOT + "json-structure-wire-shapes.yaml")
                    .setOutputDir(target.toAbsolutePath().toString())
                    .addAdditionalProperty(CodegenConstants.JSON_STRUCTURE_COMPATIBILITY_MODE, true)
                    .toClientOptInput();

            List<File> generated = new DefaultGenerator().opts(input).generate();

            Assert.assertTrue(
                    generated.stream().map(File::getName).anyMatch(name -> name.contains("Envelope")),
                    "Compatibility mode did not generate the advanced root model");
            if ("java".equals(generatorName)) {
                String eventModel = Files.readString(
                        target.resolve(
                                "src/main/java/org/openapitools/client/model/Envelope/EnvelopeEvent.java"));
                String valueModel = Files.readString(
                        target.resolve(
                                "src/main/java/org/openapitools/client/model/Envelope/EnvelopeValue.java"));
                Assert.assertFalse(
                        eventModel.contains("CodegenProperty{"),
                        "Compatibility choice mapping leaked codegen metadata into generated Java");
                Assert.assertTrue(
                        valueModel.contains(
                                "import org.openapitools.client.model.AbstractOpenApiSchema;"),
                        "Packaged JSON Structure union wrappers must import the shared Java base class");
            }
        } finally {
            target.toFile().deleteOnExit();
        }
    }
}
