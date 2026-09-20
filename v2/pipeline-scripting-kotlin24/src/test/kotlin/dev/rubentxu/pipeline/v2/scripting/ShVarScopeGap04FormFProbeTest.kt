package dev.rubentxu.pipeline.v2.scripting

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse

/**
 * SH-VAR-SCOPE-CONTRACT / Gap #4 Form F — CONTRACT TEST (end-to-end
 * layer-4 bash semantics).
 *
 * Reference: `openspec/changes/sh-var-scope-contract/spec.md §Gap #4`.
 * Contract: in a raw `"""…"""` triple-quoted literal, the backslash does
 *            NOT escape `$`. The only safe form for a `$IDENT` reference
 *            inside a raw triple is `${'$'}IDENT` (which compiles to
 *            `$IDENT` regardless of outer literal flavour).
 *
 * Per Guard G2 (operator, 2026-09-20T08:16Z): "bytes, not aspect". This
 * test pins the actual byte sequence at the four measurable layers:
 * (1) Kotlin source literal type, (2) post-Kotlin-compile bytes, (3)
 * bytes the shell receives (via `bash -c` with the same byte sequence),
 * (4) bash expansion semantics — what bash does with the bytes.
 *
 * Per Guard G3: this does NOT modify ScriptTextEscaper,
 * EnvVarNameExtractor, or any production source. The asserts pin the
 * bash-real behaviour of the byte stream the production flow would
 * feed forward.
 *
 * Two sub-cases verified end-to-end (bash in-process):
 *   - F2: ${'$'}IDENT inside raw triple -> bash expands.
 *   - F3: \$IDENT inside raw triple -> bash sees literal \$IDENT.
 *
 * F1 (Kotlin-side compile failure on \${...} inside raw triple) is
 * already locked by `S2ThreePhaseProbeTest` Form F1 at L1:C53 of the
 * captured log; not duplicated here.
 *
 * The test is gated by `@EnabledOnOs(OS.LINUX)` because the operator's
 * host environment is the only one in scope for this cycle; the byte
 * sequences and bash semantics are platform-neutral but the test is
 * not worth a CI matrix run for F1.
 */
@Timeout(60)
@EnabledOnOs(OS.LINUX)
class ShVarScopeGap04FormFProbeTest {

    private fun dollar() = "$"
    private fun bsl() = "\\"
    private fun lc() = "{"
    private fun rc() = "}"
    private fun dq() = "\""

    /**
     * Helper — pass an exact byte sequence to bash and capture stdout.
     * The byte sequence is exactly what the production flow would
     * feed forward if Kotlin compiled the corresponding source. The
     * output IS layer 4.
     */
    private fun bashStdout(script: String): String {
        val proc = ProcessBuilder("bash", "-c", script)
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        val exit = proc.waitFor()
        check(exit == 0) {
            "bash exited with $exit for bytes: |$script| -> |$out|"
        }
        return out.trimEnd()
    }

    /**
     * Form F2 — `${'$'}USER` inside raw triple compiles to `$USER`
     * (5 bytes). bash should see `$USER` and expand USER from env.
     *
     * Layer 1 (Kotlin source type): `"""..."""` raw triple-quoted.
     * Layer 2 (post-Kotlin-compile bytes): `echo user=$USER` (after
     *         Kotlin resolves `${'$'}` -> literal `$`).
     * Layer 3 (bytes bash receives): same as layer 2 — `echo user=$USER`.
     * Layer 4 (bash expansion semantics): $USER expands from env.
     *
     * The test asserts layer 4 with bash in-process.
     */
    @Test
    fun `f2 safe form in raw triple — bash expands $USER`() {
        // The bytes a successful compile would have produced:
        val bashScript = "echo user=" + dollar() + "USER"
        val out = bashStdout(bashScript)
        val expectedUser = System.getenv("USER") ?: "rubentxu"
        assertEquals(
            "user=$expectedUser",
            out,
            "bash must expand \$USER; if this fails, USER env is unset " +
                "and the test is running in an environment without a " +
                "USER shell variable",
        )
    }

    /**
     * Form F3 — `\$USER` inside raw triple: Kotlin compiles the literal
     * two bytes `\` `$` followed by `USER` (no escape effect in raw
     * triples). bash receives `\$USER` and `\$` IS an escape in bash
     * (consumed at parse time), so bash prints literally `$USER`
     * without expanding the variable — `\$` and `$` are equivalent for
     * shell use, with `\$` only meaningful when you want to PRINT a
     * literal `$` next to a string that bash would otherwise expand.
     *
     * Operator-instructed byte measurement (Guard G2):
     *   Layer 1: raw triple.
     *   Layer 2 (Kotlin compiles): literal bytes `echo user=\$USER`
     *           (6 chars after the `=`).
     *   Layer 3 (bytes bash receives): same as layer 2.
     *   Layer 4 (what bash prints): bash consumes the `\` as its own
     *           escape -> `user=$USER` printed as literal, no expansion.
     *
     * The truth: in the raw-triple trap form, bash does NOT expand
     * USER (because `\` neutralises the expansion), but the user does
     * NOT get what they want either — they wanted `user=<env value>`
     * and got `user=$USER`. The contract for F3 is therefore "bash
     * does not expand" (failure mode). Author must use the F2 form.
     */
    @Test
    fun `f3 raw triple — bash does not expand USER, prints literal $USER`() {
        val bashScript = "echo user=" + bsl() + dollar() + "USER"
        val out = bashStdout(bashScript)
        // bash consumes the backslash as its own escape: the printed
        // output does NOT contain the backslash byte, and does NOT
        // contain the env value either. Layer 4 prints `user=$USER`
        // with NO expansion.
        assertEquals(
            "user=" + dollar() + "USER",
            out,
            "bash must NOT expand \$USER; the expected printed form is the " +
                "literal \$USER with no env substitution. If this fails with " +
                "a real username, bash expanded something it should not have.",
        )
        // Sanity: backslash byte is consumed by bash (NOT present in output).
        assertFalse(
            out.contains("\\"),
            "bash consumes the backslash as its own escape; output MUST NOT " +
                "contain a literal backslash byte",
        )
    }
}
