package dev.rubentxu.pipeline.v2.domain.durable

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The guarantee: AwaitAll folds branches by severity first and by branch index only second.
 * The capacity this serves: a parallel aggregate's single terminal state. A stage with three
 * branches — one failed, one unstable, one clean — must report the failure, because a caller
 * reading "unstable" would retry work whose real cause was already known. Severity has to win
 * over position, and that ordering is not something a reader of the call site can infer.
 * The failure branch carries its message, so these assertions pin *which* branch was chosen, not
 * just what class it fell into. That is what makes the tie-break rows discriminate: an
 * implementation that folded by lowest index first and classified afterwards would return a
 * perfectly plausible-looking [BranchTerminal.Unstable] for the dominance row and still satisfy a
 * test that only checked the class.
 * The fold sits on a published boundary that production callers depend on, and had no coverage
 * of its own.
*/
class FoldAwaitAllTest {

    @Test
    fun `a failure dominates an unstable branch that sits at a lower index`() {
        // The ordering row. Lowest-index-first would answer Unstable here and lose the cause.
        val result = foldAwaitAll(
            mapOf(
                1 to BranchTerminal.Unstable,
                2 to BranchTerminal.Failed("the real cause"),
            ),
        )

        assertEquals(
            BranchTerminal.Failed("the real cause"),
            result,
            "severity outranks position: a stage with a known failure must not report as merely unstable",
        )
    }

    @Test
    fun `the lowest branch index breaks a tie within the same severity`() {
        val result = foldAwaitAll(
            mapOf(
                7 to BranchTerminal.Failed("later branch"),
                2 to BranchTerminal.Failed("earlier branch"),
                5 to BranchTerminal.Failed("middle branch"),
            ),
        )

        assertEquals(
            BranchTerminal.Failed("earlier branch"),
            result,
            "deterministic replay needs one branch, and the lowest index is the one this contract names",
        )
    }

    @Test
    fun `an unstable branch is the answer only when nothing failed`() {
        assertEquals(
            BranchTerminal.Unstable,
            foldAwaitAll(mapOf(0 to BranchTerminal.Succeeded, 3 to BranchTerminal.Unstable)),
            "with no failure present, instability is what the caller must be told",
        )
    }

    @Test
    fun `branches that are all clean fold to success`() {
        assertEquals(
            BranchTerminal.Succeeded,
            foldAwaitAll(mapOf(0 to BranchTerminal.Succeeded, 1 to BranchTerminal.Succeeded)),
            "nothing to report means success, not a missing value",
        )
    }

    @Test
    fun `no branches at all still folds to success`() {
        // Vacuous on purpose: AwaitAll over zero branches has nothing to fail.
        assertEquals(
            BranchTerminal.Succeeded,
            foldAwaitAll(emptyMap()),
            "an empty fold must not invent a failure or an instability",
        )
    }
}
