package dev.rubentxu.pipeline.context.managers

import dev.rubentxu.pipeline.context.managers.interfaces.*
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class DefaultSecretManagerTest {
    private val envManager = mockk<IEnvironmentManager>()
    private val secretManager = DefaultSecretManager(envManager)

    @Test
    fun `should bind and retrieve plain text secret`() = runTest {
        // Arrange
        val secretId = "API_KEY"
        val secretValue = PlainTextSecret("s3cr3t")
        every { envManager.set(any(), any()) } returns Unit
        every { envManager.remove(any()) } returns Unit

        // Act
        secretManager.bind(secretId, secretValue)
        val retrieved = secretManager.get(secretId, PlainTextSecret::class)

        // Assert
        assertEquals(secretValue, retrieved)
        verify(exactly = 1) { envManager.set(secretId, "s3cr3t") }
    }

    @Test
    fun `should throw MismatchedSecretException on type mismatch`() = runTest {
        // Arrange
        val secretId = "SSH_KEY"
        val secretValue = SshUserPrivateKey("key-data", "passphrase")
        every { envManager.set(any(), any()) } returns Unit
        every { envManager.remove(any()) } returns Unit
        secretManager.bind(secretId, secretValue)

        // Act & Assert
        val exception = assertThrows(MismatchedSecretException::class.java) {
            secretManager.get(secretId, AwsCredentials::class)
        }
        assertTrue(exception.message!!.contains("SshUserPrivateKey") && exception.message!!.contains("AwsCredentials"))
    }

    @Test
    fun `should inject and cleanup environment variables`() = runTest {
        // Arrange
        val secretId = "DB_PASSWORD"
        val secretValue = PlainTextSecret("db_pass")
        every { envManager.set(secretId, "db_pass") } returns Unit
        every { envManager.remove(secretId) } returns Unit

        // Act
        secretManager.bind(secretId, secretValue)
        secretManager.unbind(secretId)

        // Assert
        verify(exactly = 1) { envManager.set(secretId, "db_pass") }
        verify(exactly = 1) { envManager.remove(secretId) }
    }

    @Test
    fun `should handle username-password credentials`() = runTest {
        // Arrange
        val secretId = "DB_CREDS"
        val secretValue = UsernamePasswordCredentials("admin", "s3cr3t")
        every { envManager.set("${secretId}_USERNAME", "admin") } returns Unit
        every { envManager.set("${secretId}_PASSWORD", "s3cr3t") } returns Unit
        every { envManager.remove("${secretId}_USERNAME") } returns Unit
        every { envManager.remove("${secretId}_PASSWORD") } returns Unit

        // Act
        secretManager.bind(secretId, secretValue)
        val retrieved = secretManager.get(secretId, UsernamePasswordCredentials::class)

        // Assert
        assertEquals(secretValue, retrieved)
        verify(exactly = 1) { envManager.set("${secretId}_USERNAME", "admin") }
        verify(exactly = 1) { envManager.set("${secretId}_PASSWORD", "s3cr3t") }
        
        // Cleanup verification
        secretManager.unbind(secretId)
        verify(exactly = 1) { envManager.remove("${secretId}_USERNAME") }
        verify(exactly = 1) { envManager.remove("${secretId}_PASSWORD") }
    }

    // Additional test cases for all credential types
}
