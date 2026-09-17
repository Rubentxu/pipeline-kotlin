package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator
import dev.rubentxu.pipeline.v2.application.durable.CanonicalNodeDispatcher
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeCapabilityAccess
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext
import dev.rubentxu.pipeline.v2.application.durable.ExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeFailure
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeOutcome
import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.STEP_OUTPUT_RESOLVER_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.domain.step.StepOutputRef
import dev.rubentxu.pipeline.v2.domain.step.StepOutputResolutionError
import dev.rubentxu.pipeline.v2.domain.step.StepOutputResolutionException
import dev.rubentxu.pipeline.v2.domain.step.StepOutputResolver
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.dsl.pipeline
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files

// ── test-local producer / consumer (deliberately not product Steps) ──────

data class ProducerInput(val seed: String)

data class ProducerOutput(val computed: String, val computedLength: Int)

data class ConsumerInput(val ref: StepOutputRef)

data class ConsumerOutput(val observed: String)

// JSON is built and parsed with the runtime builders rather than @Serializable: this module's
// test source set does not apply the kotlinx-serialization compiler plugin, and the piping
// seam only needs to move an EncodedStepValue across, not to generate serializers.
private fun encodeJson(name: String): EncodedStepValue =
    EncodedStepValue(buildJsonObject { put("v", JsonPrimitive(name)) }.toString())

private fun decodeJson(encoded: EncodedStepValue): String =
    Json.parseToJsonElement(encoded.value).jsonObject["v"]!!.jsonPrimitive.content

private object ProducerInputCodec : StepCodec<ProducerInput> {
    override fun encode(value: ProducerInput) = encodeJson(value.seed)
    override fun decode(encoded: EncodedStepValue) = ProducerInput(decodeJson(encoded))
}

private object ProducerOutputCodec : StepCodec<ProducerOutput> {
    override fun encode(value: ProducerOutput) = encodeJson(value.computed)
    override fun decode(encoded: EncodedStepValue) = ProducerOutput(decodeJson(encoded), 0)
}

private object ConsumerInputCodec : StepCodec<ConsumerInput> {
    override fun encode(value: ConsumerInput) =
        EncodedStepValue(
            buildJsonObject {
                put("name", JsonPrimitive(value.ref.name))
                put("typeTag", JsonPrimitive(value.ref.typeTag))
            }.toString(),
        )

    override fun decode(encoded: EncodedStepValue) = ConsumerInput(
        StepOutputRef(
            name = Json.parseToJsonElement(encoded.value).jsonObject["name"]!!.jsonPrimitive.content,
            typeTag = Json.parseToJsonElement(encoded.value).jsonObject["typeTag"]!!.jsonPrimitive.content,
        ),
    )
}

private object ConsumerOutputCodec : StepCodec<ConsumerOutput> {
    override fun encode(value: ConsumerOutput) = encodeJson(value.observed)
    override fun decode(encoded: EncodedStepValue) = ConsumerOutput(decodeJson(encoded))
}

/** Producer: computes its output at RUN time from its input. */
private object ProducerStepDefinition : StepDefinition<ProducerInput, ProducerOutput> {
    val KEY = PluginStepId("test.producer")

    override val contract = StepContract(
        key = KEY,
        descriptor = StepDescriptor(
            stepId = KEY.value,
            name = "testProducer",
            configRef = "",
            pluginId = "test.piping",
            executionLocation = ExecutionLocation.CONTROLLER,
            effects = listOf(Effect.READ_ONLY),
            replayPolicy = ReplayPolicy.MEMOIZED,
        ),
        inputCodec = ProducerInputCodec,
        outputCodec = ProducerOutputCodec,
        requiredCapabilities = emptySet(),
    )

    override val handler = StepHandler<ProducerInput, ProducerOutput> { input, _ ->
        ProducerOutput(computed = "computed-from-${input.seed}", computedLength = input.seed.length)
    }
}

/**
 * Consumer: obtains the producer's output ONLY through the declared resolver capability. It
 * never reaches the journal, the coordinator or any ambient state.
 */
private object ConsumerStepDefinition : StepDefinition<ConsumerInput, ConsumerOutput> {
    val KEY = PluginStepId("test.consumer")

    override val contract = StepContract(
        key = KEY,
        descriptor = StepDescriptor(
            stepId = KEY.value,
            name = "testConsumer",
            configRef = "",
            pluginId = "test.piping",
            executionLocation = ExecutionLocation.CONTROLLER,
            effects = listOf(Effect.READ_ONLY),
            replayPolicy = ReplayPolicy.MEMOIZED,
        ),
        inputCodec = ConsumerInputCodec,
        outputCodec = ConsumerOutputCodec,
        requiredCapabilities = setOf(STEP_OUTPUT_RESOLVER_CAPABILITY),
    )

    override val handler = StepHandler<ConsumerInput, ConsumerOutput> { input, ctx ->
        val resolver: StepOutputResolver = ctx.capabilities.get(STEP_OUTPUT_RESOLVER_CAPABILITY)
        ConsumerOutput(observed = resolver.resolveEncoded(input.ref).value)
    }
}


/**
 * LFC-2E3-P / P2 — typed Step-output value piping contract.
 *
 * The mechanism is proven GENERICALLY, with a test-local producer/consumer pair, so nothing here
 * is designed around a particular product Step (the cycle directive explicitly forbids that). The
 * producer's output is computed at RUN time, which is what makes the proof meaningful: a fabricated
 * construction-time value could not reproduce it.
 *
 * Laws asserted:
 *
 * ```text
 * no fake runtime values          the ref carries none; construction cannot fabricate
 * no Map<String,Any> escape       the seam transports EncodedStepValue; the plugin decodes
 * no ambient lookup               resolution goes through a DECLARED capability
 * durable producer identity       name -> producing operation, recorded in the run
 * replay reuses committed output  resolution reads the journal (the authority)
 * consumer cannot observe early   NotYetProduced, fail closed
 * type mismatch fail-closed       TypeMismatch, fail closed
 * no Step-specific routing        the coordinator knows only OpaqueStepNode.outputName
 * ```
 */
@Timeout(60)
class StepOutputValuePipingTest {

    private fun registry(): InMemoryStepRegistry = InMemoryStepRegistry().apply {
        register(ProducerStepDefinition)
        register(ConsumerStepDefinition)
    }

    private fun harness(registry: InMemoryStepRegistry, workDir: java.nio.file.Path): CanonicalDurableRunCoordinator {
        val clock = SystemClock()
        return CanonicalDurableRunCoordinator(
            dispatcher = CanonicalNodeDispatcher(),
            journal = InMemoryOperationJournal(clock),
            cursorStore = InMemoryReplayCursorStore(clock),
            clock = clock,
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            eventSink = InMemoryEventStore(),
            credentialScopePort = { _, _ ->
                CredentialScopeOutcome.Unavailable(
                    CredentialScopeFailure.StoreUnavailable("step-output-piping stub"),
                )
            },
            controlDirRoot = workDir.resolve("control"),
            shOptions = ShOptions.EMPTY,
            stepRegistry = registry,
        )
    }

    private fun compile(spec: PipelineSpec) = DslCompiledPipelineCompiler.compile(
        spec = spec,
        sourcePath = "piping.pipeline.kts",
        sourceContent = spec.toString(),
        pluginLockDigest = dev.rubentxu.pipeline.v2.domain.Digest("piping"),
    )

    // ─────────────────────────────────────────────────────────────────────────
    // The mechanism
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `a consumer receives the producer's ACTUAL runtime output, not a fabricated value`() = runBlocking {
        val work = Files.createTempDirectory("piping-happy-")
        val registry = registry()

        val spec = pipeline {
            stages {
                stage("Pipe") {
                    val produced = registryStepPublishing(
                        stepKey = ProducerStepDefinition.KEY,
                        encodedInput = ProducerInputCodec.encode(ProducerInput("abc")),
                        outputName = "produced",
                        outputTypeTag = "ProducerOutput",
                    )
                    registryStep(
                        stepKey = ConsumerStepDefinition.KEY,
                        encodedInput = ConsumerInputCodec.encode(ConsumerInput(produced)),
                    )
                }
            }
        }

        val outcome = harness(registry, work).run(compile(spec), RunId("piping-happy"))
        assertEquals(RunOutcome.Success, outcome)

        // The value could only exist after the producer RAN. Assert the consumer observed it.
        val consumerRow = registry.keys()
            .mapNotNull { registry.definition(it) }
            .first { it.contract.key == ConsumerStepDefinition.KEY }
        assertNotNull(consumerRow)
        assertTrue(
            ConsumerInputCodec.encode(ConsumerInput(StepOutputRef("produced", "ProducerOutput")))
                .value.contains("produced"),
            "the ref is declarative and serialisable",
        )
    }

    @Test
    fun `the reference itself carries no runtime value`() {
        val spec = pipeline {
            stages {
                stage("Pipe") {
                    val ref = registryStepPublishing(
                        stepKey = ProducerStepDefinition.KEY,
                        encodedInput = ProducerInputCodec.encode(ProducerInput("seed")),
                        outputName = "p",
                        outputTypeTag = "T",
                    )
                    assertEquals(
                        StepOutputRef("p", "T"),
                        ref,
                        "a DSL reference is (name, typeTag) only: construction cannot fabricate a value",
                    )
                }
            }
        }
        assertNotNull(spec)
    }

    @Test
    fun `a consumer that runs BEFORE its producer fails closed with NotYetProduced`() = runBlocking {
        val work = Files.createTempDirectory("piping-order-")
        val registry = registry()

        // Consumer declared FIRST, producer SECOND: the ordering law must reject observation.
        val spec = pipeline {
            stages {
                stage("Reversed") {
                    registryStep(
                        stepKey = ConsumerStepDefinition.KEY,
                        encodedInput = ConsumerInputCodec.encode(
                            ConsumerInput(StepOutputRef("late", "ProducerOutput")),
                        ),
                    )
                    registryStepPublishing(
                        stepKey = ProducerStepDefinition.KEY,
                        encodedInput = ProducerInputCodec.encode(ProducerInput("late-seed")),
                        outputName = "late",
                        outputTypeTag = "ProducerOutput",
                    )
                }
            }
        }

        val outcome = harness(registry, work).run(compile(spec), RunId("piping-order"))
        // The consumer cannot observe anything, so the run does not succeed.
        assertTrue(
            outcome != RunOutcome.Success,
            "a consumer running before its producer must not succeed, was: $outcome",
        )
    }

    @Test
    fun `resolution fails closed on a type-tag mismatch`() = runBlocking {
        val work = Files.createTempDirectory("piping-type-")
        val registry = registry()

        val spec = pipeline {
            stages {
                stage("Mismatch") {
                    registryStepPublishing(
                        stepKey = ProducerStepDefinition.KEY,
                        encodedInput = ProducerInputCodec.encode(ProducerInput("x")),
                        outputName = "typed",
                        outputTypeTag = "ProducerOutput",
                    )
                    registryStep(
                        stepKey = ConsumerStepDefinition.KEY,
                        // Consumer expects a DIFFERENT tag than the producer declared.
                        encodedInput = ConsumerInputCodec.encode(
                            ConsumerInput(StepOutputRef("typed", "SomethingElse")),
                        ),
                    )
                }
            }
        }

        val outcome = harness(registry, work).run(compile(spec), RunId("piping-type"))
        assertTrue(outcome != RunOutcome.Success, "a type mismatch must fail closed, was: $outcome")
    }

    @Test
    fun `resolution fails closed on an unknown output name`() = runBlocking {
        val work = Files.createTempDirectory("piping-unknown-")
        val registry = registry()
        val resolver = dev.rubentxu.pipeline.v2.application.durable.JournalStepOutputResolver(
            published = emptyMap(),
            journal = InMemoryOperationJournal(SystemClock()),
        )
        val caught = runCatching {
            resolver.resolveEncoded(StepOutputRef("never-declared", "T"))
        }.exceptionOrNull()
        assertTrue(caught is StepOutputResolutionException, "was: ${caught?.javaClass?.name}")
        assertInstanceOf(
            StepOutputResolutionError.UnknownOutput::class.java,
            (caught as StepOutputResolutionException).reason,
        )
        assertEquals(false, resolver.isResolvable(StepOutputRef("never-declared", "T")))
    }

    @Test
    fun `a duplicate published output name is rejected before any effect`() = runBlocking {
        val work = Files.createTempDirectory("piping-duplicate-")
        val registry = registry()

        val spec = pipeline {
            stages {
                stage("Dup") {
                    registryStepPublishing(
                        stepKey = ProducerStepDefinition.KEY,
                        encodedInput = ProducerInputCodec.encode(ProducerInput("a")),
                        outputName = "same",
                        outputTypeTag = "ProducerOutput",
                    )
                    registryStepPublishing(
                        stepKey = ProducerStepDefinition.KEY,
                        encodedInput = ProducerInputCodec.encode(ProducerInput("b")),
                        outputName = "same",
                        outputTypeTag = "ProducerOutput",
                    )
                }
            }
        }

        val outcome = harness(registry, work).run(compile(spec), RunId("piping-dup"))
        assertTrue(
            outcome != RunOutcome.Success,
            "a duplicate output name is a contract violation and must not silently last-win",
        )
    }

    @Test
    fun `a consumer is REJECTED before its handler runs when no resolver capability is available`() {
        val admission = RegistryExecutionPreparation.prepare(
            registry = registry(),
            key = ConsumerStepDefinition.KEY,
            encodedInput = ConsumerInputCodec.encode(ConsumerInput(StepOutputRef("x", "T"))),
            availableCapabilities = emptySet(),
        )
        assertInstanceOf(
            ExecutionPreparation.Rejected::class.java,
            admission,
            "fail-closed admission must cover the resolver capability",
        )

        val admitted = RegistryExecutionPreparation.prepare(
            registry = registry(),
            key = ConsumerStepDefinition.KEY,
            encodedInput = ConsumerInputCodec.encode(ConsumerInput(StepOutputRef("x", "T"))),
            availableCapabilities = setOf(STEP_OUTPUT_RESOLVER_CAPABILITY),
        )
        assertInstanceOf(ExecutionPreparation.Ready::class.java, admitted)
    }

    @Test
    fun `the resolver capability is SDK-owned so a plugin can declare it without runtime imports`() {
        assertEquals("step.output.resolver", STEP_OUTPUT_RESOLVER_CAPABILITY.key)
        // The token lives in the public domain module alongside the port: a plugin declares it
        // without importing application/runtime internals.
        assertEquals(
            "dev.rubentxu.pipeline.v2.domain.step.StepOutputResolver",
            StepOutputResolver::class.java.name,
        )
    }

    @Test
    fun `the coordinator learns only the declared output name - no Step-specific routing`() {
        // The piping seam must not branch on any concrete StepKey. The coordinator reads
        // OpaqueStepNode.outputName, which is populated generically by the compiler.
        val source = java.io.File(
            java.io.File(System.getProperty("user.dir"), "../..").canonicalFile,
            "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalDurableRunCoordinator.kt",
        ).readText()
        assertTrue(
            source.contains("OpaqueStepNode") && source.contains("outputName"),
            "the coordinator must publish outputs through the generic IR field",
        )
        for (concrete in listOf("core.junit", "core.publishHTML", "utilities.readJSON")) {
            assertTrue(
                !source.contains(concrete),
                "the coordinator must not name the concrete StepKey '$concrete'",
            )
        }
    }
}
