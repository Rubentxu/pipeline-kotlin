package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * B1.2c2-CDE.2-a: the structural phase is a step-agnostic envelope gate that precedes typed decode. It
 * validates only schema version and a well-formed JSON-object payload, rejecting anything else as
 * [StructuralPreparation.Rejected] (SCHEMA) without reading any concrete field. This is what preserves
 * the decode-first laws (C3/C5: a structurally-invalid node is never reused and never reaches the
 * executor) once typed decode later moves behind durable resolution.
 */
@Timeout(10)
class CanonicalStructuralPreparationTest {

    private fun node(schemaVersion: String, encoded: String) = OpaqueStepNode(
        id = StepId("stage-0-step-0"),
        pluginStepId = PluginStepId("core.echo"),
        payload = VersionedStepPayload(schemaVersion = schemaVersion, encoded = encoded),
    )

    @Test
    fun `a well-formed dsl-v1 object is ready with a structural invocation`() {
        val result = CanonicalStructuralPreparation.prepare(
            node("dsl-v1", """{"kind":"echo","text":"hi"}"""),
        )
        val ready = assertInstanceOf(StructuralPreparation.Ready::class.java, result)
        assertEquals(PluginStepId("core.echo"), ready.invocation.stepKey)
        assertEquals("dsl-v1", ready.invocation.schemaVersion)
        // Structural model must carry the persisted payload losslessly.
        assertEquals("""{"kind":"echo","text":"hi"}""", ready.invocation.encodedInput.value)
    }

    @Test
    fun `an unsupported schema version is rejected as SCHEMA without reading fields`() {
        val result = CanonicalStructuralPreparation.prepare(
            node("dsl-v0", """{"kind":"sh","command":"exit 0"}"""),
        )
        val rejected = assertInstanceOf(StructuralPreparation.Rejected::class.java, result)
        assertEquals(true, rejected.reason.contains("dsl-v0"))
    }

    @Test
    fun `a malformed payload is rejected as SCHEMA`() {
        val result = CanonicalStructuralPreparation.prepare(node("dsl-v1", "{not-valid-json"))
        assertInstanceOf(StructuralPreparation.Rejected::class.java, result)
    }

    @Test
    fun `a non-object payload is rejected as SCHEMA`() {
        // dsl-v1 envelopes are JSON objects; a bare string/scalar is not structurally a step envelope.
        val result = CanonicalStructuralPreparation.prepare(node("dsl-v1", "\"just-a-string\""))
        assertInstanceOf(StructuralPreparation.Rejected::class.java, result)
    }
}
