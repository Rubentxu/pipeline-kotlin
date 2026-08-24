package dev.rubentxu.pipeline.v2.application

interface PipelineUseCase<C : Any, O : Any> {
    suspend operator fun invoke(cmd: C): Result<O>
}
