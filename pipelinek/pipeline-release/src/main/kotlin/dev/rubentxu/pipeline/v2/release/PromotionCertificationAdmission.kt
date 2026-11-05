package dev.rubentxu.pipeline.v2.release

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The interpreter at the promotion boundary.
 *
 * The pure decider ([evaluatePromotionCertification]) is the authority on
 * whether a certification admits a candidate to a stable release. This object
 * is the effectful interpreter that turns that verdict into an outcome the
 * promotion door can fail on: it reads the document, prints the operator
 * diagnostic, and returns a value.
 *
 * Keeping the pass/fail decision out of here is deliberate. A script that
 * re-derived the rules could disagree with the model, and the model is what
 * the tests pin. This only reports and refuses.
 */
object PromotionCertificationAdmission {

    /**
     * Read the certification at [certificationPath] and decide whether it
     * admits [candidate] to a stable release.
     *
     * A document that cannot be read, or that is not the document this build
     * understands, refuses the promotion with the reason: an unreadable
     * certification is not a certification that admits nothing, it is a
     * certification nobody can account for, and promoting on the strength of
     * it would be exactly the mistake the boundary exists to prevent.
     */
    fun admit(certificationPath: Path, candidate: CandidateId): PromotionCertificationOutcome {
        val text = readText(certificationPath)
            ?: return PromotionCertificationOutcome.Refused(
                "the promotion certification at $certificationPath does not exist or cannot be " +
                    "read. A stable release is promoted only against the complete certification " +
                    "of the candidate it carries.",
            )

        val certification = when (val decoded = PromotionCertificationCodec.decode(text)) {
            is PromotionCertificationDecode.Rejected ->
                return PromotionCertificationOutcome.Refused(
                    "the promotion certification at $certificationPath is not a certification " +
                        "this build can act on: ${decoded.render()}",
                )

            is PromotionCertificationDecode.Accepted -> decoded.certification
        }

        return when (val verdict = evaluatePromotionCertification(certification, candidate)) {
            is PromotionCertificationVerdict.Admissible -> PromotionCertificationOutcome.Admitted(
                candidateId = verdict.certification.candidateId.canonical,
                summary = verdict.render(),
            )

            is PromotionCertificationVerdict.Rejected -> PromotionCertificationOutcome.Refused(
                verdict.render(),
            )
        }
    }

    /** The text of the document at [path], or null when it cannot be read. */
    private fun readText(path: Path): String? = try {
        if (Files.isRegularFile(path)) Files.readString(path) else null
    } catch (e: IOException) {
        null
    }
}

/**
 * Outcome of the certification check at the promotion boundary.
 */
sealed interface PromotionCertificationOutcome {

    /**
     * The certification admits the candidate. [summary] is the verdict's own
     * diagnostic, carried through verbatim so the promotion log shows what the
     * gate concluded rather than a flattened "OK".
     */
    data class Admitted(
        val candidateId: String,
        val summary: String,
    ) : PromotionCertificationOutcome

    /** The candidate is not admitted to a stable release. The promotion MUST NOT continue. */
    data class Refused(val reason: String) : PromotionCertificationOutcome
}
