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

    /**
     * LFC-2E2-PREP (C1, 2026-09-17): the registered Step family (a `StepDefinition`).
     *
     * A `STEP_DEFINITION` resource identifies a typed Step family — the contract,
     * the handler, the codecs, the required capabilities. It is a STATIC identity:
     * one resource per `PluginStepId`, regardless of how many invocations run.
     *
     * Use it for:
     *  - event metadata: an event about a Step invocation can carry the
     *    [STEP_DEFINITION] ref alongside the per-invocation [STEP] ref;
     *  - plugin release manifest: a `PluginReleaseRef` lists the STEP_DEFINITIONs
     *    the release contributes;
     *  - capability admission logs: the resource ref identifies the family whose
     *    capabilities the engine is admitting.
     *
     * Do NOT use it as a substitute for [STEP] (per-invocation identity) or
     * [OPERATION] (durable replay/journal identity).
     */
    STEP_DEFINITION,

    /**
     * LFC-2E2-PREP (C2, 2026-09-17): a plugin release identity.
     *
     * A `PLUGIN_RELEASE` resource identifies a single release of a plugin —
     * the coordinate (`<groupId>.<artifactId>`) plus the semver version. It is
     * the canonical identity for diagnostics, capability admission logs, and
     * release-archive cross-referencing.
     *
     * One resource per release. The same `coordinate` may ship multiple
     * versions over time, each with its own PLUGIN_RELEASE resource.
     */
    PLUGIN_RELEASE,
}
