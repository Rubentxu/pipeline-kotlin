package dev.rubentxu.pipeline.v2.domain.step

/**
 * Public contribution SPI for external/core Step families (LB-02 / EP-F1).
 *
 * A plugin JAR (or the core itself) contributes zero or more [StepDefinition]s through this
 * interface. It lives in the public domain contract so a plugin module depends only on public API
 * and never on application/runtime internals.
 *
 * The domain MUST NOT call [java.util.ServiceLoader] itself: discovery is a runtime-adapter concern.
 * A runtime adapter loads contributors (e.g. via `ServiceLoader`) and registers their definitions
 * into a [StepRegistry] at composition time. [StepRegistry.register] fails closed on a duplicate
 * StepKey, so a duplicate contribution (core + plugin, or plugin A + plugin B) is rejected rather
 * than first-wins/last-wins.
 */
interface StepDefinitionContributor {
    /** Stable contributor identity (e.g. `example.uppercase`) used in duplicate diagnostics. */
    val id: String

    /** The Step families this contributor provides. */
    fun definitions(): Iterable<StepDefinition<*, *>>
}
