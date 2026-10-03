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

| Construct | Signature | Category | State | ResultConsumption | Lowers to / Interpreter |
|---|---|---|---|---|---|
| pipeline | `pipeline { }` (typed spec form) | PURE_BUILDER | STABLE | NOT_APPLICABLE | PipelineSpec consumed by DslCompiledPipelineCompiler |
| stages | `stages { }` | PURE_BUILDER | STABLE | NOT_APPLICABLE | List<StageSpec> |
| stage | `stage(name) { }` | PURE_BUILDER | STABLE | NOT_APPLICABLE | StageSpec -> StageNode |
| environment | `environment { env(k,v) }` (STAGE level only) | DECLARATIVE_DIRECTIVE | STABLE | NOT_APPLICABLE | S3.2: `EnvironmentSpec` (immutable `Map<String,String>`, blank keys rejected) on `StageSpec` -> compiler `DslCompiledPipelineCompiler` -> `StageNode.environment` -> `StageNode.projectShellOptions` -> `ShOptions.env` -> materialised ONLY at the `pb.environment().putAll` choke in `DurableShellExecutor.launch`. The patch has TWO independent consumers: the child process env, and `gateContext` (so `whenEnvIs`/`whenEnvPresent` in the same stage gate on the declared value). CERTIFIED by `S3EnvironmentSemanticWitnessTest`: last-declaration-wins precedence, siblings isolated, and a stage that declares nothing does NOT observe the previous stage's value — the witness that fails if the patch is ever made ambient. Mutation M-s3-2 made the environment a process-wide accumulator and turned the isolation and sibling witnesses RED; reverted, never committed. This row previously claimed "(pipeline+stage)": `PipelineScope` exposes only `stages { }`, so the pipeline half never had a builder and the claim is CORRECTED here. The value does not appear in the event timeline (witnessed). `EnvironmentSpec` carries PLAINTEXT by design; secret-bearing environment is `withCredentials`/`CredentialScope` (RP7-ASX), not this construct. |
| options | `options { timeout(seconds) }` | DECLARATIVE_DIRECTIVE | STABLE | NOT_APPLICABLE | OptionSpec("timeout"); StageTimeoutProjection; shell timeoutMs. S0-B: distinct from the `timeout()` BLOCK. `options.timeout` is a stage-wide SHELL DEADLINE only — it produces NO `TimeoutScheduled`/`TimeoutTriggered`; the breach surfaces as the governed step's own `StepFailed(failureKind=TIMEOUT)`. |
| directives | `directives { directive(key, encodedArgs) }` | DECLARATIVE_DIRECTIVE | EXPERIMENTAL | NOT_APPLICABLE | S1-B: StageDirective(key + opaque encoded args) on StageSpec -> StageNode.directives; canonical coordinator ADMITS each against the open DirectiveRegistry BEFORE the stage starts; unresolved key = typed USER failure, stage never starts (fail-closed). The DSL performs NO registry lookup and NO decoding: the definition that owns the key owns its codec. S2-D: the BEFORE_STAGE seam decodes EVERY admitted directive in declaration order via decodeAny and classifies it as DecodedBeforeStage { GatePredicate, Evaluated, Denied }; policy Evaluate now decodes fail-closed (undecodable args = DirectiveDenied + USER, no StageStarted) instead of being admitted unchecked; policy ProvideContext is admitted by the planner and, if it ever reaches the BEFORE_STAGE decode seam, is DENIED with a diagnostic ("not interpretable in the BEFORE_STAGE decode seam") rather than continuing silently — no production DirectiveDefinition declares it, so it is a reserved policy, not a construct with implemented semantics. |
| post | `post { always/success/failure/unstable/aborted/unsuccessful/cleanup { } }` | DECLARATIVE_DIRECTIVE | STABLE | NOT_APPLICABLE | PostConditionSpec on StageSpec -> PostSpec IR (typed StepNodes) -> PostPlanner selection by StageOutcome; coordinator dispatches finalizers through the canonical spine with `post:<CONDITION>` body paths, emits PostConditionSelected BEFORE StageFinished (S2-B). Unknown outcome fails CLOSED (no finalizer). |
| agent | `agent(label?, remoteUri?)` (stage level) | DECLARATIVE_DIRECTIVE | STABLE | NOT_APPLICABLE | S3.1: StageDirective(core.agent) -> DirectiveExecutionPolicy.Resource -> ExecutionTargetResolver, all at `DirectivePhase.BEFORE_STAGE`. The carrier is the `ExecutionTargetRequirement` ADT (LocalAny / LocalLabels / CapabilitySet / Remote), not a label String, and the engine reads the POLICY, never the key. Grants emit `ExecutionTargetResolved` (carrying the declared requirement AND the granted target, so an observer can tell a satisfied requirement from an absent one). `LocalLabels` is executable on the single-host profile: labels are checked against what the host actually advertises, derived from the `RuntimeConfig` port at acquire time (plus `unix` off Windows), so `agent(label = "linux")` succeeds on Linux and is REFUSED on Windows. The resolver asks the port rather than reading the host, so `System.getenv`/`System.getProperty` remain confined to the `SystemRuntimeConfig` adapter that `FArchM1CanonicalRuntimeConfigTest` recognises as their only home, and `agent` and `core.isUnix` cannot disagree about which machine the run is on. `Remote` is CARRIED AND DECODED but REFUSED at resolution: there is no remote allocator until RP-8, and running the stage on the local host while ignoring the selector would be exactly the silent semantic drop this manifest exists to prevent. Supplying both `label` and `remoteUri` is a contradiction and is rejected in the DSL, as is supplying neither. This construct was `UNSUPPORTED_FAIL_CLOSED` from S0 until S3.1 gave it a carrier, a resolver and an interpreter. |
| agentAny | `agentAny()` (stage level) | DECLARATIVE_DIRECTIVE | STABLE | NOT_APPLICABLE | S3.1: the explicit form of "any local target". Carries `ExecutionTargetRequirement.LocalAny`, which is a DIFFERENT encoding from an absent `agent`, so an observer can tell a deliberate unconstrained request from a stage that never mentioned an agent. Resolved by the same `ExecutionTargetResolver` seam and always granted on the single-host profile. Separate from `agent(label)` because an empty label set is not the same claim as no constraint, and collapsing them would make a stricter request indistinguishable from a looser one. |
| agentWithCapabilities | `agentWithCapabilities(vararg capabilities)` (stage level) | DECLARATIVE_DIRECTIVE | STABLE | NOT_APPLICABLE | S3.1: carries `ExecutionTargetRequirement.CapabilitySet` over the existing `StepCapability` keys, so one capability vocabulary serves both what a Step declares and what a target must provide. Checked against capabilities the run was actually GRANTED, never against what the runtime could provide in principle. An empty argument list is rejected in the DSL rather than encoded, because an empty set is indistinguishable from `agentAny()`. On the current composition the granted set is empty, so this refuses fail-closed with a diagnostic — see the `targetResolver` default on `BeforeStageDirectiveEngine`, which deliberately lives on the consuming engine rather than on the coordinator. |

## 2. Step builders (StageScope surface)

| Construct | Signature | Category | State | ResultConsumption | Lowers to / Interpreter |
|---|---|---|---|---|---|
| whenGate | `whenGate(predicate)` | DECLARATIVE_DIRECTIVE | STABLE | NOT_APPLICABLE | StageDirective(core.when) -> DirectiveExecutionPolicy.Gate -> GateEvaluator (BEFORE_STAGE) |
| whenEnvIs | `whenEnvIs(variable, expected)` | DECLARATIVE_DIRECTIVE | STABLE | NOT_APPLICABLE | whenGate(VariableEquals) |
| whenEnvPresent | `whenEnvPresent(variable)` | DECLARATIVE_DIRECTIVE | STABLE | NOT_APPLICABLE | whenGate(VariablePresent) |
| echo | `echo(text)` | ATOMIC_STEP | STABLE | NOT_APPLICABLE | StepSpec.Echo -> core.echo -> CoreEchoStep |
| sh | `sh(command)` / `sh(script, isScriptBlock, returnStdout)` | ATOMIC_STEP | STABLE | NOT_APPLICABLE | StepSpec.Shell -> core.sh -> CoreShellStep |
| error | `error(message, failureKind)` | ATOMIC_STEP | STABLE | NOT_APPLICABLE | StepSpec.Error -> core.error -> CoreErrorStep |
| sleep | `sleep(seconds)` | ATOMIC_STEP | STABLE | NOT_APPLICABLE | StepSpec.Sleep -> core.sleep -> CoreSleepStep |
| writeFile | `writeFile(file, text, encoding)` | ATOMIC_STEP | STABLE | NOT_APPLICABLE | StepSpec.WriteFile -> core.file.writeFile -> CoreWriteFileStep |
| readFile | `readFile(file, encoding)` | ATOMIC_STEP | STABLE | NOT_APPLICABLE | StepSpec.ReadFile -> core.readFile -> CoreReadFileStep |
| fileExists | `fileExists(file)` | ATOMIC_STEP | STABLE | NOT_APPLICABLE | StepSpec.FileExists -> core.fileExists -> CoreFileExistsStep |
| deleteDir | `deleteDir(path)` | ATOMIC_STEP | STABLE | NOT_APPLICABLE | StepSpec.DeleteDir -> core.deleteDir -> CoreDeleteDirStep |
| cleanWs | `cleanWs(deleteDirs, patterns)` | ATOMIC_STEP | STABLE | NOT_APPLICABLE | RegistryStepSpec core.cleanWs -> CoreCleanWsStep |
| httpRequest | `httpRequest(url, method?, customHeaders?, body?, contentType?, acceptType?, validResponseCodes?, timeoutSeconds?, authentication?)` | ATOMIC_STEP | EXPERIMENTAL | NOT_APPLICABLE | **OFFICIAL_PLUGIN `http.request`**, NOT a core Step. The builder is an extension contributed by `pipeline-step-sdk:http` and lowers EXCLUSIVELY to the generic `registryStep(...)`; `DslCompiledPipelineCompiler` has no branch for it and `StepSpec` has no variant for it (STEP_ECOSYSTEM_POLICY: `pipeline-plugin-http`; matrix: `httpRequest | OFFICIAL_PLUGIN candidate`). Required capabilities: `http.transport` (the plugin's own JDK transport) and the generic `network.egress`, which the runtime grants ONLY under `--allow-network`, so the Step fails closed at admission by default. `method` and `validResponseCodes` are TYPED (`HttpMethod`, `StatusRange`), not strings: Jenkins parses `validResponseCodes` AFTER the request has already been sent (`HttpRequest.java:552-589`). `ReplayPolicy.NEVER`; the retry is the author's `retry` block. `sslVerify`, `failOnStatusCode`, `customBands`, `responseCode`, `retry` and `retryableStatusCodes` DO NOT EXIST — that parameter list belongs to a surface extracted to its own plugin. See `docs/v2/07-uat/WU093_HTTP_DELIVERY_RECONCILIATION.md` |
| checkout | `checkout(scm)` | ATOMIC_STEP | PARTIAL | NOT_APPLICABLE | StepSpec.Checkout -> OpaqueStepNode(pluginStepId=`core.checkout`); the scm-git plugin registers `scm-git.checkout`, NOT `core.checkout` (S0-B) |
| scmGit | `scmGit(url, branch, ...)` | PURE_BUILDER | STABLE | MUST_CONSUME | Returns CheckoutSpec (0 effects); consumed by checkout. MUST_CONSUME applies to a PURE_BUILDER that hands out a config carrier which NOTHING consumes on its own: discarding it loses the author's intent entirely. It does NOT apply to SCRIPTED_RUNTIME_CALL builders, whose call already emits a step, nor to builders returning Unit or Nothing |
| git | `git(url, branch, ...)` | ATOMIC_STEP | UNSUPPORTED_FAIL_CLOSED | NOT_APPLICABLE | S0-B: rejects fail-closed with exit 2 ("non-canonical plugins" naming `core.checkout`). Zero checkouts occur. |
| archiveArtifacts | `archiveArtifacts(artifacts, ...)` | ATOMIC_STEP | STABLE | NOT_APPLICABLE | RegistryStepSpec core.archiveArtifacts -> CoreArchiveArtifactsStep |
| artifactQuery | `artifactQuery(name)` | ATOMIC_STEP | STABLE | NOT_APPLICABLE | RegistryStepSpec core.artifact.query -> CoreArtifactQueryStep |
| milestone | `milestone(ordinal, label?)` | ATOMIC_STEP | STABLE | NOT_APPLICABLE | RegistryStepSpec core.milestone -> CoreMilestoneStep |
| stash | `stash(name, includes, excludes)` | ATOMIC_STEP | STABLE | NOT_APPLICABLE | RegistryStepSpec core.stash -> CoreStashStep |
| unstash | `unstash(name, into?)` | ATOMIC_STEP | STABLE | NOT_APPLICABLE | RegistryStepSpec core.unstash -> CoreUnstashStep; blank `into` rejected by typed contract |
| publishHTML | `publishHTML(name, reportDir, ...)` | ATOMIC_STEP | PARTIAL | NOT_APPLICABLE | RegistryStepSpec core.publishHTML -> CorePublishHtmlStep; keepAll=true FAILS CLOSED at the adapter |
| registryStep | `registryStep(stepKey, encodedInput)` | ATOMIC_STEP | EXPERIMENTAL | NOT_APPLICABLE | open-world registry path (LB-02) |
| registryBlock | `registryBlock(stepKey, encodedInput) { }` | BLOCK_STEP | EXPERIMENTAL | NOT_APPLICABLE | open-world registry block path; body policy from plugin descriptor |
| load | `load(path)` | ATOMIC_STEP | UNSUPPORTED_FAIL_CLOSED | NOT_APPLICABLE | emits canonical envelope; core.load has NO handler; admission rejects |

## 3. Block builders (body owned by canonical body engine)

| Construct | Signature | Category | State | ResultConsumption | Lowers to / Interpreter |
|---|---|---|---|---|---|
| dir | `dir(path) { }` | BLOCK_STEP | STABLE | NOT_APPLICABLE | StepSpec.Dir -> core.dir -> canonical body engine (WorkingDirectory scope) |
| withEnv | `withEnv(overrides) { }` | BLOCK_STEP | STABLE | NOT_APPLICABLE | StepSpec.WithEnv -> core.withEnv -> canonical body engine (Scoped env) |
| withCredentials | `withCredentials(binding(s)) { }` | BLOCK_STEP | STABLE | NOT_APPLICABLE | StepSpec.WithCredentialsBlock -> core.withCredentials -> canonical body engine |
| timestamps | `timestamps { }` | BLOCK_STEP | STABLE | NOT_APPLICABLE | StepSpec.Timestamps -> core.timestamps -> canonical body engine (output decorator) |
| timeout | `timeout(time, unit) { }` | BLOCK_STEP | STABLE | NOT_APPLICABLE | StepSpec.TimeoutBlock -> core.timeout -> coordinator TIMEOUT projection. Block authority = `TimeoutScheduled` at admission + `TimeoutTriggered` at breach; the deadline is enforced by the child shell watchdog, so the child also emits `StepFailed(failureKind=TIMEOUT)`. S0-B corrected the row: `TimeoutFired` never existed. |
| retry | `retry(count) { }` | BLOCK_STEP | STABLE | NOT_APPLICABLE | StepSpec.RetryBlock -> core.retry -> coordinator retry (maxAttempts ONLY; `RetryAttemptStarted` / `RetryAttemptFinished`). S0-B corrected the row: `RetryAttempted` never existed. |
| waitUntil | `waitUntil(period, quiet) { }` | BLOCK_STEP | STABLE | NOT_APPLICABLE | StepSpec.WaitUntilBlock -> core.waitUntil -> Retrying(waitUntil) poll loop |
| lock | `lock(resource, timeoutSeconds?, reason?, skipIfLocked?) { }` | BLOCK_STEP | STABLE | NOT_APPLICABLE | StepSpec.Lock -> core.lock -> HANDLER_CONTINUATION body via LockCoordinator (POSIX file locks, owner = ExecutionLaneId). Events: `LockRequested` / `LockAcquired` / `LockReleased` / `LockSkipped` / `LockAcquireFailed`. Wire payload authored ONLY by CoreLockWireCodec (WU-091 G3.4); Jenkins `timeoutUnit`/`label`/`quantity`/`variable` deliberately absent (RP-8 / context coupling) |
| input | `input(message, ok?, submitter?, id?, timeoutSeconds?) { }` | BLOCK_STEP | STABLE | NOT_APPLICABLE | StepSpec.Input -> core.input -> HANDLER_CONTINUATION body via InputDecisions (file answer in the control dir, owner = runId#stepIndex). Body runs ONLY on Proceed. Events: `InputRequested` / `InputProceed` / `InputAborted` / `InputDenied`. Wire payload authored ONLY by CoreInputWireCodec; `submitter` is ATTRIBUTION, not authorization (no user database in a headless runner); `withId` and the Jenkins permission parameters are deliberately absent |
| parallel | `parallel { branch(a){} branch(b){} }` | DECLARATIVE_DIRECTIVE | STABLE | NOT_APPLICABLE | StepSpec.Parallel; canonical stage form = single Parallel root; sibling-mixed body is NON-CANONICAL by design and rejected by the durable gate |
| catchError | `catchError(buildResult?, stageResult?, message?) { }` | DECLARATIVE_DIRECTIVE | STABLE | NOT_APPLICABLE | legacy workflow-control rewrite. Authority event: `CatchErrorTriggered` only. S0-B corrected the row: `CatchErrorEntered` never existed. Contained failure does NOT fail the run: CLI exit 0, `RunFinished.outcome=unstable` (ADR-0054 projection), and steps after the block still run. |
| warnError | `warnError(message) { }` | DECLARATIVE_DIRECTIVE | STABLE | NOT_APPLICABLE | catchError(buildResult=UNSTABLE, stageResult=UNSTABLE) + StageMarkedUnstable |
| unstable | `unstable(message)` | DECLARATIVE_DIRECTIVE | STABLE | NOT_APPLICABLE | lifted marker consumed by enclosing catchError/warnError rewrite |
| node | `node(label?) { }` | BLOCK_STEP | UNSUPPORTED_FAIL_CLOSED | NOT_APPLICABLE | lowers to core.node; NO descriptor row; compile/validate fails closed (CompiledPipelineValidator) |
| ansiColor | `ansiColor(colorMapName) { }` | BLOCK_STEP | UNSUPPORTED_FAIL_CLOSED | NOT_APPLICABLE | lowers to core.ansiColor; NO descriptor row; canonical bridge rejects (exit 2, CliNonCanonicalInMemoryExitsTwoTest) |
| script | `script { }` | PURE_BUILDER | DEPRECATED | NOT_APPLICABLE | joins commands into ONE StepSpec.Shell(isScriptBlock=true); use sh() directly |

## 4. Scripted-runtime builders (placeholder returns)

| Construct | Signature | Category | State | ResultConsumption | Lowers to / Interpreter |
|---|---|---|---|---|---|
| pwd | `pwd(tmp=false): String` | SCRIPTED_RUNTIME_CALL | STABLE | MAY_DISCARD | registry core.pwd / core.pwd.tmp; returns RUNTIME_VALUE_PLACEHOLDER in DSL form; real value via CorePwdStep/CorePwdTmpStep in runtime context. Discarding is a legitimate call: the step is emitted, so nothing is lost |
| isUnix | `isUnix(): Boolean` | SCRIPTED_RUNTIME_CALL | STABLE | MAY_DISCARD | registry core.isUnix; placeholder in DSL form; CoreIsUnixStep in runtime context (LFC-2R matrix). Discarding is a legitimate call: the step is emitted, so nothing is lost |

## 5. Fail-closed stubs (removed constructs that still answer)

| Construct | Signature | Category | State | ResultConsumption | Lowers to / Interpreter |
|---|---|---|---|---|---|
| retry (retrofit) | `retry(count, delaySeconds?): Nothing` (step level) | UNSUPPORTED_FAIL_CLOSED | UNSUPPORTED_FAIL_CLOSED | NOT_APPLICABLE | "removed", "consumer", points at block form |
| retry conditions | overload `retry(n, conditions) { }` REMOVED from surface | UNSUPPORTED_FAIL_CLOSED | UNSUPPORTED_FAIL_CLOSED | NOT_APPLICABLE | plain Kotlin signature error; reflection pins no List-taking overload |

`whenCondition(String) { }` was REMOVED in S2-A, not merely deprecated. It
accepted an unencoded expression string that no decoder could read, so it always
rejected. It is replaced by the typed `whenGate`/`whenEnvIs`/`whenEnvPresent`
family in section 2, which encode a predicate the runtime can actually decode.

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

## 8. S0-B witness findings (2026-09-29)

Every STABLE surface witnessed through the INSTALLED distribution binary, not an in-process
harness: `S0SemanticWitnessMatrixTest` (17 witnesses, all green). Three categories of finding:

**F1 — manifest named events that never existed.** The machine-check validates the
constructor/category/state columns but not the prose of the interpreter column, so these
three lies survived S0-A2:
- `timeout` claimed `TimeoutFired` → no such event. Real pair: `TimeoutScheduled` + `TimeoutTriggered`.
- `retry` claimed `RetryAttempted` → no such event. Real pair: `RetryAttemptStarted` + `RetryAttemptFinished`.
- `catchError` claimed `CatchErrorEntered` → no such event. Real: `CatchErrorTriggered` only.

**F2 — a real production gap closed.** `TimeoutTriggered` was declared in the vocabulary,
the JSON codec, the SQLite store, the sequence assigner and the identity projector, but had
NO producer anywhere: a `timeout()` block that fired left only the child's
`StepFailed(TIMEOUT)`, indistinguishable from an ordinary script timeout. The block authority
seam now emits `TimeoutTriggered`, keyed on the TYPED body outcome (never on a StepKey).

**F3 — surface state corrected against observed behaviour.**
- `git` is not STABLE: it lowers to `OpaqueStepNode(pluginStepId=core.checkout)`, but the
  scm-git plugin registers `scm-git.checkout`. The canonical bridge rejects it fail-closed
  (exit 2, "non-canonical plugins"). Jenkins-familiar `git(url)` performs ZERO checkouts.
  Reclassified `UNSUPPORTED_FAIL_CLOSED`; `checkout` downgraded to `PARTIAL`.
- `catchError` does not fail the build: CLI exit 0 with `RunFinished.outcome=unstable`.
- `options.timeout` and the `timeout()` block are different surfaces with different authority
  events; the directive has none of its own.


## 7. Conservation invariants (restated for the checker)

I1: every construct appears in EXACTLY ONE row (name+category unique).
I2: category and state are from the closed sets above.
I3: no UNKNOWN category/state anywhere in this file.
I4: STABLE constructs must have a named live interpreter (registry key, coordinator
    projection or rewrite pass). A STABLE row whose interpreter is deleted fails the test.
I5: UNSUPPORTED_FAIL_CLOSED stubs must throw at call time (reflection check).
I6: adding a DSL builder or StepSpec subtype without a manifest row fails the test
    (surface grows only through this manifest).



