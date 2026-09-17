package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator
import dev.rubentxu.pipeline.v2.application.durable.CanonicalNodeDispatcher
import dev.rubentxu.pipeline.v2.application.durable.ExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeOutcome
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeFailure
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.DefinitionId
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.SourceDescriptor
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StageId
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.dsl.pipeline
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import pipeline.utilities.json.DefaultUtilitiesJsonOperations
import pipeline.utilities.json.DefaultUtilitiesShaOperations
import pipeline.utilities.json.ReadJsonCodec
import pipeline.utilities.json.ReadJsonInput
import pipeline.utilities.json.ReadJsonOutputCodec
import pipeline.utilities.json.ReadJsonStepDefinition
import pipeline.utilities.json.Sha256Codec
import pipeline.utilities.json.Sha256Input
import pipeline.utilities.json.Sha256OutputCodec
import pipeline.utilities.json.Sha256StepDefinition
import pipeline.utilities.json.UtilitiesJsonContributor
import pipeline.utilities.json.UtilitiesJsonOperations
import pipeline.utilities.json.UtilitiesShaOperations
import pipeline.utilities.json.WriteJsonCodec
import pipeline.utilities.json.WriteJsonInput
import pipeline.utilities.json.WriteJsonOutputCodec
import pipeline.utilities.json.WriteJsonStepDefinition
import java.nio.file.Files
import java.nio.file.Path

/**
 * StepContractSuite for the FIRST OFFICIAL_PLUGIN `pipeline.utilities.json@1.0.0`
 * (LFC-2E2 / FASE 6).
 *
 * Mirrors the certified pattern of `UppercaseStepContractSuiteTest` (LB-02). The
 * three families — `utilities.readJSON`, `utilities.writeJSON`, `utilities.sha256`
 * — are exercised end-to-end through the SAME registry-driven, open-world seam
 * used by core Steps, with two narrow typed capabilities supplied by the test
 * harness: `UtilitiesJsonOperations` and `UtilitiesShaOperations`.
 *
 * Certifies: identity, contract completeness, codec input/output, registry
 * resolution through ServiceLoader discovery, capability admission
 * (declared == used), durable fresh/replay/divergence, observability,
 * missing-capability fail-closed, and the real DSL → canonical pipeline
 * scenario for the JSON round-trip fixture.
 */
@Timeout(20)
class UtilitiesJsonStepContractSuiteTest {

    private fun jsonOps(): UtilitiesJsonOperations = DefaultUtilitiesJsonOperations()
    private fun shaOps(): UtilitiesShaOperations = DefaultUtilitiesShaOperations()

    private fun registry(): InMemoryStepRegistry =
        InMemoryStepRegistry().apply { ExternalStepPluginDiscovery.registerInto(this) }

    /**
     * Capability bridge for the utilities plugin. Layers `UtilitiesJsonOperations` and
     * `UtilitiesShaOperations` onto the canonical [CanonicalRuntimeCapabilityAccess]; the
     * latter still provides every core capability (EVENT_SINK, SHELL_OPERATIONS, …) so a
     * plugin-aware harness can run core Steps too. Built fresh per `harness()` so each
     * coordinator observes an isolated capability table.
     */
    private fun utilityCapabilityFactory(
        json: UtilitiesJsonOperations = DefaultUtilitiesJsonOperations(),
        sha: UtilitiesShaOperations = DefaultUtilitiesShaOperations(),
    ): (dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext) -> dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeCapabilityAccess =
        { ctx ->
            object : dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeCapabilityAccess(ctx) {
                private val extra: Map<StepCapability, Any> = mapOf(
                    UtilitiesJsonContributor.UTILITIES_JSON_CAPABILITY to json,
                    UtilitiesJsonContributor.UTILITIES_SHA_CAPABILITY to sha,
                )
                override fun available(): Set<StepCapability> = super.available() + extra.keys
                override fun <T : Any> get(key: StepCapability): T {
                    val layered = extra[key]
                    if (layered != null) {
                        @Suppress("UNCHECKED_CAST")
                        return layered as T
                    }
                    return super.get(key)
                }
            }
        }

    private fun harness(
        eventStore: InMemoryEventStore,
        workDir: Path = Files.createTempDirectory("utilities-contract-"),
        capabilityAccessFactory: (dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext) -> dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeCapabilityAccess =
            utilityCapabilityFactory(),
    ): Harness {
        val clock = SystemClock()
        val journal = InMemoryOperationJournal(clock)
        val coord = CanonicalDurableRunCoordinator(
            dispatcher = CanonicalNodeDispatcher(),
            journal = journal,
            cursorStore = InMemoryReplayCursorStore(clock),
            clock = clock,
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            eventSink = eventStore,
            credentialScopePort = { _, _ ->
                CredentialScopeOutcome.Unavailable(
                    CredentialScopeFailure.StoreUnavailable("utilities-contract-suite stub"),
                )
            },
            controlDirRoot = workDir.resolve("control"),
            shOptions = ShOptions.EMPTY,
            stepRegistry = registry(),
            capabilityAccessFactory = capabilityAccessFactory,
        )
        return Harness(coord, journal, eventStore)
    }

    private data class Harness(
        val coord: CanonicalDurableRunCoordinator,
        val journal: InMemoryOperationJournal,
        val eventStore: InMemoryEventStore,
    )

    /**
     * Standalone [StepCapabilityAccess] for the handler-level tests that exercise the typed
     * handler directly without going through the canonical coordinator. Supplies only the two
     * plugin-declared capabilities; no core capability is needed because these tests do not
     * delegate to a core Step.
     */
    private fun utilityCapabilities(): StepCapabilityAccess = object : StepCapabilityAccess {
        override fun available(): Set<StepCapability> = setOf(
            UtilitiesJsonContributor.UTILITIES_JSON_CAPABILITY,
            UtilitiesJsonContributor.UTILITIES_SHA_CAPABILITY,
        )
        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> get(key: StepCapability): T {
            return when (key) {
                UtilitiesJsonContributor.UTILITIES_JSON_CAPABILITY -> jsonOps() as T
                UtilitiesJsonContributor.UTILITIES_SHA_CAPABILITY -> shaOps() as T
                else -> throw IllegalStateException("unexpected capability $key")
            }
        }
    }

    // ───────── identity ─────────

    @Test
    fun `identity - three PluginStepIds are unique and contributor id is stable`() {
        assertEquals(PluginStepId("utilities.readJSON"), ReadJsonStepDefinition.KEY)
        assertEquals(PluginStepId("utilities.writeJSON"), WriteJsonStepDefinition.KEY)
        assertEquals(PluginStepId("utilities.sha256"), Sha256StepDefinition.KEY)
        assertEquals("pipeline.utilities.json", UtilitiesJsonContributor.COORDINATE)
        assertEquals("1.0.0", UtilitiesJsonContributor.PLUGIN_VERSION)
        assertTrue(ReadJsonStepDefinition.KEY != WriteJsonStepDefinition.KEY)
        assertTrue(WriteJsonStepDefinition.KEY != Sha256StepDefinition.KEY)
        assertTrue(ReadJsonStepDefinition.KEY != Sha256StepDefinition.KEY)
    }

    @Test
    fun `identity - duplicate registration of any utility step fails closed`() {
        val r = registry()
        assertTrue(runCatching { r.register(ReadJsonStepDefinition) }.isFailure)
        assertTrue(runCatching { r.register(WriteJsonStepDefinition) }.isFailure)
        assertTrue(runCatching { r.register(Sha256StepDefinition) }.isFailure)
    }

    // ───────── contract completeness ─────────

    @Test
    fun `contract completeness - readJSON declares read-only effect and MEMOIZED replay`() {
        val c = ReadJsonStepDefinition.contract
        assertEquals(ReadJsonStepDefinition.KEY, c.key)
        assertEquals("readJSON", c.descriptor.name)
        assertEquals("pipeline.utilities.json", c.descriptor.pluginId)
        assertEquals("1.0.0", c.descriptor.pluginVersion)
        assertEquals(setOf<Effect>(Effect.READ_ONLY), c.descriptor.effects.toSet())
        assertEquals(ReplayPolicy.MEMOIZED, c.descriptor.replayPolicy)
        assertEquals(
            setOf(UtilitiesJsonContributor.UTILITIES_JSON_CAPABILITY),
            c.requiredCapabilities,
        )
    }

    @Test
    fun `contract completeness - writeJSON declares WRITES_WORKSPACE effect and JSON capability only`() {
        val c = WriteJsonStepDefinition.contract
        assertEquals(WriteJsonStepDefinition.KEY, c.key)
        assertEquals("writeJSON", c.descriptor.name)
        assertEquals(setOf<Effect>(Effect.WRITES_WORKSPACE), c.descriptor.effects.toSet())
        assertEquals(ReplayPolicy.MEMOIZED, c.descriptor.replayPolicy)
        assertEquals(
            setOf(UtilitiesJsonContributor.UTILITIES_JSON_CAPABILITY),
            c.requiredCapabilities,
        )
    }

    @Test
    fun `contract completeness - sha256 declares read-only effect and SHA capability only`() {
        val c = Sha256StepDefinition.contract
        assertEquals(Sha256StepDefinition.KEY, c.key)
        assertEquals("sha256", c.descriptor.name)
        assertEquals(setOf<Effect>(Effect.READ_ONLY), c.descriptor.effects.toSet())
        assertEquals(ReplayPolicy.MEMOIZED, c.descriptor.replayPolicy)
        assertEquals(
            setOf(UtilitiesJsonContributor.UTILITIES_SHA_CAPABILITY),
            c.requiredCapabilities,
        )
    }

    // ───────── codec round-trips ─────────

    @Test
    fun `codec roundtrip - readJSON input preserves path`() {
        val encoded = ReadJsonCodec.encode(ReadJsonInput("/tmp/x.json"))
        val parsed = Json.parseToJsonElement(encoded.value)
        assertTrue(parsed is JsonObject, "canonical envelope must be a JSON object")
        val obj = parsed as JsonObject
        assertEquals("/tmp/x.json", (obj["path"] as JsonPrimitive).content)
        assertEquals(ReadJsonInput("/tmp/x.json"), ReadJsonCodec.decode(encoded))
    }

    @Test
    fun `codec roundtrip - writeJSON input preserves path, value and prettyPrint`() {
        val value = buildJsonObject { put("a", JsonPrimitive(1)) }
        val encoded = WriteJsonCodec.encode(WriteJsonInput("/tmp/out.json", value, prettyPrint = false))
        val decoded = WriteJsonCodec.decode(encoded)
        assertEquals("/tmp/out.json", decoded.path)
        assertEquals(false, decoded.prettyPrint)
        assertEquals(1, ((decoded.value as JsonObject)["a"] as JsonPrimitive).content.toInt())
    }

    @Test
    fun `codec roundtrip - sha256 input preserves path`() {
        val encoded = Sha256Codec.encode(Sha256Input("/tmp/in.bin"))
        assertEquals(Sha256Input("/tmp/in.bin"), Sha256Codec.decode(encoded))
    }

    @Test
    fun `codec - readJSON output round-trips symmetrically`() {
        val out = pipeline.utilities.json.ReadJsonOutput(
            path = "/tmp/x.json",
            bytes = 12L,
            sha256 = "deadbeef",
            value = buildJsonObject { put("k", JsonPrimitive("v")) },
        )
        val roundTripped = ReadJsonOutputCodec.decode(ReadJsonOutputCodec.encode(out))
        assertEquals(out, roundTripped)
    }

    // ───────── ServiceLoader discovery ─────────

    @Test
    fun `discovery - ServiceLoader finds utilities plugin contributor without manual registration`() {
        val r = registry()
        assertTrue(r.contains(ReadJsonStepDefinition.KEY))
        assertTrue(r.contains(WriteJsonStepDefinition.KEY))
        assertTrue(r.contains(Sha256StepDefinition.KEY))
    }

    // ───────── capability admission ─────────

    @Test
    fun `capability admission - readJSON prepares Ready when JSON capability is available`() {
        val admission = RegistryExecutionPreparation.prepare(
            registry = registry(),
            key = ReadJsonStepDefinition.KEY,
            encodedInput = ReadJsonCodec.encode(ReadJsonInput("/tmp/a.json")),
            availableCapabilities = setOf(UtilitiesJsonContributor.UTILITIES_JSON_CAPABILITY),
        )
        assertInstanceOf(ExecutionPreparation.Ready::class.java, admission)
    }

    @Test
    fun `capability admission - sha256 prepares Ready when SHA capability is available`() {
        val admission = RegistryExecutionPreparation.prepare(
            registry = registry(),
            key = Sha256StepDefinition.KEY,
            encodedInput = Sha256Codec.encode(Sha256Input("/tmp/a.bin")),
            availableCapabilities = setOf(UtilitiesJsonContributor.UTILITIES_SHA_CAPABILITY),
        )
        assertInstanceOf(ExecutionPreparation.Ready::class.java, admission)
    }

    @Test
    fun `capability admission - missing JSON capability REJECTS readJSON before handler runs`() {
        val admission = RegistryExecutionPreparation.prepare(
            registry = registry(),
            key = ReadJsonStepDefinition.KEY,
            encodedInput = ReadJsonCodec.encode(ReadJsonInput("/tmp/a.json")),
            availableCapabilities = setOf(UtilitiesJsonContributor.UTILITIES_SHA_CAPABILITY),
        )
        val rejected = assertInstanceOf(ExecutionPreparation.Rejected::class.java, admission)
        assertTrue(
            rejected.reason.contains(UtilitiesJsonContributor.UTILITIES_JSON_CAPABILITY.key),
            "rejection must name JSON capability, was: ${rejected.reason}",
        )
    }

    @Test
    fun `capability admission - missing SHA capability REJECTS sha256 before handler runs`() {
        val admission = RegistryExecutionPreparation.prepare(
            registry = registry(),
            key = Sha256StepDefinition.KEY,
            encodedInput = Sha256Codec.encode(Sha256Input("/tmp/a.bin")),
            availableCapabilities = setOf(UtilitiesJsonContributor.UTILITIES_JSON_CAPABILITY),
        )
        assertInstanceOf(ExecutionPreparation.Rejected::class.java, admission)
    }

    // ───────── handler semantics (typed, direct through the seam) ─────────

    @Test
    fun `handler - readJSON round-trips a real file through the typed handler seam`() = runBlocking {
        val workDir = Files.createTempDirectory("utilities-read-")
        val inputFile = workDir.resolve("input.json")
        Files.writeString(inputFile, """{"name":"pipeline","n":42}""")
        val ctx = StepHandlerContext(
            runId = RunId("utilities-read"),
            stepIndex = 0,
            capabilities = utilityCapabilities(),
        )
        val out = ReadJsonStepDefinition.handler.execute(
            ReadJsonInput(inputFile.toString()),
            ctx,
        )
        assertEquals(inputFile.toString(), out.path)
        assertTrue(out.bytes > 0L)
        assertTrue(out.sha256.length == 64, "sha256 hex must be 64 chars, got ${out.sha256.length}")
        val obj = out.value as JsonObject
        assertEquals("pipeline", (obj["name"] as JsonPrimitive).content)
        assertEquals(42, (obj["n"] as JsonPrimitive).content.toInt())
    }

    @Test
    fun `handler - writeJSON then sha256 produces matching digest of the written file`() = runBlocking {
        val workDir = Files.createTempDirectory("utilities-roundtrip-")
        val sourceFile = workDir.resolve("source.json")
        Files.writeString(sourceFile, """{"k":"v"}""")
        val targetFile = workDir.resolve("target.json")
        val ctx = StepHandlerContext(
            runId = RunId("utilities-roundtrip"),
            stepIndex = 0,
            capabilities = utilityCapabilities(),
        )
        // Step 1: read source
        val read = ReadJsonStepDefinition.handler.execute(
            ReadJsonInput(sourceFile.toString()),
            ctx,
        )
        // Step 2: write target (no prettyPrint, deterministic bytes)
        WriteJsonStepDefinition.handler.execute(
            WriteJsonInput(targetFile.toString(), read.value, prettyPrint = false),
            ctx,
        )
        // Step 3: sha256 of target
        val digest = Sha256StepDefinition.handler.execute(
            Sha256Input(targetFile.toString()),
            ctx,
        )
        assertEquals(Files.size(targetFile), digest.bytes)
        // The expected digest: target bytes are Json-encoded with encodeDefaults=true, prettyPrint=false.
        val expectedJson = Json.encodeToString(JsonElement.serializer(), read.value)
        val expectedBytes = expectedJson.toByteArray(Charsets.UTF_8)
        val expectedDigest = java.security.MessageDigest.getInstance("SHA-256").digest(expectedBytes)
            .joinToString("") { "%02x".format(it) }
        assertEquals(expectedDigest, digest.sha256)
    }

    // ───────── durable: fresh / replay / divergence ─────────

    @Test
    fun `fresh durable - sha256 writes exactly one terminal SUCCEEDED operation`() {
        val eventStore = InMemoryEventStore()
        val workDir = Files.createTempDirectory("utilities-fresh-")
        val f = workDir.resolve("a.bin")
        Files.writeString(f, "alpha")
        val h = harness(eventStore, workDir)
        runBlocking {
            val outcome = h.coord.run(
                pipelineOf(Sha256StepDefinition.KEY, Sha256Codec.encode(Sha256Input(f.toString()))),
                RunId("fresh-sha"),
            )
            assertEquals(RunOutcome.Success, outcome)
            val rows = h.journal.listForRun("fresh-sha")
            assertEquals(1, rows.size, "exactly one terminal operation row is journaled")
            assertEquals(OperationStatus.SUCCEEDED, rows.single().status)
        }
    }

    @Test
    fun `replay - a previously SUCCEEDED sha256 run is reused without re-running the handler`() {
        val eventStore = InMemoryEventStore()
        val workDir = Files.createTempDirectory("utilities-replay-")
        val f = workDir.resolve("a.bin")
        Files.writeString(f, "alpha")
        val h = harness(eventStore, workDir)
        runBlocking {
            val first = h.coord.run(
                pipelineOf(Sha256StepDefinition.KEY, Sha256Codec.encode(Sha256Input(f.toString()))),
                RunId("replay-sha"),
            )
            assertEquals(RunOutcome.Success, first)
            val second = h.coord.run(
                pipelineOf(Sha256StepDefinition.KEY, Sha256Codec.encode(Sha256Input(f.toString()))),
                RunId("replay-sha"),
            )
            assertEquals(RunOutcome.Success, second, "replay of a SUCCEEDED run must succeed (reuse)")
        }
    }

    @Test
    fun `divergence - replaying a SUCCEEDED sha256 run with a different input fails closed`() {
        val eventStore = InMemoryEventStore()
        val workDir = Files.createTempDirectory("utilities-div-")
        val f1 = workDir.resolve("f1.bin")
        val f2 = workDir.resolve("f2.bin")
        Files.writeString(f1, "alpha")
        Files.writeString(f2, "beta")
        val h = harness(eventStore, workDir)
        runBlocking {
            h.coord.run(
                pipelineOf(Sha256StepDefinition.KEY, Sha256Codec.encode(Sha256Input(f1.toString()))),
                RunId("div-sha"),
            )
            val outcome = h.coord.run(
                pipelineOf(Sha256StepDefinition.KEY, Sha256Codec.encode(Sha256Input(f2.toString()))),
                RunId("div-sha"),
            )
            assertTrue(
                outcome is RunOutcome.Failure || outcome is RunOutcome.Unstable,
                "divergence must surface as a typed failure/unstable, was: $outcome",
            )
        }
    }

    // ───────── observability ─────────

    @Test
    fun `observability - sha256 run emits StepStarted and StepFinished`() {
        val eventStore = InMemoryEventStore()
        val workDir = Files.createTempDirectory("utilities-obs-")
        val f = workDir.resolve("a.bin")
        Files.writeString(f, "alpha")
        val h = harness(eventStore, workDir)
        runBlocking {
            h.coord.run(
                pipelineOf(Sha256StepDefinition.KEY, Sha256Codec.encode(Sha256Input(f.toString()))),
                RunId("obs-sha"),
            )
            val events = h.eventStore.eventsFor("obs-sha").toList()
            assertTrue(events.any { it is StepStarted }, "StepStarted must be emitted")
            assertTrue(events.any { it is StepFinished }, "StepFinished must be emitted")
        }
    }

    // ───────── real DSL scenario ─────────

    @Test
    fun `real DSL scenario - json round-trip runs end-to-end through the canonical spine`() = runBlocking {
        val workDir = Files.createTempDirectory("utilities-dsl-")
        val sourceFile = workDir.resolve("source.json")
        val targetFile = workDir.resolve("target.json")
        Files.writeString(sourceFile, """{"hello":"world"}""")
        val spec: PipelineSpec = pipeline {
            stages {
                stage("Utilities") {
                    registryStep(
                        stepKey = ReadJsonStepDefinition.KEY,
                        encodedInput = ReadJsonCodec.encode(ReadJsonInput(sourceFile.toString())),
                    )
                    registryStep(
                        stepKey = WriteJsonStepDefinition.KEY,
                        encodedInput = WriteJsonCodec.encode(
                            WriteJsonInput(targetFile.toString(), buildJsonObject {
                                put("mirrored", JsonPrimitive("world"))
                            }),
                        ),
                    )
                    registryStep(
                        stepKey = Sha256StepDefinition.KEY,
                        encodedInput = Sha256Codec.encode(Sha256Input(targetFile.toString())),
                    )
                }
            }
        }
        val compiled = DslCompiledPipelineCompiler.compile(
            spec = spec,
            sourcePath = "01-json-roundtrip.pipeline.kts",
            sourceContent = spec.toString(),
            pluginLockDigest = Digest("lock"),
        )
        val eventStore = InMemoryEventStore()
        val h = harness(eventStore, workDir)
        val run = h.coord.run(compiled, RunId("dsl-roundtrip"))
        assertEquals(RunOutcome.Success, run)
        // target.json must now exist on disk
        assertTrue(Files.exists(targetFile))
        val events = h.eventStore.eventsFor("dsl-roundtrip").toList()
        assertTrue(events.any { it is StepStarted })
        assertTrue(events.any { it is StepFinished })
    }

    @Test
    fun `real DSL scenario - typed failure (missing-file readJSON) propagates typed UtilitiesJsonException carrying JsonNotFound reason`() = runBlocking {
        // This is the canonical "installed-acceptance proof" for the typed-failure path:
        // a real DSL pipeline runs through the canonical coordinator + canonical boundary,
        // the typed handler throws UtilitiesJsonException(JsonNotFound), the boundary wraps
        // it as a typed engine failure, and the canonical RunOutcome is a Failure whose
        // cause preserves the typed exception.
        val workDir = Files.createTempDirectory("utilities-dsl-missing-")
        val missingPath = workDir.resolve("definitely-missing.json").toString()
        val spec: PipelineSpec = pipeline {
            stages {
                stage("TypedFailure") {
                    registryStep(
                        stepKey = ReadJsonStepDefinition.KEY,
                        encodedInput = ReadJsonCodec.encode(ReadJsonInput(missingPath)),
                    )
                }
            }
        }
        val compiled = DslCompiledPipelineCompiler.compile(
            spec = spec,
            sourcePath = "02-json-typed-failure.pipeline.kts",
            sourceContent = spec.toString(),
            pluginLockDigest = Digest("lock"),
        )
        val eventStore = InMemoryEventStore()
        val h = harness(eventStore, workDir)
        val run = h.coord.run(compiled, RunId("dsl-typed-failure"))
        assertTrue(
            run is RunOutcome.Failure,
            "missing-file readJSON must surface as RunOutcome.Failure, was: ${'$'}run",
        )
        val failure = (run as RunOutcome.Failure).failure
        val cause = failure.cause
        assertTrue(
            cause is pipeline.utilities.json.UtilitiesJsonException,
            "typed failure cause must be UtilitiesJsonException, was: ${'$'}{cause?.javaClass?.name}",
        )
        val typedCause = cause as pipeline.utilities.json.UtilitiesJsonException
        assertTrue(
            typedCause.reason is pipeline.utilities.json.UtilitiesJsonError.JsonNotFound,
            "typed failure reason must be JsonNotFound, was: ${'$'}{typedCause.reason::class.simpleName}",
        )
        assertEquals(
            missingPath,
            (typedCause.reason as pipeline.utilities.json.UtilitiesJsonError.JsonNotFound).path,
        )
    }

    // ───────── helpers ─────────

    private fun pipelineOf(key: PluginStepId, encodedInput: EncodedStepValue): CompiledPipeline {
        val node = OpaqueStepNode(
            id = StepId("utilities"),
            pluginStepId = key,
            payload = VersionedStepPayload(schemaVersion = "dsl-v1", encoded = encodedInput.value),
        )
        return CompiledPipeline(
            id = DefinitionId("utilities-contract"),
            source = SourceDescriptor("Pipeline.kts", Digest("source")),
            pluginLockDigest = Digest("lock"),
            stages = listOf(
                StageNode(
                    id = StageId("utilities"),
                    name = "Utilities",
                    body = StageBody.Steps(listOf(node)),
                ),
            ),
        )
    }

    // ───────── typed failure semantics (LFC-2E2-EXPANSION U1 — JSON hardening) ─────────

    @Test
    fun `typed failure - readJSON of a missing file throws UtilitiesJsonException(JsonNotFound)`() = runBlocking {
        val ctx = StepHandlerContext(
            runId = RunId("utilities-missing"),
            stepIndex = 0,
            capabilities = utilityCapabilities(),
        )
        val missingPath = "/tmp/utilities-definitely-does-not-exist-${'$'}{System.nanoTime()}.json"
        val caught = runCatching {
            ReadJsonStepDefinition.handler.execute(ReadJsonInput(missingPath), ctx)
        }.exceptionOrNull()
        assertTrue(
            caught is pipeline.utilities.json.UtilitiesJsonException,
            "missing file must throw UtilitiesJsonException, was: ${caught?.javaClass?.name}",
        )
        val typed = caught as pipeline.utilities.json.UtilitiesJsonException
        val reason = typed.reason
        assertTrue(
            reason is pipeline.utilities.json.UtilitiesJsonError.JsonNotFound,
            "missing-file failure must be typed as JsonNotFound, was: ${reason::class.simpleName}",
        )
        assertEquals(missingPath, (reason as pipeline.utilities.json.UtilitiesJsonError.JsonNotFound).path)
    }

    @Test
    fun `typed failure - readJSON of malformed JSON throws UtilitiesJsonException(JsonParseFailure)`() = runBlocking {
        val workDir = Files.createTempDirectory("utilities-malformed-")
        val badFile = workDir.resolve("not-json.json")
        Files.writeString(badFile, "this is not valid JSON {{{ :::")
        val ctx = StepHandlerContext(
            runId = RunId("utilities-malformed"),
            stepIndex = 0,
            capabilities = utilityCapabilities(),
        )
        val caught = runCatching {
            ReadJsonStepDefinition.handler.execute(ReadJsonInput(badFile.toString()), ctx)
        }.exceptionOrNull()
        assertTrue(
            caught is pipeline.utilities.json.UtilitiesJsonException,
            "malformed JSON must throw UtilitiesJsonException, was: ${caught?.javaClass?.name}",
        )
        val typed = caught as pipeline.utilities.json.UtilitiesJsonException
        val reason = typed.reason
        assertTrue(
            reason is pipeline.utilities.json.UtilitiesJsonError.JsonParseFailure,
            "malformed-JSON failure must be typed as JsonParseFailure, was: ${reason::class.simpleName}",
        )
        assertEquals(badFile.toString(), (reason as pipeline.utilities.json.UtilitiesJsonError.JsonParseFailure).path)
    }

    @Test
    fun `typed failure - sha256 of a missing file throws UtilitiesJsonException(JsonNotFound)`() = runBlocking {
        val ctx = StepHandlerContext(
            runId = RunId("utilities-sha-missing"),
            stepIndex = 0,
            capabilities = utilityCapabilities(),
        )
        val missingPath = "/tmp/utilities-sha-missing-${'$'}{System.nanoTime()}.bin"
        val caught = runCatching {
            Sha256StepDefinition.handler.execute(Sha256Input(missingPath), ctx)
        }.exceptionOrNull()
        assertTrue(
            caught is pipeline.utilities.json.UtilitiesJsonException,
            "missing file must throw UtilitiesJsonException, was: ${caught?.javaClass?.name}",
        )
        assertTrue(
            (caught as pipeline.utilities.json.UtilitiesJsonException).reason
                is pipeline.utilities.json.UtilitiesJsonError.JsonNotFound,
        )
    }

    @Test
    fun `typed failure - UtilitiesJsonError sealed ADT is exhaustively matchable (3 cases)`() {
        val cases = listOf(
            pipeline.utilities.json.UtilitiesJsonError.JsonNotFound("/x"),
            pipeline.utilities.json.UtilitiesJsonError.JsonParseFailure("/y", "boom"),
            pipeline.utilities.json.UtilitiesJsonError.JsonIoFailure("/z", "EACCES"),
        )
        // Exhaustive `when` over the sealed ADT — compiles iff all three variants exist.
        val mapped: List<String> = cases.map { reason ->
            when (reason) {
                is pipeline.utilities.json.UtilitiesJsonError.JsonNotFound -> "notfound:" + reason.path
                is pipeline.utilities.json.UtilitiesJsonError.JsonParseFailure -> "parse:" + reason.path
                is pipeline.utilities.json.UtilitiesJsonError.JsonIoFailure -> "io:" + reason.path
            }
        }
        assertEquals(3, mapped.size)
        assertEquals("notfound:/x", mapped[0])
        assertEquals("parse:/y", mapped[1])
        assertEquals("io:/z", mapped[2])
    }
}
