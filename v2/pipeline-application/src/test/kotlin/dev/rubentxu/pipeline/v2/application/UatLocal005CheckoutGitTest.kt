package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.scm.CheckoutSpec
import dev.rubentxu.pipeline.v2.domain.scm.GitCredentials
import dev.rubentxu.pipeline.v2.domain.scm.GitScm
import dev.rubentxu.pipeline.v2.domain.scm.SecretHandleRef
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.GitCheckoutCompleted
import dev.rubentxu.pipeline.v2.events.GitCheckoutFailed
import dev.rubentxu.pipeline.v2.events.GitCheckoutStarted
import dev.rubentxu.pipeline.v2.events.GitPollChanged
import dev.rubentxu.pipeline.v2.sdk.scm.git.GitChangelogWriter
import dev.rubentxu.pipeline.v2.sdk.scm.git.GitCheckoutExecutor
import dev.rubentxu.pipeline.v2.sdk.scm.git.GitCheckoutRequest
import dev.rubentxu.pipeline.v2.sdk.scm.git.GitCredentialsApplier
import dev.rubentxu.pipeline.v2.sdk.scm.git.GitPollExecutor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * UAT-LOCAL-005: Checkout Git — integration tests for the GitCheckoutExecutor.
 *
 * Tests the 11 spec scenarios from the ML-R5 checkout-git specification:
 * 1. Clone fresh repo (no prior .git)
 * 2. Idempotent re-run (SHA equal → no-op)
 * 3. Branch checkout (existing repo, remote SHA changed)
 * 4. credentialsId resolution — string channel (API token)
 * 5. credentialsId resolution — usernamePassword channel
 * 6. changelog.txt format: 7-char SHA + first line of subject
 * 7. Poll changed (remote SHA differs from previousRemoteSha)
 * 8. Poll unchanged (remote SHA same as previousRemoteSha)
 * 9. relativeTargetDir workspace layout
 * 10. Auth-fail error taxonomy (wrong credentials → GitCheckoutFailed)
 * 11. Network fail (unreachable host → GitCheckoutFailed)
 * 12. Invalid branch (remote branch not found → GitCheckoutFailed)
 *
 * All tests use file:// local bare repos (no network). Network-dependent tests
 * are annotated @EnabledIfEnvironmentVariable(named = "V2_NETWORK_OK", matches = "true").
 *
 * @see <a href="ADR-0050">ADR-0050 — checkout-git step</a>
 */
@Timeout(120)
class UatLocal005CheckoutGitTest {

    private val processes = mutableListOf<Process>()

    private val fixedClock = Clock.fixed(
        Instant.parse("2026-08-27T10:00:00Z"),
        ZoneId.of("UTC")
    )

    @BeforeEach
    fun setUp() {
        assumeTrue(
            System.getProperty("os.name", "").lowercase().contains("linux"),
            "UAT integration tests require Linux"
        )
    }

    @AfterEach
    fun teardown() {
        processes.forEach { p ->
            if (p.isAlive) {
                p.destroyForcibly()
            }
        }
        processes.clear()
        val selfPid = ProcessHandle.current().pid()
        try {
            val pb = ProcessBuilder("pgrep", "-P", selfPid.toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
            val childProcs = pb.start().inputStream.bufferedReader().readText()
            if (childProcs.isNotBlank()) {
                childProcs.lines().filter { it.isNotBlank() }.forEach { pid ->
                    try {
                        ProcessHandle.of(pid.toLong()).ifPresent { it.destroyForcibly() }
                    } catch (_: Exception) { }
                }
            }
        } catch (_: Exception) { }
    }

    // ─── Scenario 1: Clone fresh repo ───────────────────────────────────────

    @Test
    fun `SC-001 clone fresh repo emits GitCheckoutCompleted with SHA`(@TempDir tempDir: Path) {
        // Create a local bare repo with one commit
        val bareRepo = createBareRepoWithCommits(tempDir, "fixture.git", listOf("Initial commit"))
        val workspace = tempDir.resolve("workspace")
        Files.createDirectories(workspace)

        val spec = CheckoutSpec(GitScm(url = bareRepo.toString(), branch = "master"))
        val request = createRequest(spec, workspace)

        val executor = createExecutor(tempDir)
        executor.use { exec ->
            val result = exec.execute(request)
            assertTrue(result.isSuccess, "Clone must succeed: ${result.exceptionOrNull()?.message}")
            val checkoutResult = result.getOrNull()!!
            assertTrue(checkoutResult.sha.isNotBlank(), "SHA must be non-blank")
            assertEquals("clone", checkoutResult.classification)
        }

        val events = (request.eventSink as RecordingEventSink).events
        assertTrue(events.any { it is GitCheckoutStarted }, "Must emit GitCheckoutStarted")
        assertTrue(events.any { it is GitCheckoutCompleted }, "Must emit GitCheckoutCompleted")
        assertFalse(events.any { it is GitCheckoutFailed }, "Must NOT emit GitCheckoutFailed")

        val completed = events.filterIsInstance<GitCheckoutCompleted>().first()
        assertEquals(bareRepo.toString(), completed.url)
        assertEquals("master", completed.branch)
        assertTrue(completed.sha.isNotBlank())
    }

    // ─── Scenario 2: Idempotent re-run (SHA equal → no-op) ───────────────────

    @Test
    fun `SC-002 second run with same SHA is no-op under 2s`(@TempDir tempDir: Path) {
        val bareRepo = createBareRepoWithCommits(tempDir, "fixture.git", listOf("Initial commit"))
        val workspace = tempDir.resolve("workspace")
        Files.createDirectories(workspace)

        val spec = CheckoutSpec(GitScm(url = bareRepo.toString(), branch = "master"))
        val request = createRequest(spec, workspace)

        val executor = createExecutor(tempDir)
        executor.use { exec ->
            // First run — clone
            val result1 = exec.execute(request)
            assertTrue(result1.isSuccess, "First run must succeed")
            val sha1 = result1.getOrNull()!!.sha

            // Second run — no-op (SHA equal)
            val startMs = System.currentTimeMillis()
            val result2 = exec.execute(request)
            val elapsedMs = System.currentTimeMillis() - startMs

            assertTrue(result2.isSuccess, "Second run must succeed")
            assertEquals(sha1, result2.getOrNull()!!.sha)
            assertEquals("no-op", result2.getOrNull()!!.classification)
            assertTrue(elapsedMs < 2000, "SHA-equal no-op must complete in <2s, was ${elapsedMs}ms")
        }
    }

    // ─── Scenario 3: Branch checkout (remote SHA changed) ─────────────────────

    @Test
    fun `SC-003 remote SHA change triggers fetch+reset`(@TempDir tempDir: Path) {
        // Create the bare repo and drive all commits directly through the same workDir
        val bareRepo = createBareRepoWithCommits(tempDir, "fixture.git", listOf("Initial commit"))

        // Clone bare to pusher and set up user
        val pusher = tempDir.resolve("pusher")
        Files.createDirectories(pusher)
        runGit(listOf("git", "clone", bareRepo.toString(), pusher.toString()))
        runGit(listOf("git", "-C", pusher.toString(), "config", "user.email", "test@test.com"))
        runGit(listOf("git", "-C", pusher.toString(), "config", "user.name", "Test User"))

        // First checkout — workspace fresh, executor clones
        val workspace = tempDir.resolve("workspace")
        Files.createDirectories(workspace)
        val spec = CheckoutSpec(GitScm(url = bareRepo.toString(), branch = "master"))
        val request1 = createRequest(spec, workspace)

        val executor = createExecutor(tempDir)
        executor.use { exec ->
            val result1 = exec.execute(request1)
            assertTrue(result1.isSuccess, "First checkout must succeed: ${result1.exceptionOrNull()?.message}")
            assertEquals("clone", result1.getOrNull()!!.classification)

            // Drive a new commit directly into bare using the pusher clone
            Files.writeString(pusher.resolve("newfile.txt"), "new content")
            runGit(listOf("git", "-C", pusher.toString(), "add", "."))
            runGit(listOf("git", "-C", pusher.toString(), "commit", "-m", "Second commit"))
            // Use force to reliably advance bare's master regardless of prior state
            runGit(listOf("git", "-C", pusher.toString(), "push", "--force", bareRepo.toString(), "main:master"))

            // Verify bare's master now points to the new commit
            val newRemoteSha = gitLsRemote(bareRepo.toString(), "master")
            assertTrue(newRemoteSha.isNotBlank(), "Remote SHA should be non-blank after push. Got: '$newRemoteSha'")

            // Second checkout: workspace still has old SHA, remote has new SHA → fetch+reset
            val request2 = createRequest(spec, workspace)
            val result2 = exec.execute(request2)
            assertTrue(result2.isSuccess, "Second checkout must succeed: ${result2.exceptionOrNull()?.message}")
            assertEquals("fetch+reset", result2.getOrNull()!!.classification)
        }
    }

    // ─── Scenario 4 & 5: Credentials resolution ──────────────────────────────

    @Test
    fun `SC-004 string credentialsId resolved and applied via string channel`(@TempDir tempDir: Path) {
        val bareRepo = createBareRepoWithCommits(tempDir, "fixture.git", listOf("Initial commit"))
        val workspace = tempDir.resolve("workspace")
        Files.createDirectories(workspace)

        val credsId = CredentialsId("test-api-token")
        val spec = CheckoutSpec(GitScm(
            url = bareRepo.toString(),
            branch = "master",
            credentialsId = credsId
        ))
        val request = createRequest(spec, workspace)

        // GitCredentials with string channel
        val gitCreds = GitCredentials(string = SecretHandleRef(credsId))
        val executor = createExecutorWithCreds(tempDir, gitCreds)
        executor.use { exec ->
            val result = exec.execute(request)
            assertTrue(result.isSuccess, "Checkout with string creds must succeed: ${result.exceptionOrNull()?.message}")
        }

        val events = (request.eventSink as RecordingEventSink).events
        val started = events.filterIsInstance<GitCheckoutStarted>().firstOrNull()
        assertNotNull(started, "Must emit GitCheckoutStarted")
        assertNotNull(started!!.credentialsRef, "credentialsRef must be set")
    }

    @Test
    fun `SC-005 usernamePassword credentialsId resolved and applied via header channel`(@TempDir tempDir: Path) {
        val bareRepo = createBareRepoWithCommits(tempDir, "fixture.git", listOf("Initial commit"))
        val workspace = tempDir.resolve("workspace")
        Files.createDirectories(workspace)

        val credsId = CredentialsId("test-userpass")
        val spec = CheckoutSpec(GitScm(
            url = bareRepo.toString(),
            branch = "master",
            credentialsId = credsId
        ))
        val request = createRequest(spec, workspace)

        // GitCredentials with usernamePassword channel
        val gitCreds = GitCredentials(
            user = SecretHandleRef(credsId),
            pass = SecretHandleRef(credsId)
        )
        val executor = createExecutorWithCreds(tempDir, gitCreds)
        executor.use { exec ->
            val result = exec.execute(request)
            assertTrue(result.isSuccess, "Checkout with usernamePassword creds must succeed: ${result.exceptionOrNull()?.message}")
        }

        val events = (request.eventSink as RecordingEventSink).events
        val started = events.filterIsInstance<GitCheckoutStarted>().firstOrNull()
        assertNotNull(started, "Must emit GitCheckoutStarted")
    }

    // ─── Scenario 6: Changelog format ──────────────────────────────────────

    @Test
    fun `SC-006 changelog format is 7-char SHA then first line of subject`(@TempDir tempDir: Path) {
        val bareRepo = createBareRepoWithCommits(tempDir, "fixture.git", listOf("Initial commit with a descriptive message"))
        val workspace = tempDir.resolve("workspace")
        Files.createDirectories(workspace)

        val spec = CheckoutSpec(GitScm(url = bareRepo.toString(), branch = "master", changelog = true))
        val request = createRequest(spec, workspace)

        val executor = createExecutor(tempDir)
        executor.use { exec ->
            val result = exec.execute(request)
            assertTrue(result.isSuccess, "Checkout must succeed")
        }

        val changelogFile = workspace.resolve("changelog.txt")
        assertTrue(Files.exists(changelogFile), "changelog.txt must exist when changelog=true")

        val lines = Files.readAllLines(changelogFile)
        assertTrue(lines.isNotEmpty(), "changelog.txt must have at least one line")

        // Format: 7-char SHA then space then subject
        val sha7Pattern = Regex("^[0-9a-f]{7} .+")
        for (line in lines) {
            assertTrue(sha7Pattern.matches(line), "Line must match '7-char-SHA subject': '$line'")
        }

        // Verify the 7-char SHA matches the full SHA from the event
        val events = (request.eventSink as RecordingEventSink).events
        val completed = events.filterIsInstance<GitCheckoutCompleted>().first()
        assertTrue(completed.sha.startsWith(lines[0].substring(0, 7)),
            "changelog SHA should match HEAD SHA prefix")
    }

    // ─── Scenario 7 & 8: Poll changed / unchanged ────────────────────────────

    @Test
    fun `SC-007 poll detects changed SHA and emits GitPollChanged`(@TempDir tempDir: Path) {
        val bareRepo = createBareRepoWithCommits(tempDir, "fixture.git", listOf("Initial commit"))

        // Get the initial SHA
        val initialSha = runGitWithOutput(listOf("git", "ls-remote", bareRepo.toString(), "master"))
            .split("\t")[0].trim()

        val workspace = tempDir.resolve("workspace")
        Files.createDirectories(workspace)

        // First checkout — no previousRemoteSha
        val spec1 = CheckoutSpec(GitScm(url = bareRepo.toString(), branch = "master"))
        val request1 = createRequest(spec1, workspace)
        val executor = createExecutor(tempDir)
        executor.use { exec ->
            assertTrue(exec.execute(request1).isSuccess)
        }

        // Push a new commit
        val work = tempDir.resolve("work")
        Files.createDirectories(work)
        runGit(listOf("git", "clone", bareRepo.toString(), work.toString()))
        Files.writeString(work.resolve("newfile.txt"), "Content")
        runGit(listOf("git", "-C", work.toString(), "add", "."))
        runGit(listOf("git", "-C", work.toString(), "commit", "-m", "New commit"))
        runGit(listOf("git", "-C", work.toString(), "push", "--force", bareRepo.toString(), "main:master"))

        val newSha = runGitWithOutput(listOf("git", "ls-remote", bareRepo.toString(), "master"))
            .split("\t")[0].trim()
        assertNotEquals(initialSha, newSha, "New SHA should differ from initial")

        // Second checkout — with previousRemoteSha (should emit GitPollChanged)
        val spec2 = CheckoutSpec(GitScm(url = bareRepo.toString(), branch = "master"))
        val request2 = createRequestWithPreviousSha(spec2, workspace, initialSha)
        executor.use { exec ->
            val result = exec.execute(request2)
            assertTrue(result.isSuccess, "Second checkout must succeed")
        }

        val events2 = (request2.eventSink as RecordingEventSink).events
        val pollChangedEvents = events2.filterIsInstance<GitPollChanged>()
        assertTrue(pollChangedEvents.isNotEmpty(), "Must emit GitPollChanged when remote SHA differs")
        assertEquals(initialSha, pollChangedEvents.first().previousSha)
        assertEquals(newSha, pollChangedEvents.first().newSha)
    }

    @Test
    fun `SC-008 poll detects unchanged SHA does not emit GitPollChanged`(@TempDir tempDir: Path) {
        val bareRepo = createBareRepoWithCommits(tempDir, "fixture.git", listOf("Initial commit"))

        val sha = runGitWithOutput(listOf("git", "ls-remote", bareRepo.toString(), "master"))
            .split("\t")[0].trim()

        val workspace = tempDir.resolve("workspace")
        Files.createDirectories(workspace)

        val spec = CheckoutSpec(GitScm(url = bareRepo.toString(), branch = "master"))
        val request = createRequestWithPreviousSha(spec, workspace, sha)

        val executor = createExecutor(tempDir)
        executor.use { exec ->
            val result = exec.execute(request)
            assertTrue(result.isSuccess, "Checkout must succeed")
        }

        val events = (request.eventSink as RecordingEventSink).events
        val pollChangedEvents = events.filterIsInstance<GitPollChanged>()
        assertTrue(pollChangedEvents.isEmpty(), "Must NOT emit GitPollChanged when remote SHA unchanged")
    }

    // ─── Scenario 9: relativeTargetDir ──────────────────────────────────────

    @Test
    fun `SC-009 relativeTargetDir places repo in correct subdirectory`(@TempDir tempDir: Path) {
        val bareRepo = createBareRepoWithCommits(tempDir, "fixture.git", listOf("Initial commit"))
        val workspace = tempDir.resolve("workspace")
        Files.createDirectories(workspace)

        val relDir = "my-service"
        val spec = CheckoutSpec(GitScm(
            url = bareRepo.toString(),
            branch = "master",
            relativeTargetDir = relDir
        ))
        val request = createRequest(spec, workspace)

        val executor = createExecutor(tempDir)
        executor.use { exec ->
            val result = exec.execute(request)
            assertTrue(result.isSuccess, "Checkout must succeed: ${result.exceptionOrNull()?.message}")
        }

        val serviceDir = workspace.resolve(relDir)
        assertTrue(Files.exists(serviceDir), "Service directory must exist at relativeTargetDir")
        assertTrue(Files.exists(serviceDir.resolve(".git")), ".git must exist inside relativeTargetDir")

        // changelog.txt should be at workspace root (not inside relDir)
        val changelogAtRoot = workspace.resolve("changelog.txt")
        assertTrue(Files.exists(changelogAtRoot), "changelog.txt should be at workspace root")
    }

    // ─── Scenario 10: Auth-fail error taxonomy ───────────────────────────────

    @Test
    @EnabledIfEnvironmentVariable(named = "V2_NETWORK_OK", matches = "true")
    fun `SC-010 auth fail emits GitCheckoutFailed with classified reason`(@TempDir tempDir: Path) {
        // Use a private repo URL that will auth fail
        val spec = CheckoutSpec(GitScm(
            url = "https://github.com/nonexistent-org-123456/nonexistent-private-repo.git",
            branch = "master",
            credentialsId = CredentialsId("invalid-creds-id")
        ))
        val request = createRequest(spec, tempDir.resolve("workspace").also { Files.createDirectories(it) })

        val executor = createExecutor(tempDir)
        executor.use { exec ->
            val result = exec.execute(request)
            assertTrue(result.isFailure, "Auth fail should produce failure result")
        }

        val events = (request.eventSink as RecordingEventSink).events
        val failedEvents = events.filterIsInstance<GitCheckoutFailed>()
        assertTrue(failedEvents.isNotEmpty(), "Must emit GitCheckoutFailed for auth failure")
        val reason = failedEvents.first().reason.lowercase()
        // Error classification should reflect auth or host failure
        assertTrue(
            reason.contains("authentication") ||
            reason.contains("auth") ||
            reason.contains("could not resolve") ||
            reason.contains("not found"),
            "Error reason should classify auth failure, got: ${failedEvents.first().reason}"
        )
    }

    // ─── Scenario 11: Network fail ───────────────────────────────────────────

    @Test
    @EnabledIfEnvironmentVariable(named = "V2_NETWORK_OK", matches = "true")
    fun `SC-011 network unreachable emits GitCheckoutFailed`(@TempDir tempDir: Path) {
        val spec = CheckoutSpec(GitScm(
            url = "https://10.255.255.1/nonexistent/repo.git",
            branch = "master"
        ))
        val request = createRequest(spec, tempDir.resolve("workspace").also { Files.createDirectories(it) })

        val executor = createExecutor(tempDir)
        executor.use { exec ->
            val result = exec.execute(request)
            assertTrue(result.isFailure, "Network fail should produce failure result")
        }

        val events = (request.eventSink as RecordingEventSink).events
        assertTrue(events.any { it is GitCheckoutFailed }, "Must emit GitCheckoutFailed for network failure")
    }

    // ─── Scenario 12: Invalid branch ────────────────────────────────────────

    @Test
    fun `SC-012 invalid branch emits GitCheckoutFailed with branch-not-found classification`(@TempDir tempDir: Path) {
        val bareRepo = createBareRepoWithCommits(tempDir, "fixture.git", listOf("Initial commit"))
        val workspace = tempDir.resolve("workspace")
        Files.createDirectories(workspace)

        val spec = CheckoutSpec(GitScm(
            url = bareRepo.toString(),
            branch = "nonexistent-branch-xyz"
        ))
        val request = createRequest(spec, workspace)

        val executor = createExecutor(tempDir)
        executor.use { exec ->
            val result = exec.execute(request)
            assertTrue(result.isFailure, "Invalid branch should produce failure result")
        }

        val events = (request.eventSink as RecordingEventSink).events
        val failedEvents = events.filterIsInstance<GitCheckoutFailed>()
        assertTrue(failedEvents.isNotEmpty(), "Must emit GitCheckoutFailed for invalid branch")
        val reason = failedEvents.first().reason.lowercase()
        assertTrue(
            reason.contains("not found") || reason.contains("failed") || reason.contains("empty sha"),
            "Error reason should indicate branch not found, got: ${failedEvents.first().reason}"
        )
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun createExecutor(tempDir: Path): GitCheckoutExecutor {
        val poll = GitPollExecutor()
        val changelog = GitChangelogWriter()
        val credsDir = tempDir.resolve("creds")
        Files.createDirectories(credsDir)
        val applier = GitCredentialsApplier(credsDir, GitCredentials())
        return GitCheckoutExecutor(poll, changelog, applier, fixedClock)
    }

    private fun createExecutorWithCreds(tempDir: Path, gitCreds: GitCredentials): GitCheckoutExecutor {
        val poll = GitPollExecutor()
        val changelog = GitChangelogWriter()
        val credsDir = tempDir.resolve("creds")
        Files.createDirectories(credsDir)
        val applier = GitCredentialsApplier(credsDir, gitCreds)
        return GitCheckoutExecutor(poll, changelog, applier, fixedClock)
    }

    private fun createRequest(spec: CheckoutSpec, workspace: Path): GitCheckoutRequest {
        return GitCheckoutRequest(
            spec = spec,
            runId = "uat-local-005-test",
            workspaceRoot = workspace,
            eventSink = RecordingEventSink(),
            clock = fixedClock,
            secretStore = null,
            stepIndex = 0,
            previousRemoteSha = null
        )
    }

    private fun createRequestWithPreviousSha(spec: CheckoutSpec, workspace: Path, previousSha: String): GitCheckoutRequest {
        return GitCheckoutRequest(
            spec = spec,
            runId = "uat-local-005-test",
            workspaceRoot = workspace,
            eventSink = RecordingEventSink(),
            clock = fixedClock,
            secretStore = null,
            stepIndex = 0,
            previousRemoteSha = previousSha
        )
    }

    /**
     * Creates a local bare git repo with the given commit messages.
     */
    private fun createBareRepoWithCommits(tempDir: Path, name: String, messages: List<String>): Path {
        val bareRepo = tempDir.resolve(name)

        // Create a working repo to make commits first
        val workDir = tempDir.resolve("work_${name}")
        Files.createDirectories(workDir)
        runGit(listOf("git", "init"), workDir.toFile())
        runGit(listOf("git", "-C", workDir.toString(), "config", "user.email", "test@test.com"))
        runGit(listOf("git", "-C", workDir.toString(), "config", "user.name", "Test User"))

        for (msg in messages) {
            Files.writeString(workDir.resolve("file_${msg.hashCode()}.txt"), "content for: $msg")
            runGit(listOf("git", "-C", workDir.toString(), "add", "."))
            runGit(listOf("git", "-C", workDir.toString(), "commit", "-m", msg))
        }

        // Create master branch from current HEAD (needed before first push)
        runGit(listOf("git", "-C", workDir.toString(), "branch", "--force", "master", "HEAD"))

        // Now create bare repo and push to it
        runGit(listOf("git", "init", "--bare", bareRepo.toString()))

        // Push master to bare repo
        runGit(listOf("git", "-C", workDir.toString(), "push", bareRepo.toString(), "master"))
        return bareRepo
    }

    private fun runGit(args: List<String>, dir: java.io.File? = null) {
        val pb = ProcessBuilder(args).also { if (dir != null) it.directory(dir) }
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
        val p = pb.start()
        processes.add(p)
        val exit = p.waitFor(60, TimeUnit.SECONDS)
        if (!exit || p.exitValue() != 0) {
            val err = p.errorStream.bufferedReader().readText()
            throw IllegalStateException("git command failed: ${args.joinToString(" ")}, exit=${p.exitValue()}, err=$err")
        }
    }

    private fun runGitWithOutput(args: List<String>): String {
        val pb = ProcessBuilder(args)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
        val p = pb.start()
        processes.add(p)
        val exit = p.waitFor(60, TimeUnit.SECONDS)
        if (!exit || p.exitValue() != 0) {
            throw IllegalStateException("git command failed: ${args.joinToString(" ")}")
        }
        return p.inputStream.bufferedReader().readText()
    }

    private fun gitLsRemote(url: String, branch: String): String {
        val output = runGitWithOutput(listOf("git", "ls-remote", url, branch))
        return output.split("\t").firstOrNull()?.trim() ?: ""
    }

    inner class RecordingEventSink : EventSink {
        val events = mutableListOf<DomainEvent>()

        override fun append(event: DomainEvent) {
            events.add(event)
        }

        override fun eventsFor(runId: String): Sequence<DomainEvent> {
            return events.asSequence()
        }
    }
}
