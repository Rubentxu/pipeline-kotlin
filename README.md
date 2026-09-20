# PipelineK

**PipelineK** is a local-first CI/CD engine with a Jenkins-familiar Kotlin
DSL. V2 is the active product line. The first LPR-GATE-1 release
(`pipelinek 0.39.0`) is **published** on GitHub Releases; SDKMAN
registration is in progress.

```bash
# Install (SDKMAN, once the candidate is live)
sdk install pipelinek 0.39.0

# Verify
pipelinek version   # → pipeline 0.39.0
pipelinek doctor    # jdk / os / workdir / writable

# Run your first pipeline
pipelinek run --workspace . pipeline.kts
```

## What is in v0.39.0

- Compiling, validating, and inspecting `.pipeline.kts` pipelines.
- Durable local execution with a SQLite journal and a control-root.
- Typed event stream (`CompilationStarted`, `RunStarted`, `StageStarted`,
  `StepStarted`, `StepFinished`, `StepFailed`, `RunFinished`,
  `EchoOutputCaptured`).
- Jenkins-familiar `pipeline { stages { stage { ... } } }` shape.
- Typed plugin and capability contracts.
- Local output, artifacts, events, credentials (with redaction at the
  durable shell seam), and run inspection.

## What is NOT in v0.39.0

- Remote controllers, network protocols, Jenkins runtime integration,
  remote scheduling, SaaS control plane.
- Plugin marketplace, hot reload, dependency resolution.
- Block steps (`retry`, `timeout`, `parallel`, `script`).
- `agent { ... }`, `when { ... }`, `options { ... }`, `parameters { ... }`,
  `post { ... }`.
- `~/.pipelinekrc` or other global config files.

These are deliberate v1 absences, not unfinished work. See the project
roadmap for the planned v2 evolution.

## Supported capabilities and v1 limits

- **Java**: 21 or newer (certified on Temurin 21.0.8 and 24.0.2).
- **OS**: Linux, macOS, Windows via WSL. The distribution is
  `UNIVERSAL` per SDKMAN; it ships both `bin/pipelinek` (UNIX) and
  `bin/pipelinek.bat` (Windows).
- **Disk**: ~200 MB for the distribution plus per-run control data.
- **Concurrency**: one pipeline run per CLI invocation. No daemon mode.
- **Known defects in `0.39.0`**: see the exit-code table in
  [`docs/user/cheat-sheet.md`](docs/user/cheat-sheet.md#exit-codes).
  Shell scripts must gate on the `RunFinished.outcome` event, not on `$?`,
  until the defect is fixed.

## Installation

- Recommended: SDKMAN (see [installation guide](docs/user/installation.md)).
- Fallback: download the ZIP from
  [GitHub Releases](https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.39.0).

## Your first pipeline

Create `pipeline.kts` next to any Gradle JVM project:

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

Then:

```bash
pipelinek validate pipeline.kts
pipelinek run --workspace . pipeline.kts
```

See [quickstart](docs/user/quickstart.md) for the full walk-through.

## Documentation

The full user documentation lives in [`docs/user/`](docs/user/):

- [`installation.md`](docs/user/installation.md) — install on Linux/macOS/Windows (WSL)
- [`quickstart.md`](docs/user/quickstart.md) — your first pipeline, end to end
- [`cli-reference.md`](docs/user/cli-reference.md) — every CLI flag and exit code
- [`pipeline-dsl.md`](docs/user/pipeline-dsl.md) — the certified DSL surface
- [`configuration-and-workspace.md`](docs/user/configuration-and-workspace.md) — `--workspace`, `--db`, `--control-root`
- [`credentials-and-security.md`](docs/user/credentials-and-security.md) — secret redaction
- [`events-and-troubleshooting.md`](docs/user/events-and-troubleshooting.md) — typed events, transcripts, recovery
- [`upgrading.md`](docs/user/upgrading.md) — SDKMAN upgrade, rollback
- [`cheat-sheet.md`](docs/user/cheat-sheet.md) — short, copyable, UAT-tested

## Release receipts

- [`docs/v2/07-uat/LPR_GATE_1_LOCAL_PRODUCTION_READY_0.39.0.md`](docs/v2/07-uat/LPR_GATE_1_LOCAL_PRODUCTION_READY_0.39.0.md)
  — LPR-GATE-1 closure (GitHub channel closed; SDKMAN pending).
- [`docs/v2/05-roadmap/LOCAL_FOUNDATION_CONSOLIDATION.md`](docs/v2/05-roadmap/LOCAL_FOUNDATION_CONSOLIDATION.md)
  — LFC (Local Foundation Consolidation) roadmap — the architectural
  foundation that backs the LPR milestones.
- [`docs/v2/07-uat/WU_LPR_080_SDKMAN_PUBLICATION_RECEIPT.md`](docs/v2/07-uat/WU_LPR_080_SDKMAN_PUBLICATION_RECEIPT.md)
  — SDKMAN publication status and known defects in `0.39.0`.
- [`docs/v2/07-uat/WU_LPR_071_SDKMAN_RESUME_PROTOCOL.md`](docs/v2/07-uat/WU_LPR_071_SDKMAN_RESUME_PROTOCOL.md)
  — handoff for closing the SDKMAN channel.

## Contributing

```bash
# Inspect the active V2 build
./gradlew -p v2 tasks

# Run a focused V2 test while iterating
just t 'FullyQualifiedTestName'

# Run the repository-level V2 gate at an apply/verify boundary
./gradlew check
```

`./gradlew check` forwards to the active V2 composite build. During
normal development, prefer the narrowest relevant V2 test; use the
full gate only at a milestone or verification boundary.
