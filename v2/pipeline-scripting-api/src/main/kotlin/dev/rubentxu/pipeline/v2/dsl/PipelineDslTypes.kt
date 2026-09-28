package dev.rubentxu.pipeline.v2.dsl

/**
 * Specification of a pipeline as built by the DSL.
 */
data class PipelineSpec(
    val stages: List<StageSpec>,
)

/**
 * Specification of a single stage within a pipeline.
 */
data class StageSpec(
    val name: String,
    val steps: List<StepSpec>,
    val options: OptionsSpec? = null,
    val agent: AgentSpec? = null,
    /**
     * Environment variables for this stage.
     * Injected via ProcessBuilder.environment() into each step.
     */
    val environment: Map<String, String>? = null,
)

/**
 * Agent specification for a stage.
 */
data class AgentSpec(
    val label: String,
    val remoteUri: String? = null,
)

/**
 * Environment variables block.
 */
data class EnvironmentSpec(
    val values: Map<String, String>,
)

/**
 * Options block for stage-level configuration.
 *
 * WU-RP-032: only `timeout` is in the surface — it is the only stage option with
 * a runtime interpreter (projectShellOptions -> ShOptions.timeoutMs). Stage-level
 * `retry`/`skip` were removed (WU-RP-032): retry semantics live in the retry
 * Block Step (durable control row, ADR-0075); skip is not a durable-engine concept.
 * Invalid surface is unrepresentable instead of accepted-and-dropped.
 */
data class OptionsSpec(
    val timeout: Long? = null,
)

/**
 * Timeout configuration.
 */
data class TimeoutSpec(
    val seconds: Long,
    val action: TimeoutAction = TimeoutAction.FAIL,
)

/**
 * Action to take when timeout expires.
 */
enum class TimeoutAction {
    FAIL,
    CONTINUE,
    MARK_UNSTABLE,
}

/**
 * Post conditions for a stage (e.g., always, success, failure).
 */
data class PostConditionSpec(
    val always: List<StepSpec> = emptyList(),
    val success: List<StepSpec> = emptyList(),
    val failure: List<StepSpec> = emptyList(),
)

/**
 * Conditional execution using a when clause.
 *
 * DEAD TYPE. Nothing constructs or consumes this any more:
 * [StageScope.whenCondition] now rejects its input with `IllegalArgumentException`
 * because the IR has nowhere to carry [expression]. Kept only as a marker of the
 * intended-but-unimplemented design. Deleting it, or wiring it properly, is
 * tracked as debt — do not treat its presence as evidence that `when` blocks work.
 */
data class WhenCondition(
    val expression: String,
)
