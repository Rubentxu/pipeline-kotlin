package pipeline.testing.publish

import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.dsl.StageScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import pipeline.testing.TestingContributor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * LFC-2E3-R1 — `core.publishHTML`.
 *
 * Publishes an HTML report directory produced by an earlier step (typically a test or build tool)
 * so downstream consumers can read a stable, addressable entry document.
 *
 * ## Jenkins familiarity (STEP SEMANTICS rule 1)
 *
 * The Step mirrors the Jenkins `publishHTML` surface for the parameters whose semantics this
 * runtime can honour faithfully:
 *
 * ```groovy
 * publishHTML(target: [
 *     allowMissing: false,
 *     reportDir   : 'build/reports/tests/test',
 *     reportFiles : 'index.html',
 *     reportName  : 'Unit Tests',
 *     includes    : '<glob>',   // e.g. the recursive all-files glob
 * ])
 * ```
 *
 * Deliberately NOT modelled: `keepAll` and `alwaysLinkToLastBuild`. Both are defined in terms of
 * Jenkins' build-history store, which V2 does not have. Accepting them and silently ignoring them
 * would be a lie (AGENTS.md fails closed on unsupported shape rather than degrading to a no-op), so
 * they are absent from the typed input and therefore rejected at construction time.
 *
 * ## No `ReportStore` abstraction (cycle directive)
 *
 * This Step publishes into a caller-specified destination directory through ONE narrow capability
 * port. There is deliberately no `ReportStore` / `QualityPlatform` / `ResultStore` mega-abstraction:
 * a third reporting family must demonstrate the need before such a seam is introduced.
 *
 * ## Fail-closed security posture (same discipline as the U6 archive family)
 *
 * Publishing copies files OUT of a report directory into a destination. That is a path-traversal
 * surface, so the implementation rejects:
 * - a destination that escapes the declared destination root;
 * - a source entry that escapes the report directory (including via `..`);
 * - an absolute source entry;
 * - a symbolic link (either a self-referential link or a link leaving the report directory);
 * - a report directory or entry document that does not exist (unless `allowMissing`);
 * - an entry that is not a regular file.
 */

// ─────────────────────────────────────────────────────────────────────────────
// Typed failure ADT
// ─────────────────────────────────────────────────────────────────────────────

sealed interface PublishHtmlError {
    /** The report directory does not exist. */
    data class ReportDirMissing(val path: String) : PublishHtmlError

    /** The report directory exists but the declared entry document is not inside it. */
    data class EntryMissing(val reportDir: String, val entry: String) : PublishHtmlError

    /**
     * An entry path resolved outside the report directory (path traversal, `..`, or an absolute
     * path). Carries the offending entry so the diagnostic is actionable.
     */
    data class EntryEscapesReportDir(val entry: String, val resolved: String) : PublishHtmlError

    /** An entry is a symbolic link, or resolves through one. */
    data class SymlinkRejected(val entry: String, val target: String) : PublishHtmlError

    /** An entry exists but is not a regular file (directory, device, socket, …). */
    data class EntryNotRegularFile(val entry: String) : PublishHtmlError

    /** A filesystem-level I/O failure while reading or writing. */
    data class IoFailure(val path: String, val reason: String) : PublishHtmlError
}

class PublishHtmlException(val reason: PublishHtmlError) :
    RuntimeException("testing.publishHTML: ${reason::class.simpleName}: ${reason.describe()}")

private fun PublishHtmlError.describe(): String = when (this) {
    is PublishHtmlError.ReportDirMissing -> "report directory not found: $path"
    is PublishHtmlError.EntryMissing -> "entry '$entry' not found in report directory '$reportDir'"
    is PublishHtmlError.EntryEscapesReportDir -> "entry '$entry' escapes the report directory (resolved: $resolved)"
    is PublishHtmlError.SymlinkRejected -> "symbolic link rejected: $entry -> $target"
    is PublishHtmlError.EntryNotRegularFile -> "entry is not a regular file: $entry"
    is PublishHtmlError.IoFailure -> "I/O failure at $path: $reason"
}

// ─────────────────────────────────────────────────────────────────────────────
// Typed input (Jenkins-faithful subset) and typed output
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Input for `core.publishHTML`.
 *
 * [allowMissing] is modelled as a Boolean here because that is the Jenkins wire shape, but it is
 * immediately lowered to the closed [MissingReportPolicy] ADT before any decision is taken — the
 * core of the Step never branches on a bare Boolean.
 */
@Serializable
data class PublishHtmlInput(
    /** Human label for the report (Jenkins `reportName`). */
    val reportName: String,
    /** Directory containing the produced HTML (Jenkins `reportDir`). */
    val reportDir: String,
    /** Entry document relative to [reportDir] (Jenkins `reportFiles`). */
    val reportFiles: String = "index.html",
    /** Tolerate a missing report directory instead of failing (Jenkins `allowMissing`). */
    val allowMissing: Boolean = false,
    /**
     * Destination directory for the published copy. When null the entry document is published
     * beside the report under `<reportDir>/.published`.
     */
    val targetDir: String? = null,
)

/** Closed lowering of Jenkins' `allowMissing` boolean. */
@Serializable
sealed interface MissingReportPolicy {
    /** A missing report directory fails the Step. */
    @Serializable
    data object Fail : MissingReportPolicy

    /** A missing report directory produces a typed [PublishedReport.Missing] outcome. */
    @Serializable
    data object Tolerate : MissingReportPolicy

    companion object {
        fun of(allowMissing: Boolean): MissingReportPolicy =
            if (allowMissing) Tolerate else Fail
    }
}

/**
 * Typed outcome of publishing.
 *
 * [Published] and [Missing] are ordinary outcomes; a genuine failure (traversal, symlink, I/O)
 * throws [PublishHtmlException] instead. The split follows the same "infrastructure failure is not
 * a domain outcome" boundary established by `core.junit`.
 */
@Serializable
sealed interface PublishedReport {
    val reportName: String
    val reportDir: String

    /** The report was published. */
    @Serializable
    data class Published(
        override val reportName: String,
        override val reportDir: String,
        /** Where the published copy lives. */
        val targetDir: String,
        /** Entry document path relative to [targetDir]. */
        val entryPoint: String,
        /** Every published file, relative to [targetDir], in deterministic order. */
        val publishedFiles: List<String>,
        /** Total published bytes (useful for "did the report actually have content"). */
        val totalBytes: Long,
    ) : PublishedReport

    /** The report directory was absent and the policy tolerated it. */
    @Serializable
    data class Missing(
        override val reportName: String,
        override val reportDir: String,
        val policy: MissingReportPolicy,
    ) : PublishedReport
}

// ─────────────────────────────────────────────────────────────────────────────
// Capability port — narrow surface
// ─────────────────────────────────────────────────────────────────────────────

interface ReportPublishingOperations {
    /**
     * Publish the HTML report described by [input] and return the typed [PublishedReport].
     *
     * Implementations MUST throw [PublishHtmlException] for every failure mode in
     * [PublishHtmlError]; they MUST NOT silently produce an empty publication.
     */
    @Throws(PublishHtmlException::class)
    fun publish(input: PublishHtmlInput): PublishedReport
}

// ─────────────────────────────────────────────────────────────────────────────
// Default FS-backed implementation (fail-closed against traversal and symlinks)
// ─────────────────────────────────────────────────────────────────────────────

class DefaultReportPublishingOperations : ReportPublishingOperations {

    override fun publish(input: PublishHtmlInput): PublishedReport {
        val policy = MissingReportPolicy.of(input.allowMissing)
        val reportDir = Paths.get(input.reportDir).toAbsolutePath().normalize()

        if (!Files.exists(reportDir)) {
            return when (policy) {
                is MissingReportPolicy.Tolerate -> PublishedReport.Missing(
                    reportName = input.reportName,
                    reportDir = input.reportDir,
                    policy = policy,
                )
                is MissingReportPolicy.Fail ->
                    throw PublishHtmlException(PublishHtmlError.ReportDirMissing(input.reportDir))
            }
        }
        if (!Files.isDirectory(reportDir)) {
            throw PublishHtmlException(PublishHtmlError.EntryNotRegularFile(input.reportDir))
        }

        // Reject a symlinked report directory: the whole containment argument rests on the real
        // path, so a link at the root would invalidate every per-entry check below.
        if (Files.isSymbolicLink(reportDir)) {
            throw PublishHtmlException(
                PublishHtmlError.SymlinkRejected(input.reportDir, readLink(reportDir)),
            )
        }
        val realReportDir = try {
            reportDir.toRealPath()
        } catch (e: java.io.IOException) {
            throw PublishHtmlException(
                PublishHtmlError.IoFailure(input.reportDir, e.message ?: e::class.simpleName.orEmpty()),
            )
        }

        // Resolve + validate the entry document BEFORE copying anything: a bad entry must not
        // leave a half-published directory behind.
        val entry = input.reportFiles
        val entryPath = resolveInside(realReportDir, entry, input.reportDir)
        if (!Files.exists(entryPath)) {
            throw PublishHtmlException(PublishHtmlError.EntryMissing(input.reportDir, entry))
        }
        if (Files.isSymbolicLink(entryPath)) {
            throw PublishHtmlException(PublishHtmlError.SymlinkRejected(entry, readLink(entryPath)))
        }
        if (!Files.isRegularFile(entryPath)) {
            throw PublishHtmlException(PublishHtmlError.EntryNotRegularFile(entry))
        }

        val targetRoot = (input.targetDir?.let { Paths.get(it) } ?: realReportDir.resolve(".published"))
            .toAbsolutePath()
            .normalize()

        // Collect the files to publish: the entry document plus every regular file in the report
        // directory. Directories, symlinks and anything escaping containment are rejected rather
        // than skipped, so a malicious tree cannot partially succeed.
        val collected = collectPublishable(realReportDir)

        try {
            Files.createDirectories(targetRoot)
            val targetReal = targetRoot.toRealPath()
            val published = mutableListOf<String>()
            var total = 0L
            for (relative in collected) {
                val from = resolveInside(realReportDir, relative, input.reportDir)
                val to = resolveInside(targetReal, relative, targetRoot.toString())
                Files.createDirectories(to.parent)
                Files.copy(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                total += Files.size(to)
                published += relative
            }
            val entryPoint = normalizeRelative(entry)
            return PublishedReport.Published(
                reportName = input.reportName,
                reportDir = input.reportDir,
                targetDir = targetRoot.toString(),
                entryPoint = entryPoint,
                publishedFiles = published.sorted(),
                totalBytes = total,
            )
        } catch (e: java.io.IOException) {
            throw PublishHtmlException(
                PublishHtmlError.IoFailure(targetRoot.toString(), e.message ?: e::class.simpleName.orEmpty()),
            )
        }
    }

    /**
     * Resolve [relative] against [root], failing closed when the result escapes [root].
     *
     * The containment check is on the NORMALISED real path, so `..`, absolute entries and
     * redundant separators cannot escape.
     */
    private fun resolveInside(root: Path, relative: String, reportDirForDiagnostics: String): Path {
        val candidate = if (Paths.get(relative).isAbsolute) {
            throw PublishHtmlException(
                PublishHtmlError.EntryEscapesReportDir(
                    relative,
                    Paths.get(relative).toString(),
                ),
            )
        } else {
            root.resolve(relative).normalize()
        }
        if (!candidate.startsWith(root)) {
            throw PublishHtmlException(
                PublishHtmlError.EntryEscapesReportDir(relative, candidate.toString()),
            )
        }
        // If it exists, the REAL path must also stay inside (defeats a symlinked parent).
        if (Files.exists(candidate)) {
            val real = try {
                candidate.toRealPath()
            } catch (e: java.io.IOException) {
                throw PublishHtmlException(
                    PublishHtmlError.IoFailure(candidate.toString(), e.message ?: ""),
                )
            }
            if (!real.startsWith(root)) {
                throw PublishHtmlException(
                    PublishHtmlError.EntryEscapesReportDir(relative, real.toString()),
                )
            }
        }
        return candidate
    }

    /** Every regular file under [reportRoot], as normalized relative paths. Fails closed on links. */
    private fun collectPublishable(reportRoot: Path): List<String> {
        val out = mutableListOf<String>()
        Files.walk(reportRoot).use { stream ->
            for (path in stream) {
                if (path == reportRoot) continue
                // Skip our own destination if it lives inside the report directory.
                if (path.startsWith(reportRoot.resolve(".published"))) continue
                if (Files.isSymbolicLink(path)) {
                    throw PublishHtmlException(
                        PublishHtmlError.SymlinkRejected(
                            reportRoot.relativize(path).toString().replace(java.io.File.separatorChar, '/'),
                            readLink(path),
                        ),
                    )
                }
                if (Files.isDirectory(path)) continue
                if (!Files.isRegularFile(path)) {
                    throw PublishHtmlException(
                        PublishHtmlError.EntryNotRegularFile(
                            reportRoot.relativize(path).toString().replace(java.io.File.separatorChar, '/'),
                        ),
                    )
                }
                out += reportRoot.relativize(path).toString().replace(java.io.File.separatorChar, '/')
            }
        }
        return out.sorted()
    }

    private fun readLink(path: Path): String = try {
        Files.readSymbolicLink(path).toString()
    } catch (e: java.io.IOException) {
        "<unreadable>"
    }

    private fun normalizeRelative(value: String): String =
        Paths.get(value).normalize().toString().replace(java.io.File.separatorChar, '/')
}

// ─────────────────────────────────────────────────────────────────────────────
// Step definition
// ─────────────────────────────────────────────────────────────────────────────

object PublishHtmlStepDefinition : StepDefinition<PublishHtmlInput, PublishedReport> {
    val KEY: PluginStepId = PluginStepId("core.publishHTML")

    override val contract = StepContract(
        key = KEY,
        descriptor = StepDescriptor(
            stepId = KEY.value,
            name = "publishHTML",
            configRef = "",
            pluginId = TestingContributor.COORDINATE,
            pluginVersion = TestingContributor.PLUGIN_VERSION,
            executionLocation = ExecutionLocation.CONTROLLER,
            // Same shape as `core.archiveArtifacts` (CoreArchiveArtifactsStep.kt:166-167): the Step
            // writes into the workspace, so it declares WRITES_WORKSPACE and memoizes its outcome
            // like the established archival precedent.
            effects = listOf(Effect.WRITES_WORKSPACE),
            replayPolicy = ReplayPolicy.MEMOIZED,
        ),
        inputCodec = PublishHtmlInputCodec,
        outputCodec = PublishedReportCodec,
        requiredCapabilities = setOf(TestingContributor.TESTING_PUBLISH_CAPABILITY),
    )

    override val handler = StepHandler<PublishHtmlInput, PublishedReport> { input, ctx ->
        val ops: ReportPublishingOperations = ctx.capabilities.get(
            TestingContributor.TESTING_PUBLISH_CAPABILITY,
        )
        ops.publish(input)
    }
}

private val publishJson = Json { encodeDefaults = true }

object PublishHtmlInputCodec : StepCodec<PublishHtmlInput> {
    override fun encode(value: PublishHtmlInput): EncodedStepValue =
        EncodedStepValue(publishJson.encodeToString(PublishHtmlInput.serializer(), value))

    override fun decode(encoded: EncodedStepValue): PublishHtmlInput =
        publishJson.decodeFromString(PublishHtmlInput.serializer(), encoded.value)
}

object PublishedReportCodec : StepCodec<PublishedReport> {
    override fun encode(value: PublishedReport): EncodedStepValue =
        EncodedStepValue(publishJson.encodeToString(PublishedReport.serializer(), value))

    override fun decode(encoded: EncodedStepValue): PublishedReport =
        publishJson.decodeFromString(PublishedReport.serializer(), encoded.value)
}

// ─────────────────────────────────────────────────────────────────────────────
// DSL facade (declarative only: constructs typed input, never executes)
// ─────────────────────────────────────────────────────────────────────────────

fun StageScope.publishHTML(
    reportName: String,
    reportDir: String,
    reportFiles: String = "index.html",
    allowMissing: Boolean = false,
    targetDir: String? = null,
) = registryStep(
    stepKey = PublishHtmlStepDefinition.KEY,
    encodedInput = PublishHtmlInputCodec.encode(
        PublishHtmlInput(
            reportName = reportName,
            reportDir = reportDir,
            reportFiles = reportFiles,
            allowMissing = allowMissing,
            targetDir = targetDir,
        ),
    ),
)
