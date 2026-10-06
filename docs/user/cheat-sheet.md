# PipelineK — Cheat sheet

**Documented against**: development branch, `pipelinek 0.47.0` (v2/build.gradle.kts:75), commit `b08fa948`
**Not verified against a published binary.** The current published release is `0.47.0` (its ZIP digest is listed in the release `SHA256SUMS`); see the divergence note below.

> **Documentation divergence.** This page previously claimed the `0.39.0` contract and stated that
> `parallel` / `retry` / `catchError` did not exist. That claim did not hold against the code: they
> are declared in the v2 DSL and exercised by `examples/run.sh`. The page now documents the
> development branch (`0.47.0`). Recorded 2026-10-06. See `docs/user/README.md` → "Known divergences".

> **Status.** No remote CI exists in this repository since 2026-09-30. The PRODUCT-GATE is
> `BLOCKED_EXTERNAL`; nothing here is a "production ready" claim.

Copy-paste reference. Facts come from reading the code at `b08fa948`; the example exit codes are the
ones `examples/run.sh` asserts.

## Subcommands

| Command | Does | Source |
|---|---|---|
| `version` | prints `pipeline <version>` from the jar manifest | `Main.kt:72` |
| `doctor` | jdk / os / working-directory writability | `Main.kt:91` |
| `events` | structured history from the journal | `Main.kt:115` |
| `events verify` | checks persisted history against a YAML contract. Does **not** re-run | `Main.kt:116` |
| `console` | output transcript of one `opId` | `Main.kt:133` |
| `credentials` | `add` \| `list` \| `remove` | `Main.kt:140`, `MainCredentialsCli.kt:94` |
| `validate` | compiles, prints diagnostics. **Does not run** | `Main.kt:190` |
| `run` | runs the pipeline | `Main.kt:232` |

Only `validate` and `run` go through the argument parser (`CliParser.kt:135`).

## Flags — `run` and `validate` only

| Flag | Argument | Effect | Source |
|---|---|---|---|
| `--db` | path | SQLite journal. Without it everything is in memory | `CliParser.kt:192` |
| `--resume` | — | resume a previous run. Requires `--db` | `CliParser.kt:196`, `Main.kt:239` |
| `--rerun` | — | force a fresh run. Requires `--db` | `CliParser.kt:202` |
| `--control-root` | path | control root of the durable shell | `CliParser.kt:208` |
| `--workspace` | path | working directory | `CliParser.kt:212` |
| `--isolated` | — | managed scratch workspace | `CliParser.kt:216` |
| `--plugin-jar` | path, repeatable | plugin JARs | `CliParser.kt:220` |
| `--allow-network` | — | allow egress. Denied by default | `CliParser.kt:229` |
| `--sandbox-profile` | `none` \| `local` \| `os` | `os` is rejected | `CliParser.kt:233` |

**Flags come before the script path.** `run script.kts --db x` silently ignores `--db x`
(`CliParser.kt:144`, `CliParser.kt:151`).

## Exit codes

| Code | Meaning |
|---|---|
| `0` | success, including `RunOutcome.Unstable` |
| `1` | pipeline `Failure` / `Aborted`, **and invalid CLI arguments** (`Main.kt:151`) |
| `2` | invocation / admission: script not found, failed `validate`, `--resume`/`--rerun` without `--db`, invalid `--control-root`, non-canonical Step, lease already held, compile failure, `doctor` not writable |
| `3` | artifact without `Implementation-Version`; **during a run**, a credential store whose passphrase is missing or wrong (`Main.kt:723,728`) |
| `4` | **during a run**, tampered credential store (`Main.kt:732`) |

`pipelinek credentials list` without a passphrase exits `1`, not `3`; with a wrong passphrase it
lists `Unknown` rows and exits `0`.

Invalid CLI arguments are `1`; every other input rejection is `2`. Do not unify them
(`Main.kt:151` against `Main.kt:186`, `:225`, `:241`, `:269`, `:430`, `:830`).

## Copy-paste commands

```bash
# Run
pipelinek run --workspace . pipeline.kts
pipelinek run --db ./.pipelinek/run.sqlite --control-root ./.pipelinek/ctl pipeline.kts

# Durable: second run with the same --db reuses what already succeeded
pipelinek run --db ./.pipelinek/run.sqlite --control-root ./.pipelinek/ctl --rerun pipeline.kts
pipelinek run --db ./.pipelinek/run.sqlite --control-root ./.pipelinek/ctl --resume pipeline.kts

# Compile only (does NOT run the steps, does NOT execute the canonical bridge)
pipelinek validate pipeline.kts

# Events
RUN_ID=$(pipelinek run --db ./run.sqlite pipeline.kts | jq -r '.[0].runId')
pipelinek events --db ./run.sqlite "$RUN_ID"
pipelinek events --db ./run.sqlite "$RUN_ID" --kind StepFinished --limit 20
pipelinek events verify --db ./run.sqlite --run "$RUN_ID" --contract c.yaml [--scope last-segment]

# Console transcript — the opId comes from the stream filenames, never from a bare index
for OP in $(ls -1 ./.pipelinek/durable-shell/output-plane/streams/ \
            | sed -E "s/^${RUN_ID}_//; s/_transcript$//"); do
  pipelinek console --control-dir ./.pipelinek/durable-shell "$RUN_ID" "$OP" --max-bytes 65536
done
```

An `opId` looks like `<runId>-s<stage>-<step>`, repeating the `runId` as a prefix
(`OpId.kt:60`). A guessed value such as `0` or `op-1` fails with
`console-refused: unknown-stream`, and in a `parallel` run the id also carries `-b`/`-bp`
segments that the event history does not record — the stream filename is the source of truth.

## Examples — expected exit codes

`examples/run.sh` asserts every row below. Two runs = the example is run twice with the same `--db`
and the second run must reuse, not re-execute.

| File | Shows | Exit | Outcome |
|---|---|---|---|
| `01-hello.pipeline.kts` | minimal pipeline: one stage, one `echo` | `0` | success |
| `02-multi-stage.pipeline.kts` | three stages in order | `0` | success |
| `03-shell.pipeline.kts` | real OS processes with `sh` | `0` | success |
| `04-kotlin-control-flow.pipeline.kts` | `script { }` with control flow | `0` | success |
| `05-failing-step.pipeline.kts` | `sh("exit 3")`; the next stage does **not** run | `1` | failure |
| `06-durable.pipeline.kts` | `--db` twice: the second run reuses | `0` ×2 | success |
| `07-catch-error.pipeline.kts` | nested `catchError`: FAILURE → UNSTABLE | `0` | **unstable** |
| `08-parallel.pipeline.kts` | `parallel` + `branch`; the 2nd run reuses | `0` ×2 | success |
| `09-retry.pipeline.kts` | `retry(3) { }`: fails once, then succeeds | `0` ×2 | success |
| `10-timeout.pipeline.kts` | `timeout(2, "SECONDS")` over `sleep 30` | `1` | failure |

```bash
examples/run.sh                          # all examples
examples/run.sh 01-hello.pipeline.kts    # one example
```

## Minimum pipeline

```kotlin
pipeline {
    stages {
        stage("build") {
            sh("./gradlew --no-daemon build")
            sh("test -f build/libs/*.jar")
            echo("PIPELINE-OK")
        }
    }
}
```

Fails the run on purpose (CLI exits `1`):

```kotlin
pipeline {
    stages {
        stage("fail") { sh("exit 3") }
    }
}
```

## What the DSL actually does

| Status | Constructs |
|---|---|
| **Proven by the examples** | `pipeline` `stages` `stage` `echo` `sh` `script` `catchError` `parallel` + `branch` `retry(count) { }` `timeout(time, unit) { }`, plus `--db` reuse |
| **Declared, no reference example** | `withEnv` `dir` `withCredentials` `stash`/`unstash` `archiveArtifacts` `artifactQuery` `deleteDir` `cleanWs` `writeFile` `readFile` `fileExists` `error` `sleep` `warnError` `unstable` `pwd` `isUnix` `waitUntil` `milestone` `publishHTML` `lock` `input` `timestamps` `post` `environment { }` `options { timeout }` `agent*` `whenGate`/`whenEnvIs`/`whenEnvPresent` `scmGit`, plugin builders (`httpRequest`, `junitResults`, `scmGitCheckout`, `core-utils.*`) |
| **Fails closed — compiles, then `run` exits `2`** | `git()` (resolves to the unregistered key `core.checkout`), `load()` (`core.load`), `node { }`, `ansiColor { }` |
| **Throws at the call** | `retry(count, delaySeconds)` — the step-level form, no block (`StageScopeBuilders.kt:306`). Not the same as the block form `retry(count) { }` |
| **Does not exist** | `when { }`, `parameters { }`, `properties { }`, `triggers { }`, `agent { }` as a block, `options` beyond `timeout` |

## Environment variables

| Variable | Effect |
|---|---|
| `PIPELINE_STORE_PASSPHRASE` | passphrase of the secret store (`PassphraseResolver.kt:20`) |
| `PIPELINE_SANDBOX_ALLOW_EXTRA` | sandbox relaxation (`SandboxConfig.kt:54`) |
| `PIPELINE_SANDBOX_PATH_KEEP` | keep sandbox paths (`SandboxConfig.kt:55`) |
| `APP_HOME` | packaged plugin classpath (`ScriptDefinition.kt:81`) |

No other `System.getenv` is read in `src/main`. `.env` is **not** loaded automatically.

## Traps

| Trap | What happens |
|---|---|
| Flags after the script path | silently ignored, no error (`CliParser.kt:151`) |
| `validate` as a rehearsal | can say `VALIDATION SUCCESSFUL` for a script `run` rejects with exit `2`; it never starts processes (`Main.kt:191`) and never runs the canonical bridge |
| Exit code `1` vs `2` | bad CLI arguments are `1`; every other rejection is `2` |
| Assuming a failing run exits with the step's code | `sh("exit 3")` → CLI exits `1`, not `3` |
| `--subject-kind` | the flag does not exist; the real one is `--subject` (`MainEventsCli.kt:36`) |
| `[--after-last-run-started]` | printed in the usage text but **not implemented**; the real flag is `--scope last-segment` (`MainEventsVerifyCli.kt:32`, `:37`) |
| `--allow-network=true` | not accepted; the flag takes no value (`CliParser.kt:224`) |
| `--isolated --workspace X` | rejected, mutually exclusive (`CliParser.kt:154`) |
| `--resume --rerun` | rejected, mutually exclusive (`CliParser.kt:196`, `:202`) |
| `--resume`/`--rerun` without `--db` | exit `2` (`Main.kt:239`) |
| Expecting `--help` / `--version` | not flags. Unknown command or unknown flag → exit `1` (`CliParser.kt:133`, `CliParser.kt:138`, `Main.kt:151`) |
| Expecting `--db` to make `validate` durable | `validate` accepts and ignores `--db` (`Main.kt:190`) |
| `--sandbox-profile os` | rejected (`CliParser.kt:233`) |

## Where next

| If you want | Go to |
|---|---|
| start from zero | [`quickstart.md`](quickstart.md) |
| install / update | [`installation.md`](installation.md) |
| full CLI detail | [`cli-reference.md`](cli-reference.md) |
| full DSL detail | [`pipeline-dsl.md`](pipeline-dsl.md) |
| events and recovery | [`events-and-troubleshooting.md`](events-and-troubleshooting.md) |
| the index | [`README.md`](README.md) |