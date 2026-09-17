package dev.rubentxu.pipeline.v2.events.evidence

import dev.rubentxu.pipeline.v2.domain.OperationId
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.ExecutedInvocationEvidence
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationOutput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * XCA-2B — reader contract tests against [InMemoryOperationJournal].
 *
 * These tests exercise the READER against a journal store (B.2), not the store itself.
 * The journal is pre-populated with the exact two Steps that XCA-2B's real run produced
 * (core.echo + core.sh, both SUCCEEDED) plus a RUNNING row to catch the `isTerminal`
 * shortcut regression (B.2 falsification requirement).
 *
 * ## Key property
 *
 * `isObserved = status != PENDING` — not `status.isTerminal`.
 * `RUNNING` is NOT terminal, but it IS observed because execution started.
 * `status.isTerminal` would return false for RUNNING, making it look un-executed.
 * The falsification below proves this.
 */
@Timeout(30)
class JournalRunExecutionEvidenceReaderInMemoryTest {

    private val runId = RunId("test-run-b2")
    private val fixedClock: Clock = object : Clock {
        private var counter = 1_000_000L
        override fun now() = java.time.Instant.ofEpochMilli(counter++)
    }

    private fun makeJournal(): InMemoryOperationJournal {
        return InMemoryOperationJournal(
            fixedClock,
            Json { ignoreUnknownKeys = true; encodeDefaults = true }
        )
    }

    private fun appendSucceded(
        journal: InMemoryOperationJournal,
        opId: String,
        stepId: String,
    ) {
        journal.append(
            RerunOperation(
                id = opId,
                fingerprint = Fingerprint("a".repeat(64)),
                input = OperationInput(stepId, mapOf(), runId.value, 1),
                output = OperationOutput(JsonPrimitive("ok"), 50L, System.currentTimeMillis()),
                status = OperationStatus.SUCCEEDED,
                attempt = 1,
            )
        )
    }

    private fun appendRunning(
        journal: InMemoryOperationJournal,
        opId: String,
        stepId: String,
    ) {
        journal.append(
            RerunOperation(
                id = opId,
                fingerprint = Fingerprint("b".repeat(64)),
                input = OperationInput(stepId, mapOf(), runId.value, 1),
                output = null,
                status = OperationStatus.RUNNING,
                attempt = 1,
            )
        )
    }

    private fun appendFailed(
        journal: InMemoryOperationJournal,
        opId: String,
        stepId: String,
    ) {
        journal.append(
            RerunOperation(
                id = opId,
                fingerprint = Fingerprint("c".repeat(64)),
                input = OperationInput(stepId, mapOf(), runId.value, 1),
                output = null,
                status = OperationStatus.FAILED,
                attempt = 1,
            )
        )
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // B.2 — reader returns Found with observed StepKeys {core.echo, core.sh}
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `reader returns Found for a run that executed two Steps`() {
        val journal = makeJournal()
        appendSucceded(journal, "run-b2-s0-0", "core.echo")
        appendSucceded(journal, "run-b2-s1-0", "core.sh")

        val reader = JournalRunExecutionEvidenceReader(journal)
        val result = reader.read(runId)

        assertInstanceOf(
            dev.rubentxu.pipeline.v2.domain.durable.RunEvidenceReadResult.Found::class.java,
            result,
        )
        val found = result as dev.rubentxu.pipeline.v2.domain.durable.RunEvidenceReadResult.Found
        assertEquals(2, found.invocations.size)
    }

    @Test
    fun `observed StepKeys are core-echo and core-sh from the real run`() {
        // Matches XCA-2B's real run: 006df865-a1d4-4bcf-ac8c-895c0864ea60
        // with core.echo + core.sh both SUCCEEDED.
        val journal = makeJournal()
        appendSucceded(journal, "run-b2-s0-0", "core.echo")
        appendSucceded(journal, "run-b2-s1-0", "core.sh")

        val reader = JournalRunExecutionEvidenceReader(journal)
        val result = reader.read(runId)

        val found = result as dev.rubentxu.pipeline.v2.domain.durable.RunEvidenceReadResult.Found
        val observed = found.invocations.filter { it.isObserved }

        assertEquals(
            setOf(PluginStepId("core.echo"), PluginStepId("core.sh")),
            observed.map { it.stepKey }.toSet(),
        )
    }

    @Test
    fun `all SUCCEEDED invocations are observed`() {
        val journal = makeJournal()
        appendSucceded(journal, "run-b2-s0-0", "core.echo")
        appendSucceded(journal, "run-b2-s1-0", "core.sh")

        val reader = JournalRunExecutionEvidenceReader(journal)
        val result = reader.read(runId) as dev.rubentxu.pipeline.v2.domain.durable.RunEvidenceReadResult.Found

        result.invocations.forEach { inv ->
            assertTrue(inv.isObserved, "$inv must be observed (SUCCEEDED != PENDING)")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // B.3 — FAILED Step counts as observed
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a FAILED Step is still observed`() {
        val journal = makeJournal()
        appendFailed(journal, "run-b2-s0-0", "core.sh")

        val reader = JournalRunExecutionEvidenceReader(journal)
        val result = reader.read(runId) as dev.rubentxu.pipeline.v2.domain.durable.RunEvidenceReadResult.Found

        assertEquals(1, result.invocations.size)
        assertTrue(result.invocations[0].isObserved)
        assertEquals(OperationStatus.FAILED, result.invocations[0].status)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // B.2 FALSIFICATION — the isObserved shortcut `status.isTerminal` would FAIL
    //
    // `isObserved = status != PENDING` (correct law) vs `isObserved = status.isTerminal`
    // (tempting shortcut). The shortcut fails for RUNNING because:
    //   SUCCEEDED.isTerminal = true  -> both agree (GREEN)
    //   RUNNING.isTerminal  = false  -> shortcut gives FALSE, law gives TRUE (RED)
    //
    // The test below uses RUNNING to catch the shortcut. A test using only terminal
    // states (SUCCEEDED/FAILED) would pass with both implementations — it would not
    // falsify the shortcut.
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `RUNNING is observed — catches the status-isTerminal shortcut regression`() {
        val journal = makeJournal()
        // core.pwd is RUNNING: execution started but not yet terminal.
        // Law: isObserved = status != PENDING  -> true
        // Shortcut: isObserved = status.isTerminal -> false  (RED)
        appendRunning(journal, "run-b2-s0-0", "core.pwd")

        val reader = JournalRunExecutionEvidenceReader(journal)
        val result = reader.read(runId) as dev.rubentxu.pipeline.v2.domain.durable.RunEvidenceReadResult.Found

        assertEquals(1, result.invocations.size)
        val inv = result.invocations[0]
        assertEquals(PluginStepId("core.pwd"), inv.stepKey)
        assertEquals(OperationStatus.RUNNING, inv.status)
        assertFalse(OperationStatus.RUNNING.isTerminal, "RUNNING must NOT be terminal")
        // This assertion is the guard against the shortcut:
        assertTrue(inv.isObserved, "RUNNING proves execution started — must be observed")
    }

    @Test
    fun `the shortcut status-isTerminal would drop RUNNING from observed`() {
        // Proves that the shortcut breaks the RUNNING case.
        // This is documentation-of-the-falsification, not a second test assertion —
        // the actual guard is the test above.  Here we explicitly compute what the
        // shortcut would give vs what the law gives, so the regression is visible
        // without needing to mutate the actual source.
        val runningStatus = OperationStatus.RUNNING

        val lawResult = runningStatus != OperationStatus.PENDING   // true
        val shortcutResult = runningStatus.isTerminal              // false

        assertTrue(lawResult, "law: RUNNING != PENDING -> true")
        assertFalse(shortcutResult, "shortcut: RUNNING.isTerminal -> false")
        assertTrue(
            lawResult && !shortcutResult,
            "the shortcut produces a DIFFERENT result for RUNNING — " +
                "a test using only SUCCEEDED would pass with both and hide the regression",
        )
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Ordering: canonical execution order (created_at ASC, per listForRun contract)
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `invocations are returned in canonical durable execution order`() {
        val journal = makeJournal()
        appendSucceded(journal, "run-b2-s0-0", "core.echo")
        appendSucceded(journal, "run-b2-s1-0", "core.sh")
        appendSucceded(journal, "run-b2-s2-0", "core.pwd")

        val reader = JournalRunExecutionEvidenceReader(journal)
        val result = reader.read(runId) as dev.rubentxu.pipeline.v2.domain.durable.RunEvidenceReadResult.Found

        val stepKeys = result.invocations.map { it.stepKey.value }
        assertEquals(listOf("core.echo", "core.sh", "core.pwd"), stepKeys)
    }
}
