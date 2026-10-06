# PipelineK

[![Latest release (incl. prereleases)](https://img.shields.io/github/v/release/Rubentxu/pipeline-kotlin?include_prereleases&sort=semver)](https://github.com/Rubentxu/pipeline-kotlin/releases)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue)](LICENSE)
![Kotlin](https://img.shields.io/badge/kotlin-2.4.10-blueviolet.svg)
![JVM](https://img.shields.io/badge/jvm-21-orange.svg)

[Español](README.es.md) · **English**

**A local-first CI/CD engine with a Jenkins-familiar Kotlin DSL.** You write a `.pipeline.kts` file;
PipelineK compiles, validates and runs it **on your machine**. Every step emits a typed event, every
shell process is journaled with a SHA-256 fingerprint of its inputs, and every run leaves a durable
record you can resume after a crash. No controller, no agent, no remote state, no phone-home.

```kotlin
// pipeline.kts
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

---

## Quick path

1. **Install** — download the canonical ZIP and verify its digest:
   ```bash
   VERSION=0.39.0
   curl -fL -o "pipelinek-${VERSION}.zip" \
     "https://github.com/Rubentxu/pipeline-kotlin/releases/download/v${VERSION}/pipelinek-${VERSION}.zip"
   echo "385b140c35f6f017d8077eb27d78964ddaf2bd5bd37c5e11afcae5671eb0cbb8  pipelinek-${VERSION}.zip" | sha256sum -c -
   unzip "pipelinek-${VERSION}.zip"
   ```
2. **Check your environment** — must print a writable workdir and Java 21+:
   ```bash
   ./pipelinek-${VERSION}/bin/pipelinek doctor
   ```
3. **Run something that works**:
   ```bash
   ./pipelinek-${VERSION}/bin/pipelinek run examples/01-hello.pipeline.kts   # → exit 0
   ```

<details>
<summary>Requirements and the multi-version installer</summary>

### Requirements

| | |
|---|---|
| **Java** | 21+ (certified on Temurin 21.0.8 and 24.0.2) |
| **OS** | Linux, macOS, Windows via WSL — the ZIP ships `bin/pipelinek` and `bin/pipelinek.bat` |
| **Disk** | ~200 MB for the distribution, plus per-run control data |
| **Concurrency** | one run per CLI invocation. No daemon mode |

The snippet above runs from wherever you want `pipelinek-${VERSION}/` to live, so it needs **no
`PATH` change and no `sudo`**.

### Multi-version installer

For side-by-side versions and easy rollback:

```bash
VERSION=0.39.0
curl -fL -o install-pipelinek.sh \
  "https://raw.githubusercontent.com/Rubentxu/pipeline-kotlin/main/scripts/install-pipelinek.sh"
chmod +x install-pipelinek.sh

./install-pipelinek.sh install "${VERSION}"   # download + verify + install
./install-pipelinek.sh use "${VERSION}"       # switch active version
./install-pipelinek.sh list                  # installed + active
./install-pipelinek.sh doctor

export PATH="$HOME/.local/share/pipelinek/current/bin:${PATH}"
```

Subcommands: `install`, `use`, `list`, `uninstall`, `doctor`, `help`. Source:
[`scripts/install-pipelinek.sh`](scripts/install-pipelinek.sh). Contract tests:
[`scripts/test_install_pipelinek.py`](scripts/test_install_pipelinek.py).

Two guarantees worth knowing: it has a **fail-closed URL allowlist** (`github.com`,
`objects.githubusercontent.com`, plus loopback hosts for local/air-gapped mirrors), and it is
**transactional** — download, digest, extraction and identity verification all happen in a temporary
directory, and the final directory is created by a single rename only after every check passes. A
failed install leaves nothing behind and never disturbs the active version. It also demands **exact
runtime identity**: a version mismatch, a digest mismatch, or a candidate-suffixed binary under a
final filename all fail closed rather than install something mislabelled.

**It needs bash 4+, not POSIX `sh`.** It uses associative arrays and `[[ ]]`, and it sets
`set -Eeuo pipefail`. That matters for method 4 below.

</details>

---

## Installation methods

Five ways in. **Only the first two exist today** — the other three are specified in the distribution
roadmap and have not been built. Read the status column before you copy a command.

| # | Method | Status | Where it lives |
|---|---|---|---|
| 1 | **Canonical ZIP** from GitHub Releases | ✅ **Available, verified** | This repo |
| 2 | **Multi-version installer** (`install-pipelinek.sh`) | ✅ **Available, contract-tested** | This repo |
| 3 | **`mise`** (`mise use -g pipelinek@0.39.0`) | 📋 **Specified, not built** (DIST-4) | External release harness |
| 4 | **`asdf`** (the `asdf-pipeline` plugin) | 📋 **Specified, not built** (DIST-7) | External release harness |
| 5 | **`curl \| sh` one-liner** | ❌ **Does not exist** — see below | Would be this repo |

The governing rule is in
[`ADR-0089`](docs/v2/04-adrs/ADR-0089-distribution-artifact-authority-sdkman.md) and
[`DISTRIBUTION_RELEASE_SPEC.md`](docs/v2/03-specifications/DISTRIBUTION_RELEASE_SPEC.md): **every
channel consumes the same canonical ZIP and verifies the same SHA-256. No channel rebuilds
PipelineK.** So an adapter can never give you different bytes than the ZIP.

### Method 1 — Canonical ZIP ✅

The primary path, and the only one whose bytes are verified against a published release. Full snippet
in [Quick path](#quick-path) above. Digests in
[Verified against the published artifact](#digests).

### Method 2 — Multi-version installer ✅

Detailed in the collapsible block above. Use this when you want several versions side by side and a
one-command rollback.

### Method 3 — `mise` 📋 not built yet

Planned as **DIST-4** in
[`DISTRIBUTION_ROADMAP.md`](docs/v2/05-roadmap/DISTRIBUTION_ROADMAP.md): register `pipelinek` as an
installable tool from the Aqua or GitHub-release backend, so a version manager resolves the canonical
digest and URL for you.

The shape it is designed for:

```bash
mise use -g pipelinek@0.39.0     # resolves the canonical digest and URL, then installs
mise ls pipelinek                 # shows the installed version
```

```toml
# .mise.toml — pin PipelineK next to the project's JDK
[tools]
java = "temurin-21"
pipelinek = "0.39.0"
```

**Status:** *Not started.* The plugin registry is external to this repository, so it cannot ship here.
Until it is registered, these commands do not work — do not put them in your bootstrap. `mise` *is*
already used here for the toolchain (`.tool-versions` pins java, gradle and maven), but that is a
different thing from offering PipelineK itself as a tool.

### Method 4 — `asdf` 📋 not built yet

Planned as **DIST-7**: an external `asdf-pipeline` plugin exposing the standard
`bin/install`, `bin/download` and `bin/list-bin` entry points, so `asdf` can drive the same ZIP.

The shape it is designed for:

```bash
asdf plugin add pipelinek <plugin-repo-url>    # once the plugin exists
asdf list all pipelinek                         # available versions
asdf install pipelinek 0.39.0
asdf global pipelinek 0.39.0
```

```toml
# .tool-versions — asdf reads the same file this repo already ships
[tools]
pipelinek = "0.39.0"
```

**Status:** *Not started*, and like `mise` it lives in the external harness. `asdf` already reads
this repo's `.tool-versions` for `java`, `gradle` and `maven`, but `pipelinek` is **not** in it yet,
and adding the line today would break every `asdf` user with an unresolved plugin.

### Method 5 — `curl | sh` ❌ does not exist

Many projects ship `curl -fsSL https://…/install.sh | sh`. **PipelineK does not, and piping the
current installer does not work either.** This is a property of the code, not an oversight:

| Reason | Evidence |
|---|---|
| The installer is **bash**, not POSIX `sh` — associative arrays, `[[ ]]`, `local` | `scripts/install-pipelinek.sh:46` sets `set -Eeuo pipefail`; it declares **bash 4+** as a precondition |
| Read from stdin, `BASH_SOURCE` is an **empty array**, and `set -u` then aborts | `scripts/install-pipelinek.sh:56` reads `BASH_SOURCE[0]` |
| Net effect | `curl … \| sh` **always fails**; `curl … \| bash` works only on **bash ≥ 4.4** and loses the script name in its usage text |

A future one-liner would have to be a separate, small POSIX bootstrap that `curl`s this installer and
re-executes it **with bash and an explicit argument**, rather than being this installer piped in. Until
that bootstrap exists and is contract-tested, use method 1 or 2.

---

## What it does — and what it doesn't

Read this table before you invest in it. The right column is not a to-do list.

| ✅ Does | ❌ Does not |
|---|---|
| Compiles, validates and inspects `.pipeline.kts` pipelines | **No remote CI** in this repository (see status below) |
| Durable local execution with a SQLite journal (`--db`), resumable after a crash | **No controller and no remote execution** — the control plane lives in another project |
| Typed event stream, queryable with `pipelinek events` | **No Jenkins or Kubernetes** — those are `pipelinek-fabric`'s job |
| Jenkins-familiar DSL: `sh`, `echo`, `dir`, `timeout`, `retry`, `catchError` | **No daemon** — one run per invocation |
| `script {}` blocks with real Kotlin control flow | **Not "production ready" yet** — the PRODUCT-GATE is blocked (below) |
| External plugins that add Steps *and* events **with zero changes to core** | `agent`, `load`, `node`, `ansiColor` and the `retry` retrofit **do not work**: they are accepted syntax that always fails closed |
| Local encrypted credentials, redacted in the log | **Not a drop-in for a remote control plane** |

### Honest status

| | |
|---|---|
| **Current published release** | **`0.47.0`** — it ships `pipelinek-0.47.0.zip` plus a `SHA256SUMS` manifest, so its digest is verifiable by consumers. Older release: `0.39.0` (digests at the bottom) |
| **Development branch** | Also `0.47.0` (`v2/build.gradle.kts:75`) at commit `b08fa948`. The user documentation under [`docs/user/`](docs/user/README.md) describes this version |
| **Remote CI** | **None since 2026-09-30.** `.github/workflows/` is gone, so *"CI is green"* is not evidence available here. Verification is local and manual, against the exact SHA. |
| **Production gate** | `BLOCKED_EXTERNAL` — a per-unit certification does not turn it green |

---

## How it works

Picture a restaurant with a kitchen, a floor and a pass:

```text
   📄 YOUR pipeline.kts                The recipe. It is real Kotlin and really compiles.
            │
            ▼
   🧪 SCRIPT COMPILER                 If it does not typecheck, NOTHING runs.
      (Kotlin24ScriptingHost)         Autocomplete and type errors land in your IDE.
            │
            ▼
   🧱 COMPILED PIPELINE              The recipe understood and validated.
      (CompiledPipeline)              Immutable: inspectable before anything executes.
            │
            ▼
   📖 STEP REGISTRY                  "Which steps do I know?" — fails closed if not.
      (registry + plugins)            An unknown Step is rejected BEFORE any effect.
            │
            ▼
   🧭 DURABLE COORDINATOR            The single control loop of the run. It decides.
            │
            ▼
   ⚙️ DISPATCH ENGINE                 Executes what was decided:
      (StepDispatchEngine)            journal → replay → effect → event → result.
            │                         A Step asks for capabilities by name,
            │                         never for "a context".
            ├────────────► 🗄️ EVENT STORE   SQLite · JSON · in memory
            │              events + journal + replay cursor
            ▼
   📤 OUTPUT                         pipelinek events  (reads the store)
                                      pipelinek console (reads the Output Plane)
```

### The three ideas that explain almost everything

1. **Fail-closed.** When in doubt, **stop**. An unknown Step, an unknown token, an incompatible schema
   version: all rejected before any effect is produced. This is the central policy, and it is why an
   unknown plugin is never silently ignored.
2. **Events are semantics, not logs.** Every Step emits its own typed events (`StepStarted`,
   `StepFinished`, `StepFailed`…) precisely so another process can observe and react. A Step whose
   only observable effect is its return value is considered incomplete.
3. **State is persisted, so a run can be resumed.** A journal records every operation with a
   fingerprint of its inputs. If the process dies, the next run **recognises** what already happened
   instead of blindly repeating it.

### Dependencies point inward

```text
   application · events-store · scripting-kotlin24 · step-sdk:*     adapters
                          ↓
              scripting-api · step-sdk:api · events · output      contracts
                          ↓
                    pipeline-domain                                core, framework-free
```

`pipeline-domain` may not depend on Spring, on the CLI or on SQLite — a fitness test enforces it. When
the core needs something from the outside world, **you define a port**, you do not import the library.

### Published contract ≠ implementation

| Published artifact | Carries | Does not carry |
|---|---|---|
| `:pipeline-events` | The event plane contract | No SQLite, no files |
| `:pipeline-events-store` | Journal, cursor, lease, stores | **Not published** (on purpose) |
| `:pipeline-output` | The **read** side of the Output Plane | Nothing |
| `:pipeline-output-store` | Segment writing | **Not published** (on purpose) |

Each published artifact declares its maturity **per surface, not per module**, in
`v2/contract/published-contract-maturity.json`, and every declared name must resolve in **both** the
module's `.api` dump *and* [`docs/v2/surface/DSL_SURFACE_MANIFEST.md`](docs/v2/surface/DSL_SURFACE_MANIFEST.md).
A surface that appears in only one of the two is a typo, not a surface. Contracts declared
`UNSUPPORTED_FAIL_CLOSED` are constructs that compile and then always refuse, before any effect — not
"not implemented yet".

---

## ⚠️ Two sources of truth in this repository

Read this before touching anything. It is the single biggest source of confusion for newcomers.

```text
pipeline-kotlin/
├── v2/          ← THIS is the active build. 27 modules. The product lives here.
│
├── core/              ← V1. Has its own build.gradle.kts… that NOTHING builds.
├── pipeline-cli/      ← V1. shadowJar, GraalVM native, -c/-s flags: all obsolete.
├── pipeline-backend/  ← V1. A REST API the current product does not have.
├── pipeline-config/   ← V1.
├── pipeline-steps-system/  ← V1 (forbidden on the V2 critical path).
└── v2/pipeline-protocol/   ← ORPHAN: exists on disk, not in the build.
```

The root `settings.gradle.kts` contains, in essence, **one line**:

```kotlin
includeBuild("v2")     // ← that's all
```

The repo says so itself: *"V1 source remains in the repository as legacy/history, but is not part of
the active build"* (`settings.gradle.kts:20-21`). V1's `build.gradle.kts` files are **alive on disk**
with their own dependency catalogs, which is exactly what makes them look like part of the build.
They are not. **Editing them has no effect.**

| Trap | What happens if you don't know |
|---|---|
| `./gradlew -p v2 …` from the repo root | There is no `./gradlew` at the root. The wrapper is `v2/gradlew`. Use `cd v2 && ./gradlew …` or `v2/gradlew -p v2 …` |
| `demo-native-dsl.pipeline.kts` (repo root) | Uses the V1 shape (`pipeline { stage { steps {…} } }`) and **does not compile** against the current DSL |
| A module with its own `build.gradle.kts` | The file existing does not mean the module is in the build |

---

## Exit codes

| Code | Meaning |
|---|---|
| `0` | `SUCCESS` or `UNSTABLE` |
| `1` | `FAILURE` / `ABORTED`, or a compilation error |
| `2` | Usage or flag error (including a non-canonical Step) |
| `3` | Manifest missing its version, or credentials missing the passphrase |
| `4` | Secret store tampered |

Details: [`docs/user/cli-reference.md`](docs/user/cli-reference.md) ·
[`docs/user/cheat-sheet.md`](docs/user/cheat-sheet.md).

---

## Examples

Ten runnable pipelines against the real CLI of the published `0.39.0` distribution, in
[`examples/`](examples/):

```bash
pipelinek run --workspace /tmp/pk-example examples/03-shell.pipeline.kts   # installed binary

examples/run.sh 03-shell.pipeline.kts   # cloned repo: asserts exit code + outcome
examples/run.sh                        # all ten
```

| Example | Shows | Exit |
|---|---|---|
| `01-hello.pipeline.kts` | Minimum: one stage, one `echo` | `0` |
| `02-multi-stage.pipeline.kts` | Stages run in declaration order | `0` |
| `03-shell.pipeline.kts` | Real OS processes via `sh`, incl. a shell `for` loop | `0` |
| `04-kotlin-control-flow.pipeline.kts` | Kotlin control flow inside `script {}` | `0` |
| `05-failing-step.pipeline.kts` | Typed failure: `sh` exits 3 → `StepFailed(kind=SCRIPT)` | `1` |
| `06-durable.pipeline.kts` | Durable execution with `--db`: journal, fingerprints, crash resume | `0` |
| `07-catch-error.pipeline.kts` | Nested `catchError`: inner `FAILURE` → outer `UNSTABLE`, run continues | `0` |
| `08-parallel.pipeline.kts` | Two concurrent branches with their own durable identity | `0` |
| `09-retry.pipeline.kts` | `retry`: first attempt fails, second succeeds | `0` |
| `10-timeout.pipeline.kts` | `timeout` aborts an over-running `sh` | `1` |

See [`examples/README.md`](examples/README.md) for the durable-execution demo, the event-contract
details, and the known limitations of each example.

---

## Capabilities

- Compile, validate and inspect `.pipeline.kts` pipelines.
- Durable local execution with a SQLite journal (`--db`) and a *control-root* for state isolation.
- Typed event stream: `CompilationStarted`, `RunStarted`, `StageStarted`, `StepStarted`,
  `StepFinished`, `StepFailed`, `RunFinished`, `EchoOutputCaptured`.
- Jenkins-familiar shape: `pipeline { stages { stage { … } } }`.
- Block steps: `parallel`, `retry`, `timeout`, `catchError`.
- `script {}` blocks with real Kotlin control flow.
- Typed plugin and capability contracts (`StepContract`, `requiredCapabilities`, registry-based
  discovery).
- Local credentials, with secret redaction at the durable shell seam.
- Local Steps: `artifacts`, `stash`, `unstash`, `archiveArtifacts`, `publishHTML`, `writeFile`, `pwd`,
  `isUnix`, `milestone`, `cleanWs`, `deleteDir`, `waitUntil`, `unstable`, `warnError`.

---

## Documentation

**Start here → [`docs/user/README.md`](docs/user/README.md) · [Español](docs/user/README.es.md)**

That page is the entry point to the whole user documentation. It gives you three routes depending on
why you are here, and it is also where we record which DSL constructs are *proven* versus merely
*declared*, so you know what you can rely on.

| Route | For | Pages |
|---|---|---|
| **A** | I want to run pipelines | Installation → Quickstart → DSL → CLI reference |
| **B** | I want to operate it properly | Configuration & workspace → Credentials → Events & troubleshooting |
| **C** | I want to contribute code | `CONTEXT.md` → semantic constitution → certification protocol → `AGENTS.md` |

Individual pages, if you prefer to jump straight in:

| Document | Covers |
|---|---|
| [`installation.md`](docs/user/installation.md) | Linux / macOS / Windows (WSL) from the Releases ZIP, digest verification |
| [`quickstart.md`](docs/user/quickstart.md) | Your first pipeline, end to end |
| [`pipeline-dsl.md`](docs/user/pipeline-dsl.md) | The DSL surface: proven vs declared vs fails-closed |
| [`cli-reference.md`](docs/user/cli-reference.md) | Subcommands, flags, exit codes, and the traps |
| [`configuration-and-workspace.md`](docs/user/configuration-and-workspace.md) | `--workspace`, `--db`, `--control-root`, `--isolated` |
| [`credentials-and-security.md`](docs/user/credentials-and-security.md) | Secret redaction and the credential store |
| [`events-and-troubleshooting.md`](docs/user/events-and-troubleshooting.md) | Typed events, transcripts, symptom → cause → fix |
| [`upgrading.md`](docs/user/upgrading.md) | Version selection and rollback |
| [`cheat-sheet.md`](docs/user/cheat-sheet.md) | Short copyable exit-code table |

> Every page exists in English and Spanish. Version numbers, digests, flags and event keys are
> **identical** across both; only the prose is translated.

**Coming to contribute rather than to use it?** Don't start here. Start with
[`CONTEXT.md`](CONTEXT.md) and
[`docs/pipelinek-semantic-evolution/01-semantic-constitution.md`](docs/pipelinek-semantic-evolution/01-semantic-constitution.md).

---

## Distribution channels

One canonical ZIP per release. Every channel consumes the same bytes; nothing is rebuilt per
installer.

| Channel | Status |
|---|---|
| **GitHub Releases ZIP** | ✅ Canonical artifact: ZIP + SHA-256 + CycloneDX SBOM + release manifest |
| **Multi-version installer** (`scripts/install-pipelinek.sh`) | ✅ Fail-closed URL allowlist, SHA-256 verified, no `sudo` |
| **Direct download** | ✅ Same ZIP from the release page |
| **SDKMAN** | ⏳ Vendor onboarding in progress; publish script ready, blocked on `SDKMAN_CONSUMER_KEY` / `SDKMAN_CONSUMER_TOKEN`. Digests resolved from the release `SHA256SUMS` via `scripts/release/resolve-release-digest.sh`. Contracts: `python3 scripts/release/test_sdkman_digest.py`. [ADR-0089](docs/v2/04-adrs/ADR-0089-distribution-artifact-authority-sdkman.md) |
| **Homebrew** (`rubentxu/tap/pipeline`) | 📋 Tap not started; will reuse the same ZIP |
| **mise / asdf** | 📋 Live in the external `pipelinek-release-harness` repo, not here |
| **Scoop / container image** | 📋 Gated on demand; would reuse the ZIP and `bin/pipelinek.bat` |

No channel will bypass the Releases ZIP: every installer downloads or references the same canonical
artifact and verifies its SHA-256.

### Digests

**`0.47.0` — the current release.** Its ZIP digest is taken from the release `SHA256SUMS` manifest:

| | |
|---|---|
| **ZIP SHA-256** | `2fa2d272e3b0ad385ef780e165028efaa83f92804a123f1836e17e0690d3301c` (as listed in `SHA256SUMS`) |
| **Also in that release** | `distribution-manifest.json`, `candidate-handoff.json`, `pipelinek-0.47.0.sbom.json` |

**`0.39.0` — the release the local verification below was executed against.**

| | |
|---|---|
| **ZIP SHA-256** | `385b140c35f6f017d8077eb27d78964ddaf2bd5bd37c5e11afcae5671eb0cbb8` |
| **Binary SHA-256** | `92d0f67d16f7ee12888724cfe9da56f19cc2facd51ebee319770a43f40eedeee` |
| **Certified commit** | `951b3cb5695ecc46c877776e330266e4bd44aa9e` |
| **Examples 01–10** | Each row of the table above was executed against this binary on Temurin 24.0.2, Linux x86_64, with the reported exit code and outcome |
| **`pipelinek doctor`** | jdk 24.0.2 (Eclipse Adoptium), os Linux, workdir writable |

---

## Local development

```bash
# ✅ both correct forms
cd v2 && ./gradlew check
v2/gradlew -p v2 check

# ❌ this fails: there is no ./gradlew at the repo root
./gradlew -p v2 check
```

| Task | What it does |
|---|---|
| `cd v2 && ./gradlew check` | Normal gate |
| `cd v2 && ./gradlew check --rerun-tasks` | Full gate from scratch (what the receipts require) |
| `cd v2 && ./gradlew :pipeline-application:installDist` | Builds the binary into `build/install/pipelinek/bin/` |
| `cd v2 && ./gradlew :pipeline-application:test --tests 'CanonicalInMemoryCliTest'` | Fast single-test inner loop |
| `cd v2 && ./gradlew :pipeline-architecture-tests:test` | Only the ~45 architecture fitness tests |

Requires JDK 21 (the build asks for toolchain 21; note `.tool-versions` says 24.0.2 and `devbox.json`
says 21) and Gradle 8.14.5 via the wrapper, which **only exists at `v2/gradlew`**. Optionally `just` +
`devbox` (`just bootstrap` = `devbox install`); `just t <pattern>`, `just app-fast`, `just gate-app`,
`just gate`, `just gate-escalate`, `just changed [base]`, `just doctor`.

The binary lands at `v2/pipeline-application/build/install/pipelinek/bin/pipelinek`. The name
`pipelinek` is **not hardcoded** — it is read from the build file, so the examples harness tracks a
rename.

| Env var | Purpose |
|---|---|
| `PIPELINE_CREDENTIALS_STORE` | Path to the encrypted secret store |
| `PIPELINE_STORE_PASSPHRASE` | Passphrase to decrypt it |
| `JAVA_HOME`, `GRADLE_USER_HOME`, `MAVEN_OPTS` | Build only (set by `devbox.json`) |

No `.env` is auto-loaded (the `justfile` disables it explicitly).

### Five operational traps

| Trap | What it costs you |
|---|---|
| Two Gradle invocations on one checkout | A fail-fast `FileLock` throws `GradleException`. Use separate worktrees |
| Believing a test result without reading `^e: ` first | If test compilation failed, Gradle runs the *previously compiled* class and reports its result. **A compile failure is not a RED** |
| Assuming CI covers you | There is none. The gate is local, manual, and bound to one exact SHA |
| Asserting on milliseconds | A duration is a property of the machine; it fails on a loaded box and reads as a product defect. Assert a discrete result |
| Trusting a test you never saw fail | If you mutate the code and it stays green, the test is wrong, not the code |

---

## Contributing

### Six files that get you 80% of the way

In this order — deliberately not the usual one.

| # | File | Why that one |
|---|---|---|
| 1 | [`CONTEXT.md`](CONTEXT.md) (45 lines) | The canonical glossary **with anti-terms**. Stops you saying "pipeline spec" when the official term is *Compiled Pipeline* |
| 2 | [`docs/v2/01-product/PRD_V2.md`](docs/v2/01-product/PRD_V2.md) | The only current product description: users and jobs-to-be-done |
| 3 | [`v2/settings.gradle.kts`](v2/settings.gradle.kts) | The real module graph, with comments explaining **why** each split exists and which one is not published |
| 4 | [`docs/pipelinek-semantic-evolution/01-semantic-constitution.md`](docs/pipelinek-semantic-evolution/01-semantic-constitution.md) | **The laws.** This is what you memorise |
| 5 | [`docs/v2/07-uat/CERTIFICATION_PROTOCOL.md`](docs/v2/07-uat/CERTIFICATION_PROTOCOL.md) | What `STEP-CERT` means, what a gate demands, what `NOT_RUN` is. Without it you believe receipts too easily |
| 6 | [`docs/v2/05-roadmap/ROADMAP.md`](docs/v2/05-roadmap/ROADMAP.md) | The operating sequence: what is active and in what order |

### Five concepts to internalise

1. **Fail-closed.** A silent gap is **worse** than a wrong value, because a wrong value gets checked
   and a gap does not.
2. **A receipt is evidence for its own SHA and inherits nothing.** Move the code and the evidence expired.
3. **A test that cannot fail is not a test.** An assertion you never watched fail is unproven.
4. **Compiling is not conserving.** Every DSL construct needs a typed carrier, a pure desugar with
   equivalence tests, or a fail-closed refusal.
5. **Mutation beats review.** And over-mutating is also a defect: attribute each mutation 1:1 to what
   it kills.

### Before your first PR

This project has a **work-unit** discipline and a quality gate. Read
[`AGENTS.md`](AGENTS.md) before writing code: not every change qualifies for the main gate, and some
need explicit traceability to an exit criterion.

Checklist:

- [ ] I read `CONTEXT.md` and know the vocabulary
- [ ] I know whether my change touches the **published contract** (`.api` dump) or only internals
- [ ] I ran `./gradlew check` from `v2/` on a clean tree
- [ ] I read `^e: ` before believing any test outcome
- [ ] If I changed a contract, I checked whether a receipt must be re-issued for the new SHA
- [ ] If I touched a fitness-protected area, I ran `:pipeline-architecture-tests:test` too

### Flows worth reading with the code open

- One Step end to end: its `StepContract`, how it requests a capability, how it reaches the journal.
- The fail-closed path: what happens when a Step is not in the registry.
- One event end to end: who emits it, how it is serialized, how it is read back, and what happens when
  it cannot be read.
- One replay: what `EffectReplayPolicy` decides, and why `UNSTABLE` is reusable.

---

## Acknowledgements

- To the Kotlin team, for an excellent language.
- To [Jenkins Pipeline](https://www.jenkins.io/doc/book/pipeline/) and GitHub Actions, for the mental
  model this DSL aims to resemble.

---

## License

[MIT](LICENSE) © Rubén Torres.