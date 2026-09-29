# S0-B — Semantic Witness Matrix (Semantic Honesty Gate)

Cycle: `p-733fb505b5a6bd2d/train-dsl-honesty` · Block: S0-B · Date: 2026-09-29
Base SHA: `0bffa968` (S0-A2, DSL Surface Manifest v1)
Exit criterion: every STABLE `local-core` surface has a discriminating witness that runs
through the **installed distribution binary**, not an in-process harness.

Status: **CLOSED** (witness suite green; one real production gap closed; manifest corrected).

## 1. What this block did

`S0SemanticWitnessMatrixTest` — 17 witnesses, one per STABLE surface. Each witness runs the
real CLI (`build/install/pipelinek/bin/pipelinek run <script>`), decodes the JSON event log
and asserts a full leg set: CARRIER / POSITIVE / NEGATIVE / OUTCOME / EVENT / REPLAY / INSTALLED.

The three failures inherited from the previous session are resolved. Two were test defects; one
was a real product gap that the honesty gate existed to catch.

## 2. Findings

### F1 — manifest named events that never existed (3 lies)

`FArchS0SurfaceManifestTest` validates the construct/category/state columns against live code,
but not the prose of the interpreter column. These three claims were therefore unfalsifiable
and survived S0-A2:

| Manifest claimed | Reality |
|---|---|
| `timeout` → `TimeoutScheduled`/`TimeoutFired` | `TimeoutFired` does not exist anywhere |
| `retry` → `RetryAttempted` | Real: `RetryAttemptStarted` + `RetryAttemptFinished` |
| `catchError` → `CatchErrorEntered`/`Triggered` | `CatchErrorEntered` does not exist; real: `CatchErrorTriggered` |

### F2 — real production gap: `TimeoutTriggered` had no producer

`TimeoutTriggered` was declared in `DomainEvent`, wired into `EventJsonWriter`, `JsonEventLog`,
`SqliteEventStore`, `InMemoryEventStore`, `SequenceAssigner` and `EnvelopeProjector` — and
emitted from **zero** production sites. Only `TimeoutScheduled` was produced (coordinator
`TimeoutScheduled` on block-deadline admission).

Consequence: a `timeout()` block whose deadline fired left only the child's
`StepFailed(failureKind=TIMEOUT, message="durable shell timed out")`. The block had an
admission record but no breach record, and a deadline firing was indistinguishable from an
ordinary script timeout — a semantic drop of exactly the class S0 exists to eliminate.

Fix — `CanonicalDurableRunCoordinator`, block authority seam, inside the same `finally` that
already projects `DirExited`/`TimestampsExited`:

```kotlin
if (scope is BlockShellScope.Timeout) {
    val deadlineBreached = (outcome as? StepOutcome.Failure)
        ?.failure
        ?.kind == dev.rubentxu.pipeline.v2.domain.FailureKind.TIMEOUT
    if (deadlineBreached) { eventSink.append(TimeoutTriggered(...)) }
}
```

The decision is a pure fold on the **typed** body outcome. It never branches on a `StepKey`,
and it is placed at the effect boundary (interpretation), not in the policy decision.

### F3 — surface state corrected against observed behaviour

- **`git` is not STABLE.** It lowers to `OpaqueStepNode(pluginStepId=core.checkout)`, but the
  scm-git plugin registers `scm-git.checkout`. The canonical bridge rejects it fail-closed
  (exit 2, "non-canonical plugins"). The Jenkins-familiar `git(url)` performs **zero**
  checkouts. Reclassified `ATOMIC_STEP / UNSUPPORTED_FAIL_CLOSED`; `checkout` → `PARTIAL`.
  This is the same defect class as the `scmGit` duplicate found earlier in the train.
- **`catchError` does not fail the build.** Contained failure yields CLI exit **0** with
  `RunFinished.outcome=unstable` (ADR-0054 projection); steps after the block still run.
- **`options.timeout` ≠ `timeout()` block.** The directive is a stage-wide shell deadline: it
  produces no `TimeoutScheduled` and no `TimeoutTriggered`; only the governed step's own
  `StepFailed(failureKind=TIMEOUT)`. The block owns the authority pair.

## 3. Evidence

All runs from `v2/` via `v2/gradlew -p v2`. JUnit XML is the result truth; canary verified
by deleting the XML before each run that had to execute.

| Level | Command | Result | Evidence |
|---|---|---|---|
| L0 | `:pipeline-application:compileTestKotlin` | BUILD SUCCESSFUL 21s | — |
| **L1 RED** | `:pipeline-application:test --tests '*S0SemanticWitnessMatrixTest*'` | **17 tests, 3 failed** (lines 338, 214, 192) | matches the recorded backlog exactly |
| L0 | `:pipeline-application:compileTestKotlin` | BUILD SUCCESSFUL 21s | after the production fix |
| **L1 GREEN** | same, after `installDist` rebuild | **17/17, 0 failures, 0 errors**, 101.73s | canary `c141f345…`, ts `2026-09-29T06:39:06Z` |
| L2.5 | `:pipeline-application:detekt` | 0 findings | after replacing the wildcard import |
| L3 | `:pipeline-application:test --tests '*Timeout*' '*Block*' '*Event*'` | 28 classes / 132 tests / 0 failures / 0 errors | consumer regression on the coordinator change |
| L4 | `:pipeline-architecture-tests:test` | 327/327 | includes `FArchS0SurfaceManifestTest` 8/8 |
| L4 | `:pipeline-events:test` | 188/188 | vocabulary/codec/store regression |
| L5 | `:pipeline-application:check` | see §5 | round gate |

### RED reasons (rule 21: fail for the expected reason, not a timeout)

1. `assertEquals(1, exit)` → `expected: <1> but was: <0>` — self-contradictory assertion; the
   test's own message said "must not fail the build" and its name said "run stays green".
2. `expected: java.lang.String@…<TIMEOUT> but was: FailureKind@…<TIMEOUT>` — `FailureKind` is
   an `enum class`, not a `String`; the comparison compared a String literal to the enum.
3. `TimeoutTriggered authority event missing: [… 9 events …]` — the timeline is the proof the
   gap was real: `TimeoutScheduled` at sequence 5, `StepFailed(failureKind=TIMEOUT)` at 7,
   `RunFinished(outcome=failure)` at 9, and no block-level breach record anywhere.

## 4. Files

Production (1 file, +28 lines):
- `v2/pipeline-application/.../durable/CanonicalDurableRunCoordinator.kt` — the `TimeoutTriggered` producer.

Test (1 file, new, 17 witnesses):
- `v2/pipeline-application/src/test/.../S0SemanticWitnessMatrixTest.kt`

Docs (1 file):
- `docs/v2/surface/DSL_SURFACE_MANIFEST.md` — F1/F3 corrections + new §8 findings section.

## 5. Round gate

`:pipeline-application:check` — **1802 tests / 1 failure / 0 errors / 121 pre-existing skips**,
19m 14s, within the derived 1220s budget.

The single failure is `UatDsl001JenkinsFamiliarityTest.full grammar script compiles and emits
parseable JSON` with `java.util.concurrent.TimeoutException: ... timed out after 120 seconds`.
It is **NOT a regression from this block**, on three independent grounds:

1. The failing test file is not in this block's diff (4 files changed, none of them it).
2. The test exercises the grammar compiler only: it uses no `git(`/`checkout(` and no `timeout`
   block, so it cannot reach the code this block changed.
3. Run **isolated** (rule 28), it passes 4/4 with each test at ~10s — two orders of magnitude
   under its own 120s budget. Canary `4ec8e2ce…`, `2026-09-29T07:41:32Z`.

Classification: CPU-contention flake under full-suite parallelism (rule 30), not a semantic
defect. It is disclosed rather than hidden: the test carries a hardcoded 120s `@Timeout` that
is generous in isolation and tight under contention. Widening that budget is a separate
decision, not something to change silently inside a semantic-witness block.

Round gate budget note: the first two attempts used 1170s and 1220s. The 1170s run was killed
mid-`:test` with zero XMLs written, which is a budget miss, not a hang — `output.bin` grew
steadily (4096 → 7241 bytes) and prior receipts record this same task at 15m 39s (939s), so
1.3× = 1220s. The 1220s run completed inside budget. One orphaned wrapper from the killed run
had to be terminated before re-running, because the v2 concurrency guard (commit `582a7391`)
correctly refuses a second concurrent build of the same checkout.


## 6. Reference implementation consulted

- Jenkins declarative `timeout` step (`Restart Declarative Stage`/timeout semantics) and
  `catchError`/`unstable` build result projection: adopted "timeout produces a distinct
  breach transition, catchError contains without failing the build".
- Intentional deviation: PipelineK's `catchError` projects to `unstable` rather than
  Jenkins' `success` default; that is ADR-0054, not an accident, and the manifest now says so.
- Security implications reviewed: n/a for this WU (no new credential or path handling; the
  new emission is observability-only and adds no capability).

## 7. Out of scope respected

`git`/`checkout` real wiring is S2+ work, not S0: S0 only requires the surface to stop
*claiming* semantics it does not have. No new feature added. `post`, `agent`, `options`
remain as previously classified; this block did not open them.
