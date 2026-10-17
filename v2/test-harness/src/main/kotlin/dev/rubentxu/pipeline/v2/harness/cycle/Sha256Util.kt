package dev.rubentxu.pipeline.v2.harness.cycle

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * SHA-256 helpers shared by the cycle package.
 *
 * Three call sites used to carry their own copy of these
 * implementations:
 *   - [CycleRunner] (top-level `sha256HexOfString` private)
 *   - [ProbeExecutor] (`sha256HexOfFile` and `sha256Hex`,
 *     both `internal`)
 *   - [PromotionEvidenceVerifier] (`sha256HexOfFile` private,
 *     KDoc explicitly noted the duplication and said "if a
 *     third caller appears, the helper moves to a shared
 *     file")
 *   - [CycleRunnerTest] (test helper, also `sha256HexOfFile`)
 *
 * With the runner, the executor, the verifier, and the test
 * all consuming the same primitive, the duplication
 * invariant broke: this file is the consolidation. All four
 * call sites now share a single implementation; future
 * call sites import the helpers from here.
 *
 * ## Why a [MessageDigest] direct, not a project utility
 *
 * The test-harness is product-agnostic by H0 mandate; the
 * product-side `Sha256` utility lives in another module and
 * depending on it would breach the H0 isolation. The
 * [MessageDigest] direct is the lowest-dependency option
 * that still produces the wire-format the manifest
 * requires (lowercase 64-char hex).
 *
 * ## Why the buffer size is 8192
 *
 * 8192 bytes (8 KiB) is the conventional streaming buffer
 * for [MessageDigest] file operations: it is a multiple of
 * the SHA-256 block size (64 bytes), aligns with most
 * filesystem read-ahead sizes, and avoids the per-call
 * overhead of smaller buffers. All three prior copies used
 * the same value; the shared helper keeps that choice.
 */

private const val BUFFER_SIZE: Int = 8 * 1024

/**
 * SHA-256 of a UTF-8 string, returned as lowercase 64-char
 * hex. The wire format the manifest requires
 * (`content_sha256`) is the same shape.
 */
internal fun sha256Hex(text: String): String {
    val md = MessageDigest.getInstance("SHA-256")
    val bytes = md.digest(text.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}

/**
 * Streaming SHA-256 of a file's bytes, returned as
 * lowercase 64-char hex. The streaming form keeps the
 * helper safe for arbitrary file sizes; the [BUFFER_SIZE]
 * constant pins the read window to the conventional 8 KiB.
 */
internal fun sha256HexOfFile(path: Path): String {
    val md = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            md.update(buffer, 0, read)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}
