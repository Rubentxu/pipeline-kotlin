package dev.rubentxu.pipeline.v2.dsl

import dev.rubentxu.pipeline.v2.domain.MapRuntimeConfig
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RuntimeConfig
import dev.rubentxu.pipeline.v2.domain.UnixPlatformClassifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * What the eager DSL answers when a script asks for the platform.
 *
 * ## The guarantee
 *
 * `isUnix()` answers the platform of the machine that BUILDS the plan, and it
 * answers it with the SAME function the runtime uses to classify the platform
 * it observes when the step runs. It does not answer a constant: a constant is
 * a value the reader cannot tell from the real one, and `true` is a value that
 * can be false.
 *
 * The eager DSL reads the platform through the port ([RuntimeConfig.osName]),
 * which is the port the execution-target resolver reads to decide which
 * machine a stage runs on. One platform, one authority, one classifier.
 *
 * ## Why the instrument is this cheap
 *
 * The answer is a function of two things: the platform name the port reports
 * and the canonical classifier. So every row here is a pure call over data,
 * with no plan to build and nothing to run.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class StageScopePlatformTest {

    @Test
    fun `the eager DSL answers the platform of the machine that builds the plan`() {
        assertTrue(
            platformOf("Linux"),
            "a machine that reports Linux is a Unix one",
        )
        assertFalse(
            platformOf("Windows 11"),
            "a machine that reports Windows is not a Unix one, and the DSL cannot answer otherwise",
        )
    }

    @Test
    fun `the eager DSL classifies with the canonical classifier and not with a copy`() {
        listOf("Linux", "linux", " Linux ", "mac os x", "darwin", "windows", "linux-gnu", "").forEach { osName ->
            assertEquals(
                UnixPlatformClassifier.classifyUnix(osName),
                platformOf(osName),
                "the DSL and the canonical classifier disagree about '$osName'",
            )
        }
    }

    @Test
    fun `the eager DSL still enqueues the step that observes the platform`() {
        var answered: Boolean? = null

        val spec = pipeline(host("Linux")) {
            stages {
                stage("build") {
                    answered = isUnix()
                }
            }
        }

        val step = spec.stages.single().steps.single() as StepSpec.RegistryStepSpec
        assertEquals(
            PluginStepId("core.isUnix"),
            step.stepKey,
            "the call still enqueues the step that observes the platform when it runs",
        )
    }

    @Test
    fun `pwd answers the sentinel of the workspace and enqueues the step of the workspace`() {
        var answered: String? = null

        val spec = pipeline(host("Linux")) {
            stages {
                stage("build") {
                    answered = pwd()
                }
            }
        }

        assertEquals(
            "<workspace>",
            answered,
            "the workspace does not exist while the plan is built, so the answer names it instead of inventing a path",
        )
        val step = spec.stages.single().steps.single() as StepSpec.RegistryStepSpec
        assertEquals(
            PluginStepId("core.pwd"),
            step.stepKey,
            "the call still enqueues the step that resolves the workspace when it runs",
        )
    }

    /** The answer the eager DSL gives to `isUnix()` on a machine that reports [osName]. */
    private fun platformOf(osName: String): Boolean {
        var answered: Boolean? = null

        pipeline(host(osName)) {
            stages {
                stage("build") {
                    answered = isUnix()
                }
            }
        }

        return answered ?: error("the eager DSL did not answer the platform")
    }

    private fun host(osName: String): RuntimeConfig =
        MapRuntimeConfig(env = emptyMap(), properties = mapOf("os.name" to osName))
}
