package dev.rubentxu.pipeline.v2.architecture

import java.nio.file.Files
import java.nio.file.Path

/**
 * Single shared authority for the LFC-2E1 legacy-residual snapshot.
 *
 * Every `S3<Foo>LegacyRemovedFitnessTest` used to carry its OWN copy of:
 *   - the residual `LEGACY_PLUGIN_IDS` set,
 *   - the expected metadata-row key set,
 *   - the expected dispatcher-file set,
 *   - the `6/6/6` counter assertions.
 *
 * The `core.pwd` G5 slice proved this fragile: removing one key required
 * hand-syncing SIX sibling fitness tests (and G4's commit message recorded
 * the wrong count because of exactly this duplication).
 *
 * This object is the ONE place that changes at each G4/G5 authority flip:
 *
 * ```
 * pwd            -> 6/6/6   (current, after S2-A6/G5)
 * deleteDir      -> 5/5/5
 * waitUntil      -> 4/4/4
 * milestone      -> 3/3/3
 * cleanWs        -> 2/2/2
 * load           -> 1/1/1
 * archiveArtifacts -> 0/0/0  (burn-down closed)
 * ```
 *
 * Per-Step suites MUST call [assertConverged] (global residual) plus their own
 * step-specific absence/anti-over-removal assertions. They MUST NOT declare
 * their own residual set or counters.
 */
object LegacyResidualSnapshot {

    /** One entry per legacy authority family. */
    data class Snapshot(
        val pluginIds: Set<String>,
        val metadataRows: Set<String>,
        val dispatcherFiles: Set<String>,
    ) {
        val legacyIdCount: Int get() = pluginIds.size
        val metadataRowCount: Int get() = metadataRows.size
        val dispatcherFileCount: Int get() = dispatcherFiles.size

        fun isConverged(): Boolean =
            pluginIds.size == metadataRows.size && metadataRows.size == dispatcherFiles.size
    }

    private val decoderPath =
        "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepDecoder.kt"
    private val metadataPath =
        "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepMetadata.kt"
    private val durableDir =
        "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable"

    /**
     * The CURRENT converged residual. ONE line to change per G4/G5 flip.
     * Removing an entry here is the authority flip for the whole test corpus.
     */
    private val residualIds: Set<String> = setOf(
        "core.milestone", "core.deleteDir", "core.cleanWs",
        "core.load", "core.waitUntil", "core.archiveArtifacts",
    )

    private fun codeOnly(source: String): String =
        Regex("/\\*.*?\\*/", setOf(RegexOption.DOT_MATCHES_ALL)).replace(
            Regex("//[^\\n]*").replace(source, ""),
            "",
        )

    private fun read(path: Path): String = Files.readString(path)

    /** Live residual LEGACY_PLUGIN_IDS read from the decoder source. */
    fun liveLegacyIds(root: Path): Set<String> {
        val block = Regex("val LEGACY_PLUGIN_IDS: Set<String> = setOf\\(([\\s\\S]*?)\\)")
            .find(codeOnly(read(root.resolve(decoderPath))))?.value
            ?: error("LEGACY_PLUGIN_IDS declaration not found")
        return Regex("\"(core\\.[a-zA-Z.]+)\"").findAll(block).map { it.groupValues[1] }.toSet()
    }

    /** Live residual metadata-row keys read from CanonicalCoreStepMetadata. */
    fun liveMetadataRows(root: Path): Set<String> =
        Regex("\"(core\\.[a-zA-Z.]+)\"\\s+to\\s+StepMetadata\\(")
            .findAll(codeOnly(read(root.resolve(metadataPath))))
            .map { it.groupValues[1] }.toSet()

    /** Live residual Canonical*NodeDispatcher.kt files (facade excluded). */
    fun liveDispatcherFiles(root: Path): Set<String> {
        val durable = root.resolve(durableDir)
        return Files.list(durable).use { paths ->
            paths.map { it.fileName.toString() }
                .filter {
                    it.startsWith("Canonical") && it.endsWith("NodeDispatcher.kt") &&
                        it != "CanonicalNodeDispatcher.kt"
                }
                .toList().toSet()
        }
    }

    /** Expected dispatcher file name for a legacy key, e.g. "core.deleteDir" -> "CanonicalDeleteDirNodeDispatcher.kt". */
    fun dispatcherFileFor(pluginId: String): String {
        val stem = pluginId.removePrefix("core.").split(".")
            .joinToString("") { it.replaceFirstChar { c -> c.uppercaseChar() } }
        return "Canonical${stem}NodeDispatcher.kt"
    }

    /** The EXPECTED snapshot at the current burn-down stage. */
    fun expected(): Snapshot {
        val pluginIds = residualIds
        return Snapshot(
            pluginIds = pluginIds,
            metadataRows = pluginIds,
            dispatcherFiles = pluginIds.map { dispatcherFileFor(it) }.toSet(),
        )
    }

    /**
     * Global convergence assertion: live residual == expected residual AND
     * the three counters agree. Call this from every per-Step S3 fitness test.
     */
    fun assertConverged(root: Path) {
        val expected = expected()
        val live = Snapshot(
            pluginIds = liveLegacyIds(root),
            metadataRows = liveMetadataRows(root),
            dispatcherFiles = liveDispatcherFiles(root),
        )
        check(expected.isConverged()) {
            "LegacyResidualSnapshot.expected() is itself inconsistent: $expected"
        }
        check(live.pluginIds == expected.pluginIds) {
            "LEGACY_PLUGIN_IDS residual drifted.\n expected=${expected.pluginIds}\n live=${live.pluginIds}\n" +
                "If this is an authority flip (G4/G5), update LegacyResidualSnapshot.residualIds FIRST, " +
                "then re-run; never hand-edit counters in sibling fitness tests."
        }
        check(live.metadataRows == expected.metadataRows) {
            "CanonicalCoreStepMetadata residual drifted.\n expected=${expected.metadataRows}\n live=${live.metadataRows}"
        }
        check(live.dispatcherFiles == expected.dispatcherFiles) {
            "Canonical*NodeDispatcher.kt residual drifted.\n expected=${expected.dispatcherFiles}\n live=${live.dispatcherFiles}"
        }
    }
}
