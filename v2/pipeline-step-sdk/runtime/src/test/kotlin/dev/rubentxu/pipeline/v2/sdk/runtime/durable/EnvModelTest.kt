package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EnvModelTest {

    @Test
    fun `JAVA_HOME prepends bin to PATH`() {
        val input = mapOf(
            "JAVA_HOME" to "/usr/lib/jvm/java-11",
            "PATH" to "/usr/local/bin:/usr/bin"
        )
        val result = EnvModel.apply(input)
        assertEquals("/usr/lib/jvm/java-11/bin:/usr/local/bin:/usr/bin", result["PATH"])
    }

    @Test
    fun `M2_HOME prepends bin to PATH`() {
        val input = mapOf(
            "M2_HOME" to "/opt/maven",
            "PATH" to "/usr/local/bin:/usr/bin"
        )
        val result = EnvModel.apply(input)
        assertEquals("/opt/maven/bin:/usr/local/bin:/usr/bin", result["PATH"])
    }

    @Test
    fun `both JAVA_HOME and M2_HOME prepend in correct order`() {
        // JAVA_HOME prepended first, then M2_HOME (both at front)
        val input = mapOf(
            "JAVA_HOME" to "/usr/lib/jvm/java-11",
            "M2_HOME" to "/opt/maven",
            "PATH" to "/usr/local/bin:/usr/bin"
        )
        val result = EnvModel.apply(input)
        // M2_HOME is prepended after JAVA_HOME, so M2_HOME/bin comes first
        assertEquals("/opt/maven/bin:/usr/lib/jvm/java-11/bin:/usr/local/bin:/usr/bin", result["PATH"])
    }

    @Test
    fun `neither JAVA_HOME nor M2_HOME present leaves PATH unchanged`() {
        val input = mapOf(
            "HOME" to "/home/user",
            "PATH" to "/usr/local/bin:/usr/bin"
        )
        val result = EnvModel.apply(input)
        assertEquals("/usr/local/bin:/usr/bin", result["PATH"])
    }

    @Test
    fun `empty env returns empty map`() {
        val result = EnvModel.apply(emptyMap())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `env with no PATH starts with prepended JAVA_HOME`() {
        val input = mapOf(
            "JAVA_HOME" to "/usr/lib/jvm/java-11"
        )
        val result = EnvModel.apply(input)
        // No PATH in user env: base is the inherited process PATH (Jenkins withEnv semantics)
        assertEquals("/usr/lib/jvm/java-11/bin:${System.getenv("PATH")}", result["PATH"])
    }

    @Test
    fun `env with no PATH starts with prepended M2_HOME`() {
        val input = mapOf(
            "M2_HOME" to "/opt/maven"
        )
        val result = EnvModel.apply(input)
        // No PATH in user env: base is the inherited process PATH (Jenkins withEnv semantics)
        assertEquals("/opt/maven/bin:${System.getenv("PATH")}", result["PATH"])
    }

    @Test
    fun `isValueSafeForProcessBuilder returns true for normal values`() {
        assertTrue(EnvModel.isValueSafeForProcessBuilder("simple"))
        assertTrue(EnvModel.isValueSafeForProcessBuilder("value=with=equals"))
        assertTrue(EnvModel.isValueSafeForProcessBuilder("value with spaces"))
        assertTrue(EnvModel.isValueSafeForProcessBuilder("value\$with\$dollars"))
        assertTrue(EnvModel.isValueSafeForProcessBuilder("value\nwith\nnewlines"))
        assertTrue(EnvModel.isValueSafeForProcessBuilder("value\"with\"quotes"))
    }

    @Test
    fun `isValueSafeForProcessBuilder returns false for NUL`() {
        assertFalse(EnvModel.isValueSafeForProcessBuilder("value\u0000withnul"))
    }

    @Test
    fun `special characters in env values are preserved`() {
        // Verify that special shell characters don't cause issues
        val input = mapOf(
            "KEY" to "value=with=equals",
            "PATH" to "/usr/bin"
        )
        val result = EnvModel.apply(input)
        // The value should be preserved as-is
        assertEquals("value=with=equals", result["KEY"])
    }

    @Test
    fun `unicode in env values are preserved`() {
        val input = mapOf(
            "KEY" to "日本語テスト",
            "PATH" to "/usr/bin"
        )
        val result = EnvModel.apply(input)
        assertEquals("日本語テスト", result["KEY"])
    }
}
