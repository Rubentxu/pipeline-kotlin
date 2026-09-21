# ADR-LFC-003 — Jenkins-like type-safe builder DSL

**Status:** proposed

## Context

The DSL must remain immediately familiar to Jenkins users but current broad receivers permit invalid combinations and several APIs pretend to have runtime semantics.

## Decision

Use narrow Kotlin type-safe builders with `@DslMarker`, a closed `StageBody`, and Jenkins-recognizable names/structure. Declarative DSL only builds IR.

## Consequences

Compiler/IDE prevent many invalid placements; migration remains intuitive; DSL code no longer executes hidden effects.
