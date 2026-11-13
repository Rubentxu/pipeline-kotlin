package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.BlockStepNode
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.dsl.pipeline
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * The IR of a WRAPPER: the block that owns steps of its own.
 *
 * ## The guarantee under trial
 *
 * That a wrapper — `dir { }`, `withEnv { }`, `retry { }`, and the PLUGIN form
 * `registryBlock { }` — reaches the IR as the block it is: with its own key,
 * with its own schema version, with its own payload, and with the steps of its
 * body INSIDE it. Nothing about it is flattened into the stage, and nothing
 * about it is lost on the way.
 *
 * ## Why the IR and not the DECLARATION
 *
 * Because the wrapper's meaning lives in two places at once: the KEY says what
 * kind of scope it is, and the PAYLOAD says what it declares. A compiler that
 * dropped the payload would leave a `dir` with no path, and a `withEnv` with no
 * overrides — a scope that says nothing about the scope it projects. A
 * compiler that FLATTENED the wrapper would leave the stage carrying the
 * children directly, and the scope would be gone: the children would run in
 * the stage's own environment, in the stage's own directory, with no retry and
 * no timeout. The declaration-level rows cannot see either: they read what the
 * author wrote.
 *
 * ## What this row does NOT freeze
 *
 * The bytes of the payload of a family the compiler does not own
 * (`CoreShellWireCodec` and the other codecs own theirs, and
 * `CompilerWireAuthorityTest` pins them), and the semantics of the scope at run
 * time. This row reads the IR: what the wrapper IS in the plan.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class CompiledWrapperIrTest {

    /**
     * A wrapper keeps its own key, its own schema version and its own payload.
     *
     * The four shapes the DSL offers are read together: the three core scopes
     * and the plugin form. The plugin form is the one that carries what the
     * PLUGIN declared — its key, its schema version and its encoded input —
     * and the compiler has to carry them verbatim, because the plugin's own
     * codec is what reads them back.
     */
    @Test
    @DisplayName("a wrapper keeps its own key, its own schema version and its own payload")
    fun aWrapperKeepsItsOwnKeyItsOwnSchemaVersionAndItsOwnPayload() {
        val dir = wrapperOf(
            pipeline {
                stages {
                    stage("build") { dir("sub") { echo("inside") } }
                }
            },
        )
        assertEquals("core.dir", dir.pluginStepId.value, "the directory scope kept its own key")
        assertEquals("dsl-v1", dir.payload.schemaVersion, "the directory scope kept its schema")
        assertEquals(
            "sub",
            dir.payload.json()["path"]?.jsonPrimitive?.content,
            "the directory scope kept the path it declares",
        )

        val withEnv = wrapperOf(
            pipeline {
                stages {
                    stage("build") { withEnv(listOf("TARGET=from-the-block")) { echo("inside") } }
                }
            },
        )
        assertEquals(
            "core.withEnv",
            withEnv.pluginStepId.value,
            "the environment scope kept its own key",
        )
        assertEquals(
            listOf("TARGET=from-the-block"),
            withEnv.payload.json()["overrides"]?.jsonArray?.map { it.jsonPrimitive.content },
            "the environment scope kept the overrides it declares",
        )

        val retry = wrapperOf(
            pipeline {
                stages {
                    stage("build") { retry(3) { echo("inside") } }
                }
            },
        )
        assertEquals("core.retry", retry.pluginStepId.value, "the retry scope kept its own key")
        assertEquals(
            3,
            retry.payload.json()["maxAttempts"]?.jsonPrimitive?.content?.toInt(),
            "the retry scope kept the attempt budget it declares",
        )

        val plugin = wrapperOf(
            pipeline {
                stages {
                    stage("build") {
                        registryBlock(
                            PluginStepId("example.block.repeat"),
                            EncodedStepValue("{\"count\":2}"),
                            schemaVersion = "plugin-v1",
                        ) {
                            echo("inside")
                        }
                    }
                }
            },
        )
        assertEquals(
            PluginStepId("example.block.repeat").value,
            plugin.pluginStepId.value,
            "the plugin block kept the key the plugin declared",
        )
        assertEquals(
            "plugin-v1",
            plugin.payload.schemaVersion,
            "the plugin block kept the schema version the plugin declared",
        )
        assertEquals(
            "{\"count\":2}",
            plugin.payload.encoded,
            "the plugin block kept the encoded input the plugin declared",
        )
    }

    /**
     * The steps of a wrapper are INSIDE it, and the stage carries the wrapper
     * alone.
     *
     * A wrapper that was flattened would leave the stage carrying the child
     * beside the wrapper, or instead of it. The scope would be gone with it:
     * the child would run in the stage's own directory, with the stage's own
     * environment and with no retry around it.
     */
    @Test
    @DisplayName("the steps of a wrapper are inside it, and the stage carries the wrapper alone")
    fun theStepsOfAWrapperAreInsideItAndTheStageCarriesTheWrapperAlone() {
        val spec = pipeline {
            stages {
                stage("build") {
                    dir("sub") {
                        echo("first")
                        sh("echo second")
                    }
                }
            }
        }

        val steps = compiledStepsOf(spec)
        assertEquals(
            1,
            steps.size,
            "the stage carries more than the wrapper: the steps are $steps",
        )
        val wrapper = steps.single()
        assertTrue(
            wrapper is BlockStepNode,
            "the stage does not carry a block: it carries $wrapper",
        )
        val body = (wrapper as BlockStepNode).body
        assertEquals(
            listOf("core.echo", "core.sh"),
            body.map { it.pluginStepId.value },
            "the body of the wrapper does not carry the steps the author declared",
        )
    }

    /** The IR of a compiled pipeline: the block the stage carries. */
    private fun wrapperOf(spec: PipelineSpec): BlockStepNode {
        val steps = compiledStepsOf(spec)
        assertEquals(
            1,
            steps.size,
            "the stage carries ${steps.size} steps and not the one the row reads: $steps",
        )
        val step = steps.single()
        assertTrue(step is BlockStepNode, "the stage does not carry a block: it carries $step")
        return step as BlockStepNode
    }

    /** The steps the stage of a compiled specification carries. */
    private fun compiledStepsOf(spec: PipelineSpec) =
        DslCompiledPipelineCompiler.compile(
            spec = spec,
            sourcePath = "pipeline.kts",
            sourceContent = "pipeline { }",
            pluginLockDigest = Digest("lock"),
        ).stages.single().let { stage -> (stage.body as StageBody.Steps).steps }

    /** The payload of a step, read as the object it is. */
    private fun dev.rubentxu.pipeline.v2.domain.VersionedStepPayload.json(): JsonObject =
        Json.parseToJsonElement(encoded).jsonObject
}
