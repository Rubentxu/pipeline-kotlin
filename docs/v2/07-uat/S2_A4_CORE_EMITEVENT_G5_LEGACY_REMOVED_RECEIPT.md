# S2-A4 / G5 — LEGACY_REMOVED: `core.emit.event`

**Slice:** S2-A4 (`core.emit.event` burn-down, G0..G4 complete)
**Gate:** G5 — physical removal of the legacy execution authority
**Counters after this gate:** Certified Steps with legacy executable: unchanged; residual legacy set: **8 / 8 / 8** (IDs / metadata rows / per-Step dispatcher files).

> core.emit.event legacy execution authority is physically removed. The raw-envelope
> structural overlay protocol for catchError remains intentionally alive.

## Removals (production)

1. `CanonicalCoreStepDecoder.kt` — `data class EmitEvent` command subtype deleted; `EMIT_EVENT_PLUGIN_ID` constant and decoder branch deleted.
2. `CanonicalNodeDispatcher.kt` — `emitEventDispatcher` field, `EmitEvent` when-branch and `emitEventContext()` helper deleted.
3. `CanonicalEmitEventNodeDispatcher.kt` — file deleted (main).
4. `CanonicalCoreStepMetadata.kt` — `"core.emit.event"` row deleted (table: 9 → 8).

## Removals / archives (tests)

5. `CanonicalEmitEventNodeDispatcherTest.kt` — deleted.
6. `CanonicalCoreStepCommandRegistryTest` — `EmitEvent has correct pluginId` test deleted; `sealedSubclasses` count 9 → 8.
7. `CanonicalCoreStepDecoderTest` — `decodes emitEvent plugin id` test deleted.
8. `CoreEmitEventDifferentialContractTest` (G2 dual-harness, drove the deleted legacy dispatcher) — archived as `CoreEmitEventDifferentialContractArchivedG2Test.kt.txt`. Historical evidence preserved; never rewritten to test the new Step.
9. `CoreEmitEventMigrationReadinessFitnessTest` — already `@Disabled` + archived at G4 (pre-flip readiness proof, kept as historical receipt).

## New irreversible fitness

**`S3EmitEventLegacyRemovedFitnessTest`** (pipeline-architecture-tests) proves simultaneously:

- LEGACY_REMOVED: no `EmitEvent` command subtype, no decoder branch, no dispatcher file/field/branch/context helper, no metadata row, membership absent (G4 flip holds).
- Counter convergence: exact residual set `{milestone, deleteDir, cleanWs, load, pwd, isUnix, waitUntil, archiveArtifacts}` with **8 IDs / 8 metadata rows / 8 dispatcher files**.
- Anti-over-removal (G5 law): `StructuralOverlayProjection` keeps recognizing the raw `core.emit.event` envelope (`EMIT_EVENT_PLUGIN`, `CatchErrorEntered`, `CatchErrorTriggered`); the Step whitelist keeps its four kinds; the composite `RegistryStepMetadataResolver` still serves `core.emit.event` from the registry descriptor; `CoreStepRegistryFactory` still registers `CoreEmitEventStep`.

## Updated counter pins (9 → 8)

`S3ErrorLegacyRemovedFitnessTest`, `S3WriteFileLegacyRemovedFitnessTest`, `S3SleepLegacyRemovedFitnessTest` (metadata/dispatcher snapshots), `CoreEmitEventRegistryPrimaryFitnessTest` (legacy-present test inverted to G5 absent-aware, plus an explicit structural-protocol-still-alive assertion), `FArchLfc1CanonicalCoverageTest` (emit-event kind whitelist authority repointed from the deleted `CanonicalEmitEventNodeDispatcher.kt` to `CoreEmitEventStep.kt` `ALLOWED_KINDS`).

Build note: `pipeline-architecture-tests` now declares `testImplementation` on `:pipeline-application`, `:pipeline-domain` and `:pipeline-step-sdk:runtime` to support registry-level assertions.

## Verification evidence (fresh runs, XML canary verified)

| Suite | Result |
| --- | --- |
| `S3EmitEventLegacyRemovedFitnessTest` + 3 residual S3 snapshots + `FArchLfc1CanonicalCoverageTest` + `Lfc2RegistryFamilyFitnessTest` + 3 registry-primary fitness | 34 tests / 0 failures / 0 errors |
| `CoreEmitEventStepUnitTest` | 18 / 0 |
| `CoreEmitEventRegistryPrimaryFitnessTest` | 10 / 0 |
| `EmitEventCatchErrorRegistryUatTest` (fresh + replay, post-removal decisive UAT) | 3 / 0 |
| `CanonicalStructuralPreparationTest` | 4 / 0 |
| `CanonicalCoordinatorScopeStackTest` | 5 / 0 |
| `UatLocal012ErrorHandlingTest` | 8 / 0 |
| `CanonicalCoreStepCommandRegistryTest` | 10 / 0 |
| `CanonicalCoreStepDecoderTest` | 2 / 0 |
| `CanonicalDurableRunCoordinatorTest` | 12 failures — **byte-identical set to the frozen base-SHA baseline** (`/tmp/head-fails.txt`, base `0f53e487`, Rule 16). Baseline NOT widened. |

## Untouched (G5 law)

`CoreEmitEventStep`, its factory registration, `DslCompiledPipelineCompiler` emit-event generation, `CanonicalStructuralPreparation`, `StructuralOverlayProjection` (incl. `EMIT_EVENT_PLUGIN = "core.emit.event"`, both projections), `StructuralOverlay` ADT, `LEGACY_PLUGIN_IDS` (G4 flip retained), `StepDescriptorRegistry` entry.

## Status

**G5 COMPLETE — LEGACY_REMOVED.** Per authorization: STOP here. No G6, no contract certification, no G8.
