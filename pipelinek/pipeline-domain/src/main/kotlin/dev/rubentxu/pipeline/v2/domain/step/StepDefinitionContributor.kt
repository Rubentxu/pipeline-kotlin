package dev.rubentxu.pipeline.v2.domain.step

/**
 * Public contribution SPI for external/core Step families (LB-02 / EP-F1).
 *
 * A plugin JAR (or the core itself) contributes zero or more [StepDefinition]s through this
 * interface. It lives in the public domain contract so a plugin module depends only on public API
 * and never on application/runtime internals.
 *
 * The domain MUST NOT call [java.util.ServiceLoader] itself: discovery is a runtime-adapter concern.
 * A runtime adapter loads contributors (e.g. via `ServiceLoader`) and folds their definitions into
 * a [StepRegistryBuilder] at composition time; [StepRegistry] itself is frozen and has no mutator.
 * The builder's [StepRegistryBuilder.add] fails closed on a duplicate StepKey, so a duplicate
 * contribution (core + plugin, or plugin A + plugin B) is rejected rather than first-wins/last-wins.
 *
 * The distinction is not cosmetic. While this paragraph still said "[StepRegistry.register]", it
 * named a method that BLOCK 1-F deleted, and it was the file a plugin author reads FIRST to learn
 * how to contribute — so the seam's own documentation pointed at an API that could not be called.
 * It survived because no test compiles a sentence: KDoc correctness is not mechanical, and this is
 * the cost of that.
 */
interface StepDefinitionContributor {
    /** Stable contributor identity (e.g. `example.uppercase`) used in duplicate diagnostics. */
    val id: String

    /** The Step families this contributor provides. */
    fun definitions(): Iterable<StepDefinition<*, *>>

    /**
     * The Step families this contributor provides, each wrapped in a
     * [StepRegistration] that carries [StepProviderMetadata] for the
     * additive provider-projection seam (LFC-2E2 / F5.1 / ADR-0092).
     *
     * Default implementation wraps every [definitions] entry into a
     * [StepRegistration] with the legacy-shape provider metadata
     * (publisher = `legacy-core` if the contributor id starts with
     * `core.`, otherwise `legacy-external`), preserving C10
     * backwards-compatibility: legacy contributors continue to work
     * without explicit provider metadata, and their events arrive
     * without [ProviderProvenance] in envelopes.
     *
     * New contributors (OFFICIAL_PLUGIN and beyond) MUST override this
     * method to return a [StepRegistration] per Step with real
     * [StepProviderMetadata] built from a manifest, so envelopes
     * emitted during real execution carry the audit projection.
     */
    fun registrations(): Iterable<StepRegistration<*, *>> =
        definitions().map { def ->
            StepRegistration.legacy(def, publisher = legacyPublisher())
        }

    /**
     * The publisher name to attribute to a legacy contributor. Kept as
     * a method (not a constant) so a contributor may override it.
     * Defaults to `legacy-core` for CORE contributors and
     * `legacy-external` for everything else.
     */
    fun legacyPublisher(): String =
        if (id.startsWith("core.")) "legacy-core" else "legacy-external"
}
