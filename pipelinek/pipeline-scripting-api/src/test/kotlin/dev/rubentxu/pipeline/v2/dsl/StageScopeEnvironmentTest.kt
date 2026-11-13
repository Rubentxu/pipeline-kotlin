package dev.rubentxu.pipeline.v2.dsl

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.MapRuntimeConfig
import dev.rubentxu.pipeline.v2.domain.RuntimeConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * What a script reads when it asks for a variable of the environment.
 *
 * ## The guarantee
 *
 * `env("X")` answers the variable the STAGE declares, and the variable the
 * PROCESS has when the stage declares none. A variable that is not defined
 * answers null and not an empty text, and the answer is never the material of a
 * credential.
 *
 * ## Why the instrument is this cheap
 *
 * The answer is a function of two inputs — the declaration the author writes and
 * the port the read falls back to — and the port is a fake the test owns, so
 * every row here is a pure call over data. The row that measures the freezing
 * is the one that needs the PLAN: a read that happened when the step ran would
 * leave the plan carrying no value at all, and a read that happened twice
 * would leave it carrying the second one.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class StageScopeEnvironmentTest {

    @Test
    fun `the process answers the variable the stage declares nothing`() {
        var read: String? = null

        pipeline(host(env = mapOf("TARGET" to "from-the-process"))) {
            stages {
                stage("build") {
                    read = env("TARGET")
                }
            }
        }

        assertEquals(
            "from-the-process",
            read,
            "the stage declares no variable, so the process answers",
        )
    }

    @Test
    fun `the declaration of the stage wins over the process`() {
        var read: String? = null

        pipeline(host(env = mapOf("TARGET" to "from-the-process"))) {
            stages {
                stage("build") {
                    environment { env("TARGET", "from-the-declaration") }
                    read = env("TARGET")
                }
            }
        }

        assertEquals(
            "from-the-declaration",
            read,
            "the declaration of the stage is the variable the steps run with",
        )
    }

    @Test
    fun `a variable that is not defined answers null and not an empty text`() {
        var read: String? = "the read did not happen"

        pipeline(host()) {
            stages {
                stage("build") {
                    read = env("ABSENT")
                }
            }
        }

        assertNull(read, "a variable that is not defined is not an empty text")
    }

    @Test
    fun `the pipeline reads the process when it declares nothing`() {
        var read: String? = null

        pipeline(host(env = mapOf("TARGET" to "from-the-process"))) {
            read = env("TARGET")
        }

        assertEquals(
            "from-the-process",
            read,
            "the pipeline declares no variable, so the process answers",
        )
    }

    @Test
    fun `the material of a credential is not something a read answers`() {
        var read: String? = "the read did not happen"

        val spec = pipeline(host()) {
            stages {
                stage("deploy") {
                    withCredentials(
                        listOf(StepSpec.CredentialsBinding.string("deploy-key", "TOKEN")),
                    ) {
                        read = env("TOKEN")
                    }
                }
            }
        }

        assertNull(
            read,
            "the material of a credential reaches the child process, not the environment of this one",
        )
        val binding = spec.stages.single().steps.single() as StepSpec.WithCredentialsBlock
        assertEquals(
            CredentialsId("deploy-key"),
            binding.credentialsId,
            "the plan carries the reference to the credential, never its material",
        )
    }

    @Test
    fun `the declaration of a withEnv block is visible to the reads inside it`() {
        var read: String? = null

        pipeline(host(env = mapOf("TARGET" to "from-the-process"))) {
            stages {
                stage("build") {
                    withEnv(listOf("TARGET=from-the-block")) {
                        read = env("TARGET")
                    }
                }
            }
        }

        assertEquals(
            "from-the-block",
            read,
            "the block declares the variable its steps run with, so the read inside it sees it",
        )
    }

    @Test
    fun `the declaration of a withEnv block is not visible outside it`() {
        var read: String? = null

        pipeline(host(env = mapOf("TARGET" to "from-the-process"))) {
            stages {
                stage("build") {
                    withEnv(listOf("TARGET=from-the-block")) {
                        sh("echo inside")
                    }
                    read = env("TARGET")
                }
            }
        }

        assertEquals(
            "from-the-process",
            read,
            "the block declares the variables of ITS steps, and not the ones of its parent",
        )
    }

    @Test
    fun `an override without a value declares the name with an empty value`() {
        var read: String? = "the read did not happen"

        pipeline(host(env = mapOf("TARGET" to "from-the-process"))) {
            stages {
                stage("build") {
                    withEnv(listOf("TARGET")) {
                        read = env("TARGET")
                    }
                }
            }
        }

        assertEquals(
            "",
            read,
            "the runtime gives an override without a value the name and an empty value, " +
                "and the read has to answer the same thing",
        )
    }

    @Test
    fun `the read happens while the plan is built and the plan keeps the value it read`() {
        val changing = ChangingRuntimeConfig()

        val spec = pipeline(changing) {
            stages {
                stage("build") {
                    sh("echo " + env("TARGET"))
                }
            }
        }

        assertEquals(
            "value-2",
            changing.env("TARGET"),
            "the port answers a different value now",
        )
        val shell = spec.stages.single().steps.single() as StepSpec.Shell
        assertEquals(
            "echo value-1",
            shell.command,
            "the plan carries the value it read while it was built",
        )
    }

    /**
     * The port of a machine whose value changes under the reader.
     *
     * A read that happened when the step ran would answer whatever the port
     * holds then, so the two answers have to differ for the plan to prove
     * anything.
     */
    private class ChangingRuntimeConfig : RuntimeConfig {
        private var calls = 0

        override fun env(name: String): String = "value-${++calls}"

        override fun property(name: String): String? = null

        override fun property(name: String, default: String): String = default

        override fun osName(): String = "Linux"

        override fun userDir(): String = "/tmp"
    }

    private fun host(env: Map<String, String> = emptyMap()): RuntimeConfig =
        MapRuntimeConfig(env = env, properties = emptyMap())
}
