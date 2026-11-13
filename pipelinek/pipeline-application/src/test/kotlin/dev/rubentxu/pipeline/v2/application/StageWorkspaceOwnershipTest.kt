package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.runtime.durable.WorkspaceResolver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The workspace of a stage, and who owns it.
 *
 * ## The guarantee under trial
 *
 * That the workspace of the stages of a run is decided by the FLAGS of the
 * run, and that the decision reaches the stage:
 *
 * ```text
 * (no flag)          -> the stages SHARE the directory the run was invoked in
 * --workspace <dir>  -> the stages SHARE the directory the operator named
 * --isolated         -> every stage gets a workspace of its own
 * ```
 *
 * ## Why the chain is under trial and not a function
 *
 * Because the decision is made in three places and each of them can be right
 * on its own: the CLI reads the flags, `WorkspaceIntent` turns them into a
 * lease, and `WorkspaceResolver` turns the lease into the directory of a
 * stage. A row that measured only the first would say nothing about the
 * directory a stage actually runs in, and the published text is about THAT.
 *
 * ## Why this row exists next to the published text
 *
 * Because the text that the catalogue publishes says what the product does
 * with the workspace of a stage, and the row is what makes that text a fact
 * and not an opinion. This row was GREEN before the text was corrected: the
 * product was already doing what the text now says. That makes it a FIXATION
 * and not a regression — its discriminating power is in the fact it holds,
 * not in a defect it caught.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class StageWorkspaceOwnershipTest {

    /**
     * A run that names no workspace shares the directory it was invoked in.
     *
     * This is the DEFAULT, and it is the Jenkins behaviour: the stages of the
     * run see the files another stage wrote.
     */
    @Test
    @DisplayName("a run that names no workspace shares the directory it was invoked in")
    fun aRunThatNamesNoWorkspaceSharesTheDirectoryItWasInvokedIn() {
        val resolver = resolverFor(flags(workspace = null, isolated = false))

        assertEquals(
            INVOCATION_DIRECTORY.toAbsolutePath().normalize(),
            resolver.resolve("build", 0),
            "the workspace of the stage is the directory the run was invoked in",
        )
        assertEquals(
            resolver.resolve("build", 0),
            resolver.resolve("test", 1),
            "the stages of the run share the workspace of the invocation directory",
        )
    }

    /**
     * A run that names a workspace shares the named one.
     *
     * The service runs every pipeline this way, so it is not a corner: it is
     * how a run of the product reaches its workspace.
     */
    @Test
    @DisplayName("a run that names a workspace shares the named one")
    fun aRunThatNamesAWorkspaceSharesTheNamedOne() {
        val resolver = resolverFor(flags(workspace = NAMED_WORKSPACE.toString(), isolated = false))

        assertEquals(
            NAMED_WORKSPACE.toAbsolutePath().normalize(),
            resolver.resolve("build", 0),
            "the workspace of the stage is the workspace the operator named",
        )
        assertEquals(
            resolver.resolve("build", 0),
            resolver.resolve("test", 1),
            "the stages of the run share the workspace the operator named",
        )
    }

    /**
     * A run that asks for isolation gives every stage a workspace of its own.
     *
     * The run that isolates is the one that says so, and there a stage does
     * not see the files another stage wrote.
     */
    @Test
    @DisplayName("a run that asks for isolation gives every stage a workspace of its own")
    fun aRunThatAsksForIsolationGivesEveryStageAWorkspaceOfItsOwn() {
        val resolver = resolverFor(flags(workspace = null, isolated = true))

        assertNotEquals(
            resolver.resolve("build", 0),
            resolver.resolve("test", 1),
            "the stages of an isolated run do not share the workspace",
        )
        assertEquals(
            CONTROL_ROOT.resolve("workspace").resolve("build-0"),
            resolver.resolve("build", 0),
            "the workspace of an isolated stage is its own, under the control root",
        )
    }

    /**
     * The resolver of the run, composed the way the run composes it.
     *
     * The flags are read by the CLI, the lease is resolved by the intent, and
     * the directory of a stage is resolved by the resolver: the row walks the
     * three and does not rewrite any of them.
     */
    private fun resolverFor(flags: CliFlags): WorkspaceResolver {
        val transport = WorkspaceIntent.resolveRuntimeTransport(
            flags = flags,
            invocationDirectory = INVOCATION_DIRECTORY,
            scratchParent = SCRATCH_PARENT,
        )
        return WorkspaceResolver(CONTROL_ROOT, transport.base)
    }

    /** The flags of a run, with the two that name a workspace. */
    private fun flags(workspace: String?, isolated: Boolean): CliFlags = CliFlags(
        command = CliCommand.RUN,
        dbPath = null,
        durableRunPolicy = DurableRunPolicy.ReusePriorRun,
        scriptPath = "pipeline.kts",
        workspace = workspace,
        isolated = isolated,
    )

    private companion object {
        /** The directory the run was invoked in. */
        val INVOCATION_DIRECTORY: Path = Path.of("/tmp/project")

        /** The workspace the operator named. */
        val NAMED_WORKSPACE: Path = Path.of("/tmp/named-workspace")

        /** The parent of the managed scratch of an isolated run. */
        val SCRATCH_PARENT: Path = Path.of("/tmp/scratch")

        /** The control root of the run. */
        val CONTROL_ROOT: Path = Path.of("/tmp/control-root")
    }
}
