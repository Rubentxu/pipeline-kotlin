package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * F-B1.3-001: a control journal that cannot name a persisted status refuses with its OWN
 * divergence type, carrying the file and the value — not with a raw `IllegalArgumentException`
 * thrown by `Enum.valueOf`.
 *
 * ## What this holds, and why it is not a style preference
 *
 * Both durable control journals — the retry one (ADR-0075) and the waitUntil one — persist each
 * attempt's [OperationStatus] and read it back with the same shape:
 *
 * ```kotlin
 * status = ao["status"]?.jsonPrimitive?.content
 *     ?.let { OperationStatus.valueOf(it) }
 *     ?: throw RetryControlJournalDivergenceException("status missing in $file")
 * ```
 *
 * The `?:` handles a **missing** field, which is refused correctly. The `?.let` handles a field
 * that is **present but unrecognised**, and it does so by letting `Enum.valueOf` throw
 * `IllegalArgumentException`. The enclosing `catch` only catches `SerializationException`, so that
 * exception escapes the parser's declared vocabulary entirely.
 *
 * Three consequences, in increasing severity:
 *
 * 1. **The declared contract is false for exactly one field.** Every other malformed field raises
 *    the journal's own `…DivergenceException`. This one does not, so a caller that catches the
 *    declared type does not catch it and recovery written against the contract does not recover.
 * 2. **The diagnostic loses its evidence.** `Enum.valueOf`'s message names the bad token but not
 *    the control row, so an operator with a retry and a waitUntil in flight cannot tell which
 *    durable file is unreadable. The file name is a SHA-256 of the controlOpId, so it is not
 *    guessable from the message either.
 * 3. **The refusal has no owner.** Fail-closed only means something when the caller recognises the
 *    refusal. An unexpected exception type is a refusal routed to whatever generic handler exists,
 *    and the planner's contract is specifically that a control row it cannot read is a divergence
 *    and never a re-schedule. An unreadable row that arrives as an unrecognised exception cannot
 *    be distinguished from an infrastructure fault by the code that owns the loop.
 *
 * ## Harness fidelity
 *
 * HF1 (in-process), crossing the production authority. This writes a real control file through the
 * journal's own `beginAttempt`, corrupts only the persisted status token, and reads back through
 * the journal's own `beginAttempt` (which calls `readFile`). It does not reimplement the parser and
 * does not mock the journal. The file name is derived with the same SHA-256 rule the journal uses,
 * so the fixture locates the real file rather than assuming a layout.
 *
 * Temp directories come from JUnit's `@TempDir` — never `Files.createTempDirectory`, which leaks a
 * directory per row per run into `java.io.tmpdir` and makes the suite itself a resource leak.
 * No clock, no process, no network, no shared mutable singleton.
 *
 * RED: `read` raises `IllegalArgumentException`, so the type assertion fails.
 * GREEN: the journal's own divergence type carries the file and the offending token.
 */
@DisplayName("F-B1.3-001 · a control journal refuses an unknown status in its own vocabulary")
class ControlJournalUnknownStatusTest {

    /** The journal names files by SHA-256 of the controlOpId; the fixture must use the same rule. */
    private fun controlFile(root: Path, subdir: String, controlOpId: String): Path {
        val key = MessageDigest.getInstance("SHA-256")
            .digest(controlOpId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return root.resolve(subdir).resolve("$key.attempts.json")
    }

    /** Corrupt ONLY the status token, and assert the corruption actually landed. */
    private fun corruptStatus(file: Path, from: String, to: String) {
        assertTrue(Files.isRegularFile(file), "the journal must have written $file before it can be corrupted")
        val corrupted = Files.readString(file).replace("\"$from\"", "\"$to\"")
        assertTrue(
            corrupted.contains("\"$to\""),
            "NON-VACUITY: the fixture did not actually corrupt the row at $file; the parser would " +
                "have read a valid status and this row would prove nothing.",
        )
        Files.writeString(file, corrupted)
    }

    @Test
    fun `the retry control journal refuses an unknown status with its own divergence type`(
        @TempDir root: Path,
    ) {
        val journal = FileBasedRetryControlJournal(root)
        val opId = "op-retry-unknown-status"
        val fp = Fingerprint("f".repeat(64))
        journal.beginAttempt(controlOpId = opId, attempt = 1, fingerprint = fp, status = OperationStatus.RUNNING)

        corruptStatus(controlFile(root, "retry-control", opId), "RUNNING", "PAUSED_BY_OPERATOR")

        val failure = assertThrows(RetryControlJournalDivergenceException::class.java) {
            journal.readState(
                controlOpId = opId,
                runId = "run-1",
                stageIndex = 0,
                stepIndex = 0,
                parentBodyPath = emptyList(),
                maxAttempts = 3,
                currentFingerprint = fp,
            )
        }
        assertTrue(
            failure.message.orEmpty().contains("PAUSED_BY_OPERATOR"),
            "The refusal must name the value it could not read, or the operator cannot diagnose it. " +
                "Was: ${failure.message}",
        )
    }

    @Test
    fun `the waitUntil control journal refuses an unknown status with its own divergence type`(
        @TempDir root: Path,
    ) {
        val journal = FileBasedWaitUntilControlJournal(root)
        val opId = "op-wait-unknown-status"
        val fp = Fingerprint("f".repeat(64))
        journal.beginAttempt(
            controlOpId = opId,
            attempt = 1,
            currentBackoffMs = 0L,
            fingerprint = fp,
            status = OperationStatus.RUNNING,
        )

        corruptStatus(controlFile(root, "wait-until-control", opId), "RUNNING", "PAUSED_BY_OPERATOR")

        val failure = assertThrows(WaitUntilControlJournalDivergenceException::class.java) {
            journal.readState(
                controlOpId = opId,
                initialRecurrencePeriodMs = 1000L,
                maxBackoffMs = 60_000L,
                currentFingerprint = fp,
            )
        }
        assertTrue(
            failure.message.orEmpty().contains("PAUSED_BY_OPERATOR"),
            "The refusal must name the value it could not read. Was: ${failure.message}",
        )
    }

    @Test
    fun `every status the enum defines still reads back, so strictness is not the fix`(
        @TempDir root: Path,
    ) {
        OperationStatus.entries.forEach { status ->
            val opId = "op-status-${status.name}"
            val fp = Fingerprint("f".repeat(64))
            val journal = FileBasedRetryControlJournal(root.resolve(status.name))
            journal.beginAttempt(controlOpId = opId, attempt = 1, fingerprint = fp, status = status)

            val state = journal.readState(
                controlOpId = opId,
                runId = "run-1",
                stageIndex = 0,
                stepIndex = 0,
                parentBodyPath = emptyList(),
                maxAttempts = 3,
                currentFingerprint = fp,
            )

            assertEquals(
                status,
                state.controlRows.first().status,
                "${status.name} is a defined status and must keep reading. A parser that refuses the " +
                    "real values is broken, not strict.",
            )
        }
    }
}
