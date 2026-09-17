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

    /**
     * LFC-2E2-PREP (C1, 2026-09-17): the registered Step family resource.
     *
     * Identifies a typed Step family — a [dev.rubentxu.pipeline.v2.domain.step.StepDefinition]
     * registered in a [dev.rubentxu.pipeline.v2.domain.step.StepRegistry]. The shape
     * is `<ns=pipeline, step-definition/<pluginStepId.value>>`.
     *
     * One resource per `PluginStepId`, regardless of how many invocations run.
     * The [step] and [operation] builders continue to identify per-invocation
     * identities; [stepDefinition] identifies the family.
     */
    fun stepDefinition(pluginStepId: String): ResourceRef =
        ResourceRef(
            ResourceKind.STEP_DEFINITION,
            listOf(NAMESPACE, "step-definition", pluginStepId),
        )
}
