package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.StepMetadata
import dev.rubentxu.pipeline.v2.application.StepMetadataResolver
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * The preparation of a dispatch is decided without reading a clock.
 *
 * Everything a dispatch needs BEFORE any effect happens is one value: the durable
 * identity of the operation, the structural verdict, the metadata and the fingerprint.
 * Deciding it means deriving facts, and a derivation that reads the ambient clock is
 * not a derivation: the same dispatch would carry a different instant depending on
 * WHEN it was asked.
 *
 * The preparation used to carry an instant, because a `catchError` scope pushed a frame
 * stamped with the moment it was entered, and that stamp travelled inside the durable
 * identity. The scope's contract now travels in its block payload and the scope decides
 * its own outcome, from inside the scope — so no frame is pushed, and the preparation
 * has no clock at all. The guarantee is therefore STRONGER than it was: it is not that
 * the instant is the one the caller observed, it is that there is no instant.
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

    private fun emitEvent(encoded: String) = OpaqueStepNode(
        id = StepId("step-1"),
        pluginStepId = PluginStepId("core.emit.event"),
        payload = VersionedStepPayload(schemaVersion = "dsl-v1", encoded = encoded),
    )

    /**
     * The durable identity carries the step's own payload and NOTHING ELSE.
     *
     * This is the guarantee that replaced the old one. The preparation used to hand the
     * caller an instant — the moment a control scope was entered — and that instant
     * travelled inside the operation's identity. The scope no longer pushes a frame, so
     * the identity has no room for an instant: the only thing it carries is the step's
     * own payload.
     *
     * So the assertion is about the WHOLE set of parameters, not about one of them:
     * if any other fact — an instant, a wall clock, an attempt counter that moved — were
     * folded into the identity again, a second key would appear here and the row would
     * go red on the spot.
     */
    @Test
    @DisplayName("the durable identity carries the step's payload and no instant")
    fun theDurableIdentityCarriesTheStepsPayloadAndNoInstant() {
        val ready = assertInstanceOf(
            DispatchPreparation.Ready::class.java,
            preparation.prepare(
                step = emitEvent("""{"kind":"StageMarkedUnstable","message":"aviso"}"""),
                coordinates = coordinates,
                stageShOptions = shOptions,
                hasBodyContinuation = false,
            ),
            "a well-formed step is admitted",
        )

        assertEquals(
            setOf("payload"),
            ready.input.params.keys,
            "the durable identity carries the step's payload and nothing else: no instant, no " +
                "ambient clock, no fact that is not the step's own. An identity that carried an " +
                "instant would be a different identity for the same dispatch, asked twice.",
        )
        assertEquals(
            JsonPrimitive("""{"kind":"StageMarkedUnstable","message":"aviso"}"""),
            ready.input.params["payload"],
            "the one parameter it does carry is the step's own payload, verbatim",
        )
        assertEquals(
            expectedOperationId,
            ready.operationId,
            "the durable identity of the operation is derived from the coordinates",
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
