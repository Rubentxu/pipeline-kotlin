package dev.rubentxu.pipeline.v2.runtime.durable

import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import java.io.ByteArrayOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The sanitizer of a channel over a registry that grows WHILE the channel is open.
 *
 * ## The guarantee
 * A credential that is registered after its channel is open does not leave its value in
 * the clear in the transcript of the producer, and the text that surrounds the value
 * comes out intact.
 *
 * ## The capacity this serves
 * The channel of a step is opened before the child process runs, and the credentials the
 * child prints are registered while it runs: the binding of a credential is resolved
 * during the execution. A sanitizer that captured the seam of the registry when the
 * channel opened masked the values that were known at that moment, and the value of a
 * credential that arrived later reached the transcript in the clear.
 *
 * ## What this row is and what it is not
 * This row measures the CHANNEL: the sanitizer that holds one redactor and one state
 * for the whole life of the channel. The reloading of the seam itself is measured where
 * it lives, next to the redactor that does it.
 */
class ChannelSanitizerSeamReloadTest {

    @Test
    @DisplayName("a value registered after the channel opened does not reach the transcript")
    fun `a value registered after the channel opened does not reach the transcript`() {
        val registry = SecretPatternRegistry()
        val transcript = ByteArrayOutputStream()
        val sanitizer = ChannelSanitizer(registry) { bytes, offset, length ->
            transcript.write(bytes, offset, length)
        }

        // The channel is open, and the child is already writing.
        sanitizer.write("build ok: ".toByteArray(Charsets.UTF_8))

        // The registration that reproduces the defect: the binding of the credential is
        // resolved while the child runs, which is after the channel was opened.
        registry.addSecretValue(VALUE)

        sanitizer.write(VALUE.toByteArray(Charsets.UTF_8))
        sanitizer.write(" and the step finished".toByteArray(Charsets.UTF_8))
        sanitizer.finish()

        val text = transcript.toString(Charsets.UTF_8.name())

        assertFalse(
            text.contains(VALUE),
            "the value of the credential reached the transcript of the producer: the seam of " +
                "the registry was captured when the channel opened, before the credential was " +
                "registered, and the sanitizer never reloaded it",
        )
        assertEquals(
            "build ok: $MARKER and the step finished",
            text,
            "the value must come out as one marker, and the bytes that are not part of it " +
                "must come out in their place",
        )
    }

    private companion object {
        /** The marker a masked value is replaced by. */
        val MARKER = SecretPatternRegistry.SCRUB_MARKER

        /** A value of twenty-four bytes, longer than the threshold in halves. */
        const val VALUE = "s3cret-value-fragment-24"
    }
}
