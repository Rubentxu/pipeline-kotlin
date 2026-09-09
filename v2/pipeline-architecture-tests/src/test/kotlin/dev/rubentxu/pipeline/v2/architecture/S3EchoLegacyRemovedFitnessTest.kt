package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * LFC-2 / S3.4 — irreversible Echo legacy-removal fitness.
 *
 * The Echo burn-down (S3.1 + S3.2 + S3.3) removed `core.echo` from every legacy authority:
 *  - the typed-command sealed hierarchy (`CanonicalCoreStepCommand.Echo` data class deleted);
 *  - the legacy decoder (`CanonicalCoreStepDecoder` no longer handles `ECHO_PLUGIN_ID`);
 *  - the legacy dispatcher (`CanonicalEchoNodeDispatcher` deleted, `CanonicalNodeDispatcher` has no
 *    `Echo` case in its `when`);
 *  - the legacy metadata table (`CanonicalCoreStepMetadata.table` no longer carries `core.echo`);
 *  - the closed legacy authority (`LEGACY_PLUGIN_IDS` excludes `core.echo`).
 *
 * Echo now lives ONLY as an open StepDefinition (`CoreEchoStep`) registered through
 * `CoreStepRegistryFactory`. This fitness suite proves that mechanically:
 *
 *  1. `core.echo` is in `StepRegistry` (the production registry contains it via `CoreStepRegistryFactory`);
 *  2. `core.echo` is NOT in `LEGACY_PLUGIN_IDS` (the closed legacy authority);
 *  3. `core.echo` is NOT in `CanonicalCoreStepDecoder` (no legacy decode path);
 *  4. `core.echo` is NOT in `CanonicalNodeDispatcher` legacy semantics (no legacy dispatch path);
 *  5. `core.echo` is NOT in legacy metadata (`CanonicalCoreStepMetadata`).
 *
 * Plus the **global certification policy**:
 *   `CERTIFIED ∩ LEGACY_EXECUTABLE = ∅`
 * meaning: a Step cannot be both certified as a registry Step AND a legacy executable.
 * Concretely: `core.echo` must NOT appear in `LEGACY_PLUGIN_IDS` once it is registered as an
 * open StepDefinition. Since the legacy path is now absent, ANY reference to `core.echo`
 * inside a legacy file is a regression that breaks the policy.
 *
 * Plus the **LEGACY_REMOVED rule**:
 *   a Step can only be marked `LEGACY_REMOVED` when
 *     - legacy decoder is absent (no decode case for its key);
 *     - legacy dispatcher is absent (no dispatch case);
 *     - legacy registration is absent (no entry in legacy metadata / no legacy plugin id).
 *
 * Without all three, `LEGACY_REMOVED` is a false claim — the legacy path is "merely
 * unreachable today" not "actually removed from the source tree". This is the difference
 * between `LEGACY_UNREACHABLE` (a runtime property) and `LEGACY_REMOVED` (a source property).
 */
class S3EchoLegacyRemovedFitnessTest {

    private val decoderSource = ScannerSupport.v2Root()
        .resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepDecoder.kt")
    private val nodeDispatcherSource = ScannerSupport.v2Root()
        .resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalNodeDispatcher.kt")
    private val metadataSource = ScannerSupport.v2Root()
        .resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepMetadata.kt")
    private val commandSealedSource = ScannerSupport.v2Root()
        .resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepDecoder.kt")
    private val registryFactorySource = ScannerSupport.v2Root()
        .resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreStepRegistryFactory.kt")
    private val coreEchoSource = ScannerSupport.v2Root()
        .resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreEchoStep.kt")

    private fun read(path: java.nio.file.Path): String {
        require(Files.exists(path)) { "Expected source not found: $path" }
        return Files.readString(path)
    }

    /** 1. core.echo is in StepRegistry via CoreStepRegistryFactory. */
    @Test
    fun `core echo is registered in the production StepRegistry`() {
        val factory = read(registryFactorySource)
        assertTrue(
            factory.contains("CoreEchoStep.registerInto(this)"),
            "CoreStepRegistryFactory must register core.echo via the open StepRegistry mechanism",
        )
        val coreEcho = read(coreEchoSource)
        assertTrue(
            coreEcho.contains("val KEY: PluginStepId = PluginStepId(\"core.echo\")"),
            "CoreEchoStep.KEY must be 'core.echo' (canonical plugin id)",
        )
    }

    /** 2. core.echo is NOT in the closed legacy authority LEGACY_PLUGIN_IDS. */
    @Test
    fun `core echo is NOT in the closed legacy authority LEGACY_PLUGIN_IDS`() {
        val source = read(decoderSource)
        val legacyBlock = Regex("val LEGACY_PLUGIN_IDS: Set<String> = setOf\\(([\\s\\S]*?)\\)").find(source)?.value
            ?: error("LEGACY_PLUGIN_IDS declaration not found in $decoderSource")
        assertFalse(
            legacyBlock.contains("\"core.echo\""),
            "core.echo must remain outside LEGACY_PLUGIN_IDS (legacy decode would otherwise be reachable)",
        )
    }

    /** 3. core.echo is NOT in CanonicalCoreStepDecoder (no decode case). */
    @Test
    fun `core echo is NOT decodable by CanonicalCoreStepDecoder`() {
        val source = read(decoderSource)
        // No ECHO_PLUGIN_ID constant (legacy decode key) is referenced anywhere.
        assertFalse(
            source.contains("ECHO_PLUGIN_ID"),
            "CanonicalCoreStepDecoder must not declare an ECHO_PLUGIN_ID constant (legacy decode path deleted)",
        )
        // No decode-when branch references "core.echo".
        assertFalse(
            Regex("\"core\\.echo\"\\s*->").containsMatchIn(source),
            "CanonicalCoreStepDecoder.decode must not branch on core.echo (legacy decode path deleted)",
        )
        // The sealed command hierarchy has no Echo data class.
        assertFalse(
            source.contains("data class Echo"),
            "CanonicalCoreStepCommand must not contain an Echo data class (legacy typed command deleted)",
        )
    }

    /** 4. core.echo is NOT in CanonicalNodeDispatcher legacy semantics (no dispatch case). */
    @Test
    fun `core echo is NOT dispatched by CanonicalNodeDispatcher`() {
        val source = read(nodeDispatcherSource)
        // No echo dispatcher field.
        assertFalse(
            source.contains("echoDispatcher"),
            "CanonicalNodeDispatcher must not hold an echoDispatcher field (legacy dispatch deleted)",
        )
        // No echo context helper.
        assertFalse(
            source.contains("echoContext()"),
            "CanonicalNodeDispatcher must not define an echoContext() helper (legacy dispatch deleted)",
        )
        // No Echo dispatch case.
        assertFalse(
            source.contains("CanonicalCoreStepCommand.Echo"),
            "CanonicalNodeDispatcher must not branch on CanonicalCoreStepCommand.Echo (legacy dispatch deleted)",
        )
    }

    /** 5. core.echo is NOT in legacy metadata (CanonicalCoreStepMetadata). */
    @Test
    fun `core echo is NOT in the legacy metadata table`() {
        val source = read(metadataSource)
        val tableBlock = Regex("private val table: Map<String, StepMetadata> = mapOf\\(([\\s\\S]*?)\\)").find(source)?.value
            ?: error("CanonicalCoreStepMetadata.table declaration not found in $metadataSource")
        assertFalse(
            tableBlock.contains("\"core.echo\""),
            "CanonicalCoreStepMetadata.table must not contain 'core.echo' (registry-routed, not legacy metadata)",
        )
    }

    /**
     * Global certification policy: CERTIFIED ∩ LEGACY_EXECUTABLE = ∅.
     *
     * The closed legacy authority (LEGACY_PLUGIN_IDS) is the SET of legacy-executable plugins.
     * A registry-routed certified plugin MUST NOT also appear as a legacy-executable id —
     * otherwise the same key would have two different execution families (Registry + LegacyCore)
     * and the structural switch could not guarantee deterministic routing.
     */
    @Test
    fun `certified registry-routed plugins are disjoint from LEGACY_PLUGIN_IDS`() {
        val source = read(decoderSource)
        val legacyBlock = Regex("val LEGACY_PLUGIN_IDS: Set<String> = setOf\\(([\\s\\S]*?)\\)").find(source)?.value
            ?: error("LEGACY_PLUGIN_IDS declaration not found")
        // Currently the only registry-routed core plugin is core.echo. The fitness guard scales:
        // any future plugin registered via CoreStepRegistryFactory must NOT appear in LEGACY_PLUGIN_IDS.
        // For S3.4 the assertion is concretely: core.echo is not in LEGACY_PLUGIN_IDS.
        assertFalse(
            legacyBlock.contains("\"core.echo\""),
            "core.echo must remain outside LEGACY_PLUGIN_IDS — registry-routed Step MUST NOT also be legacy-executable",
        )
    }

    /**
     * LEGACY_REMOVED rule: a Step is legacy-removed only when ALL three legacy forms are absent:
     *  - legacy decoder (no decode case for the key)
     *  - legacy dispatcher (no dispatch case)
     *  - legacy registration (no entry in legacy metadata / legacy plugin id set)
     *
     * Concretely for core.echo: the three absences are proven above; this test asserts the
     * conjunction so that marking `core.echo = LEGACY_REMOVED` is mechanically defensible.
     */
    @Test
    fun `core echo satisfies the LEGACY_REMOVED rule (decoder absent AND dispatcher absent AND registration absent)`() {
        val decoder = read(decoderSource)
        val dispatcher = read(nodeDispatcherSource)
        val metadata = read(metadataSource)

        val legacyDecoderAbsent =
            !decoder.contains("ECHO_PLUGIN_ID") &&
                !Regex("\"core\\.echo\"\\s*->").containsMatchIn(decoder) &&
                !decoder.contains("data class Echo")
        val legacyDispatcherAbsent =
            !dispatcher.contains("echoDispatcher") &&
                !dispatcher.contains("echoContext()") &&
                !dispatcher.contains("CanonicalCoreStepCommand.Echo")
        val legacyRegistrationAbsent = !metadata.contains("\"core.echo\"")

        assertTrue(
            legacyDecoderAbsent,
            "LEGACY_REMOVED requires the legacy decoder to be absent (no ECHO_PLUGIN_ID constant, no decode when-branch, no Echo data class)",
        )
        assertTrue(
            legacyDispatcherAbsent,
            "LEGACY_REMOVED requires the legacy dispatcher to be absent (no echoDispatcher field, no echoContext() helper, no dispatch when-branch)",
        )
        assertTrue(
            legacyRegistrationAbsent,
            "LEGACY_REMOVED requires the legacy registration to be absent (no entry in legacy metadata)",
        )
    }
}
