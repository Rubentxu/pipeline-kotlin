package dev.rubentxu.pipeline.v2.domain.identity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The encoding a durable operation's canonical text takes when it becomes the `op/` segment
 * of a reference.
 *
 * The OpId of a branch step names the branch frames it runs in with colons, and a segment
 * may not carry a colon. The encoding is the declared bridge: the WHOLE operation text goes
 * into the segment, nothing is trimmed, and what goes in can be read back out — the subject
 * of an event and the `opId` the event publishes name the same operation.
 */
class OperationKeyEncodingTest {

    @Test
    @DisplayName("el opId real de una rama codifica y se lee de vuelta entero")
    fun `a real branch opId encodes and decodes whole`() {
        val realOpId = "run-1-s0-0-bp2-b1:branch-0:core.sh"

        val key = ResourceRefs.operationKey(realOpId)

        assertEquals(realOpId, ResourceRefs.decodeOperationKey(key))
        assertTrue(!key.contains(':')) { "the encoded segment still carries a colon: $key" }
    }

    @Test
    @DisplayName("dos operaciones distintas nunca comparten segmento")
    fun `two distinct operations never share a segment`() {
        val first = ResourceRefs.operationKey("run-1-s0-0-bp2-b0:branch-0:core.sh")
        val second = ResourceRefs.operationKey("run-1-s0-0-bp2-b1:branch-1:core.sh")
        // The pair the naive substitution would fold: a literal separator beside a literal x.
        val third = ResourceRefs.operationKey("run-1-s0-0-bp2-b1-branch-0x3acore.sh")

        assertNotEquals(first, second)
        assertNotEquals(second, third)
    }

    @Test
    @DisplayName("la codificacion es inyectiva sobre todo el alfabeto del opId")
    fun `the encoding is injective over the whole opId alphabet`() {
        val texts = listOf(
            "run-1-s0-0",
            "run-1-s0-0-b1",
            "run_1-s0-0-bp2-b1:branch-0:core.sh",
            "x-run-x3a-bp1-b0:branch",
            "XX:YY",
            "a/b\\c#d?e%f g\ti",
        )

        val keys = texts.map { ResourceRefs.operationKey(it) }
        assertEquals(texts.size, keys.toSet().size) { "two distinct texts encoded alike: $keys" }
        assertEquals(texts, keys.map { ResourceRefs.decodeOperationKey(it) })
    }

    @Test
    @DisplayName("una referencia construida con el segmento codificado es valida")
    fun `a reference built with the encoded segment is valid`() {
        // The property the encoding exists for: the operation's reference constructs without
        // tripping the segment law, whatever the operation text carries.
        val ref = ResourceRefs.operation("run-1", 0, 0, ResourceRefs.operationKey("run-1-s0-0-bp2-b1:branch-0:core.sh"))

        assertEquals(ResourceKind.OPERATION, ref.kind)
        assertEquals("run-1-s0-0-bp2-b1x3abranch-0x3acore.sh", ref.segments.last())
    }

    @Test
    @DisplayName("un texto que la codificacion no produjo no se lee como un segmento")
    fun `a text the encoding never produced is not read as one`() {
        assertNull(ResourceRefs.decodeOperationKey("x"))
        assertNull(ResourceRefs.decodeOperationKey("x3"))
        assertNull(ResourceRefs.decodeOperationKey("xzz"))
    }
}
