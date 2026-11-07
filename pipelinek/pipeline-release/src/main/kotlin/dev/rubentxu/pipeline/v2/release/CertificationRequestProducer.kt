package dev.rubentxu.pipeline.v2.release

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * The catalogue of scenarios a certification is asked against, as it travels
 * between the certifier and this product.
 *
 * The catalogue is published by whoever owns the list of scenarios, and this
 * product reads it: the number of scenarios a request declares is the size of
 * this list, never a constant written in a program. A programme that says 26
 * because 26 was the plan promises nothing; a request built from this
 * catalogue says what the list holds, and the list can be counted.
 *
 * The shape is closed. A catalogue that carries a field this build does not
 * define is refused, because a reader that ignores a field it does not
 * understand cannot claim to have read the list.
 */
@Serializable
data class PublishedBatteryCatalogue(
    /** The shape this document claims to follow. */
    @SerialName("schema_version") val schemaVersion: String = SCHEMA_VERSION,
    /** The name the battery is published under. */
    val id: String,
    /** The version of that battery. */
    val version: String,
    /** The identifiers of the scenarios the battery holds, in the order they run. */
    val scenarios: List<String>,
) {
    companion object {
        const val SCHEMA_VERSION: String = "pipelinek-certification-battery-1"
    }
}

/**
 * Reads the catalogue of a battery.
 *
 * The reading is a closed result and never an exception: the catalogue comes
 * from outside, and a publisher that has to catch an exception to know the
 * list is unreadable is a publisher with a worse diagnostic than it needs.
 */
object PublishedBatteryCatalogueCodec {

    private val reader = Json {
        ignoreUnknownKeys = false
    }

    /**
     * Read the catalogue from [text], or say why the document is not one.
     */
    fun decode(text: String): CatalogueDecodeResult = try {
        val element = reader.parseToJsonElement(text)
        val fields = element as? JsonObject
        if (fields == null) {
            CatalogueDecodeResult.Rejected(
                "the document is not a JSON object, so it names no battery.",
            )
        } else {
            val known = serializer<PublishedBatteryCatalogue>().descriptor.elementNames.toSet()
            val unknown = fields.keys - known
            val missing = known - fields.keys
            when {
                unknown.isNotEmpty() -> CatalogueDecodeResult.Rejected(
                    "the document carries field(s) this build does not define: " +
                        unknown.sorted().joinToString(),
                )

                missing.isNotEmpty() -> CatalogueDecodeResult.Rejected(
                    "the document leaves out field(s) a catalogue requires: " +
                        missing.sorted().joinToString(),
                )

                else -> readCatalogue(fields)
            }
        }
    } catch (e: SerializationException) {
        CatalogueDecodeResult.Rejected(
            e.message ?: "the document is not a valid battery catalogue.",
        )
    } catch (e: IllegalArgumentException) {
        CatalogueDecodeResult.Rejected(
            e.message ?: "the document is not a valid battery catalogue.",
        )
    }

    private fun readCatalogue(fields: JsonObject): CatalogueDecodeResult = try {
        val catalogue = reader.decodeFromJsonElement(serializer<PublishedBatteryCatalogue>(), fields)
        if (catalogue.schemaVersion != PublishedBatteryCatalogue.SCHEMA_VERSION) {
            CatalogueDecodeResult.Rejected(
                "the document declares schema '${catalogue.schemaVersion}' and this build reads " +
                    "'${PublishedBatteryCatalogue.SCHEMA_VERSION}'.",
            )
        } else {
            CatalogueDecodeResult.Accepted(catalogue)
        }
    } catch (e: SerializationException) {
        CatalogueDecodeResult.Rejected(
            e.message ?: "the document does not describe a battery catalogue.",
        )
    } catch (e: IllegalArgumentException) {
        CatalogueDecodeResult.Rejected(
            e.message ?: "the document does not describe a battery catalogue.",
        )
    }
}

/** What reading a battery catalogue may yield, or why it could not be read. */
sealed interface CatalogueDecodeResult {

    /** The document is a catalogue this build understands. */
    data class Accepted(val catalogue: PublishedBatteryCatalogue) : CatalogueDecodeResult

    /** The document is not a catalogue this build can use. */
    data class Rejected(val reason: String) : CatalogueDecodeResult
}

/**
 * States the certification request for a candidate, from the candidate's own
 * handoff and the catalogue of the battery it is certified against.
 *
 * The request names the candidate by the digest the handoff already carries,
 * so the bytes are measured once and used twice: the request does not measure
 * the ZIP again, and it cannot therefore disagree with the handoff about what
 * it is asking to certify.
 *
 * When the candidate is the answer to a defect, the document that reported
 * that defect is the INPUT that says so: the correction is derived from the
 * report and not typed by hand, so the defect it names is a defect somebody
 * actually observed, and the candidate it was observed against is the one the
 * report says.
 */
object CertificationRequestProducer {

    /**
     * Write the request for the candidate described by [handoffPath] against
     * the battery described by [cataloguePath], and return the outcome.
     *
     * [correctionReportPath] is the report of the defect this candidate
     * answers. It is OPTIONAL: a candidate that is not a correction produces a
     * request that says nothing about any defect.
     */
    fun produce(
        handoffPath: Path,
        cataloguePath: Path,
        requestedAt: Instant,
        outPath: Path,
        correctionReportPath: Path? = null,
    ): CertificationRequestOutcome {
        val handoff = readHandoff(handoffPath)
            ?: return CertificationRequestOutcome.Refused(
                "the candidate handoff at $handoffPath does not exist or cannot be read. A " +
                    "request names a candidate, and a candidate this build cannot read cannot " +
                    "be asked about.",
            )

        val catalogueText = readText(cataloguePath)
            ?: return CertificationRequestOutcome.Refused(
                "the battery catalogue at $cataloguePath does not exist or cannot be read. The " +
                    "number of scenarios a request declares is the size of that catalogue, and " +
                    "a catalogue nobody can read declares no number at all.",
            )

        val catalogue = when (val decoded = PublishedBatteryCatalogueCodec.decode(catalogueText)) {
            is CatalogueDecodeResult.Rejected -> return CertificationRequestOutcome.Refused(
                "the battery catalogue at $cataloguePath is not a catalogue this build can use: " +
                    decoded.reason,
            )

            is CatalogueDecodeResult.Accepted -> decoded.catalogue
        }

        val correction = when (val read = readCorrection(correctionReportPath)) {
            is CorrectionReading.Refused -> return CertificationRequestOutcome.Refused(read.reason)
            is CorrectionReading.None -> null
            is CorrectionReading.Stated -> read.correction
        }

        val outcome = buildCertificationRequest(
            handoff = handoff,
            catalogue = CertificationBatteryCatalogue(
                id = catalogue.id,
                version = catalogue.version,
                scenarios = catalogue.scenarios,
            ),
            requestedAt = requestedAt,
            correction = correction,
        )

        if (outcome is CertificationRequestOutcome.Accepted) {
            val parent = outPath.parent
            if (parent != null) Files.createDirectories(parent)
            Files.writeString(outPath, CertificationRequestCodec.encode(outcome.request))
        }

        return outcome
    }

    /** The handoff at [path], or null when it cannot be read. */
    private fun readHandoff(path: Path): CandidateHandoff? {
        val text = readText(path) ?: return null
        return try {
            CandidateHandoffCodec.decode(text)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /**
     * The correction stated by the defect report at [path], or why there is
     * none.
     *
     * The report is READ with the reader that reads every report this product
     * receives, and the correction is derived from what it says: the footprint
     * of the defect and the candidate the defect was observed against. Nothing
     * about the defect is typed by hand, so the request cannot name a defect
     * that nobody observed.
     */
    private fun readCorrection(path: Path?): CorrectionReading {
        if (path == null) return CorrectionReading.None
        val text = readText(path)
            ?: return CorrectionReading.Refused(
                "the defect report at $path does not exist or cannot be read. A request that " +
                    "answers a defect says which one, and a report nobody can read answers " +
                    "nothing.",
            )
        return when (val decoded = DefectReportedEventReader.decode(text)) {
            is DefectReportedEventDecode.Accepted -> CorrectionReading.Stated(
                CandidateCorrection(
                    defect = decoded.event.defect,
                    observedAgainst = decoded.event.candidateId,
                ),
            )

            is DefectReportedEventDecode.Rejected -> CorrectionReading.Refused(
                "the defect report at $path is not a report this build can read: " +
                    decoded.rejection.describe(),
            )
        }
    }

    /** The text of the document at [path], or null when it cannot be read. */
    private fun readText(path: Path): String? = try {
        if (Files.isRegularFile(path)) Files.readString(path) else null
    } catch (e: java.io.IOException) {
        null
    }
}

/**
 * What reading the defect report may yield.
 *
 * The three cases are three different facts: no report was named, a report was
 * named and states a correction, or a report was named and cannot be read. A
 * report that cannot be read is NOT the same as no report: the operator asked
 * for a correction and the build could not say which one, and answering "there
 * is no correction" would be a lie.
 */
private sealed interface CorrectionReading {

    /** No report was named, so the candidate is not a correction. */
    data object None : CorrectionReading

    /** The report states the defect and the candidate it was seen against. */
    data class Stated(val correction: CandidateCorrection) : CorrectionReading

    /** The report was named and could not be read. */
    data class Refused(val reason: String) : CorrectionReading
}
