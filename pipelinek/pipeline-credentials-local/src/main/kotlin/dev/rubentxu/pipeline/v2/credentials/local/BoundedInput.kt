package dev.rubentxu.pipeline.v2.credentials.local

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads the bytes of an imported credential under a ceiling.
 *
 * ## Why the read is bounded
 *
 * The bytes come from outside the product: a file the operator names, or a
 * stream a pipeline writes. A read that asks for all of them is a read whose
 * memory the input decides, and the ceiling here is the store's own: a secret
 * longer than the store accepts can never be stored, so refusing it while it
 * is read is the store's answer, said earlier and without the cost.
 *
 * ## Why the file is measured before it is read
 *
 * The size of a file is a fact the filesystem can answer without reading it,
 * and refusing on it costs nothing. The read that follows is bounded anyway,
 * because a file can grow between the measurement and the read.
 */
internal object BoundedInput {

    /**
     * The bytes of the file at [path], or a refusal when it holds more than
     * [limit] bytes.
     *
     * @param subject what the file carries, in this module's own words.
     */
    fun read(path: Path, limit: Long, subject: String): ByteArray {
        val size = try {
            Files.size(path)
        } catch (e: IOException) {
            throw IllegalArgumentException("$subject could not be read: ${e.message}", e)
        }
        if (size > limit) {
            throw CredentialImportRefusal.InputTooLarge(subject, limit)
        }
        return Files.newInputStream(path).use { read(it, limit, subject) }
    }

    /**
     * The bytes of [source] until it ends, or a refusal when they are more than
     * [limit] bytes.
     *
     * @param subject what the stream carries, in this module's own words.
     */
    fun read(source: InputStream, limit: Long, subject: String): ByteArray {
        val collected = ByteArrayOutputStream()
        val buffer = ByteArray(CHUNK_BYTES)
        var total = 0L
        while (true) {
            val read = source.read(buffer)
            if (read < 0) {
                break
            }
            total += read
            if (total > limit) {
                throw CredentialImportRefusal.InputTooLarge(subject, limit)
            }
            collected.write(buffer, 0, read)
        }
        return collected.toByteArray()
    }

    /** How many bytes a read asks for at a time. */
    private const val CHUNK_BYTES = 8 * 1024
}
