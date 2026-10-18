package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.step.DEFAULT_WAIT_UNTIL_INITIAL_RECURRENCE_PERIOD_MS
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * THE single wire authority for the `core.waitUntil` **block** payload
 * (DEBT-WIRE-AUTHORITY, `bl-bl-01M3YGH3FE000387X13PG607G0`).
 *
 * Before this object existed, `DslCompiledPipelineCompiler.blockPayload`
 * hand-wrote this format in JSON while the Step's own codec wrote the
 * registry one — the same shape of divergence `core.sh` suffered
 * (`kind=sh|shell`, `command|script`, `returnStdout|returnMode`), where every
 * alias is a compatibility shim added after the fact.
 * [Lfc2WaitUntilWireAuthorityFitnessTest] freezes the law, the third Step after
 * `core.lock` (WU-091) and `core.input` (WU-092).
 *
 * ## The bytes are unchanged, and that is the contract
 *
 * [encode] reproduces the compiler's previous output character for character,
 * including the `kind` discriminator and the key order. Two measured reasons:
 *
 * 1. `Lfc2WaitUntilDslCanonicalProjectionTest` asserts, across the productive
 *    authority, that the compiled block payload carries `kind="waitUntilBlock"`.
 * 2. The compiled payload is the one string hashed into the operation identity
 *    (`StepDispatchEngine` puts `payload.encoded` into `OperationInput.params`,
 *    which `OperationInput` documents as fingerprint input), and the replay
 *    table's first rule is `!hasJournalEntry -> RERUN`. So a changed payload does
 *    not silently reuse old history: the operation runs again.
 *
 * This text previously cited `BodyStructureDigest` for reason 2. That was wrong.
 * `BodyStructureDigest.of()` appends the payload string, but its only call-site is
 * guarded by `hasBodyContinuation`, bound solely for the `HANDLER_CONTINUATION`
 * owner, which only `core.lock` and `core.input` declare. `core.waitUntil` is an
 * engine-driven block, so its payload never reaches that digest. The conclusion
 * (bytes unchanged, extraction is about authorship) survived; the mechanism did not.
 *
 * `maxBackoffMs` is therefore a deliberate format CHANGE made in a later slice,
 * and it is safe for the reason above: the durable resume misses its old key and
 * re-executes, which is the fail-closed direction DR-10 asks for. Removing the
 * discriminator later is a separate decision that needs a compatibility review.
 *
 * ## Not the same format as the registry one
 *
 * `CoreWaitUntilStep`'s codec owns `encodedInput`, discriminator
 * `kind="waitUntil"`, decoded before the handler runs. This one owns the block
 * IR payload, discriminator `kind="waitUntilBlock"`. Two carriers, two formats,
 * two types ([CoreWaitUntilInput] vs `WaitUntilInput`), and the decoder below
 * refuses the other one by name rather than guessing.
 */
object CoreWaitUntilWireCodec : StepCodec<CoreWaitUntilInput> {

    /** Discriminator of the block carrier. Distinct from the registry one by design. */
    const val BLOCK_WIRE_KIND: String = "waitUntilBlock"

    override fun encode(value: CoreWaitUntilInput): EncodedStepValue {
        val obj: JsonObject = buildJsonObject {
            put("kind", JsonPrimitive(BLOCK_WIRE_KIND))
            put("initialRecurrencePeriod", JsonPrimitive(value.initialRecurrencePeriod))
            put("quiet", JsonPrimitive(value.quiet))
            put("maxBackoffMs", JsonPrimitive(value.maxBackoffMs))
        }
        return EncodedStepValue(Json.encodeToString(JsonObject.serializer(), obj))
    }

    override fun decode(encoded: EncodedStepValue): CoreWaitUntilInput {
        val obj = try {
            Json.parseToJsonElement(encoded.value).jsonObject
        } catch (e: Exception) {
            throw CoreWaitUntilCodecException(
                "core.waitUntil block payload is not a JSON object: ${e.message ?: "parse failed"}",
            )
        }
        val kind = obj["kind"]?.jsonPrimitive?.contentOrNull
        if (kind != BLOCK_WIRE_KIND) {
            // Named, not "absent": a registry payload arriving here is a real,
            // diagnosable mistake and collapsing it into a missing-field error
            // would send the next reader to the wrong carrier.
            throw CoreWaitUntilCodecException(
                "core.waitUntil block payload kind must be '$BLOCK_WIRE_KIND' but was " +
                    "${kind ?: "absent"}; that is not this carrier",
            )
        }
        return CoreWaitUntilInput(
            initialRecurrencePeriod = obj["initialRecurrencePeriod"]?.jsonPrimitive?.longOrNull
                ?: DEFAULT_WAIT_UNTIL_INITIAL_RECURRENCE_PERIOD_MS,
            quiet = obj["quiet"]?.jsonPrimitive?.booleanOrNull ?: false,
            maxBackoffMs = obj["maxBackoffMs"]?.jsonPrimitive?.longOrNull
                ?: DEFAULT_WAIT_UNTIL_MAX_BACKOFF_MS,
        )
    }
}

/** Typed decode failure of a `core.waitUntil` block payload; never an exception across the kernel boundary. */
class CoreWaitUntilCodecException(message: String) : IllegalArgumentException(message)
