package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.StepMetadata
import dev.rubentxu.pipeline.v2.application.StepMetadataResolver
import dev.rubentxu.pipeline.v2.domain.ContextOverlay
import dev.rubentxu.pipeline.v2.domain.ExecutionContext
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * The preparation of a dispatch is decided without reading a clock.
 *
 * Everything a dispatch needs BEFORE any effect happens is one value: the durable
 * identity of the operation, the structural verdict, the control overlay that the
 * step contributes to the context, the metadata and the fingerprint. Deciding it
 * means deriving facts, and a derivation that reads the ambient clock is not a
 * derivation: the same dispatch would carry a different instant depending on WHEN
 * it was asked.
 *
 * The instant a catchError scope is entered is therefore an ARGUMENT. The caller
 * observes it and hands it over, and the core has no clock of its own.
 *
 * The other half of the guarantee is the rejection: a structurally invalid node is
 * rejected, and the rejection still carries the durable identity the journal row
 * needs — a rejection that lost its operation id could not be journaled fail-closed,
 * it would simply vanish.
 *
 * Pure and total: nothing here touches a disk, a clock or a process.
 */
class CanonicalDispatchPreparationTest {

    /**
     * The legacy core catalog is the production resolver; here it answers for the one
     * core key this test dispatches, and refuses the rest the way a lookup miss does.
     */
    private val preparation = CanonicalDispatchPreparation(
        StepMetadataResolver { key ->
            if (key.value == "core.emit.event") {
                StepMetadata(effects = setOf(Effect.READ_ONLY), replayPolicy = ReplayPolicy.MEMOIZED)
            } else {
                null
            }
        },
    )

    private val coordinates = DispatchCoordinates(
        runId = RunId("run-1"),
        stageIndex = 0,
        stepIndex = 1,
        bodyPath = emptyList(),
    )

    /**
     * The durable identity the coordinates name, spelled by the authority that owns it.
     *
     * The preparation does not invent an identity: it asks [OpId] for the spelling of
     * the coordinates. This test therefore compares against that same authority instead
     * of against a literal written by hand — a literal would be a second, drifting
     * copy of the format, and it would fail for the wrong reason if the format ever
     * changed on purpose.
     */
    private val expectedOperationId = OpId(
        runId = coordinates.runId.value,
        stageIndex = coordinates.stageIndex,
        stepIndex = coordinates.stepIndex,
        bodyPath = coordinates.bodyPath,
    ).format()

    private val shOptions = ShOptions(
        workspaceRoot = Path.of("/nonexistent/pipelinek-preparation-test"),
        captureStdout = false,
        timeoutMs = null,
        env = emptyMap(),
    )

    private fun catchErrorEntered(encoded: String) = OpaqueStepNode(
        id = StepId("step-1"),
        pluginStepId = PluginStepId("core.emit.event"),
        payload = VersionedStepPayload(schemaVersion = "dsl-v1", encoded = encoded),
    )

    @Test
    @DisplayName("the instant a catch error scope is entered is the one the caller observed")
    fun theInstantACatchErrorScopeIsEnteredIsTheOneTheCallerObserved() {
        val ready = assertInstanceOf(
            DispatchPreparation.Ready::class.java,
            preparation.prepare(
                step = catchErrorEntered("""{"kind":"CatchErrorEntered","buildResult":"UNSTABLE"}"""),
                coordinates = coordinates,
                stageShOptions = shOptions,
                hasBodyContinuation = false,
                executionContext = ExecutionContext.EMPTY,
                now = 1_700_000_000_000L,
            ),
            "a well-formed scope is admitted",
        )

        val overlay = assertInstanceOf(
            ContextOverlay.CatchErrorOverlay::class.java,
            ready.contextAfterOverlay.overlays.last(),
            "entering a scope pushes exactly one frame on the context",
        )

        assertEquals(
            1_700_000_000_000L,
            overlay.enteredAt,
            "the frame carries the instant the caller handed over, not one the core read",
        )
        assertEquals(
            expectedOperationId,
            ready.operationId,
            "the durable identity of the operation is derived from the coordinates",
        )
    }

    @Test
    @DisplayName("a payload that declares its own instant keeps it")
    fun aPayloadThatDeclaresItsOwnInstantKeepsIt() {
        val ready = assertInstanceOf(
            DispatchPreparation.Ready::class.java,
            preparation.prepare(
                step = catchErrorEntered(
                    """{"kind":"CatchErrorEntered","buildResult":"UNSTABLE","enteredAt":"1700000000123"}""",
                ),
                coordinates = coordinates,
                stageShOptions = shOptions,
                hasBodyContinuation = false,
                executionContext = ExecutionContext.EMPTY,
                now = 1_700_000_000_000L,
            ),
            "a well-formed scope is admitted",
        )

        val overlay = assertInstanceOf(
            ContextOverlay.CatchErrorOverlay::class.java,
            ready.contextAfterOverlay.overlays.last(),
            "entering a scope pushes exactly one frame on the context",
        )

        assertEquals(
            1_700_000_000_123L,
            overlay.enteredAt,
            "a declared instant is the producer's own fact and outranks the argument",
        )
    }

    @Test
    @DisplayName("a structurally invalid node is rejected with the identity the journal needs")
    fun aStructurallyInvalidNodeIsRejectedWithTheIdentityTheJournalNeeds() {
        val rejected = assertInstanceOf(
            DispatchPreparation.Rejected::class.java,
            preparation.prepare(
                step = OpaqueStepNode(
                    id = StepId("step-1"),
                    pluginStepId = PluginStepId("core.emit.event"),
                    payload = VersionedStepPayload(schemaVersion = "dsl-v2", encoded = "{}"),
                ),
                coordinates = coordinates,
                stageShOptions = shOptions,
                hasBodyContinuation = false,
                executionContext = ExecutionContext.EMPTY,
                now = 1_700_000_000_000L,
            ),
            "an unsupported payload schema is rejected before any typed semantics run",
        )

        assertEquals(
            expectedOperationId,
            rejected.operationId,
            "a rejection that lost its operation id could not be journaled fail-closed",
        )
        assertEquals(
            "Unsupported step payload schema 'dsl-v2' for 'step-1'",
            rejected.reason,
            "the rejection names the schema it refused",
        )
    }
}
