package dev.rubentxu.pipeline.v2.dsl

/**
 * Specification of a pipeline as built by the DSL.
 *
 * The [parameters] are the inputs the pipeline declares it accepts, and they are EMPTY for a
 * pipeline that declares none. They are carried as data and never as authority: the
 * declaration is the author's, and whoever checks a value against it does so with the rules
 * of [RunInputs].
*/
data class PipelineSpec(
    val stages: List<StageSpec>,
    val parameters: List<dev.rubentxu.pipeline.v2.domain.ParameterSpec> = emptyList(),

    /**
     * The variables the PIPELINE declares, as the declaration itself.
     *
     * The stages carry them too — a stage that declares none inherits them,
     * and a stage that declares one of its own keeps it — but the declaration
     * is the PIPELINE's, so it is carried here as well: the parts of the run
     * that belong to no stage (the pipeline's own `post`) run with the
     * variables the pipeline declared.
     *
     * `null` means the pipeline declares none, which is a different statement
     * from declaring an empty set.
     */
    val environment: Map<String, String>? = null,

    /**
     * `post` blocks declared on this PIPELINE.
     *
     * Carried as DATA from the DSL to the compiler, exactly as a stage's
     * `post` is: the DSL never decides which block runs. The difference is
     * WHERE it runs — a stage's `post` closes that stage, and the pipeline's
     * closes the PIPELINE, over the result of the whole run.
     *
     * A pipeline that declares none is the pipeline it always was: the field
     * defaults to an empty declaration, and an empty declaration produces no
     * entry at all.
     */
    val post: PostConditionSpec = PostConditionSpec(),
)

/**
 * Specification of a single stage within a pipeline.
*/
data class StageSpec(
    val name: String,
    val steps: List<StepSpec>,
    val options: OptionsSpec? = null,
    /**
     * Environment variables for this stage.
     * Injected via ProcessBuilder.environment into each step.
*/
    val environment: Map<String, String>? = null,

    /** Directives declared on this stage (declarative carrier only). */
    val directives: List<dev.rubentxu.pipeline.v2.domain.StageDirective> = emptyList(),

    /**
     * `post` blocks declared on this stage.
     * Carried as DATA from the DSL to the compiler; the DSL never decides which
     * block runs, it only records what the author declared.
*/
    val post: PostConditionSpec = PostConditionSpec(),
)

/**
 * Environment variables block.
*/
data class EnvironmentSpec(
    val values: Map<String, String>,
)

/**
 * Options block for stage-level configuration.
 * Only `timeout` is in the surface — it is the only stage option with
 * a runtime interpreter (projectShellOptions -> ShOptions.timeoutMs). Stage-level
 * Retry semantics live in the retry
 * Block Step (durable control row,); skip is not a durable-engine concept
 * The validity of `timeout` is enforced HERE as well as in [OptionsScope], and
 * both consult the same [StageTimeout] authority. The `init` is not redundant defence —
 * this data class is public API, so a consumer of the typed model can construct one
 * without ever touching the DSL, and before this type existed that path admitted
 * `OptionsSpec(timeout = Long.MAX_VALUE)` straight into the compiler's
 * `Math.multiplyExact`. "Invalid surface is unrepresentable" is a claim about the MODEL,
 * and a model with no `init` did not make it.
*/
data class OptionsSpec(
    val timeout: Long? = null,
) {
    init {
        // Reached only when `timeout` is non-null, so it can be named directly.
        require(timeout == null || StageTimeout.isValid(timeout)) {
            "OptionsSpec.timeout must be between 1 and ${StageTimeout.MAX_SECONDS} seconds, " +
                "was $timeout. Two separate things are wrong with that: a timeout of zero or " +
                "less is not a deadline, and a value this large has no millisecond form that " +
                "fits a Long, so it could never be projected into one. Omit the option to " +
                "declare no stage-wide deadline, which is a different statement."
        }
    }
}

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
 * Post conditions declared on a stage
 * Keyed by the closed `PostCondition` set rather than three named lists, so the
 * DSL and the IR speak the same typed vocabulary and a condition that the
 * planner cannot honour is a compile error instead of a silently ignored block.
 * The EXECUTION ORDER across conditions is not stored here: it belongs to
 * [dev.rubentxu.pipeline.v2.domain.post.PostCondition.EXECUTION_ORDER], the
 * single authority.
*/
data class PostConditionSpec(
    val conditions: Map<dev.rubentxu.pipeline.v2.domain.post.PostCondition, List<StepSpec>> = emptyMap(),
) {
    val isEmpty: Boolean get() = conditions.isEmpty()
}
