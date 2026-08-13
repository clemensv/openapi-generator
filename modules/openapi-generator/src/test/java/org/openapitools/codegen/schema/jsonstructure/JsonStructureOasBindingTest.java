/*
 * Copyright 2026 OpenAPI-Generator Contributors (https://openapi-generator.tech)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.openapitools.codegen.schema.jsonstructure;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.core.util.Yaml;
import org.openapitools.codegen.config.CodegenConfigurator;
import org.openapitools.codegen.schema.SchemaDialect;
import org.openapitools.codegen.schema.SchemaDialectDetector;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

public class JsonStructureOasBindingTest {
    @Test
    public void appliesCanonicalAddInActivationSemanticsExactly() throws Exception {
        String source = "openapi: 3.1.0\n"
                + "components:\n"
                + "  schemas:\n"
                + resource("Core", SchemaDialectDetector.JSON_STRUCTURE_CORE, "")
                + resource("Extended", SchemaDialectDetector.JSON_STRUCTURE_EXTENDED, "")
                + resource("ExtendedUnits", SchemaDialectDetector.JSON_STRUCTURE_EXTENDED,
                        "      $uses: [JSONStructureUnits]\n")
                + resource("Validation", SchemaDialectDetector.JSON_STRUCTURE_VALIDATION, "");

        JsonStructureTypeGraph graph = new JsonStructureResolver().resolve(Yaml.mapper().readTree(source));

        assertEquals(graph.getResources().get("Core").getEffectiveUses(), List.of());
        assertEquals(
                graph.getResources().get("Extended").getEffectiveUses(),
                List.of("JSONStructureImport"));
        assertFalse(graph.getResources().get("Extended").getEffectiveUses()
                .contains("JSONStructureValidation"));
        assertEquals(
                graph.getResources().get("ExtendedUnits").getEffectiveUses(),
                List.of("JSONStructureImport", "JSONStructureUnits"));
        assertEquals(
                graph.getResources().get("Validation").getEffectiveUses(),
                List.of(
                        "JSONStructureImport",
                        "JSONStructureAlternateNames",
                        "JSONStructureUnits",
                        "JSONStructureConditionalComposition",
                        "JSONStructureValidation"));
    }

    @Test
    public void refusesUnknownDialectInsteadOfFallingBackToOas() throws Exception {
        String source = "openapi: 3.1.0\n"
                + "components:\n"
                + "  schemas:\n"
                + "    Unknown:\n"
                + "      $schema: https://example.com/meta/custom\n"
                + "      name: Unknown\n"
                + "      type: object\n"
                + "      properties: { value: { type: string } }\n";

        assertEquals(
                SchemaDialectDetector.componentSchemaDialects(Yaml.mapper().readTree(source))
                        .get("Unknown"),
                SchemaDialect.UNKNOWN);
        assertThrows(
                JsonStructureResolutionException.class,
                () -> new JsonStructureResolver().resolve(Yaml.mapper().readTree(source)));
    }

    @Test
    public void configuratorRejectsUnknownDialectBeforeOasGeneration() throws Exception {
        String source = "openapi: 3.1.0\n"
                + "info: { title: Unknown dialect, version: 1.0.0 }\n"
                + "paths: {}\n"
                + "components:\n"
                + "  schemas:\n"
                + "    Unknown:\n"
                + "      $schema: https://example.com/meta/custom\n"
                + "      name: Unknown\n"
                + "      type: object\n"
                + "      properties: { value: { type: string } }\n";
        Path file = Files.createTempFile("unknown-json-structure-dialect", ".yaml");
        Files.writeString(file, source);

        assertThrows(
                IllegalArgumentException.class,
                () -> new CodegenConfigurator()
                        .setGeneratorName("java")
                        .setValidateSpec(false)
                        .setInputSpec(file.toString())
                        .toContext());

        JsonStructureResolutionOptions configured = new JsonStructureResolutionOptions()
                .addVerifiedCustomMetaSchema(
                        "https://example.com/meta/custom",
                        SchemaDialect.JSON_STRUCTURE_EXTENDED);
        assertNotNull(new CodegenConfigurator()
                .setGeneratorName("java")
                .setValidateSpec(false)
                .setInputSpec(file.toString())
                .setJsonStructureResolutionOptions(configured)
                .toContext());
    }

    @Test
    public void processesOnlyExplicitlyVerifiedCustomMetaSchemas() throws Exception {
        String customUri = "https://example.com/meta/acme";
        String source = "openapi: 3.1.0\n"
                + "components:\n"
                + "  schemas:\n"
                + "    Custom:\n"
                + "      $schema: " + customUri + "\n"
                + "      $id: https://example.com/custom\n"
                + "      name: Custom\n"
                + "      type: object\n"
                + "      properties: { value: { type: string } }\n";
        JsonNode document = Yaml.mapper().readTree(source);

        assertThrows(
                JsonStructureResolutionException.class,
                () -> new JsonStructureResolver().resolve(document));

        JsonStructureResolutionOptions options = new JsonStructureResolutionOptions()
                .addVerifiedCustomMetaSchema(customUri, SchemaDialect.JSON_STRUCTURE_EXTENDED);
        RecordingAdapter adapter = new RecordingAdapter();
        JsonStructureTypeGraph graph = new JsonStructureResolver(adapter)
                .resolve(document, null, options);
        JsonStructureResource resource = graph.getResources().get("Custom");

        assertEquals(resource.getDialect(), SchemaDialect.JSON_STRUCTURE_EXTENDED);
        assertEquals(resource.getMetaSchemaUri(), customUri);
        assertEquals(adapter.resources.get("Custom").path("$schema").textValue(), customUri);
        assertEquals(resource.getEffectiveUses(), List.of("JSONStructureImport"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new JsonStructureResolutionOptions().addVerifiedCustomMetaSchema(
                        customUri, "https://example.com/not-canonical"));
    }

    @Test
    public void acceptsOnlyConfiguredCustomMetaSchemaOffers() throws Exception {
        String customUri = "https://example.com/meta/acme";
        String source = "openapi: 3.1.0\n"
                + "components:\n"
                + "  schemas:\n"
                + "    Custom:\n"
                + "      $schema: " + customUri + "\n"
                + "      $id: https://example.com/custom\n"
                + "      $uses: [AcmeValidation]\n"
                + "      name: Custom\n"
                + "      type: object\n"
                + "      properties: { value: { type: string } }\n";
        JsonNode document = Yaml.mapper().readTree(source);
        JsonStructureResolutionOptions dialectOnly = new JsonStructureResolutionOptions()
                .addVerifiedCustomMetaSchema(customUri, SchemaDialect.JSON_STRUCTURE_EXTENDED);
        assertThrows(
                JsonStructureResolutionException.class,
                () -> new JsonStructureResolver(new RecordingAdapter())
                        .resolve(document, null, dialectOnly));

        JsonStructureResolutionOptions withOffer = new JsonStructureResolutionOptions()
                .addVerifiedCustomMetaSchema(customUri, SchemaDialect.JSON_STRUCTURE_EXTENDED)
                .addVerifiedCustomMetaSchemaOffer(customUri, "AcmeValidation");
        JsonStructureResource resource = new JsonStructureResolver(new RecordingAdapter())
                .resolve(document, null, withOffer)
                .getResources().get("Custom");
        assertEquals(
                resource.getEffectiveUses(),
                List.of("JSONStructureImport", "AcmeValidation"));
    }

    @Test
    public void retainsMetaSchemaPointerUsesWithoutResolvingThemLocally() throws Exception {
        String source = "openapi: 3.1.0\n"
                + "components:\n"
                + "  schemas:\n"
                + "    Value:\n"
                + "      $schema: https://json-structure.org/meta/extended/v0/#\n"
                + "      $id: https://example.com/value\n"
                + "      $uses: ['#/definitions/CustomAddIn']\n"
                + "      name: Value\n"
                + "      type: object\n"
                + "      properties: { value: { type: string } }\n";

        JsonStructureResource resource = new JsonStructureResolver(new RecordingAdapter())
                .resolve(Yaml.mapper().readTree(source))
                .getResources().get("Value");

        assertEquals(
                resource.getEffectiveUses(),
                List.of("JSONStructureImport", "#/definitions/CustomAddIn"));
    }

    @Test
    public void matchesImportsAgainstExactRegisteredIdsAfterStrippingOnlyTheImportFragment()
            throws Exception {
        String source = "openapi: 3.1.0\n"
                + "components:\n"
                + "  schemas:\n"
                + "    Library:\n"
                + "      $schema: https://json-structure.org/meta/extended/v0/#\n"
                + "      $id: https://example.com/library#version\n"
                + "      definitions:\n"
                + "        Item: { type: string }\n"
                + "    Consumer:\n"
                + "      $schema: https://json-structure.org/meta/extended/v0/#\n"
                + "      $id: https://example.com/consumer\n"
                + "      $importdefs: https://example.com/library#/definitions/Item\n"
                + "      name: Consumer\n"
                + "      type: object\n"
                + "      properties: { value: { type: string } }\n";

        JsonStructureResolutionException error = expectThrows(
                JsonStructureResolutionException.class,
                () -> new JsonStructureResolver(new RecordingAdapter())
                        .resolve(Yaml.mapper().readTree(source)));
        assertTrue(error.getMessage().contains("was not supplied by the caller"));
    }

    @Test
    public void resolvesCallerSuppliedExternalImportsWithoutNetworkAccess() throws Exception {
        String source = "openapi: 3.1.0\n"
                + "components:\n"
                + "  schemas:\n"
                + "    Consumer:\n"
                + "      $schema: https://json-structure.org/meta/extended/v0/#\n"
                + "      $id: https://example.com/consumer\n"
                + "      $importdefs: https://types.example.com/library#/definitions/Item\n"
                + "      name: Consumer\n"
                + "      type: object\n"
                + "      properties:\n"
                + "        item: { type: { $ref: '#/definitions/Item' } }\n";
        JsonNode external = Yaml.mapper().readTree(
                "$schema: https://json-structure.org/meta/extended/v0/#\n"
                        + "$id: https://types.example.com/library\n"
                        + "definitions:\n"
                        + "  Item:\n"
                        + "    type: object\n"
                        + "    properties: { value: { type: string } }\n");
        JsonStructureResolutionOptions options =
                new JsonStructureResolutionOptions().addExternalResource(external);
        RecordingAdapter adapter = new RecordingAdapter();

        JsonStructureTypeGraph graph = new JsonStructureResolver(adapter)
                .resolve(Yaml.mapper().readTree(source), null, options);

        assertEquals(adapter.resources.size(), 2);
        assertTrue(adapter.resources.values().stream()
                .anyMatch(resource -> "https://types.example.com/library"
                        .equals(resource.path("$id").textValue())));
        assertTrue(graph.getDeclarations().keySet().stream()
                .anyMatch(name -> name.toString().equals("https://example.com/consumer#Item")));
    }

    @Test
    public void rejectsDuplicateExternalResourceRegistrations() throws Exception {
        JsonNode external = Yaml.mapper().readTree(
                "$schema: https://json-structure.org/meta/extended/v0/#\n"
                        + "$id: https://types.example.com/library\n"
                        + "definitions: {}\n");
        JsonStructureResolutionOptions options =
                new JsonStructureResolutionOptions().addExternalResource(external);

        assertThrows(
                IllegalArgumentException.class,
                () -> options.addExternalResource(external.deepCopy()));
    }

    @Test
    public void allowsConfiguredCoreDerivedMetaSchemaToActivateImports() throws Exception {
        String customUri = "https://example.com/meta/core-with-import";
        String source = "openapi: 3.1.0\n"
                + "components:\n"
                + "  schemas:\n"
                + "    Library:\n"
                + "      $schema: " + customUri + "\n"
                + "      $id: https://example.com/library\n"
                + "      $uses: [JSONStructureImport]\n"
                + "      definitions:\n"
                + "        Item: { type: string }\n"
                + "    Consumer:\n"
                + "      $schema: " + customUri + "\n"
                + "      $id: https://example.com/consumer\n"
                + "      $uses: [JSONStructureImport]\n"
                + "      $importdefs: https://example.com/library#/definitions/Item\n"
                + "      name: Consumer\n"
                + "      type: object\n"
                + "      properties: { value: { type: string } }\n";
        JsonStructureResolutionOptions options = new JsonStructureResolutionOptions()
                .addVerifiedCustomMetaSchema(customUri, SchemaDialect.JSON_STRUCTURE_CORE)
                .addVerifiedCustomMetaSchemaOffer(customUri, "JSONStructureImport");

        JsonStructureTypeGraph graph = new JsonStructureResolver(new RecordingAdapter())
                .resolve(Yaml.mapper().readTree(source), null, options);

        assertTrue(graph.getDeclarations().keySet().stream()
                .anyMatch(name -> name.toString().equals("https://example.com/consumer#Item")));
    }

    @Test
    public void materializesSchemaAndIdUsingPublishedBasePrecedence() throws Exception {
        JsonStructureResolutionOptions allBases = new JsonStructureResolutionOptions()
                .setEncapsulatingEntityBaseUri(URI.create("https://example.com/capsule"))
                .setApplicationDefaultBaseUri(URI.create("https://example.com/application"));
        assertMaterializedId(
                "$self: https://example.com/self\n",
                URI.create("https://example.com/retrieval"),
                allBases,
                "https://example.com/self#/components/schemas/Value");

        assertMaterializedId(
                "",
                URI.create("https://example.com/retrieval"),
                allBases,
                "https://example.com/capsule#/components/schemas/Value");

        assertMaterializedId(
                "",
                URI.create("https://example.com/retrieval"),
                new JsonStructureResolutionOptions()
                        .setApplicationDefaultBaseUri(URI.create("https://example.com/application")),
                "https://example.com/retrieval#/components/schemas/Value");

        assertMaterializedId(
                "",
                null,
                new JsonStructureResolutionOptions()
                        .setApplicationDefaultBaseUri(URI.create("https://example.com/application")),
                "https://example.com/application#/components/schemas/Value");
    }

    @Test
    public void resolvesRelativeSelfAgainstTheNextAvailableBase() throws Exception {
        assertMaterializedId(
                "$self: schemas/openapi.yaml\n",
                URI.create("https://example.com/retrieval/root.yaml"),
                new JsonStructureResolutionOptions()
                        .setEncapsulatingEntityBaseUri(URI.create("https://example.com/capsule/")),
                "https://example.com/capsule/schemas/openapi.yaml#/components/schemas/Value");

        assertThrows(
                JsonStructureResolutionException.class,
                () -> new JsonStructureResolver().resolve(
                        Yaml.mapper().readTree(embeddedValueResource("$self: schemas/openapi.yaml\n")),
                        null,
                        new JsonStructureResolutionOptions()));
    }

    @Test
    public void preservesNullAnnotationValues() throws Exception {
        String source = "openapi: 3.1.0\n"
                + "components:\n"
                + "  schemas:\n"
                + "    Value:\n"
                + "      $schema: https://json-structure.org/meta/core/v0/#\n"
                + "      $id: https://example.com/value\n"
                + "      name: Value\n"
                + "      type: object\n"
                + "      examples: [null]\n"
                + "      properties:\n"
                + "        optional:\n"
                + "          type: [string, 'null']\n"
                + "          enum: [null, value]\n"
                + "          examples: [null]\n";
        JsonStructureTypeGraph graph = new JsonStructureResolver(new RecordingAdapter())
                .resolve(Yaml.mapper().readTree(source));
        JsonStructureResource resource = graph.getResources().get("Value");
        JsonStructureTypeUse optional = graph.getDeclarations()
                .get(graph.getComponentRoots().get("Value"))
                .getProperties().get("optional");

        assertEquals(resource.getExamples(), java.util.Arrays.asList((Object) null));
        assertEquals(optional.getExamples(), java.util.Arrays.asList((Object) null));
        assertEquals(optional.getEnumValues(), java.util.Arrays.asList(null, "value"));
    }

    @Test
    public void requiresExplicitIdWhenNoBaseExists() throws Exception {
        String source = embeddedValueResource("");
        RecordingAdapter adapter = new RecordingAdapter();
        assertThrows(
                JsonStructureResolutionException.class,
                () -> new JsonStructureResolver(adapter).resolve(
                        Yaml.mapper().readTree(source), null, new JsonStructureResolutionOptions()));
        assertTrue(adapter.resources.isEmpty());

        String explicit = source.replace(
                "      name: Value\n",
                "      $id: https://example.com/explicit\n      name: Value\n");
        JsonStructureTypeGraph graph = new JsonStructureResolver().resolve(
                Yaml.mapper().readTree(explicit), null, new JsonStructureResolutionOptions());
        assertEquals(
                graph.getComponentRoots().get("Value").getResourceId(),
                URI.create("https://example.com/explicit"));
    }

    @DataProvider(name = "rootTypes")
    public Object[][] rootTypes() {
        return new Object[][] {
            { "type: string\n" },
            { "type: any\n" },
            { "type: array\nitems: { type: string }\n" },
            { "type: map\nvalues: { type: string }\n" },
            { "type: object\nproperties: { value: { type: string } }\n" }
        };
    }

    @Test(dataProvider = "rootTypes")
    public void requiresNameForEveryTypedComponentResourceRoot(String typeBody) throws Exception {
        String source = "openapi: 3.1.0\n"
                + "jsonSchemaDialect: " + SchemaDialectDetector.JSON_STRUCTURE_CORE + "\n"
                + "components:\n"
                + "  schemas:\n"
                + "    Value:\n"
                + "      $id: https://example.com/value\n"
                + indent(typeBody, 6);
        assertThrows(
                JsonStructureResolutionException.class,
                () -> new JsonStructureResolver().resolve(Yaml.mapper().readTree(source)));

        String named = source.replace(
                "      $id: https://example.com/value\n",
                "      $id: https://example.com/value\n      name: Value\n");
        assertTrue(new JsonStructureResolver().resolve(Yaml.mapper().readTree(named))
                .getComponentRoots().containsKey("Value"));
    }

    private void assertMaterializedId(
            String documentFields, URI retrievalUri, JsonStructureResolutionOptions options,
            String expectedId) throws Exception {
        RecordingAdapter adapter = new RecordingAdapter();
        JsonStructureTypeGraph graph = new JsonStructureResolver(adapter).resolve(
                Yaml.mapper().readTree(embeddedValueResource(documentFields)),
                retrievalUri,
                options);

        JsonNode materialized = adapter.resources.get("Value");
        assertEquals(materialized.path("$schema").textValue(), SchemaDialectDetector.JSON_STRUCTURE_CORE);
        assertEquals(materialized.path("$id").textValue(), expectedId);
        assertEquals(graph.getComponentRoots().get("Value").getResourceId().toString(), expectedId);
    }

    private String embeddedValueResource(String documentFields) {
        return "openapi: 3.1.0\n"
                + documentFields
                + "jsonSchemaDialect: " + SchemaDialectDetector.JSON_STRUCTURE_CORE + "\n"
                + "components:\n"
                + "  schemas:\n"
                + "    Value:\n"
                + "      name: Value\n"
                + "      type: string\n";
    }

    private String resource(String name, String dialect, String extra) {
        return "    " + name + ":\n"
                + "      $schema: " + dialect + "\n"
                + "      $id: https://example.com/" + name.toLowerCase(Locale.ROOT) + "\n"
                + extra
                + "      name: " + name + "\n"
                + "      type: object\n"
                + "      properties: { value: { type: string } }\n";
    }

    private String indent(String value, int spaces) {
        String prefix = " ".repeat(spaces);
        return value.lines().map(line -> prefix + line + "\n").reduce("", String::concat);
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
