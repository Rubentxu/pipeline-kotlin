package dev.rubentxu.pipeline.v2.sdk

/**
 * Typed carrier for capability injection. Per ADR-0003, steps declare
 * capability dependencies via Kotlin 2.4 context parameters; the runtime
 * orchestrator builds a StepContext and binds it at call-site.
 */
data class StepContext(
    val runId: String = "",
    val parameters: Map<String, String> = emptyMap(),
    val environment: Map<String, String> = emptyMap(),
) {
    companion object { val EMPTY = StepContext() }
}
