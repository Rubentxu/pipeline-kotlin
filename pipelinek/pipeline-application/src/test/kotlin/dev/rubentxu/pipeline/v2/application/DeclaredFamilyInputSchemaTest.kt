package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StepDescriptorRegistry
import dev.rubentxu.pipeline.v2.domain.StepNode
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.dsl.StageScope
import dev.rubentxu.pipeline.v2.dsl.StageSpec
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * The schema that the catalogue publishes for a family the composition does
 * not register, and the payload the compiler really writes for it.
 *
 * ## The guarantee under trial
 *
 * That the descriptor of a family declares the arguments the payload of that
 * family carries. A family with no codec of its own is declared in the
 * descriptor, and its payload is written by the compiler: the two are two
 * hands, and a schema that names an argument the payload does not carry (or
 * that forgets one it does) is a fact that went stale in silence.
 *
 * ## What this instrument cannot see
 *
 * It does not run a pipeline: it compiles the step and reads the payload the
 * compiler wrote. The families WITH a codec are measured by the row that
 * round-trips the codec, in the module that owns the codecs.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class DeclaredFamilyInputSchemaTest {

    @Test
    fun `the schema of a declared family names the arguments its payload carries`() {
        families().forEach { (key, spec) ->
            val node = compileSingle(spec)
            val payload = Json.parseToJsonElement(node.payload.encoded).jsonObject
            val schema = StepDescriptorRegistry.standard().get(PluginStepId(key))?.inputSchema
                ?: fail("the registry holds no descriptor for $key")
            val parsed = Json.parseToJsonElement(schema).jsonObject
            val properties = parsed["properties"]?.jsonObject ?: JsonObject(emptyMap())
            val required = parsed["required"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty()

            payload.keys.forEach { name ->
                if (name == "kind") {
                    return@forEach
                }
                assertTrue(
                    properties.containsKey(name),
                    "the schema of $key declares the argument '$name' that its payload carries",
                )
            }
            required.forEach { name ->
                assertTrue(
                    payload.containsKey(name),
                    "the schema of $key requires '$name' and its payload carries it",
                )
            }
            properties.keys.forEach { name ->
                if (payload.containsKey(name)) {
                    return@forEach
                }
                assertFalse(
                    name in required,
                    "the schema of $key declares '$name' and its payload does not carry it: an " +
                        "argument that is not there cannot be required",
                )
            }
        }
    }

    /**
     * Every family the composition does not register, with the step that
     * produces its payload.
     *
     * @return the key of each family and the step the compiler turns into it.
     */
    private fun families(): List<Pair<String, StepSpec>> = listOf(
        "core.dir" to StepSpec.Dir(path = "sub", steps = emptyList()),
        "core.withEnv" to StepSpec.WithEnv(overrides = listOf("A=B"), steps = emptyList()),
        "core.timeout" to StepSpec.TimeoutBlock(time = 1, unit = "SECONDS", steps = emptyList()),
        "core.retry" to StepSpec.RetryBlock(count = 2, steps = emptyList()),
        "core.timestamps" to StepSpec.Timestamps(steps = emptyList()),
        "core.catchError" to catchErrorScope(),
        "core.warnError" to warnErrorScope(),
        "core.withCredentials" to StepSpec.WithCredentialsBlock(
            credentialsId = CredentialsId("credencial"),
            purpose = "una credencial",
            bindings = listOf(
                StepSpec.CredentialsBinding(
                    kind = StepSpec.CredentialsBinding.Kind.STRING,
                    credentialsId = CredentialsId("credencial"),
                    variable = "SECRETO",
                ),
            ),
            steps = emptyList(),
        ),
    )

    /**
     * The step of a `catchError { }` scope.
     *
     * The spec type of that scope is deprecated in favour of the scope that
     * builds it, so the row writes the scope an author writes and reads the
     * step it produced.
     *
     * @return the step the scope declared.
     */
    private fun catchErrorScope(): StepSpec {
        val scope = StageScope("s")
        scope.catchError { }
        return scope.steps().single()
    }

    /**
     * The step of a `warnError { }` scope.
     *
     * @return the step the scope declared.
     */
    private fun warnErrorScope(): StepSpec {
        val scope = StageScope("s")
        scope.warnError(message = "aviso") { }
        return scope.steps().single()
    }

    /**
     * The node that the compiler produces for a stage with this step.
     *
     * @param step the step of the stage.
     * @return the node the compiler produced.
     */
    private fun compileSingle(step: StepSpec): StepNode {
        val compiled = DslCompiledPipelineCompiler.compile(
            spec = PipelineSpec(stages = listOf(StageSpec(name = "s", steps = listOf(step)))),
            sourcePath = "pipeline.kts",
            sourceContent = "pipeline { }",
            pluginLockDigest = dev.rubentxu.pipeline.v2.domain.Digest("lock"),
        )
        val body = compiled.stages.single().body as StageBody.Steps
        return body.steps.single()
    }
}
