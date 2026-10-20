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
 * a body whose children share an address, a name no child carries, and a name
 * that carries exactly one.
 *
 * An ADDRESS is the name the plugin gave a child, or the child's kind when it
 * gave none. The rule that builds it lives in `StepNode.address`; what is
 * tested here is what the engine does with the addresses once it has them.
 */
class BodySlotsTest {

    @Test
    fun `resolves the one child that carries the slot key`() {
        val resolution = BodySlots.resolve(
            childAddresses = listOf(PluginStepId("core.echo"), PluginStepId("example.saga.forward")),
            slot = PluginStepId("example.saga.forward"),
        )

        val found = assertInstanceOf<SlotResolution.Found>(resolution)
        assertEquals(1, found.childIndex)
    }

    @Test
    fun `resolves the first position when the slot is the first child`() {
        val resolution = BodySlots.resolve(
            childAddresses = listOf(
                PluginStepId("example.saga.forward"),
                PluginStepId("example.saga.compensate"),
            ),
            slot = PluginStepId("example.saga.forward"),
        )

        assertEquals(0, assertInstanceOf<SlotResolution.Found>(resolution).childIndex)
    }

    @Test
    fun `resolves the later position when the slot is not the first child`() {
        val resolution = BodySlots.resolve(
            childAddresses = listOf(
                PluginStepId("example.saga.forward"),
                PluginStepId("example.saga.compensate"),
            ),
            slot = PluginStepId("example.saga.compensate"),
        )

        assertEquals(1, assertInstanceOf<SlotResolution.Found>(resolution).childIndex)
    }

    @Test
    fun `a slot no child carries is missing, not ambiguous`() {
        val resolution = BodySlots.resolve(
            childAddresses = listOf(PluginStepId("example.saga.forward")),
            slot = PluginStepId("example.saga.compensate"),
        )

        val missing = assertInstanceOf<SlotResolution.Missing>(resolution)
        assertEquals(PluginStepId("example.saga.compensate"), missing.slot)
    }

    @Test
    fun `a slot two children carry is ambiguous, and the count is reported`() {
        // Two `sh` steps in one body share an address when nobody named them. A name
        // that fits both does not identify either, and picking the first would give
        // the handler a body it did not ask for.
        val resolution = BodySlots.resolve(
            childAddresses = listOf(PluginStepId("core.sh"), PluginStepId("core.sh")),
            slot = PluginStepId("core.sh"),
        )

        val ambiguous = assertInstanceOf<SlotResolution.Ambiguous>(resolution)
        assertEquals(2, ambiguous.matches)
    }

    @Test
    fun `an empty body resolves nothing`() {
        val resolution = BodySlots.resolve(
            childAddresses = emptyList(),
            slot = PluginStepId("example.saga.forward"),
        )

        assertInstanceOf<SlotResolution.Missing>(resolution)
    }

    @Test
    fun `two children of the SAME kind are told apart by their names`() {
        // The rule that lets a body hold two steps of one kind: the plugin named them,
        // so each one has its own address and each name resolves to exactly one child.
        val addresses = listOf(
            PluginStepId("reserve.forward"),
            PluginStepId("charge.forward"),
        )

        val reserve = BodySlots.resolve(addresses, PluginStepId("reserve.forward"))
        val charge = BodySlots.resolve(addresses, PluginStepId("charge.forward"))

        assertEquals(0, assertInstanceOf<SlotResolution.Found>(reserve).childIndex)
        assertEquals(1, assertInstanceOf<SlotResolution.Found>(charge).childIndex)
    }

    @Test
    fun `two children with the SAME name are ambiguous, not resolved by guessing`() {
        val addresses = listOf(
            PluginStepId("reserve.forward"),
            PluginStepId("reserve.forward"),
        )

        val resolution = BodySlots.resolve(addresses, PluginStepId("reserve.forward"))

        assertEquals(2, assertInstanceOf<SlotResolution.Ambiguous>(resolution).matches)
    }

    @Test
    fun `a name that is not an address of any child is missing`() {
        // The kind of a child is NOT its address once it has been named: asking for the
        // kind of a named child finds nothing, because the name the plugin knows is the
        // one it declared.
        val addresses = listOf(PluginStepId("reserve.forward"))

        val resolution = BodySlots.resolve(addresses, PluginStepId("core.sh"))

        assertInstanceOf<SlotResolution.Missing>(resolution)
    }
}
