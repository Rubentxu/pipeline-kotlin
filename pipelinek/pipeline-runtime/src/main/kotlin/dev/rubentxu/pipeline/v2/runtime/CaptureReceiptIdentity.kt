package dev.rubentxu.pipeline.v2.runtime

import dev.rubentxu.pipeline.v2.domain.digest.Sha256
import java.time.Instant
import java.util.UUID

/**
 * The identity vocabulary of artifact captures.
 *
 * Each type names ONE question about a capture, and no two types answer the same
 * question: a digest identifies CONTENT but grants no authorization, a visible name
 * DESCRIBES but never identifies, and a capture is an event in a run that no name and
 * no digest can stand for. The types are deliberately distinct so the compiler, not a
 * convention, refuses a value offered as the wrong kind of identity — a [BlobId] passed
 * where an [ArtifactId] is asked does not compile, and neither can be built from the
 * other without an explicit conversion.
 */
@JvmInline
value class ArtifactId(val value: String) {
    init {
        require(value.isNotBlank()) { "ArtifactId must not be blank" }
    }

    companion object {
        /**
         * Mints the identity of one capture.
         *
         * Every mint is a different capture: the identity is drawn from the capture
         * moment, never from the captured name, the captured path or the captured
         * bytes, so two captures of the same bytes under the same name stay two
         * captures. Only the capture port mints identities; a caller that needs to
         * refer to an existing capture takes it from the receipt that carries it.
         */
        fun mint(): ArtifactId = ArtifactId(UUID.randomUUID().toString())
    }
}

/**
 * The visible name a capture was taken under.
 *
 * A name describes what the capture is FOR, and several captures may share one: the
 * name carries no identity and no uniqueness, and consumers that need either take
 * [ArtifactId] or [BlobId] from the receipt instead. The rules are the ones a durable
 * record can carry verbatim: non-empty, non-blank, no line separators.
 */
@JvmInline
value class ArtifactName(val value: String) {
    init {
        require(value.isNotEmpty()) { "ArtifactName must not be empty" }
        require(value.isNotBlank()) { "ArtifactName must not be blank" }
        require('\n' !in value && '\r' !in value) {
            "ArtifactName must not contain newlines"
        }
    }
}

/**
 * The identity of the immutable bytes a capture secured.
 *
 * The value is the canonical lowercase SHA-256 hex of the content, and it is the name
 * of the write-once digest address the retention tree holds the bytes at — the address
 * no later capture rewrites. It identifies content, not a capture: two captures of the
 * same bytes share one [BlobId] and never share an [ArtifactId], and holding a
 * [BlobId] says nothing about who may move, replace or consume the bytes.
 */
@JvmInline
value class BlobId(val value: String) {
    init {
        require(Sha256.isSha256Hex(value)) {
            "BlobId must be a canonical 64-character lowercase hex digest; " +
                "got '${value.take(8)}...' (${value.length} chars)"
        }
    }
}

/**
 * Evidence that another component assumed custody of a capture's bytes.
 *
 * The custodian states the assumption itself, so the receipt is only as true as the
 * component that issued it: it names WHICH capture ([captureId]) and WHICH bytes
 * ([blobId]) moved into whose custody. Assuming custody is not capturing, and a
 * custody receipt does not keep the capture's guarantees alive — it records that the
 * custodian now answers for the bytes, not that the bytes were re-verified.
 */
data class CustodyReceipt(
    val captureId: ArtifactId,
    val blobId: BlobId,
    val custodian: String,
) {
    init {
        require(custodian.isNotBlank()) {
            "CustodyReceipt requires the identity of the component that assumed custody"
        }
    }
}

/**
 * A temporary permission to transfer bytes, not the transfer itself.
 *
 * The ticket names WHICH bytes ([blobId]), WHO may move them ([grantedTo]) and UNTIL
 * WHEN the permission holds ([expiresAt]) — after that instant the ticket authorizes
 * nothing. Holding a ticket grants nothing beyond the transfer it names: it does not
 * certify the bytes, it does not perform the move, and it does not extend any custody
 * the bytes were under.
 */
data class TransferTicket(
    val blobId: BlobId,
    val grantedTo: String,
    val expiresAt: Instant,
) {
    init {
        require(grantedTo.isNotBlank()) {
            "TransferTicket requires the identity of the component the transfer is granted to"
        }
    }
}
