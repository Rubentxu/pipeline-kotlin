package dev.rubentxu.pipeline.v2.scripting

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * Integration tests for withCredentials env-var injection.
 *
 * Script text is constructed using StringBuilder to produce literal $ strings
 * that the Kotlin scripting host can compile.
 */
@Timeout(120)
class WithCredentialsCompileIntegrationTest {

    private val scriptingHost: Kotlin24ScriptingHost = Kotlin24ScriptingHost()

    private val domainJar = "/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin/v2/pipeline-domain/build/libs/pipeline-domain-0.1.0-SNAPSHOT.jar"
    private val dslJar: String? = ScriptDefinition.dslApiJar()

    private fun fullClasspath(): List<String> = buildList {
        add(domainJar)
        if (dslJar != null) add(dslJar)
    }

    // Build a script string with literal $ using StringBuilder
    private fun buildScript(inner: String): String {
        val sb = StringBuilder()
        // No leading $ - just the block content
        sb.append("{ ")
        sb.append(inner)
        sb.append(" }")
        return sb.toString()
    }

    // Append a $VAR reference to StringBuilder
    private fun appendDollarVar(sb: StringBuilder, name: String) {
        sb.append('$')
        sb.append(name)
    }

    // -------------------------------------------------------------------------
    // IT-001: Simple withCredentials + sh("$U_P") — compilation succeeds
    // -------------------------------------------------------------------------

    @Test
    fun `IT-001 simple usernameColonPassword binding compiles and returns success`() {
        val sb = StringBuilder()
        sb.append("{ withCredentials(StepSpec.CredentialsBinding.usernameColonPassword(\"test-creds\", \"USER_PASS\")) { sh(\"echo credentials_username_colon_password=")
        appendDollarVar(sb, "USER_PASS")
        sb.append("\") } }")
        val scriptText = sb.toString()

        val definition = ScriptDefinition.inline(text = scriptText, classpath = fullClasspath())
        val result = scriptingHost.compile(definition)

        assertTrue(result.isSuccess, "Compilation should succeed. Diagnostics: ${result.diagnostics}")
        assertNotNull(result.value, "Script instance should be created")
    }

    // -------------------------------------------------------------------------
    // IT-002: Multiple bindings in single withCredentials block
    // -------------------------------------------------------------------------

    @Test
    fun `IT-002 multiple bindings in single withCredentials compile successfully`() {
        val sb = StringBuilder()
        sb.append("{ withCredentials(")
        sb.append("StepSpec.CredentialsBinding.usernamePassword(\"creds1\", \"USR\", \"PWD\"), ")
        sb.append("StepSpec.CredentialsBinding.file(\"creds2\", \"SECRET_FILE\")")
        sb.append(") { sh(\"echo user=")
        appendDollarVar(sb, "USR")
        sb.append(" file=")
        appendDollarVar(sb, "SECRET_FILE")
        sb.append("\") } }")
        val scriptText = sb.toString()

        val definition = ScriptDefinition.inline(text = scriptText, classpath = fullClasspath())
        val result = scriptingHost.compile(definition)

        assertTrue(result.isSuccess, "Compilation should succeed. Diagnostics: ${result.diagnostics}")
        assertNotNull(result.value)
    }

    // -------------------------------------------------------------------------
    // IT-003: Mixed file-based and non-file-based bindings
    // -------------------------------------------------------------------------

    @Test
    fun `IT-003 mixed file and credential bindings compile successfully`() {
        val sb = StringBuilder()
        sb.append("{ withCredentials(")
        sb.append("StepSpec.CredentialsBinding.usernamePassword(\"un\", \"U_NAME\", \"U_PASS\"), ")
        sb.append("StepSpec.CredentialsBinding.sshUserPrivateKey(\"ssh\", \"SSH_KEY\"), ")
        sb.append("StepSpec.CredentialsBinding.file(\"file-creds\", \"MY_FILE_PATH\")")
        sb.append(") { sh(\"echo user=")
        appendDollarVar(sb, "U_NAME")
        sb.append(" ssh=")
        appendDollarVar(sb, "SSH_KEY")
        sb.append(" file=")
        appendDollarVar(sb, "MY_FILE_PATH")
        sb.append("\") } }")
        val scriptText = sb.toString()

        val definition = ScriptDefinition.inline(text = scriptText, classpath = fullClasspath())
        val result = scriptingHost.compile(definition)

        assertTrue(result.isSuccess, "Compilation should succeed. Diagnostics: ${result.diagnostics}")
        assertNotNull(result.value)
    }

    // -------------------------------------------------------------------------
    // IT-004: Cache key is stable (compiling same script twice = same key)
    // -------------------------------------------------------------------------

    @Test
    fun `IT-004 cache key is identical between two identical compilations`() {
        val sb1 = StringBuilder()
        sb1.append("{ withCredentials(StepSpec.CredentialsBinding.usernameColonPassword(\"tc\", \"UP\")) { sh(\"echo ")
        appendDollarVar(sb1, "UP")
        sb1.append("\") } }")
        val script1 = sb1.toString()

        val def1 = ScriptDefinition.inline(text = script1, classpath = fullClasspath())
        val result1 = scriptingHost.compile(def1)
        val key1 = result1.cacheKey

        val def2 = ScriptDefinition.inline(text = script1, classpath = fullClasspath())
        val result2 = scriptingHost.compile(def2)
        val key2 = result2.cacheKey

        assertTrue(key1.value == key2.value,
            "Cache key must be identical for identical script text")
    }

    // -------------------------------------------------------------------------
    // IT-005: Different script texts produce different cache keys
    // -------------------------------------------------------------------------

    @Test
    fun `IT-005 different script texts produce different cache keys`() {
        val sb1 = StringBuilder()
        sb1.append("{ withCredentials(StepSpec.CredentialsBinding.usernameColonPassword(\"tc1\", \"UP1\")) { sh(\"echo ")
        appendDollarVar(sb1, "UP1")
        sb1.append("\") } }")
        val script1 = sb1.toString()

        val sb2 = StringBuilder()
        sb2.append("{ withCredentials(StepSpec.CredentialsBinding.usernameColonPassword(\"tc2\", \"UP2\")) { sh(\"echo ")
        appendDollarVar(sb2, "UP2")
        sb2.append("\") } }")
        val script2 = sb2.toString()

        val def1 = ScriptDefinition.inline(text = script1, classpath = fullClasspath())
        val def2 = ScriptDefinition.inline(text = script2, classpath = fullClasspath())

        val result1 = scriptingHost.compile(def1)
        val result2 = scriptingHost.compile(def2)

        assertTrue(result1.cacheKey.value != result2.cacheKey.value,
            "Different script texts should produce different cache keys")
    }

    // -------------------------------------------------------------------------
    // IT-006: zip factory bindings compile successfully
    // -------------------------------------------------------------------------

    @Test
    fun `IT-006 zip factory binding compiles successfully`() {
        val sb = StringBuilder()
        sb.append("{ withCredentials(StepSpec.CredentialsBinding.zip(\"zip-creds\", \"ARCHIVE_PATH\", \"ARCHIVE_PASS\")) { sh(\"echo zip=")
        appendDollarVar(sb, "ARCHIVE_PATH")
        sb.append(" pass=")
        appendDollarVar(sb, "ARCHIVE_PASS")
        sb.append("\") } }")
        val scriptText = sb.toString()

        val definition = ScriptDefinition.inline(text = scriptText, classpath = fullClasspath())
        val result = scriptingHost.compile(definition)

        assertTrue(result.isSuccess, "Compilation should succeed. Diagnostics: ${result.diagnostics}")
        assertNotNull(result.value)
    }
}
