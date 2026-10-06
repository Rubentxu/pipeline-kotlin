# ADR-EVO-011 — Separate compilation, loading and per-run evaluation

**Status:** Proposed. **Scope:** spec 19; M0 characterization and M7 implementation.

## Context

Canonical scripting already defines compile/evaluate ports. Observed `ScriptingHost.compile` combines them via `evalWithTemplate`. Gradle generates class files at compilation and instantiates the loaded program later. Reuse needs explicit ownership of values/effects.

## Decision

Implement the canonical split inside the existing Kotlin adapter, preserving public consumers until inventory/ABI migration is approved. Compiled artifacts contain code/metadata, not prior evaluated results. Fresh evaluation produces declarative IR or retains scripted entry-point semantics; current runtime authorities execute operations.

Keep Kotlin experimental objects private. Reuse `ScriptedArtifactIdentity` and `CompiledScriptedEntryPoint`. Preserve validate, construction rejection, return-value behaviour and source locations through real corpus tests. No third engine/dispatcher.

## Consequences and alternatives

Caching `evalWithTemplate` results risks retaining run state and suppressing construction checks. A Gradle runtime dependency imports unrelated model/lifecycle responsibilities. The split requires a real pinned-backend representation and compatible consumer migration. Unsupported representation gives NO-GO for reuse without changing existing public semantics.

## Acceptance

UAT-054/055/062/073; AAT-021/022/026. Proposed is not implemented or accepted in SDDK.
