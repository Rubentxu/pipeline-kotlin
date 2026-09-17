package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator
import dev.rubentxu.pipeline.v2.application.durable.CanonicalNodeDispatcher
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeCapabilityAccess
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeFailure
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeOutcome
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import pipeline.testing.TestingContributor
import pipeline.testing.publish.DefaultReportPublishingOperations
import pipeline.testing.publish.MissingReportPolicy
import pipeline.testing.publish.PublishHtmlError
import pipeline.testing.publish.PublishHtmlException
import pipeline.testing.publish.PublishHtmlInput
import pipeline.testing.publish.PublishHtmlInputCodec
import pipeline.testing.publish.PublishHtmlStepDefinition
import pipeline.testing.publish.PublishedReport
import pipeline.testing.publish.ReportPublishingOperations
import java.nio.file.Files
import java.nio.file.Path

/**
 * StepContractSuite for the LFC-2E3-R1 `core.publishHTML` Step.
 *
 * Two things are under test:
 *  1. the ordinary contract (identity, codecs, capability admission, outcome shapes);
 *  2. the **fail-closed security posture**, because publishing copies files out of a
 *     caller-influenced directory. Traversal (`..`), absolute entries, symlinked directories,
 *     symlinked entries and symlinks inside the tree must all be rejected rather than skipped,
 *     so a hostile tree cannot partially succeed.
 */
@Timeout(30)
class TestingPublishHtmlStepContractSuiteTest {

    private fun publishOps(): ReportPublishingOperations = DefaultReportPublishingOperations()

    private fun registry(): InMemoryStepRegistry =
        InMemoryStepRegistry().apply { ExternalStepPluginDiscovery.registerInto(this) }

    private fun publishCapabilityFactory(
        ops: ReportPublishingOperations = DefaultReportPublishingOperations(),
    ): (CanonicalRuntimeContext) -> CanonicalRuntimeCapabilityAccess = { ctx ->
        object : CanonicalRuntimeCapabilityAccess(ctx) {
            private val extra: Map<StepCapability, Any> = mapOf(
                TestingContributor.TESTING_PUBLISH_CAPABILITY to ops,
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
        workDir: Path = Files.createTempDirectory("testing-publish-contract-"),
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
                    CredentialScopeFailure.StoreUnavailable("testing-publish-contract stub"),
                )
            },
            controlDirRoot = workDir.resolve("control"),
            shOptions = ShOptions.EMPTY,
            stepRegistry = registry(),
            capabilityAccessFactory = publishCapabilityFactory(),
        )
        return Harness(coord, eventStore)
    }

    private data class Harness(
        val coord: CanonicalDurableRunCoordinator,
        val eventStore: InMemoryEventStore,
    )

    private fun capabilities(): StepCapabilityAccess = object : StepCapabilityAccess {
        override fun available(): Set<StepCapability> = setOf(
            TestingContributor.TESTING_PUBLISH_CAPABILITY,
        )
        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> get(key: StepCapability): T = when (key) {
            TestingContributor.TESTING_PUBLISH_CAPABILITY -> publishOps() as T
            else -> throw IllegalStateException("unexpected capability $key")
        }
    }

    private fun ctx() = StepHandlerContext(
        runId = RunId("testing-publish"),
        stepIndex = 0,
        capabilities = capabilities(),
    )

    /** Builds a report directory with an entry document and two assets. */
    private fun reportTree(root: Path, entry: String = "index.html"): Path {
        val report = root.resolve("report")
        Files.createDirectories(report.resolve("assets"))
        Files.writeString(report.resolve(entry), "<html><body>ok</body></html>")
        Files.writeString(report.resolve("assets/style.css"), "body{}")
        Files.writeString(report.resolve("assets/app.js"), "console.log(1)")
        return report
    }

    // ───────── identity ─────────

    @Test
    fun `identity - publishHTML is core-publishHTML and shares the testing coordinate`() {
        assertEquals(PluginStepId("core.publishHTML"), PublishHtmlStepDefinition.KEY)
        assertEquals(TestingContributor.COORDINATE, PublishHtmlStepDefinition.contract.descriptor.pluginId)
    }

    @Test
    fun `identity - both testing families coexist in the SAME registry`() {
        val r = registry()
        assertTrue(r.contains(PluginStepId("core.junit")))
        assertTrue(r.contains(PluginStepId("core.publishHTML")))
        // 16 utilities + 1 example.uppercase + core.junit + core.publishHTML = 19
        assertEquals(19, r.keys().size)
    }

    // ───────── contract completeness ─────────

    @Test
    fun `contract completeness - publishHTML is a workspace writer memoized like archiveArtifacts`() {
        val c = PublishHtmlStepDefinition.contract
        assertEquals(setOf<Effect>(Effect.WRITES_WORKSPACE), c.descriptor.effects.toSet())
        assertEquals(ReplayPolicy.MEMOIZED, c.descriptor.replayPolicy)
        assertEquals(
            setOf(TestingContributor.TESTING_PUBLISH_CAPABILITY),
            c.requiredCapabilities,
            "publishHTML must declare ONLY the publisher port",
        )
    }

    @Test
    fun `contract completeness - publisher port is distinct from the read-only parser port`() {
        // Granting read access to reports must not imply permission to write published copies.
        assertTrue(
            TestingContributor.TESTING_PUBLISH_CAPABILITY != TestingContributor.TESTING_FILESYSTEM_CAPABILITY,
            "the publisher and the reader must be separately grantable",
        )
    }

    @Test
    fun `no ReportStore mega-abstraction exists in the plugin`() {
        // Cycle directive: do NOT introduce ReportStore / QualityPlatform / ResultStore before a
        // third reporting family demonstrates the need. This row is the mechanical guard.
        val root = java.io.File(System.getProperty("user.dir"), "../..").canonicalFile
        // Match DECLARATIONS only. Naming a forbidden abstraction in prose to explain its absence
        // (as this file's own documentation does) must not trip the guard; declaring one must.
        val declaration = Regex(
            """\b(?:interface|class|object|typealias)\s+(ReportStore|QualityPlatform|ResultStore)\b""",
        )
        val offenders = mutableListOf<String>()
        for (module in listOf("examples/testing-plugin/src", "examples/utilities-plugin/src")) {
            java.io.File(root, module).walkTopDown()
                .filter { it.isFile && it.name.endsWith(".kt") }
                .forEach { file ->
                    declaration.findAll(file.readText()).forEach { hit ->
                        offenders += file.name + ":" + hit.groupValues[1]
                    }
                }
        }
        assertTrue(
            offenders.isEmpty(),
            "no reporting mega-abstraction may exist yet; found $offenders",
        )
    }

    // ───────── codecs ─────────

    @Test
    fun `codec - input round-trips`() {
        val input = PublishHtmlInput(
            reportName = "Unit Tests",
            reportDir = "/tmp/r",
            reportFiles = "index.html",
            allowMissing = true,
            targetDir = "/tmp/out",
        )
        assertEquals(input, PublishHtmlInputCodec.decode(PublishHtmlInputCodec.encode(input)))
    }

    @Test
    fun `codec - declared allowMissing lowers to a closed policy ADT`() {
        assertEquals(MissingReportPolicy.Fail, MissingReportPolicy.of(allowMissing = false))
        assertEquals(MissingReportPolicy.Tolerate, MissingReportPolicy.of(allowMissing = true))
    }

    // ───────── capability admission ─────────

    @Test
    fun `capability admission - Ready when the publisher port is available`() {
        val admission = dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionPreparation.prepare(
            registry = registry(),
            key = PublishHtmlStepDefinition.KEY,
            encodedInput = PublishHtmlInputCodec.encode(
                PublishHtmlInput(reportName = "R", reportDir = "/tmp/r"),
            ),
            availableCapabilities = setOf(TestingContributor.TESTING_PUBLISH_CAPABILITY),
        )
        assertInstanceOf(dev.rubentxu.pipeline.v2.application.durable.ExecutionPreparation.Ready::class.java, admission)
    }

    @Test
    fun `capability admission - REJECTED without the publisher port`() {
        val admission = dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionPreparation.prepare(
            registry = registry(),
            key = PublishHtmlStepDefinition.KEY,
            encodedInput = PublishHtmlInputCodec.encode(
                PublishHtmlInput(reportName = "R", reportDir = "/tmp/r"),
            ),
            availableCapabilities = emptySet(),
        )
        assertInstanceOf(dev.rubentxu.pipeline.v2.application.durable.ExecutionPreparation.Rejected::class.java, admission)
    }

    // ───────── handler semantics ─────────

    @Test
    fun `handler - publishes the entry document and every asset deterministically`() = runBlocking {
        val work = Files.createTempDirectory("publish-happy-")
        val report = reportTree(work)
        val target = work.resolve("published")

        val out = PublishHtmlStepDefinition.handler.execute(
            PublishHtmlInput(
                reportName = "Unit Tests",
                reportDir = report.toString(),
                reportFiles = "index.html",
                targetDir = target.toString(),
            ),
            ctx(),
        ) as PublishedReport.Published

        assertEquals("Unit Tests", out.reportName)
        assertEquals("index.html", out.entryPoint)
        assertEquals(
            listOf("assets/app.js", "assets/style.css", "index.html"),
            out.publishedFiles,
            "published files must be a deterministic, sorted, relative list",
        )
        assertTrue(out.totalBytes > 0, "a published report must have content")
        assertTrue(Files.exists(target.resolve("index.html")))
        assertTrue(Files.exists(target.resolve("assets/style.css")))
    }

    @Test
    fun `handler - missing report directory with allowMissing=false throws ReportDirMissing`() = runBlocking {
        val work = Files.createTempDirectory("publish-missing-")
        val caught = runCatching {
            PublishHtmlStepDefinition.handler.execute(
                PublishHtmlInput(
                    reportName = "R",
                    reportDir = work.resolve("nope").toString(),
                    allowMissing = false,
                ),
                ctx(),
            )
        }.exceptionOrNull()

        assertTrue(caught is PublishHtmlException, "was: ${caught?.javaClass?.name}")
        assertTrue((caught as PublishHtmlException).reason is PublishHtmlError.ReportDirMissing)
    }

    @Test
    fun `handler - missing report directory with allowMissing=true is a typed Missing outcome`() = runBlocking {
        val work = Files.createTempDirectory("publish-allowed-")
        val out = PublishHtmlStepDefinition.handler.execute(
            PublishHtmlInput(
                reportName = "R",
                reportDir = work.resolve("nope").toString(),
                allowMissing = true,
            ),
            ctx(),
        )
        // Jenkins parity: allowMissing tolerates absence as a first-class outcome, not an error.
        assertInstanceOf(PublishedReport.Missing::class.java, out)
        assertEquals(MissingReportPolicy.Tolerate, (out as PublishedReport.Missing).policy)
    }

    @Test
    fun `handler - entry that does not exist in an existing dir throws EntryMissing`() = runBlocking {
        val work = Files.createTempDirectory("publish-entry-missing-")
        val report = reportTree(work)
        val caught = runCatching {
            PublishHtmlStepDefinition.handler.execute(
                PublishHtmlInput(
                    reportName = "R",
                    reportDir = report.toString(),
                    reportFiles = "nope.html",
                ),
                ctx(),
            )
        }.exceptionOrNull()
        assertTrue(caught is PublishHtmlException)
        assertTrue((caught as PublishHtmlException).reason is PublishHtmlError.EntryMissing)
    }

    // ───────── SECURITY: fail-closed guards ─────────

    @Test
    fun `security - entry with parent traversal is rejected`() = runBlocking {
        val work = Files.createTempDirectory("publish-traversal-")
        val report = reportTree(work)
        Files.writeString(work.resolve("secret.html"), "<html>secret</html>")

        val caught = runCatching {
            PublishHtmlStepDefinition.handler.execute(
                PublishHtmlInput(
                    reportName = "R",
                    reportDir = report.toString(),
                    reportFiles = "../secret.html",
                ),
                ctx(),
            )
        }.exceptionOrNull()

        assertTrue(caught is PublishHtmlException, "was: ${caught?.javaClass?.name}")
        val reason = (caught as PublishHtmlException).reason
        assertTrue(
            reason is PublishHtmlError.EntryEscapesReportDir,
            "traversal must be EntryEscapesReportDir, was: $reason",
        )
    }

    @Test
    fun `security - absolute entry path is rejected`() = runBlocking {
        val work = Files.createTempDirectory("publish-absolute-")
        val report = reportTree(work)
        val caught = runCatching {
            PublishHtmlStepDefinition.handler.execute(
                PublishHtmlInput(
                    reportName = "R",
                    reportDir = report.toString(),
                    reportFiles = "/etc/passwd",
                ),
                ctx(),
            )
        }.exceptionOrNull()
        assertTrue(caught is PublishHtmlException)
        assertTrue(
            (caught as PublishHtmlException).reason is PublishHtmlError.EntryEscapesReportDir,
            "absolute entries must be rejected outright",
        )
    }

    @Test
    fun `security - symlinked report directory is rejected`() = runBlocking {
        val work = Files.createTempDirectory("publish-symlink-dir-")
        val real = reportTree(work)
        val link = work.resolve("linked-report")
        Files.createSymbolicLink(link, real)

        val caught = runCatching {
            PublishHtmlStepDefinition.handler.execute(
                PublishHtmlInput(reportName = "R", reportDir = link.toString()),
                ctx(),
            )
        }.exceptionOrNull()
        assertTrue(caught is PublishHtmlException, "was: ${caught?.javaClass?.name}")
        assertTrue((caught as PublishHtmlException).reason is PublishHtmlError.SymlinkRejected)
    }

    @Test
    fun `security - symlink entry resolving OUTSIDE the report dir is rejected as an escape`() = runBlocking {
        val work = Files.createTempDirectory("publish-symlink-entry-out-")
        val report = reportTree(work)
        val outside = work.resolve("outside.html")
        Files.writeString(outside, "<html>outside</html>")
        Files.delete(report.resolve("index.html"))
        Files.createSymbolicLink(report.resolve("index.html"), outside)

        val caught = runCatching {
            PublishHtmlStepDefinition.handler.execute(
                PublishHtmlInput(reportName = "R", reportDir = report.toString()),
                ctx(),
            )
        }.exceptionOrNull()

        // Containment is checked on the RESOLVED path, so a link leaving the report directory is
        // classified as an escape (the stronger, more actionable statement) rather than merely as
        // "a link".
        assertTrue(caught is PublishHtmlException, "was: ${caught?.javaClass?.name}")
        assertTrue(
            (caught as PublishHtmlException).reason is PublishHtmlError.EntryEscapesReportDir,
            "was: ${(caught).reason}",
        )
    }

    @Test
    fun `security - symlink entry resolving INSIDE the report dir is rejected as a symlink`() = runBlocking {
        val work = Files.createTempDirectory("publish-symlink-entry-in-")
        val report = reportTree(work)
        // A link that stays inside containment: containment alone would let it through, so the
        // explicit symlink guard must reject it.
        Files.createSymbolicLink(report.resolve("alias.html"), report.resolve("index.html"))

        val caught = runCatching {
            PublishHtmlStepDefinition.handler.execute(
                PublishHtmlInput(reportName = "R", reportDir = report.toString()),
                ctx(),
            )
        }.exceptionOrNull()

        assertTrue(caught is PublishHtmlException, "was: ${caught?.javaClass?.name}")
        assertTrue(
            (caught as PublishHtmlException).reason is PublishHtmlError.SymlinkRejected,
            "was: ${(caught).reason}",
        )
    }

    @Test
    fun `security - a symlink anywhere in the tree aborts the whole publication`() = runBlocking {
        val work = Files.createTempDirectory("publish-symlink-tree-")
        val report = reportTree(work)
        Files.writeString(work.resolve("target.css"), "body{color:red}")
        Files.createSymbolicLink(report.resolve("assets").resolve("evil.css"), work.resolve("target.css"))

        val target = work.resolve("published")
        val caught = runCatching {
            PublishHtmlStepDefinition.handler.execute(
                PublishHtmlInput(
                    reportName = "R",
                    reportDir = report.toString(),
                    targetDir = target.toString(),
                ),
                ctx(),
            )
        }.exceptionOrNull()

        assertTrue(caught is PublishHtmlException, "was: ${caught?.javaClass?.name}")
        assertTrue(
            (caught as PublishHtmlException).reason is PublishHtmlError.SymlinkRejected,
            "a link inside the tree must be rejected, not skipped",
        )
        assertFalse(
            Files.exists(target.resolve("index.html")),
            "a rejected tree must not leave a partial publication behind",
        )
    }

    @Test
    fun `security - entry that is a directory is rejected`() = runBlocking {
        val work = Files.createTempDirectory("publish-entry-dir-")
        val report = reportTree(work)
        val caught = runCatching {
            PublishHtmlStepDefinition.handler.execute(
                PublishHtmlInput(
                    reportName = "R",
                    reportDir = report.toString(),
                    reportFiles = "assets",
                ),
                ctx(),
            )
        }.exceptionOrNull()
        assertTrue(caught is PublishHtmlException)
        assertTrue(
            (caught as PublishHtmlException).reason is PublishHtmlError.EntryNotRegularFile,
        )
    }

    @Test
    fun `security - PublishHtmlError is closed and exhaustively matchable (6 cases)`() {
        val cases: List<PublishHtmlError> = listOf(
            PublishHtmlError.ReportDirMissing("/a"),
            PublishHtmlError.EntryMissing("/a", "i.html"),
            PublishHtmlError.EntryEscapesReportDir("..", "/x"),
            PublishHtmlError.SymlinkRejected("l", "/t"),
            PublishHtmlError.EntryNotRegularFile("d"),
            PublishHtmlError.IoFailure("/a", "EACCES"),
        )
        val kinds = cases.map { error ->
            when (error) {
                is PublishHtmlError.ReportDirMissing -> "missing-dir"
                is PublishHtmlError.EntryMissing -> "missing-entry"
                is PublishHtmlError.EntryEscapesReportDir -> "escape"
                is PublishHtmlError.SymlinkRejected -> "symlink"
                is PublishHtmlError.EntryNotRegularFile -> "not-file"
                is PublishHtmlError.IoFailure -> "io"
            }
        }
        assertEquals(6, kinds.size)
    }

    // ───────── real DSL scenario ─────────

    @Test
    fun `real DSL scenario - publishHTML runs end-to-end through the canonical spine`() = runBlocking {
        val work = Files.createTempDirectory("publish-dsl-")
        val report = reportTree(work)
        val spec: PipelineSpec = pipeline {
            stages {
                stage("PublishE2E") {
                    registryStep(
                        stepKey = PublishHtmlStepDefinition.KEY,
                        encodedInput = PublishHtmlInputCodec.encode(
                            PublishHtmlInput(
                                reportName = "Unit Tests",
                                reportDir = report.toString(),
                                targetDir = work.resolve("published").toString(),
                            ),
                        ),
                    )
                }
            }
        }
        val compiled = DslCompiledPipelineCompiler.compile(
            spec = spec,
            sourcePath = "publishHTML.pipeline.kts",
            sourceContent = spec.toString(),
            pluginLockDigest = dev.rubentxu.pipeline.v2.domain.Digest("lock"),
        )
        val eventStore = InMemoryEventStore()
        val h = harness(eventStore, work)
        assertEquals(RunOutcome.Success, h.coord.run(compiled, RunId("dsl-publish")))
        assertTrue(Files.exists(work.resolve("published").resolve("index.html")))
    }
}
