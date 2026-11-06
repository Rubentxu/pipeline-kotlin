package dev.rubentxu.pipeline.v2.sdk.scm.git

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PluginStepException
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import dev.rubentxu.pipeline.v2.domain.workspace.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspaceLease
import dev.rubentxu.pipeline.v2.sdk.scm.git.step.GitCheckoutInput
import dev.rubentxu.pipeline.v2.sdk.scm.git.step.GitCheckoutStepDefinition
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/**
 * The checkout that stays inside the workspace.
 *
 * **The guarantee.** A destination that leaves the workspace is refused BEFORE
 * anything happens: before a credential is applied, before a directory is
 * created, before a git process starts. And the path the Step publishes as
 * `localPath` is the directory the checkout ran in.
 *
 * **Why the refusal has to come first.** The destination used to be resolved
 * twice under two different rules: the executor resolved it with `Path.resolve`
 * — which normalises nothing and confines nothing — and worked there, while the
 * Step validated the same user path afterwards, when the files were already
 * written. A refusal that arrives after the effect is a note, not a guard.
 *
 * **Why the assertions are about ABSENCES.** "Nothing outside the workspace was
 * created" is a fact about the filesystem, so it is read from the filesystem: the
 * escaped directory is looked for, and the credential directory the executor
 * would have opened is looked for. A test that only checked the exception would
 * pass with a checkout that created everything it should not have.
 */
class GitCheckoutConfinementTest {

    @TempDir
    lateinit var temp: Path

    @Test
    fun `a target that leaves the workspace is refused before the checkout starts`() {
        val workspace = Files.createDirectories(temp.resolve("workspace"))
        val escaped = temp.resolve("escape")
        val started = mutableListOf<String>()

        val definition = GitCheckoutStepDefinition(
            executorFactory = { _, _ ->
                // Reaching this factory means the checkout is about to open a
                // credential directory and to start git. The test counts it.
                started.add("executor")
                GitCheckoutExecutor(
                    poll = GitPollExecutor(controlRoot = temp.resolve("poll")),
                    changelog = GitChangelogWriter(controlRoot = temp.resolve("changelog")),
                    credentialsApplier = GitCredentialsApplier(
                        tempDir = Files.createDirectories(temp.resolve("creds")),
                    ),
                    gitControlRoot = temp.resolve("control"),
                )
            },
        )

        val failure = assertThrows<PluginStepException> {
            runBlocking {
                definition.handler.execute(
                    GitCheckoutInput(
                        url = "file:///nowhere/repo.git",
                        relativeTargetDir = "../escape",
                    ),
                    StepHandlerContext(
                        runId = RunId("run-1"),
                        stepIndex = 0,
                        capabilities = GitCheckoutTestCapabilities(location(workspace)),
                    ),
                )
            }
        }

        assertEquals(
            FailureKind.USER,
            failure.failure.kind,
            "a path the author wrote is the author's mistake, not a broken infrastructure",
        )
        assertTrue(
            failure.message!!.contains("escapes the workspace root"),
            "the refusal does not name the boundary the path crossed: ${failure.message}",
        )
        assertTrue(
            started.isEmpty(),
            "the checkout started before the destination was authorised: $started",
        )
        assertFalse(
            Files.exists(escaped),
            "a directory outside the workspace was created: $escaped",
        )
        assertFalse(
            Files.exists(temp.resolve("creds")),
            "a credential directory was opened before the refusal",
        )
    }

    @Test
    fun `an absolute path outside the workspace is refused`() {
        val workspace = Files.createDirectories(temp.resolve("workspace"))
        val outside = temp.resolve("elsewhere")

        val failure = assertThrows<PluginStepException> {
            AuthorizedCheckoutTarget.resolve(location(workspace), "scm-git.checkout", outside.toString())
        }

        assertEquals(FailureKind.USER, failure.failure.kind)
        assertTrue(
            failure.message!!.contains("absolute path outside the workspace root"),
            "the refusal does not name the rule that refused the path: ${failure.message}",
        )
    }

    @Test
    fun `the authorised destination is the directory the checkout runs in`() {
        val workspace = Files.createDirectories(temp.resolve("workspace"))

        val target = AuthorizedCheckoutTarget.resolve(location(workspace), "scm-git.checkout", "sub/dir")

        assertEquals(
            workspace.resolve("sub/dir"),
            target.path,
            "the authorised destination is not the directory the checkout would run in",
        )
    }

    /** The location a checkout runs in: the workspace root, and the directory inside it. */
    private fun location(workspace: Path): ExecutionLocation =
        ExecutionLocation(WorkspaceLease.Attached(workspace), workspace)
}
