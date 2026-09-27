package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for the pure CLI argument parser (C-029).
 *
 * Verifies the durable selection contract and fail-closed typed rejection.
 */
class MainCliParsingTest {

    private fun parsed(args: Array<String>): CliFlags {
        val result = CliParser.parse(args)
        assertTrue(result is CliParseResult.Parsed)
        return (result as CliParseResult.Parsed).flags
    }

    @Test
    fun `C-029-1 durable run defaults to reuse prior run`() {
        val config = parsed(arrayOf("run", "--db", "/tmp/test.db", "/path/to/script.kts"))

        assertEquals(CliCommand.RUN, config.command)
        assertEquals("/tmp/test.db", config.dbPath)
        assertEquals(DurableRunPolicy.ReusePriorRun, config.durableRunPolicy)
        assertEquals("/path/to/script.kts", config.scriptPath)
    }

    @Test
    fun `C-029-2 resume selects prior run`() {
        val config = parsed(arrayOf("run", "--db", "/tmp/test.db", "--resume", "/path/to/script.kts"))

        assertEquals(CliCommand.RUN, config.command)
        assertEquals("/tmp/test.db", config.dbPath)
        assertEquals(DurableRunPolicy.ResumePriorRun, config.durableRunPolicy)
        assertEquals("/path/to/script.kts", config.scriptPath)
    }

    @Test
    fun `C-029-3 rerun starts a fresh run`() {
        val config = parsed(arrayOf("run", "--db", "/tmp/test.db", "--rerun", "/path/to/script.kts"))

        assertEquals(DurableRunPolicy.StartFreshRun, config.durableRunPolicy)
    }

    @Test
    fun `invalid command is rejected with a typed error`() {
        val result = CliParser.parse(arrayOf("invalid", "/path/to/script.kts"))

        assertEquals(
            CliParseResult.Rejected(CliError.InvalidCommand("invalid")),
            result,
        )
    }

    @Test
    fun `missing script path is rejected with a typed error`() {
        val result = CliParser.parse(arrayOf("run"))

        assertEquals(CliParseResult.Rejected(CliError.MissingScriptPath), result)
    }

    @Test
    fun `validate command defaults to reuse prior run`() {
        val config = parsed(arrayOf("validate", "/path/to/script.kts"))

        assertEquals(CliCommand.VALIDATE, config.command)
        assertEquals(null, config.dbPath)
        assertEquals(DurableRunPolicy.ReusePriorRun, config.durableRunPolicy)
        assertEquals("/path/to/script.kts", config.scriptPath)
    }

    @Test
    fun `validate command accepts resume flag`() {
        val config = parsed(arrayOf("validate", "--resume", "/path/to/script.kts"))

        assertEquals(CliCommand.VALIDATE, config.command)
        assertEquals(DurableRunPolicy.ResumePriorRun, config.durableRunPolicy)
    }

    @Test
    fun `conflicting durable flags are rejected before execution`() {
        val result = CliParser.parse(arrayOf("run", "--resume", "--rerun", "/path/to/script.kts"))

        assertEquals(CliParseResult.Rejected(CliError.ConflictingDurablePolicies), result)
    }

    @Test
    fun `unsupported sandbox profile is rejected as data`() {
        val result = CliParser.parse(arrayOf("run", "--sandbox-profile", "os", "/path/to/script.kts"))

        assertEquals(CliParseResult.Rejected(CliError.UnsupportedSandboxProfile("os")), result)
    }

    @Test
    fun `unsupported sandbox profile message cites ADR-0016, M5, M9, and the rejected value`() {
        // D-012 fix: CliError.UnsupportedSandboxProfile.toString() must include
        // the cross-references the operator expects to see at the fail-closed
        // boundary (ADR-0016 sandbox policy, M5 OS-level isolation, M9 multi-tenant
        // gate) plus the rejected value verbatim for log correlation.
        val error = CliError.UnsupportedSandboxProfile("os")
        val msg = error.toString()
        assertTrue(msg.contains("ADR-0016"), "must cite ADR-0016: $msg")
        assertTrue(msg.contains("M5"), "must cite M5: $msg")
        assertTrue(msg.contains("M9"), "must cite M9: $msg")
        assertTrue(msg.contains("os"), "must echo the rejected value 'os': $msg")
    }

    @Test
    fun `plugin jars remain ordered and share one flag collection`() {
        val config = parsed(
            arrayOf(
                "run",
                "--plugin-jar", "first.jar",
                "--plugin-jar", "second.jar",
                "/path/to/script.kts",
            ),
        )

        assertEquals(listOf("first.jar", "second.jar"), config.pluginJars)
    }
}
