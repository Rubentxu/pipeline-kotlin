package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.MinimalJson
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
 * Single-writer durable store for admission control rows.
 * ## The analogy, applied to admission
 * The retry aggregate has a control row so a second invocation of the binary with the
 * same `--db` and `--control-root` does not re-run a successful child. The admission
 * aggregate has the same problem: a refused plugin, reported back to the caller, leaves
 * no evidence the run can re-read. Re-running the same artifacts through the gate would
 * re-decide the same way, but the OPERATOR needs to be able to look at a single file and
 * see why a plugin was refused — without re-running anything.
 * The retry control row is the precedent: persist BEFORE the effect, write under
 * `<controlDirRoot>/<family>/<sha256-identity>.<extension>`, atomically renamed. The
 * admission control row uses the same shape under
 * `<controlDirRoot>/admission-control/<sha256-identity>.row.json`.
 * ## Persist-before-decision-returns
 * The admission pass's contract: the row is on disk before the decision is returned. A
 * refused plugin that left the row in memory only would defeat the whole point, because
 * the row is the audit trail.
 * ## Single-writer
 * No other component writes to this file. The admission pass is the only writer; the read path is
 * for the operator and for tests. A future planner that reads admission history gets the
 * same single-writer property the retry path already has.
 * ## Why the directory appears only when a row is written
 * The directory is created when the first row is recorded, not when the journal is built. An
 * installation that composes no plugins must not leave an empty `admission-control/` behind in
 * every control root it touches: a directory that means nothing is noise an operator has to learn
 * to ignore.
*/
class AdmissionControlJournal(controlDirRoot: Path) {

    private val controlRoot: Path = controlDirRoot

    /**
     * Persist a row.
     * Atomic write: writes to a temp file in the same directory and atomically renames.
     * A crashed writer never leaves a half-written row visible to a reader.
     * @return the absolute path of the persisted row file (useful for the gate's
     *   pre-conditions assertions; production callers do not normally need it).
*/
    fun record(row: AdmissionControlRow): Path {
        val dir = admissionControlDir()
        val file = rowFile(row.pluginId)
        val tmp = Files.createTempFile(dir, "row-", ".json")
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

    private fun admissionControlDir(): Path =
        controlRoot.resolve("admission-control").also { Files.createDirectories(it) }

    private fun rowFile(pluginId: String): Path =
        admissionControlDir().resolve("${fileKey(pluginId)}.row.json")

    private fun fileKey(pluginId: String): String = Sha256.ofText(pluginId)
}

/**
 * One durable admission decision.
 * Written BEFORE the gate's refusal returns. The [verdict] field
 * is the runtime's typed identity verdict; [refusedKind] / [refusedDetail] name the
 * specific [AdmissionRejection] case when the gate refused.
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
    // The same reader every other document in this module uses: one grammar, one answer.
    val obj = MinimalJson.parseObject(text)
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

private fun Map<String, Any?>.string(key: String): String? {
    val v = this[key] ?: return null
    return v as? String
}
