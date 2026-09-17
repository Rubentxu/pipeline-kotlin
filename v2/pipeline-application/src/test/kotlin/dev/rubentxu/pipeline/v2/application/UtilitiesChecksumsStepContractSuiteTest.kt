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
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import pipeline.utilities.checksums.ChecksumOperations
import pipeline.utilities.checksums.ChecksumOutput
import pipeline.utilities.checksums.DefaultChecksumOperations
import pipeline.utilities.checksums.HashAlgorithm
import pipeline.utilities.checksums.Md5Codec
import pipeline.utilities.checksums.Md5Input
import pipeline.utilities.checksums.Md5StepDefinition
import pipeline.utilities.checksums.Sha1Codec
import pipeline.utilities.checksums.Sha1Input
import pipeline.utilities.checksums.Sha1StepDefinition
import pipeline.utilities.checksums.Sha512Codec
import pipeline.utilities.checksums.Sha512Input
import pipeline.utilities.checksums.Sha512StepDefinition
import pipeline.utilities.checksums.UtilitiesChecksumError
import pipeline.utilities.checksums.UtilitiesChecksumException
import pipeline.utilities.checksums.UtilitiesChecksumsContributor
import java.nio.file.Files
import java.nio.file.Path

/**
 * StepContractSuite for the LFC-2E2-EXPANSION U5 checksums slice.
 *
 * U5 adds three new Step families (`utilities.md5`, `utilities.sha1`,
 * `utilities.sha512`) reusing the U1 sha256 pattern but with a typed closed
 * [HashAlgorithm] enum inside the plugin (NOT a string-keyed generic Step).
 *
 * Architectural claim: the algorithm name is a typed ADT, not a runtime
 * string. Each algorithm is a distinct StepKey with its own StepContract;
 * the shared digest logic lives in a single port that the compiler can
 * verify is exhaustively consumed.
 */
@Timeout(20)
class UtilitiesChecksumsStepContractSuiteTest {

    private fun checksumOps(): ChecksumOperations = DefaultChecksumOperations()

    private fun registry(): InMemoryStepRegistry =
        InMemoryStepRegistry().apply { ExternalStepPluginDiscovery.registerInto(this) }

    private fun utilityChecksumsCapabilityFactory(
        ops: ChecksumOperations = DefaultChecksumOperations(),
    ): (dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext) -> dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeCapabilityAccess =
        { ctx ->
            object : dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeCapabilityAccess(ctx) {
                private val extra: Map<StepCapability, Any> = mapOf(
                    UtilitiesChecksumsContributor.UTILITIES_CHECKSUMS_CAPABILITY to ops,
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
        workDir: Path = Files.createTempDirectory("utilities-checksums-contract-"),
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
                    CredentialScopeFailure.StoreUnavailable("utilities-checksums-contract-suite stub"),
                )
            },
            controlDirRoot = workDir.resolve("control"),
            shOptions = ShOptions.EMPTY,
            stepRegistry = registry(),
            capabilityAccessFactory = utilityChecksumsCapabilityFactory(),
        )
        return Harness(coord, eventStore)
    }

    private data class Harness(
        val coord: CanonicalDurableRunCoordinator,
        val eventStore: InMemoryEventStore,
    )

    private fun checksumsCapabilities(): StepCapabilityAccess = object : StepCapabilityAccess {
        override fun available(): Set<StepCapability> = setOf(
            UtilitiesChecksumsContributor.UTILITIES_CHECKSUMS_CAPABILITY,
        )
        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> get(key: StepCapability): T {
            return when (key) {
                UtilitiesChecksumsContributor.UTILITIES_CHECKSUMS_CAPABILITY -> checksumOps() as T
                else -> throw IllegalStateException("unexpected capability $key")
            }
        }
    }

    // ───────── identity ─────────

    @Test
    fun `identity - three checksum PluginStepIds are unique and coordinate matches JSON plugin coordinate`() {
        assertEquals(PluginStepId("utilities.md5"), Md5StepDefinition.KEY)
        assertEquals(PluginStepId("utilities.sha1"), Sha1StepDefinition.KEY)
        assertEquals(PluginStepId("utilities.sha512"), Sha512StepDefinition.KEY)
        // Each algorithm is its own StepKey (NOT one generic "utilities.checksum").
        assertTrue(Md5StepDefinition.KEY != Sha1StepDefinition.KEY)
        assertTrue(Sha1StepDefinition.KEY != Sha512StepDefinition.KEY)
        assertTrue(Md5StepDefinition.KEY != Sha512StepDefinition.KEY)
        assertEquals(UtilitiesChecksumsContributor.COORDINATE, pipeline.utilities.json.UtilitiesJsonContributor.COORDINATE)
    }

    @Test
    fun `identity - checksum families coexist in the SAME contributor as JSON+YAML+properties+filesystem families`() {
        val r = registry()
        assertTrue(r.contains(PluginStepId("utilities.md5")))
        assertTrue(r.contains(PluginStepId("utilities.sha1")))
        assertTrue(r.contains(PluginStepId("utilities.sha512")))
        assertTrue(r.contains(PluginStepId("utilities.readJSON")))
        assertTrue(r.contains(PluginStepId("utilities.findFiles")))
        assertTrue(r.contains(PluginStepId("utilities.readProperties")))
        // 3 JSON + 2 YAML + 2 properties + 2 filesystem + 3 checksums + 1 example.uppercase = 13 total.
        assertEquals(13, r.keys().size)
    }

    // ───────── contract completeness ─────────

    @Test
    fun `contract completeness - md5 declares read-only effect and checksums capability only`() {
        val c = Md5StepDefinition.contract
        assertEquals(setOf<Effect>(Effect.READ_ONLY), c.descriptor.effects.toSet())
        assertEquals(ReplayPolicy.MEMOIZED, c.descriptor.replayPolicy)
        assertEquals(
            setOf(UtilitiesChecksumsContributor.UTILITIES_CHECKSUMS_CAPABILITY),
            c.requiredCapabilities,
        )
    }

    @Test
    fun `contract completeness - sha1 and sha512 mirror md5 contract shape (read-only + same capability)`() {
        for (def in listOf(Sha1StepDefinition, Sha512StepDefinition)) {
            assertEquals(setOf<Effect>(Effect.READ_ONLY), def.contract.descriptor.effects.toSet())
            assertEquals(ReplayPolicy.MEMOIZED, def.contract.descriptor.replayPolicy)
            assertEquals(
                setOf(UtilitiesChecksumsContributor.UTILITIES_CHECKSUMS_CAPABILITY),
                def.contract.requiredCapabilities,
            )
        }
    }

    @Test
    fun `contract completeness - checksums plugin declares exactly one new capability token`() {
        val src = java.io.File(
            java.io.File(System.getProperty("user.dir"), "../..").canonicalFile,
            "examples/utilities-plugin/src/main/kotlin/pipeline/utilities/checksums/UtilitiesChecksumsPlugin.kt",
        ).readText()
        val declared = Regex("""StepCapability\(\"([^\"]+)\"""")
            .findAll(src)
            .map { it.groupValues[1] }
            .toSet()
        assertEquals(
            setOf("utilities.checksums.operations"),
            declared,
            "U5 checksums plugin must declare exactly one capability token (utilities.checksums.operations)",
        )
    }

    @Test
    fun `contract completeness - HashAlgorithm ADT is closed and exhaustive (3 variants + jdkName + expectedHexChars)`() {
        assertEquals(3, HashAlgorithm.entries.size)
        // Each variant must have a non-empty jdkName and a positive hex-char count.
        for (algo in HashAlgorithm.entries) {
            assertTrue(algo.jdkName.isNotBlank(), "HashAlgorithm.${'$'}{algo.name} must have a non-blank jdkName")
            assertTrue(algo.expectedHexChars > 0, "HashAlgorithm.${'$'}{algo.name} must have a positive expectedHexChars")
        }
        // Distinct digests expected lengths.
        assertEquals(setOf(32, 40, 128), HashAlgorithm.entries.map { it.expectedHexChars }.toSet())
    }

    // ───────── capability admission ─────────

    @Test
    fun `capability admission - md5 prepares Ready when checksums capability is available`() {
        val admission = RegistryExecutionPreparation.prepare(
            registry = registry(),
            key = Md5StepDefinition.KEY,
            encodedInput = Md5Codec.encode(Md5Input("/tmp/a.bin")),
            availableCapabilities = setOf(UtilitiesChecksumsContributor.UTILITIES_CHECKSUMS_CAPABILITY),
        )
        assertInstanceOf(ExecutionPreparation.Ready::class.java, admission)
    }

    @Test
    fun `capability admission - missing checksums capability REJECTS md5 before handler runs`() {
        val admission = RegistryExecutionPreparation.prepare(
            registry = registry(),
            key = Md5StepDefinition.KEY,
            encodedInput = Md5Codec.encode(Md5Input("/tmp/a.bin")),
            availableCapabilities = emptySet(),
        )
        assertInstanceOf(ExecutionPreparation.Rejected::class.java, admission)
    }

    // ───────── handler semantics ─────────

    @Test
    fun `handler - md5 matches the JDK MessageDigest value for a real file`() = runBlocking {
        val workDir = Files.createTempDirectory("utilities-checksums-md5-")
        val target = workDir.resolve("hello.bin")
        Files.writeString(target, "hello world")
        val ctx = StepHandlerContext(
            runId = RunId("utilities-checksums-md5"),
            stepIndex = 0,
            capabilities = checksumsCapabilities(),
        )
        val out: ChecksumOutput = Md5StepDefinition.handler.execute(Md5Input(target.toString()), ctx)
        // Known MD5 of "hello world" (RFC 1321 test vector): 5eb63bbbe01eeed093cb22bb8f5acdc3
        assertEquals("MD5", out.algorithm)
        assertEquals("5eb63bbbe01eeed093cb22bb8f5acdc3", out.digest)
        assertEquals(11L, out.bytes)
    }

    @Test
    fun `handler - sha1 matches the JDK MessageDigest value for a real file`() = runBlocking {
        val workDir = Files.createTempDirectory("utilities-checksums-sha1-")
        val target = workDir.resolve("hello.bin")
        Files.writeString(target, "hello world")
        val ctx = StepHandlerContext(
            runId = RunId("utilities-checksums-sha1"),
            stepIndex = 0,
            capabilities = checksumsCapabilities(),
        )
        val out: ChecksumOutput = Sha1StepDefinition.handler.execute(Sha1Input(target.toString()), ctx)
        // Known SHA-1 of "hello world": 2aae6c35c94fcfb415dbe95f408b9ce91ee846ed
        assertEquals("SHA-1", out.algorithm)
        assertEquals("2aae6c35c94fcfb415dbe95f408b9ce91ee846ed", out.digest)
    }

    @Test
    fun `handler - sha512 produces a 128-hex-char digest matching the JDK MessageDigest value`() = runBlocking {
        val workDir = Files.createTempDirectory("utilities-checksums-sha512-")
        val target = workDir.resolve("hello.bin")
        Files.writeString(target, "hello world")
        val ctx = StepHandlerContext(
            runId = RunId("utilities-checksums-sha512"),
            stepIndex = 0,
            capabilities = checksumsCapabilities(),
        )
        val out: ChecksumOutput = Sha512StepDefinition.handler.execute(Sha512Input(target.toString()), ctx)
        assertEquals("SHA-512", out.algorithm)
        // Cross-check against JDK's MessageDigest directly — the architectural claim is
        // that the plugin uses the JDK digest with no opaque intermediate step.
        val expected = java.security.MessageDigest.getInstance("SHA-512")
            .digest("hello world".toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals(128, out.digest.length, "SHA-512 hex digest must be 128 chars")
        assertEquals(expected, out.digest)
    }

    @Test
    fun `handler - different algorithms produce different digests for the same file`() = runBlocking {
        val workDir = Files.createTempDirectory("utilities-checksums-different-")
        val target = workDir.resolve("data.bin")
        Files.writeString(target, "pipeline-wu-g5b")
        val ctx = StepHandlerContext(
            runId = RunId("utilities-checksums-distinct"),
            stepIndex = 0,
            capabilities = checksumsCapabilities(),
        )
        val md5out: ChecksumOutput = Md5StepDefinition.handler.execute(Md5Input(target.toString()), ctx)
        val sha1out: ChecksumOutput = Sha1StepDefinition.handler.execute(Sha1Input(target.toString()), ctx)
        val sha512out: ChecksumOutput = Sha512StepDefinition.handler.execute(Sha512Input(target.toString()), ctx)
        // Length discipline: 32 vs 40 vs 128 hex chars.
        assertEquals(32, md5out.digest.length)
        assertEquals(40, sha1out.digest.length)
        assertEquals(128, sha512out.digest.length)
        // All distinct.
        assertNotEquals(md5out.digest, sha1out.digest)
        assertNotEquals(md5out.digest, sha512out.digest)
        assertNotEquals(sha1out.digest, sha512out.digest)
    }

    // ───────── typed failure semantics ────────

    @Test
    fun `typed failure - md5 of a missing file throws UtilitiesChecksumException(ChecksumNotFound)`() = runBlocking {
        val ctx = StepHandlerContext(
            runId = RunId("utilities-checksums-missing"),
            stepIndex = 0,
            capabilities = checksumsCapabilities(),
        )
        val missing = "/tmp/utilities-checksums-missing-${System.nanoTime()}.bin"
        val caught = runCatching {
            Md5StepDefinition.handler.execute(Md5Input(missing), ctx)
        }.exceptionOrNull()
        assertTrue(
            caught is UtilitiesChecksumException,
            "missing file must throw UtilitiesChecksumException, was: ${caught?.javaClass?.name}",
        )
        assertTrue(
            (caught as UtilitiesChecksumException).reason
                is UtilitiesChecksumError.ChecksumNotFound,
        )
    }

    @Test
    fun `typed failure - UtilitiesChecksumError sealed ADT is exhaustively matchable (2 cases)`() {
        val cases = listOf(
            UtilitiesChecksumError.ChecksumNotFound("/x"),
            UtilitiesChecksumError.ChecksumIoFailure("/y", "EACCES"),
        )
        val mapped: List<String> = cases.map { reason ->
            when (reason) {
                is UtilitiesChecksumError.ChecksumNotFound -> "notfound:" + reason.path
                is UtilitiesChecksumError.ChecksumIoFailure -> "io:" + reason.path
            }
        }
        assertEquals(2, mapped.size)
        assertEquals("notfound:/x", mapped[0])
        assertEquals("io:/y", mapped[1])
    }

    // ───────── real DSL scenario ─────────

    @Test
    fun `real DSL scenario - md5 + sha1 + sha512 round-trip runs end-to-end through the canonical spine`() = runBlocking {
        val workDir = Files.createTempDirectory("utilities-checksums-dsl-")
        val target = workDir.resolve("payload.bin")
        Files.writeString(target, "checksums-e2e")
        val spec: PipelineSpec = pipeline {
            stages {
                stage("ChecksumsE2E") {
                    registryStep(
                        stepKey = Md5StepDefinition.KEY,
                        encodedInput = Md5Codec.encode(Md5Input(target.toString())),
                    )
                    registryStep(
                        stepKey = Sha1StepDefinition.KEY,
                        encodedInput = Sha1Codec.encode(Sha1Input(target.toString())),
                    )
                    registryStep(
                        stepKey = Sha512StepDefinition.KEY,
                        encodedInput = Sha512Codec.encode(Sha512Input(target.toString())),
                    )
                }
            }
        }
        val compiled = DslCompiledPipelineCompiler.compile(
            spec = spec,
            sourcePath = "05-checksums-roundtrip.pipeline.kts",
            sourceContent = spec.toString(),
            pluginLockDigest = dev.rubentxu.pipeline.v2.domain.Digest("lock"),
        )
        val eventStore = InMemoryEventStore()
        val h = harness(eventStore, workDir)
        val run = h.coord.run(compiled, RunId("dsl-checksums-roundtrip"))
        assertEquals(RunOutcome.Success, run)
    }
}
