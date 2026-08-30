---
type: adr
id: ADR-0054
title: "ML-R9 — Workflow-control and error-handling step tier (block-step nesting + 3-state outcome + 16 new step kinds)"
status: proposed
cycle: "p-733fb505b5a6bd2d/ml-r9-jenkins-catalog-steps"
date: 2026-08-30
deciders: "sddk-design (sddk.cli)"
supersedes: null
superseded_by: null
related:
  - ADR-0046  # §ML/L7 — local-ecosystem-first
  - ADR-0048  # sandbox-profile-local
  - ADR-0049  # credentials-parity
  - ADR-0051  # credentials-parity
  - ADR-0052  # jenkins top-steps
  - ADR-0053  # smoke-e2e-sandbox
---

# ADR-0054 — ML-R9 Workflow-control and error-handling step tier

> **Cycle:** `p-733fb505b5a6bd2d/ml-r9-jenkins-catalog-steps`
> **Phase:** design → apply (Tier L9 / A-full)
> **Authority:** `docs/v2/05-roadmap/ROADMAP.md:240-258` + `ADR-0046` §ML/L7 + `ADR-0048-sandbox-profile-local` + `ADR-0051-credentials-parity` + `ADR-0052-jenkins-top-steps` §D2/D4/D5/D6
> **Base SHA:** `7e15410` (= v0.22.1 = ML-R8 closure = HEAD per `sddk cycle inventory` 2026-08-30; working tree clean per AGENTS.md V2 PRIME DIRECTIVE rule 1)
> **Spec set:** 15 files (8 NEW + 1 NEW-MODIFIED umbrella + 6 DELTA) under `cycle-artifacts/p-733fb505b5a6bd2d/ml-r9-jenkins-catalog-steps/specs/`

## Context

ML-R9 ships the **next structural tier** — the workflow-control + error-handling + workspace + utility + decorator tier — for the V2 pipeline DSL. After ML-R9, a Jenkins pipeline using `dir`, `deleteDir`, `cleanWs`, `timeout`-block, `retry`-block, `catchError`/`warnError`/`unstable`, `milestone`, `pwd`/`isUnix`/`load`/`waitUntil`, `timestamps`/`ansiColor`, plus the `node` no-op variant, compiles and runs locally with the **same durability + event semantics + Jenkins-verbatim signatures** that ML-R7 promised for `writeFile`/`archiveArtifacts`.

The central architectural risk of ML-R9 is the **block-step nesting model** (8 NEW `steps: List<StepSpec>` variants in addition to the existing `WithEnv` precedent). Designing the flattening algorithm + nested-step indexing + replay cursor interplay BEFORE ml-r10..ml-r13 land is critical: every ml-r10..ml-r13 cycle that adds a block-step variant reuses the same precedent. Building the precedent wrong locks in a wrong shape for 4 more cycles. **Risk-1 mitigation**: extract `BlockStepFlattener` to `:pipeline-step-sdk:api` BEFORE any new step kind lands (pure refactor of the existing `WithEnv` executor at `PipelineRun.kt:1143-1188`; no behavioral change).

ML-R9 also introduces the **3-state outcome model** `StepResult.outcome ∈ {"success", "failure", "unstable"}` (widen 2-state → 3-state). `unstable` is Jenkins's "soft warning" semantic; pipeline-level `unstable` → exit code 0. `Main.kt:307-313` exit-code propagation is the **single behavioral breaking change**; verified to be the only call site by `grep "outcome == \"failure\""` / `"outcome != \"success\""` across the v2 source tree.

The 16 new step kinds break down as:

| Tier | Step kinds | Location |
|------|------------|----------|
| Workflow-control | `Dir`, `TimeoutBlock`, `RetryBlock`, `CatchError`, `WarnError`, `Unstable`, `NodeNoOp` | `:pipeline-step-sdk:workflow-control` |
| Workspace-cleanup | `DeleteDir`, `CleanWs` | `:pipeline-step-sdk:files` |
| Workflow-utility | `pwd`, `isUnix`, `waitUntil`, `milestone` | `:pipeline-step-sdk:runtime` |
| Output-decorator | `timestamps`, `ansiColor` | `:pipeline-step-sdk:workflow-control` |
| Scripting glue | `load` | `:pipeline-scripting-api/load/` |

Downstream consumers of the V2 pipeline DSL include: CI pipelines using `pipeline { }` scripts, UAT harnesses consuming `RunFinished.outcome`, event-log consumers parsing `JsonEventLog.encode/decode`, and journal-based replay/resume systems.

## Decision

### D1 — Block-step nesting model: `BlockStepFlattener` extracted FIRST

`BlockStepFlattener` extracted from `PipelineRun.kt:1143-1188` → `:pipeline-step-sdk:api`. The flattener ships in T-01 (ROUND-1 refactor, zero behavioral change) before any new step kind lands in T-04..T-10. **Rationale**: locks the shape for ml-r10..ml-r13; regressions surface as test failures, not as 4-cycle refactor debt.

### D2 — Nested-step indexing: hierarchical `(stepIndex, depth, blockPath)`

`stepIndex` = flattened execution-order position (monotonic, enables journal replay-by-sequence). `blockPath` = `"<outerIdx>.<innerIdx>..."` recorded in **JOURNAL METADATA ONLY** (NOT in `DomainEvent` payload per ADR-0049 §D8 redaction + INV-CR-CR4 typed-carrier discipline). **Depth limit = 3** (matches Jenkins Groovy CPS continuation depth; throws `BlockNestingDepthExceededException` at the DSL builder if exceeded).

### D3 — Replay cursor + nested-block resumption: recursive flattening at execution time

Crash-mid-`retry { dir { timeout { sh } } }`: outer-retry SUCCEEDED at attempt 1 (journaled), inner-most `sh` SUCCEEDED at attempt 1 (result.txt SUCCEEDED) → **SKIP on resume**. **MEMOIZATION is per-step** (innermost granularity), not per-block. The block-step wrapper is RE-ENTERED on resume (idempotent `cd` for `dir`, idempotent counter for `milestone`, idempotent env-merge for `withEnv`). Replay resume re-enters the block by `stepIndex`; per-step `result.txt`/`MEMOIZED` markers consulted per ADR-0046 §D2.

### D4 — `StepResult.outcome` widening: `String`-typed, NOT replaced by enum

`outcome` STAYS `String` (NOT replaced by `StepOutcome` enum). Accepted values widened to `{"success", "failure", "unstable"}`. `JsonEventLog` decoder at `JsonEventLog.kt:430` defaults to `"unknown"` for missing `outcome` — forward-compat preserved. Schema version `"v1"` stable. **Scenario SRO-S-006** ("compile-time exhaustiveness via typed enum") **REJECTED at design phase** — too much surface to migrate in one A-full cycle; deferred to ml-r9.1.

### D5 — 3-state outcome exit-code mapping: `Main.kt:307-313` widened

`"failure"` → exit 1; `"success"` / `"unstable"` → exit 0. Stderr message: `Pipeline finished with FAILURE` / `SUCCESS` / `UNSTABLE` (3 distinct strings, grep-testable). `RunFinished.outcome` aggregator accepts `"unstable"` (already does — string-typed). **The single behavioral breaking change** in ML-R9.

### D6 — Event catalog: 12 NEW `DomainEvent` variants (27 → 39)

`DirEntered`, `DirExited`, `DirDeleted`, `WsCleaned`, `TimeoutTriggered`, `CatchErrorTriggered`, `StageMarkedUnstable`, `MilestoneReached`, `MilestoneAborted`, `WorkflowLoaded`, `WaitUntilPolled`, `WaitUntilCompleted`. NO new `StageFinished`/`RunFinished` variants — outcome field widened in existing variants. Schema version `"v1"` stable; old decoders skip unknown `kind` as `null` (M1-R3 invariant, verified by EVT-CR-008).

### D7 — `JsonEventLog` encoder/decoder: 12 NEW branches (27 total variants)

Each new branch follows the existing `RunStarted`-precedent pattern (~10 LOC encoder + ~15 LOC decoder). Sealed-type exhaustiveness is the compile-time safety net; `JsonEventLogRoundTripTest` extends 15 → 27 tests.

### D8 — Module plan: NEW `:pipeline-step-sdk:workflow-control` + extensions

NEW module `:pipeline-step-sdk:workflow-control` (~1,500-2,000 LOC; mirrors `:pipeline-step-sdk:files` shape per ADR-0052 §D1 inward-only constraint). Single inbound edge from `:pipeline-step-sdk:api` for the shared utility. Extensions to 8 existing modules.

### D9 — Per-step executor location

Per spec module discipline: `Dir`/`TimeoutBlock`/`RetryBlock`/`CatchError`/`WarnError`/`Unstable`/`NodeNoOp` → `:pipeline-step-sdk:workflow-control`. `DeleteDir`/`cleanWs` → `:pipeline-step-sdk:files`. `pwd`/`isUnix`/`waitUntil`/`milestone`/`timestamps`/`ansiColor` → `:pipeline-step-sdk:runtime`. `load` → `:pipeline-scripting-api/load/` (consumes `Kotlin24ScriptingHost`).

### D10 — F-ARCH-L7 widening + 2 NEW arch tests

`FArchL7DomainEventExhaustivityTest:62` constant 27 → **39** (one-line widening). NEW `FArchL7JenkinsVerbatimSignatureReflectionTest` (~200 LOC): reflects all 16 NEW step kinds with `@Step @ JenkinsCatalog(line)` annotations. NEW `FArchL7BlockStepNestingInvariantTest` (~300 LOC): asserts JSON round-trip for every `steps: List<StepSpec>` variant + `BlockStepFlattener` monotonic stepIndex + replay cursor resumes mid-block-step correctly (R-1 mitigation).

### D11 — 3 NEW UAT classes + shared canary round-gate

`UatLocal011WorkflowControlTest` (12 scenarios; ~700 LOC), `UatLocal012ErrorHandlingTest` (8 scenarios; ~500 LOC), `UatLocal013MilestoneTimingTest` (4 scenarios; ~400 LOC). Shared `__ml_r9_canary__` per INC-R7-ARC-001 reuse pattern. Class-level `@Timeout(600)`, NO `maxParallelForks`, `destroyForcibly()` + `setsid` teardown, `printenv VAR` oracle.

### D12 — Compatibility fixtures: 3 NEW (corpus 10 → 13)

`11-workflow-control.pipeline.kts` (~50 LOC), `12-error-handling.pipeline.kts` (~50 LOC), `13-workspace-helpers.pipeline.kts` (~50 LOC). `baseline.json` extended by 3 NEW `FixtureSnapshot` entries (SHA-256 at first accepted version). `UatLocal005CorpusUntouchedTest` widens `assertEquals(10, ...)` → `assertEquals(13, ...)`. Wall-clock bound 120 s → 165 s.

### D13 — ADR-0054 disposition: OUTLINE in design, REAL file in apply

The design phase commits the outline (D1-D14 of this ADR). The apply phase (`sddk-apply` T-14) authors the real repo file at `docs/v2/04-adrs/ADR-0054-block-step-nesting.md` with full Threat model + Consequences + Future sections. Vault node created by `sddk-archive`.

### D14 — ADR-0054 outline: Title + Sections + 3 ADR criteria hold

Title: `ADR-0054: Workflow-control and error-handling step tier`. Sections: Context + Decision (D1-D16 verbatim key points) + Threat model (8 surfaces) + Consequences (positive + negative) + Future (Q-1/Q-2/Q-3/Q-4 carry-forward + ml-r10..ml-r13 commitment + ml-r9.1 typed-enum outcome migration). **3 ADR criteria hold**: (1) hard to reverse — `BlockStepFlattener` shape commits 4 future cycles; (2) surprising without context — 3-state outcome widening is a behavioral breaking change; (3) real trade-off — `String` outcome vs typed enum vs validation-only.

### D15 — Naming reconciliation: UAT catalogue `UAT-LOCAL-007/008/009` vs codebase `UatLocal011..013`

Catalogue-vs-class mismatch is structural (catalogue labels `UAT-LOCAL-N` are immutable per cycle discipline + `docs-sweep` cycle carries the docs work). Orchestrator dispatches `docs-fidelity-mop` cycle to add UAT-LOCAL-007/008/009 entries. This cycle's deliverable: 3 NEW compatibility fixture files + 3 NEW UAT class files — no docs write.

### D16 — `runPipeline` extraction vs duplicate: DUPLICATE verbatim

`runPipeline` helper borrowed verbatim from `UatLocal009TopStepsTest.kt:60-90` (D16). Extract to `:pipeline-testkit` ONLY if a 7th caller emerges (current count = 3 + future = 6 total; rule of three exceeded). Connascence analysis: Name connascence I ≈ log2(6) ≈ 2.6 bits (Medium) — acceptable.

## Threat Model

| Surface | Description | Mitigation |
|---|---|---|
| **Block-step nesting regression** | `BlockStepFlattener` extracted incorrectly; nested `retry { dir { timeout { sh } } }` produces wrong stepIndex order | T-01 GREEN-only refactor; R-11 mitigation: `CR-U9-005..008` regression gate preserved; L7-007 asserts monotonic indices |
| **Outcome widening mis-cascade** | `"unstable"` cascades through `RunFinished.outcome` aggregator but exit-code propagation at `Main.kt:311` misses one caller | Single call site verified by exhaustive `grep`; T-02 behavioral change documented; SRO-S-002/SRO-S-003 covered by UatLocal012 |
| **Dispatch site omission** | New `StepSpec` variant dispatched but `PipelineRun.kt` switch has no branch | Sealed-type exhaustiveness in compiler; `FArchL7JenkinsVerbatimSignatureReflectionTest` (L7-006) reflects all 16 variants |
| **Encoder-decoder asymmetry** | `JsonEventLog` encodes new `DomainEvent` variant but decoder branch missing → round-trip fails | Sealed-type `DomainEvent` + `JsonEventLogRoundTripTest` 15→27 widening (R-3 mitigation); T-11 lands all 12 encoder + 12 decoder branches |
| **`withEnv` atomicity regression** | `BlockStepFlattener` extraction changes `withEnv` flattening order → env vars leaked across parallel branches | R-11 mitigation: `CR-U9-005..008` (withEnv atomicity tests) run GREEN at T-01/T-03; byte-identical assertions preserved |
| **Nesting depth violation** | `dir { timeout { retry { sh } }` exceeds depth-3 limit → CPS stack overflow or undefined behavior | `BlockStepFlattener.depthGuard` throws `BlockNestingDepthExceededException` at DSL builder; Jenkins catalog depth-3 precedent |
| **`load` re-entrancy** | Same `load` path + same sha re-executed within one run → duplicate steps appended or idempotency violated | Per-run `Set<Path>` tracks loaded paths; same path + same sha = NO-OP per WUT-S-005; R-12 mitigation |
| **Milestone ordinal-monotonicity** | Out-of-order `milestone` calls produce non-monotonic ordinals → `MilestoneAborted` flooding or replay divergence | `MilestoneExecutor` uses file-based lock + monotonic check; MIL-S-002/MIL-S-003 test coverage in T-09 + UatLocal013 |

## Consequences

### Positive

- **16 NEW Jenkins-verbatim step kinds**: `dir`, `timeout`, `retry`, `catchError`, `warnError`, `unstable`, `node(no-op)`, `deleteDir`, `cleanWs`, `pwd`, `isUnix`, `load`, `waitUntil`, `timestamps`, `ansiColor`, `milestone`
- **Jenkins CPS depth-3 precedent preserved**: `BlockStepFlattener.depthGuard` throws at builder, preventing stack overflow in production pipelines
- **3-state outcome**: `success` / `failure` / `unstable` with Jenkins-verbatim semantics; pipeline-level `unstable` exits 0
- **Backward-compatible event schema**: `"v1"` stable; `JsonEventLog` decoder defaults unknown `outcome` to `"unknown"`; old decoders skip unknown `kind` as `null`
- **Zero new V1 dependencies**: all 16 step kinds are V2-native; no dependency on `:pipeline-steps-system:compiler-plugin`
- **FArchL7-007 nesting invariant**: ml-r10..ml-r13 block-step variants inherit the extracted `BlockStepFlattener` — architectural risk resolved at ML-R9
- **Deterministic JSON round-trip**: every `StepSpec` with `steps: List<StepSpec>` has verified deterministic serialization
- **MEMOIZED replay safety**: per-step `result.txt`/`MEMOIZED` markers consulted on resume; partial-progress recovery works correctly for `retry { dir { timeout { sh } } }`

### Negative

- **Single behavioral breaking change**: `Main.kt:311` exit-code propagation widened — callers that pattern-match on `outcome != "success"` now see `"unstable"` folded into "exit 0". **Migration**: callers checking `if (outcome != "success") System.exit(1)` should update to `if (outcome == "failure") System.exit(1)`.
- **`BlockStepFlattener` depth-3 limit**: Jenkins-tested nesting max is 3. Exceeding depth-3 throws `BlockNestingDepthExceededException` at the DSL builder — fail-fast rather than silent CPS overflow.
- **`String`-typed outcome** vs typed `StepOutcome` enum: no compile-time exhaustiveness. SRO-S-006 deferred to ml-r9.1.
- **Corpus wall-clock increase**: 10 → 13 fixtures increases corpus smoke-run time ~25% (120 s → 165 s per `CompatibilityCorpusTest`)

## Future

### Q-1 — `input()` headless contract (ml-r10 carry-forward)

When `input()` is called in a non-interactive pipeline (no TTY), the behavior must be defined. **Commitment**: `PIPELINE_INPUT_HEADLESS=true` env var causes `input()` to auto-succeed (return default or empty); if no default, **fail closed** (throw `FlowInterruptedException`). ADR-0054 §Decision documents this contract; implementation deferred to ml-r10-input.

### Q-2 — `node()` real agent allocation (M5+)

ML-R9 ships `node(label?)` as a NO-OP variant that emits `AgentResolved` (R-5: KDoc clearly marks "no-op variant; real agent allocation deferred to M5+ Kubernetes ephemeral workers"). **Commitment**: M5+ implements real `node(label)` via Kubernetes `Job` + `kubectl exec` / `kubectl cp`. ADR-0054 §Future documents the carry-forward.

### Q-3 — `withCredentials` 5 missing bindings (ml-r10-credentials-parity)

ADR-0051 §D8 lists 5 missing `withCredentials` bindings (usernamePassword, secretText, sshUserPrivateKey, fileCredentials, certFolder). **Commitment**: ml-r10-credentials-parity adds the 5 missing bindings; existing `CredentialBound/Used/Unbound` events carry forward unchanged. ML-R9 emits no `withCredentials`-related events.

### Q-4 — `tool(name, type)` (ml-r13)

`tool(name, type)` for tool installer + tool caller symmetry. **Commitment**: ml-r13 implements `tool()` per Jenkins catalog §1.2.20. ADR-0054 §Future documents the carry-forward.

### ml-r10..ml-r13 carry-forward

All 8 block-step variants (`Dir`, `TimeoutBlock`, `RetryBlock`, `CatchError`, `WarnError`, `Timestamps`, `AnsiColor`, `NodeNoOp`) reuse the `BlockStepFlattener` extracted in T-01. The flattener is the shared infrastructure for any future `steps: List<StepSpec>` variant.

### ml-r9.1 — `StepOutcome` typed-enum migration

Per D4 trade-off: `outcome: String` is widened to accept `"unstable"` in ML-R9. The typed `StepOutcome` enum migration (SRO-S-006) is **carried forward to ml-r9.1**. ADR-0054 §Decision documents this as a future migration: replace `String outcome` with `enum class StepOutcome { SUCCESS, FAILURE, UNSTABLE }` across all 4 `DomainEvent` variant fields + `Main.kt:307-313` + `PipelineRun.kt` accumulators + `JsonEventLog` encoder/decoder.

## V1 Precedent Diff

| Aspect | V1 Jenkins | ML-R9 V2 |
|---|---|---|
| Block nesting depth | CPS Groovy depth-3 limit | `BlockStepFlattener.depthGuard` throws at builder |
| `unstable` exit code | exit 0 (soft warning) | `Main.kt:311` → exit 0 for `"unstable"` |
| `catchError` default | `buildResult = null` → UNSTABLE | Same behavior; `CatchError(buildResult=null)` → UNSTABLE |
| `warnError` | Forces UNSTABLE on inner failure | Same; `WarnError` always forces UNSTABLE |
| `milestone` ordinal | Monotonic per-build; file-based lock survives `setsid` | Same; `MilestoneExecutor` uses `<controlDir>/milestone.lock` |
| `load` re-entrancy | Same file + same sha = NO-OP | Same; per-run `Set<Path>` tracks loaded paths |
| `retry` block | Per-block MEMOIZATION; inner `sh` SKIPPED on retry if succeeded | Per-step MEMOIZATION via `result.txt`; `retry { dir { sh } }` → `sh` SKIPPED if succeeded at attempt 1 |
| Outcome values | `success`, `failure`, `unstable` | Same 3-state model |
| `timeout` unit | `TimeUnit` enum (SECONDS, MINUTES, etc.) | `"SECONDS"`, `"MINUTES"` string per Jenkins catalog |
| `pwd(tmp=true)` | Creates temp subdirectory | Stub: returns `"<workspace>"` (R-10 mitigation: R10b defers real temp dir to ml-r9.1) |

---

## Changelog

- 2026-08-30T00:00:00Z | created | status=proposed | valid_from=2026-08-30 | valid_to=∞

---

## Appendix: BlockStepFlattener API Contract

```kotlin
object BlockStepFlattener {
    /**
     * Recursively flatten a StepSpec tree into a list of (path, spec) pairs.
     * Throws BlockNestingDepthExceededException if depth > 3.
     */
    fun flatten(root: StepSpec): List<FlattenedStep>

    /** Assign monotonic stepIndex per execution order. */
    fun index(root: StepSpec): List<IndexedStep>

    /** Depth-3 guard: throws BlockNestingDepthExceededException if depth > 3. */
    fun depthGuard(root: StepSpec)
}

data class FlattenedStep(val spec: StepSpec, val depth: Int, val blockPath: String)
data class IndexedStep(val spec: StepSpec, val stepIndex: Int, val depth: Int, val blockPath: String)
```

## Appendix: 3-State Outcome Exit-Code Table

| `RunFinished.outcome` | `Main.kt:311` exit code | stderr message |
|---|---|---|
| `"success"` | 0 | `Pipeline finished with SUCCESS` |
| `"unstable"` | 0 | `Pipeline finished with UNSTABLE` |
| `"failure"` | 1 | `Pipeline finished with FAILURE` |
| `"unknown"` | 0 | (forward-compat; JsonEventLog decoder default) |
