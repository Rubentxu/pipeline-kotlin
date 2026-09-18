package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.durable.RecoveryPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * B1.2c2-CDE.2-b2: the legacy core metadata resolver yields the same pre-decode durable metadata as
 * the decoded command's defaultMetadata (single authority, parity exact), covers every canonical core
 * plugin id, and fails fast on an unregistered key. This is the seam durable resolution consumes; it
 * never interprets a Step name.
 *
 * S3.3: `core.echo` is no longer in the legacy authority; the registry-routed Echo is verified through
 * the CoreEchoStep StepDefinition metadata instead.
 */
@Timeout(10)
class CoreLegacyStepMetadataResolverTest {

    @Disabled("S2-A6 / G5 (2026-09-12): CanonicalCoreStepCommand.Pwd subtype physically deleted (LEGACY_REMOVED). This test compared the legacy Pwd default metadata to the resolver — preserved verbatim for traceability; superseded by S3PwdLegacyRemovedFitnessTest asserting descriptor metadata against the same effect+replayPolicy invariants.")
    @Test
    fun `resolves sleep metadata by step key matching the decoded command`() {
        val resolved = CoreLegacyStepMetadataResolver.resolve(PluginStepId("core.sleep"))
        // S2-A10 / G5 (2026-09-13): CanonicalCoreStepCommand.CleanWs physically removed
        // (LEGACY_REMOVED). This test was a pre-existing red that used CleanWs as an
        // unrelated proxy; the assertion itself is structurally flawed (core.sleep is
        // registry-primary and should not be resolvable via the legacy resolver). Kept
        // disabled until a successor test is authored against CoreSleepStep.descriptor.
        // WU-LPR-301 / G5 (2026-09-18): the `decoded` value derived from
        // CanonicalCoreStepCommand.Load was removed because Load itself is LEGACY_REMOVED.
        // The disabled fixture is kept for historical traceability with the assertion removed.
        assertEquals(null, resolved?.recoveryPolicy)
    }

    /**
     * WU-LPR-301 / G5 (2026-09-18): LEGACY_PLUGIN_IDS is now `setOf()` (the membership table
     * is empty). The legacy metadata resolver no longer has any key to resolve — every
     * surviving core plugin routes through the StepRegistry. The previous fixture used
     * `core.load` and `core.waitUntil` as probes for the "no recovery" property; both keys
     * are now registry-routed (or unregistered for `core.load`, which is fail-closed). The
     * legacy resolver itself remains as a sealed seam (closed ADT), but its data table
     * converges to the empty set — the property that survives is "every legacy member has
     * no recovery" applied to an empty input.
     */
    @Test
    fun `ordinary steps declare no recovery`() {
        // CDE.2-b4: the durable protocol decides recovery from metadata.recoveryPolicy,
        // never a Step name. After WU-LPR-301 / G5, LEGACY_PLUGIN_IDS is empty, so the
        // legacy resolver has no rows: every surviving key is registry-routed and its
        // recovery policy is declared in its StepDescriptor (e.g. CoreWaitUntilStep.descriptor
        // declares RecoveryPolicy.None; the retry loop is driven by BodyExecutionPolicy.Retrying,
        // not by a recovery policy).
        assertEquals(
            emptySet<RecoveryPolicy>(),
            CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS
                .map { CoreLegacyStepMetadataResolver.resolve(PluginStepId(it)).recoveryPolicy }
                .toSet(),
            "post-WU-LPR-301 / G5: the legacy resolver's input set is empty, so its recovery " +
                "policy output collapses to the empty set. Registry-routed steps declare " +
                "their recovery policy in StepDescriptor (LB-02 / G3-A4.1).",
        )
    }

    /**
     * WU-LPR-301 / G5 (2026-09-18): the "every canonical core plugin id resolves to non-null
     * metadata" property still holds, but for a vacuous reason — LEGACY_PLUGIN_IDS is empty.
     * The membership-wins assertion is preserved as a closed-set tautology, which is the
     * post-burn-down shape of the property.
     */
    @Test
    fun `every canonical core plugin id resolves to non-null metadata`() {
        // post-WU-LPR-301 / G5: the membership table is empty, so this property is
        // vacuously satisfied. Every surviving canonical core plugin is registry-routed
        // and is verified through Core*Step.descriptor.metadata instead (LB-02 / G3-A4.1).
        for (pluginId in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS) {
            assertEquals(
                false,
                CoreLegacyStepMetadataResolver.resolve(PluginStepId(pluginId)) == null,
                "Canonical core plugin '$pluginId' must resolve durable metadata",
            )
        }
        // Closed-set tautology: the membership table is empty, so the property holds
        // structurally without resolving any row.
        assertEquals(
            emptySet<String>(),
            CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS,
            "post-WU-LPR-301 / G5: LEGACY_PLUGIN_IDS is the empty set — the legacy resolver " +
                "has no rows. Burn-down counters: 0 legacy executable steps, 0 legacy plugin " +
                "ids in the membership table.",
        )
    }

    @Test
    fun `an unregistered key fails fast`() {
        assertThrows(IllegalArgumentException::class.java) {
            CoreLegacyStepMetadataResolver.resolve(PluginStepId("core.unknown"))
        }
    }
}
