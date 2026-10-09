package dev.rubentxu.pipeline.v2.application.durable

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/**
 * The dying runtime JVM, as a real process.
 *
 * It does exactly what `DurableShellExecutor` does at the descriptor level — creates the step's output
 * destinations, launches the child with its channels attached to them — and then does something the real
 * executor never does: **park forever**, so the test can `kill -9` it and observe what survives.
 *
 * The difference from production is the whole point. Here the destinations are FIFOs owned by a separate
 * process ([ObsPc2IngestAgent]) rather than `Redirect.PIPE` owned by this JVM. Everything else is the
 * genuine article: a real child, `setsid` so it is not signalled by this process's death, a script on
 * disk rather than in argv, and a wait-for-ready barrier so "the drainer is attached" is an event.
 *
 * ## Why parking instead of waiting for the child
 *
 * A runtime that waits for its child and returns is a runtime that behaves. To ask what happens to the
 * child's output after the runtime is gone, the runtime has to still be there at the moment of the kill,
 * and the test has to be the one that decides when that is.
 */
object ObsPc2RuntimeLauncher {

    /**
     * @param args `controlDirRoot`, `runId`, `opId`, `workspace`, `fifoDir`, `readyMarker`, `secret`,
     *   `linesBefore`, `linesAfter`
     */
    @JvmStatic
    fun main(args: Array<String>) {
        val controlDirRoot = Paths.get(args[0])
        val runId = args[1]
        val opId = args[2]
        val workspace = Paths.get(args[3])
        val fifoDir = Paths.get(args[4])
        val readyMarker = Paths.get(args[5])
        val secret = args[6]
        val linesBefore = args[7].toInt()
        val linesAfter = args[8].toInt()

        Files.createDirectories(fifoDir)
        Files.createDirectories(workspace)
        val stdoutFifo = createRendezvous(fifoDir.resolve("stdout.fifo"))
        val stderrFifo = createRendezvous(fifoDir.resolve("stderr.fifo"))

        // The drainer goes first: it opens both descriptors and is attached before the child exists.
        val agent = ProcessBuilder(
            System.getProperty("java.home") + "/bin/java",
            "-cp",
            System.getProperty("java.class.path"),
            "dev.rubentxu.pipeline.v2.application.durable.ObsPc2IngestAgent",
            *ObsPc2IngestAgent.argv(controlDirRoot, runId, opId, fifoDir, readyMarker, secret),
        )
            // NOT discarded: a harness that throws away the failing process's diagnostics cannot
            // debug it. This file is what tells us why nothing reached the plane.
            .redirectError(ProcessBuilder.Redirect.to(workspace.resolve("agent.err.log").toFile()))
            .redirectOutput(ProcessBuilder.Redirect.to(workspace.resolve("agent.log").toFile()))
            .start()

        val readyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
        while (!Files.exists(readyMarker) && System.nanoTime() < readyDeadline) Thread.sleep(25)
        if (!Files.exists(readyMarker)) {
            System.err.println("the ingest agent never became ready")
            agent.destroyForcibly()
            return
        }
        Files.writeString(workspace.resolve("AGENT-READY"), "ok\n")

        val barrier = workspace.resolve("BARRIER")
        val go = workspace.resolve("GO")
        val plainBefore = (1..linesBefore).joinToString("; ") { "printf 'plain-before-$it;'" }
        val plainAfter = (1..linesAfter).joinToString("; ") { "printf 'plain-after-$it;'" }
        val scriptFile = workspace.resolve("child.sh")
        Files.writeString(
            scriptFile,
            buildString {
                append("#!/bin/sh\n")
                append(plainBefore)
                append("; printf 'canary=$secret;'")
                append("; touch ").append(barrier)
                append("; while [ ! -f ").append(go).append(" ]; do sleep 0.05; done")
                append("; ").append(plainAfter)
                append("; touch ").append(workspace.resolve("DONE"))
            },
        )

        // setsid: the child gets its own session, so this process's death does not signal it. Without
        // that, the row would be measuring SIGHUP rather than the descriptor question.
        ProcessBuilder("setsid", "sh", scriptFile.toString())
            .directory(workspace.toFile())
            .redirectOutput(ProcessBuilder.Redirect.appendTo(stdoutFifo.toFile()))
            .redirectError(ProcessBuilder.Redirect.appendTo(stderrFifo.toFile()))
            .start()

        // Park. The test kills this JVM and then releases the child.
        while (true) Thread.sleep(1000)
    }

    /**
     * Create the rendezvous as a REAL named pipe and prove it, rather than assuming it.
     *
     * `java.nio.file` cannot create a FIFO. The obvious substitute, `Files.createFile`, creates a
     * regular file — which looks correct in a listing and inverts the whole design: the child would
     * spool its output to disk unredacted, and a reader would see EOF immediately instead of blocking.
     * Measured on the first version of this prototype, that produced a 97-byte regular file containing
     * the child's secret and a drainer that reported itself drained having read nothing. So the type is
     * verified on both sides of the handoff: here before anything is attached, and again in
     * [ObsPc2IngestAgent.openRendezvous] before it is drained.
     *
     * `mkfifo(1)` is used because the rest of this harness already assumes a POSIX substrate — `sh`,
     * `setsid`, FIFOs — which is the same assumption `DurableShellExecutor`'s durable wrapper makes.
     */
    private fun createRendezvous(path: Path): Path {
        if (!Files.exists(path)) {
            val mkfifo = ProcessBuilder("mkfifo", path.toString()).redirectErrorStream(true).start()
            val out = mkfifo.inputStream.bufferedReader().readText()
            check(mkfifo.waitFor() == 0) { "mkfifo failed for $path: $out" }
        }
        check(ObsPc2IngestAgent.isFifo(path)) { "$path exists but is not a FIFO; refusing to attach the child" }
        return path
    }

    fun argv(
        controlDirRoot: Path,
        runId: String,
        opId: String,
        workspace: Path,
        fifoDir: Path,
        readyMarker: Path,
        secret: String,
        linesBefore: Int,
        linesAfter: Int,
    ): Array<String> = arrayOf(
        controlDirRoot.toString(),
        runId,
        opId,
        workspace.toString(),
        fifoDir.toString(),
        readyMarker.toString(),
        secret,
        linesBefore.toString(),
        linesAfter.toString(),
    )
}