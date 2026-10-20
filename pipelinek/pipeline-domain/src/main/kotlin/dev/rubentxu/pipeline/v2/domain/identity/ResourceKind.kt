package dev.rubentxu.pipeline.v2.domain.identity

/**
 * Kind of addressable pipeline entity a [ResourceRef] points to (EVT-1 minimal set).
 *
 * Not every pipeline concept is a resource: transient domain values (ExecutionContext,
 * overlays, retry/parallel decisions, prepared inputs, codec values) are explicitly
 * NOT resources and MUST NOT receive kinds here.
 */
enum class ResourceKind {
    PIPELINE_DEFINITION,
    RUN,
    STAGE,
    STEP,
    OPERATION,

    // LFC-2E2-prep (ADR-0092 / C1..C10): plugin provider kinds.
    // ResourceRef construction is still typed via [ResourceRefs] builders;
    // these kinds exist so the provider identity is addressable in audit
    // projections without re-parsing string segments. The wire serializer
    // is forward-compatible: it emits kind as a name string and resolves
    // it via valueOf on decode; new kinds fail-closed on old consumers.
    PLUGIN,
    PLUGIN_RELEASE,
    STEP_DEFINITION,
    PLUGIN_FAMILY,
}
