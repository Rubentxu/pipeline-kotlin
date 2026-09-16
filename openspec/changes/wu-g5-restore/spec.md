# Delta Spec: wu-g5-restore — make `waitUntil { body }` reach `dispatchRepeatUntilBody`

**Domain:** pipeline-scripting-api / pipeline-application / pipeline-domain
**Capability:** `core.waitUntil-canonical-reentry` (B14 lane, B11 W3b precedent)
**Authority:** ADR-0073 (BodyInvoker re-entry), B10 W1d (closed `BodyExecutionPolicy` ADT), B11 W3b (compiler exhaustiveness fitness), AGENTS.md §STEP CONSTITUTION (structural constructs are NOT registry Steps).
**Receipt of record (not modified):** `docs/v2/07-uat/B14_WAITUNTIL_G5A_INVALIDATION_RECEIPT.md`, commit `a31b2fa4`.

This delta formalizes what `proposal.md` authorized. It does NOT implement anything
and does NOT invent a new pattern: it tracks the same body-capture + ADT-case +
compiler-projection shape that B11 W3b certified for `WithEnv`/`Timestamps` projections.

---

## ADDED Requirements

### Requirement: wu-g5-restore — DSL captures the waitUntil body as data

The system MUST replace the terminal `StepSpec.WaitUntil(initialRecurrencePeriod, quiet)`
algebraic variant with `StepSpec.WaitUntilBlock(initialRecurrencePeriod, body: List<StepSpec>)`,
and the public `waitUntil { body }` DSL function MUST capture the body lambda as a
`List<StepSpec>` (via the same scope-threaded `steps.add(...)` mechanism used by
`retry`/`timeout`/`dir`) instead of evaluating the lambda eagerly at DSL-construction
time. The `quiet` field MUST be retained as a metadata field on the block.

#### Scenario: DSL waitUntil captures body without eager evaluation
- GIVEN a public DSL script:
  ```kotlin
  pipeline {
    stages { stage("s") {
      steps {
        waitUntil(initialRecurrencePeriod = 1L) {
          sh("test -f /tmp/marker")
        }
      }
    } }
  }
  ```
- WHEN the script compiles to its IR
- THEN the produced `StepSpec` list MUST contain a `StepSpec.WaitUntilBlock` with `body = listOf(<compiled sh step>)`
- AND the DSL function MUST NOT have invoked the body lambda during construction.

#### Scenario: eager-evaluation defect is rejected at L4 compile
- GIVEN the previous terminal `waitUntil(condition: () -> Boolean)` DSL signature
- WHEN the apply slice lands
- THEN no public DSL call site in the repository (including `v2/compatibility/*.pipeline.kts` and `v2/pipeline-application/test/...`) MAY remain that depends on `condition()` returning a `Boolean` synchronously.

---

### Requirement: wu-g5-restore — Compiler lowers WaitUntilBlock to BlockStepNode

The compiler (`DslCompiledPipelineCompiler.stepNode`) MUST contain an explicit case
`is StepSpec.WaitUntilBlock -> blockStepNode(...)` such that the body is propagated
into the resulting `BlockStepNode.body` and the `else -> OpaqueStepNode("core.${step.name}", ...)`
catch-all MUST NOT fire for this key. The propagated `pluginStepId` MUST be
`PluginStepId("core.waitUntil")`.

#### Scenario: compiled BlockStepNode carries RepeatUntil body
- GIVEN a `StepSpec.WaitUntilBlock(1L, listOf(<inner step>))`
- WHEN the compiler projects it
- THEN the produced `StepNode` MUST be a `BlockStepNode`
  - with `pluginStepId = PluginStepId("core.waitUntil")`
  - with `body` non-empty (the propagated inner steps)
- AND the produced `StepNode` MUST NOT be an `OpaqueStepNode` for this key.

---

### Requirement: wu-g5-restore — BodyExecutionPolicy gains RepeatUntil case

The closed ADT `BodyExecutionPolicy` MUST be extended with a new case
`RepeatUntil(val policy: RepeatUntilPolicy)`, becoming the 5th case alongside
`Sequential / Scoped / Retrying / Parallel`. `RepeatUntilPolicy` MUST be a typed value
class carrying `initialRecurrencePeriod: Duration` (and a `deadline` placeholder for
future expansion); the predicate that exits the loop MUST come from typed events
emitted by the body (e.g. `WaitUntilPredicateEvaluated`), NOT from a raw lambda in the
ADT. The `BodyExecutionPolicyShape` enum MUST add a matching `REPEAT_UNTIL` member and
the engine support declaration MUST advertise `REPEAT_UNTIL` before a `WaitUntilBlock`
is admitted to run.

#### Scenario: engine support declares RepeatUntil
- GIVEN an engine that supports `BodyExecutionSupport.SCOPED_SEQUENTIAL_RETRYING`
- WHEN a descriptor declares `body = StepBody.Declared(invocation, BodyExecution(owner, RepeatUntil(RepeatUntilPolicy(initialRecurrencePeriod = 1.millis))))`
- THEN the engine MUST be extended to advertise `REPEAT_UNTIL` in its `supports` set
- AND a `WaitUntilBlock` MUST be admitted to run (not rejected with `UnsupportedByEngine`).

---

### Requirement: wu-g5-restore — Canonical dispatch reaches dispatchRepeatUntilBody

For a `BlockStepNode` carrying `BodyExecutionPolicy.RepeatUntil`, the coordinator MUST
route it through `BlockShellScope.RepeatUntil` and `dispatchRepeatUntilBody`, which
re-enters the engine via `BodyInvoker.invoke` (ADR-0073). The legacy
`CanonicalWaitUntilNodeDispatcher` MUST remain physically present during RESTORE
(its removal is WU-G5B scope). No parallel `dispatch*Block` collection MAY exist
besides the canonical dispatch loop.

#### Scenario: dispatchRepeatUntilBody executes body repeatedly until predicate true
- GIVEN a `BlockStepNode(BodyExecutionPolicy.RepeatUntil(RepeatUntilPolicy(initialRecurrencePeriod=1.millis)))`
  whose body step emits `WaitUntilPredicateEvaluated(true)` on the 3rd attempt
- WHEN the coordinator dispatches the node
- THEN `dispatchRepeatUntilBody` MUST be invoked exactly once
- AND the body MUST be re-entered through `BodyInvoker.invoke` at least twice
- AND the journal MUST contain a `WaitUntilPolled` event per attempt
- AND the terminal event MUST be `WaitUntilCompleted` from the canonical path.

---

### Requirement: wu-g5-restore — Fitness obligations

Three structural fitness tests MUST be added under `pipeline-architecture-tests`:

1. `Lfc2WaitUntilDslDoesNotLowerToOpaqueStepFitnessTest`: for any
   `StepSpec.WaitUntilBlock` the produced `StepNode` is a `BlockStepNode` (NOT an
   `OpaqueStepNode("core.waitUntil", ...)`). State during RESTORE: RED, GREEN after
   WU-G5R.3.
2. `Lfc2WaitUntilCanonicalReentryFitnessTest`: the dispatched scope kind for
   `BodyExecutionPolicy.RepeatUntil` is `BlockShellScope.RepeatUntil` and
   `dispatchRepeatUntilBody` is the only execution route (no parallel dispatch
   collection). State during RESTORE: RED, GREEN after WU-G5R.4.
3. `Lfc2WaitUntilNoLegacyRoutingFitnessTest`: no production source (outside
   `application/durable/CanonicalWaitUntilNodeDispatcher.kt` and its documented
   imports) references `core.waitUntil` routing through the legacy `dispatchStub`.
   State during RESTORE: intentionally RED (legacy dispatcher still present).
   State after WU-G5B: GREEN.

#### Scenario: fitness layout at WU-G5-RESTORE close
- GIVEN WU-G5R.1..4 are all complete
- THEN `Lfc2WaitUntilDslDoesNotLowerToOpaqueStepFitnessTest` MUST be GREEN
- AND `Lfc2WaitUntilCanonicalReentryFitnessTest` MUST be GREEN
- AND `Lfc2WaitUntilNoLegacyRoutingFitnessTest` MUST be RED (legacy present, intentional)
- AND the previous G5a GREEN evidence MUST remain in the unit suite (reconciler, journal contract suite) but MUST be explicitly marked INVALIDATED in the inventory row for `core.waitUntil`.

---

### Requirement: wu-g5-restore — End-to-end example

A real `.pipeline.kts` example `v2/compatibility/22-wait-until.pipeline.kts` MUST exist
that demonstrates `waitUntil(initialRecurrencePeriod = ...) { sh("...") }` with a body
that exits the loop on its own (e.g. writes a marker file then the predicate step
verifies its presence). The example MUST be runnable via the installed CLI in fresh,
`--rerun`, and `--resume` modes. The journal MUST record `WaitUntilPolled`/
`WaitUntilCompleted` from the canonical path so they are structurally distinguishable
from any legacy-stub events.

#### Scenario: 22-wait-until.pipeline.kts exits 0 and dispatchRepeatUntilBody is the emitter
- GIVEN the installed CLI runs the example with `--rerun`
- WHEN the run completes
- THEN the exit code MUST be 0
- AND the journal MUST contain at least one `WaitUntilPolled` event emitted from the canonical path
- AND the terminal `WaitUntilCompleted` event MUST be emitted from the canonical path
- AND `dispatchRepeatUntilBody` MUST have been invoked at least once
  (verified by a thread-local sentinel set inside that function).

---

## MODIFIED Requirements

### Requirement: BodyExecutionPolicy — closed ADT gains `RepeatUntil` case

The closed ADT `BodyExecutionPolicy` MUST gain a 5th case
`RepeatUntil(val policy: RepeatUntilPolicy)` alongside `Sequential`/`Scoped`/
`Retrying`/`Parallel`; no other shape changes. The companion object's case count
documentation MUST be updated to read "5 cases". `BodyExecutionPolicyShape` MUST gain
a matching `REPEAT_UNTIL` member. The `BodyExecutionSupport.SCOPED_SEQUENTIAL_RETRYING`
companion MUST add `REPEAT_UNTIL` to its `shapes` set (or a new constant
`..._RETRYING_REPEAT_UNTIL` MUST exist), since `core.waitUntil` admits through the
same canonical body engine as `core.dir` / `core.timeout` / `core.retry`.

(Previously: 4 cases — `Sequential`/`Scoped`/`Retrying`/`Parallel`.)

#### Scenario: ADT exhaustiveness over the closed family
- GIVEN the ADT now has 5 cases
- WHEN any code compiles `when (policy: BodyExecutionPolicy)` without an `else`
- THEN the Kotlin compiler MUST enforce exhaustiveness over the 5 cases (verified by `Lfc2BodyExecutionPolicyFitnessTest` turning RED if a case is added without revisiting every `when`).

#### Scenario: companion shape set contains REPEAT_UNTIL
- GIVEN the engine supports `WaitUntilBlock`
- WHEN `BodyExecutionSupport.SCOPED_SEQUENTIAL_RETRYING_REPEAT_UNTIL.supports(RepeatUntil(...))` is invoked
- THEN the result MUST be `true`.

---

### Requirement: StepSpec algebra — `WaitUntil` is replaced by `WaitUntilBlock`

`StepSpec.WaitUntil(initialRecurrencePeriod, quiet)` is REMOVED and replaced by
`StepSpec.WaitUntilBlock(initialRecurrencePeriod, body: List<StepSpec>, quiet: Boolean = false)`.
The `quiet` field is RETAINED as a metadata field on the block (the canonical path does
not consume it today; flagged for future expansion).

(Previously: terminal `WaitUntil(initialRecurrencePeriod, quiet)` — no body field, eager-evaluated condition in DSL.)

#### Scenario: algebraic variant carries a body
- GIVEN `StepSpec.WaitUntilBlock(1L, listOf(<inner>), quiet = false)`
- WHEN the compiler projects it
- THEN `body.isNotEmpty()` MUST hold
- AND no production source outside the DSL fun MAY construct `StepSpec.WaitUntil` (the old name is gone).

---

### Requirement: DSL `waitUntil` signature — body lambda replaces condition lambda

The `condition: () -> Boolean` lambda parameter on the public `waitUntil` function
MUST be replaced by a `body: StepsScope.() -> Unit` lambda parameter (same shape as
`retry`/`timeout`), captured as a `List<StepSpec>` and added to the surrounding
`StepsScope`. The eager `condition()` invocation at construction time MUST be REMOVED.

(Previously: `waitUntil(initialRecurrencePeriod = 1L, quiet = false, condition: () -> Boolean)` — body evaluated eagerly, result discarded if true, exception thrown if false.)

#### Scenario: captured body becomes StepSpec.WaitUntilBlock
- GIVEN the public DSL fun `waitUntil(initialRecurrencePeriod = 1L) { sh("test -f marker") }`
- WHEN the fun returns
- THEN `steps.last()` MUST be a `StepSpec.WaitUntilBlock(1L, listOf(<compiled sh>))`
- AND no synchronous `RuntimeException("waitUntil condition evaluated to false")` may be raised.

---

### Requirement: W3b exhaustiveness fitness — auto-discovers WaitUntilBlock

The auto-discovery in `Lfc2BlockStepCompilerBodyExhaustivenessFitnessTest` MUST pick
up `StepSpec.WaitUntilBlock` automatically (no manual fixture update required). Adding
the variant MUST flip the fitness RED → GREEN by construction (the discovery logic
enumerates sealed `StepSpec` subtypes by reflection); a small follow-up commit to give
the discovery a typed-Variant signal is acceptable if the auto-discovery cannot detect
the new variant on its own.

(Previously: fitness was exhaustive over the prior StepSpec subtype list; `WaitUntilBlock` is the new addition.)

#### Scenario: W3b exhaustiveness goes RED → GREEN by construction
- GIVEN a fresh compile where `StepSpec.WaitUntilBlock` has no compiler case
- WHEN the fitness runs
- THEN it MUST be RED (the new variant lowers to `OpaqueStepNode` via the `else` branch).
- AND after WU-G5R.3 lands, the same fitness MUST be GREEN without manual fixture changes.

---

### Requirement: Step inventory row for `core.waitUntil` at RESTORE close

The row for `core.waitUntil` in `docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md` MUST be
updated at WU-G5-RESTORE close to:
- `Path = structural`
- `StepDefinition = N/A (structural, not a Step)`
- `Canonical = Y (BlockStepNode + BodyExecutionPolicy.RepeatUntil + dispatchRepeatUntilBody)`
- `Legacy = Y (still present; removal is WU-G5B scope)`
- `State = IMPLEMENTED_UNCERTIFIED` with
  - `canonical_path_implemented = true`
  - `canonical_path_user_reachable = true` (← flipped from `false` by RESTORE)
  - `previous_g5_evidence = INVALIDATED`
  - `blocking_gap = (none remaining; WU-G5B owns legacy removal)`.

(Previously: `core.waitUntil | CORE candidate | legacy | Y | N | Y (CanonicalWaitUntilNodeDispatcher) | Y | Y/N | N | — | — | — | IMPLEMENTED_UNCERTIFIED`.)

#### Scenario: inventory row reflects RESTORE state
- GIVEN `WU_G5_RESTORE_CLOSURE_RECEIPT.md` is committed
- WHEN the inventory row is opened
- THEN `canonical_path_user_reachable = true` MUST hold
- AND `previous_g5_evidence = INVALIDATED` MUST hold
- AND `Legacy` MUST still be `Y` (intentional — WU-G5B owns removal).

---

## REMOVED Requirements

(none this slice. The terminal `StepSpec.WaitUntil` algebraic variant is RENAMED, not
removed from the algebra — `WaitUntilBlock` is its structural replacement.)

---

## Cross-cutting Constraints

These constraints are mechanical invariants enforced by AGENTS.md and the cited ADRs.
They are not testable scenarios — they are laws the slice MUST preserve.

- AGENTS.md §STEP CONSTITUTION: `core.waitUntil` MUST NOT appear in
  `CoreStepRegistryFactory` after this slice. The `CoreWaitUntilStep.registerInto(this)`
  call in that factory (verified on HEAD) MUST be removed; `core.waitUntil` is a
  structural construct (ADR-0073), NOT a registry Step.
- AGENTS.md §10 (DSL describes; interpreters execute): the DSL function MUST NOT
  invoke the body lambda during construction; capturing it as a `List<StepSpec>` is
  sufficient.
- AGENTS.md §7 (decide purely, then interpret): `WaitUntilReconciler` MUST be a pure
  function; the coordinator interprets.
- AGENTS.md §8 (ADT-first modelling): `BodyExecutionPolicy.RepeatUntil` is a closed
  ADT case, not a `Boolean` flag.
- B10 W1a pinned concrete routing debt: ledger stays at 0; no new `dispatch*Block`
  literal introduced.
- B10 W1d closed ADT: `BodyExecutionPolicy` adds a new case; no default collapse.
- B11 W3b exhaustiveness fitness: the new `WaitUntilBlock` variant MUST be picked up
  by the auto-discovery.
- Pre-existing reds (W1a..W1d baseline: `CanonicalDurableRunCoordinatorTest 26/11`,
  `CompatibilityCorpusTest 20/2`, `Lfc0GlobalStateFitnessTest 1`, UAT 005/007/008/009,
  fixture14) MUST NOT widen.
- Invalidation receipt `B14_WAITUNTIL_G5A_INVALIDATION_RECEIPT.md` (commit `a31b2fa4`)
  is the receipt of record; this spec references it by SHA and does NOT modify it.
- The legacy `CanonicalWaitUntilNodeDispatcher` MUST remain physically present
  during RESTORE; its removal belongs to WU-G5B (separate cycle).
- Re-adding `core.waitUntil` to `LEGACY_PLUGIN_IDS` after RESTORE closes is FORBIDDEN:
  the slice flips reachability, not the LEGACY_PLUGIN_IDS membership; that flip is
  WU-G5B's job.

---

## Out of Scope (explicit non-goals)

- Physical removal of `CanonicalWaitUntilNodeDispatcher` (WU-G5B).
- Removing `"core.waitUntil"` from `LEGACY_PLUGIN_IDS` (WU-G5B).
- Removing `CoreWaitUntilStep.registerInto(this)` from `CoreStepRegistryFactory`
  if the slice's structural migration supersedes it (WU-G5B or this slice — decide
  during design; for spec purposes the registry registration is REMOVED, see the
  cross-cutting constraint above).
- New `core.*` Step implementations.
- New plugin extensions or `StepDefinitionContributor`s for `core.waitUntil`.
- Re-introducing `dispatchRetryBlock`/`dispatchTimeoutBlock` style collections.
- `ReplayPolicy.NEVER` semantics for waitUntil (the body re-executes on resume; the
  control journal is the durability anchor, mirroring RETRY-D).
- Schema migrations on the durable store (RESTORE adds no new control rows; the
  `FileBasedWaitUntilControlJournal` is created fresh at first run).

---

## Testable Surface (informative, not normative)

These are the tests the apply slice is expected to add or flip; they are NOT
requirements — they are the natural verification path implied by the requirements
above. The design phase may restructure them.

- `Lfc2WaitUntilDslCanonicalProjectionTest` (RED characterization today; flips
  GREEN at WU-G5R.3).
- `Lfc2WaitUntilDslDoesNotLowerToOpaqueStepFitnessTest`
  (RED today; GREEN at WU-G5R.3).
- `Lfc2WaitUntilCanonicalReentryFitnessTest` (RED today; GREEN at WU-G5R.4).
- `Lfc2WaitUntilNoLegacyRoutingFitnessTest` (intentionally RED during RESTORE).
- `WaitUntilReconcilerTest` (pure; already present per `B14` invalidation — retained).
- `WaitUntilControlJournalContractSuite` (already present — retained).
- `:pipeline-scripting-api:test` — DSL fun change (RED on `condition`-using call sites
  if any survive; should not).
- `v2/compatibility/22-wait-until.pipeline.kts` — installed-CLI exercise in fresh,
  `--rerun`, and `--resume` modes.

---

## Knowledge Graph (informative)

If/when the SDDK knowledge graph is enabled for this project, durable requirement
nodes corresponding to the ADDED requirements above MUST be created under
`{vault}/specs/pipeline-scripting-api/REQ-wu-g5-restore-*.md` and linked from
`{vault}/_log.md`. This is the SDDK persistence contract; for the openspec-only
slice (current state), the spec file IS the durable artifact.
