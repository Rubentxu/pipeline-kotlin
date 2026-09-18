# WU-LPR-032 — Support admission table (closure receipt)

**Status:** `CLOSED — measurement only, no production change`.

**Outcome:** Each public DSL surface, each core `StepKey`, and each
external plugin `StepKey` is classified as
`SUPPORTED` / `EXPERIMENTAL` / `DEFERRED` / `UNSUPPORTED` with the
canonical authority (registry / scripted runtime / fail-closed admission
gate) named explicitly.

---

## 1. Classification rubric

```text
SUPPORTED      Registered Step exists in CoreStepRegistryFactory (or via
               ServiceLoader discovery for externals); durable handler
               proven; runtime events emitted; replay-correct; canonical
               route only.

EXPERIMENTAL   Registered Step exists and the durable spine is the route,
               but the canonical behaviour is fail-closed on certain inputs
               (e.g. tmp=true on pwd returns a deterministic sentinel) or
               the cert is partial (A8 / G8 pending). Admitted but with a
               documented limitation.

DEFERRED       The DSL fun exists; the legacy execution authority has been
               physically removed; the registry path is NOT yet certified
               (or is intentionally absent). The Step is admitted at
               construction (declarative IR) but rejected at runtime by
               the registry admission gate, or routed through a transient
               transitional authority.

UNSUPPORTED    The DSL fun exists; no StepDefinition is registered for the
               key; LEGACY_PLUGIN_IDS is empty for the key. The DSL fun
               lowers to a StepSpec that the engine's fail-closed admission
               gate rejects with `EngineInvariantViolation`. NOT a silent
               no-op.
```

## 2. CLI surface (per WU-LPR-010)

| Command | Status | Notes |
|---------|--------|-------|
| `pipeline run <script>` | **SUPPORTED** | exit 0 success / 1 fail / 2 admission; full event burst |
| `pipeline validate <script>` | **SUPPORTED** (with drift) | exits 0 success / 1 fail; canonical contract says exit 2 on compile (filed F3) |
| `pipeline version` | **DEFERRED** | exits 1 (unknown-subcommand). Re-routed to real handler in WU-LPR-011 |
| `pipeline doctor` | **DEFERRED** | same as version |
| `pipeline events` | **DEFERRED** | not registered as subcommand; renderer exists |
| `pipeline credentials` | **DEFERRED** | not registered as subcommand; typed binding factory exists |
| `pipeline <unknown>` | **SUPPORTED** | exit 1 + usage; consistent failure surface |

## 3. Core Step surface (registry-routed)

All 14 core Steps registered in `CoreStepRegistryFactory` route through
the open `StepRegistry` family. `LEGACY_PLUGIN_IDS = ∅` (WU-LPR-301).

| StepKey | StepDescriptor.effects / replay / recovery | Cert | Status |
|---------|--------------------------------------------|------|--------|
| `core.echo`              | READ_ONLY / MEMOIZED / —                  | S3 G8 CERTIFIED | **SUPPORTED** |
| `core.sh`                | EXECUTES_SUBPROCESS / RERUN / Recoverable | S6 G8 CERTIFIED | **SUPPORTED** |
| `core.error`             | ABORTS_PIPELINE / NEVER / —                | S2-A1 G8 CERTIFIED | **SUPPORTED** |
| `core.sleep`             | READ_ONLY / MEMOIZED / —                   | S2-A2 G8 CERTIFIED | **SUPPORTED** |
| `core.file.writeFile`    | WRITES_WORKSPACE / MEMOIZED / —            | S2-A3 G8 CERTIFIED | **SUPPORTED** |
| `core.emit.event`        | READ_ONLY / MEMOIZED / —                   | S2-A4 G8 CERTIFIED | **SUPPORTED** |
| `core.isUnix`            | READ_ONLY / MEMOIZED / —                   | S2-A5 G8 CERTIFIED | **SUPPORTED** |
| `core.pwd`               | READ_ONLY / MEMOIZED / —                   | S2-A6 G8 CERTIFIED | **SUPPORTED** |
| `core.pwd.tmp`           | WRITES_WORKSPACE / MEMOIZED / —            | S2-A6 G3T CERTIFIED | **EXPERIMENTAL** (deterministic tmp; admitted but with documented dir-creation side effect) |
| `core.deleteDir`         | WRITES_WORKSPACE / MEMOIZED / —            | S2-A7 G8 CERTIFIED | **SUPPORTED** |
| `core.milestone`         | READ_ONLY / MEMOIZED / —                   | S2-A9 G8 CERTIFIED | **SUPPORTED** |
| `core.cleanWs`           | WRITES_WORKSPACE / MEMOIZED / None         | S2-A10 G8 CERTIFIED | **SUPPORTED** |
| `core.archiveArtifacts`  | WRITES_WORKSPACE / MEMOIZED / —            | S2-B10 G8 CERTIFIED | **SUPPORTED** |
| `core.waitUntil`         | READ_ONLY / MEMOIZED / None (Body=Rerty)   | S2-A8 G3R CERTIFIED | **SUPPORTED** |

## 4. DSL fun surface — pipeline / stages / stage block

```text
pipeline { ... }                       SUPPORTED       PipelineScope + DslMarker (WU-LPR-401)
stages { ... }                         SUPPORTED       StagesScope
stage("name") { ... }                  SUPPORTED       StageScope (StepDslMarker)
parallel { ... }                       SUPPORTED       ParallelScope + BranchScope
post { always / success / failure }    SUPPORTED       PostScope + PostStepsScope
options { timeout / retry / skip }     SUPPORTED       OptionsScope (stage-level retry)
environment { ... }                    SUPPORTED       EnvironmentScope (and credentials overload)
agent(label, remoteUri)                SUPPORTED       AgentSpec
whenCondition(expression) { ... }      EXPERIMENTAL    DSL admits the body; runtime ignores the
                                                       expression (no `when` predicate evaluator)
script { line(...) }                   SUPPORTED       ScriptScope → StepSpec.Shell(isScriptBlock=true)
steps(): List<StepSpec>                SUPPORTED       introspection only
```

## 5. DSL fun surface — step-level (registered Steps)

| DSL fun | StepKey | Status | Notes |
|---------|---------|--------|-------|
| `echo(text)`                          | `core.echo`              | **SUPPORTED** | registry route, S3 G8 |
| `sh(cmd)` / `sh(script, isScriptBlock, returnStdout)` | `core.sh` | **SUPPORTED** | registry route, S6 G8, `returnStdout` separates capturedStdout from consoleTranscript |
| `error(message, failureKind)`         | `core.error`             | **SUPPORTED** | registry route, S2-A1 G8 |
| `sleep(seconds)`                      | `core.sleep`             | **SUPPORTED** | registry route, S2-A2 G8 |
| `writeFile(file, text, encoding)`     | `core.file.writeFile`    | **SUPPORTED** | registry route, S2-A3 G8 |
| `readFile(file, encoding)`            | (no key registered)      | **DEFERRED** | DSL exists; no `core.file.readFile` in registry; S2-A3 sibling pending |
| `fileExists(file)`                    | (no key registered)      | **DEFERRED** | same as readFile |
| `pwd()` / `pwd(tmp=true)`             | `core.pwd` / `core.pwd.tmp` | **SUPPORTED** / **EXPERIMENTAL** | WU-LPR-402 runtime-return seam |
| `isUnix()`                            | `core.isUnix`            | **SUPPORTED** | WU-LPR-402 runtime-return seam |
| `milestone(ordinal, label)`           | `core.milestone`         | **SUPPORTED** | registry route, S2-A9 G8 |
| `cleanWs(deleteDirs, patterns)`       | `core.cleanWs`           | **SUPPORTED** | registry route, S2-A10 G8 |
| `deleteDir(path)`                     | `core.deleteDir`         | **SUPPORTED** | registry route, S2-A7 G8 |
| `archiveArtifacts(artifacts, ...)`    | `core.archiveArtifacts`  | **SUPPORTED** | registry route, S2-B10 G8 |
| `waitUntil { ... }`                   | `core.waitUntil`         | **SUPPORTED** | registry route, S2-A8 G3R; `initialRecurrencePeriod` + `quiet` honoured; body re-entry through canonical `dispatchRepeatUntilBody` |
| `dir(path) { ... }`                   | (Body Block, internal)   | **SUPPORTED** | canonical BlockStepNode; runtime contract honoured (DirEntered/DirExited events) |
| `withEnv(overrides) { ... }`          | (Body Block, internal)   | **SUPPORTED** | canonical BlockStepNode; PATH prepend + VAR=value both work |
| `withCredentials(bindings) { ... }`   | (Body Block, internal)   | **SUPPORTED** | typed `CredentialBindingSpec` conversion; 7 binding kinds |
| `timestamps { ... }`                  | (Body Block, internal)   | **EXPERIMENTAL** | DSL admits; canonical BlockStepNode; **pure log-rewriter semantics are documented but no Events emitted yet (D5 marker reuse)** |
| `ansiColor(name) { ... }`             | (Body Block, internal)   | **EXPERIMENTAL** | same as timestamps |
| `node(label) { ... }`                 | (Body Block, internal)   | **SUPPORTED** | local-only no-op; emits `AgentResolved` |
| `timeout(time, unit, activity) { }`   | (Body Block, internal)   | **EXPERIMENTAL** | DSL admits; canonical BlockStepNode; `TimeoutTriggered` is emitted on expiry, but `unit` parsing is limited (SECONDS / MINUTES only — pre-D2 limitation) |
| `retry(count, conditions) { ... }`    | (Body Block, internal)   | **SUPPORTED** | canonical BlockStepNode; durable control row + supersede-skip per ADR-0075 (RETRY-D) |
| `parallel { branch("n") { ... } }`    | (Body Block, internal)   | **SUPPORTED** | canonical BlockStepNode + BranchInvoker; emits `ParallelBranchStarted/Finished` |
| `catchError(...) { ... }`             | (legacy data class)      | **DEFERRED** | `@Deprecated` for DSL use; pre-compiler-rewritten; StepSpec.CatchError retained for legacy fixtures only |
| `warnError(...) { ... }`              | (legacy data class)      | **DEFERRED** | same as catchError |
| `unstable(message)`                   | (legacy data class)      | **DEFERRED** | same as catchError |
| `load(path)`                          | `core.load`              | **UNSUPPORTED** | WU-LPR-301 / G5; registry admission fails closed with `EngineInvariantViolation` |
| `registryStep(stepKey, encodedInput, schemaVersion)` | any | **SUPPORTED** | generic plugin primitive (LB-02 / EP-F2.6); schemaVersion MUST stay `dsl-v1` |

## 6. External plugin surface

| Plugin | Key | Status | Notes |
|--------|-----|--------|-------|
| `examples/example-uppercase-plugin` | `example.uppercase` | **SUPPORTED** | ServiceLoader-discovered; CERTIFIED per `UppercaseStepContractSuiteTest`; available only when JAR is on classpath |
| any unregistered external key | `<plugin>.<step>` | **UNSUPPORTED** | registry admission gate fails closed |

## 7. Closed-set counters (frozen by WU-LPR-301)

```text
LEGACY_PLUGIN_IDS.size():           0
CanonicalCoreStepMetadata rows:     0
CanonicalCoreStepCommand subtypes: 13        (closed IR; Load + WaitUntil removed)
Legacy decoder branches:            0
Legacy dispatcher files:            0        (CanonicalNodeDispatcher.kt is a stub for binary compat)
CanonicalCoreStepDecoder fallback:  EngineInvariantViolation on unknown legacy envelope
```

## 8. Findings for follow-up

| ID | Finding | Suggested follow-up |
|----|---------|---------------------|
| F7 | `readFile` / `fileExists` are in DSL but have no `core.file.readFile` StepKey registered; reading would fail-closed at the admission gate | WU-LPR-202 / S2-A3b |
| F8 | `timestamps` / `ansiColor` are EXPERIMENTAL because their marker-event re-emission is not implemented | WU-LPR-203 |
| F9 | `timeout`'s `unit` parser only accepts SECONDS / MINUTES (D2 deferred) | WU-LPR-204 |
| F10 | `whenCondition(expression)` body is admitted but the expression is never evaluated at runtime | WU-LPR-205 |
| F11 | `pwd(tmp=true)` deterministically creates a directory; admitted but with a side-effect the README should highlight | WU-LPR-206 |
| F12 | `archiveArtifacts` `excludes` and `onlyIfSuccessful` parameters are F2-deferred | WU-LPR-207 |
| F13 | `withEnv` PATH prepend (`PATH+X=/dir`) syntax is documented but the prepend-vs-replace logic is not exhaustively tested | WU-LPR-208 |

## 9. Auto-continue

This WU is documentation-only. Auto-continue to the next WU in the
LPR-401/402/010/032 train (per the human's `WU-LPR-401 → 402 → 010 →
032 → checkpoint` ordering, the next step is the cumulative checkpoint).

---

**CLOSED — 2026-09-18.**
