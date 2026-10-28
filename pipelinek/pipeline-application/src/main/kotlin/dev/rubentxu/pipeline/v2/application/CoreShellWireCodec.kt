package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.ShellCommand
import dev.rubentxu.pipeline.v2.domain.ShellReturnMode
import dev.rubentxu.pipeline.v2.domain.decideShellReturnMode
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * THE single authority for the `core.sh` wire format.
 *
 * ## Why this is extracted rather than private to [CoreShellStep]
 * The DSL compiler also has to PRODUCE this payload, because `StepSpec.Shell` lives
 * in `pipeline-scripting-api`, where `CoreShellInput` cannot be seen. That creates a
 * temptation: let the compiler hand-write the JSON. Doing so leaves two producers of
 * one format, and the compiler drifts.
 *
 * That is not hypothetical — it is what happened. `core.sh` had a compiler that wrote
 * one dialect and a codec that wrote another, so the reader had to carry a pile of
 * aliases for a format nobody owned:
 * ```text
 * kind          = sh      (compiler)  |  shell    (codec's own self-encoding)
 * script        = command (compiler)  |  script   (codec's own self-encoding)
 * returnStdout  = returnStdout (compiler)  |  returnMode (codec)
 * ```
 * Every one of those aliases is a compatibility shim added after the two producers
 * diverged. This object refuses to manufacture the same debt again: the compiler and
 * the Step both call THIS object, so no second place hand-writes the format.
 *
 * ## Why there are still TWO writers, and ONE reader
 * The two dialects are both durable-digest inputs ALREADY WRITTEN by shipped code:
 *  - the declarative dialect is journalled as `params("payload")`, and
 *  - the scripted dialect is journalled as `params("encodedInput")`,
 * while the operation id carries no payload at all. So unifying the two writers would
 * not be a refactor: it would move a shipped fingerprint, and a resume of that run
 * would FIND the row, see a DIVERGED fingerprint, and abort with REPLAY_COMPATIBILITY.
 * There is no sanctioned migration for a fingerprint, so the bytes of both dialects
 * are frozen.
 *
 * Hence: two writers, each reproducing byte-for-byte what its own producer has always
 * written, and ONE reader that accepts every shipped spelling and normalizes it to
 * ONE interior ([CoreShellInput]). The aliases stop being drift scars and become this
 * authority's declared vocabulary.
 *
 * ## The boundary this preserves
 * `pipeline-scripting-api` and `pipeline-domain` never learn about JSON, the
 * coordinator, the capability or the backend. The compiler knows the
 * `StepSpec.Shell -> CoreShellInput` transformation, which is a DOMAIN decision; it
 * does not know the wire format, which is an ENCODING decision.
 * `internal` rather than private precisely so the compiler in this same module can
 * reach it; it is not part of any published surface.
*/
internal object CoreShellWireCodec : StepCodec<CoreShellInput> {

    /**
     * The DECLARATIVE dialect: the bytes the DSL compiler has always written for a
     * compiled `sh` step.
     *
     * Byte-for-byte what the compiler used to build by hand, so no durable body
     * identity moves. `returnStdout` is the dialect's own vocabulary: it spans
     * NONE and STDOUT only, so a STATUS interior has no spelling here and is
     * refused rather than silently downgraded to "no stdout".
    */
    fun encodeDeclarative(value: CoreShellInput): EncodedStepValue {
        if (value.command.returnMode == ShellReturnMode.STATUS) {
            throw CoreShellCodecException(
                "core.sh declarative dialect has no 'STATUS' spelling: it declares stdout or nothing",
            )
        }
        val obj: JsonObject = buildJsonObject {
            put("kind", JsonPrimitive("sh"))
            put("command", JsonPrimitive(value.command.script))
            put("isScriptBlock", JsonPrimitive(value.isScriptBlock))
            put("returnStdout", JsonPrimitive(value.command.returnMode == ShellReturnMode.STDOUT))
        }
        return EncodedStepValue(Json.encodeToString(JsonObject.serializer(), obj))
    }

    /**
     * The SCRIPTED dialect: the bytes this codec has always written for a scripted
     * shell call, and the [StepCodec] contract the scripted runtime encodes through.
     *
     * Byte-for-byte unchanged, so no shipped scripted fingerprint moves. The
     * dialect has no `isScriptBlock` key: that flag is a COMPILE-time artifact of
     * the declarative dialect, and the scripted runtime never declared it.
    */
    override fun encode(value: CoreShellInput): EncodedStepValue {
        val obj: JsonObject = buildJsonObject {
            put("kind", JsonPrimitive("shell"))
            put("script", JsonPrimitive(value.command.script))
            value.command.encoding?.let { put("encoding", JsonPrimitive(it)) }
            value.command.label?.let { put("label", JsonPrimitive(it)) }
            put("returnMode", JsonPrimitive(value.command.returnMode.name))
        }
        return EncodedStepValue(Json.encodeToString(JsonObject.serializer(), obj))
    }

    /**
     * ONE reader for every shipped spelling, normalizing to ONE interior.
     *
     * Accepts `kind` `sh` or `shell`, the script under `command` or `script`, the
     * return mode as `returnMode` or as the `returnStdout`/`returnStatus` booleans,
     * and reads `encoding`, `label` and `isScriptBlock` when present. Anything else
     * is refused with a typed failure: a payload this authority cannot interpret
     * must not reach the runtime as a guess.
    */
    override fun decode(encoded: EncodedStepValue): CoreShellInput {
        val obj = try {
            Json.parseToJsonElement(encoded.value).jsonObject
        } catch (e: Exception) {
            throw CoreShellCodecException(
                "core.sh arguments are not a JSON object: ${e.message ?: "parse failed"}",
            )
        }
        val kind = obj["kind"]?.jsonPrimitive?.contentOrNull
        if (kind != "sh" && kind != "shell") {
            throw CoreShellCodecException("core.sh payload kind must be 'sh' or 'shell'")
        }
        val script = obj.stringOrNull("command") ?: obj.stringOrNull("script")
            ?: throw CoreShellCodecException("core.sh payload must include 'command' or 'script'")
        return CoreShellInput(
            command = ShellCommand(
                script = script,
                encoding = obj.stringOrNull("encoding"),
                label = obj.stringOrNull("label"),
                returnMode = readReturnMode(obj),
            ),
            isScriptBlock = obj.booleanOrNull("isScriptBlock") ?: false,
        )
    }

    /**
     * The return mode, in whichever vocabulary the payload was written.
     *
     * A `returnMode` key that is present but is not a known mode is REFUSED rather
     * than treated as absent: silently reading it as "no stdout" would run the step
     * under a different contract than the payload declares.
    */
    private fun readReturnMode(obj: JsonObject): ShellReturnMode {
        val raw = obj["returnMode"]
        if (raw != null) {
            val name = raw.jsonPrimitive.contentOrNull
                ?: throw CoreShellCodecException("core.sh 'returnMode' is not a string")
            return ShellReturnMode.entries.firstOrNull { it.name == name }
                ?: throw CoreShellCodecException("core.sh 'returnMode' is not a known mode: '$name'")
        }
        return decideShellReturnMode(
            returnStdout = obj.booleanOrNull("returnStdout") == true,
            returnStatus = obj.booleanOrNull("returnStatus") == true,
        )
    }
}
