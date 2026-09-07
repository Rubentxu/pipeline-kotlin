# INC-021 — CLI reports SUCCESS on compilation ERROR (silent no-op run)

- **Severidad:** high (silent false-success)
- **Prioridad:** P1
- **Descubierto:** 2026-09-06, authoring `examples/` (cycle em-4 follow-up, user-requested CLI demo)
- **Estado:** fixed — remediation complete in cycle `p-733fb505b5a6bd2d/inc-021-cli-correctness`

## Symptom (pre-fix)

`pipeline run <script>` where the script has a Kotlin compilation error:

- stdout emits only `CompilationStarted` + `CompilationFinished` (with
  `severity: ERROR` diagnostics), then prints `Pipeline finished with SUCCESS`;
- exit code is **0**;
- **zero steps execute** (no `RunStarted`/`StepStarted`/`StepFinished`).

## Fix (cycle inc-021-cli-correctness)

The CLI now constructs `RunOutcome.Failure(PipelineFailure(kind=SCHEMA,
message="Kotlin compilation failed"))` when `host.compile` returns
`ScriptCompilationResult.Failure`. This routes through the same typed
outcome path as runtime failures, producing exit code 1 and
`Pipeline finished with FAILURE`.

The legacy fallback was also tightened: the `"success"` default only fires
when a `RunFinished` event with `outcome == "success"` is present; otherwise
`FAILURE` is forced.

## Related findings (out of scope for INC-021 fix cycle)

1. **INC-021a — `validate` skips the pre-compiler rewrite.** INCORRECT:
   both `validate` and `run` go through `Kotlin24ScriptingHost.compile` →
   `ScriptTextEscaper`. The original doc claim was inaccurate for the current
   code. Both surfaces now correctly exit non-zero on compile failure.
2. **INC-021b — `--db`/`--resume` CLI durable UX incomplete.** A second
   `run --db <same>` re-executes all steps (no memoized skip; each invocation
   appears to use a fresh RunId). `--resume` emits a merged stream (duplicated
   sequence numbers: partial journal replay + full re-execution). Deferred to
   INC-021d cycle.
3. **INC-021c — corpus fixtures 06/08/09 fail to compile.** Fixed directly:
   `StageScope.sh()` accepts `isScriptBlock: Boolean = false` and preserves it
   with `returnStdout` in `StepSpec.Shell`, restoring source compatibility for
   the three fixtures without changing scripted runtime facades.

## Repro (pre-fix)

```bash
BIN=v2/pipeline-application/build/install/pipeline-application/bin/pipeline-application
sed -i 's/iteration-\\\$i/iteration-$i/' examples/03-shell.pipeline.kts  # break the escape
$BIN run examples/03-shell.pipeline.kts; echo $?   # 0 + SUCCESS, no steps
git checkout examples/03-shell.pipeline.kts        # restore
```

## Post-fix verification

```bash
$BIN run v2/pipeline-application/src/test/resources/broken/99-broken-compilation.pipeline.kts
# Exit: 1
# stderr: Pipeline finished with FAILURE

## Regression detected in v0.33.1 (cycle `corpus-closure`)

After commit `f36a0cb7` (P2 corpus-closure, reordered the `runOutcome` when-block
to gate on per-step canonical analysis FIRST), compile failures with empty
`nonCanonicalSteps` re-entered the run branch and NPE'd on `compiledPipeline!!`
at `Main.kt:345`. The test `CliCompileErrorExitsOneTest.run exits one with
FAILURE on broken compilation()` regressed from PASS (v0.33.0 baseline
`202598e7`: tests=3 failures=0) to FAIL (v0.33.1 pre-fix: tests=3 failures=1).

Fix: hoist the `compileOutcome != null` arm of the when-block to be the FIRST
case so a compile failure is reported with exit 1 instead of NPE. Commit
`e67c646f` restores PASS (v0.33.1 post-fix: tests=3 failures=0).

Evidence: worktree method comparing base `202598e7` vs `f36a0cb7` vs `e67c646f`
all running `--tests dev.rubentxu.pipeline.v2.application.CliCompileErrorExitsOneTest`.
```
