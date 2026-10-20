package dev.rubentxu.pipeline.v2.domain.step

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

/**
 * The rules that decide WHICH child of a body a slot names.
 *
 * The rule matters because the slot is the only way a handler chooses between
 * the bodies of its own plan: a Saga picks the FORWARD body on the happy path
 * and the COMPENSATE body after a failure. A name that resolves to the wrong
 * child, or to two of them, does not merely pick badly — it runs a body the
 * handler did not ask for, under the identity of the one it did.
 *
 * So the cases below are the ones the running pipeline cannot easily produce:
 * a body whose children share a key, a name no child carries, and a name that
 * carries exactly one.
 */
class BodySlotsTest {

    @Test
    fun `resolves the one child that carries the slot key`() {
        val resolution = BodySlots.resolve(
            childKeys = listOf(PluginStepId("core.echo"), PluginStepId("example.saga.forward")),
            slot = PluginStepId("example.saga.forward"),
        )

        val found = assertInstanceOf<SlotResolution.Found>(resolution)
        assertEquals(1, found.childIndex)
    }

    @Test
    fun `resolves the first position when the slot is the first child`() {
        val resolution = BodySlots.resolve(
            childKeys = listOf(PluginStepId("example.saga.forward"), PluginStepId("example.saga.compensate")),
            slot = PluginStepId("example.saga.forward"),
        )

        assertEquals(0, assertInstanceOf<SlotResolution.Found>(resolution).childIndex)
    }

    @Test
    fun `resolves the later position when the slot is not the first child`() {
        val resolution = BodySlots.resolve(
            childKeys = listOf(PluginStepId("example.saga.forward"), PluginStepId("example.saga.compensate")),
            slot = PluginStepId("example.saga.compensate"),
        )

        assertEquals(1, assertInstanceOf<SlotResolution.Found>(resolution).childIndex)
    }

    @Test
    fun `a slot no child carries is missing, not ambiguous`() {
        val resolution = BodySlots.resolve(
            childKeys = listOf(PluginStepId("example.saga.forward")),
            slot = PluginStepId("example.saga.compensate"),
        )

        val missing = assertInstanceOf<SlotResolution.Missing>(resolution)
        assertEquals(PluginStepId("example.saga.compensate"), missing.slot)
    }

    @Test
    fun `a slot two children carry is ambiguous, and the count is reported`() {
        // Two `sh` steps in one body share a key. A name that fits both does
        // not identify either, and picking the first would give the handler a
        // body it did not ask for.
        val resolution = BodySlots.resolve(
            childKeys = listOf(PluginStepId("core.sh"), PluginStepId("core.sh")),
            slot = PluginStepId("core.sh"),
        )

        val ambiguous = assertInstanceOf<SlotResolution.Ambiguous>(resolution)
        assertEquals(2, ambiguous.matches)
    }

    @Test
    fun `an empty body resolves nothing`() {
        val resolution = BodySlots.resolve(childKeys = emptyList(), slot = PluginStepId("example.saga.forward"))

        assertInstanceOf<SlotResolution.Missing>(resolution)
    }
}
