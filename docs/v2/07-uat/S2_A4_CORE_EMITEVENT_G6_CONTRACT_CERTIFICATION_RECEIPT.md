# S2-A4 / G6 — CONTRACT_SUITE: `core.emit.event`

**Slice:** S2-A4 (`core.emit.event`; G0..G5 complete)
**Gate:** G6 — canonical Step contract certification of the post-legacy registry Step
**Production code changes: ZERO** (certification only, per gate mandate).

## State certified

```text
core.emit.event:
  REGISTERED         = true
  REGISTRY_PRIMARY   = true
  LEGACY_UNREACHABLE = true
  LEGACY_REMOVED     = true
  CONTRACT_SUITE     = true
  CERTIFIED          = false   (reserved for G8 with installed-distribution/UAT evidence)

StructuralFamily = Registry
legacy counters  = 8 / 8 / 8
```

## New suite

`EmitEventStepContractSuiteTest` (pipeline-application) — **28 tests / 0 failures**, two sections per the gate mandate:

### GENERIC STEP CONTRACT ("is this a correct registry Step?")

1. **Identity**: key `core.emit.event`, registered in the production factory, StructuralFamily == Registry, duplicate registration fails closed.
2. **Descriptor frozen** (exact sets, no `contains()`): effects exactly `{READ_ONLY}`, `ReplayPolicy.MEMOIZED`, `RecoveryPolicy.None`.
3. **Capabilities**: `requiredCapabilities` equals exactly `{EVENT_SINK_CAPABILITY, STAGE_IDENTITY_CAPABILITY}`.
4. **Codec**: encode→decode round-trip for all four whitelist kinds; **layering law** — unknown kind passes the codec (envelope-only) and the handler decides typed `SCHEMA` (unknown kind != DecodeFailure).
5. **Capability admission** (real `RegistryExecutionPreparation`): both capabilities → Ready and handler executes through the boundary; missing EVENT_SINK → Rejected, handler = 0, events = 0; missing STAGE_IDENTITY → Rejected, handler = 0, events = 0; extra undeclared runtime capabilities (shell/workspace present in the production bridge) do NOT change semantics — the Step consumes only its declared two.
6. **Boundary discipline**: every semantic assertion drives `RegistryExecutionPreparation → capability admission → PreparedRegistryExecution → RegistryExecutionBoundary → CoreEmitEventStep`; no bare-handler-only certification.
7. **ADT → StepOutcome**: Success output → `StepOutcome.Success` (encoded carrier non-null, typed object gone past the seam); Unstable → `StepOutcome.Unstable`; Rejected → `StepOutcome.Failure(SCHEMA)` with the Step's configured message. Law: expected semantic failure != thrown exception.
8. **Replay as a property**: fresh execution → handler runs, semantic event exactly once; MEMOIZED + SUCCEEDED seeded via `Fingerprint.compute`/`RerunOperation` → SKIP, handler not reinvoked, zero duplicate domain events.
9. **Observability**: registry run emits the StepStarted/StepFinished lifecycle pair.

### EMIT.EVENT SEMANTIC CONTRACT ("does it keep its specific semantics?")

- **catchError markers (permanent contract)**: `CatchErrorEntered` and `CatchErrorTriggered` → Success, zero DomainEvents. **Separation certified**: `StructuralOverlayProjection` owns control-flow projection (`EMIT_EVENT_PLUGIN = "core.emit.event"`, kind recognition in `CanonicalInvocation.kt`); `CoreEmitEventStep` owns execution semantics and contains no coordinator control-flow logic (code-level scan with comments stripped).
- **StageMarkedUnstable**: payload `stageName` present → payload wins; absent → `StageIdentity.name` (via the runtime bridge); `message` present → exactly 1 event + Unstable; absent → SCHEMA, 0 events, never `IllegalStateException`.
- **FileWritten**: valid payload → exactly 1 `FileWritten` with path/sha256/size/atomicallyMoved; **frozen legacy `atomicallyMoved` parse** (missing→false; strict `"true"`→true; `"false"`, `"TRUE"`, `"yes"`, `"1"`, garbage→false). **NO-NEW-VALIDATIONS** preserved: blank path/sha256 accepted, negative size accepted, unknown extra fields accepted.
- **Typed failures**: the four G2 APPROVED_FIX rows are now definitive contract — missing `message`, missing `path`, missing `sha256`, missing/non-numeric `size` → SCHEMA with the exact frozen messages, event count 0.

### No-legacy-resurrection guard

The suite itself re-proves the irreversible G5 state (code-level, comments stripped): no `EmitEvent` command subtype, no `EMIT_EVENT_PLUGIN_ID`, no `CanonicalEmitEventNodeDispatcher.kt`, `LEGACY_PLUGIN_IDS.size == 8`. A future contract-suite fix cannot quietly resurrect legacy.

## Regression evidence (fresh runs, XML-verified)

| Suite | Result |
| --- | --- |
| `EmitEventStepContractSuiteTest` (NEW) | 28 / 0 |
| `CoreEmitEventStepUnitTest` | 18 / 0 |
| `CoreEmitEventRegistryPrimaryFitnessTest` | 10 / 0 |
| `EmitEventCatchErrorRegistryUatTest` | 3 / 0 |
| `CanonicalStructuralPreparationTest` | 4 / 0 |
| `CanonicalCoordinatorScopeStackTest` | 5 / 0 |
| `UatLocal012ErrorHandlingTest` | 8 / 0 |
| `S3EmitEventLegacyRemovedFitnessTest` (structural-protocol survivor regression) | green |
| `Lfc2RegistryFamilyFitnessTest` | green |
| `CompatibilityCorpusTest` (real acceptance corpus) | 17/18 — `fixture12ErrorHandling` PASS, `fixture15Error` PASS, only `fixture14CredentialsBindings` red (**known INC-021c**, pre-existing, not widened). Baseline matched exactly. |

Combined G6 batch: **87 tests / 0 failures**, plus corpus per the accepted baseline.

## Not touched (gate mandate)

`LEGACY_PLUGIN_IDS`, `StructuralOverlayProjection`, the kind whitelist, `atomicallyMoved` semantics, replay policy, the capability model. No defect in `CoreEmitEventStep` or the generic seam was found; no discrepancy to document.

## Status

**G6 COMPLETE — CONTRACT_SUITE=true, CERTIFIED=false.** Per authorization: STOP. G8 (installed-distribution / UAT certification evidence) awaits explicit GO.
