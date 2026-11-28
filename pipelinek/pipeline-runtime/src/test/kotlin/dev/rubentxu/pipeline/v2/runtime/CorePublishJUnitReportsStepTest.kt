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
import dev.rubentxu.pipeline.v2.sdk.junit.step.JUnitReportParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

/**
 * The publication law of `core.junit.publishReports`, measured end-to-end where the law lives.
 *
 * The fixtures are the real shapes the card names: a JVM (Surefire) report, an adversarial
 * Vitest `jest-junit` report, a file with a name NO convention could have guessed (`r.dat`),
 * an invalid XML and an empty directory the glob cannot match. Two halves:
 *  - the verdict law against a faked capture port whose read-back is verified against the
 *    receipt exactly as the production adapter verifies it (required/optional/absent/invalid/
 *    no-match, declared-only expectation and group, and the refusals of receipts the store
 *    cannot answer for);
 *  - the real local publication against the production `ArchiveArtifactsOperationsAdapter`:
 *    verified bytes in the retention store, receipts whose digest is what the store holds,
 *    and the law the publication lives by — the summary is parsed from THE CAPTURE, so a
 *    workspace file rewritten between the capture and the parse cannot alter what was
 *    published.
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

    /** A valid report whose totals differ from [surefireXml]: what the workspace holds AFTER the capture. */
    private val rewrittenXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <testsuite name="dev.rubentxu.RewrittenSpec" tests="7" failures="0" errors="0" skipped="0" time="3.0">
          <testcase name="later" classname="RewrittenSpec" time="0.5"/>
        </testsuite>
    """.trimIndent()

    private class RecordingSink : EventSink {
        val appended = mutableListOf<DomainEvent>()
        override fun append(event: DomainEvent) {
            appended.add(event)
        }
        override fun eventsFor(runId: String): Sequence<DomainEvent> = appended.asSequence()
    }

    /**
     * The capture port, faked to the contract the verdict law needs: archive answers with
     * what it is told to answer, and the read-back is verified against the receipt the same
     * way the production adapter verifies it — a regular stored node, of the receipt's size,
     * digesting to the receipt's identity — so a receipt the store cannot vouch for is a
     * typed refusal, never bytes under suspicion.
     */
    private class FakeArchiveOps(
        private val result: ArchiveArtifactsResult,
        private val storeRoot: Path,
    ) : ArchiveArtifactsOperations {
        val inputs = mutableListOf<ArchiveArtifactsInput>()
        override fun archive(input: ArchiveArtifactsInput): ArchiveArtifactsResult {
            inputs += input
            return result
        }

        override fun openCapture(receipt: CaptureReceipt): CapturedBytesResult {
            val stored = storeRoot.resolve(receipt.name.value)
            if (!Files.exists(stored, LinkOption.NOFOLLOW_LINKS) ||
                !Files.isRegularFile(stored, LinkOption.NOFOLLOW_LINKS)
            ) {
                return CapturedBytesRefusal("the digest address of '${receipt.name.value}' holds no capture")
            }
            val storedSize = Files.size(stored)
            if (storedSize != receipt.size) {
                return CapturedBytesRefusal(
                    "the digest address of '${receipt.name.value}' holds $storedSize bytes, " +
                        "the receipt names ${receipt.size}",
                )
            }
            val digest = Sha256.ofFile(stored)
            if (digest != receipt.blobId.value) {
                return CapturedBytesRefusal(
                    "the digest address of '${receipt.name.value}' holds bytes digesting to $digest, " +
                        "the receipt names ${receipt.blobId.value}",
                )
            }
            return CapturedBytes(Files.readAllBytes(stored))
        }
    }

    /**
     * The production adapter, wrapped so the workspace is rewritten ONCE, right after the
     * capture returns and before any read-back: the exact window the capture→parse law
     * must survive.
     */
    private class MutatingAfterCapture(
        private val delegate: ArchiveArtifactsOperations,
        private val mutationRoot: Path,
        private val mutation: (Path) -> Unit,
    ) : ArchiveArtifactsOperations {
        override fun archive(input: ArchiveArtifactsInput): ArchiveArtifactsResult {
            val result = delegate.archive(input)
            mutation(mutationRoot)
            return result
        }

        override fun openCapture(receipt: CaptureReceipt): CapturedBytesResult = delegate.openCapture(receipt)
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

    /**
     * A receipt the capture port would really answer for the named file: the digest and
     * the size of the bytes the workspace holds under that name, read back the same way
     * the production adapter reads them. A fake that answers with a digest the parsed
     * bytes do not carry is the lie the pairing law refuses, so the fixture cannot tell it.
     */
    private fun receipt(relPath: String) = CaptureReceipt(
        captureId = ArtifactId("capture-$relPath"),
        runId = "run-1",
        stageName = "stage-a",
        name = ArtifactName(relPath),
        blobId = BlobId(Sha256.ofFile(workspace.resolve(relPath))),
        size = Files.size(workspace.resolve(relPath)),
    )

    /** A well-formed receipt carrying an identity of OTHER bytes, for the pairing refusals. */
    private fun receiptNaming(name: String, blobId: BlobId, size: Long) = CaptureReceipt(
        captureId = ArtifactId("capture-$name"),
        runId = "run-1",
        stageName = "stage-a",
        name = ArtifactName(name),
        blobId = blobId,
        size = size,
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
        ArchiveArtifactsSuccess(relPaths.map { receipt(it) }),
        workspace,
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
        assertTrue(ops.inputs.size == 1, "the capture happens first; the verdict reads the capture")
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
    fun `invalid XML is a verdict apart from absent, read from the capture`() {
        // The capture precedes the parse: the refusal is a verdict about the CAPTURED
        // bytes, and the capture itself stays — an internal, truthful record of what the
        // run held when it published.
        Files.createDirectories(workspace.resolve("r"))
        Files.writeString(workspace.resolve("r/broken.xml"), "<testsuite tests=\"abc\"")
        val ops = successOps("r/broken.xml")

        val output = execute(PublishJUnitReportsInput(artifacts = "r/*.xml"), ops)

        val failed = assertInstanceOf(PublishJUnitReportsFailureOutput::class.java, output)
        assertEquals(FailureKind.USER, failed.failureKind)
        assertTrue(failed.message.contains("r/broken.xml"), failed.message)
        assertTrue(ops.inputs.size == 1, "the capture happened; the parse refused the captured bytes")
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
    fun `a report over the declared cap is refused from the capture's size, before any byte is read`() {
        // The cap is the hard limit the schema documents, and it decides from the SIZE THE
        // STORE read back — before the read-back and the parse. The capture happened; the
        // oversized bytes are never parsed.
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
        assertTrue(ops.inputs.size == 1, "the capture happened; the cap refused the captured size")
    }

    @Test
    fun `a report of exactly maxReportBytes is parsed — the cap is over, not at, the cap`() {
        // The cap is inclusive on the report side: a valid report of EXACTLY the declared
        // cap is a report the parser will read. This boundary is the law, not an accident
        // of the comparison.
        Files.createDirectories(workspace.resolve("r"))
        // The padding sits between the declaration and the root: a comment BEFORE the
        // declaration is not XML a parser may accept, and this row is about the cap, not
        // about a malformed document.
        val declaration = surefireXml.substringBefore("\n")
        val rest = surefireXml.substringAfter("\n")
        val pad = 4096 - surefireXml.length - 8 // the seam the split dropped plus "\n<!--"+"-->"+"\n"
        val exact = declaration + "\n<!--" + "x".repeat(pad) + "-->\n" + rest
        assertEquals(4096, exact.length)
        Files.writeString(workspace.resolve("r/exact.xml"), exact)

        val output = execute(
            PublishJUnitReportsInput(artifacts = "r/*.xml", maxReportBytes = 4096),
            successOps("r/exact.xml"),
        )

        val published = assertInstanceOf(PublishedReportsOutput::class.java, output)
        assertEquals(4096L, published.receipts.single().sizeBytes)
        assertEquals(3, published.receipts.single().tests)
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

    @Test
    fun `a receipt whose bytes the store cannot answer fails — never a publication`() {
        // The publication is built FROM the capture: a receipt the store cannot vouch for
        // — an address that holds nothing the receipt names — is an ENGINE failure that
        // names the receipt, never a summary about bytes nobody can produce.
        Files.createDirectories(workspace.resolve("out"))
        Files.writeString(workspace.resolve("out/r.dat"), surefireXml)
        val ops = FakeArchiveOps(
            ArchiveArtifactsSuccess(
                listOf(
                    receiptNaming(
                        "ghost/absent.xml",
                        BlobId(Sha256.ofText(surefireXml)),
                        surefireXml.length.toLong(),
                    ),
                ),
            ),
            workspace,
        )

        val output = execute(PublishJUnitReportsInput(artifacts = "out/r.dat"), ops)

        val failed = assertInstanceOf(PublishJUnitReportsFailureOutput::class.java, output)
        assertEquals(FailureKind.ENGINE, failed.failureKind)
        assertTrue(
            failed.message.contains("ghost/absent.xml"),
            "the failure must name the receipt the store cannot answer, got: ${failed.message}",
        )
    }

    @Test
    fun `a capture that answers without receipts is never published`() {
        // A success naming no receipt would publish "PUBLISHED" about nothing: the capture
        // is the authority, and a capture without receipts names no bytes at all.
        Files.createDirectories(workspace.resolve("r"))
        Files.writeString(workspace.resolve("r/a.xml"), surefireXml)
        val output = execute(
            PublishJUnitReportsInput(artifacts = "r/*.xml"),
            FakeArchiveOps(ArchiveArtifactsSuccess(emptyList()), workspace),
        )

        val failed = assertInstanceOf(PublishJUnitReportsFailureOutput::class.java, output)
        assertEquals(FailureKind.ENGINE, failed.failureKind)
    }

    @Test
    fun `a receipt naming a size the captured bytes do not occupy is never published`() {
        // The read-back verifies the SIZE the receipt names before any byte leaves the
        // store: a receipt whose size lies about its own capture is the failure.
        Files.createDirectories(workspace.resolve("out"))
        Files.writeString(workspace.resolve("out/r.dat"), surefireXml)
        val wrongSize = surefireXml.length.toLong() + 5L
        val ops = FakeArchiveOps(
            ArchiveArtifactsSuccess(
                listOf(
                    receiptNaming(
                        "out/r.dat",
                        BlobId(Sha256.ofFile(workspace.resolve("out/r.dat"))),
                        wrongSize,
                    ),
                ),
            ),
            workspace,
        )

        val output = execute(PublishJUnitReportsInput(artifacts = "out/r.dat"), ops)

        val failed = assertInstanceOf(PublishJUnitReportsFailureOutput::class.java, output)
        assertEquals(FailureKind.ENGINE, failed.failureKind)
        assertTrue(
            failed.message.contains(wrongSize.toString()),
            "the refusal must name the size the receipt wrongly carried, got: ${failed.message}",
        )
    }

    @Test
    fun `a receipt naming bytes the captured report does not carry is never published`() {
        // The receipt's digest must be the identity of the bytes the read-back verifies:
        // a receipt whose digest names other bytes — a diverged capture, a raced source —
        // is the failure, never a published digest the report cannot vouch for.
        Files.createDirectories(workspace.resolve("out"))
        Files.writeString(workspace.resolve("out/r.dat"), surefireXml)
        val otherBytes = BlobId(Sha256.ofText("bytes-the-step-never-parsed"))
        val ops = FakeArchiveOps(
            ArchiveArtifactsSuccess(
                listOf(receiptNaming("out/r.dat", otherBytes, surefireXml.length.toLong())),
            ),
            workspace,
        )

        val output = execute(PublishJUnitReportsInput(artifacts = "out/r.dat"), ops)

        val failed = assertInstanceOf(PublishJUnitReportsFailureOutput::class.java, output)
        assertEquals(FailureKind.ENGINE, failed.failureKind)
        assertTrue(
            failed.message.contains("out/r.dat"),
            "the refusal must name the report whose receipt diverged, got: ${failed.message}",
        )
        assertTrue(
            failed.message.contains(otherBytes.value),
            "the refusal must name the digest the receipt wrongly carried, got: ${failed.message}",
        )
    }

    @Test
    fun `the published summary corresponds exactly to the captured bytes — a report changed after its capture does not alter it`() {
        // THE LAW, and the window it survives: the workspace report is rewritten AFTER the
        // capture returns and BEFORE any read-back. The publication declares the capture's
        // totals, the capture's digest and the capture's size; the live file now holds
        // different bytes and nothing published describes them.
        Files.createDirectories(workspace.resolve("build/test-results"))
        Files.createDirectories(controlRoot)
        val liveFile = workspace.resolve("build/test-results/jvm.xml")
        Files.writeString(liveFile, surefireXml)
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
        val ops = MutatingAfterCapture(adapter, workspace) { root ->
            Files.writeString(root.resolve("build/test-results/jvm.xml"), rewrittenXml)
        }

        val output = execute(PublishJUnitReportsInput(artifacts = "build/test-results/**/*.xml"), ops)

        val published = assertInstanceOf(PublishedReportsOutput::class.java, output)
        val report = published.receipts.single()
        assertEquals(3, report.tests, "the published totals are the CAPTURE's")
        assertEquals(1, report.failures)
        assertEquals(1, report.skipped)
        // The published digest names the captured bytes, and those bytes carry the summary:
        val blob = controlRoot.resolve("artefacts/run-1/stage-a/_blobs").resolve(report.sha256)
        assertTrue(Files.isRegularFile(blob), "the published digest is an address the store holds")
        assertEquals(report.sizeBytes, Files.size(blob), "the published size is the captured size")
        val capturedSummary = JUnitReportParser.parse(Files.newInputStream(blob), "blob")
        assertEquals(report.tests, capturedSummary.tests, "the summary is the parse of the published bytes")
        assertEquals(report.failures, capturedSummary.failures)
        assertEquals(report.skipped, capturedSummary.skipped)
        // And the live file holds DIFFERENT bytes the publication ignores:
        assertNotEquals(report.sha256, Sha256.ofFile(liveFile), "the rewritten workspace bytes are not what was published")
        assertEquals(surefireXml.length.toLong(), report.sizeBytes, "the published size is the capture's size")
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
    fun `real local publication — the summary is the parse of the captured bytes the digest names`() {
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
            // The published digest is the address of the WRITE-ONCE capture, and the parse
            // of exactly those bytes is the summary the publication declares:
            val blob = controlRoot.resolve("artefacts/run-1/stage-a/_blobs").resolve(report.sha256)
            assertTrue(Files.isRegularFile(blob), "the published digest must be a capture address: $blob")
            assertEquals(report.sizeBytes, Files.size(blob), "the published size is the captured size")
            val capturedSummary = JUnitReportParser.parse(Files.newInputStream(blob), report.relPath)
            assertEquals(report.tests, capturedSummary.tests)
            assertEquals(report.failures, capturedSummary.failures)
            assertEquals(report.errors, capturedSummary.errors)
            assertEquals(report.skipped, capturedSummary.skipped)
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
    fun `an invalid report fails the publication after its capture, and the event tells the truth`() {
        // The capture precedes the parse: an invalid report is a USER failure ABOUT the
        // captured bytes, and the capture event the adapter emitted stays truthful — the
        // bytes really were secured, whatever the publication then refused to declare.
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

        val failed = assertInstanceOf(PublishJUnitReportsFailureOutput::class.java, output)
        assertEquals(FailureKind.USER, failed.failureKind)
        assertTrue(failed.message.contains("r/bad.xml"), failed.message)
        assertEquals(1, sink.appended.filterIsInstance<ArtifactArchived>().size, "the capture happened before the parse")
        val retention = controlRoot.resolve("artefacts/run-1/stage-a/_blobs")
        assertTrue(Files.list(retention).use { it.findAny().isPresent }, "the captured bytes are the ones the parse refused")
    }
}
