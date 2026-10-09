package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import dev.rubentxu.pipeline.v2.sdk.files.DeleteDirExecutor
import dev.rubentxu.pipeline.v2.sdk.files.RootDestruction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * C8-M10 / C8-M10a — old bytes, decoded by the new runtime, and then confined.
 *
 * ## Why this exists next to the decode row
 *
 * `CoreDeleteDirStepUnitTest."input codec decodes legacy payload without path
 * field (defaults to dot)"` already proves the decode half: a payload written
 * before `path` existed still yields `DeleteDirInput(path = ".")`.
 *
 * That is the *decode* claim. The safety claim is a different one. A payload
 * with no `path` decodes to the workspace ROOT — it lands exactly where C8 is
 * about — and nothing connected that decoded value to the ownership check. A
 * decode round-trip is self-consistent by construction: a codec that decodes
 * its own mistake perfectly round-trips the mistake. The question this file
 * answers is whether the value the old bytes produce is then *refused* on a
 * user-owned workspace.
 *
 * ## Why it lives here and not in the SDK matrix
 *
 * The codec under test is `CoreDeleteDirStep.definition.contract.inputCodec`,
 * which lives in pipeline-application. `pipeline-step-sdk:files` must not
 * depend on pipeline-application, so the row cannot go there (attempted, and
 * `compileTestKotlin` reported `Unresolved reference 'CoreDeleteDirStep'` —
 * the dependency direction refusing the shortcut, which is the correct
 * outcome).
 *
 * See `docs/v2/07-uat/C8_WORKSPACE_ROOT_DELETION_RECEIPT.md`.
 */
@DisplayName("C8 — a legacy payload without `path` decodes to the root and is then confined")
class C8HistoricalPayloadAuthorizationTest {

    private fun seed(root: Path) {
        Files.createDirectories(root)
        Files.writeString(root.resolve("keep.txt"), "canary")
        Files.writeString(root.resolve("README.md"), "canary")
        Files.createDirectories(root.resolve("src"))
        Files.writeString(root.resolve("src/main.kt"), "canary")
    }

    private fun assertSurvives(root: Path, why: String) {
        for (canary in listOf("keep.txt", "README.md", "src/main.kt")) {
            assertTrue(
                Files.exists(root.resolve(canary)),
                "$why — canary '$canary' is gone from $root",
            )
        }
    }

    /** Bytes from before `path` existed on the wire. */
    private fun decodeLegacyWithoutPath(): DeleteDirInput =
        CoreDeleteDirStep.definition.contract.inputCodec
            .decode(EncodedStepValue("""{"kind":"deleteDir"}"""))

    @Test
    @DisplayName("C8-M10 the legacy payload still decodes to the root, unchanged")
    fun `C8-M10 legacy payload without path decodes to the root`() {
        assertEquals(
            ".",
            decodeLegacyWithoutPath().path,
            "a payload with no `path` must keep decoding to the root. Changing this would " +
                "reinterpret existing durable history under new semantics (DR-10).",
        )
    }

    @Test
    @DisplayName("C8-M10a the decoded root is REFUSED on a user-owned workspace, zero effects")
    fun `C8-M10a decoded root is refused when the workspace is user-owned`(@TempDir tempDir: Path) {
        seed(tempDir)
        val decoded = decodeLegacyWithoutPath()

        val executor = DeleteDirExecutor(
            workspaceResolver = { _, _ -> tempDir },
            rootDestruction = RootDestruction.UserOwned,
        )

        assertThrows(IllegalArgumentException::class.java) {
            executor.execute("stage", 0, 0, StepSpec.DeleteDir(path = decoded.path))
        }
        assertSurvives(tempDir, "a legacy payload decoded to the root and wiped a user workspace")
        assertFalse(
            Files.exists(tempDir.resolve(".deleted")),
            "the MEMOIZED marker must not be written when the operation was refused; a marker " +
                "on a refused run would make a later replay believe the wipe already happened",
        )
    }

    @Test
    @DisplayName("C8-M10b the same payload DOES wipe a scratch workspace, so M10a is not vacuous")
    fun `C8-M10b decoded root wipes when the workspace is scratch`(@TempDir tempDir: Path) {
        // If C8-M10a passed because the Step is broken rather than because
        // ownership protects the workspace, this row fails. Confinement that
        // refuses everything is not confinement.
        seed(tempDir)
        val decoded = decodeLegacyWithoutPath()

        val executor = DeleteDirExecutor(
            workspaceResolver = { _, _ -> tempDir },
            rootDestruction = RootDestruction.ScratchOwned,
        )
        executor.execute("stage", 0, 0, StepSpec.DeleteDir(path = decoded.path))

        assertFalse(
            Files.exists(tempDir.resolve("keep.txt")),
            "ScratchOwned must still wipe the root. If this fails, C8-M10a is passing for the " +
                "wrong reason and proves nothing about ownership.",
        )
        assertTrue(
            Files.exists(tempDir.resolve(".deleted")),
            "the MEMOIZED marker must be written for the idempotent replay path",
        )
    }

    @Test
    @DisplayName("C8-M10c an unresolved ownership verdict on a legacy payload also fails closed")
    fun `C8-M10c legacy payload fails closed when ownership is undecided`(@TempDir tempDir: Path) {
        // The state the old Boolean could not express. A payload from history
        // arriving when nothing has resolved who owns the workspace must not
        // resolve toward destruction.
        seed(tempDir)
        val decoded = decodeLegacyWithoutPath()

        val executor = DeleteDirExecutor(
            workspaceResolver = { _, _ -> tempDir },
            rootDestruction = RootDestruction.DecidedElsewhere,
        )

        assertThrows(IllegalArgumentException::class.java) {
            executor.execute("stage", 0, 0, StepSpec.DeleteDir(path = decoded.path))
        }
        assertSurvives(tempDir, "an undecided ownership verdict resolved toward destruction")
    }
}
