/*
 * Copyright 2026 OpenAPI-Generator Contributors (https://openapi-generator.tech)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.openapitools.codegen.schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

public final class SchemaBackendRegistry {
    private final List<SchemaBackend> backends;

    public SchemaBackendRegistry() {
        List<SchemaBackend> discovered = new ArrayList<>();
        discovered.add(new JsonStructureSchemaBackend());
        ServiceLoader.load(SchemaBackend.class).forEach(discovered::add);
        this.backends = List.copyOf(discovered);
    }

    SchemaBackendRegistry(List<SchemaBackend> backends) {
        this.backends = List.copyOf(backends);
    }

    public Optional<SchemaBackend> backendFor(String effectiveDialectUri) {
        return backends.stream()
                .filter(backend -> backend.supports(effectiveDialectUri))
                .findFirst();
    }
}
