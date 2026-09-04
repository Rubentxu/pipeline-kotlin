# Module boundaries

## Proposed logical modules

Names may be adjusted to fit the existing Gradle layout; dependencies are normative.

```text
pipeline-domain
pipeline-application
pipeline-dsl-api
pipeline-script-kotlin
pipeline-plugin-api
pipeline-plugin-ksp
pipeline-plugin-runtime
pipeline-plugin-testkit
pipeline-runtime-local
pipeline-cli
```

## Dependency direction

```text
pipeline-domain <- pipeline-application <- pipeline-runtime-local <- pipeline-cli
       ^                 ^                       ^
       |                 |                       |
pipeline-plugin-api -----+---- pipeline-plugin-runtime
       ^
       |
pipeline-dsl-api <- pipeline-script-kotlin
       ^
       |
pipeline-plugin-ksp (compile-time tooling; no runtime adapter dependency)
```

The exact graph should remain acyclic.

## Forbidden dependencies

- `pipeline-domain` -> application/adapters/CLI;
- `pipeline-application` -> `*-local`, Git CLI adapter, credential implementation, concrete database;
- plugin API/handlers -> local runtime implementation;
- DSL API -> process/filesystem/credential implementations;
- KSP processor -> hardcoded core step implementation list.

## Test-only modules

Recording/in-memory adapters used only for tests move to `pipeline-plugin-testkit` or a dedicated test fixture module. Production domain packages should not contain testing adapters unless they are intentionally supported reference implementations.
