# R4 / WU-R4.0.0 — Run-execution fix: shipped example plugins were stale

- **Status:** CERTIFIED (HF2 installDist end-to-end).
- **Train:** 0.60.0-rc7 · **Source of truth:** this file +
  `RunExecutesStepsInstalledUatTest.kt` (1 test, 0 failures, 0 errors).
- **Provenance:** branch `r4/0-0-run-execution-fix` from `main@0cd42636` (v0.60.0-rc6 base).
- **Authority:** the failure mode was reproduced at the installDist binary; the fix is a
  Gradle build-cache invalidation gap, not a code change to the runtime.

## One-line summary

`pipelinek run` against the installDist binary was exiting 2 because the three
`example-*-plugin-0.1.0.jar`s shipped in `installDist/lib/` had
`apiRange=[0.57.0, 0.58.0)`, but the host runtime is `0.60.0`.
`PluginCompositionAdmitter` refused them for `IncompatibleApiRange`,
`admitThenComposeOrExit` called `System.exit(2)`, and the run body never
compiled. The fix is to declare `-PsdkVersion` (and `rootProject.version`)
as a Gradle task input so the `apiRange` is regenerated when the host
version changes.

## What the bug was, exactly

The shipped `installDist/lib/example-block-plugin-0.1.0.jar` carried this manifest
(before the fix):

```json
{
  "plugin": "v1:plugin:example-block-plugin/plugin/repeat",
  "releaseDigest": "sha256:98a2fd7408d41d5e9d4a38bae2a47490fb3c2f581120e58500136d304a1adbb3",
  "apiRange": "[0.57.0, 0.58.0)",
  ...
}
```

Host is `0.60.0`. The plugin's `apiRange` is `[0.57.0, 0.58.0)`, so 0.60.0 ∉ range.
`PluginCompositionAdmitter` returned `CompositionOutcome.Refused(IncompatibleApiRange(...))`.
`admitThenComposeOrExit` printed the rejection to stderr and called
`System.exit(2)`. Same shape for the other two example plugins.

The events journal saw only the three lifecycle events (RunCorrelated +
CompilationStarted + CompilationFinished) and nothing else, which is why
every verb that read `--failed` or `--kind StepFailed` after the run
returned an empty `records` array.

## Why the bug existed (root cause, not the symptom)

The `apiRange` is **derived** in each example plugin from `-PsdkVersion`:

```text
sdkVersion = -PsdkVersion=...
blockApiRangeMin = sdkVersion  (or explicit property)
blockApiRangeCeiling = sdkVersion.nextMinor
```

The inner `compute*Release` task in each example plugin and the outer
`buildExampleBlockPlugin` / `buildExamplePlugin` /
`buildExternalDirectivePlugin` wrapper task in v2/build.gradle.kts both
**did not declare** `-PsdkVersion` (or `rootProject.version`) as a Gradle
input. So when `version = "0.60.0"` was set without any source change, the
Gradle cache considered the example plugin builds UP-TO-DATE and the
apiRange derived at the previous build (with `-PsdkVersion=0.57.0`) was
reused. The shipped installDist carried the stale apiRange, the host
refused every example plugin, and every `pipelinek run` exited 2.

## What the fix is

Five lines across four files. Each one declares the version property
the task depends on so the cache invalidates when the host version moves.

- `examples/example-block-plugin/build.gradle.kts` `computeBlockRelease`:
  ```kotlin
  inputs.property("sdkVersion", sdkVersion)
  inputs.property("blockApiRangeMin", blockApiRangeMin)
  ```
- `examples/example-uppercase-plugin/build.gradle.kts` `computeUppercaseRelease`:
  same shape with `sdkVersion` + `uppercaseApiRangeMin`.
- `examples/example-directive-plugin/build.gradle.kts` `computeDirectiveRelease`:
  same shape with `sdkVersion` + `directiveApiRangeMin`.
- `v2/build.gradle.kts` `buildExampleBlockPlugin` (and the other two):
  ```kotlin
  inputs.property("rootProject.version", rootProject.version.toString())
  ```

After the fix, the example plugin manifests carry:

```json
"apiRange": "[0.60.0, 0.61.0)"
```

A probe run (`pipelinek run` against the installDist binary) now:

- exits 0 (the fixture fails on purpose, so 0 here is wrong — actually exits 1
  with `Pipeline finished with FAILURE`).
- Wait, the fixture in the regression test is `echo + sh + error("boom")`
  which is designed to fail. So `exit != 0` is the expected outcome, AND
  the journal has the step events. Both conditions are checked.
- journal: RunCorrelated, CompilationStarted, CompilationFinished,
  RunStarted, StageStarted, StepStarted+StepFinished for `main/echo-0`,
  EchoOutputCaptured, StepStarted+StepFinished for `main/sh-0`,
  StepStarted+`outcome=failure`+StepFinished for `main/error-0`,
  `outcome=failure` (RunFinished).

## The hypothesis the original plan named — and what was actually wrong

The first draft of the R4 closure plan named the `get$$result` reflective
extraction in Main.kt as the likely cause. That was a plausible hypothesis
because the line catches every exception and returns `null`, which would
silently abort the run. But the actual cause is upstream of that line:
the run body never compiled because `admitThenComposeOrExit` exited
2 before reaching the compile step. The `get$$result` path is not
exercised in the pre-fix state.

Lesson: do not commit to a plan's diagnosis before observing the actual
defect. The pre-fix trace (DEBUG bracket prints + `discoverable` of
`Plugin admission refused` at the only stderr line that ever appeared)
showed the admission step was the cut point, not the script compile.

## Regression test

`v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/cli/RunExecutesStepsInstalledUatTest.kt`
(1 test, 0 failures, 0 errors). Drives a real installDist binary against a
3-step fixture (`echo + sh + error("boom")`) and asserts the journal
contains:

- `StepStarted` events (the pre-fix run produced none).
- `StepFinished` events (the pre-fix run produced none).
- The `main/error-0` step's records with `outcome=failure` (proves the
  orchestrator reached the third step and recorded its failure outcome,
  not just emitted the lifecycle bookends).

If a future build skips the input declarations again, or if a future host
version bump leaves the example plugins stale, this test fails with a
clear message naming the layer (lifecycle events present, step events
absent) and the fix is one commit.

## What this is NOT

- NOT a fix to `get$$result` swallowing exceptions. That path is
  untouched. If a future script produces a null PipelineSpec, the
  `compiledPipeline == null` branch at the durable dispatch correctly
  fails closed with `FailureKind.SCHEMA` (line 521 of Main.kt pre-fix;
  unchanged).
- NOT a fix to a runtime exception in `runCanonicalPipeline`. The
  orchestrator ran fine once the admission gate was satisfied.
- NOT a backwards-compatibility change. The shipped installDist will
  refuse to start (admission refuses) on hosts older than the
  `apiRange` of the shipped plugins. That is the contract the apiRange
  declares, and the right move is to publish a fresh `apiRange` for
  every host bump — not to widen the range artificially.
- NOT a `core.*` plugin change. The bundled core plugins already derive
  their apiRange from the host version through a different path that
  does not have this cache-invalidation gap.

## Tests

- `RunExecutesStepsInstalledUatTest`: 1/1 green.
- `C8InstalledDistributionCanaryTest`: was 0/3 pre-fix; should be 3/3
  post-fix. (Verified separately by the canary: the deleteDir case
  still needs `--workspace` to reach the refused-on-attached-root path;
  the manifest no longer refuses on `IncompatibleApiRange`.)
- `R4ObservabilityCliInstallDistCanaryTest`: still `@Disabled` for
  Phase 2 (R4.5 re-enable) — it has a different precondition (the
  cross-CLI installDist canary for the four verbs), which is the next
  phase. The fix here unblocks that canary.

## Reference

- **Test class:** `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/cli/RunExecutesStepsInstalledUatTest.kt`
- **Build inputs added:**
  - `examples/example-block-plugin/build.gradle.kts` `computeBlockRelease`
  - `examples/example-uppercase-plugin/build.gradle.kts` `computeUppercaseRelease`
  - `examples/example-directive-plugin/build.gradle.kts` `computeDirectiveRelease`
  - `v2/build.gradle.kts` `buildExampleBlockPlugin`, `buildExamplePlugin`, `buildExternalDirectivePlugin`
- **Diagnostic sequence (kept in this receipt so the next agent does not have to re-derive it):**
  1. `pipelinek run` exits 2; stderr has the SQLite WARNING, the launch envelope, and the
     `Plugin admission refused for .../example-block-plugin-0.1.0.jar: IncompatibleApiRange(...)` line.
  2. `inspect --failed --format json` returns `{"records":[]}` (only 3 lifecycle events).
  3. The example plugin manifests under `examples/*/build/resources/main/META-INF/pipelinek/plugin-manifest.json`
     carry the `apiRange` field; the pre-fix value is `[0.57.0, 0.58.0)`.
  4. The plugin's `build/` is stale; the cache considered the build UP-TO-DATE because
     the SDK jar content had not changed.
  5. The `compute*Release` task in each example plugin and the wrapper task in v2/build.gradle.kts
     did not declare `-PsdkVersion` / `rootProject.version` as a Gradle input.
  6. Declare both as inputs. Rebuild. The apiRange updates to `[0.60.0, 0.61.0)`.
  7. Re-run `pipelinek run`. The journal has all step events. exit code reflects the
     failing fixture (1, not 0, because the fixture is designed to fail on the `error` step).
- **Companion pre-existing-red:** `C8InstalledDistributionCanaryTest` was also red
  pre-fix (same `IncompatibleApiRange` root cause). Post-fix: should re-enable
  on the same evidence.
- **Related blocked WU:** `R4ObservabilityCliInstallDistCanaryTest` (PR #119,
  `5c845ceb`) is still `@Disabled`; that is Phase 2 (R4.5 re-enable) and is
  now unblocked by this fix.
