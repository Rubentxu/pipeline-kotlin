package dev.rubentxu.pipeline.v2.release

import java.security.MessageDigest
import java.util.Locale

/**
 * The identity of a defect: the hash of its structural footprint.
 *
 * The identity is what makes the reception idempotent. The rule is "the same
 * defect is the same defect", and it is measured, not declared: the identity
 * is the SHA-256 of the canonical form of the footprint, so two reports of the
 * same failure produce the same identity no matter when they arrived, and two
 * different failures produce different identities.
 *
 * The canonical form is EXACTLY the one the harness already applies to its own
 * incidents (`harness/issues.py`): the four `key=value` pairs joined by the
 * unit separator, hashed with SHA-256 and cut to twelve hex characters. It is
 * not a similar canonical form, it is the same one, and that is the whole
 * point: if the two sides computed the identity differently, the same defect
 * would have two names and neither side would be able to recognise the other
 * one's. A change to this canonicalisation has to be a change to BOTH sides
 * at once, which is why the harness pins it with a test of its own.
 *
 * The identity carries NO candidate and NO date. A defect observed against two
 * candidates is one defect, and a date would make the same observation twice
 * look like two defects.
 *
 * The value is wrapped so it can only be built by hashing something: an
 * identity that anybody could spell by hand is an identity that two sides
 * would spell differently.
 */
@JvmInline
value class DefectIdentity private constructor(val canonical: String) {

    /** The label this defect carries in the task board of this repository. */
    val label: String get() = "defect:$canonical"

    override fun toString(): String = canonical

    companion object {

        /** How many hex characters of the digest the identity keeps. */
        private const val WIDTH = 12

        /**
         * The identity of the defect described by [fingerprint].
         *
         * Pure: it hashes the values it is given and touches nothing else.
         */
        fun of(fingerprint: DefectFingerprint): DefectIdentity {
            val canonical = listOf(
                "contract=${fingerprint.contract}",
                "scenario=${fingerprint.scenario}",
                "failure_type=${fingerprint.failureType.name}",
                "signature=${fingerprint.signature}",
            ).joinToString(SEPARATOR)
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(Charsets.UTF_8))
            val hex = digest.joinToString("") {
                String.format(Locale.ROOT, "%02x", it.toInt() and 0xff)
            }
            return DefectIdentity(hex.take(WIDTH))
        }

        /** The separator the canonical form joins its parts with. */
        private const val SEPARATOR = "\u001f"
    }
}

/**
 * The label a candidate carries in the task board of this repository.
 *
 * The label names the candidate by the first twelve hex characters of its
 * digest, which is enough to tell two candidates apart and short enough to be
 * read on a board.
 */
fun CandidateId.taskLabel(): String = "candidato:${digest.take(12)}"
