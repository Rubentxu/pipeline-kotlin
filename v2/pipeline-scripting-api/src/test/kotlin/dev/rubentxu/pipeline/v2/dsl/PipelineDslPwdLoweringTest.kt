package dev.rubentxu.pipeline.v2.dsl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Lowering tests for `pwd(tmp: Boolean)` — WU-LPR-402.
 *
 * After WU-LPR-402:
 *  - `pwd(tmp = false)` lowers to the REGISTRY [StepSpec.RegistryStepSpec] with
 *    `stepKey = "core.pwd"` and the canonical `{"kind":"pwd","tmp":false}` envelope.
 *    The legacy `StepSpec.Pwd` was retired at S2-A6/G5 (LEGACY_REMOVED).
 *  - `pwd(tmp = true)` lowers to the REGISTRY [StepSpec.RegistryStepSpec] with
 *    `stepKey = "core.pwd.tmp"` and the canonical empty-object envelope
 *    (PwdTmpInput = data object Unit).
 *  - The synchronous return is the placeholder `<workspace>` (defined on
 *    [StageScope.Companion.RUNTIME_VALUE_PLACEHOLDER]); the real path is
 *    produced at execution time by `core.pwd` / `core.pwd.tmp` through the
 *    scripted runtime context.
 *
 * Honesty matrix (WU-LPR-402 §3):
 *  - **Honest**: the return value is documented as a placeholder. Scripts that
 *    need the real runtime path MUST route through the scripted runtime context
 *    (compiled `.pipeline.kts` generator form, or a `scriptable` block in the
 *    structured DSL form). Reading the synchronous return value as the real
 *    runtime path is a contract violation.
 *  - **Reuse-correctness**: replay never re-observes the workspace; the
 *    persisted `PwdOutput.path` is reproduced (G7-04 evidence,
 *    `S2_A6_CORE_PWD_G7_STOP_BLOCKED_RECEIPT.md`).
 */
@DisplayName("PipelineDsl pwd lowering (WU-LPR-402)")
class PipelineDslPwdLoweringTest {

    @Test
    fun `pwd tmp=false lowers to registry StepSpec with core pwd key`() {
        val scope = StageScope("test")
        scope.pwd(tmp = false)

        val last = scope.steps().last()
        assertTrue(
            last is StepSpec.RegistryStepSpec,
            "pwd(tmp=false) must lower to StepSpec.RegistryStepSpec; got ${last::class.simpleName}",
        )
        val registry = last as StepSpec.RegistryStepSpec
        assertEquals("core.pwd", registry.stepKey.value)
        assertEquals("dsl-v1", registry.schemaVersion)
        // Input envelope is the canonical {"kind":"pwd","tmp":false} form that
        // matches CorePwdStep.inputCodec.
        assertEquals("""{"kind":"pwd","tmp":false}""", registry.encodedInput.value)
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
    fun `pwd default (no arg) lowers to registry StepSpec with core pwd key and tmp=false`() {
        // Jenkins verbatim: `pwd()` ⇒ tmp=false. WU-LPR-402 must NOT change this.
        val scope = StageScope("test")
        scope.pwd()

        val last = scope.steps().last()
        assertTrue(
            last is StepSpec.RegistryStepSpec,
            "pwd() with no arg must lower to StepSpec.RegistryStepSpec (Jenkins verbatim); " +
                "got ${last::class.simpleName}",
        )
        val registry = last as StepSpec.RegistryStepSpec
        assertEquals("core.pwd", registry.stepKey.value)
        assertTrue(registry.encodedInput.value.contains("\"tmp\":false"))
    }

    @Test
    fun `pwd returns the honest placeholder sentinel (RUNTIME_VALUE_PLACEHOLDER)`() {
        // WU-LPR-402 — the DSL fun returns the documented placeholder
        // (StageScope.Companion.RUNTIME_VALUE_PLACEHOLDER). The placeholder is
        // preserved for in-memory scripting hosts (tests, ad-hoc harnesses)
        // that read the return value, but scripts that need the real path
        // MUST route through the scripted runtime context.
        //
        // The placeholder is intentionally a constant string and does NOT
        // depend on runtimeConfig.userDir() — the previous "stub" return was
        // a dishonest "real value from host" that polluted the IR-construction
        // surface.
        val scope = StageScope("test", runtimeConfig = object : dev.rubentxu.pipeline.v2.domain.RuntimeConfig {
            override fun env(name: String): String? = null
            override fun property(name: String): String? = null
            override fun property(name: String, default: String): String = default
            override fun osName(): String = ""
            override fun userDir(): String = "/some/host/path"
        })
        val tmpReturn = scope.pwd(tmp = true)
        val noTmpReturn = scope.pwd(tmp = false)
        assertEquals(StageScope.RUNTIME_VALUE_PLACEHOLDER, tmpReturn)
        assertEquals(StageScope.RUNTIME_VALUE_PLACEHOLDER, noTmpReturn)
        // The placeholder MUST be independent of any host-supplied userDir().
        assertNotEquals("/some/host/path", tmpReturn)
        assertNotEquals("/some/host/path", noTmpReturn)
    }

    @Test
    fun `pwd multiple invocations produce independent registry StepSpecs in step order`() {
        // Ordering preserved: tmp=false → registry core.pwd; tmp=true → registry core.pwd.tmp;
        // tmp=false → registry core.pwd. The StepKey must match the per-invocation `tmp` flag.
        val scope = StageScope("test")
        scope.pwd(tmp = false)
        scope.pwd(tmp = true)
        scope.pwd(tmp = false)

        val steps = scope.steps()
        assertEquals(3, steps.size)
        assertTrue(steps.all { it is StepSpec.RegistryStepSpec })
        assertEquals("core.pwd", (steps[0] as StepSpec.RegistryStepSpec).stepKey.value)
        assertEquals("core.pwd.tmp", (steps[1] as StepSpec.RegistryStepSpec).stepKey.value)
        assertEquals("core.pwd", (steps[2] as StepSpec.RegistryStepSpec).stepKey.value)
    }

    @Test
    fun `isUnix lowers to registry StepSpec with core isUnix key`() {
        // WU-LPR-402 — isUnix is a runtime-returning DSL fun (mirror of pwd).
        // It lowers to core.isUnix, NOT the legacy StepSpec.IsUnix.
        val scope = StageScope("test")
        scope.isUnix()

        val last = scope.steps().last()
        assertTrue(
            last is StepSpec.RegistryStepSpec,
            "isUnix() must lower to StepSpec.RegistryStepSpec; got ${last::class.simpleName}",
        )
        val registry = last as StepSpec.RegistryStepSpec
        assertEquals("core.isUnix", registry.stepKey.value)
        assertEquals("dsl-v1", registry.schemaVersion)
        // Input envelope is the canonical empty-object form that matches
        // CoreIsUnixStep.inputCodec (IsUnixInput = data object Unit).
        assertEquals("{}", registry.encodedInput.value)
    }

    @Test
    fun `isUnix returns the honest placeholder sentinel (ISUNIX_PLACEHOLDER)`() {
        // WU-LPR-402 — the DSL fun returns the documented placeholder
        // (StageScope.Companion.ISUNIX_PLACEHOLDER = true). The placeholder
        // does NOT depend on runtimeConfig.osName(); the previous host-read
        // return was a dishonest "real value" that polluted the IR surface.
        val scope = StageScope("test", runtimeConfig = object : dev.rubentxu.pipeline.v2.domain.RuntimeConfig {
            override fun env(name: String): String? = null
            override fun property(name: String): String? = null
            override fun property(name: String, default: String): String = default
            override fun osName(): String = "Windows 11"
            override fun userDir(): String = ""
        })
        val unix = scope.isUnix()
        assertEquals(StageScope.ISUNIX_PLACEHOLDER, unix)
        // The placeholder MUST be independent of any host-supplied osName().
        // (Windows host says false; placeholder is true; this proves the
        // DSL fun does NOT read host state.)
        assertTrue(unix)
        assertFalse(unix == false)
    }
}
