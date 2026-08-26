package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for PATH normalisation in [EnvModel.normalizePath].
 *
 * Covers scenarios WS-S-015, WS-S-018, WS-S-020 and PATH edge cases.
 */
class PathNormalizeTest {

    // WS-S-015: default keep-set retains /usr,/bin,/sbin,/opt; drops /tmp/rogue
    @Test
    fun `WS-S-015 - default keep-set retains correct entries`() {
        val env = mapOf(
            "PATH" to "/tmp/rogue:/usr/bin:/bin:/opt:/usr/local/bin",
            "HOME" to "/home/user",
        )
        val result = env.normalizePath( pathKeep = emptySet(), javaHome = null, m2Home = null)

        assertTrue(result["PATH"]!!.startsWith("/usr/bin"), "PATH should start with /usr/bin")
        assertTrue(result["PATH"]!!.contains("/bin"), "PATH should contain /bin")
        assertTrue(result["PATH"]!!.contains("/opt"), "PATH should contain /opt")
        assertFalse(result["PATH"]!!.contains("/tmp/rogue"), "/tmp/rogue should be dropped")
        assertFalse(result["PATH"]!!.contains("/usr/local/bin"), "/usr/local/bin should be dropped (not in default keep-set)")
    }

    // WS-S-018: JAVA_HOME prepend survives PATH normalize
    @Test
    fun `WS-S-018 - JAVA_HOME prepend survives filter`() {
        val env = mapOf(
            "PATH" to "/tmp/rogue:/usr/bin:/bin:/opt:/usr/local/bin",
            "JAVA_HOME" to "/opt/jdk-21",
        )
        val result = env.normalizePath( pathKeep = emptySet(), javaHome = "/opt/jdk-21", m2Home = null)

        assertTrue(result["PATH"]!!.startsWith("/opt/jdk-21/bin"), "PATH must start with JAVA_HOME/bin")
        assertTrue(result["PATH"]!!.contains("/usr/bin"), "/usr/bin should still be present")
    }

    // JAVA + M2 prepend: JAVA first, then M2 (V1 legacy)
    @Test
    fun `JAVA_HOME and M2_HOME prepend order`() {
        val env = mapOf(
            "PATH" to "/tmp/rogue:/usr/bin:/bin:/opt",
            "JAVA_HOME" to "/opt/jdk-21",
            "M2_HOME" to "/opt/maven-3.9",
        )
        val result = env.normalizePath(
            pathKeep = emptySet(),
            javaHome = "/opt/jdk-21",
            m2Home = "/opt/maven-3.9",
        )

        val path = result["PATH"]!!
        val jdkIndex = path.indexOf("/opt/jdk-21/bin")
        val m2Index = path.indexOf("/opt/maven-3.9/bin")
        assertTrue(jdkIndex >= 0, "JAVA_HOME/bin must be present")
        assertTrue(m2Index >= 0, "M2_HOME/bin must be present")
        assertTrue(jdkIndex < m2Index, "JAVA_HOME must come BEFORE M2_HOME (V1 legacy)")
    }

    // WS-S-020: PIPELINE_SANDBOX_PATH_KEEP="/tmp/keep" retains /tmp/keep prefix
    @Test
    fun `WS-S-020 - pathKeep retains custom prefix`() {
        val env = mapOf(
            "PATH" to "/tmp/keep/subdir:/tmp/rogue:/usr/bin:/bin:/opt",
        )
        val result = env.normalizePath(
            pathKeep = setOf("/tmp/keep"),
            javaHome = null,
            m2Home = null,
        )

        assertTrue(result["PATH"]!!.contains("/tmp/keep/subdir"), "/tmp/keep/subdir must be retained")
        assertFalse(result["PATH"]!!.contains("/tmp/rogue"), "/tmp/rogue must be dropped")
    }

    // Empty PATH → empty PATH; prepends still added if JAVA_HOME/M2_HOME set
    @Test
    fun `empty PATH keeps prepends`() {
        val env = mapOf(
            "PATH" to "",
            "JAVA_HOME" to "/opt/jdk-21",
            "M2_HOME" to "/opt/maven-3.9",
        )
        val result = env.normalizePath(
            pathKeep = emptySet(),
            javaHome = "/opt/jdk-21",
            m2Home = "/opt/maven-3.9",
        )

        assertEquals("/opt/jdk-21/bin:/opt/maven-3.9/bin", result["PATH"])
    }

    // pathKeep + JAVA_HOME: PATH starts with JAVA_HOME, then user-keep, then default-keep
    @Test
    fun `pathKeep plus JAVA_HOME ordering`() {
        val env = mapOf(
            "PATH" to "/custom/bin:/usr/bin:/bin:/opt",
            "JAVA_HOME" to "/opt/jdk-21",
        )
        val result = env.normalizePath(
            pathKeep = setOf("/custom"),
            javaHome = "/opt/jdk-21",
            m2Home = null,
        )

        val path = result["PATH"]!!
        // JAVA_HOME first
        assertTrue(path.startsWith("/opt/jdk-21/bin"), "JAVA_HOME must come first")
        // Then /custom/bin
        assertTrue(path.contains("/custom/bin"), "/custom/bin must be retained")
        // Then default keep entries
        assertTrue(path.contains("/usr/bin"), "/usr/bin must be retained")
    }

    // M2_HOME only (no JAVA_HOME)
    @Test
    fun `M2_HOME prepend without JAVA_HOME`() {
        val env = mapOf(
            "PATH" to "/tmp/rogue:/usr/bin:/bin",
            "M2_HOME" to "/opt/maven-3.9",
        )
        val result = env.normalizePath(
            pathKeep = emptySet(),
            javaHome = null,
            m2Home = "/opt/maven-3.9",
        )

        val path = result["PATH"]!!
        assertTrue(path.startsWith("/opt/maven-3.9/bin"), "M2_HOME must come first (only prepend)")
        assertTrue(path.contains("/usr/bin"), "/usr/bin should be retained")
        assertTrue(path.indexOf("/tmp/rogue") < 0, "/tmp/rogue should be dropped")
    }
}
