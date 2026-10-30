package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.CatchErrorBuildResult
import dev.rubentxu.pipeline.v2.domain.step.BodyOutcomeFold
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The DECLARED contract of a control scope (`catchError` / `warnError`), as one value.
 *
 * ## Why this type exists
 * Before the cut, a control scope carried no contract at all: the compiler translated it
 * into a shell script and the runtime read that script. The two results the scope could
 * declare — how the caught failure is absorbed (`buildResult`) and what the enclosing stage
 * then sees (`stageResult`) — were consumed by the translation itself and never reached a
 * typed carrier. The scope was therefore executed by an invented language while the product
 * had no vocabulary for the thing it was executing.
 *
 * This value is that vocabulary. It is the ONLY place where the declared result family,
 * the stage-level result and the author's message are read from.
 *
 * ## The fold is part of the contract, not of the caller
 * [fold] says which of the two declared effects this scope owns:
 *  - [BodyOutcomeFold.CONTAINMENT] — the scope absorbs the body's typed outcome. `catchError`.
 *  - [BodyOutcomeFold.OUTPUT_DECORATION] — the scope decorates the body's outcome and marks
 *    the stage unstable. `warnError`.
 *
 * The fold is what the descriptor row declares and what the runtime checks the payload
 * against: a payload whose `kind` does not match the declared fold is a typed rejection,
 * not a silently accepted mismatch. Without that check the two families could swap their
 * semantics in the payload and the engine would fold the wrong way.
 *
 * ## `catchInterruptions` has no behavioural effect yet, and says so
 * The author-facing flag is carried so the declaration survives the cut and is visible in
 * the durable payload. It is NOT read by any consumer: in the canonical engine an
 * interruption of a body arrives as an ordinary typed failure at the step boundary, so
 * there is nothing for the flag to switch on. It is encoded, never defaulted on decode
 * (absence is a rejection), and its absence of effect is documented rather than implied.
 */
data class CoreWorkflowControlInput(
    /** Which declared effect this scope owns. */
    val fold: BodyOutcomeFold,
    /** What the scope does with a caught failure. */
    val buildResult: CatchErrorBuildResult,
    /** What the enclosing stage sees afterwards. */
    val stageResult: CatchErrorBuildResult,
    /** The author's message, or `null` when none was declared. */
    val message: String?,
    /** Whether the author asked the scope to contain an interruption of its body. */
    val catchInterruptions: Boolean,
)

/**
 * THE single wire authority for the control-scope **block** payload format that the
 * compiler writes and the runtime reads.
 *
 * ## Why it is an object and not a `when` in the compiler
 * The compiler used to hand-write this JSON while the runtime read it with a different
 * spelling, so the two could disagree about the format without either noticing. This
 * object is the format: the compiler encodes through it and the runtime decodes through
 * it, so there is one authority for the bytes.
 *
 * ## The discriminator carries the fold
 * `kind` is `catchError` for [BodyOutcomeFold.CONTAINMENT] and `warnError` for
 * [BodyOutcomeFold.OUTPUT_DECORATION]. The discriminator is therefore not decoration: it is
 * the fact that says which fold the caller believes it is asking for, and the runtime
 * checks it against the fold the descriptor declares.
 *
 * ## Decoding fails closed, in every direction
 * A payload that is not a JSON object, that carries an unknown `kind`, that omits a
 * required field, or that carries a result token outside
 * [CatchErrorBuildResult.supportedTokens] is a typed rejection
 * ([CoreWorkflowControlCodecException]). None of those defaults: the legacy projection
 * turned an unreadable token into `UNSTABLE`, which suppressed the very failure the scope
 * existed to catch.
 *
 * ## `message` is the only optional field
 * Absent means "the author declared no message", and it is encoded by OMITTING the key
 * rather than by writing `null`, so the decoded value and the encoded bytes agree about
 * a message that was never there.
 */
object CoreWorkflowControlWireCodec : StepCodec<CoreWorkflowControlInput> {

    /** Discriminator of a containment scope (`catchError`). */
    const val CATCH_ERROR_WIRE_KIND: String = "catchError"

    /** Discriminator of an output-decoration scope (`warnError`). */
    const val WARN_ERROR_WIRE_KIND: String = "warnError"

    /** The discriminator a fold travels under. */
    fun wireKindOf(fold: BodyOutcomeFold): String = when (fold) {
        BodyOutcomeFold.CONTAINMENT -> CATCH_ERROR_WIRE_KIND
        BodyOutcomeFold.OUTPUT_DECORATION -> WARN_ERROR_WIRE_KIND
    }

    /** The fold a discriminator names, or `null` when it names none. */
    fun foldOfWireKind(kind: String): BodyOutcomeFold? = when (kind) {
        CATCH_ERROR_WIRE_KIND -> BodyOutcomeFold.CONTAINMENT
        WARN_ERROR_WIRE_KIND -> BodyOutcomeFold.OUTPUT_DECORATION
        else -> null
    }

    override fun encode(value: CoreWorkflowControlInput): EncodedStepValue {
        val obj: JsonObject = buildJsonObject {
            put("kind", JsonPrimitive(wireKindOf(value.fold)))
            put("buildResult", JsonPrimitive(value.buildResult.wireToken))
            put("stageResult", JsonPrimitive(value.stageResult.wireToken))
            value.message?.let { put("message", JsonPrimitive(it)) }
            put("catchInterruptions", JsonPrimitive(value.catchInterruptions))
        }
        return EncodedStepValue(Json.encodeToString(JsonObject.serializer(), obj))
    }

    override fun decode(encoded: EncodedStepValue): CoreWorkflowControlInput {
        val obj = try {
            Json.parseToJsonElement(encoded.value).jsonObject
        } catch (e: Exception) {
            throw CoreWorkflowControlCodecException(
                "control scope payload is not a JSON object: ${e.message ?: "parse failed"}",
            )
        }
        val kind = obj["kind"]?.jsonPrimitive?.contentOrNull
            ?: throw CoreWorkflowControlCodecException("control scope payload requires a kind")
        val fold = foldOfWireKind(kind)
            ?: throw CoreWorkflowControlCodecException(
                "control scope payload kind '$kind' is outside the vocabulary " +
                    "$CATCH_ERROR_WIRE_KIND|$WARN_ERROR_WIRE_KIND",
            )
        return CoreWorkflowControlInput(
            fold = fold,
            buildResult = decodeResult(obj, "buildResult"),
            stageResult = decodeResult(obj, "stageResult"),
            message = obj["message"]?.jsonPrimitive?.contentOrNull,
            catchInterruptions = obj["catchInterruptions"]?.jsonPrimitive?.booleanOrNull
                ?: throw CoreWorkflowControlCodecException(
                    "control scope payload requires boolean catchInterruptions",
                ),
        )
    }

    /**
     * Reads one declared result. An absent or unreadable token is a rejection, never a
     * default: the legacy projection read an unrecognised token as `UNSTABLE` and thereby
     * suppressed the failure the scope had been installed to catch.
     */
    private fun decodeResult(obj: JsonObject, key: String): CatchErrorBuildResult {
        val token = obj[key]?.jsonPrimitive?.contentOrNull
            ?: throw CoreWorkflowControlCodecException("control scope payload requires $key")
        return CatchErrorBuildResult.parse(token)
            ?: throw CoreWorkflowControlCodecException(
                "control scope payload $key token '$token' is outside the vocabulary " +
                    "${CatchErrorBuildResult.supportedTokens}",
            )
    }
}

/** Typed decode failure of a control-scope payload; never an exception across the kernel boundary. */
class CoreWorkflowControlCodecException(message: String) : IllegalArgumentException(message)
