package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepCommand
import dev.rubentxu.pipeline.v2.application.durable.CanonicalEmitEventDispatchContext
import dev.rubentxu.pipeline.v2.application.durable.CanonicalEmitEventNodeDispatcher
import dev.rubentxu.pipeline.v2.application.durable.ExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.StructuralFamilyResolver
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.FileWritten
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.StageMarkedUnstable
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ReplayDecision
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * S2-A4 / G2 — Differential Contract Freeze for `core.emit.event`.
 *
 * Drives the LEGACY dispatcher ([CanonicalEmitEventNodeDispatcher]) and the REGISTRY
 * candidate ([CoreEmitEventStep]) against the SAME event store and asserts semantic
 * parity, EXCEPT the explicitly approved deltas frozen by this gate:
 *
 *  - APPROVED FIX: valid-kind but malformed payloads (missing mandatory fields,
 *    non-numeric `size`) move from untyped exception/INFRASTRUCTURE to typed
 *    `FailureKind.SCHEMA` with ZERO events.
 *  - APPROVED ARCHITECTURAL DELTA: implicit EventSink / runtime stage access becomes
 *    static declared capabilities (EVENT_SINK + STAGE_IDENTITY), fail-closed at
 *    admission. Static per StepDefinition; no per-kind dynamic capability sets.
 *
 * Semantic parity comparison IGNORES volatile identity: eventId, occurredAt, sequence.
 * Compared: event type, runId, stageName, message, path, sha256, size, atomicallyMoved,
 * event cardinality, StepOutcome.
 *
 * Frozen law: `wire/decode validity != emit.event semantic validity` — unknown kind
 * decodes fine and fails in the HANDLER as SCHEMA. atomicallyMoved parsing is kept
 * EXACTLY legacy (`toBooleanStrictOrNull() ?: false`): "TRUE"/"yes"/"1"/garbage -> false.
 * No NEW validations beyond the approved fix (blank-string tolerance, size sign, unknown
 * field tolerance are all inherited verbatim).
 */
@Timeout(30)
class CoreEmitEventDifferentialContractTest {

    // ===== dual harness: legacy dispatcher + registry candidate, same store =====

    private class DualHarness(
        val store: InMemoryEventStore = InMemoryEventStore(),
        val runId: String = "diff-run",
        val stageName: String = "build",
    ) {
        val sink: EventSink = store
        val legacyCtx = CanonicalEmitEventDispatchContext(runId, stageName, sink)
        val legacyDispatcher = CanonicalEmitEventNodeDispatcher()

        val candidateCtx = dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext(
            runId = RunId(runId),
            stepIndex = 0,
            capabilities = object : dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess {
                override fun available(): Set<StepCapability> =
                    setOf(EVENT_SINK_CAPABILITY, STAGE_IDENTITY_CAPABILITY)
                @Suppress("UNCHECKED_CAST")
                override fun <T : Any> get(key: StepCapability): T = when (key) {
                    EVENT_SINK_CAPABILITY -> sink
                    STAGE_IDENTITY_CAPABILITY -> StageIdentity(stageName, 0)
                    else -> throw IllegalArgumentException("unavailable: $key")
                } as T
            },
        )

        suspend fun legacy(kind: String, payload: Map<String, String?> = emptyMap()): StepOutcome =
            legacyDispatcher.dispatch(CanonicalCoreStepCommand.EmitEvent(kind, payload), legacyCtx)

        suspend fun candidate(kind: String, payload: Map<String, String?> = emptyMap()): StepOutcome {
            val encoded = CoreEmitEventStep.definition.contract.inputCodec
                .encode(CoreEmitEventInput(kind, payload))
            val output = CoreEmitEventStep.definition.handler.execute(
                CoreEmitEventStep.definition.contract.inputCodec.decode(encoded),
                candidateCtx,
            )
            return output.outcome
        }
    }

    private suspend fun DualHarness.assertParity(kind: String, payload: Map<String, String?> = emptyMap()) {
        val legacyOutcome = legacy(kind, payload)
        val candidateOutcome = candidate(kind, payload)
        assertEquals(legacyOutcome, candidateOutcome, "outcome parity for kind=$kind payload=$payload")
    }

    private suspend fun DualHarness.assertParityNoEvents(kind: String, payload: Map<String, String?> = emptyMap()) {
        assertParity(kind, payload)
        assertEquals(0, store.eventsFor(runId).count(), "no events for kind=$kind")
    }

    /** Asserts semantic parity between the legacy event (index 0) and the candidate event (index 1). */
    private fun assertFileWrittenParity(legacy: FileWritten, candidate: FileWritten) {
        assertEquals(legacy.runId, candidate.runId)
        assertEquals(legacy.path, candidate.path)
        assertEquals(legacy.sha256, candidate.sha256)
        assertEquals(legacy.size, candidate.size)
        assertEquals(legacy.atomicallyMoved, candidate.atomicallyMoved)
        assertEquals(legacy.javaClass, candidate.javaClass)
    }

    /** Asserts semantic parity between the legacy event (index 0) and the candidate event (index 1). */
    private fun assertStageMarkedUnstableParity(legacy: StageMarkedUnstable, candidate: StageMarkedUnstable) {
        assertEquals(legacy.runId, candidate.runId)
        assertEquals(legacy.stageName, candidate.stageName)
        assertEquals(legacy.message, candidate.message)
        assertEquals(legacy.javaClass, candidate.javaClass)
    }

    private fun DualHarness.semanticEvents(): List<Any> = store.eventsFor(runId).toList()

    // ===== identity / envelope =====

    @Test
    fun `identity — same PluginStepId and descriptor metadata on both paths`() {
        assertEquals("core.emit.event", CoreEmitEventStep.KEY.value)
        val d = CoreEmitEventStep.definition.contract.descriptor
        assertEquals(listOf(Effect.READ_ONLY), d.effects)
        assertEquals(ReplayPolicy.MEMOIZED, d.replayPolicy)
    }

    // ===== scope markers: PARITY, no events =====

    @Test
    fun `CatchErrorEntered — PARITY Success with zero events`() = runBlocking {
        val h = DualHarness()
        h.assertParityNoEvents("CatchErrorEntered")
    }

    @Test
    fun `CatchErrorTriggered — PARITY Success with zero events`() = runBlocking {
        val h = DualHarness()
        h.assertParityNoEvents("CatchErrorTriggered", mapOf("emitted" to "true"))
    }

    // ===== StageMarkedUnstable: PARITY incl. fallback and override =====

    @Test
    fun `StageMarkedUnstable valid — PARITY event plus Unstable with stage fallback`() = runBlocking {
        val h = DualHarness()
        h.assertParity("StageMarkedUnstable", mapOf("message" to "wobbly"))
        val events = h.semanticEvents().filterIsInstance<StageMarkedUnstable>()
        assertEquals(2, events.size, "one event per path on the shared store")
        assertStageMarkedUnstableParity(events[0], events[1])
        assertEquals("build", events[0].stageName, "both paths fall back to current stage")
    }

    @Test
    fun `StageMarkedUnstable explicit stageName — PARITY override`() = runBlocking {
        val h = DualHarness()
        h.assertParity("StageMarkedUnstable", mapOf("message" to "m", "stageName" to "deploy"))
        val events = h.semanticEvents().filterIsInstance<StageMarkedUnstable>()
        assertEquals(2, events.size)
        assertStageMarkedUnstableParity(events[0], events[1])
        assertEquals("deploy", events[0].stageName)
    }

    // ===== FileWritten: PARITY incl. all atomicallyMoved variants =====

    @Test
    fun `FileWritten valid — PARITY event plus Success`() = runBlocking {
        val h = DualHarness()
        h.assertParity(
            "FileWritten",
            mapOf("path" to "out.txt", "sha256" to "abc", "size" to "5"),
        )
        val events = h.semanticEvents().filterIsInstance<FileWritten>()
        assertEquals(2, events.size, "one event per path on the shared store")
        assertFileWrittenParity(events[0], events[1])
        assertFalse(events[0].atomicallyMoved)
    }

    @Test
    fun `FileWritten atomicallyMoved — PARITY across absent true false and lenient-garbage variants`() =
        runBlocking {
            for (variant in listOf(null, "true", "false", "TRUE", "yes", "1", "garbage")) {
                val h = DualHarness()
                val payload = linkedMapOf(
                    "path" to "p.txt", "sha256" to "s", "size" to "3",
                )
                if (variant != null) payload["atomicallyMoved"] = variant
                h.assertParity("FileWritten", payload)
                val events = h.semanticEvents().filterIsInstance<FileWritten>()
                assertEquals(2, events.size, "variant=$variant")
                assertFileWrittenParity(events[0], events[1])
                assertEquals(
                    variant?.toBooleanStrictOrNull() ?: false,
                    events[0].atomicallyMoved,
                    "atomicallyMoved parsing MUST stay exactly legacy for variant=$variant",
                )
            }
        }

    // ===== unknown kind: PARITY, SCHEMA in the HANDLER, zero events =====

    @Test
    fun `unknown kind — PARITY typed SCHEMA with zero events and decode remains total`() = runBlocking {
        val h = DualHarness()
        // codec is neutral to the whitelist: decode succeeds
        val decoded = CoreEmitEventStep.definition.contract.inputCodec
            .decode(EncodedStepValue("{\"kind\":\"SomethingUserInvented\"}"))
        assertEquals("SomethingUserInvented", decoded.kind)
        h.assertParityNoEvents("SomethingUserInvented")
        val outcome = h.candidate("SomethingUserInvented")
        assertTrue(outcome is StepOutcome.Failure)
        assertEquals(FailureKind.SCHEMA, (outcome as StepOutcome.Failure).failure.kind)
    }

    // ===== APPROVED FIX: malformed payloads move exception/INFRASTRUCTURE -> typed SCHEMA =====

    @Test
    fun `APPROVED FIX — StageMarkedUnstable missing message legacy exception candidate typed SCHEMA zero events`() =
        runBlocking {
            val h = DualHarness()
            // legacy characterization: UNTYPED exception escapes
            val legacyResult = runCatching { h.legacy("StageMarkedUnstable") }
            assertTrue(legacyResult.isFailure, "legacy baseline: exception escapes the dispatcher")
            // candidate: typed SCHEMA, no event
            val candidateOutcome = h.candidate("StageMarkedUnstable")
            assertTrue(candidateOutcome is StepOutcome.Failure)
            assertEquals(FailureKind.SCHEMA, (candidateOutcome as StepOutcome.Failure).failure.kind)
            assertEquals(0, h.semanticEvents().size)
        }

    @Test
    fun `APPROVED FIX — FileWritten missing-or-nonnumeric fields legacy exception candidate typed SCHEMA zero events`() =
        runBlocking {
            val malformed = listOf(
                mapOf<String, String?>("sha256" to "a", "size" to "1"),                    // missing path
                mapOf<String, String?>("path" to "p", "size" to "1"),                      // missing sha256
                mapOf<String, String?>("path" to "p", "sha256" to "a"),                    // missing size
                mapOf<String, String?>("path" to "p", "sha256" to "a", "size" to "not-a-number"), // non-numeric size
            )
            for (payload in malformed) {
                val h = DualHarness()
                val legacyResult = runCatching { h.legacy("FileWritten", payload) }
                assertTrue(legacyResult.isFailure, "legacy baseline must throw for $payload")
                val candidateOutcome = h.candidate("FileWritten", payload)
                assertTrue(candidateOutcome is StepOutcome.Failure, "candidate must fail typed for $payload")
                assertEquals(
                    FailureKind.SCHEMA,
                    (candidateOutcome as StepOutcome.Failure).failure.kind,
                )
                assertEquals(0, h.semanticEvents().size, "rejection MUST NOT append events for $payload")
            }
        }

    @Test
    fun `NO NEW VALIDATIONS — tolerant legacy values stay accepted (blank strings, negative size, unknown fields)`() =
        runBlocking {
            val tolerant = listOf(
                mapOf<String, String?>("path" to "", "sha256" to "", "size" to "-5"),
                mapOf<String, String?>(
                    "path" to "p", "sha256" to "a", "size" to "1", "unknownField" to "x",
                ),
                mapOf<String, String?>("message" to "", "stageName" to ""),
            )
            for (payload in tolerant) {
                val h = DualHarness()
                val kind = if (payload.containsKey("message")) "StageMarkedUnstable" else "FileWritten"
                h.assertParity(kind, payload)
            }
        }

    // ===== APPROVED ARCHITECTURAL DELTA: static capability admission =====

    @Test
    fun `APPROVED DELTA — admission matrix both present ready each missing rejected before handler`() {
        fun prepare(available: Set<StepCapability>): ExecutionPreparation =
            RegistryExecutionPreparation.prepare(
                registry = InMemoryStepRegistry().apply { CoreEmitEventStep.registerInto(this) },
                key = CoreEmitEventStep.KEY,
                encodedInput = EncodedStepValue("{\"kind\":\"CatchErrorEntered\"}"),
                availableCapabilities = available,
            )
        val both = prepare(setOf(EVENT_SINK_CAPABILITY, STAGE_IDENTITY_CAPABILITY))
        assertTrue(both is ExecutionPreparation.Ready, "both capabilities -> handler may run")
        val noSink = prepare(setOf(STAGE_IDENTITY_CAPABILITY))
        assertTrue(noSink is ExecutionPreparation.Rejected, "EVENT_SINK missing -> rejected before handler")
        val noStage = prepare(setOf(EVENT_SINK_CAPABILITY))
        assertTrue(noStage is ExecutionPreparation.Rejected, "STAGE_IDENTITY missing -> rejected before handler")
    }

    // ===== replay law frozen: READ_ONLY + MEMOIZED =====

    @Test
    fun `replay — fresh RERUN memoized-success SKIP and metadata identical to legacy row`() {
        val policy = DefaultEffectReplayPolicy()
        val effects = setOf(Effect.READ_ONLY)
        assertEquals(ReplayDecision.RERUN, policy.decide(ReplayPolicy.MEMOIZED, effects, false, null))
        assertEquals(
            ReplayDecision.SKIP,
            policy.decide(ReplayPolicy.MEMOIZED, effects, true, OperationStatus.SUCCEEDED),
        )
        assertEquals(
            ReplayDecision.RERUN,
            policy.decide(ReplayPolicy.MEMOIZED, effects, true, OperationStatus.FAILED),
        )
    }

    // ===== no-cutover invariants =====

    @Test
    fun `no cutover — registry candidate registered but LegacyCore authoritative and counters untouched`() {
        val production = CoreStepRegistryFactory.registry()
        assertTrue(production.contains(CoreEmitEventStep.KEY))
        assertEquals(
            dev.rubentxu.pipeline.v2.application.durable.StructuralStepFamily.LegacyCore,
            StructuralFamilyResolver.classify(CoreEmitEventStep.KEY, production),
        )
        assertTrue(
            PluginStepId("core.emit.event").value in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS,
            "G2 MUST NOT touch LEGACY_PLUGIN_IDS (no flip before G3/G4)",
        )
    }
}
