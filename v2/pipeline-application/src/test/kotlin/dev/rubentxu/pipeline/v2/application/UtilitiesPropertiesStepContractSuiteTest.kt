package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator
import dev.rubentxu.pipeline.v2.application.durable.CanonicalNodeDispatcher
import dev.rubentxu.pipeline.v2.application.durable.ExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeFailure
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeOutcome
import dev.rubentxu.pipeline.v2.application.SystemClock
import dev.rubentxu.pipeline.v2.application.DslCompiledPipelineCompiler
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.dsl.pipeline
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import pipeline.utilities.properties.DefaultUtilitiesPropertiesOperations
import pipeline.utilities.properties.ReadPropertiesCodec
import pipeline.utilities.properties.ReadPropertiesInput
import pipeline.utilities.properties.ReadPropertiesStepDefinition
import pipeline.utilities.properties.UtilitiesPropertiesContributor
import pipeline.utilities.properties.UtilitiesPropertiesError
import pipeline.utilities.properties.UtilitiesPropertiesException
import pipeline.utilities.properties.UtilitiesPropertiesOperations
import pipeline.utilities.properties.WritePropertiesCodec
import pipeline.utilities.properties.WritePropertiesInput
import pipeline.utilities.properties.WritePropertiesStepDefinition
import java.nio.file.Files
import java.nio.file.Path

/**
 * StepContractSuite for the LFC-2E2-EXPANSION U3 properties slice.
 *
 * Mirrors the U1 JSON + U2 YAML hardening pattern with a smaller footprint
 * because the architectural claim (zero production core change, same contributor,
 * new typed ADT) is already established by U1/U2. U3 focuses on the most
 * representative rows: identity, contract completeness, capability admission,
 * handler semantics, typed failure, and real DSL scenario.
 */
@Timeout(20)
class UtilitiesPropertiesStepContractSuiteTest {

    private fun propertiesOps(): UtilitiesPropertiesOperations = DefaultUtilitiesPropertiesOperations()

    private fun registry(): InMemoryStepRegistry =
        InMemoryStepRegistry().apply { ExternalStepPluginDiscovery.registerInto(this) }

    private fun utilityPropertiesCapabilityFactory(
        properties: UtilitiesPropertiesOperations = DefaultUtilitiesPropertiesOperations(),
    ): (dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext) -> dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeCapabilityAccess =
        { ctx ->
            object : dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeCapabilityAccess(ctx) {
                private val extra: Map<StepCapability, Any> = mapOf(
                    UtilitiesPropertiesContributor.UTILITIES_PROPERTIES_CAPABILITY to properties,
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
        workDir: Path = Files.createTempDirectory("utilities-props-contract-"),
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
                    CredentialScopeFailure.StoreUnavailable("utilities-props-contract-suite stub"),
                )
            },
            controlDirRoot = workDir.resolve("control"),
            shOptions = ShOptions.EMPTY,
            stepRegistry = registry(),
            capabilityAccessFactory = utilityPropertiesCapabilityFactory(),
        )
        return Harness(coord, journal, eventStore)
    }

    private data class Harness(
        val coord: CanonicalDurableRunCoordinator,
        val journal: InMemoryOperationJournal,
        val eventStore: InMemoryEventStore,
    )

    private fun propertiesCapabilities(): StepCapabilityAccess = object : StepCapabilityAccess {
        override fun available(): Set<StepCapability> = setOf(
            UtilitiesPropertiesContributor.UTILITIES_PROPERTIES_CAPABILITY,
        )
        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> get(key: StepCapability): T {
            return when (key) {
                UtilitiesPropertiesContributor.UTILITIES_PROPERTIES_CAPABILITY -> propertiesOps() as T
                else -> throw IllegalStateException("unexpected capability $key")
            }
        }
    }

    // ───────── identity ─────────

    @Test
    fun `identity - two properties PluginStepIds are unique and coordinate matches JSON plugin coordinate`() {
        assertEquals(PluginStepId("utilities.readProperties"), ReadPropertiesStepDefinition.KEY)
        assertEquals(PluginStepId("utilities.writeProperties"), WritePropertiesStepDefinition.KEY)
        assertTrue(ReadPropertiesStepDefinition.KEY != WritePropertiesStepDefinition.KEY)
        assertEquals(UtilitiesPropertiesContributor.COORDINATE, pipeline.utilities.json.UtilitiesJsonContributor.COORDINATE)
    }

    @Test
    fun `identity - properties families coexist in the SAME contributor as JSON and YAML families`() {
        val r = registry()
        assertTrue(r.contains(PluginStepId("utilities.readProperties")))
        assertTrue(r.contains(PluginStepId("utilities.writeProperties")))
        assertTrue(r.contains(PluginStepId("utilities.readJSON")))
        assertTrue(r.contains(PluginStepId("utilities.writeYaml")))
        // 14 utilities (3 JSON + 2 YAML + 2 properties + 2 filesystem + 3 checksums + 2 archive) + 1 example.uppercase = 15 total
        assertEquals(17, r.keys().size)
    }

    // ───────── contract completeness ─────────

    @Test
    fun `contract completeness - readProperties declares read-only effect and properties capability only`() {
        val c = ReadPropertiesStepDefinition.contract
        assertEquals(setOf<Effect>(Effect.READ_ONLY), c.descriptor.effects.toSet())
        assertEquals(ReplayPolicy.MEMOIZED, c.descriptor.replayPolicy)
        assertEquals(
            setOf(UtilitiesPropertiesContributor.UTILITIES_PROPERTIES_CAPABILITY),
            c.requiredCapabilities,
        )
    }

    @Test
    fun `contract completeness - writeProperties declares WRITES_WORKSPACE effect and properties capability only`() {
        val c = WritePropertiesStepDefinition.contract
        assertEquals(setOf<Effect>(Effect.WRITES_WORKSPACE), c.descriptor.effects.toSet())
        assertEquals(ReplayPolicy.MEMOIZED, c.descriptor.replayPolicy)
        assertEquals(
            setOf(UtilitiesPropertiesContributor.UTILITIES_PROPERTIES_CAPABILITY),
            c.requiredCapabilities,
        )
    }

    @Test
    fun `contract completeness - properties plugin declares exactly one new capability token`() {
        val src = java.io.File(
            java.io.File(System.getProperty("user.dir"), "../..").canonicalFile,
            "examples/utilities-plugin/src/main/kotlin/pipeline/utilities/properties/UtilitiesPropertiesPlugin.kt",
        ).readText()
        val declared = Regex("""StepCapability\(\"([^\"]+)\"""")
            .findAll(src)
            .map { it.groupValues[1] }
            .toSet()
        assertEquals(
            setOf("utilities.properties.operations"),
            declared,
            "U3 properties plugin must declare exactly one capability token (utilities.properties.operations)",
        )
    }

    // ───────── capability admission ─────────

    @Test
    fun `capability admission - readProperties prepares Ready when properties capability is available`() {
        val admission = RegistryExecutionPreparation.prepare(
            registry = registry(),
            key = ReadPropertiesStepDefinition.KEY,
            encodedInput = ReadPropertiesCodec.encode(ReadPropertiesInput("/tmp/a.properties")),
            availableCapabilities = setOf(UtilitiesPropertiesContributor.UTILITIES_PROPERTIES_CAPABILITY),
        )
        assertInstanceOf(ExecutionPreparation.Ready::class.java, admission)
    }

    @Test
    fun `capability admission - missing properties capability REJECTS readProperties before handler runs`() {
        val admission = RegistryExecutionPreparation.prepare(
            registry = registry(),
            key = ReadPropertiesStepDefinition.KEY,
            encodedInput = ReadPropertiesCodec.encode(ReadPropertiesInput("/tmp/a.properties")),
            availableCapabilities = emptySet(),
        )
        assertInstanceOf(ExecutionPreparation.Rejected::class.java, admission)
    }

    // ───────── handler semantics ─────────

    @Test
    fun `handler - readProperties round-trips a real file through the typed handler seam`() = runBlocking {
        val workDir = Files.createTempDirectory("utilities-props-read-")
        val inputFile = workDir.resolve("input.properties")
        Files.writeString(inputFile, "name=pipeline\nversion=1\n")
        val ctx = StepHandlerContext(
            runId = RunId("utilities-props-read"),
            stepIndex = 0,
            capabilities = propertiesCapabilities(),
        )
        val out = ReadPropertiesStepDefinition.handler.execute(
            ReadPropertiesInput(inputFile.toString()),
            ctx,
        )
        assertEquals(inputFile.toString(), out.path)
        assertTrue(out.bytes > 0L)
        assertEquals(2, out.entryCount)
        assertEquals("pipeline", (out.entries["name"] as JsonPrimitive).content)
        assertEquals("1", (out.entries["version"] as JsonPrimitive).content)
    }

    @Test
    fun `handler - writeProperties then readProperties round-trip preserves entries`() = runBlocking {
        val workDir = Files.createTempDirectory("utilities-props-roundtrip-")
        val targetFile = workDir.resolve("target.properties")
        val value = buildJsonObject {
            put("a", JsonPrimitive("1"))
            put("b", JsonPrimitive("two"))
        }
        val ctx = StepHandlerContext(
            runId = RunId("utilities-props-write"),
            stepIndex = 0,
            capabilities = propertiesCapabilities(),
        )
        WritePropertiesStepDefinition.handler.execute(
            WritePropertiesInput(targetFile.toString(), value),
            ctx,
        )
        val roundtripped = ReadPropertiesStepDefinition.handler.execute(
            ReadPropertiesInput(targetFile.toString()),
            ctx,
        )
        assertEquals(2, roundtripped.entryCount)
        assertEquals("1", (roundtripped.entries["a"] as JsonPrimitive).content)
        assertEquals("two", (roundtripped.entries["b"] as JsonPrimitive).content)
    }

    // ───────── typed failure semantics ─────────

    @Test
    fun `typed failure - readProperties of a missing file throws UtilitiesPropertiesException(PropertiesNotFound)`() = runBlocking {
        val ctx = StepHandlerContext(
            runId = RunId("utilities-props-missing"),
            stepIndex = 0,
            capabilities = propertiesCapabilities(),
        )
        val missingPath = "/tmp/utilities-props-missing-${'$'}{System.nanoTime()}.properties"
        val caught = runCatching {
            ReadPropertiesStepDefinition.handler.execute(ReadPropertiesInput(missingPath), ctx)
        }.exceptionOrNull()
        assertTrue(
            caught is UtilitiesPropertiesException,
            "missing file must throw UtilitiesPropertiesException, was: ${caught?.javaClass?.name}",
        )
        assertTrue(
            (caught as UtilitiesPropertiesException).reason
                is UtilitiesPropertiesError.PropertiesNotFound,
        )
    }

    @Test
    fun `typed failure - UtilitiesPropertiesError sealed ADT is exhaustively matchable (2 cases)`() {
        val cases = listOf(
            UtilitiesPropertiesError.PropertiesNotFound("/x"),
            UtilitiesPropertiesError.PropertiesIoFailure("/y", "EACCES"),
        )
        val mapped: List<String> = cases.map { reason ->
            when (reason) {
                is UtilitiesPropertiesError.PropertiesNotFound -> "notfound:" + reason.path
                is UtilitiesPropertiesError.PropertiesIoFailure -> "io:" + reason.path
            }
        }
        assertEquals(2, mapped.size)
        assertEquals("notfound:/x", mapped[0])
        assertEquals("io:/y", mapped[1])
    }

    // ───────── real DSL scenario ─────────

    @Test
    fun `real DSL scenario - readProperties + writeProperties round-trip runs end-to-end through the canonical spine`() = runBlocking {
        val workDir = Files.createTempDirectory("utilities-props-dsl-")
        val sourceFile = workDir.resolve("source.properties")
        Files.writeString(sourceFile, "kind=pipeline\nversion=1\n")
        val targetFile = workDir.resolve("target.properties")
        val spec: PipelineSpec = pipeline {
            stages {
                stage("PropertiesRoundtrip") {
                    registryStep(
                        stepKey = ReadPropertiesStepDefinition.KEY,
                        encodedInput = ReadPropertiesCodec.encode(ReadPropertiesInput(sourceFile.toString())),
                    )
                    registryStep(
                        stepKey = WritePropertiesStepDefinition.KEY,
                        encodedInput = WritePropertiesCodec.encode(
                            WritePropertiesInput(targetFile.toString(), buildJsonObject {
                                put("mirrored", JsonPrimitive("yes"))
                            }),
                        ),
                    )
                }
            }
        }
        val compiled = DslCompiledPipelineCompiler.compile(
            spec = spec,
            sourcePath = "03-properties-roundtrip.pipeline.kts",
            sourceContent = spec.toString(),
            pluginLockDigest = dev.rubentxu.pipeline.v2.domain.Digest("lock"),
        )
        val eventStore = InMemoryEventStore()
        val h = harness(eventStore, workDir)
        val run = h.coord.run(compiled, RunId("dsl-properties-roundtrip"))
        assertEquals(RunOutcome.Success, run)
        assertTrue(Files.exists(targetFile))
    }
}
