package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.CorrelationId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import dev.rubentxu.pipeline.v2.events.durable.RunOwnerId
import dev.rubentxu.pipeline.v2.events.RunCorrelated
import java.time.Instant
import java.util.UUID

/**
 * PK-1 / ADR-PK1-IDENTITY — tests for the new contract types.
 *
 * The tests pin the schemas that the consumer depends on:
 *
 *  - [LaunchReceipt.encode] produces a single line of `pipelinek.launch/1`
 *    JSON with the exact fields documented in the ADR.
 *  - [CliExit.encodeEnvelope] produces a single line of `pipelinek.cli/1`
 *    JSON with the schema, exit, code, message, and optional hint.
 *  - [UuidCorrelationIdGenerator.next] produces a UUIDv4 (8-4-4-4-12).
 *  - [SdkCapabilities.ALL] contains the documented capability IDs.
 *  - [RunCorrelated] preserves its fields.
 *
 * A change that breaks any of these contracts is a deliberate API
 * change and must update the ADR + release-receipt.
 */
class Pk1ContractTypesTest {

    @Test
    fun `LaunchReceipt encode produces a single line with all required fields`() {
        val correlationId = CorrelationId("00000000-0000-4000-8000-000000000001")
        val ownerId = RunOwnerId.of("test-owner")!!
        val receipt = LaunchReceipt.launched(
            runId = dev.rubentxu.pipeline.v2.domain.RunId("00000000-0000-4000-8000-000000000002"),
            correlationId = correlationId,
            ownerId = ownerId,
            startedAtMs = 1700000000000L,
            controlDir = null,
        )
        val line = LaunchReceipt.encode(receipt)
        assertTrue(line.endsWith("\n"), "LaunchReceipt line must end with \\n")
        val withoutNewline = line.removeSuffix("\n")
        assertTrue(
            withoutNewline.startsWith("{\"schema\":\"pipelinek.launch/1\""),
            "LaunchReceipt must begin with the schema marker, got: $withoutNewline",
        )
        assertTrue(withoutNewline.contains("\"runId\":\"00000000-0000-4000-8000-000000000002\""))
        assertTrue(withoutNewline.contains("\"correlationId\":\"00000000-0000-4000-8000-000000000001\""))
        assertTrue(withoutNewline.contains("\"ownerId\":\"${ownerId.value}\""))
        assertTrue(withoutNewline.contains("\"startedAtMs\":1700000000000"))
        assertTrue(withoutNewline.contains("\"status\":\"Launched\""))
        assertTrue(withoutNewline.contains("\"ownerId\":\"${ownerId.value}\""))
    }

    @Test
    fun `LaunchReceipt refused carries the reason`() {
        val receipt = LaunchReceipt.refused(
            correlationId = CorrelationId("00000000-0000-4000-8000-000000000003"),
            reason = "admission rejected",
            startedAtMs = 1700000000000L,
        )
        val line = LaunchReceipt.encode(receipt)
        assertTrue(line.contains("\"status\":\"Refused:admission rejected\""))
    }

    @Test
    fun `CliExit envelope carries schema, exit name, code, message, and hint`() {
        val exit = CliExit.InvalidArgument(
            message = "bad arg",
            hint = "use --help",
        )
        val envelope = exit.encodeEnvelope()
        assertTrue(envelope.endsWith("\n"))
        val body = envelope.removeSuffix("\n")
        assertTrue(body.contains("\"schema\":\"pipelinek.cli/1\""))
        assertTrue(body.contains("\"exit\":\"InvalidArgument\""))
        assertTrue(body.contains("\"code\":2"))
        assertTrue(body.contains("\"message\":\"bad arg\""))
        assertTrue(body.contains("\"hint\":\"use --help\""))
    }

    @Test
    fun `CliExit cases map to the documented exit codes`() {
        assertEquals(0, CliExit.Success().code)
        assertEquals(1, CliExit.PipelineFailed("x").code)
        assertEquals(2, CliExit.InvalidArgument("x").code)
        assertEquals(3, CliExit.IncompatibleVersion("x").code)
        assertEquals(4, CliExit.RuntimeNotAvailable("x").code)
        assertEquals(5, CliExit.UnknownExecution("x").code)
        assertEquals(6, CliExit.ReadFailure("x").code)
        assertEquals(7, CliExit.IntegrityViolation("x").code)
    }

    @Test
    fun `UuidCorrelationIdGenerator produces UUIDv4`() {
        val gen = UuidCorrelationIdGenerator()
        repeat(8) {
            val id = gen.next()
            // UUID format: 8-4-4-4-12
            val parsed = UUID.fromString(id.value)
            assertNotNull(parsed)
            assertEquals(4, parsed.version(), "correlationId must be UUIDv4, got ${id.value}")
        }
    }

    @Test
    fun `CorrelationId rejects blank value`() {
        try {
            CorrelationId("")
            error("CorrelationId must reject blank value")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("not be blank"))
        }
    }

    @Test
    fun `RunCorrelated preserves fields`() {
        val event = RunCorrelated(
            eventId = "e-1",
            runId = "r-1",
            sequence = 1L,
            occurredAt = Instant.parse("2026-10-12T00:00:00Z"),
            correlationId = CorrelationId("00000000-0000-4000-8000-000000000004"),
            ownerId = "owner-1",
            startedAtMs = 1700000000000L,
        )
        assertEquals("e-1", event.eventId)
        assertEquals("r-1", event.runId)
        assertEquals(1L, event.sequence)
        assertEquals("RunCorrelated", event.kind)
        assertEquals(CorrelationId("00000000-0000-4000-8000-000000000004"), event.correlationId)
        assertEquals("owner-1", event.ownerId)
        assertEquals(1700000000000L, event.startedAtMs)
    }

    @Test
    fun `SdkCapabilities contains the documented 11 entries`() {
        val ids = SdkCapabilities.ALL.map { it.id }.toSet()
        val required = setOf(
            "output.follow.v1",
            "events.follow.v1",
            "runtime.inspect.v1",
            "runtime.cancel.v1",
            "runtime.recover.v1",
            "output.read.digested.v1",
            "output.pin.v1",
            "output.refusal.retention.v1",
            "runtime.structure.v1",
            "runtime.operation.v1",
            "runtime.eventshape.v1",
        )
        assertTrue(required.all { it in ids }, "missing required capabilities: ${required - ids}")
        assertTrue(SdkCapabilities.ALL.size == 11, "expected 11 capabilities, got ${SdkCapabilities.ALL.size}")
    }

    @Test
    fun `CliError InvalidCommand maps to CliExit InvalidArgument`() {
        val cliError: CliError = CliError.InvalidCommand("foo")
        val exit = cliError.toCliExit()
        assertTrue(exit is CliExit.InvalidArgument)
        assertEquals(2, exit.code)
    }
}
