package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.ParallelBranchFinished
import dev.rubentxu.pipeline.v2.events.ParallelBranchStarted
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.StageFinished
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.events.durable.EventStoreAdmission
import dev.rubentxu.pipeline.v2.events.durable.EventStorePurpose
import dev.rubentxu.pipeline.v2.events.durable.admitEventStore
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.harness.model.EventContract
import dev.rubentxu.pipeline.v2.harness.model.ExpectedRunOutcome
import dev.rubentxu.pipeline.v2.harness.model.VerificationResult
import dev.rubentxu.pipeline.v2.harness.model.ViolationRule
import dev.rubentxu.pipeline.v2.harness.verify.EventHarness
import java.nio.file.Path
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * What `pipelinek events verify` declares over a durable history of a parallel stage whose
 * two branches slug to the same text.
 *
 * The branches `a b` and `a-b` produce the same step name — the branch goes through a slug —
 * so the two steps of the plane share stage, index and name, and a name-only identity folds
 * them into one. The compiler now refuses to MINT that plane (`IdentityScope`), but a
 * durable journal does not have to have been written by today's compiler: a run recorded
 * before the refusal, or by any other producer, is exactly the history the verification
 * reads. That is the consumer this row exercises: the store keeps the rows, the harness
 * reads them back, and the law declares — or does not declare — the contradiction.
 *
 * When the step's events carry the durable identity of the operation the step runs as
 * (`opId`), the two branches are two operations and a failure beside a success is not a
 * contradiction. The same plane without the operation identity keeps the name's blindness:
 * the law still declares it. The pair is the control each row needs — neither green has
 * never been red.
 */
class BranchSlugCollisionVerifyTest {

    @TempDir
    lateinit var storeDir: Path

    @Test
    @DisplayName("un historial con dos ramas del mismo texto y su operación no declara la contradicción")
    fun `a history with two same-text branches carrying their operation declares no contradiction`() {
        val (store, events) = write("run-slug-with-op", withOpId = true)
        try {
            val typed = EventHarness.typedHistory(events)
            val result = EventHarness.verify(
                typed,
                EventContract(
                    version = EventContract.CURRENT_VERSION,
                    name = "branch-slug-collision",
                    expect = ExpectedRunOutcome.FAILURE,
                    constraints = emptyList(),
                ),
            )

            val contradictory = (result as? VerificationResult.Invalid)
                ?.violations
                ?.filter { it.rule == ViolationRule.LAW_CONTRADICTORY_TERMINAL }
                .orEmpty()
            assertTrue(contradictory.isEmpty()) {
                "the law declared a contradiction over two distinct operations: " +
                    contradictory.joinToString("\n") { it.message }
            }

            // The green means what it says only if the identity survived the store: the two
            // branches read back from the durable rows with two distinct operation identities.
            val opIds = events.filterIsInstance<StepStarted>().mapNotNull { it.opId }.toSet()
            assertEquals(setOf(OP_FIRST, OP_SECOND), opIds) {
                "the operation identities did not survive the store: read back $opIds"
            }
        } finally {
            store.close()
        }
    }

    @Test
    @DisplayName("el mismo historial sin la identidad de la operación sigue declarando la contradicción")
    fun `the same history without the operation identity keeps declaring the contradiction`() {
        val (store, events) = write("run-slug-without-op", withOpId = false)
        try {
            val typed = EventHarness.typedHistory(events)
            val result = EventHarness.verify(
                typed,
                EventContract(
                    version = EventContract.CURRENT_VERSION,
                    name = "branch-slug-collision-limit",
                    expect = ExpectedRunOutcome.FAILURE,
                    constraints = emptyList(),
                ),
            )

            val contradictory = (result as? VerificationResult.Invalid)
                ?.violations
                ?.filter { it.rule == ViolationRule.LAW_CONTRADICTORY_TERMINAL }
                .orEmpty()
            assertTrue(contradictory.isNotEmpty()) {
                "without the operation identity the two branches are one step for the law, " +
                    "so the contradiction had to be declared; the verification said: $result"
            }
        } finally {
            store.close()
        }
    }

    // --- the fixture ----------------------------------------------------------------

    private companion object {
        const val RUN = "run-under-verification"
        const val OP_FIRST = "op-branch-1"
        const val OP_SECOND = "op-branch-2"

        /** The step name both branches slug into: the branch goes through a slug. */
        const val STEP_NAME = "fan/branch-a-b/sh-0"
    }

    /**
     * Writes one branch-collision plane into a real durable store and reads it back.
     * This is the plane the compiler refuses to mint today, and exactly the history a run
     * recorded before that refusal left behind: two branches of one stage, the same step
     * name, one failure, one success.
     */
    private fun write(
        storeName: String,
        withOpId: Boolean,
    ): Pair<dev.rubentxu.pipeline.v2.events.durable.SqliteEventStore, List<DomainEvent>> {
        val admission = admitEventStore(
            storeDir.resolve("$storeName.db").toString(),
            EventStorePurpose.Produce,
        )
        val store = (admission as EventStoreAdmission.Admitted).store

        val opIdFor: (Int) -> String? = { branch ->
            when {
                !withOpId -> null
                branch == 0 -> OP_FIRST
                else -> OP_SECOND
            }
        }
        val history = listOf<DomainEvent>(
            RunStarted("e1", RUN, 0L, AT, "pipeline.kts"),
            StageStarted("e2", RUN, 0L, AT, 0, "fan"),
            ParallelBranchStarted("e3", RUN, 0L, AT, 0, "a b", 0),
            ParallelBranchStarted("e4", RUN, 0L, AT, 1, "a-b", 0),
            StepStarted("e5", RUN, 0L, AT, 0, 0, STEP_NAME, "sh", opIdFor(0)),
            StepStarted("e6", RUN, 0L, AT, 0, 0, STEP_NAME, "sh", opIdFor(1)),
            StepFailed("e7", RUN, 0L, AT, 0, 0, STEP_NAME, "sh", FailureKind.SCRIPT, "shell exited with code 1", opIdFor(0)),
            StepFinished("e8", RUN, 0L, AT, 0, 0, STEP_NAME, "sh", StepOutcome.SUCCESS_TOKEN, opIdFor(1)),
            ParallelBranchFinished("e9", RUN, 0L, AT, 0, "a b", 0, "failure"),
            ParallelBranchFinished("e10", RUN, 0L, AT, 1, "a-b", 0, "success"),
            StageFinished("e11", RUN, 0L, AT, 0, "fan", "failure"),
            RunFinished("e12", RUN, 0L, AT, "failure", emptyList()),
        )
        history.forEach { store.append(it) }
        store.flush()

        val readBack = store.eventsFor(RUN).toList()
        return store to readBack
    }
}

private val AT: Instant = Instant.ofEpochMilli(1_700_000_000_000L)
