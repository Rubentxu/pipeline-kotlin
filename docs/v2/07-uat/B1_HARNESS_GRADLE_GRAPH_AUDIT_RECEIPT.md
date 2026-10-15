# B1 — Gradle test-graph audit: what each test task drags in

- **Date:** 2026-10-15
- **HEAD at authorship:** `5b0d08a2` (worktree)
- **Scope:** new `scripts/audit-gradle-test-graph.py` + suite. Read-only audit; no
  build file was modified by this work.

The B1 checkbox "auditar las tareas Gradle … detectar duplicaciones, tareas que
reconstruyen distribuciones innecesariamente, arranques JVM redundantes" needs a
repeatable instrument, not impressions. This tool runs `gradlew <task> --dry-run`
per test task (configuration only) and classifies the resulting graph.

---

## 1. Instrument

| Output | Meaning |
|---|---|
| graph size per task | how many tasks a test task would run |
| `HEAVY_IN_TEST` | distribution/SBOM/plugin-JAR builders reachable from a `*:test` |
| `DUPLICATE_HEAVY` | the same heavy task reachable from more than one test task |
| `TEST_ON_TEST` | a test task whose graph contains another module's `test` |
| `FORK_POLICY` | `maxParallelForks` / `forkEvery` / `useJUnitPlatform` per build file (scope `v2/`) |

`--fail-on <class>` turns a finding class into a gate, so a ratchet can be added
later without editing the tool.

## 2. Measured (this tree, six key test tasks)

```text
:pipeline-domain:test               13 tasks
:pipeline-events:test               25 tasks
:pipeline-step-sdk:runtime:test     43 tasks
:pipeline-architecture-tests:test  176 tasks
:pipeline-application:test         176 tasks  heavy: installDist, buildExamplePlugin,
                                                  buildExternalDirectivePlugin, buildExampleBlockPlugin
:pipeline-release:test             184 tasks  heavy: + cyclonedxBom, distZip

FINDINGS
  [HEAVY_IN_TEST] :pipeline-application:test …
  [HEAVY_IN_TEST] :pipeline-release:test …
  [DUPLICATE_HEAVY] buildExamplePlugin / buildExternalDirectivePlugin /
                    buildExampleBlockPlugin / installDist  (app test + release test)

TEST JVM POLICY (v2)
  pipeline-application: maxParallelForks=1 forkEvery=1 useJUnitPlatform=true
  every other v2 module: unset (Gradle defaults: 1 fork, never recycled)
```

## 3. Interpretation (checked, not assumed)

- The application test task's `installDist` dependency is **load-bearing for
  non-UAT tests**: `AppBinSupport`, `Console*`, `F5_2_JUnitStepContractTest`,
  `CoreArtifactQueryStepContractTest`, `SelfHostedPipelineScriptHonestyTest` and
  the corpus fork the installed binary. It is not a candidate for blind removal;
  the lever is splitting installed-CLI tests into their own task/source set
  (WU-RP-005 direction), measured with `just baseline`.
- `:pipeline-release:test` pulling `distZip` + CycloneDX is intentional: that task
  verifies the distribution it stages. It is therefore permanently "heavy" and
  must not be used as a fast-feedback task.
- `DUPLICATE_HEAVY` costs nothing *within one Gradle invocation* (Gradle executes
  the shared task once), but every extra invocation pays it again — which is why
  the one-graph-per-phase rule matters for the harness.
- The JVM-start surface is concentrated: only `:pipeline-application` uses
  `forkEvery=1` (the documented pause pending the UAT wedge fix); all other V2
  modules reuse a single fork.

## 4. Evidence

```text
python3 scripts/test_audit_gradle_test_graph.py
Ran 11 tests in 0.013s
OK

python3 scripts/audit-gradle-test-graph.py            # real dry-runs, exit 0
python3 scripts/audit-gradle-test-graph.py --fail-on HEAVY_IN_TEST   # would exit 2 today
```

## 5. What this does NOT claim

- No dependency was changed here; findings are inputs for B2 work, not fixes.
- `--dry-run` shows the *would-be* graph at HEAD; it does not measure task
  durations (that is `just baseline`'s job).
- V1 build files (`lib-examples/`, `pipeline-steps-system/`, …) are out of the
  policy scan by design; the V2 critical path is what this audit covers.

## End-of-work-unit closure

```text
Reference implementation consulted: none applicable; Gradle --dry-run output
    format verified against real runs.
Behaviour adopted:                  classify graphs from the build's own task
                                    planning; surface findings with a fail-on lever.
Intentional deviations:             audit is advisory by default (exit 0) so it can
                                    be run on every review without becoming a gate
                                    nobody ratified.
Security implications reviewed:     none; configuration-only Gradle runs.
Tests demonstrating the contract:    scripts/test_audit_gradle_test_graph.py
                                    (11 methods, injected dry-run runner).
```
