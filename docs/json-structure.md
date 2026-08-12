---
id: json-structure
title: JSON Structure schemas
---

OpenAPI Generator can process JSON Structure Schema Objects embedded in an
OpenAPI 3.1 Description alongside ordinary OAS/JSON Schema Schema Objects.
The effective dialect is selected from a Schema Object's `$schema` or the
Description's `jsonSchemaDialect`.

Recognized dialect URIs are:

- `https://json-structure.org/meta/core/v0/#`
- `https://json-structure.org/meta/extended/v0/#`
- `https://json-structure.org/meta/validation/v0/#`

URI matching is exact. An unrecognized URI is not interpreted as OAS/JSON
Schema.

## Supported schema processing

The JSON Structure backend:

- preserves the raw OpenAPI Description before Swagger Parser normalization;
- constructs a default `$id` from `$self` or the retrieval URI and the Schema
  Object's JSON Pointer;
- preserves resource identities and qualified definition namespaces;
- resolves local `$ref` and `$extends`;
- resolves in-description `$import` and `$importdefs` under the extended and
  validation dialects;
- rejects external imports, duplicate identities, import cycles, inheritance
  cycles, conflicting imports, and malformed Core declarations;
- supports precise primitive types, objects, arrays, sets, maps, tuples,
  choices, reusable unions, inheritance, required-property alternatives, and
  typed `additionalProperties`, primitive `enum`, primitive `const`, and
  binary encoding, compression, and media-type annotations;
- keeps JSON Structure schemas out of OAS normalization and inline-model
  flattening.

Definition-only components are supported as type libraries and are not emitted
as empty component models.

## Generator behavior

All generators receive JSON Structure declarations through the common
`CodegenModel` and `CodegenProperty` path. Models expose the dialect, logical
type, wire type, source resource, namespace, abstract status, required
alternatives, tuple order, and choice metadata.

Exact wire support is intentionally strict. Generation fails when the selected
generator cannot guarantee the JSON Structure representation for:

- `int64`, `uint64`, `int128`, `uint128`, and `decimal`, which are strings on
  the JSON wire;
- tuples, which are positional JSON arrays;
- tagged or inline choices;
- reusable and mixed unions where a generator cannot preserve all alternatives;
- non-base64 binary encodings and compressed binary values;
- mutually exclusive alternative required-property sets.

To generate target-native compatibility types despite those limitations, opt
in explicitly:

```shell
openapi-generator-cli generate \
  -i openapi.yaml \
  -g java \
  -p jsonStructureCompatibilityMode=true
```

Compatibility mode preserves logical and wire metadata and emits a warning,
but existing generator serializers may use their conventional OAS wire shape.
For example, a target may hold an `int64` as a native 64-bit integer while its
default JSON serializer writes a JSON number instead of the JSON Structure
string representation.

## Import boundary

The first implementation milestone resolves only imports whose fragment-free
URI matches the explicit `$id` of another JSON Structure Schema Object in the
same OpenAPI Description. Import targets must declare `$id`; constructed
default IDs are not import targets. Network retrieval is not enabled.

Ordinary OpenAPI `$ref` continues to reference whole component Schema Objects.
Inside a JSON Structure Schema Object, `$ref` is valid only as the value of
`type` and addresses `#/definitions/...`.

JSON Structure Schema Objects currently need to be declared under
`components.schemas`. Inline JSON Structure schemas in operations, parameters,
headers, callbacks, or webhooks are rejected with an explicit diagnostic;
reference a component Schema Object instead.
