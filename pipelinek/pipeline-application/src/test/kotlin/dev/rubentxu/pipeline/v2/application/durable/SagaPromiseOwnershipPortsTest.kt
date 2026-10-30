package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.saga.ArmRejection
import dev.rubentxu.pipeline.v2.domain.saga.CompensationArmOutcome
import dev.rubentxu.pipeline.v2.domain.saga.CompensationDeclaration
import dev.rubentxu.pipeline.v2.domain.saga.CompensationId
import dev.rubentxu.pipeline.v2.domain.saga.CompensationKey
import dev.rubentxu.pipeline.v2.domain.saga.CompensationKind
import dev.rubentxu.pipeline.v2.domain.saga.ResourceRef
import dev.rubentxu.pipeline.v2.domain.saga.SagaActionId
import dev.rubentxu.pipeline.v2.domain.saga.SagaId
import dev.rubentxu.pipeline.v2.domain.saga.SagaPlanFingerprint
import dev.rubentxu.pipeline.v2.domain.saga.SagaRecordOutcome
import dev.rubentxu.pipeline.v2.domain.saga.SagaRecordRejection
import dev.rubentxu.pipeline.v2.events.NullEventSink
import dev.rubentxu.pipeline.v2.events.durable.InMemorySagaObligationStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import java.time.Instant

/**
 * What a plugin is told when it reaches for a promise that is not its own.
 *
 * The ports are the seam a plugin actually touches, so this is where the refusal has to be
 * legible: the reason names the plugin that holds the promise, because "not allowed" without
 * a name sends the reader looking in the wrong place.
 *
 * ## What this file is for
 *
 * Three refusals, asked of the VOLATILE store the product already ships: a second plugin is
 * told who owns the promise, the owner may arm it again, and a foreign plugin is told whose
 * promise it tried to move. Ownership is a DECISION the ports take, not a fact about a file.
 *
 * ## What this file is NOT for
 *
 * The rest of ownership lives where it belongs: the durable store's own test
 * (SagaPromiseOwnershipStoreTest) asks the file who owns a promise and what survives the
 * writer, and the domain's own test asks what a promise with no recorded owner means. This
 * file does not ask those questions again.
 */
class SagaPromiseOwnershipPortsTest {

    private val store = InMemorySagaObligationStore()

    private fun intent(ownerPluginId: String?) = SagaCompensationOperations(
        runId = "run-1",
        forwardOpId = "run-1|stage-0|step-3",
        leaseEpoch = 1L,
        store = store,
        eventSink = NullEventSink,
        clock = FixedClock,
        ownerPluginId = ownerPluginId,
    )

    private fun plan(ownerPluginId: String?) = SagaPlanOperationsAdapter(
        runId = "run-1",
        leaseEpoch = 1L,
        store = store,
        clock = FixedClock,
        ownerPluginId = ownerPluginId,
    )

    private fun declaration() = CompensationDeclaration(
        sagaId = SAGA,
        actionId = SagaActionId.of("reserve-stock"),
        compensationId = CompensationId.of("release-stock"),
        planFingerprint = PLAN_A,
        resourceRef = ResourceRef.of("stock/reservation-42"),
        kind = CompensationKind.RESTORE,
    )

    @Test
    fun `another plugin that arms the same key is refused, and the reason names the owner`() {
        intent("example.saga").arm(declaration())

        val outcome = intent("other.saga").arm(declaration())

        val rejected = assertInstanceOf<CompensationArmOutcome.Rejected>(outcome)
        val refusal = assertInstanceOf<ArmRejection.NotAuthorized>(rejected.reason)
        assertTrue(refusal.reason.contains("example.saga"), refusal.reason)
    }

    @Test
    fun `the plugin that armed the promise may arm it again`() {
        intent("example.saga").arm(declaration())

        val outcome = intent("example.saga").arm(declaration())

        assertInstanceOf<CompensationArmOutcome.AlreadyArmed>(outcome)
    }

    @Test
    fun `a plugin that does not own the promise cannot move it`() {
        intent("example.saga").arm(declaration())

        val outcome = plan("other.saga").beginForward(KEY)

        val refused = assertInstanceOf<SagaRecordOutcome.Refused>(outcome)
        val foreign = assertInstanceOf<SagaRecordRejection.ForeignOwner>(refused.reason)
        assertEquals("example.saga", foreign.owner)
        assertEquals("other.saga", foreign.writer)
    }

    private object FixedClock : Clock {
        override fun now(): Instant = Instant.ofEpochMilli(2_000L)
    }

    private companion object {
        val SAGA = SagaId.of("checkout")
        val PLAN_A = SagaPlanFingerprint("a".repeat(64))
        val KEY = CompensationKey(
            sagaId = SAGA,
            actionId = SagaActionId.of("reserve-stock"),
            compensationId = CompensationId.of("release-stock"),
        )
    }
}
