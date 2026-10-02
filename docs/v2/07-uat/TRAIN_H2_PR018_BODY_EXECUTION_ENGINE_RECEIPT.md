# TRAIN H2 / PR-018 — BodyExecutionEngine (receipt)

**Slice:** TRAIN H2 (second hardening TRAIN after H1 / PR-017 `RunLifecycleEngine`)
**Branch:** `h2-body-execution-engine`
**Base:** `main == ecb029b3` (contains H1, PR-017 Done, and 0.46.0 ESTABLE)
**Head of slice:** `f1986164`
**WorkItem:** `a177e5c5-7709-4862-83b6-4c1485c43017` (PR-018)
**Authority:** ADR-0081 (continuation model, amended with dated markers in H1), ADR-0073
(block Steps re-enter through the body machinery), AGENTS.md hex-directional and
functional-design rules.

## 1. What this slice changes

H1 extracted the durable **run** lifecycle (bookends, outcome state, stage continuation)
into `RunLifecycleEngine`. H2 extracts the durable **body** path: what happens inside a
block Step — scope projection, the body-child loop, the inline retry/waitUntil loops, and
the durable segment fingerprints.

The coordinator was the single place where all of it lived. After H2 the coordinator
delegates and `BodyExecutionEngine` owns it.

| Layer | Change |
| --- | --- |
| `pipeline-application` | New `BodyExecutionEngine` (665 lines) owning the body-child loop (`invokeBodyChildren` over the `BodyChildDispatcher` seam), scope projection (`projectScope` → sealed `ScopedBody`), the waitUntil first-poll inline path, and the scoped-body execution block. The contract fingerprint helpers became internal top-level pure functions. `maxBackoffMs` is now extracted from the payload and passed to the engine, so the waitUntil backoff ceiling is configurable end-to-end. |
| `pipeline-architecture-tests` | The body-control seam fitness now pins that the loops live in the engine and the coordinator delegates. The durable-aggregate-identity fitness and the concrete-routing-debt fitness read the coordinator+engine union. The W1d routing fitness does the same. |
| `pipeline-domain` | Generated `$$serializer` classes excluded from the pitest mutation scope (they are generated, not authored). |
| build | The throughput probe is detached from the standard gate — see §4. |

### Composition, not circularity

The engine is constructed with its dependencies through the constructor plus one callback:

```kotlin
fun interface BodyChildDispatcher {
    suspend fun dispatch(...): StepOutcome
}
```

The coordinator implements that callback with its own `dispatch`, so body children re-enter
the canonical path by construction. There is no second execution route and no service
locator: the seam is one function wide, and the coordinator keeps the dispatch it already had.

## 2. Result measured (not estimated)

| Measure | `main` (ecb029b3) | `f1986164` | Delta |
| --- | --- | --- | --- |
| `CanonicalDurableRunCoordinator.kt` | 2473 lines | 2045 lines | **−428** |
| `BodyExecutionEngine.kt` | — | 665 lines | new |
| Net across the slice | — | — | +946 / −540 over 10 files |

The coordinator-growth guardrail (`CoordinatorGrowthGuardrailTest`, ratchet ceiling 2514)
remains green: the coordinator only shrank, and the bookend-ownership law still holds.

## 3. Behaviour preservation

`BodyExecutionCharacterizationTest` pins the body loop as a RED-first baseline: the exact
durable segment sequence for `dir`, `retry` and `withEnv`, plus the waitUntil first-poll
shape and the waitUntil deadline path. It is green at 5/5.

Every move in this slice is a **verbatim** move. Where a block was moved rather than
rewritten, the complexity that arrived with it was carried across explicitly
(`@Suppress("LongMethod")`, `@Suppress("CyclomaticComplexMethod")` on `executeScope3b`,
26 against a limit of 25) rather than laundered by editing the threshold.

## 4. Gate integrity defect found while certifying this slice

The repository gate could fail for reasons unrelated to the code under test. This was
found while closing H2, not while building it, and it is the most valuable thing this
slice produced.

`Rp022ThroughputProbe` pins a 20 MB/s wall-clock floor. Both the module build and the
probe's own KDoc state that it is **not** part of the standard gate, and
`excludeTags("performance")` enforces that for the `test` task. It was still running inside
`check` anyway, because the Kover plugin (0.9.9) wires every `Test` task it discovers as an
input to `koverGenerateArtifactJvm`:

```text
koverGenerateArtifactJvm --> performanceTest --> floor breach
```

A path the tag exclusion cannot reach. Measured on the same SHA and the same machine:

| Context | Best of 3 | Floor | Verdict |
| --- | --- | --- | --- |
| Inside the full `check` gate | 19,6 MB/s | 20 MB/s | **FAIL** |
| `:pipeline-credentials-api:performanceTest` alone | 22,4 MB/s | 20 MB/s | PASS |

That 14% swing is contention from the rest of the build, not the redactor. The gate was
non-reproducible, and a red gate at that moment would have said nothing about H2.

Fixed in `a0fa98d1` with Kover's supported `disabledForTestTasks` API. Verified on both
sides: `check` is green without scheduling the probe, and the probe still runs and still
fails loudly when invoked deliberately. **The floor is unchanged.** Nothing was weakened;
the measurement moved to where it means something.

## 5. Second defect: a fitness guarding a subset of the moved code

H2 moved the body-child loop and the credential acquisition into the engine, but
`Lfc2BodyExecutionPolicyFitnessTest` W1d still scanned the coordinator alone. The loop
inventory therefore measured zero loops and zero acquisitions, which the scanner reports as
`DISPATCH_WITH_CREDENTIALS_BLOCK` — a non-shared body path.

The direction of that failure is the point. An empty loop inventory is indistinguishable
from *"the body path was deleted"*, so a guard whose entire purpose is to fail when the
shared path is duplicated would have passed a deletion. Fixed in `f1986164` by reading the
coordinator+engine union, the same surface the sibling fitness already used. W1d is
green at 10/10 with 0 skipped.

The generalisable lesson: **extracting code silently invalidates every scanner whose input
was that code.** H2 caught this one because the gate caught it. Anything else scanning the
coordinator by path needs the same audit.

## 6. Product findings recorded to the byte

1. **ReDoS reachable from user surface** — the `excludes` pattern of the artifacts DSL
   reaches `AntPathMatcher` (spring-core 6.2.4) → GHSA-659m-px2c-25wj /
   CVE-2026-41848. Fixed by pinning spring-core 6.2.19.
2. **Catalogue drift** — `bcpkix` hardcoded to 1.80 below bcprov's `version.ref`.
   Unified BouncyCastle at 1.86.
3. **`maxBackoffMs` was a phantom from the DSL** — the constructor default of 60 s was
   unreachable because the value was never extracted from the payload, so the effective
   ceiling was fixed. Fixed end-to-end in `04114f47`.
4. **The waitUntil "hang" was a measurement artefact** — the correct doubling
   10 → 20 → … → 60000 accumulates ~145 s, and the 120 s JUnit budget expired during a
   legitimate 60 s wait. Not an engine defect.
5. **`RetryPolicy.backoffDelay` overflows** — `1L shl (attempt - 1)` overflows for
   attempt ≥ 64. Documented as a finding, deliberately **not** pinned as a contract, because
   pinning it would freeze the current behaviour as intended.
6. **`ViolationFixture` lost its subject** — it injects violations into the real coordinator
   source, which is now clean, so it had nothing to detect. Disabled with a stated reason
   in `47267311`; it needs synthetic sources. Open follow-up, not silently "fixed".

## 7. Gate

Full repository gate, forced re-run, on the slice head `f1986164`:

```text
cd v2 && ./gradlew check --rerun-tasks
```

```text
BUILD SUCCESSFUL in 20m 55s
277 actionable tasks: 277 executed
```

Measured from the test result XMLs written by this run only (641 class files, filtered by
mtime against the gate window):

| Measure | Value |
| --- | --- |
| Test classes reported | 641 |
| Tests | 4092 |
| **Failures** | **0** |
| Errors | 0 |
| Skipped | 140 |

Per module, the four that carry this slice:

| Module | Tests | Failures | Skipped |
| --- | --- | --- | --- |
| `pipeline-application` | 1942 | 0 | 121 |
| `pipeline-architecture-tests` | 353 | 0 | 10 |
| `pipeline-domain` | 665 | 0 | 0 |
| `pipeline-credentials-api` | 52 | 0 | 0 |

The 140 skips are pre-existing disabled/conditional tests, including the
`ViolationFixture` disabled in §6.6 — not a hidden pass.

**The gate is attributable to the code only because §4 was fixed first.** Before
`a0fa98d1`, a red or green verdict on this SHA could have been a contention artefact:
`pipeline-credentials-api/build/test-results/performanceTest` still carries the mtime of
the deliberate isolated run (07:48:23) and was not rewritten during the gate window
(07:56–08:20), which is the direct evidence that the probe no longer participates.


## 8. Deliberately not done in this slice

- **Unify the inline retry/waitUntil fallback with `RetryEngine` / `WaitUntilEngine`.**
  Two implementations of the same semantics remain. Mapped in the WorkItem as pending;
  PR-020 owns the size of the body picture once it fully lives in the engine.
- **Refactor `ViolationFixture` to synthetic sources** (finding 6).
- **The cycle phase ledger was not advanced.** The cycle
  `p-1f3622e11c093341/h2-body-execution-engine` still reports `phase: explore`,
  `artifacts: 0`, `lease: none` even though the work is in Git. Writing the exploration
  report into the cycle artifact directory, registering it in the artifact registry, and
  storing it via `sddk artifact store --kind exploration-report` all left the
  `phase.explore.complete` requirement `requires_met: false`. Recorded as a traceability
  gap rather than papered over; the closure is carried by this receipt and the WorkItem.
