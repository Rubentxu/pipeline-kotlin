package dev.rubentxu.pipeline.v2.dsl

import dev.rubentxu.pipeline.v2.domain.CatchErrorBuildResult

/**
 * The WRAPPERS a body of steps may declare: the blocks that carry steps of
 * their own.
 *
 * ## Why this type exists
 *
 * The contract of the receiver of a body ([StepBodyScope]) declares one
 * member per operation, and a member per operation is a wide contract. The
 * stage scope answers the same width the same way: it is split into
 * `StageScopeCore`, `StageScopeTopSteps` and `StageScope`, and each part
 * stays inside the width the `TooManyFunctions` rule allows the facades of
 * this product.
 *
 * This part is the WRAPPERS: the blocks that carry steps. A wrapper is the
 * one shape that a body of steps has that a stage does not: the body of a
 * wrapper is a body, and it receives the same narrow receiver.
 *
 * ## Why the members here have NO value by default
 *
 * A value by default lives in ONE place: in the interface or in the class,
 * never in both. The Kotlin compiler refuses the two at once ("More than one
 * function overridden by 'x' declares a default value for 'y'"), and the
 * synthetic `$default` that carries a value by default is bound to the CLASS
 * that declares it: when the value moves to the interface, the synthetic
 * leaves the class and a consumer compiled against the old class fails with
 * `NoSuchMethodError`.
 *
 * So the values by default STAY in the concrete scopes, where the published
 * surface already carries them, and the forms that OMIT them are offered as
 * extensions over the contract (see [StepBodyScopeExtensions]).
 */
interface StepBodyScopeWrappers {

    /**
     * DECLARES the wrapper that binds one credential.
     *
     * @param binding the binding of the credential
     * @param block the steps that run with the credential
     */
    fun withCredentials(binding: StepSpec.CredentialsBinding, block: StepBodyScope.() -> Unit)

    /**
     * DECLARES the wrapper that binds credentials.
     *
     * @param bindings the bindings of the credentials
     * @param block the steps that run with the credentials
     */
    fun withCredentials(
        vararg bindings: StepSpec.CredentialsBinding,
        block: StepBodyScope.() -> Unit,
    )

    /**
     * DECLARES the wrapper that binds credentials.
     *
     * @param bindings the bindings of the credentials
     * @param block the steps that run with the credentials
     */
    fun withCredentials(
        bindings: List<StepSpec.CredentialsBinding>,
        block: StepBodyScope.() -> Unit,
    )

    /**
     * DECLARES the wrapper that binds a credential to a variable.
     *
     * @param credentialsId the credentials entry
     * @param variable the name of the variable the credential is bound to
     * @param block the steps that run with the variable
     */
    fun environment(credentialsId: String, variable: String, block: StepBodyScope.() -> Unit)

    /**
     * DECLARES the wrapper that runs the body with variables.
     *
     * @param overrides the variables the body declares, as `NAME=VALUE`
     * @param block the steps that run with them
     */
    fun withEnv(overrides: List<String>, block: StepBodyScope.() -> Unit)

    /**
     * DECLARES the wrapper that runs the body with variables.
     *
     * @param overrides the variables the body declares, as `NAME=VALUE`
     * @param block the steps that run with them
     */
    fun withEnv(overrides: Array<String>, block: StepBodyScope.() -> Unit)

    /**
     * DECLARES the wrapper that runs the body with variables.
     *
     * @param overrides the variables the body declares, by name
     * @param block the steps that run with them
     */
    fun withEnv(overrides: Map<String, String>, block: StepBodyScope.() -> Unit)

    /**
     * DECLARES the wrapper that runs the body in a directory.
     *
     * @param path the workspace-relative directory
     * @param block the steps that run in it
     */
    fun dir(path: String, block: StepBodyScope.() -> Unit)

    /**
     * DECLARES the wrapper that holds a named resource.
     *
     * @param resource the name of the resource
     * @param timeoutSeconds the bound on the wait
     * @param reason the human-readable reason of the wait
     * @param skipIfLocked whether the body runs when the resource is taken
     * @param block the steps that run while the resource is held
     */
    fun lock(
        resource: String,
        timeoutSeconds: Int?,
        reason: String?,
        skipIfLocked: Boolean,
        block: StepBodyScope.() -> Unit,
    )

    /**
     * DECLARES the wrapper that asks the author.
     *
     * @param message the question
     * @param ok the label of the affirmative answer
     * @param submitter who may answer
     * @param id the identifier of the question
     * @param timeoutSeconds the bound on the wait
     * @param block the steps that run after the answer
     */
    fun input(
        message: String,
        ok: String,
        submitter: String?,
        id: String?,
        timeoutSeconds: Int?,
        block: StepBodyScope.() -> Unit,
    )

    /**
     * DECLARES the wrapper that folds the failure of the body.
     *
     * @param buildResult the result the body must reach
     * @param stageResult the result of the stage
     * @param message the human-readable reason of the fold
     * @param block the steps whose failure is folded
     */
    fun catchError(
        buildResult: CatchErrorBuildResult,
        stageResult: CatchErrorBuildResult?,
        message: String?,
        block: StepBodyScope.() -> Unit,
    )

    /**
     * The STABLE historical spelling of [catchError], validated at construction.
     *
     * @param buildResult the token of the result the body must reach
     * @param stageResult the token of the result of the stage
     * @param message the human-readable reason of the fold
     * @param block the steps whose failure is folded
     */
    fun catchError(
        buildResult: String?,
        stageResult: String?,
        message: String?,
        block: StepBodyScope.() -> Unit,
    )

    /**
     * DECLARES the wrapper that warns without failing.
     *
     * @param message the human-readable warning
     * @param catchInterruptions whether an interruption is caught too
     * @param block the steps whose failure is warned
     */
    fun warnError(message: String, catchInterruptions: Boolean, block: StepBodyScope.() -> Unit)

    /**
     * DECLARES the wrapper that waits for a condition.
     *
     * @param initialRecurrencePeriod the first period of the poll
     * @param maxBackoffMs the bound of the backoff
     * @param body the condition that is polled
     */
    fun waitUntil(
        initialRecurrencePeriod: Long,
        maxBackoffMs: Long,
        body: StepBodyScope.() -> Unit,
    )

    /** DECLARES the wrapper that stamps the steps with the time. */
    fun timestamps(block: StepBodyScope.() -> Unit)

    /**
     * DECLARES the wrapper that bounds the time of the body.
     *
     * @param time the bound
     * @param unit the unit of the bound
     * @param block the steps that are bounded
     */
    fun timeout(time: Long, unit: String, block: StepBodyScope.() -> Unit)

    /**
     * DECLARES the wrapper that repeats the body.
     *
     * @param count the number of attempts
     * @param block the steps that are repeated
     */
    fun retry(count: Int, block: StepBodyScope.() -> Unit)

    /**
     * The REFUSAL of a retry with a delay.
     *
     * The runtime does not offer the retry with a delay, and the scope
     * refuses it where it is written. The body refuses it the same way: a
     * body that asks for it fails with the same diagnostic a stage fails with.
     *
     * @param count the number of attempts
     * @param delaySeconds the delay between the attempts
     */
    fun retry(count: Int, delaySeconds: Long?): Nothing

    /**
     * DECLARES the wrapper that NAMES one step of the body.
     *
     * @param name the name of the step
     * @param block the step that carries the name
     */
    fun slot(name: String, block: StepBodyScope.() -> Unit)
}
