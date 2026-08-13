/*
 * Copyright 2026 OpenAPI-Generator Contributors (https://openapi-generator.tech)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.openapitools.codegen.schema.jsonstructure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class JsonStructureTypeGraph {
    private final Map<QualifiedTypeName, JsonStructureTypeDeclaration> declarations;
    private final Map<String, QualifiedTypeName> componentRoots;
    private final Map<String, String> componentByResourceId;
    private final Map<String, JsonStructureResource> resources;
    private final Map<String, JsonStructureResource> resourcesById;
    private final boolean sdkValidated;

    public JsonStructureTypeGraph(
            Map<QualifiedTypeName, JsonStructureTypeDeclaration> declarations,
            Map<String, QualifiedTypeName> componentRoots,
            Map<String, String> componentByResourceId) {
        this(declarations, componentRoots, componentByResourceId, Map.of(), false);
    }

    public JsonStructureTypeGraph(
            Map<QualifiedTypeName, JsonStructureTypeDeclaration> declarations,
            Map<String, QualifiedTypeName> componentRoots,
            Map<String, String> componentByResourceId,
            Map<String, JsonStructureResource> resources) {
        this(declarations, componentRoots, componentByResourceId, resources, false);
    }

    public JsonStructureTypeGraph(
            Map<QualifiedTypeName, JsonStructureTypeDeclaration> declarations,
            Map<String, QualifiedTypeName> componentRoots,
            Map<String, String> componentByResourceId,
            Map<String, JsonStructureResource> resources,
            boolean sdkValidated) {
        this.declarations = Collections.unmodifiableMap(new LinkedHashMap<>(declarations));
        this.componentRoots = Collections.unmodifiableMap(new LinkedHashMap<>(componentRoots));
        this.componentByResourceId = Collections.unmodifiableMap(new LinkedHashMap<>(componentByResourceId));
        this.resources = Collections.unmodifiableMap(new LinkedHashMap<>(resources));
        Map<String, JsonStructureResource> byId = new LinkedHashMap<>();
        resources.values().forEach(resource -> byId.put(resource.getId().toString(), resource));
        this.resourcesById = Collections.unmodifiableMap(byId);
        this.sdkValidated = sdkValidated;
    }

    public Map<QualifiedTypeName, JsonStructureTypeDeclaration> getDeclarations() {
        return declarations;
    }

    public Map<String, QualifiedTypeName> getComponentRoots() {
        return componentRoots;
    }

    public Map<String, String> getComponentByResourceId() {
        return componentByResourceId;
    }

    public Map<String, JsonStructureResource> getResources() {
        return resources;
    }

    public boolean isSdkValidated() {
        return sdkValidated;
    }

    public JsonStructureResource resource(QualifiedTypeName name) {
        return resourcesById.get(name.getResourceIdentity());
    }

    public Map<String, JsonStructureTypeUse> effectiveProperties(QualifiedTypeName name) {
        return Collections.unmodifiableMap(effectiveProperties(name, new LinkedHashSet<>()));
    }

    private Map<String, JsonStructureTypeUse> effectiveProperties(
            QualifiedTypeName name, Set<QualifiedTypeName> visiting) {
        if (!visiting.add(name)) {
            throw new IllegalStateException("Cyclic JSON Structure inheritance at " + name);
        }
        JsonStructureTypeDeclaration declaration = requireDeclaration(name);
        Map<String, JsonStructureTypeUse> properties = new LinkedHashMap<>();
        for (QualifiedTypeName base : declaration.getBases()) {
            effectiveProperties(base, visiting).forEach(properties::putIfAbsent);
        }
        properties.putAll(declaration.getProperties());
        visiting.remove(name);
        return properties;
    }

    public List<List<String>> effectiveRequiredAlternatives(QualifiedTypeName name) {
        List<List<String>> result = effectiveRequiredAlternatives(name, new LinkedHashSet<>());
        if (result.size() == 1 && result.get(0).isEmpty()) {
            return List.of();
        }
        return result;
    }

    private List<List<String>> effectiveRequiredAlternatives(
            QualifiedTypeName name, Set<QualifiedTypeName> visiting) {
        if (!visiting.add(name)) {
            throw new IllegalStateException("Cyclic JSON Structure inheritance at " + name);
        }
        JsonStructureTypeDeclaration declaration = requireDeclaration(name);
        List<List<String>> result = List.of(List.of());
        for (QualifiedTypeName base : declaration.getBases()) {
            result = combineRequired(result, effectiveRequiredAlternatives(base, visiting));
        }
        result = combineRequired(result, declaration.getRequiredAlternatives());
        visiting.remove(name);
        return result;
    }

    private List<List<String>> combineRequired(
            List<List<String>> left, List<List<String>> right) {
        if (right.isEmpty()) {
            return left;
        }
        List<List<String>> result = new ArrayList<>();
        for (List<String> leftAlternative : left) {
            for (List<String> rightAlternative : right) {
                Set<String> combined = new LinkedHashSet<>(leftAlternative);
                combined.addAll(rightAlternative);
                result.add(List.copyOf(combined));
            }
        }
        return result;
    }

    public List<String> effectiveTupleOrder(QualifiedTypeName name) {
        return List.copyOf(effectiveTupleOrder(name, new LinkedHashSet<>()));
    }

    private Set<String> effectiveTupleOrder(
            QualifiedTypeName name, Set<QualifiedTypeName> visiting) {
        if (!visiting.add(name)) {
            throw new IllegalStateException("Cyclic JSON Structure inheritance at " + name);
        }
        JsonStructureTypeDeclaration declaration = requireDeclaration(name);
        Set<String> order = new LinkedHashSet<>();
        for (QualifiedTypeName base : declaration.getBases()) {
            order.addAll(effectiveTupleOrder(base, visiting));
        }
        order.addAll(declaration.getTupleOrder());
        visiting.remove(name);
        return order;
    }

    public JsonWireKind effectiveWireKind(QualifiedTypeName name) {
        return effectiveWireKind(name, new LinkedHashSet<>());
    }

    private JsonWireKind effectiveWireKind(
            QualifiedTypeName name, Set<QualifiedTypeName> visiting) {
        if (!visiting.add(name)) {
            return JsonWireKind.ANY;
        }
        JsonStructureTypeDeclaration declaration = requireDeclaration(name);
        if (declaration.getKind() != JsonStructureTypeKind.ALIAS) {
            return declaration.getWireKind();
        }
        JsonStructureTypeUse use = declaration.getDeclaredType();
        if (use != null && use.isReference()) {
            return effectiveWireKind(use.getReference(), visiting);
        }
        return JsonWireKind.ANY;
    }

    public Boolean effectiveAdditionalPropertiesAllowed(QualifiedTypeName name) {
        JsonStructureTypeDeclaration declaration = requireDeclaration(name);
        if (declaration.getKind() != JsonStructureTypeKind.OBJECT) {
            return null;
        }
        if (declaration.getAdditionalPropertiesType() != null) {
            return true;
        }
        if (declaration.getAdditionalPropertiesAllowed() != null) {
            return declaration.getAdditionalPropertiesAllowed();
        }
        // Core makes abstract objects open; ordinary objects also default to open
        // when additionalProperties is absent.
        return true;
    }

    private JsonStructureTypeDeclaration requireDeclaration(QualifiedTypeName name) {
        JsonStructureTypeDeclaration declaration = declarations.get(name);
        if (declaration == null) {
            throw new IllegalArgumentException("Unknown JSON Structure declaration " + name);
        }
        return declaration;
    }
}
