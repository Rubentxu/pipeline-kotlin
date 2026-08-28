package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Tests for EnvModel PATH+= prepend semantics (ML-R7).
 *
 * Jenkins withEnv supports PATH+<NAME>=<value> syntax to prepend directories to PATH.
 * This test verifies:
 * - PATH+<NAME>=<value> prepends value to existing PATH
 * - Multiple PATH+ entries prepend in order
 * - PATH+ with empty existing PATH works
 * - JAVA_HOME/M2_HOME carry-forward still works
 * - Deduplication preserves order
 */
class EnvModelPathPlusTest {

    @Test
    fun `path_plus_prepend_adds_dir_to_existing_path`() {
        val result = EnvModel.apply(
            mapOf(
                "PATH+ANSIBLE" to "/opt/ansible/bin",
                "PATH" to "/usr/bin:/bin"
            )
        )
        assertEquals("/opt/ansible/bin:/usr/bin:/bin", result["PATH"])
    }

    @Test
    fun `path_plus_with_empty_existing_path`() {
        val result = EnvModel.apply(
            mapOf(
                "PATH+MAVEN" to "/opt/maven/bin"
            )
        )
        // PATH+MAVEN=/opt/maven/bin should work even without existing PATH
        // The actual value depends on system PATH when user env has no PATH
        assertTrue(result["PATH"]?.contains("/opt/maven/bin") == true)
    }

    @Test
    fun `path_plus_multiple_dirs_prepend_in_order`() {
        val result = EnvModel.apply(
            mapOf(
                "PATH+FIRST" to "/first/bin",
                "PATH+SECOND" to "/second/bin",
                "PATH+THIRD" to "/third/bin",
                "PATH" to "/usr/bin"
            )
        )
        // Order: /first/bin, /second/bin, /third/bin, then existing
        assertTrue(result["PATH"]?.startsWith("/first/bin:/second/bin:/third/bin:/usr/bin") == true)
    }

    @Test
    fun `path_plus_deduplicates_preserving_order`() {
        val result = EnvModel.apply(
            mapOf(
                "PATH+BIN1" to "/opt/bin",
                "PATH+BIN2" to "/opt/bin",  // duplicate
                "PATH" to "/opt/bin:/usr/bin"
            )
        )
        // /opt/bin prepended once, then existing
        assertTrue(result["PATH"]?.startsWith("/opt/bin:") == true)
        // Check no triple /opt/bin
        val count = result["PATH"]?.split(":")?.count { it == "/opt/bin" } ?: 0
        assertEquals(1, count)
    }

    @Test
    fun `java_home_carry_forward_still_works`() {
        val result = EnvModel.apply(
            mapOf(
                "JAVA_HOME" to "/opt/jdk21",
                "PATH" to "/usr/bin:/bin"
            )
        )
        assertTrue(result["PATH"]?.contains("/opt/jdk21/bin") == true)
        assertTrue(result["PATH"]?.contains("/usr/bin") == true)
    }

    @Test
    fun `maven_path_plus_prepend_m2_home_works`() {
        val result = EnvModel.apply(
            mapOf(
                "M2_HOME" to "/opt/maven",
                "PATH" to "/usr/bin:/bin"
            )
        )
        assertTrue(result["PATH"]?.contains("/opt/maven/bin") == true)
        assertTrue(result["PATH"]?.contains("/usr/bin") == true)
    }

    @Test
    fun `path_plus_with_java_home_combined`() {
        val result = EnvModel.apply(
            mapOf(
                "PATH+ANSIBLE" to "/opt/ansible/bin",
                "JAVA_HOME" to "/opt/jdk21",
                "PATH" to "/usr/bin:/bin"
            )
        )
        // JAVA_HOME/bin prepended first (outermost), then PATH+ dirs
        // Result: JAVA_HOME/bin:ANSIBLE:original
        assertTrue(result["PATH"]?.startsWith("/opt/jdk21/bin:") == true)
        assertTrue(result["PATH"]?.contains("/opt/ansible/bin") == true)
    }

    @Test
    fun `path_plus_empty_value_is_ignored`() {
        val result = EnvModel.apply(
            mapOf(
                "PATH+EMPTY" to "",
                "PATH" to "/usr/bin:/bin"
            )
        )
        assertEquals("/usr/bin:/bin", result["PATH"])
    }

    @Test
    fun `path_plus_key_must_match_path_plus_suffix`() {
        // Keys that don't match PATH\+[A-Za-z0-9_]+ should pass through unchanged
        val result = EnvModel.apply(
            mapOf(
                "PATH+SOMETHING" to "/some/bin",
                "PATH+X" to "/x/bin",
                "PATHEXTRA" to "/extra/bin",
                "PATH" to "/usr/bin"
            )
        )
        // These keys match PATH+ suffix pattern, so they get removed from output
        // but their values get prepended to PATH
        assertTrue(result["PATH"]?.startsWith("/some/bin:/x/bin:/usr/bin") == true)
        assertNull(result["PATH+SOMETHING"])
        assertNull(result["PATH+X"])
    }
}
