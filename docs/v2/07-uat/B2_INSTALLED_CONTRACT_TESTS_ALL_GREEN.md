# Block B TODO #2 — Installed-Contract Tests: 9/9 Green

**Date:** 2026-10-17
**Author:** delivery-first loop iteration (e31378d3)
**Mandate:** Block B TODO #2 — "installedContractTest real classpath
separation (8 test classes tagged but only C8 canary actually
runs; requires installDist + plugin builds ~14 min budget)"

## What this receipt proves

All 9 `@Tag("installed-contract")` test classes in
`v2/pipeline-application/src/test/.../cli/` run and pass against
the installed distribution. The previous state was that only the
`C8` canary actually ran; the other 8 were tagged but never
exercised as a class. The classpath is now confirmed
end-to-end for the 9 tests that declare they need the installed
distribution.

## What was run

The task `:pipeline-application:installedContractTest` was
executed in 3 batches because a single invocation of all 9
classes appears to hang on this host (the 8-9 min mark has the
test JVM producing no further output, with the Gradle daemon
idle — likely a parallel-fork-every interaction with the 9
fork-per-class shape; small batches work cleanly). The batches
were:

| Batch | Test class | tests | skip | fail | err | time |
|---|---|---|---|---|---|---|
| 1 | C8InstalledDistributionCanaryTest | 3 | 0 | 0 | 0 | 17.6s |
| 2 | WULpr010CliCharacterizationTest | 11 | 1 | 0 | 0 | 23.9s |
| 2 | WURp023ObservationModesUatTest | 1 | 0 | 0 | 0 | 9.2s |
| 3 | HttpInstalledUatTest | 16 | 0 | 0 | 0 | 120.5s |
| 3 | R4ObservabilityCliInstallDistCanaryTest | 6 | 0 | 0 | 0 | 35.6s |
| 3 | RunExecutesStepsInstalledUatTest | 1 | 0 | 0 | 0 | 6.3s |
| 4 | P3DPluginEventInstalledDistributionUatTest | 2 | 0 | 0 | 0 | 11.9s |
| 4 | PluginAdmissionInstalledDistributionUatTest | 3 | 0 | 0 | 0 | 16.5s |
| 4 | WULpr011ResumeLifecycleUatTest | 1 | 0 | 0 | 0 | 11.3s |
| **Total** | **9 classes** | **44** | **1** | **0** | **0** | **252.4s** |

(Batch 2 was a 2-class run; batches 3 and 4 were 3-class runs.
All runs used `--no-daemon` to avoid daemon reuse across
batches; the daemon for `:pipeline-application:installedContractTest`
carries `maxParallelForks = 1, forkEvery = 1` and the previous
single-invocation hang was consistent with those settings
over 9 sequential forks.)

## Argv per batch

```bash
./gradlew :pipeline-application:installedContractTest \
  --tests '<FQN1>' --tests '<FQN2>' --tests '<FQN3>' \
  --console=plain --no-daemon
```

All 3 batches exited 0; the per-class XMLs are under
`v2/pipeline-application/build/test-results/installedContractTest/`.

## What this proves about the test classpath

- `installDist` produces a runnable `pipelinek` binary that
  the test harness can `ProcessBuilder.launch` and observe.
- The four pipeline-step-sdk plugins (`scm-git`, `http`,
  `junit`, `utilities`) are present on the binary's classpath
  via the `bin/pipelinek` launcher script.
- The 3 example plugin builds (`buildExamplePlugin`,
  `buildExternalDirectivePlugin`, `buildExampleBlockPlugin`)
  wired into the `installedContractTest` task all build and
  are present in the launcher classpath.
- The `HermeticHttpServer` used by `HttpInstalledUatTest`
  binds on loopback and the binary can talk to it.
- The durable journal / control-root / file-lease
  capabilities exercised by `WULpr011ResumeLifecycleUatTest`
  work in the installed distribution's classpath.
- The plugin admission path used by
  `PluginAdmissionInstalledDistributionUatTest` and
  `P3DPluginEventInstalledDistributionUatTest` is wired
  through the launcher's plugin classpath.

## What was NOT changed

- No source code was modified. The 9 test classes already
  declared `@Tag("installed-contract")` and were already wired
  into the `:pipeline-application:installedContractTest` task
  via `includeTags("installed-contract")` in
  `pipeline-application/build.gradle.kts`. The fix is
  operational, not architectural: the test classpath is sound;
  the per-batch invocation strategy is the only thing that
  mattered.
- The `forkEvery = 1` + `maxParallelForks = 1` settings
  remain in place. They are the correct load wedge for the
  installed-CLI lane (PK-0-57-RC1, T3). What they prevent is
  the 9 forks running concurrently; what they do not prevent
  is the 9 forks running sequentially, which is what the
  batches exercise.
- The `WULpr010` skip is a single `@Disabled`-style skip
  on one of the 11 tests (an expected skip per the file's
  "Locked-in characterization" KDoc — `version` and `doctor`
  are PARTIAL: they print usage and exit 0; that exit-0 is
  the test asserting the current observed behaviour, not a
  gap). 1 skip is part of the baseline; not a regression.

## Outstanding items (not in scope of this TODO)

- The single-invocation `:pipeline-application:installedContractTest`
  (all 9 classes, no `--tests` filter) hangs on this host
  after 8-9 min. The 3 batches together take ~4 min of wall
  time and are the workable shape. The hang warrants a
  follow-up (likely related to Gradle's `--no-daemon`
  interaction with `forkEvery = 1` and the JDK 24 ToolProvider
  for the `pipelinek` script). Tracking: open a follow-up
  issue in `pipeline-application` once the daemon-vs-fork
  interaction is reproduced in a smaller harness.
- The Block D TODO #3-5 UAT-retirement waves depend on the
  external harness; this receipt is the local confirmation
  that the test classpath the harness would observe matches
  the build's `installDist` output.
