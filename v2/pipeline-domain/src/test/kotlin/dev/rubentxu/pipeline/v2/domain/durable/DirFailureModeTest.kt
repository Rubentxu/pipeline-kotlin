package dev.rubentxu.pipeline.v2.domain.durable

import dev.rubentxu.pipeline.v2.domain.FailureKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * WU-RP-053-DIR-FAILURE-MODE: unit tests for the typed [DirFailureMode] ADT.
 *
 * The ADT mirrors Jenkins `dir(...)` semantics:
 *   - [Contained] (default): a StepFailed inside the block is contained; the
 *     cwd is restored and the pipeline proceeds with the next sibling.
 *   - [AbortStage]: a StepFailed inside the block aborts the stage (legacy
 *     opt-in, NOT the Jenkins default; preserved so an existing user can
 *     recover the legacy behaviour explicitly).
 *
 * Sealed hierarchy (exactly two variants).
 */
class DirFailureModeTest {

    @Test
    fun `default is Contained to match Jenkins dir semantics`() {
        // The Jenkins reference: "the working directory is restored when the
        // block exits, even on exception". PipelineK's typed ADT makes this
        // invariant explicit and the default behaviour preserves parity.
        assertEquals(DirFailureMode.Contained, DirFailureMode.default())
    }

    @Test
    fun `Contained is a singleton data object`() {
        val a = DirFailureMode.Contained
        val b = DirFailureMode.Contained
        assertEquals(a, b)
        assertEquals(a.name, "Contained")
    }

    @Test
    fun `AbortStage is a singleton data object distinct from Contained`() {
        val a = DirFailureMode.AbortStage
        val b = DirFailureMode.AbortStage
        assertEquals(a, b)
        assertEquals("AbortStage", a.name)
        assertNotEquals(DirFailureMode.Contained, a)
    }

    @Test
    fun `sealed hierarchy exposes exactly two variants`() {
        val sealedSubclasses = DirFailureMode::class.sealedSubclasses
        assertEquals(
            2,
            sealedSubclasses.size,
            "DirFailureMode must remain a closed 2-variant ADT " +
                "(Contained + AbortStage). Found: ${sealedSubclasses.map { it.simpleName }}",
        )
    }

    @Test
    fun `isAborting is false for Contained and true for AbortStage`() {
        assertEquals(false, DirFailureMode.Contained.isAborting)
        assertEquals(true, DirFailureMode.AbortStage.isAborting)
    }
}
