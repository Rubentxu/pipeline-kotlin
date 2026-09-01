package dev.rubentxu.pipeline.v2.scripting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * Unit tests for [EnvVarNameExtractor].
 * Uses StringBuilder to construct script strings with literal $ characters.
 */
@Timeout(60)
class EnvVarNameExtractorTest {

    companion object {
        // Build a script string with literal $VAR using StringBuilder
        private fun makeScript(inner: String): String {
            val sb = StringBuilder()
            sb.append("{ ")
            sb.append(inner)
            sb.append(" }")
            return sb.toString()
        }

        private fun withCreds(bindingCall: String): String =
            makeScript("withCredentials(StepSpec.CredentialsBinding.$bindingCall) { sh(\"echo inside\") }")
    }

    @Test
    fun `usernamePassword extracts US and PW env vars`() {
        val s = withCreds("""usernamePassword("cid", "US", "PW")""")
        assertEquals(setOf("US", "PW"), EnvVarNameExtractor.extract(s))
    }

    @Test
    fun `usernameColonPassword extracts single env var`() {
        val s = withCreds("""usernameColonPassword("cid", "USER_PASS")""")
        assertEquals(setOf("USER_PASS"), EnvVarNameExtractor.extract(s))
    }

    @Test
    fun `sshUserPrivateKey extracts SSH_KEY_FILE env var`() {
        val s = withCreds("""sshUserPrivateKey("cid", "SSH_KEY_FILE")""")
        assertEquals(setOf("SSH_KEY_FILE"), EnvVarNameExtractor.extract(s))
    }

    @Test
    fun `sshUsernamePrivateKey extracts SSH_KEY_USR and SSH_KEY_FILE`() {
        val s = withCreds("""sshUsernamePrivateKey("cid", "SSH_KEY_USR", "SSH_KEY_FILE")""")
        assertEquals(setOf("SSH_KEY_USR", "SSH_KEY_FILE"), EnvVarNameExtractor.extract(s))
    }

    @Test
    fun `file extracts FILE_PATH env var`() {
        val s = withCreds("""file("cid", "FILE_PATH")""")
        assertEquals(setOf("FILE_PATH"), EnvVarNameExtractor.extract(s))
    }

    @Test
    fun `certificate extracts CERT_FILE env var`() {
        val s = withCreds("""certificate("cid", "CERT_FILE")""")
        assertEquals(setOf("CERT_FILE"), EnvVarNameExtractor.extract(s))
    }

    @Test
    fun `zip extracts ZIP_FILE and ZIP_PASS env vars`() {
        val s = withCreds("""zip("cid", "ZIP_FILE", "ZIP_PASS")""")
        assertEquals(setOf("ZIP_FILE", "ZIP_PASS"), EnvVarNameExtractor.extract(s))
    }

    @Test
    fun `multiple factories in single withCredentials block are all extracted`() {
        val s = makeScript("""withCredentials(StepSpec.CredentialsBinding.usernamePassword("c1", "U1", "P1"), StepSpec.CredentialsBinding.usernameColonPassword("c2", "UP2"), StepSpec.CredentialsBinding.file("c3", "FP3")) { sh("echo inside") }""")
        assertEquals(setOf("U1", "P1", "UP2", "FP3"), EnvVarNameExtractor.extract(s))
    }

    @Test
    fun `empty script returns empty set`() {
        assertTrue(EnvVarNameExtractor.extract("").isEmpty())
    }

    @Test
    fun `script without withCredentials returns empty set`() {
        val s = makeScript("pipeline { stages { stage(\"s\") { sh(\"echo hello\") } }")
        assertTrue(EnvVarNameExtractor.extract(s).isEmpty())
    }

    @Test
    fun `duplicated env var names are deduplicated`() {
        val s = makeScript("""withCredentials(StepSpec.CredentialsBinding.usernamePassword("c1", "USR", "PWD"), StepSpec.CredentialsBinding.usernameColonPassword("c2", "USR")) { sh("echo inside") }""")
        assertEquals(setOf("USR", "PWD"), EnvVarNameExtractor.extract(s))
    }
}
