# KSP code-generation contract

## Inputs

- plugin implementation signatures/annotations;
- extension kind;
- typed input/output classes;
- capability declarations;
- effect/replay annotations;
- Jenkins compatibility annotation/catalogue entry;
- plugin identity/version supplied by build configuration.

## Outputs

For each extension generate deterministic artifacts:

```text
Generated<Plugin>Dsl.kt
Generated<Plugin>Descriptors.kt
META-INF/pipeline/plugin.json fragment
META-INF/pipeline/extensions/<id>.json
META-INF/pipeline/schemas/<id>-input.json
META-INF/pipeline/schemas/<id>-output.json
META-INF/pipeline/docs/<id>.md
```

## No hardcoded step table

The processor MUST NOT switch on names like `sh`, `echo`, `junit` to choose semantics. Core steps use the same metadata path as third-party steps.

## Validation failures

Compilation fails for:

- duplicate extension ID;
- unsupported/unserializable public input/output;
- declared capability/permission mismatch;
- invalid compatibility metadata;
- unsupported overloaded façades that generate ambiguous DSL calls;
- incompatible plugin API target.

## Determinism

Given identical sources/toolchain/config, generated content must be byte-stable except for explicitly excluded build metadata. Golden tests compare generated artifacts.
