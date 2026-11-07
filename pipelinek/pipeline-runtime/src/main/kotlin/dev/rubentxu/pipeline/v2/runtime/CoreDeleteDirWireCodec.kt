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
 * THE single authority for the `core.deleteDir` wire format.
 *
 * ## Why this is extracted rather than private to [CoreDeleteDirStep]
 * The DSL compiler also has to PRODUCE this payload, because the deleteDir step is
 * declared in `pipeline-scripting-api` and lowered by the compiler. Letting the
 * compiler hand-write the JSON would leave two producers of one format, and the
 * compiler drifts.
 *
 * This family is not a hypothetical: the compiler used to degrade it to
 * `{"kind":"deleteDir","declarativeValue":"StepSpec.DeleteDir(path=…)"}`, and the
 * decode below then read the ABSENT `path` as the default `"."`. The step deleted
 * the stage root instead of the declared directory, and nothing said so.
 *
 * ## Why the decode keeps tolerating an absent `path`
 * The decode still reads a missing `path` as `"."`, because that tolerance is the
 * shipped behaviour of a shipped format and this task moves no byte. What changes is
 * that there is now only ONE producer: a divergence is no longer expressible. The
 * tolerance stays as a property of the READER, not as a licence for the writer to
 * omit a field.
 *
 * ## The bytes are unchanged, and that is the contract
 * The compiled payload is hashed into the durable fingerprint, so a changed byte is
 * not a cosmetic change: a resume of an affected run FINDS its row, sees a DIVERGED
 * fingerprint and aborts fail-closed instead of re-executing the step. The envelope
 * this codec writes is therefore byte-for-byte the one the compiler has always
 * written: `{"kind":"deleteDir","path":...}`, in that key order.
 *
 * ## The boundary this preserves
 * The compiler knows the `StepSpec.DeleteDir -> DeleteDirInput` transformation, which
 * is a DOMAIN decision; it does not know the wire format, which is an ENCODING
 * decision. This object owns the encoding, and the Step that reads the payload owns
 * the other half of the same authority.
 */
object CoreDeleteDirWireCodec : StepCodec<DeleteDirInput> {

    override fun encode(value: DeleteDirInput): EncodedStepValue =
        EncodedStepValue(
            Json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("kind", JsonPrimitive("deleteDir"))
                    put("path", JsonPrimitive(value.path))
                },
            ),
        )

    override fun decode(encoded: EncodedStepValue): DeleteDirInput {
        val obj = Json.parseToJsonElement(encoded.value).jsonObject
        require(obj["kind"]?.jsonPrimitive?.content == "deleteDir") {
            "core.deleteDir payload kind must be 'deleteDir'"
        }
        val path = obj["path"]?.jsonPrimitive?.content ?: "."
        return DeleteDirInput(path = path)
    }
}
