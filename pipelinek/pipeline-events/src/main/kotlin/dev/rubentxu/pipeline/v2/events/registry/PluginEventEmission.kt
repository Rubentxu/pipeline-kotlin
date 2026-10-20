package dev.rubentxu.pipeline.v2.events.registry

import dev.rubentxu.pipeline.v2.domain.step.StepCapability

/**
 * Typed seam for a Step that contributes an event of its OWN through the open registry
 * (P3-C / S6.4).
 *
 * ## Why this lives in the PUBLISHED contract, not in application
 *
 * It started in `pipeline-application`, which is correct-looking and wrong: that module is not
 * published, so a plugin compiled against published artifacts — the only kind that can actually
 * ship — could not name this interface and would have had to cast to a type it could not import.
 * The seam a plugin uses is part of the plugin API, so it belongs beside the rest of it, in the same
 * module as the registry it emits through. The ADAPTER stays in application, where the runtime
 * facts are.
 *
 * ## Why not `core.emit.event`
 *
 * `core.emit.event` carries an `ALLOWED_KINDS` list and a `when(input.kind)`, which is the correct
 * shape for a core Step emitting a core event: the kinds it may emit are the engine's own
 * vocabulary. It is the wrong gateway for a plugin, for a reason that is not stylistic. To emit
 * through it, a plugin would have to name a kind the core Step has to know about, which means the
 * core grows a branch per plugin — the exact closed-world defect the Step registry was split to
 * remove, reappearing one layer down. So this seam is separate and the core list stays core's.
 *
 * ## What the handler receives, and what it must never receive
 *
 * The handler gets a typed [emit] and nothing else. It does NOT receive the
 * [dev.rubentxu.pipeline.v2.events.EventStore], the JSON log, SQLite, the sequence assigner, the
 * registry, the clock, or the `runId`. All of those stay behind the adapter, and the sequence is
 * assigned by the store — this seam cannot mint one, so a plugin cannot invent its own position in
 * the stream or overwrite a core event's identity.
 *
 * The `runId` is bound by the adapter from the runtime context, not passed in by the handler: a Step
 * that could name the run it belongs to could attribute its observation to somebody else's, and the
 * event would land in a stream whose cursor another consumer is holding.
 *
 * Declared in `StepContract.requiredCapabilities`; admission is fail-closed before the handler runs
 * when it is not available, and the capability stays unexposed when no registry was composed.
 */
interface PluginEventEmission {

    /**
     * Emits [payload] under the registered [kind] into THIS invocation's run.
     *
     * The outcome is the closed [EmissionOutcome]: an unregistered kind and a codec refusal are
     * both ordinary, named results, not exceptions. An [EmissionOutcome.Emitted] outcome carries
     * the STORE-ASSIGNED event, so the sequence is the authority's decision rather than an echo of
     * what was asked for.
     */
    fun <P : Any> emit(kind: String, payload: P): EmissionOutcome
}

/**
 * The capability key, in the same published contract as the seam it names.
 *
 * A plugin declares this in `StepContract.requiredCapabilities`, so the runtime admits it
 * fail-closed before any handler runs and re-checks before the handler executes.
 */
val PLUGIN_EVENT_EMISSION_CAPABILITY: StepCapability = StepCapability("plugin.event-emission")
