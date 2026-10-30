package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeCleanup
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.StepOutcome

/**
 * Folds a body's outcome and the outcome of releasing its credentials into ONE outcome.
 *
 * ## The rule
 *
 * A cleanup that succeeded leaves the body exactly as it was. A cleanup that failed is a fact
 * about the world, and the world can be broken in two ways:
 *
 *  - the body already failed: the body's failure is the verdict, and the cleanup failure is
 *    attached to it as its cause. The body's kind and message SURVIVE, because a reader must
 *    still be able to see WHY the body failed and a cleanup problem must not be able to
 *    overwrite that.
 *  - the body did not fail: there is no body failure to report but the cleanup, so the cleanup
 *    becomes the verdict.
 *
 * ## What this is not
 *
 * There is no path here in which a cleanup failure becomes a success, and none in which a body
 * failure disappears. The shape this replaces answered a failed cleanup with an
 * infrastructure failure over ANY body, which turned a failed body into a failure whose
 * message described something else entirely: the reason the body failed was gone, and a reader
 * could not tell a body that failed from a body that succeeded and whose cleanup did not.
 */
internal fun mergeBodyAndCleanup(bodyOutcome: StepOutcome, cleanup: CredentialScopeCleanup): StepOutcome =
    when (cleanup) {
        CredentialScopeCleanup.Cleaned -> bodyOutcome
        is CredentialScopeCleanup.Failed -> when (bodyOutcome) {
            is StepOutcome.Failure -> bodyOutcome.copy(
                failure = bodyOutcome.failure.copy(
                    cause = CredentialCleanupFailure(
                        orphanPaths = cleanup.orphanPaths,
                        message = cleanup.message,
                        cause = bodyOutcome.failure.cause,
                    ),
                ),
            )

            is StepOutcome.Success, is StepOutcome.Unstable -> StepOutcome.Failure(
                PipelineFailure(FailureKind.INFRASTRUCTURE, cleanup.message),
            )
        }
    }
