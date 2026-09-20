package dev.rubentxu.pipeline.v2.scripting

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse

/**
 * SH-VAR-SCOPE-CONTRACT / Gap #2 — CONTRACT TEST.
 *
 * Reference: `openspec/changes/sh-var-scope-contract/spec.md §Gap #2`.
 * Contract: `$VAR` outside any `withCredentials` block — three observable
 *            patterns, each verified byte-level via the existing escaper
 *            pipeline (extract -> escape) without spinning up a full
 *            Kotlin compiler.
 *
 * Per Guard G2 (operator, 2026-09-20T08:16Z): "bytes, not aspect". The
 * contract is asserted on the actual byte stream the production flow
 * feeds forward.
 *
 * Per Guard G1: "DOC only is not abandonment". Gap #1 (same-name
 * collisions) is document-only and links to CHARACTERISATION.md §5.2.
 * Gap #2 here is the in-process contract test that locks the everyday
 * authoring pattern outside any credential scope.
 *
 * Per Guard G3: this cycle does NOT touch ScriptTextEscaper or
 * EnvVarNameExtractor. The asserts pin down their CURRENT behaviour.
 *
 * Fixture construction technique (from S2ThreePhaseProbeTest): source
 * bytes are assembled by String concatenation so the bytes the test
 * passes to EnvVarNameExtractor.extract / ScriptTextEscaper.escape are
 * exactly the bytes a hand-written `.pipeline.kts` file would contain.
 */
@Timeout(60)
class ShVarScopeGap02Test {

    private fun dollar() = "$"
    private fun bsl() = "\\"
    private fun lc() = "{"
    private fun rc() = "}"

    /**
     * Case 2a — Kotlin local resolves; the escoper does NOT touch the
     * unbraced `$VAR` because Kotlin wins at compile time and the bytes
     * Kotlin leaves behind are the already-substituted value.
     *
     * Source (verbatim bytes the user writes):
     *   pipeline { stages { stage("s") { val user="alice"; sh("echo user=$user") } } }
     *
     * The escoper is a no-op here (no withCredentials, no $UPPER_CASE).
     * The bytes that flow forward to core.sh are the literal Kotlin
     * string value: `pipeline ... sh("echo user=alice") ...`.
     */
    @Test
    fun `gap02a — kotlin local resolves, escoper is no-op`() {
        val source = (
            "pipeline { stages { stage(\"s\") { val user=\"alice\"; " +
                "sh(\"echo user=" + dollar() + "user\") } } }"
            )
        val envVars = EnvVarNameExtractor.extract(source)
        assertEquals(emptySet<String>(), envVars, "no withCredentials => empty envVars")
        val escaped = ScriptTextEscaper.escape(source, envVars)
        assertEquals(
            source,
            escaped,
            "no env-var rewrite happens; the escoper is a byte-identity function " +
                "when the script has no withCredentials bound identifiers",
        )
    }

    /**
     * Case 2b — Kotlin escape `\$VAR` outside any `withCredentials`.
     *
     * Source:
     *   pipeline { stages { stage("s") { sh("echo user=\${USER}") } } }
     *
     * The escoper is again a no-op (USER is not in envVars, because there
     * is no withCredentials). The bytes that flow forward carry the
     * literal `${USER}` form; bash expands it at run time.
     */
    @Test
    fun `gap02b — Kotlin escape survives escoper byte-equivalent`() {
        val source = (
            "pipeline { stages { stage(\"s\") { sh(\"echo user=" +
                bsl() + dollar() + lc() + "USER" + rc() + "\") } } }"
            )
        val envVars = EnvVarNameExtractor.extract(source)
        assertEquals(emptySet<String>(), envVars)
        val escaped = ScriptTextEscaper.escape(source, envVars)
        assertEquals(
            source,
            escaped,
            "no withCredentials => escoper leaves the escape form intact",
        )
        assertTrue(
            escaped.contains("\${USER}"),
            "byte stream MUST contain the literal \${USER} for bash to expand",
        )
    }

    /**
     * Case 2c — bare `$UPPER_CASE` outside any scope fails at the
     * Kotlin compiler (not at the escoper) because Kotlin tries to
     * template-expand the identifier and finds no local.
     *
     * This case is verified at the escoper/pipeline boundary, NOT by
     * re-running the compiler. The contract is:
     *
     *   - envVars stays empty (no withCredentials).
     *   - The escoper, when envVars is empty, is a byte-identity function
     *     (ScriptTextEscaper.escape early-returns: `if (envVars.isEmpty())
     *     return scriptText`, line 33).
     *   - The bytes the production compiler then sees are exactly the
     *     user source — and that source is what the Kotlin compiler
     *     rejects with `Unresolved reference 'NOPE_NO_BINDING'`. We have
     *     already reproduced that diagnostic at the byte level in
     *     `S2ThreePhaseProbeTest` Form A (`A_raw`, L1:C49
     *     `Unresolved reference 'USER'`).
     *
     * This test pins the escoper behaviour for the empty-envVars case
     * so a future refactor that changes the early-return cannot silently
     * flip the contract.
     */
    @Test
    fun `gap02c — bare unbound $UPPER passes through, compile must fail`() {
        val source = (
            "pipeline { stages { stage(\"s\") { sh(\"echo user=" +
                dollar() + "NOPE_NO_BINDING\") } } }"
            )
        val envVars = EnvVarNameExtractor.extract(source)
        assertEquals(emptySet<String>(), envVars)
        val escaped = ScriptTextEscaper.escape(source, envVars)
        assertEquals(
            source,
            escaped,
            "empty envVars => ScriptTextEscaper.escape is byte-identity; " +
                "the bytes the Kotlin compiler rejects are exactly the user-written bytes",
        )
        // The escoper's own output (no withCredentials) must NOT
        // contain the trap form's footprint — that would mean the
        // escoper reached into source on its own, which is forbidden.
        assertFalse(
            escaped.contains("\${'$'}"),
            "escoper MUST NOT inject \${'\$'} on its own when envVars is empty",
        )
    }
}
