# WU-LPR-010 — CLI characterization baseline (closure receipt)

**Status:** `CLOSED WITH FINDINGS — measurement only, no production change`.

**Outcome:** 11 tests added, all GREEN against the current `installDist`
binary. The WU was a measurement slice, not a redesign.

**Test file:** `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/cli/WULpr010CliCharacterizationTest.kt` (428 LOC, 11 tests).
**Commit:** `3ed81466`.

---

## 1. What this WU was scoped to

Per the human brief:

> MEASURE the existing CLI, don't redesign. Run from `installDist`,
> characterize `version/doctor/validate/run/events/credentials`.
> Determine EXISTS/PARTIAL/MISSING/BROKEN.

> Exit code contract: 0 = success, 1 = pipeline fail, 2 = invocation/config/compile/admission.

The WU produces a characterization baseline, not a fix. Each finding is
recorded as a classification (EXISTS / PARTIAL / MISSING / BROKEN) and
filed for follow-up WUs. No production code was modified.

## 2. Surface × status matrix

| # | Surface | Status | Evidence |
|---|---------|--------|----------|
| 1 | `pipeline version` | **PARTIAL** | exits 1 (unknown-subcommand rejection prints usage); not a standalone command |
| 2 | `pipeline doctor`  | **PARTIAL** | exits 1 (same as version) |
| 3 | `pipeline validate <good>` | **EXISTS** | exit 0, emits `VALIDATION SUCCESSFUL` |
| 4 | `pipeline validate <malformed>` | **EXISTS** | exit 1, emits `VALIDATION FAILED` (drift: validate uses exit 1, run uses exit 2 on compile failure) |
| 5 | `pipeline validate <missing>` | **EXISTS** | exit 1, error emitted |
| 6 | `pipeline run <passing>` | **EXISTS** | exit 0, full event burst including `RunFinished` |
| 7 | `pipeline run <failing-sh-exit-7>` | **EXISTS** | exit 1, `RunFinished` carries `outcome:"failure"` |
| 8 | `pipeline run` event set | **EXISTS** | CompilationStarted/Finished, RunStarted, StageStarted, StepStarted, EchoOutputCaptured (for atomic Steps), StepFinished, StageFinished, RunFinished |
| 9 | `pipeline run --resume` (first invocation, empty db) | **BROKEN** | uncaught `IllegalArgumentException` → exit 1; should be typed exit 2 |
| 10 | `pipeline run --resume` (second invocation, prior run) | **PARTIAL** | exits 0 but emits TWO `RunFinished` bursts (journaled replay + fresh re-execution); handler runs twice |
| 11 | `pipeline <unknown>` | **EXISTS (as failure surface)** | exit 1, prints usage |

`pipeline events` and `pipeline credentials` were not exercised in this WU
because they are NOT registered subcommands in the current CLI parser —
they print usage and exit 1, identical to `version`/`doctor`. That is a
finding on its own (no command surface) and is filed for follow-up.

## 3. Locked-in rule: exit code contract

```text
0 = success
1 = invocation error OR pipeline failure
2 = admission / compile / preconditions (run path ONLY)

Drift: validate uses exit 1 on compile failure (not exit 2).
       --resume on empty db uses exit 1 (not exit 2).
       Both should be exit 2 per the canonical contract.
```

## 4. Resume characterisation — the most material finding

A `--resume` invocation on a prior durable record emits **two** distinct
event bursts with the same `runId`:

```text
burst 1 (sequence 1..N): the journaled events REPLAYED verbatim
         - includes StepStarted, EchoOutputCaptured, StepFinished
burst 2 (sequence 1..M): a fresh re-execution of the handler
         - includes StepStarted, EchoOutputCaptured, StepFinished again
```

Two `RunFinished` events are emitted within the same process invocation.

The canonical durable contract (per ADR-0074 and prior WU-LPR-302 receipts)
is that a `MEMOIZED` Step MUST NOT re-execute when durable output exists.
Whether the resume replay is correct, broken, or "by design" is an open
question for follow-up work. This WU only records the actual behaviour
and pins it.

The characterization test is intentionally worded to assert the current
behaviour so any future change to the resume semantics will fail
loudly, forcing a deliberate decision.

## 5. Test infrastructure

- Package: `dev.rubentxu.pipeline.v2.application.cli` (new package;
  no prior tests lived there).
- Helper: `ScannerSupport.rootFromUserDir()` resolves the project root
  from `user.dir` instead of relying on `-Pv2.root`, which the Gradle
  test runner did not propagate.
- Helper: `run(vararg args)` invokes the real `installDist` binary with
  `redirectErrorStream(true)` and parses the merged output.
- Persistent state: per-test temp dirs under `/tmp/lpr010-*`, cleaned
  in `finally`. The pre-commit hook rejects unquoted `rm -rf /tmp/...`;
  `deleteRecursively()` is the safe alternative.
- Binary resolution: `PipelineInstallDistribution.installDir.resolve("bin/pipeline-application")`.

## 6. Build evidence

```text
Test command: ./gradlew -p v2 :pipeline-application:test \
              --tests 'WULpr010CliCharacterizationTest'
Result:       11 tests, 0 failures, 0 errors, 0 skipped (28 s)
XML:          v2/pipeline-application/build/test-results/test/
              TEST-dev.rubentxu.pipeline.v2.application.cli.
              WULpr010CliCharacterizationTest.xml
```

## 7. Findings for follow-up (deferred, not fixed)

| ID | Finding | Suggested follow-up |
|----|---------|---------------------|
| F1 | `version` / `doctor` are not real subcommands; both fall through to unknown-subcommand handler | WU-LPR-011 (or fold into WU-LPR-032 admission) |
| F2 | `pipeline events` and `pipeline credentials` not registered as subcommands | WU-LPR-011 |
| F3 | `validate` exits 1 on compile failure; canonical contract says exit 2 | WU-LPR-011 |
| F4 | `--resume` on empty db throws uncaught `IllegalArgumentException` → exit 1; should be typed exit 2 | WU-LPR-011 |
| F5 | `--resume` emits TWO `RunFinished` bursts on a prior run (replay + re-execute). Canonical contract: handler MUST NOT re-execute | WU-LPR-011 (decide: bug vs by-design; if bug, separate fix slice) |
| F6 | No `docs/cli.md` documenting the actual exit code contract | WU-LPR-012 |

## 8. Auto-continue

This WU is measurement-only. Auto-continue to **WU-LPR-032** (support
admission: classify each public DSL surface as
SUPPORTED/EXPERIMENTAL/DEFERRED/UNSUPPORTED) which is the next slice in
the auto-train.

---

**CLOSED WITH FINDINGS — 2026-09-18.**
