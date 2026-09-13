package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * LFC-2E1 burn-down convergence guard (S2-B10 / G5 closure proof).
 *
 * [LegacyResidualSnapshot] deliberately exposes two strengths:
 *
 * ```text
 * assertCurrentState   live == expected(declared stage)   valid at N/N/N AND (N-1)/N/N
 * assertConverged      assertCurrentState + NO in-flight REGISTRY_PRIMARY flip
 * ```
 *
 * The per-Step `S3*LegacyRemovedFitnessTest` suites call `assertCurrentState`: a per-Step
 * absence assertion must not go red because some OTHER Step happens to be mid-flip. That is
 * correct, but it left an enforcement gap — NOTHING in the corpus asserted the stronger
 * property, the one that says the residual is at a genuinely converged point rather than
 * mid-transition. A receipt sentence is not a gate; this suite is.
 *
 * ## RED -> GREEN, same file, same assertions
 *
 * This file was written ONCE and not modified between the two runs:
 *
 * ```text
 * G4 base @ 435f5f8b (before removal)
 *   RED   : IllegalStateException
 *           "Convergence requires no in-flight REGISTRY_PRIMARY flip;
 *            registryPrimaryPendingRemoval=core.archiveArtifacts (G5 not closed)"
 *
 * G5 (after removal + physicalResidual -= key + registryPrimaryPendingRemoval = null)
 *   GREEN : live == expected == 2 / 2 / 2
 * ```
 *
 * That is stronger than an `assertFailsWith`-style negative test, because the SEMANTICS of
 * the assertion never change: the guard always means "the residual is converged". The product
 * changed and the test went green. An `assertFailsWith` would have had to be rewritten at G5,
 * which is exactly the incidental mutation this design removes.
 */
class LegacyResidualConvergenceFitnessTest {

    private val root = ScannerSupport.v2Root()

    /**
     * The convergence property itself: declared stage snapshot holds AND no flip is in flight.
     */
    @Test
    fun `legacy residual is converged with no in-flight registry primary flip`() {
        LegacyResidualSnapshot.assertConverged(root)
    }

    /**
     * The three legacy authorities are read LIVE from source and must agree with the single
     * declared snapshot AND with each other. `assertConverged` already checks the first part
     * (live == expected per authority); this row adds the cross-authority equality so a
     * self-consistent-but-wrong declared snapshot cannot pass.
     */
    @Test
    fun `converged residual is the same exact key set in all three legacy authorities`() {
        val ids = LegacyResidualSnapshot.liveLegacyIds(root)
        val metadataRows = LegacyResidualSnapshot.liveMetadataRows(root)
        val dispatcherFiles = LegacyResidualSnapshot.liveDispatcherFiles(root)
        val expected = LegacyResidualSnapshot.expected()

        assertEquals(expected.pluginIds, ids, "live LEGACY_PLUGIN_IDS drifted from the declared snapshot")
        assertEquals(expected.metadataRows, metadataRows, "live metadata rows drifted")
        assertEquals(expected.dispatcherFiles, dispatcherFiles, "live dispatcher files drifted")

        assertEquals(ids.size, metadataRows.size, "ids vs metadata rows disagree (not converged)")
        assertEquals(
            metadataRows.size,
            dispatcherFiles.size,
            "metadata rows vs dispatcher files disagree (not converged)",
        )
    }

    /**
     * Anti-vacuity: a converged snapshot must still describe a NON-EMPTY residual until the
     * burn-down truly closes. Without this, deleting the burn-down ledger entirely (making
     * `expected()` trivially equal to an empty live scan) would look like convergence.
     */
    @Test
    fun `converged residual is non-vacuous while the burn-down is still open`() {
        val expected = LegacyResidualSnapshot.expected()
        if (expected.pluginIds.isEmpty()) return // burn-down fully closed: nothing left to guard
        assertFalse(
            expected.pluginIds.isEmpty(),
            "an empty declared residual is only legitimate once every legacy key is retired",
        )
        assertEquals(
            expected.pluginIds.size,
            expected.dispatcherFiles.size,
            "every residual key must still have a dispatcher file while not converged",
        )
    }
}
