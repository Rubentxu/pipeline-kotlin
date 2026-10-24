# PipelineK

[![Latest release (incl. prereleases)](https://img.shields.io/github/v/release/Rubentxu/pipeline-kotlin?include_prereleases&sort=semver)](https://github.com/Rubentxu/pipeline-kotlin/releases)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

A local-first CI/CD engine with a Jenkins-familiar Kotlin DSL.
PipelineK runs pipelines locally with durable execution, real typed
events, and structured failures — without a controller, agent, or
remote state.

## Install

The release says what its asset is called and what its digest is, so nothing
on this page has to be edited when a new release is published:

```bash
# The release feed names the newest release, candidates included.
TAG=$(curl -fsSL https://github.com/Rubentxu/pipeline-kotlin/releases.atom \
  | sed -n 's#.*releases/tag/\(v[^"]*\)".*#\1#p' | head -1)
BASE="https://github.com/Rubentxu/pipeline-kotlin/releases/download/${TAG}"

# The manifest names the release's one distribution; the SDK bundle has
# another name and is not the program.
curl -fL -o manifest.json "$BASE/manifest.json"
ASSET=$(grep -o '"pipelinek-[^"]*\.zip"' manifest.json | grep -v sdk | head -1 | tr -d '"')

curl -fL -o "$ASSET" "$BASE/$ASSET"
curl -fL -o SHA256SUMS "$BASE/SHA256SUMS"
grep " ${ASSET}$" SHA256SUMS | sha256sum -c -

unzip -q "$ASSET"
PIPELINEK="./$(basename "$ASSET" .zip)/bin/pipelinek"

"$PIPELINEK" version          # → pipeline <version>
"$PIPELINEK" doctor           # jdk / os / workdir / writable
```

Java 21 or newer is required on the `PATH`. The ZIP ships `bin/pipelinek`
(UNIX) and `bin/pipelinek.bat` (Windows); Linux, macOS and Windows (WSL) are
supported. The snippet leaves you with a `pipelinek-<version>/` directory
beside you: use it from there, or put its `bin/` on your `PATH`. Nothing
needs `sudo` and nothing starts a daemon.

### Several versions side by side

[`scripts/install-pipelinek.sh`](scripts/install-pipelinek.sh) downloads the
same canonical ZIP, verifies it against the `SHA256SUMS` manifest published
with the release, and unpacks it under `~/.local/share/pipelinek/versions/`:

```bash
curl -fL -o install-pipelinek.sh \
  "https://raw.githubusercontent.com/Rubentxu/pipeline-kotlin/main/scripts/install-pipelinek.sh"
chmod +x install-pipelinek.sh

./install-pipelinek.sh install <release>   # download + verify + install
./install-pipelinek.sh use <release>       # switch the active release
./install-pipelinek.sh list                # installed releases + active

export PATH="$HOME/.local/share/pipelinek/current/bin:${PATH}"
```

Install is **transactional**: download, digest, extraction and identity
verification all happen in a temporary directory, and the final directory is
created by a single rename only after every check has passed. A failed
install leaves nothing behind, and the previously active version is never
disturbed.

A `<release>` is a **stable version** (`0.88.0`) or a **candidate**
(`0.88.0-rc1`). The slot names the release; the artifact name and the
product version inside the bytes are read from the `manifest.json` the
release publishes, so a candidate installs from its own tag and under its
own directory, and asking for a stable version never silently yields a
candidate's bytes. The installed binary must report exactly the product
version the manifest names, and a digest mismatch, a manifest that does
not describe the requested release, or an archive root that disagrees with
the artifact all fail closed instead of installing something mislabelled.

## Your first pipeline

Write this next to your terminal:

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
"$PIPELINEK" run hello.pipeline.kts
```

A pipeline that asks for no secret needs no secret: the run above opens no
credential store and asks for no passphrase. A pipeline that binds a
credential does, and says so plainly if the passphrase is missing.

## What PipelineK is

PipelineK is a single-binary pipeline runner. You write `.pipeline.kts`
files in a typed Kotlin DSL that resembles Jenkins' Groovy syntax, and
PipelineK compiles, validates, and executes them locally. Every step
emits a typed event; every shell process is journaled with a SHA-256
fingerprint of its inputs; every run produces a durable record that you
can resume after a crash.

You keep your data local. PipelineK does not phone home, does not
schedule remotely, and does not need a controller.

## Quickstart

### Write a pipeline

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

### Validate and run

```bash
"$PIPELINEK" validate pipeline.kts
"$PIPELINEK" run --workspace . pipeline.kts
```

A run whose writer was killed — the CI agent reaped, the machine rebooted —
leaves the run unfinished: its journal says a step was still running and its
history says it never finished. `runs cancel` closes it, and refuses to close
one that a live process is still working on:

```bash
"$PIPELINEK" runs cancel <runId> --db <db> --control-root <dir>
```

For the walk-through of workspaces, `--db`, secret redaction and run
inspection, use the CLI's own help, which is generated from the code
that implements each flag:

```bash
"$PIPELINEK" --help
"$PIPELINEK" run --help
```

## Examples

The [`examples/`](examples/) directory holds runnable pipelines that exercise
the real CLI, grouped by what they cover:

| Category | What it covers |
|---|---|
| `smoke/` | The shortest path through the DSL: the environment, `sh` then `echo`, and a checkout → build → archive round trip |
| `steps/` | One scenario per step: `withEnv`, the workspace helpers, `sleep`, `writeFile`, `cleanWs`, `findFiles` |
| `control-flow/` | Kotlin control flow inside a compiled script, and workflow control |
| `durable/` | Durable execution over the journal: platform queries, milestones, `waitUntil`, `stash`/`unstash`, the replay guarantee, closing a run whose writer was killed, and the Saga scenarios — compensation, intervention, a crash mid-effect and its reconciliation |
| `security/` | Credential bindings, and a ZIP-slip path-traversal defence |
| `output/` | The output plane: file reads, a utilities round trip, ZIP/unzip, cross-step artifact queries, HTML publishing, and the live output of a process |
| `events/` | The event lane read by an external client: it reads by cursor, disconnects, and resumes without skipping or repeating a fact; it reads a step's **verdict** out of the event that ends the step; it reads the **stage** a failure belongs to out of the failure event itself — never joining two events; and it **follows** a finished run with a filter and is released when the run ends, because the filter says what the client is handed, not whether the run is over |
| `negative/` | Deliberate failures that must fail closed: error handling, the typed error step, a command that exits non-zero, a workspace symlink escape, an unregistered directive key, a plugin artifact whose bytes cannot be read, a plugin whose bytes are not the ones the operator approved, and two reads of the event lane that must say **why** they cannot be served — an unknown run and a cursor past the tail |
| `plugins/` | The external example plugins, discovered from the distribution classpath: `uppercase`, `repeat`, a contributed directive, and the Saga consumer — plus a plugin admitted under the operator's trust policy |

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
[`examples/run.sh`](examples/run.sh) run the whole set:

```bash
cd pipelinek && ./gradlew :pipeline-application:installDist
cd .. && examples/run.sh
```

The launcher gives each scenario a private control root, a private working
directory and a private credential store, runs it on both execution paths,
and judges each execution on its exit code and on the text the scenario asked
for. What a scenario may ask for is a short vocabulary of `//@` directives,
and it is written down where it is kept true: in the header of
[`examples/run.sh`](examples/run.sh). The launcher is not a framework: it has
no registry, no fixtures and no assertions of its own.

`//@` is an ordinary Kotlin comment rather than a `#!` line, and that
is deliberate. `#!` only ever worked by accident: the script compiler
strips a shebang from the *first* line of a file, so a second `#!` line
became a syntax error and the scenario failed to compile instead of
failing for its own reason. The `@` then keeps a directive from matching
prose, since real scenarios already carry comments like
`// durable execution over the journal`.

## Capabilities

This build ships with:

- Compile, validate, and inspect `.pipeline.kts` pipelines.
- Durable local execution with a SQLite journal (`--db`) and a
  control-root for state isolation.
- Typed event stream: `CompilationStarted`, `RunStarted`,
  `StageStarted`, `StepStarted`, `StepFinished`, `StepFailed`,
  `RunFinished`, `EchoOutputCaptured`.
- A read of the event lane that says **why** it cannot be served: an unknown
  run, a cursor past the tail and a storage failure are three different
  refusals with three different remedies, not one "unknown run". The follow
  contract that carries them is published as the capability
  `events.follow.v2`.
- A failure that says **which stage** it belongs to: the `StepFailed` event
  carries the stage, so a client attributes the failure to its stage from the
  failure event itself, without joining it with another event. The step index
  means a different step in each stage of the run, so that join was never
  unambiguous between stages. A failure that names no stage says so: it is not
  a failure in the first stage. The contract that carries it is published as
  the capability `events.failurestage.v1`.
- A read of the event lane that a client can **resume**: the cursor it gets
  back is the position of the last fact it was handed, so reading by cursor
  in pages, disconnecting and coming back loses no fact and repeats none.
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
- Local credentials, with secret redaction at the durable shell seam. A run
  that binds no credential never opens the store, so it never asks for a
  passphrase.
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
| Install on Linux/macOS/Windows (WSL) | The download above, and `scripts/install-pipelinek.sh`'s own `--help` |
| The same, in Spanish | `README.es.md`, a short guide to the same product |
| Every CLI flag and exit code | `pipelinek --help`, `pipelinek run --help`, and the flag table in `Main.kt` |
| The certified DSL surface | The KDoc on `StageScope.kt` and `PipelineDsl.kt` in `:pipeline-scripting-api`, which is where the DSL is declared |
| `--workspace`, `--db`, `--control-root` | The KDoc on `durable/WorkspaceResolver.kt` in `:pipeline-application` |
| Secret redaction and credentials | Redaction: the KDoc on `SecretPatternRegistry.kt` in `:pipeline-credentials-api`, which also states that a bound secret of fewer than `MIN_MASKABLE_LENGTH` (8) characters runs normally but is **not** masked, so it reaches the transcript in the clear. Store access: `PassphraseResolver.kt` and `CredentialsStorePath.kt` in `:pipeline-credentials-local` |
| Typed events, transcripts, recovery | The KDoc on `JsonEventLog.kt` in `:pipeline-events-store` |
| How the build is verified, and what is out of bounds | `AGENTS.md` in the repository root |

The reason to prefer this over a prose tree is not preference: a KDoc block
and a Markdown file drift apart, and only the first one fails to compile when
it does.

## Releases and distribution channels

The releases, with the digest of every published asset, are on
[GitHub Releases](https://github.com/Rubentxu/pipeline-kotlin/releases).

PipelineK publishes **one canonical ZIP** per release. Every other
channel consumes the same bytes; nothing is rebuilt per installer.

| Channel | Status | Notes |
|---|---|---|
| **GitHub Releases ZIP** | **Available** | Canonical artifact. ZIP + SHA-256 + CycloneDX SBOM + release manifest. |
| **Multi-version installer** (`scripts/install-pipelinek.sh`) | **Available, with a gap** | Bash installer in this repo: `install <v>` / `use <v>` / `list` / `uninstall <v>` / `doctor`. URL allowlist fail-closed; SHA-256-verified; no `sudo`. It resolves a release whose tag is exactly the version asked for, so it does not yet resolve a candidate. See [Install](#several-versions-side-by-side). |
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
