# S2-A1 — core.error — Tasks

Sequenced in G-order. Each task ends with a verified state before the next begins.

## G0 — Baseline audit

- [x] Confirm `main == origin/main == c6783f9505db8f6dc065f28724e035fefe693210`.
- [x] Re-prove LEGACY_PLUGIN_IDS == 12, metadata rows == 12, dispatcher classes == 12.
- [x] Capture LEGACY_PLUGIN_IDS digest: `b0d33e63c911e012be66c220d9b00f1a8824780fa67cfaa64db041b30b1de2c2`.
- [x] Read legacy decoder/dispatcher/metadata code paths.
- [x] Read all existing error tests + real example.
- [x] Author `docs/v2/07-uat/S2_A1_CORE_ERROR_G0_AUDIT.md`.
- [x] Author `openspec/changes/lfc2-e1-s2-legacy-catalog-burn-down/{proposal,design}.md`.
- [x] Author tasks (this file).
- [ ] STOP for user direction before G1.

## G1 — StepDefinition + typed contracts (registry seam proof)

- [ ] Author `CoreErrorStep.kt` (`data class ErrorInput`, `inputCodec`, `outputCodec`,
      `descriptor`, `definition`, `registerInto`).
- [ ] Compile `:pipeline-application:compileKotlin` (L0).
- [ ] Assert the new file is structurally sound; do NOT yet register in `CoreStepRegistryFactory`.

## G2 — registry admission

- [ ] Edit `CoreStepRegistryFactory.registry()` to add `CoreErrorStep.registerInto(this)`.
- [ ] Compile + smoke test that the registry now contains `core.error`.
- [ ] Do NOT remove legacy decoder/metadata/dispatcher yet.

## G3 — canonical behavior parity (legacy vs registry)

- [ ] Add a unit test that runs both paths against the SAME encoded input and compares
      the `StepOutcome` (`Failure(PipelineFailure(kind, message))`) byte-for-byte.
- [ ] Add a UAT that runs `v2/compatibility/15-error.pipeline.kts` against:
  - legacy (current production)
  - registry (after G2 wiring)
      and verifies identical observable:
        - exit code == 1
        - exactly 1 `StepFailed{USER,"test error message"}`
        - `RunFinished.outcome == "failure"`, `diagnostics` empty
- [ ] All parity tests GREEN.

## G4 — architecture fitness

- [ ] Author `S3ErrorLegacyRemovedFitnessTest` (structural assertions listed in design.md).
- [ ] Run; expected GREEN **with both legacy and registry paths present** (proves the new
      path is correct AND legacy is still classified as Legacy).

## G5 — legacy unreachable

- [ ] Edit `CanonicalCoreStepDecoder.decode` to remove the `"core.error"` `when` branch.
- [ ] Verify `S3ErrorLegacyRemovedFitnessTest` now classifies `"core.error"` as Registry.
- [ ] Verify `UatStep003ErrorAbortTest` etc. still GREEN via the registry path.
- [ ] Verify `StructuralFamilyResolver.classify("core.error", registry) == Registry`.

## G6 — legacy production removal

- [ ] Delete `CanonicalErrorNodeDispatcher.kt` (the entire file).
- [ ] Remove `data class Error` from `CanonicalCoreStepCommand`.
- [ ] Remove `ERROR_PLUGIN_ID` constant from `CanonicalCoreStepDecoder`.
- [ ] Remove the `"core.error"` row from `CanonicalCoreStepMetadata.table`.
- [ ] Remove `"core.error"` from `LEGACY_PLUGIN_IDS`.
- [ ] Refixture `CanonicalErrorNodeDispatcherTest` → `ErrorHandlerContractTest` (drives
      the registry path, asserts the same observable contract).
- [ ] Compile + run full L4 application module suite. Expected GREEN.

## G7 — StepContractSuite

- [ ] Author `ErrorStepContractSuiteTest` covering the 15 contractual rows from design.md.
- [ ] Run; expected all rows GREEN.

## G8 — real executable scenario / acceptance

- [ ] Run `v2/compatibility/15-error.pipeline.kts` via the installed distribution binary:
  - exit code == 1
  - exactly 1 `StepFailed{USER,"test error message"}`
  - `RunFinished.outcome == "failure"`, `diagnostics` empty
- [ ] All existing UATs GREEN: `UatStep003ErrorAbortTest`, `ErrorHandlingTest`,
  `CliCompileErrorExitsOneTest`, `UatLocal012ErrorHandlingTest`,
  `UatComp002ErrorSourceMappedTest`.

## Final counter delta

- [ ] Re-prove LEGACY_PLUGIN_IDS == 11, metadata rows == 11, dispatcher classes == 11.
- [ ] Capture new digest; record in `docs/v2/07-uat/S2_A1_CORE_ERROR_CERTIFICATION.md`.
- [ ] Author certification receipt (mirrors `CORE_ECHO_CERTIFICATION.md` shape).

## Slice close gate

```text
core.error:
  delivery:    CORE
  execution:   REGISTRY_PRIMARY
  legacy:      REMOVED
  certification: CERTIFIED

legacy: IDs = 11, metadata = 11, dispatchers = 11
```

Then STOP and report. Do NOT open S2-A2 (core.sleep) until user authorises.
