package dev.rubentxu.pipeline.core.interfaces

import dev.rubentxu.pipeline.core.jobs.StageResult

interface IPipeline {
    var stageResults: MutableList<StageResult>
    var currentStage: String
    suspend fun executeStages(context: IPipelineContext)

}
