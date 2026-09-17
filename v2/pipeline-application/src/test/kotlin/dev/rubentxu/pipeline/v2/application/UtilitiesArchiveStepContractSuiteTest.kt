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
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import pipeline.utilities.archive.ArchiveOperations
import pipeline.utilities.archive.DefaultArchiveOperations
import pipeline.utilities.archive.UnzipCodec
import pipeline.utilities.archive.UnzipInput
import pipeline.utilities.archive.UnzipOutput
import pipeline.utilities.archive.UnzipStepDefinition
import pipeline.utilities.archive.UtilitiesArchiveContributor
import pipeline.utilities.archive.UtilitiesArchiveError
import pipeline.utilities.archive.UtilitiesArchiveException
import pipeline.utilities.archive.ZipCodec
import pipeline.utilities.archive.ZipInput
import pipeline.utilities.archive.ZipOutput
import pipeline.utilities.archive.ZipStepDefinition
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * StepContractSuite for the LFC-2E2-EXPANSION U6 zip/unzip slice.
 *
 * U6 introduces archive operations with mandatory security tests:
 *   - byte-identical round-trip
 *   - nested directories
 *   - empty archive
 *   - overwrite policy
 *   - path traversal (../) — Zip Slip
 *   - absolute-path escape
 *   - malformed archive
 *
 * The unzip handler is fail-closed against Zip Slip and absolute-path entries.
 */
@Timeout(20)
class UtilitiesArchiveStepContractSuiteTest {

    private fun archiveOps(): ArchiveOperations = DefaultArchiveOperations()

    private fun registry(): InMemoryStepRegistry =
        InMemoryStepRegistry().apply { ExternalStepPluginDiscovery.registerInto(this) }

    private fun utilityArchiveCapabilityFactory(
        ops: ArchiveOperations = DefaultArchiveOperations(),
    ): (dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext) -> dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeCapabilityAccess =
        { ctx ->
            object : dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeCapabilityAccess(ctx) {
                private val extra: Map<StepCapability, Any> = mapOf(
                    UtilitiesArchiveContributor.UTILITIES_ARCHIVE_CAPABILITY to ops,
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
        workDir: Path = Files.createTempDirectory("utilities-archive-contract-"),
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
                    CredentialScopeFailure.StoreUnavailable("utilities-archive-contract-suite stub"),
                )
            },
            controlDirRoot = workDir.resolve("control"),
            shOptions = ShOptions.EMPTY,
            stepRegistry = registry(),
            capabilityAccessFactory = utilityArchiveCapabilityFactory(),
        )
        return Harness(coord, eventStore)
    }

    private data class Harness(
        val coord: CanonicalDurableRunCoordinator,
        val eventStore: InMemoryEventStore,
    )

    private fun archiveCapabilities(): StepCapabilityAccess = object : StepCapabilityAccess {
        override fun available(): Set<StepCapability> = setOf(
            UtilitiesArchiveContributor.UTILITIES_ARCHIVE_CAPABILITY,
        )
        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> get(key: StepCapability): T {
            return when (key) {
                UtilitiesArchiveContributor.UTILITIES_ARCHIVE_CAPABILITY -> archiveOps() as T
                else -> throw IllegalStateException("unexpected capability $key")
            }
        }
    }

    private fun defaultCtx() = StepHandlerContext(
        runId = RunId("utilities-archive-test"),
        stepIndex = 0,
        capabilities = archiveCapabilities(),
    )

    // ───────── identity ─────────

    @Test
    fun `identity - two archive PluginStepIds are unique and coordinate matches JSON plugin coordinate`() {
        assertEquals(PluginStepId("utilities.zip"), ZipStepDefinition.KEY)
        assertEquals(PluginStepId("utilities.unzip"), UnzipStepDefinition.KEY)
        assertTrue(ZipStepDefinition.KEY != UnzipStepDefinition.KEY)
        assertEquals(UtilitiesArchiveContributor.COORDINATE, pipeline.utilities.json.UtilitiesJsonContributor.COORDINATE)
    }

    @Test
    fun `identity - archive families coexist in the SAME contributor as JSON+YAML+properties+filesystem+checksums families`() {
        val r = registry()
        assertTrue(r.contains(PluginStepId("utilities.zip")))
        assertTrue(r.contains(PluginStepId("utilities.unzip")))
        assertTrue(r.contains(PluginStepId("utilities.readJSON")))
        assertTrue(r.contains(PluginStepId("utilities.findFiles")))
        assertTrue(r.contains(PluginStepId("utilities.md5")))
        // 14 utilities (3 JSON + 2 YAML + 2 properties + 2 filesystem + 3 checksums + 2 archive) + 1 example.uppercase = 15 total.
        assertEquals(15, r.keys().size)
    }

    // ───────── contract completeness ─────────

    @Test
    fun `contract completeness - zip declares WRITES_WORKSPACE effect and archive capability only`() {
        val c = ZipStepDefinition.contract
        assertEquals(setOf<Effect>(Effect.WRITES_WORKSPACE), c.descriptor.effects.toSet())
        assertEquals(ReplayPolicy.MEMOIZED, c.descriptor.replayPolicy)
        assertEquals(
            setOf(UtilitiesArchiveContributor.UTILITIES_ARCHIVE_CAPABILITY),
            c.requiredCapabilities,
        )
    }

    @Test
    fun `contract completeness - archive plugin declares exactly one new capability token`() {
        val src = java.io.File(
            java.io.File(System.getProperty("user.dir"), "../..").canonicalFile,
            "examples/utilities-plugin/src/main/kotlin/pipeline/utilities/archive/UtilitiesArchivePlugin.kt",
        ).readText()
        val declared = Regex("""StepCapability\(\"([^\"]+)\"""")
            .findAll(src)
            .map { it.groupValues[1] }
            .toSet()
        assertEquals(
            setOf("utilities.archive.operations"),
            declared,
            "U6 archive plugin must declare exactly one capability token (utilities.archive.operations)",
        )
    }

    // ───────── capability admission ─────────

    @Test
    fun `capability admission - zip prepares Ready when archive capability is available`() {
        val admission = RegistryExecutionPreparation.prepare(
            registry = registry(),
            key = ZipStepDefinition.KEY,
            encodedInput = ZipCodec.encode(ZipInput("/tmp/src", "/tmp/a.zip")),
            availableCapabilities = setOf(UtilitiesArchiveContributor.UTILITIES_ARCHIVE_CAPABILITY),
        )
        assertInstanceOf(ExecutionPreparation.Ready::class.java, admission)
    }

    @Test
    fun `capability admission - missing archive capability REJECTS zip before handler runs`() {
        val admission = RegistryExecutionPreparation.prepare(
            registry = registry(),
            key = ZipStepDefinition.KEY,
            encodedInput = ZipCodec.encode(ZipInput("/tmp/src", "/tmp/a.zip")),
            availableCapabilities = emptySet(),
        )
        assertInstanceOf(ExecutionPreparation.Rejected::class.java, admission)
    }

    // ───────── handler semantics ─────────

    @Test
    fun `handler - zip + unzip round-trip produces byte-identical entries for nested directories`() = runBlocking {
        val workDir = Files.createTempDirectory("utilities-archive-roundtrip-")
        val srcDir = workDir.resolve("src")
        val targetZip = workDir.resolve("a.zip")
        val dstDir = workDir.resolve("dst")
        Files.createDirectories(srcDir.resolve("sub/deep"))
        Files.writeString(srcDir.resolve("a.txt"), "alpha")
        Files.writeString(srcDir.resolve("b.txt"), "bravo")
        Files.writeString(srcDir.resolve("sub/c.txt"), "charlie")
        Files.writeString(srcDir.resolve("sub/deep/d.txt"), "delta")

        val zipOut: ZipOutput = ZipStepDefinition.handler.execute(
            ZipInput(srcDir.toString(), targetZip.toString(), overwrite = true),
            defaultCtx(),
        )
        // Files.walk lists 4 leaves + 2 directory pseudo-entries skipped by the
        // zip writer; only files are entries.
        assertEquals(4, zipOut.entryCount)
        assertTrue(zipOut.entries.containsAll(listOf("a.txt", "b.txt", "sub/c.txt", "sub/deep/d.txt")))

        val unzipOut: UnzipOutput = UnzipStepDefinition.handler.execute(
            UnzipInput(targetZip.toString(), dstDir.toString(), overwrite = true),
            defaultCtx(),
        )
        assertEquals(4, unzipOut.entryCount)

        // Byte-identical round-trip.
        assertArrayEquals(
            Files.readAllBytes(srcDir.resolve("a.txt")),
            Files.readAllBytes(dstDir.resolve("a.txt")),
        )
        assertArrayEquals(
            Files.readAllBytes(srcDir.resolve("sub/deep/d.txt")),
            Files.readAllBytes(dstDir.resolve("sub/deep/d.txt")),
        )
    }

    @Test
    fun `handler - zip of an empty directory produces a valid empty archive and unzip into an empty dir yields zero files`() = runBlocking {
        val workDir = Files.createTempDirectory("utilities-archive-empty-")
        val srcDir = workDir.resolve("empty-src")
        val targetZip = workDir.resolve("empty.zip")
        val dstDir = workDir.resolve("empty-dst")
        Files.createDirectories(srcDir)

        val zipOut: ZipOutput = ZipStepDefinition.handler.execute(
            ZipInput(srcDir.toString(), targetZip.toString(), overwrite = true),
            defaultCtx(),
        )
        assertEquals(0, zipOut.entryCount)
        // The file must exist (the writer always closes the stream).
        assertTrue(Files.exists(targetZip))

        val unzipOut: UnzipOutput = UnzipStepDefinition.handler.execute(
            UnzipInput(targetZip.toString(), dstDir.toString(), overwrite = true),
            defaultCtx(),
        )
        assertEquals(0, unzipOut.entryCount)
        assertTrue(Files.exists(dstDir))
        assertTrue(Files.isDirectory(dstDir))
    }

    @Test
    fun `handler - zip overwrite=false rejects when target exists`() = runBlocking {
        val workDir = Files.createTempDirectory("utilities-archive-nooverwrite-")
        val srcDir = workDir.resolve("src")
        val targetZip = workDir.resolve("exists.zip")
        Files.createDirectories(srcDir)
        Files.writeString(srcDir.resolve("a.txt"), "alpha")
        Files.writeString(targetZip, "garbage")
        val caught = runCatching {
            ZipStepDefinition.handler.execute(
                ZipInput(srcDir.toString(), targetZip.toString(), overwrite = false),
                defaultCtx(),
            )
        }.exceptionOrNull()
        assertTrue(caught is UtilitiesArchiveException)
        assertTrue((caught as UtilitiesArchiveException).reason is UtilitiesArchiveError.ArchiveIoFailure)
    }

    @Test
    fun `handler - unzip overwrite=false rejects when an entry already exists`() = runBlocking {
        val workDir = Files.createTempDirectory("utilities-archive-nooverwrite-unzip-")
        val srcDir = workDir.resolve("src")
        val targetZip = workDir.resolve("a.zip")
        val dstDir = workDir.resolve("dst")
        Files.createDirectories(srcDir)
        Files.writeString(srcDir.resolve("a.txt"), "alpha")
        ZipStepDefinition.handler.execute(
            ZipInput(srcDir.toString(), targetZip.toString(), overwrite = true),
            defaultCtx(),
        )
        // Pre-create the destination with a conflicting file.
        Files.createDirectories(dstDir)
        Files.writeString(dstDir.resolve("a.txt"), "preexisting")
        val caught = runCatching {
            UnzipStepDefinition.handler.execute(
                UnzipInput(targetZip.toString(), dstDir.toString(), overwrite = false),
                defaultCtx(),
            )
        }.exceptionOrNull()
        assertTrue(caught is UtilitiesArchiveException)
        assertTrue((caught as UtilitiesArchiveException).reason is UtilitiesArchiveError.ArchiveIoFailure)
    }

    // ───────── security: Zip Slip + absolute path ─────────

    @Test
    fun `security - unzip rejects Zip Slip path traversal (entry with dotdot escapes destination root)`() = runBlocking {
        val workDir = Files.createTempDirectory("utilities-archive-zipslip-")
        val targetZip = workDir.resolve("evil.zip")
        // Build a zip with one entry whose name traverses up.
        Files.newOutputStream(targetZip).use { fos ->
            ZipOutputStream(fos).use { zos ->
                zos.putNextEntry(ZipEntry("../../../etc/evil.txt"))
                zos.write("PWNED".toByteArray())
                zos.closeEntry()
            }
        }
        val dstDir = workDir.resolve("dst")
        val caught = runCatching {
            UnzipStepDefinition.handler.execute(
                UnzipInput(targetZip.toString(), dstDir.toString(), overwrite = true),
                defaultCtx(),
            )
        }.exceptionOrNull()
        assertTrue(
            caught is UtilitiesArchiveException,
            "Zip Slip MUST throw UtilitiesArchiveException, was: ${caught?.javaClass?.name}",
        )
        assertTrue(
            (caught as UtilitiesArchiveException).reason
                is UtilitiesArchiveError.UnzipPathTraversal,
            "expected UnzipPathTraversal, was: ${caught.reason::class.simpleName}",
        )
    }

    @Test
    fun `security - unzip rejects absolute-path entries`() = runBlocking {
        val workDir = Files.createTempDirectory("utilities-archive-abspath-")
        val targetZip = workDir.resolve("evil.zip")
        Files.newOutputStream(targetZip).use { fos ->
            ZipOutputStream(fos).use { zos ->
                zos.putNextEntry(ZipEntry("/tmp/pwned.txt"))
                zos.write("PWNED".toByteArray())
                zos.closeEntry()
            }
        }
        val dstDir = workDir.resolve("dst")
        val caught = runCatching {
            UnzipStepDefinition.handler.execute(
                UnzipInput(targetZip.toString(), dstDir.toString(), overwrite = true),
                defaultCtx(),
            )
        }.exceptionOrNull()
        assertTrue(
            caught is UtilitiesArchiveException,
            "absolute-path entry MUST throw UtilitiesArchiveException, was: ${caught?.javaClass?.name}",
        )
        assertTrue(
            (caught as UtilitiesArchiveException).reason
                is UtilitiesArchiveError.UnzipAbsolutePath,
            "expected UnzipAbsolutePath, was: ${caught.reason::class.simpleName}",
        )
    }

    @Test
    fun `security - unzip rejects a malformed archive with a typed failure (not a silent half-extraction)`() = runBlocking {
        val workDir = Files.createTempDirectory("utilities-archive-malformed-")
        val targetZip = workDir.resolve("malformed.zip")
        Files.writeString(targetZip, "this is not a zip file")
        val dstDir = workDir.resolve("dst")
        val caught = runCatching {
            UnzipStepDefinition.handler.execute(
                UnzipInput(targetZip.toString(), dstDir.toString(), overwrite = true),
                defaultCtx(),
            )
        }.exceptionOrNull()
        assertTrue(
            caught is UtilitiesArchiveException,
            "malformed archive MUST throw UtilitiesArchiveException, was: ${caught?.javaClass?.name}",
        )
        val reason = (caught as UtilitiesArchiveException).reason
        assertTrue(
            reason is UtilitiesArchiveError.ArchiveIoFailure,
            "expected ArchiveIoFailure (malformed archive), was: ${reason::class.simpleName}",
        )
    }

    // ───────── typed failure semantics ─────────

    @Test
    fun `typed failure - UtilitiesArchiveError sealed ADT is exhaustively matchable (4 cases)`() {
        val cases = listOf(
            UtilitiesArchiveError.ArchiveNotFound("/x"),
            UtilitiesArchiveError.ArchiveIoFailure("/y", "EACCES"),
            UtilitiesArchiveError.UnzipPathTraversal("../../etc", "/etc"),
            UtilitiesArchiveError.UnzipAbsolutePath("/etc/passwd"),
        )
        val mapped: List<String> = cases.map { reason ->
            when (reason) {
                is UtilitiesArchiveError.ArchiveNotFound -> "notfound:" + reason.path
                is UtilitiesArchiveError.ArchiveIoFailure -> "io:" + reason.path
                is UtilitiesArchiveError.UnzipPathTraversal -> "traversal:" + reason.entry
                is UtilitiesArchiveError.UnzipAbsolutePath -> "abs:" + reason.entry
            }
        }
        assertEquals(4, mapped.size)
        assertEquals("notfound:/x", mapped[0])
        assertEquals("io:/y", mapped[1])
        assertEquals("traversal:../../etc", mapped[2])
        assertEquals("abs:/etc/passwd", mapped[3])
    }

    // ───────── real DSL scenario ─────────

    @Test
    fun `real DSL scenario - zip + unzip round-trip runs end-to-end through the canonical spine`() = runBlocking {
        val workDir = Files.createTempDirectory("utilities-archive-dsl-")
        val srcDir = workDir.resolve("dsl-src")
        val targetZip = workDir.resolve("dsl.zip")
        val dstDir = workDir.resolve("dsl-dst")
        Files.createDirectories(srcDir)
        Files.writeString(srcDir.resolve("a.txt"), "alpha")
        Files.writeString(srcDir.resolve("b.txt"), "bravo")
        val spec: PipelineSpec = pipeline {
            stages {
                stage("ArchiveRoundtrip") {
                    registryStep(
                        stepKey = ZipStepDefinition.KEY,
                        encodedInput = ZipCodec.encode(
                            ZipInput(srcDir.toString(), targetZip.toString(), overwrite = true),
                        ),
                    )
                    registryStep(
                        stepKey = UnzipStepDefinition.KEY,
                        encodedInput = UnzipCodec.encode(
                            UnzipInput(targetZip.toString(), dstDir.toString(), overwrite = true),
                        ),
                    )
                }
            }
        }
        val compiled = DslCompiledPipelineCompiler.compile(
            spec = spec,
            sourcePath = "06-archive-roundtrip.pipeline.kts",
            sourceContent = spec.toString(),
            pluginLockDigest = dev.rubentxu.pipeline.v2.domain.Digest("lock"),
        )
        val eventStore = InMemoryEventStore()
        val h = harness(eventStore, workDir)
        val run = h.coord.run(compiled, RunId("dsl-archive-roundtrip"))
        assertEquals(RunOutcome.Success, run)
        assertTrue(Files.exists(dstDir.resolve("a.txt")))
        assertTrue(Files.exists(dstDir.resolve("b.txt")))
        assertArrayEquals(
            "alpha".toByteArray(),
            Files.readAllBytes(dstDir.resolve("a.txt")),
        )
    }
}
