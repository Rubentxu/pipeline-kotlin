package dev.rubentxu.pipeline.v2.domain

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * P3-E E4 — the CLOSED set of results a `catchError` scope may declare.
 *
 * ## Why this type exists at all
 *
 * The decision over this value used to be a `when` on a raw `String` whose final arm was
 *
 * ```kotlin
 * "FAILURE" -> re-throw outward
 * "SUCCESS" -> suppress, continue clean
 * else      -> suppress, continue UNSTABLE
 * ```
 *
 * `UNSTABLE` is the correct reading of exactly one token, `"UNSTABLE"`. Written as `else`
 * it became the reading of *everything else* — `"FALURE"`, `"success"`, `""`, `"WAT"` — and
 * every one of those SUPPRESSES the failure the scope was installed to catch. A pipeline
 * that misspelled its own error handling did not fail loudly; it went unstable and carried
 * on, which is the worst available outcome because nothing is reported.
 *
 * That is a fail-open on the run's control flow, and it was only ever closed by hand: three
 * string literals matched in one place, and the arm meant for the third one swallowed the
 * rest of the world.
 *
 * ## Why `parse` returns null instead of defaulting
 *
 * The whole defect was a default. A parser that turns an unrecognised token into `Unstable`
 * would reproduce it exactly. So [parse] is total over the vocabulary and **fails closed by
 * returning null**, and the caller is required to turn that null into an abort. The wire
 * spellings stay in upper case, exactly as they have always travelled.
 *
 * ## Why the wire token is declared and not derived — P3-E D3
 *
 * [wireToken] exists because `.name` would be wrong. The cases are `Success`/`Unstable`/
 * `Failure` and the wire has always carried `SUCCESS`/`UNSTABLE`/`FAILURE`, in upper case.
 * A `name` projection would silently rewrite durable history the first time this type
 * reached the codec, and the rewrite would be invisible in every test that did not compare
 * against a real record.
 *
 * D3 also had to settle the scope: `stageResult` does **not** have a smaller vocabulary than
 * `buildResult`. The producer is
 *
 * ```kotlin
 * val effectiveStageResult = stageResult?.uppercase() ?: effectiveBuildResult
 * ```
 *
 * so a scope that declares no `stageResult` inherits whatever `buildResult` said, `SUCCESS`
 * included. A two-value enum for `stageResult` would have made `SUCCESS` inexpressible —
 * a real break dressed up as a cleanup. One authority for the whole catchError result
 * family is the smaller and more honest model.
 *
 * @see CatchErrorBuildResult.parse
 * @see CatchErrorBuildResult.Serializer
 */
sealed interface CatchErrorBuildResult {

    /** The exact spelling this case has always carried on the wire. */
    val wireToken: String

    /** The caught failure is suppressed and the run continues clean. */
    data object Success : CatchErrorBuildResult {
        override val wireToken: String get() = "SUCCESS"
    }

    /** The caught failure is suppressed, but the run is marked unstable. */
    data object Unstable : CatchErrorBuildResult {
        override val wireToken: String get() = "UNSTABLE"
    }

    /** The failure is NOT caught here; it propagates to the enclosing scope. */
    data object Failure : CatchErrorBuildResult {
        override val wireToken: String get() = "FAILURE"
    }

    /**
     * Reads and writes this type as a **bare JSON string**, not as an object.
     *
     * `ContextOverlay.CatchErrorOverlay` is `@Serializable` and has carried these values as
     * `"UNSTABLE"` since before the type existed. kotlinx's default handling for a sealed
     * hierarchy would emit `{"type":"UNSTABLE"}` — a different document — so persisting the
     * typed value without this serializer would quietly change the compiled-pipeline format
     * that D3 is required to leave byte-identical.
     *
     * An unrecognised token fails closed here too: [parse] returns null and this turns that
     * into a [SerializationException], which is the decode-side equivalent of refusing to
     * invent an `UNSTABLE`.
     */
    object Serializer : KSerializer<CatchErrorBuildResult> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("CatchErrorBuildResult", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: CatchErrorBuildResult) {
            encoder.encodeString(value.wireToken)
        }

        override fun deserialize(decoder: Decoder): CatchErrorBuildResult {
            val token = decoder.decodeString()
            return parse(token) ?: throw SerializationException(
                "CatchErrorBuildResult token '$token' is outside the vocabulary " +
                    "${CatchErrorBuildResult.supportedTokens}; refusing to invent a result",
            )
        }
    }

    companion object {

        /**
         * The historical wire spellings, in the case they have always used.
         *
         * `buildResult` in `CatchErrorTriggered` travels upper case while `RunFinished.outcome`
         * travels lower case. That asymmetry is history, not a mistake to normalise away.
         */
        private val SUPPORTED: Map<String, CatchErrorBuildResult> = mapOf(
            Success.wireToken to Success,
            Unstable.wireToken to Unstable,
            Failure.wireToken to Failure,
        )

        /** Every spelling this runtime accepts, for diagnostics and validation messages. */
        val supportedTokens: Set<String> = SUPPORTED.keys

        /**
         * The single boundary where a declared result becomes typed.
         *
         * Returns **null** for anything outside [supportedTokens]. The caller MUST treat that
         * as a configuration error and fail closed — never as [Unstable].
         */
        fun parse(token: String): CatchErrorBuildResult? = SUPPORTED[token]
    }
}
