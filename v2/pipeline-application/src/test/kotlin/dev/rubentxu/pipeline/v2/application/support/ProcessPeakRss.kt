package dev.rubentxu.pipeline.v2.application.support

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * WU-093 H8 — peak resident memory of a forked process, measured from the outside.
 *
 * ## The property, and why an absolute number would be the wrong test
 *
 * H4 made `maxBodyBytes` a real bound: for a response of N bytes the plugin retains at
 * most `maxBodyBytes` while counting and hashing all N. The claim is therefore
 *
 * ```text
 *   peak RSS  ~=  O(maxBodyBytes)        and NOT  O(responseSize)
 * ```
 *
 * which is a claim about the SLOPE. A single absolute ceiling would pass for a 1 MiB
 * response and fail for a 1 GiB one while the plugin behaved identically, and would
 * also be hostage to the JVM's own baseline. So this reports a measured peak and lets
 * the caller compare two runs that differ only in response size: doubling the response
 * must not double the peak.
 *
 * ## How it is read
 *
 * `/proc/<pid>/status: VmHWM`, the kernel's own high-water mark for resident memory,
 * polled while the child runs. `VmHWM` is a high-water MARK, so a sample taken at any
 * moment after the peak already reports it; the poll interval does not have to be fine.
 * A missing `/proc` (non-Linux) yields `null`, and every caller states the measurement
 * rather than inventing one.
 */
object ProcessPeakRss {

    /** Reads the kernel's peak RSS in bytes, or `null` where `/proc` does not exist. */
    fun readPeakRssBytes(pid: Long): Long? {
        val status = File("/proc/$pid/status")
        if (!status.isFile) return null
        return runCatching {
            status.useLines { lines ->
                lines.firstOrNull { it.startsWith("VmHWM:") }
                    ?.split(Regex("\\s+"))
                    ?.getOrNull(1)
                    ?.toLongOrNull()
                    ?.times(1024)
            }
        }.getOrNull()
    }

    /**
     * Starts polling [pid] and returns the poller. [Poller.peakBytes] keeps the maximum
     * seen, so the caller can read it after the process has already exited — which is
     * the only safe moment, because the high-water mark is gone once it is reaped.
     */
    fun poll(pid: Long, intervalMs: Long = 40L): Poller = Poller(pid, intervalMs).also { it.begin() }

    class Poller internal constructor(
        private val pid: Long,
        private val intervalMs: Long,
    ) {
        private val peak = AtomicLong(0L)
        private val running = AtomicBoolean(true)
        private val thread = Thread({ loop() }, "h8-rss-poll")

        /** `null` when the platform does not expose `/proc`. */
        val peakBytes: Long? get() = peak.get().takeIf { it > 0L }

        fun begin() {
            thread.isDaemon = true
            thread.start()
        }

        fun stop() {
            running.set(false)
            thread.join(2_000)
        }

        private fun loop() {
            while (running.get()) {
                readPeakRssBytes(pid)?.let { peak.accumulateAndGet(it, ::maxOf) }
                try {
                    Thread.sleep(intervalMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
            // One last read: the peak may have happened between the last poll and exit.
            readPeakRssBytes(pid)?.let { peak.accumulateAndGet(it, ::maxOf) }
        }
    }
}
