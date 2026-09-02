package dev.rubentxu.pipeline.v2.sdk.runtime.durable.task

import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.ExecutionOutputSink
import dev.rubentxu.pipeline.v2.domain.durable.OutputChunk
import dev.rubentxu.pipeline.v2.domain.durable.TaskExecutionRequest
import dev.rubentxu.pipeline.v2.domain.durable.TaskSpec
import dev.rubentxu.pipeline.v2.domain.durable.TaskStream
import dev.rubentxu.pipeline.v2.domain.durable.executeBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * UAT-M3 core: production [ProcessDurableTaskRuntime] against REAL
 * processes. Maps to UAT M3-001 (shell success), M3-002 (argv without
 * shell), M3-003/004 (bounded output), M3-005 (mixed streams), M3-006
 * (timeout/cancel kill tree).
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class ProcessDurableTaskRuntimeTest {

    @TempDir
    lateinit var tempDir: Path

    private val clock = object : Clock {
        override fun now(): java.time.Instant = java.time.Instant.now()
    }

    private fun runtime(): ProcessDurableTaskRuntime =
        ProcessDurableTaskRuntime(controlRoot = tempDir.resolve("ctrl"), clock = clock)

    private fun execRequest(argv: List<String>, opId: String = "r1-s0-0", timeoutMs: Long? = null) =
        TaskExecutionRequest(
            task = TaskSpec.ExecTask(argv = argv),
            runId = RunId("run-1"),
            opId = opId,
            timeoutMs = timeoutMs,
        )

    private fun collectingSink(): Pair<ExecutionOutputSink, MutableList<OutputChunk>> {
        val chunks = java.util.Collections.synchronizedList(mutableListOf<OutputChunk>())
        val sink = ExecutionOutputSink { chunk -> chunks.add(chunk) }
        return sink to chunks
    }

    private fun text(chunks: List<OutputChunk>, stream: TaskStream): String =
        chunks.filter { it.stream == stream }.joinToString("") { it.data.toString(Charsets.UTF_8) }

    @Test
    fun `M3-001 exec task runs and succeeds`() = runBlocking {
        val (sink, chunks) = collectingSink()

        val result = runtime().executeBlocking(execRequest(listOf("/bin/echo", "hello-m3")), sink)

        assertTrue(result.succeeded)
        assertEquals(0, result.exitCode)
        assertTrue(text(chunks, TaskStream.STDOUT).contains("hello-m3"))
    }

    @Test
    fun `M3-002 argv with spaces and quotes crosses verbatim without shell`() = runBlocking {
        // /usr/bin/printf receives each argument verbatim: a shell would
        // have eaten the quotes and re-tokenised the spaces.
        val (sink, chunks) = collectingSink()
        val argv = listOf("/usr/bin/printf", "%s|%s\n", "two words", "quoted \"arg\"")

        runtime().executeBlocking(execRequest(argv), sink)

        assertEquals(
            "two words|quoted \"arg\"\n",
            text(chunks, TaskStream.STDOUT),
            "argv must reach the process verbatim",
        )
    }

    @Test
    fun `shell script is written to a file and executed by the interpreter`() = runBlocking {
        val (sink, chunks) = collectingSink()
        val request = TaskExecutionRequest(
            task = TaskSpec.ShellScriptTask(script = "echo script-ran"),
            runId = RunId("run-1"),
            opId = "r1-s0-1",
        )

        val result = runtime().executeBlocking(request, sink)

        assertTrue(result.succeeded)
        val controlDir = tempDir.resolve("ctrl").resolve("r1-s0-1")
        assertTrue(Files.isRegularFile(controlDir.resolve("script.sh")), "script must be materialised as a file")
        assertEquals("echo script-ran", Files.readString(controlDir.resolve("script.sh")))
        assertTrue(text(chunks, TaskStream.STDOUT).contains("script-ran"))
    }

    @Test
    fun `stdout and stderr arrive as distinct typed streams (M3-005)`() = runBlocking {
        val (sink, chunks) = collectingSink()
        val request = TaskExecutionRequest(
            task = TaskSpec.ShellScriptTask(script = "echo to-stdout; echo to-stderr 1>&2"),
            runId = RunId("run-1"),
            opId = "r1-s0-2",
        )

        runtime().executeBlocking(request, sink)

        val stdout = text(chunks, TaskStream.STDOUT)
        val stderr = text(chunks, TaskStream.STDERR)
        assertTrue(stdout.contains("to-stdout"), "stdout: $stdout")
        assertTrue(stderr.contains("to-stderr"), "stderr: $stderr")
        assertFalse(stderr.contains("to-stdout"))
    }

    @Test
    fun `nonzero exit maps to a failed result`() = runBlocking {
        val result = runtime().executeBlocking(execRequest(listOf("/bin/false"), opId = "r1-s0-3"), noopSink())

        assertFalse(result.succeeded)
        assertEquals(1, result.exitCode)
        assertFalse(result.timedOut)
        assertFalse(result.cancelled)
    }

    @Test
    fun `M3-006 timeout destroys the process tree and records TIMED_OUT`() = runBlocking {
        // The unique sleep duration AND the marker must BOTH disappear:
        // killing only the direct child would leave either alive.
        val request = TaskExecutionRequest(
            task = TaskSpec.ExecTask(argv = listOf("/bin/sh", "-c", "sleep 3731 # m3-tree-probe")),
            runId = RunId("run-1"),
            opId = "r1-s0-4",
            timeoutMs = 300,
        )

        val started = System.currentTimeMillis()
        val result = runtime().executeBlocking(request, noopSink())
        val elapsed = System.currentTimeMillis() - started

        assertTrue(result.timedOut, "must be timedOut")
        assertFalse(result.succeeded)
        assertTrue(elapsed < 15_000, "timeout must fire promptly, took ${elapsed}ms")

        awaitGone("m3-tree-probe")
        awaitGone("sleep 3731")

        val record = Files.readString(tempDir.resolve("ctrl").resolve("r1-s0-4").resolve("result.txt")).trim()
        assertEquals("TIMED_OUT", record, "durable record must say TIMED_OUT")
    }

    @Test
    fun `M3-006 cancel destroys the process tree and records CANCELLED`() = runBlocking {
        val runtime = runtime()
        val request = TaskExecutionRequest(
            task = TaskSpec.ShellScriptTask(script = "sleep 3732"),
            runId = RunId("run-1"),
            opId = "r1-s0-5",
        )

        // Dispatch on a real worker pool: cancellation must come from a
        // DIFFERENT thread than the one blocked in waitFor (production
        // callers are multithreaded; a single-threaded runBlocking cannot
        // deliver cancel while the caller thread is inside the port).
        val job = launch(kotlinx.coroutines.Dispatchers.Default) { runtime.execute(request, noopSink()) }
        kotlinx.coroutines.delay(500) // state positioning: process is live (rule 10)
        job.cancel()
        job.join()

        val record = Files.readString(tempDir.resolve("ctrl").resolve("r1-s0-5").resolve("result.txt")).trim()
        assertEquals("CANCELLED", record, "cancelled runs must still record a durable result")
        awaitGone("sleep 3732")
    }

    @Test
    fun `M3-003 bounded output — 100MB stdout drains through bounded chunks`() = runBlocking {
        val totalBytes = 100L * 1024 * 1024
        val (sink, chunks) = collectingSink()
        val request = TaskExecutionRequest(
            task = TaskSpec.ExecTask(argv = listOf("/usr/bin/head", "-c", totalBytes.toString(), "/dev/zero")),
            runId = RunId("run-1"),
            opId = "r1-s0-6",
        )

        val result = runtime().executeBlocking(request, sink)

        assertTrue(result.succeeded)
        val received = chunks.filter { it.stream == TaskStream.STDOUT }.sumOf { it.data.size.toLong() }
        assertEquals(totalBytes, received, "all 100MB must be delivered through bounded chunks")
        val maxChunk = chunks.maxOf { it.data.size }
        assertTrue(maxChunk <= 64 * 1024, "chunk size must stay bounded, got $maxChunk")
    }

    @Test
    fun `env secrets materialise into the process but never into durable files`() = runBlocking {
        val secret = "M3-SECRET-do-not-persist-9f3e"
        val (sink, chunks) = collectingSink()
        val request = TaskExecutionRequest(
            task = TaskSpec.ExecTask(argv = listOf("/usr/bin/printenv", "M3_TEST_SECRET")),
            runId = RunId("run-1"),
            opId = "r1-s0-7",
            env = mapOf("M3_TEST_SECRET" to SecretHandle.plain(secret)),
        )

        runtime().executeBlocking(request, sink)

        // printenv oracle (AGENTS §9): the process must see the value.
        assertEquals("$secret\n", text(chunks, TaskStream.STDOUT))

        // Nothing on disk may contain the secret (redaction before persisting).
        Files.walk(tempDir.resolve("ctrl")).use { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { file ->
                assertFalse(Files.readString(file).contains(secret), "secret leaked into $file")
            }
        }
    }

    @Test
    fun `durable record is written for normal exits too`() = runBlocking {
        runtime().executeBlocking(execRequest(listOf("/bin/true"), opId = "r1-s0-8"), noopSink())

        val record = Files.readString(tempDir.resolve("ctrl").resolve("r1-s0-8").resolve("result.txt")).trim()
        assertEquals("EXIT 0", record)
    }

    @Test
    fun `concurrent executions are isolated by opId`() = runBlocking {
        val runtime = runtime()
        val results = kotlinx.coroutines.coroutineScope {
            (1..4).map { i ->
                async {
                    val (sink, chunks) = collectingSink()
                    val result = runtime.executeBlocking(
                        execRequest(listOf("/bin/echo", "op-$i"), opId = "r1-s0-conc-$i"),
                        sink,
                    )
                    result to text(chunks, TaskStream.STDOUT)
                }
            }.map { it.await() }
        }

        results.forEachIndexed { index, (result, stdout) ->
            assertTrue(result.succeeded)
            assertTrue(stdout.contains("op-${index + 1}"), "stdout: $stdout")
        }
        (1..4).forEach { i ->
            val record = Files.readString(
                tempDir.resolve("ctrl").resolve("r1-s0-conc-$i").resolve("result.txt")
            ).trim()
            assertEquals("EXIT 0", record)
        }
    }

    private fun noopSink(): ExecutionOutputSink = ExecutionOutputSink { }

    /** Polls pgrep until the pattern disappears (kill lands) or fails the budget. */
    private fun awaitGone(pattern: String) {
        val deadline = System.currentTimeMillis() + 5_000
        var survivors: String
        do {
            survivors = pgrepFor(pattern)
            if (survivors.isNotBlank()) Thread.sleep(200)
        } while (survivors.isNotBlank() && System.currentTimeMillis() < deadline)
        assertEquals("", survivors, "process tree must be fully killed: $survivors")
    }

    private fun pgrepFor(marker: String): String {
        val pb = ProcessBuilder("/usr/bin/pgrep", "-f", marker)
        pb.redirectErrorStream(true)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor(5, TimeUnit.SECONDS)
        return if (p.exitValue() == 1) "" else out // pgrep exit 1 = no match
    }
}
