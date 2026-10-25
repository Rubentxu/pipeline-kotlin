package dev.rubentxu.pipeline.v2.runtime.explain

/**
 * What one observed event contributes to a diagnosis: where it lives and
 * where it sits in that lane's order. An inference without a reference is a
 * guess wearing a diagnosis's clothes, so every finding carries one.
 */
data class EvidenceRef(val lane: String, val sequence: Long)

/**
 * A finding is either an observed fact or an inference drawn from observed
 * facts. The split is the honesty contract: a fact is something an event
 * says, an inference is something the absence or order of events implies,
 * and a consumer must be able to tell them apart without parsing prose.
 */
sealed interface Finding {
    val code: String
    val evidence: EvidenceRef
}

/** Something a durable record states outright. */
sealed interface Fact : Finding {

    /** The run's own terminal event: its outcome word and its timestamp. */
    data class RunEnded(
        val outcome: String,
        val atMs: Long,
        override val evidence: EvidenceRef,
    ) : Fact {
        override val code: String get() = "run-ended"
    }

    /** The first step failure, with the typed failure kind it reported. */
    data class StepBroke(
        val failureKind: String,
        val stepName: String,
        val message: String,
        override val evidence: EvidenceRef,
    ) : Fact {
        override val code: String get() = "step-broke"
    }
}

/** Something implied by absent or unordered records; never by free text. */
sealed interface Inference : Finding {

    /** No terminal event exists; the run is open however it looks. */
    data class RunStillOpen(
        val lastObservedAtMs: Long,
        override val evidence: EvidenceRef,
    ) : Inference {
        override val code: String get() = "run-still-open"
    }

    /** A step started and no terminal step event ever followed it. */
    data class StepNeverClosed(
        val stepName: String,
        override val evidence: EvidenceRef,
    ) : Inference {
        override val code: String get() = "step-never-closed"
    }

    /**
     * A terminal event could not name the step it belonged to: it carried
     * no stage index and several open steps shared its ordinal. No duration
     * and no dangling verdict was attributed to any of them.
     */
    data class AttributionAmbiguous(
        val count: Int,
        override val evidence: EvidenceRef,
    ) : Inference {
        override val code: String get() = "attribution-ambiguous"
    }
}

/** How much of each lane the diagnosis actually consulted. */
data class Completeness(
    val eventLane: LaneState,
    val outputLane: LaneState,
    val leaseLane: LaneState,
) {
    companion object {
        /** The lanes a diagnosis built only from the event journal read. */
        val EventsOnly: Completeness = Completeness(
            eventLane = LaneState.Read,
            outputLane = LaneState.NotConsulted,
            leaseLane = LaneState.NotConsulted,
        )
    }
}

sealed interface LaneState {
    data object Read : LaneState
    data object NotConsulted : LaneState
}

/**
 * The structured facts a projection extracted from the run's records.
 * Optionality is meaning here: a null terminal fact is the fact that no
 * terminal event exists, not missing data, and the classifier treats it as
 * such.
 */
data class DiagnosisFacts(
    val runEnded: Fact.RunEnded?,
    val firstFailure: Fact.StepBroke?,
    val neverClosedStep: Inference.StepNeverClosed?,
    val lastObservedAtMs: Long,
    val lastSequence: Long,
    /** Terminals that matched several open steps and therefore closed none. */
    val ambiguousClosures: Int = 0,
)

/** The diagnosis: an explained set of findings, or a typed refusal. */
sealed interface Diagnosis {
    data class Explained(
        val status: String,
        val findings: List<Finding>,
        val completeness: Completeness,
    ) : Diagnosis

    data class Refused(val code: String) : Diagnosis
}

/**
 * Turn observed facts into a diagnosis. Pure: no lane reads, no clock, no
 * I/O — the caller states what it saw and this decides what it means.
 *
 * The rules, and the lies they refuse to tell:
 *  - a run without a terminal event is reported open, whatever its steps
 *    look like, and "open" is never softened into a success-shaped word;
 *  - a failure is the first one by evidence order, because the first break
 *    is the cause the operator acts on and later ones are its echoes;
 *  - the outcome word is the event's own vocabulary, never re-derived;
 *  - a step that never closed is an inference next to the facts, so a
 *    consumer can weigh it differently.
 */
fun classify(facts: DiagnosisFacts, completeness: Completeness): Diagnosis {
    val terminal = facts.runEnded
    val failure = facts.firstFailure
    val status = when {
        terminal != null -> "ended-${terminal.outcome}"
        else -> "still-open"
    }
    val findings = buildList {
        failure?.let { add(it) }
        terminal?.let { add(it) }
        if (terminal == null) {
            add(
                Inference.RunStillOpen(
                    lastObservedAtMs = facts.lastObservedAtMs,
                    evidence = EvidenceRef("event-journal", facts.lastSequence),
                ),
            )
        }
        facts.neverClosedStep?.let { add(it) }
        if (facts.ambiguousClosures > 0) {
            add(
                Inference.AttributionAmbiguous(
                    facts.ambiguousClosures,
                    EvidenceRef("event-journal", facts.lastSequence),
                ),
            )
        }
    }
    return Diagnosis.Explained(status, findings, completeness)
}
