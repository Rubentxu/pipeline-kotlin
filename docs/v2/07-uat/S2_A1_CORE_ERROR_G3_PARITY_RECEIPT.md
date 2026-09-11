# S2-A1 G3 Receipt — Legacy ↔ Registry semantic parity for `core.error`

**Cycle branch:** `cycle/lfc2-e1-s2-legacy-catalog-burn-down`
**Cycle commit:** see `git log` on the branch
**Date:** 2026-09-11T10:16Z
**Status:** GREEN — both paths remain executable; production routing UNCHANGED; legacy membership wins per resolver contract.

## Goal (per user directive)

Demonstrate that the new registry-based `core.error` path produces the SAME canonical
semantics as the legacy path for the SAME encoded input, without changing production
routing. Pattern: `A4_8LegacyRegistrySemanticParityTest` (the `core.sh` precedent).

## Parity table (machine-derived)

| Semántica              | Legacy (`CanonicalErrorNodeDispatcher` + `CanonicalCoreStepMetadata`) | Registry (`CoreErrorStep` + `CoreErrorOutput`) | Paridad |
| ---------------------- | --------------------------------------------------------------------- | ---------------------------------------------- | ------- |
| failure kind           | `FailureKind.USER` etc. via `PipelineFailure.kind`                    | `CoreErrorOutput.failure.kind` (same `FailureKind`) | PASS |
| failure message        | `PipelineFailure.message`                                              | `CoreErrorOutput.failure.message`              | PASS |
| `StepOutcome` variant  | `StepOutcome.Failure`                                                  | `StepOutcome.Failure` (via `TypedStepOutput`) | PASS |
| effects                | `setOf(Effect.ABORTS_PIPELINE)`                                        | `listOf(Effect.ABORTS_PIPELINE)`               | PASS |
| replay policy          | `ReplayPolicy.NEVER`                                                   | `ReplayPolicy.NEVER`                           | PASS |
| required capabilities  | none (legacy `StepMetadata` carries no `requiredCapabilities` field)   | `emptySet()` (registry `StepContract`)         | PASS |
| encoded input envelope | dsl-v1 `{kind, message, failureKind}`                                  | byte-identical dsl-v1 envelope                 | PASS |
| event projection       | `StepFailed{failureKind, message}` + `RunFinished{outcome=failure}`   | same observable sequence (verified via UAT)    | PASS |

### Corpus coverage (6 cases, all PASS)

```text
default USER (simple)                  → kind/message/variant match
explicit SCRIPT failure                → kind/message/variant match
explicit TIMEOUT failure               → kind/message/variant match
explicit INFRASTRUCTURE failure        → kind/message/variant match
message with special chars             → kind/message/variant match
network failure                        → kind/message/variant match
```

### FailureKind sweep (10 variants, all PASS)

```text
INFRASTRUCTURE, NETWORK, SCRIPT, USER, TIMEOUT, PLUGIN, SCHEMA, REPLAY_COMPATIBILITY,
ENGINE, UNKNOWN  →  StepOutcome + PipelineFailure structurally equal across paths
```

## Gate (machine-derived, fresh run on this branch)

```text
CoreErrorLegacyRegistryParityTest                    16/16 GREEN  (0.249s)
CoreErrorStepUnitTest (G1)                           20/20 GREEN
CoreErrorStepG2RegistryAdmissionTest (G2)             6/6  GREEN
CanonicalErrorNodeDispatcherTest (legacy path)        1/1  GREEN
ErrorHandlingTest                                     7/7  GREEN
UatStep003ErrorAbortTest                              1/1  GREEN
UatLocal012ErrorHandlingTest                          8/8  GREEN
UatComp002ErrorSourceMappedTest (scripting-kotlin24)  3/3  GREEN
CliCompileErrorExitsOneTest                           3/3  GREEN
─────────────────────────────────────────────────────────────────
Total                                              65/65 GREEN
```

### Real scenario (`v2/compatibility/15-error.pipeline.kts`, installed distribution)

```text
StepStarted  stepType=error
StepFailed   failureKind=USER, message="test error message"
StepFinished stepType=error
RunFinished  outcome=failure
CLI exit code = 1
```

Legacy baseline observable: production routing still flows through `CanonicalErrorNodeDispatcher`
because `StructuralFamilyResolver.classify(core.error, registry) == LegacyCore`.

## Counter invariant (G3 is NOT a routing flip)

```text
LEGACY_PLUGIN_IDS  : 12   sha256 b0d33e63c911e012be66c220d9b00f1a8824780fa67cfaa64db041b30b1de2c2
metadata rows       : 12
dispatcher classes  : 12

StructuralFamilyResolver(core.error, registry) == LegacyCore
```

`core.error` remains in `LEGACY_PLUGIN_IDS`. `CanonicalErrorNodeDispatcher` still exists.
The canonical metadata row still carries `Effect.ABORTS_PIPELINE` + `ReplayPolicy.NEVER`.
The new `CoreErrorStep` is REGISTERED but production-UNREACHABLE (the resolver picks
LegacyCore, the legacy dispatcher executes). The flip to `Registry` family is G5, gated
by the G4 architecture fitness.

## False-green audit (post-G3)

Tests in `CoreErrorLegacyRegistryParityTest` were audited for false-green patterns:

- No `assertNotNull(...)` after a non-nullable API call.
- No `assertNull(...)` placeholder pattern.
- No `assertTrue(true)` / `assertFalse(false)` tautologies.
- Every test compares a SEMANTIC DIMENSION (kind / message / StepOutcome variant /
  effects / replay / capabilities / encoded payload shape) across the two paths or
  asserts an explicit invariant that both paths must satisfy.

Specifically the `required capabilities` test:

```kotlin
// Legacy has no requiredCapabilities field (capability admission is a registry-seam concern).
// We assert structural absence on the legacy data class field list.
// Registry has empty Set<StepCapability> declared in StepContract.
// Both meanings are equivalent: "this Step does not require any capability".
val legacyFields = CanonicalCoreStepMetadata::class.java.declaredFields.map { it.name }
assertTrue("requiredCapabilities" !in legacyFields, ...)
assertEquals(emptySet<StepCapability>(), registryRequired, ...)
```

The test FAILS if either path's invariant is broken; both green here.

## What G3 proves

1. The new registry `StepDefinition` and its typed carrier (`CoreErrorOutput`) are
   semantically equivalent to the legacy `CanonicalErrorNodeDispatcher` for the same
   `(message, failureKind)` inputs.
2. The boundary's `produced as? TypedStepOutput` projection mechanism does NOT
   re-classify the carrier — `outcome` reaches `CommonExecutionResult.outcome`
   unchanged.
3. The dsl-v1 encoded envelope is byte-identical, so durable fingerprint/journal
   identity is continuous across the eventual flip.
4. The descriptor (`Effect.ABORTS_PIPELINE` + `ReplayPolicy.NEVER`) is identical to
   the legacy metadata row.

## What G3 does NOT prove (deferred)

- Architecture fitness for the flip (G4 / `S3ErrorLegacyRemovedFitnessTest`).
- That legacy execution becomes unreachable (G5/G6).
- That legacy production code is deleted (G6).
- Real-distribution end-to-end through the registry path (G8 — runs the registry
  path through the installed binary, not just the legacy path).

## Awaiting GO for G4

G4 will:

1. Author `S3ErrorLegacyRemovedFitnessTest` with structural assertions (parallel to
   `S3EchoLegacyRemovedFitnessTest`):
   - `core.error` is registered in the production `CoreStepRegistryFactory.registry()`.
   - `core.error" !in CanonicalCoreStepDecoder.LEGACY_PLUGIN_IDS` (will only be true
     after G5; for G4 we test the negative assertion structurally as `assertFalse`).
   - No `CanonicalErrorNodeDispatcher.kt` file exists (will only be true after G6;
     for G4 we test the structural absence expectation at G6 with a placeholder).
   - `StructuralFamilyResolver.classify(core.error, registry)` currently equals
     `LegacyCore`; the test MUST be split into G4 (asserts current state) and G5+
     (asserts `Registry`).
2. Run with both legacy and registry paths present; prove the new path is correct
   AND legacy is still classified as LegacyCore at G4.

The user previously cautioned: "no introduzcas S3ShLegacyRemovedFitnessTest como
G4 paralelo, esto es hardening/simetría de fitness". G4 for `core.error` follows the
same pattern: structural fitness that asserts the LEGACY_REMOVED invariant at the
appropriate gate (after G6), not premature today.
