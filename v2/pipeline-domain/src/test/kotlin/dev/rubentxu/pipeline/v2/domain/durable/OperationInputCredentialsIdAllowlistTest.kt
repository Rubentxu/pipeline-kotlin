package dev.rubentxu.pipeline.v2.domain.durable

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import kotlinx.serialization.json.JsonPrimitive

/**
 * Tests for OperationInput ALLOWED_KEYS allowlist.
 *
 * Scenario CR-BD-015: credentialsId is allowed in params
 * (SECRET_PATTERNS substring-matches "credential" and would false-positive)
 */
@DisplayName("OperationInput ALLOWED_KEYS tests")
class OperationInputCredentialsIdAllowlistTest {

    @Test
    fun `credentialsId is allowed in params despite matching credential pattern`() {
        // "credential" substring matches SECRET_PATTERNS
        // ALLOWED_KEYS must prevent false-positive
        val params = mapOf(
            "credentialsId" to JsonPrimitive("github-token"),
            "purpose" to JsonPrimitive("API_KEY"),
        )

        // Should NOT throw - credentialsId is explicitly allowed
        val input = OperationInput(
            stepId = "withCredentials-1",
            params = params,
            runId = "run-123",
            attempt = 1,
        )

        // JsonPrimitive.content returns the unwrapped string value
        assertEquals("github-token", (input.params["credentialsId"] as JsonPrimitive).content)
    }

    @Test
    fun `other secret-like keys are still rejected`() {
        val params = mapOf(
            "credentialsId" to JsonPrimitive("github-token"),
            "password" to JsonPrimitive("secret123"),  // "password" in SECRET_PATTERNS
        )

        assertThrows(IllegalArgumentException::class.java) {
            OperationInput(
                stepId = "withCredentials-2",
                params = params,
                runId = "run-123",
                attempt = 1,
            )
        }
    }

    @Test
    fun `secret key pattern still blocks non-allowed secret-like keys`() {
        val params = mapOf(
            "api_key_secret" to JsonPrimitive("value"),  // contains "secret"
        )

        assertThrows(IllegalArgumentException::class.java) {
            OperationInput(
                stepId = "withCredentials-3",
                params = params,
                runId = "run-123",
                attempt = 1,
            )
        }
    }
}
