package dev.rubentxu.pipeline.v2.sdk.scm.git

import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.scm.CheckoutSpec
import dev.rubentxu.pipeline.v2.domain.scm.GitCredentials
import dev.rubentxu.pipeline.v2.domain.scm.GitScm
import dev.rubentxu.pipeline.v2.events.GitCheckoutFailed
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * Fold-in GIT-CHK scenarios for T-12.
 *
 * Tests:
 * - GIT-CHK-009: auth-fail taxonomy
 * - GIT-CHK-010: network-fail classification
 * - GIT-CHK-012: invalid branch
 * - GIT-CHK-013: provider-agnostic non-GitHub host
 *
 * @see <a href="ADR-0051">ADR-0051 — ML-R6 credentials parity</a>
 */
@EnabledIfEnvironmentVariable(named = "V2_NETWORK_OK", matches = "true")
class FoldInGitChkTest {

    @TempDir
    lateinit var tempDir: Path

    private val fixedClock = Clock.fixed(
        Instant.parse("2026-08-27T10:00:00Z"),
        ZoneId.of("UTC")
    )

    private fun createExecutor(poll: GitPollExecutor, changelog: GitChangelogWriter, applier: GitCredentialsApplier): GitCheckoutExecutor {
        return GitCheckoutExecutor(poll, changelog, applier, fixedClock)
    }

    private fun createRequest(spec: CheckoutSpec, workspace: Path): GitCheckoutRequest {
        return GitCheckoutRequest(
            spec = spec,
            runId = "test-run",
            workspaceRoot = workspace,
            eventSink = RecordingEventSink(),
            clock = fixedClock,
            secretStore = null,
            stepIndex = 0,
            previousRemoteSha = null
        )
    }

    /**
     * Record all events emitted during checkout.
     */
    inner class RecordingEventSink : dev.rubentxu.pipeline.v2.events.EventSink {
        val events = mutableListOf<dev.rubentxu.pipeline.v2.events.DomainEvent>()

        override fun append(event: dev.rubentxu.pipeline.v2.events.DomainEvent) {
            events.add(event)
        }

        override fun eventsFor(runId: String): Sequence<dev.rubentxu.pipeline.v2.events.DomainEvent> {
            return events.asSequence()
        }
    }

    /**
     * GIT-CHK-009: Authentication failure taxonomy.
     *
     * A git clone with invalid credentials should emit GitCheckoutFailed with
     * reason containing "Authentication failed" (matching Jenkins classification).
     * The reason must NOT contain the canary secret.
     */
    @Test
    fun `GIT_CHK_009_auth_fail_taxonomy`() {
        val spec = CheckoutSpec(GitScm(
            url = "https://bogus:wrong@example.com/repo.git",
            branch = "master",
            credentialsId = CredentialsId("invalid-creds")
        ))
        val workspace = tempDir.resolve("workspace")
        workspace.toFile().mkdirs()

        val poll = GitPollExecutor()
        val changelog = GitChangelogWriter()
        val creds = GitCredentials(string = dev.rubentxu.pipeline.v2.domain.scm.SecretHandleRef(CredentialsId("invalid")))
        val applier = GitCredentialsApplier(tempDir.resolve("creds"), creds)
        val executor = createExecutor(poll, changelog, applier)

        val sink = createRequest(spec, workspace).eventSink as RecordingEventSink
        executor.use { exec ->
            exec.execute(createRequest(spec, workspace))
        }

        val failedEvents = sink.events.filterIsInstance<GitCheckoutFailed>()
        assertTrue(failedEvents.isNotEmpty(), "Must emit GitCheckoutFailed for auth failure")

        val reason = failedEvents.first().reason
        // Jenkins classification: "Authentication failed" in reason
        assertTrue(
            reason.contains("Authentication failed") ||
            reason.contains("authentication failed") ||
            reason.contains("Authentic") ||
            reason.contains("401") ||
            reason.contains("403"),
            "GitCheckoutFailed.reason should indicate auth failure, got: $reason"
        )
    }

    /**
     * GIT-CHK-010: Network failure classification.
     *
     * A git clone with an invalid host should emit GitCheckoutFailed with
     * reason containing "Could not resolve host" or similar.
     */
    @Test
    fun `GIT_CHK_010_network_fail_classification`() {
        val spec = CheckoutSpec(GitScm(
            url = "https://this-host-does-not-exist-12345.invalid/repo.git",
            branch = "master",
            credentialsId = null
        ))
        val workspace = tempDir.resolve("workspace")
        workspace.toFile().mkdirs()

        val poll = GitPollExecutor()
        val changelog = GitChangelogWriter()
        val creds = GitCredentials()
        val applier = GitCredentialsApplier(tempDir.resolve("creds"), creds)
        val executor = createExecutor(poll, changelog, applier)

        val sink = createRequest(spec, workspace).eventSink as RecordingEventSink
        executor.use { exec ->
            exec.execute(createRequest(spec, workspace))
        }

        val failedEvents = sink.events.filterIsInstance<GitCheckoutFailed>()
        assertTrue(failedEvents.isNotEmpty(), "Must emit GitCheckoutFailed for network failure")

        val reason = failedEvents.first().reason
        assertTrue(
            reason.contains("Could not resolve") ||
            reason.contains("Name or service not known") ||
            reason.contains("No address associated") ||
            reason.contains("failed to resolve") ||
            reason.contains("Network is unreachable"),
            "GitCheckoutFailed.reason should indicate network failure, got: $reason"
        )
    }

    /**
     * GIT-CHK-012: Invalid branch classification.
     *
     * A git clone with a non-existent branch should emit GitCheckoutFailed with
     * reason containing "Remote branch not found" or similar.
     */
    @Test
    fun `GIT_CHK_012_invalid_branch`() {
        // Use a valid but non-existent branch against a real public repo
        val spec = CheckoutSpec(GitScm(
            url = "https://github.com/some/public-repo.git",
            branch = "nonexistent-branch-xyz123",
            credentialsId = null
        ))
        val workspace = tempDir.resolve("workspace")
        workspace.toFile().mkdirs()

        val poll = GitPollExecutor()
        val changelog = GitChangelogWriter()
        val creds = GitCredentials()
        val applier = GitCredentialsApplier(tempDir.resolve("creds"), creds)
        val executor = createExecutor(poll, changelog, applier)

        val sink = createRequest(spec, workspace).eventSink as RecordingEventSink
        executor.use { exec ->
            exec.execute(createRequest(spec, workspace))
        }

        val failedEvents = sink.events.filterIsInstance<GitCheckoutFailed>()
        assertTrue(failedEvents.isNotEmpty(), "Must emit GitCheckoutFailed for invalid branch")

        val reason = failedEvents.first().reason
        assertTrue(
            reason.contains("Remote branch not found") ||
            reason.contains("remote ref") ||
            reason.contains("not found") ||
            reason.contains("fatal: couldn't find remote ref"),
            "GitCheckoutFailed.reason should indicate invalid branch, got: $reason"
        )
    }

    /**
     * GIT-CHK-013: Provider-agnostic non-GitHub host.
     *
     * A git clone from a non-GitHub host should work via HTTP helper.
     * This uses a public GitLab repo or similar to verify provider-agnostic behavior.
     * If V2_NETWORK_OK is not set, this test is skipped.
     */
    @Test
    fun `GIT_CHK_013_provider_agnostic_gitlab`() {
        val spec = CheckoutSpec(GitScm(
            url = "https://gitlab.com/gitlab-org/gitlab-foss.git",
            branch = "master",
            credentialsId = null
        ))
        val workspace = tempDir.resolve("workspace")
        workspace.toFile().mkdirs()

        val poll = GitPollExecutor()
        val changelog = GitChangelogWriter()
        val creds = GitCredentials()
        val applier = GitCredentialsApplier(tempDir.resolve("creds"), creds)
        val executor = createExecutor(poll, changelog, applier)

        val sink = createRequest(spec, workspace).eventSink as RecordingEventSink
        executor.use { exec ->
            val result = exec.execute(createRequest(spec, workspace))
            // We expect either success or a non-auth failure (network, etc.)
            // The key is that it should NOT fail due to hardcoded github.com
            if (result.isFailure) {
                val failedEvents = sink.events.filterIsInstance<GitCheckoutFailed>()
                if (failedEvents.isNotEmpty()) {
                    val reason = failedEvents.first().reason
                    // Should NOT fail due to github.com hardcoding
                    assertNotEquals(
                        true,
                        reason.contains("github.com") && reason.contains("extraHeader"),
                        "Must not fail due to hardcoded github.com extraHeader"
                    )
                }
            }
        }
    }
}
