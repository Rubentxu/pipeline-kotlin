package dev.rubentxu.pipeline.v2.dsl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.reflect.full.primaryConstructor

/**
 * Tests for the sealed StepSpec hierarchy exhaustiveness.
 *
 * Verifies that the sealed hierarchy contains exactly 33 variants (ML-R9 + WU-RP-033 + WU-091 + WU-092):
 * 7 base steps (Echo, Shell, Sleep, Error, Parallel, WithCredentialsBlock, Checkout)
 * + 1 generic RegistryStepSpec (LB-02 / EP-F2)
 * + 1 generic RegistryBlockSpec (WU-RP-033: body-owning open-registry form)
 * + 5 L7 Jenkins top-steps (WriteFile, ReadFile, FileExists, WithEnv, ArchiveArtifacts)
 * + 17 ML-R9 Jenkins catalog steps (Dir, DeleteDir, CleanWs, CatchError, WarnError,
 *   Unstable, Pwd, IsUnix, Load, WaitUntilBlock, Timestamps, AnsiColor, NodeNoOp,
 *   Milestone, TimeoutBlock, RetryBlock)
 * + 1 E1.1 / T7: ArtifactQuery (registry-backed, model form)
 * + 1 WU-091 / RP6-A: Lock (core.lock, body-bearing block step)
 * + 1 WU-092 / RP6-B: Input (core.input, conditionally body-bearing block step)
 *
 * GREEN: all 33 variants present
 */
@DisplayName("StepSpec sealed hierarchy tests")
class PipelineDslSealedHierarchyTest {

    @Test
    fun `sealed_hierarchy_is_exhaustive_with_33_kinds`() {
        val subclasses = StepSpec::class.sealedSubclasses
        val names = subclasses.map { it.simpleName }
        assertEquals(
            33,
            subclasses.size,
            "StepSpec sealed hierarchy must have exactly 33 variants. " +
                "Found ${subclasses.size}: ${names.joinToString()}"
        )
    }

    @Test
    fun `lock variant is body-bearing with jenkins surface`() {
        val lock = StepSpec.Lock::class.primaryConstructor
            ?: error("StepSpec.Lock must be a data class with a primary constructor")
        val params = lock.parameters.map { it.name }
        // The surface mirrors SPEC_WU091_LOCK.md §1: resource mandatory, the
        // three Jenkins options optional, the body last. `timeoutUnit`, `label`,
        // `quantity`, `variable` etc. are deliberately absent (RP-8 / context coupling).
        assertEquals(
            listOf("resource", "timeoutSeconds", "reason", "skipIfLocked", "steps"),
            params,
            "StepSpec.Lock surface drifted from SPEC_WU091_LOCK.md §1; found $params",
        )
    }

    @Test
    fun `input variant carries the Jenkins surface plus a bound`() {
        // The surface pin lives here because this module has kotlin-reflect and
        // pipeline-application does not: a fifth or sixth parameter would be an
        // undeclared addition to the public surface and nothing else would notice.
        val input = StepSpec.Input::class.primaryConstructor
            ?: error("StepSpec.Input must be a data class with a primary constructor")
        assertEquals(
            listOf("message", "ok", "submitter", "id", "timeoutSeconds", "steps"),
            input.parameters.map { it.name },
            "SPEC_WU092_INPUT.md §1 fixes this surface; found ${input.parameters.map { it.name }}",
        )
    }
}
