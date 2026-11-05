package dev.rubentxu.pipeline.v2.output.store

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * The guarantee: a run's retention pass reaches that run's directories and nobody else's.
 *
 * The capacity this serves: an output plane whose bytes can be released without releasing
 * somebody else's. A release deletes whole directories, and the only thing standing between a
 * directory and the delete is this decision — so a decision nobody can exercise is a rule nobody
 * can keep.
 *
 * ## Why these rows are pure
 * What the index declares, the name of a directory, and the run that claims it. No directory, no
 * store, no clock: the rule is the same one the store applies, and it can be applied here without
 * a disk under it.
 */
class RunDirectoryOwnershipTest {

    @Test
    fun `a directory the index declares to the claiming run is the claiming run's`() {
        val ownership = decideRunDirectoryOwnership(
            directoryName = "run_step1_stdout",
            declaredOwner = "run",
            namespace = RunNamespace("run"),
        )

        assertEquals(
            RunDirectoryOwnership.ClaimingRun,
            ownership,
            "a directory the index declares to the claiming run belongs to it",
        )
    }

    @Test
    fun `a directory the index declares to another run is not the claiming run's`() {
        val ownership = decideRunDirectoryOwnership(
            directoryName = "run_x_step1_stdout",
            declaredOwner = "run_x",
            namespace = RunNamespace("run"),
        )

        assertEquals(
            RunDirectoryOwnership.AnotherRun,
            ownership,
            "a directory another run has declared is not the claiming run's, even when its name " +
                "starts with the claiming run's prefix",
        )
    }

    @Test
    fun `a directory nobody declares is the claiming run's when its name carries the claiming run's prefix`() {
        val ownership = decideRunDirectoryOwnership(
            directoryName = "run_step1_stdout",
            declaredOwner = null,
            namespace = RunNamespace("run"),
        )

        assertEquals(
            RunDirectoryOwnership.ClaimingRun,
            ownership,
            "a directory nothing declares belongs to the run whose prefix its name carries",
        )
    }

    @Test
    fun `a directory nobody declares is the claiming run's even when another run's name starts the same way`() {
        val ownership = decideRunDirectoryOwnership(
            directoryName = "run_x_step1_stdout",
            declaredOwner = null,
            namespace = RunNamespace("run"),
        )

        assertEquals(
            RunDirectoryOwnership.ClaimingRun,
            ownership,
            "nothing declares this directory, so the prefix decides — and a prefix is not an " +
                "identity: this is the residual the declaration cannot close, and it is the " +
                "answer the store gives today",
        )
    }

    @Test
    fun `a directory nobody declares is not the claiming run's when its name carries another run's prefix`() {
        val ownership = decideRunDirectoryOwnership(
            directoryName = "other_step1_stdout",
            declaredOwner = null,
            namespace = RunNamespace("run"),
        )

        assertEquals(
            RunDirectoryOwnership.AnotherRun,
            ownership,
            "a directory nothing declares whose name carries another run's prefix is not the " +
                "claiming run's",
        )
    }

    @Test
    fun `a run identity that cannot name a directory is refused`() {
        assertThrows(UnnameableRunIdentity::class.java) {
            RunNamespace("run/other")
        }
    }
}
