package dev.rubentxu.pipeline.v2.domain.durable

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.TimeUnit

/**
 * The evidence the retry reconciler reads: the control row, the child row and
 * the identities that anchor them.
 *
 * These types are the boundary between what the journal holds and what the
 * reconciler decides, and they carry the rules that keep a decision attached
 * to a real operation: an attempt is 1-based, a child position is 0-based, an
 * operation id is named, and a schema version is at least the first one.
 *
 * They had no row either. A rule that no row reaches is a rule that nothing
 * proves: the reconciler's own rows build these values, and a value that
 * could be built wrongly would reach the decision without anybody noticing.
 *
 * The limit of the class is on the class, not on the rows.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class RetryEvidenceTest {

    private val fingerprint = Fingerprint("a".repeat(64))

    private fun control(attempt: Int = 1, status: OperationStatus = OperationStatus.RUNNING) =
        RetryControlRowSnapshot(attempt = attempt, status = status, fingerprint = fingerprint)

    private fun child(
        attempt: Int = 1,
        status: OperationStatus = OperationStatus.RUNNING,
        childIndex: Int = 0,
    ) = RetryChildRowSnapshot(attempt = attempt, childIndex = childIndex, status = status)

    private fun identity(operationId: String = "retry-1", schemaVersion: Int = 1) =
        RetryControlIdentity(operationId = operationId, schemaVersion = schemaVersion)

    // ── the guards of the evidence ────────────────────────────────────────────

    @Test
    @DisplayName("an input that allows no attempt is refused")
    fun anInputThatAllowsNoAttemptIsRefused() {
        val refusal = assertThrows<IllegalArgumentException> {
            RetryReconciliationInput(
                controlIdentity = identity(),
                maxAttempts = 0,
                currentFingerprint = fingerprint,
                controlRows = emptyList(),
                childrenByAttempt = emptyMap(),
                preControlChildren = emptyList(),
            )
        }
        assertTrue(
            refusal.message.orEmpty().contains("maxAttempts"),
            "the refusal did not name the field it is about: ${refusal.message}",
        )
    }

    @Test
    @DisplayName("an input that allows exactly one attempt is accepted")
    fun anInputThatAllowsExactlyOneAttemptIsAccepted() {
        // The budget is 1-based: one attempt is a budget, zero is not. A rule
        // that refused the first attempt would refuse every step that is not
        // allowed to be tried again.
        val accepted = RetryReconciliationInput(
            controlIdentity = identity(),
            maxAttempts = 1,
            currentFingerprint = fingerprint,
            controlRows = emptyList(),
            childrenByAttempt = emptyMap(),
            preControlChildren = emptyList(),
        )
        assertEquals(1, accepted.maxAttempts)
    }

    @Test
    @DisplayName("a control row of attempt zero is refused")
    fun aControlRowOfAttemptZeroIsRefused() {
        assertThrows<IllegalArgumentException> { control(attempt = 0) }
    }

    @Test
    @DisplayName("a child of attempt zero is refused")
    fun aChildOfAttemptZeroIsRefused() {
        assertThrows<IllegalArgumentException> { child(attempt = 0) }
    }

    @Test
    @DisplayName("a child of negative position is refused")
    fun aChildOfNegativePositionIsRefused() {
        assertThrows<IllegalArgumentException> { child(childIndex = -1) }
    }

    @Test
    @DisplayName("a control identity without an operation id is refused")
    fun aControlIdentityWithoutAnOperationIdIsRefused() {
        assertThrows<IllegalArgumentException> { identity(operationId = "   ") }
    }

    @Test
    @DisplayName("a control identity of schema zero is refused")
    fun aControlIdentityOfSchemaZeroIsRefused() {
        assertThrows<IllegalArgumentException> { identity(schemaVersion = 0) }
    }

    @Test
    @DisplayName("an attempt identity of ordinal zero is refused")
    fun anAttemptIdentityOfOrdinalZeroIsRefused() {
        val refusal = assertThrows<IllegalArgumentException> { identity().attempt(0) }
        assertTrue(
            refusal.message.orEmpty().contains("attemptOrdinal must be >= 1"),
            "the refusal did not name the argument it is about: ${refusal.message}",
        )
        assertFalse(
            refusal.message.orEmpty().contains("RetryAttemptIdentity"),
            "the factory refused through the identity it was going to build " +
                "instead of naming its own argument: ${refusal.message}",
        )
    }

    @Test
    @DisplayName("an attempt identity of ordinal one is accepted")
    fun anAttemptIdentityOfOrdinalOneIsAccepted() {
        // The FIRST attempt is a legal ordinal, and it is the boundary of the
        // guard. A guard that read its boundary as exclusive would refuse
        // every first attempt, and a suite that only ever asks for the
        // second attempt would not notice it.
        val attempt = identity().attempt(1)
        assertEquals(1, attempt.attemptOrdinal)
    }

    @Test
    @DisplayName("an attempt identity of negative ordinal is refused")
    fun anAttemptIdentityOfNegativeOrdinalIsRefused() {
        // The other side of the guard: a negative ordinal is not an ordinal.
        assertThrows<IllegalArgumentException> { identity().attempt(-1) }
        // The same guard in the identity itself, reached without the factory:
        // the factory is not the only door to the value.
        assertThrows<IllegalArgumentException> {
            RetryAttemptIdentity(control = identity(), attemptOrdinal = -1)
        }
    }

    @Test
    @DisplayName("a child identity of negative position is refused")
    fun aChildIdentityOfNegativePositionIsRefused() {
        assertThrows<IllegalArgumentException> { identity().attempt(1).child(-1) }
    }

    @Test
    @DisplayName("a child identity of position zero is accepted")
    fun aChildIdentityOfPositionZeroIsAccepted() {
        // The FIRST child of an attempt is at position zero, and it is the
        // boundary of the guard. A guard that read its boundary as exclusive
        // would refuse the first child of every attempt.
        val child = identity().attempt(1).child(0)
        assertEquals(0, child.childIndex)
    }

    // ── the identities ────────────────────────────────────────────────────────

    @Test
    @DisplayName("the control identity names the operation")
    fun theControlIdentityNamesTheOperation() {
        // The identity is the anchor of the refusal: the reconciler puts it in
        // the decision so that whoever reads the refusal knows WHICH retry
        // was refused.
        assertEquals("retry-1", identity().id())
    }

    @Test
    @DisplayName("the control identity delivers the operation id and the schema version")
    fun theControlIdentityDeliversTheOperationIdAndTheSchemaVersion() {
        // The two fields the identity carries are read by somebody: the
        // operation id names the retry and the schema version says which
        // shape of the record was persisted. A reader that got an empty id
        // or a version zero would refuse every retry for the wrong reason.
        val identity = identity()
        assertEquals("retry-1", identity.operationId)
        assertEquals(1, identity.schemaVersion)
    }

    @Test
    @DisplayName("the attempt identity is the control identity and its ordinal")
    fun theAttemptIdentityIsTheControlIdentityAndItsOrdinal() {
        val attempt = identity().attempt(2)
        assertEquals(2, attempt.attemptOrdinal)
        assertEquals("retry-1", attempt.control.id())
    }

    @Test
    @DisplayName("the child identity is the attempt identity and its position")
    fun theChildIdentityIsTheAttemptIdentityAndItsPosition() {
        val child = identity().attempt(2).child(1)
        assertEquals(1, child.childIndex)
        assertEquals(2, child.attempt.attemptOrdinal)
        assertEquals("retry-1", child.attempt.control.id())
    }

    // ── the predicates of the child evidence ─────────────────────────────────

    @Test
    @DisplayName("a child that has not finished is not terminal")
    fun aChildThatHasNotFinishedIsNotTerminal() {
        assertFalse(child(status = OperationStatus.PENDING).isTerminal)
        assertFalse(child(status = OperationStatus.RUNNING).isTerminal)
    }

    @Test
    @DisplayName("a child that has finished is terminal")
    fun aChildThatHasFinishedIsTerminal() {
        for (status in terminalStatuses) {
            assertTrue(
                child(status = status).isTerminal,
                "$status is a finished state and was not terminal",
            )
        }
    }

    @Test
    @DisplayName("only a successful child is a success")
    fun onlyASuccessfulChildIsASuccess() {
        assertTrue(child(status = OperationStatus.SUCCEEDED).isSuccess)
        for (status in allStatuses - OperationStatus.SUCCEEDED) {
            assertFalse(
                child(status = status).isSuccess,
                "$status is not a success and was read as one",
            )
        }
    }

    @Test
    @DisplayName("the failed states of a child are the failures that must run again")
    fun theFailedStatesOfAChildAreTheFailuresThatMustRunAgain() {
        // The snapshot's own vocabulary, which is NOT the vocabulary of
        // OperationStatus: here ABORTED counts as a failure, because the
        // reconciler cannot trust the outcome of an aborted child and must
        // run the body again. UNSTABLE is NOT a failure: the work ran.
        for (status in listOf(
            OperationStatus.FAILED,
            OperationStatus.FAILED_TIMEOUT,
            OperationStatus.LOST,
            OperationStatus.DIVERGENT,
            OperationStatus.ABORTED,
        )) {
            assertTrue(
                child(status = status).isFailure,
                "$status is a failure of a child and was not read as one",
            )
        }
        for (status in listOf(
            OperationStatus.PENDING,
            OperationStatus.RUNNING,
            OperationStatus.SUCCEEDED,
            OperationStatus.UNSTABLE,
        )) {
            assertFalse(
                child(status = status).isFailure,
                "$status is not a failure of a child and was read as one",
            )
        }
    }

    private companion object {
        val allStatuses = OperationStatus.entries.toSet()
        val terminalStatuses = allStatuses - setOf(OperationStatus.PENDING, OperationStatus.RUNNING)
    }
}
