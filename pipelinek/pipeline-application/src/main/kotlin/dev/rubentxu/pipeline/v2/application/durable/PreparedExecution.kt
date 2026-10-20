package dev.rubentxu.pipeline.v2.application.durable

/**
 * Runtime-ephemeral marker of an invocation that has fully passed preparation / admission / decode
 * and is ready to produce effects through the single [CommonExecutionBoundary] (CDE.3-b2).
 *
 * Contract:
 *  - Never persisted, never serialized, never part of fingerprint or replay identity.
 *  - Opaque to the durable coordinator: it carries no Step semantics the coordinator can interpret
 *    and exposes no durable state.
 *  - Sealed ONLY over the two TEMPORAL structural execution-strategy families (CDE.3-d2):
 *    legacy-compatible ([PreparedLegacyExecution]) and registry ([PreparedRegistryExecution]). This is
 *    NOT a closed ADT over concrete Steps (never `Echo`/`Sh`/...): each family stays OPEN to many
 *    concrete plugin steps (e.g. every registered step becomes a [PreparedRegistryExecution] without
 *    adding a subtype). The boundary selects by strategy family, never by step key.
 *
 * The effect capabilities live in the executor's runtime, not inside this marker. Constructing a
 * [PreparedExecution] never produces Step side effects; only [CommonExecutionBoundary.execute] does.
 */
sealed interface PreparedExecution

/**
 * Result of an invocation's strategy preparation / admission / decode (CDE.3-b3). Distinct phases,
 * never conflated:
 *  - [Rejected] is admission failure BEFORE any effect; the common executor must NOT run.
 *  - [Ready] carries an opaque [PreparedExecution] that HAS passed preparation/admission/decode and
 *    is ready to be executed through [CommonExecutionBoundary].
 *
 * Producing a [Ready] never runs the Step; only [CommonExecutionBoundary.execute] produces effects.
 */
sealed interface ExecutionPreparation {
    data class Rejected(val reason: String) : ExecutionPreparation
    data class Ready(val prepared: PreparedExecution) : ExecutionPreparation
}
