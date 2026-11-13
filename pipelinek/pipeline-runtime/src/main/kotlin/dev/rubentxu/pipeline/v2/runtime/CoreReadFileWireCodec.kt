package dev.rubentxu.pipeline.v2.runtime

import dev.rubentxu.pipeline.v2.domain.StepArgument
import dev.rubentxu.pipeline.v2.domain.StepArgumentType
import dev.rubentxu.pipeline.v2.domain.StepArguments
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * THE single authority for the `core.readFile` wire format.
 *
 * ## Why this is extracted rather than private to [CoreReadFileStep]
 * The DSL compiler also has to PRODUCE this payload, because the readFile step is
 * declared in `pipeline-scripting-api` and lowered by the compiler. Letting the
 * compiler hand-write the JSON would leave two producers of one format, and the
 * compiler drifts.
 *
 * ## The bytes are unchanged, and that is the contract
 * The compiled payload is hashed into the durable fingerprint, so a changed byte is
 * not a cosmetic change: a resume of an affected run FINDS its row, sees a DIVERGED
 * fingerprint and aborts fail-closed instead of re-executing the step. The envelope
 * this codec writes is therefore byte-for-byte the one the compiler has always
 * written: `{"kind":"readFile","file":...,"encoding":...}`, in that key order.
 *
 * ## The boundary this preserves
 * The compiler knows the `StepSpec.ReadFile -> CoreReadFileInput` transformation,
 * which is a DOMAIN decision; it does not know the wire format, which is an ENCODING
 * decision. This object owns the encoding, and the Step that reads the payload owns
 * the other half of the same authority.
 */
object CoreReadFileWireCodec : StepCodec<CoreReadFileInput> {

    override fun encode(value: CoreReadFileInput): EncodedStepValue =
        EncodedStepValue(
            Json.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("kind", JsonPrimitive("readFile"))
                put("file", JsonPrimitive(value.file))
                put("encoding", JsonPrimitive(value.encoding))
            }),
        )

    /**
     * The decode is STRICT: every field is mandatory and a missing one is a refusal,
     * not a default. That is what makes a divergence between two producers loud
     * instead of silent.
     */
    override fun decode(encoded: EncodedStepValue): CoreReadFileInput {
        val obj = Json.parseToJsonElement(encoded.value).jsonObject
        require(obj["kind"]?.jsonPrimitive?.content == "readFile") {
            "core.readFile payload kind must be 'readFile'"
        }
        return CoreReadFileInput(
            file = obj.getValue("file").jsonPrimitive.content,
            encoding = obj.getValue("encoding").jsonPrimitive.content,
        )
    }

    /** The arguments this step accepts, as the catalogue publishes them. */
    override fun schema(): String = StepArguments(
        arguments = listOf(
            StepArgument(
                name = "file",
                type = StepArgumentType.STRING,
                required = true,
                description = "the path of the file to read, relative to the workspace",
            ),
            StepArgument(
                name = "encoding",
                type = StepArgumentType.STRING,
                required = true,
                description = "the encoding the file is read with",
            ),
        ),
    ).jsonSchema("core.readFile")
}
