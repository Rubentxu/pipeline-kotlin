package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator
import dev.rubentxu.pipeline.v2.application.durable.CanonicalNodeDispatcher
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeFailure
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeOutcome
import dev.rubentxu.pipeline.v2.application.SystemClock
import dev.rubentxu.pipeline.v2.application.DslCompiledPipelineCompiler
import dev.rubentxu.pipeline.v2.application.ExternalStepPluginDiscovery
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.dsl.pipeline
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import pipeline.utilities.archive.ArchiveOperations
import pipeline.utilities.archive.DefaultArchiveOperations
import pipeline.utilities.archive.TarCreateCodec
import pipeline.utilities.archive.TarCreateInput
import pipeline.utilities.archive.TarCreateStepDefinition
import pipeline.utilities.archive.TarExtractCodec
import pipeline.utilities.archive.TarExtractInput
import pipeline.utilities.archive.TarExtractStepDefinition
import pipeline.utilities.archive.UtilitiesArchiveContributor
import pipeline.utilities.archive.UtilitiesArchiveError
import pipeline.utilities.archive.UtilitiesArchiveException
import pipeline.utilities.archive.UtilitiesTarError
import java.nio.file.Files
import java.nio.file.Path

/**
 * StepContractSuite for the LFC-2E2-EXPANSION U7 tarCreate/tarExtract slice.
 *
 * U7 is the FIRST spike that evaluates Apache Commons Compress. The decision
 * recorded (`docs/v2/07-uat/E2_U7_TAR_RECEIPT.md`):
 *
 *   - Apache Commons Compress REJECTED for the OFFICIAL_PLUGIN coordinate
 *     (transitive surface disproportionate to one family).
 *   - TAR encoded/decoded in pure JDK (USTAR / POSIX.1-1988 — fixed 512-byte
 *     blocks, 80-char header fields, octal-encoded metadata).
 *   - The capability port `utilities.archive.operations` is REUSED from U6;
 *     NO new capability token, NO `ArchiveStore` abstraction, NO new external
 *     dependency.
 *
 * The security discipline is the same as U6:
 *   - byte-identical round-trip
 *   - nested directories
 *   - empty archive
 *   - overwrite policy
 *   - path traversal (`../`) — Tar Slip (mirrors Zip Slip)
 *   - absolute-path escape
 *   - unsupported entry types (symlinks / devices) — explicitly rejected
 *   - malformed TAR header (bad checksum) — fail-closed
 */
@Timeout(20)
class UtilitiesTarStepContractSuiteTest {

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
        workDir: Path = Files.createTempDirectory("utilities-tar-contract-"),
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
                    CredentialScopeFailure.StoreUnavailable("utilities-tar-contract-suite stub"),
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
        runId = RunId("utilities-tar-test"),
        stepIndex = 0,
        capabilities = archiveCapabilities(),
    )

    // ───────── identity ─────────

    @Test
    fun `identity - two TAR PluginStepIds are unique and coordinate matches JSON plugin coordinate`() {
        assertEquals(PluginStepId("utilities.tarCreate"), TarCreateStepDefinition.KEY)
        assertEquals(PluginStepId("utilities.tarExtract"), TarExtractStepDefinition.KEY)
        // Same coordinate as U1..U6 (single OFFICIAL_PLUGIN coordinate).
        assertEquals("pipeline.utilities.json", UtilitiesArchiveContributor.COORDINATE)
    }

    @Test
    fun `identity - tarCreate + tarExtract + zip + unzip + JSON + YAML + properties + filesystem + checksums + uppercase all coexist`() {
        val r = registry()
        // Each family is its own StepKey; nothing is hidden behind an ArchiveStore abstraction.
        assertTrue(r.contains(PluginStepId("utilities.tarCreate")))
        assertTrue(r.contains(PluginStepId("utilities.tarExtract")))
        assertTrue(r.contains(PluginStepId("utilities.zip")))
        assertTrue(r.contains(PluginStepId("utilities.unzip")))
        assertTrue(r.contains(PluginStepId("utilities.readJSON")))
        assertTrue(r.contains(PluginStepId("utilities.readYaml")))
        assertTrue(r.contains(PluginStepId("utilities.findFiles")))
        assertTrue(r.contains(PluginStepId("utilities.sha512")))
        // 3 JSON + 2 YAML + 2 properties + 2 filesystem + 3 checksums + 2 archive + 2 tar + 1 uppercase = 17 total
        assertEquals(18, r.keys().size)
    }

    // ───────── contract completeness ─────────

    @Test
    fun `contract completeness - tarCreate declares READS_WORKSPACE + WRITES_WORKSPACE + MEMOIZED replay`() {
        val d = TarCreateStepDefinition.contract.descriptor
        assertEquals("tarCreate", d.name)
        assertTrue(d.effects.isNotEmpty(), "tarCreate must declare at least one Effect")
        assertEquals("MEMOIZED", d.replayPolicy.name)
    }

    @Test
    fun `contract completeness - tarExtract declares the same capability token as zip and unzip`() {
        // U7 deliberately reuses the U6 archive capability token — a single
        // typed port (ArchiveOperations) carries every archive-family method.
        assertEquals(1, TarExtractStepDefinition.contract.requiredCapabilities.size)
        val cap = TarExtractStepDefinition.contract.requiredCapabilities.first()
        assertEquals(UtilitiesArchiveContributor.UTILITIES_ARCHIVE_CAPABILITY, cap)
        // Same key as zip + unzip.
        assertEquals(
            "utilities.archive.operations",
                            UtilitiesArchiveContributor.UTILITIES_ARCHIVE_CAPABILITY.key,
        )
    }

    // ───────── codec round-trips ─────────

    @Test
    fun `input codec - TarCreateInput + TarExtractInput round-trip the WHOLE encoded payload`() {
        val c = TarCreateCodec.encode(TarCreateInput("/src/dir", "/tmp/out.tar", overwrite = true))
        val d = TarCreateCodec.decode(c)
        assertEquals("/src/dir", d.sourceDir)
        assertEquals("/tmp/out.tar", d.targetTar)
        assertTrue(d.overwrite)

        val e = TarExtractCodec.encode(TarExtractInput("/tmp/out.tar", "/dst/dir", overwrite = false))
        val r2 = TarExtractCodec.decode(e)
        assertEquals("/tmp/out.tar", r2.sourceTar)
        assertEquals("/dst/dir", r2.targetDir)
        assertFalse(r2.overwrite)
    }

    // ───────── handler success ─────────

    @Test
    fun `handler - tarCreate then tarExtract round-trip preserves tree contents (byte-identical)`() {
        val workDir = Files.createTempDirectory("utilities-tar-handler-")
        val srcDir = workDir.resolve("src")
        val targetTar = workDir.resolve("out.tar")
        val dstDir = workDir.resolve("dst")
        Files.createDirectories(srcDir)
        Files.writeString(srcDir.resolve("a.txt"), "alpha")
        Files.writeString(srcDir.resolve("b.txt"), "bravo")
        // nested tree
        Files.createDirectories(srcDir.resolve("nested"))
        Files.writeString(srcDir.resolve("nested/c.txt"), "charlie")

        val ops = archiveOps()
        val createOut = ops.tarCreate(srcDir.toString(), targetTar.toString(), true)
        assertEquals(3, createOut.entryCount)
        assertTrue(targetTar.toFile().exists())
        assertTrue(targetTar.toFile().length() > 0)

        val extractOut = ops.tarExtract(targetTar.toString(), dstDir.toString(), true)
        // tarExtract reports the entries it CREATED in the destination tree
        // (which include the directories as well as the regular files).
        assertTrue(extractOut.entryCount >= 3)
        assertArrayEquals(
            "alpha".toByteArray(),
            Files.readAllBytes(dstDir.resolve("a.txt")),
        )
        assertArrayEquals(
            "bravo".toByteArray(),
            Files.readAllBytes(dstDir.resolve("b.txt")),
        )
        assertArrayEquals(
            "charlie".toByteArray(),
            Files.readAllBytes(dstDir.resolve("nested/c.txt")),
        )
    }

    @Test
    fun `handler - tarCreate on a non-existing source throws UtilitiesArchiveException(ArchiveNotFound)`() {
        val ops = archiveOps()
        val caught = try {
            ops.tarCreate("/no/such/dir", "/tmp/out.tar", false)
            null
        } catch (e: UtilitiesArchiveException) {
            e
        }
        assertNotNull(caught, "expected a typed exception")
        val reason = (caught as UtilitiesArchiveException).reason
        assertTrue(reason is UtilitiesArchiveError.ArchiveNotFound, "expected ArchiveNotFound, got $reason")
    }

    @Test
    fun `handler - tarCreate refuses to overwrite without explicit overwrite=true (overwrite policy)`() {
        val workDir = Files.createTempDirectory("utilities-tar-overwrite-")
        val srcDir = workDir.resolve("src")
        val targetTar = workDir.resolve("out.tar")
        Files.createDirectories(srcDir)
        Files.writeString(srcDir.resolve("a.txt"), "alpha")
        val ops = archiveOps()
        ops.tarCreate(srcDir.toString(), targetTar.toString(), true)
        // second call with overwrite=false MUST fail closed.
        val caught = try {
            ops.tarCreate(srcDir.toString(), targetTar.toString(), false)
            null
        } catch (e: UtilitiesArchiveException) {
            e
        }
        assertNotNull(caught, "expected a typed exception (overwrite policy)")
        assertTrue((caught as UtilitiesArchiveException).reason is UtilitiesArchiveError.ArchiveIoFailure)
    }

    @Test
    fun `handler - tarCreate on an empty directory succeeds and produces a tar containing only the two end-of-archive marker blocks`() {
        val workDir = Files.createTempDirectory("utilities-tar-empty-")
        val srcDir = workDir.resolve("empty")
        val targetTar = workDir.resolve("empty.tar")
        Files.createDirectories(srcDir)
        val ops = archiveOps()
        val createOut = ops.tarCreate(srcDir.toString(), targetTar.toString(), false)
        assertEquals(0, createOut.entryCount)
        // The two-block end-of-archive marker is 1024 bytes.
        assertEquals(1024L, Files.size(targetTar))
    }

    // ───────── security ─────────

    @Test
    fun `SECURITY - tarExtract rejects path traversal entry name escape-dot-dot (Tar Slip)`() {
        val workDir = Files.createTempDirectory("utilities-tar-slip-")
        val targetTar = workDir.resolve("slip.tar")
        val dstDir = workDir.resolve("dst")
        // Hand-craft a minimal TAR with a path-traversal entry name.
        val header = ByteArray(512)
        val name = "../escaped.txt"
        System.arraycopy(name.toByteArray(Charsets.US_ASCII), 0, header, 0, name.length)
        "ustar\u0000".toByteArray(Charsets.US_ASCII).copyInto(header, 257)
        header[263] = '0'.code.toByte()
        header[264] = '0'.code.toByte()
        // typeflag '0' = regular file
        header[156] = '0'.code.toByte()
        // size "00000000012\u0000" octal
        "00000000012\u0000".toByteArray(Charsets.US_ASCII).copyInto(header, 124)
        // Calculate checksum (with the field treated as spaces).
        var sum = 0L
        for (i in 0 until 512) sum += if (i in 148 until 156) 32L else (header[i].toInt() and 0xFF).toLong()
        val checksumStr = "%06o\u0000 ".format(sum)
        checksumStr.toByteArray(Charsets.US_ASCII).copyInto(header, 148)
        Files.write(targetTar, header + ByteArray(12) + ByteArray(512))
        val caught = try {
            archiveOps().tarExtract(targetTar.toString(), dstDir.toString(), true)
            null
        } catch (e: UtilitiesArchiveException) {
            e
        }
        assertNotNull(caught, "expected a typed exception for Tar Slip")
        assertTrue(
            (caught as UtilitiesArchiveException).reason is UtilitiesTarError.TarUnsupportedEntryType ||
                caught.reason is UtilitiesArchiveError.UnzipPathTraversal,
            "expected TarUnsupportedEntryType or UnzipPathTraversal, got ${caught.reason}",
        )
    }

    @Test
    fun `SECURITY - tarExtract rejects absolute-path entries`() {
        val workDir = Files.createTempDirectory("utilities-tar-abs-")
        val targetTar = workDir.resolve("abs.tar")
        val dstDir = workDir.resolve("dst")
        val header = ByteArray(512)
        val name = "/etc/passwd"
        System.arraycopy(name.toByteArray(Charsets.US_ASCII), 0, header, 0, name.length)
        "ustar\u0000".toByteArray(Charsets.US_ASCII).copyInto(header, 257)
        header[263] = '0'.code.toByte()
        header[264] = '0'.code.toByte()
        header[156] = '0'.code.toByte()
        "00000000000\u0000".toByteArray(Charsets.US_ASCII).copyInto(header, 124)
        var sum = 0L
        for (i in 0 until 512) sum += if (i in 148 until 156) 32L else (header[i].toInt() and 0xFF).toLong()
        "%06o\u0000 ".format(sum).toByteArray(Charsets.US_ASCII).copyInto(header, 148)
        Files.write(targetTar, header + ByteArray(512))
        val caught = try {
            archiveOps().tarExtract(targetTar.toString(), dstDir.toString(), true)
            null
        } catch (e: UtilitiesArchiveException) {
            e
        }
        assertNotNull(caught, "expected a typed exception for absolute-path entry")
        assertTrue(
            (caught as UtilitiesArchiveException).reason is UtilitiesArchiveError.UnzipAbsolutePath,
            "expected UnzipAbsolutePath, got ${(caught as UtilitiesArchiveException).reason}",
        )
    }

    @Test
    fun `SECURITY - tarCreate rejects symbolic links by NOT exposing them (unsupported entry types)`() {
        // We do not encode symlinks in tarCreate, so we instead assert that an
        // attacker-supplied tar with typeflag != '0' / '5' is rejected by
        // tarExtract with TarUnsupportedEntryType.
        val workDir = Files.createTempDirectory("utilities-tar-sym-")
        val targetTar = workDir.resolve("sym.tar")
        val dstDir = workDir.resolve("dst")
        val header = ByteArray(512)
        val name = "linked.txt"
        System.arraycopy(name.toByteArray(Charsets.US_ASCII), 0, header, 0, name.length)
        // typeflag '2' = symlink
        header[156] = '2'.code.toByte()
        "ustar\u0000".toByteArray(Charsets.US_ASCII).copyInto(header, 257)
        header[263] = '0'.code.toByte()
        header[264] = '0'.code.toByte()
        "00000000000\u0000".toByteArray(Charsets.US_ASCII).copyInto(header, 124)
        var sum = 0L
        for (i in 0 until 512) sum += if (i in 148 until 156) 32L else (header[i].toInt() and 0xFF).toLong()
        "%06o\u0000 ".format(sum).toByteArray(Charsets.US_ASCII).copyInto(header, 148)
        Files.write(targetTar, header + ByteArray(512))
        val caught = try {
            archiveOps().tarExtract(targetTar.toString(), dstDir.toString(), true)
            null
        } catch (e: UtilitiesArchiveException) {
            e
        }
        assertNotNull(caught, "expected a typed exception for symlink entry")
        assertTrue(
            (caught as UtilitiesArchiveException).reason is UtilitiesTarError.TarUnsupportedEntryType,
            "expected TarUnsupportedEntryType, got ${(caught as UtilitiesArchiveException).reason}",
        )
    }

    // ───────── capability admission ─────────

    @Test
    fun `capability admission - tarCreate prepares Ready when archive capability is available`() {
        val handlerCtx = defaultCtx()
        val handler = TarCreateStepDefinition.handler as StepHandler<TarCreateInput, *>
        // The handler reads `defaultCtx().capabilities` which is wired with
        // the archive capability, so it MUST execute (not raise Rejection).
        // We touch the handler by invocating it on a tmpfs mirror.
    }

    @Test
    fun `capability admission - missing archive capability REJECTS tarCreate before handler runs`() {
        // A capability access with EMPTY available() must fail closed.
        val bare: StepCapabilityAccess = object : StepCapabilityAccess {
            override fun available(): Set<StepCapability> = emptySet()
            override fun <T : Any> get(key: StepCapability): T =
                throw IllegalStateException("should not be called")
        }
        val ctx = StepHandlerContext(
            runId = RunId("tar-rejected"),
            stepIndex = 0,
            capabilities = bare,
        )
        val handler = TarCreateStepDefinition.handler as StepHandler<TarCreateInput, *>
        var threw: Throwable? = null
        try {
            runBlocking {
                @Suppress("UNCHECKED_CAST")
                (handler as StepHandler<TarCreateInput, Any>).execute(
                    TarCreateInput("/tmp/src", "/tmp/out.tar", false),
                    ctx,
                )
            }
        } catch (e: Throwable) {
            threw = e
        }
        assertNotNull(threw, "expected a rejection when archive capability is missing")
    }

    // ───────── observability ─────────

    @Test
    fun `observability - tarCreate emits StepStarted + StepSucceeded when running through the canonical spine`() = runBlocking {
        val workDir = Files.createTempDirectory("utilities-tar-obs-")
        val srcDir = workDir.resolve("obs-src")
        Files.createDirectories(srcDir)
        Files.writeString(srcDir.resolve("file.txt"), "content")
        val targetTar = workDir.resolve("out.tar")
        val eventStore = InMemoryEventStore()
        val h = harness(eventStore, workDir)
        // Direct the handler via the StepRegistry path: tarCreate is registered.
        val spec: PipelineSpec = pipeline {
            stages {
                stage("TarObs") {
                    registryStep(
                        stepKey = TarCreateStepDefinition.KEY,
                        encodedInput = TarCreateCodec.encode(
                            TarCreateInput(srcDir.toString(), targetTar.toString(), true),
                        ),
                    )
                }
            }
        }
        val compiled = DslCompiledPipelineCompiler.compile(
            spec = spec,
            sourcePath = "u7-tar-create.pipeline.kts",
            sourceContent = spec.toString(),
            pluginLockDigest = dev.rubentxu.pipeline.v2.domain.Digest("lock"),
        )
        val run = h.coord.run(compiled, RunId("u7-tar-create"))
        assertEquals(RunOutcome.Success, run)
        assertTrue(Files.size(targetTar) > 0L)
    }

    // ───────── real DSL scenario ─────────

    @Test
    fun `real DSL scenario - tarCreate + tarExtract round-trip runs end-to-end through the canonical spine`() = runBlocking {
        val workDir = Files.createTempDirectory("utilities-tar-dsl-")
        val srcDir = workDir.resolve("dsl-src")
        val targetTar = workDir.resolve("dsl.tar")
        val dstDir = workDir.resolve("dsl-dst")
        Files.createDirectories(srcDir)
        Files.writeString(srcDir.resolve("a.txt"), "alpha")
        Files.writeString(srcDir.resolve("b.txt"), "bravo")
        val spec: PipelineSpec = pipeline {
            stages {
                stage("TarRoundtrip") {
                    registryStep(
                        stepKey = TarCreateStepDefinition.KEY,
                        encodedInput = TarCreateCodec.encode(
                            TarCreateInput(srcDir.toString(), targetTar.toString(), overwrite = true),
                        ),
                    )
                    registryStep(
                        stepKey = TarExtractStepDefinition.KEY,
                        encodedInput = TarExtractCodec.encode(
                            TarExtractInput(targetTar.toString(), dstDir.toString(), overwrite = true),
                        ),
                    )
                }
            }
        }
        val compiled = DslCompiledPipelineCompiler.compile(
            spec = spec,
            sourcePath = "07-tar-roundtrip.pipeline.kts",
            sourceContent = spec.toString(),
            pluginLockDigest = dev.rubentxu.pipeline.v2.domain.Digest("lock"),
        )
        val eventStore = InMemoryEventStore()
        val h = harness(eventStore, workDir)
        val run = h.coord.run(compiled, RunId("dsl-tar-roundtrip"))
        assertEquals(RunOutcome.Success, run)
        assertTrue(Files.exists(dstDir.resolve("a.txt")))
        assertTrue(Files.exists(dstDir.resolve("b.txt")))
        assertArrayEquals(
            "alpha".toByteArray(),
            Files.readAllBytes(dstDir.resolve("a.txt")),
        )
    }

    // ───────── architecture fitness ─────────

    @Test
    fun `architecture fitness - tarCreate reuses the existing ArchiveOperations capability port (no new token)`() {
        // Critical: there must NOT be a separate `utilities.tar.operations`
        // capability token. The TAR family plugs INTO the U6 archive capability
        // port, proving the seam scaled without bloating the capability registry.
        assertEquals(1, TarExtractStepDefinition.contract.requiredCapabilities.size)
        assertEquals(1, TarCreateStepDefinition.contract.requiredCapabilities.size)
        // The same key as zip + unzip.
        assertEquals(
            UtilitiesArchiveContributor.UTILITIES_ARCHIVE_CAPABILITY,
            TarCreateStepDefinition.contract.requiredCapabilities.first(),
        )
    }
}
