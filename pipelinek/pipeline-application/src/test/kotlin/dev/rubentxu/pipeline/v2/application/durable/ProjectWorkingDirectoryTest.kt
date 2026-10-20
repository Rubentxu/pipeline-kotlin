package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.BlockStepNode
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The working-directory projection is the one context dimension that decides WHERE a shell Step
 * writes, so its rejection rule is a security property rather than a formatting detail: a
 * regression here does not fail a test, it writes a file Step's output outside the workspace and
 * still reports Success.
 *
 * These are pure decisions over `Path`, with no filesystem access and no clock, which is why they
 * belong in a unit test at all.
 */
class ProjectWorkingDirectoryTest {

    private val workspace: Path = Path.of("/w/base")

    private fun options(workingDirectory: Path? = null): ShOptions = ShOptions(
        workspaceRoot = workspace,
        captureStdout = false,
        timeoutMs = null,
        env = emptyMap(),
        workingDirectory = workingDirectory,
    )

    private fun node(path: String?): BlockStepNode = BlockStepNode(
        id = StepId("step-1"),
        pluginStepId = PluginStepId("plugin-1"),
        payload = VersionedStepPayload(
            schemaVersion = "1",
            encoded = if (path == null) """{}""" else """{"path":${quote(path)}}""",
        ),
        body = emptyList(),
    )

    private fun quote(raw: String): String = "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun reject(path: String?): String {
        val projection = node(path).projectWorkingDirectory(options())
        val invalid = assertInstanceOf(BodyExecutionProjection.InvalidInput::class.java, projection) {
            "expected $path to be rejected, got $projection"
        }
        return invalid.detail
    }

    private fun accept(path: String): BodyExecutionProjection.Scope =
        assertInstanceOf(BodyExecutionProjection.Scope::class.java, node(path).projectWorkingDirectory(options()))

    @Test
    fun `a payload with no path is rejected rather than defaulting to the workspace root`() {
        assertEquals("requires a path", reject(null))
    }

    @Test
    fun `a blank path is rejected rather than resolving to the workspace root`() {
        assertEquals("path must not be blank", reject("   "))
    }

    @Test
    fun `a relative path that climbs out of the workspace is rejected`() {
        val detail = reject("../outside")
        assertTrue(detail.contains("escapes the workspace"), "was: $detail")
    }

    @Test
    fun `a relative path is rejected even when it only shares a string prefix with the workspace`() {
        // `startsWith` on String would admit this one: "/w/base-evil" starts with "/w/base".
        // Path.startsWith compares ELEMENTS, so the sibling directory is outside and must be
        // refused. If a future edit swaps Path.startsWith for a string comparison, this row is
        // the one that turns red.
        val detail = reject("../base-evil/x")
        assertTrue(detail.contains("escapes the workspace"), "was: $detail")
    }

    @Test
    fun `a relative path inside the workspace is accepted and resolved against it`() {
        val scope = accept("sub/dir").scope
        val directory = assertInstanceOf(BlockShellScope.Directory::class.java, scope)
        assertEquals(workspace.resolve("sub/dir"), directory.target)
        assertEquals(workspace, directory.previous)
    }

    @Test
    fun `the current working directory, when set, is the boundary rather than the workspace root`() {
        // Containment is measured against `workingDirectory ?: workspaceRoot`. A body that moved
        // to /w/base/sub may still be refused from leaving /w/base/sub, even though leaving
        // /w/base would be allowed -- the boundary travels with the body.
        val current = workspace.resolve("sub")
        val projection = node("../other").projectWorkingDirectory(options(workingDirectory = current))
        val invalid = assertInstanceOf(BodyExecutionProjection.InvalidInput::class.java, projection)
        assertTrue(invalid.detail.contains("escapes the workspace"), "was: ${invalid.detail}")

        val inside = assertInstanceOf(BodyExecutionProjection.Scope::class.java, node("deep").projectWorkingDirectory(options(workingDirectory = current)))
        val directory = assertInstanceOf(BlockShellScope.Directory::class.java, inside.scope)
        assertEquals(current.resolve("deep"), directory.target)
        assertEquals(current, directory.previous)
    }

    @Test
    fun `a path that normalises back inside the workspace is accepted`() {
        // "sub/../sub2" resolves to /w/base/sub2, which IS inside. Refusing it would be refusing a
        // path the caller can legitimately name, so the rule is on the RESOLVED location.
        val directory = assertInstanceOf(BlockShellScope.Directory::class.java, accept("sub/../sub2").scope)
        assertEquals(workspace.resolve("sub2"), directory.target)
    }

    @Test
    fun `an absolute path outside the boundary is rejected`() {
        // This is the one that was open. Absolute paths used to skip the check entirely, so
        // `dir("/etc/cron.d")` in a .pipeline.kts projected a Scope, BodyExecutionEngine ran
        // Files.createDirectories on it and handed it to the child as its CWD. ADR-0100 section 8/9
        // says an absolute path outside the root is rejected, and WorkspacePathResolver and
        // DirExecutor both implement that; this projection was the live guard and did not.
        val detail = reject("/etc/cron.d")
        assertTrue(detail.contains("escapes the workspace"), "was: $detail")
    }

    @Test
    fun `an absolute path inside the boundary is accepted`() {
        // Not every absolute path is a hole: ADR-0100 accepts one that stays inside the root,
        // because some Step contracts name one. Rejecting all absolutes would break that.
        val directory = assertInstanceOf(BlockShellScope.Directory::class.java, accept("/w/base/sub").scope)
        assertEquals(workspace.resolve("sub"), directory.target)
    }

    @Test
    fun `a boundary carrying a dot element still accepts its own absolute child`() {
        // Path.of keeps the "." element, and startsWith compares elements, so an unnormalised root
        // refuses the very child it contains: /w/./base does not start with /w/base's resolution
        // once the target is normalised. Measured, not assumed: with the raw root the comparison is
        // false for both `/w/./base` and `/w/base/.`, and a trailing slash is harmless.
        val unnormalised = ShOptions(
            workspaceRoot = Path.of("/w/./base"),
            captureStdout = false,
            timeoutMs = null,
            env = emptyMap(),
        )
        val projection = node("/w/base/sub").projectWorkingDirectory(unnormalised)
        val scope = assertInstanceOf(BodyExecutionProjection.Scope::class.java, projection) {
            "an unnormalised boundary refused its own child: $projection"
        }
        val directory = assertInstanceOf(BlockShellScope.Directory::class.java, scope.scope)
        assertEquals(workspace.resolve("sub"), directory.target)
        assertEquals(workspace, directory.previous)
    }
}
