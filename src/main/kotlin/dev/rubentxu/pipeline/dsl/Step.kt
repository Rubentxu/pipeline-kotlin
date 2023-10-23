package dev.rubentxu.pipeline.dsl

class Step(val block: suspend () -> Any)