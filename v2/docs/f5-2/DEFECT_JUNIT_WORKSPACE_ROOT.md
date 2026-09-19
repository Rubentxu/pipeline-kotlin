# DEFECT: `junit.results` workspaceRoot not resolved against the pipeline workspace

## Status
CLOSED — fixed in commit `7e0e5953` (F5.2.fix).

## Symptom (before fix)
```kotlin
junitResults(
    reportPath = "hello-world/build/test-results/test/TEST-com.example.SampleTest.xml",
    workspaceRoot = ".",
    failOnFailure = true,
)
```
Failed with `report file not found at /<process-cwd>/hello-world/build/...`
even when the XML existed inside the pipeline workspace supplied via
`--workspace`. The script author had no way to express "the pipeline
workspace" without an absolute path that depends on the developer's
machine or the CI runner.

## Reproducer (pre-fix)
- Checkout of a fixture into a fresh workspace.
- `sh` writes a JUnit XML inside that workspace.
- `junit.results` reads the XML via `workspaceRoot = "."`, relative path.

Observed in commit `fd8fecf9`: StepFailed `report file not found`.

## Root cause (confirmed)
`JUnitResultsStepDefinition.handler` read
`Paths.get(input.workspaceRoot)` and required it to be an existing
directory. When the caller supplied `workspaceRoot = "."`, the resulting
Path resolved against the process cwd, which is the launcher's cwd
(`/tmp/pk-uat-f5-2` in our runs), NOT the workspace supplied via
`--workspace`. The runtime context already publishes
`pipeline.workspace.root` as a system property (set by `Main.kt:583`
when `--workspace` is supplied), but the handler never read it.

## Fix
`JUnitResultsStepDefinition.handler` now resolves the effective
workspaceRoot as follows:

1. If `input.workspaceRoot` is blank / `"."` / `"./"` → fall back to
   `pipeline.workspace.root` (system property) and then to `user.dir`.
2. If `input.workspaceRoot` is absolute and names an existing
   directory → honour it.
3. If `input.workspaceRoot` is absolute but does NOT name an existing
   directory → fall back to the system property (prevents the handler
   from aborting with `workspaceRoot is not a directory` on
   caller-supplied paths that don't yet exist).
4. If `input.workspaceRoot` is relative and names an existing
   directory → honour it (preserves the test-only
   `workspaceRoot = "test/..."` pattern).
5. Otherwise → fall back to the system property.

The final fallback is always verified to be an existing directory;
otherwise the handler fails with a USER failure naming the resolved
path. The require() guarantee is unchanged: when the caller supplies an
explicit, existing, absolute workspaceRoot, the handler honours it
unconditionally — no regression of the F5.1 c10 contract.

## Verification
- 22/22 in `F5_2_JUnitStepContractTest` (4 new rows cover the
  fallback contract).
- 8/8 in `JUnitReportParserTest` (unchanged).
- 47/47 in `F5_*` regression (F5.1 + F5.2 together).
- Real E2E: `scenario_e2e.kts` on a fresh workspace
  (`/tmp/pk-uat-f5-2/ws_final`) — `Pipeline finished with SUCCESS`.
- 4 negative E2E scenarios:
  - missing → StepFailed ENGINE, msg 'report file not found at <abs>'
  - malformed → StepFailed ENGINE, msg 'malformed XML'
  - failing + failOnFailure=true → StepFailed ENGINE, msg
    '1 failed test(s) (1 failures + 0 errors out of 3)'
  - failing + failOnFailure=false → RunFinished success

## Out of scope (deliberate)
The boundary classifies PluginStepException as `FailureKind.ENGINE`,
not USER, even when the handler tagged the failure USER. This is
shared with `core.sh` and is a separate architectural decision; the
preserved user-facing message makes the cause observable.
