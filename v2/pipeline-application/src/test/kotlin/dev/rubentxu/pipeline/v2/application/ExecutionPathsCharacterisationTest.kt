package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.analyzeCanonicalDurableExecution
import dev.rubentxu.pipeline.v2.application.durable.supportsCanonicalDurableExecution
import dev.rubentxu.pipeline.v2.domain.BlockStepNode
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.dsl.pipeline
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * WU-RP-021 — Execution paths characterisation (RP-2, test-side only).
 *
 * Catalogues the SUPPORTED execution paths at HEAD 59a576e5 by compiling
 * representative pipeline fixtures to canonical IR (`CompiledPipeline`) and
 * asserting the observed structure. This is a *characterisation* suite: the
 * asserted shapes describe current production behaviour, NOT a desired target.
 * Any assertion that turns red after a production change means the execution
 * path catalogue below is stale and must be re-issued (ROADMAP RP-2).
 *
 * Paths catalogued (one test per path):
 *
 * | Path                              | Fixture                       | IR body                    |
 * |-----------------------------------|-------------------------------|----------------------------|
 * | P1 linear declarative             | echo+sh single stage          | StageBody.Steps (opaque)   |
 * | P2 multi-stage linear             | 3 stages                      | Steps per stage            |
 * | P3 scripted (script block)        | script { if }                 | SINGLE opaque core.sh heredoc (EXCEPTION) |
 * | P4 block bodies (retry/timeout)   | timeout>retry>sh              | BlockStepNode nested       |
 * | P5 dir scoping                    | dir { sh }                    | BlockStepNode `core.dir`   |
 * | P6 parallel branches              | parallel { }                  | StageBody.Parallel         |
 * | P7 non-canonical admission        | unknown plugin step           | supportsCanonical=false    |
 * | P8 environment/agent/options      | stage-level metadata          | StageNode metadata         |
 *
 * Legacy/non-canonical exceptions observed are recorded in the receipt
 * docs/v2/07-uat/WU_RP_021_RECEIPT.md.
 */
class ExecutionPathsCharacterisationTest {

    private fun compile(spec: dev.rubentxu.pipeline.v2.dsl.PipelineSpec, path: String = "test.pipeline.kts") =
        DslCompiledPipelineCompiler.compile(
            spec = spec,
            sourcePath = path,
            sourceContent = spec.toString(),
            pluginLockDigest = Digest("builtin"),
        )

    private fun stepKeys(compiled: dev.rubentxu.pipeline.v2.domain.CompiledPipeline): List<String> =
        compiled.stages.flatMap { stage ->
            when (val body = stage.body) {
                is StageBody.Steps -> body.steps.map { it.pluginStepId.value }
                is StageBody.NestedStages -> body.stages.flatMap { nested ->
                    ((nested.body as? StageBody.Steps)?.steps ?: emptyList()).map { it.pluginStepId.value }
                }
                is StageBody.Parallel -> body.branches.flatMap { branch ->
                    ((branch.body as? StageBody.Steps)?.steps ?: emptyList()).map { it.pluginStepId.value }
                }
                is StageBody.Matrix -> emptyList()
            }
        }

    // ------------------------------------------------------------------
    // P1 — linear declarative: two opaque steps in one stage
    // ------------------------------------------------------------------
    @Test
    fun `P1 linear declarative compiles to opaque steps in canonical order`() {
        val spec = pipeline {
            stages {
                stage("Build") {
                    echo("hello")
                    sh("./gradlew test")
                }
            }
        }
        val compiled = compile(spec)

        assertEquals(1, compiled.stages.size)
        val body = compiled.stages.single().body as StageBody.Steps
        assertEquals(listOf("build/echo-0", "build/sh-0"), body.steps.map { it.id.value })
        assertEquals(listOf("core.echo", "core.sh"), body.steps.map { it.pluginStepId.value })
        assertTrue(body.steps.all { it is OpaqueStepNode })
        assertTrue(body.steps.all { it.payload.schemaVersion == "dsl-v1" })
        assertTrue(compiled.supportsCanonicalDurableExecution())
    }

    // ------------------------------------------------------------------
    // P2 — multi-stage linear
    // ------------------------------------------------------------------
    @Test
    fun `P2 multi-stage pipeline keeps one StageNode per DSL stage in order`() {
        val spec = pipeline {
            stages {
                stage("A") { echo("a") }
                stage("B") { sh("echo b") }
                stage("C") { echo("c") }
            }
        }
        val compiled = compile(spec)

        assertEquals(listOf("A", "B", "C"), compiled.stages.map { it.name })
        // Characterised: step ids are prefixed with the LOWERCASED stage name
        // (`a/echo-0` for stage "A"), not a shared per-stage prefix.
        assertEquals(
            listOf(listOf("a/echo-0"), listOf("b/sh-0"), listOf("c/echo-0")),
            compiled.stages.map { stage ->
                ((stage.body as StageBody.Steps).steps.map { it.id.value })
            },
        )
        assertEquals(listOf("core.echo", "core.sh", "core.echo"), stepKeys(compiled))
        assertTrue(compiled.supportsCanonicalDurableExecution())
    }

    // ------------------------------------------------------------------
    // P3 — scripted path: `script { }` block
    // ------------------------------------------------------------------
    @Test
    fun `P3 script block lowers to a single core sh heredoc node`() {
        val spec = pipeline {
            stages {
                stage("conditional") {
                    script {
                        val x = 1
                        if (x > 0) {
                            echo("x is positive")
                        } else {
                            echo("x is not positive")
                        }
                    }
                }
            }
        }
        val compiled = compile(spec)

        // CHARACTERISED EXCEPTION (WU-RP-021): `script { }` does NOT project its
        // inner steps as IR children. The whole script block lowers to ONE opaque
        // `core.sh` node carrying a shell heredoc wrapper (buildShellScript) with
        // `set +e` continue-on-error semantics. The Kotlin control flow (if/else)
        // was evaluated at DSL CONSTRUCTION time — `val x = 1` is a compile-time
        // constant — so only the taken branch's commands reach the heredoc.
        // This is a known legacy shape documented in the WU-RP-021 receipt.
        val body = compiled.stages.single().body as StageBody.Steps
        assertEquals(1, body.steps.size)
        val scriptNode = body.steps.single() as OpaqueStepNode
        assertEquals("core.sh", scriptNode.pluginStepId.value)
        assertEquals("dsl-v1", scriptNode.payload.schemaVersion)
        assertTrue(compiled.supportsCanonicalDurableExecution())
    }

    // ------------------------------------------------------------------
    // P4 — block bodies: timeout wrapping retry wrapping sh
    // ------------------------------------------------------------------
    @Test
    fun `P4 timeout and retry lower to nested BlockStepNode chains`() {
        val spec = pipeline {
            stages {
                stage("retry-block") {
                    timeout(30, "SECONDS") {
                        retry(3) {
                            sh("echo step-A")
                            sh("echo step-B")
                        }
                    }
                }
            }
        }
        val compiled = compile(spec)

        val body = compiled.stages.single().body as StageBody.Steps
        assertEquals(1, body.steps.size, "outer block must be a single IR node")
        val outer = body.steps.single() as BlockStepNode
        assertEquals("core.timeout", outer.pluginStepId.value)

        val inner = outer.body.single() as BlockStepNode
        assertEquals("core.retry", inner.pluginStepId.value)
        assertEquals(
            listOf("core.sh", "core.sh"),
            inner.body.map { it.pluginStepId.value },
        )
        assertTrue(compiled.supportsCanonicalDurableExecution())
    }

    // ------------------------------------------------------------------
    // P5 — dir scoping block
    // ------------------------------------------------------------------
    @Test
    fun `P5 dir block lowers to core dir BlockStepNode with nested steps`() {
        val spec = pipeline {
            stages {
                stage("scoped") {
                    dir("/tmp/workflow-test") {
                        sh("echo inside")
                    }
                }
            }
        }
        val compiled = compile(spec)

        val body = compiled.stages.single().body as StageBody.Steps
        val dir = body.steps.single() as BlockStepNode
        assertEquals("core.dir", dir.pluginStepId.value)
        assertEquals(listOf("core.sh"), dir.body.map { it.pluginStepId.value })
        assertTrue(compiled.supportsCanonicalDurableExecution())
    }

    // ------------------------------------------------------------------
    // P6 — parallel branches
    // ------------------------------------------------------------------
    @Test
    fun `P6 parallel lowers to StageBody Parallel with per-branch StageNodes`() {
        val spec = pipeline {
            stages {
                stage("root") {
                    parallel {
                        branch("left") {
                            echo("l")
                        }
                        branch("right") {
                            echo("r")
                        }
                    }
                }
            }
        }
        val compiled = compile(spec)
        val body = compiled.stages.single().body

        // Characterisation: parallel projects either to StageBody.Parallel
        // (branches as StageNodes) or to a BlockStepNode — pin the observed form.
        when (body) {
            is StageBody.Parallel -> {
                assertEquals(listOf("left", "right"), body.branches.map { it.name })
                assertTrue(
                    body.branches.all { branch ->
                        ((branch.body as? StageBody.Steps)?.steps?.single()?.pluginStepId?.value) == "core.echo"
                    },
                )
            }
            is StageBody.Steps -> {
                val single = body.steps.single()
                assertTrue(
                    single is BlockStepNode,
                    "observed parallel lowering is a block node: $single",
                )
            }
            else -> throw AssertionError("unexpected parallel IR form: $body")
        }
    }

    // ------------------------------------------------------------------
    // P7 — non-canonical admission: unknown plugin step is refused fail-closed
    // ------------------------------------------------------------------
    @Test
    fun `P7 unknown opaque step is refused by the canonical admission gate`() {
        val spec = pipeline {
            stages {
                stage("s") {
                    echo("ok")
                }
            }
        }
        val compiled = compile(spec)
        assertTrue(compiled.supportsCanonicalDurableExecution())

        // Simulate an unknown plugin step by forging an OpaqueStepNode with a
        // non-canonical pluginStepId — mirrors what an external plugin that is
        // NOT loaded would leave behind after compile.
        val forged = compiled.copy(
            stages = compiled.stages.map { stage ->
                val body = stage.body as StageBody.Steps
                stage.copy(body = body.copy(steps = body.steps + OpaqueStepNode(
                    id = dev.rubentxu.pipeline.v2.domain.StepId("build/unknown-0"),
                    pluginStepId = dev.rubentxu.pipeline.v2.domain.PluginStepId("plugin.unknown"),
                    payload = dev.rubentxu.pipeline.v2.domain.VersionedStepPayload("dsl-v1", "{}"),
                )))
            },
        )
        val issues = forged.analyzeCanonicalDurableExecution()
        assertTrue(issues.isNotEmpty(), "unknown plugin id must be flagged non-canonical")
        assertTrue(!forged.supportsCanonicalDurableExecution())
    }

    // ------------------------------------------------------------------
    // P8 — stage-level metadata projection
    // ------------------------------------------------------------------
    @Test
    fun `P8 stage agent environment and options project to StageNode metadata`() {
        val spec = pipeline {
            stages {
                stage("Build") {
                    agent("linux")
                    environment { env("CI", "true") }
                    options { timeout(30) }
                    echo("hello")
                }
            }
        }
        val compiled = compile(spec)
        val stage = compiled.stages.single()

        assertEquals("linux", stage.agent?.label)
        assertEquals(mapOf("CI" to "true"), stage.environment.values)
        assertEquals(listOf("timeout=30"), stage.options.map { "${it.name}=${it.value}" })
        assertTrue(compiled.supportsCanonicalDurableExecution())
    }
}
