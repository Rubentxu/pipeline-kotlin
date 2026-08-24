package com.pipeline.v2.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Unit tests for CLI argument parsing (C-029).
 *
 * Verifies:
 * - C-029.1: --resume absent → resumeFlag = false
 * - C-029.2: --resume present → resumeFlag = true
 */
class MainCliParsingTest {

    @Test
    fun `C-029-1 resume absent yields resumeFlag false`() {
        val args = arrayOf("run", "--db", "/tmp/test.db", "/path/to/script.kts")
        val config = parseCliArgs(args)

        assertEquals("run", config?.command)
        assertEquals("/tmp/test.db", config?.dbPath)
        assertEquals(false, config?.resumeFlag, "--resume absent should yield resumeFlag=false")
        assertEquals("/path/to/script.kts", config?.scriptPath)
    }

    @Test
    fun `C-029-2 resume present yields resumeFlag true`() {
        val args = arrayOf("run", "--db", "/tmp/test.db", "--resume", "/path/to/script.kts")
        val config = parseCliArgs(args)

        assertEquals("run", config?.command)
        assertEquals("/tmp/test.db", config?.dbPath)
        assertEquals(true, config?.resumeFlag, "--resume present should yield resumeFlag=true")
        assertEquals("/path/to/script.kts", config?.scriptPath)
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
        assertEquals(false, config?.resumeFlag)
        assertEquals("/path/to/script.kts", config?.scriptPath)
    }

    @Test
    fun `parseCliArgs handles validate command with --resume`() {
        val args = arrayOf("validate", "--resume", "/path/to/script.kts")
        val config = parseCliArgs(args)

        assertEquals("validate", config?.command)
        assertNull(config?.dbPath)
        assertEquals(true, config?.resumeFlag, "--resume with validate should yield resumeFlag=true")
        assertEquals("/path/to/script.kts", config?.scriptPath)
    }
}
