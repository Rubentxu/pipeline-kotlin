# S2-A4 / G8 — FINAL CERTIFICATION: `core.emit.event` = CERTIFIED

**Slice:** S2-A4 — CLOSED
**Gate:** G8 — installed-distribution certification (evidence + receipt; **production changes = 0**)
**HEAD at certification:** `80fb80f2` — binary SHA256 `3e1cb6fefd89a3133653e570371589f75141425ad1e4f257bff46d168b12a526` (rebuilt from HEAD via `installDist`)

## Final state (frozen)

```text
core.emit.event:
  REGISTERED         = true
  REGISTRY_PRIMARY   = true
  LEGACY_UNREACHABLE = true
  LEGACY_REMOVED     = true
  CONTRACT_SUITE     = true
  REAL_CLI_SCENARIO  = true
  CERTIFIED          = true

StructuralFamily = Registry
legacy counters  = 8 / 8 / 8   (milestone, deleteDir, cleanWs, load, pwd, isUnix, waitUntil, archiveArtifacts)
```

## Gate chain (historical, SHAs as committed; remote-visibility debt noted)

| Gate | SHA | Scope |
| --- | --- | --- |
| G0 characterization | `21a187c8` | baseline audit, zero production changes |
| G1 registry candidate | `15a3f10c` (+receipt `c0caebc9`) | registration without cutover (counters 9/9/9) |
| G2 differential freeze | `294c67e1` (+receipt `da06a5cc`) | UNKNOWN_DIFFERENTIALS=0; PARITY + 4 APPROVED_FIX + replay law |
| G3 migration readiness | `32465435` | pre-flip evidence, no authority change |
| G4 registry-primary flip | `45a47a7a` (+receipt `acaf4c1e`) | counters 8/9/9; LEGACY_UNREACHABLE |
| G5 physical removal | `78d79ee0` (+receipt) | LEGACY_REMOVED; counters 8/8/8 |
| G6 contract certification | `80fb80f2` (+receipt) | CONTRACT_SUITE=true, CERTIFIED=false |
| G8 installed certification | this receipt | CERTIFIED=true |

## Installed-distribution acceptance matrix (fresh evidence, 2026-09-11T19:19Z)

Fixture: `v2/compatibility/12-error-handling.pipeline.kts` (real `catchError` DSL — the `core.emit.event` consumer). Binary rebuilt from HEAD; invocation: `pipeline-application run [--db <path>] [--rerun|--resume] <script>`. Raw evidence + SHA256 digests: `/home/rubentxu/.jcode/scratch/g8-run/evidence.sha256`.

Semantic distinction (per gate mandate): **`--rerun` = controlled re-execution; `--resume` = durable MEMOIZED reuse. They are NOT conflated.**

### FRESH (`--db g8.db`, leg1) — exit 0, outcome UNSTABLE

- runId `29c0264a…`; 8 StepStarted (full pipeline), marker Steps (`warn-error-enter-0`, `catch-error-enter-0`, `catch-error-trigger-0`, `warn-error-trigger-0`) execute as `stepType=emit`.
- **Markers emit 0 DomainEvents** (separation law holds on the wire).
- `StageMarkedUnstable` cardinality = 1 (`"intentionally flaky section"`).
- `CatchErrorTriggered` domain event from the coordinator fold = 1, published exactly at the inner `sh` failure (`StepFailed … shell exited with code 1`).

### RERUN (`--rerun`, leg2) — controlled re-execution — exit 0, outcome UNSTABLE

- New runId `b82f9cb6…` (a rerun is a new execution of the same script); 8 StepStarted; **per-execution semantic cardinality 1/1** (one SMU, one CET within this single execution); no intra-execution duplication.

### RESUME (`--resume`, same durable state, leg3) — durable reuse — exit 0, outcome UNSTABLE

- **Same persisted run identity** `b82f9cb6…` reused (fresh legs get new runIds; resume does not).
- Observable reuse property: `resume_execution_count << fresh_execution_count`. Only **2 steps re-executed** — exactly the ones NOT journaled terminal-SUCCEEDED: the failing `sh` (`catch-error-body-0`, re-ran and failed again, by design) and the post-failure `warn-error-unstable-0`. The **`core.emit.event` operations journaled SUCCEEDED before the failure point (`warn-error-enter-0`, `catch-error-enter-0`, `catch-error-trigger-0`) were NOT re-executed** (no new StepStarted for them).
- Semantic events not duplicated beyond the controlled re-execution of the post-failure tail: `resume_execution_count (2 steps) << fresh_execution_count (8 steps)`, same runId, no SMU/CET duplication of pre-failure operations.

### PLAIN DURABLE REUSE (second plain `run` with the same `--db`, leg4) — recorded separately

- Same runId `b82f9cb6…`; incremental over leg3 only (+1 SMU, +3 CET, +2 StepStarted — the same terminal-run tail pattern as resume). Recorded distinctly from `--resume` per the mandate; both legs prove the journal, not the process, is the durable authority.

### FRESH on second fixture (`15-error.pipeline.kts`, leg5) — exit 1, outcome FAILURE

- Expected: `core.error` aborts the pipeline (`core.error` = CERTIFIED reference at S2-A1). No SMU/CET emitted; `StepStarted` = 1. Confirms emit.event coexists correctly with the error path.

### Regression evidence at HEAD (XML canary, timestamps 19:21–19:22Z)

- `EmitEventStepContractSuiteTest`: **28/0**.
- `CompatibilityCorpusTest`: **17/18 expected baseline** — `fixture12ErrorHandling` PASS, `fixture15Error` PASS, `fixture14CredentialsBindings` FAIL = **known INC-021c, pre-existing, unrelated; baseline not widened**. INC-021c is not a certification blocker for `emit.event`.
- Earlier at HEAD (17:21–17:22Z, XML-verified): full 87/0 regression batch across 9 suites; `S3EmitEventLegacyRemovedFitnessTest` 8/0 re-run at 17:32Z.

## Separation law (frozen — the most delicate piece of this migration)

```text
raw core.emit.event envelope
        │
        ├── StructuralOverlayProjection
        │      owns catchError push/pop semantics (pre-decode, key-based)
        │
        └── CoreEmitEventStep
               owns typed execution semantics
```

```text
CatchErrorEntered/Triggered Step execution  ->  NO DomainEvent from CoreEmitEventStep
actual CatchErrorTriggered observable event  ->  coordinator fold-walk (observed on the wire at the inner sh failure)
```

This law is guarded irreversibly by `S3EmitEventLegacyRemovedFitnessTest` (anti-over-removal half) and the G6 suite's separation test. It prevents a future "fix" that makes the silent marker double-publish.

## Migration success (conjunction, all verified on fresh evidence)

```text
migration_success =
    registry_is_only_execution_authority        ✓ (S3 fitness + G6 identity, StructuralFamily=Registry)
  ∧ legacy_execution_is_physically_absent       ✓ (G5 removals, code-level scans, 8/8/8)
  ∧ catchError_structural_protocol_survives     ✓ (S3 anti-over-removal + wire observation)
  ∧ typed_contract_passes                       ✓ (28/0 contract suite)
  ∧ fresh_real_cli_passes                       ✓ (leg1 + leg5 on installed binary)
  ∧ rerun_semantics_pass                        ✓ (leg2: controlled re-execution, 1/1)
  ∧ resume_reuse_semantics_pass                 ✓ (leg3: same runId, 2 << 8 steps, journal is the authority)
  ∧ compatibility_baseline_not_widened          ✓ (17/18 with only INC-021c, across three independent runs)
```

## Traceability debt (unchanged)

Local commits `80fb80f2`, `78d79ee0` et al. are not yet visible from the reviewer's remote GitHub connector; local git state is the authority. Non-blocking.

## Status

**G8 COMPLETE — `core.emit.event` = CERTIFIED. S2-A4 CLOSED.**
Per authorization: STOP. S2-A5 NOT opened in this batch.
