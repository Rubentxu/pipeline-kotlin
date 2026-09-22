package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.WorkspaceResolver
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.events.HtmlReportEntry
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
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
 *   4. (WU-RP-011) relPath containing HTML-special characters is escaped in
 *      the generated index.html, so a hostile filename cannot break out of
 *      the href attribute or inject an HTML payload.
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

    // ============================================================================
    // WU-RP-011 — HTML escape of relPath in the generated index.html
    // ============================================================================
    //
    // A filename on ext4/NTFS/APFS can contain characters that have meaning in
    // HTML (notably `"`, `<`, `>`, `&`, `'`). The adapter interpolates
    // `relPath` twice in the generated `index.html` — inside an `href="…"`
    // attribute and as the link text — without escaping. A hostile report
    // author can therefore either (a) close the href attribute and inject a
    // `<script>` tag, or (b) inject HTML through the link text. WU-RP-011
    // applies context-aware output encoding (OWASP) to both contexts.
    //
    // These tests drive `PublishHtmlOperationsAdapter.buildIndexHtml` directly
    // with synthetic `HtmlReportEntry` payloads. This avoids having to
    // materialise malicious filenames on the real filesystem: while ext4 allows
    // `<` and `>` in filenames, some filesystems (and CI sandbox restrictions)
    // reject them, which would break the E2E test setup even though the
    // production code path is correct. Driving the function directly asserts
    // the exact contract that the adapter promises.

    /** Builds a single HtmlReportEntry for the given relPath, with a stable sha. */
    private fun entry(relPath: String, sizeBytes: Long = 100L): HtmlReportEntry =
        HtmlReportEntry(relPath = relPath, sha256 = "deadbeef".repeat(8), sizeBytes = sizeBytes)

    @Test
    fun `rp011 — filename containing a quote is escaped inside the href attribute`() {
        // Hostile relPath: tries to close the href attribute and inject an
        // `onerror` handler via `<img src=x onerror=alert(1)>`.
        val indexHtml = PublishHtmlOperationsAdapter(
            runIdString = "rp011-quote",
            stageIdentity = StageIdentity(name = "rp011", index = 0),
            controlDirRoot = Files.createTempDirectory("rp011-q-"),
            eventSink = sink(),
        ).buildIndexHtml(listOf(entry("ok\"><img src=x onerror=alert(1)>.html")))

        // The raw `"` from the relPath MUST be escaped to `&quot;` inside the
        // href attribute; otherwise the closing quote lets the rest of the
        // payload land as raw HTML.
        assertTrue(
            indexHtml.contains("&quot;"),
            "the generated index.html MUST escape the `\"` from relPath as &quot; in the href; got:\n$indexHtml",
        )
        // The injected `<img src=x …>` payload MUST NOT appear unescaped.
        assertFalse(
            indexHtml.contains("<img src=x"),
            "the injected <img src=x…> payload MUST NOT appear unescaped; got:\n$indexHtml",
        )
        // The escaped form `<img …>` MUST appear as `&lt;img …>` in the link text.
        assertTrue(
            indexHtml.contains("&lt;img"),
            "the injected <img … payload MUST appear escaped as &lt;img … in the link text; got:\n$indexHtml",
        )
        // Sanity: exactly one `<a>` element (one per entry), with one closing `</a>` and
        // one opening `<a href="` — the `<meta charset="utf-8">` and `<title>` add
        // unrelated quotes that we deliberately do not count. The single hostile
        // entry must produce a single anchor.
        val anchorOpens = indexHtml.split("<a href=\"").size - 1
        val anchorCloses = indexHtml.split("</a>").size - 1
        assertEquals(
            1,
            anchorOpens,
            "the document MUST contain exactly one <a> opening tag (one per entry); got $anchorOpens; doc:\n$indexHtml",
        )
        assertEquals(
            1,
            anchorCloses,
            "the document MUST contain exactly one </a> closing tag; got $anchorCloses; doc:\n$indexHtml",
        )
        // Sanity: the hostile relPath's encoded form (with the `"` escaped to `&quot;`)
        // MUST appear INSIDE the href attribute value (between `<a href="` and the
        // closing quote of that href).
        val hrefPrefix = "<a href=\""
        val hrefStart = indexHtml.indexOf(hrefPrefix) + hrefPrefix.length
        val hrefEnd = indexHtml.indexOf('"', hrefStart)
        val hrefValue = indexHtml.substring(hrefStart, hrefEnd)
        assertTrue(
            hrefValue.contains("&quot;"),
            "the href attribute MUST contain the escaped form &quot; (no raw `\"` from the relPath); hrefValue=$hrefValue; doc:\n$indexHtml",
        )
    }

    @Test
    fun `rp011 — filename containing an ampersand is escaped in both contexts`() {
        // `&` must be escaped to `&amp;`; otherwise a sequence like `&lt;script&gt;`
        // in the relPath would re-parse as a real `<script>` tag.
        val indexHtml = PublishHtmlOperationsAdapter(
            runIdString = "rp011-amp",
            stageIdentity = StageIdentity(name = "rp011", index = 0),
            controlDirRoot = Files.createTempDirectory("rp011-a-"),
            eventSink = sink(),
        ).buildIndexHtml(listOf(entry("a&b&amp;c.html")))

        // The literal `&amp;` from the relPath MUST be doubly escaped to `&amp;amp;`
        // (each `&` from the input becomes `&amp;`, and the pre-encoded `&amp;` then
        // has its `&` re-encoded as `&amp;`). The substring `&amp;amp;` MUST appear
        // once in the href attribute and once in the link text (2 occurrences total).
        val expected = "&amp;amp;"
        val occurrences = indexHtml.split(expected).size - 1
        assertEquals(
            2,
            occurrences,
            "the literal &amp; in relPath MUST be doubly escaped to &amp;amp; in BOTH contexts " +
                "(2 occurrences = 1 in href + 1 in link text); found $occurrences; got:\n$indexHtml",
        )
        // `<script>` MUST NOT be reconstructed anywhere.
        assertFalse(
            indexHtml.contains("<script"),
            "ampersand-in-relPath MUST NOT enable <script> reconstruction; got:\n$indexHtml",
        )
    }

    @Test
    fun `rp011 — filename containing angle brackets is escaped so no HTML tag is reconstructed`() {
        val indexHtml = PublishHtmlOperationsAdapter(
            runIdString = "rp011-bracket",
            stageIdentity = StageIdentity(name = "rp011", index = 0),
            controlDirRoot = Files.createTempDirectory("rp011-b-"),
            eventSink = sink(),
        ).buildIndexHtml(listOf(entry("evil<script>alert(1)</script>.html")))

        // Real `<script>` tag MUST NOT appear in the generated index.html.
        assertFalse(
            indexHtml.contains("<script>"),
            "the injected <script> tag MUST NOT appear unescaped; got:\n$indexHtml",
        )
        // The escaped form `<script>` -> `&lt;script&gt;` MUST appear in BOTH
        // the href attribute and the link text (2 occurrences total).
        val escapedCount = indexHtml.split("&lt;script&gt;").size - 1
        assertEquals(
            2,
            escapedCount,
            "the injected <script> tag MUST appear escaped in BOTH href and text contexts; " +
                "found $escapedCount occurrences; got:\n$indexHtml",
        )
    }

    @Test
    fun `rp011 — filename with Unicode and whitespace is preserved verbatim (only HTML-special chars escape)`() {
        // Unicode and whitespace are inert in HTML; they must be preserved verbatim.
        val indexHtml = PublishHtmlOperationsAdapter(
            runIdString = "rp011-unicode",
            stageIdentity = StageIdentity(name = "rp011", index = 0),
            controlDirRoot = Files.createTempDirectory("rp011-u-"),
            eventSink = sink(),
        ).buildIndexHtml(listOf(entry("Año 2026 — café résumé.html")))

        // Unicode characters MUST be preserved verbatim in BOTH contexts (4 occurrences: href+text × 2 phrases).
        val phrase = "Año 2026 — café résumé.html"
        val occurrences = indexHtml.split(phrase).size - 1
        assertEquals(
            2,
            occurrences,
            "Unicode/whitespace MUST be preserved verbatim in BOTH href and link text; " +
                "found $occurrences occurrences of \"$phrase\"; got:\n$indexHtml",
        )
        // Em-dash and accented characters MUST NOT be percent-encoded or escaped.
        assertFalse(
            indexHtml.contains("&#"),
            "Unicode characters MUST NOT be entity-encoded; got:\n$indexHtml",
        )
    }

    @Test
    fun `rp011 — escape survives the deterministic sort (multiple malicious entries do not corrupt the document)`() {
        // Two malicious relPaths + one benign. After escape, the generated
        // index.html MUST still be a valid HTML document.
        val entries = listOf(
            entry("\"><script>alert(1)</script>.html"),
            entry("normal.html"),
            entry("another.html"),
        )
        val indexHtml = PublishHtmlOperationsAdapter(
            runIdString = "rp011-multi",
            stageIdentity = StageIdentity(name = "rp011", index = 0),
            controlDirRoot = Files.createTempDirectory("rp011-m-"),
            eventSink = sink(),
        ).buildIndexHtml(entries)

        // The injected `<script>` MUST NOT appear unescaped.
        assertFalse(
            indexHtml.contains("<script>"),
            "the injected <script> tag MUST NOT appear unescaped; got:\n$indexHtml",
        )
        // The escaped payload `&lt;script&gt;` MUST appear in both contexts of
        // the malicious entry (2 occurrences total: href + text).
        val escapedCount = indexHtml.split("&lt;script&gt;").size - 1
        assertEquals(
            2,
            escapedCount,
            "the injected <script> tag MUST appear escaped in both contexts; " +
                "found $escapedCount occurrences; got:\n$indexHtml",
        )
        // The well-formed HTML container MUST still be intact — escape must
        // not corrupt the document structure.
        assertTrue(
            indexHtml.contains("<!DOCTYPE html>"),
            "the document preamble MUST remain intact after escape; got:\n$indexHtml",
        )
        assertTrue(
            indexHtml.contains("</ul></body></html>"),
            "the document close MUST remain intact after escape; got:\n$indexHtml",
        )
        // Exactly three <li> elements (one per entry, sorted by relPath).
        val liCount = indexHtml.split("<li>").size - 1
        assertEquals(
            3,
            liCount,
            "the document MUST contain exactly three <li> entries (one per HtmlReportEntry); got $liCount; doc:\n$indexHtml",
        )
        // The document MUST contain the well-formed opening <ul> and closing </ul>.
        assertTrue(
            indexHtml.contains("<ul>") && indexHtml.contains("</ul>"),
            "the document MUST contain <ul> and </ul>; got:\n$indexHtml",
        )
    }

    // ============================================================================
    // WU-RP-011 round 2 — Paths confinement + symlink filter on publishHTML
    // ============================================================================
    //
    // The WU-RP-011 charter from docs/v2/05-roadmap/ROADMAP.md L42 is two-part:
    //   (1) escape HTML/href context-aware — covered by rp011 above (round 1);
    //   (2) confine reportDir to a real path and DO NOT follow symlinks; cover
    //       traversal, intermediate and direct symlinks, Unicode, and malicious
    //       filenames with adversarial tests.
    //
    // This section covers (2). The threat model: a hostile report author could
    // either (a) make `reportDir` itself a symlink that resolves outside the
    // workspace, (b) place a symlink somewhere inside the reportDir tree that
    // points to an external file (e.g. `/etc/passwd`), or (c) use `..` segments
    // that bypass `startsWith(workspaceRoot)` only at the lexical level.
    // publish() must reject all of these before any read or copy happens, with
    // a typed `PublishHtmlFailed(FailureKind.SCRIPT, reason)` (no infra failure
    // — the rejection is a script-level decision).
    //
    // Tests use real symlinks (the unit under test is the I/O behaviour, not
    // the function signature), so they are E2E rather than contract-driven.

    /**
     * Materialises a regular file under the given report dir and runs `publish`
     * with the standard happy-path setup. Returns the typed `PublishHtmlResult`.
     */
    private fun publishSingleRegularFile(
        controlDirRoot: Path,
        workspaceBase: Path,
        reportDirRel: String = "build/reports",
        extraSetup: (Path) -> Unit = {},
    ): PublishHtmlResult {
        val reportDirAbs = workspaceBase.resolve(reportDirRel)
        Files.createDirectories(reportDirAbs)
        Files.writeString(reportDirAbs.resolve("a.html"), "<p>ok</p>")
        extraSetup(reportDirAbs)
        val input = PublishHtmlInput(
            name = "html-report",
            reportDir = reportDirRel,
            reportFiles = "**/*.html",
            keepAll = false,
            allowMissing = false,
            escapeUnderscores = false,
        )
        return newAdapter("rp011r2-${System.nanoTime()}", controlDirRoot, workspaceBase).publish(input)
    }

    @Test
    fun `rp011r2 — reportDir that is a symlink resolving outside the workspace is rejected`(
        @TempDir tmp: Path,
    ) {
        // Setup: a real reportDir at <ws>/build/reports, BUT we replace it with
        // a symlink that points OUTSIDE the workspace. The adapter MUST reject
        // this BEFORE the glob walk — by resolving reportDir.toRealPath() and
        // checking it stays inside workspaceRoot.toRealPath().
        val workspaceBase = tmp.resolve("ws")
        Files.createDirectories(workspaceBase)
        val externalDir = tmp.resolve("external")
        Files.createDirectories(externalDir)
        Files.writeString(externalDir.resolve("secret.html"), "<p>SECRET</p>")

        val realReportDir = workspaceBase.resolve("build/reports")
        Files.createDirectories(realReportDir)
        Files.delete(realReportDir)
        Files.createSymbolicLink(realReportDir, externalDir)

        val input = PublishHtmlInput(
            name = "html-report",
            reportDir = "build/reports",
            reportFiles = "**/*.html",
            keepAll = false,
            allowMissing = false,
            escapeUnderscores = false,
        )
        val r = newAdapter("rp011r2-escape", tmp.resolve("ctrl"), workspaceBase).publish(input)

        // Rejection: typed FailureKind.SCRIPT (NOT INFRASTRUCTURE) with a
        // message that names the symlink failure mode.
        assertTrue(
            r is PublishHtmlFailed,
            "reportDir that resolves outside the workspace via symlink MUST be rejected; got $r",
        )
        r as PublishHtmlFailed
        assertEquals(
            FailureKind.SCRIPT,
            r.failureKind,
            "the rejection MUST be typed as FailureKind.SCRIPT (script-level decision); got ${r.failureKind}",
        )
        assertTrue(
            r.message.contains("symlink"),
            "the rejection message MUST mention symlink; got '${r.message}'",
        )
        // No reportRoot should be created under controlDirRoot.
        val reportsRoot = tmp.resolve("ctrl").resolve("reports")
        assertFalse(
            Files.exists(reportsRoot),
            "no reports archive MUST be created when reportDir is a symlink-escape; found $reportsRoot",
        )
    }

    @Test
    fun `rp011r2 — symlink in the file tree pointing outside the reportDir is rejected`(
        @TempDir tmp: Path,
    ) {
        // Setup: reportDir is legitimate, but contains a file that is a symlink
        // pointing to an external file outside the workspace. The adapter MUST
        // reject this before any copy by walking with NOFOLLOW_LINKS and by
        // explicit `Files.isSymbolicLink` per matched file.
        val workspaceBase = tmp.resolve("ws")
        Files.createDirectories(workspaceBase)
        val external = tmp.resolve("external")
        Files.createDirectories(external)
        val externalFile = external.resolve("passwd.html")
        Files.writeString(externalFile, "<p>SHOULD NOT BE COPIED</p>")

        val realReportDir = workspaceBase.resolve("build/reports")
        Files.createDirectories(realReportDir)
        val symlinkInside = realReportDir.resolve("evil.html")
        Files.createSymbolicLink(symlinkInside, externalFile)

        val input = PublishHtmlInput(
            name = "html-report",
            reportDir = "build/reports",
            reportFiles = "**/*.html",
            keepAll = false,
            allowMissing = false,
            escapeUnderscores = false,
        )
        val r = newAdapter("rp011r2-trav", tmp.resolve("ctrl"), workspaceBase).publish(input)

        assertTrue(
            r is PublishHtmlFailed,
            "a symlink in the reportDir tree pointing outside MUST be rejected; got $r",
        )
        r as PublishHtmlFailed
        assertEquals(FailureKind.SCRIPT, r.failureKind)
        assertTrue(
            r.message.contains("symlink"),
            "the rejection message MUST mention symlink; got '${r.message}'",
        )
    }

    @Test
    fun `rp011r2 — symlink intermediate directory (dir pointing outside) is rejected`(
        @TempDir tmp: Path,
    ) {
        // Setup: a subdirectory under reportDir that is itself a symlink to an
        // external directory. The intermediate symlink MUST NOT be traversed.
        val workspaceBase = tmp.resolve("ws")
        Files.createDirectories(workspaceBase)
        val external = tmp.resolve("external")
        Files.createDirectories(external.resolve("nested"))
        Files.writeString(external.resolve("nested").resolve("leak.html"), "<p>LEAKED</p>")

        val realReportDir = workspaceBase.resolve("build/reports")
        Files.createDirectories(realReportDir)
        Files.createSymbolicLink(realReportDir.resolve("evil-dir"), external.resolve("nested"))

        val input = PublishHtmlInput(
            name = "html-report",
            reportDir = "build/reports",
            reportFiles = "**/*.html",
            keepAll = false,
            allowMissing = false,
            escapeUnderscores = false,
        )
        val r = newAdapter("rp011r2-dir-sym", tmp.resolve("ctrl"), workspaceBase).publish(input)

        // The adapter MUST NOT publish any file from the externally-linked tree.
        assertTrue(
            r is PublishHtmlFailed || (r is PublishHtmlPublished && r.entries.isEmpty()),
            "an intermediate symlinked directory MUST NOT yield published entries; got $r",
        )
    }

    @Test
    fun `rp011r2 — happy path (regular files, no symlinks) still publishes successfully`(
        @TempDir tmp: Path,
    ) {
        // Regression: the r2 hardening MUST NOT break the simple happy path.
        val controlDirRoot = tmp.resolve("ctrl")
        val workspaceBase = tmp.resolve("ws")
        val r = publishSingleRegularFile(controlDirRoot, workspaceBase)
        assertTrue(
            r is PublishHtmlPublished,
            "the happy-path publish MUST still succeed; got $r",
        )
        r as PublishHtmlPublished
        assertEquals(1, r.entries.size, "exactly one entry MUST be published")
        assertEquals("a.html", r.entries[0].relPath)
    }

    @Test
    fun `rp011r2 — Unicode filename is preserved through publish (regression after escape)`(
        @TempDir tmp: Path,
    ) {
        // Regression: Unicode in real filenames must survive the r2 hardening.
        val controlDirRoot = tmp.resolve("ctrl")
        val workspaceBase = tmp.resolve("ws")
        val reportDirAbs = workspaceBase.resolve("build/reports")
        Files.createDirectories(reportDirAbs)
        Files.writeString(reportDirAbs.resolve("Año-2026-café.html"), "<p>unicode</p>")
        val input = PublishHtmlInput(
            name = "html-report",
            reportDir = "build/reports",
            reportFiles = "**/*.html",
            keepAll = false,
            allowMissing = false,
            escapeUnderscores = false,
        )
        val r = newAdapter("rp011r2-unicode", controlDirRoot, workspaceBase).publish(input)
        assertTrue(r is PublishHtmlPublished, "Unicode filename MUST publish; got $r")
        r as PublishHtmlPublished
        assertEquals(1, r.entries.size)
        assertEquals("Año-2026-café.html", r.entries[0].relPath)
    }
}