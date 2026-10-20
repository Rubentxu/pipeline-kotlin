package dev.rubentxu.pipeline.v2.application.durable.ingest

import dev.rubentxu.pipeline.v2.application.durable.OutputPlaneProvider
import dev.rubentxu.pipeline.v2.application.durable.RedactingOutputIngress
import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.credentials.api.StreamingRedactor
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ProcessOutputChannel
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributes

/**
 * The production ingest agent of ``
 * A separate JVM that owns the read ends of a step's output, redacts in
 * memory, and commits sanitized bytes to the Output Plane. It knows
 * nothing about Steps, events, journals, results or outcomes — it
 * drains two descriptors and appends. That is the whole of it, and it
 * is what keeps it from being a second runtime.
 * ## What this is and what the prototype is not
 * This file is the **production** version. It was promoted from a
 * prototype (`dev.rubentxu.pipeline.v2.application.durable.ObsPc2IngestAgent`,
 * in `src/test/`) that proved the shape. Neither the prototype nor the two adversarial tests
 * named beside it (`ObsPc2IngestAgentPrototypeUatTest`,
 * `ObsPc2ProducerSurvivalSpikeTest`) exist in this tree any more: the test RESET removed all
 * three in `2aa071aab`. The divergence from the prototype below is therefore stated but
 * unwitnessed.
 * The production version differs from the prototype in three ways:
 * 1. **Package.** This file lives in `application.durable.ingest`,
 *    a production package. The prototype lives in the test
 *    `application.durable` package; cross-package access from test
 *    to main is the convention Kotlin/JVM uses for the in-tree
 *    reference fixture.
 * 2. **Class name and KDoc.** The class is the same (the
 *    `Object ObsPc2IngestAgent` is the load-bearing identifier the
 *    prototype and the runtime launcher agree on), but this KDoc
 *    cross-references the ADR sections the prototype's KDoc
 *    documents:,,,,.
 * 3. **The launch contract.** This `main(args)` is what the
 * Runtime launches via `ProcessBuilder` + `setsid`. The
 *    argv order is fixed by [argv] so the launcher and the agent
 *    cannot drift. The prototype's `argv` is the same shape; the
 *    production agent re-uses it verbatim.
 * ## Why FIFOs and not inherited pipe descriptors
 * Passing a pipe's read end to another process needs `SCM_RIGHTS`,
 * which the JVM does not expose. A FIFO in the control directory is
 * the portable rendezvous: the runtime redirects the child's
 * descriptors *to the FIFO* and this process opens it for reading.
 * No descriptor crosses a process boundary and no kernel handle is
 * shared, which is exactly what lets the agent outlive the runtime.
 * A FIFO is also **not a file**, so nothing unredacted can reach
 * durable storage through it. That is the whole reason the
 * plaintext-spool option was excluded: a regular file written by the
 * child would hold raw bytes, and this does not.
 * That sentence is a property to *check*, not a property the word
 * "fifo" confers. `java.nio.file` cannot create a FIFO, and the
 * first version of the prototype called `Files.createFile` —
 * producing a regular file that silently became the excluded spool.
 * See [openRendezvous].
 * ## Why it opens the FIFO O_RDONLY
 * A read-only open on a named pipe **blocks until a writer appears**. That
 * blocking is the rendezvous, not an obstacle to it: the runtime pre-opens
 * the FIFO (a "warmup" writer) before launching the agent, so the agent's
 * O_RDONLY open is released, the runtime then starts the child, and from
 * that moment the child is the only writer. When the child exits, the last
 * writer closes and the agent's read returns **EOF** — which is exactly the
 * signal the redactor needs to flush its lookahead window and exit.
 * **The previous version of this section argued for O_RDWR and was wrong
 * three times over**, which is why it is worth writing down what went
 * against it rather than deleting it:
 * 1. It stated that a read-only open "succeeds immediately and reports EOF
 *    until a writer appears". That is the opposite of what POSIX does. An
 *    O_RDONLY open blocks, and the whole design depends on it blocking.
 * 2. It presented O_RDWR's real drawback — "the agent cannot see EOF, because
 *    it is itself a writer" — as an acceptable cost to be mitigated by an
 *    explicit seal. In practice it was not a cost, it was the defect: holding
 *    a write reference meant the pipe never reached a zero-writers state when
 *    the child exited, so the redactor blocked forever on the lookahead window
 *    waiting for an EOF that could not arrive.
 * 3. It cited `` as the authority for treating completion as a
 *    separate fact. With O_RDONLY the completion signal is the pipe's own.
 * So O_RDWR was the prototype's design and this is the correction; see
 * [openRendezvous] for the code and the ordering it depends on.
 * ## Security invariant: the FIFO type
 * The type is the security invariant here, so it is checked rather
 * than assumed. [openRendezvous] refuses anything that is not a
 * named pipe; a regular file with a `.fifo` name would silently
 * accept a writer before any reader exists, report EOF to a reader
 * that reached its end, and **persist to disk every byte the child
 * wrote, unredacted**. The measured consequence of skipping this
 * check was the whole defect class in one line: `stdout.fifo` was
 * a 97-byte regular file holding the child's
 * `canary=GHS_DBG_SECRET`, the reader saw EOF the instant it looked
 * at an empty file, declared itself drained, and the Output Plane
 * stayed empty.
 * ## Shape and verification
 * One agent per run, supervised by PipelineK.
 * Verification invariants: `after_lines_written` 0/50 → 50/50,
 * `bytes_after_release` > `bytes_after_kill`, DONE post-mortem,
 * `raw_secret_on_disk == 0`.
 * ## See also
 * - the prototype fixture and the inversion spike, both removed by the test
 *   RESET in `2aa071aab` and neither replaced
*/
object ObsPc2IngestAgent {

    /**
     * Entry point for the launched JVM. The argv order is fixed by
     * [argv] so the launcher ('s wiring in
     * `DurableShellLaunching.launch`) and this agent cannot drift.
     * @param args `controlDirRoot`, `runId`, `opIdString`, `fifoDir`,
     *   `readyMarker`, `secret`
*/
    @JvmStatic
    fun main(args: Array<String>) {
        val controlDirRoot = Paths.get(args[0])
        val runId = args[1]
        val opId = args[2]
        val fifoDir = Paths.get(args[3])
        val readyMarker = Paths.get(args[4])
        val secret = args[5]

        val registry = SecretPatternRegistry().apply { addSecret(SecretHandle.plain(secret)) }
        val store = OutputPlaneProvider.storeForWriting(controlDirRoot)
        val streams = OutputPlaneProvider.streamsOf(runId, opId)
        val frameIndex = store.frameIndex()

        // Print lifecycle events to the agent's
        // stdout. The runtime redirects stdout to controlDir/agent.log,
        // so every println below is a probe line in that file. NO
        // secrets or payloads are emitted — the diagnostics record
        // the byte flow at the 5 frontiers the operator named:
        //   ChildWritten → FifoReceived → Sanitized → OutputCommitted
        // Each frontier is a separate println so the log can be
        // diffed against the spike's `bytes_after_release` and the
        // individual sinks' `refusal()` state.
        val agentPid = ProcessHandle.current().pid()

        // The agent and the test must agree on the
        // stream id and the control root. If either is wrong the
        // "committed N bytes / reader saw 0" shape becomes silent. No
        // payload, no secret: just the typed identity and the layout root.
        System.err.println(
            "pc2-agent-resolve pid=$agentPid runId=$runId opId=$opId " +
                "controlDirRoot=$controlDirRoot " +
                "stdoutStream=${streams.stdout.stream.value} " +
                "stderrStream=${streams.stderr.stream.value} " +
                "storeClass=${store.javaClass.simpleName}",
        )

        val sinks = ProcessOutputChannel.all.associateWith { channel ->
            val address = when (channel) {
                ProcessOutputChannel.STDOUT -> streams.stdout
                ProcessOutputChannel.STDERR -> streams.stderr
            }
            RedactingOutputIngress({ store.open(address.stream) }, frameIndex, address)
        }

        System.err.println(
            "pc2-agent-start pid=$agentPid runId=$runId opId=$opId fifoDir=$fifoDir " +
                "stdoutFifo=${fifoDir.resolve("stdout.fifo")} stderrFifo=${fifoDir.resolve("stderr.fifo")}",
        )

        // The channels are opened O_RDONLY, which BLOCKS until a writer
        // appears -- see [openRendezvous]. This comment previously said
        // O_RDWR and "never blocks, and keeps this process a writer",
        // which contradicted the function called on the very next lines:
        // holding a write reference is what stopped the pipe from ever
        // reaching a zero-writers state, so the redactor waited forever
        // for an EOF that could not arrive.
        val channels = mapOf(
            ProcessOutputChannel.STDOUT to openRendezvous(fifoDir.resolve("stdout.fifo")),
            ProcessOutputChannel.STDERR to openRendezvous(fifoDir.resolve("stderr.fifo")),
        )
        System.err.println("pc2-agent-fifos-opened pid=$agentPid")

        // Both descriptors are open. From here this process can
        // outlive the one that launched it.
        Files.writeString(readyMarker, "ready\n")
        System.err.println("pc2-agent-ready-marker-written pid=$agentPid")

        val threads = ProcessOutputChannel.all.map { channel ->
            // The agent now uses the redactor's state
            // machine directly. The previous design wrapped the FIFO
            // channel in an InputStream and then a RedactingInputStream,
            // which made "no more data" and "end of stream" the same
            // event (close). The state machine separates them: the
            // agent reads from the channel, feeds raw bytes to feed,
            // takes sanitized bytes from take, and only calls finish
            // when the channel returns EOF. The lookahead is then flushed
            // through the same matcher used during feeding, so the
            // secret that crossed the last chunk boundary is detected
            // rather than silently dropped.
            val ch = channels.getValue(channel)
            val redactor = StreamingRedactor(registry)
            val state = redactor.initialState()
            val sink = sinks.getValue(channel)
            Thread({
                val raw = ByteArray(8192)
                val scratch = ByteArray(8192)
                val rawBuffer = ByteBuffer.wrap(raw)
                var totalReceived = 0L
                var totalCommitted = 0L
                var iter = 0L
                try {
                    while (true) {
                        iter++
                        rawBuffer.clear()
                        val n = ch.read(rawBuffer)
                        if (n < 0) {
                            // Kernel-level EOF: the child has closed its
                            // write end of the FIFO. Flush the redactor's
                            // lookahead through the same matcher, then
                            // emit the final bytes through the sink.
                            val final = redactor.finish(state)
                            if (final.isNotEmpty()) {
                                sink.write(final, 0, final.size)
                                totalCommitted += final.size.toLong()
                            }
                            System.err.println(
                                "pc2-agent-pump-eof channel=${channel.name.lowercase()} " +
                                    "iter=$iter totalReceived=$totalReceived " +
                                    "totalCommitted=$totalCommitted " +
                                    "finalFlushedBytes=${final.size}",
                            )
                            break
                        }
                        totalReceived += n.toLong()
                        // Feed raw bytes into the lookahead; whatever is
                        // now decidable is in the output queue.
                        redactor.feed(state, raw, 0, n)
                        // Drain the decided bytes into the sink. The
                        // state machine may emit fewer than `n` bytes
                        // (lookahead holds some) or more (a matched
                        // secret expands to the SCRUB_MARKER).
                        var taken: Int
                        while (true) {
                            taken = redactor.take(state, scratch, 0, scratch.size)
                            if (taken == 0) break
                            sink.write(scratch, 0, taken)
                            totalCommitted += taken.toLong()
                        }
                        if (iter <= 5 || iter % 1000L == 0L) {
                            val refusal = sink.refusal()
                            System.err.println(
                                "pc2-agent-pump-step channel=${channel.name.lowercase()} " +
                                    "iter=$iter n=$n totalReceived=$totalReceived " +
                                    "totalCommitted=$totalCommitted " +
                                    "refusal=" + (refusal?.detail ?: "none"),
                            )
                        }
                    }
                    val refusal = sink.refusal()
                    System.err.println(
                        "pc2-agent-pump-drained channel=${channel.name.lowercase()} " +
                            "totalReceived=$totalReceived totalCommitted=$totalCommitted " +
                            "refusal=" + (refusal?.detail ?: "none"),
                    )
                    sink.close()
                } catch (e: Exception) {
                    // NOT swallowed. A harness that discards the
                    // failing process's exception is a harness that
                    // cannot debug it, and this one already cost a run
                    // reading an empty log while the real cause sat in
                    // the catch block.
                    System.err.println(
                        "pc2-agent-pump-failed channel=${channel.name.lowercase()} " +
                            "iter=$iter totalReceived=$totalReceived totalCommitted=$totalCommitted ex=$e",
                    )
                    System.err.println(e.stackTraceToString())
                }
            }, "pc2-ingest-${channel.name.lowercase()}").apply {
                isDaemon = true
                start()
            }
        }

        threads.forEach { it.join() }
        // Give the sinks a moment to flush, then close them so the
        // reservations are released and the stream is left in the
        // state a recovery would expect.
        sinks.values.forEach { runCatching { it.close() } }
        System.out.println("pc2-agent-drained:${runId}:${opId}")
    }

    /**
     * Open the rendezvous, refusing anything that is not a named
     * pipe.
     * ## Why this refuses instead of opening
     * `java.nio.file` has no `mkfifo`, so the natural way to "create
     * the FIFO" is `Files.createFile`, which creates a **regular
     * file**. A regular file is indistinguishable from a FIFO in a
     * directory listing and behaves differently in every way that
     * matters: it accepts a writer before any reader exists, reports
     * EOF to a reader that reaches its end, and **persists to disk
     * every byte the child writes, unredacted**.
     * The first version of the prototype did exactly that, and the
     * measured consequence was the whole defect class in one line:
     * `stdout.fifo` was a 97-byte regular file holding the child's
     * `canary=GHS_DBG_SECRET`, the reader saw EOF the instant it
     * looked at an empty file, declared itself drained, and the
     * Output Plane stayed empty. That reads like "the drainer does
     * not work" and is actually "the rendezvous is the plaintext
     * The one option this whole design
     * exists to avoid, silently reintroduced by a three-line
     * convenience call.
     * The type is the security invariant here, so it is checked
     * rather than assumed.
*/
    private fun openRendezvous(path: Path): SeekableByteChannel {
        require(isFifo(path)) {
            "$path is not a FIFO. Draining it would accept a plaintext spool on disk, " +
                "which the raw_secret_on_disk == 0 invariant is meant to catch."
        }
        // Open O_RDONLY. The O_RDONLY open on a named pipe blocks until
        // a writer appears, which is exactly the rendezvous we want: the
        // child opens the FIFO for write via `Redirect.to`, the kernel unblocks
        // our open, and we start reading.
        // The previous O_RDWR open made the agent itself a writer, so the
        // pipe never reached a "zero writers" state when the child exited
        // and the redactor blocked forever on the lookahead window,
        // waiting for an EOF that could not arrive. With O_RDONLY the
        // kernel's read returns EOF the moment the LAST writer closes its
        // end, which is the honest signal the redactor needs to flush
        // the lookahead and exit.
        // The runtime pre-opens the FIFO in O_RDWR mode (a "warmup")
        // before launching the agent. The warmup is a writer while the
        // runtime JVM is alive; once the agent's O_RDONLY open succeeds,
        // the runtime starts the child (which opens for write), the child
        // is the only writer, and when the child exits the agent sees EOF.
        return Files.newByteChannel(path, StandardOpenOption.READ)
    }

    /**
     * Whether [path] is a named pipe.
     * `java.nio.file` cannot create one and exposes no direct type
     * test, but [PosixFileAttributes.isOther] answers it exactly: a
     * FIFO is neither a regular file nor a directory, which is
     * precisely what `isOther` means. Asking the JDK keeps this
     * check in the same trust domain as the read that follows it,
     * instead of spawning `test -p` and parsing its exit code.
*/
    fun isFifo(path: Path): Boolean = runCatching {
        Files.readAttributes(path, PosixFileAttributes::class.java).isOther
    }.getOrDefault(false)

    /**
     * Convenience so the launcher ('s
     * `DurableShellLaunching.launch`) and this agent cannot drift on
     * argument order.
     * Identical to the prototype's `argv`; the production launcher
     * uses the same shape. The three fixtures that used to drive the agent through it
     * (`ObsPc2JvmOwnerDeathProducer`, `ObsPc2IngestAgentPrototypeUatTest`,
     * `ObsPc2ProducerSurvivalSpikeTest`) were all removed by the test RESET in `2aa071aab`,
     * so the `argv` shape above is stated, not checked.
*/
    fun argv(
        controlDirRoot: Path,
        runId: String,
        opId: String,
        fifoDir: Path,
        readyMarker: Path,
        secret: String,
    ): Array<String> = arrayOf(
        controlDirRoot.toString(),
        runId,
        opId,
        fifoDir.toString(),
        readyMarker.toString(),
        secret,
    )

    /** Marker text the launcher/test waits for, so "the agent is
     *  attached" is an event and not a sleep. */
    const val READY_CONTENT: String = "ready\n"

    /** Bytes of a string, for harnesses that need to size
     *  expectations without reading the plane. */
    fun utf8(text: String): ByteArray = text.toByteArray(StandardCharsets.UTF_8)
}
