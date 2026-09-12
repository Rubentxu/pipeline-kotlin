package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * LFC-2E1 / S2-A1 / G6 — irreversible `core.error` legacy-removal fitness.
 *
 * The S2-A1 burn-down (G0..G5 + G6) removed `core.error` from every legacy authority:
 *  - the typed-command sealed hierarchy (`CanonicalCoreStepCommand.Error` data class deleted);
 *  - the legacy decoder (`CanonicalCoreStepDecoder` no longer handles `ERROR_PLUGIN_ID`);
 *  - the legacy dispatcher (`CanonicalErrorNodeDispatcher` deleted, `CanonicalNodeDispatcher`
 *    has no `Error` case in its `when`);
 *  - the legacy metadata table (`CanonicalCoreStepMetadata.table` no longer carries
 *    `core.error`);
 *  - the closed legacy authority (`LEGACY_PLUGIN_IDS` excludes `core.error`).
 *
 * `core.error` now lives ONLY as an open StepDefinition (`CoreErrorStep`) registered through
 * `CoreStepRegistryFactory`. This fitness suite proves that mechanically, mirroring
 * `S3EchoLegacyRemovedFitnessTest`:
 *
 *  1. `core.error` is in `StepRegistry` (the production registry contains it via
 *     `CoreStepRegistryFactory`).
 *  2. `core.error` is NOT in `LEGACY_PLUGIN_IDS` (the closed legacy authority).
 *  3. `core.error` is NOT in `CanonicalCoreStepDecoder` (no legacy decode path:
 *     no `ERROR_PLUGIN_ID` constant, no `"core.error" ->` when-branch, no
 *     `data class Error`).
 *  4. `core.error` is NOT in `CanonicalNodeDispatcher` legacy semantics (no
 *     `errorDispatcher` field, no `errorContext()` helper, no
 *     `CanonicalCoreStepCommand.Error` branch).
 *  5. `core.error` is NOT in legacy metadata (`CanonicalCoreStepMetadata.table`).
 *  6. `CanonicalErrorNodeDispatcher.kt` does NOT exist on disk.
 *
 * Plus the **global certification policy**:
 *   `CERTIFIED ∩ LEGACY_EXECUTABLE = ∅`
 * meaning: a Step cannot be both certified as a registry Step AND a legacy executable.
 * The current registry-primary set is `{core.echo, core.sh, core.error}`; NONE of these
 * may appear in `LEGACY_PLUGIN_IDS`.
 *
 * Plus the **LEGACY_REMOVED rule** for `core.error`:
 *   a Step can only be marked `LEGACY_REMOVED` when
 *     - legacy decoder is absent (no decode case for its key);
 *     - legacy dispatcher is absent (no dispatch case, no field, no helper, no file);
 *     - legacy registration is absent (no entry in legacy metadata / no legacy plugin id).
 *
 * Plus the **residual exact-set equality** of `LEGACY_PLUGIN_IDS`:
 *   `{core.emit.event, core.milestone, core.deleteDir, core.cleanWs, core.load,
 *     core.pwd, core.waitUntil, core.archiveArtifacts}` (8 entries).
 *
 * State at G6 close:
 *   REGISTERED          = true
 *   REGISTRY_PRIMARY    = true
 *   LEGACY_UNREACHABLE  = true
 *   LEGACY_REMOVED      = true
 *   CERTIFIED           = false (G7/G8 will mark it)
 *
 * Counters at G6 close:
 *   LEGACY_PLUGIN_IDS  : 7
 *   metadata rows       : 7
 *   dispatcher files    : 7
 */
class S3ErrorLegacyRemovedFitnessTest {

    private val decoderSource = ScannerSupport.v2Root()
        .resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepDecoder.kt")
    private val nodeDispatcherSource = ScannerSupport.v2Root()
        .resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalNodeDispatcher.kt")
    private val metadataSource = ScannerSupport.v2Root()
        .resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepMetadata.kt")
    private val legacyDispatcherFile = ScannerSupport.v2Root()
        .resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalErrorNodeDispatcher.kt")
    private val registryFactorySource = ScannerSupport.v2Root()
        .resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreStepRegistryFactory.kt")
    private val coreErrorSource = ScannerSupport.v2Root()
        .resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreErrorStep.kt")

    private fun read(path: java.nio.file.Path): String {
        require(Files.exists(path)) { "Expected source not found: $path" }
        return Files.readString(path)
    }

    /** Strips // comments and /* */ comments so documentation mentions of deleted symbols don't
     *  count as code (mirrors FArchLeg1ExecutionAuthorityTest.codeOnly). */
    private fun codeOnly(source: String): String {
        var s = Regex("//[^\n]*").replace(source, "")
        s = Regex("/\\*.*?\\*/", setOf(RegexOption.DOT_MATCHES_ALL)).replace(s, "")
        return s
    }

    /** 1. core.error is registered in the production StepRegistry via CoreErrorStep. */
    @Test
    fun `core error is registered in the production StepRegistry`() {
        val factory = read(registryFactorySource)
        assertTrue(
            factory.contains("CoreErrorStep.registerInto(this)"),
            "CoreStepRegistryFactory must register core.error via the open StepRegistry mechanism",
        )
        val coreError = read(coreErrorSource)
        assertTrue(
            coreError.contains("val KEY: PluginStepId = PluginStepId(\"core.error\")"),
            "CoreErrorStep.KEY must be 'core.error' (canonical plugin id)",
        )
    }

    /** 2. core.error is NOT in the closed legacy authority LEGACY_PLUGIN_IDS. */
    @Test
    fun `core error is NOT in the closed legacy authority LEGACY_PLUGIN_IDS`() {
        val source = codeOnly(read(decoderSource))
        val legacyBlock = Regex("val LEGACY_PLUGIN_IDS: Set<String> = setOf\\(([\\s\\S]*?)\\)").find(source)?.value
            ?: error("LEGACY_PLUGIN_IDS declaration not found in $decoderSource")
        assertFalse(
            legacyBlock.contains("\"core.error\""),
            "core.error must remain outside LEGACY_PLUGIN_IDS (legacy decode would otherwise be reachable)",
        )
    }

    /** 2b. LEGACY_PLUGIN_IDS is exactly the 8 residual legacy keys (full-set equality). */
    @Test
    fun `LEGACY_PLUGIN_IDS is exactly the 8 residual keys (post-G6 full-set equality)`() {
        val source = codeOnly(read(decoderSource))
        val legacyBlock = Regex("val LEGACY_PLUGIN_IDS: Set<String> = setOf\\(([\\s\\S]*?)\\)").find(source)?.value
            ?: error("LEGACY_PLUGIN_IDS declaration not found in $decoderSource")
        // Extract every "core.xxx" literal that is on its own line.
        val ids = Regex("\"core\\.[a-zA-Z.]+\"").findAll(legacyBlock).map { it.value }.toSet()
        val expected = setOf(
            "\"core.milestone\"",
            "\"core.deleteDir\"",
            "\"core.cleanWs\"",
            "\"core.load\"",
            "\"core.pwd\"",
            "\"core.waitUntil\"",
            "\"core.archiveArtifacts\"",
        )
        assertEquals(
            expected,
            ids,
            "LEGACY_PLUGIN_IDS MUST equal the 8 residual legacy keys post-S2-A4/G4 (no core.error, no core.echo, no core.sh, no core.emit.event, no extras); " +
                "got $ids",
        )
    }

    /** 3. core.error is NOT decodable by CanonicalCoreStepDecoder (no ERROR_PLUGIN_ID, no when-branch, no Error data class). */
    @Test
    fun `core error is NOT decodable by CanonicalCoreStepDecoder`() {
        val source = codeOnly(read(decoderSource))
        // No ERROR_PLUGIN_ID constant (legacy decode key) is referenced anywhere.
        assertFalse(
            source.contains("ERROR_PLUGIN_ID"),
            "CanonicalCoreStepDecoder must not declare an ERROR_PLUGIN_ID constant (legacy decode path deleted)",
        )
        // No decode-when branch references "core.error".
        assertFalse(
            Regex("\"core\\.error\"\\s*->").containsMatchIn(source),
            "CanonicalCoreStepDecoder.decode must not branch on core.error (legacy decode path deleted)",
        )
        // The sealed command hierarchy has no Error data class.
        assertFalse(
            source.contains("data class Error"),
            "CanonicalCoreStepCommand must not contain an Error data class (legacy typed command deleted)",
        )
    }

    /** 4. core.error is NOT dispatched by CanonicalNodeDispatcher (no field, no helper, no branch). */
    @Test
    fun `core error is NOT dispatched by CanonicalNodeDispatcher`() {
        val source = read(nodeDispatcherSource)
        // No error dispatcher field.
        assertFalse(
            source.contains("errorDispatcher"),
            "CanonicalNodeDispatcher must not hold an errorDispatcher field (legacy dispatch deleted)",
        )
        // No error context helper.
        assertFalse(
            source.contains("errorContext()"),
            "CanonicalNodeDispatcher must not define an errorContext() helper (legacy dispatch deleted)",
        )
        // No Error dispatch case.
        assertFalse(
            source.contains("CanonicalCoreStepCommand.Error"),
            "CanonicalNodeDispatcher must not branch on CanonicalCoreStepCommand.Error (legacy dispatch deleted)",
        )
    }

    /** 5. core.error is NOT in the legacy metadata table. */
    @Test
    fun `core error is NOT in the legacy metadata table`() {
        val source = codeOnly(read(metadataSource))
        val tableBlock = Regex("private val table: Map<String, StepMetadata> = mapOf\\(([\\s\\S]*?)\\)").find(source)?.value
            ?: error("CanonicalCoreStepMetadata.table declaration not found in $metadataSource")
        assertFalse(
            tableBlock.contains("\"core.error\""),
            "CanonicalCoreStepMetadata.table must not contain 'core.error' (registry-routed, not legacy metadata)",
        )
    }

    /** 6. CanonicalErrorNodeDispatcher.kt does NOT exist on disk. */
    @Test
    fun `CanonicalErrorNodeDispatcher kt does NOT exist on disk`() {
        assertFalse(
            Files.exists(legacyDispatcherFile),
            "CanonicalErrorNodeDispatcher.kt MUST be deleted at G6 (LEGACY_REMOVED); " +
                "path=$legacyDispatcherFile",
        )
    }

    /**
     * Global certification policy: CERTIFIED ∩ LEGACY_EXECUTABLE = ∅.
     *
     * The closed legacy authority (LEGACY_PLUGIN_IDS) is the SET of legacy-executable plugins.
     * A registry-routed certified plugin MUST NOT also appear as a legacy-executable id.
     * For S2-A1 / G6 the registry-primary set is {core.echo, core.sh, core.error}; NONE of
     * these may appear in LEGACY_PLUGIN_IDS.
     */
    @Test
    fun `certified registry-routed plugins are disjoint from LEGACY_PLUGIN_IDS`() {
        val source = codeOnly(read(decoderSource))
        val legacyBlock = Regex("val LEGACY_PLUGIN_IDS: Set<String> = setOf\\(([\\s\\S]*?)\\)").find(source)?.value
            ?: error("LEGACY_PLUGIN_IDS declaration not found")
        // Concretely: core.echo, core.sh, core.error must NOT appear in LEGACY_PLUGIN_IDS.
        for (registryPrimary in listOf("core.echo", "core.sh", "core.error")) {
            assertFalse(
                legacyBlock.contains("\"$registryPrimary\""),
                "$registryPrimary must remain outside LEGACY_PLUGIN_IDS — registry-routed Step MUST NOT also be legacy-executable",
            )
        }
    }

    /**
     * LEGACY_REMOVED rule for core.error:
     *   legacy id absent
     * ∧ legacy command absent
     * ∧ legacy decoder absent
     * ∧ legacy dispatcher absent
     * ∧ legacy metadata absent
     */
    @Test
    fun `core error satisfies the LEGACY_REMOVED rule (id AND command AND decoder AND dispatcher AND metadata absent)`() {
        val decoder = codeOnly(read(decoderSource))
        val dispatcher = codeOnly(read(nodeDispatcherSource))
        val metadata = codeOnly(read(metadataSource))

        // Legacy id absent: "core.error" not in LEGACY_PLUGIN_IDS.
        val legacyBlock = Regex("val LEGACY_PLUGIN_IDS: Set<String> = setOf\\(([\\s\\S]*?)\\)").find(decoder)?.value
            ?: error("LEGACY_PLUGIN_IDS declaration not found")
        val legacyIdAbsent = !legacyBlock.contains("\"core.error\"")

        // Legacy command absent: no data class Error / no CanonicalCoreStepCommand.Error reference.
        val legacyCommandAbsent = !decoder.contains("data class Error") &&
            !decoder.contains("CanonicalCoreStepCommand.Error")

        // Legacy decoder absent: no ERROR_PLUGIN_ID, no when-branch.
        val legacyDecoderAbsent = !decoder.contains("ERROR_PLUGIN_ID") &&
            !Regex("\"core\\.error\"\\s*->").containsMatchIn(decoder)

        // Legacy dispatcher absent: no errorDispatcher field, no errorContext() helper,
        // no CanonicalCoreStepCommand.Error branch, file does NOT exist on disk.
        val legacyDispatcherAbsent = !dispatcher.contains("errorDispatcher") &&
            !dispatcher.contains("errorContext()") &&
            !dispatcher.contains("CanonicalCoreStepCommand.Error") &&
            !Files.exists(legacyDispatcherFile)

        // Legacy metadata absent: no "core.error" row.
        val legacyMetadataAbsent = !metadata.contains("\"core.error\"")

        assertTrue(
            legacyIdAbsent,
            "LEGACY_REMOVED requires core.error to be absent from LEGACY_PLUGIN_IDS",
        )
        assertTrue(
            legacyCommandAbsent,
            "LEGACY_REMOVED requires CanonicalCoreStepCommand.Error to be absent",
        )
        assertTrue(
            legacyDecoderAbsent,
            "LEGACY_REMOVED requires the legacy decoder branch for core.error to be absent",
        )
        assertTrue(
            legacyDispatcherAbsent,
            "LEGACY_REMOVED requires the legacy dispatcher (field + helper + when-branch + file) for core.error to be absent",
        )
        assertTrue(
            legacyMetadataAbsent,
            "LEGACY_REMOVED requires the legacy metadata row for core.error to be absent",
        )
    }

    /**
     * Counter snapshot at G6 close. Asserts the canonical production counters:
     *   LEGACY_PLUGIN_IDS.size == 8
     *   metadata rows           == 8
     *   per-Step dispatcher files == 8 (one Canonical<Node>NodeDispatcher.kt per legacy id,
     *                                    plus the facade CanonicalNodeDispatcher.kt)
     *
     * Each counter is asserted via full-set equality (not just a count) so that accidental
     * removals of unrelated keys or accidental additions of new keys are caught as discrete
     * failures. We parse the entries directly from their concrete shape rather than relying
     * on a fragile non-greedy `mapOf\(...\)` regex (which would mis-cut on nested parens).
     *
     * The legacy dispatcher filenames are HARDCODED, not derived from plugin ids. The
     * `plugin id -> class name` mapping is NOT a contract; it is historical naming accident.
     * The fitness asserts the REAL residual structure.
     */

    @Test
    fun `counter snapshot at G6 close -- LEGACY_PLUGIN_IDS equals the 8 residual legacy keys`() {
        val decoder = codeOnly(read(decoderSource))
        val legacyBlock = Regex("val LEGACY_PLUGIN_IDS: Set<String> = setOf\\(([\\s\\S]*?)\\)").find(decoder)?.value
            ?: error("LEGACY_PLUGIN_IDS declaration not found")
        val legacyIds = Regex("\"(core\\.[a-zA-Z.]+)\"").findAll(legacyBlock).map { it.groupValues[1] }.toSet()
        val expected = setOf(
            "core.milestone",
            "core.deleteDir",
            "core.cleanWs",
            "core.load",
            "core.pwd",
            "core.waitUntil",
            "core.archiveArtifacts",
        )
        assertEquals(expected, legacyIds, "LEGACY_PLUGIN_IDS MUST equal the 7 residual legacy keys (post-S2-A5/G5)")
        assertEquals(7, legacyIds.size)
    }

    @Test
    fun `counter snapshot at G6 close -- CanonicalCoreStepMetadata table keys equal the 7 residual legacy keys`() {
        // Metadata rows: extract via the concrete entry shape `"core.x" to StepMetadata(`
        // (matches the actual literal form in CanonicalCoreStepMetadata).
        val metadata = codeOnly(read(metadataSource))
        val metadataKeys = Regex("\"(core\\.[a-zA-Z.]+)\"\\s+to\\s+StepMetadata\\(")
            .findAll(metadata)
            .map { it.groupValues[1] }
            .toSet()
        val expected = setOf(
            "core.milestone",
            "core.deleteDir",
            "core.cleanWs",
            "core.load",
            "core.pwd",
            "core.waitUntil",
            "core.archiveArtifacts",
        )
        assertEquals(
            expected,
            metadataKeys,
            "CanonicalCoreStepMetadata.table keys MUST equal the 7 residual legacy keys (post-S2-A5/G5)",
        )
        assertEquals(7, metadataKeys.size)
        assertFalse("core.error" in metadataKeys, "core.error MUST NOT be in legacy metadata")
    }

    @Test
    fun `counter snapshot at G6 close -- per-Step dispatcher files equal the 7 residual legacy dispatcher classes`() {
        // Per-Step dispatcher files are HARDCODED (NOT derived from plugin ids) because the
        // plugin id -> class name mapping is a historical naming accident, not a contract.
        val durableDir = ScannerSupport.v2Root()
            .resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable")
        val actualDispatchers: Set<String> = Files.list(durableDir).use { paths ->
            paths
                .map { it.fileName.toString() }
                .filter { it.startsWith("Canonical") && it.endsWith("NodeDispatcher.kt") }
                .filter { it != "CanonicalNodeDispatcher.kt" } // facade, not a per-Step dispatcher
                .toList()
                .toSet()
        }
        val expectedDispatchers = setOf(
            "CanonicalMilestoneNodeDispatcher.kt",
            "CanonicalDeleteDirNodeDispatcher.kt",
            "CanonicalCleanWsNodeDispatcher.kt",
            "CanonicalLoadNodeDispatcher.kt",
            "CanonicalPwdNodeDispatcher.kt",
            "CanonicalWaitUntilNodeDispatcher.kt",
            "CanonicalArchiveArtifactsNodeDispatcher.kt",
        )
        assertEquals(
            expectedDispatchers,
            actualDispatchers,
            "Per-Step dispatcher files in durable/ MUST equal the 7 expected Canonical<Node>NodeDispatcher.kt (isUnix removed at S2-A5/G5)",
        )
        assertEquals(7, actualDispatchers.size)
        assertFalse(
            "CanonicalErrorNodeDispatcher.kt" in actualDispatchers,
            "CanonicalErrorNodeDispatcher.kt MUST NOT exist at G6 (LEGACY_REMOVED)",
        )
    }
}
