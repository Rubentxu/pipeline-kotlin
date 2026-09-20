package dev.rubentxu.pipeline.v2.scripting

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * SH-VAR-SCOPE.S2 three-phase byte-level probe.
 *
 * For each fixture, prints:
 *  - phase 1: original source text (the bytes the user wrote)
 *  - phase 2: escaper output (after ScriptTextEscaper.escape)
 *  - phase 3: kotlin-compiled result (whether compile succeeded)
 *
 * The fixture strings are built by String concatenation (NOT Kotlin string
 * templates) so the bytes we pass to the script compiler are exactly the
 * bytes that a hand-written .pipeline.kts file would contain.
 */
@Timeout(60)
class S2ThreePhaseProbeTest {

    private val domainJar = requireNotNull(ScriptDefinition.domainJar())
    private val dslJar: String? = ScriptDefinition.dslApiJar()
    private val host = Kotlin24ScriptingHost()

    private fun build(name: String, source: String): ScriptDefinition {
        val cp = buildList {
            add(domainJar)
            if (dslJar != null) add(dslJar)
        }
        return ScriptDefinition.inline(source, classpath = cp)
    }

    private fun phase1_3(name: String, source: String) {
        println("=== $name ===")
        println("PHASE_1_SOURCE=|$source|")

        // Phase 2: envVars extraction + escaper.
        val envVars = EnvVarNameExtractor.extract(source)
        val escaped = ScriptTextEscaper.escape(source, envVars)
        println("PHASE_2_ENVVARS=|$envVars|")
        println("PHASE_2_ESCAPED=|$escaped|")

        // Phase 3: compile with the project's Kotlin24ScriptingHost.
        val res = try {
            host.compile(build(name, source))
        } catch (e: Throwable) {
            println("PHASE_3_COMPILE=EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
            return
        }
        if (res.isSuccess) {
            println("PHASE_3_COMPILE=OK")
        } else {
            val msgs = res.diagnostics.joinToString(";") { d -> "${d.severity}:${d.message}:L${d.line}:C${d.column}" }
            println("PHASE_3_COMPILE=FAIL $msgs")
        }
    }

    private fun dollar() = "$"
    private fun bsl() = "\\"
    private fun lc() = "{"
    private fun rc() = "}"
    private fun dq() = "\""

    @Test
    fun `probe three forms all three phases`() {
        // Each fixture is built from pieces to avoid Kotlin-template interpretation
        // of the literal bytes inside the test source.  These bytes are exactly
        // what a user would type into a .pipeline.kts file.

        // Form A: raw $USER (unbraced, no Kotlin escape, no withCredentials).
        // Kotlin source:  pipeline { stages { stage("s") { sh("echo user=$USER") } } }
        val fA = "pipeline { stages { stage(\"s\") { sh(\"echo user=" + dollar() + "USER\") } } }"
        phase1_3("A_raw", fA)

        // Form B: trap form ${'$'}USER.
        // Kotlin source:  sh("echo user=${'$'}USER")
        val fB = "pipeline { stages { stage(\"s\") { sh(\"echo user=" + dollar() + lc() + "'" + dollar() + "'" + rc() + "USER\") } } }"
        phase1_3("B_trap", fB)

        // Form C: Kotlin \$ escape (unbraced).
        // Kotlin source:  sh("echo user=\$USER")
        val fC = "pipeline { stages { stage(\"s\") { sh(\"echo user=" + bsl() + dollar() + "USER\") } } }"
        phase1_3("C_simple", fC)

        // Form D: Kotlin braced escape \${VAR}.
        // Kotlin source:  sh("echo user=\${USER}")
        val fD = "pipeline { stages { stage(\"s\") { sh(\"echo user=" + bsl() + dollar() + lc() + "USER" + rc() + "\") } } }"
        phase1_3("D_braced", fD)

        // Form E: unbraced $USER with withCredentials binding.
        // Kotlin source:  withCredentials(StepSpec.CredentialsBinding.string("id","USER")) { sh("echo user=$USER") }
        val fE = "pipeline { stages { stage(\"s\") { withCredentials(StepSpec.CredentialsBinding.string(\"id\", \"USER\")) { sh(\"echo user=" + dollar() + "USER\") } } } }"
        phase1_3("E_withCreds", fE)
    }

    /**
     * Form F probes — added by SH-VAR-SCOPE-CONTRACT cycle (2026-09-20).
     *
     * Reference: SH_VAR_SCOPE_CONTRACT.md §7 (multi-line + raw triples).
     * Operador's literal-type distinction: in a raw triple-quoted
     * """...""" literal, the backslash does NOT escape `$`, so
     * \$USER produces the literal two bytes `\` `$` followed by `USER`.
     * The author-visible safe form inside a raw triple is
     * `${'$'}USER`, which compiles to `$USER` regardless of outer
     * literal flavour.
     *
     * Per Guard G2 (verbatim, 2026-09-20T08:16Z):
     *   "bytes, not aspect". These probes verify the actual byte
     *   sequence at four measurable layers (literal type, post-Kotlin-
     *   compile bytes, bytes shell receives, bash expansion semantics).
     *   ScriptTextEscaper MUST remain untouched during F1.
     *
     * Each fixture is built by String concatenation so the bytes we pass
     * to the script compiler are exactly the bytes a hand-written
     * .pipeline.kts file would contain. dq() yields a single double-quote
     * character without the Kotlin compiler of the test source trying to
     * interpret a string boundary where the raw triple sits.
     */
    @Test
    fun `probe Form F raw triple-quoted literal all three phases`() {
        // Form F1: Kotlin escape inside raw triple.
        // Kotlin source:  sh("""echo user=\${USER}""")
        //   - In an ordinary "...": \$ is escape, ${USER} is literal.
        //   - In a raw """...""" : \$ is two literal bytes; ${USER}
        //     is NOT compiled as a template (the $ escapes because \$ is
        //     still not a Kotlin escape in raw triples — actually the
        //     Kotlin reference 1.9+ does handle \\$ inside raw strings
        //     as a non-template escape). Phase 3 measures the truth.
        val fF1 = "pipeline { stages { stage(\"s\") { sh(" + dq() + dq() + dq() + "echo user=" + bsl() + dollar() + lc() + "USER" + rc() + dq() + dq() + dq() + ") } } }"
        phase1_3("F1_kotlin_escape_in_raw", fF1)

        // Form F2: the safe form ${'$'}USER inside raw triple.
        // Kotlin source:  sh("""echo user=${'$'}USER""")
        //   - Kotlin compiles ${'$'} -> literal `$`; adjacent USER is text.
        //   - Compiled string contains the 5 bytes: $USER.
        //   - bash sees `$USER` and expands from env (or treated as literal
        //     if USER is not in env).
        val fF2 = "pipeline { stages { stage(\"s\") { sh(" + dq() + dq() + dq() + "echo user=" + dollar() + lc() + "'" + dollar() + "'" + rc() + "USER" + dq() + dq() + dq() + ") } } }"
        phase1_3("F2_safe_form_in_raw", fF2)

        // Form F3: Kotlin-style \$ escape inside raw triple.
        // Kotlin source:  sh("""echo user=\$USER""")
        //   - In a raw triple, the `\` is NOT an escape; Kotlin compiles
        //     the literal two bytes `\` `$` followed by `USER`.
        //   - bash receives `\$USER` (6 chars) which it treats as text
        //     (no expansion). This is the operator's literal-type trap:
        //     the same bytes that are safe in "..." become unsafe in
        //     """...""" .
        val fF3 = "pipeline { stages { stage(\"s\") { sh(" + dq() + dq() + dq() + "echo user=" + bsl() + dollar() + "USER" + dq() + dq() + dq() + ") } } }"
        phase1_3("F3_kotlin_escape_trap_in_raw", fF3)
    }
}
