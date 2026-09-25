package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.scm.GitScm
import dev.rubentxu.pipeline.v2.domain.scm.Scm
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.dsl.StageSpec
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * WU-RP-053R — C3.7..C3.10: canonical-envelope formation for the remaining
 * legacy StepSpec subtypes.
 *
 * C3.1 (deleteDir) and C3.2/C3.6 (pwd/cleanWs) closed a structurally identical
 * defect: those StepSpecs fell to the generic `else` branch of
 * [DslCompiledPipelineCompiler.encodePayload], producing
 * `{"kind":"<name>","declarativeValue":"StepSpec.<Name>(...)"}`. The outer
 * `put("kind", step.name)` ensured the registry step resolved, but the typed
 * input fields were erased; the handler's typed decode defaulted absent fields
 * away, silently masking the user's input.
 *
 * C3.7..C3.10 sweep the remaining StepSpec subtypes whose envelope currently
 * goes through the generic `else` branch:
 *   - `StepSpec.Checkout` (C3.7): `scm-git.checkout`-shaped envelope
 *     (`url`, `branch`, `credentialsRef`, `changelog`, `poll`,
 *     `relativeTargetDir`) — matches `GitCheckoutInputCodec.encode`.
 *   - `StepSpec.Load` (C3.8): `load`-shaped envelope (`path`).
 *   - `StepSpec.AnsiColor` (C3.9): `ansiColor`-shaped envelope
 *     (`colorMapName`); body is collapsed by [BlockStepNode] so the envelope
 *     carries the color map only.
 *   - `StepSpec.NodeNoOp` (C3.10): `node`-shaped envelope (`label`); body is
 *     collapsed by [BlockStepNode] so the envelope carries the label only.
 *
 * RED strategy: structural discrimination only. Compile a minimal pipeline
 * with one of each step and parse the produced `OpaqueStepNode.payload.encoded`
 * JSON. The test asserts the typed fields are present and that the
 * `declarativeValue` form does NOT leak. This is purely a compile-time /
 * compiler-correctness check; it never runs the handler, so it is independent
 * of the registry state and isolated from fail-closed admission.
 *
 * No production code is modified. This is a READ-ONLY characterization test
 * against the current behaviour. After this RED suite compiles and the new
 * fields are missing, the GREEN commit extends `encodePayload.when` with the
 * four cases mirroring the C3.1/C3.2/C3.6 pattern.
 */
@Timeout(value = 60)
class WURp053rCanonicalEnvelopeLegacySweepTest {

    /**
     * Compile a synthetic [PipelineSpec] containing [steps] in a single stage
     * and return the produced `OpaqueStepNode`s.
     *
     * Branches / parallel bodies / nested stages are not exercised — this
     * RED sweep targets the `else` branch of `stepNode` which compiles a
     * terminal StepSpec into a flat `OpaqueStepNode`. Block-aware steps
     * (`Dir`, `WithEnv`, etc.) get their own dedicated
     * [DslCompiledPipelineCompiler.blockPayload] branch and are NOT in scope.
     */
    private fun compileStepsToOpaqueNodes(steps: List<StepSpec>): List<OpaqueStepNode> {
        val compiled = DslCompiledPipelineCompiler.compile(
            spec = PipelineSpec(
                stages = listOf(
                    StageSpec(
                        name = "c3-envelope-sweep",
                        steps = steps,
                    ),
                ),
            ),
            sourcePath = "wu-rp-053r/c3-envelope-sweep.pipeline.kts",
            sourceContent = "// synthetic single-stage envelope probe",
            pluginLockDigest = Digest("builtin"),
        )
        val firstStage = compiled.stages.singleOrNull()
            ?: error("expected exactly one stage, got ${compiled.stages.size}")
        val body = firstStage.body as? StageBody.Steps
            ?: error("expected StageBody.Steps, got ${firstStage.body::class.simpleName}")
        return body.steps.mapNotNull { it as? OpaqueStepNode }
    }

    /**
     * Parse the encoded JSON payload of an [OpaqueStepNode] into a
     * [JsonObject]. The compiler emits `{"kind":...}` plus either typed
     * fields (GREEN path) or `declarativeValue` (defect path).
     */
    private fun payloadJson(node: OpaqueStepNode): JsonObject {
        val encoded = node.payload.encoded
        require(encoded.startsWith("{")) {
            "payload must be a JSON object, got: $encoded"
        }
        return Json.parseToJsonElement(encoded).jsonObject
    }

    // =========================================================================
    // C3.7: StepSpec.Checkout → scm-git.checkout-shaped envelope
    //
    // Hypothesis (confirmed by C3.1/C3.2/C3.6 family): `StepSpec.Checkout`
    // currently falls to the generic `else` branch with
    // `{"kind":"checkout","declarativeValue":"StepSpec.Checkout(scm=…)"}`.
    // If/when a `core.checkout` Step key exists, the handler's typed decode
    // (`GitCheckoutInput`-shaped) would crash on absent `url`.
    //
    // Discriminante (RED): the encoded payload MUST expose the typed
    // GitScm fields the registry key expects — `url`, `branch`,
    // `credentialsRef`, `changelog`, `poll`, `relativeTargetDir` — and
    // MUST NOT carry a `declarativeValue` field.
    // =========================================================================
    @Test
    fun `RED C3_7 StepSpec_Checkout payload carries GitScm typed fields and no declarativeValue`() {
        val scm: Scm = GitScm(
            url = "https://example.com/repo.git",
            branch = "main",
            credentialsId = CredentialsId("creds-001"),
            changelog = true,
            poll = true,
            relativeTargetDir = "src",
        )
        val nodes = compileStepsToOpaqueNodes(
            listOf(StepSpec.Checkout(scm = scm)),
        )
        val checkoutNode: OpaqueStepNode = nodes.singleOrNull()
            ?: error("expected exactly one OpaqueStepNode, got ${nodes.size}")
        assertEquals(
            "core.checkout",
            checkoutNode.pluginStepId.value,
            "StepSpec.Checkout must lower to plugin step id 'core.checkout'",
        )
        val obj = payloadJson(checkoutNode)
        assertEquals(
            "checkout",
            obj["kind"]?.jsonPrimitive?.content,
            "envelope kind must be 'checkout'",
        )
        assertNull(
            obj["declarativeValue"],
            "envelope must NOT carry the legacy 'declarativeValue' field " +
                "(C3.1/C3.2/C3.6 defect signature); got=$obj",
        )
        assertEquals(
            JsonPrimitive("https://example.com/repo.git"),
            obj["url"],
            "GitScm.url must be encoded as 'url'",
        )
        assertEquals(
            JsonPrimitive("main"),
            obj["branch"],
            "GitScm.branch must be encoded as 'branch'",
        )
        assertEquals(
            JsonPrimitive("creds-001"),
            obj["credentialsRef"],
            "GitScm.credentialsId must be encoded as 'credentialsRef' " +
                "(matches GitCheckoutInputCodec)",
        )
        assertEquals(
            JsonPrimitive(true),
            obj["changelog"],
            "GitScm.changelog must be encoded as 'changelog'",
        )
        assertEquals(
            JsonPrimitive(true),
            obj["poll"],
            "GitScm.poll must be encoded as 'poll'",
        )
        assertEquals(
            JsonPrimitive("src"),
            obj["relativeTargetDir"],
            "GitScm.relativeTargetDir must be encoded as 'relativeTargetDir'",
        )
    }

    // =========================================================================
    // C3.8: StepSpec.Load → load-shaped envelope
    //
    // Hypothesis: `StepSpec.Load(path)` currently produces
    // `{"kind":"load","declarativeValue":"StepSpec.Load(path=…)"}`.
    // core.load is DEFERRED + UNSUPPORTED in local-core-v1 (no CoreLoadStep)
    // so the runtime fail-closes via the DSL compile-time guard; the
    // envelope is structurally irrelevant until a registry entry exists.
    // Closing the canonical envelope here keeps the family locked.
    //
    // Discriminante: payload carries `path` and no `declarativeValue`.
    // =========================================================================
    @Test
    fun `RED C3_8 StepSpec_Load payload carries path and no declarativeValue`() {
        val nodes = compileStepsToOpaqueNodes(
            listOf(StepSpec.Load(path = "/tmp/load-this.groovy")),
        )
        val loadNode: OpaqueStepNode = nodes.singleOrNull()
            ?: error("expected exactly one OpaqueStepNode, got ${nodes.size}")
        assertEquals(
            "core.load",
            loadNode.pluginStepId.value,
            "StepSpec.Load must lower to plugin step id 'core.load'",
        )
        val obj = payloadJson(loadNode)
        assertEquals(
            "load",
            obj["kind"]?.jsonPrimitive?.content,
            "envelope kind must be 'load'",
        )
        assertNull(
            obj["declarativeValue"],
            "envelope must NOT carry the legacy 'declarativeValue' field " +
                "(C3.1/C3.2/C3.6 defect signature); got=$obj",
        )
        assertEquals(
            JsonPrimitive("/tmp/load-this.groovy"),
            obj["path"],
            "Load.path must be encoded as 'path'",
        )
    }

    // =========================================================================
    // C3.9: StepSpec.AnsiColor → ansiColor-shaped envelope
    //
    // Hypothesis: `StepSpec.AnsiColor(colorMapName, steps)` currently produces
    // `{"kind":"ansiColor","declarativeValue":"..."}`. The body is collapsed
    // into a [BlockStepNode] via `blockPayload` only for the explicit
    // `WithEnv`-style family; `AnsiColor` is NOT in that family (it falls
    // through `stepNode` else-branch as a terminal step via `encodePayload`).
    // Closing the canonical envelope here keeps the family locked.
    //
    // Discriminante: payload carries `colorMapName` and no `declarativeValue`.
    // =========================================================================
    @Test
    fun `RED C3_9 StepSpec_AnsiColor payload carries colorMapName and no declarativeValue`() {
        val nodes = compileStepsToOpaqueNodes(
            listOf(
                StepSpec.AnsiColor(
                    colorMapName = "vga",
                    steps = listOf(StepSpec.Echo(text = "ansi-coloured text")),
                ),
            ),
        )
        val ansiNode: OpaqueStepNode = nodes.singleOrNull { node ->
            node.pluginStepId.value == "core.ansiColor"
        } ?: error(
            "expected one OpaqueStepNode with pluginStepId 'core.ansiColor', " +
                "got nodes=${nodes.map { it.pluginStepId.value }}",
        )
        val obj = payloadJson(ansiNode)
        assertEquals(
            "ansiColor",
            obj["kind"]?.jsonPrimitive?.content,
            "envelope kind must be 'ansiColor'",
        )
        assertNull(
            obj["declarativeValue"],
            "envelope must NOT carry the legacy 'declarativeValue' field " +
                "(C3.1/C3.2/C3.6 defect signature); got=$obj",
        )
        assertEquals(
            JsonPrimitive("vga"),
            obj["colorMapName"],
            "AnsiColor.colorMapName must be encoded as 'colorMapName'",
        )
    }

    // =========================================================================
    // C3.10: StepSpec.NodeNoOp → node-shaped envelope
    //
    // Hypothesis: `StepSpec.NodeNoOp(label, steps)` currently produces
    // `{"kind":"node","declarativeValue":"..."}`. core.node is LOCAL_no_op in
    // the registry (no production handler); the envelope is structurally
    // irrelevant until a registry entry exists. Closing the canonical
    // envelope here keeps the family locked.
    //
    // Discriminante: payload carries `label` and no `declarativeValue`.
    // =========================================================================
    @Test
    fun `RED C3_10 StepSpec_NodeNoOp payload carries label and no declarativeValue`() {
        val nodes = compileStepsToOpaqueNodes(
            listOf(
                StepSpec.NodeNoOp(
                    label = "linux && docker",
                    steps = listOf(StepSpec.Echo(text = "node body")),
                ),
            ),
        )
        val nodeNode: OpaqueStepNode = nodes.singleOrNull { node ->
            node.pluginStepId.value == "core.node"
        } ?: error(
            "expected one OpaqueStepNode with pluginStepId 'core.node', " +
                "got nodes=${nodes.map { it.pluginStepId.value }}",
        )
        val obj = payloadJson(nodeNode)
        assertEquals(
            "node",
            obj["kind"]?.jsonPrimitive?.content,
            "envelope kind must be 'node'",
        )
        assertNull(
            obj["declarativeValue"],
            "envelope must NOT carry the legacy 'declarativeValue' field " +
                "(C3.1/C3.2/C3.6 defect signature); got=$obj",
        )
        assertEquals(
            JsonPrimitive("linux && docker"),
            obj["label"],
            "NodeNoOp.label must be encoded as 'label'",
        )
    }

    // =========================================================================
    // Suite-wide invariant: payload must be canonical, not declarative
    //
    // Belt-and-braces: even if a specific case test has bugs (a StepSpec
    // subtype that ends up with a third envelope shape), the C3 family
    // invariant is `declarativeValue` MUST NOT appear anywhere in the
    // compiled pipeline. This protects the entire structural class of
    // defect, not just one symptom.
    // =========================================================================
    @Test
    fun `RED C3_family no opaque node payload carries declarativeValue anywhere`() {
        val steps: List<StepSpec> = listOf(
            StepSpec.Checkout(scm = GitScm(url = "https://example.com/r.git")),
            StepSpec.Load(path = "/tmp/load.groovy"),
            StepSpec.AnsiColor(colorMapName = "xterm", steps = listOf(StepSpec.Echo(text = "x"))),
            StepSpec.NodeNoOp(label = "any", steps = listOf(StepSpec.Echo(text = "y"))),
        )
        val nodes = compileStepsToOpaqueNodes(steps)
        for (node in nodes) {
            val obj = payloadJson(node)
            assertNull(
                obj["declarativeValue"],
                "node ${node.pluginStepId.value} still carries the legacy 'declarativeValue' " +
                    "field; payload=$obj",
            )
            assertNotNull(
                obj["kind"],
                "node ${node.pluginStepId.value} must carry at least the 'kind' field",
            )
            assertTrue(
                obj.size >= 2,
                "node ${node.pluginStepId.value} must carry kind + at least one typed field, " +
                    "got fields=${obj.keys}",
            )
        }
    }
}
