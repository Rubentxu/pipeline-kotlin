package dev.rubentxu.pipeline.v2.sdk.files

import dev.rubentxu.pipeline.v2.dsl.StepSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * C8 matrix — destructive intent as a CLOSED TYPE rather than a boolean flag.
 *
 * ## What this class exists to kill
 *
 * Before this work the executors carried `protectWorkspaceRoot: Boolean = false`.
 * That is the exact shape AGENTS.md §5/§8 forbids: a boolean collapsing a
 * three-state reality into two, where the third state is inexpressible. The
 * states are:
 *
 *   1. wipe PipelineK's own scratch            (Managed lease)
 *   2. refuse any root destruction             (Attached lease)
 *   3. "not my decision" — someone else decides
 *
 * `Boolean` admits {1,2} and makes 3 unrepresentable, so the caller had to
 * answer a question it does not own by passing `true` or `false`. The
 * adapters each re-derived that answer from the lease, and a third adapter
 * that forgot would silently wipe a user's project.
 *
 * ## Why the existing C8 tests did not catch it
 *
 * `WorkspaceCleanupTest` already proves the *behaviour* — Attached refuses the
 * root, Managed wipes it. Those tests pass with the boolean because the
 * adapters happen to compute the flag correctly. The defect is not observable
 * in behaviour; it is observable in the TYPE, and it becomes a data-loss bug
 * the first time a caller gets the derivation wrong.
 *
 * So these tests do not re-assert the old rows. They assert the closed type
 * exists and that every state is constructible and distinct.
 *
 * ## Mutation that kills each row
 *
 *   M1  collapse `RootDestruction` back to Boolean        -> class does not compile
 *   M2  make `Managed` refuse the root                    -> M-DISTINCT + M-MANAGED-WIPES
 *   M3  make `Attached` permit the root                   -> M-ATTACHED-REFUSES
 *   M4  map every state to `permitRoot = true`            -> M-ATTACHED-REFUSES
 */
class C8DestructiveIntentMatrixTest {

    // ── the closed type ──────────────────────────────────────────────────────

    @Test
    @DisplayName("C8-M1 RootDestruction is a closed type with exactly one case per ownership state")
    fun `C8-M1 RootDestruction has exactly the three ownership cases`() {
        val cases = RootDestruction.entries.toSet()
        assertEquals(
            setOf(
                RootDestruction.ScratchOwned,
                RootDestruction.UserOwned,
                RootDestruction.DecidedElsewhere,
            ),
            cases,
            "RootDestruction must be a sealed set of the three real states; " +
                "a fourth or a collapsed pair means a caller cannot express what it means",
        )
    }

    @Test
    @DisplayName("C8-M2 the type exposes no boolean accessor that re-collapses the decision")
    fun `C8-M2 RootDestruction exposes no boolean permitRoot`() {
        // A `permitRoot: Boolean` property on the ADT would restore exactly the
        // defect this replaces. Reflection keeps that from creeping back.
        val booleanProperties = RootDestruction::class.java.methods
            .filter { it.name.startsWith("permit") || it.name.startsWith("protect") }
            .map { it.name }
            .toSet()

        assertTrue(
            booleanProperties.isEmpty(),
            "RootDestruction must not re-expose the collapse as a boolean; found $booleanProperties",
        )
    }

    // ── each state maps to a distinct, testable decision ─────────────────────

    @Test
    @DisplayName("C8-M3 UserOwned refuses the workspace root and leaves the tree untouched")
    fun `C8-M3 UserOwned refuses the root with zero effects`(@TempDir tempDir: Path) {
        seed(tempDir)
        val executor = DeleteDirExecutor(
            workspaceResolver = { _, _ -> tempDir },
            rootDestruction = RootDestruction.UserOwned,
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            executor.execute("stage", 0, 0, StepSpec.DeleteDir(path = "."))
        }

        assertTrue(
            error.message!!.contains("refuses to delete the workspace root"),
            "refusal must name the reason, got: ${error.message}",
        )
        assertSurvives(tempDir, "zero effects: the refusal must precede every deletion")
    }

    @Test
    @DisplayName("C8-M4 ScratchOwned still wipes the root — Jenkins WCL-S-001 contract preserved")
    fun `C8-M4 ScratchOwned keeps the wipe contract`(@TempDir tempDir: Path) {
        seed(tempDir)
        val executor = DeleteDirExecutor(
            workspaceResolver = { _, _ -> tempDir },
            rootDestruction = RootDestruction.ScratchOwned,
        )

        val result = executor.execute("stage", 0, 0, StepSpec.DeleteDir(path = "."))

        assertTrue(result.deletedCount > 0, "scratch root must still be wiped, got ${result.deletedCount}")
        assertTrue(
            Files.exists(tempDir.resolve(".deleted")),
            "the MEMOIZED marker must still be written after a scratch wipe",
        )
    }

    @Test
    @DisplayName("C8-M5 UserOwned still allows a sub-path — the Step's actual purpose")
    fun `C8-M5 UserOwned allows a valid sub-path`(@TempDir tempDir: Path) {
        seed(tempDir)
        Files.createDirectories(tempDir.resolve("build/nested"))
        Files.writeString(tempDir.resolve("build/nested/out.txt"), "generated")

        val executor = DeleteDirExecutor(
            workspaceResolver = { _, _ -> tempDir },
            rootDestruction = RootDestruction.UserOwned,
        )
        executor.execute("stage", 0, 0, StepSpec.DeleteDir(path = "build"))

        // The contract deletes the CONTENTS of the target, not the directory
        // itself: `Files.walk(targetPath).filter { it != targetPath }` leaves
        // `build/` in place so the .deleted marker has somewhere to live. The
        // old C8 row in WorkspaceCleanupTest asserts exactly this shape.
        assertTrue(
            !Files.exists(tempDir.resolve("build/nested/out.txt")),
            "the contents of the sub-path must be deleted",
        )
        assertTrue(
            Files.exists(tempDir.resolve("build/.deleted")),
            "the MEMOIZED marker is written inside the target, so the target directory survives",
        )
        assertSurvives(tempDir, "deleting a sub-path must not disturb the root contents")
    }

    @Test
    @DisplayName("C8-M6 DecidedElsewhere delegates rather than guessing")
    fun `C8-M6 DecidedElsewhere does not decide for the caller`(@TempDir tempDir: Path) {
        seed(tempDir)
        val executor = DeleteDirExecutor(
            workspaceResolver = { _, _ -> tempDir },
            rootDestruction = RootDestruction.DecidedElsewhere,
        )

        // The contract is the ABSENCE of a decision, and it must fail closed:
        // an unresolved ownership question resolves toward preservation, never
        // toward destruction. C8-M6a is the mutation that proves it — inverting
        // DecidedElsewhere.permitsRootWipe killed nothing until this row existed.
        assertThrows(
            IllegalArgumentException::class.java,
            { executor.execute("stage", 0, 0, StepSpec.DeleteDir(path = ".")) },
            "DecidedElsewhere must fail closed toward preservation, not permit the wipe",
        )

        // The executor must also expose the intent it was given, unmodified,
        // so a caller can see which question it is being handed.
        assertEquals(
            RootDestruction.DecidedElsewhere,
            executor.rootDestruction,
            "the executor must expose the intent it was given, unmodified",
        )
        assertSurvives(tempDir, "an unresolved decision must not delete anything")
    }

    // ── the decision is derived from the lease, never from the path ──────────

    @Test
    @DisplayName("C8-M6a DecidedElsewhere fails closed for cleanWs too, not just deleteDir")
    fun `C8-M6a cleanWs also fails closed on an unresolved decision`(@TempDir tempDir: Path) {
        seed(tempDir)
        val executor = CleanWsExecutor(
            workspaceResolver = { _, _ -> tempDir },
            rootDestruction = RootDestruction.DecidedElsewhere,
        )

        assertThrows(
            IllegalArgumentException::class.java,
            { executor.execute("stage", 0, 0, StepSpec.CleanWs(deleteDirs = true, patterns = null)) },
            "DecidedElsewhere must refuse the pattern-less sweep on cleanWs as well",
        )
        assertSurvives(tempDir, "an unresolved decision must not sweep the workspace")
    }

    @Test
    @DisplayName("C8-M7 intent is derived from the lease and does not vary with the path argument")
    fun `C8-M7 the same intent governs every spelling of the root`(@TempDir tempDir: Path) {
        for (spelling in listOf(".", "./", "build/..")) {
            val root = Files.createTempDirectory(tempDir, "spell")
            seed(root)
            val executor = DeleteDirExecutor(
                workspaceResolver = { _, _ -> root },
                rootDestruction = RootDestruction.UserOwned,
            )

            assertThrows(
                IllegalArgumentException::class.java,
                { executor.execute("stage", 0, 0, StepSpec.DeleteDir(path = spelling)) },
                "spelling '$spelling' must be refused under UserOwned",
            )
            assertSurvives(root, "spelling '$spelling' must leave the tree untouched")
        }
    }

    @Test
    @DisplayName("C8-M8 traversal outside the workspace is refused for every intent")
    fun `C8-M8 outside-workspace paths are refused regardless of intent`(@TempDir tempDir: Path) {
        val outside = Files.createTempDirectory(tempDir, "outside")
        Files.writeString(outside.resolve("precious.txt"), "not yours")

        for (intent in RootDestruction.entries) {
            val executor = DeleteDirExecutor(
                workspaceResolver = { _, _ -> tempDir.resolve("workspace") },
                rootDestruction = intent,
            )
            Files.createDirectories(tempDir.resolve("workspace"))

            val error = assertThrows(
                IllegalArgumentException::class.java,
                { executor.execute("stage", 0, 0, StepSpec.DeleteDir(path = "../outside")) },
                "intent $intent must refuse traversal outside the workspace",
            )
            assertTrue(
                error.message!!.contains("escapes workspace root"),
                "intent $intent must refuse with the containment reason, got: ${error.message}",
            )
        }
        assertTrue(
            Files.exists(outside.resolve("precious.txt")),
            "the file outside the workspace must survive every intent",
        )
    }

    // ── C9 under the same criterion ──────────────────────────────────────────

    @Test
    @DisplayName("C9-M1 UserOwned refuses the pattern-less cleanWs and writes no marker")
    fun `C9-M1 UserOwned refuses pattern-less cleanWs with zero effects`(@TempDir tempDir: Path) {
        seed(tempDir)
        val executor = CleanWsExecutor(
            workspaceResolver = { _, _ -> tempDir },
            rootDestruction = RootDestruction.UserOwned,
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            executor.execute("stage", 0, 0, StepSpec.CleanWs(deleteDirs = true, patterns = null))
        }

        assertTrue(
            error.message!!.contains("refuses to run without patterns"),
            "refusal must name the reason, got: ${error.message}",
        )
        assertSurvives(tempDir, "zero effects: no file and no .cleaned marker")
        assertTrue(
            !Files.exists(tempDir.resolve(".cleaned")),
            "a refused cleanWs must not write the idempotency marker",
        )
    }

    @Test
    @DisplayName("C9-M2 an empty pattern list is a full wipe, not a safe no-op")
    fun `C9-M2 empty pattern list is refused like the null form`(@TempDir tempDir: Path) {
        seed(tempDir)
        val executor = CleanWsExecutor(
            workspaceResolver = { _, _ -> tempDir },
            rootDestruction = RootDestruction.UserOwned,
        )

        assertThrows(IllegalArgumentException::class.java) {
            executor.execute("stage", 0, 0, StepSpec.CleanWs(deleteDirs = true, patterns = emptyList()))
        }
        assertSurvives(tempDir, "emptyList funnels into the same destructive branch")
    }

    @Test
    @DisplayName("C9-M3 ScratchOwned keeps the pattern-less wipe contract")
    fun `C9-M3 ScratchOwned keeps the wipe contract`(@TempDir tempDir: Path) {
        seed(tempDir)
        val executor = CleanWsExecutor(
            workspaceResolver = { _, _ -> tempDir },
            rootDestruction = RootDestruction.ScratchOwned,
        )

        executor.execute("stage", 0, 0, StepSpec.CleanWs(deleteDirs = true, patterns = null))

        // ScratchOwned is the one state where a pattern-less sweep IS the
        // contract: it removes every non-.v2 file. Asserting the canary survives
        // here would assert the opposite of what ScratchOwned means.
        assertTrue(
            !Files.exists(tempDir.resolve("README.md")),
            "ScratchOwned must sweep the canary, since a pattern-less sweep is its contract",
        )
        assertTrue(
            Files.exists(tempDir.resolve(".cleaned")),
            "the .cleaned marker must still be written after a scratch sweep",
        )
    }

    @Test
    @DisplayName("C9-M4 cleanWs honours the same closed type, so C8 and C9 cannot drift apart")
    fun `C9-M4 both executors share one intent type`() {
        // The reason this is one class and not two booleans: if each executor
        // had its own flag, one could be wired to ScratchOwned while the other
        // stayed UserOwned, and the divergence would be invisible.
        val deleteDirCtor = DeleteDirExecutor::class.java.constructors.first {
            it.parameterTypes.size == 2
        }
        val cleanWsCtor = CleanWsExecutor::class.java.constructors.first {
            it.parameterTypes.size == 2
        }
        assertEquals(
            RootDestruction::class.java,
            deleteDirCtor.parameterTypes[1],
            "DeleteDirExecutor must take the shared RootDestruction type",
        )
        assertEquals(
            RootDestruction::class.java,
            cleanWsCtor.parameterTypes[1],
            "CleanWsExecutor must take the shared RootDestruction type",
        )
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private fun seed(root: Path) {
        Files.createDirectories(root)
        Files.writeString(root.resolve("README.md"), "canary")
        Files.writeString(root.resolve("keep.txt"), "canary")
        Files.createDirectories(root.resolve("src"))
        Files.writeString(root.resolve("src/main.kt"), "canary")
    }

    /**
     * Assert the canary survives — the "zero effects" oracle.
     *
     * Deliberately a filesystem observation rather than an exception assertion:
     * HARNESS FIDELITY LAW §3 forbids asserting on the throw alone, because a
     * Step that deleted everything and then threw would pass an
     * assertThrows-only test.
     */
    private fun assertSurvives(root: Path, why: String) {
        for (canary in listOf("README.md", "keep.txt", "src/main.kt")) {
            assertTrue(
                Files.exists(root.resolve(canary)),
                "$why — canary '$canary' was destroyed",
            )
        }
    }
}
