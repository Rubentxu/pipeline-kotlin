# C13 — Corpus sweep timeout budget had no margin

**Status:** complete
**Repository:** `/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin-restored`
**Baseline commit:** `3204c19f`
**Module:** `pipeline-application` (test only — no product code)
**Class:** latent test-budget defect, surfaced by C12. In scope under freeze
(certification gap: a gate that only passes on a fast machine is not a gate).

---

## 1. Symptom

After C12 fixed the interlock regression, the full `check` gate still failed:

```
> Task :pipeline-application:test FAILED
java.util.concurrent.TimeoutException: each corpus fixture produces non-empty
event stream timed out after 180 seconds
```

## 2. Diagnosis: the budget, not the product

The first corpus sweep passed (that was the C12 regression, now fixed). The
*second* sweep ran out of its 180s budget. Both sweeps do the same work: 30
fixtures, each spawning the installed CLI.

**OBSERVED timings (JUnit XML, not log text):**

| Run | tests | failures | errors | suite time |
|---|---|---|---|---|
| Baseline worktree `a1441573` | 2 | 0 | 0 | 337.9 s |
| My tree after C12 | 2 | 1 (timeout) | 0 | 351.4 s |

The baseline **passed** the identical suite at 337.9s. With a 180s per-test
budget and two ~170s sweeps, the baseline was clearing its budget by **under
2%**. There was never real headroom.

C12's VCS-marker interlock adds legitimate work (a few `Files.exists` probes per
cleanup Step). That cost ~13s across the suite and tipped a test that was
already on the edge.

Crucially: **all 30 corpus fixtures were already passing** at this point.

```
CompatibilityCorpusTest:  tests=30 failures=0 errors=0 time=154.8
CorpusNormalizerTest:     tests=4  failures=0 errors=0
CorpusSnapshotDifferTest: tests=4  failures=0 errors=0
UatLocal005CorpusUntouchedTest: tests=2 failures=0 errors=0
```

So this was never a product failure. It was a test that could only pass on a
fast, unloaded machine.

## 3. Fix

Both corpus sweeps moved from `@Timeout(180)` to `@Timeout(600)`:

- 600s is roughly **3x** the observed requirement (~170-175s per sweep), which
  is a genuine safety factor rather than a coin flip.
- Both tests do the same 30-fixture sequential sweep, so both budgets were
  raised together. Leaving one at 180s would preserve the same flake.
- The reasoning and the baseline measurement are recorded in a comment on the
  first test so the number is not later "optimised" back down without evidence.

**No product code changed. No assertion changed. No fixture was skipped or
disabled.**

Alternative considered and rejected: running the two sweeps in parallel, or
marking them `@Tag("release-scale")` to drop them from `check`. Parallel
execution would change the process-spawning semantics under test; excluding them
would remove the corpus from the release gate, which is precisely the coverage
that just caught the C12 regression. Raising the budget keeps the gate honest.

## 4. Evidence

```
$ ./gradlew :pipeline-application:test --tests "*UatCompat001CorpusSmokeRunTest*" --rerun-tasks
BUILD SUCCESSFUL in 6m 35s

tests="2" failures="0" errors="0" time="344.149"
```

OBSERVED: 2 tests, 0 failures, 344.1s. This is within 2% of the baseline's
337.9s, confirming the fix changed only the budget and not the work performed.

## 5. Known decisions

- **Raise the budget rather than speed up the test.** The suite is a
  certification sweep over 31 real CLI invocations; that cost is the point.
- Recorded the baseline measurement inline so a future "why is this 600?" can
  be answered without archaeology.

## 6. Outstanding

- The `isProjectCheckout` duplication from C12 remains follow-up debt.
- A non-VCS user project is still unprotected by the C12 interlock.
- C10 structural debt (`StageScope` 53 methods, `retry` complexity 35) remains
  suppressed by the detekt baseline and untouched.
- RP-5 conditions 4 and 7 still require human ratification. The 15 unpublished
  local commits destroyed with the original working tree remain unrecoverable.
