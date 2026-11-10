package dev.rubentxu.pipeline.v2.application

import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The way a run talks to the broker of its service: one line out, one line back.
 *
 * ## What the seam is for
 *
 * What a run does with an answer — reading it, refusing it, turning it into a
 * credential — is worth testing without a socket in the way, and what a socket
 * does — connecting, giving up, reading a line that never ends — is worth
 * testing without a broker in the way.
 *
 * ## What an implementation owes
 *
 * The answer line, or a [CredentialBrokerRefusedException] without a credential
 * that says why there is none. It never lets an exception escape whose message
 * was built from what it read.
 */
internal fun interface CredentialBrokerLink {

    /** Sends [requestLine] and returns the answer line. */
    fun exchange(requestLine: String): String
}

/**
 * The link over the private Unix socket of the run.
 *
 * ## One deadline for the whole exchange
 *
 * Connecting, sending and reading share a single deadline, and a watchdog that
 * closes the channel when it passes is what ends a call that is blocked: a
 * blocking read cannot be interrupted, and closing the channel is what turns a
 * broker that went quiet into a finished read. The watchdog says it expired
 * before it closes, so the failure is told as a timeout and not as a
 * disconnection.
 *
 * ## What it does with the bytes of the answer
 *
 * They are read into a buffer that is zeroed when the call ends. The line that
 * is returned is a `String`, which the JVM cannot wipe; the buffer is the part
 * that can be.
 *
 * @param socketPath the socket of the broker of this run.
 * @param deadlineMillis how long the whole exchange may take.
 */
internal class UnixSocketBrokerLink(
    private val socketPath: Path,
    private val deadlineMillis: Long = DEFAULT_DEADLINE_MILLIS,
) : CredentialBrokerLink {

    override fun exchange(requestLine: String): String {
        val expired = AtomicBoolean(false)
        val watchdog = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "pipelinek-credential-link-watchdog").apply { isDaemon = true }
        }
        val channel = try {
            SocketChannel.open(StandardProtocolFamily.UNIX)
        } catch (_: Exception) {
            watchdog.shutdownNow()
            throw refusal(CredentialBrokerRefusal.BROKER_UNREACHABLE)
        }
        try {
            watchdog.schedule(
                {
                    expired.set(true)
                    closeQuietly(channel)
                },
                deadlineMillis,
                TimeUnit.MILLISECONDS,
            )
            channel.connect(UnixDomainSocketAddress.of(socketPath))
            write(channel, requestLine)
            return readAnswer(channel)
        } catch (e: CredentialBrokerRefusedException) {
            throw e
        } catch (_: Throwable) {
            throw refusal(if (expired.get()) CredentialBrokerRefusal.BROKER_TIMED_OUT else CredentialBrokerRefusal.BROKER_UNREACHABLE)
        } finally {
            watchdog.shutdownNow()
            closeQuietly(channel)
        }
    }

    private fun write(channel: SocketChannel, line: String) {
        val buffer = ByteBuffer.wrap((line + "\n").toByteArray(Charsets.UTF_8))
        while (buffer.hasRemaining()) {
            channel.write(buffer)
        }
        buffer.array().fill(0)
    }

    /**
     * The answer line: everything up to the first newline.
     *
     * A connection that ends before the newline is a broker that went away in
     * the middle of its answer, and what it had sent is not an answer.
     */
    private fun readAnswer(channel: SocketChannel): String {
        var buffer = ByteArray(INITIAL_BUFFER_BYTES)
        var size = 0
        try {
            while (true) {
                if (size == buffer.size) {
                    if (size > CredentialBrokerWire.MAX_RESPONSE_LINE_BYTES) {
                        throw refusal(CredentialBrokerRefusal.BROKER_ANSWER_NOT_READABLE)
                    }
                    val bigger = buffer.copyOf(minOf(size * 2, CredentialBrokerWire.MAX_RESPONSE_LINE_BYTES + 1))
                    buffer.fill(0)
                    buffer = bigger
                }
                val read = channel.read(ByteBuffer.wrap(buffer, size, buffer.size - size))
                if (read < 0) throw refusal(CredentialBrokerRefusal.BROKER_UNREACHABLE)
                val newline = indexOfNewline(buffer, size, size + read)
                size += read
                if (newline >= 0) return String(buffer, 0, newline, Charsets.UTF_8)
            }
        } finally {
            buffer.fill(0)
        }
    }

    private fun indexOfNewline(buffer: ByteArray, from: Int, until: Int): Int {
        for (index in from until until) {
            if (buffer[index] == NEWLINE) return index
        }
        return -1
    }

    private fun refusal(refusal: CredentialBrokerRefusal) = CredentialBrokerRefusedException(null, refusal)

    private fun closeQuietly(channel: SocketChannel) {
        try {
            channel.close()
        } catch (_: IOException) {
            // A channel that is already closed is a channel that is closed.
        }
    }

    internal companion object {

        /** How long a run waits for its broker, in milliseconds. */
        const val DEFAULT_DEADLINE_MILLIS: Long = 10_000L

        private const val INITIAL_BUFFER_BYTES: Int = 8 * 1024

        private val NEWLINE: Byte = '\n'.code.toByte()
    }
}
