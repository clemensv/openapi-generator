/*
 * Copyright 2026 OpenAPI-Generator Contributors (https://openapi-generator.tech)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.openapitools.codegen.schema.jsonstructure;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;

public final class JsonStructureModelCatalog {
    private final Map<String, JsonStructureTypeDeclaration> declarationsByModelName = new LinkedHashMap<>();
    private final Map<QualifiedTypeName, String> modelNamesByDeclaration = new LinkedHashMap<>();
    private final Set<String> generatedModelNames = new LinkedHashSet<>();
    private final Set<String> reservedModelNames;
    private final Set<String> resourceComponentNames;

    public JsonStructureModelCatalog(JsonStructureTypeGraph graph) {
        this(graph, Set.of());
    }

    public JsonStructureModelCatalog(JsonStructureTypeGraph graph, Set<String> reservedModelNames) {
        this.reservedModelNames = new LinkedHashSet<>(reservedModelNames);
        this.reservedModelNames.removeAll(graph.getComponentRoots().keySet());
        this.resourceComponentNames = new LinkedHashSet<>(graph.getComponentByResourceId().values());
        graph.getComponentRoots().forEach((componentName, root) ->
                register(componentName, graph.getDeclarations().get(root), true));

        graph.getDeclarations().values().forEach(declaration -> {
            if (!modelNamesByDeclaration.containsKey(declaration.getName())) {
                String componentName = graph.getComponentByResourceId()
                        .getOrDefault(declaration.getName().getResourceId(), "JsonStructure");
                String candidate = componentName + "_" + declaration.getName().displayName().replace('.', '_');
                register(
                        uniqueName(candidate),
                        declaration,
                        shouldGenerateModel(declaration));
            }
        });
    }

    public Set<String> modelNames() {
        return generatedModelNames;
    }

    public Set<String> schemaNames() {
        return declarationsByModelName.keySet();
    }

    public JsonStructureTypeDeclaration declaration(String modelName) {
        return declarationsByModelName.get(modelName);
    }

    public String modelName(QualifiedTypeName declarationName) {
        String result = modelNamesByDeclaration.get(declarationName);
        if (result == null) {
            throw new IllegalArgumentException("No generated model name for " + declarationName);
        }
        return result;
    }

    public boolean isGeneratedModel(String modelName) {
        return generatedModelNames.contains(modelName);
    }

    public boolean isResourceComponent(String modelName) {
        return resourceComponentNames.contains(modelName);
    }

    private void register(
            String modelName,
            JsonStructureTypeDeclaration declaration,
            boolean generateModel) {
        if (declaration == null) {
            throw new IllegalArgumentException("JSON Structure root declaration was not resolved");
        }
        declarationsByModelName.put(modelName, declaration);
        modelNamesByDeclaration.put(declaration.getName(), modelName);
        if (generateModel) {
            generatedModelNames.add(modelName);
        }
    }

    private String uniqueName(String candidate) {
        String result = candidate;
        int suffix = 2;
        while (declarationsByModelName.containsKey(result) || reservedModelNames.contains(result)) {
            result = candidate + "_" + suffix++;
        }
        return result;
    }

    private boolean shouldGenerateModel(JsonStructureTypeDeclaration declaration) {
        if (declaration.isAbstractType()) {
            return false;
        }
        return !declaration.isSynthetic()
                || declaration.getKind() == JsonStructureTypeKind.OBJECT
                || declaration.getKind() == JsonStructureTypeKind.TUPLE
                || declaration.getKind() == JsonStructureTypeKind.CHOICE;
    }
}
