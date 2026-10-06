# PipelineK — Quickstart

**Documented against**: development branch, `pipelinek 0.47.0` (v2/build.gradle.kts:75), commit `b08fa948`
**Not verified against a published binary.** The current published release is `0.47.0` (its ZIP digest is listed in the release `SHA256SUMS`); see the divergence note below.

> **Documentation divergence.** This page previously claimed the `0.39.0` contract and stated that
> `parallel` / `retry` / `catchError` did not exist. That claim did not hold against the code: they
> are declared in the v2 DSL and exercised by `examples/run.sh`. The page now documents the
> development branch (`0.47.0`). Recorded 2026-10-06. See `docs/user/README.md` → "Known divergences".

> **Status.** This repository has had no remote CI since 2026-09-30 (`.github/workflows/` does not
> exist). Do not read this page as a "production ready" claim; the PRODUCT-GATE is `BLOCKED_EXTERNAL`.

## What you will be able to do

Write a `pipeline.kts`, run it with the `pipelinek` command, read its exit code, and read the
structured record of what happened — starting from zero, with no prior CI experience.

## 1. Before you start

| Need | Detail |
|---|---|
| Java 21 or newer | the distribution does not bundle a JDK |
| The `pipelinek` binary | see [`installation.md`](installation.md) |

Check what you have:

```bash
pipelinek version      # → pipeline 0.47.0
pipelinek doctor       # jdk / os / workdir; exit 2 if the working directory is not writable
```

`version` reads `Implementation-Version` from the jar manifest — it reports the built artifact, not
a hard-coded string. A jar without that attribute exits `3` (`Main.kt:72`, `Main.kt:80`).

From a compiled checkout the binary lives at:

```text
v2/pipeline-application/build/install/pipelinek/bin/pipelinek
```

## 2. Your first pipeline

A **pipeline** is a recipe: a list of things to do, in order.
A **stage** is one named room of that recipe; stages run one after another, in the order you write
them. A **step** is what you actually do inside a room.

Create a file named `pipeline.kts`:

```kotlin
pipeline {                     // 1. one pipeline
    stages {                   // 2. the rooms, in order
        stage("hello") {       // 3. a room named "hello"
            echo("hello from pipeline-kotlin v2")   // 4. one step: say this out loud
        }
    }
}
```

Line by line:

| Line | What it is |
|---|---|
| `pipeline { }` | the whole recipe. Exactly one per file. |
| `stages { }` | the ordered list of rooms. |
| `stage("hello") { }` | one room, named `hello`. The name shows up in the events. |
| `echo("…")` | a step that prints text. Starts no operating-system process. |

This is `examples/01-hello.pipeline.kts` in this repository.

## 3. Run it

```bash
pipelinek run --workspace . pipeline.kts
```

Expected result:

| Channel | What you get |
|---|---|
| stdout | one JSON array of events: `[ {...}, {...} ]` (`Main.kt:436`) |
| stderr | `Pipeline finished with SUCCESS` (`Main.kt:440`) |
| exit code | `0` |

Read the last event, which carries the outcome:

```bash
pipelinek run --workspace . pipeline.kts | jq '.[-1]'
```

> ### Trap 1 — flags go BEFORE the script
>
> ```bash
> pipelinek run --db ./.pipelinek/run.sqlite pipeline.kts   # ✅ correct
> pipelinek run pipeline.kts --db ./.pipelinek/run.sqlite   # ❌ --db is silently ignored
> ```
>
> The parser reads `--` tokens only while they come **before** the first token that is not a flag,
> and that first plain token ends parsing (`CliParser.kt:144`, `CliParser.kt:151`). A flag placed
> after the script path is not an error — it is silently dropped. There is no warning.

`--workspace .` points the run at the directory that holds your project (the toolbox the steps work
in). The default resolution when the flag is omitted is **NO VERIFICADO** on this page: pass
`--workspace` explicitly.

Exit code `0` covers both `success` and `unstable` (`Main.kt:440`, `Main.kt:443`). Only `failure`
and `aborted` produce a non-zero exit.

## 4. `validate` is not a dry run

```bash
pipelinek validate pipeline.kts      # → VALIDATION SUCCESSFUL (stderr)
```

`validate` compiles the script and prints diagnostics. That is all it does.

> ### Trap 2 — `validate` does not prove the script runs
>
> `validate` **never** starts an operating-system process (`Main.kt:191`) and **does not execute the
> canonical bridge** — the gate that `run` applies is `Main.kt:423` (in-memory) and `Main.kt:827`
> (durable). A script can therefore print `VALIDATION SUCCESSFUL` (`Main.kt:227`) and still be
> rejected by `run` with exit `2`. The only real check is `run`.
>
> `--db` is accepted by `validate` and **ignored**: it returns before any store is opened
> (`Main.kt:190`, `Main.kt:228`).

| | `validate` | `run` |
|---|---|---|
| Starts OS processes | never (`Main.kt:191`) | yes |
| Writes a journal | no — `--db` ignored | yes, to `--db` |
| Rejects `git()` / `load()` / `node {}` / `ansiColor {}` | no | yes → exit `2` |
| Final message | `VALIDATION SUCCESSFUL` | `Pipeline finished with …` |

## 5. Now something real: a shell step

`sh("…")` asks the operating system to run a command — it starts a real process, and that process's
**exit code** decides everything. An exit code is the number a command answers with; `0` means "all
fine", anything else means "something went wrong".

```kotlin
pipeline {
    stages {
        stage("system-info") {
            sh("uname -a")
        }
        stage("loop") {
            sh("for i in 1 2 3; do echo iteration-\$i; done")
        }
    }
}
```

This is `examples/03-shell.pipeline.kts`. Rule, and it is the whole rule:

```text
sh exits 0  → the step succeeds, the stage continues
sh exits ≠0 → the step fails, the stage fails, later stages do NOT run
```

Note the `\$` in the loop: Kotlin would otherwise interpolate `$i` at script-parse time.

## 6. See the events

An **event** is a typed, structured record of something that happened. Think of it as a line in the
operator's notebook: the run does not just produce a pass/fail, it produces a history you can read
back. The **journal** is that notebook — a SQLite file — and `--db` is where you put it. A **runId**
is the ticket number of one particular run.

Without `--db`, the run keeps everything in memory and nothing survives the process
(`Main.kt:234`). With `--db`, it is written where you can ask for it later.

```bash
pipelinek run --db ./.pipelinek/run.sqlite --workspace . pipeline.kts > run.json
RUN_ID=$(jq -r '.[0].runId' run.json)

pipelinek events --db ./.pipelinek/run.sqlite "$RUN_ID"
pipelinek events --db ./.pipelinek/run.sqlite "$RUN_ID" --kind StepFinished
pipelinek events --db ./.pipelinek/run.sqlite "$RUN_ID" --limit 20
```

`events` prints one JSON envelope per line to **stdout** and a cursor token
(`evt-cursor-v1:<runId>:<sequence>`) to **stderr** (`MainEventsCli.kt:77`, `MainEventsCli.kt:80`).
`--db` and the `runId` are both required — without them the exit code is `2`
(`MainEventsCli.kt:46`).

## 7. Now something that fails

Failures are the point of a CI engine. This is `examples/05-failing-step.pipeline.kts`:

```kotlin
pipeline {
    stages {
        stage("ok") {
            echo("this stage runs")
        }
        stage("boom") {
            sh("echo 'about to fail' && exit 3")
        }
        stage("never-reached") {
            echo("this stage must NOT run")
        }
    }
}
```

```bash
pipelinek run --workspace . failing.pipeline.kts
```

| Channel | What you get |
|---|---|
| stdout | the events, ending in `RunFinished` with `outcome=failure` |
| stderr | `Pipeline finished with FAILURE` (`Main.kt:451`) |
| exit code | **`1`** |

Two things to notice:

1. `sh` exited `3`, but the CLI exits **`1`**. `1` means "the pipeline failed"; the step's own exit
   code travels inside the typed failure, not in the process exit code.
2. `stage("never-reached")` does not run. `examples/run.sh` asserts exactly that for example 05.

## 8. "Did I get it?" checklist

- [ ] I installed Java 21+ and the binary, and `pipelinek version` prints a version.
- [ ] I created `pipeline.kts` with `pipeline { stages { stage("…") { … } } }`.
- [ ] I ran `pipelinek run --workspace . pipeline.kts` and got exit `0`.
- [ ] I put **all flags before** the script path, and I know that a flag after it is ignored silently.
- [ ] I know `validate` compiles but does not run, and that only `run` proves a script works.
- [ ] I know `sh("…")` starts a real process, and that a non-zero exit stops the following stages.
- [ ] I looked at the run with `pipelinek events --db <path> <runId>`.
- [ ] I know exit codes: `0` success or unstable, `1` pipeline failure **or invalid CLI arguments**,
      `2` invocation or admission rejection.

## Where next

| If you want | Go to |
|---|---|
| install / update the binary | [`installation.md`](installation.md) |
| all subcommands and flags | [`cli-reference.md`](cli-reference.md) |
| the full DSL surface, and what is *not* proven | [`pipeline-dsl.md`](pipeline-dsl.md) |
| one page to copy from | [`cheat-sheet.md`](cheat-sheet.md) |
| events, transcripts, recovery | [`events-and-troubleshooting.md`](events-and-troubleshooting.md) |
| the index of this documentation | [`README.md`](README.md) |