package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.workspace.WorkspaceOwnership
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspaceRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * RP034-H — the CLI workspace contract.
 *
 * Pure: no process, no pipeline, no filesystem writes. The origin is decided at
 * the boundary and only the root crosses into the runtime, so the entire mode
 * matrix is testable here. Installed-distribution behaviour is certified in the
 * RP034-H UAT receipt, not asserted twice.
 */
@DisplayName("RP034-H CLI workspace modes")
class WorkspaceIntentTest {

    private val invocation = Path.of("/home/dev/my-project")
    private val scratch = Path.of("/run/ctl/workspace")

    private fun flags(workspace: String? = null, isolated: Boolean = false) = CliFlags(
        command = CliCommand.RUN,
        dbPath = null,
        durableRunPolicy = DurableRunPolicy.ReusePriorRun,
        scriptPath = "pipeline.kts",
        workspace = workspace,
        isolated = isolated,
    )

    @Nested
    @DisplayName("the three modes")
    inner class Modes {

        @Test
        fun `no flag attaches the invocation directory`() {
            val lease = WorkspaceIntent.resolveWorkspaceLease(flags(), invocation, scratch)
            assertEquals(invocation, lease.root)
            assertEquals(WorkspaceOwnership.USER, lease.ownership)
        }

        @Test
        fun `an explicit workspace attaches that directory`() {
            val lease = WorkspaceIntent.resolveWorkspaceLease(
                flags(workspace = "/repos/app"), invocation, scratch,
            )
            assertEquals(Path.of("/repos/app"), lease.root)
            assertEquals(WorkspaceOwnership.USER, lease.ownership)
        }

        @Test
        fun `isolated requests PipelineK-managed scratch`() {
            val lease = WorkspaceIntent.resolveWorkspaceLease(
                flags(isolated = true), invocation, scratch,
            )
            assertEquals(scratch, lease.root)
            assertEquals(WorkspaceOwnership.PIPELINEK, lease.ownership)
        }

        @Test
        fun `the attached cases never consult the control-plane scratch`() {
            // INV-WS-004: a control-plane path must not become a user workspace.
            val otherScratch = Path.of("/somewhere/else/workspace")
            assertEquals(
                invocation,
                WorkspaceIntent.resolveWorkspaceLease(flags(), invocation, otherScratch).root,
            )
            assertEquals(
                Path.of("/repos/app"),
                WorkspaceIntent.resolveWorkspaceLease(
                    flags(workspace = "/repos/app"), invocation, otherScratch,
                ).root,
            )
        }
    }

    @Nested
    @DisplayName("the runtime transport carries both facts")
    inner class Transport {

        @Test
        fun `the default mode pins the invocation directory and marks it USER`() {
            val transport = WorkspaceIntent.resolveRuntimeTransport(flags(), invocation, scratch)
            assertEquals(invocation, transport.base)
            assertEquals(WorkspaceOwnership.USER, transport.ownership)
        }

        @Test
        fun `an explicit workspace pins that directory and marks it USER`() {
            val transport = WorkspaceIntent.resolveRuntimeTransport(
                flags(workspace = "/repos/app"), invocation, scratch,
            )
            assertEquals(Path.of("/repos/app"), transport.base)
            assertEquals(WorkspaceOwnership.USER, transport.ownership)
        }

        @Test
        fun `isolated pins no directory and marks it PIPELINEK`() {
            // The asymmetry is the point: null base lets the coordinator allocate a
            // per-stage scratch, while PIPELINEK ownership keeps it destructible.
            val transport = WorkspaceIntent.resolveRuntimeTransport(
                flags(isolated = true), invocation, scratch,
            )
            assertEquals(null, transport.base)
            assertEquals(WorkspaceOwnership.PIPELINEK, transport.ownership)
        }

        @Test
        fun `every CLI mode states its owner rather than leaving it unsaid`() {
            // RP034-Id regression at the boundary. The bug was not a wrong value
            // but a *missing* one: ownership was resolved as Attached and then
            // dropped when the transport was built, so the runtime re-derived
            // Managed for every run and deleteDir() erased the user's project.
            listOf(flags(), flags(workspace = "/repos/app"), flags(isolated = true))
                .forEach { f ->
                    assertNotNull(
                        WorkspaceIntent.resolveRuntimeTransport(f, invocation, scratch).ownership,
                        "ownership must never be absent for mode $f",
                    )
                }
        }

        @Test
        fun `the transport agrees with the lease it derives from`() {
            listOf(flags(), flags(workspace = "/repos/app"), flags(isolated = true))
                .forEach { f ->
                    val lease = WorkspaceIntent.resolveWorkspaceLease(f, invocation, scratch)
                    val transport = WorkspaceIntent.resolveRuntimeTransport(f, invocation, scratch)
                    assertEquals(lease.ownership, transport.ownership)
                    assertEquals(
                        if (lease is dev.rubentxu.pipeline.v2.domain.workspace.WorkspaceLease.Attached) {
                            lease.root
                        } else {
                            null
                        },
                        transport.base,
                    )
                }
        }
    }

    @Nested
    @DisplayName("intent is a closed type")
    inner class Intent {

        @Test
        fun `each mode maps to its own request case`() {
            assertEquals(
                WorkspaceRequest.AttachInvocationDirectory,
                WorkspaceIntent.requestFor(flags()),
            )
            assertEquals(
                WorkspaceRequest.AttachExplicit(Path.of("/repos/app")),
                WorkspaceIntent.requestFor(flags(workspace = "/repos/app")),
            )
            assertEquals(
                WorkspaceRequest.ManagedIsolated,
                WorkspaceIntent.requestFor(flags(isolated = true)),
            )
        }
    }

    @Nested
    @DisplayName("incompatible modes fail closed at admission")
    inner class Exclusion {

        @Test
        fun `isolated together with workspace is rejected by the parser`() {
            val parsed = CliParser.parse(
                arrayOf("run", "--workspace", "/repos/app", "--isolated", "pipeline.kts"),
            )
            assertTrue(
                parsed is CliParseResult.Rejected,
                "--isolated with --workspace must be rejected, was $parsed",
            )
            assertEquals(
                CliError.ConflictingWorkspaceModes("/repos/app"),
                (parsed as CliParseResult.Rejected).error,
            )
        }

        @Test
        fun `each mode alone parses successfully`() {
            listOf(
                arrayOf("run", "pipeline.kts"),
                arrayOf("run", "--workspace", "/repos/app", "pipeline.kts"),
                arrayOf("run", "--isolated", "pipeline.kts"),
            ).forEach { args ->
                assertTrue(CliParser.parse(args) is CliParseResult.Parsed, "$args must parse")
            }
        }

        @Test
        fun `the isolated flag is carried into the parsed flags`() {
            val parsed = CliParser.parse(arrayOf("run", "--isolated", "pipeline.kts"))
            assertEquals(true, (parsed as CliParseResult.Parsed).flags.isolated)
        }

        @Test
        fun `a run without the flag does not request isolation`() {
            val parsed = CliParser.parse(arrayOf("run", "pipeline.kts"))
            assertEquals(false, (parsed as CliParseResult.Parsed).flags.isolated)
        }
    }
}
