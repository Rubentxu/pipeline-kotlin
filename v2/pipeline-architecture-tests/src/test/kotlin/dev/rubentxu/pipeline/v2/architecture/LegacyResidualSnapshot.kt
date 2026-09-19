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
 * deleteDir G4 -> 5 / 6 / 6
 * deleteDir G5 -> 5 / 5 / 5   (current — converged)
 * waitUntil G4 -> 4 / 5 / 5
 * waitUntil G5 -> 4 / 4 / 4
 * milestone G4 -> 3 / 4 / 4
 * milestone G5 -> 3 / 3 / 3
 * cleanWs, load, archiveArtifacts -> ... -> 0 / 0 / 0 (burn-down closed)
 * ```
 *
 * Per-Step suites MUST call [assertCurrentState] (the DECLARED stage snapshot, which
 * encodes an in-flight REGISTRY_PRIMARY flip as (N-1)/N/N) plus their own step-specific
 * absence/anti-over-removal assertions. They MUST NOT declare their own residual set or
 * counters.
 *
 * [assertConverged] is the STRICTER G5-closure proof (it additionally rejects any in-flight
 * flip). Per-Step suites MUST NOT call it: during a G4 mutation the flip is in flight BY
 * DESIGN, so a per-Step absence suite would go red for a reason unrelated to that Step —
 * which is exactly what happened to all seven S3 suites when S2-B10/G4 landed on its own
 * (S2-B10, 2026-09-13). The two functions assert the same substantive invariant
 * (live == expected); they differ only in whether an in-flight flip is tolerated.
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
    // WU-LPR-301 / G5 (2026-09-18, receipt
    // docs/v2/07-uat/WU_LPR_301_LEGACY_EXECUTION_BURN_DOWN.md): "core.load" and
    // "core.waitUntil" physically removed (decoder branch, metadata row, dispatcher
    // forms). LEGACY_PLUGIN_IDS = empty set; burn-down closed-set complete. Counter
    // converges 2/2/2 -> 0/0/0.
    private val physicalResidual: Set<String> = emptySet()
    // S2-A7 / G5 (2026-09-12): "core.deleteDir" removed from the physical residual
    // (subtype, decoder branch, metadata row, dispatcher file all deleted).
    // S2-A9 / G5 (2026-09-13): "core.milestone" removed from the physical residual
    // (subtype, decoder branch + constant, metadata row, CanonicalMilestoneNodeDispatcher.kt
    // file, CanonicalNodeDispatcher Milestone seams all deleted). Counter converges
    // 4/5/5 -> 4/4/4 (registry-primary pending removed; metadata + dispatcher physical
    // forms removed too).
    // S2-A10 / G5 (2026-09-13): "core.cleanWs" removed from the physical residual
    // (CleanWs subtype, CLEAN_WS_PLUGIN_ID decoder branch + constant, metadata row,
    // CanonicalCleanWsNodeDispatcher.kt file, CanonicalNodeDispatcher cleanWs seams all
    // deleted). Counter converges 3/4/4 -> 3/3/3 (registry-primary pending removed;
    // metadata + dispatcher physical forms removed too).
    // S2-B10 / G5 (2026-09-13): "core.archiveArtifacts" removed from the physical residual
    // (ArchiveArtifacts subtype, ARCHIVE_ARTIFACTS_PLUGIN_ID decoder branch + constant,
    // metadata row, CanonicalArchiveArtifactsNodeDispatcher.kt file, CanonicalNodeDispatcher
    // archiveArtifacts seams all deleted). Counter converges 2/3/3 -> 2/2/2
    // (registry-primary pending removed; metadata + dispatcher physical forms removed too).

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
    // S2-A10 / G4 (2026-09-13): core.cleanWs flipped to REGISTRY_PRIMARY.
    // Legacy decoder branch / dispatcher file / metadata row remain physically
    // present (UNREACHABLE in production) until S2-A10 / G5 closes this lane.
    // S2-A10 / G5 (2026-09-13): registryPrimaryPendingRemoval back to null (LEGACY_REMOVED
    // closed). core.cleanWs physical forms are gone; only the 3 residual legacy keys
    // (core.load, core.waitUntil, core.archiveArtifacts) remain for their own G4/G5 lanes.
    // S2-B10 / G4 (2026-09-13): core.archiveArtifacts flipped to REGISTRY_PRIMARY
    // (transitional 3/3/3 -> 2/3/3; ids only). The legacy
    // CanonicalArchiveArtifactsNodeDispatcher, its ARCHIVE_ARTIFACTS_PLUGIN_ID decoder
    // branch + constant, the CanonicalCoreStepCommand.ArchiveArtifacts subtype and the
    // CanonicalCoreStepMetadata["core.archiveArtifacts"] row remain physically present
    // (UNREACHABLE in production) until S2-B10 / G5 closes this lane.
    // S2-B10 / G5 (2026-09-13): back to null — LEGACY_REMOVED closed. All core.archiveArtifacts
    // legacy forms are physically deleted. This closes the 3/3/3 -> 2/3/3 -> 2/2/2 chain and
    // restores CONVERGENCE, which LegacyResidualConvergenceFitnessTest now asserts. Only the
    // two residual legacy keys (core.load, core.waitUntil) remain for their own G4/G5 lanes.
    private val registryPrimaryPendingRemoval: String? = null

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
