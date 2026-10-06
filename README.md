# PipelineK

[![Latest release (incl. prereleases)](https://img.shields.io/github/v/release/Rubentxu/pipeline-kotlin?include_prereleases&sort=semver)](https://github.com/Rubentxu/pipeline-kotlin/releases)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue)](LICENSE)
![Kotlin](https://img.shields.io/badge/kotlin-2.4.10-blueviolet.svg)
![JVM](https://img.shields.io/badge/jvm-21-orange.svg)

[Español](README.es.md) · **English**

**A local-first CI/CD engine with a Jenkins-familiar Kotlin DSL.** You write a `.pipeline.kts` file;
PipelineK compiles, validates and runs it **on your machine**. No controller, no agent, no remote
state, no phone-home.

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

```bash
pipelinek validate pipeline.kts
pipelinek run --workspace . pipeline.kts
```

If you know Jenkins, you already know how to read this. If you know Kotlin, you already know how to
fix it.

---

## What it does

**It compiles your pipeline before it runs anything.** Your file is real Kotlin, compiled by the real
Kotlin compiler. A type error means nothing executes — you get the error in your editor, not half a
minute into a run.

```bash
$ pipelinek run examples/01-hello.pipeline.kts
[INFO]  RunStarted runId=… 
[INFO]  StageStarted stage=01-hello
[INFO]  StepStarted step=echo
PIPELINE-OK
[INFO]  StepFinished step=echo outcome=success
[INFO]  RunFinished outcome=success
$ echo $?
0
```

**It records everything, so a crash is not a lost run.** Every operation is journaled with a SHA-256
fingerprint of its inputs. If the process dies, the next run recognises what already happened instead
of blindly repeating it.

```bash
pipelinek run --db journal.db pipeline.kts    # first run
pipelinek run --db journal.db pipeline.kts --resume   # resumes it
```

**It speaks to other processes.** Every step emits its own typed events, so an external system can
observe and react from a different process. These are semantics, not log lines.

```bash
pipelinek events --db journal.db <run-id> --kind StepFailed
```

**It knows Jenkins.** Steps and block steps carry the names and behaviour you expect: `sh`, `echo`,
`dir`, `timeout`, `retry`, `catchError`, `waitUntil`, `milestone`, `stash`, `cleanWs`. See the
[Examples](#examples) below for ten that run against the published binary.

### The three ideas that explain the rest

1. **Fail-closed.** When in doubt, **stop**. An unknown step, an unknown token, an incompatible
   schema version: all rejected before any effect is produced. This is why an unknown plugin is never
   silently ignored.
2. **Events are semantics, not logs.** A step whose only observable effect is its return value is
   considered incomplete.
3. **State is persisted, so a run can be resumed.** Fingerprints in the journal are what make
   "reattach, don't relaunch" possible.

### And what it does not do

| ✅ Does | ❌ Does not |
|---|---|
| Compiles, validates and inspects `.pipeline.kts` | **No remote CI** in this repository |
| Durable local execution with a SQLite journal (`--db`), resumable | **No controller, no remote execution** — the control plane is another project |
| Typed event stream, queryable with `pipelinek events` | **No Jenkins or Kubernetes** — that is `pipelinek-fabric`'s job |
| Jenkins-familiar DSL | **No daemon** — one run per invocation |
| `script {}` blocks with real Kotlin control flow | **Not "production ready"**: the product gate is blocked |
| External plugins that add steps *and* events with **zero changes to core** | `agent`, `load`, `node`, `ansiColor` and the `retry` retrofit **do not work**: accepted syntax that always fails closed |
| Local encrypted credentials, redacted in the log | **Not a drop-in for a remote control plane** |

> **Status.** Current release: **`0.47.0`**, which ships `pipelinek-0.47.0.zip` plus a `SHA256SUMS`
> manifest, so its digest is verifiable by consumers. There is **no remote CI since 2026-09-30**, so
> *"CI is green"* is not evidence available here: verification is local, manual, and bound to one
> commit. The production gate is `BLOCKED_EXTERNAL`.

---

## Examples

Ten runnable pipelines in [`examples/`](examples/), each executed against the published binary by
`examples/run.sh`, which asserts the exit code, the terminal outcome and — for the interesting ones —
the event contract.

```bash
pipelinek run --workspace /tmp/pk-example examples/03-shell.pipeline.kts   # installed binary
examples/run.sh 03-shell.pipeline.kts   # cloned repo: asserts exit + outcome
examples/run.sh                        # all ten
```

| Example | Shows | Exit |
|---|---|---|
| `01-hello.pipeline.kts` | Minimum: one stage, one `echo` | `0` |
| `02-multi-stage.pipeline.kts` | Stages run in declaration order | `0` |
| `03-shell.pipeline.kts` | Real OS processes via `sh`, incl. a shell `for` loop | `0` |
| `04-kotlin-control-flow.pipeline.kts` | Kotlin control flow inside `script {}` | `0` |
| `05-failing-step.pipeline.kts` | `sh("exit 3")` → `StepFailed(kind=SCRIPT)`; the next stage **does not run** | `1` |
| `06-durable.pipeline.kts` | Durable execution with `--db`: journal, fingerprints, crash resume | `0` |
| `07-catch-error.pipeline.kts` | Nested `catchError`: inner `FAILURE` → outer `UNSTABLE`, run continues | `0` |
| `08-parallel.pipeline.kts` | Two concurrent branches; the second run reuses instead of relaunching | `0` |
| `09-retry.pipeline.kts` | `retry(3) { }`: first attempt fails, second succeeds | `0` |
| `10-timeout.pipeline.kts` | `timeout(2, "SECONDS")` aborts an over-running `sh` | `1` |

Two of them are worth reading in full, because they show the two things that make PipelineK
PipelineK.

**Failing on purpose, and stopping there** (`examples/05-failing-step.pipeline.kts`):

```kotlin
pipeline {
    stages {
        stage("ok")     { sh("echo first stage runs") }
        stage("fails")  { sh("exit 3") }          // ← the shell exits 3
        stage("after")  { sh("echo never-reached") }  // ← never executes
    }
}
```

Exit code `1`, outcome `failure`, and the `after` stage produces no events at all. A failed step stops
the run — it does not roll through the rest.

**Nested error handling** (`examples/07-catch-error.pipeline.kts`):

```kotlin
pipeline {
    stages {
        stage("catch-demo") {
            catchError(buildResult = "UNSTABLE", stageResult = "UNSTABLE") {   // outer
                catchError(buildResult = "FAILURE", stageResult = "FAILURE") { // inner
                    sh("exit 1")                     // fails
                }                                     // inner turns it into FAILURE
            }                                         // outer degrades it to UNSTABLE
            echo("continues after nested catch")     // the run keeps going
        }
    }
}
```

Exit code `0`, outcome **`unstable`**: the run completed but is flagged as not-quite-green. That third
state is the reason `0` is not always `success`.

Full detail, including the known limitations of each example:
[`examples/README.md`](examples/README.md).

**Watch all ten instead of reading about them** —
[`docs/user/examples.md`](docs/user/examples.md) runs each pipeline against the real binary, one
animated GIF apiece, with the exit code on screen. Here is the one worth seeing first, because
`unstable` is a state most CI systems do not have:

![pipelinek run on 07-catch-error.pipeline.kts, finishing UNSTABLE with exit code 0](docs/user/assets/examples/07-catch-error.gif)

Every GIF states on the page what it leaves out — the JSON event array on stdout — and
[`examples/run.sh`](examples/run.sh) is the assertion behind them, not the pictures.

---

## How it works

```text
   📄 YOUR pipeline.kts                The recipe. Real Kotlin, really compiled.
            │
            ▼
   🧪 SCRIPT COMPILER                 If it does not typecheck, NOTHING runs.
      (Kotlin24ScriptingHost)
            │
            ▼
   🧱 COMPILED PIPELINE              Understood and validated. Immutable:
      (CompiledPipeline)              inspectable before anything executes.
            │
            ▼
   📖 STEP REGISTRY                  "Which steps do I know?" — fails closed if not.
      (registry + plugins)            Unknown step rejected BEFORE any effect.
            │
            ▼
   🧭 DURABLE COORDINATOR            The single control loop. It decides.
            │
            ▼
   ⚙️ DISPATCH ENGINE                 Executes what was decided:
      (StepDispatchEngine)            journal → replay → effect → event → result.
            │                        A step requests capabilities by name,
            │                        never "a context".
            ├────────────► 🗄️ EVENT STORE   SQLite · JSON · in memory
            ▼
   📤 OUTPUT                         pipelinek events  ·  pipelinek console
```

Dependencies point inward, always: adapters depend on contracts, contracts depend on the core, and
`pipeline-domain` depends on nothing — a fitness test enforces it.

What ships as a published artifact is only the contract. `pipeline-events` carries the event plane
contract with no SQLite and no files; `pipeline-events-store` carries the journal and cursor and is
**not published**. That split is what lets another runtime implement the same contract.

---

## Installation

One canonical ZIP per release. Every channel consumes the same bytes and verifies the same SHA-256;
no channel rebuilds PipelineK. That rule is decided in
[`ADR-0089`](docs/v2/04-adrs/ADR-0089-distribution-artifact-authority-sdkman.md) and
[`DISTRIBUTION_RELEASE_SPEC.md`](docs/v2/03-specifications/DISTRIBUTION_RELEASE_SPEC.md).

| Option | How |
|---|---|
| **Canonical ZIP** | Download `pipelinek-0.47.0.zip` from the release, verify its digest, unzip. Java 21+, Linux/macOS/WSL |
| **Multi-version installer** | `scripts/install-pipelinek.sh` — `install` / `use` / `list` / `uninstall` / `doctor`. Fail-closed URL allowlist, digest verified, no `sudo` |
| **`curl \| sh` bootstrap** | `scripts/install-pipelinek-curl.sh` — POSIX `sh`, 13 named exit codes, verifies the installer before delegating. **Not usable yet**: no release publishes the installer as an asset yet (`…/download/v0.47.0/install-pipelinek.sh` is 404), so it exits `10` instead of installing |
| **asdf** | `asdf plugin add pipelinek https://github.com/rubentxu/asdf-pipelinek.git` then `asdf install pipelinek 0.47.0`. Downloads the release ZIP, checks its SHA-256 against the published `SHA256SUMS`, fails closed on mismatch. Never compiles. Needs JDK 21+ on `PATH`. **[Watch it work](docs/user/examples.md#00--install-with-asdf)** |
| **mise** | `mise use -g pipelinek@0.47.0`. The registry entry resolves (`mise ls-remote pipelinek` lists 0.40.0–0.47.0); a full `mise install` was **not** run here, so treat it as unverified end to end |

```bash
VERSION=0.47.0
curl -fL -o "pipelinek-${VERSION}.zip" \
  "https://github.com/Rubentxu/pipeline-kotlin/releases/download/v${VERSION}/pipelinek-${VERSION}.zip"
echo "2fa2d272e3b0ad385ef780e165028efaa83f92804a123f1836e17e0690d3301c  pipelinek-${VERSION}.zip" | sha256sum -c -
unzip "pipelinek-${VERSION}.zip"
./pipelinek-${VERSION}/bin/pipelinek doctor
```

<details>
<summary>Full installation page, requirements and traps</summary>

Requirements: **Java 21+** (certified on Temurin 21.0.8 and 24.0.2), Linux / macOS / Windows via WSL,
~92 MB for the distribution. One run per invocation; no daemon.

The ZIP runs from wherever you unpack it, so it needs no `PATH` change and no `sudo`. The
multi-version installer puts versions under `~/.local/share/pipelinek/` and never writes to `/usr` or
`/opt`.

<details>
<summary>Two traps worth knowing before you start</summary>

**1. Flags go before the script.** The parser stops at the first non-`--` token, so
`run pipeline.kts --db x` silently ignores `--db x`. Write `run --db x pipeline.kts`.

**2. In this repository, the Gradle wrapper lives in `v2/`.** There is no `./gradlew` at the root:

```bash
cd v2 && ./gradlew check        # ✅
v2/gradlew -p v2 check          # ✅
./gradlew -p v2 check           # ❌ no such file
```

</details>

</details>

---

## Exit codes

| Code | Meaning |
|---|---|
| `0` | `SUCCESS` or `UNSTABLE` |
| `1` | `FAILURE` / `ABORTED`, or a compilation error, or invalid CLI arguments |
| `2` | Invocation or admission: script not found, `validate` failed, `--resume` without `--db`, a step that is not canonical |
| `3` | Artifact without `Implementation-Version`, or credentials without a passphrase |
| `4` | Credential store tampered |

Details in [`docs/user/cli-reference.md`](docs/user/cli-reference.md).

---

## Documentation

**Start here → [`docs/user/README.md`](docs/user/README.md) · [Español](docs/user/README.es.md)**

Three routes depending on why you are here, and a record of which DSL constructs are *proven* versus
merely *declared*.

| Route | For |
|---|---|
| **A** | I want to run pipelines — install, quickstart, DSL, CLI reference |
| **B** | I want to operate it — workspace, credentials, events and troubleshooting |
| **C** | I want to contribute code — `CONTEXT.md`, the semantic constitution, `AGENTS.md` |

---

## Local development

```bash
cd v2 && ./gradlew :pipeline-application:installDist
# → v2/pipeline-application/build/install/pipelinek/bin/pipelinek
```

| Task | What it does |
|---|---|
| `cd v2 && ./gradlew check` | Normal gate |
| `cd v2 && ./gradlew check --rerun-tasks` | Full gate from scratch |
| `cd v2 && ./gradlew :pipeline-application:test --tests 'CanonicalInMemoryCliTest'` | Fast single-test loop |
| `cd v2 && ./gradlew :pipeline-architecture-tests:test` | Only the architecture fitness tests (47 `FArch*` classes, ~510 test cases) |

Requires JDK 21 and Gradle 8.14.5 via the wrapper, which **only exists at `v2/gradlew`**. Optionally
`just` + `devbox`.

Two things that cost people an hour here: never run two Gradle invocations on one checkout (there is
a fail-fast `FileLock`), and always read `^e: ` before believing any test result — if test compilation
failed, Gradle runs the *previously compiled* class and reports its result.

### Where the engineering work is tracked

| If you want to know… | Read |
|---|---|
| What is being built, and in what order | [`docs/v2/05-roadmap/ROADMAP.md`](docs/v2/05-roadmap/ROADMAP.md) |
| Why the local foundation work exists | [`LOCAL_FOUNDATION_CONSOLIDATION.md`](docs/v2/05-roadmap/LOCAL_FOUNDATION_CONSOLIDATION.md) — the LFC consolidation roadmap, with per-phase progress |
| What was decided, and why | [`docs/v2/04-adrs/`](docs/v2/04-adrs/) |
| What is actually verified | [`docs/v2/07-uat/`](docs/v2/07-uat/) — evidence is per-commit and never inherits |

---

## Contributing

Read [`CONTEXT.md`](CONTEXT.md) first (45 lines), then
[`docs/pipelinek-semantic-evolution/01-semantic-constitution.md`](docs/pipelinek-semantic-evolution/01-semantic-constitution.md).
Those two are where the vocabulary and the laws live.

Five concepts to internalise:

1. **Fail-closed.** A silent gap is worse than a wrong value: a wrong value gets checked, a gap does not.
2. **A receipt is evidence for its own SHA and inherits nothing.** Move the code and the evidence expired.
3. **A test that cannot fail is not a test.**
4. **Compiling is not conserving.** Every DSL construct needs a typed carrier, a pure desugar with equivalence tests, or a fail-closed refusal.
5. **Mutation beats review** — and over-mutating is also a defect.

There is a **work-unit discipline** and a quality gate in [`AGENTS.md`](AGENTS.md). Read it before
writing code: not every change qualifies for the main gate, and some need explicit traceability to an
exit criterion.

---

## Acknowledgements

- To the Kotlin team, for an excellent language.
- To [Jenkins Pipeline](https://www.jenkins.io/doc/book/pipeline/) and GitHub Actions, for the mental
  model this DSL aims to resemble.

## License

[MIT](LICENSE) © Rubén Torres.