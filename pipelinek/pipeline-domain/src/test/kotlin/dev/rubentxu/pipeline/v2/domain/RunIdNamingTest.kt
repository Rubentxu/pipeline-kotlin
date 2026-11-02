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
 * The guarantee holds across filesystems too: a name the alphabet admits is the
 * same name on every volume, including the ones that fold case, so two
 * identities that differ only in case cannot both carry one.
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

    /**
     * The identities with no name.
     *
     * The uppercase entries are there because a durable name must not depend on
     * the filesystem's case distinction: on a volume that folds case — APFS by
     * default, exFAT — the same identity in upper case and in lower case are ONE
     * location, and the durable
     * name IS the identity, so at most one of them may be nameable.
     */
    private val notNameable = listOf(
        "a/b",
        "a b",
        "../escape",
        "a:b",
        "",
        "a\\b",
        "RUN-42",
        "Run",
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

    @Test
    fun `dos identidades que solo difieren en caja no pueden nombrar ambas`() {
        // The pair that a case-insensitive volume confuses: 'run-42' and
        // in lower case and in upper case are ONE directory there. The durable
        // name IS the identity,
        // so at most one of them may carry a name; the other is refused at the
        // border instead of landing on somebody else's location.
        assertEquals("run-42", RunId.fileKey("run-42"), "'run-42' must keep its own name")
        assertNull(RunId.fileKey("RUN-42"), "'RUN-42' must have no name: it would name the same location")
    }
}
