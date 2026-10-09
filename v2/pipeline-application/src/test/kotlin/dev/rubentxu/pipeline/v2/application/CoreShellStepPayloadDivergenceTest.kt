package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.ShellCommand
import dev.rubentxu.pipeline.v2.domain.ShellReturnMode
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * ASX-000 — `core.sh` payload characterization, and the divergence it makes visible.
 *
 * ## The production authorities this crosses
 *
 * [CoreShellStep]'s real `StepContract`, reached through `CoreShellStep.definition.contract` — the
 * same codec the runtime admits and the scripted path encodes through
 * (`ScriptedRegistryInvoker` calls `definition.contract.inputCodec.encode(input)`).
 *
 * ## What it pins, and the defect it pins rather than hides
 *
 * `core.sh` has **two producers of the payload that decides its durable identity**, and they do not
 * agree:
 *
 * ```text
 * authored  sh("echo hi")     DslCompiledPipelineCompiler.shellPayload
 *                             {"kind":"sh","command":"echo hi","isScriptBlock":false,"returnStdout":false}
 *
 * codec      inputCodec.encode(CoreShellInput(ShellCommand("echo hi")))
 *                             {"kind":"shell","script":"echo hi","returnMode":"NONE"}
 * ```
 *
 * The payload is fingerprint material, so the same logical `sh` step has two identities depending
 * on which producer built the node. The codec's `decode` accepts BOTH spellings, and its own
 * comments say why ("A4 flip: accept BOTH canonical kind spellings ...") — the tolerance is what
 * lets real `.pipeline.kts` files route through the registry path. The tolerance is not the defect.
 * The defect is that no single authority owns the bytes.
 *
 * This is not a stylistic inconsistency, and the repository's own law says so: for `cleanWs`,
 * `milestone`, `stash`, `unstash` and `publishHtml`, `StageScope` documents that the DSL emits an
 * envelope "**byte-for-byte identical to `CoreXStep.inputCodec.encode()`** so the durable
 * fingerprint round-trips through the registry path". `core.sh` is the one Step that does not
 * satisfy that law.
 *
 * ## Why this class asserts the divergence instead of a single shape
 *
 * Fixing it means changing the payload of one of the two paths, and therefore changing the durable
 * identity of history that already exists. That is exactly what DR-10 forbids doing silently
 * ("existing history MUST NOT be silently reinterpreted") and what DR-12 requires an ADR for. So
 * ASX-000's correct output is a recorded measurement, not a quiet repair: these rows freeze the two
 * shapes and the divergence between them, and [theDivergence] is the row that fails the day somebody
 * unifies them, forcing the transition to be explicit.
 *
 * ## Characterization, stated as such
 *
 * These rows describe what the code does today, including its defect. When the single authority
 * lands, [theDivergence] and the per-shape rows must be rewritten together, in the same commit, and
 * the new expectation must cite the ADR that authorised the identity change — never swapped
 * silently to whatever the code then produces. The mutation that kills these rows today is any edit
 * to `CoreShellStep`'s `inputCodec` shape: renaming `"shell"` to `"sh"` turns [codecOwnShape] red
 * and leaves the decode-tolerance rows green.
 */
@Timeout(30)
class CoreShellStepPayloadDivergenceTest {

    private val codec = CoreShellStep.definition.contract.inputCodec

    private fun encoded(json: String) = EncodedStepValue(json)

    private fun input(script: String, returnMode: ShellReturnMode = ShellReturnMode.NONE) =
        CoreShellInput(command = ShellCommand(script = script, returnMode = returnMode))

    // ------------------------------------------------- the codec's OWN shape (production, scripted path)

    @Test
    @DisplayName("codec encode: its own shape, with optional encoding/label omitted when absent")
    fun codecOwnShape() {
        assertEquals(
            """{"kind":"shell","script":"echo hi","returnMode":"NONE"}""",
            codec.encode(input("echo hi")).value,
        )
    }

    @Test
    @DisplayName("codec encode: encoding and label appear before returnMode when present")
    fun codecOwnShapeWithOptionals() {
        assertEquals(
            """{"kind":"shell","script":"echo hi","encoding":"UTF-8","label":"build","returnMode":"STDOUT"}""",
            codec.encode(
                CoreShellInput(
                    command = ShellCommand(
                        script = "echo hi",
                        encoding = "UTF-8",
                        label = "build",
                        returnMode = ShellReturnMode.STDOUT,
                    ),
                ),
            ).value,
        )
    }

    @Test
    @DisplayName("codec: its own shape round-trips through its own decode")
    fun codecOwnShapeRoundTrips() {
        val original = input("echo hi", ShellReturnMode.STATUS)
        assertEquals(original, codec.decode(codec.encode(original)))
    }

    // ------------------------------------------------- the DSL compiler's shape (authored path)

    @Test
    @DisplayName("the authored path's shape decodes to the SAME logical input as the codec's shape")
    fun authoredShapeDecodesToTheSameInput() {
        val authored = """{"kind":"sh","command":"echo hi","isScriptBlock":false,"returnStdout":false}"""
        assertEquals(
            codec.decode(encoded(authored)),
            codec.decode(codec.encode(input("echo hi"))),
        )
    }

    @Test
    @DisplayName("authored returnStdout=true maps to the codec's STDOUT mode")
    fun authoredReturnStdoutMapsToStdoutMode() {
        val authored = """{"kind":"sh","command":"echo hi","isScriptBlock":false,"returnStdout":true}"""
        assertEquals(ShellReturnMode.STDOUT, codec.decode(encoded(authored)).command.returnMode)
    }

    @Test
    @DisplayName("authored shape enabling BOTH returnStdout and returnStatus is refused (fail closed)")
    fun authoredShapeWithBothReturnFlagsIsRefused() {
        val both = """{"kind":"sh","command":"echo hi","isScriptBlock":false,"returnStdout":true,"returnStatus":true}"""
        val failure = assertThrows(IllegalArgumentException::class.java) { codec.decode(encoded(both)) }
        assertTrue(failure.message!!.contains("returnStdout") && failure.message!!.contains("returnStatus")) {
            "the refusal must name both flags: ${failure.message}"
        }
    }

    // ------------------------------------------------- the divergence, frozen on purpose

    @Test
    @DisplayName("THE DIVERGENCE: two producers, two byte strings, ONE logical operation")
    fun theDivergence() {
        val fromCodec = codec.encode(input("echo hi")).value
        val fromAuthoredPath =
            """{"kind":"sh","command":"echo hi","isScriptBlock":false,"returnStdout":false}"""

        // Same operation to the user ...
        assertEquals(codec.decode(encoded(fromAuthoredPath)), codec.decode(encoded(fromCodec)))

        // ... and two different durable identities, because the payload is fingerprint material.
        assertNotEquals(
            fromAuthoredPath,
            fromCodec,
            "core.sh is the only Step whose DSL envelope is NOT byte-identical to its " +
                "inputCodec.encode(); the payload decides the fingerprint, so the same sh step has " +
                "two identities depending on the producer. This row is a RECORDED DEFECT, not a " +
                "contract: unifying the two shapes changes the durable identity of existing " +
                "history and needs an ADR (DR-10 / DR-12), so this row is the guard that forces " +
                "that transition to be explicit instead of silent.",
        )
    }

    // ------------------------------------------------- fail closed on everything else

    @Test
    @DisplayName("a kind that is neither 'shell' nor 'sh' is refused, naming the requirement")
    fun unknownKindIsRefused() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            codec.decode(encoded("""{"kind":"shh","command":"echo hi"}"""))
        }
        assertTrue(failure.message!!.contains("shell") || failure.message!!.contains("sh")) {
            "the refusal must state the accepted spellings: ${failure.message}"
        }
    }

    @Test
    @DisplayName("a payload with neither command nor script is refused rather than defaulted empty")
    fun missingScriptIsRefused() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            codec.decode(encoded("""{"kind":"sh","isScriptBlock":false}"""))
        }
        assertTrue(failure.message!!.contains("command") || failure.message!!.contains("script")) {
            "the refusal must name both accepted keys: ${failure.message}"
        }
    }
}
