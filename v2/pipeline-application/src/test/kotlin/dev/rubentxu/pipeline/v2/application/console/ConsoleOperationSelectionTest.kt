package dev.rubentxu.pipeline.v2.application.console

import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.StepStarted
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * R4 / WU-RC3 — one selection decision, shared by the single read and the follow loop.
 *
 * ## What these rows replace
 *
 * The rc1 resolver matched the user's `--stage`/`--step` against the **stringified index** in the
 * opId, and treated `--kind` as `val kindOk = true`. So:
 *
 *  - `--stage build` missed against an operation it should have matched, and `--stage 0` hit;
 *  - `--kind literally-anything` matched **every frame in the run** — a flag that was accepted,
 *    never interpreted. That is the "semantic drop" the Semantic Constitution §2 forbids, and it
 *    was invisible because a filter that matches everything looks exactly like a filter that
 *    worked.
 *
 * ## Harness fidelity
 *
 * **HF0 Pure Contract.** [ConsoleOperationSelection.resolve] takes a `Sequence<DomainEvent>` and
 * returns a value. It opens no journal, no store and no filesystem, so driving it directly IS the
 * faithful level and not a shortcut — a harness that stood up a real SqliteEventStore to test
 * this function would be testing SQLite.
 *
 * The events below are hand-built but they are not fabricated *facts*: they are the production
 * event SHAPES, and the rows assert that the resolver reads the fields the engine actually
 * emits. `HARNESS FIDELITY §2` forbids re-deriving a value production owns; nothing here
 * recomputes a selection — the resolver under test is the only thing producing them.
 *
 * ## Mutations
 *
 * - `M-RC3-1` (`--kind` is honoured) → return `AllOperations` whenever `kinds` is non-empty. That
 *   is the rc1 defect verbatim; killed by RC3-4 and RC3-5.
 * - `M-RC3-2` (names resolve) → compare `stageName` to the token's stringified index. Killed by
 *   RC3-1.
 * - `M-RC3-3` (axes AND) → OR the axes together. Killed by RC3-3.
 * - `M-RC3-4` (unresolved is a refusal) → return `Operations(emptySet())`. Killed by RC3-6.
 * - `M-RC3-5` (no journal, name tokens) → admit anyway and drop the axis. Killed by RC3-7.
 * - `M-RC3-6` (segment equality, not substring) → `opId.contains(selected)`. Killed by RC3-8.
 */
class ConsoleOperationSelectionTest {

    private val runId = "run-1"
    private val at = Instant.parse("2026-10-14T00:00:00Z")

    /**
     * A realistic two-stage, three-step run.
     *
     * ```text
     * stage 0 "build" : step 0 "compile" (sh)   step 1 "test"    (sh)
     * stage 1 "test"  : step 0 "report"  (junit)
     * ```
     */
    private val journal: List<DomainEvent> = listOf(
        stageStarted(0, "build"),
        stepStarted(0, 0, "compile", "sh"),
        stepStarted(0, 1, "test", "sh"),
        stageStarted(1, "test"),
        stepStarted(1, 0, "report", "junit"),
    )

    private fun stageStarted(index: Int, name: String) = StageStarted(
        eventId = "s$index", runId = runId, sequence = index.toLong(), occurredAt = at,
        stageIndex = index, stageName = name,
    )

    private fun stepStarted(stage: Int, step: Int, name: String, type: String) = StepStarted(
        eventId = "$stage-$step", runId = runId, sequence = (stage * 10 + step).toLong(),
        occurredAt = at, stageIndex = stage, stepIndex = step, stepName = name, stepType = type,
    )

    private fun resolve(
        stages: Set<String> = emptySet(),
        steps: Set<String> = emptySet(),
        kinds: Set<String> = emptySet(),
        events: Sequence<DomainEvent>? = journal.asSequence(),
    ): SelectionOutcome =
        ConsoleOperationSelection.resolve(runId, stages, steps, kinds, events)

    private fun selected(outcome: SelectionOutcome): OperationSelection =
        assertInstanceOf(SelectionOutcome.Selected::class.java, outcome, "expected a selection, got $outcome").selection

    // ---------------------------------------------------------------- names

    /**
     * RC3-1 — a stage NAME selects its operations.
     *
     * This is the row that fails on rc1: `--stage build` returned nothing there, because the
     * resolver compared the token against `"0"`.
     */
    @Test
    fun `RC3-1 a stage NAME selects every operation of that stage`() {
        val selection = selected(resolve(stages = setOf("build")))

        assertEquals(
            setOf(opId(0, 0), opId(0, 1)),
            selectedOps(selection),
            "`--stage build` must select both of stage 0's steps. On the rc1 resolver it selected " +
                "nothing, because the token was compared against the stringified index `\"0\"`.",
        )
    }

    @Test
    fun `RC3-1b a step NAME selects that step across every stage that declares it`() {
        val selection = selected(resolve(steps = setOf("test")))

        assertEquals(
            setOf(opId(0, 1)),
            selectedOps(selection),
            "only stage 0 declares a step NAMED `test`; stage 1's step is `report`. Matching by " +
                "name across stages is what makes a name filter useful and is why the resolver " +
                "collects every (stageIndex, stepIndex) hit rather than the first.",
        )
    }

    // ---------------------------------------------------------------- kind

    /**
     * RC3-4 — `--kind` is honoured.
     *
     * `stepType` IS the kind: the engine emits `"sh"`, `"junit"`, `"scm-git.checkout"`, and an
     * external plugin emits its own key. So the axis needs no new vocabulary to be real.
     */
    @Test
    fun `RC3-4 a kind selects the steps whose stepType it names`() {
        val selection = selected(resolve(kinds = setOf("junit")))

        assertEquals(
            setOf(opId(1, 0)),
            selectedOps(selection),
            "`--kind junit` must select the one junit step. The rc1 resolver returned TRUE for " +
                "any kind, so this would have selected all three.",
        )
    }

    /**
     * RC3-5 — the rc1 defect, pinned by its symptom.
     *
     * A kind nobody uses must select NOTHING. Under rc1 it selected everything, and that is the
     * exact shape of the Semantic Constitution §2 "semantic drop": the flag was accepted, and
     * nothing interpreted it, and the result looked like a successful filter.
     */
    @Test
    fun `RC3-5 an unknown kind selects nothing rather than everything`() {
        val outcome = resolve(kinds = setOf("definitely-not-a-real-kind"))

        val refusal = assertInstanceOf(
            SelectionOutcome.Refused::class.java, outcome,
            "an unknown kind must be REFUSED. Under rc1 it was a true conjunct and returned every " +
                "frame in the run, which a caller cannot distinguish from a filter that worked.",
        ).refusal
        assertInstanceOf(SelectionRefusal.UnknownKind::class.java, refusal, "got $refusal")
        assertEquals(
            listOf("junit", "sh"),
            (refusal as SelectionRefusal.UnknownKind).known,
            "the refusal must carry the known kinds so a caller fixes the typo without guessing",
        )
    }

    // ---------------------------------------------------------------- conjunction

    /**
     * RC3-3 — axes are ANDed.
     *
     * `--stage build --kind junit` matches nothing in this run, because the junit step lives in
     * stage 1. ORing the axes would return it, and a caller asking for both would silently get
     * the wrong one.
     */
    @Test
    fun `RC3-3 non-empty axes are ANDed so a combination selects the intersection`() {
        val selection = selected(resolve(stages = setOf("build"), kinds = setOf("junit")))

        assertEquals(
            emptySet<String>(),
            selectedOps(selection),
            "build's steps are `sh`; the only junit step is in stage 1. The intersection is empty, " +
                "and empty is the RIGHT answer — ORing the axes would have returned the junit step, " +
                "which is in a stage the caller excluded.",
        )
    }

    @Test
    fun `RC3-3b values within one axis are ORed`() {
        val selection = selected(resolve(kinds = setOf("sh", "junit")))

        assertEquals(
            setOf(opId(0, 0), opId(0, 1), opId(1, 0)),
            selectedOps(selection),
            "two kinds in one axis is a disjunction, not a conjunction. ANDing within an axis " +
                "would make `--kind sh --kind junit` permanently empty, which is not a shape a " +
                "caller can intend.",
        )
    }

    // ---------------------------------------------------------------- refusal

    /**
     * RC3-6 — an unresolved NAME is a refusal, not an empty result.
     *
     * `--stage typo` selecting nothing is indistinguishable from a stage that wrote nothing, and
     * that is a claim about the product the caller did not ask for and cannot verify.
     */
    @Test
    fun `RC3-6 an unresolved stage name is refused and names the known stages`() {
        val outcome = resolve(stages = setOf("bulid"))

        val refusal = assertInstanceOf(
            SelectionOutcome.Refused::class.java, outcome,
            "a typo must not read as a silent stage",
        ).refusal
        assertEquals(
            SelectionRefusal.UnknownStage("bulid", listOf("build", "test")),
            refusal,
            "the refusal must name what was asked for and what exists, so one invocation fixes " +
                "every typo rather than one per run",
        )
    }

    /**
     * RC3-7 — without a journal, a NAME cannot be honoured and the request is REFUSED.
     *
     * Not admitted-and-ignored: that is the rc1 behaviour for `--kind` generalised to every axis,
     * and it is the one behaviour the Semantic Constitution calls a silent no-op.
     */
    @Test
    fun `RC3-7 a name or kind without a journal is refused rather than admitted and ignored`() {
        val byName = assertInstanceOf(
            SelectionOutcome.Refused::class.java,
            resolve(stages = setOf("build"), events = null),
            "admitting `--stage build` with no journal would mean accepting a filter the verb " +
                "cannot apply — the rc1 `--kind` defect, applied to every axis",
        ).refusal
        assertEquals(SelectionRefusal.NoEventsAuthority(listOf("--stage")), byName)

        val byKind = assertInstanceOf(
            SelectionOutcome.Refused::class.java,
            resolve(kinds = setOf("sh"), events = null),
        ).refusal
        assertEquals(SelectionRefusal.NoEventsAuthority(listOf("--kind")), byKind)
    }

    /**
     * RC3-2 — an INDEX still works with no journal, because the opId carries it.
     *
     * A caller scripting against `--stage 0` owns no journal and should not have to. The result
     * is [OperationSelection.IndexOnly] rather than an opId set precisely because no journal
     * means no enumeration — the two are different facts and the type says so.
     */
    @Test
    fun `RC3-2 an index token resolves without a journal`() {
        val selection = selected(resolve(stages = setOf("0"), events = null))

        assertEquals(
            OperationSelection.IndexOnly(requiredStage = 0, requiredStep = null),
            selection,
            "an index is derivable from the opId alone, so it needs no authority",
        )
        assertTrue(selection.includes(opId(0, 1)), "stage 0 matches")
        assertTrue(!selection.includes(opId(1, 0)), "stage 1 does not")
    }

    // ---------------------------------------------------------------- membership

    /**
     * RC3-8 — membership is segment equality, never a substring test.
     *
     * `run-1-s0-0` contains `run-1-s0-0` and also would be "matched" by a substring test against
     * a selection containing `run-1-s0-01`-style ids. On a run with ten or more steps per stage
     * the substring form leaks neighbouring operations into every filtered read.
     */
    @Test
    fun `RC3-8 membership compares the opId exactly, not as a substring`() {
        val selection = OperationSelection.Operations(setOf(opId(0, 0)))

        assertTrue(selection.includes(opId(0, 0)), "the selected operation matches")
        assertTrue(!selection.includes(opId(0, 1)), "its sibling does not")
        assertTrue(!selection.includes("${opId(0, 0)}-extra"), "and neither does an id that merely CONTAINS it")
    }

    @Test
    fun `RC3-9 no filter at all selects every operation`() {
        assertEquals(
            OperationSelection.AllOperations,
            selected(resolve()),
            "an empty filter is not an empty selection; the two mean opposite things and conflating " +
                "them is how a missing filter became a filter that matches nothing",
        )
    }

    private fun selectedOps(selection: OperationSelection): Set<String> = when (selection) {
        OperationSelection.AllOperations ->
            error("AllOperations carries no opIds; this row needs an explicit selection")
        is OperationSelection.Operations -> selection.opIds
        is OperationSelection.IndexOnly -> error("IndexOnly is index-shaped; this row needs opIds")
    }

    /**
     * RC3-10 — a ONE-SHOT sequence resolves, because that is what the real journal hands over.
     *
     * `SqliteEventStore.eventsFor` returns a sequence that can be consumed only once. The resolver
     * used to traverse it twice — once for stages, once for steps — and the second traversal threw
     * `IllegalStateException: This sequence can be consumed only once`.
     *
     * It could not have been caught by the rows above, and that is the point of this one: they all
     * pass `journal.asSequence()`, which is NOT constrained, so the function looked total. The
     * crash was reachable only through the product, and it took `--stage NAME` and `--kind` down
     * with it. A unit row that mirrors the real source's constraint is the only thing that closes
     * that gap.
     */
    @Test
    fun `RC3-10 a sequence that can be consumed only once still resolves`() {
        val oneShot = java.util.stream.StreamSupport.stream(journal.spliterator(), false)
            .iterator() as Iterator<DomainEvent>
        val oneShotSequence = generateSequence { if (oneShot.hasNext()) oneShot.next() else null }
            .constrainOnce()

        val outcome = ConsoleOperationSelection.resolve(
            runId, setOf("build"), setOf("compile"), setOf("sh"), oneShotSequence,
        )

        assertEquals(
            setOf(opId(0, 0)),
            selected(selectedOf(outcome)),
            "a constrained-once sequence must resolve exactly like a re-iterable one",
        )
    }

    private fun selectedOf(outcome: SelectionOutcome) =
        assertInstanceOf(SelectionOutcome.Selected::class.java, outcome, "expected a selection, got $outcome").selection

    private fun selected(selection: OperationSelection): Set<String> = when (selection) {
        OperationSelection.AllOperations -> error("expected an explicit selection")
        is OperationSelection.Operations -> selection.opIds
        is OperationSelection.IndexOnly -> error("expected opIds, got an index-shaped selection")
    }

    private fun opId(stage: Int, step: Int) = OpId(runId = runId, stageIndex = stage, stepIndex = step).format()
}
