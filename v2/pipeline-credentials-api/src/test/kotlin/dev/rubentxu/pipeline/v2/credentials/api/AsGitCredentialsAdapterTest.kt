package dev.rubentxu.pipeline.v2.credentials.api

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.scm.GitCredentials
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Tests for CredentialsBinding.asGitCredentials().
 */
class AsGitCredentialsAdapterTest {

    @Test
    fun `string binding yields string carrier`() {
        val binding = CredentialsBinding.string(CredentialsId("my-api-key"), "API_KEY")
        val gitCreds = binding.asGitCredentials()

        assertNotNull(gitCreds.string, "GitCredentials must have string carrier")
        assertNull(gitCreds.user, "GitCredentials must not have user carrier")
        assertNull(gitCreds.pass, "GitCredentials must not have pass carrier")
        assertEquals("my-api-key", gitCreds.string?.id?.value)
    }

    @Test
    fun `usernamePassword binding yields user and pass carriers`() {
        val binding = CredentialsBinding.usernamePassword(
            CredentialsId("my-user-pass"),
            "USERNAME",
            "PASSWORD"
        )
        val gitCreds = binding.asGitCredentials()

        assertNull(gitCreds.string, "GitCredentials must not have string carrier")
        assertNotNull(gitCreds.user, "GitCredentials must have user carrier")
        assertNotNull(gitCreds.pass, "GitCredentials must have pass carrier")
        assertEquals("my-user-pass", gitCreds.user?.id?.value)
        assertEquals("my-user-pass", gitCreds.pass?.id?.value)
    }

    @Test
    fun `asGitCredentials round-trip through equals`() {
        val binding = CredentialsBinding.string(CredentialsId("test"), "VAR")
        val gitCreds1 = binding.asGitCredentials()
        val gitCreds2 = binding.asGitCredentials()

        assertEquals(gitCreds1, gitCreds2, "Round-trip must preserve equality")
    }
}
