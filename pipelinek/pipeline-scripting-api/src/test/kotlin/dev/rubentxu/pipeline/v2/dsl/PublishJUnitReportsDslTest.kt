package dev.rubentxu.pipeline.v2.dsl

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.post.PostCondition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * The declaration law of the JUnit publication façade.
 *
 * Four guarantees, all measured on the declared DATA and not on an execution:
 *  - the producer's declaration is explicit: pattern, format kind, format version and media
 *    type travel in the payload, and the step key is the core publication key — nothing is
 *    guessed from a file name;
 *  - the publication is hostable in a post condition (`always`), which is the
 *    publication-after-test-failure path, while a step of a plugin stays refused there;
 *  - expectation and group appear in the payload ONLY when the author declares them;
 *  - the façade is the same payload at stage level and inside a body, so the compiler lowers
 *    one declaration and not two.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class PublishJUnitReportsDslTest {

    private val publicationKey: PluginStepId = PluginStepId("core.junit.publishReports")

    @Test
    fun `the façade declares the core publication step with the producer's explicit format`() {
        val stage = StageScope("publish")
        stage.publishJUnitReports(
            artifacts = "out/r.dat",
            reportFormat = "junit-xml",
            formatVersion = "2",
            mediaType = "text/xml",
        )

        val declared = assertInstanceOf(StepSpec.RegistryStepSpec::class.java, stage.steps().single())
        assertEquals(publicationKey, declared.stepKey)
        assertEquals("dsl-v1", declared.schemaVersion)
        // The words of the producer, not an inference: pattern + format + version + mediaType.
        assertTrue(
            declared.encodedInput.value.contains("\"artifacts\":\"out/r.dat\"") &&
                declared.encodedInput.value.contains("\"reportFormat\":\"junit-xml\"") &&
                declared.encodedInput.value.contains("\"formatVersion\":\"2\"") &&
                declared.encodedInput.value.contains("\"mediaType\":\"text/xml\""),
            "the declaration must carry what the producer stated, got: ${declared.encodedInput.value}",
        )
    }

    @Test
    fun `an undeclared expectation and group stay out of the payload`() {
        val encoded = JUnitPublicationPayload.encode(artifacts = "build/test-results/**/*.xml")

        assertTrue(encoded.value.contains("\"required\":true"), "no-match must default to a failure")
        assertNull(Regex("\"expectedCount\"").find(encoded.value))
        assertNull(Regex("\"expectedGroup\"").find(encoded.value))
        assertNull(Regex("\"archiveName\"").find(encoded.value))
    }

    @Test
    fun `a declared expectation and group travel in the payload`() {
        val encoded = JUnitPublicationPayload.encode(
            artifacts = "build/test-results/**/*.xml",
            expectedCount = 3,
            expectedGroup = "unit",
            archiveName = "junit-unit",
        )

        assertTrue(encoded.value.contains("\"expectedCount\":3"))
        assertTrue(encoded.value.contains("\"expectedGroup\":\"unit\""))
        assertTrue(encoded.value.contains("\"archiveName\":\"junit-unit\""))
    }

    @Test
    fun `the publication is hostable in post always — the path that publishes after a test failure`() {
        val spec = pipeline {
            stages {
                stage("Test") {
                    sh("run-tests")
                    post {
                        always {
                            publishJUnitReports(
                                artifacts = "results/*.xml",
                                required = false,
                                expectedGroup = "e2e",
                            )
                        }
                    }
                }
            }
        }

        val steps = spec.stages.single().post.conditions.getValue(PostCondition.ALWAYS)
        val declared = assertInstanceOf(StepSpec.RegistryStepSpec::class.java, steps.single())
        assertEquals(publicationKey, declared.stepKey)
        assertTrue(
            declared.encodedInput.value.contains("\"required\":false") &&
                declared.encodedInput.value.contains("\"expectedGroup\":\"e2e\""),
            "the post emitter must delegate to the same payload: ${declared.encodedInput.value}",
        )
    }

    @Test
    fun `a post condition still refuses a step of a plugin`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            pipeline {
                post {
                    always {
                        registryStep(PluginStepId("junit.results"), EncodedStepValue("{}"))
                    }
                }
            }
        }
        // The form of the refusal, not its prose: the restriction is IllegalArgumentException
        // (asserted above) and its contract promises the message NAMES the step it refused.
        assertTrue(
            error.message!!.contains("junit.results"),
            "the refusal must name the plugin step it refused, got: ${error.message}",
        )
    }

    @Test
    fun `the façade refuses the same input invariants the runtime decoder holds`() {
        // An authoring-time refusal, not a broken decode at run time: the façade declares only
        // what the step can verify, so a negative expectation or a dead cap never ships.
        val negative = assertThrows(IllegalArgumentException::class.java) {
            JUnitPublicationPayload.encode(artifacts = "r/*.xml", expectedCount = -1)
        }
        assertTrue(negative.message!!.contains("expectedCount"), negative.message)

        val deadCap = assertThrows(IllegalArgumentException::class.java) {
            JUnitPublicationPayload.encode(artifacts = "r/*.xml", maxReportBytes = 0L)
        }
        assertTrue(deadCap.message!!.contains("maxReportBytes"), deadCap.message)
    }

    @Test
    fun `the façade declares the same payload at stage level and inside a body`() {
        val stage = StageScope("parity")
        val body = stage.blockScope("steps")

        stage.publishJUnitReports(artifacts = "r/*.xml", archiveName = "reports")
        body.publishJUnitReports(artifacts = "r/*.xml", archiveName = "reports")

        assertEquals(stage.steps(), body.steps())
    }
}
