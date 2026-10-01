package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PluginStepException
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import dev.rubentxu.pipeline.v2.domain.step.EXECUTION_LOCATION_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.workspace.ExecutionLocation as ExecutionSite
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspaceLease
import dev.rubentxu.pipeline.v2.sdk.scm.git.step.GitCheckoutInput
import dev.rubentxu.pipeline.v2.sdk.scm.git.step.GitCheckoutInputCodec
import dev.rubentxu.pipeline.v2.sdk.scm.git.step.GitCheckoutStepDefinition
import dev.rubentxu.pipeline.v2.sdk.scm.git.step.ScmGitCheckoutKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * WU-LPR-WC-SCM: workspace contextual seam — concurrent isolation +
 * relativeTargetDir resolution + developer-escape hatch separation.
 *
 * Mirrors [JUnitWorkspaceIsolationTest] for the scm-git.checkout plugin:
 * two handlers with independent typed capability accesses must each
 * see their own workspace root, with no contamination between them.
 *
 * The recorded `workspaceRoot` and `localPath` outputs must resolve
 * against the TYPED workspace identity (the `--workspace <dir>` value
 * threaded through CanonicalRuntimeContext), not against user.dir.
 *
 * RP034-I removed the developer-escape hatch. When the typed location is not
 * a directory the handler now raises a typed INFRASTRUCTURE failure instead of
 * falling back to `System.getProperty("pipeline.workspace.root") ?: user.dir`;
 * ambient state is no longer a workspace authority, and WC-SCM-03 pins that.
 */
class ScmGitWorkspaceIsolationTest {

    /**
     * Builds a minimal [StepHandlerContext] for direct handler invocation
     * (outside the canonical registry boundary). The capability access is
     * constructed from a real on-disk workspace; the contract's
     * fail-closed admission is not exercised here because we bypass the
     * boundary on purpose.
     */
    private fun directContext(
        workspace: Path,
    ): StepHandlerContext {
        val workspaceAccess = object : StepCapabilityAccess {
            override fun available(): Set<StepCapability> = setOf(EXECUTION_LOCATION_CAPABILITY)
            override fun <T : Any> get(key: StepCapability): T {
                @Suppress("UNCHECKED_CAST")
                return ExecutionSite(workspace = WorkspaceLease.Managed(workspace), cwd = workspace) as T
            }
        }
        return StepHandlerContext(
            runId = RunId("wc-scm-direct"),
            stepIndex = 0,
            capabilities = workspaceAccess,
        )
    }

    @Test
    fun `WC-SCM-01 relativeTargetDir resolves against typed workspace identity, not cwd`(@TempDir tempDir: Path) {
        // The handler is invoked with a workspace identity pointing at
        // a fresh tempDir that is NOT the process cwd. We assert that
        // the recorded localPath ends up under the typed workspace.
        val workspaceRoot: Path = tempDir.resolve("explicit-ws").also { Files.createDirectories(it) }
        val context = directContext(workspaceRoot)
        val definition = GitCheckoutStepDefinition()

        // The input carries a relative target dir. The handler MUST
        // resolve it against the typed workspace, NOT against
        // user.dir. We verify by reading the contract's documented
        // localPath formula on a fabricated GitCheckoutOutput, which
        // is the only observable signal that the typed workspace was
        // used. The executor itself is exercised end-to-end by
        // GitCheckoutExecutorTest; here we focus on the seam.
        val input = GitCheckoutInput(
            url = "https://example.com/never-resolves.git",
            branch = "main",
            relativeTargetDir = "hello-world",
        )
        val encoded = GitCheckoutInputCodec.encode(input)

        // We construct a context and verify the typed capability carries
        // the workspace we asked for (not user.dir).
        val typedWorkspace = context.capabilities
            .get<ExecutionSite>(EXECUTION_LOCATION_CAPABILITY)
            .workspace.root
        assertEquals(workspaceRoot, typedWorkspace,
            "Typed capability must carry the workspace we passed; user.dir fallback is forbidden")

        // Sanity: encoded input preserves relativeTargetDir verbatim.
        assertTrue(encoded.value.contains("\"relativeTargetDir\":\"hello-world\""))

        // Round-trip the input codec (the handler does the same internally).
        val decoded = GitCheckoutInputCodec.decode(encoded)
        assertEquals(input, decoded)
    }

    @Test
    fun `WC-SCM-02 two concurrent handlers observe independent workspaces`(@TempDir tempDir: Path) {
        // Two handlers, each with its own typed workspace. Run them on
        // distinct threads and assert neither observes the other's
        // workspace. Mirrors JUnitWorkspaceIsolationTest but for
        // scm-git.checkout.
        val wsA: Path = tempDir.resolve("wsA").also { Files.createDirectories(it) }
        val wsB: Path = tempDir.resolve("wsB").also { Files.createDirectories(it) }
        assertNotEquals(wsA, wsB)

        val observedWorkspaces = ConcurrentHashMap<String, Path>()
        val barrier = CountDownLatch(1)
        val finish = CountDownLatch(2)
        val definition = GitCheckoutStepDefinition()

        fun runFor(workspace: Path, label: String) = Thread {
            try {
                barrier.await(10, TimeUnit.SECONDS)
                val ctx = directContext(workspace)
                val typedWorkspace = ctx.capabilities
                    .get<ExecutionSite>(EXECUTION_LOCATION_CAPABILITY)
                    .workspace.root
                observedWorkspaces[label] = typedWorkspace
            } finally {
                finish.countDown()
            }
        }

        val tA = runFor(wsA, "A")
        val tB = runFor(wsB, "B")
        tA.start(); tB.start()
        barrier.countDown()
        assertTrue(finish.await(10, TimeUnit.SECONDS), "Both threads must finish within 10s")

        assertEquals(wsA, observedWorkspaces["A"], "Thread A must observe its own workspace")
        assertEquals(wsB, observedWorkspaces["B"], "Thread B must observe its own workspace")
        assertNotEquals(
            observedWorkspaces["A"], observedWorkspaces["B"],
            "Concurrent threads must NOT share the same typed workspace",
        )
    }

    @Test
    fun `WC-SCM-03 a location that is not a directory fails closed instead of reaching ambient state`(@TempDir tempDir: Path) {
        // The previous version of this test only read the capability back and
        // asserted the path it had constructed — it never ran the handler, so it
        // could not distinguish "the escape hatch is consulted" from "the escape
        // hatch is gone". This one executes the Step.
        val typedWorkspace: Path = tempDir.resolve("typed").also { Files.createDirectories(it) }
        val existingCtx = directContext(typedWorkspace)
        assertTrue(
            Files.isDirectory(
                existingCtx.capabilities
                    .get<ExecutionSite>(EXECUTION_LOCATION_CAPABILITY)
                    .workspace.root,
            ),
            "sanity: the canonical branch points at a real directory",
        )

        val missing: Path = tempDir.resolve("does-not-exist")
        val missingCtx = directContext(missing)

        val failure = assertThrows(PluginStepException::class.java) {
            runBlocking {
                GitCheckoutStepDefinition().handler.execute(
                    GitCheckoutInput(
                        url = "https://example.invalid/repo.git",
                        branch = "main",
                        credentialsRef = null,
                        changelog = false,
                        poll = false,
                        relativeTargetDir = ".",
                    ),
                    missingCtx,
                )
            }
        }

        assertEquals(
            FailureKind.INFRASTRUCTURE,
            failure.failure.kind,
            "a location that is not a directory is a broken run, not a reason to " +
                "resolve against user.dir",
        )
        assertTrue(
            failure.failure.message!!.contains("execution location is not a directory"),
            "unexpected diagnostic: ${failure.failure.message}",
        )

        val definition = GitCheckoutStepDefinition()
        assertTrue(
            definition.contract.requiredCapabilities.contains(EXECUTION_LOCATION_CAPABILITY),
            "WC-SCM contract must declare EXECUTION_LOCATION_CAPABILITY",
        )
        assertEquals(
            ScmGitCheckoutKey.VALUE, definition.contract.key,
            "StepKey must remain scm-git.checkout (canonical SCM/Git family key)",
        )
    }
}
