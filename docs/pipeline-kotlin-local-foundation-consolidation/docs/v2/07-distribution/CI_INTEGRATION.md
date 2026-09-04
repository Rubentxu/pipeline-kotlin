# Integrating pipeline-kotlin into local and hosted CI

## Principle

Projects pin the CLI version, commit `pipeline.kts` + `pipeline.lock`, and run the same command locally and in hosted CI.

## Generic CI

```bash
pipeline validate --locked
pipeline run --ci
```

## mise example

```toml
[tools]
pipeline = "1.0.0"
```

CI installs/activates mise, then `mise exec -- pipeline run --ci`.

## SDKMAN example

Useful for JVM-centric build images:

```bash
sdk install pipeline 1.0.0
pipeline run --ci
```

## Caching

Cache only documented safe directories (compiler/plugin/download cache). Do not cache active run state as a substitute for artifacts. A future remote cache must be a capability/adapter, not implicit shared filesystem state.

## Reproducibility

CI SHOULD run `pipeline plugins verify --locked` before execution when external plugins are used.
