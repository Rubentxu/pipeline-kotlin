package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.step.AdmissionRejection
import dev.rubentxu.pipeline.v2.domain.step.ArtifactOrigin
import dev.rubentxu.pipeline.v2.domain.step.Digest
import dev.rubentxu.pipeline.v2.domain.step.TrustMetadata
import dev.rubentxu.pipeline.v2.domain.digest.Sha256
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

/**
 * B3.2 — single-writer durable store for admission control rows.
 *
 * ## The RETRY-D analogy, applied to admission
 *
 * The retry aggregate has a control row so a second invocation of the binary with the
 * same `--db` and `--control-root` does not re-run a successful child. The admission
 * aggregate has the same problem: a refused plugin, reported back to the caller, leaves
 * no evidence the run can re-read. Re-running the same artifacts through the gate would
 * re-decide the same way, but the OPERATOR needs to be able to look at a single file and
 * see why a plugin was refused — without re-running anything.
 *
 * The retry control row is the precedent: persist BEFORE the effect, write under
 * `<controlDirRoot>/<family>/<sha256-identity>.<extension>`, atomically renamed. The
 * admission control row uses the same shape under
 * `<controlDirRoot>/admission-control/<sha256-identity>.row.json`.
 *
 * ## Persist-before-decision-returns
 *
 * The gate's contract: the row is on disk before the gate's refusal is returned. A
 * refused plugin that left the row in memory only would defeat the whole point, because
 * the row is the audit trail.
 *
 * ## Single-writer
 *
 * No other component writes to this file. The gate is the only writer; the read path is
 * for the operator and for tests. A future planner that reads admission history gets the
 * same single-writer property the retry path already has.
 */
class AdmissionControlJournal(controlDirRoot: Path) {

    private val admissionControlDir: Path = controlDirRoot.resolve("admission-control")

    init {
        Files.createDirectories(admissionControlDir)
    }

    /**
     * Persist a row.
     *
     * Atomic write: writes to a temp file in the same directory and atomically renames.
     * A crashed writer never leaves a half-written row visible to a reader.
     *
     * @return the absolute path of the persisted row file (useful for the gate's
     *   pre-conditions assertions; production callers do not normally need it).
     */
    fun record(row: AdmissionControlRow): Path {
        val file = rowFile(row.pluginId)
        val tmp = Files.createTempFile(admissionControlDir, "row-", ".json")
        Files.writeString(tmp, encode(row), Charsets.UTF_8)
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
        return file
    }

    /**
     * Read the persisted row for [pluginId], or `null` if the gate has not yet decided
     * that identity. A second run that re-encounters the same plugin gets the durable
     * record of the prior decision.
     */
    fun readRow(pluginId: String): AdmissionControlRow? {
        val file = rowFile(pluginId)
        if (!Files.exists(file)) return null
        val text = Files.readString(file, Charsets.UTF_8)
        return decode(pluginId, text)
    }

    private fun rowFile(pluginId: String): Path =
        admissionControlDir.resolve("${fileKey(pluginId)}.row.json")

    private fun fileKey(pluginId: String): String = Sha256.ofText(pluginId)
}

/**
 * B3.2 — one durable admission decision.
 *
 * Written BEFORE the gate's refusal returns (RETRY-D, ADR-0075 §6). The [verdict] field
 * is the runtime's typed identity verdict; [refusedKind] / [refusedDetail] name the
 * specific [AdmissionRejection] case when the gate refused.
 *
 * @property pluginId the canonical [ResourceRef] text of the plugin; the durable key
 * @property origin the [ArtifactOrigin.canonicalText] the gate measured
 * @property declaredDigest the digest the manifest's `releaseDigest` field carried
 * @property measuredDigest the SHA-256 the gate computed over the artifact bytes
 *   (`null` when the runtime could not read them — the [verdict] is
 *   `Unverified.ArtifactNotMeasurable`)
 * @property verdict the runtime's typed trust verdict (one of Verified, Unverified, Mismatch)
 * @property refusedKind the first-level case name of [AdmissionRejection] (nullable
 *   when the gate admitted)
 * @property refusedDetail the rejection's payload text, if any (nullable)
 * @property recordedAt when the row was written
 */
data class AdmissionControlRow(
    val pluginId: String,
    val origin: String,
    val declaredDigest: Digest,
    val measuredDigest: Digest?,
    val verdict: TrustMetadata,
    val refusedKind: String?,
    val refusedDetail: String?,
    val recordedAt: Instant,
) {
    companion object {
        /**
         * Compose a row from the gate's typed inputs.
         *
         * The [verdict] and [rejection] are produced together so the row is consistent:
         * a [AdmissionRejection] implies a [TrustMetadata.Unverified] or [Mismatch] verdict,
         * and an [Admitted] plugin has `refusedKind = null`. The composer is the only
         * place this invariant is checked.
         */
        fun compose(
            pluginId: String,
            origin: ArtifactOrigin,
            declaredDigest: Digest,
            measuredDigest: Digest?,
            verdict: TrustMetadata,
            rejection: AdmissionRejection?,
        ): AdmissionControlRow {
            val (refusedKind, refusedDetail) = when (rejection) {
                null -> null to null
                else -> rejection::class.simpleName!! to rejection.toString()
            }
            return AdmissionControlRow(
                pluginId = pluginId,
                origin = origin.canonicalText(),
                declaredDigest = declaredDigest,
                measuredDigest = measuredDigest,
                verdict = verdict,
                refusedKind = refusedKind,
                refusedDetail = refusedDetail,
                recordedAt = Instant.now(),
            )
        }
    }
}

/**
 * Canonical text of an [ArtifactOrigin]. Hand-written to keep the durable row independent
 * of any Kotlin data class's `toString()` evolution.
 */
private fun ArtifactOrigin.canonicalText(): String = when (this) {
    is ArtifactOrigin.LocalClasspathEntry -> "local:$location"
    is ArtifactOrigin.ResolvedCoordinate -> "coord:$group:$artifact:$version"
    is ArtifactOrigin.ReusedCompiledArtifact -> "cache:$cacheKey"
    ArtifactOrigin.Unknown -> "unknown"
}

private fun encode(row: AdmissionControlRow): String {
    val verdict = when (val v = row.verdict) {
        is TrustMetadata.Verified -> "Verified"
        is TrustMetadata.Unverified -> when (v.reason) {
            TrustMetadata.UnverifiedReason.NoTrustedDigestAvailable -> "Unverified.NoTrustedDigestAvailable"
            TrustMetadata.UnverifiedReason.ArtifactNotMeasurable -> "Unverified.ArtifactNotMeasurable"
            TrustMetadata.UnverifiedReason.OutsideOperatorTrustPolicy -> "Unverified.OutsideOperatorTrustPolicy"
        }
        is TrustMetadata.Mismatch -> "Mismatch"
    }
    val measured = row.measuredDigest?.value ?: ""
    // Hand-rolled JSON to keep the on-disk format stable and free of any third-party
    // serializer's defaults. Same approach as PluginManifestCodec.
    val sb = StringBuilder()
    sb.append("{\n")
    sb.append("  \"schemaVersion\": 1,\n")
    sb.append("  \"pluginId\": \"").append(escape(row.pluginId)).append("\",\n")
    sb.append("  \"origin\": \"").append(escape(row.origin)).append("\",\n")
    sb.append("  \"declaredDigest\": \"").append(escape(row.declaredDigest.value)).append("\",\n")
    sb.append("  \"measuredDigest\": \"").append(escape(measured)).append("\",\n")
    sb.append("  \"verdict\": \"").append(escape(verdict)).append("\",\n")
    sb.append("  \"refusedKind\": ").append(row.refusedKind?.let { "\"${escape(it)}\"" } ?: "null").append(",\n")
    sb.append("  \"refusedDetail\": ").append(row.refusedDetail?.let { "\"${escape(it)}\"" } ?: "null").append(",\n")
    sb.append("  \"recordedAt\": \"").append(row.recordedAt.toString()).append("\"\n")
    sb.append("}\n")
    return sb.toString()
}

private fun decode(pluginId: String, text: String): AdmissionControlRow {
    // Same hand-rolled cursor shape as PluginManifestCodec; small enough to inline here.
    val obj = TinyJsonObject(text).readObject()
        ?: error("admission control row at pluginId=$pluginId is not a JSON object")
    val declaredRaw = obj.string("declaredDigest")
        ?: error("admission control row missing declaredDigest")
    val measuredRaw = obj.string("measuredDigest")
    val verdictRaw = obj.string("verdict")
        ?: error("admission control row missing verdict")
    val refusedKind = obj.string("refusedKind")
    val refusedDetail = obj.string("refusedDetail")
    val recordedAt = obj.string("recordedAt")?.let { Instant.parse(it) } ?: Instant.EPOCH

    val verdict: TrustMetadata = when (verdictRaw) {
        "Verified" -> TrustMetadata.Verified(
            digest = Digest(measuredRaw ?: declaredRaw),
            origin = ArtifactOrigin.Unknown,
        )
        "Unverified.NoTrustedDigestAvailable" -> TrustMetadata.Unverified(
            reason = TrustMetadata.UnverifiedReason.NoTrustedDigestAvailable,
            origin = ArtifactOrigin.Unknown,
        )
        "Unverified.ArtifactNotMeasurable" -> TrustMetadata.Unverified(
            reason = TrustMetadata.UnverifiedReason.ArtifactNotMeasurable,
            origin = ArtifactOrigin.Unknown,
        )
        "Unverified.OutsideOperatorTrustPolicy" -> TrustMetadata.Unverified(
            reason = TrustMetadata.UnverifiedReason.OutsideOperatorTrustPolicy,
            origin = ArtifactOrigin.Unknown,
        )
        "Mismatch" -> TrustMetadata.Mismatch(
            declared = Digest(declaredRaw),
            measured = Digest(measuredRaw ?: declaredRaw),
            origin = ArtifactOrigin.Unknown,
        )
        else -> error("admission control row has unknown verdict: $verdictRaw")
    }
    return AdmissionControlRow(
        pluginId = pluginId,
        origin = obj.string("origin") ?: "unknown",
        declaredDigest = Digest(declaredRaw),
        measuredDigest = measuredRaw?.takeIf { it.isNotEmpty() }?.let { Digest(it) },
        verdict = verdict,
        refusedKind = refusedKind,
        refusedDetail = refusedDetail,
        recordedAt = recordedAt,
    )
}

private fun escape(s: String): String = s
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\r", "\\r")
    .replace("\t", "\\t")

/**
 * Tiny JSON object reader. Same approach as the one in
 * [dev.rubentxu.pipeline.v2.application.OperatorTrustDatabase]: a small cursor that
 * only knows objects, strings and null/long literals. The on-disk format is contractual
 * and a richer parser would invite a second authority.
 */
private class TinyJsonObject(private val source: String) {
    private var pos = 0

    fun readObject(): Map<String, Any?>? {
        skipWs()
        if (!consume('{')) return null
        val out = LinkedHashMap<String, Any?>()
        skipWs()
        if (consume('}')) return out
        while (true) {
            skipWs()
            val key = readString() ?: return null
            skipWs()
            if (!consume(':')) return null
            skipWs()
            val value = readValue()
            out[key] = value
            skipWs()
            if (consume(',')) continue
            if (consume('}')) return out
            return null
        }
    }

    private fun readValue(): Any? {
        skipWs()
        return when (peek()) {
            '"' -> readString()
            'n' -> if (consumeLiteral("null")) null else error("invalid null at $pos")
            't' -> if (consumeLiteral("true")) true else error("invalid true at $pos")
            'f' -> if (consumeLiteral("false")) false else error("invalid false at $pos")
            else -> {
                // number — read digits + dot
                val start = pos
                while (pos < source.length && (source[pos].isDigit() || source[pos] == '-' || source[pos] == '.')) pos++
                if (pos == start) error("invalid value at $pos")
                source.substring(start, pos).toLongOrNull()
            }
        }
    }

    private fun readString(): String? {
        if (!consume('"')) return null
        val sb = StringBuilder()
        while (pos < source.length) {
            when (val c = source[pos++]) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (pos >= source.length) return null
                    when (val esc = source[pos++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        else -> return null
                    }
                }
                else -> sb.append(c)
            }
        }
        return null
    }

    private fun consumeLiteral(literal: String): Boolean {
        if (pos + literal.length > source.length) return false
        if (source.substring(pos, pos + literal.length) != literal) return false
        pos += literal.length
        return true
    }

    private fun peek(): Char? = if (pos < source.length) source[pos] else null

    private fun consume(c: Char): Boolean {
        if (pos < source.length && source[pos] == c) {
            pos++
            return true
        }
        return false
    }

    private fun skipWs() {
        while (pos < source.length && source[pos].isWhitespace()) pos++
    }
}

private fun Map<String, Any?>.string(key: String): String? {
    val v = this[key] ?: return null
    return v as? String
}
