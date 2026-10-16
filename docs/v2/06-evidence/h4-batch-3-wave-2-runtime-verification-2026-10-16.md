# H4 batch 3 wave 2 — runtime verification (2026-10-16)

## Verdict

```text
10 of 10 @Disabled UATs are SKIPPED at runtime by JUnit.
40 of 40 test cases skipped.
0 failures, 0 errors.
```

This receipt records the runtime-level verification of the
`@Disabled("migrated-pending")` annotations applied in
commits `f33dd0ae` and `2fa36077`. The static-level
verification (grep in `a7db0041`) and the compile-level
verification (`compileTestKotlin: BUILD SUCCESSFUL`) are
extended here with JUnit runtime evidence: every test case
in every @Disabled class is discovered, marked as
`<skipped/>` in the JUnit XML, and not executed.

## Verification (re-runnable)

```text
$ cd v2 && ./gradlew :pipeline-application:test \
    --tests UatLocal001KillDuringShTest \
    --tests UatLocal002ResumeAfterKillTest \
    --tests UatLocal007SandboxProfileTest \
    --tests UatLocal010SmokeE2ESandboxTest \
    --tests UatStep001ShExecutionTest \
    --tests UatStep002EchoCaptureTest \
    --tests UatStep003ErrorAbortTest \
    --tests UatStep004SleepTimingTest \
    --tests UatEvt001ReplayTest \
    --tests UatDsl006BodyExecutionTest \
    --rerun-tasks

BUILD SUCCESSFUL in 2m 8s
91 actionable tasks: 91 executed
```

The test run completed in 2m 8s on `origin/main = f40e6391`.

## Per-class JUnit XML summary

| Class | tests | skipped | failures | errors |
|---|---|---|---|---|
| `UatLocal001KillDuringShTest` | 1 | 1 | 0 | 0 |
| `UatLocal002ResumeAfterKillTest` | 1 | 1 | 0 | 0 |
| `UatLocal007SandboxProfileTest` | 14 | 14 | 0 | 0 |
| `UatLocal010SmokeE2ESandboxTest` | 12 | 12 | 0 | 0 |
| `UatStep001ShExecutionTest` | 2 | 2 | 0 | 0 |
| `UatStep002EchoCaptureTest` | 1 | 1 | 0 | 0 |
| `UatStep003ErrorAbortTest` | 1 | 1 | 0 | 0 |
| `UatStep004SleepTimingTest` | 1 | 1 | 0 | 0 |
| `UatEvt001ReplayTest` | 3 | 3 | 0 | 0 |
| `UatDsl006BodyExecutionTest` | 4 | 4 | 0 | 0 |
| **Total** | **40** | **40** | **0** | **0** |

Each class has `tests=N skipped=N failures=0 errors=0` in the
JUnit XML, and the XML contains the `<skipped/>` element
inside each `<testcase>` (e.g. for `UatLocal001`:

```xml
<testsuite name="dev.rubentxu.pipeline.v2.application.UatLocal001KillDuringShTest"
           tests="1" skipped="1" failures="0" errors="0"
           timestamp="2026-10-16T21:18:46.297Z"
           hostname="bazzite-rubentxu" time="0.0">
  <testcase name="sigkill mid sh resume reads result txt no reexec()"
            classname="dev.rubentxu.pipeline.v2.application.UatLocal001KillDuringShTest"
            time="0.0">
    <skipped/>
  </testcase>
</testsuite>
```

The `time="0.0"` and `<skipped/>` together are the JUnit-5
evidence that the test was discovered but not executed due
to `@Disabled`. The class-level `skipped="N"` counter
matches the test-case count exactly.

## Three-level verification summary

| Level | Evidence | Status |
|---|---|---|
| Source | `grep -q '@Disabled("migrated-pending")'` in 10/10 files (commit `a7db0041`) | VERIFIED |
| Compile | `compileTestKotlin: BUILD SUCCESSFUL in 11s` after all 10 edits | VERIFIED |
| Runtime | 40/40 JUnit test cases `<skipped/>`, 0 failures, 0 errors | VERIFIED (this receipt) |

The H4 batch 3 wave 2 work is verified at all three levels:
the annotation is in the source, the source compiles, and
JUnit recognizes the annotation and skips the tests at
runtime.

## Cross-references

- Trace correction: `docs/v2/06-evidence/h4-batch-3-trace-correction-2026-10-16.md` (commit `3aa64648`).
- Decision manifest: `docs/v2/06-evidence/h4-batch-3-deletion-decisions-2026-10-16.md` (commit `aec3a777`).
- Wave 2 commits: `f33dd0ae` (UatLocal001) + `2fa36077` (9 more).
- Wave 2 static verification: `docs/v2/06-evidence/h4-batch-3-wave-2-verification-2026-10-16.md` (commit `a7db0041`).
- Wave 3 KEEP decision: `docs/v2/06-evidence/h4-batch-3-wave-3-decision-2026-10-16.md` (commit `49749c39`).
- Wave 3.x KEEP decision: `docs/v2/06-evidence/h4-batch-3-wave-3x-decision-2026-10-16.md` (commit `f40e6391`).
- AGENTS.md §1 (UAT migration to harness; `@Disabled(migrated-pending)` is canonical).
