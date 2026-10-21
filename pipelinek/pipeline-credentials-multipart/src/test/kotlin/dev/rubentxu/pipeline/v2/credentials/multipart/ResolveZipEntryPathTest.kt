package dev.rubentxu.pipeline.v2.credentials.multipart

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Where a `Zip` credential is allowed to be unpacked.
 * This is the only guard between a free-form entry name read out of the secret store and a
 * `Files.write` with attacker-chosen bytes. `Zip.entries` is a `Map<String, ByteArray>`: `..` is
 * not a malformed archive there, it is a legal key, so confinement has to be decided here rather
 * than hoped for.
 * The entry names below are the ones an archive actually carries. The materializer itself is
 * untestable as a unit test -- it creates directories and writes files, and the unit-test rule
 * forbids both -- which is why the decision lives in a pure function rather than inline in
 * `materializeZip`. The consequence is stated plainly: this pins WHERE an entry may land, not
 * that the write happened.
*/
class ResolveZipEntryPathTest {

    private val id = CredentialsId("archive-cred")
    private val tempDir: Path = Paths.get("/tmp/pipeline-zip-abc123-archive-cred")

    // --- the ordinary entries are ordinary ---------------------------------------

    @Test
    fun `a plain entry name resolves inside the directory`() {
        assertEquals(tempDir.resolve("config.json"), resolveZipEntryPath(id, tempDir, "config.json"))
    }

    @Test
    fun `a nested entry name resolves inside the directory`() {
        // Nested paths are the reason `materializeZip` creates parent directories at all, so
        // refusing them would break every archive with a directory in it.
        assertEquals(
            tempDir.resolve("certs/server.pem"),
            resolveZipEntryPath(id, tempDir, "certs/server.pem"),
        )
    }

    @Test
    fun `an inner traversal that comes back is still inside`() {
        // `certs/../config.json` never leaves. Refusing it would be the opposite defect: a guard
        // so tight that legitimate archives stop materializing.
        assertEquals(
            tempDir.resolve("config.json"),
            resolveZipEntryPath(id, tempDir, "certs/../config.json"),
        )
    }

    // --- Zip Slip -----------------------------------------------------------------

    @Test
    fun `an entry that climbs one level is refused`() {
        val refusal = assertThrows(ZipEntryEscapesMaterializationException::class.java) {
            resolveZipEntryPath(id, tempDir, "../escape.txt")
        }

        assertEquals("../escape.txt", refusal.entryName)
        assertEquals(Paths.get("/tmp/escape.txt"), refusal.resolvedPath)
    }

    @Test
    fun `an entry that climbs several levels is refused`() {
        assertThrows(ZipEntryEscapesMaterializationException::class.java) {
            resolveZipEntryPath(id, tempDir, "../../etc/cron.d/pwn")
        }
    }

    @Test
    fun `an absolute entry name is refused`() {
        // `Path.resolve` returns the other path unchanged when the argument is absolute, so an
        // absolute key does not need ".." at all to name any path on the filesystem.
        assertThrows(ZipEntryEscapesMaterializationException::class.java) {
            resolveZipEntryPath(id, tempDir, "/etc/cron.d/pwn")
        }
    }

    @Test
    fun `a sibling whose name begins with the directory is refused`() {
        // The case a character prefix admits. `<tmpdir>-evil` starts with the temp directory's
        // text but is not inside it, and it is the name an attacker picks once they see one
        // prefix check in the source.
        assertThrows(ZipEntryEscapesMaterializationException::class.java) {
            resolveZipEntryPath(id, tempDir, "../pipeline-zip-abc123-archive-cred-evil/x.txt")
        }
    }

    @Test
    fun `an entry that climbs out and back in by a longer route is still refused`() {
        // Normalisation happens before the check, so a name that returns to the directory is
        // judged on where it actually lands. This one lands outside and stays outside.
        assertThrows(ZipEntryEscapesMaterializationException::class.java) {
            resolveZipEntryPath(id, tempDir, "certs/../../elsewhere/secret.txt")
        }
    }

    // --- the refusal names what an operator has to fix ----------------------------

    @Test
    fun `the refusal carries the credential, the key and the destination`() {
        // Three different audiences read this: which credential to rotate, which key to remove
        // from the store, and where the write would have gone. A message naming only the last
        // one leaves the other two to guesswork.
        val refusal = assertThrows(ZipEntryEscapesMaterializationException::class.java) {
            resolveZipEntryPath(id, tempDir, "../../elsewhere/x")
        }

        assertEquals(id, refusal.credentialsId)
        assertEquals("../../elsewhere/x", refusal.entryName)
        assertEquals(Paths.get("/elsewhere/x"), refusal.resolvedPath)
        val text = refusal.message.orEmpty()
        assertEquals(true, text.contains("archive-cred"), "refusal does not name the credential: $text")
        assertEquals(true, text.contains("../../elsewhere/x"), "refusal does not name the entry: $text")
    }
}
