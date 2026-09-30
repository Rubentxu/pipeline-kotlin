package dev.rubentxu.pipeline.v2.domain.post

import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.StepNode
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The pure `post` decision: which finalizers fire, and in what order.
 *
 * Everything here is a total function of its inputs. No coordinator, no process,
 * no event sink — the finalizer ORDER is a contract, so it must be provable
 * without running anything.
 */
class PostPlannerTest {

    private fun node(name: String): StepNode = OpaqueStepNode(
        id = StepId("post-$name"),
        pluginStepId = PluginStepId("core.echo"),
        payload = VersionedStepPayload("1", name),
    )

    private fun planOf(vararg pairs: Pair<PostCondition, List<String>>) = PostPlan(
        bodies = pairs.associate { (condition, names) -> condition to names.map(::node) },
    )

    /** Strip the `post-` prefix the [node] factory adds, for readable assertions. */
    private fun List<StepNode>.names(): List<String> = map { it.id.value.removePrefix("post-") }

    // ---- membership: the Jenkins subset ---------------------------------------

    @Test
    @DisplayName("S2B-POST-001: always and cleanup fire for EVERY outcome")
    fun alwaysAndCleanupFireForEveryOutcome() {
        for (outcome in listOf(
            StageOutcome.Succeeded,
            StageOutcome.Unstable,
            StageOutcome.Failed,
            StageOutcome.Aborted,
            StageOutcome.Skipped,
        )) {
            assertTrue(PostCondition.ALWAYS.selects(outcome), "always must fire for $outcome")
            assertTrue(PostCondition.CLEANUP.selects(outcome), "cleanup must fire for $outcome")
        }
    }

    @Test
    @DisplayName("S2B-POST-002: success fires only on success")
    fun successOnlyOnSuccess() {
        assertTrue(PostCondition.SUCCESS.selects(StageOutcome.Succeeded))
        for (outcome in listOf(StageOutcome.Failed, StageOutcome.Unstable, StageOutcome.Aborted, StageOutcome.Skipped)) {
            assertTrue(!PostCondition.SUCCESS.selects(outcome), "success must NOT fire for $outcome")
        }
    }

    @Test
    @DisplayName("S2B-POST-003: failure fires only on failure")
    fun failureOnlyOnFailure() {
        assertTrue(PostCondition.FAILURE.selects(StageOutcome.Failed))
        for (outcome in listOf(StageOutcome.Succeeded, StageOutcome.Unstable, StageOutcome.Aborted, StageOutcome.Skipped)) {
            assertTrue(!PostCondition.FAILURE.selects(outcome), "failure must NOT fire for $outcome")
        }
    }

    @Test
    @DisplayName("S2B-POST-004: unsuccessful covers failure and unstable but NOT a skip")
    fun unsuccessfulCoversFailureAndUnstableOnly() {
        assertTrue(PostCondition.UNSUCCESSFUL.selects(StageOutcome.Failed))
        assertTrue(PostCondition.UNSUCCESSFUL.selects(StageOutcome.Unstable))
        assertTrue(
            !PostCondition.UNSUCCESSFUL.selects(StageOutcome.Succeeded),
            "a successful stage is not unsuccessful",
        )
        assertTrue(
            !PostCondition.UNSUCCESSFUL.selects(StageOutcome.Skipped),
            "a stage that never ran is not unsuccessful; it did not happen",
        )
    }

    @Test
    @DisplayName("S2B-POST-005: a skip still runs always and cleanup, and nothing else")
    fun aSkipRunsOnlyAlwaysAndCleanup() {
        val plan = planOf(
            PostCondition.ALWAYS to listOf("always"),
            PostCondition.SUCCESS to listOf("success"),
            PostCondition.FAILURE to listOf("failure"),
            PostCondition.UNSTABLE to listOf("unstable"),
            PostCondition.ABORTED to listOf("aborted"),
            PostCondition.UNSUCCESSFUL to listOf("unsuccessful"),
            PostCondition.CLEANUP to listOf("cleanup"),
        )

        assertEquals(
            listOf("always", "cleanup"),
            PostPlanner.plan(plan, StageOutcome.Skipped).names(),
        )
    }

    // ---- ordering: the part that must never drift ----------------------------

    @Test
    @DisplayName("S2B-POST-006: ordering is versioned, not map-iteration order")
    fun orderingIsVersionedNotMapOrder() {
        // Declared in a deliberately confusing order; the PLAN must not follow it.
        val plan = planOf(
            PostCondition.CLEANUP to listOf("cleanup"),
            PostCondition.UNSUCCESSFUL to listOf("unsuccessful"),
            PostCondition.ALWAYS to listOf("always"),
            PostCondition.FAILURE to listOf("failure"),
        )

        assertEquals(
            listOf("always", "failure", "unsuccessful", "cleanup"),
            PostPlanner.plan(plan, StageOutcome.Failed).names(),
        )
    }

    @Test
    @DisplayName("S2B-POST-007: cleanup is always LAST")
    fun cleanupIsAlwaysLast() {
        val plan = planOf(
            PostCondition.ALWAYS to listOf("always"),
            PostCondition.CLEANUP to listOf("cleanup"),
        )
        for (outcome in listOf(StageOutcome.Succeeded, StageOutcome.Failed, StageOutcome.Skipped, StageOutcome.Aborted)) {
            val order = PostPlanner.plan(plan, outcome).names()
            assertEquals(
                listOf("always", "cleanup"),
                order,
                "cleanup must follow always for $outcome",
            )
        }
    }

    @Test
    @DisplayName("S2B-POST-008: several blocks under one condition keep the author's order")
    fun severalBlocksKeepAuthorOrder() {
        val plan = planOf(PostCondition.ALWAYS to listOf("first", "second", "third"))

        assertEquals(
            listOf("first", "second", "third"),
            PostPlanner.plan(plan, StageOutcome.Succeeded).names(),
        )
    }

    // ---- decision observability: the event projection ------------------------

    @Test
    @DisplayName("S2B-POST-009: selected/skipped projections mirror plan() exactly")
    fun decisionProjectionsMirrorThePlan() {
        val plan = planOf(
            PostCondition.ALWAYS to listOf("always"),
            PostCondition.FAILURE to listOf("failure"),
            PostCondition.CLEANUP to listOf("cleanup"),
        )
        for (outcome in listOf(
            StageOutcome.Succeeded,
            StageOutcome.Unstable,
            StageOutcome.Failed,
            StageOutcome.Aborted,
            StageOutcome.Skipped,
        )) {
            val planned = PostPlanner.plan(plan, outcome)
            val selected = PostPlanner.selectedConditions(plan, outcome)
            val skipped = PostPlanner.skippedConditions(plan, outcome)

            // Every planned node belongs to a selected condition, and every
            // selected condition contributed its whole block.
            assertEquals(planned.size, selected.sumOf { plan.bodies[it]!!.size })
            assertEquals(
                (selected + skipped).sortedBy { PostCondition.EXECUTION_ORDER.indexOf(it) },
                PostCondition.EXECUTION_ORDER.filter { plan.bodies.containsKey(it) },
                "selected + skipped must partition the declared set (concatenation order may differ)",
            )
        }
    }

    // ---- totality ------------------------------------------------------------

    @Test
    @DisplayName("S2B-POST-010: an empty plan plans nothing, for every outcome")
    fun emptyPlanPlansNothing() {
        for (outcome in listOf(StageOutcome.Succeeded, StageOutcome.Failed, StageOutcome.Skipped)) {
            assertTrue(
                PostPlanner.plan(PostPlan(), outcome).isEmpty(),
                "an empty plan must produce no finalizers for $outcome",
            )
        }
    }

    @Test
    @DisplayName("S2B-POST-011: EXECUTION_ORDER contains every declared condition exactly once")
    fun executionOrderIsCompleteAndUnique() {
        assertEquals(PostCondition.entries.toSet(), PostCondition.EXECUTION_ORDER.toSet())
        assertEquals(PostCondition.entries.size, PostCondition.EXECUTION_ORDER.size)
    }

    // ---- the closed outcome mapping ------------------------------------------

    @Test
    @DisplayName("S2B-POST-012: every coordinator outcome maps; unknown fails closed")
    fun outcomeMappingIsTotalOverTheEventVocabulary() {
        assertEquals(StageOutcome.Succeeded, PostCondition.outcomeOf("success"))
        assertEquals(StageOutcome.Unstable, PostCondition.outcomeOf("unstable"))
        assertEquals(StageOutcome.Failed, PostCondition.outcomeOf("failed"))
        assertEquals(StageOutcome.Aborted, PostCondition.outcomeOf("aborted"))
        assertEquals(StageOutcome.Skipped, PostCondition.outcomeOf("skipped"))
        // An unknown string must NOT be silently read as success: that would
        // skip failure blocks at the exact moment they matter.
        assertNull(PostCondition.outcomeOf("SUCCESS"))
        assertNull(PostCondition.outcomeOf(""))
        assertNull(PostCondition.outcomeOf("green"))
    }
}
