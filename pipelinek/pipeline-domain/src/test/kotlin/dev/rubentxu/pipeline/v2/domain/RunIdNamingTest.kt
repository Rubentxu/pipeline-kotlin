package dev.rubentxu.pipeline.v2.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The guarantee: a run identity can be a NAME in the durable plane, and two
 * different identities never share one.
 *
 * ## What this test is for
 *
 * A run identity becomes a name when it is persisted: the lease that grants its
 * publishing authority, the directory that holds its artefacts. A name that were
 * a transformation of the identity — a folded or escaped form — could give two
 * identities the same name, and then two runs would share one lease: one of
 * them would be refused as "already owned" and the other would take the lease
 * over, advancing the fencing token and fencing out a publisher that had done
 * nothing wrong.
 *
 * So the guarantee is INJECTIVE, and the rule that carries it is total: an
 * identity either can name a location and its key is the identity itself, or it
 * cannot and it has NO key at all.
 *
 * ## Why the rule refuses instead of encoding
 *
 * An escaped key would be injective too, but it would leave the product with two
 * answers to "what does a safe run id look like": the one that refuses and the
 * one that encodes. Everything else in the product refuses, so this refuses too.
 *
 * The rule is pure, so this table is the whole guarantee: no filesystem, no
 * process and no clock.
 */
class RunIdNamingTest {

    /**
     * The identities the product itself produces, plus the ones it must refuse.
     *
     * The first group is what the product generates: a UUIDv4 (the durable run
     * id) and a hex digest (the definition id). The second group is what can
     * arrive from outside, because a run id is also an argument the operator
     * types and a value the SDK is allowed to supply.
     */
    private val nameable = listOf(
        "run-42",
        "a_b",
        "0189a3f2-1b4c-7d5e-8f90-0123456789ab",
        "9f2b7c1d4e6a8b0c",
        "run.42",
    )

    private val notNameable = listOf(
        "a/b",
        "a b",
        "../escape",
        "a:b",
        "",
        "a\\b",
    )

    @Test
    fun `una identidad que puede nombrar conserva su nombre`() {
        for (id in nameable) {
            assertEquals(id, RunId.fileKey(id), "'$id' must keep its own name")
            assertTrue(RunId.isNameable(id), "'$id' must be nameable")
        }
    }

    @Test
    fun `una identidad que no puede nombrar no tiene llave`() {
        for (id in notNameable) {
            assertNull(RunId.fileKey(id), "'$id' must not be turned into a name")
            assertTrue(!RunId.isNameable(id), "'$id' must not be nameable")
        }
    }

    @Test
    fun `dos identidades distintas nunca comparten llave`() {
        val keys = nameable.map { RunId.fileKey(it) }

        assertEquals(
            nameable.size,
            keys.toSet().size,
            "two identities must never reach the same key: $nameable",
        )
    }

    @Test
    fun `una identidad que no puede nombrar no colisiona con otra que si`() {
        // The pair that a folding rule confuses: 'a/b' folded onto '_' is
        // 'a_b', so both would name the same lease. Here one of them has no
        // name at all, so the collision cannot happen.
        assertNull(RunId.fileKey("a/b"), "'a/b' must have no key")
        assertEquals("a_b", RunId.fileKey("a_b"), "'a_b' must keep its own key")
    }
}
