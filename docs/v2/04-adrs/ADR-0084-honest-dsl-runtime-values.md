---
type: adr
id: ADR-0084
title: "Declarative DSL describes IR; runtime values require an explicit scripted runtime"
status: proposed
date: 2026-09-18
deciders: "Rubentxu (product owner)"
supersedes: null
superseded_by: null
related:
  - ADR-0065
  - ADR-0070
  - ADR-0071
  - ADR-0074
  - docs/v2/05-roadmap/LFC2_HONEST_DSL_CLOSURE.md
---

# ADR-0084 — Honest DSL and runtime values

## Context

The Jenkins-like DSL is useful, but a declarative builder cannot truthfully return runtime values such as workspace path or OS detection during construction. Historical bridges/fallbacks (`pwd`, `isUnix`, eager condition handling) mix description and execution and make invalid programs look valid.

## Decision

Two explicit surfaces:

1. **Declarative DSL** constructs typed IR only.
2. **Scripted Runtime DSL** invokes runtime Steps/capabilities and may return typed values under durable/replay semantics.

No public declarative builder can fabricate a runtime value. Unsupported combinations fail before effects.

Introduce a common `@DslMarker` and split the current large DSL by model/builders/core/registry/scripted ownership as needed to reduce receiver ambiguity and connascence.

## Examples

Allowed declarative:

```kotlin
pipeline {
    stages {
        stage("Build") {
            steps { sh("./gradlew build") }
        }
    }
}
```

Runtime-returning:

```kotlin
script {
    val unix = isUnix()
    if (unix) sh("./gradlew test")
}
```

Rejected: `pwd()` returning `<workspace>` or `isUnix()` returning a fallback boolean during declaration.

## Compatibility

The first LPR release freezes only the supported subset. Existing dishonest surfaces may be removed, made experimental, or fail-closed before the compatibility contract is declared stable.
