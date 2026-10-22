package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.workspace.DestructiveAuthorization
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspaceOwnership
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspacePathResolver
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * A root nobody claimed is not a root the product may destroy.
 *
 * The destructive permission is granted by exactly one case of the lease:
 * `Managed`, which asserts that PipelineK allocated this root. `Attached`
 * refuses it. A root whose owner was never stated has asserted nothing, so it
 * has to arrive as the refusing case — and it used to arrive as the permitting
 * one, while the adapter's own documentation called that the fail-closed
 * choice when it was the fail-open one.
 *
 * The path to it was live, not theoretical: `ShOptions.EMPTY` states no owner
 * and is the default of `CanonicalDurableRunCoordinator`, and it points at a
 * shared temp directory. An unaccredited root that could be deleted was a real
 * possibility, not a shape on paper.
 *
 * Pure and total: the lease and the authorisation are values, and nothing here
 * touches a disk, a clock or a process.
 */
class UnaccreditedWorkspaceOwnershipTest {

    private val root: Path = Path.of("/w/root")

    private fun leaseFor(ownership: WorkspaceOwnership?) =
        ShOptionsExecutionLocationAdapter.from(
            workspaceRoot = root,
            scopedWorkingDirectory = null,
            fallbackRoot = root,
            ownership = ownership,
        ).workspace

    private fun authorizationFor(ownership: WorkspaceOwnership?) =
        WorkspacePathResolver.authorizeRootDestruction(leaseFor(ownership), "deleteDir")

    @Test
    @DisplayName("a root with no stated owner is not ours to destroy")
    fun aRootWithNoStatedOwnerIsNotOursToDestroy() {
        val authorization = authorizationFor(null)

        assertTrue(
            authorization is DestructiveAuthorization.Refused,
            "an unstated owner must never grant the destructive permission: $authorization",
        )
    }

    @Test
    @DisplayName("a root PipelineK allocated keeps its cleanup permission")
    fun aRootPipelineKAllocatedKeepsItsCleanupPermission() {
        val authorization = authorizationFor(WorkspaceOwnership.PIPELINEK)

        assertTrue(
            authorization is DestructiveAuthorization.Permitted,
            "PipelineK's own scratch is disposable and stays deletable: $authorization",
        )
    }

    @Test
    @DisplayName("a root the user owns refuses destruction")
    fun aRootTheUserOwnsRefusesDestruction() {
        val authorization = authorizationFor(WorkspaceOwnership.USER)

        assertTrue(
            authorization is DestructiveAuthorization.Refused,
            "a user-owned root is never ours to destroy: $authorization",
        )
    }
}
