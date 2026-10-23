package dev.rubentxu.pipeline.v2.output.store

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The guarantee: a stream's bytes belong to ONE identity.
 *
 * The capacity this serves: an output plane whose bytes can be attributed. The plane files a
 * stream under a folded name, and the fold is lossy — two different identities reach one
 * directory. When that happens, one of them must be refused: bytes from two operations in one
 * directory cannot be attributed to either of them afterwards, and a reader that asked for one
 * of them would be handed the other's.
 *
 * These rows are pure: what the directory records, and who claims it. No directory, no store, no
 * clock.
 */
class StreamDirectoryOwnershipTest {

    @Test
    fun `a directory that names nobody may be claimed`() {
        val ownership = decideStreamDirectoryOwnership(recorded = null, claimed = "run/op/stdout")

        assertEquals(
            StreamDirectoryOwnership.Unclaimed,
            ownership,
            "a directory with no owner is free: the writer that fills it becomes its owner",
        )
    }

    @Test
    fun `a directory that names the claimer is owned by it`() {
        val ownership = decideStreamDirectoryOwnership(recorded = "run/op/stdout", claimed = "run/op/stdout")

        assertEquals(
            StreamDirectoryOwnership.Owned,
            ownership,
            "the identity that already owns the directory keeps it",
        )
    }

    @Test
    fun `a directory that names somebody else is foreign`() {
        val ownership = decideStreamDirectoryOwnership(recorded = "run/op/stdout", claimed = "run/op_a/stdout")

        val foreign = assertInstanceOf(StreamDirectoryOwnership.Foreign::class.java, ownership)
        assertEquals("run/op/stdout", foreign.owner, "the refusal names the identity that owns the bytes")
        assertEquals("run/op_a/stdout", foreign.claimer, "the refusal names the identity that was refused")
    }

    @Test
    fun `identities are compared whole, not by prefix`() {
        val ownership = decideStreamDirectoryOwnership(recorded = "run/op/stdout", claimed = "run/op/stdout/extra")

        assertTrue(
            ownership is StreamDirectoryOwnership.Foreign,
            "an identity that starts with another one is a different identity: got $ownership",
        )
    }

    @Test
    fun `an empty record refuses the claim`() {
        // A record that was truncated to nothing says nothing about who owns the bytes, and a
        // record that says nothing cannot be read as "mine".
        val ownership = decideStreamDirectoryOwnership(recorded = "", claimed = "run/op/stdout")

        assertTrue(
            ownership is StreamDirectoryOwnership.Foreign,
            "a record that names nobody cannot be the claimer's own record: got $ownership",
        )
    }
}
