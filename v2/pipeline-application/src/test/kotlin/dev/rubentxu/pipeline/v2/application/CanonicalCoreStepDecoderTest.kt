package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CanonicalCoreStepDecoderTest {
    @Test
    fun `core sleep is no longer legacy decodable (registry-routed since S2-A2 G5)`() {
        val node = OpaqueStepNode(
            id = StepId("build/sleep-0"),
            pluginStepId = PluginStepId("core.sleep"),
            payload = VersionedStepPayload(
                "dsl-v1",
                """{"kind":"sleep","seconds":3}""",
            ),
        )

        // Fail-closed: unknown legacy plugin ids MUST be rejected, never re-routed.
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalCoreStepDecoder.decode(node)
        }
    }

    // core.file.writeFile decoder branch removed at LFC-2E1-S2-A3/G5 (LEGACY_REMOVED);
    // writeFile now decodes via CoreWriteFileStep codec in the registry path.

    // S2-A4 / G5: the emitEvent decoder branch was removed (LEGACY_REMOVED). The raw
    // core.emit.event envelope is consumed pre-decode by StructuralOverlayProjection and
    // executively by CoreEmitEventStep via the registry codec — never by this decoder.
    @Test
    fun `throws IllegalArgumentException for unknown plugin step id`() {
        val node = OpaqueStepNode(
            id = StepId("build/unknown-0"),
            pluginStepId = PluginStepId("core.unknown"),
            payload = VersionedStepPayload(
                "dsl-v1",
                """{"kind":"unknown"}""",
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            CanonicalCoreStepDecoder.decode(node)
        }
    }
}
