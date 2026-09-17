# LFC-2E3-P / P3 — LOCAL EVENT TRANSPORT (RECONNAISSANCE + DESIGN, NOT YET IMPLEMENTED)

| Field | Value |
| --- | --- |
| Cycle | LFC-2E3-P — PLATFORM HARDENING |
| Slice | P3 — local event transport |
| Status | **P3.0 DONE** (protective fitness + RED). P3.1..P3.6 not started. Design frozen below. |
| Predecessors | P1 (`48e6b3b5`), P2 (`511ff329`), P2.1 (`c4b0d5a5`) |
| Law already in `AGENTS.md` | § LOCAL EVENT TRANSPORT (written ahead of implementation) |

This document exists so P3 does not begin by rediscovering its own blast radius. It records what
was verified about the existing event infrastructure and the exact integration points a new event
case must satisfy.

## 1. Why P3 is larger than P1/P2

The plugin-facing half of P1 and P2 was **additive in isolation**: a new interface, an optional IR
field, a new DSL function. A new `DomainEvent` case is not equivalent, because `DomainEvent` is a
**45-case sealed hierarchy with serialization and persistence machinery that switches over it
exhaustively**. Kotlin will reject every non-exhaustive `when`, so the change surfaces as compile
errors across modules — good for correctness, but it means the slice cannot be half-applied.

Verified integration points (all in `v2/pipeline-events/src/main/kotlin/dev/rubentxu/pipeline/v2/events/`):

| File | What it does per case | Cost of a new case |
| --- | --- | --- |
| `DomainEvent.kt` | declares the 45-case sealed hierarchy | +1 case with `eventId/runId/sequence/kind` |
| `JsonEventLog.kt` | `when (event)` emitting extra JSON fields per case | +1 arm |
| `SqliteEventStore.kt` | `when (event)` re-copying with the assigned sequence | +1 arm + persistence path |
| `InMemoryEventStore.kt` | same sequence-assignment switch | +1 arm |
| `identity/EnvelopeProjector.kt` | identity projection over events | +1 arm |
| `identity/SequenceAssigner.kt` | sequence assignment over events | +1 arm |

Each arm is mechanical (mostly `event.copy(sequence = assignedSequence)` or a JSON field block), so
the slice is bounded — but it must be done in one pass and verified with a serialization
round-trip plus a persistence round-trip, not just a compile.

## 1b. ARCHITECTURAL CORRECTION — one generic carrier, not plugin-specific cases

The first design added `DomainEvent.TestReportPublished` / `TestSuiteCompleted` /
`TestFailuresDetected` directly to the core hierarchy. **That was wrong**, and the six-switch
finding was the clue: it would mean every future plugin (coverage, SCM, HTTP, artifacts,
containers) re-editing core. The platform would claim "zero core edits per plugin" for Steps while
requiring six per plugin EVENT — false extensibility.

Corrected split:

```text
PipelineEventEnvelope            (canonical, core)
  +- eventId
  +- runId
  +- invocationId                (to add)
  +- sequence                    (from the canonical run mechanism, never the plugin)
  +- DomainEvent.PluginEvent     (the ONE generic carrier core learns ONCE)
        +- provider
        +- eventType             (plugin-owned, namespaced)
        +- schemaVersion
        +- payload               (encoded)
        +- resourceRefs
```

Core learns `PluginEvent` once; `utilities.*`, `testing.*`, `coverage.*`, `scm.*`, `http.*` and
`artifacts.*` never touch the hierarchy again.

Typing is preserved: events stay typed INSIDE the plugin (a sealed `TestingEvent` plus an explicit
codec to/from the carrier payload), and open ACROSS the platform boundary — the same
"closed core mechanics + open plugin catalog" shape as Steps.

## 1c. Implementation split (corrected)

```text
P3.0 RED + protection   done   prove a new plugin event currently needs core edits; freeze the
                               core catalog; forbid plugin-domain names in core
P3.1 generic carrier    next   DomainEvent.PluginEvent + wire the six integration points ONCE
P3.2 plugin event codec        typed plugin event -> encoded carrier
P3.3 capability                LOCAL_EVENT_PUBLISHER_CAPABILITY; host owns envelope/identity/sequence
P3.4 E3 testing events         publish the three typed testing events over the carrier
P3.5 replay/idempotency        stable eventIds + Harness dedupe by eventId
P3.6 observer isolation        a throwing subscriber cannot alter the committed canonical result
P3-GATE                        Lfc2PluginEventExtensibilityFitnessTest stays green
```

## 2. Design (frozen)

### 2.1 Direction — one way, never inverted

```text
canonical execution
  -> domain result committed
  -> authoritative journal state
  -> derive events
  -> LocalEventPublisher
  -> subscribers / Event Harness / history projection
```

```text
NEVER:  publish event -> treat publication success as execution authority
```

### 2.2 New `DomainEvent` case — the seven distinguished fields

```kotlin
/**
 * A domain observation DERIVED by a Step from its own committed result and published locally.
 *
 * Derived, never authoritative: the journal remains the authority for execution state.
 */
data class StepObservationPublished(
    override val eventId: String,        // stable identity, deterministic (see 2.3)
    override val runId: String,
    override val sequence: Long,         // explicit and deterministic; never derived from time
    override val occurredAt: Instant,
    val invocationId: String,            // the producing Step invocation (its operation id)
    val eventType: String,               // plugin-owned, namespaced
    val resourceRefs: List<String>,      // references to observed resources, NOT payload copies
    val payload: String,                 // bounded, plugin-owned encoded payload
) : DomainEvent {
    override val kind: String get() = "StepObservationPublished"
}
```

`invocationId` and `sequence` are the two that matter most: `sequence` means EVT-4 later never has
to reconstruct ordering from timestamps, and `invocationId` ties an observation to the exact
invocation that produced it.

### 2.3 Identity and duplicate semantics

`exactly-once` is **not** promised. The contract is:

```text
stable event identity
+  at-least-once / replayable publication
+  idempotent consumers
```

`eventId` must be derived deterministically from `(runId, invocationId, eventType, ordinal)` so a
replay reproduces the SAME logical identity rather than minting a fresh one. Consumers and the
Event Harness deduplicate by `eventId`; they MUST NOT assume they will never see the same physical
event twice.

### 2.4 Plugin-facing port (SDK-owned, mirroring the output resolver)

```kotlin
interface LocalEventPublisher {
    fun publish(observations: List<StepObservation>)
}
val LOCAL_EVENT_PUBLISHER_CAPABILITY: StepCapability = StepCapability("step.events")
```

Same pattern as `STEP_OUTPUT_RESOLVER_CAPABILITY` and `StepCapabilityContributor`: SDK-owned token,
runtime adapter, declared in `StepContract.requiredCapabilities`, fail-closed admission. The Step
never sees the `EventSink`, so it cannot emit arbitrary core kinds.

### 2.5 Authority separation (the acceptance law)

Publication happens **after** the operation is committed to the journal, and its failure is
swallowed by the adapter:

- a subscriber that throws must not change the step outcome or the run outcome;
- an absent publisher must not make a Step inadmissible unless it declares the capability;
- the journal row is written before any observation is published.

## 3. Volume rule (freeze now, enforce with a fitness row)

```text
events represent meaningful domain observations,
not a lossless echo of every parsed record
```

`TestSuiteCompleted` per suite is the pressure point the review identified: a report with thousands
of suites would emit thousands of events. Decide before implementing:

- either cap/aggregate suite-level observations behind a threshold, or
- emit a bounded summary plus references and let a consumer expand on demand.

The fitness row must assert that emission is a function of **observations**, not of input size —
reusing the shape of the E3-T3 derivation invariant
(`|derive(report)| == 1 + suites + (if hasTestFailures 1 else 0)`), which is already bounded by
suite count rather than case count. That existing invariant is the template: P3 should inherit the
same discipline rather than invent a weaker one.

## 4. Acceptance criteria (both are executable)

```text
A. junit with 1 failed test
   -> execution SUCCESS
   -> TestReportPublished observed
   -> TestFailuresDetected observed
   -> replay
   -> SAME logical event identities
   -> no duplicate terminal semantics

B. a subscriber throws
   -> the junit canonical result remains committed
   -> the run authority (outcome + journal) is unchanged
```

Criterion B is the one that actually proves authority/observability separation; A proves identity
stability across replay.

## 5. The first real consumer

The three E3 testing events (`TestReportPublished`, `TestSuiteCompleted`, `TestFailuresDetected`)
are already modelled and derived purely by `TestingEventDerivation` (E3-T3). P3 is what gives them
a transport. `core.junit` should publish them from its committed result, which also makes it the
first production publisher — and, together with P2.5, gives the eventual quality gate two real
producers to be designed against instead of an empty abstraction.

## 6. Explicitly out of scope

```text
network relay
controller
Jenkins integration
remote workers
exactly-once delivery
```

Those are EVT-4 and later. The P3 boundary is LOCAL.

## 7. Dashboard invariants that must stay 0

```text
legacy residual 0 · Step-specific core routing 0 · provider drift 0
capability drift 0 · certification drift 0 · old plugin ABI regressions 0
untyped output bindings 0 · event authority violations 0
```

`event authority violations` is the P3-specific one: any path where publication success, subscriber
failure or observer absence influences canonical execution state.

## 8. Entry state for the slice

```text
worktree  pipeline-wu-g5b
branch    cycle/wu-g5b
HEAD      c4b0d5a5
tree      clean
tests     239 plugin-suite tests, 0 failures, 0 errors
```

---

# P3.0 — DELIVERED

`Lfc2PluginEventExtensibilityFitnessTest` (4 rows). Exactly one row is RED; the other three already
pass and are the architectural protection:

| Row | State | Meaning |
| --- | --- | --- |
| `RED - core exposes exactly ONE generic carrier for plugin-authored events` | **RED** | P3.1 acceptance criterion; fails until `DomainEvent.PluginEvent` exists |
| `the core event catalog is frozen` | pass | the 45 core cases are pinned; any change is a deliberate edit to the frozen set |
| `no core event case is named after a plugin domain` | pass | `CoverageCalculated` / `JUnitPublished` / `ArtifactUploaded` in core is rejected BY NAME |
| `core serialization and persistence do not know plugin-owned event types` | pass | scans the six machinery files for plugin-domain event names |

This is the artifact that stops the architecture being re-closed in six months: the guards do not
depend on anyone remembering the rule, and the RED row states the P3.1 contract precisely.

## Volume contract (frozen, not yet implemented)

```text
1 TestReportPublished per report
0..N TestSuiteCompleted, one per LOGICAL suite, NEVER one per testcase
0..1 TestFailuresDetected per report (a SUMMARY: failedCount, errorCount,
     suiteCountWithFailures, reportRef -- not a copy of every failure)
```

```text
event volume = O(suites), not O(test cases)
```

No artificial hard cap: a cap would discard domain information arbitrarily. The contract is the
per-suite/per-report shape above, and details stay in `TestReport`.

## Identity contract (frozen, not yet implemented)

```text
eventId = hash(runId, invocationId, eventType, logicalEventKey)

logicalEventKey(TestReportPublished)   = report identity
logicalEventKey(TestSuiteCompleted)    = report identity + suite identity
logicalEventKey(TestFailuresDetected)  = report identity
```

A replay therefore reproduces the SAME logical `eventId`. `sequence` is NOT computed by the plugin:
it comes from the canonical run mechanism.

## A note on probe reliability (learned here)

The first version of the catalog probe used `DomainEvent::class.java.declaredClasses`. On a Kotlin
sealed interface that returns a synthetic **empty-named** entry, so the probe silently compared
against the wrong set. Switched to a source scan, which is also the idiom this repository already
uses for catalog fitness. Reflection over Kotlin sealed hierarchies is not a reliable catalog read.
