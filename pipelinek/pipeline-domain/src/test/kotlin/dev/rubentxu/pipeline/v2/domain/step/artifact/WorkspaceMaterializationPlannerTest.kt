package dev.rubentxu.pipeline.v2.domain.step.artifact

import dev.rubentxu.pipeline.v2.domain.digest.Sha256
import dev.rubentxu.pipeline.v2.domain.workspace.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.workspace.PathAnchor
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspaceLease
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class WorkspaceMaterializationPlannerTest {

    private val root: Path = Path.of("/ws/project")
    private val location = ExecutionLocation(
        workspace = WorkspaceLease.Managed(root),
        cwd = root,
    )
    private val validSha = Sha256.ofText("payload")

    private fun descriptor(
        dest: String,
        executable: Boolean = false,
        size: Long = 7L,
        sha: String = validSha,
    ) = AuthorizedFileDescriptor(
        sourcePath = Path.of("/artifacts/app.bin"),
        relativeDestination = dest,
        expectedSizeBytes = size,
        expectedSha256 = sha,
        executable = executable,
    )

    @Test
    fun `plans relative destination against cwd and respects dir scope`() {
        val scopedLocation = location.copy(cwd = root.resolve("services/api"))
        val request = MaterializationRequest(
            location = scopedLocation,
            files = listOf(descriptor("out/app.jar")),
        )

        val plan = WorkspaceMaterializationPlanner.plan(request)

        assertTrue(plan is MaterializationPlan.Planned, "expected Planned, got $plan")
        val entry = (plan as MaterializationPlan.Planned).entries.single()
        assertEquals(root.resolve("services/api/out/app.jar"), entry.resolvedDestination)
    }

    @Test
    fun `supports workspace root anchor when explicitly configured`() {
        val scopedLocation = location.copy(cwd = root.resolve("services/api"))
        val request = MaterializationRequest(
            location = scopedLocation,
            files = listOf(descriptor("out/app.jar")),
            policy = MaterializationPolicy(anchor = PathAnchor.WORKSPACE_ROOT),
        )

        val plan = WorkspaceMaterializationPlanner.plan(request)

        assertTrue(plan is MaterializationPlan.Planned, "expected Planned, got $plan")
        val entry = (plan as MaterializationPlan.Planned).entries.single()
        assertEquals(root.resolve("out/app.jar"), entry.resolvedDestination)
    }

    @Test
    fun `rejects empty blank and nul-byte destinations`() {
        for (bad in listOf("", "   ", "out/\u0000app.jar")) {
            val plan = WorkspaceMaterializationPlanner.plan(
                MaterializationRequest(location, listOf(descriptor(bad))),
            )
            assertTrue(plan is MaterializationPlan.Rejected, "expected rejection for '$bad'")
            assertTrue(
                (plan as MaterializationPlan.Rejected).error is MaterializationError.InvalidDestination,
                "expected InvalidDestination for '$bad', got ${plan.error}",
            )
        }
    }

    @Test
    fun `rejects absolute destination paths even inside workspace root`() {
        for (abs in listOf("/etc/passwd", "/ws/project/out/app.jar", "\\windows\\system32")) {
            val plan = WorkspaceMaterializationPlanner.plan(
                MaterializationRequest(location, listOf(descriptor(abs))),
            )
            assertTrue(plan is MaterializationPlan.Rejected, "expected rejection for '$abs'")
            assertTrue(
                (plan as MaterializationPlan.Rejected).error is MaterializationError.InvalidDestination,
                "expected InvalidDestination for '$abs', got ${plan.error}",
            )
        }
    }

    @Test
    fun `rejects parent traversal segments`() {
        for (traversal in listOf("../outside.txt", "sub/../../outside.txt", "sub/../other.txt")) {
            val plan = WorkspaceMaterializationPlanner.plan(
                MaterializationRequest(location, listOf(descriptor(traversal))),
            )
            assertTrue(plan is MaterializationPlan.Rejected, "expected rejection for '$traversal'")
            assertTrue(
                (plan as MaterializationPlan.Rejected).error is MaterializationError.PathTraversalRejected,
                "expected PathTraversalRejected for '$traversal', got ${plan.error}",
            )
        }
    }

    @Test
    fun `rejects duplicate destinations within the same request`() {
        val request = MaterializationRequest(
            location = location,
            files = listOf(descriptor("dist/app.jar"), descriptor("./dist/app.jar")),
        )

        val plan = WorkspaceMaterializationPlanner.plan(request)

        assertTrue(plan is MaterializationPlan.Rejected)
        assertTrue((plan as MaterializationPlan.Rejected).error is MaterializationError.DuplicateDestination)
    }

    @Test
    fun `rejects executable descriptor when policy forbids execute permissions`() {
        val request = MaterializationRequest(
            location = location,
            files = listOf(descriptor("bin/tool.sh", executable = true)),
            policy = MaterializationPolicy(executablePolicy = ExecutablePermissionPolicy.FORBID),
        )

        val plan = WorkspaceMaterializationPlanner.plan(request)

        assertTrue(plan is MaterializationPlan.Rejected)
        assertTrue((plan as MaterializationPlan.Rejected).error is MaterializationError.ExecutableNotAuthorized)
    }

    @Test
    fun `allows executable descriptor when policy explicitly authorizes it`() {
        val request = MaterializationRequest(
            location = location,
            files = listOf(descriptor("bin/tool.sh", executable = true)),
            policy = MaterializationPolicy(executablePolicy = ExecutablePermissionPolicy.ALLOW_AUTHORIZED),
        )

        val plan = WorkspaceMaterializationPlanner.plan(request)

        assertTrue(plan is MaterializationPlan.Planned)
    }

    @Test
    fun `descriptor validates non-negative size and lowercase sha256 hex`() {
        assertThrows(IllegalArgumentException::class.java) {
            descriptor("a.txt", size = -1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            descriptor("a.txt", sha = validSha.uppercase())
        }
        assertThrows(IllegalArgumentException::class.java) {
            descriptor("a.txt", sha = "not-a-sha256")
        }
    }

    @Test
    fun `converts archived file entry to non-executable authorized descriptor`() {
        val entry = ArchivedFileEntry(
            relPath = "build/libs/app.jar",
            sha256 = validSha,
            sizeBytes = 42L,
            absolutePath = "/control/artefacts/build/libs/app.jar",
        )

        val converted = entry.toAuthorizedDescriptor()

        assertEquals(Path.of("/control/artefacts/build/libs/app.jar"), converted.sourcePath)
        assertEquals("build/libs/app.jar", converted.relativeDestination)
        assertEquals(42L, converted.expectedSizeBytes)
        assertEquals(validSha, converted.expectedSha256)
        assertFalse(converted.executable)
    }
}
