package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.domain.saga.CompensationBodyDigest
import dev.rubentxu.pipeline.v2.domain.saga.CompensationId
import dev.rubentxu.pipeline.v2.domain.saga.CompensationKind
import dev.rubentxu.pipeline.v2.domain.saga.CompensationObligationStatus
import dev.rubentxu.pipeline.v2.domain.saga.EffectReceiptRef
import dev.rubentxu.pipeline.v2.domain.saga.ResourceRef
import dev.rubentxu.pipeline.v2.domain.saga.SagaActionId
import dev.rubentxu.pipeline.v2.domain.saga.SagaId
import dev.rubentxu.pipeline.v2.domain.saga.SagaIntentContract
import dev.rubentxu.pipeline.v2.domain.saga.SagaPlanFingerprint
import java.sql.ResultSet

/**
 * How a durable obligation row is read.
 *
 * The row has exactly one meaning, so it has exactly one reader: the store that writes it and
 * the reader that only looks at it both come here. A second copy of this decoding would be a
 * second opinion about what a promise says, and the two would eventually disagree about a
 * status nobody looked at.
 *
 * Reading fails closed on anything this build does not understand: an unknown intent version,
 * an unknown status or an unknown kind raises instead of being coerced into a default. A
 * promise whose meaning cannot be established is not a promise this build may honour.
 */
internal object SagaObligationRowCodec {

    /**
     * Reads one row.
     */
    fun read(rs: ResultSet): SagaObligationRow {
        val intentVersion = rs.getInt("intent_version")
        SagaIntentContract.requireSupported(intentVersion)
        val receipt = rs.getString("receipt_ref")
        return SagaObligationRow(
            runId = rs.getString("run_id"),
            sagaId = SagaId.of(rs.getString("saga_id")),
            actionId = SagaActionId.of(rs.getString("action_id")),
            compensationId = CompensationId.of(rs.getString("compensation_id")),
            intentVersion = intentVersion,
            planFingerprint = SagaPlanFingerprint(rs.getString("plan_fingerprint")),
            resourceRef = ResourceRef.of(rs.getString("resource_ref")),
            kind = CompensationKind.valueOf(rs.getString("compensation_kind")),
            status = CompensationObligationStatus.valueOf(rs.getString("status")),
            forwardOpId = rs.getString("forward_op_id"),
            compensationOpId = rs.getString("compensation_op_id"),
            leaseEpoch = rs.getLong("lease_epoch"),
            createdAtMs = rs.getLong("created_at_ms"),
            updatedAtMs = rs.getLong("updated_at_ms"),
            receiptRef = receipt?.let { EffectReceiptRef.of(it) },
            ownerPluginId = ownerOf(rs),
            bodyDigest = bodyDigestOf(rs),
        )
    }

    /**
     * The plugin that armed the promise, when the database says so.
     *
     * A database written before the provenance was kept has no such column, and the read-only
     * reader cannot add it. A promise whose owner was never recorded is not treated as owned by
     * anyone, so an absent owner is the honest reading rather than a failure.
     */
    private fun ownerOf(rs: ResultSet): String? {
        val meta = rs.metaData
        for (index in 1..meta.getColumnCount()) {
            if (meta.getColumnLabel(index) == OWNER_COLUMN) return rs.getString(index)
        }
        return null
    }

    /**
     * The shape of the body the promise is paid with, when the database says so.
     *
     * Two absences are possible and both mean "this promise names no body": a database written
     * before digests were kept has no such column, and a digest that cannot be read back cannot
     * be compared with anything. A promise that cannot prove a divergence does not block
     * anything, so an unreadable digest is not a failure.
     */
    private fun bodyDigestOf(rs: ResultSet): CompensationBodyDigest? {
        val meta = rs.metaData
        for (index in 1..meta.getColumnCount()) {
            if (meta.getColumnLabel(index) != BODY_DIGEST_COLUMN) continue
            val hex = rs.getString(index) ?: return null
            return runCatching { CompensationBodyDigest(hex) }.getOrNull()
        }
        return null
    }

    private const val OWNER_COLUMN = "owner_plugin_id"
    private const val BODY_DIGEST_COLUMN = "body_digest"
}
