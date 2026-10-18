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
 * ## Why the bytes must not move
 *
 * `Lfc2WaitUntilDslCanonicalProjectionTest` asserts, across the productive
 * authority (`DslCompiledPipelineCompiler.compile`), that the compiled block
 * payload carries `kind="waitUntilBlock"`. `BodyStructureDigest` appends
 * `child.payload.encoded` — the STRING — so changing a single character moves
 * the durable body identity and DR-10 fails closed on existing history. The
 * extraction therefore owns the format **as it is today**, byte for byte; it
 * is a change of authorship, not of the format.
 *
 * This fitness asserts *authorship*, never a literal value. The byte-identity
 * guarantee is carried by the behavioural contract test
 * (`CoreWaitUntilWireCodecContractTest`, pipeline-application), which compiles
 * a real block through the productive path.
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
        val expected = listOf("waitUntilBlock", "initialRecurrencePeriod", "quiet")
        val missing = expected.filter { !code.contains(it) }
        assertTrue(
            missing.isEmpty(),
            "CoreWaitUntilWireCodec no longer spells $missing; the guard tracks a moved or " +
                "renamed wire authority. Update the path(s) this fitness scans, not the law.",
        )
    }
}
