package dev.rubentxu.pipeline.v2.dsl

import dev.rubentxu.pipeline.v2.domain.FailureKind

/**
 * The ONE owner of a sequence of declared steps.
 *
 * ## What it owns
 *
 * Two things, and both of them used to be written more than once:
 *
 * - the SEQUENCE: the list the steps are declared into, and the CAPTURE that
 *   hands that list over as it is. A scope that kept its own list beside the
 *   one it published had two lists, and the step could land in the one nobody
 *   reads.
 * - the CONSTRUCTION of the steps that more than one scope declares: `echo`,
 *   `sh`, `error` and `sleep` were built three times —once in the stage
 *   scope, once in the branch scope and once in the post scope— and a step
 *   that is built in two places is a step that can differ in two places.
 *
 * ## What it does NOT own
 *
 * The SCOPE owns what the scope publishes. The scope's `steps` is part of the
 * published surface, and this class writes INTO that list instead of replacing
 * it: the list a consumer reads is the list the owner writes into, and there
 * is only one of them.
 *
 * ## Why it is INTERNAL
 *
 * The published surface does not move: the same `echo`, the same `sh`, the
 * same `error`, the same `sleep` and the same `steps` —and the same `StepSpec`
 * for each of them. What changes is WHERE the step is built, and where a step
 * is built is not a consumer's business.
 */
internal class StepSequence {

    /** The steps declared so far, in the order they were declared. */
    private val declared = mutableListOf<StepSpec>()

    /**
     * The list this owner writes into, as the scope publishes it.
     *
     * It is the LIVE list and not a copy: the scope publishes the steps it
     * declares, and a consumer that holds the published list holds the same
     * sequence the owner writes into.
     */
    fun published(): MutableList<StepSpec> = declared

    /**
     * The steps declared so far, as they are.
     *
     * A SNAPSHOT: the sequence is handed over and not lent. A body that was
     * captured is the body that was declared, and nothing that is declared
     * afterwards joins it.
     */
    fun capture(): List<StepSpec> = declared.toList()

    /** Declares a step that is BUILT elsewhere. */
    fun declare(step: StepSpec) {
        declared.add(step)
    }

    /** Declares the steps that were declared elsewhere, in that order. */
    fun declareAll(steps: List<StepSpec>) {
        declared.addAll(steps)
    }

    /** Declares the step that writes one line to the run log. */
    fun echo(text: String) {
        declared.add(StepSpec.Echo(text))
    }

    /**
     * Declares the shell step.
     *
     * @param command the command the shell runs
     * @param isScriptBlock whether the command is a block of shell lines and
     *   not a single command
     * @param returnStdout whether the step asks for the captured standard output
     */
    fun shell(command: String, isScriptBlock: Boolean = false, returnStdout: Boolean = false) {
        declared.add(
            StepSpec.Shell(
                command = command,
                isScriptBlock = isScriptBlock,
                returnStdout = returnStdout,
            ),
        )
    }

    /** Declares the step that fails the stage. */
    fun error(message: String, failureKind: FailureKind) {
        declared.add(StepSpec.Error(message, failureKind))
    }

    /** Declares the step that waits. */
    fun sleep(seconds: Long) {
        declared.add(StepSpec.Sleep(seconds))
    }

    /**
     * Declares the block that runs its body in another directory.
     *
     * @param path the directory, as the author wrote it
     * @param body the steps the block governs
     */
    fun dir(path: String, body: List<StepSpec>) {
        declared.add(StepSpec.Dir(path = path, steps = body))
    }

    /**
     * Declares the block that holds a named resource.
     *
     * @param resource the name of the resource to hold
     * @param timeoutSeconds the maximum wait once the resource is contended
     * @param reason the motive surfaced in the lock events
     * @param skipIfLocked whether the block runs nothing when the resource is held
     * @param body the steps the block governs
     */
    fun lock(
        resource: String,
        timeoutSeconds: Int?,
        reason: String?,
        skipIfLocked: Boolean,
        body: List<StepSpec>,
    ) {
        declared.add(
            StepSpec.Lock(
                resource = resource,
                timeoutSeconds = timeoutSeconds,
                reason = reason,
                skipIfLocked = skipIfLocked,
                steps = body,
            ),
        )
    }
}
