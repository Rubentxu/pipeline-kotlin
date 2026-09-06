# INC-021 — CLI reports SUCCESS on compilation ERROR (silent no-op run)

- **Severidad:** high (silent false-success)
- **Prioridad:** P1
- **Descubierto:** 2026-09-06, authoring `examples/` (cycle em-4 follow-up, user-requested CLI demo)
- **Estado:** open — remediation needs a product cycle (scope firewall)

## Symptom

`pipeline run <script>` where the script has a Kotlin compilation error:

- stdout emits only `CompilationStarted` + `CompilationFinished` (with
  `severity: ERROR` diagnostics), then prints `Pipeline finished with SUCCESS`;
- exit code is **0**;
- **zero steps execute** (no `RunStarted`/`StepStarted`/`StepFinished`).

A compiling pipeline emits the full 7+ event lifecycle. Verified with
`examples/03-shell.pipeline.kts` before the `$`-escape fix: `$i` interpolated
as Kotlin → `Unresolved reference 'i'` → SUCCESS with no steps.

## Impact

Any script with a compile error appears to succeed. This also undermines
corpus assertions that only require exit 0 + non-empty events: compilation
events alone satisfy them (suspected false green for fixtures whose shell text
requires escaping the pre-compiler does not cover).

## Related findings (same discovery session)

1. **INC-021a — `validate` skips the pre-compiler rewrite.** `validate`
   compiles raw Kotlin, so scripts that `run` fine (pre-compiler extracts
   `sh(...)` payloads) fail validation with bogus unresolved-reference
   diagnostics. `validate v2/compatibility/06-loop.pipeline.kts` fails while
   `run` passes.
2. **INC-021b — `--db`/`--resume` CLI durable UX incomplete.** A second
   `run --db <same>` re-executes all steps (no memoized skip; each invocation
   appears to use a fresh RunId). `--resume` emits a merged stream (duplicated
   sequence numbers: partial journal replay + full re-execution). Memoized
   same-RunId skip IS proven at coordinator level (CanonicalDurableRunCoordinatorTest)
   and end-to-end in SPIKE-016, but not yet as one-command CLI resume.

## Repro

```bash
BIN=v2/pipeline-application/build/install/pipeline-application/bin/pipeline-application
sed -i 's/iteration-\\\$i/iteration-$i/' examples/03-shell.pipeline.kts  # break the escape
$BIN run examples/03-shell.pipeline.kts; echo $?   # 0 + SUCCESS, no steps
git checkout examples/03-shell.pipeline.kts        # restore
```
