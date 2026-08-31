package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * D-1.3: Tests for masked SecretHandle entry propagation in EnvModel.apply.
 *
 * Verifies that masked (non-PATH+=) entries are propagated to the output map
 * as-is, while PATH+= prepends are captured for path construction.
 */
class EnvModelMaskedEntryPropagationTest {

    /**
     * Case 1: Mixed typed env — masked SSH + JAVA_HOME + PATH+=
     * All 3 entries present in output; isMasked==true on SSH.
     */
    @Test
    fun `mixed typed env masked SSH plus JAVA_HOME and PATH plus preserves all entries`() {
        val env = mapOf(
            "SSH_KEY" to dev.rubentxu.pipeline.v2.domain.SecretHandle.masked("/tmp/id_rsa"),
            "JAVA_HOME" to dev.rubentxu.pipeline.v2.domain.SecretHandle.masked("/usr/lib/jvm/java-11"),
            "PATH+MAVEN" to dev.rubentxu.pipeline.v2.domain.SecretHandle.masked("/opt/maven/bin")
        )

        val result = EnvModel.apply(env)

        // All three entries present
        assertTrue(result.containsKey("SSH_KEY"), "SSH_KEY should be in output")
        assertTrue(result.containsKey("JAVA_HOME"), "JAVA_HOME should be in output")
        assertTrue(result.containsKey("PATH+MAVEN"), "PATH+MAVEN should be in output")

        // SSH is masked
        assertTrue(result["SSH_KEY"]!!.isMasked, "SSH_KEY should be masked")
        assertEquals("/tmp/id_rsa", result["SSH_KEY"]!!.materialize())

        // JAVA_HOME and PATH+MAVEN are masked
        assertTrue(result["JAVA_HOME"]!!.isMasked, "JAVA_HOME should be masked")
        assertTrue(result["PATH+MAVEN"]!!.isMasked, "PATH+MAVEN should be masked")

        // PATH is constructed with JAVA_HOME/bin prepended (no original PATH, no non-masked PATH entry)
        assertTrue(result.containsKey("PATH"), "PATH should be constructed")
        assertTrue(result["PATH"]!!.materialize().startsWith("/usr/lib/jvm/java-11/bin"),
            "PATH should start with JAVA_HOME/bin prepend")
    }

    /**
     * Case 2: Masked SSH alone — SSH entry with isMasked==true in output.
     * PATH is seeded from System.getenv when not in input (original typed behavior preserved).
     */
    @Test
    fun `masked SSH alone propagates with isMasked true`() {
        val env = mapOf(
            "SSH_KEY" to dev.rubentxu.pipeline.v2.domain.SecretHandle.masked("/tmp/id_rsa")
        )

        val result = EnvModel.apply(env)

        assertTrue(result.containsKey("SSH_KEY"), "SSH_KEY should be in output")
        assertTrue(result["SSH_KEY"]!!.isMasked, "SSH_KEY should be masked")
        assertEquals("/tmp/id_rsa", result["SSH_KEY"]!!.materialize())
    }

    /**
     * Case 3: Masked SSH + PATH-only — SSH in output, PATH correct.
     * PATH entry with isMasked==true propagates as-is (not processed for prepend).
     */
    @Test
    fun `masked SSH plus masked PATH propagates SSH and preserves PATH`() {
        val env = mapOf(
            "SSH_KEY" to dev.rubentxu.pipeline.v2.domain.SecretHandle.masked("/tmp/id_rsa"),
            "PATH" to dev.rubentxu.pipeline.v2.domain.SecretHandle.masked("/usr/bin:/bin")
        )

        val result = EnvModel.apply(env)

        // SSH in output, masked
        assertTrue(result.containsKey("SSH_KEY"), "SSH_KEY should be in output")
        assertTrue(result["SSH_KEY"]!!.isMasked, "SSH_KEY should be masked")
        assertEquals("/tmp/id_rsa", result["SSH_KEY"]!!.materialize())

        // PATH propagated as-is (masked, not transformed)
        assertTrue(result.containsKey("PATH"), "PATH should be in output")
        assertTrue(result["PATH"]!!.isMasked, "PATH should be masked")
        assertEquals("/usr/bin:/bin", result["PATH"]!!.materialize())
    }
}
