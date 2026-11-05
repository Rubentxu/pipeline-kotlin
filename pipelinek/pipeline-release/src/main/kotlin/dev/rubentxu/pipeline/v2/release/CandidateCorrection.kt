package dev.rubentxu.pipeline.v2.release

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The correction that produced a candidate: the defect that motivated it, and
 * the candidate the defect was observed against.
 *
 * A candidate built after a defect was fixed is not just another candidate: it
 * answers a question that was left open, and the question is "does the fix
 * hold?". The receiver needs to know that the candidate it is about to certify
 * is the answer to a defect it reported, because that is what tells it WHICH
 * scenario has to be revalidated first and what the rest of the battery is
 * for. Without this fact the receiver sees an unexplained candidate, and the
 * link between the defect and its fix lives in a chat window that nobody can
 * search a year later.
 *
 * The defect travels as its FOOTPRINT and not as its identity: the identity is
 * COMPUTED from the footprint by both sides, and an identity that travelled
 * would be a second spelling of the same fact that the two sides could
 * disagree about. The two sides name the same defect with the same string
 * because they compute it from the same four facts with the same
 * canonicalisation, not because one of them copied the other's.
 *
 * The candidate the defect was seen against is a DIFFERENT candidate from the
 * one being certified. A candidate that corrects itself says nothing, and the
 * request that carries this value refuses to exist in that state.
 */
@Serializable
data class CandidateCorrection internal constructor(
    /** The structural footprint of the defect that motivated the correction. */
    val defect: DefectFingerprint,
    /** The candidate the defect was observed against. */
    @SerialName("observed_against") val observedAgainst: CandidateId,
)
