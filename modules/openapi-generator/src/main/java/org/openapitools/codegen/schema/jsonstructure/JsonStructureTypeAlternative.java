/*
 * Copyright 2026 OpenAPI-Generator Contributors (https://openapi-generator.tech)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.openapitools.codegen.schema.jsonstructure;

import java.util.Objects;

/** An ordered member of a JSON Structure type union. */
public final class JsonStructureTypeAlternative {
    private final JsonStructureTypeKind primitiveKind;
    private final String primitiveName;
    private final QualifiedTypeName reference;
    private final String referenceDescription;

    private JsonStructureTypeAlternative(
            JsonStructureTypeKind primitiveKind,
            String primitiveName,
            QualifiedTypeName reference,
            String referenceDescription) {
        this.primitiveKind = primitiveKind;
        this.primitiveName = primitiveName;
        this.reference = reference;
        this.referenceDescription = referenceDescription;
    }

    public static JsonStructureTypeAlternative primitive(
            JsonStructureTypeKind kind, String sourceName) {
        return new JsonStructureTypeAlternative(
                Objects.requireNonNull(kind),
                Objects.requireNonNull(sourceName),
                null,
                null);
    }

    public static JsonStructureTypeAlternative reference(
            QualifiedTypeName reference, String description) {
        return new JsonStructureTypeAlternative(
                null,
                null,
                Objects.requireNonNull(reference),
                description);
    }

    public boolean isPrimitive() {
        return primitiveKind != null;
    }

    public boolean isReference() {
        return reference != null;
    }

    public JsonStructureTypeKind getPrimitiveKind() {
        return primitiveKind;
    }

    public String getPrimitiveName() {
        return primitiveName;
    }

    public QualifiedTypeName getReference() {
        return reference;
    }

    public String getReferenceDescription() {
        return referenceDescription;
    }

    public String identity() {
        return isPrimitive() ? primitiveName : reference.toString();
    }
}
