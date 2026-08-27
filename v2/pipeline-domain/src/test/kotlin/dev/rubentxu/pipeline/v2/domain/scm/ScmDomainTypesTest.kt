package dev.rubentxu.pipeline.v2.domain.scm

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.superclasses

class ScmDomainTypesTest {

    @Test
    fun `GitScm is the only direct subtype of Scm`() {
        // GitScm must be a direct subtype of Scm sealed interface
        val scmSubtypes = Scm::class.sealedSubclasses
        assertTrue(scmSubtypes.isNotEmpty(), "Scm must have at least one sealed subclass")

        // GitScm must be among the sealed subclasses
        val gitScmClass = scmSubtypes.find { it.simpleName == "GitScm" }
        assertNotNull(gitScmClass, "GitScm must be a sealed subclass of Scm")

        // Only GitScm should be a direct Scm subtype at L5 (SubversionScm/GithubScm deferred)
        assertEquals(1, scmSubtypes.size, "Only GitScm should be a direct Scm subtype at L5")
    }

    @Test
    fun `GitScm data class equality and default values`() {
        val credId = CredentialsId("test-creds")
        val gitScm = GitScm(
            url = "https://example.com/repo.git",
            branch = "main",
            credentialsId = credId,
            changelog = true,
            poll = true,
            relativeTargetDir = "."
        )

        // Test equality
        val gitScm2 = GitScm(
            url = "https://example.com/repo.git",
            branch = "main",
            credentialsId = credId,
            changelog = true,
            poll = true,
            relativeTargetDir = "."
        )
        assertEquals(gitScm, gitScm2, "GitScm instances with same params must be equal")

        // Test hashCode
        assertEquals(gitScm.hashCode(), gitScm2.hashCode())

        // Test default values
        val gitScmDefault = GitScm(url = "https://example.com/repo.git")
        assertEquals("master", gitScmDefault.branch, "Default branch must be 'master'")
        assertNull(gitScmDefault.credentialsId, "Default credentialsId must be null")
        assertEquals(true, gitScmDefault.changelog, "Default changelog must be true")
        assertEquals(true, gitScmDefault.poll, "Default poll must be true")
        assertEquals(".", gitScmDefault.relativeTargetDir, "Default relativeTargetDir must be '.'")
    }

    @Test
    fun `GitCredentials null round-trips through equals hashCode`() {
        val creds1 = GitCredentials(string = null, user = null, pass = null)
        val creds2 = GitCredentials(string = null, user = null, pass = null)

        assertEquals(creds1, creds2, "GitCredentials null instances must be equal")
        assertEquals(creds1.hashCode(), creds2.hashCode())
    }

    @Test
    fun `SecretHandleRef is the only credentials carrier - no Map String String in Scm kt`() {
        // This is a grep-gate: Scm.kt must NOT contain Map<String,String> token
        // We read the source file and scan for the forbidden pattern
        val scmFile = Path.of("v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/scm/Scm.kt")
        if (Files.exists(scmFile)) {
            val content = Files.readString(scmFile)
            val hasMapStringString = content.contains("Map<String,String>") ||
                                      content.contains("Map<String, String>") ||
                                      content.contains("mapOf<String,")
            assertTrue(!hasMapStringString, "Scm.kt must NOT use Map<String,String> - use SecretHandleRef instead")
        }
    }

    @Test
    fun `CheckoutSpec wraps GitScm correctly`() {
        val gitScm = GitScm(url = "https://example.com/repo.git", branch = "main")
        val checkoutSpec = CheckoutSpec(gitScm)

        assertEquals(gitScm, checkoutSpec.scm, "CheckoutSpec must wrap the provided Scm")
    }

    @Test
    fun `SecretHandleRef carries id and kind`() {
        val credId = CredentialsId("my-secret-id")
        val ref = SecretHandleRef(credId, "string")

        assertEquals(credId, ref.id, "SecretHandleRef id must match")
        assertEquals("string", ref.kind, "SecretHandleRef kind must match")
    }

    @Test
    fun `SecretHandleRef without kind is allowed`() {
        val credId = CredentialsId("my-secret-id")
        val ref = SecretHandleRef(credId)

        assertEquals(credId, ref.id, "SecretHandleRef id must match")
        assertNull(ref.kind, "SecretHandleRef kind must be null when not specified")
    }
}
