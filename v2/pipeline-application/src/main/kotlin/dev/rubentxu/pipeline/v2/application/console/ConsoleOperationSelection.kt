package dev.rubentxu.pipeline.v2.application.console

import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.StepStarted

/**
 * R4 / WU-RC3 — the ONE decision that answers "which operations does this console request want?",
 * resolved once and consumed by every frame path.
 *
 * ## Why this exists
 *
 * The rc1 console verb resolved filters in three different places, inconsistently:
 *
 *  - `--follow` was dispatched BEFORE the filter branch in `CliParser.consoleValidate`, so
 *    `console --frames --follow --stage build` admitted a plain `FollowFrames` and **silently
 *    dropped the filter**. That is the audit's P0-4.
 *  - `StageStepKindResolver` matched the user's `--stage`/`--step` against the **stringified
 *    index** the opId carries, so `--stage 0` hit and `--stage build` missed against the very
 *    same operation.
 *  - `--kind` was `val kindOk = true` — a non-empty kind set contributed a *true* conjunct. Any
 *    `--kind nonsense` matched every frame in the run. That is the "semantic drop" the Semantic
 *    Constitution §2 forbids outright: a flag accepted, never interpreted.
 *
 * None of those is fixable by adjusting a comparison. The names and the kinds are not in the
 * stream id; they are in the **events journal**, and `StepStarted` carries exactly what is needed
 * (`stageIndex`, `stageName`, `stepIndex`, `stepName`, `stepType`).
 *
 * ## The shape
 *
 * A selection is a SET OF OPERATION IDS. Deciding membership by set membership rather than by
 * re-deriving a predicate per frame is what makes the single read and the follow loop the same
 * decision: they both ask this one function, so they cannot drift apart again.
 *
 * ## Purity
 *
 * [resolve] reads a `Sequence<DomainEvent>` and returns a value. It opens no store, touches no
 * clock and no filesystem, so the whole resolution — including every failure — is testable at
 * HF0 without a journal, a run, or a control root. Deciding is separated from interpreting: the
 * caller opens the journal and hands the events over.
 */
sealed interface OperationSelection {
    /** Every operation of the run. The unfiltered case; nothing is decided. */
    data object AllOperations : OperationSelection

    /** Exactly these operation ids. An empty set is legal and means "nothing matched". */
    data class Operations(val opIds: Set<String>) : OperationSelection

    /**
     * The no-journal case: the selection is stated as required INDICES, not as an opId set.
     *
     * Its own case, and deliberately not a degenerate [Operations]. Without a journal there is no
     * `StepStarted` to enumerate operations from, so the only authority available is the opId the
     * caller already scoped the read to. [includes] therefore parses THAT opId and checks its own
     * indices — which is sound, because an opId states its stage and step exactly.
     *
     * Making it a separate case is what lets the refusal be precise: `null` on both axes means
     * the filter genuinely constrains nothing, while `Operations(emptySet())` would mean "this
     * stage exists and wrote nothing" — a fact about the product that this path cannot establish
     * and must therefore never claim.
     */
    data class IndexOnly(val requiredStage: Int?, val requiredStep: Int?) : OperationSelection

    /**
     * Membership test.
     *
     * The one function every frame path consults. It takes the opId **segment** of a stream id,
     * not the whole id, because that is what a selection is over — and comparing segments rather
     * than substrings is what stops `run-1-s0-0` from matching a selection containing
     * `run-1-s0-01`.
     */
    fun includes(opId: String): Boolean = when (this) {
        AllOperations -> true
        is Operations -> opId in opIds
        is IndexOnly -> OpId.parse(opId)?.let { parsed ->
            (requiredStage == null || parsed.stageIndex == requiredStage) &&
                (requiredStep == null || parsed.stepIndex == requiredStep)
        } ?: false
    }
}

/** Why a filter could not be turned into a selection. */
sealed interface SelectionRefusal {
    /** The journal has no stage by that name. [known] is what it does have, for the message. */
    data class UnknownStage(val requested: String, val known: List<String>) : SelectionRefusal

    /** The journal has no step by that name. */
    data class UnknownStep(val requested: String, val known: List<String>) : SelectionRefusal

    /** The journal has no step of that type. `stepType` is what `--kind` names. */
    data class UnknownKind(val requested: String, val known: List<String>) : SelectionRefusal

    /**
     * No events journal was reachable, so names and kinds cannot be resolved at all.
     *
     * Its own case, and the reason this block can be honest about `--kind`: an index token
     * (`--stage 0`) is derivable from the opId alone, but a NAME and a TYPE are not. Rather than
     * admit the filter and pretend to apply it, the request is refused and told what to supply.
     */
    data class NoEventsAuthority(val axesNeedingIt: List<String>) : SelectionRefusal
}

/** What resolving a filter produced. */
sealed interface SelectionOutcome {
    data class Selected(val selection: OperationSelection) : SelectionOutcome
    data class Refused(val refusal: SelectionRefusal) : SelectionOutcome
}

/**
 * R4 / WU-RC3 — resolve `--stage` / `--step` / `--kind` tokens into an [OperationSelection].
 *
 * ## The resolution, stated once
 *
 * ```text
 * --stage NAME  -> StageStarted.stageName == NAME  -> its stageIndex
 * --step  NAME  -> StepStarted.stepName   == NAME  -> its (stageIndex, stepIndex)
 * --kind  TYPE  -> StepStarted.stepType   == TYPE  -> its (stageIndex, stepIndex)
 *
 * every token resolves independently; UNRESOLVED tokens are a REFUSAL, not an empty set
 * an operation survives when it satisfies EVERY non-empty axis (AND across axes, OR within one)
 * ```
 *
 * A numeric `--stage 0` / `--step 1` is accepted as an index **as well as** a name, because the
 * opId carries the index and a caller scripting against it should not have to own the journal.
 * The index is matched against the SAME resolved set as the name, so a name and an index can
 * never disagree about which operations they select.
 *
 * ## Why an unresolved token is a refusal and not an empty result
 *
 * `--stage typo` selecting nothing looks exactly like a stage that wrote nothing, and the caller
 * has no way to tell a typo from a genuinely silent stage. Silent wrong answers are the failure
 * with the worst consequence here — a filtered console that shows too little is read as "the
 * pipeline was quiet", which is a claim about the PRODUCT. So the answer names the token and the
 * known set.
 */
object ConsoleOperationSelection {

    /**
     * @param events the run's events. `null` means **no journal was reachable** — a fact the
     *   caller established, not an empty history, and the two produce different answers.
     */
    fun resolve(
        runId: String,
        stages: Set<String>,
        steps: Set<String>,
        kinds: Set<String>,
        events: Sequence<DomainEvent>?,
    ): SelectionOutcome {
        if (stages.isEmpty() && steps.isEmpty() && kinds.isEmpty()) {
            return SelectionOutcome.Selected(OperationSelection.AllOperations)
        }

        // A name and a kind are both journal-only. Resolve what is journal-only FIRST so a
        // request that needs the journal is refused as such even when its numeric tokens would
        // have been satisfiable on their own — admitting it and then half-applying it is the
        // rc1 failure again.
        val needsJournal = stages.any { !it.isIndexToken() } ||
            steps.any { !it.isIndexToken() } ||
            kinds.isNotEmpty()
        if (events == null) {
            if (!needsJournal) return resolveByIndex(stages, steps)
            return SelectionOutcome.Refused(
                SelectionRefusal.NoEventsAuthority(
                    axesNeedingIt = buildList {
                        if (stages.any { !it.isIndexToken() }) add("--stage")
                        if (steps.any { !it.isIndexToken() }) add("--step")
                        if (kinds.isNotEmpty()) add("--kind")
                    },
                ),
            )
        }

        // WU-C FIX — the events are traversed ONCE and materialised.
        //
        // `SqliteEventStore.eventsFor` hands back a sequence that can be consumed only once, so
        // the two `filterIsInstance` calls this used to make threw
        // `IllegalStateException: This sequence can be consumed only once` on the SECOND one.
        // It never appeared in a unit test because `list.asSequence()` is not constrained — the
        // only real journal is the one that constrains, so the crash was reachable ONLY from the
        // product, and it took `--stage NAME` and `--kind` both down with it.
        //
        // Materialising once also makes the function total over ANY sequence, which is the
        // contract it should have had from the start.
        val history = events.toList()
        val stagesStarted = history.filterIsInstance<StageStarted>()
        val stepsStarted = history.filterIsInstance<StepStarted>()

        // Resolve every token, refusing the FIRST unresolvable one with the full known set so a
        // caller fixing several typos fixes them in one pass rather than one per invocation.
        val stageIndexes = buildSet {
            for (token in stages) {
                if (token.isIndexToken()) {
                    add(token.toInt())
                } else {
                    val hit = stagesStarted.filter { it.stageName == token }.map { it.stageIndex }
                    if (hit.isEmpty()) {
                        return SelectionOutcome.Refused(
                            SelectionRefusal.UnknownStage(token, stagesStarted.map { it.stageName }.distinct().sorted()),
                        )
                    }
                    addAll(hit)
                }
            }
        }

        val stepKeys = buildSet {
            for (token in steps) {
                if (token.isIndexToken()) {
                    add(StepKey(stagesStarted.map { it.stageIndex }.minOrNull() ?: 0, token.toInt()))
                } else {
                    val hit = stepsStarted.filter { it.stepName == token }
                        .map { StepKey(it.stageIndex, it.stepIndex) }
                    if (hit.isEmpty()) {
                        return SelectionOutcome.Refused(
                            SelectionRefusal.UnknownStep(token, stepsStarted.map { it.stepName }.distinct().sorted()),
                        )
                    }
                    addAll(hit)
                }
            }
        }

        val kindKeys = buildSet {
            for (token in kinds) {
                val hit = stepsStarted.filter { it.stepType == token }
                    .map { StepKey(it.stageIndex, it.stepIndex) }
                if (hit.isEmpty()) {
                    return SelectionOutcome.Refused(
                        SelectionRefusal.UnknownKind(token, stepsStarted.map { it.stepType }.distinct().sorted()),
                    )
                }
                addAll(hit)
            }
        }

        // AND across axes, OR within one. `isNotEmpty()` on an axis means the axis constrains.
        val selected = stepsStarted.filter { step ->
            (stageIndexes.isEmpty() || step.stageIndex in stageIndexes) &&
                (stepKeys.isEmpty() || StepKey(step.stageIndex, step.stepIndex) in stepKeys) &&
                (kindKeys.isEmpty() || StepKey(step.stageIndex, step.stepIndex) in kindKeys)
        }.map { OpId(runId = runId, stageIndex = it.stageIndex, stepIndex = it.stepIndex).format() }

        return SelectionOutcome.Selected(OperationSelection.Operations(selected.toSet()))
    }

    /**
     * Index-only resolution: no journal, so every token must be an index.
     *
     * An opId's own indices are the whole authority here, which is why this can be sound without
     * a journal at all. It produces the SET of opIds rather than a predicate so the caller's
     * shape is the same either way — the difference is visible in the refusal, not in the type.
     */
    private fun resolveByIndex(
        stages: Set<String>,
        steps: Set<String>,
    ): SelectionOutcome {
        // With no journal there is no step->opId enumeration to intersect, so an index-only
        // filter is honoured where it CAN be: the opId-scoped read already narrows to one
        // operation, and the selection records the indices that operation must carry. This is
        // returned as an explicit pair so the caller can decide, rather than an empty set that
        // would look like "no such stage exists".
        val requiredStage = stages.singleOrNull()?.toInt()
        val requiredStep = steps.singleOrNull()?.toInt()
        if (stages.size > 1 || steps.size > 1) {
            // Several indices with no journal cannot be enumerated, and returning the first would
            // be an arbitrary answer. Refuse rather than guess.
            return SelectionOutcome.Refused(
                SelectionRefusal.NoEventsAuthority(listOf("--stage", "--step").distinct()),
            )
        }
        return SelectionOutcome.Selected(
            OperationSelection.IndexOnly(requiredStage = requiredStage, requiredStep = requiredStep),
        )
    }

    /** `runId-s0-0`'s third-from-last numeric segment is an index; a name never is. */
    private fun String.isIndexToken(): Boolean = isNotEmpty() && all { it.isDigit() }
}

/** A step's address within its stage. The journal names it; the opId is minted from it. */
private data class StepKey(val stageIndex: Int, val stepIndex: Int)
