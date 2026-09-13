package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext
import dev.rubentxu.pipeline.v2.application.durable.ExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.application.durable.PreparedRegistryExecution
import dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionBoundary
import dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.StructuralFamilyResolver
import dev.rubentxu.pipeline.v2.application.durable.StructuralStepFamily
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess
import dev.rubentxu.pipeline.v2.events.ArtifactArchiveFailed
import dev.rubentxu.pipeline.v2.events.ArtifactArchived
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path

/**
 * Unit + handler tests for [CoreArchiveArtifactsStep] (LFC-2E1 S2-B10 / G1).
 *
 * Pins the candidate contract:
 *   - identity (KEY == legacy pluginId)
 *   - typed I/O codecs with byte-identical legacy envelopes
 *   - descriptor: effects `{WRITES_WORKSPACE}` (recorded delta vs legacy READ_ONLY row),
 *     replay MEMOIZED (byte-equivalent)
 *   - declared capability == `{ARTIFACT_ARCHIVE_OPERATIONS_CAPABILITY}` (single capability)
 *   - handler happy path: files archived, exactly one `ArtifactArchived`, typed output
 *   - handler typed-failure path: empty match + allowEmptyArchive=false →
 *     `ArtifactArchiveFailed` + typed SCRIPT failure (NOT an engine exception)
 *   - allowEmptyArchive=true → success with empty `ArtifactArchived` (legacy parity)
 *   - missing capability fail-closed admission: handler invocation 0, no event
 *   - structural family: LegacyCore while `core.archiveArtifacts` ∈ LEGACY_PLUGIN_IDS
 *   - counter invariant 5 / 5 / 5 unchanged (G1 does NOT touch legacy authorities)
 */
@Timeout(60)
class CoreArchiveArtifactsStepUnitTest {

    // ------------------------------------------------------------------
    // Identity
    // ------------------------------------------------------------------

    @Test
    fun `identity - KEY is core archiveArtifacts and matches descriptor`() {
        assertEquals("core.archiveArtifacts", CoreArchiveArtifactsStep.KEY.value)
        assertEquals("core.archiveArtifacts", CoreArchiveArtifactsStep.definition.contract.descriptor.stepId)
        assertEquals("archiveArtifacts", CoreArchiveArtifactsStep.definition.contract.descriptor.name)
    }

    // ------------------------------------------------------------------
    // Contract completeness
    // ------------------------------------------------------------------

    @Test
    fun `descriptor effects = WRITES_WORKSPACE (recorded delta vs legacy READ_ONLY row)`() {
        assertEquals(
            setOf(Effect.WRITES_WORKSPACE),
            CoreArchiveArtifactsStep.definition.contract.descriptor.effects.toSet(),
        )
        // S2-B10/G5 (LEGACY_REMOVED): the legacy metadata row that declared READ_ONLY is now
        // physically deleted, so the G1/G2-era delta (READ_ONLY vs WRITES_WORKSPACE) is no
        // longer a comparison between two live authorities. It is recorded history, and the
        // registry descriptor is the sole surviving statement. Asserting the row's ABSENCE
        // keeps the removal locked here as well as in the contract suite.
        assertFalse("core.archiveArtifacts" in CanonicalCoreStepMetadata.pluginIds)
    }

    @Test
    fun `ReplayPolicy MEMOIZED preserved across the legacy metadata row removal`() {
        assertEquals(
            ReplayPolicy.MEMOIZED,
            CoreArchiveArtifactsStep.definition.contract.descriptor.replayPolicy,
        )
        // Frozen at G2: the legacy row declared MEMOIZED too, so the registry descriptor
        // preserves the pre-migration replay semantics exactly. The legacy row is deleted at
        // G5, so the parity can no longer be asserted live — it is asserted structurally by
        // the registry value above and preserved in the G2 differential receipt.
        assertFalse("core.archiveArtifacts" in CanonicalCoreStepMetadata.pluginIds)
    }

    @Test
    fun `required capabilities = ARTIFACT_ARCHIVE_OPERATIONS_CAPABILITY only`() {
        assertEquals(
            setOf(ARTIFACT_ARCHIVE_OPERATIONS_CAPABILITY),
            CoreArchiveArtifactsStep.definition.contract.requiredCapabilities,
        )
    }

    // ------------------------------------------------------------------
    // Codecs — byte-identical legacy envelope
    // ------------------------------------------------------------------

    @Test
    fun `input codec round-trip preserves the legacy archiveArtifacts envelope`() {
        val input = ArchiveArtifactsInput(
            artifacts = "build/libs/*.jar",
            allowEmptyArchive = false,
            excludes = "",
            fingerprint = false,
        )
        val encoded = CoreArchiveArtifactsStep.definition.contract.inputCodec.encode(input)
        val json = kotlinx.serialization.json.Json.parseToJsonElement(encoded.value).jsonObject
        assertEquals("archiveArtifacts", json["kind"]?.jsonPrimitive?.content)
        assertEquals("build/libs/*.jar", json["artifacts"]?.jsonPrimitive?.content)
        assertEquals("false", json["allowEmptyArchive"]?.jsonPrimitive?.content)
        assertEquals("", json["excludes"]?.jsonPrimitive?.content)
        assertEquals("false", json["fingerprint"]?.jsonPrimitive?.content)
        assertEquals(input, CoreArchiveArtifactsStep.definition.contract.inputCodec.decode(encoded))
    }

    @Test
    fun `input codec decodes legacy payload with defaults for absent fields`() {
        val legacy = EncodedStepValue("""{"kind":"archiveArtifacts","artifacts":"*.txt"}""")
        val decoded = CoreArchiveArtifactsStep.definition.contract.inputCodec.decode(legacy)
        assertEquals(
            ArchiveArtifactsInput(artifacts = "*.txt", allowEmptyArchive = false, excludes = "", fingerprint = false),
            decoded,
        )
    }

    @Test
    fun `input codec rejects foreign envelope kind at decode`() {
        val invalid = EncodedStepValue("""{"kind":"echo","artifacts":"*.txt"}""")
        val ex = assertThrows(IllegalArgumentException::class.java) {
            CoreArchiveArtifactsStep.definition.contract.inputCodec.decode(invalid)
        }
        assertTrue(ex.message!!.contains("kind must be 'archiveArtifacts'"))
    }

    @Test
    fun `input codec requires artifacts field (legacy requiredString parity)`() {
        val invalid = EncodedStepValue("""{"kind":"archiveArtifacts"}""")
        assertThrows(Exception::class.java) {
            CoreArchiveArtifactsStep.definition.contract.inputCodec.decode(invalid)
        }
    }

    @Test
    fun `output codec round-trip - success shape`() {
        val out = CoreArchiveArtifactsStep.ArchiveArtifactsOutput(archivedCount = 3)
        val encoded = CoreArchiveArtifactsStep.definition.contract.outputCodec.encode(out)
        val json = kotlinx.serialization.json.Json.parseToJsonElement(encoded.value).jsonObject
        assertEquals("archiveArtifacts", json["kind"]?.jsonPrimitive?.content)
        assertEquals(3, json["archivedCount"]?.jsonPrimitive?.content?.toInt())
        val decoded = CoreArchiveArtifactsStep.definition.contract.outputCodec.decode(encoded)
        assertInstanceOf(CoreArchiveArtifactsStep.ArchiveArtifactsOutput::class.java, decoded)
        assertEquals(3, (decoded as CoreArchiveArtifactsStep.ArchiveArtifactsOutput).archivedCount)
    }

    @Test
    fun `output codec round-trip - typed failure shape preserves kind and message`() {
        val out = CoreArchiveArtifactsStep.ArchiveArtifactsFailureOutput(
            failureKind = FailureKind.SCRIPT,
            message = "archiveArtifacts: no files matched 'x'",
        )
        val encoded = CoreArchiveArtifactsStep.definition.contract.outputCodec.encode(out)
        val decoded = CoreArchiveArtifactsStep.definition.contract.outputCodec.decode(encoded)
        assertInstanceOf(CoreArchiveArtifactsStep.ArchiveArtifactsFailureOutput::class.java, decoded)
        decoded as CoreArchiveArtifactsStep.ArchiveArtifactsFailureOutput
        assertEquals(FailureKind.SCRIPT, decoded.failureKind)
        assertEquals("archiveArtifacts: no files matched 'x'", decoded.message)
        assertInstanceOf(StepOutcome.Failure::class.java, decoded.outcome)
    }

    @Test
    fun `durable law - encoded output survives durable persistence and decodes to the same values`() {
        val encoded = CoreArchiveArtifactsStep.definition.contract.outputCodec.encode(
            CoreArchiveArtifactsStep.ArchiveArtifactsOutput(archivedCount = 2),
        )
        val persisted: String = encoded.value
        val recovered = CoreArchiveArtifactsStep.definition.contract.outputCodec.decode(EncodedStepValue(persisted))
        assertEquals(2, (recovered as CoreArchiveArtifactsStep.ArchiveArtifactsOutput).archivedCount)
    }

    // ------------------------------------------------------------------
    // Handler happy path (real adapter, real files)
    // ------------------------------------------------------------------

    @Test
    fun `handler archives matched files with exactly one ArtifactArchived event`() = runBlocking {
        val runId = "s2b10-handler-happy"
        val sink = InMemoryEventStore()
        val controlRoot = Files.createTempDirectory("s2b10-happy-").toAbsolutePath()
        val workspace = controlRoot.resolve("workspace/test-0")
        Files.createDirectories(workspace)
        Files.writeString(workspace.resolve("artifact.txt"), "artifact-content")
        Files.writeString(workspace.resolve("other.log"), "log")

        val ctx = stepHandlerContext(runId, controlRoot, sink)
        val output = CoreArchiveArtifactsStep.definition.handler.execute(
            ArchiveArtifactsInput(artifacts = "*.txt", allowEmptyArchive = false),
            ctx,
        )

        assertInstanceOf(CoreArchiveArtifactsStep.ArchiveArtifactsOutput::class.java, output)
        assertEquals(StepOutcome.Success, output.outcome)
        assertEquals(1, (output as CoreArchiveArtifactsStep.ArchiveArtifactsOutput).archivedCount)

        val events = sink.eventsFor(runId).toList().filterIsInstance<ArtifactArchived>()
        assertEquals(1, events.size, "exactly one ArtifactArchived expected")
        val entry = events.single().files.single()
        assertEquals("artifact.txt", entry.relPath)
        assertEquals(64, entry.sha256.length)
        assertTrue(entry.size > 0)
        assertEquals("artifact-content".length.toLong(), entry.size)
    }

    @Test
    fun `archived bytes are byte-identical to the source file`() = runBlocking {
        val runId = "s2b10-bytes"
        val sink = InMemoryEventStore()
        val controlRoot = Files.createTempDirectory("s2b10-bytes-").toAbsolutePath()
        val workspace = controlRoot.resolve("workspace/test-0")
        Files.createDirectories(workspace)
        val content = "byte-identical-payload-ümlaut"
        Files.writeString(workspace.resolve("data.bin"), content)

        val ctx = stepHandlerContext(runId, controlRoot, sink)
        CoreArchiveArtifactsStep.definition.handler.execute(
            ArchiveArtifactsInput(artifacts = "data.bin", allowEmptyArchive = false),
            ctx,
        )

        val resolver = dev.rubentxu.pipeline.v2.application.durable.WorkspaceResolver(controlRoot)
        val artifact = resolver
            .resolveArchiveDir(runId, "test")
            .resolve("data.bin")
        assertTrue(Files.exists(artifact), "archived copy must exist at artefacts retention dir")
        assertEquals(content, Files.readString(artifact))
    }

    // ------------------------------------------------------------------
    // Handler typed-failure paths
    // ------------------------------------------------------------------

    @Test
    fun `handler empty match with allowEmptyArchive=false returns typed SCRIPT failure and emits ArtifactArchiveFailed`() = runBlocking {
        val runId = "s2b10-handler-empty-fail"
        val sink = InMemoryEventStore()
        val controlRoot = Files.createTempDirectory("s2b10-empty-").toAbsolutePath()

        val ctx = stepHandlerContext(runId, controlRoot, sink)
        val output = CoreArchiveArtifactsStep.definition.handler.execute(
            ArchiveArtifactsInput(artifacts = "nonexistent-*.txt", allowEmptyArchive = false),
            ctx,
        )

        assertInstanceOf(CoreArchiveArtifactsStep.ArchiveArtifactsFailureOutput::class.java, output)
        output as CoreArchiveArtifactsStep.ArchiveArtifactsFailureOutput
        assertEquals(FailureKind.SCRIPT, output.failureKind)
        assertEquals("archiveArtifacts: no files matched 'nonexistent-*.txt'", output.message)
        assertInstanceOf(StepOutcome.Failure::class.java, output.outcome)

        val failed = sink.eventsFor(runId).toList().filterIsInstance<ArtifactArchiveFailed>()
        assertEquals(1, failed.size, "exactly one ArtifactArchiveFailed expected")
        assertTrue(failed.single().reason.contains("allowEmptyArchive is false"))
    }

    @Test
    fun `handler empty match with allowEmptyArchive=true succeeds with empty ArtifactArchived (legacy parity)`() = runBlocking {
        val runId = "s2b10-handler-empty-ok"
        val sink = InMemoryEventStore()
        val controlRoot = Files.createTempDirectory("s2b10-emptyok-").toAbsolutePath()

        val ctx = stepHandlerContext(runId, controlRoot, sink)
        val output = CoreArchiveArtifactsStep.definition.handler.execute(
            ArchiveArtifactsInput(artifacts = "nonexistent-*.txt", allowEmptyArchive = true),
            ctx,
        )

        assertInstanceOf(CoreArchiveArtifactsStep.ArchiveArtifactsOutput::class.java, output)
        assertEquals(0, (output as CoreArchiveArtifactsStep.ArchiveArtifactsOutput).archivedCount)
        val archived = sink.eventsFor(runId).toList().filterIsInstance<ArtifactArchived>()
        assertEquals(1, archived.size, "legacy parity: empty ArtifactArchived still emitted")
        assertTrue(archived.single().files.isEmpty())
    }

    @Test
    fun `handler applies excludes patterns (recorded candidate delta vs legacy ignore)`() = runBlocking {
        val runId = "s2b10-handler-excludes"
        val sink = InMemoryEventStore()
        val controlRoot = Files.createTempDirectory("s2b10-exc-").toAbsolutePath()
        val workspace = controlRoot.resolve("workspace/test-0")
        Files.createDirectories(workspace)
        Files.writeString(workspace.resolve("keep.txt"), "keep")
        Files.writeString(workspace.resolve("skip.txt"), "skip")

        val ctx = stepHandlerContext(runId, controlRoot, sink)
        val output = CoreArchiveArtifactsStep.definition.handler.execute(
            ArchiveArtifactsInput(artifacts = "*.txt", excludes = "skip.txt", allowEmptyArchive = false),
            ctx,
        )

        assertEquals(1, (output as CoreArchiveArtifactsStep.ArchiveArtifactsOutput).archivedCount)
        val archived = sink.eventsFor(runId).toList().filterIsInstance<ArtifactArchived>()
        assertEquals("keep.txt", archived.single().files.single().relPath)
    }

    // ------------------------------------------------------------------
    // Structural family classification (G1 invariant: LEGACY membership wins)
    // ------------------------------------------------------------------

    @Test
    @Disabled(
        "Historical S2-B10/G1 snapshot: S2-B10/G4 (2026-09-13) flipped core.archiveArtifacts to REGISTRY_PRIMARY, " +
            "so the LegacyCore expectation is no longer true. Superseded by " +
            "`core archiveArtifacts registry flip - structural family resolves to Registry post-G4` below. " +
            "Preserved verbatim for traceability.",
    )
    fun `structural family - core archiveArtifacts stays LegacyCore while in LEGACY_PLUGIN_IDS`() {
        assertTrue("core.archiveArtifacts" in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS)
        val registry = CoreStepRegistryFactory.registry()
        assertEquals(
            StructuralStepFamily.LegacyCore,
            StructuralFamilyResolver.classify(CoreArchiveArtifactsStep.KEY, registry),
        )
    }

    // ------------------------------------------------------------------
    // S2-B10/G4: the authority flip (supersedes the archived G1 snapshots)
    // ------------------------------------------------------------------

    @Test
    fun `core archiveArtifacts registry flip - structural family resolves to Registry post-G4`() {
        assertFalse("core.archiveArtifacts" in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS)
        assertEquals(
            StructuralStepFamily.Registry,
            StructuralFamilyResolver.classify(CoreArchiveArtifactsStep.KEY, CoreStepRegistryFactory.registry()),
        )
    }

    @Test
    fun `core archiveArtifacts LEGACY_REMOVED - residual counters are 2 ids 2 metadata rows 2 dispatcher files`() {
        assertEquals(2, CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS.size)
        assertEquals(setOf("core.load", "core.waitUntil"), CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS)
        // S2-B10/G5: metadata + dispatcher physical forms removed too (2 / 2 / 2).
        assertEquals(
            setOf("core.load", "core.waitUntil"),
            CanonicalCoreStepMetadata.pluginIds,
            "the two unrelated residual keys MUST survive while core.archiveArtifacts converges",
        )
        assertFalse("core.archiveArtifacts" in CanonicalCoreStepMetadata.pluginIds)
    }

    // ------------------------------------------------------------------
    // Counter invariant — per-stage snapshots (all superseded, archived)
    // ------------------------------------------------------------------

    @Test
    @Disabled(
        "Historical S2-B10/G1 snapshot: the 5/5/5 counter was already STALE at the S2-B10/G4 base " +
            "(c0e27f21: LEGACY_PLUGIN_IDS.size == 3 after the S2-A9/S2-A10 G5 closures; this row failed at base too, " +
            "recorded as pre-existing red). S2-B10/G4 then flipped core.archiveArtifacts, taking the residual to " +
            "2 / 3 / 3, which is asserted by CoreArchiveArtifactsStepContractSuiteTest > " +
            "`G5 LEGACY_REMOVED invariant - core dot archiveArtifacts physical forms destroyed and counters are 2 2 2`. " +
            "Preserved verbatim for traceability.",
    )
    fun `counters - G1 leaves legacy counters at 5 5 5`() {
        assertEquals(5, CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS.size)
        assertTrue("core.archiveArtifacts" in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS)
        assertTrue("core.archiveArtifacts" in CanonicalCoreStepMetadata.pluginIds)
    }

    // ------------------------------------------------------------------
    // Real seam: fail-closed admission
    // ------------------------------------------------------------------

    @Test
    fun `real seam - missing ARTIFACT_ARCHIVE_OPERATIONS_CAPABILITY rejects admission with no event`() {
        val sink = InMemoryEventStore()
        val workspace = Files.createTempDirectory("s2b10-misscap-").toAbsolutePath()
        val preparation = RegistryExecutionPreparation.prepare(
            registry = CoreStepRegistryFactory.registry(),
            key = CoreArchiveArtifactsStep.KEY,
            encodedInput = EncodedStepValue("""{"kind":"archiveArtifacts","artifacts":"*.txt"}"""),
            availableCapabilities = emptySet(),
        )
        assertInstanceOf(ExecutionPreparation.Rejected::class.java, preparation)
        assertTrue(
            sink.eventsFor("s2b10-misscap").toList().filterIsInstance<ArtifactArchived>().isEmpty(),
            "no handler run means no ArtifactArchived",
        )
    }

    @Test
    fun `real seam - full capability executes through preparation and boundary with typed output`() = runBlocking {
        val runId = "s2b10-real-seam"
        val sink = InMemoryEventStore()
        val controlRoot = Files.createTempDirectory("s2b10-seam-").toAbsolutePath()
        val workspace = controlRoot.resolve("workspace/test-0")
        Files.createDirectories(workspace)
        Files.writeString(workspace.resolve("artifact.txt"), "seam-content")

        val preparation = RegistryExecutionPreparation.prepare(
            registry = CoreStepRegistryFactory.registry(),
            key = CoreArchiveArtifactsStep.KEY,
            encodedInput = EncodedStepValue("""{"kind":"archiveArtifacts","artifacts":"*.txt","allowEmptyArchive":false,"excludes":"","fingerprint":false}"""),
            availableCapabilities = setOf(ARTIFACT_ARCHIVE_OPERATIONS_CAPABILITY),
        )
        val ready = assertInstanceOf(ExecutionPreparation.Ready::class.java, preparation)
        val prepared = assertInstanceOf(PreparedRegistryExecution::class.java, ready.prepared)

        val ctx = canonicalContext(runId, controlRoot, sink)
        val result = RegistryExecutionBoundary.coexecute(prepared, ctx)
        assertEquals(StepOutcome.Success, result.outcome)
        assertNotNull(result.encodedOutput)
        val decoded = CoreArchiveArtifactsStep.definition.contract.outputCodec.decode(result.encodedOutput!!)
        assertEquals(1, (decoded as CoreArchiveArtifactsStep.ArchiveArtifactsOutput).archivedCount)
        assertEquals(1, sink.eventsFor(runId).toList().filterIsInstance<ArtifactArchived>().size)
    }

    @Test
    fun `duplicate registration fails closed`() {
        val registry = InMemoryStepRegistry().also { CoreArchiveArtifactsStep.registerInto(it) }
        try {
            CoreArchiveArtifactsStep.registerInto(registry)
            throw AssertionError("expected duplicate-key rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("core.archiveArtifacts"), "got: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Synthesises a [StepHandlerContext] whose capability access returns the real
     * [ArchiveArtifactsOperationsAdapter]. The workspace temp dir is created as a
     * child of the resolver root so `WorkspaceResolver.resolve("test", 0)` lands on
     * the actual directory the files were written to.
     */
    private fun stepHandlerContext(
        runId: String,
        controlRoot: Path,
        sink: InMemoryEventStore,
    ): StepHandlerContext =
        StepHandlerContext(
            runId = dev.rubentxu.pipeline.v2.domain.RunId(runId),
            stepIndex = 0,
            capabilities = object : StepCapabilityAccess {
                override fun available(): Set<dev.rubentxu.pipeline.v2.domain.step.StepCapability> =
                    setOf(ARTIFACT_ARCHIVE_OPERATIONS_CAPABILITY)

                @Suppress("UNCHECKED_CAST")
                override fun <T : Any> get(key: dev.rubentxu.pipeline.v2.domain.step.StepCapability): T =
                    when (key) {
                        ARTIFACT_ARCHIVE_OPERATIONS_CAPABILITY -> ArchiveArtifactsOperationsAdapter(
                            runIdString = runId,
                            stageIdentity = StageIdentity(name = "test", index = 0),
                            controlDirRoot = controlRoot,
                            eventSink = sink,
                        ) as T
                        else -> throw IllegalArgumentException("unexpected capability $key")
                    }
            },
        )

    private fun canonicalContext(
        runId: String,
        controlRoot: Path,
        sink: InMemoryEventStore,
    ): CanonicalRuntimeContext =
        CanonicalRuntimeContext(
            opId = OpId("$runId-op", 0, 0),
            runId = runId,
            stageName = "test",
            stageIndex = 0,
            stepIndex = 0,
            shOptions = ShOptions.EMPTY.copy(workspaceRoot = controlRoot.resolve("workspace/test-0")),
            controlDirRoot = controlRoot,
            eventSink = sink,
        )
}
