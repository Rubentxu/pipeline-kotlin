# PipelineK — CLI reference

> **Documentation divergence.** This page previously claimed the `0.39.0` contract and stated that
> `parallel` / `retry` / `catchError` did not exist. That claim did not hold against the code: they
> are declared in the v2 DSL and exercised by `examples/run.sh`. The page now documents the
> development branch (`0.47.0`). Recorded 2026-10-06. See `docs/user/README.md` → "Known divergences".

**Documented against**: development branch, `pipelinek 0.47.0` (v2/build.gradle.kts:75), commit `b08fa948`
**Not verified against a published binary.** The current published release is `0.47.0` (its ZIP digest is listed in the release `SHA256SUMS`); see the divergence note below.

## What you will be able to do when you finish

- Invoke every subcommand correctly, with its real flags and nothing invented.
- Predict the exit code before you press Enter — including the two cases that
  look identical and are not.
- Run a pipeline, then read its history back with `events`, `events verify`
  and `console` without re-executing anything.
- Understand why `validate` saying `VALIDATION SUCCESSFUL` does **not** mean
  `run` will accept the script.

## The binary

The command is `pipelinek`. In a compiled checkout it lives at:

```text
v2/pipeline-application/build/install/pipelinek/bin/pipelinek
```

## Subcommands

| Subcommand | What it does | Line |
|---|---|---|
| `version` | Reads `Implementation-Version` from the jar. No version → exit `3` | `Main.kt:72`, `:80` |
| `doctor` | Checks JDK, OS, and whether the cwd is writable. Not writable → exit `2` | `Main.kt:91`, `:110` |
| `events` | Structured history from the journal | `Main.kt:115` |
| `events verify` | Verifies the **persisted** history against a YAML contract. Does **not** re-run | `Main.kt:116` |
| `console` | Output transcript of one `opId` | `Main.kt:133` |
| `credentials` | `add` \| `list` \| `remove` | `Main.kt:140`, `MainCredentialsCli.kt:94` |
| `validate` | Compiles and reports diagnostics | `Main.kt:190` |
| `run` | Executes | `Main.kt:232` |

Only `validate` and `run` go through `CliParser` (`CliParser.kt:135`). The
flags below belong to those two and to nothing else.

## Flags (`run` and `validate` only)

| Flag | Argument | Effect | Line |
|---|---|---|---|
| `--db` | path | SQLite journal. **Without it, everything is in memory** | `CliParser.kt:192` |
| `--resume` | — | Resume a previous run. **Requires `--db`**, else exit `2` | `CliParser.kt:196`, `Main.kt:239` |
| `--rerun` | — | Force a fresh run. **Requires `--db`** | `CliParser.kt:202` |
| `--control-root` | path | Control root of the durable shell | `CliParser.kt:208` |
| `--workspace` | path | Working directory | `CliParser.kt:212` |
| `--isolated` | — | Managed scratch workspace | `CliParser.kt:216` |
| `--plugin-jar` | path (repeatable) | Plugin JARs | `CliParser.kt:220` |
| `--allow-network` | — | Permits egress. **Denied by default** | `CliParser.kt:229` |
| `--sandbox-profile` | `none`\|`local`\|`os` | `os` → rejected | `CliParser.kt:233` |

`--allow-network` takes **no** value: `--allow-network=<value>` is not accepted
(`CliParser.kt:224`).

## The six traps

These are the places where the CLI does something other than what a careful
reader expects. All six are behavioural facts of the parser, with the line
that produces them.

### 1. Flags go **before** the script. Afterwards they are ignored, silently.

The parser loop consumes tokens while they start with `--`
(`CliParser.kt:144`), and the first non-`--` token is taken as the script and
**parsing stops** (`:151`). So:

```bash
pipelinek run --db /tmp/j.db build.pipeline.kts   # correct
pipelinek run build.pipeline.kts --db /tmp/j.db   # --db is ignored, no error
```

There is no warning. If a flag "does nothing", check its position first.

### 2. Unknown flag → exit `1`. Other input rejections → exit `2`. Do not unify them.

An unknown `--algo` prints the usage message and exits `1`
(`CliParser.kt:242`, `Main.kt:151`). Everything else that is refused — missing
script, failed `validate`, `--resume` without `--db`, invalid `--control-root`,
non-canonical step, lease already held, compile failure, non-writable cwd for
`doctor` — exits `2` (`Main.kt:186,225,241,269,430,830`).

If you write a wrapper script, you have to branch on the code, not assume
"bad input is always 2".

### 3. `--resume` and `--rerun` need `--db`; the pairs are mutually exclusive.

- `--resume` without `--db` → exit `2` (`CliParser.kt:196`, `Main.kt:239`).
- `--rerun` without `--db` → exit `2` (`CliParser.kt:202`).
- `--resume` **and** `--rerun` together → error; they are exclusive
  (`CliParser.kt:196,202`).
- `--isolated` **and** `--workspace` together → error; they are exclusive
  (`CliParser.kt:154`).

Without `--db` the journal lives only in memory, so there is nothing to resume
from or to re-run against.

### 4. `events verify` does **not** re-execute anything.

It reads the history already persisted in `--db` and compares it against a
contract file. If you want the pipeline to actually run again, that is `run`,
not `events verify`.

### 5. `events` documents a flag that does not exist.

The KDoc at `MainEventsCli.kt:14` advertises `--subject-kind`. **The real flag
is `--subject`.**

### 6. `events verify` advertises a flag that does not exist.

The usage message at `MainEventsVerifyCli.kt:37` mentions
`[--after-last-run-started]`, **which does not exist**. The real one is
`--scope last-segment`.

Both traps have the same cause: documentation drifted from the parser. Trust
the flag table, not the help prose.

## Exit codes

| Code | Meaning |
|---|---|
| `0` | Success, including `RunOutcome.Unstable` |
| `1` | Pipeline `Failure` / `Aborted`, **and invalid CLI arguments** (`Main.kt:151`) |
| `2` | Invocation / admission: script not found, `validate` failed, `--resume`/`--rerun` without `--db`, invalid `--control-root`, **non-canonical step**, lease already held, compile failure, `doctor` not writable |
| `3` | Artifact without `Implementation-Version`; credentials without a passphrase or with a wrong passphrase |
| `4` | Credential store tampered with |

Two things worth memorising:

- **`Unstable` is `0`.** A pipeline that ends unstable has not "half failed" in
  the shell's eyes.
- **`1` and `2` are not interchangeable.** See trap 2.

## Environment variables

| Variable | Effect | Line |
|---|---|---|
| `PIPELINE_STORE_PASSPHRASE` | Secret store passphrase | `credentials-local/.../PassphraseResolver.kt:20` |
| `PIPELINE_SANDBOX_ALLOW_EXTRA` | Sandbox relaxation | `step-sdk/runtime/.../SandboxConfig.kt:54` |
| `PIPELINE_SANDBOX_PATH_KEEP` | Sandbox path preservation | `SandboxConfig.kt:55` |
| `APP_HOME` | Classpath of bundled plugins | `scripting-api/.../ScriptDefinition.kt:81` |

No other `System.getenv` is read in `src/main`. **`.env` files are not loaded
automatically** — export them yourself if your pipeline needs them.

## `events`, `events verify`, `console`

### `events`

Structured history from the journal.

| Flag / positional | Meaning | Line |
|---|---|---|
| `--db <path>` | Journal to read. Missing → exit `2` | `MainEventsCli.kt:34`, `:46` |
| `--kind K` | Filter by event kind | `:35` |
| `--subject <v1:kind:seg…>` | Filter by subject | `:36` |
| `--limit N` | Default **100** | `:37` |
| `--after-cursor TOKEN` | Continue after a cursor | `:38` |
| positional | `runId`; missing → exit `2` | `:39`, `:46` |

One JSON envelope per line on **stdout** (`:77`); the cursor goes to
**stderr** (`:80`).

### `events verify`

Compares the persisted history against a YAML contract.

| Flag | Meaning | Line |
|---|---|---|
| `--db` | Journal to read | `MainEventsVerifyCli.kt:31` |
| `--run` | Run id to verify | `:31` |
| `--contract` | Contract file | `:31` |
| `--from-sequence N` | Start sequence | `:31` |
| `--scope last-segment` | Scope the check (the real flag; see trap 6) | `:32` |

It prints `contract:`, `observed-terminal-outcome:`, `acceptance:` and the
violations. Exit `0` = PASSED, `1` = FAILED, `2` = usage or decode error.

### `console`

The output transcript of a single `opId`.

| Flag / positional | Meaning | Line |
|---|---|---|
| `--control-dir <path>` | Control directory | `MainConsoleCli.kt:140` |
| `--max-bytes N` | Default 65536 | `:141` |
| `--after-cursor TOKEN` | Continue after a cursor | `:142` |
| `--range FROM:TO` | Byte range | `:143` |
| positionals | `runId` and `opId` | `:145` |

Rejections exit `1` (`:98`); invalid usage exits `2` (`:157`).

## `validate` vs `run` — the difference that confuses people

| | `validate` | `run` |
|---|---|---|
| Launches OS processes | **Never** (`Main.kt:191`) | Yes |
| SQLite journal | No: `--db` is accepted and **ignored** (returns at `:228`, before `:233`) | Yes |
| Events | Emits to an **in-memory** store and prints them as JSON on stdout `:209` | Persists to SQLite |
| Runs the canonical bridge | **NO** | Yes: `Main.kt:423` (in-memory) and `:827` (durable) |
| Detects `git()` / `load()` / `node{}` / `ansiColor{}` | **No** | Yes → exit `2` |
| Final message | `VALIDATION SUCCESSFUL` on stderr `:227` | `Pipeline finished with …` on stderr |

The consequence you must internalise: **`validate` is not a dress rehearsal.**
It can print `VALIDATION SUCCESSFUL` for a script that `run` then rejects with
exit `2`, because `validate` never reaches the canonical bridge where those
four constructs are refused. **The real check is `run`.**

And if you pass `--db` to `validate` expecting a journal, you get none: the
flag is accepted and discarded.

## Authority note

This repository has **no remote CI since 2026-09-30** (`.github/workflows/`
does not exist; commit `754ddda0` removed the CI workflows). So this page never claims "CI
green" and never claims "production ready": the PRODUCT-GATE is
`BLOCKED_EXTERNAL`. Every claim above is a source citation, not a published
build receipt.

## Checklist

- [ ] My flags come **before** the script path.
- [ ] `--resume` / `--rerun` come with `--db`, and never both.
- [ ] I picked `--isolated` **or** `--workspace`, not both.
- [ ] `--allow-network` has no `=value`.
- [ ] I branch on exit `1` and exit `2` separately.
- [ ] I know `events verify` reads history; it does not re-run anything.
- [ ] I used `--subject` and `--scope last-segment`, not the names in the help prose.
- [ ] I validated with `run`, not only with `validate`.

## Next

- [`pipeline-dsl.md`](pipeline-dsl.md) — the DSL surface, and which constructs
  are proven, declared, or fail closed.
- [`README.md`](README.md) — the hub for the whole user documentation.

_This page describes the development branch. The current published release is `0.47.0`;
the difference is recorded in the divergence note at the top._