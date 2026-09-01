package dev.rubentxu.pipeline.v2.scripting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * Unit tests for [ScriptTextEscaper].
 * Uses StringBuilder to construct script strings with literal $ characters.
 */
@Timeout(60)
class ScriptTextEscaperTest {

    // Append a $VAR reference to StringBuilder
    private fun appendDollar(sb: StringBuilder, name: String) {
        sb.append('$')
        sb.append(name)
    }

    private fun makeEscapedScript(bindingCall: String, envVars: Set<String>): String {
        val sb = StringBuilder()
        sb.append("{ withCredentials(StepSpec.CredentialsBinding.$bindingCall) { sh(\"echo ")
        for (v in envVars) {
            appendDollar(sb, v)
            sb.append(" ")
        }
        sb.append("\") } }")
        return sb.toString()
    }

    private fun makeExpectedScript(bindingCall: String, envVars: Set<String>): String {
        val sb = StringBuilder()
        sb.append("{ withCredentials(StepSpec.CredentialsBinding.$bindingCall) { sh(\"echo ")
        for (v in envVars) {
            sb.append("\${'$'}")
            sb.append(v)
            sb.append(" ")
        }
        sb.append("\") } }")
        return sb.toString()
    }

    // -------------------------------------------------------------------------
    // SCR-A1: usernamePassword
    // -------------------------------------------------------------------------
    @Test
    fun `SCR-A1 usernamePassword escapes US and PW`() {
        val input = makeEscapedScript("usernamePassword(\"cid\", \"US\", \"PW\")", setOf("US", "PW"))
        val escaped = ScriptTextEscaper.escape(input, setOf("US", "PW"))
        val expected = makeExpectedScript("usernamePassword(\"cid\", \"US\", \"PW\")", setOf("US", "PW"))
        assertEquals(expected, escaped)
    }

    // -------------------------------------------------------------------------
    // SCR-A2: usernameColonPassword
    // -------------------------------------------------------------------------
    @Test
    fun `SCR-A2 usernameColonPassword escapes USER_PASS`() {
        val input = makeEscapedScript("usernameColonPassword(\"cid\", \"USER_PASS\")", setOf("USER_PASS"))
        val escaped = ScriptTextEscaper.escape(input, setOf("USER_PASS"))
        val expected = makeExpectedScript("usernameColonPassword(\"cid\", \"USER_PASS\")", setOf("USER_PASS"))
        assertEquals(expected, escaped)
    }

    // -------------------------------------------------------------------------
    // SCR-A3: sshUserPrivateKey
    // -------------------------------------------------------------------------
    @Test
    fun `SCR-A3 sshUserPrivateKey escapes SSH_KEY_FILE`() {
        val input = makeEscapedScript("sshUserPrivateKey(\"cid\", \"SSH_KEY_FILE\")", setOf("SSH_KEY_FILE"))
        val escaped = ScriptTextEscaper.escape(input, setOf("SSH_KEY_FILE"))
        val expected = makeExpectedScript("sshUserPrivateKey(\"cid\", \"SSH_KEY_FILE\")", setOf("SSH_KEY_FILE"))
        assertEquals(expected, escaped)
    }

    // -------------------------------------------------------------------------
    // SCR-A4: sshUsernamePrivateKey
    // -------------------------------------------------------------------------
    @Test
    fun `SCR-A4 sshUsernamePrivateKey escapes both vars`() {
        val input = makeEscapedScript("sshUsernamePrivateKey(\"cid\", \"SSH_KEY_USR\", \"SSH_KEY_FILE\")", setOf("SSH_KEY_USR", "SSH_KEY_FILE"))
        val escaped = ScriptTextEscaper.escape(input, setOf("SSH_KEY_USR", "SSH_KEY_FILE"))
        val expected = makeExpectedScript("sshUsernamePrivateKey(\"cid\", \"SSH_KEY_USR\", \"SSH_KEY_FILE\")", setOf("SSH_KEY_USR", "SSH_KEY_FILE"))
        assertEquals(expected, escaped)
    }

    // -------------------------------------------------------------------------
    // SCR-A5: file
    // -------------------------------------------------------------------------
    @Test
    fun `SCR-A5 file escapes FILE_PATH`() {
        val input = makeEscapedScript("file(\"cid\", \"FILE_PATH\")", setOf("FILE_PATH"))
        val escaped = ScriptTextEscaper.escape(input, setOf("FILE_PATH"))
        val expected = makeExpectedScript("file(\"cid\", \"FILE_PATH\")", setOf("FILE_PATH"))
        assertEquals(expected, escaped)
    }

    // -------------------------------------------------------------------------
    // SCR-A6: certificate
    // -------------------------------------------------------------------------
    @Test
    fun `SCR-A6 certificate escapes CERT_FILE`() {
        val input = makeEscapedScript("certificate(\"cid\", \"CERT_FILE\")", setOf("CERT_FILE"))
        val escaped = ScriptTextEscaper.escape(input, setOf("CERT_FILE"))
        val expected = makeExpectedScript("certificate(\"cid\", \"CERT_FILE\")", setOf("CERT_FILE"))
        assertEquals(expected, escaped)
    }

    // -------------------------------------------------------------------------
    // SCR-A7: zip
    // -------------------------------------------------------------------------
    @Test
    fun `SCR-A7 zip escapes ZIP_FILE and ZIP_PASS`() {
        val input = makeEscapedScript("zip(\"cid\", \"ZIP_FILE\", \"ZIP_PASS\")", setOf("ZIP_FILE", "ZIP_PASS"))
        val escaped = ScriptTextEscaper.escape(input, setOf("ZIP_FILE", "ZIP_PASS"))
        val expected = makeExpectedScript("zip(\"cid\", \"ZIP_FILE\", \"ZIP_PASS\")", setOf("ZIP_FILE", "ZIP_PASS"))
        assertEquals(expected, escaped)
    }

    // -------------------------------------------------------------------------
    // SCR-N1: Non-matching vars — idempotent
    // -------------------------------------------------------------------------
    @Test
    fun `SCR-N1 variable not in envVars set is left unchanged`() {
        val sb = StringBuilder()
        sb.append("{ sh(\"echo ")
        appendDollar(sb, "UNKNOWN_VAR")
        sb.append("\") }")
        val input = sb.toString()
        val escaped = ScriptTextEscaper.escape(input, setOf("OTHER_VAR"))
        assertEquals(input, escaped)
    }

    @Test
    fun `SCR-N1 empty envVars set returns script unchanged`() {
        val sb = StringBuilder()
        sb.append("{ sh(\"echo ")
        appendDollar(sb, "VAR")
        sb.append("\") }")
        val input = sb.toString()
        val escaped = ScriptTextEscaper.escape(input, emptySet())
        assertEquals(input, escaped)
    }

    // -------------------------------------------------------------------------
    // SCR-N2: Edge cases
    // -------------------------------------------------------------------------
    @Test
    fun `SCR-N2 dollar alone stays unchanged`() {
        val sb = StringBuilder()
        sb.append("{ sh(\"price: ")
        appendDollar(sb, "100")
        sb.append("\") }")
        val input = sb.toString()
        val escaped = ScriptTextEscaper.escape(input, setOf("US"))
        assertEquals(input, escaped)
    }

    @Test
    fun `SCR-N2 var already inside brace template stays unchanged`() {
        // ${'$'}VAR at depth=1 should not be escaped again
        val sb = StringBuilder()
        sb.append("{ sh(\"echo \${'$'}VAR inside braces\") }")
        val input = sb.toString()
        val escaped = ScriptTextEscaper.escape(input, setOf("VAR"))
        assertEquals(input, escaped)
    }

    @Test
    fun `SCR-N2 single quotes string is not processed`() {
        val sb = StringBuilder()
        sb.append("{ val x = '")
        appendDollar(sb, "VAR")
        sb.append("' }")
        val input = sb.toString()
        val escaped = ScriptTextEscaper.escape(input, setOf("VAR"))
        assertEquals(input, escaped)
    }

    @Test
    fun `SCR-N2 line comment is not processed`() {
        val sb = StringBuilder()
        sb.append("// echo ")
        appendDollar(sb, "US")
        sb.append(" and ")
        appendDollar(sb, "PW")
        sb.append("\n{ sh(\"hello\") }")
        val input = sb.toString()
        val escaped = ScriptTextEscaper.escape(input, setOf("US", "PW"))
        assertEquals(input, escaped)
    }

    @Test
    fun `SCR-N2 block comment is not processed`() {
        val sb = StringBuilder()
        sb.append("/* user: ")
        appendDollar(sb, "US")
        sb.append(" pass: ")
        appendDollar(sb, "PW")
        sb.append(" */{ sh(\"hello\") }")
        val input = sb.toString()
        val escaped = ScriptTextEscaper.escape(input, setOf("US", "PW"))
        assertEquals(input, escaped)
    }

    @Test
    fun `SCR-N2 variable at depth 1 inside template string is not escaped`() {
        val sb = StringBuilder()
        sb.append("{ val s = \"prefix \${'$'}VAR suffix\" }")
        val input = sb.toString()
        val escaped = ScriptTextEscaper.escape(input, setOf("VAR"))
        assertEquals(input, escaped)
    }
}
