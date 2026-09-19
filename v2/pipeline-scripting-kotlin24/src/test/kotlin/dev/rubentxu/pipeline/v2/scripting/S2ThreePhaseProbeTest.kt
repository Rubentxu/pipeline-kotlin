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
}
