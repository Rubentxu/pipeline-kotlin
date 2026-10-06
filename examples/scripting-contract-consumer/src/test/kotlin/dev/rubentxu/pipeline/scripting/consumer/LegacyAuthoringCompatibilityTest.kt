package dev.rubentxu.pipeline.scripting.consumer

import dev.rubentxu.pipeline.v2.domain.CatchErrorBuildResult
import dev.rubentxu.pipeline.v2.domain.FailureKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * P3-E E6 — the STABLE authoring surface still works, from a consumer that declares one
 * coordinate.
 *
 * Read [LegacyAuthoring] for why this proof is kept separate from [ScriptingAuthoringTest].
 * The short version: the two are not two spellings of the same test, they are two DIFFERENT
 * promises, and the failure this file exists against is a suite that stays green because the
 * old spelling stopped being exercised.
 */
@DisplayName("P3-E E6 — legacy authoring: the STABLE surface still compiles and agrees with the typed one")
class LegacyAuthoringCompatibilityTest {

    // ---- SOURCE: the documented spellings still compile -----------------------------

    @Test
    fun `error with a String kind still compiles and yields the same typed state`() {
        val legacy = LegacyAuthoring.errorLegacy("boom", "USER")
        val typed = ScriptingAuthoring.explicitForm(FailureKind.USER)

        assertEquals(typed, legacy, "both spellings must build the same StepSpec.Error")
        assertEquals(
            FailureKind.USER,
            legacy.failureKind,
            "the token must become the domain value, not survive as text",
        )
    }

    @Test
    fun `error without a kind is untouched`() {
        assertEquals(
            FailureKind.USER,
            LegacyAuthoring.errorDefault("boom").failureKind,
            "the habitual form needs no vocabulary and must not change",
        )
    }

    @Test
    fun `catchError with String results still compiles and yields the same typed state`() {
        val legacy = LegacyAuthoring.catchErrorLegacy("FAILURE", "UNSTABLE")
        val typed = LegacyAuthoring.catchErrorTyped(
            CatchErrorBuildResult.Failure,
            CatchErrorBuildResult.Unstable,
        )

        assertEquals(
            typed,
            legacy,
            "the legacy spelling must produce EXACTLY the same specification as the typed one. " +
                "Two spellings that compile and disagree would be worse than either alone.",
        )
        assertEquals(CatchErrorBuildResult.Failure, legacy.buildResult)
        assertEquals(CatchErrorBuildResult.Unstable, legacy.stageResult)
    }

    @Test
    fun `a bare catchError stays an absence on both results`() {
        val spec = LegacyAuthoring.catchErrorBare()

        assertNull(spec.buildResult, "no declared buildResult must stay null, not become UNSTABLE")
        assertNull(spec.stageResult, "no declared stageResult must stay null")
    }

    // ---- and the legacy spelling REFUSES a misspelling, without a scripting host ----

    @Test
    fun `un token mal escrito en la grafia legacy se rechaza al construir el pipeline`() {
        // The negative that makes the legacy path worth anything.
        //
        // A consumer has no scripting host, so it cannot observe "no RunStarted" the way a UAT
        // can. It does not need to: `pipeline { }` builds the StepSpec SYNCHRONOUSLY, so the
        // refusal happens on the line below, before any pipeline object exists. There is no run
        // to have started yet — the refusal is upstream of everything.
        for (bad in listOf("USR", "FAILLURE", "success", "Stable", "", "USER ")) {
            assertThrows(IllegalArgumentException::class.java, { LegacyAuthoring.errorLegacy("boom", bad) }) {
                "error(\"boom\", \"$bad\") must be refused at construction. It compiled, which is " +
                    "the whole cost of the legacy surface: an invalid token reaches the builder, " +
                    "and the builder is the last place it can be caught."
            }
        }

        for (bad in listOf("FAILLURE", "FAILURE ", "unstable", "WAT")) {
            assertThrows(IllegalArgumentException::class.java, { LegacyAuthoring.catchErrorLegacy(bad, bad) }) {
                "catchError(buildResult = \"$bad\") must be refused at construction"
            }
        }
    }

    @Test
    fun `el rechazo nombra el token y el vocabulario, para que el autor pueda arreglarlo`() {
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            LegacyAuthoring.errorLegacy("boom", "USR")
        }
        val message = thrown.message!!

        assertTrue(
            message.contains("USR"),
            "the refusal must name the offending token, said: $message",
        )
        assertTrue(
            message.contains("USER") && message.contains("SCRIPT"),
            "the refusal must state the vocabulary so the author can fix it: $message",
        )
    }

    @Test
    fun `un rechazo no deja un pipeline a medias`() {
        // A partial construction would be the worst outcome: a half-built pipeline that a
        // later step somehow completes.
        assertThrows(IllegalArgumentException::class.java) {
            LegacyAuthoring.catchErrorLegacy("FAILURE", "FAILLURE")
        }
    }

    // ---- WIRE: and it carries the historical tokens ----------------------------------

    @Test
    fun `a legacy spelling writes the same durable token as a typed one`() {
        val fromLegacy = LegacyAuthoring.catchErrorLegacy("FAILURE", "UNSTABLE")
        val fromTyped = LegacyAuthoring.catchErrorTyped(
            CatchErrorBuildResult.Failure,
            CatchErrorBuildResult.Unstable,
        )

        assertEquals(
            LegacyAuthoring.catchWireToken(fromTyped.buildResult!!),
            LegacyAuthoring.catchWireToken(fromLegacy.buildResult!!),
        )
        assertEquals(
            "FAILURE",
            LegacyAuthoring.catchWireToken(fromLegacy.buildResult!!),
            "the token has always been UPPER CASE; a reader matching \"FAILURE\" cannot tell " +
                "this record from one written before the migration",
        )
    }

    @Test
    fun `every catchError token a legacy author may write survives as the historical spelling`() {
        // The three tokens the compatibility corpus actually writes, plus the vocabulary they
        // must map to. A token outside this set is refused at construction, not defaulted.
        val historical = mapOf(
            "SUCCESS" to CatchErrorBuildResult.Success,
            "UNSTABLE" to CatchErrorBuildResult.Unstable,
            "FAILURE" to CatchErrorBuildResult.Failure,
        )

        for ((token, expected) in historical) {
            val spec = LegacyAuthoring.catchErrorLegacy(token, token)
            assertEquals(expected, spec.buildResult, "'$token' must map to $expected")
            assertEquals(
                token,
                LegacyAuthoring.catchWireToken(expected),
                "'$token' must travel as '$token'",
            )
        }
    }
}