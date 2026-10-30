package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeCleanup
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import java.nio.file.Path

/**
 * The fold of a body's outcome and the outcome of releasing its credentials.
 *
 * The claim under test is that a cleanup problem never REPLACES the body's own verdict, and
 * never hides it either: the body failed for its own reason, and the leftovers of the release
 * are a second fact that travels beside that reason instead of erasing it.
 */
class BodyCleanupOutcomeTest {

    private val orphan = Path.of("/tmp/credential-materialization")

    @Test
    fun `a clean cleanup leaves the body exactly as it was`() {
        val outcome = mergeBodyAndCleanup(StepOutcome.Success, CredentialScopeCleanup.Cleaned)

        assertEquals(StepOutcome.Success, outcome)
    }

    @Test
    fun `a clean cleanup leaves a failed body exactly as it was`() {
        val failure = PipelineFailure(FailureKind.SCRIPT, "the body refused")

        val outcome = mergeBodyAndCleanup(
            StepOutcome.Failure(failure),
            CredentialScopeCleanup.Cleaned,
        )

        assertEquals(StepOutcome.Failure(failure), outcome)
    }

    @Test
    fun `a failed cleanup over a failed body keeps the body's kind and message`() {
        val outcome = mergeBodyAndCleanup(
            StepOutcome.Failure(PipelineFailure(FailureKind.SCRIPT, "the body refused")),
            CredentialScopeCleanup.Failed(listOf(orphan), "could not wipe the materialization"),
        )

        // The reason the body failed is the verdict. A cleanup that could not wipe what it
        // materialized is a fact about the world, and it does not get to say what went wrong
        // inside the body.
        val failure = assertInstanceOf<StepOutcome.Failure>(outcome).failure
        assertEquals(FailureKind.SCRIPT, failure.kind)
        assertEquals("the body refused", failure.message)
    }

    @Test
    fun `the leftovers of a failed cleanup travel as the cause of the body's failure`() {
        val outcome = mergeBodyAndCleanup(
            StepOutcome.Failure(PipelineFailure(FailureKind.SCRIPT, "the body refused")),
            CredentialScopeCleanup.Failed(listOf(orphan), "could not wipe the materialization"),
        )

        val failure = assertInstanceOf<StepOutcome.Failure>(outcome).failure
        val cause = assertInstanceOf<CredentialCleanupFailure>(failure.cause)
        assertEquals(listOf(orphan), cause.orphanPaths)
        assertEquals("could not wipe the materialization", cause.message)
    }

    @Test
    fun `a failed cleanup over a body that did not fail becomes the verdict`() {
        val outcome = mergeBodyAndCleanup(
            StepOutcome.Success,
            CredentialScopeCleanup.Failed(listOf(orphan), "could not wipe the materialization"),
        )

        // There is no body failure to report, so the cleanup is the verdict.
        val failure = assertInstanceOf<StepOutcome.Failure>(outcome).failure
        assertEquals(FailureKind.INFRASTRUCTURE, failure.kind)
        assertEquals("could not wipe the materialization", failure.message)
    }

    @Test
    fun `a failed cleanup over an unstable body becomes the verdict too`() {
        val outcome = mergeBodyAndCleanup(
            StepOutcome.Unstable,
            CredentialScopeCleanup.Failed(listOf(orphan), "could not wipe the materialization"),
        )

        val failure = assertInstanceOf<StepOutcome.Failure>(outcome).failure
        assertEquals(FailureKind.INFRASTRUCTURE, failure.kind)
    }

    @Test
    fun `no cleanup turns a failure into a success`() {
        val outcomes = listOf(
            mergeBodyAndCleanup(
                StepOutcome.Failure(PipelineFailure(FailureKind.SCRIPT, "the body refused")),
                CredentialScopeCleanup.Cleaned,
            ),
            mergeBodyAndCleanup(
                StepOutcome.Failure(PipelineFailure(FailureKind.SCRIPT, "the body refused")),
                CredentialScopeCleanup.Failed(listOf(orphan), "could not wipe the materialization"),
            ),
            mergeBodyAndCleanup(
                StepOutcome.Success,
                CredentialScopeCleanup.Failed(listOf(orphan), "could not wipe the materialization"),
            ),
        )

        assertTrue(
            outcomes.none { it is StepOutcome.Success },
            "a failed body or a failed cleanup must never read as a success",
        )
    }

    @Test
    fun `a clean cleanup leaves no cause behind`() {
        val outcome = mergeBodyAndCleanup(
            StepOutcome.Failure(PipelineFailure(FailureKind.SCRIPT, "the body refused")),
            CredentialScopeCleanup.Cleaned,
        )

        val failure = assertInstanceOf<StepOutcome.Failure>(outcome).failure
        assertNull(failure.cause)
    }

    @Test
    fun `the cause of a body failure is preserved under the cleanup failure`() {
        val original = IllegalStateException("the body threw")

        val outcome = mergeBodyAndCleanup(
            StepOutcome.Failure(PipelineFailure(FailureKind.SCRIPT, "the body refused", original)),
            CredentialScopeCleanup.Failed(listOf(orphan), "could not wipe the materialization"),
        )

        val failure = assertInstanceOf<StepOutcome.Failure>(outcome).failure
        val cause = assertInstanceOf<CredentialCleanupFailure>(failure.cause)
        assertSame(original, cause.cause)
    }
}
