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
"$PIPELINEK" doctor           # jdk / os / workdir / writable / script cache
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

The [`examples/`](examples/) directory holds the external example plugins.
They are **product**, not tests: each one is an independent Gradle build that
compiles against the published SDK alone, and the distribution ships their
JARs as bundled plugins. Building them is what proves the plugin contract is
reachable from outside this build.

| Plugin | What it proves |
|---|---|
| `example-uppercase-plugin` | The plugin contract is reachable at all from outside the build |
| `example-directive-plugin` | The directive kernel is open by key from outside the build |
| `example-block-plugin` | A handler drives its own body through the bound continuation, compiled against the public SDK only |
| `example-saga-plugin` | The Saga contract is reachable through the published capabilities, with the engine owning the state |

Each one is the ROOT of its own Gradle build, on purpose: a plugin that needed
anything from `pipeline-application` would make the open-world claim it proves
false. The four are built by ONE task in the root build script, which forks a
single Gradle invocation over the composite in `examples/example-plugins` so
they build concurrently.

## Verification

The verification of this repository is `scripts/verify-all.sh`, and it runs
three legs:

```bash
scripts/verify-all.sh
```

1. **Unit tests** — one pure domain rule or isolated application decision
   per test, in each module's `src/test/kotlin`.
2. **Integration tests with mocks** — two or more real modules composed as
   the product wires them, with every effect that would reach outside the
   process (the filesystem, a subprocess, the clock, the network) replaced by
   a fake the test owns. They live in each module's `src/integrationTest/kotlin`.
3. **Mutants** — `pitest` over the seams the mutated modules declare. A
   surviving mutant is a hole in the test that was supposed to kill it, not a
   defect in the product.

There is no end-to-end layer: no scenario suite, no acceptance harness, no
launcher. A behaviour that neither a unit test nor a mocked integration test
can observe is stated as an honest gap in the change that introduces it.

The gate also reads the tree before it builds anything, and it refuses a green
over nothing: a module that has a test source and produces no JUnit report
fails the run, because Gradle reports SUCCESS over an empty result set.

The gate certifies a **batch of ten tasks** from the backlog board, not every
change: the board is the unit of delivery, and between two batches the loop is
the scoped run of the module that changed. The gate does not re-run what
Gradle already knows is unchanged — the verdict says which reports it wrote
and which it read from disk — so a batch costs what the batch changed and no
more.

For the inner loop, the gate takes filters — `--module` and `--test` — and a
filtered run says `PASS (subset)`, because it measured less than the whole:

```bash
scripts/verify-all.sh --stage 3 --module ':pipeline-domain' --test '*ResolveTest'
scripts/verify-all.sh --list
```

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
  `events.follow.v3`.
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

### The cache of compiled scripts

Compiling a `.pipeline.kts` costs seconds; running the compiled result costs
milliseconds. PipelineK keeps the compiled form in a cache so the second run of
the same script is fast, and the cache is **on by default**.

| | |
|---|---|
| Where | `${XDG_CACHE_HOME:-$HOME/.cache}/pipelinek/script-cache` |
| How private | The directory is created by PipelineK, owned by you, and readable by nobody else. If it cannot be proven private — it exists but belongs to someone else, or others may read it — the cache is **not used**, and `doctor` says why. |
| How to move it | `PIPELINEK_SCRIPT_CACHE_DIR=/some/path` |
| How to turn it off | `PIPELINEK_SCRIPT_CACHE_DIR=off` |

A compiled script carries the literals of the script that produced it, so the
cache is a place where your code may sit in a readable form. The rule is
therefore closed by default: a cache directory that cannot be proven private
is not used, and the run simply compiles again. `pipelinek doctor` always
reports where the cache is and whether it is being used.

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
lives next to the code it describes, and it is read with that code. A comment
is never compiled, so a drift between a KDoc and this file is invisible to
the compiler; what catches it is an instrument — `DocumentationDriftTest`
reads this file and the code and fails when they disagree.

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
