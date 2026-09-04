# SPEC-LFC-010 — Plugin resolution, manifests and lockfile

**Status:** proposed

## Two-phase compilation

Inspired by Gradle Kotlin DSL's plugin-aware model availability:

```text
Phase A: resolve plugin set -> verify -> read static manifests -> build compile classpath
Phase B: compile pipeline with generated typed plugin façades -> canonical IR
```

A pipeline MUST NOT arbitrarily download executable plugin code during step execution.

## Manifest

```yaml
apiVersion: pipeline.dev/v1
kind: PipelinePlugin
metadata:
  id: pipeline.junit
  version: 1.4.0
spec:
  pluginApi: 1
  runtime: ">=1.0 <2"
  extensions:
    steps: [junit.junit]
  permissions:
    workspace: [read]
```

## Lockfile

```yaml
lockVersion: 1
plugins:
  - id: pipeline.junit
    version: 1.4.0
    source: maven
    coordinates: dev.rubentxu.pipeline:junit-plugin:1.4.0
    sha256: ...
```

The compile cache key includes source digest, DSL/compiler version and lock digest.

## Discovery

A JVM mechanism such as `ServiceLoader` may instantiate a provider **after** the verified plugin JAR set is known. It must not be used as an uncontrolled resolver.
