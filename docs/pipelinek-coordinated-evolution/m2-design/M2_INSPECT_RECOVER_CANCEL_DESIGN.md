# CRIC-M2 — INSPECT / RECOVER / CANCEL design (closes B.1–B.8)

**Status:** PROPOSED. This is the design that consumes the M2 audit
(`docs/pipelinek-coordinated-evolution/m2-design/M2_INSPECT_RECOVER_CANCEL_AUDIT.md`,
commit `dc047cb2`) and produces the contract the next block (`M2-Impl`)
implements against.

**Worktree:** `pk-cric-m2` (branch `audit/cric-m2-inspect-recover-cancel`)
at `dc047cb2`. The audit branch is remote-only; this design lands on
`main` as a docs-only commit. **Read-only:** this document introduces
no code change and no new authority.

**Anchor:** the Block 2 plan (*"auditar primero las interfaces públicas
existentes, la autoridad única de recovery, el journal privado y las
políticas de efectos. Implementar únicamente los puertos públicos
faltantes para inspección no destructiva, cancelación y recuperación
segura. No mover leases, fencing o scheduler a PipelineK. Tests:
caídas en varias ventanas, intentos repetidos, observación ambigua,
interrupción, journal incompatible y cero duplicación de efectos.
Validación integrada con Fabric sobre UAT-FW y AAT-05..10."*) and the
normative text in `coordination/INTERFACE_CONTRACT.md` §5:
*"Runtime inspeccionable: `inspect/recover/cancel/follow` mediante
interfaces segregadas, reutilizando los puertos públicos REALES de
PipelineK. Una lectura no ejecuta recuperación destructiva; no relanza
efectos externos automáticamente."*

## 0. Re-use, do not duplicate

The audit (§A.1–A.7) confirms that every primitive the M2 contract
needs already exists. The rule this design sharpens:

| TRACK B requirement                      | Existing primitive (re-use, no new authority) | What changes for M2 |
|---|---|---|
| Run-level state read (Inspect)           | `EventHistory.history` (`pipeline-events:…/events/identity/EventHistoryPorts.kt:151-153`); `EventTail.readAfter` (`:159-161`); `OutputFrameIndex.streamsOfRun` + `framesOfRun` (`pipeline-output:…/output/OutputFrameIndex.kt:140-200`); `OutputTailPort.tailState` (`pipeline-output:…/output/OutputTailState.kt:86-96`); `OperationJournal.listForRun` + `getEndedAt` (`pipeline-events-store:…/events/durable/OperationJournal.kt:67-89`); `ReplayCursorStore.load` (`pipeline-events-store:…/events/durable/ReplayCursorStore.kt:16-64`) | **NEW public port** that reconciles these into one typed `RuntimeObservation`. The M2 port is a thin composition; no port listed above is re-implemented. |
| "Is this run terminal?" typed answer     | `OperationJournal.listForRun` returns the journal rows in order (`OperationJournal.kt:67-71`); the journal's terminal row carries `ended_at` (`OperationJournal.kt:81-89`) | A new read-side method `OperationJournal.findTerminal(runId)` is **NOT** introduced (audit G.1). The `RuntimeIntrospectionPort` derives terminality by composing the existing `listForRun` + the existing `ReplayCursorStore.load`; that is consistent with the audit's B.4 reasoning. |
| Recover without re-executing effects     | `EffectReplayPolicy.decide` (`pipeline-step-sdk/runtime:…/durable/EffectReplayPolicy.kt:63-95`, normative matrix pinned by `EffectReplayPolicyTableFitnessTest`); `OperationJournal.append` / `beginOperation` (`OperationJournal.kt:28-130`); `ReplayCursorStore.advance` (`ReplayCursorStore.kt:16-64`); `OutputRecoveryPort.recover` (`pipeline-output-store:…/output/store/OutputWritePorts.kt:166-176`); `OutputFrameIndex.recoverUnframedBytes` (`OutputFrameIndex.kt:107-200`); `RecoveredExecutionMaterializer.materialize` (`pipeline-application:…/durable/RecoveredExecutionMaterializer.kt:60-`); `RecoveryInterpretationEngine.interpret` (`pipeline-application:…/durable/RecoveryInterpretationEngine.kt:65-348`, internal). `RunningSubprocessRecovery.observe` (`pipeline-application:…/durable/RunningSubprocessRecovery.kt:65-80`, internal). | **NEW public port** `RuntimeRecoverPort.recover(runId, options)` + a new pure-decider object `RuntimeRecoverDecision.decideRecovery(observation, journal): RecoveryChoice`. The decider wraps the existing `EffectReplayPolicy.decide` matrix; the recover port wraps the existing journal + replay cursor + output-recovery path. |
| Cancel, terminal + idempotent            | `RunExecutionLease.acquire` + `RunExecutionLease.authorisePublish` + `RunExecutionLease.release` (the pure decider at `pipeline-events-store:…/events/durable/RunExecutionLease.kt:50-309`); `FileBackedRunExecutionLeaseStore.acquire` (`:40-253`, the OS-lock + fencing-token store); `OperationJournal.append` for the terminal row (`OperationJournal.kt:30-36`); `OutputSealPort.seal` (`pipeline-output-store:…/output/store/OutputWritePorts.kt:205-218`); `ObservationWakeup` (`pipeline-application:…/observation/ObservationWakeup.kt:35-89`, the existing wakeup vocabulary; not wired today — see §6.4). | **NEW public port** `RuntimeControlPort.cancel(runId, reason): CancelOutcome`. Cancel consults the existing `RunExecutionLease` (the pure decider — NOT the file-backed store, which stays internal-to-PK) and writes a terminal row via the existing journal. It does NOT take a fresh lease. |
| Pure decision observable / testable     | `PK-SPEC-02-RUNTIME-OBSERVATION.md` §"Modelo funcional" already sketches `decideRecovery(observation, journal): RecoveryChoice`. | **NEW public pure-decider** `RuntimeRecoverDecision.decideRecovery(observation, journal): RecoveryChoice`. No effect. Same shape the spec already pins. |
| Refusal vocabulary                       | `OutputRefusal` (`pipeline-output:…/output/OutputRefusal.kt:14-60`); `EventRecordReadRefusal` (M1-A, `pipeline-events:…/events/identity/EventRecordReadPort.kt:103-122`); `EventFollowRefusal` (M1-C, `pipeline-events:…/events/follow/EventFollower.kt`). | **NEW sealed refusal ADTs per port**, in the same convention: `IntrospectionRefusal`, `CancelRefusal`, `RecoverRefusal`. No `Either`/`Result` re-use. |
| Capabilities advertised                  | `output.follow.v1`, `events.follow.v1` (CRIC-M1, `coordination/INTERFACE_CONTRACT.md` §6 + the `Capacidades publicadas` table). | **NEW** static capability strings `runtime.inspect.v1`, `runtime.cancel.v1`, `runtime.recover.v1` in the same `Capabilities` companion per-module. |
| Coordination: a read MUST NOT execute destructive recovery | The audit's §D.1 verdict. The `RuntimeIntrospectionPort` MUST NOT invoke `OutputRecoveryPort.recover()` or any journal write. | Pinned by a contract test (§7.1, `IntrospectionDoesNotMutateStateTest`). |

The audit identified **0 public Cancel ports** and **0 public Recover
ports**. M2 introduces exactly three public ports, each adding **no new
authority** — every authority the M2 ports touch is named in the table
above with `module:file:line`, and the design's composition contract
(§6) re-states the same names.

## 1. Public capability identifiers

The audit (G.5) and the M1 design pattern (M1 §1) agree: capability IDs
are static strings published per module. The M2 first cut advertises:

- `runtime.inspect.v1` — run-level typed view of an in-flight or
  terminal run; exposed by the new `:pipeline-runtime` module (§2.1).
- `runtime.cancel.v1` — terminal, single-shot, idempotent cancel;
  exposed by `:pipeline-runtime`.
- `runtime.recover.v1` — idempotent recovery without re-executing
  external effects; exposed by `:pipeline-runtime`.

These are static strings published in a `Capabilities` object in
`:pipeline-runtime`. They are NOT discovered through `@PublishedApi`
(that annotation is for inline-function visibility, not runtime
capability advertisement — see M1 §1 revision note 4). The M2 first
cut advertises them as `EXPERIMENTAL`; promotion to `STABLE` happens
only after a candidate is `CERTIFIED` against the M2 contract test
suite.

## 2. Module placement and public types

### 2.1 Decision: a new `:pipeline-runtime` module

The audit's open issue G.1 names two consistent options:
**(a)** keep the three M2 ports in `:pipeline-events` (mirror the M1
shape); **(b)** split them into a new `:pipeline-runtime` module.

This design picks **(b) — a new `:pipeline-runtime` module** — and
justifies the choice against the segregation rule (§D.5 of the audit):

1. **Cross-plane composition.** The three M2 ports compose BOTH the
   event plane (`EventTail`, `EventHistory`,
   `OperationJournal.listForRun`) and the output plane
   (`OutputFrameIndex.framesOfRun`, `OutputTailPort.tailState`,
   `OutputRecoveryPort.recover`, `OutputSealPort.seal`). Putting all
   three in `:pipeline-events` would force an `api` dependency from
   `:pipeline-events` onto `:pipeline-output` (or its asymmetric twin
   in `:pipeline-output`), which is a structural change the audit
   does not authorise. A new module that depends on both planes
   preserves the existing plane topology and is the natural home
   for runtime verbs that read or compose across planes.

2. **Segregation by physical boundary.** The audit's D.5 verdict pins
   the rule "the verbs MUST be segregated interfaces, NOT one wide
   port". Putting `RuntimeIntrospectionPort`,
   `RuntimeControlPort`, `RuntimeRecoverPort` in three separate
   files in the same module — each with its own sealed `*Refusal`
   ADT — is a stronger segregation than three methods on a single
   port in a single file. The M1 design uses the same shape
   (`OutputFollower` in `output/follow/`, `EventFollower` in
   `events/follow/`, `EventRecordReadPort` in
   `events/identity/`) but M2 has THREE verbs, and a one-verb-per-file
   rule with a shared module home is the cleaner segregation.

3. **No new lease, no new fencing, no new scheduler.** The audit
   confirms (B.8, F.7) that the M2 ports consult the existing
   `RunExecutionLease` decider but the
   `FileBackedRunExecutionLeaseStore` (the OS-lock + fencing-token
   store) stays **internal to `:pipeline-events-store`**. The
   `:pipeline-runtime` module declares its `api` dependency on
   `:pipeline-domain`, `:pipeline-events`, `:pipeline-output`, and
   (transitively, NOT directly) the internal modules it composes.
   The package boundaries established by M1 are preserved.

4. **Consumer story.** A Fabric that adopts `:pipeline-runtime` gets
   the three M2 ports with one Maven coordinate; a Fabric that
   adopts only `:pipeline-events` (the M1 read-side surface) is not
   forced to depend on `:pipeline-runtime` for a verb it does not
   need. The cross-plane composition lives in the public module
   Fabric reaches for when it wants the runtime verbs.

#### 2.1.1 Module layout

```text
v2/pipeline-runtime/
├── build.gradle.kts                      (NEW: kotlin("jvm") + maven-publish)
└── src/main/kotlin/dev/rubentxu/pipeline/v2/runtime/
    ├── Capabilities.kt                   (NEW: static IDs + helper)
    ├── inspect/
    │   ├── RuntimeIntrospectionPort.kt   (NEW: §3.1)
    │   ├── RuntimeObservation.kt         (NEW: §3.2)
    │   ├── IntrospectionRefusal.kt       (NEW: §3.3)
    │   └── RuntimeIntrospectionResult.kt (NEW: §3.4)
    ├── control/
    │   ├── RuntimeControlPort.kt         (NEW: §4.1)
    │   ├── CancelReason.kt               (NEW: §4.2)
    │   ├── CancelOutcome.kt              (NEW: §4.3)
    │   └── CancelRefusal.kt              (NEW: §4.4)
    └── recover/
        ├── RuntimeRecoverPort.kt         (NEW: §5.1)
        ├── RecoverOptions.kt             (NEW: §5.2)
        ├── RecoverOutcome.kt             (NEW: §5.3)
        ├── RecoverRefusal.kt             (NEW: §5.4)
        └── RuntimeRecoverDecision.kt     (NEW: §5.5)
```

The implementation classes (`RuntimeIntrospectionAdapter`,
`RuntimeControlAdapter`, `RuntimeRecoverAdapter`,
`RuntimeRecoverDecisionImpl`) live in `:pipeline-application` because
that module is the only one that can reach the internal authorities
(`OperationJournal`, `RunExecutionLease`, `FileBackedRunExecutionLeaseStore`,
`OutputRecoveryPort`, `RecoveredExecutionMaterializer`). The
application module exposes adapters to the runtime module as
constructor functions; the runtime module exposes the ports to
consumers. This is the M1 pattern (the `EventFollower` /
`OutputFollower` interfaces live in `:pipeline-events` /
`:pipeline-output`; the SQLite / segment implementations live in
`:pipeline-events-store` / `:pipeline-output-store`).

### 2.2 `Capabilities` companion

```kotlin
// New file: v2/pipeline-runtime/src/main/kotlin/.../runtime/Capabilities.kt

package dev.rubentxu.pipeline.v2.runtime

import dev.rubentxu.pipeline.v2.domain.Capability

/**
 * Static capability identifiers for the runtime surface.
 *
 * Mirrors M1 §1 (the `output.follow.v1` / `events.follow.v1` strings
 * advertised in `:pipeline-output` and `:pipeline-events`). A consumer
 * that wants the runtime verbs depends on `:pipeline-runtime` and
 * negotiates the three IDs below; absence of any of them requires
 * the consumer to fall back to the existing published surface (or to
 * a typed refusal), per INTERFACE_CONTRACT §6.
 *
 * All three IDs are advertised as EXPERIMENTAL in the first M2
 * candidate. Promotion to PUBLICADA happens only after the M2
 * contract test suite and the M2 cross-JVM e2e test are green and
 * the candidate is CERTIFIED.
 */
object Capabilities {
    const val RUNTIME_INSPECT_V1: Capability = Capability("runtime.inspect.v1")
    const val RUNTIME_CANCEL_V1: Capability = Capability("runtime.cancel.v1")
    const val RUNTIME_RECOVER_V1: Capability = Capability("runtime.recover.v1")

    /** Helper for the application-layer capability registry. */
    fun registered(): List<Capability> = listOf(
        RUNTIME_INSPECT_V1, RUNTIME_CANCEL_V1, RUNTIME_RECOVER_V1,
    )
}
```

### 2.3 Companion constants (design pins)

The M2 design pins these constants. They live in their respective
port file as `companion object` members; consumers see them as
`RuntimeIntrospectionPort.DEFAULT_INSPECT_*`.

| Constant | Value | Where it lives | Purpose |
|---|---|---|---|
| `DEFAULT_INSPECT_TIMEOUT_MS` | `5_000L` | `RuntimeIntrospectionPort.kt` | Upper bound on a single `inspect` call's wall time (the audit §E.4 p0 cancellation budget implies reads must be bounded). |
| `DEFAULT_RECOVER_DEADLINE_MS` | `2_000L` | `RuntimeRecoverPort.kt` | Upper bound on a single `recover` call's wall time before the port returns `RecoverOutcome.ReattachPending(deadlineMs)`. |
| `DEFAULT_RECOVER_OPTIONS` | `RecoverOptions.Default` | `RecoverOptions.kt` | The default options: `dryRun = false`, `replayPolicy = EffectReplayPolicy.Production`. |
| `CANCEL_REASON_DEFAULT` | `CancelReason.UserRequested` | `CancelReason.kt` | The reason carried in the terminal `RunFinished` event when the caller passes no reason. |

These constants are implementation defaults, not contract surfaces;
they document the baseline the implementation MUST satisfy and the
UAT matrix MUST verify.

## 3. `RuntimeIntrospectionPort.inspect(runId)` — design

### 3.1 Port signature

```kotlin
// New file: v2/pipeline-runtime/src/main/kotlin/.../runtime/inspect/RuntimeIntrospectionPort.kt

package dev.rubentxu.pipeline.v2.runtime.inspect

/**
 * M2 — public read-only port that answers "what does this run look
 * like right now?" for a given [runId].
 *
 * ## Why this port exists
 *
 * Every primitive Fabric needs to answer that question already exists
 * inside PK (M2 audit §A.1–A.5): event-plane reads via
 * [dev.rubentxu.pipeline.v2.events.identity.EventHistory] and
 * [dev.rubentxu.pipeline.v2.events.identity.EventTail]; output-plane
 * reads via
 * [dev.rubentxu.pipeline.v2.output.OutputFrameIndex],
 * [dev.rubentxu.pipeline.v2.output.OutputReadPort],
 * [dev.rubentxu.pipeline.v2.output.OutputTailPort]; journal reads via
 * [dev.rubentxu.pipeline.v2.events.durable.OperationJournal]; cursor
 * state via
 * [dev.rubentxu.pipeline.v2.events.durable.ReplayCursorStore]; lease
 * state via the pure decider
 * [dev.rubentxu.pipeline.v2.events.durable.RunExecutionLease] and its
 * effectful adapter
 * [dev.rubentxu.pipeline.v2.events.durable.FileBackedRunExecutionLeaseStore].
 *
 * The audit's B.1, B.4 gap is that NONE of these is exposed as a
 * single typed answer. The closest thing the application layer offers
 * today is the defaulted `ObserveLanes.hasRunFinished(runId)` method
 * (`v2/pipeline-application:…/application/MainObserveCli.kt:467`), which
 * is an event-scan over `eventsOf(runId).any { it is RunFinished }` —
 * fine for the CLI, not a published port.
 *
 * This port reconciles the existing primitives into one typed
 * [RuntimeObservation] (B.4 closed the case for the runtime-shaped
 * ADT) and one closed [IntrospectionRefusal] ADT (B.1 named the
 * substrate gaps; this design formalises them).
 *
 * ## What this port does and does NOT do
 *
 * DOES:
 *   - Read the journal (`OperationJournal.listForRun`).
 *   - Read the event tail (`EventTail.readAfter` for the latest
 *     sequence; `EventHistory.history` for bounded filtering).
 *   - Read the output tail (`OutputTailPort.tailState`,
 *     `OutputFrameIndex.streamsOfRun`).
 *   - Consult the pure lease decider
 *     (`RunExecutionLease.acquire`) with the live lease state fetched
 *     by the application's adapter — this returns a typed decision;
 *     the file-backed store is NOT exposed through this port.
 *   - Return one of the closed [RuntimeObservation] cases or one of
 *     the closed [IntrospectionRefusal] cases.
 *
 * DOES NOT:
 *   - Invoke [dev.rubentxu.pipeline.v2.output.store.OutputRecoveryPort.recover]
 *     or any journal write. The audit's §D.1 pins this as PASS-by-design;
 *     the implementation MUST NOT add an inspect-side path that recovers
 *     internally.
 *   - Take a lease. Inspect is read-only.
 *   - Introduce a new lease store, a new fencing scheme, or a new
 *     scheduler (audit B.8).
 *   - Return an `Any?`, a nullable sentinel, or an exception. The
 *     result is one of two closed ADTs.
 *
 * ## Concurrency and ordering
 *
 * `inspect` is callable from any number of concurrent observers
 * without state change (audit D.1, §E.2 UAT-PK-012). Two concurrent
 * calls to `inspect(runId)` on the same run MUST return observations
 * that differ only in monotonic progress (a journal sequence that
 * advanced, a frame ordinal that advanced). The implementation
 * MUST NOT race the writer; the contract test
 * `IntrospectionConcurrencyTest` (§7.1) pins this.
 *
 * ## Stability
 *
 * This port is `EXPERIMENTAL` for the M2 first cut. The capability
 * ID is `runtime.inspect.v1`; promotion to `PUBLICADA` happens only
 * after the M2 candidate is `CERTIFIED` (audit F.5).
 *
 * @see M2_INSPECT_RECOVER_CANCEL_AUDIT.md §B.1, §B.4, §D.1, §D.5.
 */
fun interface RuntimeIntrospectionPort {

    /**
     * Inspect the live state of [runId] right now.
     *
     * @param runId The run to inspect. `String`, NOT a typed `RunId`
     *              (M1 design §7 — typed identity is a future
     *              M-block concern, not a M2 introduction).
     * @return A [RuntimeIntrospectionResult] that is either an
     *         [RuntimeIntrospectionResult.Observation] carrying the
     *         closed [RuntimeObservation], or a
     *         [RuntimeIntrospectionResult.Refused] carrying a closed
     *         [IntrospectionRefusal].
     *
     *         NEVER throws. A substrate that is unobservable is
     *         returned as a typed refusal, not an exception.
     */
    fun inspect(runId: String): RuntimeIntrospectionResult

    companion object {
        /** Upper bound on a single `inspect` call's wall time. */
        const val DEFAULT_INSPECT_TIMEOUT_MS: Long = 5_000L
    }
}
```

### 3.2 `RuntimeObservation` — the closed ADT

The audit (B.1, B.4) re-affirms the shape
`PK-SPEC-02-RUNTIME-OBSERVATION.md` already sketches at lines 22-28.
This design formalises that shape, names each case after the
substrate it represents, and pins the data each case carries.

```kotlin
// New file: v2/pipeline-runtime/src/main/kotlin/.../runtime/inspect/RuntimeObservation.kt

package dev.rubentxu.pipeline.v2.runtime.inspect

import dev.rubentxu.pipeline.v2.events.identity.RunOwnerId
import dev.rubentxu.pipeline.v2.events.identity.FencingToken
import dev.rubentxu.pipeline.v2.output.OutputStreamId

/**
 * M2 — closed ADT the audit's B.1/B.4 names "what does this run look
 * like right now?" Each case names the substrate condition it
 * represents; new cases are compile errors at every `when` site.
 *
 * The shape matches `PK-SPEC-02-RUNTIME-OBSERVATION.md` lines 22-28.
 * The audit's correction is to add `process: ProcessRef?` (NOT carried
 * across the JVM boundary — see below) and to bind
 * [RecoveredTerminal] to a typed [TerminalObservation] (NOT to a
 * raw `(StepOutcome, OperationStatus)` pair — see
 * [TerminalObservation] for why).
 */
sealed interface RuntimeObservation {

    /**
     * The run is in flight and observed.
     *
     * `process` is OPTIONAL: the runtime does not always have a
     * durable reference to the live writer process (the writer may be
     * on a host this introspection port cannot inspect, e.g. when the
     * writer is behind Fabric and this port reads the journal + lease
     * record only). The audit's B.1 says "name the live holder and
     * its fencing token", and that is what [leaseHolder] and
     * [fencingToken] do — they are the durable, cross-process facts
     * a Fabric can act on. The optional [process] is a ProcessRef
     * (PID + host) ONLY when the introspection adapter is
     * co-located with the writer's JVM and the OS exposes the PID;
     * cross-process, this field is `null`. The contract does not
     * depend on it.
     */
    data class Running(
        val attempt: AttemptId,
        val leaseHolder: LeaseHolder?,
        val fencingToken: FencingToken,
        val journalPosition: JournalPosition,
        val outputTails: List<OutputTailView>,
        val process: ProcessRef? = null,
    ) : RuntimeObservation

    /**
     * The run reached a terminal state and that state was observed by
     * the journal. The [terminal] carries the typed observation (NOT
     * a raw `(StepOutcome, OperationStatus)` pair — that would
     * collapse "what the journal recorded" with "what the run
     * semantically is", which is the same category error the audit
     * §D.4 calls out).
     */
    data class Terminal(
        val attempt: AttemptId,
        val terminal: TerminalObservation,
        val terminalAtMs: Long,
    ) : RuntimeObservation

    /**
     * The run is in flight and the substrate was successfully
     * inspected, but the inspection found no live evidence. This is a
     * FACT about the substrate, not a refusal — the runtime knows
     * the run exists but the journal / lease record / output tail is
     * empty. This is the closed distinction the M1-A correction made
     * between "unknown run" and "empty run" — see §3.5 below.
     */
    data class LiveButEmpty(
        val attempt: AttemptId,
        val reason: String, // bounded diagnostic, NOT a free-text reason
    ) : RuntimeObservation

    /**
     * The substrate could not be inspected. The [reason] is a closed
     * [IntrospectionFailure] so a `when` over the typed reasons fails
     * to compile when a new substrate is added.
     */
    data class Unobservable(
        val reason: IntrospectionFailure,
    ) : RuntimeObservation
}

/** The current attempt a run is on. */
@JvmInline
value class AttemptId(val value: Int) {
    init { require(value >= 1) { "attempt must be >= 1, got $value" } }
}

/**
 * The lease holder as observed by the pure decider. `null` when the
 * run is unowned at inspection time (the lease was released, or no
 * acquisition has happened yet).
 */
data class LeaseHolder(
    val ownerId: RunOwnerId,
    val fencingToken: FencingToken,
    val alive: Boolean?,
)

/**
 * The position of the journal relative to the live run. Monotonically
 * non-decreasing across two consecutive `inspect` calls on the same
 * run.
 */
data class JournalPosition(
    /** Number of journaled operations. */
    val operations: Int,
    /** The latest journaled operation id (OpId-formatted), or null for an empty journal. */
    val latestOpId: String?,
    /** The latest `ended_at` of any terminal row, or null when no row is terminal. */
    val latestTerminalAtMs: Long?,
)

/** A bounded view of one output stream's tail state. */
data class OutputTailView(
    val stream: OutputStreamId,
    /** `Open(committedEnd)` or `Sealed(finalEnd)`; mirrors `OutputTailState`. */
    val state: TailState,
    /** The latest frame ordinal observed for this stream, or null when no frames have been declared. */
    val lastOrdinal: Long?,
)

/** Mirrors the published `OutputTailState` discriminated union without dragging the type along. */
sealed interface TailState {
    data class Open(val committedEnd: Long) : TailState
    data class Sealed(val finalEnd: Long) : TailState
}

/** A typed terminal observation, projected from the journal's terminal row. */
sealed interface TerminalObservation {
    data class Succeeded(val terminalAtMs: Long) : TerminalObservation
    data class Failed(val failureKind: String, val terminalAtMs: Long) : TerminalObservation
    data class Unstable(val terminalAtMs: Long) : TerminalObservation
    data class Aborted(val terminalAtMs: Long) : TerminalObservation
    data class Cancelled(val terminalAtMs: Long) : TerminalObservation
    /** The journal recorded a terminal that does not map to the canonical outcomes. */
    data class Other(val rawOutcome: String, val terminalAtMs: Long) : TerminalObservation
}

/**
 * Closed ADT of reasons an inspection could not be served. Mirrors
 * the M1 convention (`OutputRefusal`, `EventRecordReadRefusal`,
 * `EventFollowRefusal`): a sealed interface per port, no
 * `Either`/`Result` re-use.
 *
 * The audit's B.1 names `NoEventStore`, `NoControlRoot`, `UnknownRun`,
 * `LeaseHeldByAnother` + the live holder's fencing token. This design
 * formalises those plus the substrate gaps the audit's §C unmasked
 * (overlap demarcation — same authority, different shape).
 */
sealed interface IntrospectionRefusal {

    /** The run is not known to this PK instance. */
    data class UnknownRun(val runId: String) : IntrospectionRefusal

    /** No `--control-root` was given at startup; there is no journal or lease to consult. */
    data object NoControlRoot : IntrospectionRefusal

    /** The event plane is not configured for this instance. */
    data object NoEventStore : IntrospectionRefusal

    /** The output plane is not configured for this instance. */
    data object NoOutputPlane : IntrospectionRefusal

    /**
     * The run is held by another process whose lease is still live.
     * The [heldBy] and [fencingToken] name the live authority; this
     * is the same fact the cancel port refuses on (audit B.2). The
     * introspection port reports it; it does NOT attempt to take over.
     */
    data class LeaseHeldByAnother(
        val heldBy: RunOwnerId,
        val fencingToken: FencingToken,
    ) : IntrospectionRefusal

    /** The underlying storage failed; [cause] is a short diagnostic. */
    data class StorageError(val cause: String) : IntrospectionRefusal

    /**
     * The journal, the lease record, and the cursor store disagree
     * about the run's state. The [details] field carries one-line
     * diagnostics from each substrate so an operator can read what
     * the inspector saw.
     */
    data class InconsistentLeaseState(val details: String) : IntrospectionRefusal
}

/**
 * Closed ADT of reasons the substrate could not be inspected at all.
 * Mirrors the audit's B.1 distinction between "substrate cannot be
 * inspected" (refusal) and "substrate was inspected and yields
 * nothing" (the [RuntimeObservation.LiveButEmpty] case). The two
 * cases are deliberately different types so a consumer can switch on
 * them without parsing a free-text reason.
 */
sealed interface IntrospectionFailure {
    data object NoEventStore : IntrospectionFailure
    data object NoOutputPlane : IntrospectionFailure
    data object NoJournal : IntrospectionFailure
    data object StorageError(val cause: String) : IntrospectionFailure
}

/**
 * A process-scoped handle to the live writer (PID + host). Optional;
 * cross-process inspection ports always return `null`. The contract
 * does not depend on it.
 */
data class ProcessRef(val host: String, val pid: Long)
```

### 3.3 `RuntimeIntrospectionResult` — the port envelope

```kotlin
// New file: v2/pipeline-runtime/src/main/kotlin/.../runtime/inspect/RuntimeIntrospectionResult.kt

package dev.rubentxu.pipeline.v2.runtime.inspect

/**
 * M2 — the result envelope returned by [RuntimeIntrospectionPort.inspect].
 * Closed ADT: one case for an observation, one for a refusal.
 *
 * The envelope shape mirrors the M1 read ports:
 * [dev.rubentxu.pipeline.v2.events.identity.EventRecordReadResult] and
 * [dev.rubentxu.pipeline.v2.output.OutputReadResult]. The result is a
 * typed ADT, not a nullable. The follow-style ports over the runtime
 * (none exist today) would translate a `Refused` into a terminal
 * `Refused` event; the introspection port is pull-by-call, so it
 * returns the refusal inline.
 */
sealed interface RuntimeIntrospectionResult {

    /** The inspection succeeded; [observation] is the typed view. */
    data class Observation(val observation: RuntimeObservation) :
        RuntimeIntrospectionResult

    /** The inspection could not be served; [refusal] names the reason. */
    data class Refused(val refusal: IntrospectionRefusal) :
        RuntimeIntrospectionResult
}
```

### 3.4 Composition rule

`RuntimeIntrospectionPort.inspect(runId)`:

```text
1. Event-plane: EventTail.readAfter(runId, cursor=null, limit=1) -> latest event
   (or EventRecordReadPort.readRecords for typed). PASS-by-design, audit §D.1.
2. Output-plane: OutputFrameIndex.streamsOfRun(runId) +
   OutputTailPort.tailState(stream) for each stream.
3. Journal: OperationJournal.listForRun(runId) (ordered) +
   OperationJournal.getEndedAt(opId, attempt) for each terminal row.
4. Cursor: ReplayCursorStore.load(runId) -> (runId, lastOpId, stageIndex,
   savedAt).
5. Lease: RunExecutionLease.acquire(currentLeaseRecord, request). The
   introspection port consults the pure decider with a synthetic
   LeaseRequest and observes the typed LeaseAcquisition; the
   FileBackedRunExecutionLeaseStore is NOT exposed.
6. Reconcile:
   - journal has a terminal row -> Terminal(...)
   - journal + lease + output are all empty AND no RunStarted seen ->
     LiveButEmpty(attempt=1, reason=...)
   - journal has RunStarted but no terminal AND lease is Reentered/Acquired ->
     Running(...)
   - lease is AlreadyOwned by another -> Refused(LeaseHeldByAnother(...))
   - any substrate returned a typed StorageError -> Refused(StorageError(...))
   - journal and lease disagree on ownerId OR live-and-not-alive ->
     Refused(InconsistentLeaseState(...))
```

The introspection port is **read-only by construction**: every call
above is a SELECT, a tail-state query, or a `load`. No `append`, no
`beginOperation`, no `acquire` that takes a fresh lease, no
`recover()` invocation.

### 3.5 Unknown vs Empty (M1-A correction)

The M1 design (revision note 1) corrected a similar issue: a read
port distinguishes a run that does not exist (`UnknownRun` refusal)
from a run that exists but has not yet produced anything (an empty
page). The M2 port applies the same discipline:

- `Refused(UnknownRun(runId))` — the journal has no row for `runId`,
  the event plane has no envelope for `runId`, and the lease record
  is `null`. The run is genuinely unknown to this PK instance.
- `Observation(LiveButEmpty(attempt, reason))` — the journal has no
  row, the event plane has no envelope, AND there is no signal that
  the run was ever started. This is the case where Fabric polls a
  runId that was never written; the typed answer is "live but empty",
  not "unknown".

The implementation distinguishes the two by joining the three
substrates: if any of them has a positive fact (a journal row, an
event, a lease record), the run is known and the answer is one of
`Running`, `Terminal`, or a typed refusal. If all three are empty,
the answer is `LiveButEmpty`. The contract test
`IntrospectionUnknownVsEmptyTest` (§7.1) pins the distinction.

### 3.6 Authority `inspect` composes

The audit's §D.6 verdict is PASS-by-design. The composed authorities,
named with module:file:line:

- `EventHistory.history` — `pipeline-events/src/main/kotlin/.../events/identity/EventHistoryPorts.kt:151-153`
- `EventTail.readAfter` — `pipeline-events/.../events/identity/EventHistoryPorts.kt:159-161`
- `OutputReadPort` — `pipeline-output/src/main/kotlin/.../output/OutputReadPort.kt:19-44`
- `OutputTailPort.tailState` — `pipeline-output/.../output/OutputTailState.kt:86-96`
- `OutputFrameIndex.streamsOfRun` / `framesOfRun` — `pipeline-output/.../output/OutputFrameIndex.kt:140-200`
- `OperationJournal.listForRun` / `getEndedAt` / `getStartedAt` — `pipeline-events-store/src/main/kotlin/.../events/durable/OperationJournal.kt:67-89`
- `ReplayCursorStore.load` — `pipeline-events-store/.../events/durable/ReplayCursorStore.kt:16-64`
- `RunExecutionLease.acquire` (the pure decider) — `pipeline-events-store/.../events/durable/RunExecutionLease.kt:50-309`

None of these is replaced, re-implemented, or moved. The
`FileBackedRunExecutionLeaseStore`
(`pipeline-events-store/.../events/durable/FileBackedRunExecutionLeaseStore.kt:40-253`)
stays internal to PK; the introspection port consults the pure
decider via an application-layer adapter that gathers the lease
record facts.

## 4. `RuntimeControlPort.cancel(runId, reason)` — design

### 4.1 Port signature

```kotlin
// New file: v2/pipeline-runtime/src/main/kotlin/.../runtime/control/RuntimeControlPort.kt

package dev.rubentxu.pipeline.v2.runtime.control

import dev.rubentxu.pipeline.v2.runtime.inspect.AttemptId
import dev.rubentxu.pipeline.v2.runtime.inspect.TerminalObservation

/**
 * M2 — public terminal control verb that flips a run's durable
 * state to CANCELLED, exactly once.
 *
 * ## Why this port exists
 *
 * The audit's B.2 / B.7 gap is that no public `Cancel` port exists
 * today. The closest surfaces are
 * [dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShellLaunching.kill]
 * (kills ONE subprocess of the writer; no journal write, no
 * terminality guarantee, no fencing — calling it from outside the
 * writer's JVM would corrupt the journal),
 * [dev.rubentxu.pipeline.v2.output.store.OutputSealPort.seal]
 * (terminal marker for a stream; refuses appends after seal; NOT a
 * run cancel), and the internal `OperationJournal.append` path the
 * coordinator uses to close a run today (reachable only through the
 * internal `RunLifecycleEngine`, which has no external entry).
 *
 * This port publishes the cancel verb. The implementation lives in
 * `:pipeline-application` (next to the existing
 * `RunLifecycleEngine`); the consumer reaches it through
 * `:pipeline-runtime`.
 *
 * ## Idempotency invariant (audit D.3)
 *
 * A cancel call made N times produces one terminal state, not N. The
 * second call returns [CancelOutcome.AlreadyCancelled], NOT a refusal.
 * The audit's D.3 verdict flags this invariant as UNVERIFIED today;
 * the contract test `CancelIdempotencyTest` (§7.2) exhaustively pins
 * the closed `CancelOutcome` ADT.
 *
 * ## Lease boundary (audit B.2, B.8, G.3)
 *
 * Cancel MUST NOT cross lease boundaries. The audit's G.3 stance is
 * "the cancel port MUST NOT flip a run whose lease is held by another
 * process". The implementation consults the existing
 * [dev.rubentxu.pipeline.v2.events.durable.RunExecutionLease.acquire]
 * pure decider (`pipeline-events-store/.../events/durable/RunExecutionLease.kt:50-309`)
 * BEFORE it writes the terminal row, and refuses with
 * [CancelRefusal.LeaseHeldByAnother] on
 * [LeaseAcquisition.AlreadyOwned].
 *
 * Cancel does NOT take a fresh lease; it consults the existing one.
 *
 * ## What cancel does and does NOT do
 *
 * DOES:
 *   - Consult the pure lease decider (existing) for authority.
 *   - Write exactly one terminal journal row via the existing
 *     [OperationJournal.append] (`pipeline-events-store:.../events/durable/OperationJournal.kt:30-36`)
 *     with outcome `Cancelled`.
 *   - Mark each declared output stream terminal via the existing
 *     [OutputSealPort.seal] (`pipeline-output-store:.../output/store/OutputWritePorts.kt:205-218`).
 *     The seal is idempotent (audit §A.2 verifies the existing seal
 *     semantics).
 *   - Emit the cancellation [CancelOutcome] synchronously after the
 *     terminal row is committed.
 *   - Wake any in-flight reader through the existing
 *     [ObservationWakeup] vocabulary
 *     (`pipeline-application:.../observation/ObservationWakeup.kt:35-89`)
 *     by appending an `OutputAdvanced` wakeup for each sealed stream
 *     and an `EventsCommitted` wakeup for the terminal event. NO new
 *     wakeup transport is introduced.
 *
 * DOES NOT:
 *   - Take a new lease (no `acquire`; the decider is consulted with
 *     the current state).
 *   - Introduce a new fencing scheme (audit B.8).
 *   - Move leases, fencing, or schedulers into PK (audit B.8).
 *   - Re-execute external side effects (a cancelled run is terminal;
 *     no step is re-run).
 *   - Modify output bytes already persisted (the seal marks a
 *     final-end; bytes already committed are unchanged).
 *
 * @see M2_INSPECT_RECOVER_CANCEL_AUDIT.md §B.2, §B.7, §B.8, §D.3,
 *      §F.7, G.3.
 */
fun interface RuntimeControlPort {

    /**
     * Cancel [runId] for [reason]. Idempotent: a second call returns
     * [CancelOutcome.AlreadyCancelled].
     *
     * @param runId The run to cancel. `String`, NOT typed.
     * @param reason The reason the cancel was issued; carried in the
     *               terminal `RunFinished` event's payload.
     * @return A [CancelOutcome] that is one of [CancelOutcome.Cancelled]
     *         (this call flipped the state), [CancelOutcome.AlreadyCancelled]
     *         (a prior call already flipped it), or
     *         [CancelOutcome.Refused] (a closed [CancelRefusal] reason).
     *
     *         NEVER throws. A refusal is the explicit answer the
     *         contract expects for "may not cancel" scenarios.
     */
    fun cancel(runId: String, reason: CancelReason): CancelOutcome
}
```

### 4.2 `CancelReason` — closed ADT

```kotlin
// New file: v2/pipeline-runtime/src/main/kotlin/.../runtime/control/CancelReason.kt

package dev.rubentxu.pipeline.v2.runtime.control

/**
 * M2 — closed ADT of cancel reasons a caller may pass. The reason
 * is carried in the terminal `RunFinished` event payload and surfaces
 * in the journal row. New cases are compile errors at every `when`
 * site that exhausts this ADT.
 */
sealed interface CancelReason {

    /** An operator or a consumer explicitly asked to cancel. */
    data object UserRequested : CancelReason

    /** A consumer-supplied free-text reason (bounded length). */
    data class Annotated(val text: String) : CancelReason {
        init {
            require(text.length <= 256) {
                "CancelReason.Annotated text must be <= 256 chars, got ${text.length}"
            }
            require('\n' !in text) {
                "CancelReason.Annotated text must not contain newlines"
            }
        }
    }
}
```

### 4.3 `CancelOutcome` — closed ADT

```kotlin
// New file: v2/pipeline-runtime/src/main/kotlin/.../runtime/control/CancelOutcome.kt

package dev.rubentxu.pipeline.v2.runtime.control

/**
 * M2 — closed ADT returned by [RuntimeControlPort.cancel]. Each case
 * names a real outcome of a cancel call; new cases are compile errors
 * at every `when` site. Mirrors the M1 refusal-extension pattern.
 */
sealed interface CancelOutcome {

    /**
     * This call flipped the run to CANCELLED. The terminal journal
     * row was committed and the seal was applied.
     */
    data class Cancelled(
        /** The attempt this cancel flipped (M2: always `AttemptId(1)`
         *  because cancel does not introduce a new attempt). */
        val attempt: AttemptId,
        /** The terminal journal row's `ended_at` timestamp (epoch ms). */
        val terminalAtMs: Long,
        /** The number of streams that were sealed by this cancel call. */
        val streamsSealed: Int,
    ) : CancelOutcome

    /**
     * The run was already CANCELLED by a prior call; this call was
     * a no-op. NOT a refusal — idempotency is the contract, and the
     * caller is allowed to retry without surfacing an error
     * (audit D.3, B.2).
     */
    data class AlreadyCancelled(
        val attempt: AttemptId,
        val terminalAtMs: Long,
    ) : CancelOutcome

    /** The cancel was refused; [refusal] names the closed reason. */
    data class Refused(val refusal: CancelRefusal) : CancelOutcome
}
```

### 4.4 `CancelRefusal` — closed ADT

```kotlin
// New file: v2/pipeline-runtime/src/main/kotlin/.../runtime/control/CancelRefusal.kt

package dev.rubentxu.pipeline.v2.runtime.control

import dev.rubentxu.pipeline.v2.events.identity.RunOwnerId
import dev.rubentxu.pipeline.v2.events.identity.FencingToken

/**
 * M2 — closed ADT of reasons a cancel call was refused. The audit's
 * B.2 names `LeaseHeldByAnother`, `UnknownRun`, `RunTerminal`,
 * `JournalUnavailable`. This design adds `IncompatibleRunState`
 * for runs in a terminal state that is not `Cancelled` (e.g. a run
 * that already reached `Succeeded` or `Failed`).
 */
sealed interface CancelRefusal {

    /** The run is not known to this PK instance. */
    data class UnknownRun(val runId: String) : CancelRefusal

    /**
     * The run has already reached a terminal state whose outcome is
     * NOT `Cancelled`. The terminal outcome is named in [outcome] so
     * a caller can decide whether the cancel is moot.
     */
    data class RunTerminal(val outcome: String) : CancelRefusal

    /**
     * The run is held by another process whose lease is still live.
     * The [ownerId] and [fencingToken] name the live authority; the
     * cancel port MUST NOT cross lease boundaries (audit B.2, B.8,
     * G.3). This authority belongs to Fabric, not to PK.
     */
    data class LeaseHeldByAnother(
        val ownerId: RunOwnerId,
        val fencingToken: FencingToken,
    ) : CancelRefusal

    /**
     * The run is in a state where cancel does not apply. Example: a
     * run whose `AttemptId` is greater than the live attempt, or a
     * run whose journal row is non-terminal but whose lease was
     * released (a tombstone rather than a run).
     */
    data class IncompatibleRunState(val details: String) : CancelRefusal

    /**
     * The journal could not be written. [cause] is a short diagnostic.
     * A cancel that cannot write its terminal fact is a fail-closed
     * refusal, NOT a fallback to `start()` or `run()` (audit B.2).
     */
    data class JournalUnavailable(val cause: String) : CancelRefusal

    /** The underlying storage failed; [cause] is a short diagnostic. */
    data class StorageError(val cause: String) : CancelRefusal
}
```

### 4.5 Idempotency check

The cancel port consults two sources to determine whether the call
is the first or a duplicate:

1. **Journal:** `OperationJournal.listForRun(runId)` — if any row
   has `outcome == "cancelled"`, the cancel has already happened.
   The check is a SELECT, idempotent and side-effect free.
2. **Lease:** `RunExecutionLease.acquire(current, request)` — if the
   pure decider returns `LeaseAcquisition.AlreadyOwned` by a holder
   whose token is the one the cancel port just issued, the cancel
   has already happened in this JVM. (This is a same-JVM fast path;
   the cross-JVM check is the journal one.)

The audit's D.3 invariant is "cancel races with itself exactly once".
The contract test `CancelRaceTest` (§7.2) launches N concurrent
cancel calls on the same run and asserts the union of outcomes is
exactly one `Cancelled` and `N-1` `AlreadyCancelled`. The `Refused`
case is not an acceptable outcome of a same-run cancel race; a
refusal under race is a bug.

### 4.6 Composition rule

`RuntimeControlPort.cancel(runId, reason)`:

```text
1. Lease consultation (existing):
   LeaseRecord current = FileBackedRunExecutionLeaseStore.observe(runId)
     // read-only, no acquisition
   LeaseAcquisition decision =
     RunExecutionLease.acquire(current, LeaseRequest(runId, THIS_OWNER))
   when (decision) {
     is AlreadyOwned -> return Refused(LeaseHeldByAnother(...))
     is Unverifiable -> return Refused(JournalUnavailable(...))
     is Acquired, Reentered, TakenOver -> proceed to step 2
   }
   // NOTE: the cancel port does NOT acquire; the decider is
   // consulted for authority, and the lease store is NOT mutated.
   // The "Acquired" outcome above is the decider's verdict on what
   // WOULD happen if cancel asked for the lease; cancel does not
   // actually request the lease.

2. Idempotency check (existing):
   terminalRows = OperationJournal.listForRun(runId)
     .filter { it.endedAt != null && outcome == "cancelled" }
   if (terminalRows.isNotEmpty())
     return AlreadyCancelled(attempt=1, terminalAtMs=...)

3. Terminal journal write (existing):
   OperationJournal.append(
     DurableOperation.RunFinished(
       runId, attempt=1, outcome="cancelled", reason=reason,
       endedAt=clock.now(),
     ),
   )
   // Idempotent at the journal level: appending a second terminal
   // row with the same (runId, attempt) returns the same row. The
   // implementation MUST be safe to call twice in a row.

4. Per-stream seal (existing):
   streams = OutputFrameIndex.streamsOfRun(runId)
   sealed = streams.map { OutputSealPort.seal(it) }
   // OutputSealPort.seal is idempotent (audit §A.2).

5. Wakeup emission (existing):
   for (stream in streams) ObservationWakeup.OutputAdvanced(stream)
   ObservationWakeup.EventsCommitted(terminalSequence)

6. Return Cancelled(attempt=1, terminalAtMs=..., streamsSealed=sealed.size).
```

The cancel port does not introduce a write path that bypasses the
existing journal or the existing seal port. Every step above
reuses a published-or-internal authority; the port's only novelty is
the order and the typed refusal envelope.

### 4.7 Authority `cancel` composes

Named with module:file:line:

- `RunExecutionLease.acquire` (pure decider) — `pipeline-events-store/src/main/kotlin/.../events/durable/RunExecutionLease.kt:213-247`
- `RunExecutionLease.release` (pure decider) — `pipeline-events-store/.../events/durable/RunExecutionLease.kt:294-308`
- `FileBackedRunExecutionLeaseStore.observe` (read-side twin; the audit's G.2 open issue is closed by this read-side method, NOT by publishing the store) — `pipeline-events-store/.../events/durable/FileBackedRunExecutionLeaseStore.kt:40-253`
- `OperationJournal.append` — `pipeline-events-store/.../events/durable/OperationJournal.kt:30-36`
- `OperationJournal.listForRun` (idempotency check) — `pipeline-events-store/.../events/durable/OperationJournal.kt:67-71`
- `OutputSealPort.seal` (existing terminal marker, idempotent) — `pipeline-output-store/.../output/store/OutputWritePorts.kt:205-218`
- `ObservationWakeup` (existing vocabulary) — `pipeline-application/src/main/kotlin/.../observation/ObservationWakeup.kt:35-89`

#### 4.7.1 Audit G.2 resolution: `observe(runId)` on the file-backed lease store

The audit's G.2 asks how `RuntimeIntrospectionPort.inspect` reports
`LeaseHeldByAnother` without publishing the lease store. The answer:
the application-layer adapter that backs the introspection and the
cancel ports exposes ONE new READ-ONLY method on the
`FileBackedRunExecutionLeaseStore`:

```kotlin
// v2/pipeline-events-store/.../events/durable/FileBackedRunExecutionLeaseStore.kt
// NEW read-only method (the store remains non-published; the method
// is internal-to-PK, called only by the application-layer adapter).

/**
 * Observe the live lease record for [runId] WITHOUT acquiring it.
 *
 * Read-only: this method does not take the OS file lock, does not
 * advance the fencing token, and does not mutate any durable state.
 * It returns the durable LeaseRecord so the pure decider can
 * authoritatively answer `acquire(current, request)`.
 *
 * The pure decider is what makes the introspection port's
 * `LeaseHeldByAnother` answer authoritative; the read-side twin
 * exists only to fetch the durable facts the decider needs.
 *
 * @return The current `LeaseRecord`, or null when no lease has ever
 *         been issued for [runId].
 */
fun observe(runId: String): LeaseRecord?
```

The lease store stays in `:pipeline-events-store` (NOT published).
The introspection port and the cancel port reach it through the
application-layer adapter. No new authority is published.

## 5. `RuntimeRecoverPort.recover(runId)` — design

### 5.1 Port signature

```kotlin
// New file: v2/pipeline-runtime/src/main/kotlin/.../runtime/recover/RuntimeRecoverPort.kt

package dev.rubentxu.pipeline.v2.runtime.recover

import dev.rubentxu.pipeline.v2.runtime.inspect.AttemptId

/**
 * M2 — public idempotent recover verb that brings a run back to a
 * known state WITHOUT re-executing external effects.
 *
 * ## Why this port exists
 *
 * The audit's B.3 / B.6 gap is that every existing `Recover` path is
 * internal to PK. The decision tree
 * ([dev.rubentxu.pipeline.v2.sdk.runtime.durable.EffectReplayPolicy.decide]
 * pinned by `EffectReplayPolicyTableFitnessTest`),
 * the substrate observer
 * ([dev.rubentxu.pipeline.v2.application.durable.RunningSubprocessRecovery.observe],
 * internal),
 * the materialiser
 * ([dev.rubentxu.pipeline.v2.application.durable.RecoveredExecutionMaterializer.materialize]),
 * and the interpretation engine
 * ([dev.rubentxu.pipeline.v2.application.durable.RecoveryInterpretationEngine.interpret],
 * internal) all exist; none is reachable from outside the JVM that
 * owns the run.
 *
 * The contract surface that comes closest is
 * [dev.rubentxu.pipeline.v2.output.OutputRefusal.RecoveryNotCompleted]
 * (`pipeline-output:.../output/OutputRefusal.kt:14-60`) — the
 * SIGNAL of unreadiness, not a way to act on it.
 *
 * This port publishes the recover verb. The implementation lives in
 * `:pipeline-application`; the consumer reaches it through
 * `:pipeline-runtime`.
 *
 * ## Idempotency + no-rerun invariant (audit D.4)
 *
 * A second call to `recover(runId)` on the same run is a no-op: the
 * journal already says the run is reconciled, and the recover port
 * returns [RecoverOutcome.AlreadyRecovered] without re-running the
 * substrate observer or invoking any step's `execute` again.
 *
 * The audit's D.4 verdict is PARTIALLY VERIFIABLE today because the
 * decider is internal; the M2 work exposes the decider as the
 * `RuntimeRecoverDecision` companion (§5.5) so the public
 * [RecoverOutcome.RecoveredTerminal] case is the ONLY path that
 * does not re-execute, and the public
 * [RecoverOutcome.FailClosed] case carries the reason when the
 * substrate cannot be observed.
 *
 * ## What recover does and does NOT do
 *
 * DOES:
 *   - Read the journal (`OperationJournal.listForRun`) to determine
 *     what was already done.
 *   - Read the cursor (`ReplayCursorStore.load`) to determine where
 *     the run left off.
 *   - Read the substrate observer
 *     ([RunningSubprocessRecovery.observe],
 *     `pipeline-application:.../durable/RunningSubprocessRecovery.kt:65-80`)
 *     to determine the durable terminal of any RUNNING operation.
 *   - Consult the hand-rolled pure decider
 *     ([dev.rubentxu.pipeline.v2.sdk.runtime.durable.EffectReplayPolicy.decide],
 *     `pipeline-step-sdk/runtime:.../durable/EffectReplayPolicy.kt:63-95`,
 *     normative matrix).
 *   - Materialise the recovered terminal via the existing
 *     [RecoveredExecutionMaterializer.materialize]
 *     (`pipeline-application:.../durable/RecoveredExecutionMaterializer.kt:60-`).
 *   - Append the terminal journal row via the existing
 *     [OperationJournal.append] (one row, idempotent).
 *   - Advance the cursor via the existing
 *     [ReplayCursorStore.advance]
 *     (`pipeline-events-store:.../events/durable/ReplayCursorStore.kt:16-64`,
 *     idempotent CAS).
 *   - Reconcile the output store via the existing
 *     [OutputRecoveryPort.recover]
 *     (`pipeline-output-store:.../output/store/OutputWritePorts.kt:166-176`)
 *     AND the existing
 *     [OutputFrameIndex.recoverUnframedBytes]
 *     (`pipeline-output:.../output/OutputFrameIndex.kt:107-200`).
 *     These are called in that order: the output store recovery runs
 *     first (it closes the durable gap); the frame index recovery runs
 *     second (it appends a frame for any committed-but-unframed
 *     bytes).
 *
 * DOES NOT:
 *   - Re-execute external side effects. The recovered terminal is
 *     the journal's own observation; no step's `execute` is called.
 *   - Take a lease. Recover is idempotent and re-entrant; it
 *     consults the existing lease record but does not acquire it.
 *   - Introduce a new scheduler. The recover path is pull-by-call;
 *     no background worker.
 *   - Modify output bytes already persisted (the recovery closes
 *     gaps; it does not edit bytes).
 *   - Move leases, fencing, or schedulers into PK (audit B.8).
 *
 * @see M2_INSPECT_RECOVER_CANCEL_AUDIT.md §B.3, §B.6, §D.4, §F.7.
 */
fun interface RuntimeRecoverPort {

    /**
     * Recover [runId]. Idempotent: a second call returns
     * [RecoverOutcome.AlreadyRecovered]. Does NOT re-execute external
     * effects.
     *
     * @param runId The run to recover. `String`, NOT typed.
     * @param options The recovery options; default is
     *                [RecoverOptions.Default]. Use `dryRun = true` to
     *                compute the decision without writing.
     * @return A [RecoverOutcome] that is one of
     *         [RecoverOutcome.RecoveredTerminal],
     *         [RecoverOutcome.ReattachPending],
     *         [RecoverOutcome.FailClosed], or
     *         [RecoverOutcome.AlreadyRecovered].
     *
     *         NEVER throws. A substrate that cannot be observed is
     *         returned as a typed refusal, not an exception.
     */
    fun recover(
        runId: String,
        options: RecoverOptions = RecoverOptions.Default,
    ): RecoverOutcome

    companion object {
        /** Upper bound on a single `recover` call's wall time. */
        const val DEFAULT_RECOVER_DEADLINE_MS: Long = 2_000L
    }
}
```

### 5.2 `RecoverOptions` — closed data

```kotlin
// New file: v2/pipeline-runtime/src/main/kotlin/.../runtime/recover/RecoverOptions.kt

package dev.rubentxu.pipeline.v2.runtime.recover

import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ReplayPolicy

/**
 * M2 — knobs for [RuntimeRecoverPort.recover].
 *
 * The default mirrors the existing production policy: NOT a dry run,
 * and the production replay policy. A consumer that wants to ask
 * "what would happen?" sets `dryRun = true`; the port computes the
 * decision without writing and returns the typed outcome.
 *
 * The replay policy is the same [ReplayPolicy] the existing
 * `EffectReplayPolicy.decide` consumes. M2 does NOT introduce a new
 * policy vocabulary; it reuses the SDK's enum verbatim.
 */
data class RecoverOptions(
    /** When true, the port computes the decision without writing. */
    val dryRun: Boolean = false,
    /**
     * The replay policy the port consults via
     * [dev.rubentxu.pipeline.v2.sdk.runtime.durable.EffectReplayPolicy.decide].
     * Default is `EffectReplayPolicy.Production` (mirrors the in-tree
     * `DefaultEffectReplayPolicy`).
     */
    val replayPolicy: ReplayPolicy = ReplayPolicy.RERUN,
    /**
     * Reporting-only threshold; the port reports observed lag at this
     * interval. NOT a refusal trigger. Mirrors M1 §2.2 / §3.3.
     */
    val lagReportIntervalMs: Long = 1_000L,
) {
    init {
        require(lagReportIntervalMs >= 0) {
            "lagReportIntervalMs must be non-negative, got $lagReportIntervalMs"
        }
    }

    companion object {
        /** The default options: not a dry run, production replay policy. */
        val Default: RecoverOptions = RecoverOptions()
    }
}
```

### 5.3 `RecoverOutcome` — closed ADT

```kotlin
// New file: v2/pipeline-runtime/src/main/kotlin/.../runtime/recover/RecoverOutcome.kt

package dev.rubentxu.pipeline.v2.runtime.recover

import dev.rubentxu.pipeline.v2.runtime.inspect.AttemptId
import dev.rubentxu.pipeline.v2.runtime.inspect.TerminalObservation

/**
 * M2 — closed ADT returned by [RuntimeRecoverPort.recover]. Each case
 * names a real outcome; new cases are compile errors at every
 * `when` site. Mirrors the audit's B.3 shape and the
 * `PK-SPEC-02-RUNTIME-OBSERVATION.md` `RecoveryChoice` ADT.
 */
sealed interface RecoverOutcome {

    /**
     * The run reached a known terminal state and the recovery is
     * complete. The [terminal] carries the typed observation (NOT a
     * raw `(StepOutcome, OperationStatus)` pair — see
     * [TerminalObservation] for why).
     *
     * This is the only outcome that does NOT re-execute external
     * effects; the terminal is the journal's own observation (audit
     * D.4, no-rerun invariant).
     */
    data class RecoveredTerminal(
        val attempt: AttemptId,
        val terminal: TerminalObservation,
        val terminalAtMs: Long,
        /** What the recovery did, for audit. */
        val report: RecoverReport,
    ) : RecoverOutcome

    /**
     * The substrate is still reattachable and no terminal has been
     * observed yet. The [deadlineMs] is the wall-clock deadline after
     * which a follow-up call should re-check.
     */
    data class ReattachPending(
        val attempt: AttemptId,
        val deadlineMs: Long,
    ) : RecoverOutcome

    /**
     * The recovery cannot proceed. [reason] names the closed
     * [RecoverRefusal] so a `when` over the typed reasons fails to
     * compile when a new failure mode is added.
     */
    data class FailClosed(val reason: RecoverRefusal) : RecoverOutcome

    /**
     * The run was already recovered by a prior call; this call was
     * a no-op. NOT a refusal — idempotency is the contract.
     */
    data class AlreadyRecovered(
        val attempt: AttemptId,
        val terminalAtMs: Long,
    ) : RecoverOutcome
}

/**
 * Bounded audit of what recovery did. The recover port reports
 * what it observed and what it wrote so the caller can verify the
 * "no-rerun" invariant on its own.
 */
data class RecoverReport(
    /** Number of journal rows reconciled (terminal rows committed). */
    val journalRowsCommitted: Int,
    /** Number of frames appended by `OutputFrameIndex.recoverUnframedBytes`. */
    val framesAppended: Int,
    /** Number of streams reconciled by `OutputRecoveryPort.recover`. */
    val streamsReconciled: Int,
    /** Whether the cursor advanced. */
    val cursorAdvanced: Boolean,
    /** The replay decisions observed per operation, in order. */
    val replayDecisions: List<String>,
)
```

### 5.4 `RecoverRefusal` — closed ADT

```kotlin
// New file: v2/pipeline-runtime/src/main/kotlin/.../runtime/recover/RecoverRefusal.kt

package dev.rubentxu.pipeline.v2.runtime.recover

/**
 * M2 — closed ADT of reasons a recover call failed closed. The
 * audit's B.3 names `NotRecoverable`, `UnknownRun`,
 * `JournalIncompatible`. This design adds `StorageError`,
 * `SubstrateUnavailable`, and `LeaseHeldByAnother` (mirroring the
 * cancel port — recover also MUST NOT cross lease boundaries, audit
 * G.3 by analogy).
 */
sealed interface RecoverRefusal {

    /** The run is not known to this PK instance. */
    data class UnknownRun(val runId: String) : RecoverRefusal

    /**
     * The substrate was inspected and yielded no recoverable evidence
     * (analogous to `RunningSubprocessObservation.Lost`). The run is
     * genuinely `Lost`, not `Unobservable` — these are different
     * cases on purpose.
     */
    data object NotRecoverable : RecoverRefusal

    /**
     * The substrate could not be inspected (analogous to
     * `RunningSubprocessObservation.Unavailable` /
     * `UnobservableCause.NoControlRootConfigured`).
     */
    data class SubstrateUnavailable(val cause: String) : RecoverRefusal

    /**
     * The journal is on a schema this PK cannot read. The audit's
     * `journal incompatible` test case is pinned here; the recover
     * port refuses rather than attempting to interpret a schema it
     * does not understand.
     */
    data class JournalIncompatible(val version: String) : RecoverRefusal

    /**
     * The run is held by another process whose lease is still live.
     * Recover MUST NOT cross lease boundaries (audit B.8, G.3 by
     * analogy); the live authority must release before recover can
     * proceed.
     */
    data class LeaseHeldByAnother(val details: String) : RecoverRefusal

    /** The underlying storage failed; [cause] is a short diagnostic. */
    data class StorageError(val cause: String) : RecoverRefusal
}
```

### 5.5 `RuntimeRecoverDecision` — the pure decider

The audit's B.6 gap asks for a public pure function
`decideRecovery(observation, journal): RecoveryChoice`. The
`PK-SPEC-02-RUNTIME-OBSERVATION.md` §"Modelo funcional" already
sketches the shape; this design formalises it.

```kotlin
// New file: v2/pipeline-runtime/src/main/kotlin/.../runtime/recover/RuntimeRecoverDecision.kt

package dev.rubentxu.pipeline.v2.runtime.recover

import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeObservation
import dev.rubentxu.pipeline.v2.runtime.inspect.TerminalObservation

/**
 * M2 — public pure decider the recover port composes. Returns a
 * [RecoveryChoice] that the recover port interprets into a
 * [RecoverOutcome]. The decider is PURE: same inputs always yield
 * the same outputs; no I/O, no clock, no process ID.
 *
 * The shape mirrors `PK-SPEC-02-RUNTIME-OBSERVATION.md` lines 22-37.
 * `Reattach` / `ReuseTerminal` / `FailClosed` are the three cases the
 * spec names; this design adds the `AlreadyRecovered` case to make the
 * idempotency check observable from outside the implementation.
 *
 * The decider composes the existing
 * [dev.rubentxu.pipeline.v2.sdk.runtime.durable.EffectReplayPolicy.decide]
 * (`pipeline-step-sdk/runtime:.../durable/EffectReplayPolicy.kt:63-95`,
 * normative matrix pinned by `EffectReplayPolicyTableFitnessTest`)
 * for the per-effect replay decision. It does NOT introduce a new
 * policy vocabulary.
 */
object RuntimeRecoverDecision {

    /**
     * Decide what to do with [observation] given the durable [journal].
     *
     * @param observation The run-level view from
     *                    [RuntimeIntrospectionPort.inspect].
     * @param journal     A `JournalProof` snapshot — the journal
     *                    rows for the run + the cursor state.
     *                    The decider does NOT call the journal; it
     *                    reads from the proof the caller passes in.
     *                    The proof shape is the same
     *                    `JournalProof` the spec already names; this
     *                    design defines it concretely below.
     * @return A [RecoveryChoice] that the recover port interprets.
     *
     *         Pure total function: every case in the closed
     *         [RuntimeObservation] ADT and every state of the
     *         journal proof maps to exactly one [RecoveryChoice]
     *         case.
     */
    fun decideRecovery(
        observation: RuntimeObservation,
        journal: JournalProof,
    ): RecoveryChoice = TODO("composed at M2-Impl; design pin: see §5.5.1")

    /**
     * A snapshot of the journal state the decider needs. The caller
     * is responsible for fetching this from
     * [OperationJournal.listForRun] +
     * [ReplayCursorStore.load] and passing it in; the decider does
     * not call the journal itself.
     */
    data class JournalProof(
        val terminalRow: TerminalRow?,
        val replayCursor: ReplayCursor?,
        val operations: List<OperationSnapshot>,
    )

    /**
     * The terminal journal row, if any. `null` when the run is not
     * terminal.
     */
    data class TerminalRow(
        val outcome: String,
        val terminalAtMs: Long,
    )

    /** Snapshot of one journaled operation; mirrors `DurableOperation`. */
    data class OperationSnapshot(
        val opId: String,
        val attempt: Int,
        val outcome: OperationOutcome?,
        val replayPolicy: String,
        val effects: List<String>,
    )

    /** Closed vocabulary of operation outcomes the decider recognises. */
    sealed interface OperationOutcome {
        data object Succeeded : OperationOutcome
        data object Failed : OperationOutcome
        data object Unstable : OperationOutcome
        data object Aborted : OperationOutcome
        data object Cancelled : OperationOutcome
        data object Running : OperationOutcome
        /** Any outcome not in the closed set. */
        data class Other(val raw: String) : OperationOutcome
    }

    /** Snapshot of the replay cursor; mirrors `ReplayCursorStore.load`. */
    data class ReplayCursor(
        val runId: String,
        val lastOpId: String,
        val stageIndex: Int,
        val savedAtMs: Long,
    )
}

/**
 * M2 — closed ADT returned by [RuntimeRecoverDecision.decideRecovery].
 * Mirrors `PK-SPEC-02-RUNTIME-OBSERVATION.md` lines 29-33 plus an
 * `AlreadyRecovered` idempotency case.
 */
sealed interface RecoveryChoice {

    /**
     * The substrate is still reattachable and no terminal has been
     * observed. The recover port returns [RecoverOutcome.ReattachPending]
     * with the deadline.
     */
    data class Reattach(
        val attempt: dev.rubentxu.pipeline.v2.runtime.inspect.AttemptId,
        val deadlineMs: Long,
    ) : RecoveryChoice

    /**
     * The substrate was inspected and the terminal is the journal's
     * own observation; the recover port materialises the terminal
     * and returns [RecoverOutcome.RecoveredTerminal]. This is the
     * ONLY case that does NOT re-execute external effects (audit
     * D.4).
     */
    data class ReuseTerminal(
        val receipt: TerminalReceipt,
    ) : RecoveryChoice

    /**
     * The recovery cannot proceed. The recover port returns
     * [RecoverOutcome.FailClosed] with the closed reason.
     */
    data class FailClosed(
        val cause: RecoverRefusal,
    ) : RecoveryChoice

    /**
     * The run was already recovered by a prior call. The recover
     * port returns [RecoverOutcome.AlreadyRecovered] without
     * touching the substrate.
     */
    data class AlreadyRecovered(
        val attempt: dev.rubentxu.pipeline.v2.runtime.inspect.AttemptId,
        val terminalAtMs: Long,
    ) : RecoveryChoice
}

/**
 * The typed terminal receipt the recover port materialises. Mirrors
 * [TerminalObservation] but carries the original
 * `ReplayDecision` per operation so the audit trail is observable.
 */
data class TerminalReceipt(
    val terminal: TerminalObservation,
    val perOperationDecisions: List<PerOperationDecision>,
)

/** Per-operation decision the decider made. */
data class PerOperationDecision(
    val opId: String,
    val replayDecision: String, // SKIP / RERUN / ABORT
)
```

#### 5.5.1 Decision matrix (normative, pinned by `RuntimeRecoverDecisionTableFitnessTest`)

```text
observation = Running(attempt, leaseHolder=non-null alive) AND
  journal.terminalRow == null AND
  any operations where outcome == RUNNING
                                    -> Reattach(attempt, deadlineMs=now + 30s)

observation = Running(attempt, leaseHolder=null) AND
  journal.terminalRow == null AND
  any operations where outcome == RUNNING
                                    -> ReuseTerminal(receipt)
                                       (the lease was released cleanly;
                                        the operations are terminal in
                                        the journal; reuse them)

observation = Terminal(attempt, terminal, terminalAtMs) AND
  journal.terminalRow matches
                                    -> AlreadyRecovered(attempt, terminalAtMs)

observation = Terminal(attempt, terminal, terminalAtMs) AND
  journal.terminalRow is null
                                    -> ReuseTerminal(receipt)
                                       (terminal in the journal's
                                        evidence but not in its rows;
                                        materialise from observation)

observation = LiveButEmpty(attempt, reason) AND
  journal.operations.isEmpty()
                                    -> FailClosed(SubstrateUnavailable(reason))

observation = Unobservable(reason) -> FailClosed(cause=StorageError(reason))

journal.terminalRow != null AND
  journal.terminalRow.outcome == "cancelled"
                                    -> AlreadyRecovered
                                       (a cancelled run is terminal;
                                        there is nothing to recover)

journal.terminalRow != null AND
  journal.terminalRow.outcome == "succeeded" / "failed" / "unstable"
  AND cursor advances
                                    -> ReuseTerminal(receipt)

observation == Unobservable(reason = StorageError) AND
  journal.storageError
                                    -> FailClosed(cause=StorageError(...))

ANY state where the journal schema version does not match this PK's
                                    -> FailClosed(JournalIncompatible(version))
```

The matrix is exhaustive over the closed `RuntimeObservation` ADT
and the closed `JournalProof` ADT. The contract test
`RuntimeRecoverDecisionTableFitnessTest` (§7.3) pins every cell.

### 5.6 Composition rule

`RuntimeRecoverPort.recover(runId, options)`:

```text
1. Introspect (existing):
   observation = RuntimeIntrospectionPort.inspect(runId)
     // For audit D.1: introspect does NOT recover; it observes.

2. Build the journal proof (existing):
   operations = OperationJournal.listForRun(runId)
   terminalRow = operations.find { it.endedAt != null }
   cursor = ReplayCursorStore.load(runId)
   proof = JournalProof(terminalRow, cursor, operations)

3. Decide (pure, existing primitive):
   decision = RuntimeRecoverDecision.decideRecovery(observation, proof)
     // PURE: same inputs always yield the same outputs.

4. Interpret the decision (existing primitives):
   when (decision) {
     is AlreadyRecovered -> return AlreadyRecovered(...)
     is Reattach        -> return ReattachPending(attempt, deadlineMs)
     is ReuseTerminal   -> {
       if (options.dryRun) return RecoveredTerminal(
         attempt, terminal=receipt.terminal, terminalAtMs=...,
         report=RecoverReport(...all zeros...),
       ) // dry run: do not write
       // Materialise (existing):
       materialised = RecoveredExecutionMaterializer.materialize(...)
       // Write exactly one terminal row (existing):
       OperationJournal.append(materialised)
       // Advance cursor (existing, idempotent CAS):
       ReplayCursorStore.advance(cursor)
       // Reconcile output store (existing):
       OutputRecoveryPort.recover()           // closes the durable gap
       OutputFrameIndex.recoverUnframedBytes() // appends frames for unframed bytes
       return RecoveredTerminal(
         attempt, terminal, terminalAtMs, report,
       )
     }
     is FailClosed -> return FailClosed(decision.cause)
   }
```

The recover port does not introduce a new write path. Every step
reuses a published-or-internal authority; the port's only novelty is
the order, the typed refusal envelope, and the no-rerun invariant
pin.

### 5.7 Authority `recover` composes

Named with module:file:line:

- `RuntimeIntrospectionPort.inspect` (step 1) — `v2/pipeline-runtime/src/main/kotlin/.../runtime/inspect/RuntimeIntrospectionPort.kt`
- `OperationJournal.listForRun` (step 2) — `pipeline-events-store/src/main/kotlin/.../events/durable/OperationJournal.kt:67-71`
- `ReplayCursorStore.load` (step 2) — `pipeline-events-store/.../events/durable/ReplayCursorStore.kt:16-64`
- `EffectReplayPolicy.decide` (step 3, via the new public decider wrapper) — `pipeline-step-sdk/runtime/src/main/kotlin/.../durable/EffectReplayPolicy.kt:63-95`
- `RecoveredExecutionMaterializer.materialize` (step 4, ReuseTerminal arm) — `pipeline-application/src/main/kotlin/.../durable/RecoveredExecutionMaterializer.kt:60-`
- `OperationJournal.append` (step 4, ReuseTerminal arm) — `pipeline-events-store/.../events/durable/OperationJournal.kt:30-36`
- `ReplayCursorStore.advance` (step 4, ReuseTerminal arm) — `pipeline-events-store/.../events/durable/ReplayCursorStore.kt:16-64`
- `OutputRecoveryPort.recover` (step 4, output reconciliation) — `pipeline-output-store/src/main/kotlin/.../output/store/OutputWritePorts.kt:166-176`
- `OutputFrameIndex.recoverUnframedBytes` (step 4, frame recovery) — `pipeline-output/src/main/kotlin/.../output/OutputFrameIndex.kt:107-200`
- `FileBackedRunExecutionLeaseStore.observe` (lease consultation, NOT acquisition) — `pipeline-events-store/.../events/durable/FileBackedRunExecutionLeaseStore.kt:40-253`, with the new read-only `observe(runId)` method pinned in §4.7.1

None of these is replaced, re-implemented, or moved. The recover
port is a thin composition; the only novel type is the public
`RuntimeRecoverDecision` decider wrapper, which is a pure function
that delegates to the existing `EffectReplayPolicy.decide` matrix.

## 6. Composition rules

The M2 ports compose the existing M1 ports and the existing internal
authorities. The composition table:

| M2 sub-view                       | M1 / internal port that backs it                                                 | Authority file:line |
|---|---|---|
| `RuntimeObservation.Running.journalPosition` | `OperationJournal.listForRun` + `OperationJournal.getEndedAt` | `pipeline-events-store/.../events/durable/OperationJournal.kt:67-89` |
| `RuntimeObservation.Running.outputTails` | `OutputTailPort.tailState` + `OutputFrameIndex.streamsOfRun` | `pipeline-output/.../output/OutputTailState.kt:86-96`, `pipeline-output/.../output/OutputFrameIndex.kt:140-200` |
| `RuntimeObservation.Running.leaseHolder` | `FileBackedRunExecutionLeaseStore.observe` + `RunExecutionLease.acquire` (pure decider) | `pipeline-events-store/.../events/durable/FileBackedRunExecutionLeaseStore.kt:40-253`, `pipeline-events-store/.../events/durable/RunExecutionLease.kt:213-247` |
| `RuntimeObservation.Terminal.terminal` | `OperationJournal.listForRun` + the journal's terminal `outcome` column | `pipeline-events-store/.../events/durable/OperationJournal.kt:67-89` |
| `IntrospectionRefusal.LeaseHeldByAnother` | `RunExecutionLease.acquire` returning `AlreadyOwned` | `pipeline-events-store/.../events/durable/RunExecutionLease.kt:223-247` |
| `CancelOutcome.Cancelled.streamsSealed` | `OutputSealPort.seal` (idempotent) | `pipeline-output-store/.../output/store/OutputWritePorts.kt:205-218` |
| `CancelOutcome.Cancelled.terminalAtMs` | `OperationJournal.append` + `clock.now()` | `pipeline-events-store/.../events/durable/OperationJournal.kt:30-36` |
| `CancelRefusal.LeaseHeldByAnother` | `RunExecutionLease.acquire` returning `AlreadyOwned` | `pipeline-events-store/.../events/durable/RunExecutionLease.kt:223-247` |
| `RecoverOutcome.RecoveredTerminal.terminal` | `RecoveredExecutionMaterializer.materialize` (existing) | `pipeline-application/.../durable/RecoveredExecutionMaterializer.kt:60-` |
| `RecoverReport.journalRowsCommitted` | `OperationJournal.append` | `pipeline-events-store/.../events/durable/OperationJournal.kt:30-36` |
| `RecoverReport.framesAppended` | `OutputFrameIndex.recoverUnframedBytes` | `pipeline-output/.../output/OutputFrameIndex.kt:107-200` |
| `RecoverReport.streamsReconciled` | `OutputRecoveryPort.recover` | `pipeline-output-store/.../output/store/OutputWritePorts.kt:166-176` |
| `RecoverReport.cursorAdvanced` | `ReplayCursorStore.advance` | `pipeline-events-store/.../events/durable/ReplayCursorStore.kt:16-64` |
| `RecoverRefusal.LeaseHeldByAnother` | `RunExecutionLease.acquire` returning `AlreadyOwned` | `pipeline-events-store/.../events/durable/RunExecutionLease.kt:223-247` |

The M2 ports live in `:pipeline-runtime`. The application-layer
adapters live in `:pipeline-application`. The internal authorities
stay in their existing modules. **No new authority is introduced.**

### 6.1 Segregation rule (§D.5)

The three ports are three different interfaces in three different
files in three different packages. Each has its own sealed `*Refusal`
ADT. None of them extends the others. A consumer that wants only
the inspect verb depends on `RuntimeIntrospectionPort`; the cancel
and recover verbs are not dragged into its classpath.

### 6.2 No new lease, no new fencing, no new scheduler (B.8)

The `FileBackedRunExecutionLeaseStore`
(`pipeline-events-store/.../events/durable/FileBackedRunExecutionLeaseStore.kt:40-253`)
is **not published**. The introspection and cancel ports consult the
existing pure decider via the application-layer adapter. The new
`observe(runId)` method on the file-backed store (§4.7.1) is a
read-only twin, not a new lease authority. The recover port does
not touch the lease store; it consults the observation the
introspection port produced.

No new scheduler is introduced. Every M2 port is pull-by-call. The
introspection port returns synchronously; the cancel port commits
the terminal row synchronously and returns; the recover port
returns synchronously with a typed outcome. The application layer
may adapt the introspection port to a polling follow (M1 pattern),
but that adaptation is an application-layer concern, not a
contract surface.

### 6.3 No `Flow`, no coroutines

The M2 ports are pull-by-call. The published contract does not
introduce `kotlinx-coroutines` or `Flow`. This mirrors the M1
discipline (`pipeline-events/.../events/identity/EventRecordReadPort.kt`,
the read-side; `pipeline-events/.../events/follow/EventFollower.kt`,
the iterator-style handle). The application layer may adapt the
ports to coroutine-shaped APIs internally; the contract does not.

### 6.4 Existing wakeup transport (no new wakeup)

The audit's §E.4 mentions `controller_cpu_silent`: the new M2 read
port MUST NOT introduce a per-customer polling loop with a fixed
interval. The cancel and recover ports emit wakeups through the
existing `ObservationWakeup` vocabulary
(`pipeline-application:.../observation/ObservationWakeup.kt:35-89`),
which is the same vocabulary the M1 follow contracts honour (M1
§6). The M2 first cut does not wire `ObservationWakeup` to a real
emitter — that is out of scope, identical to M1's out-of-scope note.
A future non-breaking addition can swap the implementation to
honour `ObservationWakeup`; the public types do not change.

## 7. Test surface

The audit's §E.2 names six new UAT cases (`UAT-PK-009` through
`-014`). The M1 design §5 corrected the UAT ID range to avoid
collision with the Fabric UAT matrix (`UAT-PK-M1-001..006`). This
design applies the same correction: the M2 cases are renamed to
`UAT-PK-M2-001..014` to match the package's M1-introduced
convention. The audit's G.4 open issue is closed by this
renumbering.

The test cases below list what the implementation must pass. The
test classes live in `:pipeline-runtime` for the port-level cases
and in `:pipeline-application` for the cross-JVM e2e cases.

### 7.1 Inspect

| Test file | Case | What it pins |
|---|---|---|
| `IntrospectionContractTest.kt` | `inspect(Running) returns Running(...)` | `Running` shape, including lease holder + fencing token + journal position + output tails. |
| `IntrospectionContractTest.kt` | `inspect(Terminal) returns Terminal(...)` | `Terminal` shape with the typed `TerminalObservation` (NOT a raw `(StepOutcome, OperationStatus)` pair). |
| `IntrospectionContractTest.kt` | `inspect(LiveButEmpty) returns LiveButEmpty(reason)` | Empty-but-known vs unknown (M1-A correction, §3.5). |
| `IntrospectionContractTest.kt` | `inspect(UnknownRun) returns Refused(UnknownRun)` | Closed refusal envelope; never an exception. |
| `IntrospectionContractTest.kt` | `inspect(LeaseHeldByAnother) returns Refused(LeaseHeldByAnother(ownerId, fencingToken))` | Lease authority is named, not collapsed. |
| `IntrospectionContractTest.kt` | `inspect(NoControlRoot) returns Refused(NoControlRoot)` | Substrate gap is typed, not free-text. |
| `IntrospectionContractTest.kt` | `inspect(InconsistentLeaseState) returns Refused(InconsistentLeaseState)` | Cross-substrate disagreement is typed. |
| `IntrospectionUnknownVsEmptyTest.kt` | `unknown runId returns UnknownRun; empty-but-known returns LiveButEmpty` | §3.5 pin. |
| `IntrospectionDoesNotMutateStateTest.kt` | `inspect N times in a row returns observations that differ only in monotonic progress` | Audit §D.1 + §E.2 UAT-PK-M2-012. |
| `IntrospectionConcurrencyTest.kt` | `N concurrent inspectors do not race the writer` | Audit §D.1, §E.2 UAT-PK-M2-012. |

### 7.2 Cancel

| Test file | Case | What it pins |
|---|---|---|
| `CancelContractTest.kt` | `cancel(Running, UserRequested) returns Cancelled(attempt, terminalAtMs, streamsSealed)` | Success path; terminal row written; per-stream seal invoked. |
| `CancelContractTest.kt` | `cancel(Terminal, UserRequested) returns AlreadyCancelled(attempt, terminalAtMs)` | Idempotency: cancel on already-cancelled is NOT a refusal (audit §D.3). |
| `CancelContractTest.kt` | `cancel(Terminal.succeeded, UserRequested) returns Refused(RunTerminal("succeeded"))` | Cancel on a non-cancelled terminal is a refusal with the typed outcome. |
| `CancelContractTest.kt` | `cancel(LeaseHeldByAnother) returns Refused(LeaseHeldByAnother(ownerId, fencingToken))` | Lease boundary (audit B.2, B.8, G.3). |
| `CancelContractTest.kt` | `cancel(UnknownRun) returns Refused(UnknownRun)` | Substrate gap typed, not exception. |
| `CancelContractTest.kt` | `cancel(JournalUnavailable) returns Refused(JournalUnavailable)` | Fail-closed on journal write failure. |
| `CancelContractTest.kt` | `cancel(IncompatibleRunState) returns Refused(IncompatibleRunState)` | Cancel does not silently fall through to `run()` (audit B.2). |
| `CancelIdempotencyTest.kt` | `N concurrent cancel calls yield exactly one Cancelled + N-1 AlreadyCancelled; never a Refused` | Audit §D.3 + §E.2 UAT-PK-M2-013. |
| `CancelNoSideEffectsTest.kt` | `cancel does NOT invoke any step's execute; output bytes already persisted are unchanged` | Audit §D.2 + §E.2 UAT-PK-M2-010. |
| `CancelDoesNotTakeLeakTest.kt` | `cancel does NOT call FileBackedRunExecutionLeaseStore.acquire` | Audit §B.8. |
| `CancelWakeupEmissionTest.kt` | `cancel emits ObservationWakeup.OutputAdvanced + EventsCommitted` | §6.4 pin: NO new wakeup transport. |

### 7.3 Recover

| Test file | Case | What it pins |
|---|---|---|
| `RecoverContractTest.kt` | `recover(ReuseTerminal) returns RecoveredTerminal(terminal, report)` | Success path; `report` is bounded and observable. |
| `RecoverContractTest.kt` | `recover(AlreadyRecovered) returns AlreadyRecovered(attempt, terminalAtMs)` | Idempotency: a second call is a no-op. |
| `RecoverContractTest.kt` | `recover(Reattach) returns ReattachPending(attempt, deadlineMs)` | Reattach path. |
| `RecoverContractTest.kt` | `recover(FailClosed(JournalIncompatible)) returns FailClosed(JournalIncompatible(version))` | Audit §B.3, §E.2 "journal incompatible" case. |
| `RecoverContractTest.kt` | `recover(FailClosed(NotRecoverable)) returns FailClosed(NotRecoverable)` | Substrate observed + empty. |
| `RecoverContractTest.kt` | `recover(FailClosed(SubstrateUnavailable)) returns FailClosed(SubstrateUnavailable(cause))` | Substrate not observable. |
| `RecoverContractTest.kt` | `recover(LeaseHeldByAnother) returns FailClosed(LeaseHeldByAnother(details))` | Lease boundary by analogy. |
| `RecoverNoRerunTest.kt` | `recover does NOT call any step's execute; the journal's terminal row is the source of truth` | Audit §D.4 + §E.2 UAT-PK-M2-014. |
| `RecoverDryRunTest.kt` | `recover(dryRun=true) computes the decision without writing` | Idempotency + observability. |
| `RecoverSideEffectCountTest.kt` | `run a side-effecting step, kill the host, recover — assert the side effect ran exactly once` | Audit §E.2 "cero duplicación de efectos"; counter is `double_side_effect_count`. |
| `RuntimeRecoverDecisionTableFitnessTest.kt` | `every (observation, journal) tuple maps to exactly one RecoveryChoice` | §5.5.1 matrix is exhaustive. |

### 7.4 Cross-JVM (audit §E.2 "ventanas")

The audit's plan specifies *caídas en varias ventanas, intentos
repetidos, observación ambigua, interrupción, journal incompatible
y cero duplicación de efectos*. The cross-JVM tests:

| Test file | Case | What it pins |
|---|---|---|
| `M2CrossJvmInspectTest.kt` | `UAT-PK-M2-001`: process A starts a run; process B inspects while A is alive | Cross-process inspect; observer sees `Running` with the lease holder. |
| `M2CrossJvmInspectTest.kt` | `UAT-PK-M2-002`: process A is SIGKILLed; process B inspects; B sees `Running` with `leaseHolder.alive = false` | Lease liveness reflects process death. |
| `M2CrossJvmInspectTest.kt` | `UAT-PK-M2-003`: process A is SIGKILLed mid-write; process B inspects; B sees a typed `InconsistentLeaseState` if the journal and lease disagree | Cross-substrate disagreement pin. |
| `M2CrossJvmCancelTest.kt` | `UAT-PK-M2-004`: process A starts a run; process B cancels from a separate JVM; A's writer stops appending within `p0_cancel_delivery_p95 < 2 s` | Audit §E.4 p0 cancellation budget. |
| `M2CrossJvmCancelTest.kt` | `UAT-PK-M2-005`: process B cancels twice in a row; second call is `AlreadyCancelled` | Idempotency cross-JVM. |
| `M2CrossJvmCancelTest.kt` | `UAT-PK-M2-006`: process A holds the lease; process B cancels; B sees `Refused(LeaseHeldByAnother)`; A's run is unaffected | Lease boundary cross-JVM. |
| `M2CrossJvmRecoverTest.kt` | `UAT-PK-M2-007`: process A starts a side-effecting step, B inspects (Running), A is SIGKILLed, B recovers, B sees `RecoveredTerminal` with the journal's terminal | No-rerun cross-JVM. |
| `M2CrossJvmRecoverTest.kt` | `UAT-PK-M2-008`: UAT-PK-M2-007 repeated, but B's recover is interrupted; B re-recovers; B sees `AlreadyRecovered` | Idempotency cross-JVM. |
| `M2CrossJvmRecoverTest.kt` | `UAT-PK-M2-009`: process A is SIGKILLed with a journal schema version this PK cannot read; B sees `FailClosed(JournalIncompatible)` | Audit "journal incompatible" test list. |
| `M2CrossJvmAmbiguityTest.kt` | `UAT-PK-M2-010`: process A's journal and lease disagree on owner; B sees `Refused(InconsistentLeaseState)`; A continues writing | Ambiguous observation. |
| `M2CrossJvmConcurrentInspectTest.kt` | `UAT-PK-M2-011`: 100 processes inspect the same runId in parallel; A is not slowed by more than `controller_cpu_silent` (audit §E.4) | Audit §E.4 controller-CPU-silent. |
| `M2CrossJvmRepeatedAttemptsTest.kt` | `UAT-PK-M2-012`: process B retries recover N times; the journal's terminal row is published once | Audit §E.2 "intentos repetidos" + §E.4 `out_of_order_advances = 0`. |
| `M2CrossJvmZeroDoubleEffectTest.kt` | `UAT-PK-M2-013`: SIGKILL supervisor != SIGKILL child; the side effect ran exactly once | Audit §E.2 "cero duplicación de efectos" + `double_side_effect_count = 0`. |
| `M2CrossJvmCancellationInterruptionTest.kt` | `UAT-PK-M2-014`: process B's cancel is interrupted mid-journal-write; A's writer either sees a terminal row or doesn't; B's retry sees the right answer | Audit §E.2 "interrupción". |

### 7.5 Capability registration (mirror M1-E)

A single contract test in
`v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/coordination/CapabilityRegistrationTest.kt`
mirrors the M1 test
(`v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/coordination/CapabilityRegistrationTest.kt`),
extended to assert that the M2 capabilities (`runtime.inspect.v1`,
`runtime.cancel.v1`, `runtime.recover.v1`) appear in the
`Capacidades publicadas` table of `INTERFACE_CONTRACT.md`, that the
table references the M2 certifying test classes, and that the
`CONTRACT_SHA256.txt` is refreshed. The M2 implementation does NOT
touch the contract; the next M2 candidate publication does
(§9.2).

## 8. Cross-walk to the cross-repo contract

### 8.1 Map to `INTERFACE_CONTRACT.md` §5

The contract's §5 says: *"Runtime inspeccionable:
`inspect/recover/cancel/follow` mediante interfaces segregadas,
reutilizando los puertos públicos REALES de PipelineK. Una lectura
no ejecuta recuperación destructiva; no relanza efectos externos
automáticamente."* The M2 design maps each clause:

| Contract clause | M2 design answer |
|---|---|
| `inspect/recover/cancel` segregated interfaces | Three new interfaces in three packages: `runtime.inspect.RuntimeIntrospectionPort`, `runtime.control.RuntimeControlPort`, `runtime.recover.RuntimeRecoverPort`. Each is a `fun interface` with one method; each carries its own sealed `*Refusal` ADT. |
| Reusing REAL public ports | The composition table (§6) names every existing authority the M2 ports compose; no port is re-implemented. |
| A read does not execute destructive recovery | `RuntimeIntrospectionPort.inspect` is pull-by-call with no journal write; the `IntrospectionDoesNotMutateStateTest` (§7.1) pins this. The audit's §D.1 verdict is PASS-by-design. |
| A read does not re-trigger external side effects | The recover port's `ReuseTerminal` arm materialises the journal's terminal; the `RecoverNoRerunTest` (§7.3) pins this. The audit's §D.4 verdict is PARTIALLY VERIFIABLE — the M2 work closes it. |

### 8.2 Contract SHA bump

The M2 implementation requires the `INTERFACE_CONTRACT.md`
"Capacidades publicadas" table to add three rows:

```text
| `runtime.inspect.v1` | v1 | `v0.50.0-rc1` (TBD by M2-Impl) | PipelineK |
  `RuntimeIntrospectionContractTest` (TBD) | `M2CrossJvmInspectTest` (TBD) | **PUBLICADA** |
| `runtime.cancel.v1`  | v1 | `v0.50.0-rc1` (TBD by M2-Impl) | PipelineK |
  `CancelContractTest` (TBD) | `M2CrossJvmCancelTest` (TBD) | **PUBLICADA** |
| `runtime.recover.v1` | v1 | `v0.50.0-rc1` (TBD by M2-Impl) | PipelineK |
  `RecoverContractTest` (TBD) | `M2CrossJvmRecoverTest` (TBD) | **PUBLICADA** |
```

The contract promotion is identical in shape to the M1 promotion:
the implementation lands and is `EXPERIMENTAL` first; after the M2
candidate is `CERTIFIED` against the test suite and the
`CapabilityRegistrationTest` is green, the table updates to
**PUBLICADA** and `CONTRACT_SHA256.txt` is refreshed. The contract
sha bump is a one-line, three-row update, plus a SHA refresh —
exactly the same shape as the M1 promotion.

### 8.3 `PAIR_RECEIPT.json` cells

The next handoff populates the M2 milestone section with the same
shape the M1 handoff used
(`docs/pipelinek-coordinated-evolution/handoff/PK_CONTRACT_HANDOFF.md`):
the SHA of the contract (after the bump), the PK tag, the Fabric
tag, the ABIs, the certified evidence, and the e2e cross-JVM
results. The cells the M2 next handoff populates:

- `milestone: "M2"`
- `pair_id: "M2-YYYYMMDD-pk-fabric"`
- `state: "BLOCKED"` → `"PAIR_CERTIFIED"` after verification
- `contract_sha256: <refreshed sha>`
- `releases.pipelinek.tag: "v0.50.0-rc1"`
- `releases.pipelinek.changed: true` (the M2 work introduces new public ports)
- `releases.fabric.tag: <fabric tag>` — set by the Fabric agent
- `releases.fabric.changed: true` if Fabric adopts the M2 ports, else `false`
- `interop.pipelinek_release_tag: "v0.50.0-rc1"`
- `interop.consumer_compiled: true` — the M2 ports are in the ABI; Fabric compiles against the artifact
- `interop.e2e_executed: true` — the M2 cross-JVM tests are green
- `evidence[].id`: the M2 certifying test IDs from §7 (one entry per evidence source, with `status: PASS`)
- `approvals[].role`: `PK_PROVIDER`, `FAB_CONSUMER`, `PAIR_INTEGRATOR`
- `rollback_pair.last_known_good: "M1-YYYYMMDD-pk-fabric"` (the M1 pair is the last known good until M2 ships)

The `pipelinek_certification_receipt.state` is `"RUN"` while the
M2 implementation is in flight; it becomes `"CERTIFIED"` after the
M2 candidate is published and the M2 capability table is in the
contract.

## 9. Release shape

### 9.1 Version

The M2 candidate is `v0.50.0-rc1`. The audit's F.5 names this as
the natural next PK pre-release; the M1 design used `v0.49.0-rc1`
and the M2 work introduces three new public ports without breaking
the M1 follow ports, so the version bump is a MINOR bump (0.49 →
0.50) plus the `-rc1` pre-release suffix. The release-receipt v2
model applies identically: PK publishes the candidate, Fabric
certifies against it, and the `PAIR_RECEIPT.json` records the
`PAIR_CERTIFIED` state.

### 9.2 Capabilities manifest

The M2 candidate's capabilities manifest (mirrors the M1
`v0.49.0-rc1` manifest at
`docs/pipelinek-coordinated-evolution/handoff/PK_CONTRACT_HANDOFF.md`)
declares:

- `output.follow.v1` — **PUBLICADA** in `v0.49.0-rc1` (carried forward).
- `events.follow.v1` — **PUBLICADA** in `v0.49.0-rc1` (carried forward).
- `runtime.inspect.v1` — **EXPERIMENTAL** in `v0.50.0-rc1`,
  promoted to **PUBLICADA** after the M2 candidate is `CERTIFIED`.
- `runtime.cancel.v1` — **EXPERIMENTAL** in `v0.50.0-rc1`,
  promoted to **PUBLICADA** after the M2 candidate is `CERTIFIED`.
- `runtime.recover.v1` — **EXPERIMENTAL** in `v0.50.0-rc1`,
  promoted to **PUBLICADA** after the M2 candidate is `CERTIFIED`.

The M2 implementation does NOT publish these capabilities. It
ADDS them in `EXPERIMENTAL` form (the static strings in
`Capabilities.kt`, §2.2). The promotion to **PUBLICADA** happens
in the contract-table update that follows the M2 candidate
certification.

### 9.3 Compatibility matrix (mirrors M1)

| Pair | PipelineK | Fabric | Verdict |
|---|---|---|---|
| PK `v0.49.0-rc1` × Fabric previous | n/a | n/a | Already certified. |
| PK `v0.50.0-rc1` × Fabric previous | Adds runtime verbs. | Negotiation: `output.follow.v1`, `events.follow.v1` present; `runtime.*.v1` absent. | Fabric falls back to M1 contract per `INTERFACE_CONTRACT §6` ("ausencia de `runtime.inspect.v1` requiere fallback anunciado o refusal tipado, nunca un falso LIVE"). |
| PK `v0.50.0-rc1` × Fabric adopting M2 | Adds runtime verbs. | Adopts the three M2 ports. | Certifies; new `PAIR_CERTIFIED` for M2. |
| PK future × Fabric adopting M2 | n/a | n/a | Future. |

The matrix is identical in shape to the M1 matrix.

## 10. Open issues for the orchestrator

The M2 design carries the audit's open issues (G.1–G.5) forward
and adds design-phase findings.

### 10.1 Resolved by this design

- **G.1 (home module).** Resolved by §2.1: a new `:pipeline-runtime`
  module hosts the three M2 ports. Justified against the
  segregation rule (§D.5) and the cross-plane composition
  requirement. The alternative (placing the three ports in
  `:pipeline-events`) was rejected because it would force an
  asymmetric `api` dependency on `:pipeline-output`.

- **G.2 (lease store for inspect).** Resolved by §4.7.1: a
  read-only `observe(runId)` method on the existing
  `FileBackedRunExecutionLeaseStore`. The lease store stays
  non-published; the introspection port consults the pure decider
  via the application-layer adapter.

- **G.3 (cancel across lease boundaries).** Resolved by §4.1 +
  §4.6: cancel consults the pure decider BEFORE writing; on
  `AlreadyOwned` the cancel returns `Refused(LeaseHeldByAnother(...))`
  with the live holder's fencing token. Cancel does not cross
  lease boundaries. Recover uses the same rule by analogy (§5.7).

- **G.4 (UAT IDs).** Resolved by §7: the M2 cases are renamed to
  `UAT-PK-M2-001..014` to match the M1-introduced convention.

- **G.5 (contract change).** Resolved by §8.2: the contract
  promotion is a three-row table update + a SHA refresh,
  identical in shape to M1.

### 10.2 New open issues raised by this design

- **G-M2-D1 (capability classification).** The M2 first cut
  advertises `runtime.*.v1` as `EXPERIMENTAL`. The next PK
  candidate `v0.50.0-rc1` carries them as `EXPERIMENTAL`. The
  promotion to `PUBLICADA` is decided after the candidate is
  certified. The orchestrator's decision: is `EXPERIMENTAL` the
  correct starting state, or should the M2 first cut be
  `STABLE-by-design` because the audit's gap is so well-scoped?
  Default in this design: `EXPERIMENTAL`, mirroring M1.

- **G-M2-D2 (recover dry-run coverage).** The M2 design includes
  `RecoverOptions.dryRun`. The orchestrator's decision: is a
  dry-run port part of the M2 contract, or is it an
  application-layer convenience on top of the closed
  `RuntimeRecoverDecision.decideRecovery` decider? Default in
  this design: part of the M2 contract, because the audit's
  G-M2 tests include "observation ambigua" — a dry-run is the
  cheapest way to surface a decision without writing.

- **G-M2-D3 (OutputPlaneInspection port).** The audit's B.5 names
  a typed query `OutputRecoveryStatus(storeId): RecoveryStatus`
  as a possible B.5 port. This design does NOT introduce that
  port; the same fact is reachable through
  `RuntimeIntrospectionPort.inspect(runId).outputTails` +
  a future typed query on the output tail. The orchestrator's
  decision: is a dedicated `OutputPlaneInspectionPort` required
  for the M2 contract, or is the inspection port's view
  sufficient? Default in this design: NOT required; the
  inspection port's `OutputTailView` is the answer.

- **G-M2-D4 (`OperationJournal.findTerminal`?).** The audit's
  B.4 discusses adding a `findTerminal(runId)` method to the
  journal. This design does NOT add it; the inspection port
  derives terminality by composing `listForRun` + `getEndedAt`.
  The orchestrator's decision: is the indexed lookup worth a
  new public method? Default in this design: NOT required; the
  composition cost is one SELECT and one IndexedSeq scan per
  inspect, well within `DEFAULT_INSPECT_TIMEOUT_MS = 5_000L`.

- **G-M2-D5 (cross-JVM test infra).** The M2 cross-JVM tests
  (§7.4) require the same `ProcessBuilder` infrastructure M1-D
  used. The orchestrator's decision: is the M1-D infrastructure
  sufficient, or does M2-D need new fixtures? Default in this
  design: M1-D is sufficient; M2-D reuses the same example
  consumer process.

- **G-M2-D6 (M2-Impl routing).** The M2 design is the contract;
  the implementation is `M2-Impl`. The orchestrator's decision:
  is `M2-Impl` a single block (like M1-A..M1-E) or a chain of
  blocks (M2-A inspect, M2-B cancel, M2-C recover, M2-D
  cross-JVM, M2-E capability registration)? Default in this
  design: a single block per port plus a single cross-JVM block
  plus a single capability-registration block, mirroring M1.

- **G-M2-D7 (recover report stability).** The
  `RecoverReport.replayDecisions` is a `List<String>` of the
  per-operation decisions (`SKIP / RERUN / ABORT`). The
  orchestrator's decision: should the report carry typed
  `ReplayDecision` (a M2-public re-export of the SDK enum) or
  free-text strings? Default in this design: free-text strings,
  because the SDK enum is a M2-private detail and exposing it
  makes the contract depend on `:pipeline-step-sdk/runtime`.
  Re-exporting is a future concern.

## 11. Implementation plan (in dependency order)

Each block is a work-unit commit on a `feat/cric-m2-runtime` branch;
no cross-block code is mixed. The plan mirrors M1 §10 and the
block 2 plan in the audit.

### Block M2-A — `:pipeline-runtime` module + capability surface

- New file `v2/pipeline-runtime/build.gradle.kts`: `kotlin("jvm") +
  maven-publish`, `api` deps on `:pipeline-domain`,
  `:pipeline-events`, `:pipeline-output`,
  `:pipeline-step-sdk:runtime`. No `kotlinx-coroutines`.
- New file `v2/pipeline-runtime/src/main/kotlin/.../runtime/Capabilities.kt`.
- New files in `runtime/inspect/`: `RuntimeIntrospectionPort.kt`,
  `RuntimeObservation.kt`, `IntrospectionRefusal.kt`,
  `RuntimeIntrospectionResult.kt`.
- Compile-only contract test
  `RuntimeIntrospectionPortCompileTest.kt` (the test imports every
  public type and asserts the public surface compiles against the
  published `:pipeline-events` and `:pipeline-output` artifacts).
- Add the `S7-sdk` publication to `:pipeline-runtime`.

### Block M2-B — cancel port

- New files in `runtime/control/`: `RuntimeControlPort.kt`,
  `CancelReason.kt`, `CancelOutcome.kt`, `CancelRefusal.kt`.
- New read-only method
  `FileBackedRunExecutionLeaseStore.observe(runId)` (§4.7.1).
- New application-layer adapter
  `RuntimeControlAdapter` in `:pipeline-application`.
- Surgical test `CancelContractTest.kt` (table-exhaustive over the
  closed `CancelOutcome` ADT).

### Block M2-C — recover port + decider

- New files in `runtime/recover/`: `RuntimeRecoverPort.kt`,
  `RecoverOptions.kt`, `RecoverOutcome.kt`, `RecoverRefusal.kt`,
  `RuntimeRecoverDecision.kt`.
- New application-layer adapter `RuntimeRecoverAdapter`.
- Surgical test `RecoverContractTest.kt` +
  `RuntimeRecoverDecisionTableFitnessTest.kt` (table-exhaustive
  over the closed `RuntimeObservation` × `JournalProof` matrix).

### Block M2-D — cross-JVM e2e

- New example consumer
  `examples/runtime-verbs-consumer/` that imports the published
  `:pipeline-runtime` artifact and exercises
  `runtime.inspect.v1`, `runtime.cancel.v1`, `runtime.recover.v1`
  in a separate `Process`.
- E2E test `M2CrossJvmInspectTest.kt`, `M2CrossJvmCancelTest.kt`,
  `M2CrossJvmRecoverTest.kt`, `M2CrossJvmAmbiguityTest.kt`,
  `M2CrossJvmConcurrentInspectTest.kt`,
  `M2CrossJvmRepeatedAttemptsTest.kt`,
  `M2CrossJvmZeroDoubleEffectTest.kt`,
  `M2CrossJvmCancellationInterruptionTest.kt` — together with the
  port-level tests, this is the M2 cross-JVM proof battery.

### Block M2-E — capability registration

- Update `CapabilityRegistrationTest` (the M1 test) to also assert
  the M2 capabilities appear in the contract's `Capacidades
  publicadas` table.
- Cross-reference in
  `coordination/INTERFACE_CONTRACT.md` line 6 + the
  `Capacidades publicadas` table: the three M2 capability IDs
  are referenced as `EXPERIMENTAL in pipeline-runtime
  v0.50.0-rc1+; promoted to STABLE after first M2-certified
  candidate`. The `CONTRACT_SHA256.txt` is refreshed.

## 12. Closed ADT discipline (M2 hold-to-the-line)

This design follows the M1 closed-ADT discipline:

- Every result of a port is a sealed interface or sealed class.
- Every case in a closed ADT is enumerated; new cases are compile
  errors at every `when` site.
- No `Either`/`Result` re-use; each port has its own typed refusal
  ADT.
- No nullable sentinels; `null` means "this lane is absent"
  (M1 design convention), and even then only in pre-existing ports
  the M2 design does not re-define.
- No `kotlinx-coroutines` in the published contract.
- No `Flow` in the published contract; pull-by-call.

## 13. Compliance with the Block 2 plan (audit F)

The audit's decision is `BLOCK 2 PK-CANDIDATE NEEDED`. The M2
design is the contract that the candidate implements. The design's
hold-to-the-line:

- *"Auditar primero las interfaces públicas existentes"* — the
  audit (commit `dc047cb2`) is the input; §0 of this design
  re-states the re-use table.
- *"La autoridad única de recovery"* — §5.7 names the existing
  authority (`EffectReplayPolicy.decide`, `OperationJournal`,
  `OutputRecoveryPort.recover`, `OutputFrameIndex.recoverUnframedBytes`).
  The M2 recover port composes them; it does not replace them.
- *"El journal privado"* — `OperationJournal` stays where it is
  (`:pipeline-events-store`, not published). The M2 ports read it
  via the application-layer adapter. No new public journal methods
  are introduced.
- *"Las políticas de efectos"* — `EffectReplayPolicy.decide` is the
  single authority; the M2 decider delegates to it verbatim.
- *"Implementar únicamente los puertos públicos faltantes para
  inspección no destructiva, cancelación y recuperación segura"* —
  exactly three ports (§4, §5, §6).
- *"No mover leases, fencing o scheduler a PipelineK"* — §6.2.
- *"Tests: caídas en varias ventanas, intentos repetidos,
  observación ambigua, interrupción, journal incompatible y cero
  duplicación de efectos"* — §7.4 UAT-PK-M2-002, -008, -010, -012,
  -009, -013.
- *"Validación integrada con Fabric sobre UAT-FW y AAT-05..10"* —
  the M2 next handoff populates the `PAIR_RECEIPT.json` (§8.3);
  the Fabric-side UAT/AAT matrix is set by the Fabric agent.
- *"Salida: release candidata PK si hubo evolución real de API; en
  otro caso, handoff de compatibilidad y reutilización del release
  PK anterior para la release Fabric M2"* — §9.1 names the PK
  candidate `v0.50.0-rc1` because there is real API evolution (the
  three M2 ports).

## 14. Out of scope

- Implementation. The M2 design is a contract; `M2-Impl` is the
  next block.
- v0.49.0-rc1 follow-ups. Frozen.
- v0.48.0 GA promotion. Integrator's decision.
- Cross-repo edits to `Rubentxu/pipelinek-fabric/...`. The M2
  next handoff is the contract-level handoff; the Fabric agent
  produces the consumer-side edits.
- M3, M4, M5, M6, M7. Independent blocks.
- Wiring `ObservationWakeup` to a real emitter. M1 §6 noted this
  is out of scope; this design inherits the note.

## 15. Open questions (held for the certifier and Fabric)

- Q1 (certifier): does the existing `wu-rp-053-workspace-contract`
  exercise a `recover` or `cancel` style read? If not, the M2 work
  proposes `UAT-PK-M2-001..014` as additions to the certifier's
  battery (the e2e tests in §7.4 are the producer-side
  counterparts).
- Q2 (Fabric): will the M2 runtime ports be added to the existing
  `examples/fabric-contract-consumer/` example, or is a new
  `examples/runtime-verbs-consumer/` needed? Default in this
  design: separate example, M2-D.
- Q3 (cross-repo contract): the `INTERFACE_CONTRACT.md` reference
  to `runtime.inspect.v1` / `runtime.cancel.v1` / `runtime.recover.v1`
  needs to be promoted from "absent" to "EXPERIMENTAL" with the
  same wording Fabric uses on its side. This promotion happens once
  Q1 and Q2 are answered and the M2 cross-JVM tests are green —
  not unilaterally here.
- Q4 (M3 / M4): the user has authorised opening investigations
  for M3 (retention/range invariants) and M4 (Jenkins import
  elimination) in independent branches. Those are out of scope
  for the M2 work; this design does not introduce any M3 or M4
  contract.