package dev.rubentxu.pipeline.v2.release

import dev.rubentxu.pipeline.v2.domain.RunId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The durable name of one certification cycle.
 *
 * A cycle is the whole trip of one release train: ask a certifier, receive a
 * receipt, admit it, and — when the receipt says the bytes are wrong — start
 * again with a new candidate. The name is what the durable plane files that
 * trip under: the journal attributes its rows to it and the lease guards it.
 *
 * The name is built here and not spelled by the caller because the rule that
 * decides which strings may name a durable location belongs to the identity
 * ([RunId.isNameable]). A caller that spelled the name would spell it a second
 * way, and the two drift the first time one of them uses a character the
 * other one does not admit.
 */
@JvmInline
value class CertificationCycleId private constructor(val value: String) {

    init {
        require(value.isNotBlank()) { "a certification cycle cannot be unnamed." }
    }

    override fun toString(): String = value

    companion object {

        /** The prefix that tells a cycle apart from a pipeline run in the durable plane. */
        const val PREFIX: String = "certification-cycle-"

        /**
         * The cycle of one release train, or null when that train cannot name one.
         */
        fun of(releaseTrain: ProductVersion): CertificationCycleId? {
            val name = PREFIX + releaseTrain.value
            return if (RunId.isNameable(name)) CertificationCycleId(name) else null
        }
    }
}

/**
 * A defect reported against a candidate of the cycle.
 *
 * The checkpoint names the defect and the candidate it was found against, and
 * nothing else. The defect's own document belongs to whoever receives it: a
 * checkpoint that carried the defect's body would hold a second copy of a
 * fact that has an owner, and the two copies would be able to disagree.
 */
@Serializable
data class CheckpointDefect internal constructor(
    /** The identity of the defect, so the same defect is not recorded twice. */
    @SerialName("defect_id") val defectId: String,
    /** The candidate the defect was found against. */
    val candidate: CandidateId,
) {
    init {
        require(defectId.isNotBlank()) {
            "a defect without an identifier cannot be recognised as the same defect twice."
        }
    }
}

/**
 * The state of one certification cycle, as a value.
 *
 * ## What it names
 *
 * The candidate the cycle is certifying NOW, the request that asked for it, the
 * receipt that admitted it (if one has arrived) and the defects reported
 * against the candidates of this cycle, each against the candidate it was found
 * against.
 *
 * ## What it does not do
 *
 * It does not decide whether a receipt admits its candidate. That verdict
 * belongs to the admission, and the checkpoint holds what the admission
 * concluded; a second rule here would be a second answer to the same question.
 *
 * ## The revision
 *
 * [revision] counts the transitions this checkpoint has absorbed. It is the
 * checkpoint's own number and not a clock: a transition that changes nothing
 * does not move it, so two cycles that reached the same state by different
 * routes can still be told apart by how they got there.
 */
@Serializable
data class CertificationCheckpoint internal constructor(
    /** The shape this record claims to follow. */
    @SerialName("schema_version") val schemaVersion: String = SCHEMA_VERSION,
    /** The durable name of the cycle this state belongs to. */
    @SerialName("cycle_id") val cycleId: String,
    /** The candidate the cycle is certifying now. Its digest IS the candidate identity. */
    val candidate: CandidateId,
    /** The request that asked for this candidate's certification. */
    val request: CertificationRequest? = null,
    /** The receipt that admitted this candidate, when one has arrived. */
    val receipt: CertificationReceipt? = null,
    /** The defects reported against the candidates of this cycle. */
    val defects: List<CheckpointDefect> = emptyList(),
    /** How many transitions this checkpoint has absorbed. */
    @SerialName("revision") val revision: Int,
) {
    init {
        require(cycleId.isNotBlank()) { "a checkpoint cannot belong to no cycle." }
        require(RunId.isNameable(cycleId)) {
            "the cycle name '$cycleId' cannot name a durable location, so the checkpoint " +
                "could not be filed under it."
        }
        require(revision >= 1) {
            "a checkpoint that has absorbed no transition is not a checkpoint, and its " +
                "revision is $revision."
        }
    }

    companion object {
        /** The shape this record is written in. */
        const val SCHEMA_VERSION: String = "pipelinek-certification-checkpoint-1"
    }
}

/**
 * A change in the state of the cycle.
 *
 * ## Identity
 *
 * Every transition carries the identity of the SUBJECT it speaks about, and
 * that identity is what makes the transition idempotent: a transition whose
 * subject the checkpoint already knows changes nothing.
 *
 * The identity of a request is the ASK and not the document: the candidate, the
 * battery and how many scenarios it holds. The instant the request was written
 * is deliberately not part of it. A request re-issued after an interruption is
 * the same ask, and treating it as a different one would move the state
 * forward for a question nobody asked twice.
 */
sealed interface CertificationTransition {

    /** The identity of the subject this transition speaks about. */
    val identity: String

    /**
     * The certification of a candidate was asked for.
     */
    data class Requested(val request: CertificationRequest) : CertificationTransition {
        override val identity: String
            get() = "requested:${request.candidateId.canonical}:" +
                "${request.battery.id}@${request.battery.version}:${request.battery.scenarioCount}"
    }

    /**
     * A receipt arrived and admitted a candidate.
     */
    data class Admitted(val receipt: CertificationReceipt) : CertificationTransition {
        override val identity: String get() = "admitted:${receipt.resultId}"
    }

    /**
     * A defect was reported against a candidate.
     */
    data class DefectFound(val defect: CheckpointDefect) : CertificationTransition {
        override val identity: String get() = "defect:${defect.defectId}"
    }
}

/**
 * What applying a transition to a checkpoint may yield.
 */
sealed interface CheckpointTransitionOutcome {

    /** The transition changed the state. */
    data class Advanced(val checkpoint: CertificationCheckpoint) : CheckpointTransitionOutcome

    /**
     * The transition was already known, so the state did not move.
     *
     * This is the outcome that keeps a repeated transition from doing the work
     * twice: the state is returned as it stands and nothing is written.
     */
    data class Unchanged(
        val checkpoint: CertificationCheckpoint,
        val reason: String,
    ) : CheckpointTransitionOutcome

    /** The transition contradicts the state and was not applied. */
    data class Rejected(val reason: String) : CheckpointTransitionOutcome
}

/**
 * Apply [transition] to the state of the cycle and say what came out.
 *
 * Pure: it reads the values it is given and returns a decision. It touches no
 * journal, no lease and no clock, which is what makes the rule testable on
 * its own and what lets a mutation prove the guard has teeth.
 *
 * A transition that the state already knows leaves it untouched. A transition
 * that contradicts the state is rejected rather than applied: a receipt about
 * another candidate, or a defect against a cycle that has no candidate yet, is
 * a fact the cycle cannot hold.
 */
fun applyCertificationTransition(
    cycleId: CertificationCycleId,
    current: CertificationCheckpoint?,
    transition: CertificationTransition,
): CheckpointTransitionOutcome = when (transition) {
    is CertificationTransition.Requested -> applyRequested(cycleId, current, transition.request)
    is CertificationTransition.Admitted -> applyAdmitted(current, transition.receipt)
    is CertificationTransition.DefectFound -> applyDefect(current, transition.defect)
}

/**
 * The first request of a cycle, or a request the cycle already made.
 */
private fun applyRequested(
    cycleId: CertificationCycleId,
    current: CertificationCheckpoint?,
    request: CertificationRequest,
): CheckpointTransitionOutcome {
    if (current == null) {
        return CheckpointTransitionOutcome.Advanced(
            CertificationCheckpoint(
                cycleId = cycleId.value,
                candidate = request.candidateId,
                request = request,
                revision = 1,
            ),
        )
    }

    val asked = current.request
    if (asked != null && askIdentity(asked) == askIdentity(request)) {
        return CheckpointTransitionOutcome.Unchanged(
            current,
            "the certification of ${request.candidateId.canonical} against battery " +
                "${request.battery.id} ${request.battery.version} was already asked for.",
        )
    }

    // A receipt admits BYTES. When the ask changes to another candidate, the
    // receipt that admitted the previous one says nothing about this one and
    // is dropped; when the ask changes for the same candidate, the receipt
    // still speaks about those bytes and stays.
    val receipt = if (current.candidate == request.candidateId) current.receipt else null
    return CheckpointTransitionOutcome.Advanced(
        current.copy(
            candidate = request.candidateId,
            request = request,
            receipt = receipt,
            revision = current.revision + 1,
        ),
    )
}

/**
 * A receipt that admits the candidate the cycle is certifying.
 */
private fun applyAdmitted(
    current: CertificationCheckpoint?,
    receipt: CertificationReceipt,
): CheckpointTransitionOutcome {
    if (current == null) {
        return CheckpointTransitionOutcome.Rejected(
            "no certification has been asked for in this cycle, so there is nothing for a " +
                "receipt to admit.",
        )
    }
    if (receipt.candidateId != current.candidate) {
        return CheckpointTransitionOutcome.Rejected(
            "the receipt certifies ${receipt.candidateId.canonical} but this cycle is " +
                "certifying ${current.candidate.canonical}.",
        )
    }
    val held = current.receipt
    if (held != null && held.resultId == receipt.resultId) {
        return CheckpointTransitionOutcome.Unchanged(
            current,
            "the receipt ${receipt.resultId} was already recorded for " +
                "${current.candidate.canonical}.",
        )
    }
    return CheckpointTransitionOutcome.Advanced(
        current.copy(receipt = receipt, revision = current.revision + 1),
    )
}

/**
 * A defect found against one of the cycle's candidates.
 */
private fun applyDefect(
    current: CertificationCheckpoint?,
    defect: CheckpointDefect,
): CheckpointTransitionOutcome {
    if (current == null) {
        return CheckpointTransitionOutcome.Rejected(
            "no candidate has been asked for in this cycle, so a defect has nothing to have " +
                "been found against.",
        )
    }
    if (current.defects.any { it.defectId == defect.defectId }) {
        return CheckpointTransitionOutcome.Unchanged(
            current,
            "the defect ${defect.defectId} was already recorded against " +
                "${defect.candidate.canonical}.",
        )
    }
    return CheckpointTransitionOutcome.Advanced(
        current.copy(
            defects = current.defects + defect,
            revision = current.revision + 1,
        ),
    )
}

/**
 * The identity of an ASK: what was asked, and not when the document was written.
 */
private fun askIdentity(request: CertificationRequest): String =
    CertificationTransition.Requested(request).identity

/**
 * The state of the cycle, as an operator reads it.
 */
fun CertificationCheckpoint.render(): String {
    val asked = request?.let {
        "battery ${it.battery.id} ${it.battery.version} (${it.battery.scenarioCount} scenarios)"
    } ?: "no certification asked"
    val admitted = receipt?.let { "receipt ${it.resultId} (${it.decision})" } ?: "no receipt yet"
    val defects = if (defects.isEmpty()) {
        "no defects"
    } else {
        defects.joinToString { "${it.defectId}@${it.candidate.canonical}" }
    }
    return "cycle $cycleId revision $revision: candidate ${candidate.canonical}, $asked, " +
        "$admitted, $defects"
}
