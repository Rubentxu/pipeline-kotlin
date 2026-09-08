package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * LFC-2 / B1.2c2-CDE.2-e (F1/F2/F3) — durable coordinator scope.
 *
 * Makes the CDE.2 confinement architecturally irreversible: the canonical durable coordinator is
 * structural-only and must not know the closed core typed world. The legacy typed decode and the
 * CanonicalCoreStepCommand representation live behind LegacyExecutionBoundary (a compatibility
 * boundary the future registry strategy will occupy without touching the durable spine).
 *
 * These are source-level guardrails for the transition (no heavy architecture framework yet). They
 * scan the coordinator source file only. A dependency/module rule is preferred once boundaries are
 * formalized into modules; until then this is the guard.
 */
class Lfc2DurableCoordinatorScopeFitnessTest {

    private val coordinatorSource = ScannerSupport.v2Root()
        .resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalDurableRunCoordinator.kt")
    private val boundarySource = ScannerSupport.v2Root()
        .resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/LegacyExecutionBoundary.kt")

    private fun read(path: java.nio.file.Path): String {
        require(Files.exists(path)) { "Expected source not found: $path" }
        return Files.readString(path)
    }

    /** F1 — durable coordinator must not depend on the core typed command. */
    @Test
    fun `durable coordinator does not reference CanonicalCoreStepCommand`() {
        assertFalse(
            read(coordinatorSource).contains("CanonicalCoreStepCommand"),
            "CanonicalDurableRunCoordinator must not reference CanonicalCoreStepCommand; it is confined to LegacyExecutionBoundary",
        )
    }

    /** F2 — durable coordinator must not directly decode core Steps. */
    @Test
    fun `durable coordinator does not reference CanonicalCoreStepDecoder`() {
        assertFalse(
            read(coordinatorSource).contains("CanonicalCoreStepDecoder"),
            "CanonicalDurableRunCoordinator must not decode via CanonicalCoreStepDecoder; decode goes through LegacyExecutionBoundary",
        )
    }

    /** F2 — legacy decode lives only behind the boundary file. */
    @Test
    fun `legacy decode boundary owns the core typed decoder`() {
        assertTrue(
            read(boundarySource).contains("CanonicalCoreStepDecoder"),
            "LegacyExecutionBoundary must own the CanonicalCoreStepDecoder reference",
        )
    }

    /** F3 — no concrete Step-name semantic routing in the durable coordinator. */
    @Test
    fun `durable coordinator has no concrete core step routing`() {
        val source = read(coordinatorSource)
        assertFalse(source.contains("isShellPlugin"), "Recovery must be decided by metadata, never isShellPlugin")
        assertFalse(source.contains("\"core.sh\""), "No concrete Step name 'core.sh' may be routed in the durable protocol")
        assertFalse(source.contains("\"core.echo\""), "No concrete Step name 'core.echo' may be routed in the durable protocol")
        assertFalse(source.contains("CanonicalCoreStepCommand."), "No CanonicalCoreStepCommand companion usage in the durable protocol")
    }
}
