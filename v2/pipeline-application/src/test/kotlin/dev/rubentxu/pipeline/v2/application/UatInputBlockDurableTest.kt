package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

/**
 * RP6-B / WU-092 input laws, against the REAL distribution (HF2 forked CLI),
 * sharing one `--db` + `--control-root` so the answer channel is the same one an
 * operator would inspect: `<controlDirRoot>/inputs/<runId#stepIndex>/request.json`.
 *
 * `FileInputDecisionsTest` proves the channel as a component; this file proves the
 * PRODUCTION ROUTING — that the compiled `.pipeline.kts` reaches the registry, the
 * handler, the adapter and the durable spine with no manual wiring.
 *
 * Laws under test (SPEC_WU092_INPUT.md §7.7):
 *  1. WI-L1 a `PROCEED` answer runs the body and finishes green;
 *  2. WI-L2 an `ABORT` fails the run, emits InputAborted and emits NO InputProceed;
 *  3. WI-L3 a malformed answer does NOT end the wait — a later valid one does;
 *  4. WI-L4 the FIRST answer wins; a second write does not overwrite a decision
 *           already taken (the load-bearing half of `CREATE_NEW`);
 *  5. WI-L5 `timeoutSeconds` bounds the wait and denies with TIMED_OUT;
 *  6. WI-L6 an enclosing `timeout` cancels an indefinite waiter, body untouched;
 *  7. WI-L7 an abort runs neither the body nor anything after the question;
 *  8. WI-L8 a rerun of a decided block does NOT ask again (MEMOIZED, D3).
 *
 * Plus WI-L9, the law every other row stands on: two `input` steps in one run are
 * two questions in two directories. A shared channel would let the second step read
 * the first step's answer, and the run would deploy on a permission granted to a
 * different decision.
 */
@Timeout(420)
class UatInputBlockDurableTest {
    private val processes = mutableListOf<Process>()

    @AfterEach
    fun terminateProcesses() {
        processes.forEach { process ->
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
        processes.clear()
    }

    /** A run started in the background, together with the log it writes to. */
    private data class AsyncRun(val process: Process, val log: Path) {
        fun isAlive(): Boolean = process.isAlive
        fun await(timeoutSeconds: Long = 150): CliResult {
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
            }
            val text = Files.readString(log)
            if (!finished) error("run did not finish: $text")
            return CliResult(process.exitValue(), text)
        }
    }

    private data class CliResult(val exitCode: Int, val output: String)

    private fun startRun(workdir: Path, args: List<String>): AsyncRun {
        val log = Files.createTempFile(workdir, "pipeline-cli-", ".log")
        val process = ProcessBuilder(
            System.getProperty("java.home") + "/bin/java",
            "-cp",
            System.getProperty("java.class.path"),
            "dev.rubentxu.pipeline.v2.application.MainKt",
            *args.toTypedArray(),
        )
            .directory(workdir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start()
            .also { processes.add(it) }
        return AsyncRun(process, log)
    }

    private fun runCli(workdir: Path, args: List<String>): CliResult =
        startRun(workdir, args).await()

    private fun invocation(tempDir: Path, script: Path) = listOf(
        "run", "--format", "json",
        "--db", tempDir.resolve("journal.db").toString(),
        "--control-root", tempDir.resolve("control").toString(),
        script.toString(),
    )

    private fun markerLines(marker: Path): List<String> =
        if (Files.exists(marker)) Files.readAllLines(marker).filter { it.isNotBlank() } else emptyList()

    private fun inputsRoot(tempDir: Path): Path = tempDir.resolve("control").resolve("inputs")

    private fun questionDirs(tempDir: Path): List<Path> {
        val root = inputsRoot(tempDir)
        if (!Files.exists(root)) return emptyList()
        val dirs = mutableListOf<Path>()
        Files.newDirectoryStream(root).use { stream ->
            stream.forEach { if (Files.isDirectory(it)) dirs.add(it) }
        }
        return dirs
    }

    /**
     * Waits for `count` published questions. The op id is `runId#stepIndex` and the run
     * id is not knowable from the test, so the test discovers the channel the way an
     * operator does: by looking for `request.json` on disk. Discovering it is also part
     * of what is under test — if the channel lived anywhere else, every row here would
     * hang rather than quietly pass.
     */

    /**
     * Waits for the next UNANSWERED question.
     *
     * "Unanswered" is load-bearing, not a convenience: the op id is `runId#stepIndex`,
     * so a second `input` in the same run is a SECOND directory rather than a second
     * file in the first one. Returning "any question" would hand back the already
     * answered first one and the test would deadlock on its own bookkeeping.
     */
    private fun awaitQuestion(tempDir: Path, timeoutMs: Long = 45_000): Path {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val pending = questionDirs(tempDir).filter {
                Files.exists(it.resolve("request.json")) && !Files.exists(it.resolve("response.json"))
            }
            if (pending.isNotEmpty()) return pending.first()
            Thread.sleep(50)
        }
        error(
            "no unanswered question appeared under ${inputsRoot(tempDir)}; " +
                "questions on disk: ${questionDirs(tempDir).map { Files.readString(it.resolve("request.json")) }}",
        )
    }

    /**
     * Writes an answer the way a careful operator does: into a sibling temp file, then
     * moved into place, so the reader never observes a half-written file. `atomic =
     * false` is used ONLY by the row that deliberately writes a broken answer.
     */
    private fun answer(question: Path, raw: String, atomic: Boolean = true) {
        val response = question.resolve("response.json")
        if (!atomic) {
            Files.writeString(response, raw)
            return
        }
        val staging = question.resolve("response.json.partial")
        Files.writeString(staging, raw)
        Files.move(staging, response, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun proceed(submitter: String = "release-team") =
        """{"decision":"PROCEED","submitter":"$submitter"}"""

    private fun abort(submitter: String = "release-team") =
        """{"decision":"ABORT","submitter":"$submitter","message":"not this time"}"""

    private fun script(tempDir: Path, name: String, body: String): Path {
        val file = tempDir.resolve(name)
        Files.writeString(file, body.trimIndent())
        return file
    }

    // ── WI-L1 ────────────────────────────────────────────────────────────────

    @Test
    fun `WI-L1 a PROCEED answer runs the body and finishes green`(@TempDir tempDir: Path) {
        val marker = tempDir.resolve("marker.txt")
        val file = script(
            tempDir,
            "input-proceed.kts",
            """
            pipeline {
                stages {
                    stage("deploy") {
                        input("Deploy to production?", submitter = "release-team", id = "deploy-1") {
                            sh("echo body-ran >> '$marker'")
                        }
                    }
                }
            }
            """,
        )
        val run = startRun(tempDir, invocation(tempDir, file))
        val question = awaitQuestion(tempDir)

        // The published question is what the operator is asked, so its content is
        // observable surface, not an internal detail.
        val request = Files.readString(question.resolve("request.json"))
        assertTrue(
            request.contains("\"message\":\"Deploy to production?\""),
            "the published question must carry the message; got $request",
        )
        assertTrue(
            request.contains("\"submitter\":\"release-team\"") && request.contains("\"id\":\"deploy-1\""),
            "the Jenkins options must reach the channel; got $request",
        )

        answer(question, proceed())
        val result = run.await()
        assertEquals(0, result.exitCode, "a granted question must finish green; output:\n${result.output}")
        assertEquals(listOf("body-ran"), markerLines(marker), "Proceed must run the body exactly once")
        assertTrue(
            result.output.contains("\"kind\":\"InputRequested\""),
            "the question must be observable; output:\n${result.output}",
        )
        assertTrue(
            result.output.contains("\"kind\":\"InputProceed\""),
            "the grant must be observable; output:\n${result.output}",
        )
    }

    // ── WI-L2 ────────────────────────────────────────────────────────────────

    @Test
    fun `WI-L2 an ABORT fails the run and never emits a proceed`(@TempDir tempDir: Path) {
        val marker = tempDir.resolve("marker.txt")
        val file = script(
            tempDir,
            "input-abort.kts",
            """
            pipeline {
                stages {
                    stage("deploy") {
                        input("Deploy to production?") {
                            sh("echo body-ran >> '$marker'")
                        }
                    }
                }
            }
            """,
        )
        val run = startRun(tempDir, invocation(tempDir, file))
        answer(awaitQuestion(tempDir), abort())
        val result = run.await()

        assertTrue(result.exitCode != 0, "an abort must fail the run, as Jenkins does; output:\n${result.output}")
        assertEquals(emptyList<String>(), markerLines(marker), "an aborted question must not run its body")
        assertTrue(
            result.output.contains("\"kind\":\"InputAborted\""),
            "the refusal must be observable; output:\n${result.output}",
        )
        assertFalse(
            result.output.contains("\"kind\":\"InputProceed\""),
            "an abort must never also report a proceed; output:\n${result.output}",
        )
    }

    // ── WI-L3 ────────────────────────────────────────────────────────────────

    @Test
    fun `WI-L3 a malformed answer does not end the wait and a later valid one does`(@TempDir tempDir: Path) {
        val marker = tempDir.resolve("marker.txt")
        val file = script(
            tempDir,
            "input-malformed.kts",
            """
            pipeline {
                stages {
                    stage("deploy") {
                        input("Deploy to production?") {
                            sh("echo body-ran >> '$marker'")
                        }
                    }
                }
            }
            """,
        )
        val run = startRun(tempDir, invocation(tempDir, file))
        val question = awaitQuestion(tempDir)

        // A truncated file means "a human is still typing", not "no".
        answer(question, """{"decision":"PROCE""", atomic = false)
        Thread.sleep(2_000)
        assertTrue(run.isAlive(), "a malformed answer must not end the wait; the run must still be asking")

        answer(question, proceed())
        val result = run.await()
        assertEquals(0, result.exitCode, "output:\n${result.output}")
        assertEquals(listOf("body-ran"), markerLines(marker))
    }

    // ── WI-L4 ────────────────────────────────────────────────────────────────

    @Test
    fun `WI-L4 the first answer wins and a later one cannot overwrite it`(@TempDir tempDir: Path) {
        val marker = tempDir.resolve("marker.txt")
        val file = script(
            tempDir,
            "input-first-wins.kts",
            """
            pipeline {
                stages {
                    stage("deploy") {
                        input("Deploy to production?") {
                            sh("echo body-ran >> '$marker'")
                        }
                    }
                }
            }
            """,
        )
        val run = startRun(tempDir, invocation(tempDir, file))
        val question = awaitQuestion(tempDir)

        answer(question, abort())
        Thread.sleep(2_000)
        // Someone changes their mind, or a second automation writes the file. The
        // decision is already durable: rewriting it must not silently deploy.
        answer(question, proceed())
        val result = run.await()

        assertTrue(
            result.exitCode != 0,
            "the FIRST answer must stand: a later PROCEED must not overwrite a recorded ABORT; " +
                "output:\n${result.output}",
        )
        assertEquals(emptyList<String>(), markerLines(marker), "the body must not run")
        assertTrue(
            result.output.contains("\"kind\":\"InputAborted\""),
            "output:\n${result.output}",
        )
    }

    // ── WI-L5 ────────────────────────────────────────────────────────────────

    @Test
    fun `WI-L5 timeoutSeconds bounds the wait and denies with TIMED_OUT`(@TempDir tempDir: Path) {
        val marker = tempDir.resolve("marker.txt")
        val file = script(
            tempDir,
            "input-timeout.kts",
            """
            pipeline {
                stages {
                    stage("deploy") {
                        input("Deploy to production?", timeoutSeconds = 2) {
                            sh("echo body-ran >> '$marker'")
                        }
                    }
                }
            }
            """,
        )
        val result = runCli(tempDir, invocation(tempDir, file))
        assertTrue(result.exitCode != 0, "an unanswered question must fail the run; output:\n${result.output}")
        assertEquals(emptyList<String>(), markerLines(marker), "an unanswered question must not run its body")
        assertTrue(
            result.output.contains("\"kind\":\"InputDenied\""),
            "the denial must be observable; output:\n${result.output}",
        )
        assertTrue(
            result.output.contains("TIMED_OUT"),
            "the denial must carry its reason, not merely exist; output:\n${result.output}",
        )
        // The question stays on disk: it is a durable fact about the run, and the
        // operator may still be looking at it after the run gave up on it.
        val asked = questionDirs(tempDir).first { Files.exists(it.resolve("request.json")) }
        assertTrue(
            Files.exists(asked.resolve("request.json")),
            "a denied question must stay inspectable",
        )
    }

    // ── WI-L6 ────────────────────────────────────────────────────────────────

    @Test
    fun `WI-L6 an enclosing timeout cancels an indefinite waiter`(@TempDir tempDir: Path) {
        val marker = tempDir.resolve("marker.txt")
        val file = script(
            tempDir,
            "input-cancel.kts",
            """
            pipeline {
                stages {
                    stage("deploy") {
                        timeout(3, "SECONDS") {
                            input("Deploy to production?") {
                                sh("echo body-ran >> '$marker'")
                            }
                        }
                    }
                }
            }
            """,
        )
        val result = runCli(tempDir, invocation(tempDir, file))
        assertTrue(result.exitCode != 0, "a cancelled wait must fail the run; output:\n${result.output}")
        assertEquals(emptyList<String>(), markerLines(marker), "a cancelled question must not run its body")
        assertFalse(
            result.output.contains("\"kind\":\"InputProceed\""),
            "nobody answered, so nobody may have proceeded; output:\n${result.output}",
        )
    }

    // ── WI-L7 ────────────────────────────────────────────────────────────────

    @Test
    fun `WI-L7 an abort runs neither the body nor anything after the question`(@TempDir tempDir: Path) {
        val marker = tempDir.resolve("marker.txt")
        val file = script(
            tempDir,
            "input-abort-scope.kts",
            """
            pipeline {
                stages {
                    stage("deploy") {
                        input("Deploy to production?") {
                            sh("echo body-ran >> '$marker'")
                        }
                        sh("echo after-question >> '$marker'")
                    }
                }
            }
            """,
        )
        val run = startRun(tempDir, invocation(tempDir, file))
        answer(awaitQuestion(tempDir), abort())
        val result = run.await()

        assertTrue(result.exitCode != 0, "output:\n${result.output}")
        assertEquals(
            emptyList<String>(),
            markerLines(marker),
            "a refused permission must stop the pipeline: neither the guarded body nor the " +
                "step after it may run. Running `after-question` would mean a refusal only " +
                "skipped a body while the pipeline walked on regardless.",
        )
    }

    // ── WI-L8 ────────────────────────────────────────────────────────────────

    @Test
    fun `WI-L8 a resume does not ask for a second decision`(@TempDir tempDir: Path) {
        val marker = tempDir.resolve("marker.txt")
        // The bound is what makes this row fail FAST when the law breaks: without a
        // re-usable answer the resume would wait for an operator who is never going to
        // answer, and the denial would arrive only at the bound. 8s keeps a red run
        // cheap while leaving a healthy resume plenty of room.
        val file = script(
            tempDir,
            "input-resume.kts",
            """
            pipeline {
                stages {
                    stage("deploy") {
                        input("Deploy to production?", timeoutSeconds = 8) {
                            sh("echo body-ran >> '$marker'")
                            sh("exit 1")
                        }
                    }
                }
            }
            """,
        )
        // A granted question, then a body that fails: the run is resumable and the
        // question HAS been decided.
        val first = startRun(tempDir, invocation(tempDir, file))
        answer(awaitQuestion(tempDir), proceed())
        val firstResult = first.await()
        assertTrue(firstResult.exitCode != 0, "the failing body must fail the run; output:\n${firstResult.output}")
        assertEquals(listOf("body-ran"), markerLines(marker))

        val askedBefore = questionDirs(tempDir).toSet()
        assertEquals(1, askedBefore.size, "the first run asked exactly one question")

        // The resume runs with NO operator intervention whatsoever — nobody answers,
        // nobody watches. That is the whole law: the decision is a durable fact, so a
        // resumed run must be able to finish on what is already on disk.
        val second = runCli(tempDir, invocation(tempDir, file))

        assertFalse(
            second.output.contains("\"kind\":\"InputDenied\""),
            "a resumed run must NOT need a new decision. The first run was already " +
                "answered, so the denial here means the resume ignored the recorded " +
                "answer and stopped to ask a human again — which is what an operator " +
                "abandoning a queue of stale questions experiences. output:\n${second.output}",
        )
        assertEquals(
            askedBefore,
            questionDirs(tempDir).toSet(),
            "a resume must not open a new question directory; output:\n${second.output}",
        )
        assertEquals(
            listOf("body-ran"),
            markerLines(marker),
            "and the body must not run twice for one granted question",
        )
    }

    // ── WI-L9 ────────────────────────────────────────────────────────────────

    @Test
    fun `WI-L9 two input steps in one run are two questions`(@TempDir tempDir: Path) {
        val marker = tempDir.resolve("marker.txt")
        val file = script(
            tempDir,
            "input-two.kts",
            """
            pipeline {
                stages {
                    stage("deploy") {
                        input("Deploy to production?") {
                            sh("echo first >> '$marker'")
                        }
                        input("Also update the changelog?") {
                            sh("echo second >> '$marker'")
                        }
                    }
                }
            }
            """,
        )
        val run = startRun(tempDir, invocation(tempDir, file))

        // The SECOND question does not exist yet, and waiting for both up front would
        // deadlock the test against the very law it asserts: the run is sequential, so
        // it cannot ask the second until the first is answered. Asking them one at a
        // time is also how the law reads — each answer releases the next question.
        val deployQuestion = awaitQuestion(tempDir)
        val deployRequest = Files.readString(deployQuestion.resolve("request.json"))
        assertTrue(
            deployRequest.contains("Deploy to production?"),
            "the first question must be asked first; got $deployRequest",
        )
        answer(deployQuestion, proceed())

        val changelogQuestion = awaitQuestion(tempDir)
        val changelogRequest = Files.readString(changelogQuestion.resolve("request.json"))
        assertTrue(
            changelogRequest.contains("Also update the changelog?"),
            "the second step must ask its OWN question, not repeat the first; got $changelogRequest",
        )
        assertTrue(
            changelogQuestion != deployQuestion,
            "two input steps must address two questions; a shared channel would let the " +
                "second step read the first answer and act on a permission meant for " +
                "another decision.",
        )

        answer(changelogQuestion, abort())
        val result = run.await()
        assertTrue(result.exitCode != 0, "the refused second question fails the run; output:\n${result.output}")
        assertEquals(
            listOf("first"),
            markerLines(marker),
            "only the granted body may run; the refused question's body must not",
        )
    }
}
