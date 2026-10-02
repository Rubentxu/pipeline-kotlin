package dev.rubentxu.pipeline.v2.architecture

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * LFC-2 / RP6-B / WU-092 G3.5 — the `core.input` wire-authority guard
 * (SPEC_WU092_INPUT.md, mirroring `Lfc2LockWireAuthorityFitnessTest`).
 *
 * ## Law under test
 *
 * ```
 * DslCompiledPipelineCompiler
 *     may know:     StepSpec.Input, CoreInputInput, CoreInputWireCodec
 *     must NOT know: the input wire JSON
 * ```
 *
 * `CoreInputWireCodec` is THE single authority for the `core.input` wire format.
 * `core.sh` is the frozen counter-example: its compiler producer and its codec
 * self-encoding drifted (`kind=sh|shell`, `command|script`) and every alias became
 * a compatibility shim added after the divergence. This guard refuses to
 * manufacture that debt for input.
 *
 * ## Why this guard scans STRUCTURALLY, unlike the lock one
 *
 * The lock guard is lexical: it forbids the literals `resource`, `timeoutSeconds`
 * and `skipIfLocked` anywhere in the compiler, which is sound because that
 * vocabulary belongs to `core.lock` alone.
 *
 * The naive port of that approach to input is UNSOUND, and the first execution of
 * this guard proved it: `put("message"` is not input vocabulary. `StepSpec.Error`,
 * `StepSpec.WarnError`, `StepSpec.Unstable` and `StepSpec.CatchError` have all
 * legitimately written a `message` key in this compiler since before RP6-B. A
 * lexical scan therefore fails on correct code, and the only "fixes" available to
 * a future maintainer are deleting a real law or learning to ignore the guard.
 *
 * So the law is enforced where it is actually decidable: the payload is produced
 * by ONE `when` branch, and that branch must contain nothing but the delegation
 * to `CoreInputWireCodec`. The structural form is strictly stronger than the
 * lexical one — it forbids EVERY hand-written key, including `message`, forever —
 * and it has no false positives to weaken it.
 *
 * Scope is `core.input` only. `Dir`, `WithEnv`, `TimeoutBlock`, `RetryBlock` and
 * the rest still author their wire inline in the compiler: that is
 * DEBT-WIRE-AUTHORITY, a separate horizontal evolutive, deliberately not folded
 * into this train.
 */
class Lfc2InputWireAuthorityFitnessTest {

    private val compilerSource: Path = ScannerSupport.v2Root().resolve(
        "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/DslCompiledPipelineCompiler.kt",
    )
    private val wireCodecSource: Path = ScannerSupport.v2Root().resolve(
        "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreInputWireCodec.kt",
    )

    private fun read(path: Path): String {
        require(Files.exists(path)) { "Expected source not found: $path" }
        return Files.readString(path)
    }

    /** Comment-blind view of a source file, like the rest of the Lfc2 scan family. */
    private fun codeOnly(text: String): String =
        text.lineSequence()
            .filter { !it.trimStart().startsWith("//") && !it.trimStart().startsWith("*") }
            .joinToString("\n")

    /**
     * The body of the `is StepSpec.Input ->` arm of the payload `when`, brace
     * aware, so a one-liner delegation and a multi-line hand-built JsonObject are
     * both captured in full.
     */
    private fun inputPayloadBranch(): String {
        val lines = read(compilerSource).lines()
        val start = lines.indexOfFirst {
            it.trimStart().startsWith("is StepSpec.Input ->") && it.contains("CoreInputWireCodec")
        }
        require(start >= 0) {
            "No `is StepSpec.Input -> ... CoreInputWireCodec` arm found in $compilerSource. " +
                "core.input must lower through the single wire authority (G3.4)."
        }
        val body = StringBuilder()
        var depth = 0
        for (i in start until lines.size) {
            val trimmed = lines[i].trim()
            if (i > start && (trimmed.startsWith("is StepSpec.") || trimmed.startsWith("else ->") || trimmed == "}")) {
                break
            }
            body.appendLine(lines[i])
            depth += lines[i].count { it == '{' } - lines[i].count { it == '}' }
            if (i > start && depth <= 0) break
        }
        return codeOnly(body.toString())
    }

    @Test
    fun `compiler knows the input domain decision and the codec symbol`() {
        val code = codeOnly(read(compilerSource))
        assertTrue(
            code.contains("StepSpec.Input"),
            "DslCompiledPipelineCompiler must route StepSpec.Input; if this fails the routing " +
                "case was removed and input falls into the generic else branch.",
        )
        assertTrue(
            code.contains("CoreInputInput"),
            "the compiler must know CoreInputInput (the StepSpec.Input -> CoreInputInput " +
                "domain transformation, G3.3).",
        )
        assertTrue(
            code.contains("CoreInputWireCodec"),
            "the compiler must encode the payload through CoreInputWireCodec (G3.4).",
        )
    }

    @Test
    fun `the input payload branch only delegates to the wire authority`() {
        val branch = inputPayloadBranch()
        assertTrue(
            branch.contains("CoreInputWireCodec.encode"),
            "the input arm of the payload `when` must delegate to CoreInputWireCodec.encode; " +
                "it no longer does, so someone gave the compiler a second wire author:\n$branch",
        )
        // The probe is `put("`, not `put(`: the bare `put(` is a SUBSTRING of
        // `toCoreInputInput(` and would fail on the correct code. Widening it back
        // would be the exact false positive this guard was rewritten to remove.
        assertFalse(
            branch.contains("put(\""),
            "the input payload branch authors JSON keys itself:\n$branch\nThe wire format " +
                "belongs to CoreInputWireCodec alone (G3.4).",
        )
        assertFalse(
            branch.contains("buildJsonObject") || branch.contains("JsonObject("),
            "the input payload branch builds a JsonObject instead of delegating:\n$branch",
        )
    }

    @Test
    fun `no compiler branch authors an input-only wire key`() {
        // `message` is deliberately absent here: it is shared with error/warnError/
        // unstable/catchError and is covered structurally by the branch scan above.
        // These four ARE input-exclusive, so any occurrence is unattributable debt.
        val code = codeOnly(read(compilerSource))
        val forbidden = listOf(
            "put(\"submitter\"",
            "put(\"ok\"",
            "put(\"timeoutSeconds\"",
            "put(\"kind\", \"input\"",
        )
        val found = forbidden.filter { code.contains(it) }
        assertTrue(
            found.isEmpty(),
            "DslCompiledPipelineCompiler hand-writes input-exclusive wire keys ($found). Those " +
                "belong to CoreInputWireCodec (G3.4; the core.sh dialect split is the frozen " +
                "counter-example).",
        )
    }

    @Test
    fun `the wire authority owns the input vocabulary`() {
        val code = codeOnly(read(wireCodecSource))
        val expected = listOf("message", "ok", "submitter", "id", "timeoutSeconds")
        val missing = expected.filter { !code.contains(it) }
        assertTrue(
            missing.isEmpty(),
            "CoreInputWireCodec no longer spells $missing; the guard tracks a moved or renamed " +
                "wire authority. Update the path(s) this fitness scans, not the law.",
        )
    }
}
