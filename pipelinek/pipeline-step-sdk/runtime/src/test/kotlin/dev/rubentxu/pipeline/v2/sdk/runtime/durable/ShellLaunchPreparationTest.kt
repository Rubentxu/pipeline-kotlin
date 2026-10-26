package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import dev.rubentxu.pipeline.v2.domain.SecretHandle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * The environment a durable shell child inherits is a policy, and the policy is stated here.
 *
 * **The guarantee.** The child's environment is composed in one order — the durable markers, then
 * the sandbox profile, then the caller's variables, and finally the path is normalised again — and
 * under the local profile the deny-listed variables never reach the child and the path contains
 * only the sanctioned prefixes. A caller-supplied path cannot smuggle an entry past that filter,
 * because the filter is applied again after the caller's variables are merged.
 *
 * **The observable capacity it serves.** A durable `sh` step runs in an environment the operator
 * chose: the variables that can hijack a child process are gone, the path is the one that was
 * sanctioned, and the operation's identity is readable from the child's own environment when
 * somebody has to look at `/proc`. Nothing in the tree exercised the sandbox before this, so a
 * regression in the deny-list or in the path filter would have reached every sandboxed step
 * unnoticed.
 *
 * **Why this instrument and not another.** The composition is a function from an environment to an
 * environment, so the cheapest instrument that can fail is a call to it. A process, a filesystem or
 * a clock would add the cost and the flakiness without adding a single reachable way for the
 * policy to be wrong. The wrapper's own content already has its own instrument, and the
 * end-to-end scenarios already run real children; neither of them can say WHICH rule of the
 * composition broke, which is exactly what a rule-by-rule check can.
 *
 * **What this does not freeze.** The sentinels' exact wording is not asserted: the only thing
 * that matters about the marker the wrapper inherits is that it differs from the operation's own
 * cookie, because the kill-scan matches the real cookie and would otherwise take the wrapper's
 * machinery for a victim. The names of the variables and the operation's identity are asserted,
 * because those are what a reader of the child's environment depends on.
 */
class ShellLaunchPreparationTest {

    private val protocol = ShellProtocolLayout(Path.of("/tmp/pipelinek-shell-preparation/op-42"))

    private fun preparation(opId: String = "op-42") = ShellLaunchPreparation(
        protocol = protocol,
        opId = opId,
        heartbeatCheckInterval = 300L,
        pumpedViaAgent = false,
    )

    /**
     * The environment the launcher inherits, in miniature. The path carries one entry outside the
     * keep-set (`/opt/rogue/bin`), so a filter that does nothing and a filter that drops too much
     * are distinguishable.
     */
    private val ambient = mapOf(
        "PATH" to "/usr/bin:/bin:/opt/rogue/bin",
        "JAVA_HOME" to "/opt/jdk",
        "LD_PRELOAD" to "/opt/rogue/lib.so",
        "BASH_FUNC_helper%%" to "() { :; }",
        "HOME" to "/home/pipeline",
    )

    private fun env(vararg pairs: Pair<String, String>): Map<String, SecretHandle> =
        pairs.associate { (key, value) -> key to SecretHandle.plain(value) }

    @Test
    fun `the child's environment carries the operation's identity`() {
        val child = preparation(opId = "op-42").composeEnvironment(
            ambient = ambient,
            sandbox = SandboxConfig.NONE,
            userEnv = env("PATH" to "/usr/bin"),
        )

        // The operation's identity is what a reader of the child's own environment looks for.
        assertEquals("op-42", child["DURABLE_SH_OPID"])

        // The marker the wrapper's machinery inherits must NOT be the operation's own cookie:
        // the kill-scan matches the real cookie, and a machinery that carried it would be
        // killed as a victim of the very operation it belongs to.
        assertTrue(child.containsKey("PIPELINE_OP_COOKIE"), "the wrapper's marker is missing")
        assertNotEquals("op-42", child["PIPELINE_OP_COOKIE"])

        // The kill-scan marker is bound to the operation, so a scan can find the operation's
        // processes by it.
        assertTrue(
            child["DURABLE_SH_COOKIE"]?.contains("op-42") == true,
            "the kill-scan marker is not bound to the operation",
        )
    }

    @Test
    fun `the deny-listed variables do not reach the child under the local profile`() {
        val child = preparation().composeEnvironment(
            ambient = ambient,
            sandbox = SandboxConfig.LOCAL,
            userEnv = env("PATH" to "/usr/bin"),
        )

        assertFalse(child.containsKey("LD_PRELOAD"), "a deny-listed variable reached the child")
        assertFalse(child.containsKey("BASH_FUNC_helper%%"), "a deny-listed prefix reached the child")
        assertEquals("/home/pipeline", child["HOME"], "an ordinary variable was dropped")
    }

    @Test
    fun `the deny-listed variables reach the child when there is no profile`() {
        val child = preparation().composeEnvironment(
            ambient = ambient,
            sandbox = SandboxConfig.NONE,
            userEnv = env("PATH" to "/usr/bin"),
        )

        assertEquals("/opt/rogue/lib.so", child["LD_PRELOAD"])
    }

    @Test
    fun `a deny-listed variable named in the allow set reaches the child`() {
        val child = preparation().composeEnvironment(
            ambient = ambient,
            sandbox = SandboxConfig(SandboxProfile.LOCAL, allowExtra = setOf("LD_PRELOAD")),
            userEnv = env("PATH" to "/usr/bin"),
        )

        assertEquals("/opt/rogue/lib.so", child["LD_PRELOAD"], "an allowed variable was dropped")
    }

    @Test
    fun `the path keeps only the sanctioned prefixes under the local profile`() {
        val child = preparation().composeEnvironment(
            ambient = ambient,
            sandbox = SandboxConfig.LOCAL,
            userEnv = env("PATH" to "/usr/bin:/bin:/opt/rogue/bin"),
        )

        // The rogue prefix is gone, the sanctioned ones are not, and the inherited home's own
        // bin directory leads the path.
        assertEquals("/opt/jdk/bin:/usr/bin:/bin", child["PATH"])
    }

    @Test
    fun `a prefix named in the keep set survives the filter`() {
        val child = preparation().composeEnvironment(
            ambient = ambient,
            sandbox = SandboxConfig(SandboxProfile.LOCAL, pathKeep = setOf("/opt/rogue")),
            userEnv = env("PATH" to "/usr/bin:/opt/rogue/bin"),
        )

        assertEquals("/opt/jdk/bin:/usr/bin:/opt/rogue/bin", child["PATH"])
    }

    @Test
    fun `the path is not filtered when there is no profile`() {
        val child = preparation().composeEnvironment(
            ambient = ambient,
            sandbox = SandboxConfig.NONE,
            userEnv = env("PATH" to "/usr/bin"),
        )

        assertEquals("/usr/bin", child["PATH"], "the path was filtered without a profile")
    }

    @Test
    fun `a caller's path cannot smuggle an entry past the filter`() {
        val child = preparation().composeEnvironment(
            ambient = ambient,
            sandbox = SandboxConfig.LOCAL,
            userEnv = env("PATH" to "/usr/bin:/tmp/rogue/bin"),
        )

        // The caller's path replaces the inherited one, and it is filtered again afterwards: a
        // caller that names a path cannot widen the child's reach.
        assertEquals("/opt/jdk/bin:/usr/bin", child["PATH"])
    }

    @Test
    fun `a caller's path is not filtered when there is no profile`() {
        val child = preparation().composeEnvironment(
            ambient = ambient,
            sandbox = SandboxConfig.NONE,
            userEnv = env("PATH" to "/usr/bin:/tmp/rogue/bin"),
        )

        assertEquals("/usr/bin:/tmp/rogue/bin", child["PATH"])
    }

    @Test
    fun `the caller's variables reach the child and win over the inherited ones`() {
        val child = preparation().composeEnvironment(
            ambient = ambient,
            sandbox = SandboxConfig.LOCAL,
            userEnv = env("PATH" to "/usr/bin", "HOME" to "/home/other", "STEP_NAME" to "build"),
        )

        assertEquals("/home/other", child["HOME"])
        assertEquals("build", child["STEP_NAME"])
    }

    @Test
    fun `a caller's home contributes its bin directory to the path`() {
        val child = preparation().composeEnvironment(
            ambient = ambient,
            sandbox = SandboxConfig.NONE,
            userEnv = env("PATH" to "/usr/bin", "JAVA_HOME" to "/opt/other-jdk"),
        )

        // The caller's home leads the path and the inherited one does not follow it: the
        // caller's variables are merged last, so the caller's home is the one that counts.
        assertEquals("/opt/other-jdk/bin:/usr/bin", child["PATH"])
    }

    @Test
    fun `a caller's prepended path entry leads the path`() {
        val child = preparation().composeEnvironment(
            ambient = ambient,
            sandbox = SandboxConfig.NONE,
            userEnv = env("PATH" to "/usr/bin", "PATH+MAVEN" to "/opt/maven/bin"),
        )

        assertEquals("/opt/maven/bin:/usr/bin", child["PATH"])
    }

    @Test
    fun `a caller's variables are not modified by the composition`() {
        val callerEnv = env("PATH" to "/usr/bin", "HOME" to "/home/other")
        val before = callerEnv.mapValues { it.value.materialize() }

        preparation().composeEnvironment(
            ambient = ambient,
            sandbox = SandboxConfig.LOCAL,
            userEnv = callerEnv,
        )

        // The caller's map belongs to the caller: the composition reads it and materialises the
        // handles into a map of its own, so a later reader of the caller's map still finds the
        // caller's values and not the composed ones.
        assertEquals(before, callerEnv.mapValues { it.value.materialize() })
    }
}
