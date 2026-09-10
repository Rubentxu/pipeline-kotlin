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
}
