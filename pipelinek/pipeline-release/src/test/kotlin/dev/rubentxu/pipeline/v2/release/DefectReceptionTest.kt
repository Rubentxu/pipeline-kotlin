package dev.rubentxu.pipeline.v2.release

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The reception of a defect report, and the guarantees it may take.
 *
 * Pure: no filesystem, no clock, no board of its own. The board is given, and
 * the moment the report declares is supplied, not read.
 */
class DefectReceptionTest {

    private val candidate = CandidateId.fromDigest("a".repeat(64))

    private val otherCandidate = CandidateId.fromDigest("b".repeat(64))

    /**
     * A board that holds the tasks it is given, in memory.
     *
     * It holds them by the LABELS the model composed, so it re-derives no
     * rule: a task is found because the task that was opened carries the
     * label, not because this double knows how a defect is labelled.
     */
    private class InMemoryBoard : DefectTaskBoard {
        val opened = mutableListOf<DefectTaskSpec>()

        override fun taskHolding(label: String): String? =
            opened.indexOfFirst { it.labels.contains(label) }
                .takeIf { it >= 0 }
                ?.let { "TASK-${it + 1}" }

        override fun openTask(spec: DefectTaskSpec): String {
            opened += spec
            return "TASK-${opened.size}"
        }
    }

    private fun event(
        against: CandidateId = candidate,
        blocksRelease: Boolean = true,
    ): DefectReportedEvent = DefectReportedEvent(
        protocolVersion = DefectReportedEvent.PROTOCOL_VERSION,
        candidateId = against,
        defect = DefectFingerprint(
            contract = "contract.alpha",
            scenario = "scenario.beta",
            failureType = DefectFailureType.REPRODUCIBLE_DEFECT,
            signature = "sig-123",
        ),
        evidence = DefectEvidence(
            productVersion = ProductVersion.parseOrThrow("0.44.0"),
            productCommit = "0".repeat(40),
            harnessRevision = "abc123",
            commands = listOf("pipelinek run scenario.beta"),
            expected = "the scenario passes",
            observed = "the scenario failed",
            logs = listOf("line one"),
            severity = DefectSeverity.HIGH,
            blocksRelease = blocksRelease,
        ),
        observedAt = WireInstant.of(Instant.parse("2026-11-05T18:04:00Z")),
    )

    /**
     * What the entry point does, in one place: ask the board, ask the model,
     * and open the task the model produced.
     */
    private fun receive(
        board: DefectTaskBoard,
        event: DefectReportedEvent,
        current: CandidateId,
    ): DefectReception {
        val identity = DefectIdentity.of(event.defect)
        val held = board.taskHolding(identity.label)?.let { DefectTaskHeld(it) }
        val reception = defectReception(
            event = event,
            identity = identity,
            currentCandidate = current,
            alreadyHeld = held,
            milestone = "m-44",
            documentReference = "/tmp/defect.reported.json",
        )
        when (reception) {
            is DefectReception.Opened -> board.openTask(reception.spec)
            is DefectReception.KeptApart -> board.openTask(reception.spec)
            is DefectReception.AlreadyReceived -> Unit
        }
        return reception
    }

    @Test
    fun `a new defect against the candidate in course opens one task`() {
        val board = InMemoryBoard()

        val reception = receive(board, event(), candidate)

        val opened = assertInstanceOf(DefectReception.Opened::class.java, reception)
        assertEquals(1, board.opened.size)
        assertTrue(opened.spec.labels.contains("defect:0453d93fd214"))
        assertTrue(opened.spec.labels.contains("candidato:aaaaaaaaaaaa"))
        assertTrue(opened.spec.labels.contains("rc-certification"))
        assertEquals("m-44", opened.spec.milestone)
    }

    @Test
    fun `the task carries the candidate, the scenario, what was seen and when`() {
        val board = InMemoryBoard()

        val reception = receive(board, event(), candidate)

        val opened = assertInstanceOf(DefectReception.Opened::class.java, reception)
        val description = opened.spec.description
        assertTrue(description.contains(candidate.canonical))
        assertTrue(description.contains("scenario.beta"))
        assertTrue(description.contains("the scenario failed"))
        assertTrue(description.contains("2026-11-05T18:04:00Z"))
        assertTrue(opened.spec.title.contains("scenario.beta"))
        assertTrue(opened.spec.title.contains("aaaaaaaaaaaa"))
    }

    @Test
    fun `the same defect received twice leaves one task`() {
        val board = InMemoryBoard()

        receive(board, event(), candidate)
        val second = receive(board, event(), candidate)

        val already = assertInstanceOf(DefectReception.AlreadyReceived::class.java, second)
        assertEquals("TASK-1", already.taskId)
        assertEquals(1, board.opened.size)
    }

    @Test
    fun `a defect of another candidate is kept apart from the cycle in course`() {
        val board = InMemoryBoard()

        val reception = receive(board, event(against = otherCandidate), candidate)

        val apart = assertInstanceOf(DefectReception.KeptApart::class.java, reception)
        assertEquals(1, board.opened.size)
        assertTrue(apart.spec.labels.contains("candidato:bbbbbbbbbbbb"))
        assertFalse(apart.spec.labels.contains("candidato:aaaaaaaaaaaa"))
        assertTrue(apart.spec.title.contains("otro ciclo"))
        assertTrue(apart.spec.description.contains("candidato distinto"))
    }

    @Test
    fun `the identity of a defect does not change with the candidate`() {
        val here = DefectIdentity.of(event().defect)
        val there = DefectIdentity.of(event(against = otherCandidate).defect)

        assertEquals(here, there)
    }

    @Test
    fun `the identity is the hash the harness computes over the same text`() {
        val identity = DefectIdentity.of(event().defect)

        // The SHA-256 of
        // "contract=contract.alpha\x1Fscenario=scenario.beta\x1F
        //  failure_type=REPRODUCIBLE_DEFECT\x1Fsignature=sig-123",
        // cut to twelve hex characters. It is the same string the harness
        // canonicalises, so both sides name the same defect the same way.
        assertEquals("0453d93fd214", identity.canonical)
        assertEquals("defect:0453d93fd214", identity.label)
    }

    @Test
    fun `a defect that blocks the release is worked before one that does not`() {
        val board = InMemoryBoard()

        val blocking = assertInstanceOf(
            DefectReception.Opened::class.java,
            receive(board, event(), candidate),
        )
        val other = assertInstanceOf(
            DefectReception.Opened::class.java,
            receive(InMemoryBoard(), event(blocksRelease = false), candidate),
        )

        assertEquals("high", blocking.spec.priority)
        assertEquals("medium", other.spec.priority)
    }
}
