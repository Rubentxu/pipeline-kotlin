package dev.rubentxu.pipeline.v2.scripting

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse

/**
 * SH-VAR-SCOPE-CONTRACT / Gap #3 — CONTRACT TEST.
 *
 * Reference: `openspec/changes/sh-var-scope-contract/spec.md §Gap #3`.
 * Contract: shell-specific expansions `${VAR:-default}`, `$(cmd)`,
 *            `${VAR##pattern}` inside `sh("…")` — the contract is that
 *            the opening `{` MUST NOT form a Kotlin template. The
 *            author must use the Kotlin `\$` / `\${…}` escape family.
 *
 * Per Guard G2 (operator, 2026-09-20T08:16Z): "bytes, not aspect".
 * Verifies the actual byte stream produced by `EnvVarNameExtractor +
 * ScriptTextEscaper.escape` for the three authorised shell expansion
 * shapes.
 *
 * Per Guard G3: this does NOT modify ScriptTextEscaper or
 * EnvVarNameExtractor. The asserts pin their CURRENT behaviour.
 *
 * The negative `${'$'}{USER}` form is already locked as Trap T1 by
 * `S2ThreePhaseProbeTest` Form B (`Unresolved reference 'USER'`,
 * captured at L1 of `S2-three-phase-probe-extended.txt`); not
 * duplicated here.
 */
@Timeout(60)
class ShVarScopeGap03Test {

    private fun dollar() = "$"
    private fun bsl() = "\\"
    private fun lc() = "{"
    private fun rc() = "}"

    /**
     * Case 3a — `${VAR:-default}` — the safe escape form `\${VAR:-default}`
     *            must survive the escoper byte-equivalent so bash
     *            receives the literal `${OPTIONAL:-fallback}` and
     *            expands with the default when OPTIONAL is unset.
     */
    @Test
    fun `gap03a — braced default expansion survives escoper`() {
        val source = (
            "pipeline { stages { stage(\"s\") { withEnv(listOf(\"OPTIONAL=\")) { " +
                "sh(\"echo value=" + bsl() + dollar() + lc() +
                "OPTIONAL:-fallback" + rc() + "\") } } } }"
            )
        val envVars = EnvVarNameExtractor.extract(source)
        // withEnv is NOT extracted by EnvVarNameExtractor (operator decision G1);
        // the only env-var surface here is from withEnv, so envVars is empty.
        assertEquals(
            emptySet<String>(),
            envVars,
            "withEnv is NOT a source of envVars by operator decision (G1)",
        )
        val escaped = ScriptTextEscaper.escape(source, envVars)
        assertEquals(
            source,
            escaped,
            "empty envVars => byte-identity; the literal \${OPTIONAL:-fallback} " +
                "must reach bash for its default-expansion semantics",
        )
        assertTrue(
            escaped.contains("\${OPTIONAL:-fallback}"),
            "byte stream MUST contain the literal \${OPTIONAL:-fallback} " +
                "(default-expansion shape)",
        )
    }

    /**
     * Case 3b — `$(cmd)` — the literal `$(hostname)` must survive the
     *            escoper so bash performs command substitution.
     */
    @Test
    fun `gap03b — command substitution survives escoper`() {
        val source = (
            "pipeline { stages { stage(\"s\") { " +
                "sh(\"echo host=" + bsl() + dollar() + "(hostname)\") } } }"
            )
        val envVars = EnvVarNameExtractor.extract(source)
        assertEquals(emptySet<String>(), envVars)
        val escaped = ScriptTextEscaper.escape(source, envVars)
        assertEquals(
            source,
            escaped,
            "literal \$(hostname) must reach bash for command substitution",
        )
        assertTrue(
            escaped.contains("\$(hostname)"),
            "byte stream MUST contain \$(hostname) for bash command substitution",
        )
    }

    /**
     * Case 3c — `${VAR##pattern}` — glob-strip suffix in a braced
     *            shell parameter expansion must reach bash intact.
     */
    @Test
    fun `gap03c — glob-strip expansion survives escoper`() {
        val source = (
            "pipeline { stages { stage(\"s\") { withEnv(listOf(\"PATH_STR=/a/b/c\")) { " +
                "sh(\"echo head=" + bsl() + dollar() + lc() +
                "PATH_STR##*/" + rc() + "\") } } } }"
            )
        val envVars = EnvVarNameExtractor.extract(source)
        assertEquals(
            emptySet<String>(),
            envVars,
            "withEnv is NOT a source of envVars (G1)",
        )
        val escaped = ScriptTextEscaper.escape(source, envVars)
        assertEquals(
            source,
            escaped,
            "literal \${PATH_STR##*/} must reach bash for glob-strip semantics",
        )
        assertTrue(
            escaped.contains("\${PATH_STR##*/}"),
            "byte stream MUST contain \${PATH_STR##*/} for bash glob-strip",
        )
        // And: the escoper MUST NOT inject any trap-form footprint —
        // confirming the same byte-identity guarantee as Gap #2.
        assertFalse(
            escaped.contains("\${'$'}"),
            "escoper MUST NOT inject \${'\$'} when envVars is empty",
        )
    }
}
