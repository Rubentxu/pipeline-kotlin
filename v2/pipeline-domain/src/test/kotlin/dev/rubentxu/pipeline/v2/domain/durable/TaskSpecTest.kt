package dev.rubentxu.pipeline.v2.domain.durable

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TaskSpecTest {

    @Test
    fun `ExecTask preserves argv exactly — spaces and quotes untouched`() {
        val argv = listOf("/usr/bin/printf", "%s %s\n", "hello world", "with \"quotes\" and 'apostrophes'")
        val task = TaskSpec.ExecTask(argv)

        assertEquals(argv, task.argv, "argv must be preserved verbatim (no shell re-tokenisation)")
    }

    @Test
    fun `empty argv is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { TaskSpec.ExecTask(emptyList()) }
    }

    @Test
    fun `empty argv elements are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { TaskSpec.ExecTask(listOf("git", "")) }
    }

    @Test
    fun `ShellScriptTask rejects blank scripts`() {
        assertThrows(IllegalArgumentException::class.java) {
            TaskSpec.ShellScriptTask(script = "   ")
        }
    }

    @Test
    fun `ShellScriptTask defaults to POSIX_SH`() {
        val task = TaskSpec.ShellScriptTask(script = "echo hi")

        assertEquals(InterpreterPolicy.POSIX_SH, task.interpreter)
        assertEquals("/bin/sh", task.interpreter.binary)
    }

    @Test
    fun `interpreter binaries are a closed safe set`() {
        // The enum must not allow arbitrary binary injection: binaries are
        // fixed per entry and both are absolute standard shells.
        InterpreterPolicy.entries.forEach { policy ->
            assertTrue(policy.binary.startsWith("/bin/"), "${policy.name} binary must be an absolute /bin path")
        }
        assertEquals(setOf("POSIX_SH", "BASH"), InterpreterPolicy.entries.map { it.name }.toSet())
    }

    @Test
    fun `both variants are TaskSpec`() {
        assertTrue(TaskSpec.ShellScriptTask("echo") is TaskSpec)
        assertTrue(TaskSpec.ExecTask(listOf("true")) is TaskSpec)
    }
}
