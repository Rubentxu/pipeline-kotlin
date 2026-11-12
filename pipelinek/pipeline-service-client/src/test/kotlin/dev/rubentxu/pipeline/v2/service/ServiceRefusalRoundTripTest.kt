package dev.rubentxu.pipeline.v2.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Every refusal the service writes is the refusal its client reads.
 *
 * ## The guarantee under trial
 *
 * The service and its client share one codec, and a refusal crosses it twice: the service encodes
 * it under its kind and the client decodes that kind back into the same value. A kind the encoder
 * writes and the decoder does not know is not a refusal for the client: the line falls to
 * `Unreadable`, and a client that branches on the refusal's name never sees the name.
 *
 * ## Why each row is here
 *
 * - The trip is taken by every refusal of the vocabulary, with the facts it carries, because the
 *   word is written in one place and read in another and nothing else ties the two.
 * - The census compares the list with the sealed hierarchy, so a refusal added to the vocabulary
 *   and not to the trip fails here instead of shipping unread.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ServiceRefusalRoundTripTest {

    @Test
    fun `every refusal the service encodes is read back by the client as the same refusal`() {
        REFUSALS.forEach { refusal ->
            val line = ServiceCodec.encode(ServiceResponse.Refused(ServiceProtocol.SCHEMA, refusal))

            assertEquals(
                ServiceLine.Refused(ServiceProtocol.SCHEMA, refusal),
                ServiceCodec.decodeLine(line),
                "the client does not read back the refusal '${refusal.kind}' the service wrote: $line",
            )
        }
    }

    @Test
    fun `the trip is taken by every refusal of the vocabulary`() {
        val permitted = checkNotNull(ServiceRefusal::class.java.permittedSubclasses) {
            "the vocabulary is not a sealed hierarchy the census can read"
        }

        assertEquals(
            permitted.map { it.name }.sorted(),
            REFUSALS.map { it.javaClass.name }.distinct().sorted(),
            "a refusal of the vocabulary is missing from the trip, or the trip names one twice",
        )
    }

    private companion object {
        val REFUSALS: List<ServiceRefusal> = listOf(
            ServiceRefusal.UnknownRun("run-1"),
            ServiceRefusal.NotConfiguredControlRoot,
            ServiceRefusal.NotConfiguredEventPlane,
            ServiceRefusal.NotConfiguredOutputPlane,
            ServiceRefusal.StorageFailed("the disk is gone"),
            ServiceRefusal.HeldByAnother("owner-1", 7L),
            ServiceRefusal.Inconsistent("the journal and the lease disagree"),
            ServiceRefusal.UnknownRunOperation("op-1", 2),
            ServiceRefusal.IncompatibleProtocol,
            ServiceRefusal.MalformedRequest,
            ServiceRefusal.RequestTooLarge,
            ServiceRefusal.TooManyConnections,
            ServiceRefusal.UnknownOp,
            ServiceRefusal.InvalidCursor("not-a-cursor", "evt-cursor-v1"),
            ServiceRefusal.CursorBeyondTail("run-1", 40L, 12L),
            ServiceRefusal.OutputPlaneRefused("retention-gap", "the bytes are gone"),
            ServiceRefusal.NotAuthorized,
            ServiceRefusal.RunNotAuthorized("run-1"),
            ServiceRefusal.TooManySubscriptions,
            ServiceRefusal.StartNotAuthorized,
            ServiceRefusal.ScriptNotAdmitted("/work/build.pipeline.kts"),
            ServiceRefusal.WorkspaceNotAdmitted("/work"),
            ServiceRefusal.IdempotencyConflict("key-1"),
            ServiceRefusal.TooManySupervisedRuns,
            ServiceRefusal.RunStartRefused("child-not-started", "the launcher is gone"),
            ServiceRefusal.RunConfigurationRefused("unknown-key", "no key named 'x'"),
            ServiceRefusal.ParametersRefused("missing", "no value for 'target'"),
            ServiceRefusal.DescribeParametersRefused("not-compiled", "the script does not compile"),
            ServiceRefusal.ConfigurationProviderRefused("session-closed", "the session is gone"),
            ServiceRefusal.CredentialProviderRefused("session-closed", "the session is gone"),
            ServiceRefusal.CancelNotAuthorized,
            ServiceRefusal.RunCancelRefused("already-terminal", "the run ended"),
            ServiceRefusal.AnswerNotAuthorized,
            ServiceRefusal.InputAnswerRefused("no-question", "the run asks nothing"),
        )
    }
}
