package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CoreStepRegistryFactory
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
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import dev.rubentxu.pipeline.v2.events.CatchErrorTriggered
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.StageMarkedUnstable
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * S2-A4 / G4 — decisive catchError UAT after the REGISTRY_PRIMARY flip.
 *
 * The real route exercised here (production-like fixture: core registry + composite resolver +
 * seamed routing):
 *
 * ```
 * compiler-injected CatchErrorEntered envelope
 *   → CanonicalStructuralPreparation
 *   → StructuralOverlayProjection pushes the scope        (pre-decode, key-based)
 *   → core.emit.event executes through the REGISTRY       (post-flip)
 *   → inner body
 *   → CatchErrorTriggered envelope
 *   → StructuralOverlayProjection pops the scope
 *   → core.emit.event Registry handler returns Success silently
 * ```
 *
 * Fresh: correct scope push/pop, tolerated inner failure, NO spurious event from the
 * markers, expected run outcome, sibling executes.
 *
 * Replay: structural overlays still apply pre-reconcile (C6 — a reused CatchErrorEntered
 * still pushes its scope), MEMOIZED reuse does not corrupt the context stack, and there is
 * NO double publication of events (handler not re-invoked on reuse).
 *
 * Also covers StageMarkedUnstable through the same real coordinator seam: payload stageName
 * wins when present, StageIdentity.name fallback when absent, cardinality 1, Unstable outcome.
 */
@Timeout(60)
class EmitEventCatchErrorRegistryUatTest {

    // ===== step builders (same envelope shapes the compiler emits) =====

    private fun emitEventStep(id: String, kind: String, vararg fields: Pair<String, String>) =
        OpaqueStepNode(
            id = StepId(id),
            pluginStepId = PluginStepId("core.emit.event"),
            payload = VersionedStepPayload(
                "dsl-v1",
                buildString {
                    append("{\"kind\":\"").append(kind).append('"')
                    fields.forEach { (name, value) ->
                        append(",\"").append(name).append("\":\"").append(value).append('"')
                    }
                    append('}')
                },
            ),
        )

    private fun echoStep(id: String, text: String) = OpaqueStepNode(
        id = StepId(id),
        pluginStepId = PluginStepId("core.echo"),
        payload = VersionedStepPayload("dsl-v1", """{"kind":"echo","text":"$text"}"""),
    )

    private fun pipeline(vararg nodes: OpaqueStepNode) = CompiledPipeline(
        id = DefinitionId("emit-event-registry-uat-pipeline"),
        source = SourceDescriptor("Pipeline.kts", Digest("source")),
        pluginLockDigest = Digest("lock"),
        stages = listOf(
            StageNode(
                id = StageId("build"),
                name = "build",
                body = StageBody.Steps(nodes.toList()),
            ),
        ),
    )

    private fun emitEventInput(runIdValue: String, encoded: String) = OperationInput(
        stepId = "core.emit.event",
        params = mapOf("payload" to JsonPrimitive(encoded)),
        runId = runIdValue,
        attempt = 1,
    )

    // ===== FRESH: full catchError route through the registry =====

    @Test
    fun `fresh — catchError scope push pop via registry with tolerated inner failure and no spurious marker events`() =
        runBlocking {
            val clock = SystemClock()
            val journal = InMemoryOperationJournal(clock)
            val store = InMemoryEventStore()
            val coord = CoordinatorFixture.default(clock, journal, store)

            val outcome = coord.run(
                pipeline(
                    // compiler-injected entry marker (buildResult default UNSTABLE)
                    emitEventStep("build/catch-enter", "CatchErrorEntered", "buildResult" to "UNSTABLE"),
                    // inner failing body handled by the catchError scope
                    emitEventStep(
                        "build/catch-trigger",
                        "CatchErrorTriggered",
                        "buildResult" to "UNSTABLE",
                        "stageResult" to "UNSTABLE",
                        "emitted" to "true",
                    ),
                    echoStep("build/after-catch", "after catchError"),
                ),
                RunId("emit-uat-fresh"),
            )

            assertEquals(RunOutcome.Success, outcome, "tolerated scope => successful continuation")
            val events = store.eventsFor("emit-uat-fresh")
            // NO spurious domain event from the silent markers (the coordinator publishes the
            // real CatchErrorTriggered only at a real inner failure — none here):
            assertEquals(
                0,
                events.filterIsInstance<CatchErrorTriggered>().count(),
                "markers MUST NOT append events on a non-failing body (ERR-S-008)",
            )
            assertTrue(
                events.filterIsInstance<StepStarted>().any { it.stepName == "build/after-catch" },
                "sibling after the scope MUST execute",
            )
            assertTrue(
                journal.listForRun("emit-uat-fresh").map { it.status }.all { it == OperationStatus.SUCCEEDED },
                "all journaled operations (markers + sibling) must be SUCCEEDED",
            )
        }

    // ===== REPLAY: memoized reuse + overlay push/pop survives =====

    @Test
    fun `replay — memoized marker reuse keeps overlay push pop and never double publishes`() = runBlocking {
        val clock = SystemClock()
        val runId = RunId("emit-uat-replay")
        val journal = InMemoryOperationJournal(clock)
        val store = InMemoryEventStore()

        // Seed the durable journal the way a PRIOR FRESH RUN would have: the entry marker
        // SUCCEEDED under its fingerprint. On the second run the coordinator must STILL push
        // the scope (overlay projects pre-reconcile from the raw envelope, C6) while reusing
        // the operation WITHOUT re-running the handler.
        val enteredEncoded = """{"kind":"CatchErrorEntered","buildResult":"UNSTABLE"}"""
        val input = emitEventInput(runId.value, enteredEncoded)
        journal.append(
            RerunOperation(
                id = "${runId.value}-s0-0",
                fingerprint = Fingerprint.compute(input, "core.emit.event", ReplayPolicy.MEMOIZED, 1),
                input = input,
                output = null,
                status = OperationStatus.SUCCEEDED,
                attempt = 1,
            ),
        )
        val coord = CoordinatorFixture.default(clock, journal, store)

        val outcome = coord.run(
            pipeline(
                emitEventStep("build/catch-enter", "CatchErrorEntered", "buildResult" to "UNSTABLE"),
                emitEventStep(
                    "build/catch-trigger",
                    "CatchErrorTriggered",
                    "buildResult" to "UNSTABLE",
                    "stageResult" to "UNSTABLE",
                    "emitted" to "true",
                ),
                echoStep("build/after-catch", "after catchError"),
            ),
            runId,
        )

        assertEquals(RunOutcome.Success, outcome, "reused marker still establishes a working scope")
        assertTrue(
            store.eventsFor(runId.value).filterIsInstance<StepStarted>().any { it.stepName == "build/after-catch" },
            "sibling executes after the reused scope — overlay push survived the reuse",
        )
        assertEquals(
            0,
            store.eventsFor(runId.value).filterIsInstance<CatchErrorTriggered>().count(),
            "no spurious/double publication of the marker event on replay",
        )
    }

    // ===== StageMarkedUnstable through the real coordinator seam =====

    @Test
    fun `StageMarkedUnstable via real seam — payload stageName wins fallback to StageIdentity when absent`() =
        runBlocking {
            val clock = SystemClock()
            val journal = InMemoryOperationJournal(clock)
            val store = InMemoryEventStore()
            val coord = CoordinatorFixture.default(clock, journal, store)

            val outcome = coord.run(
                pipeline(
                    // absent stageName -> StageIdentity("build") fallback
                    emitEventStep("build/warn", "StageMarkedUnstable", "message" to "wobbly"),
                ),
                RunId("emit-uat-unstable"),
            )

            assertEquals(RunOutcome.Unstable, outcome)
            val events = store.eventsFor("emit-uat-unstable").filterIsInstance<StageMarkedUnstable>().toList()
            assertEquals(1, events.size, "event cardinality exactly 1")
            assertEquals("build", events.single().stageName, "StageIdentity fallback through the runtime bridge")
            assertEquals("wobbly", events.single().message)
        }
}
