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
}
