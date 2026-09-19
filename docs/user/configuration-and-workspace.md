# PipelineK — Configuration & workspace

**Release verified against**: `pipelinek 0.39.0`.

This page documents how to point PipelineK at your project, where it
stores durable state, and how to control what the `sh` step can see.

## `--workspace` (project root)

`--workspace <dir>` tells PipelineK where your project's toolchain lives
(`./gradlew`, `./mvnw`, `package.json`, etc.). Without it, the
`sh("...")` step runs in the **control-root's parent**, which is rarely
what you want.

```bash
pipelinek run --workspace . pipeline.kts
```

The `.` means "current directory". Use an absolute path if your script
lives outside your project root:

```bash
pipelinek run --workspace /home/me/projects/myapp \
              --db /home/me/.pipelinek/myapp.sqlite \
              --control-root /home/me/.pipelinek/myapp-ctl \
              /path/to/pipeline.kts
```

## `--db` (durable journal)

`--db <path>` is the **SQLite database** that records every event of
the run. Without it, the run executes but its history is in-memory
only and is lost when the CLI exits.

```bash
pipelinek run --db /tmp/myapp.sqlite --workspace . pipeline.kts
```

The file is created if it does not exist; it is reused on subsequent
runs with the same path. Delete it to start fresh.

## `--control-root` (transcripts & retry state)

`--control-root <dir>` is where PipelineK writes step transcripts,
captured stdout/stderr, retry counters, and any per-run artifacts
that aren't your own build outputs.

```bash
pipelinek run --control-root /tmp/myapp-ctl --workspace . pipeline.kts
```

If omitted, PipelineK defaults to `<workspace>/.pipelinek`.

> **Security note**: `--control-root` may contain **captured shell
> transcripts** of `sh("...")` steps. Secrets you pass to a step may
> end up here. See [`credentials-and-security.md`](credentials-and-security.md).

## Resume vs rerun

| Flag | Use when |
|---|---|
| `--resume` | You re-ran the CLI with the same `--db` and want to continue from where the previous run stopped. Steps that already completed are not re-executed. |
| `--rerun` | You want to re-execute every step from scratch, even if `--db` has prior state. Useful after fixing the script. |

Both flags require `--db`. They are **mutually exclusive**.

## Layout example

```
myapp/
├── gradlew                 # your project (--workspace)
├── pipeline.kts            # your pipeline
└── .pipelinek/             # default --control-root if not set
    ├── run-2026-09-19-…/
    │   ├── transcript.txt
    │   └── …
myapp.sqlite                # default --db if not set, lives next to workspace
```
