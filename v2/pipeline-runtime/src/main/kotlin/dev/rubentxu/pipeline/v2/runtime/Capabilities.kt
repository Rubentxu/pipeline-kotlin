package dev.rubentxu.pipeline.v2.runtime

import dev.rubentxu.pipeline.v2.domain.Capability

/**
 * Static capability identifiers published by PipelineK runtime surface.
 *
 * M1 capability strings (`output.follow.v1`, `events.follow.v1`) are
 * redeclared here from their historical homes (`:pipeline-output`,
 * `:pipeline-events`) so the contract test can enumerate all five IDs
 * from one place. Their semantics and certifying tests are unchanged.
 *
 * The M2 capabilities (`runtime.inspect.v1`, `runtime.cancel.v1`,
 * `runtime.recover.v1`) are advertised as EXPERIMENTAL in `v0.50.0-rc1`
 * and transition to PUBLICADA after the candidate is CERTIFIED by
 * `pipelinek-release-harness`.
 */
object Capabilities {
    // CRIC-M1 — PUBLICADA in v0.49.0-rc1, carried forward as PUBLICADA in v0.50.0-rc1
    val OUTPUT_FOLLOW_V1: Capability = Capability("output.follow.v1")
    val EVENTS_FOLLOW_V1: Capability = Capability("events.follow.v1")

    // CRIC-M2 — EXPERIMENTAL in v0.50.0-rc1, promoted to PUBLICADA after the candidate is CERTIFIED
    val RUNTIME_INSPECT_V1: Capability = Capability("runtime.inspect.v1")
    val RUNTIME_CANCEL_V1: Capability = Capability("runtime.cancel.v1")
    val RUNTIME_RECOVER_V1: Capability = Capability("runtime.recover.v1")
}