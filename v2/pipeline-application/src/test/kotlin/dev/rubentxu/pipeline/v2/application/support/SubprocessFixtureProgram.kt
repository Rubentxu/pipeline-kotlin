package dev.rubentxu.pipeline.v2.application.support

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * S6-PRE — the CHILD under test for [OwnedSubprocess]'s own guarantees.
 *
 * ## Why this is a real program and not a shell one-liner
 *
 * The properties [OwnedSubprocessRunTest] claims are about process plumbing: pipe saturation,
 * a child that never exits, a grandchild that outlives its parent. A shell script would import
 * that host's shell, its quoting rules and its `yes` availability into the evidence. Launching a
 * JVM from the same JDK that runs the test keeps the fixture hermetic and portable, and it means
 * the "child that hangs" is a real JVM whose thread dump is genuinely obtainable — which is the
 * whole point of capturing one.
 *
 * ## Why the modes are separate
 *
 * Each mode isolates ONE failure mode, so a mutation can be attributed to it. `saturate` fills a
 * pipe. `hang` never exits. `grandchild` leaves a descendant behind. A combined mode would prove
 * that *something* hangs, which is the kind of evidence that cannot fail for the right reason.
 */
object SubprocessFixtureProgram {

    /** Pipe buffer is 64 KiB on Linux; going far past it is what forces the writer to block. */
    private const val SATURATION_BYTES = 4L * 1024 * 1024

    @JvmStatic
    fun main(args: Array<String>) {
        when (args.firstOrNull()) {
            // Fill BOTH pipes. Draining one and ignoring the other must still deadlock, which is
            // precisely the half-fix this fixture exists to catch.
            "saturate" -> saturate()
            "hang" -> hang()
            "grandchild" -> grandchild()
            "exit" -> System.exit(if (args.getOrNull(1) == "0") 0 else 3)
            else -> {
                System.err.println("unknown mode '${args.firstOrNull()}'")
                System.exit(64)
            }
        }
    }

    private fun saturate() {
        val chunk = ByteArray(64 * 1024) { 'x'.code.toByte() }
        var written = 0L
        val out = System.out
        val err = System.err
        while (written < SATURATION_BYTES) {
            out.write(chunk)
            out.flush()
            err.write(chunk)
            err.flush()
            written += chunk.size
        }
        out.flush()
        err.flush()
        System.exit(0)
    }

    /** Never exits. Not an exception and not a return: a child that fails to start is a different law. */
    private fun hang() {
        while (true) {
            Thread.sleep(TimeUnit.MINUTES.toMillis(5))
        }
    }

    /**
     * Leave a descendant behind and then hang too.
     *
     * The grandchild is a detached JVM running the same `hang` mode, so the parent's death cannot
     * take it down. That is what makes it a real test of tree ownership rather than of courtesy.
     */
    private fun grandchild() {
        val java = File(System.getProperty("java.home"), "bin").resolve("java").absolutePath
        ProcessBuilder(
            java,
            "-cp",
            System.getProperty("java.class.path"),
            SubprocessFixtureProgram::class.java.name,
            "hang",
        ).start()
        hang()
    }
}
