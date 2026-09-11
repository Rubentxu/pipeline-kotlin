package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * S2-A2 / G5 irreversible proof:
 * LEGACY_REMOVED(core.sleep) = membership absent ∧ command absent ∧ decoder absent
 * ∧ dispatcher absent ∧ metadata absent.
 */
class S3SleepLegacyRemovedFitnessTest {
    private val root = ScannerSupport.v2Root()
    private val decoder = root.resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepDecoder.kt")
    private val metadata = root.resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepMetadata.kt")
    private val dispatcher = root.resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalNodeDispatcher.kt")
    private val sleepDispatcher = root.resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalSleepNodeDispatcher.kt")
    private val registry = root.resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreStepRegistryFactory.kt")
    private val sleepStep = root.resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreSleepStep.kt")

    private val expectedIds = setOf(
        "core.milestone", "core.deleteDir", "core.cleanWs",
        "core.load", "core.pwd", "core.isUnix", "core.waitUntil", "core.archiveArtifacts",
    )

    private fun read(path: java.nio.file.Path): String = Files.readString(path)
    private fun codeOnly(source: String): String = Regex("//[^\\n]*").replace(source, "")
        .let { Regex("/\\*.*?\\*/", setOf(RegexOption.DOT_MATCHES_ALL)).replace(it, "") }
    private fun legacyIds(): Set<String> {
        val block = Regex("val LEGACY_PLUGIN_IDS: Set<String> = setOf\\(([\\s\\S]*?)\\)").find(codeOnly(read(decoder)))?.value
            ?: error("LEGACY_PLUGIN_IDS declaration not found")
        return Regex("\\\"(core\\.[a-zA-Z.]+)\\\"").findAll(block).map { it.groupValues[1] }.toSet()
    }

    @Test fun `core sleep remains registered and structurally registry owned`() {
        assertTrue(read(registry).contains("CoreSleepStep.registerInto(this)"))
        assertTrue(read(sleepStep).contains("PluginStepId(\"core.sleep\")"))
        assertFalse("core.sleep" in legacyIds())
    }

    @Test fun `legacy command decoder and dispatcher forms are absent`() {
        val decoderSource = codeOnly(read(decoder))
        assertFalse(decoderSource.contains("data class Sleep"))
        assertFalse(decoderSource.contains("SLEEP_PLUGIN_ID"))
        assertFalse(Regex("\\\"core\\.sleep\\\"\\s*->").containsMatchIn(decoderSource))
        assertFalse(Files.exists(sleepDispatcher))
        val dispatcherSource = codeOnly(read(dispatcher))
        assertFalse(dispatcherSource.contains("sleepDispatcher"))
        assertFalse(dispatcherSource.contains("CanonicalCoreStepCommand.Sleep"))
        assertFalse(dispatcherSource.contains("sleepContext()"))
    }

    @Test fun `legacy metadata has no core sleep row`() {
        val source = codeOnly(read(metadata))
        assertFalse(Regex("\\\"core\\.sleep\\\"\\s+to\\s+StepMetadata\\(").containsMatchIn(source))
    }

    @Test fun `transitional snapshot converges to 8 IDs 9 metadata rows 9 dispatchers S2-A4-G4 window`() {
        assertEquals(expectedIds, legacyIds())
        val metadataKeys = Regex("\\\"(core\\.[a-zA-Z.]+)\\\"\\s+to\\s+StepMetadata\\(")
            .findAll(codeOnly(read(metadata))).map { it.groupValues[1] }.toSet()
        assertEquals(expectedIds + "core.emit.event", metadataKeys)
        val durable = root.resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable")
        val actualDispatchers = Files.list(durable).use { paths -> paths.map { it.fileName.toString() }
            .filter { it.startsWith("Canonical") && it.endsWith("NodeDispatcher.kt") && it != "CanonicalNodeDispatcher.kt" }
            .toList().toSet() }
        assertEquals(setOf(
            "CanonicalEmitEventNodeDispatcher.kt",
            "CanonicalMilestoneNodeDispatcher.kt", "CanonicalDeleteDirNodeDispatcher.kt",
            "CanonicalCleanWsNodeDispatcher.kt", "CanonicalLoadNodeDispatcher.kt", "CanonicalPwdNodeDispatcher.kt",
            "CanonicalIsUnixNodeDispatcher.kt", "CanonicalWaitUntilNodeDispatcher.kt",
            "CanonicalArchiveArtifactsNodeDispatcher.kt",
        ), actualDispatchers)
        assertEquals(8, legacyIds().size)
        assertEquals(9, metadataKeys.size)
        assertEquals(9, actualDispatchers.size)
    }
}
