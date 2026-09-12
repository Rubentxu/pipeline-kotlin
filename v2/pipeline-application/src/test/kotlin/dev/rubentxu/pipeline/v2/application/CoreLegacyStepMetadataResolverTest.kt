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
        val decoded = CanonicalCoreStepCommand.CleanWs().defaultMetadata
        assertEquals(decoded, resolved)
    }

    @Test
    fun `ordinary steps declare no recovery`() {
        // CDE.2-b4: the durable protocol decides recovery from metadata.recoveryPolicy, never a Step name.
        // core.sh is no longer a legacy authority member (S6); its recovery is read from CoreShellStep.descriptor.
        assertEquals(
            RecoveryPolicy.None,
            CoreLegacyStepMetadataResolver.resolve(PluginStepId("core.sleep")).recoveryPolicy,
        )
        assertEquals(
            RecoveryPolicy.None,
            CoreLegacyStepMetadataResolver.resolve(PluginStepId("core.load")).recoveryPolicy,
        )
    }

    @Test
    fun `every canonical core plugin id resolves to non-null metadata`() {
        for (pluginId in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS) {
            assertEquals(
                false,
                CoreLegacyStepMetadataResolver.resolve(PluginStepId(pluginId)) == null,
                "Canonical core plugin '$pluginId' must resolve durable metadata",
            )
        }
    }

    @Test
    fun `an unregistered key fails fast`() {
        assertThrows(IllegalArgumentException::class.java) {
            CoreLegacyStepMetadataResolver.resolve(PluginStepId("core.unknown"))
        }
    }
}
