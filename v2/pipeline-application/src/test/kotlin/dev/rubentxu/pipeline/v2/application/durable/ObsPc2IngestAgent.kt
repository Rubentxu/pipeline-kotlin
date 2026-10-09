package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.credentials.api.StreamingRedactor
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ProcessOutputChannel
import java.nio.channels.Channels
import java.nio.channels.SeekableByteChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributes

/**
 * The ingest agent of `ADR-OBS-003`, as a prototype: it proves the *shape* before any production wiring.
 *
 * ## What it is
 *
 * A separate JVM that owns the read ends of a step's output, redacts in memory and commits sanitized
 * bytes to the Output Plane. It knows nothing about Steps, events, journals, results or outcomes — it
 * drains two descriptors and appends. That is the whole of it, and it is what keeps it from being a
 * second runtime.
 *
 * ## Why FIFOs and not inherited pipe descriptors
 *
 * Passing a pipe's read end to another process needs `SCM_RIGHTS`, which the JVM does not expose. A FIFO
 * in the control directory is the portable rendezvous: the runtime redirects the child's descriptors
 * *to the FIFO* and this process opens it for reading. No descriptor crosses a process boundary and no
 * kernel handle is shared, which is exactly what lets the agent outlive the runtime.
 *
 * A FIFO is also **not a file**, so nothing unredacted can reach durable storage through it. That is the
 * whole reason the plaintext-spool option was excluded: a regular file written by the child would hold
 * raw bytes, and this does not.
 *
 * That sentence is a property to *check*, not a property the word "fifo" confers. `java.nio.file` cannot
 * create a FIFO, and the first version of this prototype called `Files.createFile` — producing a regular
 * file that silently became the excluded spool. See [openRendezvous].
 *
 * ## Why it opens the FIFO O_RDWR
 *
 * Opening a FIFO read-only succeeds immediately but reports EOF until a writer appears, and a read end
 * with no writer is indistinguishable from a finished child. O_RDWR on a FIFO never blocks and keeps a
 * writer reference alive for this process, so "no data yet" is not mistaken for "the child is done".
 *
 * The cost is stated rather than hidden: **the agent cannot see EOF**, because it is itself a writer. It
 * therefore cannot tell that a child exited by watching the pipe. Completion is a separate fact — the
 * step's terminal, or a seal — and conflating "no data right now" with "finished" is precisely the bug
 * `ADR-0085` forbids. A production agent needs an explicit seal; this prototype ends by being killed.
 *
 * ## Fidelity
 *
 * HF3-ish: a real child, real FIFOs, a real redactor, and the genuine `RedactingOutputIngress` into a
 * real store. What it does NOT yet prove is the production wiring: `DurableShellExecutor` still hands the
 * child `Redirect.PIPE` to this process's own JVM, which is the gap the implementation would close.
 */
object ObsPc2IngestAgent {

    /**
     * @param args `controlDirRoot`, `runId`, `opIdString`, `fifoDir`, `readyMarker`, `secret`
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

        val sinks = ProcessOutputChannel.all.associateWith { channel ->
            val address = when (channel) {
                ProcessOutputChannel.STDOUT -> streams.stdout
                ProcessOutputChannel.STDERR -> streams.stderr
            }
            frameIndex.declareStream(address.stream, address.channel)
            RedactingOutputIngress(store.open(address.stream), frameIndex, address)
        }

        // O_RDWR on a FIFO: never blocks, and keeps this process a writer so the read side never
        // reports a spurious EOF before the child has started.
        val channels = mapOf(
            ProcessOutputChannel.STDOUT to openRendezvous(fifoDir.resolve("stdout.fifo")),
            ProcessOutputChannel.STDERR to openRendezvous(fifoDir.resolve("stderr.fifo")),
        )

        // Both descriptors are open. From here this process can outlive the one that launched it.
        Files.writeString(readyMarker, "ready\n")

        val threads = ProcessOutputChannel.all.map { channel ->
            val input = Channels.newInputStream(channels.getValue(channel))
            val redacted = StreamingRedactor(registry).wrap(input)
            val sink = sinks.getValue(channel)
            Thread({
                val buffer = ByteArray(8192)
                try {
                    while (true) {
                        // Ask for what is READY, not for a whole buffer — the same gate the production
                        // pump uses. RedactingInputStream fills its own input buffer before it decides
                        // anything, so asking for 8 KiB when the child has written 97 bytes and parked
                        // blocks forever. The first version of this prototype did exactly that, and the
                        // FIFO stayed full while the plane stayed empty.
                        val ready = maxOf(1, minOf(buffer.size, redacted.available()))
                        val n = redacted.read(buffer, 0, ready)
                        if (n < 0) break
                        sink.write(buffer, 0, n)
                    }
                } catch (e: Exception) {
                    // NOT swallowed. A harness that discards the failing process's exception is a harness
                    // that cannot debug it, and this one already cost a run reading an empty log while the
                    // real cause sat in the catch block.
                    System.err.println("pc2-agent-pump-${channel.name.lowercase()}-failed: $e")
                    e.printStackTrace()
                }
            }, "pc2-ingest-${channel.name.lowercase()}").apply {
                isDaemon = true
                start()
            }
        }

        threads.forEach { it.join() }
        // Give the sinks a moment to flush, then close them so the reservations are released and the
        // stream is left in the state a recovery would expect.
        sinks.values.forEach { runCatching { it.close() } }
        System.out.println("pc2-agent-drained:${runId}:${opId}")
    }

    /**
     * Open the rendezvous, refusing anything that is not a named pipe.
     *
     * ## Why this refuses instead of opening
     *
     * `java.nio.file` has no `mkfifo`, so the natural way to "create the FIFO" is `Files.createFile`,
     * which creates a **regular file**. A regular file is indistinguishable from a FIFO in a directory
     * listing and behaves differently in every way that matters: it accepts a writer before any reader
     * exists, reports EOF to a reader that reaches its end, and **persists to disk every byte the child
     * writes, unredacted**.
     *
     * The first version of this prototype did exactly that, and the measured consequence was the whole
     * defect class in one line: `stdout.fifo` was a 97-byte regular file holding the child's
     * `canary=GHS_DBG_SECRET`, the reader saw EOF the instant it looked at an empty file, declared
     * itself drained, and the Output Plane stayed empty. That reads like "the drainer does not work"
     * and is actually "the rendezvous is the plaintext spool ADR-OBS-003 excludes" — the one option
     * this whole design exists to avoid, silently reintroduced by a three-line convenience call.
     *
     * The type is the security invariant here, so it is checked rather than assumed.
     */
    private fun openRendezvous(path: Path): SeekableByteChannel {
        require(isFifo(path)) {
            "$path is not a FIFO. Draining it would accept a plaintext spool on disk, " +
                "which ADR-OBS-003 excludes and which raw_secret_on_disk == 0 is meant to catch."
        }
        return Files.newByteChannel(path, StandardOpenOption.READ, StandardOpenOption.WRITE)
    }

    /**
     * Whether [path] is a named pipe.
     *
     * `java.nio.file` cannot create one and exposes no direct type test, but
     * [PosixFileAttributes.isOther] answers it exactly: a FIFO is neither a regular file nor a
     * directory, which is precisely what `isOther` means. Asking the JDK keeps this check in the same
     * trust domain as the read that follows it, instead of spawning `test -p` and parsing its exit code.
     */
    fun isFifo(path: Path): Boolean = runCatching {
        Files.readAttributes(path, PosixFileAttributes::class.java).isOther
    }.getOrDefault(false)

    /** Convenience so the launcher and this agent cannot drift on argument order. */
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

    /** Marker text the launcher/test waits for, so "the agent is attached" is an event and not a sleep. */
    const val READY_CONTENT: String = "ready\n"

    /** Bytes of a UTF-8 string, for harnesses that need to size expectations without reading the plane. */
    fun utf8(text: String): ByteArray = text.toByteArray(StandardCharsets.UTF_8)
}