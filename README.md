# PipelineK

A local-first CI/CD engine with a Jenkins-familiar Kotlin DSL.
PipelineK runs pipelines locally with durable execution, real typed
events, and structured failures — without a controller, agent, or
remote state.

```bash
VERSION=0.39.0

curl -fL -o "pipelinek-${VERSION}.zip" \
  "https://github.com/Rubentxu/pipeline-kotlin/releases/download/v${VERSION}/pipelinek-${VERSION}.zip"

echo "385b140c35f6f017d8077eb27d78964ddaf2bd5bd37c5e11afcae5671eb0cbb8  pipelinek-${VERSION}.zip" \
  | sha256sum -c -

unzip "pipelinek-${VERSION}.zip"

./pipelinek-${VERSION}/bin/pipelinek version          # → pipeline 0.39.0
./pipelinek-${VERSION}/bin/pipelinek doctor           # jdk / os / workdir / writable
```

Then run your first pipeline:

```bash
./pipelinek-${VERSION}/bin/pipelinek run examples/01-hello.pipeline.kts
```

## What is PipelineK

PipelineK is a single-binary pipeline runner. You write `.pipeline.kts`
files in a typed Kotlin DSL that resembles Jenkins' Groovy syntax, and
PipelineK compiles, validates, and executes them locally. Every step
emits a typed event; every shell process is journaled with a SHA-256
fingerprint of its inputs; every run produces a durable record that you
can resume after a crash.

You keep your data local. PipelineK does not phone home, does not
schedule remotely, and does not need a controller.

## Quickstart

### 1. Install

Download the canonical ZIP from
[GitHub Releases](https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.39.0),
verify its SHA-256, and unzip it:

```bash
VERSION=0.39.0

curl -fL -o "pipelinek-${VERSION}.zip" \
  "https://github.com/Rubentxu/pipeline-kotlin/releases/download/v${VERSION}/pipelinek-${VERSION}.zip"

echo "385b140c35f6f017d8077eb27d78964ddaf2bd5bd37c5e11afcae5671eb0cbb8  pipelinek-${VERSION}.zip" \
  | sha256sum -c -

unzip "pipelinek-${VERSION}.zip"

./pipelinek-${VERSION}/bin/pipelinek version
./pipelinek-${VERSION}/bin/pipelinek doctor
```

Linux, macOS and Windows (WSL) are supported. The ZIP ships both
`bin/pipelinek` (UNIX) and `bin/pipelinek.bat` (Windows). Java 21 or
newer is required on the `PATH`. The snippet above assumes you run it
from a directory where you want `pipelinek-${VERSION}/` to live
(`./pipelinek-0.39.0/bin/pipelinek` works without touching your `PATH`
or needing `sudo`).

> **SDKMAN is not yet available.** `sdk install pipelinek 0.39.0` is the
> canonical long-term install command but the candidate is still in
> vendor onboarding; running it today may fail. Until SDKMAN goes live,
> install from GitHub Releases as shown above. See
> [Distribution channels](#distribution-channels) for the full picture.

### 2. Write a pipeline

Create `pipeline.kts`:

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

### 3. Validate and run

```bash
pipelinek validate pipeline.kts
pipelinek run --workspace . pipeline.kts
```

The full walk-through (workspaces, `--db`, secret redaction, run
inspection) is in
[`docs/user/quickstart.md`](docs/user/quickstart.md).

## Examples

The [`examples/`](examples/) directory contains ten runnable
pipelines that exercise the real CLI of the published `v0.39.0`
distribution. If you installed via SDKMAN or unzipped the GitHub
artifact, you can run any example directly:

```bash
pipelinek run --workspace /tmp/pk-example examples/03-shell.pipeline.kts
```

If you cloned the repository, the [`examples/run.sh`](examples/run.sh)
script automates the assertions (exit code, terminal outcome, event
contract for 07–10):

```bash
# Build the local binary first, then run the assertions
./gradlew -p v2 :pipeline-application:installDist
examples/run.sh 03-shell.pipeline.kts
examples/run.sh            # run all ten
```

| Example | What it shows | Expected exit code |
|---|---|---|
| `01-hello.pipeline.kts` | Minimal pipeline: one stage, one `echo` | `0` |
| `02-multi-stage.pipeline.kts` | Stages execute in declaration order | `0` |
| `03-shell.pipeline.kts` | Real OS processes via `sh`, including a shell `for` loop | `0` |
| `04-kotlin-control-flow.pipeline.kts` | Kotlin control flow inside `script {}` blocks | `0` |
| `05-failing-step.pipeline.kts` | Typed failure: `sh` exits 3 → `StepFailed(kind=SCRIPT)`, outcome `failure` | `1` |
| `06-durable.pipeline.kts` | Durable execution with `--db`: journal, fingerprints, crash resume | `0` |
| `07-catch-error.pipeline.kts` | Nested `catchError`: inner `FAILURE` → outer `UNSTABLE`, pipeline continues | `0` |
| `08-parallel.pipeline.kts` | Two concurrent branches with their own durable identity | `0` |
| `09-retry.pipeline.kts` | `retry`: first attempt fails, second succeeds | `0` |
| `10-timeout.pipeline.kts` | `timeout` deadline aborts an over-running `sh`, outcome `failure` | `1` |

See [`examples/README.md`](examples/README.md) for the durable-execution
demo, the event-contract details, and the known limitations of each
example.

## Capabilities

PipelineK `0.39.0` ships with:

- Compile, validate, and inspect `.pipeline.kts` pipelines.
- Durable local execution with a SQLite journal (`--db`) and a
  control-root for state isolation.
- Typed event stream: `CompilationStarted`, `RunStarted`,
  `StageStarted`, `StepStarted`, `StepFinished`, `StepFailed`,
  `RunFinished`, `EchoOutputCaptured`.
- Jenkins-familiar `pipeline { stages { stage { ... } } }` shape.
- Block steps: `parallel`, `retry`, `timeout`, `catchError`.
- Kotlin `script {}` blocks with real Kotlin control flow.
- Typed plugin and capability contracts (`StepContract`,
  `requiredCapabilities`, registry-based discovery).
- Local credentials, with secret redaction at the durable shell seam.
- Local `artifacts`, `stash`, `unstash`, `archiveArtifacts`,
  `publishHTML`, `writeFile`, `pwd`, `isUnix`, `load`, `milestone`,
  `cleanWs`, `deleteDir`, `waitUntil`, `unstable`, `warnError`.

### System requirements

- **Java**: 21 or newer (certified on Temurin 21.0.8 and 24.0.2).
- **OS**: Linux, macOS, Windows via WSL. The ZIP ships both
  `bin/pipelinek` (UNIX) and `bin/pipelinek.bat` (Windows).
- **Disk**: ~200 MB for the distribution plus per-run control data.
- **Concurrency**: one pipeline run per CLI invocation. No daemon mode.

## Documentation

The full user documentation lives in [`docs/user/`](docs/user/):

- [`installation.md`](docs/user/installation.md) — install on
  Linux/macOS/Windows (WSL) from the GitHub Releases ZIP.
- [`quickstart.md`](docs/user/quickstart.md) — your first pipeline,
  end to end.
- [`cli-reference.md`](docs/user/cli-reference.md) — every CLI flag
  and exit code.
- [`pipeline-dsl.md`](docs/user/pipeline-dsl.md) — the certified DSL
  surface.
- [`configuration-and-workspace.md`](docs/user/configuration-and-workspace.md) —
  `--workspace`, `--db`, `--control-root`.
- [`credentials-and-security.md`](docs/user/credentials-and-security.md) —
  secret redaction.
- [`events-and-troubleshooting.md`](docs/user/events-and-troubleshooting.md) —
  typed events, transcripts, recovery.
- [`upgrading.md`](docs/user/upgrading.md) — upgrade strategy (SDKMAN
  section is a placeholder pending vendor onboarding).
- [`cheat-sheet.md`](docs/user/cheat-sheet.md) — short, copyable,
  exit-code table.

## Releases

The current published release is
[`pipelinek 0.39.0`](https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.39.0).

## Distribution channels

PipelineK publishes **one canonical ZIP** per release. Every other
channel consumes the same bytes; nothing is rebuilt per installer.

| Channel | Status (2026-09-24) | Notes |
|---|---|---|
| **GitHub Releases ZIP** | **Available** | Canonical artifact. ZIP + SHA-256 + CycloneDX SBOM + release manifest. |
| **Direct download** | **Available** | Same ZIP from the release page; no extra hop. |
| **SDKMAN** (`pipelinek` candidate) | **Pending** | Vendor onboarding in progress; the publish script (`scripts/release/sdkman-publish.sh`) is ready but blocked on `SDKMAN_CONSUMER_KEY` / `SDKMAN_CONSUMER_TOKEN`. Track [WU-LPR-080](docs/v2/05-roadmap/LPR_WORK_UNITS.md). See [ADR-0089](docs/v2/04-adrs/ADR-0089-distribution-artifact-authority-sdkman.md). |
| **Homebrew** (`rubentxu/tap/pipeline`) | **Future** | Project tap not started. Formulas will reuse the same ZIP. Listed as LFC9-004 in the historical distribution backlog (`docs/historico/2026-09-21/paquetes/pipeline-kotlin-local-foundation-consolidation/docs/v2/05-roadmap/IMPLEMENTATION_BACKLOG.md`). |
| **mise** (Aqua backend / GitHub release backend) | **Future** | Prefer Aqua backend over a bespoke plugin; the GitHub release backend is a fallback. Listed as LFC9-007 in the historical distribution backlog. |
| **asdf** (`pipeline` plugin) | **Future** | JReleaser-generated packaging; minimal plugin kept portable across Linux and macOS. Listed as LFC9-006 in the historical distribution backlog. |
| **Scoop** (Windows-native manifest) | **Future** | Gated on Windows demand; would reuse the ZIP and `bin/pipelinek.bat`. Not started. |
| **Container image** | **Future** | Planned for reproducible runners. Not started. |

No channel is going to bypass the GitHub Release ZIP: every installer
above (when implemented) will download or reference the same canonical
artifact and verify its SHA-256. The full distribution strategy lives
in
[`docs/historico/2026-09-21/paquetes/pipeline-kotlin-local-foundation-consolidation/docs/v2/07-distribution/DISTRIBUTION_STRATEGY.md`](docs/historico/2026-09-21/paquetes/pipeline-kotlin-local-foundation-consolidation/docs/v2/07-distribution/DISTRIBUTION_STRATEGY.md)
(historical) and is referenced by
[ADR-0089](docs/v2/04-adrs/ADR-0089-distribution-artifact-authority-sdkman.md)
(active).

The `v0.39.0` ZIP is the public artifact authority today. SDKMAN will
become the recommended channel only after the install UAT
(`scripts/release/sdkman-install-uat.sh`) passes on a clean runner; until
then the canonical path remains the ZIP download.

### Verified against the published artifact

Every claim in the README above has been verified against the official
`pipelinek-0.39.0.zip` downloaded from GitHub Releases:

- **ZIP SHA-256**: `385b140c35f6f017d8077eb27d78964ddaf2bd5bd37c5e11afcae5671eb0cbb8`
- **Binary SHA-256**: `92d0f67d16f7ee12888724cfe9da56f19cc2facd51ebee319770a43f40eedeee`
- **Certified commit**: `951b3cb5695ecc46c877776e330266e4bd44aa9e`
- **Examples 01–10**: each row of the Examples table above was executed
  against this binary on JDK 24.0.2 (Temurin) on Linux x86_64 with the
  reported exit code and outcome.
- **`pipelinek doctor`**: jdk 24.0.2 (Eclipse Adoptium), os Linux,
  workdir writable.

Reproduce locally:

```bash
VERSION=0.39.0

curl -fL -o "pipelinek-${VERSION}.zip" \
  "https://github.com/Rubentxu/pipeline-kotlin/releases/download/v${VERSION}/pipelinek-${VERSION}.zip"

echo "385b140c35f6f017d8077eb27d78964ddaf2bd5bd37c5e11afcae5671eb0cbb8  pipelinek-${VERSION}.zip" \
  | sha256sum -c -

unzip "pipelinek-${VERSION}.zip"

./pipelinek-${VERSION}/bin/pipelinek doctor
```
