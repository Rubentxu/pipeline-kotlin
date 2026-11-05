package dev.rubentxu.pipeline.v2.release

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * What the state of a certification cycle holds, and what a change to it does.
 *
 * The guarantee under test is the one the cycle rests on: the state names the
 * candidate, the request and the receipt, and a change that the state already
 * knows moves nothing. A change that contradicts the state is refused instead
 * of applied, because a state that holds a receipt about other bytes is a
 * state nobody can act on.
 *
 * Pure: no journal, no lease, no clock. The values are built by the test.
 */
class CertificationCheckpointTest {

    private val version = ProductVersion.parseOrThrow("0.44.0")

    /** 64 hex, so the candidate identity is a digest and not a name. */
    private val digest = "a".repeat(64)

    /** The digest of a DIFFERENT candidate. */
    private val otherDigest = "b".repeat(64)

    private val instant = Instant.parse("2026-11-05T19:30:00Z")

    private val cycle: CertificationCycleId = requireNotNull(CertificationCycleId.of(version))

    private fun request(
        candidateDigest: String = digest,
        scenarios: List<String> = listOf("alpha", "beta"),
    ): CertificationRequest {
        val outcome = buildCertificationRequest(
            handoff = handoff(candidateDigest),
            catalogue = CertificationBatteryCatalogue(
                id = "harness-certification-battery",
                version = "1",
                scenarios = scenarios,
            ),
            requestedAt = instant,
        )
        return assertInstanceOf(CertificationRequestOutcome.Accepted::class.java, outcome).request
    }

    private fun handoff(candidateDigest: String): CandidateHandoff = CandidateHandoff(
        candidateId = CandidateId.fromDigest(candidateDigest),
        releaseTrain = version,
        candidateSequence = 1,
        productVersion = version,
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
            sha256 = digest,
            builtAt = "2026-11-05T19:30:00Z",
        ),
        sbom = null,
        sdk = null,
        bom = null,
    )

    private fun receipt(
        candidateDigest: String = digest,
        resultId: String = "0123456789abcdef",
    ): CertificationReceipt = CertificationReceipt(
        resultId = resultId,
        candidateId = CandidateId.fromDigest(candidateDigest),
        decision = CertificationDecision.CERTIFIED,
        battery = CertifiedBattery(
            id = "harness-certification-battery",
            version = "1",
            scenarioCount = 2,
        ),
        runs = listOf(
            ScenarioExecution("alpha", "EXECUTED_PASS"),
            ScenarioExecution("beta", "EXECUTED_PASS"),
        ),
    )

    private fun checkpointOf(outcome: CheckpointTransitionOutcome): CertificationCheckpoint {
        val advanced = assertInstanceOf(CheckpointTransitionOutcome.Advanced::class.java, outcome)
        return advanced.checkpoint
    }

    @Test
    fun `the first request creates the state and names the candidate and its digest`() {
        val requested = request()

        val checkpoint = checkpointOf(
            applyCertificationTransition(cycle, null, CertificationTransition.Requested(requested)),
        )

        assertEquals(1, checkpoint.revision)
        assertEquals(CandidateId.fromDigest(digest), checkpoint.candidate)
        assertEquals("sha256:$digest", checkpoint.candidate.canonical)
        assertEquals(requested, checkpoint.request)
        assertNull(checkpoint.receipt)
    }

    @Test
    fun `the same request asked again does not move the state`() {
        val requested = request()
        val first = checkpointOf(
            applyCertificationTransition(cycle, null, CertificationTransition.Requested(requested)),
        )

        val outcome = applyCertificationTransition(
            cycle,
            first,
            CertificationTransition.Requested(requested),
        )

        val unchanged = assertInstanceOf(CheckpointTransitionOutcome.Unchanged::class.java, outcome)
        assertEquals(1, unchanged.checkpoint.revision)
        assertEquals(first, unchanged.checkpoint)
    }

    @Test
    fun `a request re-issued at another instant is the same ask and does not move the state`() {
        // The ask is the candidate, the battery and how many scenarios it holds. The instant the
        // document was written is not part of it: an interrupted agent that asks again is asking
        // the same question, and a state that moved for it would hide that the work was done.
        val first = checkpointOf(
            applyCertificationTransition(cycle, null, CertificationTransition.Requested(request())),
        )
        val reissued = buildCertificationRequest(
            handoff = handoff(digest),
            catalogue = CertificationBatteryCatalogue(
                id = "harness-certification-battery",
                version = "1",
                scenarios = listOf("alpha", "beta"),
            ),
            requestedAt = instant.plusSeconds(60),
        )
        val reissuedRequest = assertInstanceOf(
            CertificationRequestOutcome.Accepted::class.java,
            reissued,
        ).request

        val outcome = applyCertificationTransition(
            cycle,
            first,
            CertificationTransition.Requested(reissuedRequest),
        )

        val unchanged = assertInstanceOf(CheckpointTransitionOutcome.Unchanged::class.java, outcome)
        assertEquals(1, unchanged.checkpoint.revision)
    }

    @Test
    fun `a request for another candidate replaces the candidate and drops the receipt of the old one`() {
        val admitted = checkpointOf(
            applyCertificationTransition(cycle, null, CertificationTransition.Requested(request())),
        )
        val withReceipt = checkpointOf(
            applyCertificationTransition(
                cycle,
                admitted,
                CertificationTransition.Admitted(receipt()),
            ),
        )

        val checkpoint = checkpointOf(
            applyCertificationTransition(
                cycle,
                withReceipt,
                CertificationTransition.Requested(request(candidateDigest = otherDigest)),
            ),
        )

        assertEquals(CandidateId.fromDigest(otherDigest), checkpoint.candidate)
        assertNull(checkpoint.receipt, "a receipt admits bytes, and these are not those bytes")
        assertEquals(3, checkpoint.revision)
    }

    @Test
    fun `a receipt that admits the candidate is held by the state`() {
        val requested = checkpointOf(
            applyCertificationTransition(cycle, null, CertificationTransition.Requested(request())),
        )
        val admitted = receipt()

        val checkpoint = checkpointOf(
            applyCertificationTransition(cycle, requested, CertificationTransition.Admitted(admitted)),
        )

        assertEquals(admitted, checkpoint.receipt)
        assertEquals(2, checkpoint.revision)
    }

    @Test
    fun `a receipt about another candidate is refused and names both digests`() {
        val requested = checkpointOf(
            applyCertificationTransition(cycle, null, CertificationTransition.Requested(request())),
        )

        val outcome = applyCertificationTransition(
            cycle,
            requested,
            CertificationTransition.Admitted(receipt(candidateDigest = otherDigest)),
        )

        val rejected = assertInstanceOf(CheckpointTransitionOutcome.Rejected::class.java, outcome)
        assertTrue(rejected.reason.contains(digest), "the refusal must name the candidate asked for")
        assertTrue(rejected.reason.contains(otherDigest), "the refusal must name the candidate that arrived")
    }

    @Test
    fun `the same receipt recorded twice does not move the state`() {
        val requested = checkpointOf(
            applyCertificationTransition(cycle, null, CertificationTransition.Requested(request())),
        )
        val admitted = checkpointOf(
            applyCertificationTransition(cycle, requested, CertificationTransition.Admitted(receipt())),
        )

        val outcome = applyCertificationTransition(
            cycle,
            admitted,
            CertificationTransition.Admitted(receipt()),
        )

        val unchanged = assertInstanceOf(CheckpointTransitionOutcome.Unchanged::class.java, outcome)
        assertEquals(2, unchanged.checkpoint.revision)
    }

    @Test
    fun `a defect is held against the candidate it was found against`() {
        val requested = checkpointOf(
            applyCertificationTransition(cycle, null, CertificationTransition.Requested(request())),
        )
        val defect = CheckpointDefect("defect-1", CandidateId.fromDigest(otherDigest))

        val checkpoint = checkpointOf(
            applyCertificationTransition(cycle, requested, CertificationTransition.DefectFound(defect)),
        )

        assertEquals(listOf(defect), checkpoint.defects)
        assertEquals(CandidateId.fromDigest(otherDigest), checkpoint.defects.single().candidate)
    }

    @Test
    fun `the same defect reported twice is recorded once`() {
        val requested = checkpointOf(
            applyCertificationTransition(cycle, null, CertificationTransition.Requested(request())),
        )
        val defect = CheckpointDefect("defect-1", CandidateId.fromDigest(digest))
        val withDefect = checkpointOf(
            applyCertificationTransition(cycle, requested, CertificationTransition.DefectFound(defect)),
        )

        val outcome = applyCertificationTransition(
            cycle,
            withDefect,
            CertificationTransition.DefectFound(defect),
        )

        val unchanged = assertInstanceOf(CheckpointTransitionOutcome.Unchanged::class.java, outcome)
        assertEquals(1, unchanged.checkpoint.defects.size)
        assertEquals(2, unchanged.checkpoint.revision)
    }

    @Test
    fun `the state names the candidate, the request, the receipt and the defects when it is read`() {
        val requested = checkpointOf(
            applyCertificationTransition(cycle, null, CertificationTransition.Requested(request())),
        )
        val admitted = checkpointOf(
            applyCertificationTransition(cycle, requested, CertificationTransition.Admitted(receipt())),
        )
        val withDefect = checkpointOf(
            applyCertificationTransition(
                cycle,
                admitted,
                CertificationTransition.DefectFound(CheckpointDefect("defect-1", CandidateId.fromDigest(digest))),
            ),
        )

        val rendered = withDefect.render()

        assertTrue(rendered.contains("sha256:$digest"), "the reading must name the candidate")
        assertTrue(rendered.contains("0123456789abcdef"), "the reading must name the receipt")
        assertTrue(rendered.contains("defect-1"), "the reading must name the defect")
        assertTrue(rendered.contains(cycle.value), "the reading must name the cycle")
    }
}
