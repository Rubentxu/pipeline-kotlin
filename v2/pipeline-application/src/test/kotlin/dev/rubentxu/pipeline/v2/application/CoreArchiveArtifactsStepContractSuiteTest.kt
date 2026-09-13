package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator
import dev.rubentxu.pipeline.v2.application.durable.CanonicalNodeDispatcher
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeCapabilityAccess
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext
import dev.rubentxu.pipeline.v2.application.durable.CommonExecutionResult
import dev.rubentxu.pipeline.v2.application.durable.ExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.application.durable.PreparedRegistryExecution
import dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionBoundary
import dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.StructuralFamilyResolver
import dev.rubentxu.pipeline.v2.application.durable.StructuralStepFamily
import dev.rubentxu.pipeline.v2.application.durable.WorkspaceResolver
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeFailure
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeOutcome
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopePort
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.DefinitionId
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.SourceDescriptor
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StageId
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.RecoveryPolicy
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.dsl.pipeline
import dev.rubentxu.pipeline.v2.events.ArtifactArchived
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ReplayDecision
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * StepContractSuite — LFC-2E1 / S2-B10 / G3 for `core.archiveArtifacts`.
 *
 * Certifies the G1 registry candidate across the registry-driven, open-world Step seam,
 * following the certified `core.deleteDir` (S2-A7) / `core.cleanWs` (S2-A10) models.
 * Effectful pattern: ONE required capability ([ARTIFACT_ARCHIVE_OPERATIONS_CAPABILITY]) —
 * the handler reaches the typed `ArchiveArtifactsOperations` seam; all filesystem semantics,
 * workspace/retention resolution, globbing and `ArtifactArchived` / `ArtifactArchiveFailed`
 * emission live in `ArchiveArtifactsOperationsAdapter` (single emission authority).
 *
 * archiveArtifacts-specific semantics (S2-B10/G1, G2):
 *  - input envelope is
 *    `{"kind":"archiveArtifacts","artifacts":<str>,"allowEmptyArchive":<bool>,"excludes":<str>,"fingerprint":<bool>}`;
 *  - effects are `{ WRITES_WORKSPACE }` (candidate; the legacy row's `{READ_ONLY}` is the
 *    frozen delta D3 owned by G4/G5), replayPolicy MEMOIZED, recoveryPolicy None;
 *  - the capability is conditionally exposed only when `controlDirRoot != null`;
 *  - output is `ArchiveArtifactsOutput(archivedCount)` on success and
 *    `ArchiveArtifactsFailureOutput(failureKind, message)` on failure;
 *  - durable observation: exactly one `ArtifactArchived` XOR one `ArtifactArchiveFailed`
 *    per execution;
 *  - failures are TYPED (`FailureKind.SCRIPT`, the legacy contract) and never thrown:
 *    a thrown handler would be re-classified ENGINE by the boundary (test 18 pins both sides).
 *
 * ## G4 transitional honesty
 *
 * From G4 the key is ABSENT from `LEGACY_PLUGIN_IDS`, so `StructuralFamilyResolver`
 * classifies it Registry and `CoreArchiveArtifactsStep.definition` is the production
 * authority (rows 17b/17c assert the resolver output and drive a real non-empty archive
 * through the production coordinator, an input the legacy authority could not satisfy —
 * frozen delta D1).
 *
 * The legacy forms are still PHYSICALLY present (decoder branch, `ArchiveArtifacts`
 * subtype, metadata row, `CanonicalArchiveArtifactsNodeDispatcher.kt`); they are
 * UNREACHABLE in production but type-loadable until G5/LEGACY_REMOVED. That is why the
 * residual is 2 / 3 / 3 and not 2 / 2 / 2, and row 17 asserts BOTH halves of that law.
 *
 * The G3 receipt recorded the pre-flip state (3 / 3 / 3); this suite now pins the G4
 * state deliberately, as that receipt's §6 required.
 *
 * S2-B10 / G3 — AGENTS.md 17/17 coverage (per Step Constitution §LB-02):
 * ```
 *  1.  identity                                              REQUIRED
 *  2.  contract completeness                                 REQUIRED
 *  3.  input codec round-trip                                REQUIRED
 *      3a. input codec tolerant defaults (legacy envelope)   REQUIRED
 *      3b. input codec rejection (foreign envelope kind)     REQUIRED
 *  4.  output codec round-trip (success)                     REQUIRED
 *      4a. output codec round-trip (failure variant)         REQUIRED (archiveArtifacts-specific)
 *      4b. output codec rejection (foreign kind)             REQUIRED
 *  5.  canonical envelope (byte-identical to compiler        REQUIRED
 *      lowering)
 *  6.  production registry resolution                        REQUIRED
 *      6a. fresh factory consistency                         REQUIRED
 *  7.  capability declaration (EXACTLY one)                  REQUIRED
 *      7a. capability admission (available → Ready)          REQUIRED
 *      7b. missing capability → Rejected (fail closed)       REQUIRED
 *      7c. conditional exposure (controlDirRoot=null)        REQUIRED
 *  8.  success (typed outcome + one ArtifactArchived)        REQUIRED
 *  9.  typed failure (step's OWN SCRIPT failure, not         REQUIRED
 *      ENGINE; null encodedOutput)
 *      9a. thrown-handler negative control → ENGINE          REQUIRED (test law)
 * 10.  fresh durable (1 terminal SUCCEEDED operation)        REQUIRED
 * 11.  replay (MEMOIZED + WRITES_WORKSPACE rerun            REQUIRED
 *      idempotent)
 *      11a. replay decision — policy unit property           REQUIRED
 * 12.  divergence                                           COVERED BY G2 — see
 *                                                              `CoreArchiveArtifactsDifferentialContractTest`
 *                                                              (10 differential rows; the
 *                                                              deliberate divergences D1..D5 are
 *                                                              frozen there, including
 *                                                              effect-classification divergence,
 *                                                              which a same-input replay test
 *                                                              cannot express)
 * 13.  observability (StepStarted + StepFinished pair)      REQUIRED
 * 14.  architecture fitness                                 DELEGATED to
 *                                                              `Lfc2RegistryFamilyFitnessTest`,
 *                                                              the six `S3*LegacyRemovedFitnessTest`
 *                                                              suites and the six
 *                                                              `Core*RegistryPrimaryFitnessTest`
 *                                                              suites (evidence recorded in the
 *                                                              G3 readiness receipt)
 * 15.  real DSL scenario                                    REQUIRED
 * 16.  ArtifactArchived payload (relPath/sha256/size)       REQUIRED (archiveArtifacts-specific)
 * 17.  G4 counters invariant (2/3/3) + routing             REQUIRED (archiveArtifacts-specific;
 *      17b/17c)                                               G3 pinned 3/3/3, G4 pins 2/3/3)
 * ```
 */
@Timeout(60)
class CoreArchiveArtifactsStepContractSuiteTest {

    private val tempDirs = mutableListOf<Path>()

    @AfterEach
    fun cleanup() {
        tempDirs.forEach { runCatching { it.toFile().deleteRecursively() } }
        tempDirs.clear()
    }

    private fun tempDir(prefix: String): Path =
        Files.createTempDirectory(prefix).also { tempDirs.add(it) }

    private fun registry(): InMemoryStepRegistry =
        InMemoryStepRegistry().apply { CoreArchiveArtifactsStep.registerInto(this) }

    private fun noOpCredentialScopePort(): CredentialScopePort =
        CredentialScopePort { _, _ ->
            CredentialScopeOutcome.Unavailable(
                CredentialScopeFailure.StoreUnavailable("step-contract-suite stub"),
            )
        }

    private data class Harness(
        val coord: CanonicalDurableRunCoordinator,
        val journal: InMemoryOperationJournal,
        val eventStore: InMemoryEventStore,
        val registry: dev.rubentxu.pipeline.v2.domain.step.StepRegistry,
        val workDir: Path,
    )

    private fun freshHarness(
        eventStore: InMemoryEventStore,
        workDir: Path = tempDir("archiveartifacts-contract-"),
    ): Harness {
        val clock = SystemClock()
        val journal = InMemoryOperationJournal(clock)
        val registryForCoord = CoreStepRegistryFactory.registry()
        val coord = CanonicalDurableRunCoordinator(
            dispatcher = CanonicalNodeDispatcher(),
            journal = journal,
            cursorStore = InMemoryReplayCursorStore(clock),
            clock = clock,
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            eventSink = eventStore,
            credentialScopePort = noOpCredentialScopePort(),
            controlDirRoot = workDir.resolve("control"),
            shOptions = ShOptions.EMPTY,
            stepRegistry = registryForCoord,
        )
        return Harness(coord, journal, eventStore, registryForCoord, workDir)
    }

    private fun archiveNode(
        nodeId: String = "archive/archiveArtifacts",
        artifacts: String = "nope/*.jar",
        allowEmptyArchive: Boolean = true,
    ) = OpaqueStepNode(
        id = StepId(nodeId),
        pluginStepId = CoreArchiveArtifactsStep.KEY,
        payload = VersionedStepPayload(
            schemaVersion = "dsl-v1",
            encoded = CoreArchiveArtifactsStep.definition.contract.inputCodec.encode(
                ArchiveArtifactsInput(
                    artifacts = artifacts,
                    allowEmptyArchive = allowEmptyArchive,
                ),
            ).value,
        ),
    )

    private fun pipeline(vararg nodes: OpaqueStepNode) = CompiledPipeline(
        id = DefinitionId("archiveartifacts-contract-suite"),
        source = SourceDescriptor("Pipeline.kts", Digest("source")),
        pluginLockDigest = Digest("lock"),
        stages = listOf(
            StageNode(
                id = StageId("build"),
                name = "build",
                body = StageBody.Steps(nodes.toList()),
            ),
        ),
    )

    /** Seeds the stage workspace the adapter resolves: `<root>/workspace/build-0`. */
    private fun seedStageWorkspace(controlDirRoot: Path, files: Map<String, String>): Path {
        val resolver = WorkspaceResolver(controlDirRoot)
        val workspace = resolver.ensureCreated(resolver.resolve("build", 0))
        files.forEach { (relative, content) ->
            val target = workspace.resolve(relative)
            Files.createDirectories(target.parent)
            Files.writeString(target, content)
        }
        return workspace
    }

    private fun runtimeContext(
        controlDirRoot: Path?,
        runId: String,
        sink: InMemoryEventStore,
    ) = CanonicalRuntimeContext(
        opId = OpId(runId, 0, 0),
        runId = runId,
        stageName = "build",
        stageIndex = 0,
        stepIndex = 0,
        shOptions = ShOptions.EMPTY,
        controlDirRoot = controlDirRoot,
        eventSink = sink,
    )

    private fun events(sink: InMemoryEventStore, runId: String): List<DomainEvent> =
        sink.eventsFor(runId).toList()

    private suspend fun prepare(
        key: PluginStepId = CoreArchiveArtifactsStep.KEY,
        encoded: EncodedStepValue,
        available: Set<dev.rubentxu.pipeline.v2.domain.step.StepCapability>,
        registry: dev.rubentxu.pipeline.v2.domain.step.StepRegistry = registry(),
    ): ExecutionPreparation = RegistryExecutionPreparation.prepare(
        registry = registry,
        key = key,
        encodedInput = encoded,
        availableCapabilities = available,
    )

    private suspend fun coexecute(
        encoded: EncodedStepValue,
        controlDirRoot: Path,
        runId: String,
        sink: InMemoryEventStore,
        available: Set<dev.rubentxu.pipeline.v2.domain.step.StepCapability> =
            setOf(ARTIFACT_ARCHIVE_OPERATIONS_CAPABILITY),
    ): CommonExecutionResult {
        val preparation = prepare(encoded = encoded, available = available)
        val ready = assertInstanceOf(ExecutionPreparation.Ready::class.java, preparation)
        val prepared = assertInstanceOf(PreparedRegistryExecution::class.java, ready.prepared)
        return RegistryExecutionBoundary.coexecute(prepared, runtimeContext(controlDirRoot, runId, sink))
    }

    // ===== 1. identity =====

    @Test
    fun `identity — KEY is core dot archiveArtifacts and duplicate registration fails`() {
        assertEquals(PluginStepId("core.archiveArtifacts"), CoreArchiveArtifactsStep.KEY)
        assertEquals("core.archiveArtifacts", CoreArchiveArtifactsStep.KEY.value)
        assertEquals("core.archiveArtifacts", CoreArchiveArtifactsStep.definition.contract.descriptor.stepId)
        assertEquals("archiveArtifacts", CoreArchiveArtifactsStep.definition.contract.descriptor.name)
        val r = registry()
        assertTrue(
            runCatching { CoreArchiveArtifactsStep.registerInto(r) }.isFailure,
            "duplicate registration of core.archiveArtifacts must fail closed",
        )
    }

    // ===== 2. contract completeness =====

    @Test
    fun `contract completeness — key, descriptor, codecs, single capability, WRITES_WORKSPACE, MEMOIZED, None`() {
        val contract = CoreArchiveArtifactsStep.definition.contract
        assertEquals(CoreArchiveArtifactsStep.KEY, contract.key)
        assertNotNull(contract.descriptor, "StepDescriptor must be present")
        assertEquals("archiveArtifacts", contract.descriptor.name)
        assertEquals(
            setOf(Effect.WRITES_WORKSPACE),
            contract.descriptor.effects.toSet(),
            "the candidate MUST declare WRITES_WORKSPACE (copies files into retention; delta D3)",
        )
        assertEquals(
            ReplayPolicy.MEMOIZED,
            contract.descriptor.replayPolicy,
            "replayPolicy MUST be MEMOIZED (legacy metadata row parity)",
        )
        assertEquals(
            RecoveryPolicy.None,
            contract.descriptor.recoveryPolicy,
            "recoveryPolicy MUST be None (per the S2-B10 G0 inventory)",
        )
        assertEquals(
            setOf(ARTIFACT_ARCHIVE_OPERATIONS_CAPABILITY),
            contract.requiredCapabilities,
            "core.archiveArtifacts MUST declare EXACTLY {ARTIFACT_ARCHIVE_OPERATIONS}",
        )
        assertNotNull(contract.inputCodec, "input codec must be present")
        assertNotNull(contract.outputCodec, "output codec must be present")
    }

    // ===== 3. input codec round-trip =====

    @Test
    fun `codec input — default input encodes to the canonical envelope and round-trips`() {
        val value = ArchiveArtifactsInput(
            artifacts = "build/libs/*.jar",
            allowEmptyArchive = false,
            excludes = "",
            fingerprint = false,
        )
        val encoded = CoreArchiveArtifactsStep.definition.contract.inputCodec.encode(value)
        assertEquals(
            """{"kind":"archiveArtifacts","artifacts":"build/libs/*.jar","allowEmptyArchive":false,"excludes":"","fingerprint":false}""",
            encoded.value,
            "input codec must emit the canonical dsl-v1 archiveArtifacts envelope",
        )
        assertEquals(
            value,
            CoreArchiveArtifactsStep.definition.contract.inputCodec.decode(encoded),
            "round-trip must reconstruct ArchiveArtifactsInput",
        )
    }

    // ===== 3a. tolerant defaults (legacy decoder parity) =====

    @Test
    fun `codec input — decode accepts the legacy envelope without optional fields with tolerant defaults`() {
        val legacy = EncodedStepValue("""{"kind":"archiveArtifacts","artifacts":"build/libs/*.jar"}""")
        assertEquals(
            ArchiveArtifactsInput(
                artifacts = "build/libs/*.jar",
                allowEmptyArchive = false,
                excludes = "",
                fingerprint = false,
            ),
            CoreArchiveArtifactsStep.definition.contract.inputCodec.decode(legacy),
            "missing allowEmptyArchive/fingerprint MUST default false and missing excludes to \"\" (legacy decoder parity)",
        )
    }

    // ===== 3b. input codec rejection =====

    @Test
    fun `codec input — decode rejects a foreign envelope kind`() {
        val bad = EncodedStepValue("""{"kind":"echo","artifacts":"build/libs/*.jar"}""")
        assertTrue(
            runCatching { CoreArchiveArtifactsStep.definition.contract.inputCodec.decode(bad) }.isFailure,
            "input decode must fail closed on a non-archiveArtifacts kind",
        )
    }

    // ===== 4. output codec round-trip (success) =====

    @Test
    fun `codec output — ArchiveArtifactsOutput round-trips byte-identically and survives durable string form`() {
        val value = CoreArchiveArtifactsStep.ArchiveArtifactsOutput(archivedCount = 3)
        val encoded = CoreArchiveArtifactsStep.definition.contract.outputCodec.encode(value)
        assertEquals(
            value,
            CoreArchiveArtifactsStep.definition.contract.outputCodec.decode(encoded),
            "round-trip decode must reconstruct ArchiveArtifactsOutput",
        )
        assertEquals(
            value,
            CoreArchiveArtifactsStep.definition.contract.outputCodec.decode(EncodedStepValue(encoded.value)),
            "durable persistence (string round-trip) MUST decode to the same values",
        )
        assertEquals(StepOutcome.Success, value.outcome)
    }

    // ===== 4a. output codec round-trip (failure variant) =====

    @Test
    fun `codec output — ArchiveArtifactsFailureOutput round-trips with its SCRIPT kind and message`() {
        val value = CoreArchiveArtifactsStep.ArchiveArtifactsFailureOutput(
            failureKind = FailureKind.SCRIPT,
            message = "archiveArtifacts: no files matched 'build/libs/*.jar'",
        )
        val encoded = CoreArchiveArtifactsStep.definition.contract.outputCodec.encode(value)
        assertEquals(
            value,
            CoreArchiveArtifactsStep.definition.contract.outputCodec.decode(encoded),
            "the failure output variant MUST round-trip (failure kind + message are durable data)",
        )
        val outcome = assertInstanceOf(StepOutcome.Failure::class.java, value.outcome)
        assertEquals(FailureKind.SCRIPT, outcome.failure.kind)
        assertEquals(value.message, outcome.failure.message)
    }

    // ===== 4b. output codec rejection =====

    @Test
    fun `codec output — decode rejects a non-archiveArtifacts kind`() {
        val bad = EncodedStepValue("""{"kind":"cleanWs","archivedCount":1}""")
        assertTrue(
            runCatching { CoreArchiveArtifactsStep.definition.contract.outputCodec.decode(bad) }.isFailure,
            "output decode must fail closed on a non-archiveArtifacts kind",
        )
    }

    // ===== 5. canonical envelope =====

    @Test
    fun `canonical envelope — input codec is byte-identical to the legacy compiler lowering for non-default fields`() {
        val envelope = CoreArchiveArtifactsStep.definition.contract.inputCodec.encode(
            ArchiveArtifactsInput(
                artifacts = "build/libs/*.jar",
                allowEmptyArchive = true,
                excludes = "**/*.tmp",
                fingerprint = true,
            ),
        ).value
        // Verbatim shape of DslCompiledPipelineCompiler.encodePayload StepSpec.ArchiveArtifacts.
        assertEquals(
            """{"kind":"archiveArtifacts","artifacts":"build/libs/*.jar","allowEmptyArchive":true,"excludes":"**/*.tmp","fingerprint":true}""",
            envelope,
            "registry input codec MUST emit a byte-identical dsl-v1 envelope",
        )
        // Cross-acceptance: the legacy decoder consumes the candidate payload verbatim.
        val decoded = assertInstanceOf(
            CanonicalCoreStepCommand.ArchiveArtifacts::class.java,
            CanonicalCoreStepDecoder.decode(
                OpaqueStepNode(
                    id = StepId("archive-0"),
                    pluginStepId = CoreArchiveArtifactsStep.KEY,
                    payload = VersionedStepPayload("dsl-v1", envelope),
                ),
            ),
        )
        assertEquals(
            CanonicalCoreStepCommand.ArchiveArtifacts(
                artifacts = "build/libs/*.jar",
                allowEmptyArchive = true,
                excludes = "**/*.tmp",
                fingerprint = true,
            ),
            decoded,
        )
    }

    // ===== 6. production registry resolution =====

    @Test
    fun `registry resolution — production factory contains core dot archiveArtifacts`() {
        val production = CoreStepRegistryFactory.registry()
        assertTrue(
            production.contains(CoreArchiveArtifactsStep.KEY),
            "production registry must contain core.archiveArtifacts",
        )
        assertSame(
            CoreArchiveArtifactsStep.definition,
            production.definition(CoreArchiveArtifactsStep.KEY),
            "production registry MUST return the canonical CoreArchiveArtifactsStep.definition instance",
        )
    }

    // ===== 6a. fresh factory consistency =====

    @Test
    fun `registry resolution — production factory registry is fresh per call and consistent across calls`() {
        val r1 = CoreStepRegistryFactory.registry()
        val r2 = CoreStepRegistryFactory.registry()
        assertFalse(r1 === r2, "factory must produce fresh per-call registries")
        assertTrue(r1.contains(CoreArchiveArtifactsStep.KEY))
        assertTrue(r2.contains(CoreArchiveArtifactsStep.KEY))
        assertSame(
            r1.definition(CoreArchiveArtifactsStep.KEY),
            r2.definition(CoreArchiveArtifactsStep.KEY),
        )
    }

    // ===== 7. capability declaration =====

    @Test
    fun `capability declaration — core dot archiveArtifacts declares EXACTLY ARTIFACT_ARCHIVE_OPERATIONS`() {
        val declared = CoreArchiveArtifactsStep.definition.contract.requiredCapabilities
        assertEquals(
            1,
            declared.size,
            "core.archiveArtifacts MUST declare exactly 1 required capability; got ${declared.map { it.key }}",
        )
        assertTrue(ARTIFACT_ARCHIVE_OPERATIONS_CAPABILITY in declared)
        assertEquals("artifact-archive.operations", ARTIFACT_ARCHIVE_OPERATIONS_CAPABILITY.key)
    }

    // ===== 7a. capability admission (available) =====

    @Test
    fun `capability admission — the capability available prepares Ready`() {
        runBlocking {
            val preparation = prepare(
                encoded = CoreArchiveArtifactsStep.definition.contract.inputCodec.encode(
                    ArchiveArtifactsInput(artifacts = "nope/*.jar", allowEmptyArchive = true),
                ),
                available = setOf(ARTIFACT_ARCHIVE_OPERATIONS_CAPABILITY),
            )
            assertTrue(
                preparation is ExecutionPreparation.Ready,
                "admission must succeed when ARTIFACT_ARCHIVE_OPERATIONS is available",
            )
        }
    }

    // ===== 7b. missing capability =====

    @Test
    fun `missing capability — admission rejects and names the missing capability`() {
        runBlocking {
            val preparation = prepare(
                encoded = CoreArchiveArtifactsStep.definition.contract.inputCodec.encode(
                    ArchiveArtifactsInput(artifacts = "nope/*.jar", allowEmptyArchive = true),
                ),
                available = emptySet(),
            )
            val rejected = assertInstanceOf(ExecutionPreparation.Rejected::class.java, preparation)
            assertTrue(
                rejected.reason.contains(ARTIFACT_ARCHIVE_OPERATIONS_CAPABILITY.key),
                "Rejection MUST identify the missing capability; got '${rejected.reason}'",
            )
        }
    }

    // ===== 7c. conditional exposure (controlDirRoot == null) =====

    @Test
    fun `conditional exposure — controlDirRoot null means the capability is absent and admission rejects`() {
        val access = CanonicalRuntimeCapabilityAccess(runtimeContext(null, "archive-null-ctrl", InMemoryEventStore()))
        assertTrue(
            ARTIFACT_ARCHIVE_OPERATIONS_CAPABILITY !in access.available(),
            "ARTIFACT_ARCHIVE_OPERATIONS_CAPABILITY must NOT be exposed when controlDirRoot is null",
        )
        runBlocking {
            val preparation = prepare(
                encoded = CoreArchiveArtifactsStep.definition.contract.inputCodec.encode(
                    ArchiveArtifactsInput(artifacts = "nope/*.jar", allowEmptyArchive = true),
                ),
                available = access.available(),
            )
            assertTrue(
                preparation is ExecutionPreparation.Rejected,
                "admission MUST reject when the capability was not exposed (controlDirRoot=null)",
            )
        }
    }

    // ===== 8. success (typed outcome + observability) =====

    @Test
    fun `success — boundary coexecute archives matched files into retention and returns typed success`() {
        runBlocking {
            val root = tempDir("archiveartifacts-success-")
            val sink = InMemoryEventStore()
            seedStageWorkspace(root, mapOf("build/libs/smoke.jar" to "jar\n"))

            val result = coexecute(
                encoded = CoreArchiveArtifactsStep.definition.contract.inputCodec.encode(
                    ArchiveArtifactsInput(artifacts = "build/libs/*.jar", allowEmptyArchive = false),
                ),
                controlDirRoot = root,
                runId = "archive-ok",
                sink = sink,
            )

            assertEquals(StepOutcome.Success, result.outcome)
            val typed = CoreArchiveArtifactsStep.definition.contract.outputCodec.decode(result.encodedOutput!!)
            assertEquals(CoreArchiveArtifactsStep.ArchiveArtifactsOutput(archivedCount = 1), typed)

            val archived = events(sink, "archive-ok").filterIsInstance<ArtifactArchived>()
            assertEquals(1, archived.size, "exactly one ArtifactArchived per execution")
            assertEquals(listOf("build/libs/smoke.jar"), archived.single().files.map { it.relPath })

            // The retention file exists under the canonical resolver spelling (`artefacts/`).
            assertTrue(
                Files.exists(root.resolve("artefacts/archive-ok/build/build/libs/smoke.jar")),
                "the archived copy MUST land in <controlDirRoot>/artefacts/<runId>/<stage>/...",
            )
        }
    }

    // ===== 9. typed failure (the step's OWN failure, never ENGINE) =====

    @Test
    fun `typed failure — an empty match without allowEmptyArchive surfaces as the step's own SCRIPT failure`() {
        runBlocking {
            val root = tempDir("archiveartifacts-fail-")
            val sink = InMemoryEventStore()
            seedStageWorkspace(root, mapOf("build/libs/smoke.jar" to "jar\n"))

            val result = coexecute(
                encoded = CoreArchiveArtifactsStep.definition.contract.inputCodec.encode(
                    ArchiveArtifactsInput(artifacts = "nope/*.jar", allowEmptyArchive = false),
                ),
                controlDirRoot = root,
                runId = "archive-fail",
                sink = sink,
            )

            val failure = assertInstanceOf(StepOutcome.Failure::class.java, result.outcome)
            // Test law (AGENTS.md §REPLAY POLICY): the failure kind MUST be the step's
            // contractual kind, never INFRASTRUCTURE — the classic false-green is a replay /
            // admission failure masquerading as a step failure (UatStep003ErrorAbortTest).
            assertEquals(FailureKind.SCRIPT, failure.failure.kind)
            assertEquals("archiveArtifacts: no files matched 'nope/*.jar'", failure.failure.message)
            // A typed step failure IS a legitimate durable value: the failure variant of the
            // output ADT is encoded and persisted (contrast with 9a, where a thrown handler is
            // an ENGINE defect and carries no typed output at all).
            assertNotNull(result.encodedOutput, "a typed failure MUST persist its output variant")
            val encodedFailure = CoreArchiveArtifactsStep.definition.contract.outputCodec
                .decode(result.encodedOutput!!)
            assertEquals(
                CoreArchiveArtifactsStep.ArchiveArtifactsFailureOutput(
                    failureKind = FailureKind.SCRIPT,
                    message = "archiveArtifacts: no files matched 'nope/*.jar'",
                ),
                encodedFailure,
            )

            assertEquals(
                1,
                events(sink, "archive-fail").filterIsInstance<dev.rubentxu.pipeline.v2.events.ArtifactArchiveFailed>().size,
            )
            assertEquals(0, events(sink, "archive-fail").filterIsInstance<ArtifactArchived>().size)
        }
    }

    // ===== 9a. thrown-handler negative control =====

    @Test
    fun `typed failure negative control — a thrown handler is re-classified ENGINE by the boundary`() {
        val throwingRegistry = InMemoryStepRegistry().apply {
            register(
                object : StepDefinition<ArchiveArtifactsInput, dev.rubentxu.pipeline.v2.domain.durable.TypedStepOutput> {
                    override val contract = StepContract(
                        key = CoreArchiveArtifactsStep.KEY,
                        descriptor = CoreArchiveArtifactsStep.definition.contract.descriptor,
                        inputCodec = CoreArchiveArtifactsStep.definition.contract.inputCodec,
                        outputCodec = CoreArchiveArtifactsStep.definition.contract.outputCodec,
                        requiredCapabilities = setOf(ARTIFACT_ARCHIVE_OPERATIONS_CAPABILITY),
                    )

                    override val handler: StepHandler<ArchiveArtifactsInput, dev.rubentxu.pipeline.v2.domain.durable.TypedStepOutput> =
                        StepHandler { _: ArchiveArtifactsInput, _: StepHandlerContext ->
                            throw IllegalStateException("core.archiveArtifacts handler contract violated for test")
                        }
                },
            )
        }
        runBlocking {
            val root = tempDir("archiveartifacts-throw-")
            val sink = InMemoryEventStore()
            val encoded = CoreArchiveArtifactsStep.definition.contract.inputCodec.encode(
                ArchiveArtifactsInput(artifacts = "nope/*.jar", allowEmptyArchive = true),
            )
            val preparation = prepare(encoded = encoded, available = setOf(ARTIFACT_ARCHIVE_OPERATIONS_CAPABILITY), registry = throwingRegistry)
            val ready = assertInstanceOf(ExecutionPreparation.Ready::class.java, preparation)
            val prepared = assertInstanceOf(PreparedRegistryExecution::class.java, ready.prepared)

            val result = RegistryExecutionBoundary.coexecute(
                prepared,
                runtimeContext(root, "archive-throw", sink),
            )
            val failure = assertInstanceOf(StepOutcome.Failure::class.java, result.outcome)
            assertEquals(
                FailureKind.ENGINE,
                failure.failure.kind,
                "a thrown handler is an adapter/engine defect: failure kind MUST be ENGINE",
            )
            assertTrue(failure.failure.message.contains("core.archiveArtifacts handler contract violated for test"))
            assertNull(result.encodedOutput)
            assertEquals(0, events(sink, "archive-throw").filterIsInstance<ArtifactArchived>().size)
        }
    }

    // ===== 10. fresh durable =====

    @Test
    fun `fresh durable — first execution of core dot archiveArtifacts writes one terminal SUCCEEDED operation`() {
        runBlocking {
            val eventStore = InMemoryEventStore()
            val h = freshHarness(eventStore)
            val outcome = h.coord.run(pipeline(archiveNode()), RunId("archive-first"))
            assertEquals(RunOutcome.Success, outcome)
            val rows = h.journal.listForRun("archive-first")
            assertEquals(1, rows.size, "exactly one terminal operation row is journaled")
            assertEquals(OperationStatus.SUCCEEDED, rows.single().status)
        }
    }

    // ===== 11. replay (idempotent rerun) =====

    @Test
    fun `replay — MEMOIZED archiveArtifacts with WRITES_WORKSPACE reruns idempotently with identical entries`() {
        runBlocking {
            val root = tempDir("archiveartifacts-replay-")
            val sink = InMemoryEventStore()
            seedStageWorkspace(root, mapOf("build/libs/smoke.jar" to "jar\n"))
            val encoded = CoreArchiveArtifactsStep.definition.contract.inputCodec.encode(
                ArchiveArtifactsInput(artifacts = "build/libs/*.jar", allowEmptyArchive = false),
            )

            val first = coexecute(encoded, root, "archive-replay-1", sink)
            val second = coexecute(encoded, root, "archive-replay-2", sink)

            assertEquals(StepOutcome.Success, first.outcome)
            assertEquals(StepOutcome.Success, second.outcome)

            val firstEntry = events(sink, "archive-replay-1").filterIsInstance<ArtifactArchived>().single().files.single()
            val secondEntry = events(sink, "archive-replay-2").filterIsInstance<ArtifactArchived>().single().files.single()
            assertEquals(
                firstEntry.relPath to firstEntry.sha256,
                secondEntry.relPath to secondEntry.sha256,
                "REPLACE_EXISTING makes re-execution byte-identical (frozen delta D5)",
            )
            assertEquals("jar\n", Files.readString(root.resolve("artefacts/archive-replay-2/build/build/libs/smoke.jar")))
        }
    }

    // ===== 11a. replay decision — policy unit property =====

    @Test
    fun `replay decision — DefaultEffectReplayPolicy reruns MEMOIZED WRITES_WORKSPACE with a SUCCEEDED entry`() {
        assertEquals(
            ReplayDecision.RERUN,
            DefaultEffectReplayPolicy().decide(
                replayPolicy = ReplayPolicy.MEMOIZED,
                effects = setOf(Effect.WRITES_WORKSPACE),
                hasJournalEntry = true,
                journaledOutcome = OperationStatus.SUCCEEDED,
            ),
            "the frozen decision matrix pins MEMOIZED+WRITES_WORKSPACE+journaled → RERUN",
        )
    }

    // ===== 13. observability =====

    @Test
    fun `observability — every core dot archiveArtifacts run emits a StepStarted StepFinished pair`() {
        runBlocking {
            val eventStore = InMemoryEventStore()
            val h = freshHarness(eventStore)
            h.coord.run(pipeline(archiveNode()), RunId("archive-obs"))
            val emitted = events(h.eventStore, "archive-obs")
            assertTrue(emitted.any { it is StepStarted }, "StepStarted must be emitted")
            assertTrue(emitted.any { it is StepFinished }, "StepFinished must be emitted")
        }
    }

    // ===== 16. ArtifactArchived payload =====

    @Test
    fun `ArtifactArchived payload — relPath sha256 size and archivedAt are present and self-consistent`() {
        runBlocking {
            val root = tempDir("archiveartifacts-payload-")
            val sink = InMemoryEventStore()
            val workspace = seedStageWorkspace(
                root,
                mapOf("build/libs/smoke.jar" to "jar\n", "build/libs/other.jar" to "other\n"),
            )

            coexecute(
                encoded = CoreArchiveArtifactsStep.definition.contract.inputCodec.encode(
                    ArchiveArtifactsInput(artifacts = "build/libs/*.jar", allowEmptyArchive = false),
                ),
                controlDirRoot = root,
                runId = "archive-payload",
                sink = sink,
            )

            val event = events(sink, "archive-payload").filterIsInstance<ArtifactArchived>().single()
            assertEquals("ArtifactArchived", event.kind)
            assertEquals(2, event.files.size)
            event.files.forEach { entry ->
                assertEquals("archive-payload", entry.runId)
                assertEquals("build", entry.stageName)
                assertEquals(64, entry.sha256.length, "sha256 MUST be a 64-char hex digest")
                assertTrue(entry.size > 0, "size MUST reflect the archived file")
                assertTrue(
                    Files.exists(workspace.resolve(entry.relPath)),
                    "relPath '${entry.relPath}' MUST be workspace-relative",
                )
            }
            assertEquals(
                setOf("build/libs/smoke.jar", "build/libs/other.jar"),
                event.files.map { it.relPath }.toSet(),
            )
        }
    }

    // ===== 15. real DSL scenario =====

    @Test
    fun `real DSL scenario — pipeline DSL lowers archiveArtifacts and the run succeeds on both authorities`() {
        runBlocking {
            val spec: PipelineSpec = pipeline {
                stages {
                    stage("build") {
                        // allowEmptyArchive=true with a non-matching pattern is chosen deliberately:
                        // the legacy and registry authorities AGREE on this input (both succeed with
                        // an empty ArtifactArchived), so this row stays green across the G4 flip.
                        archiveArtifacts(artifacts = "nope/*.jar", allowEmptyArchive = true)
                    }
                }
            }
            val compiled = DslCompiledPipelineCompiler.compile(
                spec = spec,
                sourcePath = "archive.pipeline.kts",
                sourceContent = "pipeline { stages { stage(\"build\") { archiveArtifacts(...) } } }",
                pluginLockDigest = Digest("lock"),
            )
            val node = compiled.stages.single().let { stage ->
                assertInstanceOf(StageBody.Steps::class.java, stage.body).steps.single()
            }
            assertEquals(
                CoreArchiveArtifactsStep.KEY,
                node.pluginStepId,
                "the DSL MUST lower to the core.archiveArtifacts plugin key",
            )

            val eventStore = InMemoryEventStore()
            val h = freshHarness(eventStore)
            assertEquals(
                RunOutcome.Success,
                h.coord.run(compiled, RunId("archive-dsl")),
                "the DSL program MUST run through the canonical spine",
            )
            val archived = events(h.eventStore, "archive-dsl").filterIsInstance<ArtifactArchived>()
            assertEquals(1, archived.size, "exactly one ArtifactArchived emitted")
            assertTrue(archived.single().files.isEmpty(), "allowEmptyArchive=true parity: empty files list")
        }
    }

    // ===== 17. G4 counters invariant =====

    @Test
    fun `G4 invariant — core dot archiveArtifacts is registry-primary with counters 2 3 3`() {
        // S2-B10/G4 flipped this key to REGISTRY_PRIMARY. The residual is now
        // 2 ids / 3 metadata rows / 3 dispatcher files: the id is gone from the routing
        // authority while the metadata row and dispatcher file remain PHYSICALLY present
        // (UNREACHABLE in production) until G5/LEGACY_REMOVED converges them to 2 / 2 / 2.
        assertFalse(
            "core.archiveArtifacts" in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS,
            "core.archiveArtifacts MUST be absent from LEGACY_PLUGIN_IDS after the G4 flip",
        )
        assertEquals(2, CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS.size)
        assertEquals(
            setOf("core.load", "core.waitUntil"),
            CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS,
        )
        // G4 does NOT physically remove the legacy forms; asserting they survive is the
        // anti-over-removal half of the counter law (2 / 3 / 3, not 2 / 2 / 2).
        assertNotNull(
            CanonicalCoreStepMetadata.metadata("core.archiveArtifacts"),
            "the legacy metadata row MUST survive G4 (physical removal is G5)",
        )
        // Dispatcher-file presence is a static source property asserted by
        // LegacyResidualSnapshot in :pipeline-architecture-tests, which pins
        // 2 / 3 / 3 for this transitional stage.
    }

    // ===== 17b. G4 routing (StructuralFamilyResolver, runtime seam) =====

    @Test
    fun `G4 routing — StructuralFamilyResolver classifies core dot archiveArtifacts as Registry`() {
        // The counter law alone cannot prove the flip: LEGACY_PLUGIN_IDS is the input to
        // BOTH production consumers, so this row asserts the resolver's actual output.
        assertEquals(
            StructuralStepFamily.Registry,
            StructuralFamilyResolver.classify(
                CoreArchiveArtifactsStep.KEY,
                CoreStepRegistryFactory.registry(),
            ),
            "after G4 the key MUST classify as Registry, never LegacyCore",
        )
        // Negative controls: the rule is membership-based, not key-name-based.
        assertEquals(
            StructuralStepFamily.LegacyCore,
            StructuralFamilyResolver.classify(
                PluginStepId("core.waitUntil"),
                CoreStepRegistryFactory.registry(),
            ),
            "a key still in LEGACY_PLUGIN_IDS MUST stay LegacyCore (legacy-membership-wins)",
        )
        assertEquals(
            StructuralStepFamily.LegacyCore,
            StructuralFamilyResolver.classify(CoreArchiveArtifactsStep.KEY, null),
            "with no registry injected the key MUST stay LegacyCore (legacy coordinator unchanged)",
        )
    }

    // ===== 17c. G4 routing, end-to-end (the behavioural proof) =====

    @Test
    fun `G4 routing end-to-end — a non-empty archive succeeds through production wiring where legacy failed`() {
        runBlocking {
            // The strongest evidence that the flip is live: drive a NON-EMPTY, REAL match
            // through the full production wiring. Before G4 this input failed end-to-end
            // (legacy glob anchored on absolute paths, frozen delta D1), so a green run here
            // can only mean the registry adapter executed.
            val eventStore = InMemoryEventStore()
            val h = freshHarness(eventStore)
            seedStageWorkspace(
                h.workDir.resolve("control"),
                mapOf("build/libs/smoke.jar" to "jar\n"),
            )

            val outcome = h.coord.run(
                pipeline(archiveNode(artifacts = "build/libs/*.jar", allowEmptyArchive = false)),
                RunId("archive-g4-e2e"),
            )

            assertEquals(
                RunOutcome.Success,
                outcome,
                "the registry authority MUST archive a real match; the legacy authority could not (D1)",
            )
            val archived = events(h.eventStore, "archive-g4-e2e").filterIsInstance<ArtifactArchived>().single()
            assertEquals(
                listOf("build/libs/smoke.jar"),
                archived.files.map { it.relPath },
                "a non-empty ArtifactArchived proves the certified AntStyleGlob engine ran",
            )
        }
    }
}
