package dev.rubentxu.pipeline.v2.dsl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Lowering tests for `pwd(tmp: Boolean)` — S2-A6 / G3R.
 *
 * After G3R:
 *  - `pwd(tmp = false)` lowers to the LEGACY [StepSpec.Pwd] (core.pwd is NOT yet
 *    AUTHORITY_FLIP_READY; G4 is the flip slice).
 *  - `pwd(tmp = true)` lowers to the REGISTRY [StepSpec.RegistryStepSpec] with
 *    `stepKey = "core.pwd.tmp"` and the canonical empty-envelope encoded input.
 *    The runtime resolves the StepDefinition through the open registry.
 *
 * Both calls return the synchronous `runtimeConfig.userDir()` placeholder so the
 * in-memory scripting host stays backward-compatible. Returning the real tmp
 * path is out of G3R scope (the PWD_RUNTIME_RETURN_RECONNECTION blocker).
 */
@DisplayName("PipelineDsl pwd lowering (S2-A6 / G3R)")
class PipelineDslPwdLoweringTest {

    @Test
    fun `pwd tmp=false lowers to legacy StepSpec Pwd (core pwd not yet AUTHORITY_FLIP_READY)`() {
        val scope = StageScope("test")
        scope.pwd(tmp = false)

        val last = scope.steps().last()
        assertTrue(
            last is StepSpec.Pwd,
            "pwd(tmp=false) must lower to legacy StepSpec.Pwd; got ${last::class.simpleName}",
        )
        val pwd = last as StepSpec.Pwd
        assertEquals(false, pwd.tmp)
    }

    @Test
    fun `pwd tmp=true lowers to StepSpec RegistryStepSpec with core pwd tmp key`() {
        val scope = StageScope("test")
        scope.pwd(tmp = true)

        val last = scope.steps().last()
        assertTrue(
            last is StepSpec.RegistryStepSpec,
            "pwd(tmp=true) must lower to StepSpec.RegistryStepSpec; got ${last::class.simpleName}",
        )
        val registry = last as StepSpec.RegistryStepSpec
        assertEquals("core.pwd.tmp", registry.stepKey.value)
        assertEquals("dsl-v1", registry.schemaVersion)
        // Input envelope is the canonical empty-object form that matches
        // CorePwdTmpStep.inputCodec (PwdTmpInput = data object Unit).
        assertEquals("{}", registry.encodedInput.value)
    }

    @Test
    fun `pwd default (no arg) lowers to legacy StepSpec Pwd with tmp=false`() {
        // Jenkins verbatim: `pwd()` ⇒ tmp=false. G3R must NOT change this.
        val scope = StageScope("test")
        scope.pwd()

        val last = scope.steps().last()
        assertTrue(
            last is StepSpec.Pwd,
            "pwd() with no arg must lower to legacy StepSpec.Pwd (Jenkins verbatim); " +
                "got ${last::class.simpleName}",
        )
        assertEquals(false, (last as StepSpec.Pwd).tmp)
    }

    @Test
    fun `pwd returns the synchronous userDir placeholder (backward-compatible)`() {
        // The DSL still returns runtimeConfig.userDir() synchronously. G3R preserves
        // this contract; the real tmp path is computed at runtime (canonical OpId).
        // We use a custom RuntimeConfig (public SPI) returning "<workspace>" to
        // assert the exact placeholder; StubRuntimeConfig is `internal` and not
        // directly reachable from this package in the test source set.
        val scope = StageScope("test", runtimeConfig = object : dev.rubentxu.pipeline.v2.domain.RuntimeConfig {
            override fun env(name: String): String? = null
            override fun property(name: String): String? = null
            override fun property(name: String, default: String): String = default
            override fun osName(): String = ""
            override fun userDir(): String = ""
        })
        val tmpReturn = scope.pwd(tmp = true)
        val noTmpReturn = scope.pwd(tmp = false)
        assertEquals("<workspace>", tmpReturn)
        assertEquals("<workspace>", noTmpReturn)
    }

    @Test
    fun `pwd multiple invocations produce independent StepSpecs in step order`() {
        // Ordering preserved: tmp=false → legacy; tmp=true → registry; tmp=false → legacy.
        val scope = StageScope("test")
        scope.pwd(tmp = false)
        scope.pwd(tmp = true)
        scope.pwd(tmp = false)

        val steps = scope.steps()
        assertEquals(3, steps.size)
        assertTrue(steps[0] is StepSpec.Pwd)
        assertTrue(steps[1] is StepSpec.RegistryStepSpec)
        assertTrue(steps[2] is StepSpec.Pwd)
        assertEquals("core.pwd.tmp", (steps[1] as StepSpec.RegistryStepSpec).stepKey.value)
    }
}
