package dev.rubentxu.pipeline.v2.events.registry

/**
 * Public contribution SPI for the event kinds a plugin OWNS (P3-B / Semantic Constitution §9).
 *
 * A plugin JAR (or the core itself) contributes zero or more event declarations through this
 * interface. It lives in the published `pipeline-events` contract so a plugin depends only on
 * public API and never on application/runtime internals.
 *
 * ## Why this exists
 *
 * The registry, the carrier and the durable round trip all existed while nothing could ever
 * populate the registry from a classpath: `RegistryEventEmitter` was constructed in no production
 * site, and there was no `ServiceLoader` path for events at all, only for Steps, Directives and
 * Capabilities. A feature that cannot be reached is not delivered — the same shape as
 * `http.request` passing its whole contract suite and still being refused at admission.
 *
 * ## The domain does NOT load this
 *
 * Mirroring [dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor], the event model MUST
 * NOT call [java.util.ServiceLoader] itself: discovery is a runtime-adapter concern. A runtime
 * adapter loads contributors and registers their declarations into an [EventRegistry] at
 * composition time, and the registry refuses a duplicate kind rather than letting first-wins or
 * last-wins decide.
 *
 * ## Why a contributor returns DECLARATION OUTCOMES, not definitions
 *
 * [definitions] yields [EventDefinitionCreation], so a plugin can hand back a malformed
 * declaration and be told so by name, instead of the composition dropping it and carrying on. The
 * asymmetry is the point: a broken plugin JAR must abort composition, exactly as a broken Step or
 * capability contributor does, rather than degrade into a runtime where some of a plugin's events
 * exist and others silently do not.
 *
 * A contributor performs NO effects. It is a declaration, and a declaration that emitted anything
 * would be a Step wearing a contributor's name.
 *
 * @see EventDefinition for what one declaration carries.
 * @see EventRegistry for the fail-closed admission it is composed into.
 */
interface EventDefinitionContributor {

    /** Stable contributor identity (e.g. `acme.analytics`), used in composition diagnostics. */
    val id: String

    /**
     * The event kinds this contributor owns.
     *
     * Every entry is a construction OUTCOME rather than a bare definition, so an invalid
     * declaration reaches the registry as [EventDefinitionCreation.Invalid] with its reasons
     * intact and composition fails closed naming them.
     */
    fun definitions(): Iterable<EventDefinitionCreation<*>>
}
