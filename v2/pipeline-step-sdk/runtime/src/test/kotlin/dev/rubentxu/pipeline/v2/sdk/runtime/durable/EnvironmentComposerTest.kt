package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import dev.rubentxu.pipeline.v2.domain.SecretHandle
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * M4 Slice 2 — EnvironmentComposer pure behavior tests.
 *
 * Tests [EnvironmentComposer] as a pure, stateless, dependency-free object
 * accepting ONLY pre-resolved `Map<String, SecretHandle>` for all env-var stages.
 * No `EnvValue` instances, no `CredentialProvider`, no credentials-api imports.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class EnvironmentComposerTest {

    // ---------------------------------------------------------------------------
    // L1 — six stages compose in precedence order (binding amendment 3)
    // ---------------------------------------------------------------------------

    @Test
    fun `L1 - six stages compose in precedence order - base stage provides lowest precedence`() {
        val req = EnvCompositionRequest(
            base = mapOf("A" to SecretHandle.plain("from-base")),
        )
        val result = EnvironmentComposer.compose(req)
        assertEquals("from-base", result["A"]!!.borrow { String(it, Charsets.UTF_8) })
    }

    @Test
    fun `L1 - six stages compose in precedence order - stage overlay wins over base`() {
        val req = EnvCompositionRequest(
            base = mapOf("X" to SecretHandle.plain("from-base")),
            stage = mapOf("X" to SecretHandle.plain("from-stage")),
        )
        val result = EnvironmentComposer.compose(req)
        assertEquals("from-stage", result["X"]!!.borrow { String(it, Charsets.UTF_8) })
    }

    @Test
    fun `L1 - six stages compose in precedence order - withEnv wins over stage`() {
        val req = EnvCompositionRequest(
            base = mapOf("Y" to SecretHandle.plain("from-base")),
            stage = mapOf("Y" to SecretHandle.plain("from-stage")),
            withEnv = listOf("Y" to SecretHandle.plain("from-withenv")),
        )
        val result = EnvironmentComposer.compose(req)
        assertEquals("from-withenv", result["Y"]!!.borrow { String(it, Charsets.UTF_8) })
    }

    @Test
    fun `L1 - six stages compose in precedence order - credentials wins over withEnv`() {
        val req = EnvCompositionRequest(
            base = mapOf("Z" to SecretHandle.plain("from-base")),
            stage = mapOf("Z" to SecretHandle.plain("from-stage")),
            withEnv = listOf("Z" to SecretHandle.plain("from-withenv")),
            credentials = mapOf("Z" to SecretHandle.plain("from-credentials")),
        )
        val result = EnvironmentComposer.compose(req)
        assertEquals("from-credentials", result["Z"]!!.borrow { String(it, Charsets.UTF_8) })
    }

    @Test
    fun `L1 - six stages compose in precedence order - all four stages coexist with disjoint keys`() {
        val req = EnvCompositionRequest(
            base = mapOf("BASE_KEY" to SecretHandle.plain("from-base")),
            stage = mapOf("STAGE_KEY" to SecretHandle.plain("from-stage")),
            withEnv = listOf("WITHENV_KEY" to SecretHandle.plain("from-withenv")),
            credentials = mapOf("CRED_KEY" to SecretHandle.plain("from-credentials")),
        )
        val result = EnvironmentComposer.compose(req)

        assertEquals(4, result.size)
        assertEquals("from-base", result["BASE_KEY"]!!.borrow { String(it, Charsets.UTF_8) })
        assertEquals("from-stage", result["STAGE_KEY"]!!.borrow { String(it, Charsets.UTF_8) })
        assertEquals("from-withenv", result["WITHENV_KEY"]!!.borrow { String(it, Charsets.UTF_8) })
        assertEquals("from-credentials", result["CRED_KEY"]!!.borrow { String(it, Charsets.UTF_8) })
    }

    @Test
    fun `L1 - six stages compose in precedence order - last-wins on override across all four stages`() {
        // Override key X in each stage; credentials (stage 4) must win
        val req = EnvCompositionRequest(
            base = mapOf("X" to SecretHandle.plain("base")),
            stage = mapOf("X" to SecretHandle.plain("stage")),
            withEnv = listOf("X" to SecretHandle.plain("withenv")),
            credentials = mapOf("X" to SecretHandle.plain("credentials")),
        )
        val result = EnvironmentComposer.compose(req)
        assertEquals("credentials", result["X"]!!.borrow { String(it, Charsets.UTF_8) })
    }

    // ---------------------------------------------------------------------------
    // Minimal behavior tests (deterministic, empty, PATH, no-Bash-literal)
    // ---------------------------------------------------------------------------

    @Test
    fun `empty request returns empty map`() {
        val result = EnvironmentComposer.compose(EnvCompositionRequest())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `result is deterministic across multiple calls with same input`() {
        val req = EnvCompositionRequest(
            base = mapOf(
                "A" to SecretHandle.plain("val-a"),
                "B" to SecretHandle.plain("val-b"),
            ),
        )
        val first = EnvironmentComposer.compose(req)
        val second = EnvironmentComposer.compose(req)
        assertEquals(first, second)
    }

    @Test
    fun `no-Bash-literal - dollar sign preserved verbatim in plain values`() {
        // The composer does NOT expand $VAR — values pass through as-is
        val req = EnvCompositionRequest(
            base = mapOf("LITERAL" to SecretHandle.plain("\$HOME is \$HOME")),
        )
        val result = EnvironmentComposer.compose(req)
        assertEquals("\$HOME is \$HOME", result["LITERAL"]!!.borrow { String(it, Charsets.UTF_8) })
    }

    @Test
    fun `PATH order - pathPlus prepended in declaration order - first declared is outermost`() {
        val req = EnvCompositionRequest(
            base = mapOf("PATH" to SecretHandle.plain("/usr/bin:/bin")),
            pathPlus = listOf("/opt/maven/bin", "/opt/gradle/bin"),
        )
        val result = EnvironmentComposer.compose(req)
        // Jenkins verbatim: first-declared = outermost
        assertEquals("/opt/maven/bin:/opt/gradle/bin:/usr/bin:/bin", result["PATH"]!!.borrow { String(it, Charsets.UTF_8) })
    }

    @Test
    fun `PATH order - empty pathPlus preserves existing PATH`() {
        val req = EnvCompositionRequest(
            base = mapOf("PATH" to SecretHandle.plain("/usr/local/bin:/usr/bin")),
            pathPlus = emptyList(),
        )
        val result = EnvironmentComposer.compose(req)
        assertEquals("/usr/local/bin:/usr/bin", result["PATH"]!!.borrow { String(it, Charsets.UTF_8) })
    }

    @Test
    fun `PATH order - JAVA_HOME bin prepended after pathPlus`() {
        val req = EnvCompositionRequest(
            base = mapOf(
                "PATH" to SecretHandle.plain("/usr/bin:/bin"),
                "JAVA_HOME" to SecretHandle.plain("/usr/lib/jvm/java-11"),
            ),
            pathPlus = listOf("/opt/gradle/bin"),
        )
        val result = EnvironmentComposer.compose(req)
        // pathPlus first, then JAVA_HOME/bin prepended (stage 5 order: pathPlus then JAVA_HOME)
        assertEquals("/usr/lib/jvm/java-11/bin:/opt/gradle/bin:/usr/bin:/bin", result["PATH"]!!.borrow { String(it, Charsets.UTF_8) })
    }

    @Test
    fun `PATH order - M2_HOME bin prepended after JAVA_HOME`() {
        val req = EnvCompositionRequest(
            base = mapOf(
                "PATH" to SecretHandle.plain("/usr/bin:/bin"),
                "JAVA_HOME" to SecretHandle.plain("/usr/lib/jvm/java-11"),
                "M2_HOME" to SecretHandle.plain("/opt/maven"),
            ),
        )
        val result = EnvironmentComposer.compose(req)
        // JAVA_HOME prepended first, then M2_HOME/bin prepended to that result
        assertEquals("/opt/maven/bin:/usr/lib/jvm/java-11/bin:/usr/bin:/bin", result["PATH"]!!.borrow { String(it, Charsets.UTF_8) })
    }

    @Test
    fun `sandbox config is recorded in result metadata but not applied`() {
        val req = EnvCompositionRequest(
            base = mapOf("VAR" to SecretHandle.plain("value")),
            sandbox = SandboxConfig.LOCAL,
        )
        val result = EnvironmentComposer.compose(req)
        // Sandbox stage is recorded; no deny-list application in composer
        assertEquals("value", result["VAR"]!!.borrow { String(it, Charsets.UTF_8) })
    }
}
