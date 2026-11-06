package dev.rubentxu.pipeline.v2.sdk.files

/**
 * The bytes of a file, and how big the file is.
 *
 * ## Why the size travels with the bytes
 *
 * A caller that has to decide whether a file fits under a limit needs TWO
 * facts, and they are read at two different moments: the size, and the bytes
 * that came back. The size is what the rule is about; the bytes are what the
 * caller would publish. Carrying them together lets the caller compare them
 * without asking the filesystem twice, and lets it notice that the file
 * changed between the two.
 *
 * ## What the bytes are NOT
 *
 * They are not necessarily the whole file: the read is bounded, so a file
 * that does not fit comes back short. A caller that publishes them without
 * looking at [sizeBytes] would publish a prefix that looks like a whole file.
 */
data class BoundedBytes(
    /** The bytes that crossed the boundary, at most the number that were asked for. */
    val bytes: ByteArray,
    /** The size the file had when it was measured, before the bytes were read. */
    val sizeBytes: Long,
)
