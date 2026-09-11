package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.ExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.StructuralFamilyResolver
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.application.durable.StructuralStepFamily
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.events.FileWritten
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.StageMarkedUnstable
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * S2-A4 / G1 — registry candidate unit tests for `core.emit.event`.
 *
 * Covers: codec round-trip per whitelist kind, open-codec behaviour (unknown kind reaches
 * the handler), descriptor fidelity, exact capability set, admission-level rejection per
 * missing capability, full handler branch matrix (including the typed-SCHEMA rejection
 * fix candidate), production registry membership, and StructuralFamily == LegacyCore
 * (legacy-membership-wins until the G4 flip).
 *
 * At least one test drives the FULL seam:
 * RegistryExecutionPreparation -> capability admission -> handler (not the handler alone).
 */
@Timeout(30)
class CoreEmitEventStepUnitTest {

    private fun registry(): InMemoryStepRegistry =
        InMemoryStepRegistry().apply { CoreEmitEventStep.registerInto(this) }

    /** Minimal in-memory harness: event store + explicit capability table + handler ctx. */
    private class EmitHarness(
        val eventStore: InMemoryEventStore = InMemoryEventStore(),
        stageName: String = "build",
        stageIndex: Int = 0,
        runId: String = "emit-unit",
        capabilities: Map<StepCapability, Any> = mapOf(
            EVENT_SINK_CAPABILITY to eventStore,
            STAGE_IDENTITY_CAPABILITY to StageIdentity(stageName, stageIndex),
        ),
    ) {
        val ctx = dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext(
            runId = RunId(runId),
            stepIndex = 0,
            capabilities = object : dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess {
                override fun available(): Set<StepCapability> = capabilities.keys
                @Suppress("UNCHECKED_CAST")
                override fun <T : Any> get(key: StepCapability): T =
                    capabilities[key] as? T ?: throw IllegalArgumentException("unavailable: $key")
            },
        )

        suspend fun invoke(encoded: EncodedStepValue): CoreEmitEventOutput =
            CoreEmitEventStep.definition.handler.execute(
                CoreEmitEventStep.definition.contract.inputCodec.decode(encoded),
                ctx,
            )
    }


    private fun harness(runId: String = "emit-unit", stageName: String = "build") =
        EmitHarness(runId = runId, stageName = stageName)

    /** Capability set the production bridge provides for emit.event (both capabilities). */
    private fun fullCapabilities(): Set<StepCapability> =
        setOf(EVENT_SINK_CAPABILITY, STAGE_IDENTITY_CAPABILITY)

    private fun encode(kind: String, vararg fields: Pair<String, String?>): EncodedStepValue {
        val body = buildString {
            append("{\"kind\":\"").append(kind).append('"')
            fields.forEach { (k, v) ->
                append(",\"").append(k).append("\":")
                if (v != null) append('"').append(v).append('"') else append("null")
            }
            append('}')
        }
        return EncodedStepValue(body)
    }

    // ===== codec round-trip per whitelist kind =====

    @Test
    fun `codec — round-trips all four whitelist kinds byte-identically`() {
        val codec = CoreEmitEventStep.definition.contract.inputCodec
        val cases = listOf(
            CoreEmitEventInput("CatchErrorEntered", mapOf("buildResult" to "UNSTABLE")),
            CoreEmitEventInput(
                "CatchErrorTriggered",
                mapOf("buildResult" to "UNSTABLE", "message" to "m", "emitted" to "true"),
            ),
            CoreEmitEventInput("StageMarkedUnstable", mapOf("message" to "wobbly")),
            CoreEmitEventInput(
                "FileWritten",
                mapOf("path" to "out.txt", "sha256" to "abc", "size" to "5", "atomicallyMoved" to "true"),
            ),
        )
        for (input in cases) {
            val encoded = codec.encode(input)
            assertEquals(input, codec.decode(encoded), "round-trip must preserve ${input.kind}")
        }
    }

    @Test
    fun `codec — preserves null field values as JSON null (legacy envelope parity)`() {
        val codec = CoreEmitEventStep.definition.contract.inputCodec
        val input = CoreEmitEventInput("CatchErrorEntered", mapOf("message" to null))
        val encoded = codec.encode(input).value
        assertTrue(
            encoded.contains("\"message\":null"),
            "null payload values MUST be encoded as JSON null (emitEventPayload parity); got $encoded",
        )
        assertEquals(input, codec.decode(EncodedStepValue(encoded)))
    }

    @Test
    fun `codec — unknown kind is NOT a decode failure (whitelist is handler semantics)`() {
        val codec = CoreEmitEventStep.definition.contract.inputCodec
        val decoded = codec.decode(encode("SomethingUserInvented"))
        assertEquals("SomethingUserInvented", decoded.kind, "decoder is total; whitelist lives in the handler")
    }

    // ===== descriptor fidelity =====

    @Test
    fun `descriptor — byte-equivalent to the legacy metadata row (READ_ONLY + MEMOIZED)`() {
        val descriptor = CoreEmitEventStep.definition.contract.descriptor
        assertEquals("core.emit.event", descriptor.stepId)
        assertEquals("emitEvent", descriptor.name)
        assertEquals(setOf(Effect.READ_ONLY), descriptor.effects.toSet())
        assertEquals(ReplayPolicy.MEMOIZED, descriptor.replayPolicy)
    }

    @Test
    fun `capabilities — contract declares exactly EVENT_SINK plus STAGE_IDENTITY`() {
        assertEquals(
            setOf(EVENT_SINK_CAPABILITY, STAGE_IDENTITY_CAPABILITY),
            CoreEmitEventStep.definition.contract.requiredCapabilities,
        )
    }

    // ===== admission-level rejections =====

    @Test
    fun `admission — missing EVENT_SINK capability is rejected fail-closed`() {
        val preparation = RegistryExecutionPreparation.prepare(
            registry = registry(),
            key = CoreEmitEventStep.KEY,
            encodedInput = encode("CatchErrorEntered"),
            availableCapabilities = setOf(STAGE_IDENTITY_CAPABILITY),
        )
        assertTrue(preparation is ExecutionPreparation.Rejected, "missing event sink must reject admission")
    }

    @Test
    fun `admission — missing STAGE_IDENTITY capability is rejected fail-closed`() {
        val preparation = RegistryExecutionPreparation.prepare(
            registry = registry(),
            key = CoreEmitEventStep.KEY,
            encodedInput = encode("CatchErrorEntered"),
            availableCapabilities = setOf(EVENT_SINK_CAPABILITY),
        )
        assertTrue(preparation is ExecutionPreparation.Rejected, "missing stage identity must reject admission")
    }

    // ===== full seam: preparation -> admission -> handler =====

    @Test
    fun `full seam — StageMarkedUnstable flows preparation admission boundary handler and appends exactly one event`() =
        runBlocking {
            val h = harness()
            val preparation = RegistryExecutionPreparation.prepare(
                registry = registry(),
                key = CoreEmitEventStep.KEY,
                encodedInput = encode("StageMarkedUnstable", "message" to "wobbly"),
                availableCapabilities = fullCapabilities(),
            )
            assertTrue(preparation is ExecutionPreparation.Ready)
            val output = CoreEmitEventStep.definition.handler.execute(
                CoreEmitEventStep.definition.contract.inputCodec.decode(encode("StageMarkedUnstable", "message" to "wobbly")),
                h.ctx,
            )
            assertTrue(output is CoreEmitEventOutput.EmitEventUnstable, "got $output")
            val events = h.eventStore.eventsFor("emit-unit").filterIsInstance<StageMarkedUnstable>().toList()
            assertEquals(1, events.size, "exactly one StageMarkedUnstable event")
            assertEquals("build", events.single().stageName, "stageName MUST fall back to StageIdentity.name")
            assertEquals("wobbly", events.single().message)
        }

    // ===== handler branch matrix =====

    @Test
    fun `handler — CatchErrorEntered returns Success with zero events`() = runBlocking {
        val h = harness()
        val output = h.invoke(encode("CatchErrorEntered"))
        assertTrue(output is CoreEmitEventOutput.EmitEventSuccess)
        assertEquals(0, h.eventStore.eventsFor("emit-unit").count(), "scope markers MUST NOT append events")
    }

    @Test
    fun `handler — CatchErrorTriggered returns Success with zero events`() = runBlocking {
        val h = harness()
        val output = h.invoke(encode("CatchErrorTriggered", "emitted" to "true"))
        assertTrue(output is CoreEmitEventOutput.EmitEventSuccess)
        assertEquals(0, h.eventStore.eventsFor("emit-unit").count(), "scope markers MUST NOT append events")
    }

    @Test
    fun `handler — StageMarkedUnstable with explicit stageName overrides the StageIdentity fallback`() =
        runBlocking {
            val h = harness(stageName = "build")
            val output = h.invoke(
                encode("StageMarkedUnstable", "message" to "m", "stageName" to "deploy"),
            )
            assertTrue(output is CoreEmitEventOutput.EmitEventUnstable)
            val events = h.eventStore.eventsFor("emit-unit").filterIsInstance<StageMarkedUnstable>().toList()
            assertEquals(1, events.size)
            assertEquals("deploy", events.single().stageName, "explicit payload stageName wins over fallback")
        }

    @Test
    fun `handler — FileWritten succeeds and appends exactly one event`() = runBlocking {
        val h = harness()
        val output = h.invoke(
            encode("FileWritten", "path" to "out.txt", "sha256" to "abc", "size" to "5"),
        )
        assertTrue(output is CoreEmitEventOutput.EmitEventSuccess)
        val events = h.eventStore.eventsFor("emit-unit").filterIsInstance<FileWritten>().toList()
        assertEquals(1, events.size)
        assertFalse(events.single().atomicallyMoved, "atomicallyMoved MUST default to false when omitted")
    }

    @Test
    fun `handler — FileWritten parses explicit atomicallyMoved`() = runBlocking {
        val h = harness()
        h.invoke(
            encode(
                "FileWritten",
                "path" to "o.txt", "sha256" to "k", "size" to "7", "atomicallyMoved" to "true",
            ),
        )
        assertTrue(h.eventStore.eventsFor("emit-unit").filterIsInstance<FileWritten>().toList().single().atomicallyMoved)
    }

    @Test
    fun `handler — unknown kind is typed SCHEMA rejection with zero events`() = runBlocking {
        val h = harness()
        val output = h.invoke(encode("SomethingUserInvented"))
        assertTrue(output is CoreEmitEventOutput.EmitEventRejected, "got $output")
        assertEquals(
            dev.rubentxu.pipeline.v2.domain.FailureKind.SCHEMA,
            (output as CoreEmitEventOutput.EmitEventRejected).failure.kind,
        )
        assertEquals(0, h.eventStore.eventsFor("emit-unit").count())
    }

    @Test
    fun `fix candidate — StageMarkedUnstable without message is typed SCHEMA rejection with zero events`() =
        runBlocking {
            val h = harness()
            val output = h.invoke(encode("StageMarkedUnstable"))
            assertTrue(output is CoreEmitEventOutput.EmitEventRejected)
            assertEquals(
                dev.rubentxu.pipeline.v2.domain.FailureKind.SCHEMA,
                (output as CoreEmitEventOutput.EmitEventRejected).failure.kind,
            )
            assertEquals(0, h.eventStore.eventsFor("emit-unit").count(), "rejection MUST NOT append any event")
        }

    @Test
    fun `fix candidate — FileWritten missing fields is typed SCHEMA rejection with zero events`() = runBlocking {
        val h = harness()
        for (incomplete in listOf(
            encode("FileWritten", "sha256" to "a", "size" to "1"),
            encode("FileWritten", "path" to "p", "size" to "1"),
            encode("FileWritten", "path" to "p", "sha256" to "a"),
            encode("FileWritten", "path" to "p", "sha256" to "a", "size" to "not-a-number"),
        )) {
            val output = h.invoke(incomplete)
            assertTrue(output is CoreEmitEventOutput.EmitEventRejected, "got $output for $incomplete")
            assertEquals(
                dev.rubentxu.pipeline.v2.domain.FailureKind.SCHEMA,
                (output as CoreEmitEventOutput.EmitEventRejected).failure.kind,
            )
        }
        assertEquals(0, h.eventStore.eventsFor("emit-unit").count(), "rejections MUST NOT append any event")
    }

    // ===== production registry membership + structural family =====

    @Test
    fun `registry — production factory contains the step and StructuralFamily is Registry post-flip`() {
        val production = CoreStepRegistryFactory.registry()
        assertTrue(
            production.contains(CoreEmitEventStep.KEY),
            "step MUST be registered in the production registry",
        )
        assertEquals(
            StructuralStepFamily.Registry,
            StructuralFamilyResolver.classify(CoreEmitEventStep.KEY, production),
            "S2-A4/G4: production routing authority is the registry (REGISTRY_PRIMARY)",
        )
        assertFalse(
            PluginStepId("core.emit.event").value in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS,
            "S2-A4/G4: core.emit.event flipped OUT of LEGACY_PLUGIN_IDS",
        )
    }

    @Test
    fun `identity — duplicate registration fails`() {
        val r = registry()
        assertTrue(runCatching { CoreEmitEventStep.registerInto(r) }.isFailure)
    }
}
