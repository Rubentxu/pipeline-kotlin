package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * LFC-2 / RP6-A / WU-091 G3.5 — `core.lock` wire-authority guard (SPEC_WU091_LOCK.md).
 *
 * ## Law under test
 *
 * ```
 * DslCompiledPipelineCompiler
 *     may know:    StepSpec.Lock, CoreLockInput, CoreLockWireCodec
 *     must NOT know: the lock wire vocabulary
 *                    ("resource", "timeoutSeconds", "skipIfLocked")
 *                    or any hand-written lock wire JSON
 * ```
 *
 * `CoreLockWireCodec` is THE single authority for the `core.lock` wire format.
 * `core.sh` is the counter-example frozen by WU-091: its compiler producer and
 * its codec self-encoding drifted (`kind=sh|shell`, `command|script`,
 * `returnStdout|returnMode`), and every alias is a compatibility shim added
 * after the divergence. This guard refuses to manufacture that debt for lock.
 *
 * Scope is exactly the operator's G3.5 sizing: prohibited ONLY for `core.lock`
 * today. `Dir`, `WithEnv`, `TimeoutBlock`, `RetryBlock`, … still serialize wire
 * inline in the compiler — that is DEBT-WIRE-AUTHORITY, a separate horizontal
 * evolutivo AFTER RP6-A, deliberately NOT folded into this train.
 *
 * The scan is comment-blind, like the rest of the Lfc2 source-scan fitness
 * family: a forbidden literal inside a comment does not trip it, and a
 * permitted symbol only named in comments does not satisfy the allowed-
 * knowledge assertion either.
 */
class Lfc2LockWireAuthorityFitnessTest {

    private val compilerSource = ScannerSupport.v2Root().resolve(
        "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/DslCompiledPipelineCompiler.kt",
    )
    private val wireCodecSource = ScannerSupport.v2Root().resolve(
        "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreLockWireCodec.kt",
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
    fun `compiler knows the lock domain decision and the codec symbol`() {
        val code = codeOnly(read(compilerSource))
        // Allowed knowledge — the domain transformation and the encoding call.
        assertTrue(
            code.contains("StepSpec.Lock"),
            "DslCompiledPipelineCompiler must route StepSpec.Lock; if this fails the routing " +
                "case was removed and lock falls into the generic else branch.",
        )
        assertTrue(
            code.contains("CoreLockInput"),
            "DslCompiledPipelineCompiler must know CoreLockInput (the StepSpec.Lock -> " +
                "CoreLockInput domain transformation, G3.3).",
        )
        assertTrue(
            code.contains("CoreLockWireCodec"),
            "DslCompiledPipelineCompiler must encode the lock payload through CoreLockWireCodec " +
                "(the single wire authority, G3.4).",
        )
    }

    @Test
    fun `compiler never writes the lock wire vocabulary`() {
        val code = codeOnly(read(compilerSource))
        // Forbidden knowledge — WIRE-JSON AUTHORSHIP. The domain transformation
        // (StepSpec.Lock -> CoreLockInput with named arguments) is allowed: those
        // are the fields of a typed domain value checked by the Kotlin compiler.
        // What the compiler must never do is hand-write the lock wire JSON — the
        // JsonObject keys and string-embedded spellings of that format:
        val forbidden = listOf(
            "put(\"resource\"",          // JsonObject key authorship
            "put(\"timeoutSeconds\"",
            "put(\"skipIfLocked\"",
            "put(\"kind\", \"lock\"",     // lock kind literal in a hand-built payload
            "\\\"resource\\\"",           // raw JSON string spelling
            "\\\"timeoutSeconds\\\"",
            "\\\"skipIfLocked\\\"",
        )
        val found = forbidden.filter { code.contains(it) }
        assertTrue(
            found.isEmpty(),
            "DslCompiledPipelineCompiler hand-writes lock wire JSON ($found). The compiler must " +
                "lower StepSpec.Lock through CoreLockWireCodec.encode, never author the wire " +
                "format (G3.4, SPEC_WU091_LOCK.md; the core.sh dialect split is the frozen " +
                "counter-example).",
        )
    }

    @Test
    fun `lock wire vocabulary lives in the wire authority`() {
        val code = codeOnly(read(wireCodecSource))
        val expected = listOf("resource", "timeoutSeconds", "skipIfLocked")
        val missing = expected.filter { !code.contains(it) }
        assertTrue(
            missing.isEmpty(),
            "CoreLockWireCodec no longer spells $missing; the guard tracks a moved or renamed " +
                "wire authority. Update the path(s) this fitness scans, not the law.",
        )
    }
}
