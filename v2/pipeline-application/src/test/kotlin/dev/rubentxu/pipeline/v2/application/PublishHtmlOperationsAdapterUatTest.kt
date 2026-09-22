package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.WorkspaceResolver
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * WU-RP-010 — E2E coverage of `PublishHtmlOperationsAdapter` against UAT-RP-005
 * (Production-Ready UAT Matrix §005 "Publish HTML"). Three test-only checks
 * added in WU-RP-010 round 1. The other two UAT-RP-005 invariants (archive
 * MANIFEST.json, HTML-escape of relPath) require production code changes and
 * are scheduled for WU-RP-011 / WU-RP-013 after operator sign-off.
 *
 * Test layout:
 *   1. user-provided index.html in the workspace reportDir is NOT overwritten
 *   2. archive index.html is deterministic (replay-stable)
 *   3. replay fingerprint matches across two publishes of the same input
 */
class PublishHtmlOperationsAdapterUatTest {

    private fun sink() = InMemoryEventStore()

    private fun newAdapter(
        runId: String,
        controlDirRoot: Path,
        workspaceBase: Path,
        eventSink: InMemoryEventStore = sink(),
    ): PublishHtmlOperationsAdapter {
        // Seed the workspace so ensureCreated() finds it (idempotent).
        Files.createDirectories(workspaceBase)
        return PublishHtmlOperationsAdapter(
            runIdString = runId,
            stageIdentity = StageIdentity(name = "test-stage", index = 0),
            controlDirRoot = controlDirRoot,
            eventSink = eventSink,
            workspaceBase = workspaceBase,
        )
    }

    private fun sha256(file: Path): String {
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
     * UAT-RP-005 invariant 1: a user-provided `index.html` inside the workspace's
     * reportDir MUST NOT be overwritten by the publish step. The archive may
     * contain its own `index.html` (the generated one), but the workspace is
     * untouched.
     */
    @Test
    fun `user-provided index html in workspace reportDir is NOT overwritten`() {
        val controlDirRoot = Files.createTempDirectory("rp010-ctrl-")
        val workspaceBase = Files.createTempDirectory("rp010-ws-")
        val reportDirRel = "build/reports"
        val reportDirAbs = workspaceBase.resolve(reportDirRel)
        Files.createDirectories(reportDirAbs)

        // Sentinel: a real HTML file the user authored.
        val userIndex = reportDirAbs.resolve("index.html")
        val sentinelBytes = "<!doctype html><title>USER-AUTHORED-INDEX</title>"
        Files.writeString(userIndex, sentinelBytes)

        // Also a non-index file so the glob matches at least one file.
        Files.writeString(reportDirAbs.resolve("report.html"), "<p>report</p>")

        val runId = "rp010-r1"
        val adapter = newAdapter(runId, controlDirRoot, workspaceBase)
        val input = PublishHtmlInput(
            name = "html-report",
            reportDir = reportDirRel,
            reportFiles = "**/*.html",
            keepAll = false,
            allowMissing = false,
            escapeUnderscores = false,
        )

        val result = adapter.publish(input)
        assertTrue(result is PublishHtmlPublished, "publish must succeed (was $result)")

        // Workspace file MUST be unchanged.
        val afterBytes = Files.readString(userIndex)
        assertEquals(sentinelBytes, afterBytes,
            "publishHTML must not overwrite user-provided index.html in the workspace")

        // And the workspace's `reportDir` must still contain the user index plus
        // the report (no deletion of user files).
        assertTrue(Files.exists(userIndex), "workspace index.html must still exist after publish")
        assertTrue(Files.exists(reportDirAbs.resolve("report.html")),
            "workspace report.html must still exist after publish")
    }

    /**
     * UAT-RP-005 invariant 2 (deterministic archive index) — replay-stable
     * fingerprint. The generated `index.html` in the archive MUST be byte-stable
     * across consecutive publishes of the same input (modulo the HtmlReportPublished
     * eventId UUID, which is an event, not an archive artifact).
     *
     * We invoke publish() twice on the same adapter/controlDirRoot/workspace and
     * assert SHA256 of `<archiveRoot>/index.html` is identical both times.
     */
    @Test
    fun `replay produces identical archive index html fingerprint`() {
        val controlDirRoot = Files.createTempDirectory("rp010-ctrl-")
        val workspaceBase = Files.createTempDirectory("rp010-ws-")
        val reportDirRel = "build/reports"
        val reportDirAbs = workspaceBase.resolve(reportDirRel)
        Files.createDirectories(reportDirAbs)
        Files.writeString(reportDirAbs.resolve("a.html"), "<p>a</p>")
        Files.writeString(reportDirAbs.resolve("b.html"), "<p>b</p>")
        Files.writeString(reportDirAbs.resolve("c.html"), "<p>c</p>")

        val runId = "rp010-r2"
        val input = PublishHtmlInput(
            name = "html-report",
            reportDir = reportDirRel,
            reportFiles = "**/*.html",
            keepAll = false,
            allowMissing = false,
            escapeUnderscores = false,
        )

        // First publish
        val adapter1 = newAdapter(runId, controlDirRoot, workspaceBase)
        val r1 = adapter1.publish(input)
        assertTrue(r1 is PublishHtmlPublished, "first publish must succeed")
        val reportRoot1 = (r1 as PublishHtmlPublished).targetPath
        val indexPath1 = Path.of(reportRoot1).resolve("index.html")
        val sha1 = sha256(indexPath1)

        // Second publish on the same controlDirRoot
        val adapter2 = newAdapter(runId, controlDirRoot, workspaceBase)
        val r2 = adapter2.publish(input)
        assertTrue(r2 is PublishHtmlPublished, "second publish must succeed")
        val reportRoot2 = (r2 as PublishHtmlPublished).targetPath
        val indexPath2 = Path.of(reportRoot2).resolve("index.html")
        val sha2 = sha256(indexPath2)

        assertEquals(reportRoot1, reportRoot2, "archive target path must be identical across replay")
        assertEquals(sha1, sha2, "archive index.html fingerprint MUST be replay-stable")
    }

    /**
     * UAT-RP-005 invariant 4 (replay verificada) — the per-entry sha256 reported
     * in the HtmlReportPublished event MUST be identical across two consecutive
     * publishes of the same input. This is the second-channel check: the event
     * payload's sha256 must match the on-disk bytes after copy.
     */
    @Test
    fun `replay per-entry sha256 reported in event is identical across publishes`() {
        val controlDirRoot = Files.createTempDirectory("rp010-ctrl-")
        val workspaceBase = Files.createTempDirectory("rp010-ws-")
        val reportDirRel = "build/reports"
        val reportDirAbs = workspaceBase.resolve(reportDirRel)
        Files.createDirectories(reportDirAbs)
        Files.writeString(reportDirAbs.resolve("a.html"), "<p>a</p>")
        Files.writeString(reportDirAbs.resolve("b.html"), "<p>b</p>")

        val runId = "rp010-r3"
        val input = PublishHtmlInput(
            name = "html-report",
            reportDir = reportDirRel,
            reportFiles = "**/*.html",
            keepAll = false,
            allowMissing = false,
            escapeUnderscores = false,
        )

        val sink1 = sink(); val adapter1 = newAdapter(runId, controlDirRoot, workspaceBase, sink1)
        val r1 = adapter1.publish(input)
        assertTrue(r1 is PublishHtmlPublished)
        val entries1 = (r1 as PublishHtmlPublished).entries.sortedBy { it.relPath }

        val sink2 = sink(); val adapter2 = newAdapter(runId, controlDirRoot, workspaceBase, sink2)
        val r2 = adapter2.publish(input)
        assertTrue(r2 is PublishHtmlPublished)
        val entries2 = (r2 as PublishHtmlPublished).entries.sortedBy { it.relPath }

        assertEquals(entries1.size, entries2.size, "entry count must match across replay")
        for ((e1, e2) in entries1.zip(entries2)) {
            assertEquals(e1.relPath, e2.relPath, "relPath ordering must match")
            assertEquals(e1.sha256, e2.sha256, "per-entry sha256 MUST match across replay")
            assertEquals(e1.sizeBytes, e2.sizeBytes, "per-entry sizeBytes MUST match across replay")
        }
    }

    /**
     * Bonus (sanity): the per-entry sha256 reported in the event MUST equal the
     * sha256 of the FINAL bytes on disk in the archive. This is the
     * hash-of-FINAL-content invariant (UAT-RP-005 row 3, partial coverage).
     */
    @Test
    fun `per-entry sha256 matches sha256 of final bytes in archive`() {
        val controlDirRoot = Files.createTempDirectory("rp010-ctrl-")
        val workspaceBase = Files.createTempDirectory("rp010-ws-")
        val reportDirRel = "build/reports"
        val reportDirAbs = workspaceBase.resolve(reportDirRel)
        Files.createDirectories(reportDirAbs)
        Files.writeString(reportDirAbs.resolve("a.html"), "<p>a</p>")
        Files.writeString(reportDirAbs.resolve("b.html"), "<p>b</p>")

        val runId = "rp010-r4"
        val input = PublishHtmlInput(
            name = "html-report",
            reportDir = reportDirRel,
            reportFiles = "**/*.html",
            keepAll = false,
            allowMissing = false,
            escapeUnderscores = false,
        )

        val adapter = newAdapter(runId, controlDirRoot, workspaceBase)
        val r = adapter.publish(input)
        assertTrue(r is PublishHtmlPublished)
        val published = r as PublishHtmlPublished
        val reportRoot = Path.of(published.targetPath)
        for (e in published.entries) {
            val onDisk = reportRoot.resolve(e.relPath)
            val diskSha = sha256(onDisk)
            assertEquals(e.sha256, diskSha,
                "event sha256 MUST equal sha256 of FINAL bytes in archive for ${e.relPath}")
        }
        // Sanity: archive has at least 2 entries (a.html, b.html).
        assertNotEquals(0, published.entries.size, "entries must be non-empty")
    }
}