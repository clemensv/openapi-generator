/*
 * Copyright 2026 OpenAPI-Generator Contributors (https://openapi-generator.tech)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.openapitools.codegen.schema.jsonstructure;

import com.fasterxml.jackson.databind.JsonNode;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/** Delegates schema validation to the official Java SDK when it is on the class path. */
final class ReflectiveJsonStructureSdkValidationAdapter implements JsonStructureValidationAdapter {
    private static final String VALIDATOR_CLASS =
            "org.json_structure.validation.SchemaValidator";
    private static final String OPTIONS_CLASS =
            "org.json_structure.validation.ValidationOptions";

    private final Constructor<?> optionsConstructor;
    private final Method setAllowImport;
    private final Method setExternalSchemas;
    private final Constructor<?> validatorConstructor;
    private final Method validate;
    private final Method isValid;
    private final Method getErrorsOnly;

    private ReflectiveJsonStructureSdkValidationAdapter(
            Constructor<?> optionsConstructor,
            Method setAllowImport,
            Method setExternalSchemas,
            Constructor<?> validatorConstructor,
            Method validate,
            Method isValid,
            Method getErrorsOnly) {
        this.optionsConstructor = optionsConstructor;
        this.setAllowImport = setAllowImport;
        this.setExternalSchemas = setExternalSchemas;
        this.validatorConstructor = validatorConstructor;
        this.validate = validate;
        this.isValid = isValid;
        this.getErrorsOnly = getErrorsOnly;
    }

    static JsonStructureValidationAdapter discover() {
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            Class<?> optionsType = Class.forName(OPTIONS_CLASS, true, loader);
            Class<?> validatorType = Class.forName(VALIDATOR_CLASS, true, loader);
            Constructor<?> optionsConstructor = optionsType.getConstructor();
            Method setAllowImport = optionsType.getMethod("setAllowImport", boolean.class);
            Method setExternalSchemas = optionsType.getMethod("setExternalSchemas", Map.class);
            Constructor<?> validatorConstructor = validatorType.getConstructor(optionsType);
            Method validate = validatorType.getMethod("validate", JsonNode.class);
            Class<?> resultType = validate.getReturnType();
            return new ReflectiveJsonStructureSdkValidationAdapter(
                    optionsConstructor,
                    setAllowImport,
                    setExternalSchemas,
                    validatorConstructor,
                    validate,
                    resultType.getMethod("isValid"),
                    resultType.getMethod("getErrorsOnly"));
        } catch (ClassNotFoundException | LinkageError ex) {
            return Unavailable.INSTANCE;
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(
                    "Unsupported JSON Structure Java SDK validation API", ex);
        }
    }

    @Override
    public void validate(Map<String, JsonNode> resources) {
        try {
            Map<String, JsonNode> externalSchemas = new LinkedHashMap<>();
            resources.values().forEach(resource -> {
                JsonNode id = resource.get("$id");
                if (id != null && id.isTextual()) {
                    externalSchemas.put(id.textValue(), resource.deepCopy());
                }
            });

            Object options = optionsConstructor.newInstance();
            setAllowImport.invoke(options, true);
            setExternalSchemas.invoke(options, externalSchemas);
            Object validator = validatorConstructor.newInstance(options);
            for (Map.Entry<String, JsonNode> resource : resources.entrySet()) {
                Object result = validate.invoke(validator, resource.getValue().deepCopy());
                if (!(Boolean) isValid.invoke(result)) {
                    Object errors = getErrorsOnly.invoke(result);
                    String details = errors instanceof Iterable
                            ? stream((Iterable<?>) errors)
                            : String.valueOf(errors);
                    throw new JsonStructureResolutionException(
                            "JSON Structure SDK rejected component "
                                    + resource.getKey() + ": " + details);
                }
            }
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            throw new JsonStructureResolutionException(
                    "JSON Structure SDK validation failed: " + cause.getMessage());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(
                    "Unable to invoke JSON Structure Java SDK validation", ex);
        }
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    private String stream(Iterable<?> errors) {
        return java.util.stream.StreamSupport.stream(errors.spliterator(), false)
                .map(String::valueOf)
                .collect(Collectors.joining("; "));
    }

    private enum Unavailable implements JsonStructureValidationAdapter {
        INSTANCE;

        @Override
        public void validate(Map<String, JsonNode> resources) {
            // Resolution remains usable on the Java 11 baseline. Callers that require
            // schema validation must place the official SDK on the runtime class path.
        }

        @Override
        public boolean isAvailable() {
            return false;
        }
    }
}
