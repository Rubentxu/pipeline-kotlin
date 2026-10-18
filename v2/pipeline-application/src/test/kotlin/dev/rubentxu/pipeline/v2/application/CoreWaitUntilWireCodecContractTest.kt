package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.BlockStepNode
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.dsl.StageSpec
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Contract of [CoreWaitUntilWireCodec] — the single wire authority for the
 * `core.waitUntil` block carrier (DEBT-WIRE-AUTHORITY).
 *
 * ## Productive authority crossed
 *
 * `DslCompiledPipelineCompiler.compile` — the production entry point that
 * lowers a `PipelineSpec` to canonical IR. Nothing is reimplemented here: the
 * fixture is a `PipelineSpec` and the payload is read out of the compiled
 * `BlockStepNode`, so this is a characterisation of the product, not of the
 * codec in isolation.
 *
 * ## What is guaranteed, and why it matters
 *
 * The payload STRING is a durable input. `StepDispatchEngine` puts
 * `child.payload.encoded` into `OperationInput.params`, which `OperationInput`
 * documents as fingerprint input, so a changed payload moves the operation
 * identity; the replay table's first rule is `!hasJournalEntry -> RERUN`, so a
 * resume of an older waitUntil block MISSES its journal row and re-executes
 * rather than silently reusing it. These rows therefore pin the bytes exactly,
 * not a decoded projection of them.
 *
 * These rows previously attributed that property to `BodyStructureDigest`, which
 * is wrong: its payload-appending call is guarded by `hasBodyContinuation`, bound
 * only for the `HANDLER_CONTINUATION` owner, which only `core.lock` and
 * `core.input` declare. `core.waitUntil` is an engine-driven block and never
 * reaches that digest. The conclusion held; the mechanism was invented.
 *
 * The format DID change after the codec was named: `maxBackoffMs` was added so
 * the block payload can carry a per-block ceiling that `WaitUntilShape` cannot
 * express. That is a deliberate change with a compatibility consequence (above),
 * recorded in the ROADMAP correction of 2026-10-18 — not a side effect of naming
 * the codec, and the frozen bytes below are the new ones.
 *
 * Sibling of `Lfc2WaitUntilDslCanonicalProjectionTest`, which asserts the
 * structural shape of the same node. This file asserts the wire.
 */
@DisplayName("CoreWaitUntilWireCodec: the waitUntil block wire has one owner and its bytes are frozen")
class CoreWaitUntilWireCodecContractTest {

    private fun compiledBlockPayload(
        initialRecurrencePeriod: Long = 7L,
    ): String {
        val spec = PipelineSpec(
            stages = listOf(
                StageSpec(
                    name = "wait-until-wire",
                    steps = listOf(
                        StepSpec.WaitUntilBlock(
                            initialRecurrencePeriod = initialRecurrencePeriod,
                            body = listOf(StepSpec.Shell(command = "test -f /tmp/marker")),
                        ),
                    ),
                ),
            ),
        )
        val compiled = DslCompiledPipelineCompiler.compile(
            spec = spec,
            sourcePath = "wait-until-wire.pipeline.kts",
            sourceContent = """pipeline { stages { stage("wait-until-wire") { waitUntil { sh("true") } } } }""",
            pluginLockDigest = Digest("wire-contract-lock"),
        )
        val body = compiled.stages.single().body
        check(body is StageBody.Steps) { "expected a Steps body, got ${body::class.java.simpleName}" }
        val node = body.steps.single()
        check(node is BlockStepNode) {
            "waitUntil must lower to BlockStepNode, got ${node::class.java.simpleName}"
        }
        return node.payload.encoded
    }

    @Test
    fun `compiled block payload is byte-identical to the frozen wire`() {
        // Whole-string comparison on purpose. A parsed-and-rebuilt expectation would
        // accept a reordered or renamed key, which is exactly the durable-identity
        // move this row exists to prevent.
        assertEquals(
            """{"kind":"waitUntilBlock","initialRecurrencePeriod":7,"maxBackoffMs":60000}""",
            compiledBlockPayload(),
        )
    }

    @Test
    fun `compiled block payload carries every declared field verbatim`() {
        assertEquals(
            """{"kind":"waitUntilBlock","initialRecurrencePeriod":250,"maxBackoffMs":60000}""",
            compiledBlockPayload(initialRecurrencePeriod = 250L),
        )
    }

    @Test
    fun `the compiler delegates to the wire authority rather than agreeing with it by luck`() {
        // Without this row the previous one would still pass if the codec were dead and
        // the compiler had kept its own JSON: two independent hand-writers can produce
        // the same string today and drift apart tomorrow. Equality to the codec proves
        // there is one producer.
        assertEquals(
            CoreWaitUntilWireCodec.encode(
                CoreWaitUntilInput(initialRecurrencePeriod = 7L),
            ).value,
            compiledBlockPayload(),
        )
    }

    @Test
    fun `the wire round-trips through its own decoder`() {
        val encoded = EncodedStepValue(compiledBlockPayload(initialRecurrencePeriod = 33L))
        assertEquals(
            CoreWaitUntilInput(initialRecurrencePeriod = 33L),
            CoreWaitUntilWireCodec.decode(encoded),
        )
    }

    @Test
    fun `a registry-carrier payload is refused by name, not decoded by guesswork`() {
        // `kind="waitUntil"` is the OTHER carrier (CoreWaitUntilStep's encodedInput).
        // Two formats share two field names, so a tolerant decoder would silently
        // accept the wrong one; the refusal names which carrier arrived.
        val registryShaped = EncodedStepValue(
            """{"kind":"waitUntil","initialRecurrencePeriod":10}""",
        )
        val failure = assertThrows(CoreWaitUntilCodecException::class.java) {
            CoreWaitUntilWireCodec.decode(registryShaped)
        }
        assertTrue(
            failure.message?.contains("waitUntilBlock") == true,
            "the refusal must name the expected carrier, got: ${failure.message}",
        )
    }

    @Test
    fun `a non-object payload is refused with a typed failure`() {
        assertThrows(CoreWaitUntilCodecException::class.java) {
            CoreWaitUntilWireCodec.decode(EncodedStepValue("[]"))
        }
    }
}
