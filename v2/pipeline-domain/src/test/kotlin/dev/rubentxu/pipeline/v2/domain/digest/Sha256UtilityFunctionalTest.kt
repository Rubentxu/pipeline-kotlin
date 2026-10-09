package dev.rubentxu.pipeline.v2.domain.digest

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * B0: the single digest utility, exercised as a FUNCTION over real files.
 *
 * ## Why this test crosses the production authority
 *
 * [Sha256] is the utility B0 introduces so that the 29 `MessageDigest.getInstance("SHA-256")`
 * call sites in this repository stop being 29 independent encodings of one decision. This test
 * enters through that production object, not through a local reimplementation: it computes expected
 * values with the JDK (`MessageDigest` plus `String.format`) and compares, so a divergence between
 * the utility and the JDK oracle is a RED rather than a tautology.
 *
 * It does NOT certify the callers. It certifies the primitive. Whether each of the 29 sites
 * migrated is a separate question answered by the migration receipt.
 *
 * ## The four properties B0 names
 *
 * ```text
 * P1 two paths      same bytes under two paths -> one digest (path is not content)
 * P2 spaces         a path containing spaces hashes identically and does not split
 * P3 one byte       a single changed byte changes the digest
 * P4 exact metadata exclusion
 *                   excluded names are skipped; near-miss names are NOT skipped
 * ```
 *
 * P4 is the one with teeth, and it is why this is a functional test rather than a table of
 * expected constants. "Excludes generated metadata exactly" has a failure mode that a
 * constant table cannot see: an exclusion written as `endsWith("index")` or `contains("meta")`
 * silently swallows a real source file. The negative rows below pin that the matcher is exact,
 * and the mutation that kills this test is widening the predicate, not changing a hash.
 */
class Sha256UtilityFunctionalTest {

    @Test
    fun `P1 two paths holding identical bytes produce one digest`(@TempDir tmp: Path) {
        val left = tmp.resolve("left")
        Files.createDirectories(left)
        val right = tmp.resolve("deeply").resolve("nested").resolve("right")
        // createDirectories on the PARENT is not enough: `right` is the directory the file
        // goes into, and Files.write does not create it. The first version of this test
        // failed with NoSuchFileException for exactly that reason.
        Files.createDirectories(right)

        val leftFile = left.resolve("payload.txt")
        val rightFile = right.resolve("payload.txt")
        Files.write(leftFile, "same bytes, different location\n".toByteArray(StandardCharsets.UTF_8))
        Files.write(rightFile, "same bytes, different location\n".toByteArray(StandardCharsets.UTF_8))

        val leftDigest = Sha256.ofFile(leftFile)
        val rightDigest = Sha256.ofFile(rightFile)

        assertEquals(leftDigest, rightDigest, "digest must be over CONTENT; the path is not part of it")
        assertEquals(Sha256.ofText("same bytes, different location\n"), leftDigest)
    }

    @Test
    fun `P1b two files that differ only by path still hash equal when contents are equal`(@TempDir tmp: Path) {
        val a = tmp.resolve("a.txt")
        val b = tmp.resolve("b.txt")
        Files.write(a, byteArrayOf(1, 2, 3))
        Files.write(b, byteArrayOf(1, 2, 3))

        assertEquals(Sha256.ofFile(a), Sha256.ofFile(b))
    }

    @Test
    fun `P2 a path containing spaces hashes the file it names`(@TempDir tmp: Path) {
        val dirName = "my build dir"
        val spaced = tmp.resolve(dirName).also { Files.createDirectories(it) }
        val spacedFile = spaced.resolve("report file.txt")
        val expectedBytes = "content behind spaces\n".toByteArray(StandardCharsets.UTF_8)
        Files.write(spacedFile, expectedBytes)

        // A sibling in a space-free directory is the control: if the implementation were
        // splitting or trimming anywhere, these two would diverge.
        val plain = tmp.resolve("plain").also { Files.createDirectories(it) }
        val plainFile = plain.resolve("report file.txt")
        Files.write(plainFile, expectedBytes)

        assertEquals(
            Sha256.ofText("content behind spaces\n"),
            Sha256.ofFile(spacedFile),
            "a space in the path must not alter the digest of the bytes it names",
        )
        assertEquals(Sha256.ofFile(plainFile), Sha256.ofFile(spacedFile))
    }

    @Test
    fun `P3 a single changed byte changes the digest`(@TempDir tmp: Path) {
        val original = tmp.resolve("original.bin")
        val mutated = tmp.resolve("mutated.bin")
        // 4096 bytes, one differing bit, to defeat any buffer-boundary shortcut.
        val base = ByteArray(4096) { (it % 251).toByte() }
        Files.write(original, base)

        val changed = base.copyOf()
        changed[2048] = (changed[2048].toInt() xor 0x01).toByte()
        Files.write(mutated, changed)

        assertNotEquals(
            Sha256.ofFile(original),
            Sha256.ofFile(mutated),
            "one flipped bit in the middle must change the digest",
        )
    }

    @Test
    fun `P4 excluded names are skipped`(@TempDir tmp: Path) {
        val source = tmp.resolve("source.txt")
        val generated = tmp.resolve("index.json")
        Files.write(source, "payload\n".toByteArray(StandardCharsets.UTF_8))
        Files.write(generated, "{\"generated\":true}".toByteArray(StandardCharsets.UTF_8))

        val excluded = setOf("index.json")
        val digest = Sha256.ofDirectory(tmp, excluded)

        // The directory digest is framed as `relativePath \u0000 <file digest> \u0000`
        // per file, not as the concatenated content. Restating the framing here rather
        // than calling a production helper keeps the assertion an independent statement
        // of the contract instead of a restatement of the implementation.
        val expected = Sha256.ofText(
            buildString {
                append("source.txt").append('\u0000')
                append(Sha256.ofText("payload\n")).append('\u0000')
            },
        )
        assertEquals(
            expected,
            digest,
            "with the generated metadata excluded, only source.txt contributes",
        )
    }

    @Test
    fun `P4b exclusion is exact - a near-miss name is NOT excluded`(@TempDir tmp: Path) {
        val keep = tmp.resolve("index.json.bak")
        val drop = tmp.resolve("index.json")
        Files.write(keep, "KEEP".toByteArray(StandardCharsets.UTF_8))
        Files.write(drop, "DROP".toByteArray(StandardCharsets.UTF_8))

        val excluded = setOf("index.json")
        val digest = Sha256.ofDirectory(tmp, excluded)

        // Only index.json.bak contributes. If the matcher were a suffix, prefix or
        // substring test, index.json would also be dropped and this would still be the
        // digest of the one kept file — which is why the P4 row above, where dropping a
        // file IS the expected outcome, is the pair that pins the matcher. Together:
        // the exclusion must skip exactly the named file AND leave its near-misses in.
        val expected = Sha256.ofText(
            buildString {
                append("index.json.bak").append('\u0000')
                append(Sha256.ofText("KEEP")).append('\u0000')
            },
        )
        assertEquals(
            expected,
            digest,
            "the exclusion must be an exact name match, so index.json.bak survives",
        )
    }

    @Test
    fun `P4c an empty exclusion set includes every regular file`(@TempDir tmp: Path) {
        Files.write(tmp.resolve("one.txt"), "ONE".toByteArray(StandardCharsets.UTF_8))
        Files.write(tmp.resolve("two.txt"), "TWO".toByteArray(StandardCharsets.UTF_8))

        // No exclusions, two files: the digest must be the digest of BOTH in path order.
        // Recorded here as the explicit contract, because the alternative reading (last file
        // wins) would satisfy a weaker test.
        val expected = MessageDigest.getInstance("SHA-256")
            .digest(
                buildString {
                    append("one.txt").append('\u0000').append(Sha256.ofText("ONE")).append('\u0000')
                    append("two.txt").append('\u0000').append(Sha256.ofText("TWO")).append('\u0000')
                }.toByteArray(StandardCharsets.UTF_8),
            )
            .joinToString("") { "%02x".format(it) }

        assertEquals(expected, Sha256.ofDirectory(tmp, emptySet()))
    }

    @Test
    fun `hex form is lowercase, 64 chars, and matches the JDK oracle`(@TempDir tmp: Path) {
        val file = tmp.resolve("x.txt")
        Files.write(file, byteArrayOf(0, 1, 2, 3, 127, -128, -1))

        val digest = Sha256.ofFile(file)
        assertEquals(64, digest.length, "SHA-256 hex is 64 characters")
        assertTrue(digest.all { it in '0'..'9' || it in 'a'..'f' }, "hex must be lowercase, got $digest")

        val oracle = MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(file))
            .joinToString("") { "%02x".format(it) }
        assertEquals(oracle, digest)
    }
}