package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Unit tests for CLI argument parsing (C-029).
 *
 * Verifies:
 * - C-029.1: no durable selection flag → ReusePriorRun
 * - C-029.2: --resume → ResumePriorRun
 * - C-029.3: --rerun → StartFreshRun
 */
class MainCliParsingTest {

    @Test
    fun `C-029-1 durable run defaults to reuse prior run`() {
        val args = arrayOf("run", "--db", "/tmp/test.db", "/path/to/script.kts")
        val config = parseCliArgs(args)

        assertEquals("run", config?.command)
        assertEquals("/tmp/test.db", config?.dbPath)
        assertEquals(DurableRunPolicy.ReusePriorRun, config?.durableRunPolicy)
        assertEquals("/path/to/script.kts", config?.scriptPath)
    }

    @Test
    fun `C-029-2 resume selects prior run`() {
        val args = arrayOf("run", "--db", "/tmp/test.db", "--resume", "/path/to/script.kts")
        val config = parseCliArgs(args)

        assertEquals("run", config?.command)
        assertEquals("/tmp/test.db", config?.dbPath)
        assertEquals(DurableRunPolicy.ResumePriorRun, config?.durableRunPolicy)
        assertEquals("/path/to/script.kts", config?.scriptPath)
    }

    @Test
    fun `C-029-3 rerun starts a fresh run`() {
        val args = arrayOf("run", "--db", "/tmp/test.db", "--rerun", "/path/to/script.kts")
        val config = parseCliArgs(args)

        assertEquals(DurableRunPolicy.StartFreshRun, config?.durableRunPolicy)
    }

    @Test
    fun `parseCliArgs returns null for invalid command`() {
        val args = arrayOf("invalid", "/path/to/script.kts")
        val config = parseCliArgs(args)

        assertNull(config, "Invalid command should yield null config")
    }

    @Test
    fun `parseCliArgs returns null for missing script path`() {
        val args = arrayOf("run")
        val config = parseCliArgs(args)

        assertNull(config, "Missing script path should yield null config")
    }

    @Test
    fun `parseCliArgs handles validate command without --resume`() {
        val args = arrayOf("validate", "/path/to/script.kts")
        val config = parseCliArgs(args)

        assertEquals("validate", config?.command)
        assertNull(config?.dbPath)
        assertEquals(DurableRunPolicy.ReusePriorRun, config?.durableRunPolicy)
        assertEquals("/path/to/script.kts", config?.scriptPath)
    }

    @Test
    fun `parseCliArgs handles validate command with --resume`() {
        val args = arrayOf("validate", "--resume", "/path/to/script.kts")
        val config = parseCliArgs(args)

        assertEquals("validate", config?.command)
        assertNull(config?.dbPath)
        assertEquals(DurableRunPolicy.ResumePriorRun, config?.durableRunPolicy)
        assertEquals("/path/to/script.kts", config?.scriptPath)
    }
}
