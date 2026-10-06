package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.dsl.pipeline
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * P3-E E6 — the wire does not move when the type does.
 *
 * ## What this replaces, and why it is not the same test
 *
 * `ErrorStepDefaultFailureKindTest` used to carry this assertion:
 *
 * ```
 * val declared = (scope.steps().single() as StepSpec.Error).failureKind   // a String
 * assertTrue(FailureKind.entries.any { it.name == declared })
 * ```
 *
 * That guarded a real defect: `failureKind` was a `String`, so a token outside the vocabulary
 * could reach `StepSpec.Error` and was only caught by the decoder at Step admission — mid-run,
 * about a decision the author believed was already accepted. E6 typed the field as
 * [FailureKind], so the compiler now guarantees what that test could only observe, and the
 * assertion became a question with no failure mode.
 *
 * The risk did not disappear; it moved. Vocabulary membership can no longer be violated at the
 * authoring surface. What CAN still go wrong — silently, without a compile error and without a
 * test turning red — is the IR projection: `DslCompiledPipelineCompiler` writes
 * `failureKind.name` into the `dsl-v1` payload, and if that ever stops being `name`, or stops
 * being emitted, every durable record written since the change decodes differently or not at
 * all. S8 freezes compatibility against this wire.
 *
 * So the guarantee is re-anchored where the risk actually lives.
 *
 * ## The four compatibility axes, kept apart
 *
 * This test asserts two of them and refuses to claim the other two:
 *
 *  - SEMANTIC: `FailureKind.USER` is a closed vocabulary, exhaustively matched by `CoreErrorStep`.
 *    A wrong choice fails at compile time.
 *  - WIRE / HISTORY: the payload token is `FailureKind.name`, byte-identical to the String the
 *    pre-E6 encoder wrote, for every case.
 *  - SOURCE: `error("msg")` still needs no import and no ceremony; only the explicit non-default
 *    case names `FailureKind.X`.
 *  - BINARY: `StepSpec.Error.getFailureKind()` changed its JVM return type from `String` to
 *    `FailureKind`. That is a real break and it is recorded, not tested away — it is in
 *    `published-contract-exceptions.json`, because a test cannot make a consumer's
 *    `NoSuchMethodError` go away. Asserting it here would be claiming a compatibility this
 *    change does not have.
 */
@DisplayName("P3-E E6 — failureKind is typed on the authoring surface and unchanged on the wire")
class ErrorFailureKindWireCompatibilityTest {

    private fun encodedPayloadFor(kind: FailureKind): String {
        val compiled = DslCompiledPipelineCompiler.compile(
            spec = pipeline {
                stages {
                    stage("Build") {
                        error("boom", kind)
                    }
                }
            },
            sourcePath = "error.pipeline.kts",
            sourceContent = "error pipeline",
            pluginLockDigest = Digest("lock-v1"),
        )
        val body = compiled.stages.single().body as StageBody.Steps
        return (body.steps.single() as OpaqueStepNode).payload.encoded
    }

    /**
     * Every case, not a sample. The projection is `FailureKind.name`, so this is total by
     * construction today; the test exists so that a case added later without a decision about
     * its wire token is a RED rather than an unexamined byte in the durable stream.
     */
    @Test
    fun `todo FailureKind proyecta su nombre historico y nada mas`() {
        for (kind in FailureKind.entries) {
            val encoded = encodedPayloadFor(kind)

            assertTrue(
                encoded.contains("\"failureKind\":\"${kind.name}\""),
                "el token durable de ${kind.name} debe seguir siendo \"${kind.name}\". El " +
                    "proyector escribe FailureKind.name, y ese nombre ES el token historico; si " +
                    "esto falla, o el payload dejo de emitir el campo o el token cambio, y las dos " +
                    "cosas rompen la lectura de todo registro durable escrito desde este SHA. " +
                    "Payload: $encoded",
            )
        }
    }

    /**
     * The two tokens the shipped corpus actually exercises, pinned as literal bytes.
     *
     * `v2/compatibility/15-error.pipeline.kts` uses USER and three application fixtures use
     * SCRIPT. Those are the four executable scenarios a compatibility corpus re-runs, so a
     * silent change to either token would be invisible to every other test in the repository.
     */
    @Test
    fun `los tokens del corpus de compatibilidad son los de siempre`() {
        assertTrue(
            encodedPayloadFor(FailureKind.USER).contains("\"failureKind\":\"USER\""),
            "USER es el token del fixture 15-error.pipeline.kts del corpus de compatibilidad.",
        )
        assertTrue(
            encodedPayloadFor(FailureKind.SCRIPT).contains("\"failureKind\":\"SCRIPT\""),
            "SCRIPT es el token de error-abort, grammar-full y timeout-retry.",
        )
    }

    /** Round-trip through the production authority: IR payload -> `core.error`'s decoder. */
    @Test
    fun `el token proyectado vuelve a ser el mismo FailureKind al decodificar`() {
        for (kind in FailureKind.entries) {
            val encoded = encodedPayloadFor(kind)
            val decoded = CoreErrorStep.definition.contract.inputCodec.decode(EncodedStepValue(encoded))

            assertEquals(
                kind,
                decoded.failureKind,
                "el token que sale del IR tiene que volver a ser el mismo caso tipado. Si no, " +
                    "el encoder y el decoder no comparten autoridad y el fallo aparece en " +
                    "lectura, no en escritura.",
            )
            assertEquals("boom", decoded.message)
        }
    }

    /**
     * Fail-closed survives the migration, and this is the assertion that would catch a decoder
     * that quietly defaulted an unrecognised token back to `UNKNOWN`.
     */
    @Test
    fun `un token fuera del vocabulario falla cerrado y no se vuelve UNKNOWN`() {
        val encoded = encodedPayloadFor(FailureKind.USER).replace("USER", "USR")

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            CoreErrorStep.definition.contract.inputCodec.decode(EncodedStepValue(encoded))
        }
    }
}
