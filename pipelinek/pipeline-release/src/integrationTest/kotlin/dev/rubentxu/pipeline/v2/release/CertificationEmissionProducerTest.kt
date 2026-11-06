package dev.rubentxu.pipeline.v2.release

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant

/**
 * The emission of the certification request, and the inbox it lands in.
 *
 * The guarantee under test is that the message lands where the contract says
 * it lands, under the name the contract says, and nowhere else. The unit lane
 * proves that the reader understands the contract and refuses a doctored one;
 * this lane proves that the contract is the thing that DECIDES where the
 * message goes, and not a path somebody repeated in a shell script.
 *
 * It also proves the second half of that: the message of one candidate does
 * not overwrite the message of another, and a second emission of the same
 * candidate does not write the file twice.
 *
 * The lane is the integration one because the guarantee is about a directory:
 * a unit test that only computed a path would not know whether the file
 * arrived. There is no process and no port here, so nothing deeper is
 * needed.
 */
class CertificationEmissionProducerTest {

    @TempDir
    lateinit var temp: Path

    /** The digest of the candidate the emission is about. */
    private val digest = "a".repeat(64)

    /** The digest of ANOTHER candidate: its message belongs beside this one. */
    private val otherDigest = "b".repeat(64)

    private val publishedAt = Instant.parse("2026-11-05T18:04:00Z")

    /** The inbox the receiver observes, resolved by the environment. */
    private val inbox: Path get() = temp.resolve("inbox")

    /** The environment the producer reads. */
    private val environment: Map<String, String>
        get() = mapOf("PIPELINEK_INBOX_DIRECTORY" to inbox.toString())

    private fun handoff(candidateDigest: String = digest): CandidateHandoff = CandidateHandoff(
        candidateId = CandidateId.fromDigest(candidateDigest),
        releaseTrain = ProductVersion.parseOrThrow("0.44.0"),
        candidateSequence = 1,
        productVersion = ProductVersion.parseOrThrow("0.44.0"),
        sourceCommit = "0".repeat(40),
        artifact = CandidateArtifact(
            name = "pipelinek-0.44.0.zip",
            sha256 = candidateDigest,
            size = 4096,
            archiveRoot = "pipelinek-0.44.0",
            implementationVersion = "0.44.0",
        ),
        distributionManifest = CandidateFileRef(
            name = "distribution-manifest.json",
            sha256 = candidateDigest,
            builtAt = "2026-11-05T18:04:00Z",
        ),
        sbom = null,
        sdk = null,
        bom = null,
    )

    private fun requestText(candidateDigest: String = digest): String {
        val outcome = buildCertificationRequest(
            handoff = handoff(candidateDigest),
            catalogue = CertificationBatteryCatalogue(
                id = "harness-certification-battery",
                version = "1",
                scenarios = listOf("alpha", "beta", "gamma"),
            ),
            requestedAt = Instant.parse("2026-11-05T17:00:00Z"),
        )
        val request = assertInstanceOf(CertificationRequestOutcome.Accepted::class.java, outcome).request
        return CertificationRequestCodec.encode(request)
    }

    private fun publicationReceipt(): String = """
        github-release: PUBLISHED tag=v0.44.0-rc1 prerelease=1 assets=6 remote=origin
          signature verified against AAAA (PipelineK release key)
    """.trimIndent()

    /**
     * The contract of this repository, at the path the build handed over.
     *
     * The path arrives with the build instead of being composed here, so the
     * document this witness reads is the very document the build declares as
     * an input of this lane. Two derivations of one path would be two facts,
     * and the build's is the one that decides whether this lane runs again.
     */
    private fun contractPath(): Path {
        val contract = System.getProperty("pipeline.receptionContract")
            ?: error(
                "the test needs the contract of the repository, and the build did not hand it " +
                    "over. A witness that cannot find the document it is about proves nothing.",
            )
        return Paths.get(contract)
    }

    /** The documents of one emission, written where the producer reads them. */
    private fun documents(candidateDigest: String = digest): Triple<Path, Path, Path> {
        val handoffFile = temp.resolve("candidate-handoff.json")
        val requestFile = temp.resolve("certification-request.json")
        val receiptFile = temp.resolve("github-release.txt")
        Files.writeString(handoffFile, CandidateHandoffCodec.encode(handoff(candidateDigest)))
        Files.writeString(requestFile, requestText(candidateDigest))
        Files.writeString(receiptFile, publicationReceipt())
        return Triple(handoffFile, requestFile, receiptFile)
    }

    private fun emit(
        candidateDigest: String = digest,
        contract: Path = contractPath(),
        environment: Map<String, String> = this.environment,
    ): CertificationEmissionOutcome {
        val (handoffFile, requestFile, receiptFile) = documents(candidateDigest)
        return CertificationEmissionProducer.emit(
            handoffPath = handoffFile,
            requestPath = requestFile,
            publicationReceiptPath = receiptFile,
            publishedAt = publishedAt,
            contractPath = contract,
            receiptPath = temp.resolve("rc.certification.requested.txt"),
            environment = environment,
        )
    }

    @Test
    fun `the message lands in the inbox the contract declares, under the name it declares`() {
        val outcome = emit()

        val emitted = assertInstanceOf(CertificationEmissionOutcome.Emitted::class.java, outcome)
        assertEquals(
            inbox.resolve("rc.certification.requested.$digest.json"),
            emitted.eventFile,
            "the message must land where the contract says, and the contract is the only thing " +
                "that says where that is",
        )
        assertTrue(Files.isRegularFile(emitted.eventFile), "the message must be there")
        assertTrue(Files.isRegularFile(emitted.receiptFile), "the receipt of the emission must be there")
        assertEquals(
            listOf("rc.certification.requested.$digest.json"),
            Files.list(inbox).use { files -> files.map { it.fileName.toString() }.sorted().toList() },
            "the inbox must hold the message and nothing else",
        )
    }

    @Test
    fun `the message of another candidate lands beside this one, under its own name`() {
        emit()
        emit(candidateDigest = otherDigest)

        assertEquals(
            listOf(
                "rc.certification.requested.$digest.json",
                "rc.certification.requested.$otherDigest.json",
            ),
            Files.list(inbox).use { files -> files.map { it.fileName.toString() }.sorted().toList() },
            "the inbox holds one message per candidate, and the name tells them apart",
        )
    }

    @Test
    fun `a second emission of the same candidate writes the message once`() {
        emit()

        val outcome = emit()

        assertInstanceOf(CertificationEmissionOutcome.AlreadyEmitted::class.java, outcome)
        assertEquals(
            1,
            Files.list(inbox).use { files -> files.count() },
            "the same question must not leave two messages in the inbox",
        )
    }

    @Test
    fun `a message that cannot land in the inbox is refused, and not a crash`() {
        // The inbox is a FILE, so the directory that should hold the messages
        // cannot be created. A message that cannot land is an outcome of the
        // emission and not a crash of the program.
        val blocked = temp.resolve("blocked-inbox")
        Files.writeString(blocked, "this is a file, not a directory")

        val outcome = emit(
            environment = mapOf("PIPELINEK_INBOX_DIRECTORY" to blocked.toString()),
        )

        val failed = assertInstanceOf(CertificationEmissionOutcome.Failed::class.java, outcome)
        assertTrue(
            failed.reason.contains("could not be written"),
            "the refusal must say that the message could not land, got: ${failed.reason}",
        )
    }

    @Test
    fun `a contract this build does not understand stops the emission`() {        val doctored = temp.resolve("doctored-contract.json")
        Files.writeString(
            doctored,
            Files.readString(contractPath()).replace(
                "\"required\": true",
                "\"required\": false",
            ),
        )

        val outcome = emit(contract = doctored)

        val failed = assertInstanceOf(CertificationEmissionOutcome.Failed::class.java, outcome)
        assertTrue(
            failed.reason.contains("sweep"),
            "the refusal must say what is wrong with the contract, got: ${failed.reason}",
        )
        assertTrue(
            !Files.exists(inbox),
            "a message must not land anywhere when the contract that declares the inbox is refused",
        )
    }
}
