package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.CorrelationId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import dev.rubentxu.pipeline.v2.events.durable.RunOwnerId
import dev.rubentxu.pipeline.v2.events.RunCorrelated
import java.io.File
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

    /**
     * H1.5 (2026-10-18). A rejection that says "run 'pipeline --help'" is a promise; the
     * question this asks is whether the promise is kept.
     *
     * Before this, `--help` was itself rejected by the very path whose message pointed at
     * it. The mutation for this row is deleting `HELP_TOKENS` from `isHelpRequest`'s set,
     * which is indistinguishable from the shipped defect.
     */
    @Test
    fun `every flag a rejection hint names is one the CLI accepts`() {
        val hints = listOf(CliError.MissingCommand, CliError.InvalidCommand("observ"))
            .mapNotNull { it.toCliExit().hint }
        assertTrue(hints.isNotEmpty(), "expected at least one rejection hint to check")
        val named = Regex("'([^']+)'").findAll(hints.joinToString(" "))
            .flatMap { quoted -> quoted.groupValues[1].split(' ', '\t').asSequence() }
            .filter { it.startsWith("-") }   // a flag, not the binary name in "run 'pipelinek --help'"
            .toList()
        assertTrue(named.isNotEmpty(), "no flag named in any hint; the cross-check would be vacuous")
        val unkept = named.filterNot { isHelpRequest(arrayOf(it)) }
        assertTrue(
            unkept.isEmpty(),
            "a rejection tells the operator to run ${unkept.joinToString(", ")}, " +
                "and the CLI rejects that flag instead of answering it",
        )
    }

    /**
     * A verb in [CLI_VERBS] that nothing dispatches advertises a capability that does not
     * exist. Measured 2026-10-18: the hand-written verb list in the rejection hint named 10
     * verbs and omitted `inspect`, which the CLI dispatches.
     *
     * Both dispatch sites are read: [CLI_VERBS] is a description of the CLI, and the CLI is
     * a chain of comparisons in `Main.kt` plus the `CliCommand` `when` in `CliParser`. Reading
     * only one of them would let the row pass on whichever half it did not look at.
     */
    @Test
    fun `every advertised verb is dispatched`() {
        val appDir = File(TestProjectRoot.dir, "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application")
        val main = File(appDir, "Main.kt").readText()
        val parser = File(appDir, "CliParser.kt").readText()
        val dispatched = buildSet {
            Regex("""args\.firstOrNull\(\) == "([a-z-]+)"""").findAll(main).forEach { add(it.groupValues[1]) }
            Regex(""""([a-z-]+)" -> CliCommand\.""").findAll(parser).forEach { add(it.groupValues[1]) }
        }
        // The specific claim FIRST, so a mutation is attributed to it and not to the
        // coverage guard below. Guard order is part of attribution: measured 2026-10-18,
        // adding a phantom verb failed the coverage guard before the claim that names it.
        val undispatched = CLI_VERBS.map { it.first }.filterNot { it in dispatched }
        assertTrue(
            undispatched.isEmpty(),
            "CLI_VERBS advertises ${undispatched.joinToString(", ")}, which nothing dispatches. " +
                "Dispatched: ${dispatched.sorted()}",
        )
        // Then the guard that keeps the claim above from passing on a scan it cannot see.
        assertTrue(
            dispatched.size >= CLI_VERBS.size,
            "read ${dispatched.size} dispatched verbs but CLI_VERBS advertises ${CLI_VERBS.size}; " +
                "a dispatch this row cannot see is a hole in the row, not a clean scan",
        )
    }
}
