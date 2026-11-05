package dev.rubentxu.pipeline.v2.release

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.DateTimeException
import java.time.Instant

/**
 * A request that asks an external certifier to certify one candidate against
 * one named battery of scenarios.
 *
 * The request is a closed value: it names the candidate by the digest of the
 * distribution that was measured, the product version that digest belongs to,
 * the artifact itself, the battery the certification is asked against, and the
 * instant the request was made. Nothing else travels in it.
 *
 * The candidate is named by its digest and never by a name. A tag, a branch or
 * a release label can be pointed at different bytes; a digest cannot, so the
 * receiver always knows which bytes it is being asked to certify.
 *
 * The number of scenarios is not written in this document by hand: it is the
 * size of the inventory the request was built from, and
 * [buildCertificationRequest] is the only way to obtain one. A count written
 * in a program is a promise that nobody keeps; a count derived from the
 * inventory is a fact a receiver can check.
 *
 * The constructor is internal on purpose. A receiver reads and decodes this
 * document; only the producer states one, and only from an inventory.
 */
@Serializable
data class CertificationRequest internal constructor(
    @SerialName("schema_version") val schemaVersion: String = SCHEMA_VERSION,
    /** The candidate identity: the SHA-256 of the distribution that was measured. */
    @SerialName("candidate_id") val candidateId: CandidateId,
    /** The product version the candidate materialises. */
    @SerialName("product_version") val productVersion: ProductVersion,
    /** The distribution the certification is asked about. */
    val artifact: CandidateArtifact,
    /** The battery the certification is asked against. */
    val battery: CertificationBattery,
    /** The instant the request was made. */
    @SerialName("requested_at") val requestedAt: WireInstant,
) {
    companion object {
        const val SCHEMA_VERSION: String = "pipelinek-certification-request-1"
    }
}

/**
 * The battery a certification is asked against, named by its own name and
 * version, and sized by the number of scenarios it holds.
 *
 * The size is part of the document because a receiver has to be able to tell
 * whether it ran what it was asked to run. A request that named a battery
 * without saying how big it is would let a receiver certify 3 of 13 scenarios
 * and still call the battery certified.
 */
@Serializable
data class CertificationBattery internal constructor(
    /** The name the battery is published under. */
    val id: String,
    /** The version of that battery. */
    val version: String,
    /** How many scenarios the battery holds. */
    @SerialName("scenario_count") val scenarioCount: Int,
) {
    init {
        require(scenarioCount > 0) {
            "a battery that holds no scenario asks for nothing; scenario_count is " +
                "$scenarioCount."
        }
    }
}

/**
 * The inventory of scenarios a request is built from.
 *
 * The inventory names the battery and lists what the battery holds. It is an
 * input: whoever owns the list publishes it, and this product reads it to
 * state a request. The size of [scenarios] is what the request declares, so a
 * scenario that is not in the inventory is not certified, and a scenario that
 * is in it cannot be silently left out.
 */
data class CertificationBatteryCatalogue(
    /** The name the battery is published under. */
    val id: String,
    /** The version of that battery. */
    val version: String,
    /** The identifiers of the scenarios the battery holds, in the order they run. */
    val scenarios: List<String>,
)

/**
 * The instant a document states, in ISO-8601 and normalised to UTC.
 *
 * Wrapped in a value class with a private constructor so an instant cannot be
 * built from a string that is not one: a document whose moment is "yesterday"
 * says nothing about when the thing it reports happened.
 *
 * The name says the WIRE and not the event, because the same instant travels
 * in every document that crosses the boundary between repositories: the moment
 * a certification was asked for, and the moment a defect was observed. A type
 * named after one of them would be a lie in the other.
 */
@Serializable(with = WireInstantSerializer::class)
@JvmInline
value class WireInstant private constructor(val canonical: String) {

    /** The instant as a time value. */
    val instant: Instant get() = Instant.parse(canonical)

    override fun toString(): String = canonical

    companion object {
        /** The instant, normalised to UTC. */
        fun of(instant: Instant): WireInstant = WireInstant(instant.toString())

        /** The instant, or null when [raw] is not one. */
        fun parseOrNull(raw: String): WireInstant? = try {
            WireInstant(Instant.parse(raw.trim()).toString())
        } catch (e: DateTimeException) {
            null
        }
    }
}

/**
 * Serialises [WireInstant] in its canonical form and validates on the way
 * back in.
 *
 * Validating while decoding matters: a document this product did not write
 * must not be able to state a moment that is not an instant. The rule is
 * enforced at the boundary, not only when the value is built.
 */
object WireInstantSerializer : KSerializer<WireInstant> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor(
            "dev.rubentxu.pipeline.v2.release.WireInstant",
            PrimitiveKind.STRING,
        )

    override fun serialize(encoder: Encoder, value: WireInstant) {
        encoder.encodeString(value.canonical)
    }

    override fun deserialize(decoder: Decoder): WireInstant {
        val raw = decoder.decodeString()
        return WireInstant.parseOrNull(raw) ?: throw SerializationException(
            "the instant must be an ISO-8601 instant such as 2026-11-05T18:04:00Z, got '$raw'.",
        )
    }
}

/**
 * What a certification request may be, or why it may not be stated.
 */
sealed interface CertificationRequestOutcome {

    /**
     * The request states the candidate and the battery, and its scenario count
     * is the size of the inventory it was built from.
     */
    data class Accepted(val request: CertificationRequest) : CertificationRequestOutcome

    /** The request cannot be stated, and [reason] says why. */
    data class Refused(val reason: String) : CertificationRequestOutcome
}

/**
 * Operator-facing diagnostic for a request that could not be stated.
 */
fun CertificationRequestOutcome.render(): String = when (this) {
    is CertificationRequestOutcome.Accepted ->
        "certification request stated: ${request.candidateId.canonical} against battery " +
            "${request.battery.id} ${request.battery.version} (${request.battery.scenarioCount} " +
            "scenarios)"

    is CertificationRequestOutcome.Refused ->
        "certification request REFUSED: $reason"
}

/**
 * State the request that asks [catalogue] to certify the candidate in
 * [handoff].
 *
 * The candidate identity and the artifact travel as the values the handoff
 * already holds: the digest in this request is the digest that was measured
 * when the candidate was materialised, not a second measurement of the same
 * file. Two measurements of one file that disagree are two measurements, and
 * the receiver would have no way to tell which one it is certifying.
 *
 * The scenario count is the size of [catalogue], never a constant. An
 * inventory with no scenario, with a blank name, or with a repeated scenario
 * identifier produces no request at all: a request that names a battery it
 * cannot describe is a request nobody can act on.
 *
 * Pure: it reads the values it is given and returns a decision. It touches no
 * filesystem and no clock.
 */
fun buildCertificationRequest(
    handoff: CandidateHandoff,
    catalogue: CertificationBatteryCatalogue,
    requestedAt: Instant,
): CertificationRequestOutcome {
    if (catalogue.id.isBlank()) {
        return CertificationRequestOutcome.Refused(
            "the battery has no name: a request that names no battery asks for nothing.",
        )
    }
    if (catalogue.version.isBlank()) {
        return CertificationRequestOutcome.Refused(
            "the battery '${catalogue.id}' has no version: a request that names a battery " +
                "without its version cannot say which battery it asks for.",
        )
    }
    if (catalogue.scenarios.isEmpty()) {
        return CertificationRequestOutcome.Refused(
            "the battery '${catalogue.id}' holds no scenario: a request that asks for an empty " +
                "battery asks for no certification.",
        )
    }
    if (catalogue.scenarios.any { it.isBlank() }) {
        return CertificationRequestOutcome.Refused(
            "the battery '${catalogue.id}' holds a scenario with no identifier: a scenario " +
                "nobody can name is a scenario nobody can certify.",
        )
    }
    val repeated = catalogue.scenarios.groupBy { it }.filterValues { it.size > 1 }.keys
    if (repeated.isNotEmpty()) {
        return CertificationRequestOutcome.Refused(
            "the battery '${catalogue.id}' holds a repeated scenario identifier " +
                "(${repeated.sorted().joinToString(", ")}): the size of the inventory would " +
                "then count a scenario twice, and the count is what the request declares.",
        )
    }

    return CertificationRequestOutcome.Accepted(
        CertificationRequest(
            candidateId = handoff.candidateId,
            productVersion = handoff.productVersion,
            artifact = handoff.artifact,
            battery = CertificationBattery(
                id = catalogue.id,
                version = catalogue.version,
                scenarioCount = catalogue.scenarios.size,
            ),
            requestedAt = WireInstant.of(requestedAt),
        ),
    )
}
