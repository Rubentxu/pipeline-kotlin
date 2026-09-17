package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator
import dev.rubentxu.pipeline.v2.application.durable.CanonicalNodeDispatcher
import dev.rubentxu.pipeline.v2.application.durable.ExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeFailure
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeOutcome
import dev.rubentxu.pipeline.v2.application.DslCompiledPipelineCompiler
import dev.rubentxu.pipeline.v2.application.SystemClock
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import pipeline.utilities.yaml.DefaultUtilitiesYamlOperations
import pipeline.utilities.yaml.ReadYamlCodec
import pipeline.utilities.yaml.ReadYamlInput
import pipeline.utilities.yaml.ReadYamlStepDefinition
import pipeline.utilities.yaml.UtilitiesYamlContributor
import pipeline.utilities.yaml.UtilitiesYamlError
import pipeline.utilities.yaml.UtilitiesYamlException
import pipeline.utilities.yaml.UtilitiesYamlOperations
import pipeline.utilities.yaml.WriteYamlCodec
import pipeline.utilities.yaml.WriteYamlInput
import pipeline.utilities.yaml.WriteYamlStepDefinition
import java.nio.file.Files
import java.nio.file.Path

/**
 * StepContractSuite for the LFC-2E2-EXPANSION U2 YAML slice.
 *
 * Mirrors the U1 JSON hardening pattern: identity, contract completeness, codec
 * round-trips, registry resolution via ServiceLoader, capability admission
 * (declared == used), handler semantics (read/write round-trip), typed failure
 * semantics (typed YamlException + sealed YamlError ADT), and a real-DSL scenario.
 *
 * Certifies: the YAML capability port expands the existing OFFICIAL_PLUGIN
 * family WITHOUT any production core change, and the typed failure surface is
 * the same shape as U1's JSON one.
 */
@Timeout(20)
class UtilitiesYamlStepContractSuiteTest {

    private fun yamlOps(): UtilitiesYamlOperations = DefaultUtilitiesYamlOperations()

    private fun registry(): InMemoryStepRegistry =
        InMemoryStepRegistry().apply { ExternalStepPluginDiscovery.registerInto(this) }

    /**
     * Capability bridge for the YAML capability: layers `UtilitiesYamlOperations`
     * onto the canonical bridge; the latter still provides every core capability
     * so a host that does not bind a YAML capability sees the suite's typed
     * rejection row in the contract.
     */
    private fun utilityYamlCapabilityFactory(
        yaml: UtilitiesYamlOperations = DefaultUtilitiesYamlOperations(),
    ): (dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext) -> dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeCapabilityAccess =
        { ctx ->
            object : dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeCapabilityAccess(ctx) {
                private val extra: Map<StepCapability, Any> = mapOf(
                    UtilitiesYamlContributor.UTILITIES_YAML_CAPABILITY to yaml,
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
        workDir: Path = Files.createTempDirectory("utilities-yaml-contract-"),
        capabilityAccessFactory: (dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext) -> dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeCapabilityAccess =
            utilityYamlCapabilityFactory(),
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
                    CredentialScopeFailure.StoreUnavailable("utilities-yaml-contract-suite stub"),
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

    private fun yamlCapabilities(): StepCapabilityAccess = object : StepCapabilityAccess {
        override fun available(): Set<StepCapability> = setOf(
            UtilitiesYamlContributor.UTILITIES_YAML_CAPABILITY,
        )
        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> get(key: StepCapability): T {
            return when (key) {
                UtilitiesYamlContributor.UTILITIES_YAML_CAPABILITY -> yamlOps() as T
                else -> throw IllegalStateException("unexpected capability $key")
            }
        }
    }

    // ───────── identity ─────────

    @Test
    fun `identity - two YAML PluginStepIds are unique and coordinate matches JSON plugin coordinate`() {
        assertEquals(PluginStepId("utilities.readYaml"), ReadYamlStepDefinition.KEY)
        assertEquals(PluginStepId("utilities.writeYaml"), WriteYamlStepDefinition.KEY)
        assertTrue(ReadYamlStepDefinition.KEY != WriteYamlStepDefinition.KEY)
        assertEquals(UtilitiesYamlContributor.COORDINATE, pipeline.utilities.json.UtilitiesJsonContributor.COORDINATE)
        assertEquals("1.0.0", UtilitiesYamlContributor.PLUGIN_VERSION)
    }

    @Test
    fun `identity - both YAML families coexist in the SAME contributor as JSON families`() {
        val r = registry()
        assertTrue(r.contains(PluginStepId("utilities.readYaml")))
        assertTrue(r.contains(PluginStepId("utilities.writeYaml")))
        assertTrue(r.contains(PluginStepId("utilities.readJSON")))
        assertTrue(r.contains(PluginStepId("utilities.writeJSON")))
        assertTrue(r.contains(PluginStepId("utilities.sha256")))
        // 3 JSON + 2 YAML + 2 properties + 2 filesystem + 3 checksums + 2 archive + 1 example.uppercase = 15 total.
        assertEquals(18, r.keys().size)
    }

    @Test
    fun `identity - duplicate registration of any YAML step fails closed`() {
        val r = registry()
        assertTrue(runCatching { r.register(ReadYamlStepDefinition) }.isFailure)
        assertTrue(runCatching { r.register(WriteYamlStepDefinition) }.isFailure)
    }

    // ───────── contract completeness ─────────

    @Test
    fun `contract completeness - readYaml declares read-only effect and YAML capability only`() {
        val c = ReadYamlStepDefinition.contract
        assertEquals(ReadYamlStepDefinition.KEY, c.key)
        assertEquals("readYaml", c.descriptor.name)
        assertEquals(setOf<Effect>(Effect.READ_ONLY), c.descriptor.effects.toSet())
        assertEquals(ReplayPolicy.MEMOIZED, c.descriptor.replayPolicy)
        assertEquals(
            setOf(UtilitiesYamlContributor.UTILITIES_YAML_CAPABILITY),
            c.requiredCapabilities,
        )
    }

    @Test
    fun `contract completeness - writeYaml declares WRITES_WORKSPACE effect and YAML capability only`() {
        val c = WriteYamlStepDefinition.contract
        assertEquals(WriteYamlStepDefinition.KEY, c.key)
        assertEquals("writeYaml", c.descriptor.name)
        assertEquals(setOf<Effect>(Effect.WRITES_WORKSPACE), c.descriptor.effects.toSet())
        assertEquals(ReplayPolicy.MEMOIZED, c.descriptor.replayPolicy)
        assertEquals(
            setOf(UtilitiesYamlContributor.UTILITIES_YAML_CAPABILITY),
            c.requiredCapabilities,
        )
    }

    @Test
    fun `contract completeness - YAML plugin declares exactly one new capability token`() {
        val src = java.io.File(
            java.io.File(System.getProperty("user.dir"), "../..").canonicalFile,
            "examples/utilities-plugin/src/main/kotlin/pipeline/utilities/yaml/UtilitiesYamlPlugin.kt",
        ).readText()
        val declared = Regex("""StepCapability\(\"([^\"]+)\"""")
            .findAll(src)
            .map { it.groupValues[1] }
            .toSet()
        assertEquals(
            setOf("utilities.yaml.operations"),
            declared,
            "U2 YAML plugin must declare exactly one capability token (utilities.yaml.operations)",
        )
    }

    // ───────── codec round-trips ─────────

    @Test
    fun `codec roundtrip - readYaml input preserves path`() {
        val encoded = ReadYamlCodec.encode(ReadYamlInput("/tmp/x.yaml"))
        val parsed = Json.parseToJsonElement(encoded.value)
        assertTrue(parsed is JsonObject)
        val obj = parsed as JsonObject
        assertEquals("/tmp/x.yaml", (obj["path"] as JsonPrimitive).content)
        assertEquals(ReadYamlInput("/tmp/x.yaml"), ReadYamlCodec.decode(encoded))
    }

    @Test
    fun `codec roundtrip - writeYaml input preserves path and value`() {
        val value = buildJsonObject { put("hello", JsonPrimitive("yaml")) }
        val encoded = WriteYamlCodec.encode(WriteYamlInput("/tmp/y.yaml", value))
        val parsed = Json.parseToJsonElement(encoded.value)
        assertTrue(parsed is JsonObject)
        val obj = parsed as JsonObject
        assertEquals("/tmp/y.yaml", (obj["path"] as JsonPrimitive).content)
        assertEquals(value, WriteYamlCodec.decode(encoded).value)
    }

    // ───────── capability admission ─────────

    @Test
    fun `capability admission - readYaml prepares Ready when YAML capability is available`() {
        val admission = RegistryExecutionPreparation.prepare(
            registry = registry(),
            key = ReadYamlStepDefinition.KEY,
            encodedInput = ReadYamlCodec.encode(ReadYamlInput("/tmp/a.yaml")),
            availableCapabilities = setOf(UtilitiesYamlContributor.UTILITIES_YAML_CAPABILITY),
        )
        assertInstanceOf(ExecutionPreparation.Ready::class.java, admission)
    }

    @Test
    fun `capability admission - writeYaml prepares Ready when YAML capability is available`() {
        val admission = RegistryExecutionPreparation.prepare(
            registry = registry(),
            key = WriteYamlStepDefinition.KEY,
            encodedInput = WriteYamlCodec.encode(
                WriteYamlInput("/tmp/a.yaml", buildJsonObject { put("k", JsonPrimitive("v")) }),
            ),
            availableCapabilities = setOf(UtilitiesYamlContributor.UTILITIES_YAML_CAPABILITY),
        )
        assertInstanceOf(ExecutionPreparation.Ready::class.java, admission)
    }

    @Test
    fun `capability admission - missing YAML capability REJECTS readYaml before handler runs`() {
        val admission = RegistryExecutionPreparation.prepare(
            registry = registry(),
            key = ReadYamlStepDefinition.KEY,
            encodedInput = ReadYamlCodec.encode(ReadYamlInput("/tmp/a.yaml")),
            availableCapabilities = emptySet(),
        )
        assertInstanceOf(ExecutionPreparation.Rejected::class.java, admission)
    }

    // ───────── handler semantics ─────────

    @Test
    fun `handler - readYaml round-trips a real YAML file through the typed handler seam`() = runBlocking {
        val workDir = Files.createTempDirectory("utilities-yaml-read-")
        val inputFile = workDir.resolve("input.yaml")
        Files.writeString(inputFile, "name: pipeline\nn: 42\n")
        val ctx = StepHandlerContext(
            runId = RunId("utilities-yaml-read"),
            stepIndex = 0,
            capabilities = yamlCapabilities(),
        )
        val out = ReadYamlStepDefinition.handler.execute(
            ReadYamlInput(inputFile.toString()),
            ctx,
        )
        assertEquals(inputFile.toString(), out.path)
        assertTrue(out.bytes > 0L)
        val obj = out.value as JsonObject
        assertEquals("pipeline", (obj["name"] as JsonPrimitive).content)
        assertEquals("42", (obj["n"] as JsonPrimitive).content)
    }

    @Test
    fun `handler - writeYaml produces a parseable YAML file`() = runBlocking {
        val workDir = Files.createTempDirectory("utilities-yaml-write-")
        val targetFile = workDir.resolve("out.yaml")
        val value = buildJsonObject {
            put("a", JsonPrimitive("1"))
            put("b", JsonPrimitive(true))
        }
        val ctx = StepHandlerContext(
            runId = RunId("utilities-yaml-write"),
            stepIndex = 0,
            capabilities = yamlCapabilities(),
        )
        val out = WriteYamlStepDefinition.handler.execute(
            WriteYamlInput(targetFile.toString(), value),
            ctx,
        )
        assertEquals(targetFile.toString(), out.path)
        assertTrue(out.bytes > 0L)
        // Round-trip: read the same file with readYaml and check the value matches.
        val roundtripped = ReadYamlStepDefinition.handler.execute(
            ReadYamlInput(targetFile.toString()),
            ctx,
        )
        val obj = roundtripped.value as JsonObject
        assertEquals("1", (obj["a"] as JsonPrimitive).content)
        assertEquals("true", (obj["b"] as JsonPrimitive).content)
    }

    // ───────── typed failure semantics (mirrors U1 JSON pattern) ─────────

    @Test
    fun `typed failure - readYaml of a missing file throws UtilitiesYamlException(YamlNotFound)`() = runBlocking {
        val ctx = StepHandlerContext(
            runId = RunId("utilities-yaml-missing"),
            stepIndex = 0,
            capabilities = yamlCapabilities(),
        )
        val missingPath = "/tmp/utilities-yaml-missing-${'$'}{System.nanoTime()}.yaml"
        val caught = runCatching {
            ReadYamlStepDefinition.handler.execute(ReadYamlInput(missingPath), ctx)
        }.exceptionOrNull()
        assertTrue(
            caught is UtilitiesYamlException,
            "missing file must throw UtilitiesYamlException, was: ${caught?.javaClass?.name}",
        )
        val reason = (caught as UtilitiesYamlException).reason
        assertTrue(
            reason is UtilitiesYamlError.YamlNotFound,
            "missing-file failure must be typed as YamlNotFound, was: ${reason::class.simpleName}",
        )
        assertEquals(missingPath, (reason as UtilitiesYamlError.YamlNotFound).path)
    }

    @Test
    fun `typed failure - readYaml of malformed YAML throws UtilitiesYamlException(YamlParseFailure)`() = runBlocking {
        val workDir = Files.createTempDirectory("utilities-yaml-malformed-")
        val badFile = workDir.resolve("not-yaml.yaml")
        // SnakeYAML's parser will choke on a tab-character indentation mismatch.
        Files.writeString(badFile, "key: : value\n\t- oops\n  - :: : ::\n")
        val ctx = StepHandlerContext(
            runId = RunId("utilities-yaml-malformed"),
            stepIndex = 0,
            capabilities = yamlCapabilities(),
        )
        val caught = runCatching {
            ReadYamlStepDefinition.handler.execute(ReadYamlInput(badFile.toString()), ctx)
        }.exceptionOrNull()
        assertTrue(
            caught is UtilitiesYamlException,
            "malformed YAML must throw UtilitiesYamlException, was: ${caught?.javaClass?.name}",
        )
        val reason = (caught as UtilitiesYamlException).reason
        assertTrue(
            reason is UtilitiesYamlError.YamlParseFailure,
            "malformed-YAML failure must be typed as YamlParseFailure, was: ${reason::class.simpleName}",
        )
        assertEquals(badFile.toString(), (reason as UtilitiesYamlError.YamlParseFailure).path)
    }

    @Test
    fun `typed failure - UtilitiesYamlError sealed ADT is exhaustively matchable (3 cases)`() {
        val cases = listOf(
            UtilitiesYamlError.YamlNotFound("/x"),
            UtilitiesYamlError.YamlParseFailure("/y", "boom"),
            UtilitiesYamlError.YamlIoFailure("/z", "EACCES"),
        )
        val mapped: List<String> = cases.map { reason ->
            when (reason) {
                is UtilitiesYamlError.YamlNotFound -> "notfound:" + reason.path
                is UtilitiesYamlError.YamlParseFailure -> "parse:" + reason.path
                is UtilitiesYamlError.YamlIoFailure -> "io:" + reason.path
            }
        }
        assertEquals(3, mapped.size)
        assertEquals("notfound:/x", mapped[0])
        assertEquals("parse:/y", mapped[1])
        assertEquals("io:/z", mapped[2])
    }

    // ───────── real DSL scenario ─────────

    @Test
    fun `real DSL scenario - readYaml + writeYaml round-trip runs end-to-end through the canonical spine`() = runBlocking {
        val workDir = Files.createTempDirectory("utilities-yaml-dsl-")
        val sourceFile = workDir.resolve("source.yaml")
        Files.writeString(sourceFile, "kind: pipeline\nversion: 1\n")
        val targetFile = workDir.resolve("target.yaml")
        val spec: PipelineSpec = pipeline {
            stages {
                stage("YamlRoundtrip") {
                    registryStep(
                        stepKey = ReadYamlStepDefinition.KEY,
                        encodedInput = ReadYamlCodec.encode(ReadYamlInput(sourceFile.toString())),
                    )
                    registryStep(
                        stepKey = WriteYamlStepDefinition.KEY,
                        encodedInput = WriteYamlCodec.encode(
                            WriteYamlInput(targetFile.toString(), buildJsonObject {
                                put("mirrored", JsonPrimitive("yes"))
                            }),
                        ),
                    )
                }
            }
        }
        val compiled = DslCompiledPipelineCompiler.compile(
            spec = spec,
            sourcePath = "02-yaml-roundtrip.pipeline.kts",
            sourceContent = spec.toString(),
            pluginLockDigest = Digest("lock"),
        )
        val eventStore = InMemoryEventStore()
        val h = harness(eventStore, workDir)
        val run = h.coord.run(compiled, RunId("dsl-yaml-roundtrip"))
        assertEquals(RunOutcome.Success, run)
        assertTrue(Files.exists(targetFile))
    }
}
