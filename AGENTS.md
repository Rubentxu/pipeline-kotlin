# AGENTS.md

## V2 DEVELOPMENT PRIME DIRECTIVE

1. Authority: docs/v2/ (ROADMAP, ADRs, MIGRATION_PLAN, FITNESS, CURRENT_STATE).
2. Scope firewall: no implementation change without
   Milestone → Backlog → Exit criterion → Gate/UAT traceability.
3. No V1 repair on the V2 critical path; classify + quarantine instead.
4. No V2 dependency on :pipeline-steps-system:compiler-plugin.

## HEXAGONAL ARCHITECTURE (MANDATORY)

Every implementation MUST preserve hexagonal dependency direction:

1. Domain and application contracts define the inner seams and MUST NOT depend
   on infrastructure, process, persistence, UI/CLI, or framework adapters.
2. Adapters (Kotlin scripting hosts, Gradle/CLI, SQL/journals, process runners,
   network clients) depend on inner contracts and implement their interfaces;
   dependencies MUST NOT point back from inner modules to adapters.
3. Public ports expose explicit typed contracts, preferably sealed ADTs for
   outcomes with distinct semantics. Do not coordinate a boolean with nullable
   or `Any?` values when a closed result type can express the cases.
4. Generated artifacts may depend only on their declared public port. They MUST
   NOT name application runtime, journal, persistence, or process adapters.
5. Before adding a dependency, identify the owning seam and verify that it
   points inward. If it would reverse the direction, introduce or refine a
   port instead of coupling layers.

## STRICT TYPED FUNCTIONAL DESIGN (MANDATORY)

Implement domain and application behavior in a Haskell-inspired functional
style where it improves correctness and makes invalid states unrepresentable:

1. Model finite business outcomes, lifecycle states, commands, and errors as
   sealed ADTs. `when` over an ADT MUST be exhaustive; do not use an `else`
   branch to hide an unhandled case.
2. Prefer immutable data, pure functions, explicit inputs, and returned values.
   Keep I/O, persistence, clocks, randomness, process execution, and framework
   calls at adapter seams behind typed ports.
3. Do not use `Any?`, nullable sentinels, booleans coupled to nullable values,
   mutable flag bags, or stringly typed state when a value class, enum, sealed
   ADT, or typed DSL can state the invariant directly.
4. DSLs MUST be statically typed, preserve meaningful result types, and reject
   unsupported combinations before effects are launched. A DSL MUST NOT mimic
   dynamic behavior by erasing types at its public interface.
5. Prefer total transformations over partial functions. Validate external input
   at adapters and return a typed failure case; do not let unchecked parsing,
   casts, or incidental exceptions become normal domain control flow.
6. Exceptions remain appropriate at process/framework boundaries and for
   irrecoverable programmer defects. Expected operational outcomes MUST use the
   corresponding typed result algebra.

### Exceptions (require explicit human approval + new Milestone)

A. Critical security fix on V1 with no V2 equivalent.
B. INC reclassification promoting a QUARANTINED component.
C. Compatibility shim required by an in-flight UAT.
D. Backlog item with documented Exit criterion + Gate owner.

## STEP SEMANTICS (MANDATORY)

1. Jenkins familiarity: step names, parameters, semantics, and outcomes MUST
   match Jenkins behavior (see `docs/v2/00-context/JENKINS_REFERENCE_BASELINE.md`)
   so Jenkins users can adopt pipelines without relearning — e.g. `dir`,
   `timeout`, `retry`, `catchError`, `warnError`, `unstable`, `milestone`,
   `deleteDir`, `cleanWs`, `pwd`, `isUnix`, `load`, `waitUntil`.
2. Per-step observability: every step MUST emit its own typed domain events
   (e.g. `DirEntered`/`DirExited`, `DirDeleted`, `WsCleaned`,
   `WaitUntilPolled`/`WaitUntilCompleted`, `MilestoneReached`) so external
   systems can observe and react from separate processes. A step whose only
   observable effect is its return value is incomplete.
3. Fail-closed coverage: a step family without canonical decoder/dispatcher
   support MUST be rejected before execution on EVERY run path — never silently
   converted to a comment, no-op, or empty shell.

## STEP CONSTITUTION & EXTENSIBILITY (MANDATORY)

StepSpec is declarative structural IR (demonstrated by EP-F2.5 production
reachability audit, `docs/v2/07-uat/LB02_EP_F2_5_STEPSPEC_EXECUTION_DECOUPLING.md`):
production Step execution MUST flow through the canonical compiled
representation (`StepNode`) and `CanonicalDurableRunCoordinator`. New Step
implementations MUST NOT add direct `StepSpec` execution paths or teach
`PipelineRun`/`PipelineOrchestrator` how to execute concrete `StepSpec` forms.
`StepSpec.RegistryStepSpec` is the single generic structural escape hatch for
open-world Step semantics; concrete external `StepSpec` subtypes are forbidden.

Authority: ADR-0070..0074 + STEP_CONSTITUTION / STEP_PLUGIN_CERTIFICATION / PIPELINE_TEST_HARNESS.
This is the operative translation; the ADRs/specs are the architectural authority. Openspec change:
`openspec/changes/lfc2-step-constitution-plugin-seam`.

4. **Closed execution structure, open Step registry.** The engine exhaustively matches a closed
   structural ADT (`ExecutionNode`/`StepBodies`); it MUST NOT `when` over plugin Step classes.
   `StepKey → StepDefinition → StepHandler` resolves via an open registry.
5. **One execution path.** Core Steps are a standard bundled plugin set; core and external plugins
   run the exact same path (`Invoke → Registry → erased adapter → StepHandler → declared
   capabilities → durable engine → typed result/events`). No privileged core path.
6. **Fail-closed admission is registry-driven** (ADR-0069 invariant preserved): unknown/incompatible
   `StepKey`, schema mismatch and body-shape mismatch are rejected before effects on every run path.
7. **Forbidden** (fitness-gated): concrete-Step switches in a central dispatcher; KSP with
   `when(stepName)`; core privileged paths; fake runtime returns; a `Map<String, Any?>` public Step
   contract; a plugin requiring changes in domain/application/compiler/dispatcher; declaring
   capabilities broader than those actually used.
8. **Block Steps re-enter the engine** through `BodyInvoker.invoke`/`BranchInvoker.invokeAll`
   (ADR-0073). Never add a `dispatchRetryBlock`/`dispatchTimeoutBlock`/… collection; route
   control-flow Steps through the shared body machinery. `parallel` is composable Named Bodies, not a
   permanent stage-terminal.
9. **A Step is done only when CERTIFIED** (ADR-0074). States: DESIGNED /
   IMPLEMENTED_UNCERTIFIED / CERTIFIED / QUARANTINED / RETIRED. Never record `DONE/PASS` for an
   uncertified Step; quarantine or mark `IMPLEMENTED_UNCERTIFIED` instead.
10. **Harness fidelity HF0..HF6** (ADR-0072): test at the minimum faithful level (HF0 Pure Contract,
    HF1 In-Process, HF2 Forked Real Distribution, HF3 Restart/Resume, HF4 Rootless Sandbox,
    HF5 Service Sandbox, HF6 Online Smoke). The canonical LFC-2 `T0..T4` items are NOT renamed.
11. **Executable scenarios** (ADR-0071): the shown `.pipeline.kts` is the file the harness runs; the
    same file is executed by ScenarioRunner, TestKit, Just and CI. Invalid programs are first-class
    fixtures.

### Pre-decode durable metadata belongs to StepDescriptor (LB-02 / G3-A4.1)

StepDescriptor is the registry source of truth for static execution/durability metadata
required BEFORE typed input decode, including:

- `effects`
- `replayPolicy`
- `recoveryPolicy`

Registry metadata resolution MUST derive these properties from the descriptor and MUST
NOT branch on concrete StepKey values.

`recoveryPolicy` MUST be available before `StepCodec.decode` and MUST NOT require
constructing `PreparedRegistryExecution`.

MUST NOT:

- MUST NOT derive recovery semantics from concrete StepKey/name.
- MUST NOT place registry recovery metadata in parallel authorities outside `StepDescriptor`.

(Reference implementation Steps at this rule's writing are `core.echo` (atomic) and `core.sh`
(effectful/recoverable, LB-02); both are `CERTIFIED` and authoritative for the rule's proof.)

### Capability-routed handler discipline (LB-02 / G3-A4.2)

A handler that needs runtime capability access (e.g. `SHELL_OPERATIONS_CAPABILITY` for
`core.sh`) MUST declare the capability in `StepContract.requiredCapabilities`. The
boundary admits capabilities fail-closed at prepare-time and re-checks before the
handler runs. The handler MUST NOT:

- reach `CanonicalRuntimeContext` directly;
- import process-engine classes (`ProcessBuilder`, `Runtime.exec`, `bash -c`, etc.);
- emit observability events directly when the underlying substrate is the single
  authority (e.g. `echo`-style events emitted by `ShExecution`).

### Fitness tests (mechanically checkable)

- runtime does not depend on the DSL `StepSpec`;
- no central concrete-Step switch (fitness scans for per-step dispatcher cases);
- KSP has no semantic `when(stepName)`;
- no declarative fake-return path (no silent placeholder return);
- no global cwd/env mutation (dir/withEnv propagate an explicit context);
- declared capability == used capability;
- an external plugin adds a Step with zero core changes.

## STEP IMPLEMENTATION — OPERATIVE GUIDE (DERIVED FROM ADRs/SPEC)

**Authority hierarchy.** ADRs (ADR-0070..0074), `STEP_CONSTITUTION`, `STEP_PLUGIN_CERTIFICATION`
and `PIPELINE_TEST_HARNESS` are the architectural authority. This section is the **operative
translation**: how to implement, migrate, test and certify a Step without re-introducing legacy
and without inventing a new pattern. Where this section and the ADRs disagree, the ADRs win.

**Reference implementations** (live, defended by fitness):
- atomic / in-controller Step → `CoreEchoStep` (`core.echo`, `CERTIFIED`).
- effectful / recoverable process Step → `CoreShellStep` (`core.sh`, `CERTIFIED`).
- external / open-world Step → `example.uppercase` (`examples/example-uppercase-plugin`, `CERTIFIED`).

When you start a new Step, follow the certified pattern. Do not extract a fresh façade.

**Effectful / recoverable Step rules (validated by LB-02, `core.sh` reference):**
- Effectful handlers use narrow declared capabilities; the handler never reaches a coordinator,
  journal, event sink, or global context directly.
- A durable **console transcript** and a **typed Step value** are independent output channels and
  MUST NOT be conflated. `capturedStdout` (typed value requested by a capture mode) is not console
  output; `consoleTranscript` is the observable console output.
- `returnStdout` controls STDOUT projection only; it MUST NOT suppress STDERR. A stream exposed as a
  typed value stays separate from console projection.
- A durable transcript MAY be merged when channel identity is not part of the public contract, but
  output data MUST NOT be lost or duplicated, and a persisted file opened for writing MUST have a
  single writer (no two `O_TRUNC` opens of one file).
- Typed handler output leaves execution through `CommonExecutionResult`; durable persistence consumes
  encoded output, never the typed object.
- `ReplayPolicy` controls reuse independently of output presence.
- Recoverable Steps declare `RecoveryPolicy` on `StepDescriptor`; recovery routing MUST NOT branch on
  concrete `StepKey`.
- Effectful Steps reuse existing runtime engines through ports/adapters rather than reimplementing
  them, and use neutral domain naming: external product names appear only in integrations /
  compatibility boundaries, never as canonical runtime concepts.

### Step implementation golden path

```text
1. Define typed Input/Output value types (sealed/data class; never Any?).
2. Define StepCodec<I> and StepCodec<O>. The Input codec encodes the WHOLE input payload.
   The engine MUST NOT require codecs to round-trip only specific fields.
3. Define StepContract: key, descriptor, inputCodec, outputCodec, requiredCapabilities.
4. Implement the typed handler using ONLY the declared capabilities. Do not request a
   "context", a coordinator, a service locator, or any omnipotent parameter.
5. Register through StepRegistry — never instantiate the Step class manually inside the
   coordinator, dispatcher, compiler, or any adapter.
6. Produce a StructuralRegistry canonical invocation from the DSL (the DSL is data
   construction; runtime values come from the handler).
7. Verify fresh / replay / divergence / typed-invalid / missing-capability behaviour.
8. Run the StepContractSuite. Every row must pass.
9. If migrating legacy:
     REGISTRY_PRIMARY
     → LEGACY_UNREACHABLE
     → LEGACY_REMOVED
     → CERTIFIED.
   A Step cannot be CERTIFIED while it remains legacy-executable.
```

### Burn-down sequence template (G0..G8)

This is the only legitimate sequence to take a `LEGACY_PLUGIN_IDS` entry through the new
spine. The S3 / `core.echo` burn-down is the worked example (see `docs/v2/07-uat/S3_ECHO_BURNDOWN_CERTIFICATION.md`).

```text
G0 baseline / pre-existing failures:
    Fresh canary on pre-apply SHA; reproduce every pre-existing failure with base SHA + SHA-256
    logs. Persist evidence under docs/v2/07-uat/.
G1 registry seam proof:
    Implement Core<Name>Step behind the registry, keeping the legacy decode/dispatch path
    intact. PROVE the registry path is correct (handler + contract + codecs + capabilities).
G2 corpus migration:
    Migrate the durable characterisation/characterization corpus to drive the Step through
    the registry so the same fingerprint/op-journal is exercised by both paths.
G3 REGISTRY_PRIMARY:
    Flip the production wiring to the registry (CoreStepRegistryFactory contains the Step;
    coordinator's stepRegistry is the production factory). Both paths still exist on paper.
G4 LEGACY_UNREACHABLE:
    Remove the legacy execution path source-of-truth (canonical command data class, decoder
    branch, dispatcher case, metadata table row). Prove via fitness that classify() routes
    the key as Registry on every production wiring.
G5 LEGACY_REMOVED:
    Mechanical fitness: source-level absence of all THREE legacy forms (decoder, dispatcher,
    registration). Distinct from LEGACY_UNREACHABLE (runtime property); LEGACY_REMOVED is a
    static source property and is what we mean by "removed".
G6 architecture fitness:
    Run the L4/L5 architecture fitness against the new path; the Lfc2RegistryFamilyFitness
    suite must remain green and now reference the renamed LEGACY_PLUGIN_IDS.
G7 StepContractSuite:
    16/17 coverage: identity, contract completeness, codec input, codec output, canonical
    envelope, registry resolution, capability admission, success, typed failure, fresh
    durable, replay, divergence, observability, missing capability, architecture fitness,
    real DSL scenario (pipeline { stages { stage("...") { steps { <step>(...) } } } }).
G8 CERTIFIED:
    Update the per-Step state in the burn-down ledger. Anything not yet CERTIFIED must be
    reported as IMPLEMENTED_UNCERTIFIED with an exact gap description. Never record DONE/PASS
    for an uncertified Step (ADR-0074).
```

No slice may invent a different shape. If a future Step genuinely needs a new gate, propose
the addition in an ADR before adding it.

### Counters (project dashboard / roadmap)

Until every LEGACY_PLUGIN_IDS entry is burned down, the project must track three numbers:

```text
Certified Steps:           N
Legacy executable Steps:   M     (where N + M = |LEGACY_PLUGIN_IDS| + external plugin count)
Registry-primary Steps:    N
```

`N + M = total`; convergence means `M -> 0`. The updated values belong in the per-cycle
release receipt (and in `docs/v2/07-uat/S3_ECHO_BURNDOWN_CERTIFICATION.md` style receipts).

### MUST NOT (Step Constitution enforcement)

These are mechanically defensible; fitness tests in the S3/S4 sections are the canonical
implementation of each:

```text
- add a concrete Step case to CanonicalNodeDispatcher;
- route by StepKey / stepName in the durable coordinator (no when(stepKey), no when(stepName));
- decode legacy command and re-encode for the registry (no "compat" seam);
- execute handlers before capability admission (capability check MUST be in RegistryExecutionPreparation.prepare, before the typed handler call);
- accept CanonicalRuntimeContext, the coordinator, or any service locator as a StepHandler argument;
- persist typed Input or PreparedExecution as `Any`/Map (prepared is runtime-ephemeral, never fingerprinted, never journaled);
- claim CERTIFIED while the Step remains legacy-executable (LEGACY_REMOVED is a prerequisite of CERTIFIED);
- modify journal/replay semantics as part of a normal Step migration (those are spine-level);
- introduce a KSP processor that branches on a concrete Step name;
- give core a privileged execution path that external plugins cannot reach.
```

A common failure mode is "polymorphic dispatcher": the moment a coordinator's `when` branch
discriminates `core.echo` vs `core.sh`, the spine has regressed to a closed world. If you need
behaviour that varies by Step, declare it as part of the StepContract (replay policy,
recoverable operation, required capabilities) — the engine reads the contract, it does not
read the Step key.

### DSL vs runtime (using Steps)

Authoring an example or a test:

```kotlin
// GOOD: declarative DSL construction
steps {
    echo("hola")
    sh("./gradlew test")
}
```

```kotlin
// GOOD: capturing runtime values inside a scriptable block
script {
    val branch = shStdout("git branch --show-current").trim()
    if (branch == "main") {
        sh("./publish.sh")
    }
}
```

```kotlin
// BAD: simulating runtime values during construction
pipeline {
    val branch = "main"               // fabricated runtime value — forbidden
    sh("./publish-${if (branch == "main") "prod" else "dev"}.sh")
}
```

Construction (the DSL builder) MUST NOT perform I/O, MUST NOT shell out, MUST NOT call
`pwd()` / `isUnix()` / `now()` and pretend those are real values. Anything that needs
runtime data lives inside a Step handler with declared capabilities (input codec is the
canonical envelope, not ad-hoc field probing).

### Block Steps (when the time comes)

When implementing `retry`, `timeout`, `parallel`, `script`, etc., control-flow enters the
engine via `BodyInvoker.invoke` / `BranchInvoker.invokeAll` (ADR-0073). Do NOT add a
collection like `dispatchRetryBlock` / `dispatchTimeoutBlock`. Each Block Step declares its
own body-shape contract and is routed the same way as atomic Steps. `parallel` is a
composable set of Named Bodies, not a permanent stage-terminal.


## EXTERNAL STEP / PLUGIN AUTHORING GUIDE (DERIVED FROM CERTIFIED PROOF)

**Authority hierarchy.** ADRs/SPECs > AGENTS.md. This section is operative guidance derived
from certified implementations. If a future implementation contradicts a valid ADR, do NOT
silently "fix" the ADR from AGENTS.md — escalate and amend the ADR.

**Reference implementations** (all CERTIFIED; architectural references, not mandatory
dependencies — follow the closest one rather than inventing a new execution pattern):

| Reference | Kind |
| --- | --- |
| `core.echo` | CERTIFIED reference for atomic/simple Steps |
| `core.sh` | CERTIFIED reference for effectful/recoverable Steps |
| `example.uppercase` (`examples/example-uppercase-plugin`) | CERTIFIED reference for external/open-world Steps |

### External plugin golden path

The certified flow (LB-02, proven end-to-end by `example.uppercase`):

```text
1.  Define typed Input and Output.
2.  Define StepCodec<Input> and StepCodec<Output>.
3.  Define StepDescriptor.
4.  Define StepContract.
5.  Implement StepHandler using declared capabilities only.
6.  Define StepDefinition.
7.  Expose it through StepDefinitionContributor.
8.  Package the contributor in an external JAR.
9.  Register the contributor through the supported discovery metadata.
10. Provide a plugin-owned typed Kotlin DSL extension.
11. The extension lowers only to registryStep(...).
12. registryStep produces RegistryStepSpec.
13. The compiler lowers RegistryStepSpec generically.
14. Runtime discovery resolves the StepDefinition.
15. Execute through the canonical durable spine.
16. Run the StepContractSuite.
17. Reach CERTIFIED before treating the plugin as production-ready.
```

### Plugin ownership boundaries

```text
PLUGIN OWNS:
- StepKey
- Input / Output types
- codecs
- StepDescriptor
- StepContract
- handler
- StepDefinition
- contributor
- ergonomic Kotlin DSL extension

CORE OWNS:
- RegistryStepSpec structural representation
- generic compiler lowering
- discovery mechanism
- StepRegistry
- durable protocol
- capability admission
- CommonExecutionBoundary
- journal/replay/recovery infrastructure
```

Strong rule: **the compiler MUST NOT know how to convert plugin-specific arguments into
plugin Input. The plugin DSL façade performs typed construction and encoding.**

### Closed IR / open semantics (first-level law)

```text
StepSpec is a closed declarative structural IR.

RegistryStepSpec is the single generic structural representation for
open-world Step semantics.

External plugins MUST NOT define or require new StepSpec subclasses.
```

```text
StepSpec MUST NOT be executed directly.

Production Step execution MUST flow:

StepSpec
→ compiled canonical representation
→ CanonicalDurableRunCoordinator
→ execution spine
```

Re-introducing execution semantics in `PipelineRun`/`PipelineOrchestrator` is prohibited.
The F2.5 finding is frozen: direct StepSpec execution there = legacy architectural debt =
not a valid extension point. New Steps/plugins MUST NOT add cases there; the counter tends
to zero via the independent burn-down.

### External DSL rule

Conceptual pattern (based on the certified `example.uppercase`):

```kotlin
fun StageScope.uppercase(text: String) =
    registryStep(
        stepKey = UppercaseStepDefinition.KEY,
        encodedInput = UppercaseCodec.encode(UppercaseInput(text)),
    )
```

Plugins provide ergonomic typed Kotlin extension functions; core provides only the generic
`registryStep(...)` primitive. The extension:

- MAY construct typed plugin Input;
- MAY call the plugin codec;
- MUST only produce declarative data;
- MUST NOT resolve the runtime registry;
- MUST NOT execute handlers;
- MUST NOT access capabilities;
- MUST NOT query runtime state.

### Discovery rules

```text
External StepDefinitions MUST enter runtime composition through
StepDefinitionContributor/discovery.

They MUST NOT be manually added to CoreStepRegistryFactory.
```

```text
duplicate StepKey → fail closed
```

Never "first wins", never "last wins". The failure diagnostic MUST identify the key and
the conflicting contributors.

Current production discovery adapter: **ServiceLoader** (`ExternalStepPluginDiscovery` is
the only ServiceLoader site). The architecture depends on the `StepDefinitionContributor`
SPI; ServiceLoader is the current adapter, not an eternal domain law — discovery may be
replaced in the future without changing the Step model.

### Public API boundary

External plugins may depend only on the public plugin/Step SDK surface. They MUST NOT
import coordinator implementations, application internals, durable internals, dispatcher
internals, core Step implementation packages, or legacy execution packages. If a plugin
needs an internal import for a legitimate feature: classify it as an SDK gap; do not work
around it.

### Capabilities

Plugin handler → declared capability keys → capability admission → minimal capability
interfaces. NEVER `plugin handler → CanonicalRuntimeContext`. An external plugin gains no
additional privileges by being installed. Missing capability: handler never runs (handler
= 0), fail closed via typed Rejection.

### Descriptor metadata

`StepDescriptor` (effects, replayPolicy, recoveryPolicy) is the registry authority for
pre-decode metadata. External plugins declare these policies exactly like core Steps. The
metadata resolver MUST NOT know concrete plugin StepKeys.

### Input/output codecs

`StepCodec<I>` represents the complete typed Input `I`; no field extraction by the
compiler/core. Output flow: handler `O` → `outputCodec.encode(O)` →
`CommonExecutionResult` → durable encoded result. The durable engine never knows typed
`O`. And: `OUTPUT_EXISTS != REPLAY_REUSE` — `ReplayPolicy` remains the only authority for
reuse/rerun.

### Console/transcript rule

Typed Step output and the observable/durable console transcript are independent channels
and MUST NOT be conflated. A durable console transcript MAY be merged when channel
identity is not part of the public contract, but data MUST NOT be silently lost or
duplicated. Invocation modes that expose a stream as a typed value must keep it separate
from the console projection (certified by `core.sh`).

### Neutral naming

Core/runtime concepts MUST use neutral domain terminology. External product names
(e.g. Jenkins) are allowed only in explicit integration adapters, compatibility
boundaries, and historical/migration documentation — never as canonical runtime
nomenclature.

### External plugin MUST NOT

```text
MUST NOT:
- add the plugin StepKey to a core/legacy catalogue;
- modify coordinator routing for a plugin;
- modify DslCompiledPipelineCompiler with a concrete plugin case;
- add a concrete external StepSpec subtype;
- manually register the plugin in CoreStepRegistryFactory;
- add a dispatcher case;
- decode plugin-specific input in core/compiler;
- execute handlers during DSL construction;
- depend on internal runtime packages;
- access CanonicalRuntimeContext from handlers;
- bypass capability admission;
- infer replay from presence of encoded output;
- create a plugin-specific durable/recovery path.
```

### Certification rules

External plugin certification requires more than unit tests. Minimum proof rows:

```text
identity
contract completeness
input codec
output codec
canonical envelope
discovery via real mechanism
registry resolution
capability admission
handler execution
typed rejection
durable fresh
replay
divergence
observability
real DSL
real external JAR
installed-distribution execution
absence/isolation
zero production semantic changes
architecture fitness
```

No `CERTIFIED` if any mandatory row is missing.

### Testing external plugins

Final plugin proof MUST use the real plugin artifact. Unit tests may register definitions
manually, but certification needs the full chain:

```text
source
→ independent plugin build
→ JAR
→ discovery
→ script compiler visibility
→ real .pipeline.kts
→ installed distribution
→ execution
```

And the isolation pair is mandatory (detects classpath leakage):

```text
without plugin → unavailable / clear failure
with plugin    → available / green
```

### Zero-production-change rule

Once generic plugin infrastructure exists, adding a new external Step plugin MUST require
zero Step-specific semantic changes to production core. For a new plugin:

```text
coordinator modifications       = 0
durable modifications           = 0
compiler concrete-Step cases    = 0
core metadata rows              = 0
legacy catalogue entries        = 0
dispatcher cases                = 0
```

If any is > 0: stop and classify the missing generic extension point before proceeding.

### KSP

KSP MAY generate plugin plumbing or ergonomic DSL code, but MUST NOT introduce
Step-specific semantics into core/compiler. The handwritten external proof
(`example.uppercase`) is the current authority; extensibility MUST NOT depend on a central
list of known Steps.

### Explicitly out of scope (do not build yet)

Plugin marketplace, hot reload, dependency resolution, plugin signing, remote repository,
plugin lifecycle manager, default-import discovery, advanced KSP automation. Document and
implement only what `example.uppercase = CERTIFIED` has demonstrated.


## RETRY-D — DURABLE CONTROL ROWS (MANDATORY)

Authority: ADR-0075. The retry aggregate is a **durable control row**, not an
event or an in-memory counter. Without a control row, a second invocation of
the binary with the same `--db` and `--control-root` re-runs the entire retry
loop and duplicates a previously successful child effect.

```text
retry control row   → canonical durable state (persisted BEFORE child effects)
retry event         → observability only (not authoritative)
in-memory counter   → prohibited as the durable source of truth
```

### Production wire-up

```kotlin
CanonicalDurableRunCoordinator(
    ...,
    stepRegistry = stepRegistry,
    retryControlJournal = FileBasedRetryControlJournal(controlDirRoot),
).run(pipeline, runId)
```

Without `retryControlJournal`, the retry aggregate has no durable anchor:
the dispatch loop still runs `W1–W5`, but every plan() call sees an empty
control journal and re-schedules attempt 1 from scratch. This is the
failure mode that caused R2 to loop in the closure script.

### Planner invariants

The dispatch loop and `RetryReconciler.reconcile()` cooperate on the
following invariant:

```text
control rows = [ (1, FAILED), (2, RUNNING) ]
plan() MUST skip attempt 1 because attempt 1 is terminal AND already
has a successor (attempt 2) in the control rows. The planner must
reach attempt 2 and return ScheduleAttempt(2) / ResumeAttempt(2),
NOT AdvanceAfterFailure(1 -> 2).
```

Without the supersede-skip, the planner iterates attempts in order, sees
`(1, FAILED)` first, and returns `AdvanceAfterFailure(1 → 2)` again — the
dispatch loop never reaches attempt 2 and burns the retry budget on
redundant advance decisions.

The fix lives in `RetryReconciler` (`pipeline-domain`):

```kotlin
if ((control.status.isFailureForRetry() || control.status.isTerminal) &&
    byAttempt.containsKey(attempt + 1)
) {
    continue
}
```

### What MAY NOT exist

```text
- A second retry coordinator, a RetryShExecutor, or a parallel dispatch path.
- A `dispatchRetryBlock` collection alongside the canonical dispatch loop.
- A retry decision based solely on event streams or in-memory counters.
- A retry plan() that branches on concrete StepKey / stepName.
- A run-mode that skips retryControlJournal injection (e.g. a "dev mode").
```

### Closure proof (R1–R6)

```text
R1 / R3 / R4 / R6 : pipeline-domain unit tests, commit caa0b497
R5 Window C      : child success / control stale reconciliation, caa0b497
R2 installDist   : counter file advanced 1 -> 2 with retry-ok=1 emitted;
                   journal contains {FAILED, SUCCEEDED};
                   replay with same --db/--control-root reuses cached
                   success without re-executing child bodies;
                   commit aae1acb1.
```

The four pre-existing compatibility/UAT failures (UatLocal008 credential
events, UatLocal009 archiveArtifacts) remain out of RETRY-D scope and are
not regressions from this work.

## REPLAY POLICY — EXECUTION VS RE-EXECUTION (MANDATORY)

Validated by E-EM-11 NEVER-1 (EffectReplayPolicy fix; receipt:
`docs/v2/07-uat/E_EM_11_CLOSURE_RECEIPT.md`).

### Law

```text
Replay policy governs execution in relation to EXISTING durable history.
It MUST NOT suppress a legitimate first execution unless admission
denial is explicitly part of that policy.
```

### Minimal matrix (ReplayPolicy.NEVER)

```text
ReplayPolicy.NEVER

fresh / no durable entry
    -> EXECUTE (ReplayDecision.RERUN: "execute handler now";
       naming debt — do not read RERUN as "this is a re-run")

existing durable history (any journaled status)
    -> ABORT / fail closed (no handler execution)
```

The decision MUST live in the generic replay authority
(`EffectReplayPolicy.decide`), never as a per-Step special case. Any
future Step declaring `ReplayPolicy.NEVER` inherits the correct
semantics automatically.

### Frozen distinction: Effect vs Decision

```text
Effect.ABORTS_PIPELINE != ReplayDecision.ABORT
```

```text
ABORTS_PIPELINE
    -> behavior of the Step AFTER legitimate execution
       (the handler ran; its typed outcome aborts the pipeline)

ReplayDecision.ABORT
    -> execution admission / re-execution decision
       (the handler NEVER runs; fail closed before effects)
```

### Test law: distinguish failure classes

Tests MUST distinguish a typed Step failure from a replay/admission
infrastructure failure when both can produce the same surface event
(e.g. both `StepFailed`). At minimum assert:

```text
failureKind == the Step's contractual kind  (not INFRASTRUCTURE)
message   == the Step's configured message  (not "Replay aborted")
```

(False-green precedent: `UatStep003ErrorAbortTest` passed while
`core.error` was unexecutable, because both paths emit a StepFailed.)

## V2 TESTING RULES

### Execution economics ( Gradle )

1. Inner loop: targeted runs only (`--tests 'UatLocal004*'`), warm daemon,
   NO `--rerun-tasks` while iterating.
 2. Full round gate = `./gradlew -p v2 check` (incremental) runs ONCE per
    apply/verify round, as the final gate. Never per-iteration. Gradle's
    content-hash up-to-date checks are the freshness oracle: a no-op
    `check` returning BUILD SUCCESSFUL with all tasks UP-TO-DATE is a
    VALID green — it proves nothing changed since the last green
    (measured 2026-08-30: forced gate 977s vs 1s incremental no-op).
    Escalate to `check --rerun-tasks` ONLY after (a) a run killed
    mid-flight, (b) suspected stale green, or (c) hidden-state suspicion;
    then reconcile ONCE with the rule-4 budget before trusting
    incremental again (reconciliation measured 948s).
3. Never `--no-daemon` for repeated runs; the daemon JVM stays warm.
4. Wrap every Gradle invocation in `timeout` — silent hangs are defects
   of the harness, not the code under test. Two regimes:
   - Targeted / inner-loop runs: `timeout 600` (fixed).
   - Full round gate (`check` incremental, or escalated
     `check --rerun-tasks`): DERIVED budget =
     last green round-gate duration × 1.3, floor 600, ceiling 1800.
     Compute and record the budget in the round plan BEFORE the run;
     put the observed duration in the round receipt. An over-budget
     kill is a signal: either the suite legitimately grew (re-derive
     the baseline explicitly and document why) or something degraded
     (diagnose per rules 28-31). NEVER raise a timeout mid-run.
5. Base-SHA evidence is immutable: never recompile a base worktree to
   "re-prove" a result already captured (cite the prior XML/SHA).
6. `v2/gradle.properties` MUST keep enabled: `org.gradle.caching=true`,
   `org.gradle.parallel=true` (module-level parallelism only).

Canonical inner loop (TDD red-green, seconds — measured 2s no-op / 22-40s
with incremental compile):

```bash
timeout 600 ./gradlew -p v2 :pipeline-application:test --tests 'UatLocal004*'
timeout 600 ./gradlew -p v2 :pipeline-step-sdk:runtime:test --tests 'DurableShellExecutorAdversarialTest'
```

Round gate (once per apply/verify round, not per iteration). Incremental
by default — the escalated budget (rule 4) applies to the escalation form
only; current escalated baseline 977s → budget 1270:

```bash
./gradlew -p v2 check                              # incremental (default)
timeout 1270 ./gradlew -p v2 check --rerun-tasks   # escalation only
```

Quick interface: `just gate` / `just gate-escalate` / `just t '<pattern>'` /
`just corpus <n>` / `just changed [base]` (see justfile test-efficiency
group).

### Test design ( hangs and processes )

7. Every UAT / integration test class MUST declare JUnit `@Timeout`
   (class-level or per-test). A hung test must FAIL in seconds, never block
   the test JVM (lesson: 47-min hang from an unwired watchdog).
8. Teardown hygiene: `destroyForcibly()` in `finally`; kill the whole
   process group (`setsid` children survive parent kill); `@AfterEach`
   must guarantee zero living children.
9. Env assertions use `printenv VAR` as the oracle (emits exact value,
   quoting-safe with special chars), not `echo`.
10. `Thread.sleep` is allowed ONLY for state positioning (e.g. ensuring a
    process is mid-execution before killing it). Never sleep to wait for a
    condition — poll with a deadline instead.
11. Do NOT add `maxParallelForks` to UAT modules: these tests verify
    timing semantics (heartbeat staleness, backoff, LOST classification)
    that degrade under CPU contention and turn deterministic suites flaky.
    EXCEPTION PATH: functional suites without timing semantics (e.g. the
    compatibility corpus, one method per fixture since 2026-08-30) MAY be
    parallelized — but only as a measured, explicit decision with a
    recorded before/after baseline, never as a default.
12. Prefer SDK-level unit tests when semantics do not depend on real
    processes. Real-process UATs are reserved for kill/resume/durability
    semantics that cannot be mocked faithfully.
13. Kotlin string interpolation: shell `$VAR` inside Kotlin strings needs
    `${'$'}VAR`.

### Integrity

14. Zero-fabrication: every reported test result comes from a fresh run of
    an existing file; record argv, exit code, and output digest.
15. Never weaken assertions, skip, or ignore a test to make a gate pass.
16. Never classify a failure as "pre-existing" without fresh base-vs-head
    evidence (worktree method) — the cycle base SHA is the comparison
    point, not a mid-cycle commit.

### Validation ladder ( iteration protocol )

17. Always validate at the MINIMUM sufficient level; escalate ONLY on
    green. L0 compile (`:pipeline-application:compileTestKotlin`, ~10 s)
    → L1 single test (`--tests "...UatLocal005EnvSpecialCharsTest.WS-S-008*"`)
    → L2 full class (`--tests 'UatLocal005*'`) → L3 related set
    (package/feature filter) → L4 module suite → L5 full `check`
    (the round gate). Never jump L1→L4; exception: cross-cutting changes
    (build files, engine core, base DSL, event model) → L4 directly.
     Derive the level from `git diff --name-only <base>` or `just changed
     [base]`; that output is advisory and MUST NOT be copied as an unfiltered
     module-suite command.
     - Docs-only: documentation/link validation; no Gradle.
     - Test-only in module M: `:M:compileTestKotlin`, then the edited test
       methods; run the full class only at batch end.
     - Production in M: `:M:compileTestKotlin` → owning test methods → owning
       class; add direct consumer tests only where the changed public boundary
       is exercised. Do not run bare `:M:test` before method/class evidence is
       green.
     - `v2/compatibility/<NN>-*.pipeline.kts`: run `just corpus <NN>`; run
       `CompatibilityCorpusTest.allCorpusFixturesAreDiscoverable` only when
       fixture inventory changes.
     - Shared domain/event/runtime/scripting API contracts, Gradle build files,
       or architecture rules: L4 is affected module suites plus relevant
       fitness tests. Add application/UAT tests only for impacted consumers.
     - L5 `just gate` is justified only as the final apply/verify or release
       gate, once lower levels are green.

     `--tests` is mandatory at L1/L2. A bare `:M:test`,
     `:pipeline-application:test`, or `check` is never a discovery mechanism.
     Rules 4, 23-31 continue to govern timeouts, log capture, XML canaries,
     escalation, and hang diagnosis.
18. One behavior per iteration. Batch the edits, then validate once — no
    validation between micro-edits. L0 after every batch: a 10 s compile
    error beats a 60 s test failure.
19. Test-only edits: L0+L1 while iterating, L2 at batch end. Production
    edits: L1→L2→L3 after each change batch.
20. A green test is an asset — do NOT re-run it unless (a) the production
    code it covers changed, (b) its test class changed, or (c) an L4/L5
    milestone was reached. Track the last green run per (test, class, module).
21. TDD discipline: RED must fail for the EXPECTED reason (read the
    assertion message, not just the failure). A timeout or compile error
    is NOT a valid RED. GREEN = minimal implementation, validated at L1.
22. Use `--fail-fast` when running more than one test in iteration.

### Output capture and result truth

23. NEVER pipe test output through `| tail` under `timeout`: the output is
    lost when the process is killed. Safe pattern (`<budget>` per rule 4:
    600 targeted, derived for the round gate):
    `timeout <budget> ./gradlew ... > /tmp/gradle-run.log 2>&1; tail -n 30 /tmp/gradle-run.log`
24. Long builds run backgrounded with polling:
    `nohup timeout <budget> ./gradlew ... > /tmp/gradle-run.log 2>&1 &` then
    poll `tail -n 20 /tmp/gradle-run.log` — keep editing while it runs.
25. Result truth is the JUnit XML in `build/test-results/test/`, NOT the
    console or the Gradle exit code. When a run MUST have executed, use
    the canary: delete `TEST-<Class>.xml` first, run, verify it regenerated.
26. XML `timestamp` is UTC while `ls` shows local time (10:27Z == 12:27
    local). Convert before concluding a result is stale.
27. After a build killed by timeout, distrust `BUILD SUCCESSFUL` /
    `UP-TO-DATE`; confirm with the canary (rule 25) before interpreting.

### Hang protocol ( a test that never ends )

28. Do NOT retry blindly or raise the timeout. Isolate first: run the
    class's tests one by one (`--tests "...Test.method*"`) to identify
    which one hangs.
29. With the process alive: `jcmd <pid> Thread.print` on the Gradle worker
    JVM; `ps aux | grep sh` for orphaned shell processes.
30. If a test passes isolated but hangs in the class: suspect shared state
    or inter-test interference within the same JVM. Report it, do not
    ignore it.
31. Shell hygiene: never `pkill -f <pattern>` where the pattern matches
    the pkill command line itself. Use `pkill -f GradleDaemon` or kill by
    PID via `jps`.

### Coordinator test composition (LB-02 / A5)

Validated by the A5 Sh-corpus migration: this rule governs how coordinator and
durable behaviour tests are composed, and is Step-agnostic.

- Ordinary coordinator / durable behaviour tests MUST use the production-like
  registry-aware composition (the registered core `StepRegistry` +
  `StepMetadataResolver` + `CommonExecutionBoundary` + capability composition).
- Bare / no-registry coordinator construction MUST be explicit and reserved for
  negative fail-closed tests or intentional legacy characterization.
- Tests MUST observe the common execution / family-routing seam rather than
  depending on concrete legacy command subtypes.
- A pre-existing red test baseline MUST NOT be widened or re-baselined during a
  Step migration.

### End-of-round checklist

- [ ] Exactly one L4/L5 full run actually executed (canary verified, fresh XML).
- [ ] Fresh XMLs show `failures="0" errors="0"` covering everything touched.
- [ ] No green test modified without documented reason.
- [ ] No background builds or orphan processes left alive.

---

# Intelligent Change-Scoped Testing

## Purpose

Coding agents MUST use a **change-scoped, progressive and evidence-driven testing strategy**.

The objective is to obtain the smallest sufficient verification evidence for the active code change while preserving confidence in correctness.

During normal implementation work, agents MUST NOT repeatedly execute the complete repository test suite.

Full-project verification is reserved for explicit verification boundaries such as:

* final verification;
* pre-merge validation;
* release preparation;
* CI gates;
* repository-wide changes;
* changes whose impact cannot be bounded safely;
* explicit user requests.

The testing strategy defined here is currently performed by the agent using repository information, Git state and the persistent testing state file.

It is an informed and conservative approximation, not authoritative static or dynamic impact analysis.

---

# Persistent Testing State

Before selecting or discovering tests, read:

`.agent/TESTING-STATE.md`

If the file does not exist, create it using the project's testing topology discovered during the task.

This file is persistent working knowledge shared across agents and sessions.

It exists to avoid repeatedly rediscovering:

* project structure;
* components and modules;
* test frameworks;
* test commands;
* test selectors;
* component → test relationships;
* dependency and contract relationships;
* expensive test suites;
* previous verification evidence;
* unresolved testing gaps.

The state file is **advisory, not authoritative**.

The following sources have higher authority:

1. current Git state;
2. source code;
3. build/project manifests;
4. dependency declarations;
5. executable tests;
6. actual test results;
7. CI/build configuration;
8. `.agent/TESTING-STATE.md`.

If the state file conflicts with current repository evidence, update the state file.

Never blindly trust stale entries.

---

# Core Testing Principle

Always begin with the narrowest defensible test scope.

Use progressive widening:

```text
Active Change
    ↓
Affected behavior / SUT
    ↓
Direct tests
    ↓
Owning component tests
    ↓
Dependency / contract tests
    ↓
Risk-specific checks
    ↓
Full verification only when justified
```

Testing scope MUST be determined before selecting a runner command.

Commands are execution mechanisms, not testing strategy.

---

# 1. Determine the Active Change

Before running tests, inspect the current repository change.

Use Git and repository state to identify relevant:

* modified files;
* added files;
* deleted files;
* renamed files;
* changed tests;
* changed configuration;
* manifests;
* dependency declarations;
* schemas;
* migrations;
* generated-code inputs;
* build files;
* public interfaces;
* protocols;
* infrastructure definitions.

Reason about the current active change, not the whole repository.

Do not begin normal implementation work by running every test.

---

# 2. Identify the System Under Test

For the active change, determine the smallest meaningful System Under Test (SUT).

A SUT may be:

* function;
* class;
* module;
* namespace;
* package;
* library;
* crate;
* component;
* service;
* application feature;
* frontend component;
* API;
* database boundary;
* schema;
* build target;
* plugin;
* infrastructure component.

Use repository topology, imports, manifests, dependency declarations and existing test organisation.

Classify impact confidence when useful:

```text
KNOWN
LIKELY
UNKNOWN
```

Do not silently convert `UNKNOWN` into `NOT AFFECTED`.

---

# 3. Read Existing Testing Knowledge Before Discovering It Again

Consult `.agent/TESTING-STATE.md` before probing build/test tooling.

Reuse previously established information such as:

* project test commands;
* unit-test selectors;
* module/package selectors;
* integration commands;
* contract-test commands;
* full verification command;
* environment requirements;
* known expensive suites.

Do not repeatedly invoke:

* `--help`;
* runner discovery commands;
* broad repository searches;
* build-system inspection;

when that information has already been established and remains valid.

If existing testing knowledge is stale, update only the affected knowledge.

---

# 4. Build a Lightweight Impact Model

For each changed artifact, reason approximately through:

```text
change
  → changed artifact
  → owning SUT/component
  → dependencies/contracts
  → possible consumers
  → relevant verification
```

Consider at least:

* direct ownership;
* compile dependencies;
* reverse dependencies;
* runtime dependencies;
* public interfaces;
* shared libraries;
* schemas;
* serialization contracts;
* database contracts;
* generated artifacts;
* configuration consumers;
* external interfaces.

The impact model may cross languages and toolchains.

Example:

```text
OpenAPI schema
   ↓
backend service
   ↓
generated TypeScript client
   ↓
frontend component
```

Relevant verification can therefore include tests from multiple languages.

Never restrict impact analysis to file extensions or same-language tests.

---

# 5. Progressive Verification Levels

## Level 0 — Cheap deterministic checks

When relevant to the changed surface, first use inexpensive checks such as:

* compile;
* type-check;
* syntax validation;
* lint;
* formatting validation;
* schema validation;
* local static analysis.

Run scoped versions when available.

Do not automatically execute unrelated repository-wide checks.

---

## Level 1 — Direct behavioral tests

Run the tests most closely associated with the modified behavior.

Prefer:

* individual test;
* test function;
* test class;
* test file;
* test target;
* package/module subset;
* tag/filter;
* affected feature tests.

If implementing new behavior using TDD:

```text
focused failing test
    ↓
minimal implementation
    ↓
same focused test
    ↓
nearby affected tests
```

Do NOT execute the entire repository test suite during each Red/Green/Refactor iteration.

---

## Level 2 — Owning component verification

If direct tests are insufficient to establish confidence, widen to the owning:

* module;
* package;
* library;
* service;
* application component;
* build target.

Only widen when there is a reason.

Do not include unrelated components.

---

## Level 3 — Dependency and contract closure

Widen when the change crosses an important boundary.

Examples:

* public API;
* public library interface;
* OpenAPI;
* GraphQL;
* Protobuf;
* database schema;
* event schema;
* serialization format;
* shared configuration;
* generated client;
* plugin interface;
* runtime protocol.

Identify likely consumers.

Run relevant:

* consumer tests;
* provider tests;
* contract tests;
* integration tests;
* reverse-dependent component tests;
* generated-code verification.

Document the reason for widening.

---

## Level 4 — Risk-specific verification

Run specialised verification when justified by the active change.

Examples:

* security tests;
* architecture tests;
* migration tests;
* compatibility tests;
* performance tests;
* concurrency tests;
* resilience tests;
* mutation testing;
* E2E;
* UAT.

Do not run these merely because they exist.

---

## Level 5 — Full verification

Execute the complete project verification profile only when justified.

Typical reasons:

* explicit user request;
* final `verify` phase;
* pre-merge gate;
* release gate;
* repository-wide change;
* global build-system change;
* global test infrastructure change;
* core dependency with broad unknown reach;
* impact cannot be bounded confidently;
* targeted failures reveal wider impact;
* repository policy explicitly requires it.

Full verification MUST NOT be used merely because it is easier than reasoning about test impact.

---

# 6. Prefer Evidence over Test Quantity

For each candidate test or check, ask:

> What plausible regression caused by the active change could this verification detect?

If there is no meaningful answer, it is probably not part of the normal implementation feedback loop.

Running more tests is not automatically better testing.

The objective is:

> sufficient relevant evidence with minimum unnecessary execution.

Not:

> minimum number of tests at any cost.

Correctness always takes precedence over optimisation.

---

# 7. Evidence Reuse

Do not rerun successful verification unnecessarily.

A previous result may be considered reusable when relevant inputs have not changed.

Consider evidence stale when any relevant input changes, including:

* production code under test;
* dependencies of the SUT;
* corresponding test code;
* shared schemas;
* configuration;
* build configuration;
* dependency versions;
* generated-code inputs;
* test environment;
* test runner/toolchain.

Prefer selective invalidation.

Example:

```text
component A tests PASS

later:
component B changes
and B is unrelated to A

→ keep A evidence
→ test B
```

Do not rerun A merely because another edit occurred elsewhere.

---

# 8. Failure Handling

When a selected test fails:

1. inspect the failed test;
2. determine whether the failure is plausibly caused by the active change;
3. fix the smallest relevant cause;
4. rerun the failed test first;
5. rerun its local affected batch;
6. widen only if the failure suggests broader impact.

Do not react to every failure by immediately running the complete repository suite.

Never weaken a meaningful test merely to obtain a green result.

---

# 9. Unknown Impact

Uncertainty must be visible.

Use explicit descriptions such as:

```text
KNOWN:
- payments component changed
- payments unit tests affected

LIKELY:
- checkout integration may depend on modified API

UNKNOWN:
- could not establish whether reporting consumes this schema
```

If the uncertainty could hide a meaningful regression:

1. inspect the relationship;
2. update the testing state if resolved;
3. widen verification when necessary.

Do not claim successful scoped verification when material impact remains unknown.

---

# 10. Persistent Learning

When useful testing knowledge is discovered, update:

`.agent/TESTING-STATE.md`

Useful persistent knowledge includes:

* component topology;
* component dependency;
* SUT → test mapping;
* contract → consumer mapping;
* stable test command;
* stable test selector;
* expensive suite;
* required environment;
* common impact rule;
* testing gap;
* misleading or obsolete command.

Do not put raw terminal output or long logs into the state file.

Store concise, reusable knowledge.

---

# 11. Active Verification State

During a meaningful implementation slice, maintain the `Active Change` section of `.agent/TESTING-STATE.md`.

Record:

* changed surfaces;
* likely SUTs;
* known impact;
* likely impact;
* unknown impact;
* planned verification;
* completed verification;
* invalidated evidence.

This allows another agent or a later session to continue without reconstructing all testing context from scratch.

---

# 12. End-of-Slice Testing Report

At the end of a meaningful coding slice, provide a concise testing report:

```text
Changed:
- ...

Affected SUT:
- ...

Verification executed:
- <test/check> — <reason>

Evidence reused:
- <evidence> — <reason still valid>

Verification deliberately not executed:
- <suite/component> — <reason outside impact closure>

Unknown impact:
- ...

Result:
PASS | FAIL | BLOCKED | BROAD VERIFY REQUIRED

Full verification required now:
YES | NO

Reason:
...
```

The explanation should describe semantic reasons, not merely commands.

Prefer:

```text
Selected checkout contract tests because the modified
payments schema is consumed by checkout.
```

instead of:

```text
Ran ./gradlew test --tests CheckoutTest.
```

---

# 13. Forbidden Behaviors

Coding agents MUST NOT routinely:

* run all repository tests after every edit;
* run all repository tests before every commit;
* rerun already-fresh successful evidence;
* choose tests only from filenames;
* assume only same-language tests are affected;
* repeatedly rediscover runner syntax;
* invoke broad suites because selecting a subset requires thought;
* hide unknown impact;
* claim exhaustive impact analysis;
* change tests only to make builds green;
* interpret successful narrow testing as full-project verification.

---

# 14. Session Startup Protocol

When starting or resuming coding work:

```text
1. Inspect Git state.
2. Read `.agent/TESTING-STATE.md`.
3. Validate relevant cached knowledge against current repository state.
4. Identify the active SUT and impact.
5. Reuse known testing commands/selectors.
6. Construct the smallest justified verification plan.
7. Execute progressively.
8. Update evidence and testing knowledge.
```

Do not rediscover the complete project test topology on every session unless repository changes make the cached topology stale.

---

# 15. Session Handoff Protocol

Before ending a meaningful coding session, update `.agent/TESTING-STATE.md` with enough information for another agent to continue efficiently.

At minimum record:

```text
what changed
what was tested
what passed
what failed
what evidence remains fresh
what became stale
what impact remains unknown
what should be tested next
```

The handoff should prevent the next agent from unnecessarily repeating successful verification.

---

# 16. Guiding Rule

When deciding whether another test should run, ask:

> Does this test provide new evidence about a plausible effect of the current change?

If yes, run it.

If no, do not run it during the normal implementation loop.

If uncertain and the uncertainty is material, investigate or widen explicitly.

## COROUTINES — EXECUTION MECHANISM, NEVER DURABLE AUTHORITY (MANDATORY)

Validated by PAR-D (ADR-0076; receipt:
`docs/v2/07-uat/PAR_D_CLOSURE_RECEIPT.md`).

### Law

```text
Coroutines execute a previously determined typed decision;
they are not the authority for durable truth.
```

Operative pipeline:

```text
durable facts
    -> pure reconciliation
    -> typed decision
    -> coroutine execution / effects
```

### Consequences

- Coroutine completion or cancellation is NEVER, by itself, durable
  truth. Durable state changes only through the typed decision boundary
  and the journal (single writer).
- `CancellationException` is an execution mechanism: it MUST NOT be
  mapped to a generic infrastructure failure or to a terminal durable
  outcome.
- A coroutine failure used as control flow is forbidden on decision
  paths: branch/concurrent outcomes are typed values folded by the
  declared policy.
- Concurrency primitives (scope choice, dispatcher, structured scopes)
  MUST reflect — not define — the durable contract. A failing branch in
  a supervisor join is a contained typed outcome, not a cancellation of
  durable facts.

## EXPLICIT IMMUTABLE EXECUTION CONTEXT (MANDATORY)

Validated by CTX-P (receipt: `docs/v2/07-uat/CTX_P_CLOSURE_RECEIPT.md`).

### Law

```text
Concurrent execution context is explicit immutable data.
A branch or nested execution may derive a child context, but it MUST
NOT mutate parent or sibling execution context through coordinator
state, thread-locals, coroutine-locals, or global ambient state.
```

Operative forms:

```text
lexical scope:      parent context -- pure derivation --> child context
linearized scope:   Context_n + structural transition --> Context_n+1
```

### Consequences

- Execution context ownership MUST be explicit: passed as a value, never
  discovered by reading coordinator/global/thread/coroutine ambient state.
- The context value MUST NOT own journal, event sink, step registry,
  coroutine scope, or process executor. It is not a GodContext.
- Context transitions (e.g. structural scope enter/exit) are pure data:
  `(context, transition) -> nextContext`, fail-closed on invariant
  violation. No generic pop/drop; no finally-restore idiom over a shared
  authority.
- Sequential state transitions are valid; a shared mutable context
  authority is not. `immutable != stateless`.
- Preserved PAR-D law: the execution context is not durable truth;
  fingerprint, journal identity, and replay semantics are independent of
  context threading.
