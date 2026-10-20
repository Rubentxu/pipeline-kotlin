package dev.rubentxu.pipeline.v2.domain.identity

/**
 * The ONLY authority for constructing [ResourceRef]s (EVT-1 law: typed
 * construction, no ad-hoc concatenation in production code).
 *
 * Deterministic: builders are pure functions of their arguments. Branch names,
 * completion order, timestamps and occurrence UUIDs are never inputs.
 *
 * Segment convention (hierarchical, parent-first):
 * ```
 * pipeline:  <ns=default, pipeline/<definitionId>>
 * run:       <ns=default, run/<runId>>
 * stage:     <ns=default, run/<runId>/stage/<stageIndex>>
 * step:      <ns=default, run/<runId>/stage/<stageIndex>/step/<stepIndex>>
 * operation: <ns=default, run/<runId>/stage/<stageIndex>/step/<stepIndex>/op/<opKey>>
 * ```
 * `runId` and `opKey` are the local durable authorities (RunId / OpId); here they
 * appear only as identity SEGMENTS of the projection.
 */
object ResourceRefs {
    const val NAMESPACE: String = "pipeline"

    fun pipeline(definitionId: String): ResourceRef =
        ResourceRef(ResourceKind.PIPELINE_DEFINITION, listOf(NAMESPACE, "pipeline", definitionId))

    fun run(runId: String): ResourceRef =
        ResourceRef(ResourceKind.RUN, listOf(NAMESPACE, "run", runId))

    fun stage(runId: String, stageIndex: Int): ResourceRef =
        ResourceRef(
            ResourceKind.STAGE,
            listOf(NAMESPACE, "run", runId, "stage", stageIndex.toString()),
        )

    fun step(runId: String, stageIndex: Int, stepIndex: Int): ResourceRef =
        ResourceRef(
            ResourceKind.STEP,
            listOf(NAMESPACE, "run", runId, "stage", stageIndex.toString(), "step", stepIndex.toString()),
        )

    fun operation(runId: String, stageIndex: Int, stepIndex: Int, opKey: String): ResourceRef =
        ResourceRef(
            ResourceKind.OPERATION,
            listOf(
                NAMESPACE, "run", runId,
                "stage", stageIndex.toString(),
                "step", stepIndex.toString(),
                "op", opKey,
            ),
        )

    // ---- LFC-2E2-prep plugin provider builders (ADR-0092) ----

    /**
     * Logical plugin identity (PLUGIN_IDENTITY_MODEL §"ResourceRef").
     * Segment convention: `<ns, plugin, <identity>>` (single-segment identity).
     * Use this for the logical identity of a provider; for an immutable
     * artifact, use [pluginRelease].
     */
    fun plugin(namespace: String, identity: String): ResourceRef =
        ResourceRef(
            ResourceKind.PLUGIN,
            listOf(namespace, "plugin", identity),
        )

    /**
     * Immutable plugin release identity.
     * Segment convention: `<ns, plugin, <identity>, release, <version>>`.
     * The release digest lives in `PluginReleaseRef`, not in segments
     * (digests are too long and contain forbidden characters).
     */
    fun pluginRelease(namespace: String, identity: String, version: String): ResourceRef =
        ResourceRef(
            ResourceKind.PLUGIN_RELEASE,
            listOf(namespace, "plugin", identity, "release", version),
        )

    /**
     * One Step family's addressable identity under a plugin release.
     * Segment convention: `<ns, plugin, <identity>, step, <stepKey>>`.
     */
    fun stepDefinition(namespace: String, identity: String, stepKey: String): ResourceRef =
        ResourceRef(
            ResourceKind.STEP_DEFINITION,
            listOf(namespace, "plugin", identity, "step", stepKey),
        )

    /**
     * A functional family under a plugin publisher.
     * Segment convention: `<ns, plugin-family, <family>>`.
     */
    fun pluginFamily(namespace: String, family: String): ResourceRef =
        ResourceRef(
            ResourceKind.PLUGIN_FAMILY,
            listOf(namespace, "plugin-family", family),
        )
}
