package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.durable.RecoveryPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
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

    @Test
    fun `resolves sleep metadata by step key matching the decoded command`() {
        val resolved = CoreLegacyStepMetadataResolver.resolve(PluginStepId("core.sleep"))
        val decoded = CanonicalCoreStepCommand.Sleep(5).defaultMetadata
        assertEquals(decoded, resolved)
    }

    @Test
    fun `resolves shell metadata by step key matching the decoded command`() {
        val resolved = CoreLegacyStepMetadataResolver.resolve(PluginStepId("core.sh"))
        val decoded = CanonicalCoreStepCommand.Shell("exit 0", false, false).defaultMetadata
        assertEquals(decoded, resolved)
    }

    @Test
    fun `shell declares external-subprocess recovery, ordinary steps declare none`() {
        // CDE.2-b4: the durable protocol decides recovery from metadata.recoveryPolicy, never a Step name.
        assertEquals(
            RecoveryPolicy.ExternalSubprocess,
            CoreLegacyStepMetadataResolver.resolve(PluginStepId("core.sh")).recoveryPolicy,
        )
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
        for (pluginId in CanonicalCoreStepCommand.ALL_PLUGIN_IDS) {
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
