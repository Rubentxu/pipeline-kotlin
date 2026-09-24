package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * WU-RP-012 — E2E coverage of `StashOperationsAdapter` against UAT-RP-008
 * (Stash: symlink a archivo exterior, enlace en directorios origen/destino y
 * nombre malicioso; no seguir escapes) and UAT-RP-009 (Stash roundtrip: stage
 * A stash, stage B unstash; mismo contenido y SHA; fresh/rerun/kill-resume por
 * políticas declaradas).
 *
 * Charter (ROADMAP L43): stash/unstash — no seguir symlinks fuera del workspace,
 * validar árbol destino/entrada y cierres bajo error/interrupción. Revisar
 * exposición de secretos y permisos. Comparar resultado frente a los recibos
 * WU-089/090 sin reescribirlos.
 *
 * The tests assert:
 *   1. stash() rejects a symlink file in the workspace pointing outside
 *   2. stash() rejects a symlink directory in the workspace pointing outside
 *   3. stash() happy path with regular files preserves content + sha256
 *   4. unstash() rejects `into` path that resolves outside the workspace
 *   5. unstash() rejects symlink file inside the stashRoot pointing outside
 *   6. stash→unstash roundtrip preserves content and sha256 (UAT-RP-009)
 *   7. unstash() happy path into workspace (regression)
 */
class StashOperationsAdapterUatTest {

    private fun sink(): InMemoryEventStore = InMemoryEventStore()

    private fun newAdapter(
        runId: String,
        controlDirRoot: Path,
        workspaceBase: Path,
        effectiveWorkingDirectory: Path? = null,
        eventSink: InMemoryEventStore = sink(),
    ): StashOperationsAdapter {
        Files.createDirectories(workspaceBase)
        return StashOperationsAdapter(
            runIdString = runId,
            stageIdentity = StageIdentity(name = "rp012-stage", index = 0),
            controlDirRoot = controlDirRoot,
            eventSink = eventSink,
            workspaceBase = workspaceBase,
            effectiveWorkingDirectory = effectiveWorkingDirectory,
        )
    }

    private fun sha256Of(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { stream ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * WU-RP-053 cut 5 RED: stash and unstash inherit the immutable cwd of
     * `dir(...)`. A stash in `dir("nested")` must neither capture files from
     * the enclosing workspace nor restore them there.
     */
    @Test
    fun `stash and unstash inside dir use effective cwd as their base`(
        @TempDir tmp: Path,
    ) {
        val checkout = Files.createDirectories(tmp.resolve("checkout"))
        val nested = Files.createDirectories(checkout.resolve("nested"))
        val rootFile = Files.writeString(checkout.resolve("keep-root.txt"), "root")
        val nestedFile = Files.writeString(nested.resolve("roundtrip.txt"), "nested")
        val adapter = newAdapter(
            runId = "rp053-stash-cwd",
            controlDirRoot = tmp.resolve("control"),
            workspaceBase = checkout,
            effectiveWorkingDirectory = nested,
        )

        val stashed = adapter.stash(StashInput(name = "nested", includes = "**/*.txt"))
        assertTrue(stashed is StashSuccess, "expected stash success, got $stashed")
        stashed as StashSuccess
        assertEquals(listOf("roundtrip.txt"), stashed.entries.map { it.relPath })

        Files.delete(nestedFile)
        val restored = adapter.unstash(UnstashInput(name = "nested"))
        assertTrue(restored is StashRestoredResult, "expected unstash success, got $restored")
        assertEquals("nested", Files.readString(nestedFile))
        assertEquals("root", Files.readString(rootFile))
    }

    // ============================================================================
    // WU-RP-012 — stash() symlink safety
    // ============================================================================

    @Test
    fun `rp012 — stash rejects a symlink file in the workspace pointing outside the workspace`(
        @TempDir tmp: Path,
    ) {
        // Setup: a regular file AND a symlink-to-external-file in the workspace.
        val workspaceBase = tmp.resolve("ws")
        Files.createDirectories(workspaceBase.resolve("src"))
        Files.writeString(workspaceBase.resolve("src").resolve("real.txt"), "<real/>")
        val external = tmp.resolve("external")
        Files.createDirectories(external)
        Files.writeString(external.resolve("secret.txt"), "SHOULD NOT BE STASHED")
        Files.createSymbolicLink(
            workspaceBase.resolve("src").resolve("evil-link.txt"),
            external.resolve("secret.txt"),
        )

        val adapter = newAdapter("rp012-symfile", tmp.resolve("ctrl"), workspaceBase)
        val r = adapter.stash(StashInput(name = "my-stash", includes = "src/**"))

        // The stash MUST be rejected (script-level) because the workspace contains
        // a symlink pointing outside it.
        assertTrue(
            r is StashFailed,
            "stash MUST reject when the workspace contains an external-pointing symlink; got $r",
        )
        r as StashFailed
        assertEquals(
            FailureKind.SCRIPT,
            r.failureKind,
            "the rejection MUST be typed as FailureKind.SCRIPT (script-level decision); got ${r.failureKind}",
        )
        assertTrue(
            r.message.contains("symlink"),
            "the rejection message MUST mention symlink; got '${r.message}'",
        )
        // The external file MUST NOT have been copied into the stash archive.
        val stashRoot = tmp.resolve("ctrl").resolve("stashes").resolve("rp012-symfile").resolve("my-stash")
        assertTrue(
            !Files.exists(stashRoot) || !Files.exists(stashRoot.resolve("src").resolve("evil-link.txt")),
            "no external file MUST be copied into the stashRoot",
        )
    }

    @Test
    fun `rp012 — stash rejects a symlink directory in the workspace pointing outside the workspace`(
        @TempDir tmp: Path,
    ) {
        // Setup: a subdirectory under workspace that is a symlink to an external tree.
        val workspaceBase = tmp.resolve("ws")
        Files.createDirectories(workspaceBase.resolve("src"))
        Files.writeString(workspaceBase.resolve("src").resolve("real.txt"), "<real/>")
        val external = tmp.resolve("external")
        Files.createDirectories(external.resolve("nested"))
        Files.writeString(external.resolve("nested").resolve("leak.txt"), "LEAKED CONTENT")
        Files.createSymbolicLink(
            workspaceBase.resolve("src").resolve("evil-dir"),
            external.resolve("nested"),
        )

        val adapter = newAdapter("rp012-symdir", tmp.resolve("ctrl"), workspaceBase)
        val r = adapter.stash(StashInput(name = "my-stash", includes = "src/**"))

        // The stash MUST be rejected; even if it succeeds, the externally-linked
        // files MUST NOT be copied.
        assertTrue(
            r is StashFailed || (r is StashSuccess && r.entries.all { !it.relPath.contains("evil-dir") }),
            "stash MUST NOT copy files from an externally-linked symlinked directory; got $r",
        )
        if (r is StashFailed) {
            assertEquals(FailureKind.SCRIPT, r.failureKind)
            assertTrue(
                r.message.contains("symlink"),
                "the rejection message MUST mention symlink; got '${r.message}'",
            )
        } else {
            r as StashSuccess
            assertTrue(
                r.entries.none { it.relPath.contains("evil-dir") },
                "no entry MUST come from the externally-linked directory; got entries=${r.entries.map { it.relPath }}",
            )
        }
    }

    @Test
    fun `rp012 — stash happy path with regular files preserves content and sha256`(
        @TempDir tmp: Path,
    ) {
        // Setup: three regular files in the workspace.
        val workspaceBase = tmp.resolve("ws")
        Files.createDirectories(workspaceBase.resolve("src"))
        Files.writeString(workspaceBase.resolve("src").resolve("a.txt"), "alpha")
        Files.writeString(workspaceBase.resolve("src").resolve("b.txt"), "bravo")
        Files.writeString(workspaceBase.resolve("src").resolve("c.txt"), "charlie")

        val adapter = newAdapter("rp012-happy", tmp.resolve("ctrl"), workspaceBase)
        val r = adapter.stash(StashInput(name = "happy", includes = "src/**"))

        assertTrue(r is StashSuccess, "stash of regular files MUST succeed; got $r")
        r as StashSuccess
        assertEquals(3, r.entries.size, "exactly three entries MUST be stashed; got ${r.entries.size}")

        // Verify each entry's content matches the source file.
        for (entry in r.entries) {
            val src = workspaceBase.resolve(entry.relPath)
            val stashed = tmp.resolve("ctrl").resolve("stashes").resolve("rp012-happy")
                .resolve("happy").resolve(entry.relPath)
            assertTrue(Files.exists(src), "source MUST exist: $src")
            assertTrue(Files.exists(stashed), "stashed MUST exist: $stashed")
            assertEquals(
                Files.readString(src),
                Files.readString(stashed),
                "content of $stashed MUST match source $src",
            )
            assertEquals(
                sha256Of(src),
                entry.sha256,
                "sha256 in entry MUST match the source; relPath=${entry.relPath}",
            )
        }
    }

    // ============================================================================
    // WU-RP-012 — unstash() symlink safety
    // ============================================================================

    @Test
    fun `rp012 — unstash rejects an into-path that resolves outside the workspace`(
        @TempDir tmp: Path,
    ) {
        // Setup: a valid stash, then a malicious `into` path that, when resolved
        // through symlinks, escapes the workspace. We pre-create a symlink at
        // `<ws>/evil-link` → `<tmp>/external`, then unstash into "evil-link".
        val workspaceBase = tmp.resolve("ws")
        Files.createDirectories(workspaceBase.resolve("src"))
        Files.writeString(workspaceBase.resolve("src").resolve("file.txt"), "ok")

        val external = tmp.resolve("external")
        Files.createDirectories(external)
        Files.createSymbolicLink(workspaceBase.resolve("evil-link"), external)

        val controlDirRoot = tmp.resolve("ctrl")
        val adapter = newAdapter("rp012-intosym", controlDirRoot, workspaceBase)

        // First, stash normally.
        val stashResult = adapter.stash(StashInput(name = "my-stash", includes = "src/**"))
        assertTrue(stashResult is StashSuccess, "prerequisite stash MUST succeed; got $stashResult")

        // Then unstash into the symlink.
        val r = adapter.unstash(UnstashInput(name = "my-stash", into = "evil-link"))

        assertTrue(
            r is StashFailed,
            "unstash into a symlink resolving outside the workspace MUST be rejected; got $r",
        )
        r as StashFailed
        assertEquals(
            FailureKind.SCRIPT,
            r.failureKind,
            "the rejection MUST be typed as FailureKind.SCRIPT; got ${r.failureKind}",
        )
        assertTrue(
            r.message.contains("symlink") || r.message.contains("escape") || r.message.contains("workspace"),
            "the rejection message MUST explain the confinement failure; got '${r.message}'",
        )
        // The external dir MUST be empty (no restore happened).
        val restored = external.resolve("src").resolve("file.txt")
        assertTrue(
            !Files.exists(restored),
            "no file MUST be restored into the externally-resolving `into` target; found $restored",
        )
    }

    @Test
    fun `rp012 — unstash rejects a symlink file inside the stashRoot pointing outside`(
        @TempDir tmp: Path,
    ) {
        // Setup: a valid stash, then inject a symlink into the stashRoot between
        // stash and unstash. The unstash MUST reject the symlink and not copy it.
        val workspaceBase = tmp.resolve("ws")
        Files.createDirectories(workspaceBase.resolve("src"))
        Files.writeString(workspaceBase.resolve("src").resolve("file.txt"), "ok")

        val controlDirRoot = tmp.resolve("ctrl")
        val adapter = newAdapter("rp012-trav", controlDirRoot, workspaceBase)
        val stashResult = adapter.stash(StashInput(name = "my-stash", includes = "src/**"))
        assertTrue(stashResult is StashSuccess, "prerequisite stash MUST succeed; got $stashResult")

        // Inject a symlink into the stashRoot AFTER the stash (simulates a tampered
        // archive or a bug in stash that left a symlink behind).
        val stashRoot = controlDirRoot.resolve("stashes").resolve("rp012-trav").resolve("my-stash")
        val external = tmp.resolve("external")
        Files.createDirectories(external)
        Files.writeString(external.resolve("leak.txt"), "LEAKED")
        Files.createSymbolicLink(stashRoot.resolve("src").resolve("evil-link.txt"), external.resolve("leak.txt"))

        val r = adapter.unstash(UnstashInput(name = "my-stash"))

        // The unstash MUST NOT silently copy the externally-linked file.
        // We accept either: explicit SCRIPT-level rejection OR a clean restore that
        // omits the symlink entry.
        assertTrue(
            r is StashFailed || (r is StashRestoredResult && r.entries.none { it.relPath.contains("evil-link") }),
            "unstash MUST NOT copy externally-linked files from the stashRoot; got $r",
        )
        if (r is StashFailed) {
            assertEquals(FailureKind.SCRIPT, r.failureKind)
            assertTrue(
                r.message.contains("symlink"),
                "the rejection message MUST mention symlink; got '${r.message}'",
            )
        } else {
            r as StashRestoredResult
            assertTrue(
                r.entries.none { it.relPath.contains("evil-link") },
                "no entry MUST come from the externally-linked symlink; got entries=${r.entries.map { it.relPath }}",
            )
        }
        // The external file MUST NOT have been copied into the workspace.
        val leakedInto = workspaceBase.resolve("src").resolve("evil-link.txt")
        assertTrue(
            !Files.exists(leakedInto) || Files.isSymbolicLink(leakedInto),
            "the leaked file MUST NOT have been copied into the workspace as a regular file; found $leakedInto",
        )
    }

    @Test
    fun `rp012 — stash followed by unstash is bit-exact roundtrip (UAT-RP-009)`(
        @TempDir tmp: Path,
    ) {
        // Setup: a regular workspace with files. Stash, then unstash into a fresh
        // workspace (same root, files cleared). Verify content and sha256 match.
        val workspaceBase = tmp.resolve("ws")
        Files.createDirectories(workspaceBase.resolve("dir"))
        Files.writeString(workspaceBase.resolve("top.txt"), "top")
        Files.writeString(workspaceBase.resolve("dir").resolve("nested.txt"), "nested content")

        // Compute expected sha256s and content before stash.
        val expectedFiles = listOf(
            workspaceBase.resolve("top.txt"),
            workspaceBase.resolve("dir").resolve("nested.txt"),
        )
        val expectedSha = expectedFiles.associateWith { sha256Of(it) }
        val expectedContent = expectedFiles.associateWith { Files.readString(it) }

        val controlDirRoot = tmp.resolve("ctrl")
        val adapter = newAdapter("rp012-roundtrip", controlDirRoot, workspaceBase)

        // Stash.
        val stashResult = adapter.stash(StashInput(name = "rt", includes = "**/*.txt"))
        assertTrue(stashResult is StashSuccess, "stash MUST succeed; got $stashResult")
        stashResult as StashSuccess
        // Capture sha256 reported by the stash event.
        val reportedStashSha = stashResult.entries.associate { it.relPath to it.sha256 }

        // Clear the workspace.
        Files.walk(workspaceBase).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
        Files.createDirectories(workspaceBase)

        // Unstash.
        val unstashResult = adapter.unstash(UnstashInput(name = "rt"))
        assertTrue(unstashResult is StashRestoredResult, "unstash MUST succeed; got $unstashResult")
        unstashResult as StashRestoredResult

        for ((rel, sha) in reportedStashSha) {
            val restored = workspaceBase.resolve(rel)
            assertTrue(Files.exists(restored), "restored file MUST exist: $restored")
            assertEquals(
                sha,
                sha256Of(restored),
                "restored sha256 MUST match stashed sha256; rel=$rel",
            )
            assertEquals(
                expectedContent[expectedFiles.first { it.fileName.toString() == File(rel).name && workspaceBase.relativize(it).toString() == rel }],
                Files.readString(restored),
                "restored content MUST match original; rel=$rel",
            )
        }
        // Also: every original file MUST be present in the unstash result.
        assertEquals(
            expectedSha.size,
            unstashResult.entries.size,
            "all original files MUST be present in the unstash result; expected ${expectedSha.size}, got ${unstashResult.entries.size}",
        )
    }

    @Test
    fun `rp012 — unstash happy path into workspace (regression)`(
        @TempDir tmp: Path,
    ) {
        // Regression: unstash without `into` restores everything at the workspace root.
        val workspaceBase = tmp.resolve("ws")
        Files.createDirectories(workspaceBase.resolve("sub"))
        Files.writeString(workspaceBase.resolve("sub").resolve("x.txt"), "x")

        val controlDirRoot = tmp.resolve("ctrl")
        val adapter = newAdapter("rp012-unstash-happy", controlDirRoot, workspaceBase)
        val stashResult = adapter.stash(StashInput(name = "h", includes = "sub/**"))
        assertTrue(stashResult is StashSuccess, "prerequisite stash MUST succeed; got $stashResult")

        // Clear workspace.
        Files.walk(workspaceBase).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
        Files.createDirectories(workspaceBase)

        val r = adapter.unstash(UnstashInput(name = "h"))
        assertTrue(r is StashRestoredResult, "unstash into workspace MUST succeed; got $r")
        r as StashRestoredResult
        assertEquals(1, r.entries.size)
        val restored = workspaceBase.resolve(r.entries[0].relPath)
        assertTrue(Files.exists(restored), "restored file MUST exist: $restored")
        assertEquals("x", Files.readString(restored))
    }
}

// java.io.File imported last (used only by the roundtrip test's string-name lookup).
private typealias File = java.io.File
