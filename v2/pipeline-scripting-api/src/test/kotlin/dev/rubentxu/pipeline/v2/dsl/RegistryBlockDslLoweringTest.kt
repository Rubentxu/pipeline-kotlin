package dev.rubentxu.pipeline.v2.dsl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

/**
 * WU-RP-033 / RP-3 exit criterion: the generic `registryBlock(...)` DSL primitive
 * lowers a body-owning external Step to `StepSpec.RegistryBlockSpec` carrying the
 * plugin StepKey, the encoded input, and the structural body children. Runtime
 * admission resolves the declared BodyExecutionPolicy from the open registry and
 * rejects fail-closed; the DSL only constructs declarative data.
 */
class RegistryBlockDslLoweringTest {

    @Test
    fun `registryBlock lowers to RegistryBlockSpec with stepKey, encoded input and body children`() {
        val spec = pipeline {
            stages {
                stage("s") {
                    registryBlock(
                        stepKey = dev.rubentxu.pipeline.v2.domain.PluginStepId("test.upperblock"),
                        encodedInput = dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue("{\"prefix\":\"p\"}"),
                    ) {
                        echo("child-1")
                        sh("echo two")
                    }
                }
            }
        }
        val stage = spec.stages.single()
        val step = stage.steps.single() as StepSpec.RegistryBlockSpec
        assertEquals("test.upperblock", step.stepKey.value)
        assertEquals("dsl-v1", step.schemaVersion)
        assertEquals("{\"prefix\":\"p\"}", step.encodedInput.value)
        assertEquals(2, step.body.size)
        assertEquals("echo", step.body[0].name)
        assertEquals("sh", step.body[1].name)
    }

    @Test
    fun `registryBlock body captures nested steps declaratively in declaration order`() {
        val spec = pipeline {
            stages {
                stage("s") {
                    registryBlock(
                        stepKey = dev.rubentxu.pipeline.v2.domain.PluginStepId("k.x"),
                        encodedInput = dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue("{}"),
                    ) {
                        echo("a")
                        echo("b")
                        echo("c")
                    }
                }
            }
        }
        val step = spec.stages.single().steps.single() as StepSpec.RegistryBlockSpec
        assertEquals(listOf("a", "b", "c"), step.body.map { (it as StepSpec.Echo).text })
    }

    @Test
    fun `atomic registryStep remains a leaf with no body`() {
        assertDoesNotThrow {
            pipeline {
                stages {
                    stage("s") {
                        registryStep(
                            stepKey = dev.rubentxu.pipeline.v2.domain.PluginStepId("test.plain"),
                            encodedInput = dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue("{}"),
                        )
                    }
                }
            }
        }
        val spec = pipeline {
            stages {
                stage("s") {
                    registryStep(
                        stepKey = dev.rubentxu.pipeline.v2.domain.PluginStepId("test.plain"),
                        encodedInput = dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue("{}"),
                    )
                }
            }
        }
        val step = spec.stages.single().steps.single()
        assertTrue(step is StepSpec.RegistryStepSpec, "atomic form must stay RegistryStepSpec")
    }
}
