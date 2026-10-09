package dev.rubentxu.pipeline.v2.domain.digest

import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * The one SHA-256 utility in this repository (B0).
 *
 * ## Why this exists
 *
 * An inventory at `6e8e86bd` found 29 `MessageDigest.getInstance("SHA-256")` call sites across
 * seven modules and four separately written `sha256Hex` helpers, with no shared utility. Each
 * site re-decided two things that must agree:
 *
 * ```text
 * hex encoding   lowercase "%02x" vs something else
 * byte source    UTF-8 by default, or platform default
 * ```
 *
 * A divergence in either is not visible at the call site. `Fingerprint` hashing a payload as
 * UTF-8 while an artifact handle hashes the same bytes as platform default produces two digests
 * for one artifact on a non-UTF-8 default, and the mismatch surfaces as a replay divergence
 * far from its cause. The duplication is therefore a correctness risk, not only duplication.
 *
 * ## What it guarantees
 *
 * ```text
 * hex      lowercase, 64 characters, no locale surprises
 * text     UTF-8, always — never the platform default charset
 * file     streamed, so a large artifact does not have to be resident
 * ```
 *
 * The streaming form is what [ofFile] uses and what the file-digest call sites should use; it
 * is byte-for-byte identical to hashing the file's contents read whole.
 *
 * ## Scope, and what it deliberately does NOT do
 *
 * This is a primitive, not a policy. It does not decide *what* is hashed, in *which* order, or
 * which files participate — those are decisions of the caller and belong to that caller's
 * contract. [ofDirectory] is the one bundled composite, because "a directory's identity with
 * generated metadata excluded" is a decision this repository has already had to make twice
 * (`archiveArtifacts`, `stash`), and having it once, with an EXACT exclusion, is cheaper than
 * having it twice with a subtly different matcher.
 *
 * Callers whose digest is already durable (every fingerprint written by a shipped candidate)
 * MUST NOT change their input bytes to "improve" them. Migrating a call site means routing the
 * same bytes through this object, not changing what is hashed.
 */
object Sha256 {

    /** Buffer size for streaming. 8192 is the JDK default and matches the previous sites. */
    private const val STREAM_BUFFER = 8192

    /**
     * Lowercase hex of the SHA-256 of [bytes].
     *
     * `%02x` on a signed `Byte` yields the two-digit form in Kotlin because the format masks
     * to 8 bits; the value is therefore stable for negative bytes (`-1` -> `ff`), which the
     * tests pin against the JDK oracle.
     */
    fun ofBytes(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /**
     * Lowercase hex of the SHA-256 of [text], encoded as UTF-8.
     *
     * UTF-8 is explicit and never `Charset.defaultCharset()`. Every previous site that hashed a
     * string used `toByteArray(Charsets.UTF_8)`; making it the only option removes the class of
     * bug where a new site silently picks the platform default and disagrees with every
     * existing durable fingerprint on the same input.
     */
    fun ofText(text: String): String = ofBytes(text.toByteArray(StandardCharsets.UTF_8))

    /**
     * Lowercase hex of the SHA-256 of the file's CONTENTS, streamed.
     *
     * The path is not an input. Two files with equal bytes at different locations hash equal,
     * which is what an artifact-identity caller means and what a call site that hashed the path
     * would silently get wrong.
     */
    fun ofFile(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { stream -> stream.update(digest) }
        return hex(digest.digest())
    }

    /**
     * Lowercase hex of the SHA-256 of [text], matching [ofBytes] for the same content.
     *
     * Retained as a named alternative rather than removed: `ofText` is the spelling that says
     * "this was a string", and having both is one line. If a caller is migrating, prefer
     * [ofText].
     */
    fun ofString(value: String): String = ofText(value)

    /**
     * A stable digest of a directory's regular files, in path order, with generated metadata
     * excluded by EXACT file name.
     *
     * The framing is explicit and unambiguous: each contributing file contributes
     * `relativePath \u0000 content`, and the sequence is terminated.
     *
     * The separator is NUL rather than a space, and that choice is load-bearing: a SPACE is a
     * legal character in a file name, so a space-separated framing makes the directory holding
     * `("a b.txt", "c.txt")` and the one holding `("a.txt", "b c.txt")` produce the same
     * pre-image and therefore the same digest. A collision reachable by renaming a file is
     * exactly what an identity function must not have.
     *
     * Excluded names match the file name EXACTLY (`index.json` excludes `index.json` and does
     * not exclude `index.json.bak`, `myindex.json` or `index`). A prefix, suffix or substring
     * match is the specific defect this method exists to prevent, so it is spelled as an
     * equality test on the file name and nothing else.
     *
     * Empty exclusions and empty directories are defined rather than left to a null: an empty
     * directory is the digest of an empty byte sequence, which is a real value.
     */
    fun ofDirectory(root: Path, excludedFileNames: Set<String> = emptySet()): String {
        // Files.walk returns a Stream whose walk directory handles must be closed. `use` does
        // not apply: kotlin.io.use is declared for Closeable, and a Stream is not one, so the
        // directory handles would leak for the lifetime of the test JVM. Closed explicitly.
        val stream = Files.walk(root)
        val canonical: List<Pair<String, Path>> = try {
            // Ordered by RELATIVE PATH, with an explicit comparator.
            //
            // `.sorted()` with no argument is wrong here and was caught by the
            // functional test, not by review: kotlin.Pair does not implement Comparable,
            // so the natural-order comparator throws ClassCastException on the second
            // file. Any directory holding more than one file would have failed at
            // runtime. Ordering by relative path is also the contract: the digest must
            // not depend on the order the filesystem happens to return.
            stream.filter { Files.isRegularFile(it) }
                .filter { it.fileName?.toString() !in excludedFileNames }
                .map { file -> root.relativize(file).toString() to file }
                .sorted(compareBy<Pair<String, Path>> { it.first })
                .toList()
        } finally {
            stream.close()
        }
        val preimage = buildString {
            canonical.forEach { (relativePath, file) ->
                append(relativePath).append('\u0000').append(Sha256.ofFile(file)).append('\u0000')
            }
        }
        return ofBytes(preimage.toByteArray(StandardCharsets.UTF_8))
    }

    /**
     * Feed a stream into [digest] and return the stream.
     *
     * Takes the digest rather than creating one so a caller hashing several sources into one
     * digest (a manifest of parts, say) shares the framing and the hex form without copying
     * them.
     */
    private fun InputStream.update(digest: MessageDigest): InputStream {
        val buffer = ByteArray(STREAM_BUFFER)
        while (true) {
            val read = read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
        return this
    }

    /** Lowercase hex, two characters per byte, no locale-dependent formatting. */
    private fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        bytes.forEach { byte -> out.append(HEX[(byte.toInt() shr 4) and 0x0F]).append(HEX[byte.toInt() and 0x0F]) }
        return out.toString()
    }

    /** Lookup table, so hex encoding cannot depend on a default locale or on `%02x`. */
    private const val HEX = "0123456789abcdef"
}