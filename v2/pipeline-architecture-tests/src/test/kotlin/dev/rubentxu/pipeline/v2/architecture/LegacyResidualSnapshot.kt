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
 * This object is the ONE place that changes at each G4/G5 authority flip.
 * It models the TRANSITIONAL G4 state (registry-primary flipped, physical removal
 * pending), so the burn-down progresses as:
 *
 * ```
 * pwd closed                 -> 6 / 6 / 6
 * deleteDir G4 -> 5 / 6 / 6   (current — transitional, pending G5)
 * deleteDir G5 -> 5 / 5 / 5
 * waitUntil G4 -> 4 / 5 / 5
 * waitUntil G5 -> 4 / 4 / 4
 * milestone G4 -> 3 / 4 / 4
 * milestone G5 -> 3 / 3 / 3
 * cleanWs, load, archiveArtifacts -> ... -> 0 / 0 / 0 (burn-down closed)
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
     * The PHYSICAL residual: keys that still have a legacy metadata row and/or
     * a legacy dispatcher file on disk. shrinks ONLY at G5 (physical removal).
     * ONE line to change per G5.
     */
    private val physicalResidual: Set<String> = setOf(
        "core.milestone", "core.deleteDir", "core.cleanWs",
        "core.load", "core.waitUntil", "core.archiveArtifacts",
    )

    /**
     * The key that has REGISTRY_PRIMARY-flipped (G4) but is NOT yet physically
     * removed (G5). Its LEGACY_PLUGIN_IDS entry is already gone, while its
     * metadata row and dispatcher file remain until G5.
     *
     * State machine per burn-down lane:
     *   before G4: null                       -> N / N / N
     *   at G4:     registryPrimaryPendingRemoval = key   -> (N-1) / N / N
     *   at G5:     physicalResidual -= key; back to null -> (N-1) / (N-1) / (N-1)
     */
    private val registryPrimaryPendingRemoval: String? = "core.deleteDir"

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

    /** The EXPECTED snapshot at the current burn-down stage (transitional-aware). */
    fun expected(): Snapshot {
        val pluginIds = physicalResidual - listOfNotNull(registryPrimaryPendingRemoval)
        return Snapshot(
            pluginIds = pluginIds,
            metadataRows = physicalResidual,
            dispatcherFiles = physicalResidual.map { dispatcherFileFor(it) }.toSet(),
        )
    }

    /**
     * Transitional-aware global assertion: live residual == expected residual for
     * the CURRENT stage (including the G4 transitional N-1/N/N state).
     * Call this from every per-Step S3 fitness test.
     */
    fun assertCurrentState(root: Path) {
        val expected = expected()
        val live = Snapshot(
            pluginIds = liveLegacyIds(root),
            metadataRows = liveMetadataRows(root),
            dispatcherFiles = liveDispatcherFiles(root),
        )
        check(expected.dispatcherFiles.size == expected.metadataRows.size) {
            "LegacyResidualSnapshot.expected() is itself inconsistent: $expected"
        }
        check(live.pluginIds == expected.pluginIds) {
            "LEGACY_PLUGIN_IDS residual drifted.\n expected=${expected.pluginIds}\n live=${live.pluginIds}\n" +
                "If this is an authority flip (G4/G5), update LegacyResidualSnapshot FIRST " +
                "(physicalResidual at G5; registryPrimaryPendingRemoval at G4), then re-run; " +
                "never hand-edit counters in sibling fitness tests."
        }
        check(live.metadataRows == expected.metadataRows) {
            "CanonicalCoreStepMetadata residual drifted.\n expected=${expected.metadataRows}\n live=${live.metadataRows}"
        }
        check(live.dispatcherFiles == expected.dispatcherFiles) {
            "Canonical*NodeDispatcher.kt residual drifted.\n expected=${expected.dispatcherFiles}\n live=${live.dispatcherFiles}"
        }
    }

    /**
     * Strict convergence assertion: same as [assertCurrentState] but ALSO requires
     * that no G4 flip is in flight (the corpus is at a converged N/N/N point).
     * Use at G5 closure proofs.
     */
    fun assertConverged(root: Path) {
        check(registryPrimaryPendingRemoval == null) {
            "Convergence requires no in-flight REGISTRY_PRIMARY flip; " +
                "registryPrimaryPendingRemoval=${registryPrimaryPendingRemoval} (G5 not closed)"
        }
        assertCurrentState(root)
    }
}
