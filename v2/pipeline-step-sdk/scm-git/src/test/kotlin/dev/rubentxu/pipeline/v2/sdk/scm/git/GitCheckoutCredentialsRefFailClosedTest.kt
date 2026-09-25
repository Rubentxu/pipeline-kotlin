package dev.rubentxu.pipeline.v2.sdk.scm.git

import dev.rubentxu.pipeline.v2.domain.scm.CheckoutSpec
import dev.rubentxu.pipeline.v2.domain.scm.GitCredentials
import dev.rubentxu.pipeline.v2.domain.scm.GitScm
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * WU-RP-053R · B2 — credentialsRef fail-closed validation.
 *
 * Background (discriminante):
 *
 *   GitCheckoutExecutor.execute(...)
 *   |- resolveGitCredentials(req, spec)
 *      |- if secretStore == null && req.secretStore == null
 *      |     return null        // ← GAP: silently "no creds", proceeds anonymously
 *      |                        //    even though user declared credentialsRef = "x"
 *      |- else
 *            store.get(credentialsId)  // throws if not found (fail-closed in stores
 *                                       // that throw — but stores that return null
 *                                       // would also be fail-open)
 *
 * Today, if a user passes `credentialsRef = "github-token"` in the .pipeline.kts
 * but the SecretStore is unavailable (not loaded, wrong path, not configured),
 * the executor silently proceeds with anonymous git. The original credentials
 * intent is lost. This is fail-open and contradicts the typed `credentialsId`
 * carrier discipline (INV-L5-CR-005).
 *
 * This suite pins the FAIL-CLOSED contract:
 *
 *   credentialsRef != null  &&  no resolvable SecretStore
 *     → Result.failure(IllegalStateException("credentialsRef declared as '...' but no
 *                                              SecretStore was provided to resolve it.
 *                                              Either supply a SecretStore
 *                                              (LocalSecretStore via PIPELINE_CREDENTIALS_STORE
 *                                              or wire one into GitCheckoutExecutor /
 *                                              GitCheckoutRequest) or remove credentialsRef."))
 *
 *   credentialsRef != null  &&  SecretStore returns null for the id
 *     → Result.failure(SecretNotFoundException / wrapped USER failure)
 *     (covered by the existing store; this suite focuses on the no-store path)
 *
 * The fix lives in [GitCheckoutExecutor.resolveGitCredentials]: when the
 * declared credentialsId cannot be resolved, the executor MUST fail before
 * any subprocess is launched.
 */
@Timeout(60)
class GitCheckoutCredentialsRefFailClosedTest {

    private val processes = mutableListOf<Process>()

    @BeforeEach
    fun setUp() {
        assumeTrue(
            System.getProperty("os.name", "").lowercase().contains("linux"),
            "fail-closed integration test requires Linux",
        )
    }

    @AfterEach
    fun teardown() {
        processes.forEach { p ->
            if (p.isAlive) p.destroyForcibly()
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
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    /**
     * RED — credentialsRef declared but no SecretStore reachable.
     *
     * Builds a real bare+working repo locally so the executor has a valid
     * fetch target. Declares a credentialsRef in the spec. Wires
     * [GitCheckoutRequest.secretStore] = null AND constructs the
     * [GitCheckoutExecutor] without a store, then asserts:
     *
     *   - Result is failure (not success).
     *   - The failure message names BOTH the declared credentialsRef and the
     *     missing store so the user can act on the diagnostic.
     *   - The failure occurs BEFORE any subprocess is launched (no checkout
     *     files appear under the requested target).
     *
     * Today this assertion FAILS — the executor silently proceeds with
     * anonymous git and writes the working tree under the target.
     */
    @Test
    fun `B2 RED — credentialsRef declared without SecretStore fails closed`(@TempDir tempDir: Path) {
        val bareRepo = createBareRepoWithCommit(tempDir)
        val workspaceRoot = tempDir.resolve("workspace")
        Files.createDirectories(workspaceRoot)
        val relativeTargetDir = "scm-target"
        val target = workspaceRoot.resolve(relativeTargetDir)

        val executor = buildExecutor(secretStore = null)
        val request = GitCheckoutRequest(
            spec = CheckoutSpec(
                scm = GitScm(
                    url = bareRepo.toString(),
                    branch = "master",
                    credentialsId = CredentialsId("declared-but-unresolvable"),
                    changelog = false,
                    poll = true,
                    relativeTargetDir = relativeTargetDir,
                ),
            ),
            runId = "b2-red",
            workspaceRoot = workspaceRoot,
            eventSink = dev.rubentxu.pipeline.v2.events.NullEventSink,
            clock = java.time.Clock.systemUTC(),
            secretStore = null, // B2 gap: no store reachable
            stepIndex = 0,
            previousRemoteSha = null,
        )

        val outcome = executor.execute(request)

        // P1: the operation is a failure, not a silent success.
        assertTrue(
            outcome.isFailure,
            "Expected Result.failure when credentialsRef is declared but no SecretStore is reachable; " +
                "got success instead (fail-open). Outcome: $outcome",
        )
        val failure = outcome.exceptionOrNull()

        // P2: the diagnostic names BOTH the declared credentialsRef and the
        // missing store so the user can act on the message.
        val msg = failure?.message.orEmpty()
        assertTrue(
            msg.contains("declared-but-unresolvable", ignoreCase = true),
            "Failure message must name the declared credentialsRef so the user can fix the script; got: $msg",
        )
        assertTrue(
            msg.contains("SecretStore", ignoreCase = true) ||
                msg.contains("credentials", ignoreCase = true),
            "Failure message must explain that the SecretStore is missing/unreachable; got: $msg",
        )

        // P3: the failure occurs BEFORE any subprocess is launched. No
        // checkout files appear under the requested target — this is the
        // security property: a declared-but-unresolvable credential MUST
        // NOT silently succeed as anonymous.
        assertTrue(
            !Files.exists(target) || !Files.isDirectory(target) ||
                Files.list(target).use { it.findAny().isEmpty },
            "Expected no checkout files under $relativeTargetDir because the credentialsRef " +
                "could not be resolved; got existing content (fail-open write).",
        )
    }

    private fun buildExecutor(secretStore: dev.rubentxu.pipeline.v2.credentials.api.SecretStore?): GitCheckoutExecutor {
        val poll = GitPollExecutor()
        val changelog = GitChangelogWriter()
        // credsDir parent is tempDir.parent so it survives the apply() lifecycle.
        val credsDir = Files.createTempDirectory("b2-creds-")
        val applier = GitCredentialsApplier(
            tempDir = credsDir,
            credentials = GitCredentials(), // empty; resolution will be from request/secretStore
        )
        return GitCheckoutExecutor(
            poll = poll,
            changelog = changelog,
            credentialsApplier = applier,
            secretStore = secretStore,
        )
    }

    private fun createBareRepoWithCommit(tempDir: Path): Path {
        val bareRepo = tempDir.resolve("fixture-${UUID.randomUUID()}.git")
        val workDir = tempDir.resolve("work-${UUID.randomUUID()}")
        Files.createDirectories(workDir)
        runGit(listOf("git", "init", "-b", "master"), workDir.toFile())
        runGit(listOf("git", "-C", workDir.toString(), "config", "user.email", "b2@test"))
        runGit(listOf("git", "-C", workDir.toString(), "config", "user.name", "B2"))
        Files.writeString(workDir.resolve("README.md"), "b2 fixture\n")
        runGit(listOf("git", "-C", workDir.toString(), "add", "."))
        runGit(listOf("git", "-C", workDir.toString(), "commit", "-m", "initial"))
        runGit(listOf("git", "init", "--bare", bareRepo.toString()))
        runGit(listOf("git", "-C", workDir.toString(), "push", bareRepo.toString(), "master"))
        return bareRepo
    }

    private fun runGit(args: List<String>, dir: java.io.File? = null) {
        val pb = ProcessBuilder(args).also { if (dir != null) it.directory(dir) }
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
        val p = pb.start()
        processes.add(p)
        val exit = p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)
        if (!exit || p.exitValue() != 0) {
            val err = p.errorStream.bufferedReader().readText()
            throw IllegalStateException(
                "git command failed: ${args.joinToString(" ")}, exit=${p.exitValue()}, err=$err",
            )
        }
    }
}
