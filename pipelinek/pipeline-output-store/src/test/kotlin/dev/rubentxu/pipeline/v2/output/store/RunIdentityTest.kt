package dev.rubentxu.pipeline.v2.output.store

import dev.rubentxu.pipeline.v2.domain.RunId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `runDirectoryName` decides the on-disk name of a run in the artifact plane, and it either names
 * the run with the run's OWN identity or refuses. The name IS the identity here: a run's frames,
 * its streams declaration and its owner record all live under a directory whose name is the run
 * id, so two identities that resolved to one name would share one frame index — and a reader
 * asking about one of them would be served the other's frames without any sign that they were
 * not the ones it asked for.
 *
 * The function is pure: a `String` in, a `String` out or a typed refusal out. No disk, no clock,
 * no subprocess, which is what AGENTS.md asks of a unit test of a domain rule.
 *
 * ## The two rules this pins
 *
 *  1. **The name is the identity.** A run's directory is named after the run, verbatim. An
 *     identity outside the durable alphabet is refused instead of being folded into a name that
 *     belongs to another run.
 *  2. **There is ONE owner of the alphabet.** The rule is asked of the domain's [RunId], which is
 *     the same authority the durable lease asks. An artifact plane that accepted a run the lease
 *     refuses — or the other way round — would have two answers to "what is a run identity", and
 *     the operator would meet both.
 *
 * ## Discriminative mutations the test must catch
 *   * The `isNameable` check removed from `runDirectoryName` — every identity would be named, and
 *     the row "an identity with a path separator is refused" is what lights RED.
 *   * `runDirectoryName` answering `safeStreamName(runId)` — the fold would make `a/b` and `a_b`
 *     the same name; the row "two identities that collide do not share a name" is what lights RED,
 *     and the rows about nameable identities stay GREEN, which is what makes the mutation local.
 *   * The alphabet copied into this module instead of asked of [RunId] — a copy that drifts is
 *     caught by the row "this plane accepts exactly what the lease accepts". A copy that is
 *     IDENTICAL is invisible to this instrument: only review catches that, and this file says so
 *     rather than claiming a coverage it does not have.
 */
class RunIdentityTest {

    // --- the guarantee: a nameable identity names ITSELF ----------------------------------

    @Test
    fun `a nameable identity is its own directory name`() {
        // The name IS the identity. A rule that returned anything else — a prefix, a suffix, a
        // derived key — would make the name and the identity two different things, and the
        // identity would have to be recoverable from the name for the directory to be findable.
        NAMEABLE.forEach { identity ->
            assertEquals(identity, runDirectoryName(identity))
        }
    }

    @Test
    fun `the durable name of a run does not change`() {
        // The format already written on disk keeps its names. Every identity this plane accepts
        // names the same directory it named before, so a run written by an earlier build is still
        // found by this one. A rule that renamed the survivors would strand every run already on
        // disk, which is why the refusal exists instead of a rename.
        assertEquals("run-7", runDirectoryName("run-7"))
        assertEquals("a_b", runDirectoryName("a_b"))
        assertEquals("9f1c2e5a-0b3d-4f6a-8c1e-2d4b6f8a0c12", runDirectoryName("9f1c2e5a-0b3d-4f6a-8c1e-2d4b6f8a0c12"))
        assertEquals("A.B", runDirectoryName("A.B"))
    }

    // --- the refusal: an identity that cannot name a directory is refused ------------------

    @Test
    fun `an identity with a path separator is refused`() {
        // THE ROW. A separator inside the identity is what makes a name able to leave the artifact
        // root, so it is refused at the point where the name is decided and never reaches a path.
        val thrown = assertThrows(UnnameableRunIdentity::class.java) {
            runDirectoryName("a/b")
        }
        assertEquals("a/b", thrown.runId)
    }

    @Test
    fun `an identity outside the durable alphabet is refused`() {
        NOT_NAMEABLE.forEach { identity ->
            assertThrows(UnnameableRunIdentity::class.java) {
                runDirectoryName(identity)
            }
        }
    }

    @Test
    fun `the refusal names the identity it refused`() {
        // A refusal that does not say WHICH identity was refused leaves the operator guessing which
        // of the two names on the command line was the wrong one.
        val thrown = assertThrows(UnnameableRunIdentity::class.java) {
            runDirectoryName("weird name")
        }
        assertTrue(
            thrown.message?.contains("weird name") == true,
            "the message must carry the refused identity: <${thrown.message}>",
        )
    }

    @Test
    fun `the refusal is a typed refusal`() {
        // The caller renders the refusal through the CLI's own vocabulary, and it can only do that
        // if the refusal is a TYPE it can recognise. An unchecked exception that is not one of the
        // product's own shapes reaches the operator as a stack trace.
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            runDirectoryName("a/b")
        }
        assertTrue(thrown is UnnameableRunIdentity)
    }

    // --- the collision this rule exists to prevent ----------------------------------------

    @Test
    fun `two identities that collide when folded do not share a name`() {
        // `a/b` and `a_b` are two different runs. Folding made them one directory, one frame file
        // and one owner record, so one run's frame index answered with the other's frames. The
        // refusal is what keeps them apart: one of them names its directory and the other has none.
        val folded = assertThrows(UnnameableRunIdentity::class.java) {
            runDirectoryName("a/b")
        }
        val nameable = runDirectoryName("a_b")
        assertEquals("a_b", nameable)
        assertNotEquals(nameable, folded.runId)
    }

    @Test
    fun `every identity the alphabet rejects is refused and every one it accepts is named`() {
        // The partition, stated as a partition: there is no third outcome. A nameable identity is
        // named, and an identity outside the alphabet is refused — nothing is quietly repaired.
        NAMEABLE.forEach { identity ->
            assertEquals(identity, runDirectoryName(identity))
        }
        NOT_NAMEABLE.forEach { identity ->
            assertThrows(UnnameableRunIdentity::class.java) { runDirectoryName(identity) }
        }
    }

    // --- ONE owner of the alphabet: the same set the durable lease accepts ------------------

    @Test
    fun `this plane accepts exactly the identities the durable lease accepts`() {
        // The lease asks `RunId.fileKey` before it names a lock file, and refuses what it cannot
        // name. This plane asks the same authority. Two planes answering "what is a run identity"
        // differently is how a run reaches a store that cannot hold it, so the two sets have to be
        // the same set — and the agreement is asserted against the OWNER, not against a copy.
        (NAMEABLE + NOT_NAMEABLE).forEach { identity ->
            val leaseAccepts = RunId.fileKey(identity) != null
            val planeAccepts = runCatching { runDirectoryName(identity) }.isSuccess
            assertEquals(
                leaseAccepts,
                planeAccepts,
                "the lease and the artifact plane must agree about '$identity'",
            )
        }
    }

    @Test
    fun `the identity the lease names is the identity this plane names`() {
        // Agreeing about WHICH identities are accepted is not enough: the accepted identity has to
        // name the same thing in both planes, or a run the lease holds is not the run this plane
        // keeps.
        NAMEABLE.forEach { identity ->
            assertEquals(RunId.fileKey(identity), runDirectoryName(identity))
        }
    }

    @Test
    fun `an identity the lease cannot name has no key here either`() {
        // The mirror of the row above, stated for the refusal: the two planes do not merely
        // agree about the accepted ones, they refuse the same rejected ones.
        NOT_NAMEABLE.forEach { identity ->
            assertNull(RunId.fileKey(identity))
            assertThrows(UnnameableRunIdentity::class.java) { runDirectoryName(identity) }
        }
    }

    private companion object {
        /** Identities inside the durable alphabet. */
        val NAMEABLE = listOf(
            "run-7",
            "a_b",
            "A.B",
            "0",
            "9f1c2e5a-0b3d-4f6a-8c1e-2d4b6f8a0c12",
            "x".repeat(64),
        )

        /** Identities outside it: a separator, a space, a character the alphabet does not carry. */
        val NOT_NAMEABLE = listOf(
            "a/b",
            "a\\b",
            "a b",
            "a:b",
            "a/b/c",
            "../escape",
            "",
        )
    }
}
