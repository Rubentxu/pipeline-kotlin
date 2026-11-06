package dev.rubentxu.pipeline.v2.sdk.http

import dev.rubentxu.pipeline.v2.domain.step.EgressRefusal
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the operator reads when a plugin loses the network.
 *
 * ## The guarantee
 *
 * The refusal speaks about the PLUGIN and never about the RUN. The run's own
 * child process is a different subject with a different fact: a shell step's
 * child keeps the host's network unless the run asked for a boundary that can
 * impose it. A refusal that says "this run may not reach the network" tells an
 * operator that their run is offline, and their run is not.
 *
 * ## Why this instrument
 *
 * The diagnostic is the whole product of this refusal: the typed failure
 * travels to the journal, but the sentence the operator reads is what turns a
 * missing permission into an action. A sentence that lies about the run's
 * network is worse than no sentence, because it is believed.
 */
class EgressDeniedDiagnosticTest {

    @Test
    fun `the refusal does not claim that the run is offline`() {
        val diagnostic = HttpFailure.EgressDenied(EgressRefusal.NetworkNotPermitted).diagnostic

        assertFalse(
            diagnostic.contains("this run may not reach the network"),
            "the refusal claims a boundary the run does not have: $diagnostic",
        )
        assertFalse(
            diagnostic.contains("the run may not reach the network"),
            "the refusal claims a boundary the run does not have: $diagnostic",
        )
    }

    @Test
    fun `the refusal names the plugin as the subject it denies`() {
        val diagnostic = HttpFailure.EgressDenied(EgressRefusal.NetworkNotPermitted).diagnostic

        assertTrue(
            diagnostic.contains("plugin"),
            "the refusal does not say whose permission is missing: $diagnostic",
        )
    }

    @Test
    fun `the refusal names the remedy that opens the plugin's permission`() {
        val diagnostic = HttpFailure.EgressDenied(EgressRefusal.NetworkNotPermitted).diagnostic

        assertTrue(
            diagnostic.contains("--allow-network"),
            "the refusal does not name the switch that grants the permission: $diagnostic",
        )
    }

    @Test
    fun `the refusal says that the gate decides for plugins and not for the run`() {
        val diagnostic = HttpFailure.EgressDenied(EgressRefusal.NetworkNotPermitted).diagnostic

        assertTrue(
            diagnostic.contains("not for the run"),
            "the refusal leaves the operator believing the run is offline: $diagnostic",
        )
    }
}
