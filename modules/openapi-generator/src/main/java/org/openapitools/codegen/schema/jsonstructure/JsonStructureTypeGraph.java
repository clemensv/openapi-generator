/*
 * Copyright 2026 OpenAPI-Generator Contributors (https://openapi-generator.tech)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.openapitools.codegen.schema.jsonstructure;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class JsonStructureTypeGraph {
    private final Map<QualifiedTypeName, JsonStructureTypeDeclaration> declarations;
    private final Map<String, QualifiedTypeName> componentRoots;
    private final Map<URI, String> componentByResourceId;

    public JsonStructureTypeGraph(
            Map<QualifiedTypeName, JsonStructureTypeDeclaration> declarations,
            Map<String, QualifiedTypeName> componentRoots,
            Map<URI, String> componentByResourceId) {
        this.declarations = Collections.unmodifiableMap(new LinkedHashMap<>(declarations));
        this.componentRoots = Collections.unmodifiableMap(new LinkedHashMap<>(componentRoots));
        this.componentByResourceId = Collections.unmodifiableMap(new LinkedHashMap<>(componentByResourceId));
    }

    public Map<QualifiedTypeName, JsonStructureTypeDeclaration> getDeclarations() {
        return declarations;
    }

    public Map<String, QualifiedTypeName> getComponentRoots() {
        return componentRoots;
    }

    public Map<URI, String> getComponentByResourceId() {
        return componentByResourceId;
    }
}
