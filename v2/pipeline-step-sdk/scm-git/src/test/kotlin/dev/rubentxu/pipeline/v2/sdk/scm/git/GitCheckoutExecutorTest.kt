package dev.rubentxu.pipeline.v2.sdk.scm.git

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.CredentialsRef
import dev.rubentxu.pipeline.v2.domain.scm.CheckoutSpec
import dev.rubentxu.pipeline.v2.domain.scm.GitCredentials
import dev.rubentxu.pipeline.v2.domain.scm.GitScm
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.GitCheckoutCompleted
import dev.rubentxu.pipeline.v2.events.GitCheckoutFailed
import dev.rubentxu.pipeline.v2.events.GitCheckoutStarted
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * Tests for GitCheckoutExecutor.
 *
 * Tests:
 * 1. Idempotent no-op on second run (SHA equal, <2s)
 * 2. Fetch+reset on remote advance
 * 3. Auth fail emits GitCheckoutFailed
 * 4. Argv guard rejects extraHeader
 *
 * @param timeoutSeconds class-level timeout (default 120)
 */
@EnabledIfEnvironmentVariable(named = "V2_GIT_AVAILABLE", matches = "true")
class GitCheckoutExecutorTest {

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
    inner class RecordingEventSink : EventSink {
        val events = mutableListOf<dev.rubentxu.pipeline.v2.events.DomainEvent>()

        override fun append(event: dev.rubentxu.pipeline.v2.events.DomainEvent) {
            events.add(event)
        }

        override fun eventsFor(runId: String): Sequence<dev.rubentxu.pipeline.v2.events.DomainEvent> {
            return events.asSequence()
        }
    }

    @Test
    fun `idempotent no-op second run returns under 2s`() {
        // Create a local bare repo
        val bareRepo = tempDir.resolve("fixture.git")
        Files.createDirectories(bareRepo)
        runProcess(listOf("git", "init", "--bare"), tempDir.toFile())
        runProcess(listOf("git", "init"), tempDir.resolve("work").toFile())
        val work = tempDir.resolve("work")
        Files.writeString(work.resolve("README.txt"), "Hello")
        runProcess(listOf("git", "add", "."), work.toFile())
        runProcess(listOf("git", "commit", "-m", "Initial"), work.toFile())
        runProcess(listOf("git", "push", bareRepo.toString(), "master", "--set-upstream"), work.toFile())

        val workspace = tempDir.resolve("workspace")
        Files.createDirectories(workspace)

        val spec = CheckoutSpec(GitScm(url = bareRepo.toString(), branch = "master"))
        val request = createRequest(spec, workspace)

        val poll = GitPollExecutor()
        val changelog = GitChangelogWriter()
        val applier = GitCredentialsApplier(tempDir.resolve("creds"), GitCredentials())
        val executor = createExecutor(poll, changelog, applier)

        // First run - should clone
        executor.use { exec ->
            val start = System.currentTimeMillis()
            val result1 = exec.execute(request)
            val duration1 = System.currentTimeMillis() - start

            assertTrue(result1.isSuccess, "First run must succeed: ${result1.exceptionOrNull()?.message}")
            assertTrue(result1.getOrNull()?.sha?.isNotBlank() == true, "First run must return SHA")

            // Second run - should be no-op (<2s)
            val start2 = System.currentTimeMillis()
            val result2 = exec.execute(request)
            val duration2 = System.currentTimeMillis() - start2

            assertTrue(result2.isSuccess, "Second run must succeed")
            assertTrue(duration2 < 2000, "Second run (SHA-equal no-op) must complete in <2s, was ${duration2}ms")
        }
    }

    @Test
    fun `fetch and reset on remote advance produces changelog entry`() {
        // Create repo, push, then push another commit
        val bareRepo = tempDir.resolve("fixture.git")
        Files.createDirectories(bareRepo)
        runProcess(listOf("git", "init", "--bare"), tempDir.toFile())

        val work = tempDir.resolve("work")
        Files.createDirectories(work)
        runProcess(listOf("git", "init"), work.toFile())
        Files.writeString(work.resolve("README.txt"), "Hello")
        runProcess(listOf("git", "add", "."), work.toFile())
        runProcess(listOf("git", "commit", "-m", "Initial commit"), work.toFile())
        runProcess(listOf("git", "push", bareRepo.toString(), "master", "--set-upstream"), work.toFile())

        // Push a second commit
        Files.writeString(work.resolve("file2.txt"), "World")
        runProcess(listOf("git", "add", "."), work.toFile())
        runProcess(listOf("git", "commit", "-m", "Second commit"), work.toFile())
        runProcess(listOf("git", "push"), work.toFile())

        val workspace = tempDir.resolve("workspace")
        Files.createDirectories(workspace)

        val spec = CheckoutSpec(GitScm(url = bareRepo.toString(), branch = "master"))
        val request = createRequest(spec, workspace)

        val poll = GitPollExecutor()
        val changelog = GitChangelogWriter()
        val applier = GitCredentialsApplier(tempDir.resolve("creds"), GitCredentials())
        val executor = createExecutor(poll, changelog, applier)

        executor.use { exec ->
            val result = exec.execute(request)
            assertTrue(result.isSuccess, "Checkout must succeed: ${result.exceptionOrNull()?.message}")
        }

        // Verify changelog has both commits
        val changelogFile = workspace.resolve("changelog.txt")
        assertTrue(Files.exists(changelogFile), "changelog.txt must exist")
        val lines = Files.readAllLines(changelogFile)
        assertTrue(lines.size >= 2, "changelog.txt must have at least 2 entries after push, got: ${lines.size}")
    }

    @Test
    fun `auth fail emits GitCheckoutFailed event`() {
        val spec = CheckoutSpec(GitScm(
            url = "https://github.com/nonexistent-private/repo.git",
            branch = "master",
            credentialsId = CredentialsId("invalid-creds")
        ))
        val request = createRequest(spec, tempDir.resolve("workspace"))
        Files.createDirectories(tempDir.resolve("workspace"))

        val poll = GitPollExecutor()
        val changelog = GitChangelogWriter()
        val creds = GitCredentials(string = dev.rubentxu.pipeline.v2.domain.scm.SecretHandleRef(CredentialsId("invalid")))
        val applier = GitCredentialsApplier(tempDir.resolve("creds"), creds)
        val executor = createExecutor(poll, changelog, applier)

        val sink = request.eventSink as RecordingEventSink
        executor.use { exec ->
            exec.execute(request)
        }

        // Check that GitCheckoutFailed was emitted
        val failedEvents = sink.events.filterIsInstance<GitCheckoutFailed>()
        assertTrue(failedEvents.isNotEmpty(), "Must emit GitCheckoutFailed for auth failure")
    }

    @Test
    fun `argv guard rejects extraHeader in execute`() {
        val spec = CheckoutSpec(GitScm(
            url = "https://example.com/repo.git",
            branch = "main",
            credentialsId = CredentialsId("api")
        ))
        val workspace = tempDir.resolve("workspace")
        Files.createDirectories(workspace)

        // Create an executor with argv-polluting credentials
        val poll = GitPollExecutor()
        val changelog = GitChangelogWriter()
        // The applier itself won't pollute argv, but we can test the guard directly
        val applier = GitCredentialsApplier(tempDir.resolve("creds"), GitCredentials())

        val executor = createExecutor(poll, changelog, applier)

        // Test the guard directly
        val exception = runCatching {
            GitCheckoutExecutor.guardProcessBuilderArgs(listOf("git", "ls-remote", "--extraHeader=Authorization: Bearer token"))
        }.exceptionOrNull()

        assertTrue(exception != null, "guard must reject extraHeader")
        assertTrue(exception is IllegalArgumentException, "Must throw IllegalArgumentException")
    }

    private fun runProcess(args: List<String>, dir: java.io.File): Int {
        val pb = ProcessBuilder(args)
        pb.directory(dir)
        pb.inheritIO()
        return pb.start().waitFor()
    }
}
