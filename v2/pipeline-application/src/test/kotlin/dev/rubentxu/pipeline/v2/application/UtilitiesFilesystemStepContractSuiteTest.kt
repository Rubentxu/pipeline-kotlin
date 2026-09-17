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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import pipeline.utilities.filesystem.DefaultUtilitiesFilesystemOperations
import pipeline.utilities.filesystem.FindFilesCodec
import pipeline.utilities.filesystem.FindFilesInput
import pipeline.utilities.filesystem.FindFilesOutput
import pipeline.utilities.filesystem.FindFilesStepDefinition
import pipeline.utilities.filesystem.TouchCodec
import pipeline.utilities.filesystem.TouchInput
import pipeline.utilities.filesystem.TouchStepDefinition
import pipeline.utilities.filesystem.UtilitiesFilesystemContributor
import pipeline.utilities.filesystem.UtilitiesFilesystemError
import pipeline.utilities.filesystem.UtilitiesFilesystemException
import pipeline.utilities.filesystem.UtilitiesFilesystemOperations
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

/**
 * StepContractSuite for the LFC-2E2-EXPANSION U4 filesystem slice.
 *
 * U4 introduces the FIRST non-codec-shaped Step families in the
 * OFFICIAL_PLUGIN family:
 *   - findFiles returns a LIST-shaped output (paths matching a glob).
 *   - touch creates/updates a file's last-modified timestamp.
 *
 * The architectural claim for U4 is that the plugin model scales to
 * non-codec shapes (LIST, timestamp) without ANY production core change.
 */
@Timeout(20)
class UtilitiesFilesystemStepContractSuiteTest {

    private fun fsOps(): UtilitiesFilesystemOperations = DefaultUtilitiesFilesystemOperations()

    private fun registry(): InMemoryStepRegistry =
        InMemoryStepRegistry().apply { ExternalStepPluginDiscovery.registerInto(this) }

    private fun utilityFilesystemCapabilityFactory(
        ops: UtilitiesFilesystemOperations = DefaultUtilitiesFilesystemOperations(),
    ): (dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext) -> dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeCapabilityAccess =
        { ctx ->
            object : dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeCapabilityAccess(ctx) {
                private val extra: Map<StepCapability, Any> = mapOf(
                    UtilitiesFilesystemContributor.UTILITIES_FILESYSTEM_CAPABILITY to ops,
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
        workDir: Path = Files.createTempDirectory("utilities-fs-contract-"),
    ): Harness {
        val clock = SystemClock()
        val coord = CanonicalDurableRunCoordinator(
            dispatcher = CanonicalNodeDispatcher(),
            journal = InMemoryOperationJournal(clock),
            cursorStore = InMemoryReplayCursorStore(clock),
            clock = clock,
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            eventSink = eventStore,
            credentialScopePort = { _, _ ->
                CredentialScopeOutcome.Unavailable(
                    CredentialScopeFailure.StoreUnavailable("utilities-fs-contract-suite stub"),
                )
            },
            controlDirRoot = workDir.resolve("control"),
            shOptions = ShOptions.EMPTY,
            stepRegistry = registry(),
            capabilityAccessFactory = utilityFilesystemCapabilityFactory(),
        )
        return Harness(coord, eventStore)
    }

    private data class Harness(
        val coord: CanonicalDurableRunCoordinator,
        val eventStore: InMemoryEventStore,
    )

    private fun fsCapabilities(): StepCapabilityAccess = object : StepCapabilityAccess {
        override fun available(): Set<StepCapability> = setOf(
            UtilitiesFilesystemContributor.UTILITIES_FILESYSTEM_CAPABILITY,
        )
        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> get(key: StepCapability): T {
            return when (key) {
                UtilitiesFilesystemContributor.UTILITIES_FILESYSTEM_CAPABILITY -> fsOps() as T
                else -> throw IllegalStateException("unexpected capability $key")
            }
        }
    }

    // ───────── identity ─────────

    @Test
    fun `identity - two filesystem PluginStepIds are unique and coordinate matches JSON plugin coordinate`() {
        assertEquals(PluginStepId("utilities.findFiles"), FindFilesStepDefinition.KEY)
        assertEquals(PluginStepId("utilities.touch"), TouchStepDefinition.KEY)
        assertTrue(FindFilesStepDefinition.KEY != TouchStepDefinition.KEY)
        assertEquals(UtilitiesFilesystemContributor.COORDINATE, pipeline.utilities.json.UtilitiesJsonContributor.COORDINATE)
    }

    @Test
    fun `identity - filesystem families coexist in the SAME contributor as JSON+YAML+properties families`() {
        val r = registry()
        assertTrue(r.contains(PluginStepId("utilities.findFiles")))
        assertTrue(r.contains(PluginStepId("utilities.touch")))
        assertTrue(r.contains(PluginStepId("utilities.readJSON")))
        assertTrue(r.contains(PluginStepId("utilities.readYaml")))
        assertTrue(r.contains(PluginStepId("utilities.readProperties")))
        // 3 JSON + 2 YAML + 2 properties + 2 filesystem + 3 checksums + 2 archive + 1 example.uppercase = 15 total.
        assertEquals(18, r.keys().size)
    }

    // ───────── contract completeness ─────────

    @Test
    fun `contract completeness - findFiles declares read-only effect and filesystem capability only`() {
        val c = FindFilesStepDefinition.contract
        assertEquals(setOf<Effect>(Effect.READ_ONLY), c.descriptor.effects.toSet())
        assertEquals(ReplayPolicy.MEMOIZED, c.descriptor.replayPolicy)
        assertEquals(
            setOf(UtilitiesFilesystemContributor.UTILITIES_FILESYSTEM_CAPABILITY),
            c.requiredCapabilities,
        )
    }

    @Test
    fun `contract completeness - touch declares WRITES_WORKSPACE effect and filesystem capability only`() {
        val c = TouchStepDefinition.contract
        assertEquals(setOf<Effect>(Effect.WRITES_WORKSPACE), c.descriptor.effects.toSet())
        assertEquals(ReplayPolicy.MEMOIZED, c.descriptor.replayPolicy)
        assertEquals(
            setOf(UtilitiesFilesystemContributor.UTILITIES_FILESYSTEM_CAPABILITY),
            c.requiredCapabilities,
        )
    }

    @Test
    fun `contract completeness - filesystem plugin declares exactly one new capability token`() {
        val src = java.io.File(
            java.io.File(System.getProperty("user.dir"), "../..").canonicalFile,
            "examples/utilities-plugin/src/main/kotlin/pipeline/utilities/filesystem/UtilitiesFilesystemPlugin.kt",
        ).readText()
        val declared = Regex("""StepCapability\(\"([^\"]+)\"""")
            .findAll(src)
            .map { it.groupValues[1] }
            .toSet()
        assertEquals(
            setOf("utilities.filesystem.operations"),
            declared,
            "U4 filesystem plugin must declare exactly one capability token (utilities.filesystem.operations)",
        )
    }

    // ───────── capability admission ─────────

    @Test
    fun `capability admission - findFiles prepares Ready when filesystem capability is available`() {
        val admission = RegistryExecutionPreparation.prepare(
            registry = registry(),
            key = FindFilesStepDefinition.KEY,
            encodedInput = FindFilesCodec.encode(FindFilesInput("/tmp", "*.kt")),
            availableCapabilities = setOf(UtilitiesFilesystemContributor.UTILITIES_FILESYSTEM_CAPABILITY),
        )
        assertInstanceOf(ExecutionPreparation.Ready::class.java, admission)
    }

    @Test
    fun `capability admission - missing filesystem capability REJECTS findFiles before handler runs`() {
        val admission = RegistryExecutionPreparation.prepare(
            registry = registry(),
            key = FindFilesStepDefinition.KEY,
            encodedInput = FindFilesCodec.encode(FindFilesInput("/tmp", "*.kt")),
            availableCapabilities = emptySet(),
        )
        assertInstanceOf(ExecutionPreparation.Rejected::class.java, admission)
    }

    // ───────── handler semantics ─────────

    @Test
    fun `handler - findFiles walks a directory tree and returns matching paths`() = runBlocking {
        val workDir = Files.createTempDirectory("utilities-fs-find-")
        Files.writeString(workDir.resolve("a.kt"), "")
        Files.writeString(workDir.resolve("b.kt"), "")
        Files.writeString(workDir.resolve("c.txt"), "")
        Files.createDirectory(workDir.resolve("sub"))
        Files.writeString(workDir.resolve("sub/d.kt"), "")
        val ctx = StepHandlerContext(
            runId = RunId("utilities-fs-find"),
            stepIndex = 0,
            capabilities = fsCapabilities(),
        )
        val out: FindFilesOutput = FindFilesStepDefinition.handler.execute(
            FindFilesInput(workDir.toString(), "**/*.kt", maxDepth = 5),
            ctx,
        )
        assertTrue(
            out.matchCount >= 3,
            "should match at least 3 .kt files (a, b, sub/d), was: ${out.matchCount}, matches: ${out.matches}",
        )
        assertTrue(out.matches.all { it.endsWith(".kt") })
    }

    @Test
    fun `handler - touch creates a new file with the requested lastModifiedMillis`() = runBlocking {
        val workDir = Files.createTempDirectory("utilities-fs-touch-")
        val target = workDir.resolve("marker.txt")
        val ts = 1_700_000_000_000L
        val ctx = StepHandlerContext(
            runId = RunId("utilities-fs-touch-create"),
            stepIndex = 0,
            capabilities = fsCapabilities(),
        )
        val out = TouchStepDefinition.handler.execute(
            TouchInput(target.toString(), ts, createDirs = true),
            ctx,
        )
        assertTrue(out.created)
        assertEquals(ts, out.lastModifiedMillis)
        assertTrue(Files.exists(target))
        assertEquals(FileTime.fromMillis(ts), Files.getLastModifiedTime(target))
    }

    // ───────── typed failure semantics ─────���───

    @Test
    fun `typed failure - findFiles with a missing root throws UtilitiesFilesystemException(FilesystemNotFound)`() = runBlocking {
        val ctx = StepHandlerContext(
            runId = RunId("utilities-fs-missing-root"),
            stepIndex = 0,
            capabilities = fsCapabilities(),
        )
        val missingRoot = "/tmp/utilities-fs-missing-${'$'}{System.nanoTime()}"
        val caught = runCatching {
            FindFilesStepDefinition.handler.execute(FindFilesInput(missingRoot, "*.kt"), ctx)
        }.exceptionOrNull()
        assertTrue(
            caught is UtilitiesFilesystemException,
            "missing root must throw UtilitiesFilesystemException, was: ${caught?.javaClass?.name}",
        )
        assertTrue(
            (caught as UtilitiesFilesystemException).reason
                is UtilitiesFilesystemError.FilesystemNotFound,
        )
    }

    @Test
    fun `typed failure - UtilitiesFilesystemError sealed ADT is exhaustively matchable (3 cases)`() {
        val cases = listOf(
            UtilitiesFilesystemError.FilesystemNotFound("/x"),
            UtilitiesFilesystemError.FilesystemInvalidGlob("[", "unterminated character class"),
            UtilitiesFilesystemError.FilesystemIoFailure("/y", "EACCES"),
        )
        val mapped: List<String> = cases.map { reason ->
            when (reason) {
                is UtilitiesFilesystemError.FilesystemNotFound -> "notfound:" + reason.path
                is UtilitiesFilesystemError.FilesystemInvalidGlob -> "glob:" + reason.pattern
                is UtilitiesFilesystemError.FilesystemIoFailure -> "io:" + reason.path
            }
        }
        assertEquals(3, mapped.size)
        assertEquals("notfound:/x", mapped[0])
        assertEquals("glob:[", mapped[1])
        assertEquals("io:/y", mapped[2])
    }

    // ───────── real DSL scenario ─────────

    @Test
    fun `real DSL scenario - findFiles + touch round-trip runs end-to-end through the canonical spine`() = runBlocking {
        val workDir = Files.createTempDirectory("utilities-fs-dsl-")
        Files.writeString(workDir.resolve("existing.txt"), "")
        val spec: PipelineSpec = pipeline {
            stages {
                stage("FilesystemOps") {
                    registryStep(
                        stepKey = TouchStepDefinition.KEY,
                        encodedInput = TouchCodec.encode(
                            TouchInput(workDir.resolve("new.txt").toString(), 1_700_000_000_000L, createDirs = true),
                        ),
                    )
                    registryStep(
                        stepKey = FindFilesStepDefinition.KEY,
                        encodedInput = FindFilesCodec.encode(
                            FindFilesInput(workDir.toString(), "*.txt"),
                        ),
                    )
                }
            }
        }
        val compiled = DslCompiledPipelineCompiler.compile(
            spec = spec,
            sourcePath = "04-filesystem-roundtrip.pipeline.kts",
            sourceContent = spec.toString(),
            pluginLockDigest = dev.rubentxu.pipeline.v2.domain.Digest("lock"),
        )
        val eventStore = InMemoryEventStore()
        val h = harness(eventStore, workDir)
        val run = h.coord.run(compiled, RunId("dsl-filesystem-roundtrip"))
        assertEquals(RunOutcome.Success, run)
        assertTrue(Files.exists(workDir.resolve("new.txt")))
    }
}
