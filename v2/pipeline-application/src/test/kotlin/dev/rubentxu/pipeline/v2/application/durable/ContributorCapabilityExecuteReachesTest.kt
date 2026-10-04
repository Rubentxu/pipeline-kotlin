package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.SystemClock
import dev.rubentxu.pipeline.v2.application.support.CoordinatorFixture
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.DefinitionId
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.SourceDescriptor
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StageId
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.CompositeCapabilityContributor
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.RuntimeCapabilityContributor
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.events.durable.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files

/**
 * WU-093 H7-D — a contributed capability has to be REACHABLE, not merely declared.
 *
 * ## The defect
 *
 * Admission (PREPARE) built its capability access from the run's
 * `capabilityContributor`. The execute-time re-check (EXECUTE) built a SECOND access
 * that omitted it. So every plugin Step declaring a contributor-provided capability
 * was admitted and then threw:
 *
 * ```text
 * EngineInvariantViolation: registry step 'http.request' reached execute
 *   without declared capabilities available: http.transport, credentials.basic
 * ```
 *
 * `http.request` could not run at all, with or without `--allow-network`.
 *
 * ## Why H4.5 did not catch it
 *
 * H4.5 fixed the same class of defect one layer out — the contributor was never
 * presented to the runtime at all — and proved it by asserting the capability was
 * *available* from a hand-built access. That assertion was true and useless: it never
 * ran a Step through the boundary, so it could not tell a reachable capability from a
 * decorative one.
 *
 * The lesson is in this file's shape. A capability nobody can reach is not a
 * capability, and the only honest test for one is executing. So this executes, and it
 * does so with a capability nobody outside this file has ever heard of — no HTTP, no
 * credentials, no `http.request`. The defect lives in the spine, so it is pinned in
 * the spine, and it stays pinned for every plugin that comes after this one.
 *
 * ```text
 * D1  a contributed capability reaches a handler on the real boundary
 * D2  the handler received the EXACT instance the contributor produced
 * D3  a capability nobody contributes still fails closed
 * ```
 */
@Timeout(30)
class ContributorCapabilityExecuteReachesTest {

    private val key = PluginStepId("test.contributed")

    /** A capability with no meaning outside this file. */
    private val contributed: StepCapability = StepCapability("test.contributed.capability")

    /** The identity every claim about "the same instance" depends on. */
    private val instance = Any()

    /**
     * A deliberately tiny wire format of this test's own: `{"probe":"..."}`. It is NOT
     * the registry's `{"payload": ...}` envelope -- that is the journal PARAM name -- and
     * an earlier draft of this file confused the two, which is worth remembering
     * because the confusion looks exactly like a schema failure in the product.
     */
    private val probeCodec = object : StepCodec<String> {
        override fun encode(value: String): EncodedStepValue =
            EncodedStepValue("""{"probe":${Json.encodeToString(JsonPrimitive(value))}}""")

        override fun decode(encoded: EncodedStepValue): String =
            Json.parseToJsonElement(encoded.value).jsonObject["probe"]!!.jsonPrimitive.content
    }

    private val definition = object : StepDefinition<String, String> {
        override val contract = StepContract(
            key = key,
            descriptor = StepDescriptor(
                stepId = key.value,
                name = key.value,
                configRef = "test.contributed",
                effects = listOf(Effect.READ_ONLY),
                replayPolicy = ReplayPolicy.NEVER,
            ),
            inputCodec = probeCodec,
            outputCodec = probeCodec,
            requiredCapabilities = setOf(contributed),
        )

        override val handler = StepHandler<String, String> { input, ctx ->
            // The handler does not receive the contributor; it receives the ACCESS the
            // boundary built. Reaching the value at all is what D1 is about.
            @Suppress("UNCHECKED_CAST")
            val seen = ctx.capabilities.get<Any>(contributed)
            if (seen !== instance) error("handler received $seen, not the contributed instance")
            seen.toString() + "|" + input
        }
    }

    private fun pipeline(input: String) = CompiledPipeline(
        id = DefinitionId("contributed-capability"),
        source = SourceDescriptor("Pipeline.kts", Digest("source")),
        pluginLockDigest = Digest("lock"),
        stages = listOf(
            StageNode(
                id = StageId("build"),
                name = "build",
                body = StageBody.Steps(
                    listOf(
                        OpaqueStepNode(
                            id = StepId("probe"),
                            pluginStepId = key,
                            payload = VersionedStepPayload("dsl-v1", """{"probe":"$input"}"""),
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun coordinator(clock: SystemClock, journal: InMemoryOperationJournal) =
        CoordinatorFixture.default(
            clock = clock,
            journal = journal,
            eventSink = InMemoryEventStore(),
            stepRegistry = InMemoryStepRegistry().apply { register(definition) },
            shOptions = ShOptions(
                workspaceRoot = Files.createTempDirectory("h7d"),
                captureStdout = false,
                timeoutMs = null,
                env = emptyMap(),
            ),
            capabilityContributor = CompositeCapabilityContributor(
                listOf(
                    RuntimeCapabilityContributor { mapOf<StepCapability, Any>(contributed to instance) },
                ),
            ),
        )

    @Test
    fun `D1 a contributed capability reaches the handler on the real boundary`() = runBlocking {
        val clock = SystemClock()

        val outcome = coordinator(clock, InMemoryOperationJournal(clock))
            .run(pipeline("ok"), RunId("h7d-reach"))

        assertEquals(
            RunOutcome.Success,
            outcome,
            "the Step was admitted and then could not execute. PREPARE saw the contributor " +
                "and EXECUTE did not, so a declared plugin capability was a promise the " +
                "engine never kept.",
        )
    }

    @Test
    fun `D2 the handler received the EXACT instance the contributor produced`() = runBlocking {
        val clock = SystemClock()
        val journal = InMemoryOperationJournal(clock)

        coordinator(clock, journal).run(pipeline("ok"), RunId("h7d-identity"))

        val recorded = journal.listForRun("h7d-identity").single()
        assertEquals(
            """{"probe":"$instance|ok"}""",
            recorded.output?.result?.jsonPrimitive?.content,
            "the value the handler saw must be the contributed instance itself, not a copy " +
                "or a re-derived equivalent. Two instances would mean two opinions about one " +
                "capability, which is the failure every capability seam here exists to " +
                "prevent. The handler asserts identity before returning, so reaching this " +
                "line already proves the instance check passed.",
        )
    }

    @Test
    fun `D3 a capability nobody contributes still fails closed`() = runBlocking {
        // Unchanged by this fix, and asserted anyway: threading the contributor through
        // must not have become a permissive default. The declared capability is absent,
        // so the Step must still be refused.
        val clock = SystemClock()
        val coordinator = CoordinatorFixture.default(
            clock = clock,
            journal = InMemoryOperationJournal(clock),
            eventSink = InMemoryEventStore(),
            stepRegistry = InMemoryStepRegistry().apply { register(definition) },
            shOptions = ShOptions(
                workspaceRoot = Files.createTempDirectory("h7d-empty"),
                captureStdout = false,
                timeoutMs = null,
                env = emptyMap(),
            ),
            capabilityContributor = CompositeCapabilityContributor(
                listOf(RuntimeCapabilityContributor { emptyMap() }),
            ),
        )

        val outcome = coordinator.run(pipeline("ok"), RunId("h7d-empty"))

        assertTrue(
            outcome is RunOutcome.Failure,
            "an unsatisfied declared capability must stay a failure. The default " +
                "contributor is empty, not permissive, and that is why this fix is safe.",
        )
    }
}
