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
 * The composition also decides what the controller's own credential material does: it does not
 * cross into the child by inheritance, whatever the profile is, while a credential the pipeline
 * declares on purpose still does reach it. The passphrase that opens the credential store is the
 * controller's, not the pipeline's — a step that inherited it would hold the key to every secret
 * the store holds, which is more than the pipeline asked for. The location of the store is not
 * credential material and does cross: on its own it grants nothing.
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
     * The preparation of a run that says it is confined.
     *
     * The confinement is the same plan the provider probes, built here from the same
     * value, so the wrapper runs the user's script inside the very boundary the
     * admission asked about.
     */
    private fun confinedPreparation(workspace: Path = Path.of("/tmp/pipelinek-workspace")) =
        ShellLaunchPreparation(
            protocol = protocol,
            opId = "op-42",
            heartbeatCheckInterval = 300L,
            pumpedViaAgent = false,
            confinement = SandboxConfinement(
                binary = Path.of("/usr/bin/bwrap"),
                workspace = workspace,
            ),
        )

    /**
     * The environment the launcher inherits, in miniature. The path carries one entry outside the
     * keep-set (`/opt/rogue/bin`), so a filter that does nothing and a filter that drops too much
     * are distinguishable.
     *
     * It also carries what a controller that HAS a credential store carries: the passphrase that
     * opens it (credential material) and the location of the store (a locator). The two are not
     * the same kind of thing, and the composition treats them differently on purpose.
     */
    private val ambient = mapOf(
        "PATH" to "/usr/bin:/bin:/opt/rogue/bin",
        "JAVA_HOME" to "/opt/jdk",
        "LD_PRELOAD" to "/opt/rogue/lib.so",
        "BASH_FUNC_helper%%" to "() { :; }",
        "HOME" to "/home/pipeline",
        "PIPELINE_STORE_PASSPHRASE" to "controller-store-passphrase",
        "PIPELINE_CREDENTIALS_STORE" to "/home/pipeline/.pipeline/credentials.bin",
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

    @Test
    fun `the controller's store passphrase does not reach the child when there is no profile`() {
        val child = preparation().composeEnvironment(
            ambient = ambient,
            sandbox = SandboxConfig.NONE,
            userEnv = env("PATH" to "/usr/bin"),
        )

        // The passphrase opens the store that holds every secret. A step that inherited it could
        // decrypt all of them, which is more authority than the pipeline asked for. No profile is
        // the DEFAULT profile, so this is the common case, not an exotic one.
        assertFalse(
            child.containsKey("PIPELINE_STORE_PASSPHRASE"),
            "the controller's store passphrase reached the child",
        )
    }

    @Test
    fun `the controller's store passphrase does not reach the child under the local profile`() {
        val child = preparation().composeEnvironment(
            ambient = ambient,
            sandbox = SandboxConfig.LOCAL,
            userEnv = env("PATH" to "/usr/bin"),
        )

        // The local profile is not a shield here: its deny-list is about INJECTION vectors
        // (LD_PRELOAD, BASH_ENV, ...), and the passphrase is not one of them. The credential
        // material is removed whatever the profile is.
        assertFalse(
            child.containsKey("PIPELINE_STORE_PASSPHRASE"),
            "the controller's store passphrase reached the child",
        )
    }

    @Test
    fun `a store passphrase the caller declares reaches the child`() {
        val child = preparation().composeEnvironment(
            ambient = ambient,
            sandbox = SandboxConfig.NONE,
            userEnv = env(
                "PATH" to "/usr/bin",
                "PIPELINE_STORE_PASSPHRASE" to "declared-by-the-pipeline",
            ),
        )

        // What is removed is the INHERITANCE, not the request. A credential the pipeline declares
        // on purpose is the channel that delivers it, and the declared value is the one that
        // counts -- not the one the controller happened to hold.
        assertEquals("declared-by-the-pipeline", child["PIPELINE_STORE_PASSPHRASE"])
    }

    @Test
    fun `the location of the store reaches the child because it is not a credential`() {
        val child = preparation().composeEnvironment(
            ambient = ambient,
            sandbox = SandboxConfig.NONE,
            userEnv = env("PATH" to "/usr/bin"),
        )

        // A locator is not credential material: it says WHERE the store is, and without the
        // passphrase it says nothing anybody can use. Filtering it would break the flows that
        // point the product at a store without opening it.
        assertEquals("/home/pipeline/.pipeline/credentials.bin", child["PIPELINE_CREDENTIALS_STORE"])
    }

    @Test
    fun `the confined run executes the user's script inside the confinement`() {
        val script = Path.of("/tmp/pipelinek-shell-preparation/op-42/script.sh")
        val wrapper = confinedPreparation().wrapperContent(script)

        assertTrue(
            wrapper.contains("'/usr/bin/bwrap'") && wrapper.contains("'--ro-bind' '/' '/'"),
            "the user's script runs inside the boundary: $wrapper",
        )
        assertTrue(
            wrapper.contains("'${script}'"),
            "the script is the command the confinement runs: $wrapper",
        )
    }

    @Test
    fun `the protocol of the run stays outside the boundary`() {
        val script = Path.of("/tmp/pipelinek-shell-preparation/op-42/script.sh")
        val wrapper = confinedPreparation().wrapperContent(script)

        val confinementCommand = wrapper
            .substringAfter("'/usr/bin/bwrap'")
            .substringBefore("'${script}'")

        assertTrue(
            "result.txt" !in confinementCommand && ".cookie" !in confinementCommand,
            "the exit code and the cookie are written by the wrapper, outside the boundary: $wrapper",
        )
    }

    @Test
    fun `the exit code is taken from the confined command`() {
        val script = Path.of("/tmp/pipelinek-shell-preparation/op-42/script.sh")
        val wrapper = confinedPreparation().wrapperContent(script)

        val confinementAt = wrapper.indexOf("'/usr/bin/bwrap'")
        val scriptAt = wrapper.indexOf("'${script}'")
        val exitCodeAt = wrapper.indexOf("EXIT_CODE=\$?")

        assertTrue(
            confinementAt >= 0 && confinementAt < scriptAt && scriptAt < exitCodeAt,
            "the exit code the run publishes is the one the confinement returned: $wrapper",
        )
    }

    @Test
    fun `the legacy run is not confined`() {
        val script = Path.of("/tmp/pipelinek-shell-preparation/op-42/script.sh")
        val wrapper = preparation().wrapperContent(script)

        assertTrue(
            "bwrap" !in wrapper,
            "a run that did not ask for a boundary does not get one: $wrapper",
        )
    }
}
