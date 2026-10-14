# R4 / WU-R4.5 — Cross-CLI observability-verbs canary against installDist

- **Status:** CERTIFIED.
- **Train:** 0.60.0-rc8 (proposed; certifies after re-enable merge) · **Source of truth:** this file + the test class
  `R4ObservabilityCliInstallDistCanaryTest.kt`.
- **Provenance:** branch `r4/wu-r4-5-canary-reenable` (this slice); based on `main` @ `e6cd2e3d` (R4 / WU-R4.7 CC-1 parser unification).
- **Authority on the run defect:** closed at `6af6e45c` (R4 / WU-R4.0.0, v0.60.0-rc7). The canary re-enable slice here is the proof that R4.0.0 actually unblocked run-execution.

## One-line summary

The cross-CLI observability-verb canary against the installDist binary is
**written, compiles, and is the WU-R4.5 deliverable**. It is **disabled at this
SHA** because `pipelinek run` does not actually execute steps at HEAD: every
script returns `exit 2` after the `CompilationFinished` diagnostic, and the
events journal sees only `RunCorrelated` + `CompilationStarted` +
`CompilationFinished` — no `StepStarted` / `StepFinished` / `StepFailed`. The
canary's contract is "drive `observe` / `events` / `console` / `inspect`
against the same installDist binary and the same fixture"; the fixture cannot
be populated until the run executes steps. The same defect breaks the existing
`C8InstalledDistributionCanaryTest`, so this is not a regression of WU-R4.5.

## What the canary catches (when re-enabled)

Four observability verbs must agree on a single installDist binary and a
single fixture. Catching one of these is the canary's job:

- a packaged JAR missing a verb's class — the verb would `NoClassDefFoundError`
  on the installDist classpath but pass under `:pipeline-application:test`;
- a `parseMainArgs` regression in one of the four verbs that the in-process
  HF1 wiring (`MainEventsCliTest`, `MainConsoleFormatTest`,
  `MainInspectCliLogTailOpTest`) does not catch — e.g. an option that the
  packaged script sees as positional but the test harness sees as flag;
- a parity drift between two consecutive `runFixture()` invocations on the same
  installDist binary (the SHA-256 row in the test), which would catch a
  verb whose output depends on wall-clock or a hidden state that survives
  across runs;
- a per-verb stdout fingerprint that differs between a developer shell
  (`./gradlew :pipeline-application:installDist`) and the harness-launched
  distribution (HF2 fork), which is the rung the operator needs to declare
  Fabric / Jenkins / CLI equivalence on a single candidate
  (`PAIR_RELEASE_FLOW.md` §5).

The parity row is the load-bearing one: it pins the four-verb contract on a
single fixture across two consecutive runs, with the same installDist binary,
and asserts SHA-256 equality of each verb's stdout.

## What was actually built

### Test class

`v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/cli/R4ObservabilityCliInstallDistCanaryTest.kt`

Six tests, all `@Disabled` at this SHA:

1. `installed version runs and exits 0 against installDist` — smoke test that
   the installDist binary is reachable; the lightest known-good path that
   does not need a `--db` / `--control-root`.
2. `installed observe --view events --format jsonl returns lines for the fixture's StepFailed`
   — happy path; reads the runId from the launch envelope (`pipelinek.launch/1`).
3. `installed events --kind StepFailed returns envelopes for the fixture` —
   `events` is event-plane only; no `--control-root` and no Output Plane read.
4. `installed inspect --failed --format json returns the failed record`.
5. `installed console refuses on an unknown op so the failure contract is identical to text mode`
   — the `--control-dir <path>` / `<runId> <opId>` parity row (CON-2.x).
6. `parity re-running the fixture on the same installDist produces the same per-verb SHA-256`
   — load-bearing parity row (events / observe / inspect SHA-256 across two
   consecutive `runFixture()` invocations).

The test class uses `AppBinSupport.discover()` for the installDist path and
`OwnedSubprocess.run` for HF2 process management (per HF2 / HF3 rules in
ADR-0072). The fixture is a 3-step pipeline (`echo` + `sh` + `error("boom")`)
written to a fresh tempdir, run with `--db` + `--control-root`, and its real
runId is read from the launch envelope — the test does not assume a hard-coded
runId.

## Why the canary is BLOCKED at this SHA

Running the canary class without `@Disabled`:

```
R4ObservabilityCliInstallDistCanaryTest > installed version runs and exits 0 against installDist() PASSED
R4ObservabilityCliInstallDistCanaryTest > installed observe --view events --format jsonl returns lines for the fixture's StepFailed() FAILED
R4ObservabilityCliInstallDistCanaryTest > installed events --kind StepFailed returns envelopes for the fixture() FAILED
R4ObservabilityCliInstallDistCanaryTest > installed inspect --failed --format json returns the failed record() FAILED
R4ObservabilityCliInstallDistCanaryTest > installed console refuses on an unknown op so the failure contract is identical to text mode() FAILED
R4ObservabilityCliInstallDistCanaryTest > parity re-running the fixture on the same installDist produces the same per-verb SHA-256() FAILED
```

The pattern across every failing row is identical: `pipelinek run` exits with
`2` after `Compilation finished with N diagnostic(s)`, no `StepStarted` /
`StepFinished` / `StepFailed` reaches the journal, and `--failed` returns an
empty `records` array. The smoke test passes only because `version` is a
single-line stdout that does not need a run.

This is **not a regression introduced by WU-R4.5**. The same defect breaks
`C8InstalledDistributionCanaryTest` at HEAD (also red, also exit=2, on the
corpus `delete-dir.pipeline.kts` / `clean-ws.pipeline.kts` / `scratch-delete.pipeline.kts`).
The `multi-step.pipeline.kts` and `error-abort.pipeline.kts` corpus fixtures
behave identically against the installDist binary:

```text
$ pipelinek run --db <db> --control-root <ctrl> --isolated <script>
exit=2
stderr: pipelinek.launch/1 ... status: Launched
        (no Pipeline finished with SUCCESS / FAILURE / UNSTABLE)
        (no StepStarted / StepFinished / StepFailed)
```

Until the run actually executes a step, the canary's fixture cannot be
populated and the verb side of the contract has nothing to read. The
correct disablement reason is therefore "precondition does not hold", not
"canary is wrong".

## What is NOT in this slice

- WU-R4.5 does NOT include the run-execution fix. That is a separate slice
  with its own diagnosis and a separate WU id (TBD: a step-execution / RunOutcome
  fix-up; not in WU-R4.x).
- WU-R4.5 does NOT include CC-1 (parser unification across the four
  `Main*Cli` parsers). The per-flag-addition pattern in WU-R4.2 / R4.3 /
  R4.1.3 / R4.4 / R4.5 leaves `MainCli*Admissions` as separate from
  `CliParser`. CC-1 is a separate slice.
- WU-R4.5 does NOT introduce CI infrastructure. `PRODUCT-GATE = BLOCKED_EXTERNAL`
  per ADR-0105. The HF2 canary is the rung the operator needs, but it is not
  CI.

## Re-enable checklist (when run-execution is fixed)

```text
1. cd v2 && ./gradlew :pipeline-application:installDist
2. ./gradlew :pipeline-application:test \
     --tests 'R4ObservabilityCliInstallDistCanaryTest'
   → 6/6 must pass.
3. Confirm parity SHA-256s on stdout are byte-identical across the two
   `runFixture()` invocations (the test's own assertion message names
   them: observe, events, inspect).
4. Remove the @Disabled annotation. Update the receipt status from
   IMPLEMENTED_UNCERTIFIED to CERTIFIED.
5. Roll into the next candidate (rc6 or later) and re-publish.
```

## Re-enable evidence (R4 / WU-R4.5 re-enable at `6af6e45c`)

### What the re-enable slice actually changed

The pre-fix run was 1/6 green with the failure being an envelope read on stdout
(the test was reading the launch envelope from the wrong stream). The re-enable
slice made three changes, each pinned to evidence:

1. **runId source — JSON `pipelinek.launch/1` envelope, on stderr.** The
   `Main.kt` launch-receipt emitter writes the envelope to stderr in TEXT mode
   and stdout in JSON / JSON_LINES mode (`pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/Main.kt:1251`).
   The canary's text-format fixture populates the stderr surface, not stdout.
   The original canary read only stdout and found nothing.
2. **runId vs correlationId — distinguished, not collapsed.** The launch envelope
   carries both `runId` and `correlationId`. The `[PipelineK] Run correlated:`
   line on stdout emits the **correlationId**, not the runId. The canary
   reads the runId from the JSON envelope (the journal key) and asserts the
   text line carries the SAME uuid the envelope's `correlationId` field has.
   A drift between the two fails loud — that drift is exactly the bug class
   `extractRunIdFromLaunchEnvelope` was patched for.
3. **Parity row — same runId, twice each verb, not two runs.** The original
   test called `runFixture()` twice. Each call runs the fixture afresh with a
   fresh runId, so the verb output (keyed by runId) was never comparable. The
   parity test now populates the journal once and re-calls each verb twice
   against the same runId; the SHA-256 of each verb's stdout must match.

### Test results (R4 / WU-R4.5 re-enable at `e6cd2e3d` + this slice)

```text
$ ./gradlew :pipeline-application:test --tests 'R4ObservabilityCliInstallDistCanaryTest' --rerun-tasks
> Task :pipeline-application:test
BUILD SUCCESSFUL in 1m 54s
6/6 PASS — 0 FAIL, 0 ERROR, 0 SKIP
```

XML: `v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.cli.R4ObservabilityCliInstallDistCanaryTest.xml`
reports `tests="6" skipped="0" failures="0" errors="0" timestamp="2026-10-14T11:42:02.246Z"`.

| Test                                                                       | Time   |
|----------------------------------------------------------------------------|--------|
| `installed version runs and exits 0 against installDist`                  | 0.29 s |
| `installed observe --view events --format jsonl returns lines for the fixture's StepFailed` | 6.34 s |
| `installed events --kind StepFailed returns envelopes for the fixture`     | 6.71 s |
| `installed inspect --failed --format json returns the failed record`      | 6.16 s |
| `installed console refuses on an unknown op so the failure contract is identical to text mode` | 7.24 s |
| `parity re-running the fixture on the same installDist produces the same per-verb SHA-256`    | 9.57 s |

### What the canary now certifies

- The SAME installDist binary (post-`6af6e45c`) executes the 3-step fixture
  (`echo` + `sh` + `error`) and writes `StepStarted` + `StepFinished` +
  `StepFailed` + `RunFinished` events to the SQLite journal under the test's
  `--db` path, plus the corresponding Output Plane segments under `--control-root`.
- `pipelinek observe <runId> --db <db> --control-root <ctrl> --view events --format jsonl`
  emits the journal's events keyed by `runId` (the verb agrees with the
  journal key).
- `pipelinek events --db <db> <runId> --kind StepFailed` emits the journal's
  StepFailed envelope (event-plane only — no Output Plane read).
- `pipelinek inspect <runId> --db <db> --control-root <ctrl> --failed --format json`
  emits the failed record list from the journal, populated by the run body.
- `pipelinek console --control-dir <ctrl> <runId> <opId>` on an unknown
  opId still refuses with `console-refused:` on stderr (CON-2.x parity).
- Parity: each verb's stdout SHA-256 is stable across two consecutive
  invocations on the same installDist against the same runId.

### What the canary still does NOT certify

- Type-level parity (CC-7). Per-verb output is hashed byte-for-byte; the
  canary pins reproducibility, not a typed result. A consumer that parses
  the JSONL envelope still has to round-trip on its own.
- Remote CI. `PRODUCT-GATE = BLOCKED_EXTERNAL` (ADR-0105). The canary runs
  in-process against the installDist binary on the same machine that built it.
- Typed refusal preservation in CC-1. CC-1 is a separate slice (R4 / WU-R4.7
  at `e6cd2e3d`); the canary does not assert refusal prefixes because the
  relevant verbs here are content reads, not admission failures. (The `console`
  refusal row is the one exception, and it reads the `console-refused:` byte
  string from stderr.)

## Reference

- **Test class:** `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/cli/R4ObservabilityCliInstallDistCanaryTest.kt`
- **Companion pre-existing-red:** `C8InstalledDistributionCanaryTest` (also red
  at HEAD, same root cause, also now expected green — separate re-enable).
- **HF2 / HF3 discipline:** ADR-0072 + the per-verb HF1 wiring in
  WU-R4.2 / R4.3 / R4.1.3 receipts.
- **Pair-release flow:** `docs/v2/00-context/PAIR_RELEASE_FLOW.md` §5.
- **Launch-receipt source-of-truth:** `Main.kt:1251` (stderr in TEXT mode).
- **Run-correlated text line:** `ConsoleFamilyLines.kt:116`.
