package dev.rubentxu.pipeline.v2.dsl

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for the sealed StepSpec hierarchy exhaustiveness.
 *
 * Verifies that the sealed hierarchy contains exactly 28 variants (ML-R9):
 * 7 base steps (Echo, Shell, Sleep, Error, Parallel, WithCredentialsBlock, Checkout)
 * + 5 L7 Jenkins top-steps (WriteFile, ReadFile, FileExists, WithEnv, ArchiveArtifacts)
 * + 17 ML-R9 Jenkins catalog steps (Dir, DeleteDir, CleanWs, CatchError, WarnError,
 *   Unstable, Pwd, IsUnix, Load, WaitUntil, Timestamps, AnsiColor, NodeNoOp,
 *   Milestone, TimeoutBlock, RetryBlock)
 *
 * GREEN: all 28 variants present
 */
@DisplayName("StepSpec sealed hierarchy tests")
class PipelineDslSealedHierarchyTest {

    @Test
    fun `sealed_hierarchy_is_exhaustive_with_28_kinds`() {
        val subclasses = StepSpec::class.sealedSubclasses
        val names = subclasses.map { it.simpleName }
        assertEquals(
            28,
            subclasses.size,
            "StepSpec sealed hierarchy must have exactly 28 variants. " +
                "Found ${subclasses.size}: ${names.joinToString()}"
        )
    }
}
