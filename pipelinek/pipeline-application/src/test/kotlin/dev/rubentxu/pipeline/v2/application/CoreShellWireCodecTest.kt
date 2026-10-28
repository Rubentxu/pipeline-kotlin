package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.ShellCommand
import dev.rubentxu.pipeline.v2.domain.ShellReturnMode
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.dsl.StageSpec
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The `core.sh` wire format has ONE authority, and the bytes it writes are FROZEN.
 *
 * ## The guarantee
 *
 * A `core.sh` payload has exactly one reading and exactly one spelling per
 * dialect, and the spelling is the one shipped code has always written:
 *
 * - the DECLARATIVE dialect (what the DSL compiler produces) is
 *   `{"kind":"sh","command":…,"isScriptBlock":…,"returnStdout":…}`;
 * - the SCRIPTED dialect (what the scripted runtime produces) is
 *   `{"kind":"shell","script":…,"encoding"?, "label"?, "returnMode":…}`;
 * - and EVERY shipped spelling of either one — including the aliases that
 *   existed because two producers used to disagree — reads back into ONE
 *   interior, the same [CoreShellInput].
 *
 * The compiler and the Step call the SAME authority, so the format cannot
 * grow a second producer.
 *
 * ## Why the bytes are frozen, and not a preference
 *
 * The payload is an input of the durable FINGERPRINT
 * (`params("payload")` on the canonical path, `params("encodedInput")` on the
 * scripted one). The operation id carries no payload at all, so the payload
 * bytes are the only thing that decides whether a resumed run recognises its
 * own step. A byte that moves does NOT re-execute the step: the journaled row
 * is FOUND, the fingerprint DIVERGES, and the run aborts with
 * REPLAY_COMPATIBILITY. There is no sanctioned migration for a fingerprint.
 *
 * So a payload that "looks cleaner" is a payload that breaks the resume of
 * every run already durable with the old one. The historical byte forms are
 * therefore part of the contract, and they are asserted as LITERALS here.
 *
 * ## The capacity this serves
 *
 * The durable identity of a `core.sh` step across a resume: what the run
 * wrote is what the run reads back, in the same spelling, with no second
 * producer able to say otherwise.
 *
 * ## Why this instrument
 *
 * Both the codec and the compiler are pure: they open nothing, read nothing
 * and write nothing, and they answer with bytes. The cheapest instrument that
 * can fail is to call them and read the bytes. No process, no filesystem, no
 * clock.
 *
 * ## What this instrument cannot see
 *
 * That the FINGERPRINT really consumes these bytes is a property of the
 * durable layer, observed there and not here. That a resumed run really
 * aborts with REPLAY_COMPATIBILITY is observed by the durable examples. What
 * this instrument DOES see is the thing that would break both: a payload
 * whose spelling stops being the one the authority writes.
 */
class CoreShellWireCodecTest {

    /**
     * The declarative dialect keeps the bytes the DSL compiler has always
     * written. A literal, not a round-trip: a round-trip would still pass if
     * BOTH writers drifted together.
     */
    @Test
    fun `the declarative dialect writes the bytes the compiler has always written`() {
        val interior = CoreShellInput(
            command = ShellCommand(script = "echo hi", returnMode = ShellReturnMode.NONE),
            isScriptBlock = false,
        )

        assertEquals(
            """{"kind":"sh","command":"echo hi","isScriptBlock":false,"returnStdout":false}""",
            CoreShellWireCodec.encodeDeclarative(interior).value,
        )
    }

    /**
     * The declarative dialect also carries the stdout flag and the script-block
     * flag, because the compiler's bytes carried them and a reader that dropped
     * them would re-encode a different step than the one that ran.
     */
    @Test
    fun `the declarative dialect carries the stdout and script-block flags`() {
        val interior = CoreShellInput(
            command = ShellCommand(script = "echo hi", returnMode = ShellReturnMode.STDOUT),
            isScriptBlock = true,
        )

        assertEquals(
            """{"kind":"sh","command":"echo hi","isScriptBlock":true,"returnStdout":true}""",
            CoreShellWireCodec.encodeDeclarative(interior).value,
        )
    }

    /**
     * The scripted dialect keeps the bytes its own codec has always written,
     * with the absent optionals OMITTED rather than written as null.
     */
    @Test
    fun `the scripted dialect writes the bytes the scripted runtime has always written`() {
        val bare = CoreShellInput(command = ShellCommand(script = "echo hi"))
        assertEquals(
            """{"kind":"shell","script":"echo hi","returnMode":"NONE"}""",
            CoreShellWireCodec.encode(bare).value,
        )

        val annotated = CoreShellInput(
            command = ShellCommand(
                script = "echo hi",
                encoding = "utf-8",
                label = "greeting",
                returnMode = ShellReturnMode.STATUS,
            ),
        )
        assertEquals(
            """{"kind":"shell","script":"echo hi","encoding":"utf-8","label":"greeting","returnMode":"STATUS"}""",
            CoreShellWireCodec.encode(annotated).value,
        )
    }

    /**
     * The compiler produces EXACTLY the bytes of the authority.
     *
     * The compiler and the codec used to be two producers of one format, and
     * they disagreed about the spelling. This row is the one that goes red
     * when they disagree again: the compiled payload, read back by the
     * authority and written back by it, must be the very same string.
     *
     * It also pins the compiled bytes as a LITERAL, so a writer that drifts
     * away from the authority cannot pass by drifting together with it.
     */
    @Test
    fun `the compiler produces exactly the bytes of the authority`() {
        val payload = compiledShellPayload(StepSpec.Shell(command = "echo hi"))

        assertEquals(
            """{"kind":"sh","command":"echo hi","isScriptBlock":false,"returnStdout":false}""",
            payload,
        )

        val interior = CoreShellWireCodec.decode(EncodedStepValue(payload))
        assertEquals(payload, CoreShellWireCodec.encodeDeclarative(interior).value)
    }

    /**
     * EVERY shipped spelling reads back into the SAME interior.
     *
     * The aliases exist because two producers disagreed; the point of ONE
     * reader is that the disagreement stops being observable. A payload that
     * arrives in any of the shipped spellings describes the same step.
     */
    @Test
    fun `every shipped spelling reads back into the same interior`() {
        val expected = CoreShellInput(
            command = ShellCommand(script = "echo hi", returnMode = ShellReturnMode.STDOUT),
            isScriptBlock = false,
        )

        val declarative = CoreShellWireCodec.decode(
            EncodedStepValue("""{"kind":"sh","command":"echo hi","isScriptBlock":false,"returnStdout":true}"""),
        )
        val scripted = CoreShellWireCodec.decode(
            EncodedStepValue("""{"kind":"shell","script":"echo hi","returnMode":"STDOUT"}"""),
        )
        val legacyBooleans = CoreShellWireCodec.decode(
            EncodedStepValue("""{"kind":"sh","script":"echo hi","returnStdout":true}"""),
        )

        assertEquals(expected, declarative)
        assertEquals(expected, scripted)
        assertEquals(expected, legacyBooleans)
    }

    /**
     * The compile-time flag survives the interior: a reader that dropped it
     * could not reproduce the bytes it read.
     */
    @Test
    fun `the script-block flag survives the interior`() {
        val interior = CoreShellWireCodec.decode(
            EncodedStepValue("""{"kind":"sh","command":"echo hi","isScriptBlock":true,"returnStdout":false}"""),
        )

        assertTrue(interior.isScriptBlock)
    }

    /**
     * A payload the authority cannot interpret is REFUSED, never guessed.
     *
     * The three refusals are the three ways a payload can lie: it is not JSON,
     * it is not a `core.sh` payload, or it declares a return mode that does
     * not exist. Guessing any of them would run a step under a contract the
     * payload does not state.
     */
    @Test
    fun `a payload the authority cannot interpret is refused`() {
        assertThrows(CoreShellCodecException::class.java) {
            CoreShellWireCodec.decode(EncodedStepValue("not json at all"))
        }
        assertThrows(CoreShellCodecException::class.java) {
            CoreShellWireCodec.decode(EncodedStepValue("""{"kind":"echo","text":"hi"}"""))
        }
        assertThrows(CoreShellCodecException::class.java) {
            CoreShellWireCodec.decode(EncodedStepValue("""{"kind":"shell","script":"echo hi","returnMode":"SOMETHING"}"""))
        }
    }

    /**
     * The declarative dialect has no spelling for STATUS, so it refuses one
     * instead of silently downgrading it to "no stdout".
     */
    @Test
    fun `the declarative dialect refuses a return mode it cannot spell`() {
        val interior = CoreShellInput(
            command = ShellCommand(script = "echo hi", returnMode = ShellReturnMode.STATUS),
        )

        assertThrows(CoreShellCodecException::class.java) {
            CoreShellWireCodec.encodeDeclarative(interior)
        }
    }

    /**
     * The compiled payload of a single `sh` step, read from the compiled
     * pipeline. Pure: the compiler opens nothing and the spec is data.
     *
     * The `Digest` is named in FULL because this file cannot import it: the
     * domain publishes TWO types named `Digest` — one in
     * `dev.rubentxu.pipeline.v2.domain` and one in
     * `dev.rubentxu.pipeline.v2.domain.step` — and a file that imports the
     * first can no longer resolve the `domain.step` package part, so the
     * `EncodedStepValue` import above would stop resolving. The full name
     * keeps the file independent of that collision AND of import order,
     * which a formatter is free to change.
     */
    private fun compiledShellPayload(step: StepSpec.Shell): String {
        val compiled = DslCompiledPipelineCompiler.compile(
            spec = PipelineSpec(stages = listOf(StageSpec(name = "s", steps = listOf(step)))),
            sourcePath = "pipeline.kts",
            sourceContent = "pipeline { }",
            pluginLockDigest = dev.rubentxu.pipeline.v2.domain.Digest("lock"),
        )
        val body = compiled.stages.single().body as StageBody.Steps
        return body.steps.single().payload.encoded
    }
}
