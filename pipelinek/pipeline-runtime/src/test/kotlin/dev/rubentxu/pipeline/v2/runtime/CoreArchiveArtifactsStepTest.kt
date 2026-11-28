package dev.rubentxu.pipeline.v2.runtime

import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.artifact.ArtifactIndexCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import dev.rubentxu.pipeline.v2.domain.workspace.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspaceLease
import dev.rubentxu.pipeline.v2.events.ArtifactArchived
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSink
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.reflect.KClass

/**
 * The public wire of `core.archiveArtifacts` after the capture identity.
 *
 * Three shapes of one law — the identity the capture port mints reaches every public
 * projection of the step, and a payload that predates the field still decodes:
 *  - the step output carries one capture id per archived file, in receipt order;
 *  - the handle recorded in the index pairs each capture identity with the digest of
 *    its own bytes;
 *  - the output codec round-trips the ids, and a legacy envelope without them decodes
 *    to an explicit absence — never to invented identities.
 */
class CoreArchiveArtifactsStepTest {

    @TempDir
    lateinit var tempDir: Path

    private val workspace: Path get() = tempDir.resolve("ws")
    private val controlRoot: Path get() = tempDir.resolve("control")

    private class RecordingSink : EventSink {
        val appended = mutableListOf<DomainEvent>()
        override fun append(event: DomainEvent) {
            appended.add(event)
        }
        override fun eventsFor(runId: String): Sequence<DomainEvent> = appended.asSequence()
    }

    private class Seams(
        private val ops: ArchiveArtifactsOperations,
        private val index: ArtifactIndexCapability,
    ) : StepCapabilityAccess {
        override fun available(): Set<StepCapability> =
            setOf(ARTIFACT_ARCHIVE_OPERATIONS_CAPABILITY, ARTIFACT_INDEX_CAPABILITY)

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> get(key: StepCapability, type: KClass<T>): T = when (key) {
            ARTIFACT_ARCHIVE_OPERATIONS_CAPABILITY -> ops as T
            ARTIFACT_INDEX_CAPABILITY -> index as T
            else -> throw AssertionError("the step asked for a capability it does not declare: $key")
        }
    }

    private fun execute(input: ArchiveArtifactsInput, sink: RecordingSink, index: ArtifactIndexCapability) =
        runBlocking {
            Files.createDirectories(workspace)
            Files.createDirectories(controlRoot)
            CoreArchiveArtifactsStep.definition.handler.execute(
                input,
                StepHandlerContext(
                    runId = RunId("run-1"),
                    stepIndex = 0,
                    capabilities = Seams(
                        ops = ArchiveArtifactsOperationsAdapter(
                            runIdString = "run-1",
                            stageIdentity = StageIdentity(name = "stage-a", index = 0),
                            controlDirRoot = controlRoot,
                            eventSink = sink,
                            executionLocation = ExecutionLocation(
                                workspace = WorkspaceLease.Managed(workspace),
                                cwd = workspace,
                            ),
                        ),
                        index = index,
                    ),
                ),
            )
        }

    @Test
    fun `the step output carries one capture id per archived file, paired by the handle too`() {
        Files.writeString(workspace.resolve("a.txt"), "wire-bytes-a")
        Files.writeString(workspace.resolve("b.txt"), "wire-bytes-b-longer")
        val sink = RecordingSink()
        val index = InMemoryArtifactIndex()

        val output = assertInstanceOf(
            CoreArchiveArtifactsStep.ArchiveArtifactsOutput::class.java,
            execute(ArchiveArtifactsInput(artifacts = "*.txt", name = "app"), sink, index),
        )

        assertEquals(2, output.archivedCount)
        assertEquals(
            output.archivedCount,
            output.captureIds.size,
            "the output answers with one identity per capture, no more and no less",
        )
        assertTrue(output.captureIds.none { it.isBlank() }, "a minted identity is never anonymous")
        assertEquals(
            output.captureIds.size,
            output.captureIds.distinct().size,
            "two captures are two public identities even under one logical name",
        )

        // The same identities reach the durable event and the queryable handle: one law,
        // three projections, no channel that mints its own story.
        val eventIds = sink.appended.filterIsInstance<ArtifactArchived>()
            .flatMap { it.files }
            .map { it.captureId }
        assertEquals(
            output.captureIds.toSet(),
            eventIds.filterNotNull().toSet(),
            "the wire output and the durable event name the same captures",
        )
        eventIds.forEach { assertNotNull(it, "adapter captures are not anonymous on the event") }

        val handle = index.query("app")
        assertNotNull(handle)
        assertEquals(2, handle!!.files.size)
        assertEquals(
            output.captureIds.toSet(),
            handle.files.mapNotNull { it.captureId }.toSet(),
            "the handle's entries carry the step's capture identities",
        )
        assertEquals(
            handle.files.mapNotNull { it.captureId }.toSet().size,
            handle.files.size,
            "distinct captures, distinct identities on the handle",
        )
    }

    @Test
    fun `the output codec round-trips capture ids and a legacy envelope decodes to explicit absence`() {
        val codec = CoreArchiveArtifactsStep.definition.contract.outputCodec
        val ids = listOf("capture-id-one", "capture-id-two")

        val decoded = codec.decode(codec.encode(CoreArchiveArtifactsStep.ArchiveArtifactsOutput(2, ids)))
        val output = assertInstanceOf(CoreArchiveArtifactsStep.ArchiveArtifactsOutput::class.java, decoded)
        assertEquals(2, output.archivedCount)
        assertEquals(ids, output.captureIds, "the wire carries the identities in order")

        val legacy = codec.decode(
            EncodedStepValue("""{"kind":"archiveArtifacts","archivedCount":3}"""),
        )
        val legacyOutput = assertInstanceOf(CoreArchiveArtifactsStep.ArchiveArtifactsOutput::class.java, legacy)
        assertEquals(3, legacyOutput.archivedCount, "a pre-identity payload still decodes its count")
        assertEquals(
            emptyList<String>(),
            legacyOutput.captureIds,
            "absent identities decode as an explicit absence, never as invented ones",
        )

        val encoded = Json.parseToJsonElement(codec.encode(CoreArchiveArtifactsStep.ArchiveArtifactsOutput(2, ids)).value).jsonObject
        assertEquals(ids.size, (encoded.getValue("captureIds") as JsonArray).jsonArray.size)
    }
}
