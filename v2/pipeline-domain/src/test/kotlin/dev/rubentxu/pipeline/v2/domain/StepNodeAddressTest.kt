package dev.rubentxu.pipeline.v2.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The rule that builds a body child's ADDRESS.
 *
 * An address is HOW a child of a body is called: the name the plugin gave it, or
 * its kind when the plugin gave none. It is not the same thing as the kind — the
 * kind is WHAT runs — and the difference is what lets a body hold two steps of the
 * same kind without the engine having to guess which one was meant.
 *
 * One rule, and the behaviour that predates named bodies survives it untouched: a
 * body that names nothing is addressed exactly as it was.
 */
class StepNodeAddressTest {

    @Test
    fun `a child nobody named is addressed by its kind`() {
        val node = OpaqueStepNode(
            id = StepId("body/sh-0"),
            pluginStepId = PluginStepId("core.sh"),
            payload = VersionedStepPayload("dsl-v1", "{}"),
        )

        assertEquals(PluginStepId("core.sh"), node.address)
    }

    @Test
    fun `a named child is addressed by its name, not by its kind`() {
        val node = OpaqueStepNode(
            id = StepId("body/sh-0"),
            pluginStepId = PluginStepId("core.sh"),
            payload = VersionedStepPayload("dsl-v1", "{}"),
            slot = PluginStepId("reserve.forward"),
        )

        assertEquals(PluginStepId("reserve.forward"), node.address)
    }

    @Test
    fun `the kind of a named child is still what runs`() {
        val node = BlockStepNode(
            id = StepId("body/dir-0"),
            pluginStepId = PluginStepId("core.dir"),
            payload = VersionedStepPayload("dsl-v1", "{}"),
            body = emptyList(),
            slot = PluginStepId("reserve.compensate"),
        )

        assertEquals(PluginStepId("core.dir"), node.pluginStepId)
        assertEquals(PluginStepId("reserve.compensate"), node.address)
    }
}
