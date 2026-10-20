package dev.rubentxu.pipeline.v2.credentials.api

import java.io.BufferedInputStream
import java.io.FileInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * WU-LPR-011 secret-redaction slice: chunk-boundary-safe transcript reader.
 *
 * Reads a durable console transcript through [StreamingRedactor] so a secret
 * straddling arbitrary read boundaries (the P5 characterization in
 * WU-LPR-040) is still scrubbed before the content reaches the observable
 * event/console plane.
 *
 * Law of channel separation (trunk rule): this redactor is for the
 * OBSERVABLE console/event channel only. The typed value channel
 * (`output.txt` / `capturedStdout`) is deliberately NOT routed through it —
 * a typed value requested by a capture mode must stay exact.
 */
class TranscriptRedactor(
    private val registry: SecretPatternRegistry,
    private val chunkSize: Int = 8192,
) {

    /** Redacts a transcript file, or returns null when the file does not exist. */
    fun redactFile(file: Path): String? {
        if (!Files.exists(file)) return null
        return redactStream(BufferedInputStream(FileInputStream(file.toFile())))
    }

    /** Redacts any stream; the caller owns closing the ORIGINAL stream semantics. */
    fun redactStream(input: InputStream): String {
        val redactor = StreamingRedactor(registry, chunkSize)
        return redactor.wrap(input).use { stream ->
            stream.readBytes().toString(Charsets.UTF_8)
        }
    }
}
