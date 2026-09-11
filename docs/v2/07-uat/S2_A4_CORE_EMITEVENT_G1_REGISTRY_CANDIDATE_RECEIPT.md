# S2-A4 / G1 — core.emit.event Registry Candidate

- **Gate**: G1 (registry seam proof) — candidate only, NO cutover.
- **Base SHA**: `0f53e487` · **G1 commit**: `15a3f10c`
- **Scope**: registry-routed `core.emit.event` candidate proven correct; production wiring and
  legacy decode/dispatch path untouched.

## Exit state (G1 contract)

| Flag | Value |
|---|---|
| REGISTERED | **true** (`CoreEmitEventStep.registerInto` in `CoreStepRegistryFactory`) |
| LEGACY_UNREACHABLE | false (legacy path intact — G4) |
| LEGACY_REMOVED | false (G5) |
| CERTIFIED | false (G8) |
| StructuralFamily(`core.emit.event`) | `LegacyCore` (LEGACY_PLUGIN_IDS untouched) |
| Counters (LEGACY / metadata / dispatchers) | **9 / 9 / 9** |

## Design (user-approved)

1. **`STAGE_IDENTITY_CAPABILITY`** (`runtime.stage-identity`) + narrow `StageIdentity(name, index)`
   value; bound in `CanonicalRuntimeCapabilityAccess` from runtime context. `StepHandlerContext`
   unchanged (runId/stepIndex/capabilities only) — no stage data in context, no fingerprint change.
2. **Envelope-only codec**: input codec validates structure (`kind` present), NOT whitelist
   membership. Whitelist = handler semantics → unknown kind is a typed `SCHEMA` step failure,
   preserving legacy failure layering (Q1/G2 linkage).
3. **Typed output ADT** `CoreEmitEventOutput` (`EmitEventSuccess` / `EmitEventUnstable` /
   `EmitEventRejected(PipelineFailure)`); handler never uses exceptions as semantics.
4. **APPROVED FIX CANDIDATE** (final classification at G2): valid-kind incomplete payloads
   (StageMarkedUnstable without message; FileWritten missing path/sha256/size or non-numeric size)
   produce typed `SCHEMA` Rejected with ZERO events, replacing the legacy untyped
   `IllegalStateException`→`INFRASTRUCTURE` path.
5. **Whitelist kept verbatim**: all four kinds (CatchErrorEntered, CatchErrorTriggered,
   StageMarkedUnstable, FileWritten). No contract narrowing during burn-down.
6. **Metadata preserved**: READ_ONLY + MEMOIZED, exactly as legacy.
7. `atomicallyMoved` lenient parsing preserved (`toBooleanStrictOrNull() ?: false`, incl. silent
   false on garbage) — G2 classification item.
8. requiredCapabilities = exactly {EVENT_SINK_CAPABILITY, STAGE_IDENTITY_CAPABILITY}.

## Evidence

- `CoreEmitEventStepUnitTest` **18 tests / 0 failures** (fresh XML canary, <2min): codec round-trip
  per kind, JSON-null payload parity, unknown-kind-not-decode-failure, descriptor fidelity, exact
  capability set, per-capability admission rejection, full
  `RegistryExecutionPreparation → admission → handler` seam test (≥1 required, satisfied),
  full branch matrix (markers→Success+0 events; unstable event + stageName fallback + override;
  FileWritten success/default/explicit atomicallyMoved; SCHEMA rejections + 0 events),
  production registry membership + LegacyCore classification, duplicate registration fails closed.
- Neighbors green (fresh XML): `CoreWriteFileStepUnitTest` 9/0, `SleepStepContractSuiteTest` 21/0,
  `WriteFileStepContractSuiteTest` 21/0, `CanonicalCoreStepCommandRegistryTest` 11/0,
  `CompatibilityCorpusTest.fixture17` 1/0.
- **Rule 16 evidence**: `CanonicalDurableRunCoordinatorTest` shows 12 failures at HEAD. Fresh
  worktree at base `0f53e487` reproduces the **identical 12-failure set** (set-diff empty).
  Pre-existing baseline, NOT caused by G1 and NOT widened. Not in G1 scope.

## Q-linkage carried to G2

- **Q1**: legacy untyped `error()`/IllegalStateException → INFRASTRUCTURE; candidate emits typed
  SCHEMA. Differential-contract freeze must classify this change explicitly.
- **Q2**: CatchErrorEntered/Triggered remain coordinator fold-walk markers; FileWritten has no
  compiler callsite — unchanged by G1.
- **Q3**: EVENT_SINK_CAPABILITY reused as declared; STAGE_IDENTITY_CAPABILITY added as the
  second narrow capability.
- **atomicallyMoved**: lenient-parse semantics frozen for differential capture at G2.

## Next

G2: differential-contract freeze (legacy vs candidate on the same op-journal corpus) → then
G3/G4 flip (STOP after each gate for authorization, per user protocol).
