package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * EC-3 Regression Test: Inline reconcileRunningOperations has been deleted.
 *
 * Validates that the duplicate inline private `reconcileRunningOperations`
 * at PipelineRun.kt:229-317 (marked TODO[M3-R4.3]) has been removed.
 * The reconciler logic is now exclusively in BranchReconciler.
 *
 * Closes: dup-6, smell-7, smell-8
 */
class InlinedReconcileDeletedTest {

    private val pipelineRunPath = "/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin/v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/PipelineRun.kt"

    @Test
    fun `grep returns zero matches for inline reconcileRunningOperations`() {
        // grep -n "private fun reconcileRunningOperations" PipelineRun.kt should return nothing
        val privateFunResult = Runtime.getRuntime().exec(
            arrayOf("grep", "-n", "private fun reconcileRunningOperations", pipelineRunPath)
        ).waitFor()
        assertEquals(1, privateFunResult, "grep should return exit code 1 (no match) for 'private fun reconcileRunningOperations'")

        // grep -n "TODO[M3-R4.3]" PipelineRun.kt should return nothing
        val todoResult = Runtime.getRuntime().exec(
            arrayOf("grep", "-n", "TODO\\[M3-R4.3\\]", pipelineRunPath)
        ).waitFor()
        assertEquals(1, todoResult, "grep should return exit code 1 (no match) for 'TODO[M3-R4.3]'")

        // grep "reconcileRunningOperations" should still find the BranchReconciler call
        val reconcilerResult = Runtime.getRuntime().exec(
            arrayOf("grep", "-n", "reconcileRunningOperations", pipelineRunPath)
        ).waitFor()
        assertEquals(0, reconcilerResult, "grep should find 'reconcileRunningOperations' call via BranchReconciler")
    }
}
