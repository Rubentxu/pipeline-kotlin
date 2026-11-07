package dev.rubentxu.pipeline.v2.runtime

import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * THE single authority for the `core.archiveArtifacts` wire format.
 *
 * ## Why this is extracted rather than private to [CoreArchiveArtifactsStep]
 * The DSL compiler also has to PRODUCE this payload, because the archiveArtifacts
 * step is declared in `pipeline-scripting-api` and lowered by the compiler. Letting
 * the compiler hand-write the JSON would leave two producers of one format, and the
 * compiler drifts.
 *
 * ## Why this family was migrated FIRST
 * This is the format whose decode hides the MOST defaults: `allowEmptyArchive`,
 * `excludes` and `fingerprint` are all read as absent-means-default. A producer that
 * forgets one of them does not fail — it changes what the run archives, silently.
 * With two producers of that format, "forgets one" is a matter of time; with one, it
 * is not expressible.
 *
 * ## The bytes are unchanged, and that is the contract
 * The compiled payload is hashed into the durable fingerprint, so a changed byte is
 * not a cosmetic change: a resume of an affected run FINDS its row, sees a DIVERGED
 * fingerprint and aborts fail-closed instead of re-executing the step. The envelope
 * this codec writes is therefore byte-for-byte the one the compiler has always
 * written: `{"kind":"archiveArtifacts","artifacts":...,"allowEmptyArchive":...,
 * "excludes":...,"fingerprint":...}`, plus `name` only when it is not null.
 *
 * ## The boundary this preserves
 * The compiler knows the `StepSpec.ArchiveArtifacts -> ArchiveArtifactsInput`
 * transformation, which is a DOMAIN decision; it does not know the wire format,
 * which is an ENCODING decision. This object owns the encoding, and the Step that
 * reads the payload owns the other half of the same authority.
 */
object CoreArchiveArtifactsWireCodec : StepCodec<ArchiveArtifactsInput> {

    override fun encode(value: ArchiveArtifactsInput): EncodedStepValue =
        EncodedStepValue(
            Json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("kind", JsonPrimitive("archiveArtifacts"))
                    put("artifacts", JsonPrimitive(value.artifacts))
                    put("allowEmptyArchive", JsonPrimitive(value.allowEmptyArchive))
                    put("excludes", JsonPrimitive(value.excludes))
                    put("fingerprint", JsonPrimitive(value.fingerprint))
                    // Optional name. Only
                    // emitted when non-null to preserve byte-shape for
                    // legacy callers (backward-compat).
                    value.name?.let { put("name", JsonPrimitive(it)) }
                },
            ),
        )

    override fun decode(encoded: EncodedStepValue): ArchiveArtifactsInput {
        val obj = Json.parseToJsonElement(encoded.value).jsonObject
        require(obj["kind"]?.jsonPrimitive?.content == "archiveArtifacts") {
            "core.archiveArtifacts payload kind must be 'archiveArtifacts'"
        }
        return ArchiveArtifactsInput(
            artifacts = obj.getValue("artifacts").jsonPrimitive.content,
            allowEmptyArchive = obj["allowEmptyArchive"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
            excludes = obj["excludes"]?.jsonPrimitive?.content.orEmpty(),
            fingerprint = obj["fingerprint"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
            name = obj["name"]?.jsonPrimitive?.content,
        )
    }
}
