# PipelineK — Cheat sheet

**Release verified against**: `pipelinek 0.39.0`. Every snippet on this
page was extracted with the published binary (`pipelinek 0.39.0`, ZIP
SHA-256 `385b140c35f6f017d8077eb27d78964ddaf2bd5bd37c5e11afcae5671eb0cbb8`)
and re-executed end-to-end. **Not** visual-review-only.

## Install & version selection

```bash
# SDKMAN (recommended, once SDKMAN is in place)
sdk install pipelinek 0.39.0
sdk use pipelinek 0.39.0          # this shell only
sdk default pipelinek 0.39.0      # all new shells
```

## Verify the install

```bash
pipelinek version                  # → pipeline 0.39.0
pipelinek doctor                   # jdk, os, workdir, writable
```

## Validate & run

```bash
# Compile-only — does NOT run the steps
pipelinek validate pipeline.kts

# Real run with durable journal + control-root
pipelinek run --workspace . \
              --db ./.pipelinek/run.sqlite \
              --control-root ./.pipelinek/ctl \
              pipeline.kts
```

Re-run after fixing the script:

```bash
pipelinek run --workspace . --db ./.pipelinek/run.sqlite \
              --control-root ./.pipelinek/ctl --rerun pipeline.kts
```

Resume an interrupted run:

```bash
pipelinek run --workspace . --db ./.pipelinek/run.sqlite \
              --control-root ./.pipelinek/ctl pipeline.kts
```

## SDKMAN side

```bash
sdk list pipelinek                 # versions available
sdk current pipelinek              # active version in this shell
```

## Update

```bash
sdk install pipelinek <new-version>
sdk default pipelinek <new-version>
pipelinek version                  # confirm
```

## Exit codes

The CLI in `0.39.0` has a **defect in exit-code propagation** under
several common scenarios. The authoritative success signal is the
`RunFinished.outcome` field in the NDJSON event stream, not the shell
exit code.

| Scenario | Expected | Observed (v0.39.0) |
|---|---|---|
| `run` on a passing script (fresh DB) | `0` | `0` ✓ |
| `run` on a failing script (fresh DB) | `1` | `0` — **DEFECT** |
| `run` on a failing script (after success in same DB, different script path) | `1` | `0` — **DEFECT** |
| `run` on a failing script (after success in same DB, `--rerun`) | `1` | `1` ✓ |
| `validate` on a well-formed script | `0` | `0` ✓ |
| `validate` on a malformed script | non-zero | `0` — **DEFECT** |
| `pipelinek` (no args) | non-zero | `0` — **DEFECT** |
| Unknown flag (e.g. `--version`) | non-zero | `0` — **DEFECT** |

Reliable signal in shell scripts:

```bash
pipelinek run --workspace . pipeline.kts | \
  jq -e 'select(.kind=="RunFinished") | .outcome == "success"'
```

The defect is recorded in
`docs/v2/07-uat/WU_LPR_080_SDKMAN_PUBLICATION_RECEIPT.md` as an open
item of LPR-GATE-1. Future releases will restore the exit-code
contract and re-verify it via `scripts/release/cheat-sheet-uat.sh`.

## Minimum real pipeline (no checkout of the repo needed)

Save this as `pipeline.kts` next to any Gradle JVM project (a directory
that contains `gradlew` / `gradlew.bat`):

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

This pipeline:

- runs `./gradlew build` (real Gradle, your project's build.gradle.kts)
- asserts a jar was produced
- emits the `PIPELINE-OK` marker

Failure path (returns `outcome=failure`, CLI exits `1` in v0.39.0):

```kotlin
pipeline {
    stages {
        stage("fail") {
            sh("false")
        }
    }
}
```

## Per-project version pin (`.sdkmanrc`)

```ini
# .sdkmanrc (project root)
sdkman_auto_use=true
sdkman_auto_install=true
pipelinek=0.39.0
```

Then `cd` into the project and `sdk env` to activate the pinned
version in the current shell.

## Inspect a run

The CLI prints NDJSON events to **stdout**. The durable journal lives
at `--db <path>`. Per-run transcripts (which may contain secrets, see
[`credentials-and-security.md`](credentials-and-security.md)) live under
`--control-root/<run-id>/`.

```bash
# Stream live events while a run is in progress
pipelinek run --workspace . pipeline.kts | jq

# After the fact, look at the most recent run
ls -lt ./.pipelinek/ctl | head
```

## What this release does NOT expose

- `--workspace` is in the binary; without it `sh(...)` runs in the
  control-root's parent. Use `.` to point at your project root.
- `--help` and `--version` are **not** first-class flags. They print
  the synopsis or are silently ignored.
- There is no `~/.pipelinekrc` global config; everything is per-run
  via flags.
