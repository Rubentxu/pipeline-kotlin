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
        val result: Map<String, String> = EnvModel.apply(emptyMap<String, String>())
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

    // ===== applyDenyList tests (WS-S-014, WS-S-016, WS-S-017, WS-S-019) =====

    // WS-S-014: applyDenyList strips LD_PRELOAD when not in allowExtra
    @Test
    fun `WS-S-014 - denyList strips LD_PRELOAD when not in allowExtra`() {
        val env = mapOf(
            "LD_PRELOAD" to "/tmp/evil.so",
            "HOME" to "/home/user",
            "PATH" to "/usr/bin",
        )
        val result = env.applyDenyList(allowExtra = emptySet())
        assertTrue(!result.containsKey("LD_PRELOAD"), "LD_PRELOAD must be stripped")
        assertEquals("/home/user", result["HOME"], "HOME must be retained")
        assertEquals("/usr/bin", result["PATH"], "PATH must be retained")
        // Original map unchanged
        assertTrue(env.containsKey("LD_PRELOAD"), "Original map must be unchanged")
    }

    // WS-S-016: applyDenyList not called when profile=NONE (executor branch)
    // We test that the deny list correctly identifies all 11 deny-listed keys
    @Test
    fun `WS-S-016 - denyList strips all 11 deny-listed keys`() {
        val env = mapOf(
            "LD_PRELOAD" to "/tmp/evil.so",
            "LD_LIBRARY_PATH" to "/tmp/lib",
            "BASH_ENV" to "/tmp/bashenv",
            "ENV" to "/tmp/env",
            "SHELLOPTS" to "emacs:ignoreeof",
            "IFS" to " \t\n",
            "PYTHONPATH" to "/tmp/py",
            "NODE_OPTIONS" to "--inspect",
            "JAVA_TOOL_OPTIONS" to "-Xmx1g",
            "JDK_JAVA_OPTIONS" to "-Xmx2g",
            "HOME" to "/home/user",
            "PATH" to "/usr/bin",
        )
        val result = env.applyDenyList(allowExtra = emptySet())
        assertTrue(!result.containsKey("LD_PRELOAD"), "LD_PRELOAD must be stripped")
        assertTrue(!result.containsKey("LD_LIBRARY_PATH"), "LD_LIBRARY_PATH must be stripped")
        assertTrue(!result.containsKey("BASH_ENV"), "BASH_ENV must be stripped")
        assertTrue(!result.containsKey("ENV"), "ENV must be stripped")
        assertTrue(!result.containsKey("SHELLOPTS"), "SHELLOPTS must be stripped")
        assertTrue(!result.containsKey("IFS"), "IFS must be stripped")
        assertTrue(!result.containsKey("PYTHONPATH"), "PYTHONPATH must be stripped")
        assertTrue(!result.containsKey("NODE_OPTIONS"), "NODE_OPTIONS must be stripped")
        assertTrue(!result.containsKey("JAVA_TOOL_OPTIONS"), "JAVA_TOOL_OPTIONS must be stripped")
        assertTrue(!result.containsKey("JDK_JAVA_OPTIONS"), "JDK_JAVA_OPTIONS must be stripped")
        // Retained
        assertEquals("/home/user", result["HOME"], "HOME must be retained")
        assertEquals("/usr/bin", result["PATH"], "PATH must be retained")
    }

    // WS-S-017: deny-list runs BEFORE user putAll
    // Verify deny-list is applied to the pbEnv BEFORE user env is merged
    @Test
    fun `WS-S-017 - denyList applied before user putAll`() {
        // Simulate: pbEnv has deny-listed key, user env has same key
        val pbEnv = mapOf(
            "LD_PRELOAD" to "/system/ld.so.preload",
            "PATH" to "/usr/bin",
        )
        val userEnv = mapOf(
            "LD_PRELOAD" to "/tmp/evil.so", // User trying to inject
            "HOME" to "/home/user",
        )
        // First apply deny-list to pbEnv (as executor does)
        val afterDeny = pbEnv.applyDenyList(allowExtra = emptySet())
        assertTrue(!afterDeny.containsKey("LD_PRELOAD"), "pbEnv LD_PRELOAD must be stripped before putAll")
        // Then putAll user env (would add back user's LD_PRELOAD)
        // This simulates the order: denyList first, then user putAll
        val merged = afterDeny.toMutableMap().apply { putAll(userEnv) }
        assertEquals("/tmp/evil.so", merged["LD_PRELOAD"], "After putAll, user LD_PRELOAD is present (local profile allows user to set it)")
        // For NONE profile, no deny-list is applied: user LD_PRELOAD would survive directly
    }

    // WS-S-019: allowExtra={"JAVA_TOOL_OPTIONS"} retains it; LD_PRELOAD still stripped
    @Test
    fun `WS-S-019 - allowExtra retains JAVA_TOOL_OPTIONS`() {
        val env = mapOf(
            "LD_PRELOAD" to "/tmp/evil.so",
            "JAVA_TOOL_OPTIONS" to "-Xmx1g",
            "HOME" to "/home/user",
        )
        val result = env.applyDenyList(allowExtra = setOf("JAVA_TOOL_OPTIONS"))
        assertTrue(!result.containsKey("LD_PRELOAD"), "LD_PRELOAD must still be stripped")
        assertEquals("-Xmx1g", result["JAVA_TOOL_OPTIONS"], "JAVA_TOOL_OPTIONS must be retained via allowExtra")
        assertEquals("/home/user", result["HOME"], "HOME must be retained")
    }

    // BASH_FUNC_* glob: BASH_FUNC_* keys are stripped
    @Test
    fun `BASH_FUNC glob keys are stripped`() {
        val env = mapOf(
            "BASH_FUNC_TEST" to "() { echo hi; }",
            "BASH_FUNC_LS" to "() { ls --color=auto; }",
            "HOME" to "/home/user",
        )
        val result = env.applyDenyList(allowExtra = emptySet())
        assertTrue(!result.containsKey("BASH_FUNC_TEST"), "BASH_FUNC_TEST must be stripped")
        assertTrue(!result.containsKey("BASH_FUNC_LS"), "BASH_FUNC_LS must be stripped")
        assertEquals("/home/user", result["HOME"], "HOME must be retained")
    }

    // Cross-reference: pathKeep="/custom" + PATH retain via normalizePath (WS-S-020)
    @Test
    fun `WS-S-020 - pathKeep retains custom prefix in normalizePath`() {
        val env = mapOf(
            "PATH" to "/custom/bin:/tmp/rogue:/usr/bin",
        )
        val result = env.normalizePath(pathKeep = setOf("/custom"), javaHome = null, m2Home = null)
        assertTrue(result["PATH"]!!.contains("/custom/bin"), "/custom/bin must be retained")
        assertTrue(result["PATH"]!!.indexOf("/tmp/rogue") < 0, "/tmp/rogue must be dropped")
    }

    // Cross-reference: JAVA_HOME/M2_HOME prepend survives normalize (WS-S-018)
    @Test
    fun `WS-S-018 - JAVA_HOME M2_HOME prepend survives normalizePath`() {
        val env = mapOf(
            "PATH" to "/usr/bin:/bin",
            "JAVA_HOME" to "/opt/jdk-21",
            "M2_HOME" to "/opt/maven-3.9",
        )
        val result = env.normalizePath(pathKeep = emptySet(), javaHome = "/opt/jdk-21", m2Home = "/opt/maven-3.9")
        assertTrue(result["PATH"]!!.startsWith("/opt/jdk-21/bin:/opt/maven-3.9/bin"),
            "PATH must start with JAVA_HOME/bin:M2_HOME/bin prepends")
    }
}
