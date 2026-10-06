# PipelineK — CLI reference

> **Documentation divergence.** This page previously claimed the `0.39.0` contract and stated that
> `parallel` / `retry` / `catchError` did not exist. That claim did not hold against the code: they
> are declared in the v2 DSL and exercised by `examples/run.sh`. Recorded 2026-10-06.
> See `docs/user/README.md` → "Known divergences".

**Verified against the installed binary**, not just the sources: `pipelinek 0.47.0` as installed by
asdf (`~/.asdf/installs/pipelinek/0.47.0`), on this machine, 2026-10-06. Repository HEAD at the time
was `54ae56f0`. Claims marked **[ran]** below were executed against that binary; the rest cite the
source line that produces them.

## What you will be able to do when you finish

- Invoke every subcommand correctly, with its real flags and nothing invented.
- Predict the exit code before you press Enter — including the pairs that look identical and are not.
- Run a pipeline, then read its history back with `events`, `events verify` and `console`
  without re-executing anything.
- Produce an `opId` for `console` instead of guessing one.
- Understand why `validate` saying `VALIDATION SUCCESSFUL` does **not** mean `run` will accept the
  script.

## The binary

The command is `pipelinek`. How you get it is covered in [`installation.md`](installation.md).
Installed with asdf it is a shim:

```text
~/.asdf/shims/pipelinek   →   ~/.asdf/installs/pipelinek/0.47.0
```

## There is no `help`

`pipelinek help` does not print usage. It fails: **[ran]**

```text
$ pipelinek help
Invalid CLI arguments: InvalidCommand(value=help)
Usage: pipeline <validate|run> [--db <path>] [--resume|--rerun] [--control-root <path>] <script>
$ echo $?
1
```

The same happens with no arguments, except the error is `MissingCommand` instead. If you are writing
a wrapper, do not shell out to `pipelinek help` expecting text on stdout.

## Known gap: `run` prints a JSON event wall, not a readable log

**This is an open product defect, recorded here because it shapes everything you see.**

What `pipelinek run` writes today, verified against the installed binary:

- **stdout**: the whole event stream, encoded as one JSON array, unconditionally —
  `println(JsonEventLog.encode(events))` at `Main.kt:436`. There is no flag to change or suppress it.
- **stderr**: two or three summary lines — `Pipeline finished with SUCCESS`, or
  `cause [SCRIPT]: shell exited with code 3`.
- **The output of a `sh` step appears in neither.** It is only reachable through
  `console`, from the durable control plane.

So a first-time user types `pipelinek run hello.pipeline.kts` and gets a wall of JSON envelopes
carrying `eventId`, `occurredAt` and `runId`. Nothing in that output reads like the run it reports.

What the design already says should happen. [`CLI_OBSERVABILITY_SPEC.md`](../../docs/v2/03-specifications/CLI_OBSERVABILITY_SPEC.md)
separates two axes:

- **`view`** — *what* information: `normal` (the default human view: lifecycle, failures and a
  bounded tail of the failing operation's transcript), `events`, `full`, `console`, `quiet`.
- **`format`** — *how* it is rendered: `text` (human readable), `jsonl`, `json`.

The spec states that on success `run` "does not stream all child output in `normal` mode. It shows
important lifecycle and final summary", and that JSON appears **only** when `--format jsonl|json`
is requested, with stdout carrying the machine payload alone.

None of it is implemented. There is no `--view`, `--format`, `--quiet`, `--follow` or `--fields`
anywhere in the application, and no `inspect` or `logs` command. The spec is marked `PROPOSED`.

This also diverges from an accepted ADR. ADR-0077 §8 requires that "execution output
(stdout/stderr/transcript) stays on a separate channel", and its *Rejected* list explicitly excludes
"events encoded into console logs". Today the events **are** the console output.

It also does not meet the Jenkins-familiarity rule this repository holds itself to, which is why a
`pipelinek run` does not look like `+ echo hello` / `hello` / `Finished: SUCCESS`.

**Until that is decided, this is what works.** Take the JSON apart yourself, and read shell output
from the durable plane:

```bash
# every event, one line each, in order
pipelinek run --db ./.d/j.sqlite --workspace . hello.pipeline.kts \
  | jq -r '.[] | (.sequence|tostring) as $s | (($s+"        ")[0:4]) + "  " + .kind'

# what a sh step actually printed
pipelinek console --control-dir ./.d/durable-shell "$RUN_ID" "$OP_ID"
```

## Subcommands

| Subcommand | What it does | Line |
|---|---|---|
| `version` | Reads `Implementation-Version` from the jar. No version → exit `3` | `Main.kt:72`, `:80` |
| `doctor` | Prints `jdk:` / `os:` / `workdir:`. Not writable → exit `2` | `Main.kt:91`, `:110` |
| `events` | Structured history from the journal | `Main.kt:115` |
| `events verify` | Verifies the **persisted** history against a YAML contract. Does **not** re-run | `Main.kt:116` |
| `console` | Output transcript of one `opId` | `Main.kt:133` |
| `credentials` | `add` \| `list` \| `remove` \| `rotate` | `Main.kt:140`, `MainCredentialsCli.kt:94` |
| `validate` | Compiles and reports diagnostics | `Main.kt:190` |
| `run` | Executes | `Main.kt:232` |

The first six are dispatched **before** the argument parser (`Main.kt:72`–`:142`); only `validate`
and `run` go through `CliParser` (`CliParser.kt:135`). The flags in the next section belong to those
two and to nothing else.

`doctor` output is three fixed lines: **[ran]**

```text
jdk: 24.0.2 (Eclipse Adoptium)
os: Linux 7.2.7-ogc1.1.fc44.x86_64
workdir: /path/to/your/checkout (writable)
```

It writes a probe file into the current directory and deletes it, so it is also a write-permission
test.

## Flags (`run` and `validate` only)

| Flag | Argument | Effect | Line |
|---|---|---|---|
| `--db` | path | SQLite journal. **Without it, everything is in memory**. Must contain a directory component — see trap 11 | `CliParser.kt:192` |
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

**The default control root is derived from `--db`, not fixed.** With no `--control-root` it is
`dirname(<--db path>)/durable-shell` (`Main.kt:501`). So these two runs use different durable
state directories, which matters for credentials:

```bash
pipelinek run --db ./.pipelinek/run.sqlite p.kts    # durable shell → ./.pipelinek/durable-shell
pipelinek run --db /tmp/other.sqlite     p.kts    # durable shell → /tmp/durable-shell
```

## The eleven traps

These are the places where the CLI does something other than what a careful reader expects. All eleven
are behavioural facts, cited by line or observed against the binary.

### 1. Flags go **before** the script. Afterwards they are ignored, silently.

The parser loop consumes tokens while they start with `--` (`CliParser.kt:144`), and the first
non-`--` token is taken as the script and **parsing stops** (`:151`). So:

```bash
pipelinek run --db /tmp/j.db build.pipeline.kts   # correct
pipelinek run build.pipeline.kts --db /tmp/j.db   # --db is ignored, no error
```

There is no warning. If a flag "does nothing", check its position first.

### 2. Unknown flag → exit `1`. Other input rejections → exit `2`. Do not unify them.

An unknown `--algo` prints the usage message and exits `1` (`CliParser.kt:242`, `Main.kt:151`).
Everything else that is refused for `run`/`validate` — missing script, failed `validate`,
`--resume` without `--db`, invalid `--control-root`, non-canonical step, lease already held, compile
failure — exits `2` (`Main.kt:186,225,241,269,430,830`). `doctor` not writable also exits `2`
(`Main.kt:110`).

If you write a wrapper script, you have to branch on the code, not assume "bad input is always 2".

### 3. `--resume` and `--rerun` need `--db`; the pairs are mutually exclusive.

- `--resume` without `--db` → exit `2` (`CliParser.kt:196`, `Main.kt:239`).
- `--rerun` without `--db` → exit `2` (`CliParser.kt:202`).
- `--resume` **and** `--rerun` together → error; they are exclusive (`CliParser.kt:196,202`).
- `--isolated` **and** `--workspace` together → error; they are exclusive (`CliParser.kt:154`).

Without `--db` the journal lives only in memory, so there is nothing to resume from or to re-run
against.

### 4. `events verify` does **not** re-execute anything.

It reads the history already persisted in `--db` and compares it against a contract file. If you
want the pipeline to actually run again, that is `run`, not `events verify`.

### 5. `events` documents a flag that does not exist.

The KDoc at `MainEventsCli.kt:14` advertises `--subject-kind`. **The real flag is `--subject`.**

### 6. `events verify` advertises a flag that does not exist.

The usage line printed by the binary itself mentions `[--after-last-run-started]`, and that flag
**does not exist** **[ran]**. The real one is `--scope last-segment`.

Both traps have the same cause: the help prose drifted from the parser. Trust the flag tables, not
the help text.

### 7. `opId` is not a number you make up.

`console` takes a `runId` and an `opId`. The `opId` is derived from the run and printed in the
event history; it is never a bare index. Its format is (`OpId.kt:60`):

```text
<runId>-s<stageIndex>-<stepIndex>[-b<branchIndex>][-bp<N>-<segment>…]
```

For a plain two-stage pipeline the obvious guesses — `0`, `1`, `op-1` — all fail with
`console-refused: unknown-stream`.

**Linear runs** can compose it from the event subject **[ran]**:

```bash
RUN_ID=$(jq -r '.[0].runId' run.json | head -1)
OP_ID=$(pipelinek events --db ./run.sqlite "$RUN_ID" --kind StepStarted \
        | jq -r 'select(.subject.kind=="STEP") | "\(.subject.segments[2])-s\(.subject.segments[4])-\(.subject.segments[6])"' \
        | head -1)
pipelinek console --control-dir ./.pipelinek/durable-shell "$RUN_ID" "$OP_ID"
```

**Branched runs cannot**, and this is not obvious: a `parallel` step carries extra `-b` and `-bp`
segments, and `StepStarted` subjects only record `stage` and `step` — so composing from them yields
an id that `console` refuses **[ran]**. The event history does not carry the `opId`.

The source of truth in every case is the stream filename, which is `<runId>_<opId>_transcript`:

```bash
for OP in $(ls -1 ./.pipelinek/durable-shell/output-plane/streams/ \
            | sed -E "s/^${RUN_ID}_//; s/_transcript$//"); do
  pipelinek console --control-dir ./.pipelinek/durable-shell "$RUN_ID" "$OP"
done
```

Note the `opId` repeats the `runId` as its prefix. Copy the whole filename minus both wrappers; do
not strip the prefix.

### 11. `--db` without a directory component crashes and then hangs. **[verified defect]**

`Main.kt:501` derives the default control root as `dbPath.parent.resolve("durable-shell")`. A bare
filename has no parent, so `Paths.get("run.sqlite").parent` is `null` and the run dies with an
unhandled `NullPointerException`:

```text
$ pipelinek run --db run.sqlite --workspace . pipeline.kts
Exception in thread "main" java.lang.NullPointerException: Cannot invoke
  "java.nio.file.Path.resolve(String)" because the return value of
  "java.nio.file.Path.getParent()" is null
        at dev.rubentxu.pipeline.v2.application.MainKt.main(Main.kt:501)
```

Worse, the process does **not exit** after the main thread dies — it has to be killed **[ran]**:

```text
$ timeout -s KILL 45 pipelinek run --db run.sqlite --workspace . pipeline.kts; echo $?
137          # 137 = the timeout killed it; the command never returned on its own
```

Adding any directory component makes it work normally **[ran]**:

```bash
pipelinek run --db ./.d/run.sqlite --workspace . pipeline.kts    # exit 0
```

So: **always give `--db` a directory**, and `mkdir -p` it first. This is an open product defect,
not a documentation workaround — the input is not validated and the failure is neither typed nor
reported through the exit-code contract.

### 8. `credentials add` and `rotate` cannot be scripted.

Both prompt for the secret with `Console.readPassword()` (`MainCredentialsCli.kt:32,35`), which
requires a real terminal. **[ran]** Piping the value in, or redirecting from `/dev/null`, does not
help:

```text
$ printf 'sekrit\n' | pipelinek credentials add pipedcred
Enter secret value: Error: no TTY available
$ echo $?
1
```

If you provision credentials in a headless job, this CLI cannot do it. Use `PIPELINE_STORE_PASSPHRASE`
for the passphrase, but the secret itself still needs a TTY.

### 9. `credentials` rejects a bad invocation with `1`, not `2`.

`pipelinek credentials` with no subcommand prints its usage and returns `1`
(`MainCredentialsCli.kt:99`), and `pipelinek events` / `pipelinek events verify` / `pipelinek
console` with bad usage return `2`. **[ran]** The three subcommands do not agree, so branch on the
subcommand, not on "usage error".

### 10. The credential store that `add` writes and the one `run` reads are different files.

- `credentials add` defaults to `~/.pipeline/credentials.bin` (`MainCredentialsCli.kt:62`).
- `run` defaults to `<control-root>/../credentials.bin` (`Main.kt:705`,
  `MainCredentialsSupport.kt:28`) — with the derived default control root, that is
  `dirname(<--db>)/credentials.bin`.

So `credentials add` followed by `run` with default paths gives you a store the run never opens, and
the run silently proceeds **with no credentials** (`Main.kt:707` returns `null` when the file is
absent). Nothing warns you.

Set the path explicitly to make the two agree. **[ran]** The override works:

```bash
export PIPELINE_CREDENTIALS_STORE="$HOME/.pipeline/credentials.bin"
pipelinek credentials list     # reads it
```

## Exit codes

| Code | Meaning |
|---|---|
| `0` | Success, including `RunOutcome.Unstable` |
| `1` | Pipeline `Failure` / `Aborted`; invalid CLI arguments (`Main.kt:151`); operational failure of the `credentials` subcommand — no TTY, unknown `--kind`, no passphrase for `list` **[ran]** |
| `2` | Invocation / admission rejection in `run`/`validate`, plus **usage** errors of `events`, `events verify` and `console` **[ran]** |
| `3` | Jar manifest without `Implementation-Version` (`Main.kt:80`); or, **during a run**, a credential store that exists but whose passphrase is unavailable or wrong (`Main.kt:723,728`) |
| `4` | Credential store tampered with, **during a run** (`Main.kt:732`) |

Three things worth memorising:

- **`Unstable` is `0`.** A pipeline that ends unstable has not "half failed" in the shell's eyes.
- **`1` and `2` are not interchangeable.** See trap 2.
- **Codes `3` and `4` are run-time only.** `pipelinek credentials list` without a passphrase returns
  `1` and an explanation **[ran]**, not `3`. And a **wrong** passphrase does not fail either: it
  lists the store with `KIND` and `SCOPE` as `Unknown` and exits `0` **[ran]**.

## Environment variables

| Variable | Effect | Line |
|---|---|---|
| `PIPELINE_CREDENTIALS_STORE` | Path of the credential store for **both** the CLI and a run. This is the only way to make trap 10 go away | `MainCredentialsCli.kt:62`, `Main.kt:704` |
| `PIPELINE_STORE_PASSPHRASE` | Secret store passphrase | `PassphraseResolver.kt:20` |
| `PIPELINE_SANDBOX_ALLOW_EXTRA` | Sandbox relaxation | `SandboxConfig.kt:89` |
| `PIPELINE_SANDBOX_PATH_KEEP` | Sandbox path preservation | `SandboxConfig.kt:90` |
| `APP_HOME` | Classpath of bundled plugins | `scripting-api/.../ScriptDefinition.kt:81` |

`PATH` is also read as a fallback when composing a step's environment
(`EnvironmentComposer.kt:104`, `EnvModel.kt:61,148`), and `PIPELINE_CREDENTIALS_STORE` is read from
three separate sites rather than one. **`.env` files are not loaded automatically** — export them
yourself if your pipeline needs them.

## `events`, `events verify`, `console`

### `events`

Structured history from the journal. One JSON envelope per line on **stdout**
(`MainEventsCli.kt:77`); the continuation cursor goes to **stderr** (`:80`).

| Flag / positional | Meaning | Line |
|---|---|---|
| `--db <path>` | Journal to read. Missing → exit `2` | `MainEventsCli.kt:34`, `:46` |
| `--kind K` | Filter by event kind | `:35` |
| `--subject <v1:kind:seg…>` | Filter by subject | `:36` |
| `--limit N` | Default **100** | `:37` |
| `--after-cursor TOKEN` | Continue after a cursor | `:38` |
| positional | `runId`; missing → exit `2` | `:39`, `:46` |

Because the cursor is on stderr, `... | jq` stays clean. That is what makes the event spine
readable:

```bash
pipelinek events --db ./run.sqlite "$RUN_ID" \
  | jq -r '[.occurredAt[11:19], .kind, (.subject.value // "-")] | @tsv'
```

### `events verify`

Compares the persisted history against a YAML contract.

| Flag | Meaning | Line |
|---|---|---|
| `--db` | Journal to read | `MainEventsVerifyCli.kt:31` |
| `--run` | Run id to verify | `:31` |
| `--contract` | Contract file | `:31` |
| `--from-sequence N` | Start sequence | `:31` |
| `--scope last-segment` | Scope the check (the real flag; see trap 6) | `:32` |

It prints `contract:`, `observed-terminal-outcome:`, `acceptance:` and the violations.
Exit `0` = PASSED, `1` = FAILED, `2` = usage or decode error.

### `console`

The output transcript of a single `opId`.

| Flag / positional | Meaning | Line |
|---|---|---|
| `--control-dir <path>` | Control directory | `MainConsoleCli.kt:140` |
| `--max-bytes N` | Default 65536 | `:141` |
| `--after-cursor TOKEN` | Continue after a cursor | `:142` |
| `--range FROM:TO` | Byte range | `:143` |
| positionals | `runId` and `opId` | `:145` |

An unknown stream — including a wrong `opId` — exits `1` (`:98`); invalid usage exits `2` (`:157`).

## `credentials`

| Subcommand | Meaning |
|---|---|
| `add [--kind <kind>] <id>` | Prompts for the secret (needs a TTY) and stores it |
| `list` | Prints id, kind and scope of every entry |
| `remove <id>` | Deletes one entry |
| `rotate [--kind <kind>] <id>` | Re-encrypts with a new secret (needs a TTY) |

`--kind` accepts `secret-text`, `username-password`, `ssh-private-key`, `secret-file`,
`certificate`, `zip` and `username-colon-password` (`MainCredentialsCli.kt:76`). A wrong `--kind`
exits `1` **[ran]**.

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
It can print `VALIDATION SUCCESSFUL` for a script that `run` then rejects with exit `2`, because
`validate` never reaches the canonical bridge where those four constructs are refused. **The real
check is `run`.**

And if you pass `--db` to `validate` expecting a journal, you get none: the flag is accepted and
discarded.

## Authority note

This repository has **no remote CI since 2026-09-30** (`.github/workflows/` does not exist; commit
`754ddda0` removed the CI workflows). So this page never claims "CI green" and never claims
"production ready": the PRODUCT-GATE is `BLOCKED_EXTERNAL`. Every claim above is either a source
citation or an observation of the installed binary — not a published build receipt.

## Checklist

- [ ] My flags come **before** the script path.
- [ ] `--resume` / `--rerun` come with `--db`, and never both.
- [ ] I picked `--isolated` **or** `--workspace`, not both.
- [ ] `--allow-network` has no `=value`.
- [ ] I branch on exit `1` and exit `2` separately.
- [ ] I do not shell out to `pipelinek help` — it exits `1`.
- [ ] I know `events verify` reads history; it does not re-run anything.
- [ ] I used `--subject` and `--scope last-segment`, not the names in the help prose.
- [ ] My `opId` came from the event history, not from a guess.
- [ ] `PIPELINE_CREDENTIALS_STORE` is set, so `credentials add` and `run` share one store.
- [ ] I validated with `run`, not only with `validate`.

## Next

- [`installation.md`](installation.md) — how to get this binary.
- [`events-and-troubleshooting.md`](events-and-troubleshooting.md) — reading a failed run.
- [`pipeline-dsl.md`](pipeline-dsl.md) — the DSL surface, and which constructs are proven, declared,
  or fail closed.
- [`README.md`](README.md) — the hub for the whole user documentation.