package dev.rubentxu.pipeline.v2.application

@Deprecated("Unused since v0.39; kept for ABI")
interface PipelineUseCase<C : Any, O : Any> {
    @Suppress("unused")
    suspend operator fun invoke(cmd: C): Result<O>
}
