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

URI matching is exact and byte-for-byte. An unrecognized URI is not interpreted
as OAS/JSON Schema. A custom meta-schema can be registered through
`JsonStructureResolutionOptions.addVerifiedCustomMetaSchema(...)` only after
the caller has resolved it offline and verified which canonical meta-schema it
imports; the frontend does not infer semantics from its URI. Custom add-in
names exposed by that meta-schema must likewise be registered explicitly with
`addVerifiedCustomMetaSchemaOffer(...)`.

Canonical activation follows OAS binding §§4.2–4.5:

- **core** enables Core only;
- **extended** enables `JSONStructureImport` by default and merely offers
  Alternate Names, Units, Validation, and Conditional Composition for explicit
  activation through that schema resource's own `$uses`;
- **validation** enables all extended add-ins by default.

## Supported schema processing

The JSON Structure backend:

- preserves the raw OpenAPI Description before Swagger Parser normalization;
- materializes missing `$schema`/`$id` before SDK validation; default `$id` base
  precedence is `$self`, encapsulating entity, retrieval URI, then configured
  application default, followed by the Schema Object's document-root pointer;
- preserves resource identities and qualified definition namespaces;
- resolves local `$ref` and `$extends`;
- resolves in-description `$import` and `$importdefs` under the extended and
  validation dialects;
- rejects unresolved external imports, duplicate identities, import cycles,
  inheritance cycles, conflicting imports, and unrepresentable resolution
  graphs;
- supports precise primitive types, objects, arrays, sets, maps, tuples,
  choices, reusable unions, inheritance, required-property alternatives, and
  typed `additionalProperties`, primitive `enum`, primitive `const`, and
  binary encoding, compression, and media-type annotations;
- keeps JSON Structure schemas out of OAS normalization and inline-model
  flattening.

Definition-only components are supported as type libraries and are not emitted
as empty component models.

### Core frontend conformance baseline

The frontend follows
[JSON Structure Core draft-04](https://json-structure.github.io/core/draft-vasters-json-structure-core.html)
(repository HEAD `7371ab9`, tag `draft-vasters-json-structure-core-04`,
content commit `395e80e`)
and the current Core v0 meta-schema (meta repository commit `c5efa13`). The
draft is authoritative where the published meta-schema is stale. In particular,
the frontend accepts `int8`, `uint8`, `int16`, `uint16`, `float8`, and `any`;
applies decimal defaults of precision 34 and scale 7; and enforces the draft's
abstract/additional-properties rule.

The following conservative resolutions are covered by tests:

- OpenAPI-embedded `$schema` and `$id` defaults follow the [JSON Structure
  OAS binding](https://json-structure.github.io/oas-binding/draft-vasters-json-structure-oas-binding.html).
  Every embedded root with `type` requires `name`, regardless of type
  kind; `$root` resources and definition-only libraries may use their keyed
  identities without a redundant document name.
- A declaration embedded in `definitions` or a property gets its name from its
  key. An explicit `name`, when supplied, must match that keyed identity.
- `$extends` is accepted on inline `choice` because the Core inline-choice
  section and meta-schema require it, despite the general `$extends` paragraph
  naming only objects and tuples.
- A type-reference object may carry `description`, as expressly allowed by the
  Core type-reference rule and represented by the meta-schema. The frontend
  retains that context; the SDK owns validation of other reference members.
- Inline compound members of a non-discriminated union are rejected. This
  follows the normative union rule and meta-schema despite one contradictory
  map example in the draft. Union member order and `first-match` semantics are
  retained explicitly in graph and codegen metadata.

### SDK validation boundary

OpenAPI Generator does **not** implement a JSON Structure schema validator,
instance validator, or primitive codec. Before ingestion, the resolver passes
standalone-materialized resources to a `JsonStructureValidationAdapter`. The
default adapter delegates to the official Java JSON Structure SDK when that SDK
is present on the runtime class path. This keeps keyword placement, primitive
lexical/range checking, binary/date/time codecs, and the canonical conformance
contract owned by the SDK.

When the Java SDK is unavailable, the adapter is explicitly inactive and the
frontend assumes that input was validated with an official SDK. The graph exposes
`isSdkValidated()`; codegen exposes `jsonStructureSdkValidated` and
`x-json-structure-sdk-validated`, so downstream generators cannot mistake that
path for SDK validation. The remaining
resolver diagnostics are limited to information required to construct a stable
generator graph: embedded resource identity, parseable structural members,
local/imported reference resolution, inheritance, add-in replacement, cycles,
conflicts, and codegen naming. The repository tests cover this adapter boundary
and generator-specific graph behavior; they do not copy the SDK's schema or
instance-validation vectors. The canonical cross-language contracts remain in
the [JSON Structure SDK test assets](https://github.com/json-structure/sdk/tree/master/test-assets).

`$offers` and `$uses` are represented at resource level. Their effective type
identities, import rebinding, replacement targets, and graph conflicts survive
into codegen metadata, but applying add-ins while validating JSON instances is
SDK/runtime behavior.

## Generator behavior

All generators receive JSON Structure declarations through the common
`CodegenModel` and `CodegenProperty` path. Models expose the dialect, logical
type, wire type, effective resource, effective qualified name, source resource,
namespace, abstract status, effective required alternatives, tuple order and
element types, choice order, ordered union alternatives and first-match policy,
precision/scale, examples, additional-properties policy, and offered/active
add-ins. Referenced properties expose the same effective/source identity and
contextual-reference metadata.

The configured generator package or module remains authoritative. JSON
Structure namespaces are preserved in metadata. Java maps each schema
resource to a subpackage of the configured model package and retains a
globally unique flattened class name within it; other generators flatten the
resource and declaration namespaces into generated model names. For example,
Java places `ConsumerCommonGeometryPoint` and
`ConsumerCommonMetadataPoint` in `org.openapitools.client.model.Consumer`.

Each resource scope uses its explicit `name`, or a stable name materialized
from the OAS component key when absent. Duplicate resource names are rejected
because they would collide in the aggregate namespace. Definition-only
resources remain available for import resolution, but client models are emitted
from rooted resources; `$import` and `$importdefs` copies are emitted under the
importing resource's namespace rather than duplicated under an unreferenced
source namespace.

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

Imports whose fragment-free URI matches the exact explicit `$id` of another
JSON Structure Schema Object in the same OpenAPI Description resolve directly.
Import targets must declare `$id`; constructed default IDs are not import
targets. External self-contained resources can be supplied through
`JsonStructureResolutionOptions.addExternalResource(...)`; they are SDK
validated and participate in the same copy-not-link resolution. The generator
does not perform implicit network retrieval, so callers retain transport
policy, authentication, size limits, caching, and allow-list control.

Ordinary OpenAPI `$ref` continues to reference whole component Schema Objects.
Inside a JSON Structure Schema Object, `$ref` is valid only as the value of
`type` and addresses `#/definitions/...`.

JSON Structure Schema Objects currently need to be declared under
`components.schemas`. Inline JSON Structure schemas in operations, parameters,
headers, callbacks, or webhooks are rejected with an explicit diagnostic;
reference a component Schema Object instead.

## Focused generation suite

Generator integration coverage uses a compact set of representative descriptions.
Schema/instance conformance remains in the canonical SDK assets; this suite covers:

- safe primitive and annotation mappings;
- arrays, sets, maps, nested named objects, and typed additional properties;
- in-description imports, namespaces, and structural inheritance;
- mixed OAS/JSON Schema and JSON Structure components;
- advanced tuple, choice, union, alternative-required, numeric wire, and
  binary wire shapes.

Strict-safe descriptions are generated with Java, C#, Go, Python,
TypeScript Fetch, Kotlin, Rust, and PHP. Advanced wire shapes verify both the
default strict diagnostic and the explicit compatibility-mode path.

The Docker-backed native build matrix compiles or type-checks all four
strict-safe descriptions plus the explicit compatibility-mode wire-shapes
description with each target's native toolchain:

```shell
mvn -pl modules/openapi-generator-cli -am -DskipTests package
bin/json-structure-native-build.sh
```
