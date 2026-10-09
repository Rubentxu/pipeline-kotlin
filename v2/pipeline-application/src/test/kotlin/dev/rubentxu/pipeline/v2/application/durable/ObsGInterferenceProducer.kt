package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.MainConsoleCli
import dev.rubentxu.pipeline.v2.output.OutputChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The producer an [ObsGReaderRecoveryInterferenceUatTest] parks between `write` and `commit`.
 *
 * ## Why this exists instead of extending [ObsBJvmDeathProducer]
 *
 * The existing producer runs a real `sh` step, whose pump reserves, writes and commits **inside one
 * call**. There is therefore no point at which a test can hold the writer between those two steps
 * and let another process open the store — which is the only shape that can answer the question the
 * audit asks about a live producer.
 *
 * This producer drives the store's own public port (`OutputAppendPort.open` → `reserve` → `write` →
 * `commit`), which is the same sequence `appendFrom` performs internally and the one `ShExecution`
 * eventually runs. Nothing here re-implements the store: it takes a real reservation, writes real
 * bytes through it, and publishes or never publishes them exactly as the product does.
 *
 * ## What it must NOT do
 *
 * It must not flush, close or shut anything down on the way out, and it must not recover between its
 * own reserve and its commit. It is a writer, and the property under test is what a *reader* does to
 * it while it is mid-reservation.
 */
object ObsGInterferenceProducer {

    /**
     * @param args `controlRoot`, `runId`, `opId`, `committedBytes`, `parkedBytes`, `parkedMarker`,
     *   `releaseMarker`, `committedMarker`
     */
    @JvmStatic
    fun main(args: Array<String>) {
        val controlRoot = Paths.get(args[0])
        val runId = args[1]
        val opId = args[2]
        val committedBytes = args[3].toInt()
        val parkedBytes = args[4].toInt()
        val parkedMarker = Paths.get(args[5])
        val releaseMarker = Paths.get(args[6])
        val committedMarker = Paths.get(args[7])

        val store = OutputPlaneProvider.storeFor(controlRoot)
        val handle = store.open(OutputPlaneProvider.streamId(runId, opId, OutputChannel.STDOUT))

        // Phase 1 — acknowledged bytes, so the stream is not empty and a lost byte is attributable.
        if (committedBytes > 0) {
            val reservation = handle.reserve(committedBytes)
            reservation.write(payload('A', committedBytes))
            reservation.commit()
        }

        // Phase 2 — written, durable, NOT acknowledged. This is the window the whole row is about.
        val parked = handle.reserve(parkedBytes)
        parked.write(payload('B', parkedBytes))

        // The barrier is the proof the writer is inside the window and still alive.
        Files.writeString(parkedMarker, "${parked.base}|${parked.written}\n")

        // Park. A SIGKILL here is the crash row; a release file is the orderly row.
        while (!Files.exists(releaseMarker)) Thread.sleep(25)

        val committedAt = parked.commit()
        Files.writeString(committedMarker, "$committedAt\n")
    }

    /**
     * Convenience for building argv, so the test and the producer cannot drift on the argument
     * order — which is the failure mode this file exists to avoid.
     */
    fun argv(
        controlRoot: Path,
        runId: String,
        opId: String,
        committedBytes: Int,
        parkedBytes: Int,
        parkedMarker: Path,
        releaseMarker: Path,
        committedMarker: Path,
    ): Array<String> = arrayOf(
        controlRoot.toString(),
        runId,
        opId,
        committedBytes.toString(),
        parkedBytes.toString(),
        parkedMarker.toString(),
        releaseMarker.toString(),
        committedMarker.toString(),
    )

    private fun payload(fill: Char, count: Int): ByteArray = ByteArray(count) { fill.code.toByte() }
}

/**
 * The observer: the `console` verb, in its own process, opening the Output Plane.
 *
 * A trampoline and nothing else — [MainConsoleCli.main] is the production entry point, and
 * [MainConsoleCli] is a Kotlin `object` whose `main` is not `static`, so a forked JVM cannot address
 * it directly. What the observer does is exactly what `pipeline console` does: parse, resolve the
 * control directory, and call `OutputPlaneProvider.storeFor`, which recovers.
 */
object ObsGConsoleObserver {

    @JvmStatic
    fun main(args: Array<String>) {
        val exit = MainConsoleCli.main(arrayOf(*args))
        // On STDOUT, because the caller redirects stderr to DISCARD: a control line written to stderr
        // is a control line thrown away, which cost this harness its first run.
        System.out.println("obsg-console-observer-exit:$exit")
    }
}
