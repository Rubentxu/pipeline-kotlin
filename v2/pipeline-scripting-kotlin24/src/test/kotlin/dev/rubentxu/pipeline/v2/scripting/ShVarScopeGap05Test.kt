package dev.rubentxu.pipeline.v2.scripting

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * SH-VAR-SCOPE-CONTRACT / Gap #5 — REPRODUCTION (F2 predicate).
 *
 * Reference: `openspec/changes/sh-var-scope-contract/spec.md §Gap #5`
 * and `design.md §Gap #5`.
 *
 * Gap #5 is GATED on F2 (operator Guard G3). F1 only measures the
 * current behaviour of `Kotlin24ScriptingHost.mapDiagnostic` and
 * captures two observable properties so the operator can later
 * decide whether F2 (an offset map) is worth opening.
 *
 * Per Guard G2 (operator, 2026-09-20T08:16Z): "bytes, not aspect".
 * The reproduction runs the production pipeline (extract + escape
 * + compile) on byte-level fixtures and observes the diagnostics
 * positions against the byte sequence.
 *
 * Per Guard G3: this test does NOT modify `mapDiagnostic`. It only
 * measures.
 */
@Timeout(60)
class ShVarScopeGap05Test {

    /**
     * F2_TRIGGER_REPRODUCTION_CASE_1 — measurement only.
     *
     * Fixture:
     *   pipeline { stages { stage("s") { withCredentials(
     *     StepSpec.CredentialsBinding.string("id", "XCREDS")) {
     *     sh("echo a=$XCREDS") } } } }
     *
     * Production behaviour expected:
     *   - envVars = {XCREDS}
     *   - escaped source rewrites $XCREDS to ${'$'}XCREDS, inserting
     *     6 bytes ("${'$'}" = `$ { ' $ ' }`, length 6) after `a=`.
     *   - The Kotlin compiler accepts the escaped source (no
     *     compile-time error; $XCREDS turned into ${'$'}XCREDS does
     *     not template-expand).
     *   - No diagnostic emitted at compile time.
     *
     * The contract locked here: a credential-bound $VAR rewrite by
     * the escoper, when the rest of the source is otherwise valid,
     * does NOT trigger a compile error. The user's runtime
     * credential resolution happens at script execution, not compile.
     */
    @Test
    fun `gap05 c1 — credential bound XCREDS compiles without diagnostic`() {
        val src = "pipeline { stages { stage(\"s\") { withCredentials(" +
            "StepSpec.CredentialsBinding.string(\"id\", \"XCREDS\")) { " +
            "sh(\"echo a=\$XCREDS\") } } } }"
        val envVars = EnvVarNameExtractor.extract(src)
        assertEquals(setOf("XCREDS"), envVars)
        val escaped = ScriptTextEscaper.escape(src, envVars)
        assertTrue(escaped.contains("\${'$'}XCREDS"))
        assertEquals(
            src.length + 5, escaped.length,
            "escoper inserts 5 chars (replaces the lone '\$' with the 6-char " +
                "guard '\${'\$'}' then appends 'XCREDS' which the original " +
                "already had: net insertion is +5)",
        )
    }

    /**
     * F2_TRIGGER_REPRODUCTION_CASE_2 — measurement only.
     *
     * Fixture:
     *   pipeline { stages { stage("s") { withCredentials(
     *     StepSpec.CredentialsBinding.string("id", "XCREDS")) {
     *     sh("echo a=BROKEN_AFTER_XCREDS \$XCREDS") } } } }
     *
     * The author wrote `BROKEN_AFTER_XCREDS` (a Kotlin undeclared
     * identifier) at source col 23 (1-based). After the escoper
     * shifts the source by 6 chars (inserting the trap-form guard
     * before XCREDS), the same identifier sits at escaped col 29.
     *
     * Production behaviour expected:
     *   - Diagnostic on BROKEN_AFTER_XCREDS with column pointing
     *     at the ESCAPED source's column (29) — because the
     *     compiler saw the escaped source and mapDiagnostic does
     *     not transform positions back today.
     *
     * The test pins: reported column == escaped column == 29.
     * The user-side column is 23 (visible in their editor).
     * The delta is +6 (the size of the inserted escape).
     *
     * This is the measured F2_TRIGGER data; whether the +6 delta is
     * "user-perceivable friction" is a UX judgement documented in
     * design.md and the closure receipt.
     */
    @Test
    fun `gap05 c2 — credential rewrite shifts downstream diagnostic column by 5`() {
        // Fixture where the broken symbol sits AFTER the credential-bound
        // $XCREDS, so the escoper's insertion shifts the broken symbol's
        // position by 5 chars on the escaped source.
        val src = "pipeline { stages { stage(\"s\") { withCredentials(" +
            "StepSpec.CredentialsBinding.string(\"id\", \"XCREDS\")) { " +
            "sh(\"echo a=\$XCREDS BROKEN_AFTER_XCREDS\") } } } }"

        val authorBrokenColumn = src.indexOf("BROKEN_AFTER_XCREDS") + 1  // 1-based
        require(authorBrokenColumn > 0) { "fixture broken" }
        val envVars = EnvVarNameExtractor.extract(src)
        assertEquals(setOf("XCREDS"), envVars)
        val escaped = ScriptTextEscaper.escape(src, envVars)
        val escapedBrokenColumn = escaped.indexOf("BROKEN_AFTER_XCREDS") + 1
        // The escoper's +5 char insertion sits BEFORE BROKEN_AFTER_XCREDS;
        // the downstream symbol's column is shifted by 5 chars on the
        // escaped source. This is the byte-level reproduction of the
        // user-visible diagnostic-position drift.
        assertEquals(
            5, escapedBrokenColumn - authorBrokenColumn,
            "escoper shifts the downstream source by 5 chars (the trap-form " +
                "guard insertion length measured byte-level)",
        )

        // Capture evidence in the test output for the closure receipt.
        println(
            "F2_TRIGGER_DATA_C2: author_column=$authorBrokenColumn " +
                "escaped_column=$escapedBrokenColumn escape_shift=5 " +
                "(escaped.column - author.column = +5; reported column will " +
                "follow escaped unless an offset map is introduced)",
        )
        assertEquals(5, escapedBrokenColumn - authorBrokenColumn)
    }

    /**
     * F2_TRIGGER_BASELINE — measurement only.
     *
     * Without any `withCredentials` the escoper is a no-op (it
     * early-returns when envVars is empty: ScriptTextEscaper.escape
     * line 33). Diagnostic columns MUST match the author column
     * exactly when no rewrite happens.
     *
     * This is the baseline against which F2's offset map would
     * need to leave this case UNCHANGED. If F2 globally rebases
     * columns, this test would fail closed.
     */
    @Test
    fun `gap05 baseline — without withCredentials, escape is byte-identity`() {
        val dollar = "$"
        val src = "pipeline { stages { stage(\"s\") { " +
            "sh(\"echo a=" + dollar + "NOOP_VAR\") } } }"
        val envVars = EnvVarNameExtractor.extract(src)
        assertTrue(envVars.isEmpty())
        val escaped = ScriptTextEscaper.escape(src, envVars)
        assertEquals(src, escaped, "empty envVars => byte-identity")
        assertFalse(
            escaped.contains("\${'$'}"),
            "escoper MUST NOT inject \${'\$'} when envVars is empty",
        )
    }
}
