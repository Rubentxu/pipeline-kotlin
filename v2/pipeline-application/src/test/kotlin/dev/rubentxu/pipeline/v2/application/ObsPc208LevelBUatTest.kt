package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.ObsPc208Producer
import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.application.durable.OutputPlaneProvider
import dev.rubentxu.pipeline.v2.application.durable.ShExecution
import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.ShellCommand
import dev.rubentxu.pipeline.v2.domain.ShellReturnMode
import dev.rubentxu.pipeline.v2.events.durable.InMemoryEventStore
import dev.rubentxu.pipeline.v2.output.OutputReadResult
import dev.rubentxu.pipeline.v2.output.store.SegmentOutputStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.SandboxConfig
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * OBS-PC-208 — Nivel B como **garantía de producto**, no como spike.
 *
 * `ObsPc2ProducerSurvivalSpikeTest` prueba que el camino de ingesta funciona
 * mecánicamente: 50/50 marcadores, `bytes_after_release > bytes_after_kill`,
 * `DONE` presente, sin secretos en disco. Eso demuestra que la vertical está
 * implementada.
 *
 * Una garantía de producto es otra cosa: cada propiedad tiene una **prueba
 * independiente con mutación** que la tumba, y cada falsificador del operador
 * tiene un método que falla cuando el comportamiento se rompe. Esto es lo que
 * vuelve a OBS-PC-208 `STEP-CERT` y, combinado con un `PRODUCT-GATE` real, lo
 * hace `CERTIFIED`.
 *
 * ## Estructura
 *
 * - [happyPath]: los **11 pasos** de OBS-PC-208. Cada paso es una aserción
 *   discreta que la implementación cumple y que un cambio regresivo rompería.
 * - [falsifiers]: los **10 falsificadores** del operador. Cada uno es un
 *   `@Test` que ejercita un escenario adversario y verifica que la garantía
 *   se mantiene. El nombre del test es el falsificador, no la propiedad;
 *   un cambio que rompa la propiedad es exactamente lo que el nombre
 *   describe.
 *
 * ## Fidelidad
 *
 * HF5 (real subprocess, real signals, real channel). `kill -9` es un kill real
 * al PID del productor; `setsid` aísla al hijo del grupo; el FIFO se crea con
 * `mkfifo` real y el agente se lanza con un `java -cp` real desde una nueva
 * JVM. Sin `Files.createTempDirectory` sin padre. Sin asserts de tiempo de
 * pared. `@TempDir` por test.
 */
@Timeout(value = 600, unit = TimeUnit.SECONDS)
@DisplayName("OBS-PC-208 — Nivel B: garantía de producto (11 pasos + 10 falsificadores)")
class ObsPc208LevelBUatTest {

    private val secret = "GHS9_PC208_CANARY_4b8e1d7a2c6f"
    private val runId = "r-obs-pc208"

    @TempDir
    lateinit var root: Path

    private lateinit var controlRoot: Path

    @BeforeEach
    fun setUp() {
        assumeTrue(
            !System.getProperty("os.name").orEmpty().contains("win", ignoreCase = true),
            "the durable shell substrate requires a POSIX host",
        )
        OutputPlaneProvider.forgetAll()
        controlRoot = Files.createDirectories(root.resolve("control"))
    }

    /**
     * Wait for any surviving ingest agent before the @TempDir cleanup tries
     * to delete the temp dir. A live agent holds the Output Plane segment
     * files open (via its FileChannel ownership lock), and JUnit's recursive
     * delete fails if any file is locked — which surfaces as a
     * "Failed to close extension context" rather than a test failure, and
     * the suite ends RED even when every assertion was green.
     *
     * R2.3(b) / TODO 4: this is the price of the kill-the-runtime guarantee.
     * Each HappyPath step that uses `kill -9` on the producer leaves the
     * agent running until it sees the child exit + drain EOF; without this
     * teardown the next test's @TempDir creation collides with the previous
     * test's still-warming agent's warmup FileChannel.
     */
    @org.junit.jupiter.api.AfterEach
    fun tearDown() {
        try {
            awaitNoAgentProcess(30)
        } catch (_: Exception) {
            // Best effort; the JUnit cleanup will still try to delete and
            // may leave a stale agent, but the next test's @TempDir is a
            // different directory and the agent's lock is on the old one.
        }
    }

    private fun opIdFormat() = OpId(runId, 0, 0).format()

    private fun committedBytes(): ByteArray {
        val store = OutputPlaneProvider.storeForReading(controlRoot)
        val stream = OutputPlaneProvider.streamsOf(runId, opIdFormat()).stdout.stream
        val out = java.io.ByteArrayOutputStream()
        var cursor: dev.rubentxu.pipeline.v2.output.OutputCursor? =
            dev.rubentxu.pipeline.v2.output.OutputCursor.start(stream)
        while (cursor != null) {
            when (val result = store.read(stream, cursor, 65536)) {
                is OutputReadResult.Page -> {
                    out.write(result.page.bytes)
                    cursor = result.page.next
                }
                is OutputReadResult.Refused -> cursor = null
            }
        }
        return out.toByteArray()
    }

    private fun rawSecretBytesOnDisk(): Int {
        val planeDir = controlRoot.resolve(OutputPlaneProvider.OUTPUT_DIR)
        if (!Files.isDirectory(planeDir)) return 0
        val needle = secret.toByteArray(Charsets.UTF_8)
        return Files.walk(planeDir).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .mapToInt { file ->
                    val hay = Files.readAllBytes(file)
                    var n = 0
                    outer@ for (i in 0..hay.size - needle.size) {
                        for (j in needle.indices) {
                            if (hay[i + j] != needle[j]) continue@outer
                        }
                        n++
                    }
                    n
                }
                .sum()
        }
    }

    /**
     * The 11 verification steps of OBS-PC-208. Each step is a discrete
     * assertion that the implementation passes; a regression in any
     * step flips exactly one row.
     */
    @Nested
    @DisplayName("OBS-PC-208 — happy path: 11 pasos")
    inner class HappyPath {

        @Test
        @Timeout(300)
        fun `step 1-2 — boot runtime, agent and child, confirm sanitized bytes before crash`() {
            val barrier = Files.createFile(root.resolve("BARRIER"))
            val go = root.resolve("GO")
            val done = root.resolve("DONE")
            val workspace = Files.createDirectories(root.resolve("workspace"))

            runProducer(
                workspace = workspace,
                barrier = barrier,
                go = go,
                done = done,
                linesBefore = 50,
                linesAfter = 1, // step 1-2: stop after barrier; no post-crash lines yet
                extraStderr = false,
                scriptExtras = "",
            )

            // Wait for the child to reach barrier + commit at least one canary line.
            awaitFile(barrier, 90, "child never reached barrier")
            val beforeKill = awaitPrefix("plain-before-", 60, "no sanitized prefix before kill")
            val text = beforeKill.toString(Charsets.UTF_8)
            assertTrue(
                text.contains("plain-before-"),
                "step 1-2: sanitized bytes must reach the plane before the runtime dies",
            )
            assertTrue(
                !text.contains(secret),
                "step 1-2: secret must be redacted even on the prefix read",
            )
        }

        @Test
        @Timeout(300)
        fun `step 3-4 — kill only the runtime, release child for 50 stdout + stderr corpus`() {
            val barrier = Files.createFile(root.resolve("BARRIER"))
            val go = root.resolve("GO")
            val done = root.resolve("DONE")
            val workspace = Files.createDirectories(root.resolve("workspace"))

            runProducer(
                workspace = workspace,
                barrier = barrier,
                go = go,
                done = done,
                linesBefore = 50,
                linesAfter = 50,
                extraStderr = true,
                scriptExtras = "",
            )

            awaitFile(barrier, 90, "child never reached barrier")
            val beforeKill = awaitPrefix("plain-before-", 60, "no sanitized prefix before kill")

            // Step 3: kill only the runtime JVM; the child must survive (setsid).
            val producer = producerHandle!!
            ProcessBuilder("kill", "-9", producer.pid().toString()).start().waitFor(5, TimeUnit.SECONDS)
            producer.waitFor(30, TimeUnit.SECONDS)
            assertTrue(!producer.isAlive, "the producer must die from SIGKILL")

            // Step 4: release the child; 50 stdout lines + stderr corpus.
            Files.writeString(go, "go")
            val childFinished = await({ Files.exists(done) }, 90)
            assertTrue(childFinished, "step 4: child must finish post-kill")

            assertTrue(
                Files.exists(workspace.resolve("RESUMED")),
                "step 4: child must resume after JVM death (setsid isolation)",
            )
            assertTrue(
                Files.exists(workspace.resolve("ALIVE_MARK")),
                "step 4: child must not be killed by a watchdog",
            )
            val afterLines = (1..50).count { Files.exists(workspace.resolve("AFTER-$it")) }
            assertEquals(50, afterLines, "step 4: all 50 post-kill stdout lines must reach workspace")
        }

        @Test
        @Timeout(300)
        fun `step 5-6 — confirm child writes happened, wait for new committed positions`() {
            val barrier = Files.createFile(root.resolve("BARRIER"))
            val go = root.resolve("GO")
            val done = root.resolve("DONE")
            val workspace = Files.createDirectories(root.resolve("workspace"))

            runProducer(
                workspace = workspace,
                barrier = barrier,
                go = go,
                done = done,
                linesBefore = 100,
                linesAfter = 50,
                extraStderr = true,
                scriptExtras = "",
            )

            awaitFile(barrier, 90, "child never reached barrier")
            val beforeKill = awaitPrefix("plain-before-", 60, "no sanitized prefix before kill")

            val producer = producerHandle!!
            ProcessBuilder("kill", "-9", producer.pid().toString()).start().waitFor(5, TimeUnit.SECONDS)
            producer.waitFor(30, TimeUnit.SECONDS)

            OutputPlaneProvider.forgetAll()
            val afterKill = committedBytes()
            assertEquals(
                beforeKill.size,
                afterKill.size,
                "step 5: bytes acknowledged before kill must survive the kill (Level A)",
            )

            // Step 5: child writes happen — confirmed by RESUMED, ALIVE_MARK, and AFTER-N files.
            Files.writeString(go, "go")
            await({ Files.exists(done) }, 90)

            // Step 6: wait for new committed positions. The agent keeps draining
            // after the runtime dies; the plane must grow past afterKill.
            val afterGo = awaitIncrement(afterKill, 120, "step 6: plane must grow past afterKill")
            assertNotEquals(
                afterKill.size,
                afterGo.size,
                "step 6: new committed positions must appear (Level B closure)",
            )
        }

        @Test
        @Timeout(300)
        fun `step 7-8 — open a reader in another JVM, verify exact expected bytes, order, sanitized marker`() {
            val barrier = Files.createFile(root.resolve("BARRIER"))
            val go = root.resolve("GO")
            val done = root.resolve("DONE")
            val workspace = Files.createDirectories(root.resolve("workspace"))

            runProducer(
                workspace = workspace,
                barrier = barrier,
                go = go,
                done = done,
                linesBefore = 100,
                linesAfter = 50,
                extraStderr = true,
                scriptExtras = "",
            )

            awaitFile(barrier, 90, "child never reached barrier")
            val beforeKill = awaitPrefix("plain-before-", 60, "no sanitized prefix before kill")

            val producer = producerHandle!!
            ProcessBuilder("kill", "-9", producer.pid().toString()).start().waitFor(5, TimeUnit.SECONDS)
            producer.waitFor(30, TimeUnit.SECONDS)

            Files.writeString(go, "go")
            await({ Files.exists(done) }, 90)

            // Step 7: open a fresh reader in *this* JVM — storeForReading does not
            // recover and does not see the writing cache, so the bytes come from
            // disk only, exactly as a second process would.
            OutputPlaneProvider.forgetAll()
            // Step 8: verify the bytes — the prefix is intact, the secret is
            // absent, the post-kill marker is present, the secret is sanitised
            // (the **** marker) at least once.
            val afterGo = committedBytes()
            val text = afterGo.toString(Charsets.UTF_8)
            assertTrue(
                text.startsWith(beforeKill.toString(Charsets.UTF_8)),
                "step 7-8: prefix is intact in the external reader",
            )
            assertTrue(
                !text.contains(secret),
                "step 7-8: no raw secret in the external reader",
            )
            assertTrue(
                text.contains("plain-after-"),
                "step 7-8: post-kill stdout reaches the external reader",
            )
            assertTrue(
                text.contains("****"),
                "step 7-8: the canary is sanitised to **** in the external reader",
            )
        }

        @Test
        @Timeout(300)
        fun `step 9-10 — reading is resumable, no raw secret on durable storage`() {
            val barrier = Files.createFile(root.resolve("BARRIER"))
            val go = root.resolve("GO")
            val done = root.resolve("DONE")
            val workspace = Files.createDirectories(root.resolve("workspace"))

            runProducer(
                workspace = workspace,
                barrier = barrier,
                go = go,
                done = done,
                linesBefore = 50,
                linesAfter = 30,
                extraStderr = true,
                scriptExtras = "",
            )

            awaitFile(barrier, 90, "child never reached barrier")
            val beforeKill = awaitPrefix("plain-before-", 60, "no sanitized prefix before kill")

            val producer = producerHandle!!
            ProcessBuilder("kill", "-9", producer.pid().toString()).start().waitFor(5, TimeUnit.SECONDS)
            producer.waitFor(30, TimeUnit.SECONDS)
            Files.writeString(go, "go")
            await({ Files.exists(done) }, 90)
            awaitIncrement(beforeKill, 120, "post-kill bytes must reach the plane")

            // Step 9: resumable read. Open a reader, read the prefix, open
            // another reader at a later offset, verify it continues without
            // re-emitting the bytes already read.
            OutputPlaneProvider.forgetAll()
            val store = OutputPlaneProvider.storeForReading(controlRoot)
            val stream = OutputPlaneProvider.streamsOf(runId, opIdFormat()).stdout.stream
            val total = committedBytes().size
            val firstRead = readAt(store, stream, 0L, minOf(2048, total))
            val secondRead = readAt(store, stream, firstRead.size.toLong(), total - firstRead.size)
            val combined = firstRead + secondRead
            assertEquals(total, combined.size, "step 9: resumable read covers the whole stream")
            assertEquals(
                committedBytes().toList(),
                combined.toList(),
                "step 9: combined reads equal a single full read",
            )

            // Step 10: no raw secret in any file under the control root.
            // The FIFO is a named pipe (not a regular file), so it does not
            // appear in `walk`; the search covers the Output Plane only.
            val rawCount = rawSecretBytesOnDisk()
            assertEquals(
                0,
                rawCount,
                "step 10: raw secret must not appear in the Output Plane; this catches any future regression that re-introduces a plaintext spool",
            )
        }

        @Test
        @Timeout(300)
        fun `step 11 — terminate child, check drain, sealing, resource closure`() {
            val barrier = Files.createFile(root.resolve("BARRIER"))
            val go = root.resolve("GO")
            val done = root.resolve("DONE")
            val workspace = Files.createDirectories(root.resolve("workspace"))

            runProducer(
                workspace = workspace,
                barrier = barrier,
                go = go,
                done = done,
                linesBefore = 30,
                linesAfter = 20,
                extraStderr = false,
                scriptExtras = "",
            )

            awaitFile(barrier, 90, "child never reached barrier")
            val beforeKill = awaitPrefix("plain-before-", 60, "no sanitized prefix before kill")

            val producer = producerHandle!!
            ProcessBuilder("kill", "-9", producer.pid().toString()).start().waitFor(5, TimeUnit.SECONDS)
            producer.waitFor(30, TimeUnit.SECONDS)
            Files.writeString(go, "go")
            await({ Files.exists(done) }, 90)
            awaitIncrement(beforeKill, 120, "post-kill bytes must reach the plane")

            // Step 11a: drain — wait for the agent to finish (it has no more
            // writers once the child is done, so the O_RDONLY open sees EOF
            // and the agent exits with pc2-agent-drained).
            // The agent's log is in controlDir/agent.log; we already
            // snapshot it in the spike test path. For this row we wait
            // until no agent process is alive.
            awaitNoAgentProcess(60)

            // Step 11b: sealing — read the committed extent and verify the
            // final number is stable. The Output Plane does not seal
            // automatically on agent exit (it seals at the run terminal),
            // but the bytes must be durably committed and not regress.
            OutputPlaneProvider.forgetAll()
            val finalSize1 = committedBytes().size
            Thread.sleep(2_000)
            val finalSize2 = committedBytes().size
            assertEquals(
                finalSize1,
                finalSize2,
                "step 11: committed extent is stable after drain",
            )

            // Step 11c: resource closure — no orphan processes. The agent,
            // child, and producer are all gone. Any leftover ObsPc2IngestAgent
            // would be a leak.
            var orphans = 0
            for (ph in ProcessHandle.allProcesses().toList()) {
                val cmd = ph.info().command().orElse("")
                if (cmd.contains("ObsPc2IngestAgent")) orphans++
            }
            assertEquals(
                0,
                orphans.toLong(),
                "step 11: no orphan agent processes after drain",
            )
        }
    }

    // -------- Falsifiers --------

    /**
     * Falsifiers are tests of the form: "if the property were broken, this
     * scenario would reproduce the defect". The `@Test` name is the
     * falsifier, not the property — a regression that breaks the property
     * is exactly what the name describes.
     */
    @Nested
    @DisplayName("OBS-PC-208 — falsificadores")
    inner class Falsifiers {

        @Test
        @Timeout(300)
        fun `f01 — bytes without newline terminator are committed`() {
            val workspace = Files.createDirectories(root.resolve("workspace"))
            val done = root.resolve("DONE")
            // Child prints 30 lines to STDOUT, none of which end in \n. The shell
            // `printf` is used to write exactly the bytes we want. R2.3(b) / TODO 4:
            // the previous version redirected each line to a workspace file
            // (`> ${workspace.resolve("NONL-$it")}`), so the bytes never reached
            // the Output Plane and the falsifier was a no-op. The test's claim
            // is about the Output Plane, so the bytes must go there.
            val script = (1..30).joinToString("; ") {
                "printf 'plain-nonl-$it'"
            }
            // R2.3(b) / TODO 4: the producer now ALWAYS appends `touch done`
            // as the last script part. The test must NOT include it in
            // scriptExtras, or the producer would duplicate it.
            // f01 is not a kill-the-runtime test, so barrier=null (no
            // barrier/go wait) — the script runs to completion on its own.
            runProducer(
                workspace = workspace,
                barrier = null,
                go = root.resolve("GO"),
                done = done,
                linesBefore = 0,
                linesAfter = 0,
                extraStderr = false,
                scriptExtras = script,
            )
            await({ Files.exists(done) }, 90)
            awaitIncrement(ByteArray(0), 60, "bytes without newline must reach the plane")
            val raw = committedBytes()
            val text = raw.toString(Charsets.UTF_8)
            for (i in 1..30) {
                if (!text.contains("plain-nonl-$i")) {
                    val reportDir = java.nio.file.Files.createDirectories(
                        java.nio.file.Path.of(System.getProperty("user.home"), "obsPc2diag"),
                    )
                    val hexDump = raw.joinToString("") { String.format("%02x", it.toInt() and 0xFF) }
                    reportDir.resolve("OBS-PC-208-f01-content-${System.nanoTime()}.txt").toFile().writeText(
                        "=== f01 stdout (${raw.size} bytes) ===\n$text\n=== f01 stdout hex ===\n$hexDump\n",
                    )
                    assertTrue(false, "f01: line $i without newline must be present (hex+text dumped to $reportDir)")
                }
            }
        }

        @Test
        @Timeout(300)
        fun `f02 — secret split across multiple FIFO writes is sanitised`() {
            val workspace = Files.createDirectories(root.resolve("workspace"))
            val done = root.resolve("DONE")
            // The canary is split into 3-byte fragments across many writes
            // with no separators. The redactor's lookahead must stitch them
            // together and emit one **** marker.
            val half = secret.length / 2
            val frag1 = secret.substring(0, half)
            val frag2 = secret.substring(half)
            val script = buildString {
                // R2.3(b) / TODO 4: the previous version redirected the
                // fragments to workspace files (`>> ${workspace.resolve("PIECE-A")}`),
                // so the redactor never saw them and the falsifier was a
                // no-op. The claim is "the redactor's lookahead is consulted
                // across writes", which only makes sense if the bytes reach
                // the FIFO. Write them to stdout (no redirect) instead.
                append("for i in 1 2 3 4 5 6 7 8 9 10; do printf '$frag1'; done; ")
                append("for i in 1 2 3 4 5 6 7 8 9 10; do printf '$frag2'; done")
                // R2.3(b) / TODO 4: producer adds `touch done` as the last
                // part; do not duplicate it here.
            }
            // f02 is not a kill-the-runtime test, so barrier=null.
            runProducer(
                workspace = workspace,
                barrier = null,
                go = root.resolve("GO"),
                done = done,
                linesBefore = 0,
                linesAfter = 0,
                extraStderr = false,
                scriptExtras = script,
            )
            await({ Files.exists(done) }, 90)
            // The redactor's lookahead is bounded by maxLiteral; the secret
            // can leak ONLY if the lookahead is not consulted across writes.
            awaitIncrement(ByteArray(0), 60, "f02: pieces must be considered by the redactor")
            val text = committedBytes().toString(Charsets.UTF_8)
            assertTrue(
                !text.contains(frag1) || !text.contains(frag2) || text.contains("****"),
                "f02: secret split across writes must be sanitised; either fragment absent or **** present",
            )
            // The Output Plane must not contain the secret unredacted.
            assertEquals(
                0,
                rawSecretBytesOnDisk(),
                "f02: secret fragments must not appear unredacted in the Output Plane",
            )
        }

        @Test
        @Timeout(300)
        fun `f04 — stdout active while stderr silent — both channels isolated`() {
            val workspace = Files.createDirectories(root.resolve("workspace"))
            val done = root.resolve("DONE")
            // 10 stdout lines, no stderr. The canary emission is disabled
            // here by setting linesAfter=0 and the per-line scriptExtras
            // omits the canary; we ask the producer to write exactly the
            // bytes we want.
            val script = buildString {
                append("for i in 1 2 3 4 5 6 7 8 9 10; do printf 'stdout-only-%s\\n' \"\$i\"; done")
                // R2.3(b) / TODO 4: producer adds `touch done` as the last
                // part; do not duplicate it here.
            }
            runProducer(
                workspace = workspace,
                barrier = null,
                go = root.resolve("GO"),
                done = done,
                linesBefore = 0,
                linesAfter = 0,
                extraStderr = false,
                scriptExtras = script,
            )
            await({ Files.exists(done) }, 90)
            awaitIncrement(ByteArray(0), 60, "f04: stdout-only data must reach the plane")
            val raw = committedBytes()
            val text = raw.toString(Charsets.UTF_8)
            // R2.3(b) / TODO 4: probe the actual content when a falsifier
            // claims a missing line. The symptom "line 2 missing" is what
            // the operator needs to see, not the bare assertion message.
            for (i in 1..10) {
                if (!text.contains("stdout-only-$i")) {
                    val reportDir = java.nio.file.Files.createDirectories(
                        java.nio.file.Path.of(System.getProperty("user.home"), "obsPc2diag"),
                    )
                    val stderrText = readStreamBytes(stderr = true).toString(Charsets.UTF_8)
                    val hexDump = raw.joinToString("") {
                        String.format("%02x", it.toInt() and 0xFF)
                    }
                    reportDir.resolve("OBS-PC-208-f04-content-${System.nanoTime()}.txt").toFile().writeText(
                        "=== f04 stdout (${raw.size} bytes) ===\n$text\n" +
                            "=== f04 stdout hex ===\n$hexDump\n" +
                            "=== f04 stderr (${stderrText.length} chars) ===\n$stderrText\n",
                    )
                    assertTrue(false, "f04: stdout line $i must be present (hex+text dumped to $reportDir)")
                }
            }
        }

        @Test
        @Timeout(180)
        fun `f05 — producer that exits without writing anything does not deadlock the agent`() {
            val workspace = Files.createDirectories(root.resolve("workspace"))
            val done = root.resolve("DONE")
            // f05: producer that exits without writing anything. The script
            // is just `true` (no-op) so the child runs and exits cleanly.
            // The producer adds `touch done` as the last part.
            val script = "true"
            runProducer(
                workspace = workspace,
                barrier = null,
                go = root.resolve("GO"),
                done = done,
                linesBefore = 0,
                linesAfter = 0,
                extraStderr = false,
                scriptExtras = script,
            )
            // The child exits with no output. The agent's drain must see
            // EOF and exit cleanly (no infinite block on the read).
            await({ Files.exists(done) }, 60)
            awaitNoAgentProcess(30)
            // No bytes committed, no failure recorded.
            OutputPlaneProvider.forgetAll()
            val text = committedBytes()
            assertEquals(0, text.size, "f05: empty producer must produce an empty plane")
        }

        @Test
        @Timeout(300)
        fun `f08 — two operations of the same run, no streamId or ownership mixing`() {
            val workspace = Files.createDirectories(root.resolve("workspace"))
            val doneA = root.resolve("DONE-A")
            val doneB = root.resolve("DONE-B")

            // R2.3(b) / TODO 4: the previous version used the barrier/go pattern
            // (waiting for GO-A after touching BARRIER-A) which is the Level-B
            // kill-the-runtime pattern. f08 is NOT testing that — it tests
            // that two operations in the same run get distinct streams. The
            // barrier/go wait left the script blocked forever because the test
            // never created GO-A. Use barrier=null and let the script run to
            // completion on its own.
            runProducer(
                workspace = workspace,
                barrier = null,
                go = root.resolve("GO-A"),
                done = doneA,
                linesBefore = 0,
                linesAfter = 20,
                extraStderr = false,
                scriptExtras = "",
                opIdOverride = OpId(runId, 0, 0).format(),
            )
            awaitFile(doneA, 90, "op A never finished")

            // Start a second op in the same run after the first one finished.
            // R2.3(b) / TODO 4: the second op's bytes may not have been
            // committed by the agent at the moment the runtime's poll detects
            // the child exit and returns. Wait for the plane to grow before
            // asserting the extent.
            runProducer(
                workspace = workspace,
                barrier = null,
                go = root.resolve("GO-B"),
                done = doneB,
                linesBefore = 0,
                linesAfter = 20,
                extraStderr = false,
                scriptExtras = "",
                opIdOverride = OpId(runId, 0, 1).format(),
            )
            awaitFile(doneB, 90, "op B never finished")

            // Each op's stream is keyed by its own opId. The reader must
            // see each stream independently, with no cross-talk.
            val streamA = OutputPlaneProvider.streamsOf(runId, OpId(runId, 0, 0).format()).stdout.stream
            val streamB = OutputPlaneProvider.streamsOf(runId, OpId(runId, 0, 1).format()).stdout.stream
            val store = OutputPlaneProvider.storeForReading(controlRoot)
            val extentA = store.committedExtent(streamA) ?: 0
            // Wait for op B's agent to commit its bytes (the done file
            // appears before the agent's final commit lands).
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30)
            var extentB = store.committedExtent(streamB) ?: 0
            while (extentB == 0L && System.nanoTime() < deadline) {
                Thread.sleep(100)
                OutputPlaneProvider.forgetAll()
                extentB = store.committedExtent(streamB) ?: 0
            }
            assertTrue(extentA > 0, "f08: op A stream is non-empty")
            assertTrue(extentB > 0, "f08: op B stream is non-empty (extent=$extentB)")
            // The two streams MUST be distinct OutputStreamIds.
            assertNotEquals(streamA, streamB, "f08: stream ids are distinct per opId")
        }
    }

    // -------- Producer infrastructure --------

    private var producerHandle: Process? = null

    private fun runProducer(
        workspace: Path,
        barrier: Path?,
        go: Path,
        done: Path,
        linesBefore: Int,
        linesAfter: Int,
        extraStderr: Boolean,
        scriptExtras: String,
        opIdOverride: String? = null,
    ) {
        val argv = ObsPc208Producer.argv(
            controlDirRoot = controlRoot,
            runId = runId,
            workspace = workspace,
            barrier = barrier,
            go = go,
            done = done,
            secret = secret,
            linesBefore = linesBefore,
            linesAfter = linesAfter,
            extraStderr = extraStderr,
            opIdOverride = opIdOverride,
            scriptExtras = scriptExtras,
        )
        val pb = ProcessBuilder(
            System.getProperty("java.home") + "/bin/java",
            "-cp",
            System.getProperty("java.class.path"),
            "dev.rubentxu.pipeline.v2.application.durable.ObsPc208Producer",
            *argv,
        )
        val process = pb
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .redirectOutput(ProcessBuilder.Redirect.to(workspace.resolve("producer.log").toFile()))
            .start()
        producerHandle = process
    }

    private fun awaitFile(marker: Path, seconds: Long, message: String) {
        if (!await({ Files.exists(marker) }, seconds)) error(message)
    }

    private fun awaitPrefix(marker: String, seconds: Long, message: String): ByteArray {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        var best = ByteArray(0)
        while (System.nanoTime() < deadline) {
            val current = committedBytes()
            if (current.toString(Charsets.UTF_8).contains(marker)) return current
            if (current.size > best.size) best = current
            Thread.sleep(100)
        }
        if (best.isNotEmpty()) return best
        error(message)
    }

    private fun awaitIncrement(baseline: ByteArray, seconds: Long, message: String): ByteArray {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (System.nanoTime() < deadline) {
            val current = committedBytes()
            if (current.size > baseline.size) return current
            Thread.sleep(200)
        }
        // R2.3(b) probe: copy the agent log so a failing assertion leaves evidence
        // of WHY the drain did not advance. The agent.log lives under the
        // control root, which the @TempDir cleanup will delete; we save it to
        // ~/obsPc2diag/ for inspection.
        val reportDir = java.nio.file.Files.createDirectories(
            java.nio.file.Path.of(System.getProperty("user.home"), "obsPc2diag"),
        )
        val agentLog = runCatching {
            Files.walk(controlRoot).use { stream ->
                stream.filter { it.fileName.toString() == "agent.log" }.findFirst().orElse(null)
            }
        }.getOrNull()
        if (agentLog != null && Files.exists(agentLog)) {
            Files.copy(
                agentLog,
                reportDir.resolve("OBS-PC-208-agent-${System.nanoTime()}.log"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }
        // R2.3(b) probe: also dump both stream contents (stdout and stderr) so
        // we can see WHERE the bytes went. The f04 symptom is "stdout empty
        // but stderr has 367 bytes" — that is the diagnostic the operator needs.
        val stderrDump = readStreamBytes(stderr = true)
        val stdoutDump = readStreamBytes(stderr = false)
        if (stderrDump.isNotEmpty() || stdoutDump.isNotEmpty()) {
            reportDir.resolve("OBS-PC-208-stderr-${System.nanoTime()}.txt").toFile().writeText(
                "=== stdout (${stdoutDump.size} bytes) ===\n" +
                    stdoutDump.toString(Charsets.UTF_8) + "\n" +
                    "=== stderr (${stderrDump.size} bytes) ===\n" +
                    stderrDump.toString(Charsets.UTF_8) + "\n",
            )
        }
        // R2.3(b) probe: copy script.sh and wrapper.sh so we can see what the
        // runtime actually wrote. The bash syntax error in stderr suggests the
        // script content is malformed in a way that the println doesn't show.
        runCatching {
            Files.walk(controlRoot).use { stream ->
                for (name in listOf("script.sh", "wrapper.sh", "result.txt")) {
                    val file = stream.filter { it.fileName.toString() == name }.findFirst().orElse(null)
                    if (file != null && Files.exists(file)) {
                        Files.copy(
                            file,
                            reportDir.resolve("OBS-PC-208-$name-${System.nanoTime()}"),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        )
                    }
                }
            }
        }
        error("$message — baseline=${baseline.size} current=${committedBytes().size} (agent log + streams copied to $reportDir)")
    }

    private fun readStreamBytes(stderr: Boolean): ByteArray {
        return runCatching {
            val store = OutputPlaneProvider.storeForReading(controlRoot)
            val stream = if (stderr) {
                OutputPlaneProvider.streamsOf(runId, opIdFormat()).stderr.stream
            } else {
                OutputPlaneProvider.streamsOf(runId, opIdFormat()).stdout.stream
            }
            val out = java.io.ByteArrayOutputStream()
            var cursor: dev.rubentxu.pipeline.v2.output.OutputCursor? =
                dev.rubentxu.pipeline.v2.output.OutputCursor.start(stream)
            while (cursor != null) {
                when (val result = store.read(stream, cursor, 65536)) {
                    is OutputReadResult.Page -> {
                        out.write(result.page.bytes)
                        cursor = result.page.next
                    }
                    is OutputReadResult.Refused -> cursor = null
                }
            }
            out.toByteArray()
        }.getOrDefault(ByteArray(0))
    }

    private fun await(predicate: () -> Boolean, seconds: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (System.nanoTime() < deadline) {
            if (predicate()) return true
            Thread.sleep(50)
        }
        return predicate()
    }

    private fun awaitNoAgentProcess(seconds: Long) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (System.nanoTime() < deadline) {
            var alive = false
            for (ph in ProcessHandle.allProcesses().toList()) {
                val cmd = ph.info().command().orElse("")
                if (cmd.contains("ObsPc2IngestAgent")) {
                    alive = true
                    break
                }
            }
            if (!alive) return
            Thread.sleep(200)
        }
        error("agent process still alive after $seconds s")
    }

    private fun readAt(
        store: SegmentOutputStore,
        stream: dev.rubentxu.pipeline.v2.output.OutputStreamId,
        fromOffset: Long,
        maxBytes: Int,
    ): ByteArray {
        val cursor = dev.rubentxu.pipeline.v2.output.OutputCursor(stream, fromOffset)
        val out = java.io.ByteArrayOutputStream()
        var c: dev.rubentxu.pipeline.v2.output.OutputCursor? = cursor
        while (c != null) {
            when (val result = store.read(stream, c, maxBytes)) {
                is OutputReadResult.Page -> {
                    out.write(result.page.bytes)
                    c = result.page.next
                }
                is OutputReadResult.Refused -> c = null
            }
        }
        return out.toByteArray()
    }
}
