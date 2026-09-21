package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.WorkspaceResolver
import dev.rubentxu.pipeline.v2.artefacts.local.AntStyleGlob
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.HtmlReportEntry
import dev.rubentxu.pipeline.v2.events.HtmlReportFailed as HtmlReportFailedEvent
import dev.rubentxu.pipeline.v2.events.HtmlReportPublished
import dev.rubentxu.pipeline.v2.events.HtmlReportSkipped
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * Production adapter for [PublishHtmlOperations] (WU-LPR-090 / Tier B #2).
 *
 * Storage layout: `<controlDirRoot>/reports/<runId>/<sanitizedName>/`. The
 * reports directory is a SIBLING of `stashes/` and `artefacts/`, so
 * `WorkspaceResolver.cleanupAfterComplete()` does NOT destroy published
 * reports when the producing stage finishes.
 *
 * The adapter is the ONLY place that:
 * - reads workspace files matching the glob,
 * - copies them into the run-scoped reports archive,
 * - generates the `index.html` wrapper,
 * - computes per-file sha256 / size,
 * - emits `HtmlReportPublished` / `HtmlReportSkipped` / `HtmlReportFailed` events.
 *
 * The handler reaches ONLY [PublishHtmlOperations] via the
 * [PUBLISH_HTML_OPERATIONS_CAPABILITY] typed seam — it never touches `Files`,
 * `MessageDigest`, or `EventSink` directly.
 *
 * Reference: jenkinsci/htmlpublisher-plugin master @ 6a536b8d
 *   `HtmlPublisher.archiveReports()` + `HTMLReportTarget.getArchive()`.
 *   Pipeline-K deviation: no symlink alternative (rejected by WU-LPR-090/proposal
 *   §OQ1 for cross-platform portability); no `keepAll` run-overlap (rejected for
 *   v1 determinism; spec.md §R4 keeps `keepAll` as a typed input but the
 *   adapter treats it as a hint for future replay-aware archival).
 */
class PublishHtmlOperationsAdapter(
    private val runIdString: String,
    private val stageIdentity: StageIdentity,
    private val controlDirRoot: Path,
    private val eventSink: EventSink,
    /** WU-LPR-062 parity: optional project-workspace override (--workspace). */
    private val workspaceBase: Path? = null,
) : PublishHtmlOperations {

    override fun publish(input: PublishHtmlInput): PublishHtmlResult {
        val resolver = WorkspaceResolver(controlDirRoot, workspaceBase)
        val workspaceRoot = resolver.ensureCreated(
            resolver.resolve(stageIdentity.name, stageIdentity.index),
        )

        // Defense-in-depth: re-validate reportDir even though PublishHtmlInput's
        // init block already forbids ".." segments and absolute paths.
        val reportDir = workspaceRoot.resolve(input.reportDir).normalize()
        if (!reportDir.startsWith(workspaceRoot)) {
            val reason = "reportDir '${input.reportDir}' escapes workspace"
            eventSink.append(htmlReportFailedEvent(input, reason, FailureKind.SCRIPT))
            return PublishHtmlFailed(FailureKind.SCRIPT, reason)
        }

        // R1/R2: missing directory.
        if (!Files.exists(reportDir)) {
            if (input.allowMissing) {
                val skip = PublishHtmlSkipped(PublishHtmlSkipReason.DIRECTORY_MISSING)
                eventSink.append(
                    HtmlReportSkipped(
                        eventId = UUID.randomUUID().toString(),
                        runId = runIdString,
                        sequence = 0L,
                        occurredAt = Instant.now(),
                        stageName = stageIdentity.name,
                        reportName = input.name,
                        reportDir = input.reportDir,
                        reason = skip.reason.name,
                    ),
                )
                return skip
            }
            val reason = "reportDir '${input.reportDir}' does not exist"
            eventSink.append(htmlReportFailedEvent(input, reason, FailureKind.SCRIPT))
            return PublishHtmlFailed(FailureKind.SCRIPT, reason)
        }

        // R3: glob matching.
        val matched: List<Path> = try {
            AntStyleGlob(input.reportFiles).match(
                root = reportDir,
                excludes = emptyList(),
                defaultExcludes = true,
            )
        } catch (e: Exception) {
            val reason = "reportFiles glob '${input.reportFiles}' failed: ${e.message}"
            eventSink.append(htmlReportFailedEvent(input, reason, FailureKind.SCRIPT))
            return PublishHtmlFailed(FailureKind.SCRIPT, reason)
        }

        if (matched.isEmpty()) {
            if (input.allowMissing) {
                val skip = PublishHtmlSkipped(PublishHtmlSkipReason.NO_FILES_MATCHED)
                eventSink.append(
                    HtmlReportSkipped(
                        eventId = UUID.randomUUID().toString(),
                        runId = runIdString,
                        sequence = 0L,
                        occurredAt = Instant.now(),
                        stageName = stageIdentity.name,
                        reportName = input.name,
                        reportDir = input.reportDir,
                        reason = skip.reason.name,
                    ),
                )
                return skip
            }
            val reason = "reportFiles glob '${input.reportFiles}' matched no files under '${input.reportDir}'"
            eventSink.append(htmlReportFailedEvent(input, reason, FailureKind.SCRIPT))
            return PublishHtmlFailed(FailureKind.SCRIPT, reason)
        }

        // R5/R6: copy into the run-scoped reports archive. Reject symlinks (R7).
        val sanitized = PublishHtmlSanitiser.sanitize(input.name, input.escapeUnderscores)
        val reportRoot = reportsRoot(runIdString, sanitized)
        return try {
            // Idempotent: a fresh publish overwrites a previous one.
            if (Files.exists(reportRoot)) {
                Files.walk(reportRoot).use { stream ->
                    stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }
            }
            Files.createDirectories(reportRoot)

            val entries = mutableListOf<HtmlReportEntry>()
            for (file in matched) {
                if (Files.isSymbolicLink(file)) {
                    // R7: refuse to follow symlinks (security: avoid escaping reportDir).
                    val reason = "reportDir contains symlink at '$file'; refusing for safety"
                    eventSink.append(htmlReportFailedEvent(input, reason, FailureKind.SCRIPT))
                    return PublishHtmlFailed(FailureKind.SCRIPT, reason)
                }
                val rel = reportDir.relativize(file)
                val target = reportRoot.resolve(rel.toString())
                Files.createDirectories(target.parent ?: reportRoot)
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING)
                entries.add(
                    HtmlReportEntry(
                        relPath = rel.toString(),
                        sha256 = sha256Of(target),
                        sizeBytes = Files.size(target),
                    ),
                )
            }

            // R8: index.html with one <a> per published file (sorted by relPath).
            val indexHtml = buildIndexHtml(entries)
            val indexPath = reportRoot.resolve("index.html")
            Files.writeString(indexPath, indexHtml)

            val targetPath = reportRoot.toString()
            eventSink.append(
                HtmlReportPublished(
                    eventId = UUID.randomUUID().toString(),
                    runId = runIdString,
                    sequence = 0L,
                    occurredAt = Instant.now(),
                    stageName = stageIdentity.name,
                    reportName = input.name,
                    reportDir = input.reportDir,
                    entries = entries.toList(),
                    targetPath = targetPath,
                ),
            )
            PublishHtmlPublished(entries = entries.toList(), targetPath = targetPath)
        } catch (e: Exception) {
            val reason = "publishHTML '${input.name}' failed: ${e.message}"
            eventSink.append(htmlReportFailedEvent(input, reason, FailureKind.INFRASTRUCTURE))
            PublishHtmlFailed(FailureKind.INFRASTRUCTURE, reason)
        }
    }

    /** Resolves the durable per-run per-name reports root. */
    private fun reportsRoot(runIdString: String, sanitizedName: String): Path {
        val reportsRoot = controlDirRoot.resolve("reports").resolve(runIdString).resolve(sanitizedName)
        return reportsRoot
    }

    private fun htmlReportFailedEvent(
        input: PublishHtmlInput,
        reason: String,
        failureKind: FailureKind,
    ): HtmlReportFailedEvent =
        HtmlReportFailedEvent(
            eventId = UUID.randomUUID().toString(),
            runId = runIdString,
            sequence = 0L,
            occurredAt = Instant.now(),
            stageName = stageIdentity.name,
            reportName = input.name,
            reportDir = input.reportDir,
            failureKind = failureKind,
            reason = reason,
        )

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
     * Generates the index.html wrapper (R8). Sorted by relPath for determinism
     * (replay-stable fingerprint).
     */
    private fun buildIndexHtml(entries: List<HtmlReportEntry>): String {
        val sb = StringBuilder()
        sb.appendLine("<!DOCTYPE html>")
        sb.appendLine("<html><head><meta charset=\"utf-8\"><title>Published Report</title></head><body>")
        sb.appendLine("<ul>")
        for (e in entries.sortedBy { it.relPath }) {
            sb.append("<li><a href=\"").append(e.relPath).append("\">")
            sb.append(e.relPath).append("</a> (").append(e.sizeBytes).append(" bytes)</li>")
            sb.appendLine()
        }
        sb.appendLine("</ul></body></html>")
        return sb.toString()
    }
}
