package dev.rubentxu.pipeline.v2.release

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * The event that tells a receiver that a candidate is published and asks for
 * its certification.
 *
 * The event is what crosses the boundary between two repositories, so its
 * shape is a contract rather than a report. It says four things: that a
 * release exists, which release it is, when it was published, and what is
 * being asked for. The request travels INSIDE the event, because the receiver
 * has to be able to create its task against exactly those bytes, and a
 * reference the receiver would have to resolve could resolve to something
 * else.
 *
 * The request names its own shape version, and that version is what says
 * whether the receiver understands what the event carries: the event's own
 * fields did not change when the request learned to carry a correction, and a
 * version that changed for that would say that the event's contract changed
 * when it did not.
 *
 * The moment in the event is the moment of the PUBLICATION, not the moment of
 * the build. Those are two different instants, and confusing them produces a
 * request about bytes the receiver cannot download yet.
 *
 * The name of the event is a constant of the surface that owns the wire
 * contract, and not a key of the telemetry vocabulary: the telemetry table
 * governs the attributes an OpenTelemetry export may carry, and this is not
 * an export. The hierarchy of run events is not its home either, because a run
 * event names a run and a sequence, and there is no run here. The ledger of
 * deliberate breaking changes is not its home either: nothing that was
 * published breaks when a name is added, and a ledger that recorded an
 * addition would say the opposite of what it is for.
 *
 * The event carries NO signature. The signature of this cycle belongs to the
 * promotion to GA, where there is a key to check it against: this product
 * verifies CONTENT, not the issuer of that content, and a rule that pretends
 * to have an anchor it does not have does not create one.
 */
@Serializable
data class CertificationRequestedEvent internal constructor(
    @SerialName("schema_version") val schemaVersion: String = SCHEMA_VERSION,
    /** The name the receiver listens for. */
    val name: String = NAME,
    /** The version of the cross-repository event protocol this event speaks. */
    @SerialName("protocol_version") val protocolVersion: String,
    /** The candidate identity: the SHA-256 of the distribution that was published. */
    @SerialName("candidate_id") val candidateId: CandidateId,
    /** The release the certification is asked about. */
    val release: PublishedReleaseRef,
    /** The instant the release was published. */
    @SerialName("occurred_at") val occurredAt: WireInstant,
    /** The request that is being made. */
    val request: CertificationRequest,
) {
    init {
        require(candidateId == request.candidateId) {
            "the event names the candidate ${candidateId.canonical} and carries a request for " +
                "${request.candidateId.canonical}: an event that names one candidate and asks " +
                "about another is an event nobody can act on."
        }
    }

    companion object {
        const val SCHEMA_VERSION: String = "pipelinek-certification-requested-1"

        /** The name of the event, as the receiver knows it. */
        const val NAME: String = "rc.certification.requested"

        /** The version of the cross-repository event protocol this build speaks. */
        const val PROTOCOL_VERSION: String = "1"
    }
}

/**
 * The release a certification is asked about.
 *
 * The tag is what the release is published under, and the version is what the
 * bytes inside it report. Both travel because a reader that has to parse a
 * tag to learn a version is a reader with two spellings of the same fact.
 */
@Serializable
data class PublishedReleaseRef(
    /** The tag the release was published under. */
    val tag: String,
    /** The product version the release materialises. */
    val version: ProductVersion,
)

/**
 * What an event may be, or why it may not be stated.
 */
sealed interface CertificationRequestedEventOutcome {

    /** The event states the release, the moment and the request. */
    data class Accepted(val event: CertificationRequestedEvent) : CertificationRequestedEventOutcome

    /** The event cannot be stated, and [reason] says why. */
    data class Refused(val reason: String) : CertificationRequestedEventOutcome
}

/**
 * Operator-facing diagnostic for an event that could not be stated.
 */
fun CertificationRequestedEventOutcome.render(): String = when (this) {
    is CertificationRequestedEventOutcome.Accepted ->
        "certification requested: ${event.candidateId.canonical} published as " +
            "${event.release.tag} at ${event.occurredAt.canonical}"

    is CertificationRequestedEventOutcome.Refused ->
        "certification request REFUSED: $reason"
}

/**
 * State the event that asks for the certification of [request] against the
 * published [release].
 *
 * The candidate identity is taken from the request and not asked for again:
 * the digest that travels in the event and the digest that travels in the
 * request are the same measurement, and two measurements of one file that
 * disagree are two measurements.
 *
 * A release with no tag produces no event: a reference to no release is a
 * reference the receiver cannot download.
 *
 * Pure: it reads the values it is given and returns a decision. It touches no
 * filesystem and no clock.
 */
fun buildCertificationRequestedEvent(
    request: CertificationRequest,
    release: PublishedReleaseRef,
    occurredAt: Instant,
): CertificationRequestedEventOutcome {
    if (release.tag.isBlank()) {
        return CertificationRequestedEventOutcome.Refused(
            "the release has no tag: an event that names no release asks about bytes the " +
                "receiver cannot download.",
        )
    }

    return CertificationRequestedEventOutcome.Accepted(
        CertificationRequestedEvent(
            protocolVersion = CertificationRequestedEvent.PROTOCOL_VERSION,
            candidateId = request.candidateId,
            release = release,
            occurredAt = WireInstant.of(occurredAt),
            request = request,
        ),
    )
}
