package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * The captured stdout channel, read under one rule.
 *
 * **The guarantee.** A reader of the captured channel tells apart the things that are not the
 * same thing: nobody recorded anything, the program printed nothing, the program printed
 * something, the channel could not be read, and the channel could not be disposed of. The last
 * two are not values at all, and neither of them is an empty value: an empty value is the answer
 * to "the program printed nothing", and a reader that answers it for a channel it could not read
 * is inventing the output of a program.
 *
 * **The observable capacity it serves.** A `sh` step that returns its stdout publishes the value
 * the program printed. A value that was lost, or a value that was never read, must not reach the
 * consumer as an empty string, because the consumer cannot tell that from a program that printed
 * nothing.
 *
 * **Why this is a unit test.** The channel is a file under the control directory, and the two
 * failures covered here are failures of the FILESYSTEM, not of a process: a file that cannot be
 * read, and a file that cannot be deleted. Both are produced in a temporary directory, without a
 * child, without a clock and without a port.
 */
class CapturedStdoutReadTest {

    @TempDir
    lateinit var temp: Path

    @Test
    fun `a channel nobody wrote is an absence`() {
        val opDir = temp.resolve("op-absent")
        Files.createDirectories(opDir)

        val read = DurableShellExecutor().readOutputText(opDir)

        assertInstanceOf(
            CapturedStdoutRead.Absent::class.java,
            read,
            "a channel that was never written is an absence, and not an empty value",
        )
    }

    @Test
    fun `a channel that holds nothing is an empty value`() {
        // The program printed nothing. That is a fact about the program, and it is not the same
        // fact as a channel nobody wrote.
        val opDir = temp.resolve("op-empty")
        Files.createDirectories(opDir)
        Files.writeString(ShellProtocolLayout(opDir).outputFile, "")

        val read = DurableShellExecutor().readOutputText(opDir)

        val present = assertInstanceOf(
            CapturedStdoutRead.Present::class.java,
            read,
            "a channel that holds nothing is a value, and the value is empty",
        )
        assertEquals("", present.text, "the empty value was not published as empty")
    }

    @Test
    fun `a channel that holds a value publishes it trimmed`() {
        // The trim is the semantics every consumer of a captured stdout already depends on, and
        // it is not changed here.
        val opDir = temp.resolve("op-present")
        Files.createDirectories(opDir)
        Files.writeString(ShellProtocolLayout(opDir).outputFile, "hello\n")

        val read = DurableShellExecutor().readOutputText(opDir)

        val present = assertInstanceOf(
            CapturedStdoutRead.Present::class.java,
            read,
            "a channel that holds a value did not publish it",
        )
        assertEquals("hello", present.text, "the value was not published trimmed")
    }

    @Test
    fun `a channel that cannot be read is not an empty value`() {
        val opDir = temp.resolve("op-unreadable")
        Files.createDirectories(opDir)
        // The channel is a DIRECTORY: reading it fails, and that failure is the fact under test.
        Files.createDirectories(ShellProtocolLayout(opDir).outputFile)

        val read = DurableShellExecutor().readOutputText(opDir)

        val unreadable = assertInstanceOf(
            CapturedStdoutRead.Unreadable::class.java,
            read,
            "a channel that could not be read was published as a value",
        )
        assertTrue(
            unreadable.reason.isNotBlank(),
            "the refusal does not say what the filesystem refused",
        )
    }

    @Test
    fun `a channel that cannot be disposed of keeps the value it held`() {
        val opDir = temp.resolve("op-not-disposed")
        Files.createDirectories(opDir)
        Files.writeString(ShellProtocolLayout(opDir).outputFile, "hello\n")
        // The channel's directory cannot be written, so the channel cannot be deleted.
        Files.setPosixFilePermissions(opDir, PosixFilePermissions.fromString("r-x------"))
        try {
            // The refusal is a property of the FILESYSTEM, not of the code, so it is measured
            // before the row is trusted: a host whose owner may delete anyway cannot produce the
            // refusal, and that is declared instead of pretended.
            val deletable = runCatching {
                Files.deleteIfExists(ShellProtocolLayout(opDir).outputFile)
            }.isSuccess
            assumeTrue(
                !deletable,
                "this host's owner may delete in a directory it cannot write, so the refusal " +
                    "cannot be produced here and the row did not run",
            )

            val read = DurableShellExecutor().readOutputText(opDir)

            val notDisposed = assertInstanceOf(
                CapturedStdoutRead.NotDisposed::class.java,
                read,
                "a channel that could not be disposed of was published as a plain value",
            )
            assertEquals(
                "hello",
                notDisposed.text,
                "the value was read before the disposal was refused, so the value is a fact and " +
                    "the refusal is another one; neither of them is an empty value",
            )
            assertTrue(
                notDisposed.reason.isNotBlank(),
                "the refusal does not say what the filesystem refused",
            )
        } finally {
            Files.setPosixFilePermissions(opDir, PosixFilePermissions.fromString("rwx------"))
        }
    }
}
