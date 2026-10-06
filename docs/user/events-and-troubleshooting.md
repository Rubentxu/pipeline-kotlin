# Events and troubleshooting

**Documented against**: development branch, `pipelinek 0.47.0` (v2/build.gradle.kts:75), commit `b08fa948`
**Not verified against a published binary.** The current published release is `0.47.0` (its ZIP digest is listed in the release `SHA256SUMS`); see the divergence note below.

> **Documentation divergence.** This page previously carried the header *"Release verified against:
> `pipelinek 0.39.0`"*. That header cannot be sustained: the page described a surface the current
> branch no longer matches, and it was never re-verified against a published binary. The page now
> documents the development branch (`0.47.0`). Recorded 2026-10-06. See `docs/user/README.md` →
> "Known divergences". The stronger claim that `parallel` / `retry` / `catchError` did not exist
> applied only to `pipeline-dsl.md` and `cli-reference.md`.

---

## What you will be able to do after reading this page

- Explain what an event is, and why it is not a log line.
- Read a run's history with `pipelinek events`, including its flags and its two documented traps.
- Read one operation's console transcript with `pipelinek console`.
- Check persisted history against a contract with `pipelinek events verify`.
- Diagnose six common failures from the exit code and the message, without guessing at the code.

---

## The golden rule of diagnosis

> **Look at the exit code and the message first. Then confirm your flags came *before* the script
> path. Only after both are settled should you suspect the code.**

Most "PipelineK is broken" reports are one of the six cases in the table below, and none of them is a
bug in a Step. Checking the exit code and the flag order costs ten seconds and resolves the majority
of them.

---

## What an event is

An event is a **record of something that happened with meaning**, not a log line.

| An event is | A log line is |
|---|---|
| Typed, with a stable kind | Unstructured text |
| Written to the journal | Written to a stream |
| Stable across a restart | Gone when the process dies |
| Read back by `pipelinek events` | Grepped in a terminal |

Events are the run's structured history. Because they are stored in the journal, they survive the
run — but only if you gave the run a journal with `--db`. **Without `--db` there is no history to
read afterwards.** See [configuration-and-workspace.md](configuration-and-workspace.md).

Events require a journal to exist. A run without `--db` emits events, but there is nothing persisted
to query them from later.

---

## `pipelinek events`

Reads the structured history out of the journal (`Main.kt:115`, `MainEventsCli.kt`).

| Flag | Argument | Default | Source |
|---|---|---|---|
| `--db` | path | — (required) | `MainEventsCli.kt:34` |
| `--kind` | K | all | `:35` |
| `--subject` | `<v1:kind:segment…>` | all | `:36` |
| `--limit` | N | **100** | `:37` |
| `--after-cursor` | TOKEN | start of history | `:38` |
| *positional* | `runId` | — (required) | `:39` |

Output shape: one JSON envelope per line on **stdout** (`MainEventsCli.kt:77`), and the cursor on
**stderr** (`MainEventsCli.kt:80`). That split is deliberate — it means you can pipe stdout straight
into `jq` without filtering the cursor out.

```bash
pipelinek events --db .pipelinek/journal.sqlite RUN_ID
pipelinek events --db .pipelinek/journal.sqlite --kind StepStarted --limit 500 RUN_ID
pipelinek events --db .pipelinek/journal.sqlite --after-cursor TOKEN RUN_ID
```

Missing `--db` or a missing `runId` is **exit 2** (`MainEventsCli.kt:46`).

### Trap 1: the flag is `--subject`, not `--subject-kind`

The KDoc at `MainEventsCli.kt:14` advertises `--subject-kind`. **That flag does not exist.** The real
one is `--subject` (`MainEventsCli.kt:36`). Follow the implementation, not the comment.

---

## `pipelinek events verify`

Checks the persisted history against a contract file. It **does not re-execute anything**
(`Main.kt:116`).

| Flag | Argument | Source |
|---|---|---|
| `--db` | path | `MainEventsVerifyCli.kt:31` |
| `--run` | runId | `:31` |
| `--contract` | path | `:31` |
| `--from-sequence` | N | `:31` |
| `--scope` | `last-segment` | `:32` |

It prints `contract:`, `observed-terminal-outcome:`, `acceptance:` and any violations.

| Exit | Meaning |
|---|---|
| `0` | PASSED |
| `1` | FAILED |
| `2` | Usage or decode problem |

### Trap 2: `--after-last-run-started` does not exist

The usage message at `MainEventsVerifyCli.kt:37` advertises `[--after-last-run-started]`. That flag is
not implemented. The real one is `--scope last-segment` (`MainEventsVerifyCli.kt:32`).

Two usage messages in this area are wrong. When a flag in a usage string does nothing, check
`MainEventsVerifyCli.kt` or `MainEventsCli.kt` directly before believing it.

---

## `pipelinek console`

Reads the output transcript for one operation (`Main.kt:133`, `MainConsoleCli.kt`).

| Flag | Argument | Default | Source |
|---|---|---|---|
| `--control-dir` | path | — | `MainConsoleCli.kt:140` |
| `--max-bytes` | N | **65536** | `:141` |
| `--after-cursor` | TOKEN | start | `:142` |
| `--range` | `FROM:TO` | whole | `:143` |
| *positional* | `runId`, `opId` | — | `:145` |

Rejections exit `1` (`MainConsoleCli.kt:98`); invalid usage exits `2` (`:157`). Note that this
subcommand splits its error handling across two codes — read the message.

```bash
pipelinek console --control-dir .pipelinek/control RUN_ID OP_ID
pipelinek console --control-dir .pipelinek/control --max-bytes 4096 RUN_ID OP_ID
```

Secrets from the credential store are redacted at the durable shell seam before the transcript is
written. See [credentials-and-security.md](credentials-and-security.md).

---

## `validate` is not a rehearsal

This is the most expensive misunderstanding in the whole tool, so it gets its own table.

| | `validate` | `run` |
|---|---|---|
| Launches OS processes | **Never** (`Main.kt:191`) | Yes |
| SQLite journal | Accepts `--db` and **ignores it** (returns at `Main.kt:228`, before `:233`) | Yes |
| Events | Emits to an in-memory store, printed as JSON on stdout (`Main.kt:209`) | Persisted to SQLite |
| Canonical bridge | **Does not run it** | Yes (`Main.kt:423` in-memory, `:827` durable) |
| Detects `git()` / `load()` / `node { }` / `ansiColor { }` | **No** | Yes → exit 2 |
| Final message | `VALIDATION SUCCESSFUL` on stderr (`Main.kt:227`) | `Pipeline finished with …` on stderr |

**Consequence:** `validate` can print `VALIDATION SUCCESSFUL` for a script that `run` then rejects
with exit 2. That is not a contradiction and not a bug in your script. `validate` never crosses the
gate that `run` crosses. The only real check is `run`.

---

## Diagnostic table

Six cases, each with the real signature. Read the exit code column first.

| # | Symptom | Exit | Real cause | Fix |
|---|---|---|---|---|
| 1 | Flags placed after the script, ignored with no error | `0` (or wrong-but-plausible) | Parser stops at the first non-`--` token (`CliParser.kt:144,151`) | Move all flags **before** the script path |
| 2 | `validate` says `VALIDATION SUCCESSFUL`, `run` fails | `2` | `validate` never crosses the canonical bridge and never detects the non-canonical constructs | Trust `run`. `validate` is a compile check, not a rehearsal |
| 3 | `--resume` or `--rerun` without `--db` | `2` | Both flags require the journal (`CliParser.kt:196,202`, `Main.kt:239`) | Add `--db <path>`, or drop the flag |
| 4 | Used `git()`, `load()`, `node { }` or `ansiColor { }` | `2` | Compiles, then is refused: no valid descriptor or registration (`StageScopeBuilders.kt:193`, `StageScope.kt:349,435,449`) | Remove it. This is **failing closed by design**, not a lost feature |
| 5 | Used `retry(count, delaySeconds)` without a block | exception at the call site | The step-level overload throws `IllegalArgumentException` when called (`StageScopeBuilders.kt:306`) | Use the block form `retry(count) { }`, which is the one example 09 exercises |
| 6 | Binary has no `Implementation-Version` | `3` | `version` reads the manifest entry; absent → exit 3 (`Main.kt:72,80`) | Rebuild, or use the installed distribution |

### Notes on the trickier rows

**Row 4 — failing closed is the contract.** These four constructions compile and are then rejected
before any effect, because the effective registry does not carry a descriptor for them. The gate sits
at `Main.kt:423` and `Main.kt:827`; eligibility is computed in
`CanonicalStructuralDecisions.kt:154`. The error message enumerates 11 fixed keys, but **the real
criterion is the effective registry**, not that printed list. Do not treat the 11 keys as the rule.

**Row 5 — two different `retry`.** `retry(count) { }` takes a block and works; `examples/09-retry.pipeline.kts`
depends on it. `retry(count, delaySeconds)` is the step-level form and throws at the call. Same name,
different shapes, opposite outcomes. If your script throws at the call site and you did not expect
that, you have the second one.

**Row 1 is the expensive one**, because it fails *successfully*. Everything looks fine and the run is
simply not durable.

---

## Exit code reference

| Code | Meaning |
|---|---|
| `0` | Success, including an `Unstable` run outcome |
| `1` | Pipeline `Failure`/`Aborted`, invalid CLI arguments, `console` rejections, `events verify` FAILED |
| `2` | Invocation and admission: script not found, `validate` failed, `--resume`/`--rerun` without `--db`, invalid `--control-root`, non-canonical Step, held lease, compilation failure, `doctor` with an unwritable directory |
| `3` | Artifact with no `Implementation-Version`; credentials with a missing or wrong passphrase |
| `4` | Tampered credentials store |

The trap: invalid CLI arguments give `1`, most other input rejections give `2` (`Main.kt:151` versus
`Main.kt:186,225,241,269,430,830`). Do not unify them.

Codes `3` and `4` are explained on the credentials page.

---

## Other subcommands

| Command | Purpose | Notes |
|---|---|---|
| `version` | Reads `Implementation-Version` from the jar | Exit 3 if absent (`Main.kt:72,80`) |
| `doctor` | Checks JDK, OS, and whether the cwd is writable | Unwritable cwd → exit 2 (`Main.kt:91,110`) |

`doctor` is the fastest first check when you do not know what is wrong. It is worth running before a
long debugging session.

---

## Authority note

This repository has had **no remote CI since 2026-09-30** — the commit `754ddda0` removed the CI workflows Do not expect a green CI
badge to stand behind these pages. Everything here is a static reading of the source at `b08fa948`.

---

## Next

- **All user pages** → [README.md](README.md)
- **Workspace and flags** → [configuration-and-workspace.md](configuration-and-workspace.md)
- **Credentials and secrets** → [credentials-and-security.md](credentials-and-security.md)