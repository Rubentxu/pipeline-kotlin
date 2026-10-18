package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * DEBT-WIRE-AUTHORITY (`bl-bl-01M3YGH3FE000387X13PG607G0`) — `core.waitUntil`
 * block wire-authority guard. Sibling of [Lfc2LockWireAuthorityFitnessTest]
 * (WU-091) and the `core.input` guard (WU-092); same law, third Step.
 *
 * ## Law under test
 *
 * ```
 * DslCompiledPipelineCompiler
 *     may know:    StepSpec.WaitUntilBlock, CoreWaitUntilInput, CoreWaitUntilWireCodec
 *     must NOT know: the waitUntil BLOCK wire vocabulary
 *                    ("kind":"waitUntilBlock", "initialRecurrencePeriod", "quiet")
 *                    or any hand-written waitUntil block wire JSON
 * ```
 *
 * ## Why the Step codec already existing is not enough
 *
 * `CoreWaitUntilStep` declares an (anonymous) `StepCodec<WaitUntilInput>`.
 * That codec owns the **registry invocation** carrier — `encodedInput`, whose
 * discriminator is `kind="waitUntil"`. The `waitUntil { }` DSL construct is a
 * **block**: it lowers to `BlockStepNode`, and its body-carrying IR is a
 * **different carrier** with a different discriminator,
 * `kind="waitUntilBlock"`, written by `blockPayload`. So the Step codec never
 * governed the block wire; the compiler became its second, hand-written
 * writer. Naming the block format is the whole point of this slice.
 *
 * ## Which bytes are durable input, and which are not
 *
 * `Lfc2WaitUntilDslCanonicalProjectionTest` asserts, across the productive
 * authority (`DslCompiledPipelineCompiler.compile`), that the compiled block
 * payload carries `kind="waitUntilBlock"`.
 *
 * This file previously argued that the whole payload string is durable input
 * because `BodyStructureDigest` appends `child.payload.encoded`. That argument
 * was wrong: the digest's payload-appending call is guarded by
 * `hasBodyContinuation`, bound only for the `HANDLER_CONTINUATION` owner, which
 * only `core.lock` and `core.input` declare. `core.waitUntil` is an
 * engine-driven block and never reaches it. Naming the format in a file that
 * only names it cannot create the obligation, so the ownership is uncontested
 * either way — but the stated reason would have been a fiction, and a fiction
 * in a guard is a guard that cannot be trusted.
 *
 * The payload string IS durable input through a different route:
 * `StepDispatchEngine` puts it in `OperationInput.params`, which
 * `OperationInput` documents as fingerprint input, and the ADR-0103 D1 table's
 * first rule is `!hasJournalEntry -> RERUN`.
 *
 * This fitness asserts *authorship*, never a literal value. Byte identity is
 * carried by the behavioural contract test (`CoreWaitUntilWireCodecContractTest`,
 * pipeline-application), which compiles a real block through the productive
 * path — and whose frozen bytes moved once, deliberately, when `maxBackoffMs`
 * was added to the format (ROADMAP correction, 2026-10-18).
 *
 * Family: source-scan architecture fitness. The scan is comment-blind, like the
 * rest of the Lfc2 family: a forbidden literal inside a comment does not trip
 * it, and a permitted symbol named only in a comment does not satisfy the
 * allowed-knowledge assertion.
 */
class Lfc2WaitUntilWireAuthorityFitnessTest {

    private val compilerSource = ScannerSupport.v2Root().resolve(
        "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/DslCompiledPipelineCompiler.kt",
    )
    private val wireCodecSource = ScannerSupport.v2Root().resolve(
        "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreWaitUntilWireCodec.kt",
    )
    private val dispatchEngineSource = ScannerSupport.v2Root().resolve(
        "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/StepDispatchEngine.kt",
    )
    private val bodyEngineSource = ScannerSupport.v2Root().resolve(
        "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/BodyExecutionEngine.kt",
    )

    private fun read(path: java.nio.file.Path): String {
        require(Files.exists(path)) { "Expected source not found: $path" }
        return Files.readString(path)
    }

    /** Comment-blind view of a source file. */
    private fun codeOnly(text: String): String =
        text.lineSequence()
            .filter { !it.trimStart().startsWith("//") && !it.trimStart().startsWith("*") }
            .joinToString("\n")

    @Test
    fun `compiler knows the waitUntil domain decision and the codec symbol`() {
        val code = codeOnly(read(compilerSource))
        assertTrue(
            code.contains("StepSpec.WaitUntilBlock"),
            "DslCompiledPipelineCompiler must route StepSpec.WaitUntilBlock; if this fails the " +
                "routing arm was removed and waitUntil falls into the generic else branch.",
        )
        assertTrue(
            code.contains("CoreWaitUntilInput"),
            "DslCompiledPipelineCompiler must know CoreWaitUntilInput (the " +
                "StepSpec.WaitUntilBlock -> CoreWaitUntilInput domain transformation). Without a " +
                "typed value there is nothing for a codec to encode.",
        )
        assertTrue(
            code.contains("CoreWaitUntilWireCodec"),
            "DslCompiledPipelineCompiler must encode the waitUntil block payload through " +
                "CoreWaitUntilWireCodec — the single wire authority for that format.",
        )
    }

    @Test
    fun `compiler never writes the waitUntil block wire vocabulary`() {
        val code = codeOnly(read(compilerSource))
        // Allowed knowledge: the domain transformation with NAMED ARGUMENTS. Those are
        // fields of a typed domain value checked by the Kotlin compiler, not wire text.
        // Forbidden knowledge: WIRE-JSON AUTHORSHIP — the JsonObject keys of that
        // format, written by hand in the compiler.
        val forbidden = listOf(
            "put(\"kind\", \"waitUntilBlock\"",
            "put(\"initialRecurrencePeriod\"",
            "put(\"quiet\"",
        )
        val found = forbidden.filter { code.contains(it) }
        assertTrue(
            found.isEmpty(),
            "DslCompiledPipelineCompiler hand-writes the waitUntil block wire JSON ($found). " +
                "It must lower StepSpec.WaitUntilBlock through " +
                "CoreWaitUntilWireCodec.encode, never author the wire format " +
                "(DEBT-WIRE-AUTHORITY; the core.sh dialect split is the frozen counter-example).",
        )
    }

    @Test
    fun `waitUntil block wire vocabulary lives in the wire authority`() {
        val code = codeOnly(read(wireCodecSource))
        val expected = listOf("waitUntilBlock", "initialRecurrencePeriod", "quiet", "maxBackoffMs")
        val missing = expected.filter { !code.contains(it) }
        assertTrue(
            missing.isEmpty(),
            "CoreWaitUntilWireCodec no longer spells $missing; the guard tracks a moved or " +
                "renamed wire authority. Update the path(s) this fitness scans, not the law.",
        )
    }

    /**
     * The wire has exactly ONE reader of a per-block field, and it is the typed projection.
     *
     * The residue this guards against is measured, not hypothetical:
     * `StepDispatchEngine` extracted `maxBackoffMs` from the raw payload with a
     * regex and passed it to `BodyExecutionEngine` as an override parameter. That
     * value reached the INLINE polling branch only, and the inline branch runs
     * iff the waitUntil control journal is null, which `CompositionRoot` never
     * satisfies in production. So a per-block ceiling was settable in a payload
     * and unreachable in the branch production takes — the defining symptom of a
     * second, unregulated reader of a wire field.
     *
     * A projection now decodes it once (`projectWaitUntilScope`) and both branches
     * read `scope.maxBackoffMs`, so the override parameter has no reader left.
     */
    @Test
    fun `no waitUntil block field is read by a side channel beside the typed projection`() {
        val dispatch = codeOnly(read(dispatchEngineSource))
        assertTrue(
            !dispatch.contains("maxBackoffMsOverride"),
            "StepDispatchEngine computes maxBackoffMsOverride, a regex side-channel into one " +
                "polling branch. The typed projection supplies the ceiling to BOTH branches; a " +
                "second reader is a second authority for the same field.",
        )
        val engine = codeOnly(read(bodyEngineSource))
        assertTrue(
            !engine.contains("maxBackoffMsOverride"),
            "BodyExecutionEngine still declares a maxBackoffMsOverride parameter. With the " +
                "projection as the only reader it has no caller that can supply a non-null value.",
        )
    }
}
