package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.BlockStepNode
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.MapRuntimeConfig
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.dsl.pipeline
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * The plan of a `withEnv` block, and the environment its children run with.
 *
 * ## The guarantee under trial
 *
 * That the plan and the environment say the SAME thing about the variable the
 * block declares. The block declares `TARGET=from-the-block` for its steps,
 * and the runtime applies that declaration to the children of the block
 * (`scope.parentEnv + envOverrides`, in the body engine). A plan that read
 * the variable before the declaration reached it would carry the value of the
 * host while the child ran with the value of the block: the two artefacts the
 * runtime uses would answer differently about one variable.
 *
 * ## Why this is an INTEGRATION row
 *
 * Because the two halves live in two different places: the READ happens in
 * the DSL module, and the ENVIRONMENT travels in the payload the compiler
 * writes. A row that looked at only one of them would pass while the product
 * disagreed with itself, so the row reads both and compares them.
 *
 * ## What this row does not freeze
 *
 * How the runtime applies the declaration — that is the body engine's own
 * guarantee, and it has its own rows there. This row certifies that the plan
 * and the payload agree, which is what the compiler and the DSL can answer.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class WithEnvEnvironmentIntegrationTest {

    @Test
    fun `the plan of a withEnv block carries the value the block declares`() {
        val spec = pipeline(
            MapRuntimeConfig(env = mapOf("TARGET" to "from-the-host"), properties = emptyMap()),
        ) {
            stages {
                stage("build") {
                    withEnv(listOf("TARGET=from-the-block")) {
                        sh("echo " + env("TARGET"))
                    }
                }
            }
        }

        val compiled = DslCompiledPipelineCompiler.compile(
            spec = spec,
            sourcePath = "pipeline.kts",
            sourceContent = "pipeline { }",
            pluginLockDigest = Digest("lock"),
        )
        val body = compiled.stages.single().body as StageBody.Steps
        val block = body.steps.single() as BlockStepNode

        assertEquals(
            PluginStepId("core.withEnv"),
            block.pluginStepId,
            "the block compiles into the family that owns the environment it declares",
        )
        val overrides = Json.parseToJsonElement(block.payload.encoded)
            .jsonObject["overrides"]
            ?.jsonArray
            ?.map { it.jsonPrimitive.content }
            .orEmpty()
        assertEquals(
            listOf("TARGET=from-the-block"),
            overrides,
            "the payload carries the declaration the runtime applies to the children",
        )
        val shell = block.body.single() as OpaqueStepNode
        val command = Json.parseToJsonElement(shell.payload.encoded)
            .jsonObject["command"]
            ?.jsonPrimitive
            ?.content
        assertEquals(
            "echo from-the-block",
            command,
            "the plan reads the value the block declares, and not the one the host has",
        )
    }
}
