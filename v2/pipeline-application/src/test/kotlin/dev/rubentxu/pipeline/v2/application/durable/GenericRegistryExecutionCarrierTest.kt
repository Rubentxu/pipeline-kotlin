package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CoreEchoStep
import dev.rubentxu.pipeline.v2.application.EVENT_SINK_CAPABILITY
import dev.rubentxu.pipeline.v2.application.EchoInput
import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.sdk.runtime.echo
import dev.rubentxu.pipeline.v2.sdk.StepContext
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files

/**
 * LB-02 / G3-A1 — generic carrier extension of the registry execution model.
 *
 * **Goal**: prove the [RegistryExecutionResult] carrier belongs to the
 * registry execution model as a whole, NOT to `core.sh`. We exercise the
 * carrier against `core.echo` and against a tiny ad-hoc `Input -> Unit`
 * Step to demonstrate:
 *
 *  - The carrier carries BOTH [StepOutcome] AND [EncodedStepValue]?.
 *  - A handler that returns a non-Unit value produces a populated carrier.
 *  - A handler that returns `Unit` produces a carrier with `encodedOutput = null`.
 *  - A thrown handler produces a `StepOutcome.Failure` carrier with `encodedOutput = null`.
 *  - The carrier flows back to a caller (the durable coordinator's projection
 *    point) without losing either responsibility.
 *
 * No Sh, no `CanonicalCoreStepCommand.Sh`, no `RecoveryPolicy`, no journal
 * row: this slice is a pure step-boundary extension.
 *
 * @see docs/v2/00-context/LB02_TYPED_OUTPUT_DECISION.md
 */
@Timeout(15)
class GenericRegistryExecutionCarrierTest {

    /** A fixture `Input -> Unit` Step: proves the carrier handles void outputs correctly. */
    private data class UnitFixtureInput(val payload: String)

    private val unitFixtureKey = PluginStepId("test.unit-fixture")

    private val unitFixture: StepDefinition<UnitFixtureInput, Unit> = object : StepDefinition<UnitFixtureInput, Unit> {
        override val contract: StepContract<UnitFixtureInput, Unit> = StepContract(
            key = unitFixtureKey,
            descriptor = StepDescriptor(
                stepId = "test.unit-fixture",
                name = "unit-fixture",
                configRef = "",
                executionLocation = ExecutionLocation.AGENT,
                effects = listOf(Effect.READ_ONLY),
                replayPolicy = ReplayPolicy.MEMOIZED,
            ),
            inputCodec = object : StepCodec<UnitFixtureInput> {
                override fun encode(value: UnitFixtureInput) = EncodedStepValue(
                    Json.encodeToString(JsonObject.serializer(), buildJsonObject {
                        put("kind", JsonPrimitive("unit-fixture"))
                        put("payload", JsonPrimitive(value.payload))
                    }),
                )

                override fun decode(encoded: EncodedStepValue): UnitFixtureInput {
                    val obj = Json.parseToJsonElement(encoded.value).jsonObject
                    require(obj["kind"]?.jsonPrimitive?.content == "unit-fixture") {
                        "unit-fixture payload kind must be 'unit-fixture'"
                    }
                    return UnitFixtureInput(obj.getValue("payload").jsonPrimitive.content)
                }
            },
            outputCodec = object : StepCodec<Unit> {
                override fun encode(value: Unit): EncodedStepValue = EncodedStepValue("\"\"")

                override fun decode(encoded: EncodedStepValue): Unit = Unit
            },
            requiredCapabilities = emptySet(),
        )

        override val handler: StepHandler<UnitFixtureInput, Unit> =
            StepHandler { _, _ -> /* pure void */ }
    }

    /** A failing fixture Step: proves a thrown handler produces a Failure carrier. */
    private data class FailingInput(val payload: String)

    private val failingFixtureKey = PluginStepId("test.failing-fixture")

    private val failingFixture: StepDefinition<FailingInput, String> = object : StepDefinition<FailingInput, String> {
        override val contract: StepContract<FailingInput, String> = StepContract(
            key = failingFixtureKey,
            descriptor = StepDescriptor(
                stepId = "test.failing-fixture",
                name = "failing-fixture",
                configRef = "",
                executionLocation = ExecutionLocation.AGENT,
                effects = listOf(Effect.READ_ONLY),
                replayPolicy = ReplayPolicy.MEMOIZED,
            ),
            inputCodec = object : StepCodec<FailingInput> {
                override fun encode(value: FailingInput) = EncodedStepValue(
                    Json.encodeToString(JsonObject.serializer(), buildJsonObject {
                        put("kind", JsonPrimitive("failing-fixture"))
                        put("payload", JsonPrimitive(value.payload))
                    }),
                )

                override fun decode(encoded: EncodedStepValue): FailingInput {
                    val obj = Json.parseToJsonElement(encoded.value).jsonObject
                    return FailingInput(obj.getValue("payload").jsonPrimitive.content)
                }
            },
            outputCodec = object : StepCodec<String> {
                override fun encode(value: String) = EncodedStepValue(value)

                override fun decode(encoded: EncodedStepValue): String = encoded.value
            },
            requiredCapabilities = emptySet(),
        )

        override val handler: StepHandler<FailingInput, String> =
            StepHandler { _, _ -> throw IllegalStateException("fixture: handler contract violated for test") }
    }

    private fun buildContext(): CanonicalRuntimeContext {
        // Echo's contract requires EVENT_SINK; for unit/failing fixtures, none.
        // The context here is structural-only and is consumed through CanonicalRuntimeCapabilityAccess.
        return CanonicalRuntimeContext(
            opId = OpId("g3-a1-fixture", 0, 0),
            runId = "g3-a1-fixture",
            stageName = "fixture",
            stageIndex = 0,
            stepIndex = 0,
            shOptions = dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions.EMPTY,
            controlDirRoot = Files.createTempDirectory("g3-a1-"),
            eventSink = InMemoryEventStore(),
        )
    }

    // -------- Carrier carries StepOutcome + EncodedStepValue? --------

    @Test
    fun `carrier — echo returns StepOutcome Success and a populated EncodedStepValue`() = runBlocking {
        // First the canonical existing echo fixture, exercised through the
        // new coexecute path. Proves the carrier works against an existing,
        // CERTIFIED step family.
        val registry = InMemoryStepRegistry().apply { CoreEchoStep.registerInto(this) }
        val ctx = buildContext()
        val prepared: PreparedRegistryExecution = RegistryExecutionPreparation.prepare(
            registry = registry,
            key = CoreEchoStep.KEY,
            encodedInput = CoreEchoStep.definition.contract.inputCodec.encode(EchoInput("carrier-ok")),
            availableCapabilities = setOf(EVENT_SINK_CAPABILITY),
        ).let {
            require(it is ExecutionPreparation.Ready) { "admission must succeed: $it" }
            (it as ExecutionPreparation.Ready).prepared as PreparedRegistryExecution
        }
        val result = RegistryExecutionBoundary.coexecute(prepared, ctx)
        assertEquals(StepOutcome.Success, result.outcome, "carrier.outcome must be Success")
        assertNotNull(result.encodedOutput, "echo's typed String output must cross the carrier")
        // The echo's outputCodec is opaque raw-text; the carrier should keep it raw.
        assertEquals("carrier-ok\n", result.encodedOutput!!.value)
    }

    @Test
    fun `Step returning Unit produces encodedOutput null but Success outcome`() = runBlocking {
        // A handler returning Unit (the void Step) must reduce to encodedOutput = null,
        // NOT a String "kotlin.Unit". The boundary classifies Unit specially.
        val registry = InMemoryStepRegistry().apply { register(unitFixture) }
        val ctx = buildContext()
        val prepared: PreparedRegistryExecution = RegistryExecutionPreparation.prepare(
            registry = registry,
            key = unitFixtureKey,
            encodedInput = unitFixture.contract.inputCodec.encode(UnitFixtureInput("anything")),
            availableCapabilities = emptySet(),
        ).let {
            require(it is ExecutionPreparation.Ready) { "admission must succeed: $it" }
            (it as ExecutionPreparation.Ready).prepared as PreparedRegistryExecution
        }
        val result = RegistryExecutionBoundary.coexecute(prepared, ctx)
        assertEquals(StepOutcome.Success, result.outcome)
        assertEquals(null, result.encodedOutput, "Unit-returning handler must NOT cross a typed-output value")
    }

    @Test
    fun `thrown handler produces Failure outcome and null encodedOutput`() = runBlocking {
        // Echo proves a Success carrier. This proves a Failure carrier: a thrown
        // handler (adapter defect) surfaces as StepOutcome.Failure(ENGINE) with
        // encodedOutput = null because there is no successful terminal to encode.
        val registry = InMemoryStepRegistry().apply { register(failingFixture) }
        val ctx = buildContext()
        val prepared: PreparedRegistryExecution = RegistryExecutionPreparation.prepare(
            registry = registry,
            key = failingFixtureKey,
            encodedInput = failingFixture.contract.inputCodec.encode(FailingInput("x")),
            availableCapabilities = emptySet(),
        ).let {
            require(it is ExecutionPreparation.Ready) { "admission must succeed: $it" }
            (it as ExecutionPreparation.Ready).prepared as PreparedRegistryExecution
        }
        val result = RegistryExecutionBoundary.coexecute(prepared, ctx)
        val failure = result.outcome as StepOutcome.Failure
        assertEquals(dev.rubentxu.pipeline.v2.domain.FailureKind.ENGINE, failure.failure.kind)
        assertEquals(null, result.encodedOutput, "Failure carrier must NOT carry a typed-output value")
    }

    // -------- Carrier is what CommonExecutionBoundary.execute returns too --------

    @Test
    fun `adaptBoundary_execute returns CommonExecutionResult with outcome and registry encoded output`() = runBlocking {
        // A3: CommonExecutionBoundary.execute() is the SINGLE seam. The registry path
        // returns a CommonExecutionResult carrying both the closed StepOutcome and the
        // (optional) encoded typed output. Echo's typed String crosses via EVENT_SINK,
        // not via this slot, so encodedOutput is null here.
        val registry = InMemoryStepRegistry().apply { CoreEchoStep.registerInto(this) }
        val ctx = buildContext()
        val boundary = RegistryExecutionBoundary.adapt()
        val prepared: PreparedRegistryExecution = RegistryExecutionPreparation.prepare(
            registry = registry,
            key = CoreEchoStep.KEY,
            encodedInput = CoreEchoStep.definition.contract.inputCodec.encode(EchoInput("step-outcome-only")),
            availableCapabilities = setOf(EVENT_SINK_CAPABILITY),
        ).let {
            require(it is ExecutionPreparation.Ready) { "admission must succeed: $it" }
            (it as ExecutionPreparation.Ready).prepared as PreparedRegistryExecution
        }
        // The carrier is the single seam; the registry path produces BOTH the closed outcome
        // AND the encoded typed output. Echo's typed String is encoded through the contract's
        // outputCodec and surfaces here as a non-null EncodedStepValue (mirroring the EVENT_SINK
        // event it also emits — both are valid projections of the same typed O).
        val executionResult = boundary.execute(prepared, ctx)
        assertEquals(StepOutcome.Success, executionResult.outcome)
        // Echo handler returns "input\n" through its String outputCodec; carrier holds the encoded value.
        assertEquals(EncodedStepValue("step-outcome-only\n"), executionResult.encodedOutput)
    }

    // -------- Sanity: the registry mirror is unaffected --------

    @Test
    fun `seamed router — legacy family still routes through Pre-existing CommonExecutionBoundary`() = runBlocking {
        // Belt-and-braces: the existing CommonExecutionBoundary shape remains; the
        // carrier extension is registry-only.
        val registry = InMemoryStepRegistry().apply { CoreEchoStep.registerInto(this) }
        val ctx = buildContext()
        val boundary = RegistryExecutionBoundary.adapt()
        val echoPrepared: PreparedRegistryExecution = RegistryExecutionPreparation.prepare(
            registry = registry,
            key = CoreEchoStep.KEY,
            encodedInput = CoreEchoStep.definition.contract.inputCodec.encode(EchoInput("router")),
            availableCapabilities = setOf(EVENT_SINK_CAPABILITY),
        ).let {
            require(it is ExecutionPreparation.Ready) { "admission must succeed: $it" }
            (it as ExecutionPreparation.Ready).prepared as PreparedRegistryExecution
        }
        val echoResult = boundary.execute(echoPrepared, ctx)
        // The boundary throws on legacy-family prepared, as the docstring states.
        val legacyOutcome = runCatching {
            boundary.execute(
                PreparedLegacyExecution(dev.rubentxu.pipeline.v2.application.CanonicalCoreStepCommand.Pwd()),
                ctx,
            )
        }
        assertTrue(legacyOutcome.isFailure, "RegistryExecutionBoundary must still refuse legacy-family PreparedExecution")
        assertEquals(StepOutcome.Success, echoResult.outcome)
    }

    // -------- Carrier is the typed-output / StepOutcome pair, not two Steps --------

    @Test
    fun `carrier — CommonExecutionResult is exactly one Outcome and one Optional EncodedStepValue`() {
        // Sanity on the structural shape: a data class with two fields, the second
        // one nullable. If a future contributor adds `rawOutput: String?` or any
        // ad-hoc slot to the carrier, this test fails loudly.
        val outcome: StepOutcome = StepOutcome.Success
        val encoded: EncodedStepValue? = EncodedStepValue("any-shape")
        val carrier = CommonExecutionResult(outcome = outcome, encodedOutput = encoded)
        // Structural equality check: two carriers with same fields are equal.
        val same = CommonExecutionResult(StepOutcome.Success, EncodedStepValue("any-shape"))
        assertEquals(carrier, same)
        // Encoding with null reduces to outcome-only:
        val noOutput = CommonExecutionResult(StepOutcome.Success, null)
        assertNotNull(noOutput.outcome)
        assertEquals(null, noOutput.encodedOutput)
    }
}
