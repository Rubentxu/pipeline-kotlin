package dev.rubentxu.pipeline.core.dsl

@PipelineDsl
class Step(val block: suspend () -> Any)