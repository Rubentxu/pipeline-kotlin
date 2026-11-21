package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.credentials.api.StreamingRedactor
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.ExecutionOutputSink
import dev.rubentxu.pipeline.v2.domain.durable.OutputChunk
import dev.rubentxu.pipeline.v2.domain.durable.TaskExecutionRequest
import dev.rubentxu.pipeline.v2.domain.durable.TaskSpec
import dev.rubentxu.pipeline.v2.domain.durable.TaskStream
import dev.rubentxu.pipeline.v2.events.durable.DurableStores
import dev.rubentxu.pipeline.v2.events.durable.EventStoreAdmission
import dev.rubentxu.pipeline.v2.events.durable.EventStorePurpose
import dev.rubentxu.pipeline.v2.events.durable.admitEventStore
import dev.rubentxu.pipeline.v2.output.OutputChannel
import dev.rubentxu.pipeline.v2.output.OutputFrame
import dev.rubentxu.pipeline.v2.output.OutputReadResult
import dev.rubentxu.pipeline.v2.runtime.durable.OutputPlaneProvider
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeObservation
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.task.ProcessDurableTaskRuntime
import dev.rubentxu.pipeline.v2.service.ServiceAnswer
import dev.rubentxu.pipeline.v2.service.ServiceClient
import dev.rubentxu.pipeline.v2.service.ServiceRunStart
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * The PRODUCER of the Output Plane sanitizes BEFORE it persists and BEFORE it assigns the ranges.
 *
 * ## The guarantee under trial
 *
 * That the bytes the producer writes into the Output Plane are the bytes the REDUCER produced,
 * and that the byte ranges the producer assigns describe those bytes and not the ones the child
 * wrote. The secret is printed by the step, SPLIT between two writes, because a producer that
 * only masks a secret contained in a single chunk is not a producer that masks secrets.
 *
 * ## The capacity this serves
 *
 * A consumer of the Output Plane — the console of a service, the transcript of a CI system —
 * reads the bytes the producer persisted and addresses them by the ranges the producer assigned.
 * If the producer persisted the raw bytes and assigned the ranges of the sanitized ones (or the
 * other way round), the two would describe different things and every reader would be wrong in a
 * way that looks right: the offsets would be plausible and the content would leak.
 *
 * ## Why the oracle is the REDUCER and not a literal
 *
 * The law asserts that the persisted bytes ARE the reducer's output, computed by running the
 * reducer over the bytes the child wrote. It never asserts that the output contains a marker, or
 * that it does not contain a particular text: a law written that way would pass for a producer
 * that masks the secret with the WRONG shape, and would keep passing when the reducer changed.
 * The oracle is the class that does the masking, so the law survives the mask changing and dies
 * when the masking stops happening.
 *
 * ## Why this is an INTEGRATION test
 *
 * The facts under trial are about a MACHINE: a real child process that prints a secret, a real
 * pump that reads it, a real store that persists it and a real frame index that numbers the
 * ranges. A fake sink would prove that the code calls a redactor; it would not prove that the
 * bytes the store holds are the ones the redactor produced.
 *
 * ## What this does NOT freeze
 *
 * The mask itself, the number of frames the split produced, and the identity of the run: those
 * are the producer's business. What is frozen is the RELATION between the persisted bytes and
 * the assigned ranges.
 */
@Timeout(value = 600, unit = java.util.concurrent.TimeUnit.SECONDS)
class ProducerOutputRedactionIntegrationTest {

    @TempDir
    lateinit var root: Path

    /**
     * The CLI path: the operator runs a script and the product persists what the run printed.
     */
    @Test
    fun `the CLI path persists the reduced bytes and assigns the ranges of those bytes`() {
        val control = Files.createDirectories(root.resolve("cli-control"))
        val script = root.resolve("cli.pipeline.kts")
        Files.writeString(script, SCRIPT)

        val receipt = runCliChild(script, control)
        val transcript = transcriptOf(control, receipt.runId)

        assertTheProducerReducedBeforeItPersisted(transcript)
    }

    /**
     * The service path: a client asks the service over a socket, and the service runs the
     * pipeline in a child process of its own.
     */
    @Test
    fun `the service path persists the reduced bytes and assigns the ranges of those bytes`() {
        val control = Files.createDirectories(root.resolve("service-control"))
        val scripts = Files.createDirectories(root.resolve("scripts"))
        val script = scripts.resolve("secret.pipeline.kts")
        Files.writeString(script, SCRIPT)
        val db = root.resolve("service.db")
        materialiseStore(db, control)

        val service = startService(control, db, scripts)
        try {
            val answer = client(STARTER_TOKEN).startRun("secret.pipeline.kts")
            val started = answer as? ServiceAnswer.Answered
            val run = started?.value as? ServiceRunStart
            requireNotNull(run) { "the service answered the start with: $answer" }

            awaitTerminal(run.runId)

            val transcript = transcriptOf(control, run.runId)
            assertTheProducerReducedBeforeItPersisted(transcript)
        } finally {
            service.stop()
        }
    }

    // ------------------------------------------------------------------ the law

    /**
     * The law, applied to one transcript.
     *
     * Three facts, and the third is the one that says the FORM:
     *
     *  1. the secret does not reach the persisted bytes;
     *  2. the ranges the producer assigned are exactly the ranges of the persisted bytes;
     *  3. the persisted bytes ARE the reducer's output over the bytes the child wrote.
     */
    private fun assertTheProducerReducedBeforeItPersisted(transcript: Transcript) {
        // 1. The secret does not escape. The secret is checked in the form the child wrote it
        //    AND in the encodings the registry knows it by: a producer that masked only the
        //    plain form would still publish the hex form.
        val encodings = secretEncodings()
        val persisted = transcript.bytes.toString(Charsets.ISO_8859_1)
        encodings.forEach { encoding ->
            assertFalse(
                persisted.contains(encoding),
                "the secret reached the persisted bytes in the form '$encoding'",
            )
        }

        // 2. The ranges the producer assigned ARE the ranges of the bytes it persisted: they
        //    start at zero, they touch one another without a gap and without an overlap, and
        //    they end where the committed extent ends. A producer that assigned the ranges of
        //    the RAW bytes while persisting the reduced ones would leave a gap here.
        assertTrue(
            transcript.frames.isNotEmpty(),
            "the producer assigned no range at all to the bytes it persisted",
        )
        var cursor = 0L
        transcript.frames.forEach { frame ->
            assertEquals(
                cursor,
                frame.from,
                "the producer left a gap (or an overlap) before the range of frame ${frame.ordinal}",
            )
            cursor = frame.to
        }
        assertEquals(
            transcript.committed,
            cursor,
            "the ranges the producer assigned do not end where the committed bytes end",
        )

        // 3. The FORM: the persisted bytes ARE the reducer's output over the bytes the child
        //    wrote. The oracle is the reducer, computed here and not read from the store, so the
        //    assertion is about the SHAPE of the pipeline and not about the text of a mask.
        assertArrayEquals(
            reducedBytes(),
            transcript.bytes,
            "the persisted bytes are not the reducer's output: the producer persisted something else",
        )

        // And the ranges address exactly those bytes: reading at the ranges returns them.
        val readAtRanges = transcript.frames.fold(ByteArray(0)) { sink, frame ->
            sink + transcript.read(frame.from, frame.to)
        }
        assertArrayEquals(
            transcript.bytes,
            readAtRanges,
            "the ranges the producer assigned do not address the bytes it persisted",
        )
    }

    /**
     * The bytes the reducer produces over the bytes the child wrote.
     *
     * The child writes the secret in two pieces, and the reducer is fed the same two pieces, so
     * the oracle is the reducer's answer for the chunking the producer really saw.
     */
    private fun reducedBytes(): ByteArray {
        val registry = SecretPatternRegistry().apply { addSecret(SecretHandle.plain(SECRET)) }
        val redactor = StreamingRedactor(registry)
        val state = redactor.initialState()
        val scratch = ByteArray(4096)
        val produced = java.io.ByteArrayOutputStream()
        // The two writes of the step: the first carries the head of the secret, the second
        // carries the rest of it AND the newline the step's `printf` writes with it.
        listOf(FIRST_HALF, SECOND_HALF + "\n").forEach { piece ->
            val bytes = piece.toByteArray()
            redactor.feed(state, bytes, 0, bytes.size)
            while (true) {
                val taken = redactor.take(state, scratch, 0, scratch.size)
                if (taken == 0) break
                produced.write(scratch, 0, taken)
            }
        }
        produced.write(redactor.finish(state))
        return produced.toByteArray()
    }

    /** The forms of the secret that a producer that masks it has to mask. */
    @OptIn(ExperimentalStdlibApi::class)
    private fun secretEncodings(): List<String> {
        val raw = SECRET.toByteArray()
        val hex = raw.toHexString()
        return listOf(SECRET, hex, hex.uppercase())
    }

    // ------------------------------------------------------------------ the CLI path

    /**
     * The CLI's own entry, in a child JVM of its own: the same `main` the launcher of the
     * distribution starts, with the `run` verb.
     */
    private fun runCliChild(script: Path, control: Path): Receipt {
        val argv = listOf(
            javaBinary(),
            "-cp",
            System.getProperty("java.class.path"),
            MAIN_CLASS,
            "run",
            "--db",
            root.resolve("cli.db").toString(),
            "--control-root",
            control.toString(),
            script.toString(),
        )
        val captured = CapturedOutput()
        val runtime = ProcessDurableTaskRuntime(root.resolve("cli-supervisions"), clock) { builder ->
            builder.environment()["JAVA_TOOL_OPTIONS"] = "-D$CANARY_PROPERTY=true"
            builder.start()
        }
        val request = TaskExecutionRequest(
            task = TaskSpec.ExecTask(argv),
            runId = RunId("ac17-cli"),
            opId = "ac17-cli",
            timeoutMs = CHILD_DEADLINE_MILLIS,
            workspaceRoot = script.parent?.toString(),
        )
        val result = runBlocking { runtime.execute(request, captured) }
        assertEquals(
            0,
            result.exitCode,
            "the CLI child did not finish well: ${captured.text()}",
        )
        return receiptOf(captured.text())
    }

    // ------------------------------------------------------------------ the service path

    /**
     * The service, over a real socket, with the real supervisor.
     */
    private fun startService(control: Path, db: Path, scripts: Path): Service {
        val admission = admitEventStore(db.toString(), EventStorePurpose.Read)
        val store = (admission as EventStoreAdmission.Admitted).store
        val supervisionRoot = root.resolve("service-supervisions")
        val registry = when (val read = ServiceStartRegistry.read(root.resolve(ServiceStartRegistry.FILE_NAME))) {
            is ServiceStartRegistryReading.Read -> read.registry
            is ServiceStartRegistryReading.Refused -> error(read.detail)
        }
        val supervisor = ServiceRunSupervisor(
            admission = ServiceStartAdmission(scripts, null),
            runPolicy = ServiceRunPolicy(
                eventStore = db.toAbsolutePath().toString(),
                controlRoot = control.toAbsolutePath().toString(),
                deadlineMillis = CHILD_DEADLINE_MILLIS,
                allowNetwork = false,
            ),
            command = ServiceRunCommand(
                javaBinary = javaBinary(),
                classpath = System.getProperty("java.class.path"),
                mainClass = MAIN_CLASS,
            ),
            // The child of the service is a JVM of its own, and the registry that decides what
            // it masks is built by that JVM. The property that registers the synthetic secret
            // has to reach it, and the environment of the child is where a JVM reads it from.
            port = ProcessDurableTaskRuntime(supervisionRoot, clock) { builder ->
                builder.environment()["JAVA_TOOL_OPTIONS"] = "-D$CANARY_PROPERTY=true"
                builder.start()
            },
            registry = registry,
            limits = ServiceStartLimits(maxSupervisedRuns = 2, startDeadlineMillis = CHILD_DEADLINE_MILLIS),
            supervisionRoot = supervisionRoot,
        )
        val operations = ServiceOperations(
            introspection = composeRuntimeIntrospectionPort(
                eventStore = store,
                controlRoot = control,
                clock = clock,
            ),
            substrate = {
                ServiceSubstrate(
                    eventStore = db.toString(),
                    controlRoot = control.toString(),
                    eventStorePresent = Files.exists(db),
                    controlRootPresent = Files.exists(control),
                )
            },
            starts = supervisor,
            cancellations = null,
        )
        val stores = DurableStores.open(store, control, clock)
        val streets = ServiceSubscription(
            lanes = ComposeLanes.forService(store, control),
            events = composeEventRecordReadPort(store, stores.lease),
        )
        val socket = root.resolve("service.sock")
        val transport = UnixSocketTransport.bind(socket)
        val server = ServiceServer(transport, operations, streets, authorizations())
        val thread = Thread({ server.serve() }, "service-under-test").apply {
            isDaemon = true
            start()
        }
        return Service(server, supervisor, store, thread)
    }

    /**
     * The authorizations this service knows: one that may start a run.
     */
    private fun authorizations(): ServiceAuthorizations = ServiceAuthorizations.of(
        listOf(
            ServiceAuthorization(STARTER_TOKEN, "starter", ServiceScope.WithRights(ServiceScope.All, setOf(ServiceScope.Right.Start))),
        ),
    )

    /**
     * The terminal observation of [runId], asked for until it arrives.
     */
    private fun awaitTerminal(runId: String): RuntimeObservation.Terminal {
        val deadline = System.currentTimeMillis() + CHILD_DEADLINE_MILLIS
        while (System.currentTimeMillis() < deadline) {
            val answer = client(STARTER_TOKEN).runState(runId)
            if (answer is ServiceAnswer.Answered) {
                val observation = answer.value
                if (observation is RuntimeObservation.Terminal) return observation
            }
            Thread.sleep(500L)
        }
        error("the run $runId did not finish in time")
    }

    /**
     * A client that presents [token].
     */
    private fun client(token: String): ServiceClient = ServiceClient(
        endpoint = socket,
        credential = token,
        deadline = java.time.Duration.ofSeconds(240),
        schema = dev.rubentxu.pipeline.v2.service.ServiceProtocol.SCHEMA,
    )

    /**
     * The socket the service listens on.
     */
    private val socket: Path get() = root.resolve("service.sock")

    // ------------------------------------------------------------------ reading the plane

    /**
     * What the producer persisted and the ranges it assigned, read through the product's own
     * read side: the store and the frame index are the authorities, and a reader that parsed
     * the files on disk would be measuring a layout instead of a guarantee.
     */
    private fun transcriptOf(control: Path, runId: String): Transcript {
        val store = OutputPlaneProvider.storeForReading(control)
        val frames = store.frameIndex()
            .framesOfRun(runId, -1L, FRAME_LIMIT)
            .filter { it.channel == OutputChannel.STDOUT }
            .sortedBy { it.from }
        assertTrue(frames.isNotEmpty(), "the run $runId holds no frame for its standard output")
        val stream = frames.first().stream
        val committed = store.committedExtent(stream)
        requireNotNull(committed) { "the stream $stream has no committed extent" }
        return Transcript(
            runId = runId,
            frames = frames,
            committed = committed,
            read = { from, to ->
                when (val page = store.readRange(stream, from, to)) {
                    is OutputReadResult.Page -> page.page.bytes
                    is OutputReadResult.Refused -> error("the range [$from, $to) was refused: ${page.reason}")
                }
            },
        )
    }

    /**
     * The transcript of one run: the ranges the producer assigned, and the way to read at them.
     */
    private class Transcript(
        val runId: String,
        val frames: List<OutputFrame>,
        val committed: Long,
        val read: (Long, Long) -> ByteArray,
    ) {
        /** The bytes the producer persisted, read from the first byte to the committed end. */
        val bytes: ByteArray get() = read(0L, committed)
    }

    /**
     * The receipt of a launch, read from what the child printed.
     */
    private fun receiptOf(text: String): Receipt {
        val line = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("{") && it.contains(LaunchReceipt.SCHEMA) }
        requireNotNull(line) { "the child printed no launch receipt: $text" }
        val document = Json.parseToJsonElement(line) as kotlinx.serialization.json.JsonObject
        return Receipt(document.getValue("runId").jsonPrimitive.content)
    }

    /**
     * The identity of the run a receipt names.
     */
    private class Receipt(val runId: String)

    /**
     * What a child printed, collected by the sink the run wrote to.
     */
    private class CapturedOutput : ExecutionOutputSink {
        private val chunks = ConcurrentHashMap<TaskStream, MutableList<ByteArray>>()

        override suspend fun append(chunk: OutputChunk) {
            chunks.computeIfAbsent(chunk.stream) { java.util.concurrent.CopyOnWriteArrayList() }
                .add(chunk.data)
        }

        /** Everything the child printed, on either stream. */
        fun text(): String = chunks.values.flatten()
            .fold(ByteArrayOutputStream()) { sink, bytes -> sink.write(bytes); sink }
            .toString(Charsets.UTF_8)
    }

    /**
     * The service, and the way to stop it.
     */
    private class Service(
        private val server: ServiceServer,
        private val supervisor: ServiceRunSupervisor,
        private val store: dev.rubentxu.pipeline.v2.events.durable.SqliteEventStore,
        private val thread: Thread,
    ) {
        /** Stops the service and the runs it supervises. */
        fun stop() {
            server.close()
            supervisor.close()
            thread.join(10_000L)
            store.close()
        }
    }

    /**
     * The store the service reads and the run writes, created before the service opens it.
     */
    private fun materialiseStore(db: Path, control: Path) {
        val admission = admitEventStore(db.toString(), EventStorePurpose.Produce)
        val writable = (admission as EventStoreAdmission.Admitted).store
        DurableStores.open(writable, control, clock)
        writable.close()
    }

    /** The JVM this test runs in, and the one its children run in. */
    private fun javaBinary(): String =
        Path.of(System.getProperty("java.home"), "bin", "java").toString()

    /** The clock this lane stamps its material with: it does not move. */
    private object clock : Clock {
        override fun now(): Instant = Instant.EPOCH
    }

    private companion object {
        /** The entry point the launcher of the distribution starts, and the service's child. */
        private const val MAIN_CLASS: String = "dev.rubentxu.pipeline.v2.application.MainKt"

        /**
         * The property that makes the product register the synthetic secret below.
         *
         * The secret is the product's OWN canary: it is registered in the registry exactly the
         * way a credential value is, and it protects nothing because it is in no real credential.
         */
        private const val CANARY_PROPERTY: String = "pipelinek.redaction.canaries"

        /** The synthetic secret the step prints. */
        private const val SECRET: String = "GHS6_CANARY_7f3a9c2e1b4d5e6f"

        /** The first half of the secret, written by the first write of the step. */
        private const val FIRST_HALF: String = "GHS6_CAN"

        /** The second half of the secret, written by the second write of the step. */
        private const val SECOND_HALF: String = "ARY_7f3a9c2e1b4d5e6f"

        /** The token of the client that may start a run. */
        private const val STARTER_TOKEN: String = "token-of-the-starter"

        /** How long a child of this test may take. */
        private const val CHILD_DEADLINE_MILLIS: Long = 240_000L

        /** How many frames of one run are read. */
        private const val FRAME_LIMIT: Int = 4096

        /**
         * The script under trial.
         *
         * The step writes the secret in two pieces with a pause between them, so the producer's
         * pump reads them in two reads and the reducer has to hold its window across the seam.
         */
        private val SCRIPT: String = """
            pipeline {
                stages {
                    stage("prints") {
                        sh("printf '%s' '$FIRST_HALF'; sleep 0.4; printf '%s\\n' '$SECOND_HALF'")
                    }
                }
            }
        """.trimIndent()
    }
}
