package dev.rubentxu.pipeline.v2.domain

/** Declares where a static step contract expects to execute. */
enum class ExecutionLocation { CONTROLLER, WORKER, AGENT }
