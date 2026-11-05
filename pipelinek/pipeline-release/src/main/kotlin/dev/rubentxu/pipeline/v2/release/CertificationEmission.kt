package dev.rubentxu.pipeline.v2.release

import kotlinx.serialization.SerializationException
import java.time.Instant

/**
 * What the emission of a certification request may yield.
 */
sealed interface CertificationEmission {

    /** The event is to be written, and [receipt] says what was emitted and when. */
    data class Ready(
        val event: CertificationRequestedEvent,
        val document: String,
        val receipt: String,
    ) : CertificationEmission

    /** The very same event was already emitted; there is nothing left to write. */
    data class AlreadyEmitted(val receipt: String) : CertificationEmission

    /** The event cannot be emitted, and [reason] says why. */
    data class Refused(val reason: String) : CertificationEmission
}

/**
 * Operator-facing diagnostic for an emission.
 */
fun CertificationEmission.render(): String = when (this) {
    is CertificationEmission.Ready ->
        "certification request emitted: ${event.candidateId.canonical} published as " +
            event.release.tag

    is CertificationEmission.AlreadyEmitted ->
        "certification request ALREADY emitted: nothing was written twice"

    is CertificationEmission.Refused ->
        "certification request NOT emitted: $reason"
}

/**
 * Compose the certification request event for a release that has just been
 * published.
 *
 * The publication is a FACT that is read, not a claim that is passed in: the
 * receipt that records it is the input, and a receipt that does not record a
 * published release produces no event at all. A candidate that was built and
 * never published has no receipt, and a request about bytes the receiver
 * cannot download is worse than no request.
 *
 * The tag travels where it was measured — out of the receipt — and never as a
 * parameter, so the event cannot name a release that was not published.
 *
 * The request travels where it was stated — out of [requestText], which is the
 * document the admission gate already evaluated — and it is NOT derived here.
 * Deriving it a second time would give the receiver a request that could
 * disagree with the one that was admitted, and the receiver would have no way
 * to tell which of the two is the truth.
 *
 * The handoff is read for the same reason the request is not: it is the
 * document that was measured when the candidate was materialised, and the event
 * cross-checks it against the request. A request about another candidate is
 * the request about another candidate, and the event refuses it instead of
 * carrying it.
 *
 * [alreadyEmitted] is what an emission of the same candidate left behind. The
 * same document is not emitted twice, and a DIFFERENT document for the same
 * candidate is refused rather than written over: two requests for one
 * candidate would leave the receiver with no way to tell which one is the
 * request.
 *
 * Pure: it reads the values it is given and returns a decision. It touches no
 * filesystem and no clock — the moment is supplied.
 */
fun certificationRequestedEmission(
    handoffText: String,
    requestText: String,
    publicationReceipt: String,
    publishedAt: Instant,
    alreadyEmitted: String? = null,
): CertificationEmission {
    val published = publishedReleaseOf(publicationReceipt)
        ?: return CertificationEmission.Refused(
            "the release was not published: the publication receipt records no published " +
                "release. A candidate that was built and never published has no request.",
        )
    if (!published.prerelease) {
        return CertificationEmission.Refused(
            "the published release '${published.tag}' is not a release candidate: a " +
                "certification request asks about a candidate, and a stable release is not one.",
        )
    }

    val handoff = try {
        CandidateHandoffCodec.decode(handoffText)
    } catch (e: SerializationException) {
        return CertificationEmission.Refused(
            "the candidate handoff could not be read: " +
                (e.message ?: "the document does not describe a candidate."),
        )
    } catch (e: IllegalArgumentException) {
        return CertificationEmission.Refused(
            "the candidate handoff could not be read: " +
                (e.message ?: "the document does not describe a candidate."),
        )
    }

    val request = when (val read = CertificationRequestCodec.decode(requestText)) {
        is CertificationRequestDecodeResult.Accepted -> read.request
        is CertificationRequestDecodeResult.Rejected -> return CertificationEmission.Refused(
            "the certification request could not be read: " +
                when (val rejection = read.rejection) {
                    is CertificationRequestRejection.UnknownField ->
                        "unknown field(s) ${rejection.fields.joinToString()}"
                    is CertificationRequestRejection.MalformedDocument -> rejection.detail
                },
        )
    }

    if (request.candidateId != handoff.candidateId) {
        return CertificationEmission.Refused(
            "the certification request is about ${request.candidateId.canonical} and the " +
                "candidate that was published is ${handoff.candidateId.canonical}. The receiver " +
                "would be asked about bytes that were not published.",
        )
    }

    val event = when (
        val outcome = buildCertificationRequestedEvent(
            request = request,
            release = PublishedReleaseRef(tag = published.tag, version = handoff.releaseTrain),
            occurredAt = publishedAt,
        )
    ) {
        is CertificationRequestedEventOutcome.Accepted -> outcome.event
        is CertificationRequestedEventOutcome.Refused ->
            return CertificationEmission.Refused(outcome.reason)
    }

    val document = CertificationRequestedEventCodec.encode(event)
    val receipt = receiptOf(event, published.tag)

    if (alreadyEmitted != null) {
        return if (alreadyEmitted == document) {
            CertificationEmission.AlreadyEmitted(receipt)
        } else {
            CertificationEmission.Refused(
                "something was already emitted for this candidate " +
                    "(${describeEmission(alreadyEmitted)}), and this one would be a different " +
                    "one. Two requests for one candidate leave the receiver with no way to tell " +
                    "which of them is the request.",
            )
        }
    }

    return CertificationEmission.Ready(event = event, document = document, receipt = receipt)
}

/**
 * What the document that is already on disk says, for a refusal that has to
 * name it instead of guessing.
 */
private fun describeEmission(document: String): String =
    when (val decoded = CertificationRequestedEventCodec.decode(document)) {
        is CertificationEventDecode.Accepted ->
            "candidate ${decoded.event.candidateId.canonical}, emitted at " +
                decoded.event.occurredAt.canonical

        is CertificationEventDecode.Rejected ->
            "a document that is not a certification request this build can read"
    }

/**
 * The release the publication receipt records.
 */
private data class PublishedRelease(val tag: String, val prerelease: Boolean)

private const val PUBLISHED_MARKER = "github-release: PUBLISHED "

/**
 * The release the publication receipt records, or null when it records none.
 *
 * The receipt is the ordinary text the publication writes. The line that
 * matters says the release was published, and it names the tag it was
 * published under and whether it was published as a release candidate. A
 * receipt without that line is a receipt of something that did not happen.
 */
private fun publishedReleaseOf(receipt: String): PublishedRelease? {
    val line = receipt.lineSequence().firstOrNull { it.startsWith(PUBLISHED_MARKER) } ?: return null
    val fields = line.removePrefix(PUBLISHED_MARKER)
        .split(' ')
        .mapNotNull { token ->
            val separator = token.indexOf('=')
            if (separator <= 0) null else token.substring(0, separator) to token.substring(separator + 1)
        }
        .toMap()
    val tag = fields["tag"]?.takeIf { it.isNotBlank() } ?: return null
    return PublishedRelease(tag = tag, prerelease = fields["prerelease"] == "1")
}

/**
 * The receipt of the emission: what was emitted, against what, and when.
 *
 * The two moments are stated because they are two facts: the request was made
 * before the release was published, and the publication is what the event
 * announces. The distance between them is the time the certification took.
 */
private fun receiptOf(event: CertificationRequestedEvent, tag: String): String {
    val battery = event.request.battery
    return buildString {
        append("certification-request: EMITTED\n")
        append("  event_name: ${CertificationRequestedEvent.NAME}\n")
        append("  candidate_id: ${event.candidateId.canonical}\n")
        append("  release_tag: $tag\n")
        append("  release_version: ${event.release.version}\n")
        append("  battery: ${battery.id} ${battery.version} (${battery.scenarioCount} scenarios)\n")
        append("  occurred_at: ${event.occurredAt.canonical}\n")
        append("  requested_at: ${event.request.requestedAt.canonical}\n")
    }
}
