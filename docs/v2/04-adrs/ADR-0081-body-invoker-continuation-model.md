---
type: adr
id: ADR-0081
title: "BodyInvoker / Continuation Execution Model — typed body seam and durable control rows"
status: proposed
date: 2026-09-12
deciders: "Rubentxu (product owner)"
supersedes: null
superseded_by: null
related:
  - ADR-0070  # Invoke → Registry → capability admission → handler
  - ADR-0073  # block-step re-entry through BodyInvoker (this ADR concretizes it)
  - ADR-0075  # retry control rows — the durable control-row pattern reused here
  - ADR-0076  # coroutines as execution mechanism, never durable authority
  - ADR-0069  # replay semantics policy
  - docs/v2/08-spikes/WAITUNTIL_BODY_INVOKER_SPIKE.md  # driving use case
---

# ADR-0081 — BodyInvoker / Continuation Execution Model

## Context

ADR-0073 decided that block Steps re-enter the engine through `BodyInvoker` /
`BranchInvoker` and banned the `dispatch*Block` method collection. It did not,
however, define the representation. What exists today in the code:

- The engine's body machinery is the coordinator's `dispatchBody` over the closed
  `StepNode` ADT (`OpaqueStepNode` / `BlockStepNode(body: List<StepNode>)`), with
  retry/timeout folding typed decisions from durable control rows (ADR-0075).
- No `BodyInvoker`, `BranchInvoker`, `BodyRef`, or `BodyOutcome` type exists in
  any module (verified by the WAITUNTIL spike, §2).
- `core.waitUntil` is blocked (`WAITUNTIL_BODY_INVOKER`): its condition is a
  non-serializable Kotlin lambda. It is not a list of child `StepNode`s, so the
  current `BlockStepNode.body` representation cannot express it, and the DSL
  evaluates the condition eagerly at construction time (a frozen DSL/runtime
  violation).

Every future control-flow Step (retry, timeout, catchError, waitUntil, and any
external plugin Step with a body) needs ONE answer to: how does a Step own a
body, how is that body identified durably, and what is the typed result of
invoking it?

## Decision

### D1 — One typed seam, injected as a capability

A block Step's handler receives body re-entry ONLY through a declared capability
injected like every other capability (`StepCapabilityAccess`, fail-closed
admission before the handler runs, ADR-0070):

```kotlin
// pipeline-domain (inner contract; no engine, journal, or process dependency)
fun interface BodyInvoker {
    suspend fun invoke(body: BodyRef, context: BodyInvocationContext): BodyOutcome
}

val BODY_INVOKER_CAPABILITY: StepCapability = StepCapability("bodyInvoker")
```

There is no second path. A handler that never re-enters the engine does not
declare the capability; a handler that does is rejected at prepare time when the
engine has not bound it. This generalizes, and does not replace, the existing
core capability set (`EVENT_SINK_CAPABILITY`, `SHELL_OPERATIONS_CAPABILITY`, ...).

### D2 — BodyRef: serializable body identity, not body content

A body is referenced by value-identity, never by a lambda or by the handler
holding a `List<StepNode>`:

```kotlin
@JvmInline @Serializable
value class BodyRef(val encoded: String)

// Closed factory surface (plugins construct only through these; the ADT stays closed)
object BodyRefs {
    fun childBody(bodyPath: List<BlockSegment>): BodyRef
    fun branchBody(parentPath: List<BlockSegment>, branchIndex: Int, key: PluginStepId): BodyRef
    fun namedBody(parentPath: List<BlockSegment>, name: String): BodyRef
}
```

- `BodyRef` is derived deterministically from the durable execution identity
  already owned by the engine (`OpId`'s `bodyPath: List<BlockSegment>`). The
  encoding is the canonical `bodyPath` join; the ref is therefore stable across
  process restarts and is the natural key for journal rows, control rows, and
  event correlation.
- The DSL/handler NEVER captures executable content in a `BodyRef`. Runtime
  content (e.g. a condition lambda for `waitUntil`) travels through a separate
  typed capability bound at dispatch time (e.g. a `ConditionEvaluator` under
  `conditionEvaluation`); it never serializes. A `BodyRef` names a body; the
  capability supplies what to do inside it.
- External plugins get the same factories via the public SDK surface. No plugin
  constructs a `BodyRef` by string-munging; unknown encodings fail closed at the
  adapter (`BodyRef` decode belongs to the engine adapter, mirrors codec
  discipline).

### D3 — BodyOutcome: closed typed algebra, never booleans or exceptions

```kotlin
sealed interface BodyOutcome {
    data class Completed(val outcome: StepOutcome) : BodyOutcome   // reuses the closed StepOutcome algebra (Success / Unstable / Failure(PipelineFailure))
    data class Cancelled(val reason: CancellationReason) : BodyOutcome
}
```

- `Completed(StepOutcome.Failure)` is a contained typed result: block Steps like
  `catchError` fold it per their declared policy. It is NOT a pipeline abort.
- `Cancelled` distinguishes structured-concurrency cancellation from a step
  failure (ADR-0076 law: cancellation is an execution mechanism, mapped to its
  own typed case, never reclassified as INFRASTRUCTURE). `CancellationReason`
  is a closed ADT (`Deadline`, `ParentCancelled`, `ScopeShutDown`).
- Invoker infrastructure defects throw; everything else is a typed case.

### D4 — BodyInvocationContext: explicit immutable derivation, not ambient

Per the CTX-P law, the context handed to `invoke` is explicit immutable data
derived by the caller:

```kotlin
data class BodyInvocationContext(
    val attempt: AttemptSegment? = null,   // per-attempt identity (retry) — appended to bodyPath as a BlockSegment
    val patch: ExecutionContextPatch = ExecutionContextPatch.None,  // dir/withEnv/credentials scope patches
)
```

- Scope patches (`dir`, `withEnv`, `withCredentials`) are typed values on the
  context, projected by the engine adapter into `BlockShellScope`-equivalent
  structure — the coordinator's existing scope projection remains the substrate;
  the port does not reimplement it.
- Contexts derive child contexts purely; no thread-local/coroutine-local/global
  ambient state; the context does not own journal, registry, scope, or executor.

### D5 — Replay: per-attempt durable control rows, anchored on BodyRef

The durable truth of a body invocation is a control row keyed by the deterministic
`BodyRef` (+ attempt segment when retrying), persisted BEFORE child effects,
mirroring ADR-0075:

- control row = durable state; body events (`StepStarted/StepFinished` of
  children, per-poll events of loops) = observability only;
- `plan()`-style reconciliation skips terminal attempts that already have a
  successor;
- a terminal row is the reuse authority for `ReplayPolicy.MEMOIZED` bodies;
  events and in-memory counters are never authoritative;
- waitUntil-style loops reuse the same rows: each poll attempt is a row
  `(attempt, lastPolledAt, conditionResult)`, and the terminal
  `WaitUntilCompleted` row is the memoization anchor. Resume-mid-wait is
  therefore a journal read, not a re-run from attempt 1 — the W2 open question
  of the spike resolves in favor of the control-row journal, not the weaker
  "journaled terminal only" anchor.

### D6 — Nested scopes compose; no privileged paths

Nested blocks append `BlockSegment`s to the `bodyPath` the engine already
threads through `dispatch`/`dispatchBody` — nesting depth is expressed by path
length, not by wrapper objects. `parallel` is `Named` bodies through the same
seam (a `BranchInvoker` MAY be derived later as a thin fan-out of `BodyInvoker`
plus the ADR-0076 join policy; defining it is NOT part of this slice). Core
block Steps and external plugin block Steps take the identical path; there is no
core-only shortcut.

### D7 — Event correlation

Every body invocation emits events correlated by the `OpId` implied by the
`BodyRef`'s `bodyPath` (runId + stageIndex + stepIndex + bodyPath). Per-step
observability events remain the child steps' own typed events; the invoking
block Step additionally emits its family events (e.g. `WaitUntilPolled` per
attempt, `RetryAttemptScheduled`). No new correlation identifier is introduced.

### D8 — Cancellation propagation

`BodyInvoker.invoke` is `suspend`; structured concurrency propagates parent
cancellation into the body naturally, and the adapter maps coroutine
cancellation to `BodyOutcome.Cancelled` at the seam — never to
`StepOutcome.Failure(INFRASTRUCTURE)` and never to a swallowed exception
(ADR-0068 / ADR-0076). Cancellation does NOT write a terminal durable row: a
cancelled body has no result; the run's journal state decides on resume.

### D9 — Retry representation

Retry of a body is expressed as N invocations with `attempt` segments on
`BodyInvocationContext` — the engine appends
`BlockSegment(attempt, PluginStepId("retry-attempt"))` exactly as
`dispatchBody` does today. The retry aggregate remains a durable control row
(ADR-0075); the migration migrates the coordinator's retry loop onto this seam
in a later slice without changing its durable semantics.

### D10 — External plugin access

`BodyInvoker`, `BodyRef`/`BodyRefs`, `BodyOutcome`, and
`BodyInvocationContext` live in `pipeline-domain` (inner seam) and are exported
through the public plugin SDK surface. A plugin declares
`BODY_INVOKER_CAPABILITY` in `StepContract.requiredCapabilities` exactly like
core Steps. Zero production-core semantic changes are required for a plugin to
add a block Step.

## What this ADR does NOT decide

- `BranchInvoker` / parallel join policy specifics (ADR-0076 remains the
  authority; a later slice derives `BranchInvoker` if needed).
- The DSL capture form for non-Step bodies (e.g. how `waitUntil { condition }`
  binds a condition ref at scripting time) — that is a scripting-host adapter
  concern, sequenced as spike phase W3.
- Migration order of existing `dispatchBody` call sites onto the port.

## Consequences

- The coordinator's body machinery remains the single execution substrate; the
  port is a thin typed façade over it, so `LEGACY_REMOVED`/fitness counters are
  unaffected by this slice (contract only).
- `core.waitUntil` becomes a SMALL lift: condition capability + poll loop in the
  handler + control rows per D5 (loop logic already characterized in
  `CanonicalWaitUntilNodeDispatcher.dispatch`).
- A new failure class is now expressible: `BodyOutcome.Cancelled`. Engine
  adapters MUST map it before `StepOutcome` classification, or the
  ADR-0068/0076 mapping laws are violated.
- Fitness gates (closed ADT, no `when(stepKey)` routing, capability ==
  declared==used) apply unchanged to the adapter that binds
  `BODY_INVOKER_CAPABILITY`.
