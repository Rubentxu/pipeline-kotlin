package dev.rubentxu.pipeline.v2.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/**
 * The digest of a plugin artifact, and the identity it is supposed to carry.
 *
 * ## The guarantee
 * The digest of an artifact names WHAT the artifact carries, and not WHERE it was found. Two
 * artifacts with the same content have the same digest however they are laid out on the disk,
 * and an artifact measured before it is copied measures the same after it is copied.
 *
 * ## Why this instrument
 * The digest is arithmetic over paths and bytes, so the cheapest instrument that can fail is to
 * lay two trees out on a real filesystem and compare what the product says about them. There is
 * no classloader here and no process: the guarantee under test is about the IDENTITY, and the
 * identity is what the digest is.
 *
 * ## What this does not freeze
 * The exact bytes of the digest. Nothing outside the product reads it, and a change of encoding
 * would be invisible to every consumer of the identity as long as the identity keeps following
 * the content. What these rows freeze is the PROPERTY: same content, same digest; different
 * content, different digest; and no digest at all for an artifact that reaches outside itself.
 */
class PluginArtifactDigestTest {

    @TempDir
    lateinit var root: Path

    @Test
    fun `two identical trees in different places have the same digest`() {
        val first = tree(root.resolve("first"), "a.txt" to "the same bytes")
        val second = tree(root.resolve("second"), "a.txt" to "the same bytes")

        assertEquals(
            digestOf(first),
            digestOf(second),
            "the identity of a plugin depends on where the plugin happens to be written",
        )
    }

    @Test
    fun `two identical trees with the same names in different places have the same digest`() {
        val first = tree(root.resolve("one"), "a.txt" to "alpha", "nested/b.txt" to "beta")
        val second = tree(root.resolve("two"), "a.txt" to "alpha", "nested/b.txt" to "beta")

        assertEquals(
            digestOf(first),
            digestOf(second),
            "the identity of a plugin depends on the directory that holds it",
        )
    }

    @Test
    fun `a tree that differs in one byte has a different digest`() {
        val first = tree(root.resolve("first"), "a.txt" to "the same bytes")
        val second = tree(root.resolve("second"), "a.txt" to "other bytes")

        assertNotEquals(
            digestOf(first),
            digestOf(second),
            "two artifacts with different content are the same artifact",
        )
    }

    @Test
    fun `a tree that differs only in the name of a file has a different digest`() {
        val first = tree(root.resolve("first"), "a.txt" to "the same bytes")
        val second = tree(root.resolve("second"), "b.txt" to "the same bytes")

        assertNotEquals(
            digestOf(first),
            digestOf(second),
            "the name a file has inside the artifact is not part of the artifact's identity",
        )
    }

    @Test
    fun `the digest of a file is the digest of its bytes`() {
        val bytes = "the plugin's bytes".toByteArray(Charsets.UTF_8)
        val artifact = root.resolve("plugin.jar")
        Files.write(artifact, bytes)

        assertEquals(
            "sha256:" + hex(MessageDigest.getInstance("SHA-256").digest(bytes)),
            digestOf(artifact),
            "the digest of an artifact is not the digest of what it carries",
        )
    }

    @Test
    fun `the digest of a file bigger than the reading buffer is the digest of all its bytes`() {
        val bytes = ByteArray(300 * 1024) { (it % 251).toByte() }
        val artifact = root.resolve("big.jar")
        Files.write(artifact, bytes)

        assertEquals(
            "sha256:" + hex(MessageDigest.getInstance("SHA-256").digest(bytes)),
            digestOf(artifact),
            "the digest of an artifact is computed over part of what it carries",
        )
    }

    @Test
    fun `a tree with a symbolic link has no digest`() {
        val artifact = tree(root.resolve("with-link"), "a.txt" to "the plugin's bytes")
        Files.createSymbolicLink(artifact.resolve("elsewhere.txt"), Path.of("/etc/hostname"))

        assertNull(
            digestOf(artifact),
            "an artifact that reaches outside itself was given an identity",
        )
    }

    /** The digest the product measures over an artifact. */
    private fun digestOf(artifact: Path): String? =
        PluginManifestResourceReader.digestOfArtifactAt(artifact)?.value

    /** A directory artifact holding the given files, relative to the directory. */
    private fun tree(into: Path, vararg files: Pair<String, String>): Path {
        Files.createDirectories(into)
        for ((name, content) in files) {
            val file = into.resolve(name)
            Files.createDirectories(file.parent)
            Files.writeString(file, content)
        }
        return into
    }

    /** The digest's bytes as the hex the digest is spelled in. */
    private fun hex(bytes: ByteArray): String = HexFormat.of().formatHex(bytes)
}
