# DSL Surface Manifest v1 (S0 Semantic Honesty Gate)

Status: ACTIVE - governed by the Semantic Conservation Law.
Work item: e0d18fab-0059-4114-b491-936960594493 (TRAIN e001988a, cycle p-733fb505b5a6bd2d/train-dsl-honesty).
Machine-check: `FArchS0SurfaceManifestTest` (pipeline-architecture-tests).

This manifest is the closed inventory of the Pipeline-K declarative DSL surface. Every
construct lives in EXACTLY ONE category and carries EXACTLY ONE state. There is no
UNKNOWN bucket: an unlisted construct makes the machine-check fail, which is the point.

Categories (closed set):
- DECLARATIVE_DIRECTIVE - lowers into IR structure consumed by the canonical coordinator.
- ATOMIC_STEP - one OpaqueStepNode with a registered runtime handler.
- BLOCK_STEP - one BlockStepNode whose body the canonical body engine owns.
- PURE_BUILDER - constructs a typed value at script-construction time; zero run effects.
- SCRIPTED_RUNTIME_CALL - only meaningful inside a scripted runtime context; the DSL
  builder inserts a registry step and returns a placeholder (reading the synchronous
  return outside a runtime context is a documented contract violation, not silent).
- UNSUPPORTED_FAIL_CLOSED - accepted-syntax stub that ALWAYS throws with a diagnostic;
  the construct exists only so a rejected call names itself instead of failing with a
  bare Kotlin signature error.

States (closed set): STABLE, PARTIAL, EXPERIMENTAL, DEPRECATED, UNSUPPORTED_FAIL_CLOSED.

The machine-check enforces, against live code (not this file alone):
1. This file lists every public DSL builder on StageScope/StageScopeTopSteps/StageScopeCore
   and every StepSpec subtype (reflection over the compiled modules).
2. No StepSpec subtype lowering is ambiguous: `DslCompiledPipelineCompiler` maps each
   body-bearing subtype to exactly one `core.*` plugin key (exhaustive when-branches,
   verified by Lfc2BlockStepCompilerBodyExhaustivenessFitnessTest).
3. Every BLOCK_STEP key has a declared body row in StepDescriptorRegistry.standard()
   owned by the canonical body engine (the CompiledPipelineValidator fails compile
   otherwise; this test pins the same truth from the manifest side).
4. Every ATOMIC_STEP key resolves to a registered StepDefinition (CoreStepRegistryFactory
   or an external plugin contributor) or to a runtime adapter; keys listed
   UNSUPPORTED_FAIL_CLOSED are the only exception.
5. Every entry in the FAIL_CLOSED_STUBS table resolves to a `Nothing`-returning or
   throwing method on the live DSL class (a stub that stopped throwing silently is a
   manifest lie and fails the test).
6. No manifest entry may carry a state outside the closed set, a category outside the
   closed set, or an empty category/state field.

## 1. Pipeline skeleton

| Construct | Signature | Category | State | Lowers to / Interpreter |
|---|---|---|---|---|
| pipeline | `pipeline { }` (typed spec form) | PURE_BUILDER | STABLE | PipelineSpec consumed by DslCompiledPipelineCompiler |
| stages | `stages { }` | PURE_BUILDER | STABLE | List<StageSpec> |
| stage | `stage(name) { }` | PURE_BUILDER | STABLE | StageSpec -> StageNode |
| environment | `environment { env(k,v) }` (pipeline+stage) | DECLARATIVE_DIRECTIVE | STABLE | EnvironmentSpec; propagation WITNESSED at shell env (S0-B) |
| options | `options { timeout(seconds) }` | DECLARATIVE_DIRECTIVE | STABLE | OptionSpec("timeout"); StageTimeoutProjection; shell timeoutMs |
| post | `post { always/success/failure { } }` | UNSUPPORTED_FAIL_CLOSED | UNSUPPORTED_FAIL_CLOSED | IllegalStateException at scope close (toStageBuilder) |

## 2. Step builders (StageScope surface)

| Construct | Signature | Category | State | Lowers to / Interpreter |
|---|---|---|---|---|
| echo | `echo(text)` | ATOMIC_STEP | STABLE | StepSpec.Echo -> core.echo -> CoreEchoStep |
| sh | `sh(command)` / `sh(script, isScriptBlock, returnStdout)` | ATOMIC_STEP | STABLE | StepSpec.Shell -> core.sh -> CoreShellStep |
| error | `error(message, failureKind)` | ATOMIC_STEP | STABLE | StepSpec.Error -> core.error -> CoreErrorStep |
| sleep | `sleep(seconds)` | ATOMIC_STEP | STABLE | StepSpec.Sleep -> core.sleep -> CoreSleepStep |
| writeFile | `writeFile(file, text, encoding)` | ATOMIC_STEP | STABLE | StepSpec.WriteFile -> core.file.writeFile -> CoreWriteFileStep |
| readFile | `readFile(file, encoding)` | ATOMIC_STEP | STABLE | StepSpec.ReadFile -> core.readFile -> CoreReadFileStep |
| fileExists | `fileExists(file)` | ATOMIC_STEP | STABLE | StepSpec.FileExists -> core.fileExists -> CoreFileExistsStep |
| deleteDir | `deleteDir(path)` | ATOMIC_STEP | STABLE | StepSpec.DeleteDir -> core.deleteDir -> CoreDeleteDirStep |
| cleanWs | `cleanWs(deleteDirs, patterns)` | ATOMIC_STEP | STABLE | RegistryStepSpec core.cleanWs -> CoreCleanWsStep |
| checkout | `checkout(scm)` | ATOMIC_STEP | STABLE | StepSpec.Checkout -> scm-git.checkout -> GitCheckoutStep (plugin) |
| scmGit | `scmGit(url, branch, ...)` | PURE_BUILDER | STABLE | Returns CheckoutSpec (0 effects); consumed by checkout |
| git | `git(url, branch, ...)` | ATOMIC_STEP | STABLE | = checkout(scmGit(...)) -> exactly 1 scm-git.checkout (S0-B witness) |
| archiveArtifacts | `archiveArtifacts(artifacts, ...)` | ATOMIC_STEP | STABLE | RegistryStepSpec core.archiveArtifacts -> CoreArchiveArtifactsStep |
| artifactQuery | `artifactQuery(name)` | ATOMIC_STEP | STABLE | RegistryStepSpec core.artifact.query -> CoreArtifactQueryStep |
| milestone | `milestone(ordinal, label?)` | ATOMIC_STEP | STABLE | RegistryStepSpec core.milestone -> CoreMilestoneStep |
| stash | `stash(name, includes, excludes)` | ATOMIC_STEP | STABLE | RegistryStepSpec core.stash -> CoreStashStep |
| unstash | `unstash(name, into?)` | ATOMIC_STEP | STABLE | RegistryStepSpec core.unstash -> CoreUnstashStep; blank `into` rejected by typed contract |
| publishHTML | `publishHTML(name, reportDir, ...)` | ATOMIC_STEP | PARTIAL | RegistryStepSpec core.publishHTML -> CorePublishHtmlStep; keepAll=true FAILS CLOSED at the adapter |
| registryStep | `registryStep(stepKey, encodedInput)` | ATOMIC_STEP | EXPERIMENTAL | open-world registry path (LB-02) |
| registryBlock | `registryBlock(stepKey, encodedInput) { }` | BLOCK_STEP | EXPERIMENTAL | open-world registry block path; body policy from plugin descriptor |
| load | `load(path)` | ATOMIC_STEP | UNSUPPORTED_FAIL_CLOSED | emits canonical envelope; core.load has NO handler; admission rejects |

## 3. Block builders (body owned by canonical body engine)

| Construct | Signature | Category | State | Lowers to / Interpreter |
|---|---|---|---|---|
| dir | `dir(path) { }` | BLOCK_STEP | STABLE | StepSpec.Dir -> core.dir -> canonical body engine (WorkingDirectory scope) |
| withEnv | `withEnv(overrides) { }` | BLOCK_STEP | STABLE | StepSpec.WithEnv -> core.withEnv -> canonical body engine (Scoped env) |
| withCredentials | `withCredentials(binding(s)) { }` | BLOCK_STEP | STABLE | StepSpec.WithCredentialsBlock -> core.withCredentials -> canonical body engine |
| timestamps | `timestamps { }` | BLOCK_STEP | STABLE | StepSpec.Timestamps -> core.timestamps -> canonical body engine (output decorator) |
| timeout | `timeout(time, unit) { }` | BLOCK_STEP | STABLE | StepSpec.TimeoutBlock -> core.timeout -> coordinator TIMEOUT projection (TimeoutScheduled/TimeoutFired) |
| retry | `retry(count) { }` | BLOCK_STEP | STABLE | StepSpec.RetryBlock -> core.retry -> coordinator retry (maxAttempts ONLY; RetryAttempted events) |
| waitUntil | `waitUntil(period, quiet) { }` | BLOCK_STEP | STABLE | StepSpec.WaitUntilBlock -> core.waitUntil -> Retrying(waitUntil) poll loop |
| parallel | `parallel { branch(a){} branch(b){} }` | DECLARATIVE_DIRECTIVE | STABLE | StepSpec.Parallel; canonical stage form = single Parallel root; sibling-mixed body is NON-CANONICAL by design and rejected by the durable gate |
| catchError | `catchError(buildResult?, stageResult?, message?) { }` | DECLARATIVE_DIRECTIVE | STABLE | legacy workflow-control rewrite (CatchErrorEntered/Triggered + folded outcome) |
| warnError | `warnError(message) { }` | DECLARATIVE_DIRECTIVE | STABLE | catchError(buildResult=UNSTABLE, stageResult=UNSTABLE) + StageMarkedUnstable |
| unstable | `unstable(message)` | DECLARATIVE_DIRECTIVE | STABLE | lifted marker consumed by enclosing catchError/warnError rewrite |
| node | `node(label?) { }` | BLOCK_STEP | UNSUPPORTED_FAIL_CLOSED | lowers to core.node; NO descriptor row; compile/validate fails closed (CompiledPipelineValidator) |
| ansiColor | `ansiColor(colorMapName) { }` | BLOCK_STEP | UNSUPPORTED_FAIL_CLOSED | lowers to core.ansiColor; NO descriptor row; canonical bridge rejects (exit 2, CliNonCanonicalInMemoryExitsTwoTest) |
| script | `script { }` | PURE_BUILDER | DEPRECATED | joins commands into ONE StepSpec.Shell(isScriptBlock=true); use sh() directly |

## 4. Scripted-runtime builders (placeholder returns)

| Construct | Signature | Category | State | Interpreter |
|---|---|---|---|---|
| pwd | `pwd(tmp=false): String` | SCRIPTED_RUNTIME_CALL | STABLE | registry core.pwd / core.pwd.tmp; returns RUNTIME_VALUE_PLACEHOLDER in DSL form; real value via CorePwdStep/CorePwdTmpStep in runtime context |
| isUnix | `isUnix(): Boolean` | SCRIPTED_RUNTIME_CALL | STABLE | registry core.isUnix; placeholder in DSL form; CoreIsUnixStep in runtime context (LFC-2R matrix) |

## 5. Fail-closed stubs (removed constructs that still answer)

| Construct | Signature | Category | State | Diagnostic anchors |
|---|---|---|---|---|
| agent | `agent(label, remoteUri?): Nothing` (stage level) | UNSUPPORTED_FAIL_CLOSED | UNSUPPORTED_FAIL_CLOSED | "agent", "no runtime component ever read it" |
| retry (retrofit) | `retry(count, delaySeconds?): Nothing` (step level) | UNSUPPORTED_FAIL_CLOSED | UNSUPPORTED_FAIL_CLOSED | "removed", "consumer", points at block form |
| whenCondition | `whenCondition(expression) { }` | UNSUPPORTED_FAIL_CLOSED | UNSUPPORTED_FAIL_CLOSED | "not supported", "conditional step" |
| retry conditions | overload `retry(n, conditions) { }` REMOVED from surface | UNSUPPORTED_FAIL_CLOSED | UNSUPPORTED_FAIL_CLOSED | plain Kotlin signature error; reflection pins no List-taking overload |

## 6. Closed sets (machine-check targets)

- StepSpec subtypes (33 after S0 block A): the sealed family in
  `pipeline-scripting-api/.../dsl/StepSpec.kt`. The manifest must cover every subtype's
  producing builder. Subtypes without a DSL producer are internal IR detail
  (RegistryStepSpec/RegistryBlockSpec are produced by registryStep/registryBlock and by
  stash/unstash/publishHTML/cleanWs/milestone/artifactQuery encoders).
- Registry body rows (9): core.catchError, core.warnError (LEGACY_LINEAR);
  core.withEnv, core.dir, core.withCredentials, core.timeout, core.timestamps,
  core.retry, core.waitUntil (CANONICAL_ENGINE).
- Registry terminal rows (5): core.emit.event, core.sh, core.echo, core.sleep,
  core.file.writeFile.
- Core step handlers registered (20): see CoreStepRegistryFactory.
- External plugin steps: scm-git.checkout, junit.results, core-utils.{readJson,
  writeJson, sha256, readYaml, writeYaml, findFiles, zip, unzip}.

## 7. Conservation invariants (restated for the checker)

I1: every construct appears in EXACTLY ONE row (name+category unique).
I2: category and state are from the closed sets above.
I3: no UNKNOWN category/state anywhere in this file.
I4: STABLE constructs must have a named live interpreter (registry key, coordinator
    projection or rewrite pass). A STABLE row whose interpreter is deleted fails the test.
I5: UNSUPPORTED_FAIL_CLOSED stubs must throw at call time (reflection check).
I6: adding a DSL builder or StepSpec subtype without a manifest row fails the test
    (surface grows only through this manifest).



