package dev.rubentxu.pipeline.v2.sdk.utilities.step

import dev.rubentxu.pipeline.v2.domain.PluginStepException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path

/**
 * Where an archive entry is allowed to land.
 *
 * ## The guarantee this test protects
 *
 * An archive is data from outside: its entry names are chosen by whoever built the zip, not
 * by the pipeline. `core-utils.unzip` writes those names into the workspace, so the only
 * question that matters is whether the name can name a place OUTSIDE the destination. A
 * `../` in an entry name is the classic way to answer yes.
 *
 * ## Why this test exists next to the code
 *
 * `core-utils.unzip` creates directories and writes files, and AGENTS.md section 2 forbids
 * both in a unit test. So the decision was lifted into [resolveUnzipEntryPath], a pure
 * function over two paths, and this test asks it directly. What the test does NOT claim is
 * that a write happened: that guarantee belongs to the executable E2E layer under
 * `v2/examples/` (`output/27-zip-unzip` and `security/28-zip-slip-defense`).
 *
 * ## The rows that look like a mistake
 *
 * Two rows below would look wrong to a reader who thinks "a name with `..` is an attack":
 * `release..tar.gz` and `certs/../config.json`. Both land INSIDE the destination, and
 * refusing them would reject legitimate archives. The old rule refused any name CONTAINING
 * two dots, so `release..tar.gz` — a legal file name — took the whole archive down. The
 * rule that decides is containment, not the spelling of the name.
 */
class UnzipEntryPathTest {

    private val destination: Path = Path.of("/tmp/pipelinek-unzip-destination")

    // --- the ordinary entries are ordinary ---------------------------------------

    @Test
    fun `a plain entry name lands inside the destination`() {
        assertEquals(destination.resolve("config.json"), resolveUnzipEntryPath(destination, "config.json"))
    }

    @Test
    fun `a nested entry name lands inside the destination`() {
        assertEquals(
            destination.resolve("certs/server.pem"),
            resolveUnzipEntryPath(destination, "certs/server.pem"),
        )
    }

    @Test
    fun `a name that only LOOKS like an escape is allowed`() {
        // Two dots in a legal file name. The old rule refused the whole archive for this.
        assertEquals(
            destination.resolve("release..tar.gz"),
            resolveUnzipEntryPath(destination, "release..tar.gz"),
        )
    }

    @Test
    fun `a name that climbs out and comes back is still inside`() {
        // It lands inside, so it is allowed. A guard so tight that legitimate archives stop
        // extracting is the opposite defect.
        assertEquals(
            destination.resolve("config.json"),
            resolveUnzipEntryPath(destination, "certs/../config.json"),
        )
    }

    // --- Zip Slip ------------------------------------------------------------------

    @Test
    fun `an entry that climbs one level is refused`() {
        val refusal = assertRefused("../escaped.txt")

        assertTrue(refusal.contains("Zip Slip"), "the refusal must name the rule: $refusal")
        assertTrue(refusal.contains("../escaped.txt"), "the refusal must name the entry: $refusal")
    }

    @Test
    fun `an entry that climbs several levels is refused`() {
        assertRefused("../../etc/cron.d/pwn")
    }

    @Test
    fun `an absolute entry name is refused`() {
        // `Path.resolve` returns the other path unchanged when the argument is absolute, so an
        // absolute name does not need ".." at all to name any path on the filesystem.
        assertRefused("/etc/cron.d/pwn")
    }

    @Test
    fun `a sibling whose name begins with the destination is refused`() {
        // The case a character prefix admits. `<destination>-evil` starts with the
        // destination's text but is not inside it, and it is the name an attacker picks once
        // they see one prefix check in the source.
        assertRefused("../pipelinek-unzip-destination-evil/x.txt")
    }

    @Test
    fun `an entry that climbs out and back in by a longer route is still refused`() {
        // Normalisation happens before the check, so a name is judged on where it lands.
        assertRefused("certs/../../elsewhere/secret.txt")
    }

    @Test
    fun `an entry name with a backslash is refused`() {
        // On POSIX a backslash is a legal character in a file name, but it is the separator
        // Windows uses: a zip written on Windows may carry `dir\file.txt` where the intent
        // was a directory. Refusing it is the documented, fail-closed choice.
        assertRefused("dir\\file.txt")
    }

    @Test
    fun `an entry name the platform cannot turn into a path is refused, not crashed`() {
        // A NUL byte is legal in a zip entry name and illegal in a path. The Step used to
        // throw `InvalidPathException` — an internal error that says nothing about the
        // archive — where a USER failure names the entry.
        val refusal = assertRefused("bad\u0000name.txt")

        assertTrue(refusal.contains("not a usable path"), "the refusal must say why: $refusal")
    }

    private fun assertRefused(entryName: String): String {
        val thrown = assertThrows<PluginStepException> {
            resolveUnzipEntryPath(destination, entryName)
        }
        val message = thrown.failure.message
        assertTrue(message.isNotBlank(), "the refusal must say something")
        return message
    }
}
