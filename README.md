# PipelineK

[![Latest release (incl. prereleases)](https://img.shields.io/github/v/release/Rubentxu/pipeline-kotlin?include_prereleases&sort=semver)](https://github.com/Rubentxu/pipeline-kotlin/releases)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

A local-first CI/CD engine with a Jenkins-familiar Kotlin DSL.
PipelineK runs pipelines locally with durable execution, real typed
events, and structured failures — without a controller, agent, or
remote state.

```bash
VERSION=0.62.0

curl -fL -o "pipelinek-${VERSION}.zip" \
  "https://github.com/Rubentxu/pipeline-kotlin/releases/download/v${VERSION}/pipelinek-${VERSION}.zip"

echo "385b140c35f6f017d8077eb27d78964ddaf2bd5bd37c5e11afcae5671eb0cbb8  pipelinek-${VERSION}.zip" \
  | sha256sum -c -

unzip "pipelinek-${VERSION}.zip"

./pipelinek-${VERSION}/bin/pipelinek version          # → pipeline 0.62.0
./pipelinek-${VERSION}/bin/pipelinek doctor           # jdk / os / workdir / writable
```

Then run your first pipeline. Write this file next to your terminal:

```kotlin
// hello.pipeline.kts
pipeline {
    stages {
        stage("hello") {
            echo("PIPELINE-OK")
        }
    }
}
```

```bash
./pipelinek-${VERSION}/bin/pipelinek run hello.pipeline.kts
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
[GitHub Releases](https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.62.0),
verify its SHA-256, and unzip it:

```bash
VERSION=0.62.0

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
(`./pipelinek-0.62.0/bin/pipelinek` works without touching your `PATH`
or needing `sudo`).

> **SDKMAN is not yet available.** `sdk install pipelinek 0.62.0` is the
> canonical long-term install command but the candidate is still in
> vendor onboarding; running it today may fail. Until SDKMAN goes live,
> install from GitHub Releases as shown above. See
> [Distribution channels](#distribution-channels) for the full picture.

### 1.1 Install with the script (multi-version manager)

If you want multiple versions side-by-side and easy rollback, fetch
and run the in-tree installer. It downloads the same canonical ZIP,
verifies its SHA-256 against the `SHA256SUMS` manifest published with
the release, and unpacks under `~/.local/share/pipelinek/versions/`:

```bash
VERSION=0.62.0

curl -fL -o "install-pipelinek.sh" \
  "https://raw.githubusercontent.com/Rubentxu/pipeline-kotlin/main/scripts/install-pipelinek.sh"

chmod +x install-pipelinek.sh
./install-pipelinek.sh install "${VERSION}"   # download + verify + install
./install-pipelinek.sh use "${VERSION}"       # switch the active version
./install-pipelinek.sh list                  # installed versions + active
./install-pipelinek.sh doctor                # run pipelinek doctor (active)

# Activate in your shell:
export PATH="$HOME/.local/share/pipelinek/current/bin:${PATH}"
pipelinek version                            # → pipeline 0.62.0
```

Available subcommands: `install`, `use`, `list`, `uninstall`, `doctor`,
`help`. The script has a fail-closed URL allowlist
(`github.com`, `objects.githubusercontent.com`, plus loopback hosts for
local or air-gapped mirrors).

Install is **transactional**: download, digest, extraction and identity
verification all happen in a temporary directory, and the final version
directory is created by a single rename only after every check has
passed. A failed install leaves nothing behind, and the previously
active version is never disturbed.

The installer also requires **exact runtime identity**: the installed
binary must report exactly the requested version. A mismatch, a digest
mismatch, an archive root that disagrees with the version, or a
candidate-suffixed binary under a final filename all fail closed
instead of installing something mislabelled.

It never uses `sudo` and never spawns a daemon. Source:
[`scripts/install-pipelinek.sh`](scripts/install-pipelinek.sh).

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

A run whose writer was killed — the CI agent reaped, the machine rebooted —
leaves the run unfinished: its journal says a step was still running and its
history says it never finished. `runs cancel` closes it, and refuses to close
one that a live process is still working on:

```bash
pipelinek runs cancel <runId> --db <db> --control-root <dir>
```

For the walk-through of workspaces, `--db`, secret redaction and run
inspection, use the CLI's own help, which is generated from the code
that implements each flag:

```bash
pipelinek --help
pipelinek run --help
```

## Examples

The [`examples/`](examples/) directory holds 44 runnable
pipelines that exercise the real CLI, grouped by what they cover:

| Category | Scenarios | What it covers |
|---|---|---|
| `smoke/` | 3 | The shortest path through the DSL: the environment, `sh` then `echo`, and a checkout → build → archive round trip |
| `steps/` | 6 | One scenario per step: `withEnv`, the workspace helpers, `sleep`, `writeFile`, `cleanWs`, `findFiles` |
| `control-flow/` | 2 | Kotlin control flow inside a compiled script, and workflow control |
| `durable/` | 12 | Durable execution over the journal: platform queries, milestones, `waitUntil`, `stash`/`unstash`, the replay guarantee, closing a run whose writer was killed, and six Saga scenarios — compensation, intervention, a crash mid-effect and its reconciliation |
| `security/` | 2 | Credential bindings, and a ZIP-slip path-traversal defence |
| `output/` | 7 | The output plane: file reads, a utilities round trip, ZIP/unzip, mixed utilities, cross-step artifact queries, HTML publishing, and the live output of a process |
| `negative/` | 7 | Deliberate failures that must fail closed: error handling, the typed error step, a command that exits non-zero, a workspace symlink escape, an unregistered directive key, a plugin artifact whose bytes cannot be read, and a plugin whose bytes are not the ones the operator approved |
| `plugins/` | 5 | The four external example plugins, discovered from the distribution classpath: `uppercase`, `repeat`, a contributed directive, and the Saga consumer — plus a plugin admitted under the operator's trust policy |

Each scenario runs **twice** — once with a private journal database, which is
how a CI runs a pipeline, and once without one, which is the default for a
local run — and both executions have to meet the same expectations. The two
execution paths are the same product answering the same question, and a
difference between them is a defect.

A scenario that declares a durable directive is about the journal, so it runs
only on the journalled path: with no journal there is nothing to resume and
nothing to compensate, and the marker such a scenario writes would be found by
the second execution and read as a repeat.

The scenarios live in this repository, not in the released ZIP: the ZIP ships
the distribution alone. From an installed distribution you run your own
`.pipeline.kts`; from a clone, build the local distribution once and let
[`examples/run.sh`](examples/run.sh) run the whole set. The
launcher discovers every scenario, gives each one a private control root, a
private working directory and a generated passphrase, runs it on both execution
paths, and judges each execution on its exit code and on the text the scenario
asked for:

```bash
cd pipelinek && ./gradlew :pipeline-application:installDist
cd .. && examples/run.sh
```

A scenario may carry these directives as `//@` comments near its top,
and nothing else about a scenario is interpreted:

```kotlin
//@ expect-exit 1              // the default is 0
//@ expect-stdout <substring>  // the run must PRODUCE this, not merely exit 0
//@ expect-stderr <substring>  // the refusal must be this specific, not
                              // merely a nonzero exit
//@ expect-transcript <text>   // the bytes the run COMMITTED must contain it
//@ durable                    // run twice over one control root; the
                              // pipeline itself asserts the effect did
                              // not repeat, this only makes it happen
//@ credentials <id> <kind>    // seed a credential into this scenario's store
```

Every directive except `expect-exit` may appear more than once, and each
occurrence must be satisfied. The launcher also takes one optional argument, a
glob over the scenario paths relative to `examples/`, so
`examples/run.sh 'control-flow/*'` runs one slice of the set. A glob that
matches nothing is an error rather than a run of zero scenarios.

That is the entire vocabulary. The launcher is not a framework: it has
no registry, no fixtures and no assertions of its own.

`//@` is an ordinary Kotlin comment rather than a `#!` line, and that
is deliberate. `#!` only ever worked by accident: the script compiler
strips a shebang from the *first* line of a file, so a second `#!` line
became a syntax error and the scenario failed to compile instead of
failing for its own reason. The `@` then keeps a directive from matching
prose, since real scenarios already carry comments like
`// durable execution over the journal`.

## Capabilities

PipelineK `0.62.0` ships with:

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
- An optional operator trust policy for third-party plugins: with
  `--plugin-trust <file>`, a plugin brought with `--plugin-jar` is admitted
  only if it is the artifact the operator pinned (by the SHA-256 of its
  bytes) and it carries a detached Ed25519 signature that verifies under the
  publisher key the operator keeps. Without the flag, plugins are admitted on
  their manifest and API range, as before.
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

There is no separate user-documentation tree in this repository. The
versioned Markdown under `docs/` and under the build root was removed by the
documentation RESET, and this section previously listed nine files under
`docs/user/` that no longer existed — a promise the repository could not
keep. Rather than restate them as links that 404, the authority is now the
code that implements each surface:

| For | Read |
|---|---|
| Install on Linux/macOS/Windows (WSL) | The Releases ZIP below, and `scripts/install-pipelinek.sh`'s own `--help` |
| Every CLI flag and exit code | `pipelinek --help`, `pipelinek run --help`, and the flag table in `Main.kt` |
| The certified DSL surface | The KDoc on `StageScope.kt` and `PipelineDsl.kt` in `:pipeline-scripting-api`, which is where the DSL is declared |
| `--workspace`, `--db`, `--control-root` | The KDoc on `WorkspaceResolver.kt` in `:pipeline-application` |
| Secret redaction and credentials | Redaction: the KDoc on `SecretPatternRegistry.kt` in `:pipeline-credentials-api`, which also states that a bound secret of fewer than `MIN_MASKABLE_LENGTH` (8) characters runs normally but is **not** masked, so it reaches the transcript in the clear. Store access: `PassphraseResolver.kt` and `CredentialsStorePath.kt` in `:pipeline-credentials-local` |
| Typed events, transcripts, recovery | The KDoc on `JsonEventLog.kt` in `:pipeline-events-store` |
| How the build is verified, and what is out of bounds | `AGENTS.md` in the repository root |

The reason to prefer this over a prose tree is not preference: a KDoc block
and a Markdown file drift apart, and only the first one fails to compile when
it does.

## Releases

The current published release is
[`pipelinek 0.62.0`](https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.62.0).

## Distribution channels

PipelineK publishes **one canonical ZIP** per release. Every other
channel consumes the same bytes; nothing is rebuilt per installer.

| Channel | Status (2026-09-24) | Notes |
|---|---|---|
| **GitHub Releases ZIP** | **Available** | Canonical artifact. ZIP + SHA-256 + CycloneDX SBOM + release manifest. |
| **Multi-version installer** (`scripts/install-pipelinek.sh`) | **Available** | Bash installer in this repo: `install <v>` / `use <v>` / `list` / `uninstall <v>` / `doctor`. URL allowlist fail-closed; SHA-256-verified; no `sudo`. See [§1.1](#11-install-with-the-script-multi-version-manager). |
| **Direct download** | **Available** | Same ZIP from the release page; no extra hop. |
| **SDKMAN** (`pipelinek` candidate) | **Pending** | Vendor onboarding in progress; the publish script (`scripts/release/sdkman-publish.sh`) is ready but blocked on `SDKMAN_CONSUMER_KEY` / `SDKMAN_CONSUMER_TOKEN`. It resolves digests from the release `SHA256SUMS` manifest via `scripts/release/resolve-release-digest.sh` (the old `<asset>.sha256` sidecar is not published and 404s). Its former contract tests were deleted by the testing RESET and no replacement covers digest resolution, so that behaviour is currently unverified by automated tests and rests on the manual release-page check before a publish. The SDKMAN artifact authority is decided in the publish script and in `CandidateTagProvenance` (`:pipeline-release`). |
| **Homebrew** (`rubentxu/tap/pipeline`) | **Future** | Project tap not started. Formulas will reuse the same ZIP. |
| **mise** (Aqua backend / GitHub release backend) | **Future (harness)** | Lives in the external `pipelinek-release-harness` repo: an Aqua registry entry (preferred) or a GitHub release backend stub. Not a plugin in this repo. |
| **asdf** (`asdf-pipeline` plugin) | **Future (harness)** | Lives in the external `pipelinek-release-harness` repo: an `asdf-pipeline` plugin with `bin/install`, `bin/download`, `bin/list-bin`, sourced from the canonical ZIP. Not a plugin in this repo. |
| **Scoop** (Windows-native manifest) | **Future** | Gated on Windows demand; would reuse the ZIP and `bin/pipelinek.bat`. Not started. |
| **Container image** | **Future** | Planned for reproducible runners. Not started. |

No channel is going to bypass the GitHub Release ZIP: every installer
above (when implemented) will download or reference the same canonical
artifact and verify its SHA-256. The one-artifact rule is enforced in
the release scripts under `scripts/release/` and, for provenance, in
`CandidateTagProvenance` (`:pipeline-release`).

The `v0.62.0` ZIP is the public artifact authority today. SDKMAN becomes
the recommended channel only once its digest resolution has been exercised
end to end against a clean runner. It has not been: the install contract
tests that used to cover it were deleted by the testing RESET and
AGENTS.md section 2 forbids the subprocess-based replacements that would have
taken their place, so that coverage gap is open by decision rather than by
oversight. Until it is closed, the canonical path remains the ZIP download.

### Verified against the published artifact

Every claim in the README above has been verified against the official
`pipelinek-0.62.0.zip` downloaded from GitHub Releases:

- **ZIP SHA-256**: `385b140c35f6f017d8077eb27d78964ddaf2bd5bd37c5e11afcae5671eb0cbb8`
- **Binary SHA-256**: `92d0f67d16f7ee12888724cfe9da56f19cc2facd51ebee319770a43f40eedeee`
- **Certified commit**: `951b3cb5695ecc46c877776e330266e4bd44aa9e`
- **Examples**: the scenarios in the table above were executed against that
  binary on JDK 24.0.2 (Temurin) on Linux x86_64 with the reported exit code
  and outcome. The set has since been reorganised — some scenarios were merged
  and some retired — so the counts this README publishes are the ones the
  tree holds today, and the gate runs them against the distribution built from
  this tree rather than against a published release.
- **`pipelinek doctor`**: jdk 24.0.2 (Eclipse Adoptium), os Linux,
  workdir writable.

Reproduce locally:

```bash
VERSION=0.62.0

curl -fL -o "pipelinek-${VERSION}.zip" \
  "https://github.com/Rubentxu/pipeline-kotlin/releases/download/v${VERSION}/pipelinek-${VERSION}.zip"

echo "385b140c35f6f017d8077eb27d78964ddaf2bd5bd37c5e11afcae5671eb0cbb8  pipelinek-${VERSION}.zip" \
  | sha256sum -c -

unzip "pipelinek-${VERSION}.zip"

./pipelinek-${VERSION}/bin/pipelinek doctor
```
