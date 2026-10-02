package dev.rubentxu.pipeline.v2.application

import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * WU-092 / RP6-B G2 — the file adapter, where the interesting laws live.
 *
 * Every row is a way the mechanism could betray an operator: answering a
 * half-written file as a refusal, losing a question because its directory was
 * missing, sharing one file between two questions in the same run, or waiting
 * forever because nobody ever said anything.
 */
@Timeout(60)
class FileInputDecisionsTest {

    private fun request(opId: String = "run-1#0", message: String = "deploy to prod?") = InputRequest(
        opId = opId,
        message = message,
        ok = "Proceed",
        submitter = null,
        id = null,
    )

    private fun answerFile(root: Path, opId: String): Path =
        root.resolve(opId.replace(Regex("[^A-Za-z0-9._-]"), "_")).resolve("response.json")

    @Test
    fun `a proceed is observed and carries its attribution`(@TempDir tempDir: Path) {
        val decisions = FileInputDecisions(tempDir, pollIntervalMs = 10)
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val result = scope.async { decisions.awaitDecision(request(), waitMillis = 5_000L) }
        // The question must be published BEFORE an answer can be written: without
        // this wait the test would pass for the wrong reason.
        val requestFile = tempDir.resolve("run-1_0").resolve("request.json")
        val deadline = System.currentTimeMillis() + 5_000
        while (!Files.exists(requestFile) && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertTrue(Files.exists(requestFile), "the question must be published")
        Files.writeString(
            answerFile(tempDir, "run-1#0"),
            InputAnswerCodec.encode(InputDecision.Proceed("ana", "lgtm")),
        )
        val resolution = runBlocking { result.await() }
        assertEquals(
            InputResolution.Answered(InputDecision.Proceed("ana", "lgtm")),
            resolution,
        )
        scope.cancel()
    }

    @Test
    fun `a half-written answer is not an answer`(@TempDir tempDir: Path) {
        val decisions = FileInputDecisions(tempDir, pollIntervalMs = 10)
        val answer = answerFile(tempDir, "run-1#0")
        Files.createDirectories(answer.parent)

        // Truncated at every prefix of a real answer. None of them may end the wait:
        // this is the law that makes a filesystem-based answer mechanism safe.
        val complete = InputAnswerCodec.encode(InputDecision.Proceed("ana", "lgtm"))
        for (prefix in 1 until complete.length) {
            Files.writeString(answer, complete.substring(0, prefix))
            val resolution = runBlocking { decisions.awaitDecision(request(), waitMillis = 120L) }
            assertTrue(
                resolution is InputResolution.Denied && resolution.reason is InputDenialReason.TimedOut,
                "prefix of length $prefix must NOT be read as an answer, got $resolution",
            )
        }
    }

    @Test
    fun `a complete answer after a malformed one is still observed`(@TempDir tempDir: Path) {
        val decisions = FileInputDecisions(tempDir, pollIntervalMs = 10)
        val answer = answerFile(tempDir, "run-1#0")
        Files.createDirectories(answer.parent)
        Files.writeString(answer, """{"decision":"PRO""")   // half-written
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val result = scope.async { decisions.awaitDecision(request(), waitMillis = 10_000L) }
        Thread.sleep(200)                                          // it must still be waiting
        Files.writeString(answer, InputAnswerCodec.encode(InputDecision.Abort("ana", "no")))
        val resolution = runBlocking { result.await() }
        assertEquals(InputResolution.Answered(InputDecision.Abort("ana", "no")), resolution)
        scope.cancel()
    }

    @Test
    fun `an unrecognised decision is not an answer either`(@TempDir tempDir: Path) {
        val decisions = FileInputDecisions(tempDir, pollIntervalMs = 10)
        val answer = answerFile(tempDir, "run-1#0")
        Files.createDirectories(answer.parent)
        Files.writeString(answer, """{"decision":"MAYBE","message":"hmm"}""")
        val resolution = runBlocking { decisions.awaitDecision(request(), waitMillis = 150L) }
        assertTrue(
            resolution is InputResolution.Denied && resolution.reason is InputDenialReason.TimedOut,
            "an unknown decision must not be read as an answer, got $resolution",
        )
    }

    @Test
    fun `an expired bound denies with the time actually waited`(@TempDir tempDir: Path) {
        val decisions = FileInputDecisions(tempDir, pollIntervalMs = 10)
        val started = System.currentTimeMillis()
        val resolution = runBlocking { decisions.awaitDecision(request(), waitMillis = 120L) }
        val elapsed = System.currentTimeMillis() - started
        val reason = (resolution as InputResolution.Denied).reason
        assertTrue(reason is InputDenialReason.TimedOut, "got $reason")
        val timedOut = reason as InputDenialReason.TimedOut
        assertTrue(
            timedOut.waitedMillis >= 100L,
            "the denial must report the time waited, not a constant: ${timedOut.waitedMillis}",
        )
        assertTrue(elapsed < 5_000L, "the bound must actually stop the wait, took ${elapsed}ms")
    }

    @Test
    fun `two questions in one run do not share a file`(@TempDir tempDir: Path) {
        val decisions = FileInputDecisions(tempDir, pollIntervalMs = 10)
        val first = answerFile(tempDir, "run-1#0")
        Files.createDirectories(first.parent)
        // An answer to the SECOND question must not satisfy the FIRST.
        Files.writeString(first, InputAnswerCodec.encode(InputDecision.Abort("ana", "for #1 only")))
        Files.createDirectories(answerFile(tempDir, "run-1#1").parent)
        Files.writeString(
            answerFile(tempDir, "run-1#1"),
            InputAnswerCodec.encode(InputDecision.Proceed("ana", null)),
        )
        assertEquals(
            InputResolution.Answered(InputDecision.Abort("ana", "for #1 only")),
            runBlocking { decisions.awaitDecision(request(opId = "run-1#0"), waitMillis = 2_000L) },
        )
        assertEquals(
            InputResolution.Answered(InputDecision.Proceed("ana", null)),
            runBlocking {
                decisions.awaitDecision(request(opId = "run-1#1", message = "second question"), waitMillis = 2_000L)
            },
        )
    }

    @Test
    fun `a hostile op id cannot address another question directory`(@TempDir tempDir: Path) {
        val decisions = FileInputDecisions(tempDir, pollIntervalMs = 10)
        // A path separator in the op id must be neutralised, or a question could be
        // answered by writing into somebody else's directory.
        val resolution = runBlocking {
            decisions.awaitDecision(
                request(opId = "../../escape"),
                waitMillis = 150L,
            )
        }
        assertTrue(resolution is InputResolution.Denied, "a traversal op id must not resolve, got $resolution")
        assertNull(
            tempDir.resolve("..").resolve("..").resolve("escape").takeIf { Files.exists(it) },
            "nothing may be written outside the root",
        )
    }

    @Test
    fun `an unpublishable question is a typed denial, not an exception`(@TempDir tempDir: Path) {
        // A file where the root should be: the question can never be asked, and the
        // operator needs to read WHY.
        val blocker = tempDir.resolve("blocked-root")
        Files.writeString(blocker, "not a directory")
        val decisions = FileInputDecisions(blocker, pollIntervalMs = 10)
        val failure = runCatching {
            runBlocking { decisions.awaitDecision(request(), waitMillis = 1_000L) }
        }.exceptionOrNull()
        assertTrue(failure is UnanswerableException, "got $failure")
        assertNotNull(failure!!.message)
    }

    @Test
    fun `cancellation propagates instead of becoming a business value`(@TempDir tempDir: Path) {
        // The law, and the one this adapter got wrong on the first attempt: a
        // cancelled coroutine must not be converted into Denied(Cancelled). The
        // promise it would complete is already cancelled, so the "typed" result
        // would be discarded anyway — flattening it only hides the stop signal.
        val decisions = FileInputDecisions(tempDir, pollIntervalMs = 20)
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val result = scope.async { decisions.awaitDecision(request(), waitMillis = null) }
        Thread.sleep(150)
        scope.cancel()
        val thrown = runCatching { runBlocking { result.await() } }.exceptionOrNull()
        assertTrue(
            thrown is CancellationException,
            "a cancelled wait must propagate cancellation, got $thrown",
        )
        assertTrue(
            result.isCancelled,
            "the caller must be able to stop: the wait cannot hand it a value to continue with",
        )
    }
}
