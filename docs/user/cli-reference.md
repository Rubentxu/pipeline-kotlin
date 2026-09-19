# PipelineK — CLI reference

**Release verified against**: `pipelinek 0.39.0`. Everything on this page
was observed against the published binary (`pipelinek 0.39.0`, ZIP SHA-256
`385b140c35f6f017d8077eb27d78964ddaf2bd5bd37c5e11afcae5671eb0cbb8`).

The CLI is intentionally small. Only the flags documented here are part
of the stable contract; anything else is **not** a public API.

## Synopsis

```
pipelinek <validate|run> [--db <path>] [--resume|--rerun] [--control-root <path>] <script>
```

The CLI does **not** accept `--help` or `--version` as first-class flags.
Running `pipelinek` (no args) prints the synopsis above and exits `1`.
Unknown flags are silently ignored — that is the parser's current
behavior, not a documented contract.

## Subcommands

| Subcommand | Purpose |
|---|---|
| `validate <script>` | Compile the script. Emits typed events. Exit `0` on success, `1` on DSL/compile errors or missing file. |
| `run <script>` | Compile + execute. Exit `0` if `outcome=success`, `1` otherwise (step failure, etc.). |

`validate` and `run` both expect the **script path** as the last
positional argument.

## Flags (in order, all optional)

| Flag | Argument | Effect |
|---|---|---|
| `--db` | `<path>` | Path to the durable SQLite journal for this run. Default: not set (in-memory only — you cannot resume a previous run). |
| `--control-root` | `<path>` | Directory where PipelineK stores per-run control files (transcripts, retry state, captured output). If omitted, a `.pipelinek` directory is created next to the script. |
| `--resume` | (none) | Reattach to an existing run in `--db` and re-execute only the steps that didn't complete. |
| `--rerun` | (none) | Re-execute the run from scratch, even if `--db` has prior state. Useful after fixing the script. |

`--resume` and `--rerun` are **mutually exclusive**. Passing both is
undefined behavior.

## Exit codes (certified)

The CLI in `0.39.0` has a defect in exit-code propagation under several
common scenarios (see [`cheat-sheet.md`](cheat-sheet.md#exit-codes)
for the full table). The headline of this defect:

| Exit | Meaning | Verified scenario (where it works) |
|---|---|---|
| `0` | Either success or, **defectively**, some failure paths | `run` on a passing script (fresh DB); `validate` on a well-formed script |
| `1` | Runtime / usage error (only on fresh-DB failure) | `run` with a failing step on a fresh DB; `run --rerun` after a failure |
| `2` | **Not observed in `0.39.0`.** Reserved for future use. | — |

**Until the defect is fixed**, gate shell scripts on
`RunFinished.outcome` from the NDJSON event stream, not on `$?`.

## Environment

- `JAVA_HOME` — if set, used to locate `java`. PipelineK does not
  bundle a JDK. Java 21 or newer is required.
- `PATH` — must contain `java`. `asdf` and SDKMAN-managed JDKs work
  transparently because PipelineK reads `java` from `PATH`.

## What the CLI does NOT do (v1 contract)

- No plugin manager, no marketplace, no remote execution.
- No hot reload, no daemon mode, no JSON RPC server.
- No `--list`, `--search`, or auto-update flags.
- No `~/.pipelinekrc` or global config file.

These are deliberate absences in the v1 contract, not unfinished work.
