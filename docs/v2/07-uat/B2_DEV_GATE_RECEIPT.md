# B2 — devGate: one planner, one Gradle graph

- **Date:** 2026-10-15
- **HEAD at authorship:** `db9cc838` (worktree)
- **Scope:** new `scripts/plan-affected-tests.py` + suite; `just changed` rewritten to it;
  new `just dev` recipe. Operator approved gate work at discretion.

---

## 1. What changed

```text
scripts/plan-affected-tests.py   single source of truth for file -> test mapping
just changed <base>              advisory plan (was: a second bash copy of the mapping)
just dev <base>                  executor: affected python suites, then ONE Gradle graph
```

`just dev` properties:

- exactly **one** Gradle invocation per phase (no nested invocations against the checkout);
- **incremental** (no `--rerun-tasks`), UAT battery excluded via the existing
  `shard.excludes` property;
- build-system diffs plan the full round gate (`check`), not a guessed module;
- a changed script without a suite is reported as `UNCLASSIFIED` and the recipe exits 2
  (fail closed) instead of quietly running nothing;
- `:<module>:test` is emitted only when `v2/<module>/build.gradle.kts` exists — a plan
  never invents a task (the old bash fallback emitted `:settings.gradle.kts:test`).

## 2. Evidence

Hermetic planner suite:

```text
python3 scripts/test_plan_affected_tests.py
Ran 15 tests in 0.042s
OK
```

Real mapping samples:

```text
$ python3 scripts/plan-affected-tests.py --files v2/pipeline-step-sdk/http/... \
      --files scripts/admission-check.py --files v2/settings.gradle.kts
build-system change: full round gate (just gate)
gradle plan: v2/gradlew -p v2 check
python suite: python3 scripts/test_admission_check.py

$ python3 scripts/plan-affected-tests.py --files v2/config/x.yml   # no module
unclassified productive path: v2/config/x.yml                       (exit 2)
```

End-to-end executor run against a real commit range:

```text
$ just dev 5b0d08a2
dev plan: gradle=[] python=[scripts/test_audit_gradle_test_graph.py]
dev> python3 scripts/test_audit_gradle_test_graph.py
Ran 11 tests in 0.011s
OK
```

## 3. What this does NOT claim

- The planner maps at **module** granularity (the granularity the repository's test
  layout actually offers) plus the corpus/sdk/events special cases; it is not a
  call-graph impact analyser, and it does not pretend to be one.
- No before/after speedup is claimed yet: measuring `just dev` against `just gate-app`
  on a real application change is a `just baseline` exercise, queued, not asserted here.
- `just dev` is the developer loop, not a mandatory gate; B3 (`integrationGate`) is the
  next slice and will compose the evidence tooling rather than re-run whole gates.

## End-of-work-unit closure

```text
Reference implementation consulted: the removed bash mapping inside justfile was the
    previous authority; this consolidates it rather than adding a second engine.
Behaviour adopted:                  pure plan() + thin executors; fail closed on
                                    unclassified paths and on tools with no suite.
Intentional deviations:             module granularity, chosen because the fixture
                                    layout cannot support finer selection honestly.
Security implications reviewed:     none; git diff and test selection only.
Tests demonstrating the contract:    scripts/test_plan_affected_tests.py (15 methods)
                                    + the real `just dev` run above.
```
