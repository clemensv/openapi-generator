/*
 * Copyright 2026 OpenAPI-Generator Contributors (https://openapi-generator.tech)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.openapitools.codegen.schema.jsonstructure;

public enum JsonStructureTypeKind {
    STRING,
    NUMBER,
    INT8,
    UINT8,
    INT16,
    UINT16,
    INT32,
    UINT32,
    INT64,
    UINT64,
    INT128,
    UINT128,
    FLOAT8,
    FLOAT,
    DOUBLE,
    DECIMAL,
    BOOLEAN,
    NULL,
    BINARY,
    DATE,
    DATETIME,
    TIME,
    DURATION,
    UUID,
    URI,
    JSON_POINTER,
    OBJECT,
    ARRAY,
    SET,
    MAP,
    TUPLE,
    CHOICE,
    UNION,
    ANY;

    public static JsonStructureTypeKind fromName(String name) {
        switch (name) {
            case "string":
                return STRING;
            case "number":
                return NUMBER;
            case "integer":
            case "int32":
                return INT32;
            case "int8":
                return INT8;
            case "uint8":
                return UINT8;
            case "int16":
                return INT16;
            case "uint16":
                return UINT16;
            case "uint32":
                return UINT32;
            case "int64":
                return INT64;
            case "uint64":
                return UINT64;
            case "int128":
                return INT128;
            case "uint128":
                return UINT128;
            case "float8":
                return FLOAT8;
            case "float":
                return FLOAT;
            case "double":
                return DOUBLE;
            case "decimal":
                return DECIMAL;
            case "boolean":
                return BOOLEAN;
            case "null":
                return NULL;
            case "binary":
                return BINARY;
            case "date":
                return DATE;
            case "datetime":
                return DATETIME;
            case "time":
                return TIME;
            case "duration":
                return DURATION;
            case "uuid":
                return UUID;
            case "uri":
                return URI;
            case "jsonpointer":
                return JSON_POINTER;
            case "object":
                return OBJECT;
            case "array":
                return ARRAY;
            case "set":
                return SET;
            case "map":
                return MAP;
            case "tuple":
                return TUPLE;
            case "choice":
                return CHOICE;
            case "any":
                return ANY;
            default:
                throw new IllegalArgumentException("Unknown JSON Structure type: " + name);
        }
    }

    public JsonWireKind wireKind() {
        switch (this) {
            case INT64:
            case UINT64:
            case INT128:
            case UINT128:
            case DECIMAL:
            case STRING:
            case BINARY:
            case DATE:
            case DATETIME:
            case TIME:
            case DURATION:
            case UUID:
            case URI:
            case JSON_POINTER:
                return JsonWireKind.STRING;
            case NUMBER:
            case INT8:
            case UINT8:
            case INT16:
            case UINT16:
            case INT32:
            case UINT32:
            case FLOAT8:
            case FLOAT:
            case DOUBLE:
                return JsonWireKind.NUMBER;
            case BOOLEAN:
                return JsonWireKind.BOOLEAN;
            case NULL:
                return JsonWireKind.NULL;
            case ARRAY:
            case SET:
            case TUPLE:
                return JsonWireKind.ARRAY;
            case OBJECT:
            case MAP:
            case CHOICE:
                return JsonWireKind.OBJECT;
            case ANY:
            case UNION:
                return JsonWireKind.ANY;
            default:
                throw new IllegalStateException("Missing wire mapping for " + this);
        }
    }
}
