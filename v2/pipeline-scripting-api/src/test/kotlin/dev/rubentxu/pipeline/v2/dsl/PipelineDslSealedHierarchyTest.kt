package dev.rubentxu.pipeline.v2.dsl

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for the sealed StepSpec hierarchy exhaustiveness.
 *
 * Verifies that the sealed hierarchy contains exactly 12 variants:
 * 7 existing (Echo, Shell, Sleep, Error, Parallel, WithCredentialsBlock, Checkout)
 * + 5 new L7 Jenkins top-steps (WriteFile, ReadFile, FileExists, WithEnv, ArchiveArtifacts)
 *
 * RED: assertion mismatch (currently 7, expected 12)
 * GREEN: all 12 variants present
 */
@DisplayName("StepSpec sealed hierarchy tests")
class PipelineDslSealedHierarchyTest {

    @Test
    fun `sealed_hierarchy_is_exhaustive_with_12_kinds`() {
        val subclasses = StepSpec::class.sealedSubclasses
        val names = subclasses.map { it.simpleName }
        assertEquals(
            12,
            subclasses.size,
            "StepSpec sealed hierarchy must have exactly 12 variants. " +
                "Found ${subclasses.size}: ${names.joinToString()}"
        )
    }
}
