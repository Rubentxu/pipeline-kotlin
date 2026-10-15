# B3 — integrationGate: composed evidence with a typed receipt

- **Date:** 2026-10-15
- **HEAD at authorship:** `361b7eba` (worktree)
- **Scope:** new `scripts/run-integration-gate.py` + suite, `just integration` recipe.
  Operator approved gate work at discretion. No existing gate was replaced.

---

## 1. What it is

```text
PHASE 1  python3 scripts/fitness-harness-coverage.py          (harness registry)
PHASE 2  affected python suites                               (from the planner)
PHASE 3  ONE Gradle invocation over the planned tasks         (or the round gate
         when the plan says so), UAT battery excluded via the existing
         shard.excludes property, incremental (no --rerun-tasks), with a boundary
         file written BEFORE the run so fresh JUnit evidence can be adjudicated

VERDICT  PASS | FAIL | BLOCKED | STALE | (NOT_RUN recorded per phase)
```

Typed rules, each pinned by a test:

| Verdict | Rule |
|---|---|
| `PASS` | every phase that RAN exited 0 |
| `FAIL` | any phase that ran exited non-zero |
| `BLOCKED` | the plan has unclassified paths, or `--expect-sha` ≠ HEAD (no phase runs) |
| `STALE` | the Gradle phase ran but produced no fresh JUnit XML inside the boundary |
| `NOT_RUN` | a phase with nothing to do: recorded, **never** counted as a pass |

The receipt carries HEAD SHA, tree state, base, the plan (changed files, gradle
tasks, python suites), each phase's argv/exit/duration and the fresh vs
carried-over JUnit counts. Freshness is measured by the same boundary technique
as `run-scoped-junit-report.py`, whose functions are reused (no second reader).

## 2. Evidence

Hermetic suite (injected runner + evidence collector, real planner, temp git repo):

```text
python3 scripts/test_run_integration_gate.py
Ran 13 tests in 2.447s
OK
```

Two defects this slice's own tests found and fixed:

- the planner used `git diff --name-only`, which **hides untracked files**; a
  change consisting of new files planned nothing. Now
  `git diff --name-only <base>` ∪ `git ls-files --others --exclude-standard`.
- the tests' phase classifier proved the winner-take-all rule (`BLOCKED` and
  `STALE` are reachable and are not silent passes).

Real end-to-end run (scripts-only diff → Gradle correctly NOT_RUN):

```text
$ just integration 5b0d08a2
INTEGRATION GATE  head=361b7eba8764  verdict=PASS
  base=5b0d08a2  tree=dirty  plan: gradle=[] python=[test_audit_gradle_test_graph.py,
                                                      test_plan_affected_tests.py,
                                                      test_run_integration_gate.py]
  [ran] fitness: exit=0 0.084s
  [ran] python:…/test_audit_gradle_test_graph.py: exit=0 0.147s
  [ran] python:…/test_plan_affected_tests.py: exit=0 0.174s
  [ran] python:…/test_run_integration_gate.py: exit=0 2.493s
  [NOT_RUN] gradle: exit=0 0.0s
```

## 3. What this does NOT claim

- `PASS` over a scripts-only diff means "the phases that ran were green"; the
  Gradle phase is explicitly `NOT_RUN`. A reader must not read it as a full gate.
- No concurrency limits per resource type (SQLite, leases, SIGKILL, sandbox) are
  implemented here; B3's per-resource limiter is still open, and the app-module
  fork policy (`forkEvery=1`) is untouched.
- `integrationGate` is not wired as a mandatory gate for `main`; that requires
  the `AGENTS.md`/`CERTIFICATION_PROTOCOL.md` wording change (operator approved
  gate work, but replacing a mandatory gate should still be a recorded decision).
- No ABI check (binary-compatibility-validator) is included yet: it runs in the
  Gradle phase only when a plan includes the module that configures it.

## End-of-work-unit closure

```text
Reference implementation consulted: run-scoped-junit-report.py (boundary and
    fresh/carried-over semantics) and plan-affected-tests.py (selection); both are
    imported, not reimplemented.
Behaviour adopted:                  compose existing evidence machinery into one
                                    typed receipt; measure freshness, never infer it.
Intentional deviations:             phases are sequential and single-Gradle by design
                                    (the checkout lock forbids concurrent invocations).
Security implications reviewed:     none; local processes and local files.
Tests demonstrating the contract:    scripts/test_run_integration_gate.py (13 methods)
                                    + the real run above.
```
