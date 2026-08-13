/*
 * Copyright 2026 OpenAPI-Generator Contributors (https://openapi-generator.tech)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.openapitools.codegen.schema.jsonstructure;

import java.net.URI;
import java.util.List;
import java.util.Objects;

public final class QualifiedTypeName {
    private final URI resourceId;
    private final String resourceIdentity;
    private final List<String> namespace;
    private final String localName;

    public QualifiedTypeName(URI resourceId, List<String> namespace, String localName) {
        this.resourceId = Objects.requireNonNull(resourceId);
        this.resourceIdentity = resourceId.toString();
        this.namespace = List.copyOf(namespace);
        this.localName = Objects.requireNonNull(localName);
    }

    public URI getResourceId() {
        return resourceId;
    }

    public String getResourceIdentity() {
        return resourceIdentity;
    }

    public List<String> getNamespace() {
        return namespace;
    }

    public String getLocalName() {
        return localName;
    }

    public String displayName() {
        return namespace.isEmpty() ? localName : String.join(".", namespace) + "." + localName;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof QualifiedTypeName)) {
            return false;
        }
        QualifiedTypeName that = (QualifiedTypeName) other;
        return resourceIdentity.equals(that.resourceIdentity)
                && namespace.equals(that.namespace)
                && localName.equals(that.localName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(resourceIdentity, namespace, localName);
    }

    @Override
    public String toString() {
        return resourceIdentity + "#" + displayName();
    }
}
