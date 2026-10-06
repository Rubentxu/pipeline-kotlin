# PipelineK — Pipeline DSL

> **Documentation divergence.** This page previously claimed the `0.39.0` contract and stated that
> `parallel` / `retry` / `catchError` did not exist. That claim did not hold against the code: they
> are declared in the v2 DSL and exercised by `examples/run.sh`. The page now documents the
> development branch (`0.47.0`). Recorded 2026-10-06. See `docs/user/README.md` → "Known divergences".

**Documented against**: development branch, `pipelinek 0.47.0` (v2/build.gradle.kts:75), commit `b08fa948`
**Not verified against a published binary.** The current published release is `0.47.0` (its ZIP digest is listed in the release `SHA256SUMS`); see the divergence note below.

## What you will be able to do when you finish

- Write a `.pipeline.kts` file that the engine actually accepts, using the real
  shape `pipeline { stages { stage("…") { … } } }`.
- Tell, before you write a line, whether a construct is **proven**, merely
  **declared**, or **fails closed** — and never claim a declared construct
  works.
- Avoid the four constructs that compile and are then rejected by `run` with
  exit `2`, and the one `retry` form that throws at the call site.
- Read the catalogue and know exactly where the evidence for each construct
  comes from.

## Words you will need

| Term | Everyday picture | Meaning in PipelineK |
|---|---|---|
| **stage** | a room in a house you walk through in order | a named unit of work; steps inside run in order |
| **step** | one instruction on a checklist | the smallest unit that produces events |
| **workspace** | the toolbox, the folder where the work happens | the working directory the steps run in |
| **journal** | the operator's notebook, where the log was written | the durable SQLite history; the thing `--db` points at |
| **event** | a line written in the notebook | a typed record another process can read |
| **block step** | a box containing smaller instructions | a step whose body is itself a sequence of steps |

The DSL is a Kotlin DSL inside an ordinary `.kts` file: you get the whole
Kotlin language at file level, and the DSL only governs the body of
`pipeline { … }`.

## The three states — read this before the catalogue

Every construct below carries exactly one of these labels. Do not skip this
table; it is the difference between an honest page and a page that promises
things.

| Label | What it means | What you may claim |
|---|---|---|
| **Proven by the examples** | `examples/run.sh` executes it **and asserts** an exit code, a terminal outcome, and (for 07–10) an event contract | "this works" |
| **Declared, no reference example** | It exists in the code; **no example in this repository uses it** | "this is declared", and nothing stronger |
| **Fails closed** | It compiles, and `run` then refuses it with exit `2` (or it throws at the call) | "do not use this" |

> The middle label is not a polite way of saying "untested". It means exactly
> what it says: **no example exercises it, and this page therefore does not
> claim it works.**

## Minimum structure

```kotlin
pipeline {
    stages {
        stage("build") {
            sh("./gradlew --no-daemon build")
            sh("test -f build/libs/*.jar")
            echo("GRADLE-DEMO-OK")
        }
    }
}
```

Steps inside a `stage` run **in order**. The next step runs only if the
previous one ended successfully. A failing step fails the stage and the run,
and the CLI exits `1`.

A worked, asserted example of exactly this shape is
`examples/01-hello.pipeline.kts`.

## Tier 1 — Proven by the examples

These are the constructs `examples/run.sh` actually runs and checks.

| Construct | Example | CLI exit | Terminal outcome |
|---|---|---|---|
| `pipeline { }` / `stages { }` / `stage("x") { }` | 01, 02 | `0` | `success` |
| `echo(text)` | all | `0` | `success` |
| `sh(command)` | 03, 05 | `0` / `1` | `success` / `failure` |
| `script { }` | 04 | `0` | `success` |
| `catchError(buildResult, stageResult, message) { }` | 07 | `0` | **`unstable`** |
| `parallel { branch("x") { } }` | 08 | `0` | `success` |
| `retry(count) { }` — the **block** form | 09 | `0` | `success` |
| `timeout(time, unit) { }` — the block form | 10 | `1` | `failure` |
| Durable journal `--db` and reuse | 06, 08, 09 | `0` | `success` |

### The example catalogue

| File | What it shows | CLI exit | Terminal outcome |
|---|---|---|---|
| `01-hello.pipeline.kts` | Minimal pipeline: one stage, one `echo` | `0` | `success` |
| `02-multi-stage.pipeline.kts` | Three stages, in order | `0` | `success` |
| `03-shell.pipeline.kts` | Real OS processes through `sh` | `0` | `success` |
| `04-kotlin-control-flow.pipeline.kts` | `script { }` with control flow | `0` | `success` |
| `05-failing-step.pipeline.kts` | `sh("exit 3")`; the later stage **does not** run | **`1`** | `failure` |
| `06-durable.pipeline.kts` | `--db` twice: the second reuses | `0` (×2) | `success` |
| `07-catch-error.pipeline.kts` | Nested `catchError`: FAILURE→UNSTABLE | `0` | **`unstable`** |
| `08-parallel.pipeline.kts` | `parallel` + `branch`; the 2nd run reuses | `0` (×2) | `success` |
| `09-retry.pipeline.kts` | `retry(3) { }`: fails once, then succeeds | `0` (×2) | `success` |
| `10-timeout.pipeline.kts` | `timeout(2,"SECONDS")` over `sleep 30` | **`1`** | `failure` |

### What `run.sh` actually asserts

Running the example is not enough — the harness checks the observable
behaviour, so these are behavioural claims, not decoration:

| Example | Assertion |
|---|---|
| `05` | A later stage **must not** execute. |
| `07` | Exactly `1` `CatchErrorTriggered(FAILURE)` then `1` `CatchErrorTriggered(UNSTABLE)`, **innermost first**, and the `echo` after the scopes still runs. |
| `08` | The **second** run with the same `--db` produces `0` `ParallelBranchStarted` and `0` `StepStarted` — it reuses, it does not relaunch. |
| `09` | Exactly `2` `RetryAttemptFinished`, `failed` then `succeeded`. |
| `10` | At least `1` `TimeoutScheduled`, plus a `StepFailed` whose message contains "timed out". |

`catchError` in action (example 07):

```kotlin
pipeline {
    stages {
        stage("catch-demo") {
            catchError(buildResult = "UNSTABLE", stageResult = "UNSTABLE") {
                catchError(buildResult = "FAILURE", stageResult = "FAILURE") {
                    sh("echo inner-body-failing")
                    sh("exit 1")
                }
            }
            echo("continues after nested catch")
        }
    }
}
```

`parallel` and `branch` (example 08):

```kotlin
stage("parallel-demo") {
    parallel {
        branch("left") {
            echo("left branch")
            sh("echo left-work")
        }
        branch("right") {
            echo("right branch")
            sh("echo right-work")
        }
    }
}
```

## The two forms of `retry` — read this twice

There are **two** `retry` overloads in the DSL, and they behave differently.
Confusing them is the single most common mistake on this page.

| Form | What happens | Label |
|---|---|---|
| `retry(count = 3) { … }` — the **block** form | Registers the `core.retry` block step and is honoured by the coordinator. Example 09 runs it. | **Proven by the examples** |
| `retry(count = 3, delaySeconds = 5)` — the **step-level** form, no block | **Throws `IllegalArgumentException` at the call site**, before any step is registered (`StageScopeBuilders.kt:306`) | **Fails closed** |

Why both exist: the step-level overload was removed because the projected
retry policy had **no runtime consumer** — the compiled path reads only the
`maxAttempts` of the `core.retry` block step, so `delaySeconds` was accepted
and then never executed. It was kept as an explicitly throwing signature so
that a caller migrating from it gets this diagnostic instead of a bare Kotlin
signature error:

```text
retry(count = N) at step level was removed: the projected retry policy had no
runtime consumer … Use the block form retry(N) { ... }.
```

**Which one to use: always `retry(count) { … }`.**

## Tier 2 — Declared, no reference example

Everything in this section is declared in the v2 DSL. **No example in this
repository uses it.** This page makes no claim about whether it works; treat
it as "declared", and verify it yourself before you depend on it.

`withEnv`, `dir`, `withCredentials`, `stash` / `unstash`, `archiveArtifacts`,
`artifactQuery`, `deleteDir`, `cleanWs`, `writeFile`, `readFile`, `fileExists`,
`error`, `sleep`, `warnError`, `unstable`, `pwd`, `isUnix`, `waitUntil`,
`milestone`, `publishHTML`, `lock`, `input`, `timestamps`, `post`,
`environment { }`, `options { timeout }`, `agent` / `agentAny` /
`agentWithCapabilities`, `whenGate` / `whenEnvIs` / `whenEnvPresent`,
`checkout` / `scmGit` / `git`, `registryStep` / `registryBlock`, and all
plugin builders (`httpRequest`, `junitResults`, `scmGitCheckout`,
`core-utils.*`).

### Signatures of the main ones

| Construct | Signature | Line |
|---|---|---|
| `echo` | `echo(text: String)` | `StageScopeBuilders.kt:110` |
| `sh` | `sh(command: String)` | `StageScopeBuilders.kt:114` |
| `sh` | `sh(script: String, isScriptBlock: Boolean = false, returnStdout: Boolean = false)` | `StageScopeBuilders.kt:118` |
| `error` | `error(message: String, failureKind: FailureKind = FailureKind.USER)` | `StageScopeBuilders.kt:128` |
| `sleep` | `sleep(seconds: Long)` | `StageScopeBuilders.kt:132` |
| `writeFile` | `writeFile(file, text, encoding = "UTF-8")` | `StageScopeBuilders.kt:312` |
| `readFile` | `readFile(file, encoding = "UTF-8")` | `StageScopeBuilders.kt:335` |
| `fileExists` | `fileExists(file)` | `StageScopeBuilders.kt:339` |
| `archiveArtifacts` | `archiveArtifacts(artifacts, allowEmptyArchive = false, excludes = "", fingerprint = false, name = null)` | `StageScopeBuilders.kt:357` |
| `deleteDir` | `deleteDir(path = ".")` | `StageScope.kt:69` |
| `cleanWs` | `cleanWs(deleteDirs = true, patterns: List<String>? = null)` and `cleanWs(deleteDirs = true, vararg patterns: String)` | `StageScope.kt:90,118` |
| `catchError` | `catchError(buildResult: String? = null, stageResult: String? = null, message: String? = null, block)` | `StageScope.kt:141` |
| `warnError` | `warnError(message, catchInterruptions = true, block)` | `StageScope.kt:171` |
| `unstable` | `unstable(message: String)` | `StageScope.kt:197` |
| `waitUntil` | `waitUntil(initialRecurrencePeriod = 1L, quiet = false, body)` | `StageScope.kt:391` |
| `timestamps` | `timestamps(block)` | `StageScope.kt:421` |
| `milestone` | `milestone(ordinal: Int, label: String? = null)` | `StageScope.kt:473` |
| `stash` | `stash(name, includes, excludes = "")` | `StageScope.kt:515` |
| `unstash` | `unstash(name, into = null)` | `StageScope.kt:552` |
| `publishHTML` | `publishHTML(name, reportDir, reportFiles = "**", keepAll = false, …)` | `StageScope.kt:599` |
| `timeout` | `timeout(time: Long, unit: String, block)` | `StageScope.kt:638` |
| `retry` | `retry(count: Int, block)` | `StageScope.kt:652` |
| `dir` | `dir(path: String, block)` | `StageScopeBuilders.kt:386` |
| `lock` | `lock(resource, timeoutSeconds = null, reason = null, skipIfLocked = false, block)` | `StageScopeBuilders.kt:409` |
| `input` | `input(message, ok = "Proceed", submitter = null, id = null, timeoutSeconds = null, block)` | `StageScopeBuilders.kt:446` |
| `withEnv` | `withEnv(overrides, block)` | `StageScopeBuilders.kt:343` |
| `withCredentials` | `withCredentials(vararg bindings, block)` | `StageScopeBuilders.kt:240` |
| `script` | `script(block: ScriptScope.() -> Unit)` — **DEPRECATED**, flattens to a `StepSpec.Shell` | `StageScopeBuilders.kt:269` |
| `post` | `post(block: PostScope.() -> Unit)` | `StageScopeBuilders.kt:222` |
| `environment` | `environment(block: EnvironmentScope.() -> Unit)` | `StageScopeBuilders.kt:206` |
| `options` | `options(block)` — only `timeout(seconds)` | `StageScopeBuilders.kt:214` |
| `scmGit` | `scmGit(url, branch = "master", credentialsId = null, changelog = true, poll = true, relativeTargetDir = ".")` — **zero effects**, returns a carrier | `StageScopeBuilders.kt:175` |

Two entries deserve a warning of their own:

- `script { }` is **DEPRECATED**: it flattens to a `StepSpec.Shell`
  (`StageScopeBuilders.kt:269`). Example 04 still uses it and still passes,
  which is why it is in Tier 1 — but it is on its way out.
- `scmGit(...)` is a **pure builder**: it performs no I/O, emits no step and
  no event, and returns a `CheckoutSpec` carrier
  (`StageScopeBuilders.kt:175`). It is `checkout(...)` that does the work.
  A builder that performed the effect would be a step wearing a builder's
  name.

### Not every scope offers every construct

A scope is a restricted set of moves. Calling a construct from the wrong
scope does not compile.

| Scope | What it exposes | Line |
|---|---|---|
| `ScriptScope` | `line`, `echo`, `sh`, `error` | `StageScope.kt:421,427,437,441,445` |
| `BranchScope` | `echo`, `sh`, `error`, `sleep`, `dir`, `lock` | `StageScope.kt:355-396` |
| `PostStepsScope` | `echo`, `sh`, `error`, `sleep` | `StageScope.kt:315-330` |

So inside a `branch { … }` you can call `echo`, `sh`, `error`, `sleep`,
`dir` and `lock` — and nothing else from this page.

## Tier 3 — Fails closed

Four constructs **compile and are then rejected by `run` with exit `2`**,
because they have no descriptor or no valid registry entry. They are not
"not finished": they are refused, and refusing is the contract.

| Construct | Why it is refused | Where |
|---|---|---|
| `git(url, branch = "master", …)` | Delegates to `checkout(scmGit(...))` → key `core.checkout`, which **nobody registers** | `StageScopeBuilders.kt:193` |
| `load(path)` | Emits `core.load`, with no descriptor | `StageScope.kt:349` |
| `node(label) { }` | Block without a descriptor | `StageScope.kt:449` |
| `ansiColor(colorMapName) { }` | Block without a descriptor | `StageScope.kt:435` |

The gate that rejects them is at `Main.kt:423` (in-memory) and `Main.kt:827`
(durable); eligibility is computed at `CanonicalStructuralDecisions.kt:154`.
The error message happens to enumerate 11 fixed keys, but **the real rule is
the effective registry**, not that list — so do not memorise the list as if
it were the contract.

Plus the fifth case, which is different in kind: the step-level
`retry(count, delaySeconds)` throws at the call. See
[The two forms of `retry`](#the-two-forms-of-retry--read-this-twice).

## What does not exist

Do not look for these. They are not hidden, not experimental, not
"coming soon" — they are absent:

- `when { }` — not a construct in this DSL. Use `catchError` / `whenGate`,
  not a `when` block.
- `parameters { }` — does not exist.
- `properties { }` — does not exist.
- `triggers { }` — does not exist.
- `agent { }` as a **block** — does not exist. (The `agent`,
  `agentAny` and `agentWithCapabilities` **callables** are declared; see
  Tier 2. That is a different thing from an `agent { }` block.)
- `options { }` with anything beyond `timeout` — does not exist.

## Step registry reference

The engine resolves each construct through an open registry. Registered
today:

| Family | Keys |
|---|---|
| Core handlers (22) | `core.echo`, `core.sh`, `core.error`, `core.sleep`, `core.file.writeFile`, `core.readFile`, `core.fileExists`, `core.archiveArtifacts`, `core.artifact.query`, `core.emit.event`, `core.isUnix`, `core.pwd`, `core.pwd.tmp`, `core.deleteDir`, `core.cleanWs`, `core.milestone`, `core.waitUntil`, `core.stash`, `core.unstash`, `core.publishHTML`, `core.lock`, `core.input` (`CoreStepRegistryFactory.kt:25-199`) |
| Block descriptors (exactly 9) | `core.catchError`, `core.warnError`, `core.withEnv`, `core.dir`, `core.withCredentials`, `core.timeout`, `core.timestamps`, `core.retry`, `core.waitUntil` (`StepDescriptorRegistry.standard()`: `:102,122,147,160,173,186,201,219,239`) |
| Plugin, via `ServiceLoader` | `http.request`, `junit.results`, `scm-git.checkout`, `core-utils.*` (`readJson`, `writeJson`, `sha256`, `readYaml`, `writeYaml`, `findFiles`, `zip`, `unzip`) |

Reading this table tells you something useful: `catchError`, `timeout` and
`retry` are **block descriptors**, which is why they take a body; `echo` and
`sh` are **handlers**, which is why they take arguments.

## Checklist before you run

- [ ] My file uses `pipeline { stages { stage("…") { … } } }` and nothing else at the top level.
- [ ] Every construct I used is in Tier 1 or I accept that I am relying on a Tier 2, unproven construct.
- [ ] I am not using `git(...)`, `load(...)`, `node { }` or `ansiColor { }`.
- [ ] My `retry` has a block.
- [ ] I am not looking for `when { }`, `parameters { }` or `agent { }`.
- [ ] I ran it with `run`, not only with `validate` — `validate` is not a dress rehearsal.

## Authority note

This repository has **no remote CI since 2026-09-30** (`.github/workflows/`
does not exist; commit `754ddda0` removed `lpr0-ci.yml`, `release.yml`,
`v2-baseline.yml` and `sdkman-publish.yml`). Therefore this page never
promises "CI green" and never says "production ready": the PRODUCT-GATE is
`BLOCKED_EXTERNAL`. What it does give you is source citations, and the
behavioural evidence `examples/run.sh` asserts.

## Next

- [`cli-reference.md`](cli-reference.md) — every subcommand, every flag, the
  parser traps and the exit-code table.
- [`README.md`](README.md) — the hub for the whole user documentation.

_This page describes the development branch. The current published release is `0.47.0`;
the difference is recorded in the divergence note at the top._