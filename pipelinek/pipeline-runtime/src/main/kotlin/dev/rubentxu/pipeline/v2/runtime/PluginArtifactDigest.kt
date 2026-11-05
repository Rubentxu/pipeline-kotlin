package dev.rubentxu.pipeline.v2.runtime

import dev.rubentxu.pipeline.v2.domain.step.Digest
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.HexFormat

/**
 * The digest of a plugin artifact: an identity that follows the CONTENT and not the ADDRESS.
 *
 * ## What the digest is for
 * The runtime compares artifacts with each other to answer two questions: "are these two
 * candidates the same artifact?" and "are the bytes I am about to load the bytes I measured?".
 * Both questions are about CONTENT. A digest that also depends on WHERE the artifact was read
 * answers a third question nobody asked — "is this the same file at this address?" — and answers
 * it wrongly: the copy the runtime loads lives at another address by design, so a directory
 * artifact measured before the copy and measured again after it can never agree, and the runtime
 * reports a change that did not happen.
 *
 * ## The shape of a directory's digest
 * For a DIRECTORY the digest covers, for every regular file, the file's path RELATIVE to the
 * artifact root — with '/' as the separator, whatever the platform's separator is — and the
 * SHA-256 of the file's bytes. The entries are visited in path order, so the digest does not
 * depend on the order the filesystem happens to hand the files over. The absolute location of
 * the artifact, the permissions of its files and the times they were written are NOT part of it:
 * the identity of a plugin is what it carries, not where it was found.
 *
 * For a single FILE the digest is the SHA-256 of the file's bytes and nothing else. That is the
 * digest the runtime has always produced for a JAR, byte for byte, so a digest an operator
 * pinned in a trust row still names the same artifact.
 *
 * ## Why a symbolic link is refused
 * A link inside an artifact can point anywhere, including outside it. Following it would make the
 * artifact's identity depend on something that is not the artifact, and the identity would stop
 * being the same on the next machine. The policy is therefore explicit and fail-closed: an
 * artifact tree that contains a symbolic link has no digest, and the refusal names the link.
 *
 * ## Why the bytes are read in a stream
 * The digest of a file is computed over a fixed-size buffer, so the memory a measurement needs
 * does not grow with the size of the artifact. An artifact is a thing the operator brought, and
 * its size is not bounded by anything this runtime controls.
 */
internal object PluginArtifactDigest {

    /** How many bytes are read at a time while hashing a file. */
    private const val BUFFER_SIZE: Int = 64 * 1024

    /** What the measurement of an artifact produced. */
    sealed interface Outcome {

        /** The artifact's bytes were read and hashed. */
        data class Measured(val digest: Digest) : Outcome

        /** The artifact's bytes could not be read, so the artifact has no identity. */
        data class Refused(val reason: Refusal) : Outcome
    }

    /** Why an artifact has no digest. */
    sealed interface Refusal {

        /** The sentence the operator reads. */
        fun describe(): String

        /**
         * The artifact tree contains a symbolic link, and a link can point outside the artifact.
         */
        data class SymbolicLink(val path: String) : Refusal {
            override fun describe(): String =
                "the artifact contains a symbolic link at $path, and a link can point outside the " +
                    "artifact, so the artifact carries no content of its own to identify"
        }

        /** The artifact's bytes could not be read. */
        data class Unreadable(val detail: String) : Refusal {
            override fun describe(): String = detail
        }
    }

    /**
     * The digest of the artifact at [root], or the reason it has none.
     *
     * The root itself may be reached through a symbolic link: an operator who names a plugin by
     * a link names the CONTENT behind it, and the content is what is identified. A link INSIDE
     * the artifact is a different matter, and it is refused.
     *
     * @param root the artifact: a file, or a directory holding one.
     */
    fun measure(root: Path): Outcome {
        val artifact = try {
            root.toRealPath()
        } catch (e: IOException) {
            return Outcome.Refused(
                Refusal.Unreadable("the artifact at $root could not be read: ${e.message}"),
            )
        }

        if (Files.isDirectory(artifact)) return measureTree(artifact)

        if (!Files.isRegularFile(artifact)) {
            return Outcome.Refused(
                Refusal.Unreadable("the artifact at $root is neither a file nor a directory"),
            )
        }

        val bytes = digestOfFile(artifact)
            ?: return Outcome.Refused(
                Refusal.Unreadable("the artifact at $root could not be read"),
            )
        return Outcome.Measured(Digest("sha256:" + hex(bytes)))
    }

    /**
     * The digest of a directory artifact: its files' relative paths and their content.
     *
     * @param root the directory, already resolved to its real location.
     */
    private fun measureTree(root: Path): Outcome {
        val entries = mutableListOf<Entry>()
        var refusal: Refusal? = null

        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (Files.isSymbolicLink(dir)) {
                        refusal = Refusal.SymbolicLink(relative(root, dir))
                        return FileVisitResult.TERMINATE
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (Files.isSymbolicLink(file)) {
                        refusal = Refusal.SymbolicLink(relative(root, file))
                        return FileVisitResult.TERMINATE
                    }
                    if (!attrs.isRegularFile) {
                        refusal = Refusal.Unreadable("the artifact holds $file, which is not a regular file")
                        return FileVisitResult.TERMINATE
                    }
                    entries += Entry(relative(root, file), file)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    refusal = Refusal.Unreadable("the artifact's file $file could not be read: ${exc.message}")
                    return FileVisitResult.TERMINATE
                }
            },
        )

        val reason = refusal
        if (reason != null) return Outcome.Refused(reason)

        val tree = MessageDigest.getInstance("SHA-256")
        for (entry in entries.sortedBy { it.relativePath }) {
            val content = digestOfFile(entry.file)
                ?: return Outcome.Refused(
                    Refusal.Unreadable("the artifact's file ${entry.file} could not be read"),
                )
            tree.update(entry.relativePath.toByteArray(Charsets.UTF_8))
            tree.update(0)
            tree.update(content)
        }
        return Outcome.Measured(Digest("sha256:" + hex(tree.digest())))
    }

    /**
     * The SHA-256 of a file's bytes, read through a fixed-size buffer.
     *
     * @return the digest's bytes, or null when the file could not be read.
     */
    private fun digestOfFile(file: Path): ByteArray? {
        val digest = MessageDigest.getInstance("SHA-256")
        return try {
            Files.newInputStream(file).use { input -> absorb(input, digest) }
            digest.digest()
        } catch (e: IOException) {
            null
        }
    }

    /** Feed every byte of [input] into [digest], without holding the file in memory. */
    private fun absorb(input: InputStream, digest: MessageDigest) {
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return
            digest.update(buffer, 0, read)
        }
    }

    /** The path of [file] relative to [root], with '/' as the separator on every platform. */
    private fun relative(root: Path, file: Path): String =
        root.relativize(file).joinToString("/") { it.toString() }

    /** The digest's bytes as the hex the digest is spelled in. */
    private fun hex(bytes: ByteArray): String = HexFormat.of().formatHex(bytes)

    /** One file of a directory artifact: where it is inside the artifact, and where it is. */
    private data class Entry(val relativePath: String, val file: Path)
}
