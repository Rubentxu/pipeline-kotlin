package dev.rubentxu.pipeline.v2.domain.step.artifact

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * The capture-identity clause of an archived file entry.
 *
 * [ArchivedFileEntry] now mirrors the third identity of the receipt: an entry either
 * names WHICH capture produced it or states, by null, that it names none. A blank is
 * neither — it says nothing while wearing the shape of saying something — so the
 * constructor refuses it and the honest cases pass.
 */
class ArchivedFileEntryCaptureIdTest {

    private val digest = "a".repeat(64)

    private fun entry(captureId: String?) = ArchivedFileEntry(
        relPath = "build/app.jar",
        sha256 = digest,
        sizeBytes = 10L,
        absolutePath = "<archive://build/app.jar>",
        captureId = captureId,
    )

    @Test
    fun `an entry names its capture or says so with null, and a blank identity is refused`() {
        assertNull(entry(null).captureId, "absence is stated, not guessed")
        assertEquals("capture-1", entry("capture-1").captureId)
        assertThrows(IllegalArgumentException::class.java) { entry(" ") }
        assertDoesNotThrow { entry(null) }
    }
}
