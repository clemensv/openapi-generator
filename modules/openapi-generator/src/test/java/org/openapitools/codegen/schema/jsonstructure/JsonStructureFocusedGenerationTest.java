/*
 * Copyright 2026 OpenAPI-Generator Contributors (https://openapi-generator.tech)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.openapitools.codegen.schema.jsonstructure;

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
                {"java", "src/main/java/org/openapitools/client/model/Consumer.java",
                        "src/main/java/org/openapitools/client/model/ConsumerCommonGeometryPoint.java",
                        "src/main/java/org/openapitools/client/model/ConsumerCommonMetadataPoint.java"},
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
            Assert.assertTrue(
                    rootSource.contains("ConsumerCommonGeometryPoint"),
                    generatorName + " root model does not reference the Common.Geometry type");
            Assert.assertTrue(
                    rootSource.contains("ConsumerCommonMetadataPoint"),
                    generatorName + " root model does not reference the Common.Metadata type");
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
                            .anyMatch(name -> name.toLowerCase().contains(expectedModel.toLowerCase())),
                    generatorName + " did not generate model " + expectedModel + " for " + fixture);
        } finally {
            target.toFile().deleteOnExit();
        }
    }

    @Test
    public void rejectsAdvancedWireShapesInStrictMode() throws IOException {
        Path target = Files.createTempDirectory("json-structure-wire-strict");
        try {
            ClientOptInput input = new CodegenConfigurator()
                    .setGeneratorName("java")
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

    @Test
    public void allowsAdvancedWireShapesOnlyInCompatibilityMode() throws IOException {
        Path target = Files.createTempDirectory("json-structure-wire-compatibility");
        try {
            ClientOptInput input = new CodegenConfigurator()
                    .setGeneratorName("java")
                    .setInputSpec(RESOURCE_ROOT + "json-structure-wire-shapes.yaml")
                    .setOutputDir(target.toAbsolutePath().toString())
                    .addAdditionalProperty(CodegenConstants.JSON_STRUCTURE_COMPATIBILITY_MODE, true)
                    .toClientOptInput();

            List<File> generated = new DefaultGenerator().opts(input).generate();

            Assert.assertTrue(
                    generated.stream().map(File::getName).anyMatch(name -> name.contains("Envelope")),
                    "Compatibility mode did not generate the advanced root model");
            String eventModel = Files.readString(
                    target.resolve("src/main/java/org/openapitools/client/model/EnvelopeEvent.java"));
            Assert.assertFalse(
                    eventModel.contains("CodegenProperty{"),
                    "Compatibility choice mapping leaked codegen metadata into generated Java");
        } finally {
            target.toFile().deleteOnExit();
        }
    }
}
