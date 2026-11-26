package dev.rubentxu.pipeline.v2.runtime

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.digest.Sha256
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.EXECUTION_LOCATION_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import dev.rubentxu.pipeline.v2.domain.workspace.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspaceLease
import dev.rubentxu.pipeline.v2.dsl.JUnitPublicationPayload
import dev.rubentxu.pipeline.v2.events.ArtifactArchived
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSink
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

/**
 * The publication law of `core.junit.publishReports`, measured end-to-end where the law lives.
 *
 * The fixtures are the real shapes the card names: a JVM (Surefire) report, an adversarial
 * Vitest `jest-junit` report, a file with a name NO convention could have guessed (`r.dat`),
 * an invalid XML and an empty directory the glob cannot match. Two halves:
 *  - the verdict law against a faked capture port (required/optional/absent/invalid/no-match,
 *    declared-only expectation and group);
 *  - the real local publication against the production `ArchiveArtifactsOperationsAdapter`:
 *    verified bytes in the retention store, receipts whose digest is what the store holds,
 *    and no archive event when a report is invalid.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class CorePublishJUnitReportsStepTest {

    @TempDir
    lateinit var tempDir: Path

    private val workspace: Path get() = tempDir.resolve("ws")
    private val controlRoot: Path get() = tempDir.resolve("control")

    private val surefireXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <testsuite name="dev.rubentxu.PublishSpec" tests="3" failures="1" errors="0" skipped="1" time="2.5">
          <testcase name="ok" classname="PublishSpec" time="1.0"/>
          <testcase name="broken" classname="PublishSpec" time="1.0"><failure message="boom">stack</failure></testcase>
          <testcase name="pending" classname="PublishSpec" time="0.0"><skipped/></testcase>
        </testsuite>
    """.trimIndent()

    /** The real shape of a Vitest `jest-junit` reporter: a testsuites wrapper repeating totals. */
    private val vitestXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <testsuites name="vitest tests" tests="2" failures="0" errors="0" time="0.9">
          <testsuite name="src/a.test.ts" timestamp="2026-11-29T00:00:00.000Z" tests="2" failures="0" errors="0" skipped="0" time="0.9">
            <testcase classname="a" name="sums" time="0.4"/>
            <testcase classname="a" name="subtracts" time="0.5"/>
          </testsuite>
        </testsuites>
    """.trimIndent()

    private class RecordingSink : EventSink {
        val appended = mutableListOf<DomainEvent>()
        override fun append(event: DomainEvent) {
            appended.add(event)
        }
        override fun eventsFor(runId: String): Sequence<DomainEvent> = appended.asSequence()
    }

    /** The capture port, faked to the two facts the verdict law needs: the answer and the ask. */
    private class FakeArchiveOps(private val result: ArchiveArtifactsResult) : ArchiveArtifactsOperations {
        val inputs = mutableListOf<ArchiveArtifactsInput>()
        override fun archive(input: ArchiveArtifactsInput): ArchiveArtifactsResult {
            inputs += input
            return result
        }
    }

    private class Seams(
        private val site: ExecutionLocation,
        private val ops: ArchiveArtifactsOperations,
    ) : StepCapabilityAccess {
        override fun available(): Set<StepCapability> =
            setOf(EXECUTION_LOCATION_CAPABILITY, ARTIFACT_ARCHIVE_OPERATIONS_CAPABILITY)

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> get(key: StepCapability, type: KClass<T>): T = when (key) {
            EXECUTION_LOCATION_CAPABILITY -> site as T
            ARTIFACT_ARCHIVE_OPERATIONS_CAPABILITY -> ops as T
            else -> throw AssertionError("the step asked for a capability it does not declare: $key")
        }
    }

    private fun receipt(relPath: String, bytes: ByteArray) = CaptureReceipt(
        runId = "run-1",
        stageName = "stage-a",
        relPath = relPath,
        sha256 = Sha256.ofBytes(bytes),
        size = bytes.size.toLong(),
    )

    private fun execute(
        input: PublishJUnitReportsInput,
        ops: ArchiveArtifactsOperations,
    ): Any = runBlocking {
        Files.createDirectories(workspace)
        CorePublishJUnitReportsStep.definition.handler.execute(
            input,
            StepHandlerContext(
                runId = RunId("run-1"),
                stepIndex = 0,
                capabilities = Seams(
                    site = ExecutionLocation(
                        workspace = WorkspaceLease.Managed(workspace),
                        cwd = workspace,
                    ),
                    ops = ops,
                ),
            ),
        )
    }

    private fun successOps(vararg relPaths: String) = FakeArchiveOps(
        ArchiveArtifactsSuccess(relPaths.map { receipt(it, "x".toByteArray()) }),
    )

    // ---- AC #1: the producer declares type and format; nothing is guessed from names ----

    @Test
    fun `the declared pattern publishes what it selects whatever the file is named`() {
        Files.createDirectories(workspace.resolve("out"))
        Files.writeString(workspace.resolve("out/r.dat"), surefireXml)
        val ops = successOps("out/r.dat")

        val output = execute(
            PublishJUnitReportsInput(artifacts = "out/r.dat"),
            ops,
        )

        val published = assertInstanceOf(PublishedReportsOutput::class.java, output)
        assertEquals(1, published.receipts.size)
        assertEquals("out/r.dat", published.receipts.single().relPath)
        // The reading is real: the JVM totals reached the output.
        assertEquals(3, published.receipts.single().tests)
        assertEquals(1, published.receipts.single().failures)
        // The capture went through the 339 port with the author's declaration.
        assertEquals(1, ops.inputs.size)
        assertEquals("out/r.dat", ops.inputs.single().artifacts)
        assertEquals(false, ops.inputs.single().allowEmptyArchive)
    }

    @Test
    fun `the refused message names the declared format version and media type`() {
        Files.createDirectories(workspace.resolve("out"))
        Files.writeString(workspace.resolve("out/claim.xml"), "<html><body>not a report</body></html>")
        val ops = successOps("out/claim.xml")

        val output = execute(
            PublishJUnitReportsInput(
                artifacts = "out/*.xml",
                reportFormat = "junit-xml",
                formatVersion = "2",
                mediaType = "text/xml",
            ),
            ops,
        )

        val failed = assertInstanceOf(PublishJUnitReportsFailureOutput::class.java, output)
        assertEquals(FailureKind.USER, failed.failureKind)
        assertTrue(
            failed.message.contains("junit-xml v2") && failed.message.contains("text/xml"),
            "the refusal must echo the producer's declaration, got: ${failed.message}",
        )
        assertTrue(ops.inputs.isEmpty(), "an unverified byte must never reach the capture port")
    }

    // ---- AC #2: publication after failure; required/optional/absent/invalid distinguished ----

    @Test
    fun `a report with failing tests is still published — publication does not depend on the verdict`() {
        Files.createDirectories(workspace.resolve("r"))
        Files.writeString(workspace.resolve("r/fails.xml"), surefireXml) // failures="1"
        val ops = successOps("r/fails.xml")

        val output = execute(PublishJUnitReportsInput(artifacts = "r/*.xml"), ops)

        assertInstanceOf(PublishedReportsOutput::class.java, output)
        assertEquals(1, ops.inputs.size)
    }

    @Test
    fun `a required publication whose glob matches nothing is not a pass`() {
        Files.createDirectories(workspace.resolve("empty-dir"))
        val ops = successOps()

        val output = execute(PublishJUnitReportsInput(artifacts = "empty-dir/**/*.xml"), ops)

        val failed = assertInstanceOf(PublishJUnitReportsFailureOutput::class.java, output)
        assertEquals(FailureKind.SCRIPT, failed.failureKind)
        assertTrue(failed.message.contains("not a pass"), failed.message)
        assertTrue(ops.inputs.isEmpty(), "nothing was matched, so the port must not be asked")
    }

    @Test
    fun `an optional publication says absent — never published`() {
        Files.createDirectories(workspace.resolve("empty-dir"))
        val ops = successOps()

        val output = execute(
            PublishJUnitReportsInput(artifacts = "empty-dir/**/*.xml", required = false),
            ops,
        )

        val absent = assertInstanceOf(AbsentOptionalReportsOutput::class.java, output)
        assertEquals("empty-dir/**/*.xml", absent.artifacts)
        assertTrue(ops.inputs.isEmpty(), "an absent optional publication archives nothing")
    }

    @Test
    fun `invalid XML is a verdict apart from absent, and nothing is archived`() {
        Files.createDirectories(workspace.resolve("r"))
        Files.writeString(workspace.resolve("r/broken.xml"), "<testsuite tests=\"abc\"")
        val ops = successOps("r/broken.xml")

        val output = execute(PublishJUnitReportsInput(artifacts = "r/*.xml"), ops)

        val failed = assertInstanceOf(PublishJUnitReportsFailureOutput::class.java, output)
        assertEquals(FailureKind.USER, failed.failureKind)
        assertTrue(failed.message.contains("r/broken.xml"), failed.message)
        assertTrue(ops.inputs.isEmpty())
    }

    @Test
    fun `an empty report is refused as invalid, not as absent`() {
        Files.createDirectories(workspace.resolve("r"))
        Files.writeString(workspace.resolve("r/zero.xml"), "")
        val ops = successOps("r/zero.xml")

        val output = execute(PublishJUnitReportsInput(artifacts = "r/*.xml"), ops)

        val failed = assertInstanceOf(PublishJUnitReportsFailureOutput::class.java, output)
        assertEquals(FailureKind.USER, failed.failureKind)
        assertTrue(failed.message.contains("empty"), failed.message)
    }

    @Test
    fun `a report over the declared cap is refused before it is parsed`() {
        // The cap is the hard limit the schema documents, and it decides BEFORE the parse: a
        // valid-but-oversized report must never reach the parser, and nothing reaches the port.
        Files.createDirectories(workspace.resolve("r"))
        Files.writeString(
            workspace.resolve("r/fat.xml"),
            "<!--" + "x".repeat(5 * 1024) + "-->" + surefireXml,
        )
        val ops = successOps("r/fat.xml")

        val output = execute(
            PublishJUnitReportsInput(artifacts = "r/*.xml", maxReportBytes = 4096),
            ops,
        )

        val failed = assertInstanceOf(PublishJUnitReportsFailureOutput::class.java, output)
        assertEquals(FailureKind.USER, failed.failureKind)
        assertTrue(
            failed.message.contains("r/fat.xml") && failed.message.contains("maxReportBytes=4096"),
            "the refusal must name the oversized report and the cap that refused it, got: ${failed.message}",
        )
        assertTrue(ops.inputs.isEmpty(), "a byte the cap refuses must never reach the capture port")
    }

    // ---- AC #3: expectation and group are declared-only; no-match never passes ----

    @Test
    fun `a declared expected count decides, an undeclared one invents no rule`() {
        Files.createDirectories(workspace.resolve("r"))
        Files.writeString(workspace.resolve("r/a.xml"), surefireXml)
        Files.writeString(workspace.resolve("r/b.xml"), vitestXml)

        val without = execute(PublishJUnitReportsInput(artifacts = "r/*.xml"), successOps("r/a.xml", "r/b.xml"))
        assertInstanceOf(PublishedReportsOutput::class.java, without)

        val mismatch = execute(
            PublishJUnitReportsInput(artifacts = "r/*.xml", expectedCount = 5),
            successOps(),
        )
        val failed = assertInstanceOf(PublishJUnitReportsFailureOutput::class.java, mismatch)
        assertEquals(FailureKind.SCRIPT, failed.failureKind)
        assertTrue(failed.message.contains("expected 5"), failed.message)

        val exact = execute(
            PublishJUnitReportsInput(artifacts = "r/*.xml", expectedCount = 2, expectedGroup = "unit"),
            successOps("r/a.xml", "r/b.xml"),
        )
        assertEquals("unit", assertInstanceOf(PublishedReportsOutput::class.java, exact).group)
    }

    @Test
    fun `an optional publication with a declared expectation still fails on no-match`() {
        Files.createDirectories(workspace.resolve("empty-dir"))
        val output = execute(
            PublishJUnitReportsInput(
                artifacts = "empty-dir/**/*.xml",
                required = false,
                expectedCount = 2,
            ),
            successOps(),
        )
        assertInstanceOf(PublishJUnitReportsFailureOutput::class.java, output)
    }

    @Test
    fun `the group is a carried label and never a forced policy`() {
        Files.createDirectories(workspace.resolve("r"))
        // A report whose tests FAILED: the group label must not turn that into a failure of
        // the publication, because no build policy is implied by declaring a group.
        Files.writeString(workspace.resolve("r/failing.xml"), surefireXml)

        val output = execute(
            PublishJUnitReportsInput(artifacts = "r/*.xml", expectedGroup = "e2e"),
            successOps("r/failing.xml"),
        )

        val published = assertInstanceOf(PublishedReportsOutput::class.java, output)
        assertEquals("e2e", published.group)
        assertEquals(
            dev.rubentxu.pipeline.v2.domain.StepOutcome.Success,
            published.outcome,
        )
    }

    // ---- AC #4: wire compatibility, registry presence, real local publication ----

    @Test
    fun `the DSL façade and the codec are one format — the payload decodes to the declaration`() {
        val input = PublishJUnitReportsInput(
            artifacts = "out/*.dat",
            reportFormat = "junit-xml",
            formatVersion = "2",
            mediaType = "text/xml",
            required = false,
            expectedCount = 4,
            expectedGroup = "unit",
            archiveName = "junit-unit",
            maxReportBytes = 4096,
        )
        val fromFaçade = JUnitPublicationPayload.encode(
            artifacts = input.artifacts,
            reportFormat = input.reportFormat,
            formatVersion = input.formatVersion,
            mediaType = input.mediaType,
            required = input.required,
            expectedCount = input.expectedCount,
            expectedGroup = input.expectedGroup,
            archiveName = input.archiveName,
            maxReportBytes = input.maxReportBytes,
        )

        assertEquals(CorePublishJUnitReportsWireCodec.encode(input), fromFaçade)
        assertEquals(input, CorePublishJUnitReportsWireCodec.decode(fromFaçade))
        assertEquals(PluginStepId("core.junit.publishReports"), JUnitPublicationPayload.STEP_KEY)
    }

    @Test
    fun `the defaults of the codec decode an absent-field payload to the declared format`() {
        val decoded = CorePublishJUnitReportsWireCodec.decode(
            EncodedStepValue("""{"kind":"junitPublish","artifacts":"r/*.xml"}"""),
        )
        assertEquals("junit-xml", decoded.reportFormat)
        assertEquals("1", decoded.formatVersion)
        assertEquals("application/xml", decoded.mediaType)
        assertTrue(decoded.required, "an absent required means REQUIRED, never a silent pass")
        assertNull(decoded.expectedCount)
        assertNull(decoded.expectedGroup)
    }

    @Test
    fun `declaring a format the step cannot verify is refused when decoding, never parsed`() {
        // Fail-closed: a producer cannot claim testng-xml and get junit-xml verdicts. The
        // refusal happens at decode — before the handler sees a file — and names the format
        // the author declared, because the claim itself is the defect.
        val error = assertThrows(IllegalArgumentException::class.java) {
            CorePublishJUnitReportsWireCodec.decode(
                EncodedStepValue("""{"kind":"junitPublish","artifacts":"r/*.xml","reportFormat":"testng-xml"}"""),
            )
        }
        assertTrue(
            error.message!!.contains("testng-xml"),
            "the refusal must name the declared format, got: ${error.message}",
        )
    }

    @Test
    fun `the output codec carries every publication form through a round-trip`() {
        // The scripted form decodes this codec's text to hand the value back to the script, so
        // every fact of the three forms must survive encode→decode: a renamed output key dies
        // here and not silently in the consumer.
        val codec = CorePublishJUnitReportsStep.definition.contract.outputCodec

        val published = PublishedReportsOutput(
            group = "unit",
            receipts = listOf(
                PublishedReport(
                    relPath = "out/r.dat", sha256 = "café\"ded\\beef", sizeBytes = 512L,
                    tests = 3, failures = 1, errors = 2, skipped = 4,
                ),
                PublishedReport(
                    relPath = "out/otro.xml", sha256 = "def456", sizeBytes = 1L,
                    tests = 0, failures = 0, errors = 0, skipped = 0,
                ),
            ),
        )
        assertEquals(published, codec.decode(codec.encode(published)))

        val absent = AbsentOptionalReportsOutput(artifacts = "r/**/*.xml", group = null)
        assertEquals(absent, codec.decode(codec.encode(absent)))

        val failed = PublishJUnitReportsFailureOutput(FailureKind.USER, "no es válido \"junit-xml\"")
        assertEquals(failed, codec.decode(codec.encode(failed)))
    }

    @Test
    fun `archiveArtifacts keeps its own wire format unchanged — existing clients are compatible`() {
        val legacy = """{"kind":"archiveArtifacts","artifacts":"build/**/*.jar","allowEmptyArchive":false,"excludes":"","fingerprint":false}"""
        val decoded = CoreArchiveArtifactsWireCodec.decode(EncodedStepValue(legacy))

        assertEquals("build/**/*.jar", decoded.artifacts)
        assertNull(decoded.name, "the legacy bytes carry no name")
        assertEquals(legacy, CoreArchiveArtifactsWireCodec.encode(decoded).value)
    }

    @Test
    fun `the step is registered in the production core registry`() {
        assertNotNull(CoreStepRegistryFactory.registry().definition(CorePublishJUnitReportsStep.KEY))
    }

    @Test
    fun `real local publication — verified bytes reach the retention store with receipts read back`() {
        Files.createDirectories(workspace.resolve("build/test-results"))
        Files.createDirectories(controlRoot)
        Files.writeString(workspace.resolve("build/test-results/jvm.xml"), surefireXml)
        Files.writeString(workspace.resolve("build/test-results/vitest.junit.xml"), vitestXml)
        val sink = RecordingSink()
        val adapter = ArchiveArtifactsOperationsAdapter(
            runIdString = "run-1",
            stageIdentity = StageIdentity(name = "stage-a", index = 0),
            controlDirRoot = controlRoot,
            eventSink = sink,
            executionLocation = ExecutionLocation(
                workspace = WorkspaceLease.Managed(workspace),
                cwd = workspace,
            ),
        )

        val output = execute(PublishJUnitReportsInput(artifacts = "build/test-results/**/*.xml"), adapter)

        val published = assertInstanceOf(PublishedReportsOutput::class.java, output)
        assertEquals(listOf("build/test-results/jvm.xml", "build/test-results/vitest.junit.xml"), published.receipts.map { it.relPath })
        for (report in published.receipts) {
            val stored = controlRoot.resolve("artefacts/run-1/stage-a").resolve(report.relPath)
            assertTrue(Files.isRegularFile(stored), "the publication must leave real bytes: $stored")
            assertEquals(Sha256.ofFile(stored), report.sha256, "the receipt states what the store holds")
            assertEquals(Files.size(stored), report.sizeBytes)
        }
        assertTrue(sink.appended.filterIsInstance<ArtifactArchived>().size == 1, "one success event for one publication")
    }

    @Test
    fun `a Vitest-shaped report publishes with its real totals`() {
        Files.createDirectories(workspace.resolve("r"))
        Files.writeString(workspace.resolve("r/vitest.junit.xml"), vitestXml)

        val output = execute(PublishJUnitReportsInput(artifacts = "r/*.xml"), successOps("r/vitest.junit.xml"))

        val published = assertInstanceOf(PublishedReportsOutput::class.java, output)
        assertEquals(2, published.receipts.single().tests)
        assertEquals(0, published.receipts.single().failures)
    }

    @Test
    fun `real local publication archives nothing when a report is invalid`() {
        Files.createDirectories(workspace.resolve("r"))
        Files.createDirectories(controlRoot)
        Files.writeString(workspace.resolve("r/good.xml"), vitestXml)
        Files.writeString(workspace.resolve("r/bad.xml"), "<testsuite tests=\"1\" failures=\"x\"/>")
        val sink = RecordingSink()
        val adapter = ArchiveArtifactsOperationsAdapter(
            runIdString = "run-1",
            stageIdentity = StageIdentity(name = "stage-a", index = 0),
            controlDirRoot = controlRoot,
            eventSink = sink,
            executionLocation = ExecutionLocation(
                workspace = WorkspaceLease.Managed(workspace),
                cwd = workspace,
            ),
        )

        val output = execute(PublishJUnitReportsInput(artifacts = "r/*.xml"), adapter)

        assertInstanceOf(PublishJUnitReportsFailureOutput::class.java, output)
        assertTrue(sink.appended.filterIsInstance<ArtifactArchived>().isEmpty(), "no success event on an invalid report")
        val retention = controlRoot.resolve("artefacts/run-1/stage-a")
        assertTrue(!Files.exists(retention) || Files.list(retention).use { !it.findAny().isPresent }, "no byte archived")
    }
}
