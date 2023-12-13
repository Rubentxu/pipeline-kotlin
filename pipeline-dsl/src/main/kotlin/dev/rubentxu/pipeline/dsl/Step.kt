package dev.rubentxu.pipeline.dsl

@PipelineDsl
class Step(val block: suspend () -> Any)