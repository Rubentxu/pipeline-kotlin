package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalNodeDispatcher
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeCapabilityAccess
import dev.rubentxu.pipeline.v2.application.durable.ExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.application.durable.PreparedRegistryExecution
import dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionBoundary
import dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionPreparation
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import dev.rubentxu.pipeline.v2.application.durable.StructuralFamilyResolver
import dev.rubentxu.pipeline.v2.application.durable.StructuralStepFamily
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.StageMarkedUnstable
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Paths

/**
 * S2-A4 / G3 — Migration Readiness for `core.emit.event` (PRE-flip: no authority change).
 *
 * Proves, before the G4 flip:
 *
 *  1. Candidate registered; key still in LEGACY_PLUGIN_IDS; family == LegacyCore.
 *  2. Both capabilities available through the REAL runtime bridge
 *     ([CanonicalRuntimeCapabilityAccess] over a [CanonicalRuntimeContext]).
 *  3. Structural control semantics are INDEPENDENT of legacy execution semantics:
 *     `CanonicalStructuralPreparation → StructuralOverlayProjection` yields the catchError
 *     push/pop descriptors from the RAW envelope, never constructing
 *     `CanonicalCoreStepCommand.EmitEvent` and never touching
 *     `CanonicalEmitEventNodeDispatcher`. This protects
 *     `structural control semantics != legacy execution semantics` so catchError cannot
 *     accidentally depend on the legacy decoder for its scope stack.
 *  4. Candidate execution through the REAL seam:
 *     RegistryExecutionPreparation → capability admission → RegistryExecutionBoundary → handler,
 *     for CatchErrorEntered (Success/0 events), StageMarkedUnstable (Unstable/1 event),
 *     unknown kind (SCHEMA/0 events). Production routing untouched.
 *
 * Archived-at-G4 note: like CoreErrorMigrationReadinessFitnessTest, the pre-flip structural
 * assertions (family == LegacyCore, key in LEGACY_PLUGIN_IDS) are recorded by method name and
 * will be archived at G4 rather than inverted.
 */
@Disabled("Archived G3 evidence (S2-A4): superseded by CoreEmitEventRegistryPrimaryFitnessTest " +
    "after the G4 REGISTRY_PRIMARY flip. Pre-flip assertions (LegacyCore family, legacy " +
    "membership) became incorrect after a successful migration; see class kdoc and " +
    "CoreErrorMigrationReadinessFitnessTest for the archival rationale.")
@Timeout(30)
class CoreEmitEventMigrationReadinessFitnessTest {

    private val key = PluginStepId("core.emit.event")

    private fun emitNode(encoded: String) = OpaqueStepNode(
        id = StepId("stage-0-step-0"),
        pluginStepId = key,
        payload = VersionedStepPayload(schemaVersion = "dsl-v1", encoded = encoded),
    )

    /** Structural pipeline: preparation -> overlay projection, from the raw envelope only. */
    private fun projectOverlay(encoded: String): StructuralOverlay {
        val prepared = CanonicalStructuralPreparation.prepare(emitNode(encoded))
        val ready = prepared as StructuralPreparation.Ready
        return StructuralOverlayProjection.project(ready.invocation.stepKey, ready.envelope)
    }

    // ===== 1. registration + legacy-membership (pre-flip state) =====

    @Test
    fun `G3 readiness -- candidate registered and key still in LEGACY_PLUGIN_IDS with LegacyCore family`() {
        val production = CoreStepRegistryFactory.registry()
        assertTrue(production.contains(key), "candidate must be registered pre-flip")
        assertTrue(
            key.value in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS,
            "pre-flip: legacy membership is still the structural authority",
        )
        assertEquals(
            StructuralStepFamily.LegacyCore,
            StructuralFamilyResolver.classify(key, production),
        )
    }

    // ===== 2. real runtime capability bridge provides both capabilities =====

    @Test
    fun `G3 readiness -- real CanonicalRuntimeCapabilityAccess provides EVENT_SINK and STAGE_IDENTITY`() {
        val ctx = runtimeContext(InMemoryEventStore())
        val access = CanonicalRuntimeCapabilityAccess(ctx)
        assertTrue(EVENT_SINK_CAPABILITY in access.available())
        assertTrue(STAGE_IDENTITY_CAPABILITY in access.available())
        val identity = access.get<StageIdentity>(STAGE_IDENTITY_CAPABILITY)
        assertEquals("build", identity.name)
    }

    // ===== 3. structural overlay independent of legacy execution =====

    @Test
    fun `G3 readiness -- CatchErrorEntered overlay projects from raw envelope without legacy decode`() {
        val overlay = projectOverlay(
            """{"kind":"CatchErrorEntered","buildResult":"FAILURE","stageResult":"FAILURE","message":"tolerated"}""",
        ) as StructuralOverlay.CatchErrorEntered
        assertEquals("FAILURE", overlay.buildResult)
        assertEquals("FAILURE", overlay.stageResult)
        assertEquals("tolerated", overlay.message)
    }

    @Test
    fun `G3 readiness -- CatchErrorTriggered overlay projects emitted flag from raw envelope`() {
        val overlay = projectOverlay(
            """{"kind":"CatchErrorTriggered","emitted":"true"}""",
        ) as StructuralOverlay.CatchErrorTriggered
        assertTrue(overlay.emitted)
    }

    @Test
    fun `G3 readiness -- FileWritten and unknown kinds project no overlay`() {
        assertEquals(
            StructuralOverlay.None,
            projectOverlay("""{"kind":"FileWritten","path":"p","sha256":"a","size":"1"}"""),
        )
        assertEquals(StructuralOverlay.None, projectOverlay("""{"kind":"WhoKnows"}"""))
    }

    // ===== 4. candidate execution through the REAL seam (no production routing) =====

    @Test
    fun `G3 real seam -- CatchErrorEntered via preparation admission boundary is Success with zero events`() =
        runBlocking {
            val store = InMemoryEventStore()
            val prepared = RegistryExecutionPreparation.prepare(
                registry = CoreStepRegistryFactory.registry(),
                key = key,
                encodedInput = dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue(
                    """{"kind":"CatchErrorEntered","buildResult":"FAILURE"}""",
                ),
                availableCapabilities = CanonicalRuntimeCapabilityAccess(runtimeContext(store)).available(),
            )
            assertTrue(prepared is ExecutionPreparation.Ready)
            val result = RegistryExecutionBoundary.adapt().execute(
                (prepared as ExecutionPreparation.Ready).prepared as PreparedRegistryExecution,
                runtimeContext(store),
            )
            assertEquals(StepOutcome.Success, result.outcome)
            assertEquals(0, store.eventsFor("g3-run").count(), "markers MUST NOT append events")
        }

    @Test
    fun `G3 real seam -- StageMarkedUnstable via real seam is Unstable with exactly one event`() = runBlocking {
        val store = InMemoryEventStore()
        val ctx = runtimeContext(store) // stageName = "build" -> fallback authority
        val prepared = RegistryExecutionPreparation.prepare(
            registry = CoreStepRegistryFactory.registry(),
            key = key,
            encodedInput = dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue(
                """{"kind":"StageMarkedUnstable","message":"wobbly"}""",
            ),
            availableCapabilities = CanonicalRuntimeCapabilityAccess(ctx).available(),
        )
        assertTrue(prepared is ExecutionPreparation.Ready)
        val result = RegistryExecutionBoundary.adapt().execute(
            (prepared as ExecutionPreparation.Ready).prepared as PreparedRegistryExecution,
            ctx,
        )
        assertEquals(StepOutcome.Unstable, result.outcome)
        val events = store.eventsFor("g3-run").filterIsInstance<StageMarkedUnstable>().toList()
        assertEquals(1, events.size)
        assertEquals("build", events.single().stageName, "StageIdentity fallback through the real bridge")
        assertEquals("wobbly", events.single().message)
    }

    @Test
    fun `G3 real seam -- unknown kind via real seam is typed SCHEMA with zero events`() = runBlocking {
        val store = InMemoryEventStore()
        val ctx = runtimeContext(store)
        val prepared = RegistryExecutionPreparation.prepare(
            registry = CoreStepRegistryFactory.registry(),
            key = key,
            encodedInput = dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue(
                """{"kind":"Nope"}""",
            ),
            availableCapabilities = CanonicalRuntimeCapabilityAccess(ctx).available(),
        )
        assertTrue(prepared is ExecutionPreparation.Ready, "decode is total; rejection is handler semantics")
        val result = RegistryExecutionBoundary.adapt().execute(
            (prepared as ExecutionPreparation.Ready).prepared as PreparedRegistryExecution,
            ctx,
        )
        val failure = result.outcome as StepOutcome.Failure
        assertEquals(FailureKind.SCHEMA, failure.failure.kind)
        assertEquals(0, store.eventsFor("g3-run").count())
    }

    // ===== helpers =====

    private fun runtimeContext(store: InMemoryEventStore): CanonicalRuntimeContext =
        CanonicalRuntimeContext(
            opId = OpId(runId = "g3-run", stageIndex = 0, stepIndex = 0),
            runId = "g3-run",
            stageName = "build",
            stageIndex = 0,
            stepIndex = 0,
            shOptions = ShOptions.EMPTY,
            controlDirRoot = null,
            eventSink = store,
        )
}
