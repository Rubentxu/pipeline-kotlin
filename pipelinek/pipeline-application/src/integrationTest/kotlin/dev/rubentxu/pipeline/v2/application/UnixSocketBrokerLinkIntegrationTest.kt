package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The link of a run to its broker, over a real Unix socket.
 *
 * ## The guarantee under trial
 *
 * That one line goes out and one line comes back, whole, however the bytes
 * arrive; and that every way the other end can fail — absent, silent, gone
 * halfway, slow, endless — ends the call inside one deadline, with the word that
 * says which, and with nothing of what was read in the failure.
 *
 * ## The capacity this serves
 *
 * A step that waits for a credential must not wait forever and must be told why
 * it got none. A broker that went quiet and a broker that is gone are different
 * facts to an operator, and neither may leave the run blocked.
 *
 * ## Why this is an INTEGRATION test
 *
 * What is under trial is what a blocking read of a real socket does when the
 * other end behaves badly: a watchdog closing the channel under a read that
 * cannot be interrupted. A double of the socket would show the code handling the
 * failures the double produces, not the failures the kernel produces.
 *
 * ## What this does not freeze
 *
 * The default deadline, which is a policy of the service, and the size of the
 * buffer the answer is read through.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class UnixSocketBrokerLinkIntegrationTest {

    @TempDir
    lateinit var directory: Path

    @Test
    fun `the request goes out as one line and the answer line comes back without its newline`() {
        server { channel ->
            received += readLine(channel)
            write(channel, "the answer\n")
        }.use { server ->
            val answer = link(server).exchange("the request")

            assertEquals("the answer", answer)
            assertEquals(listOf("the request"), server.received)
        }
    }

    @Test
    fun `an answer that arrives in pieces is one answer`() {
        server { channel ->
            readLine(channel)
            write(channel, "first ")
            Thread.sleep(80)
            write(channel, "second ")
            Thread.sleep(80)
            write(channel, "third\n")
        }.use { server ->
            assertEquals("first second third", link(server).exchange("request"))
        }
    }

    @Test
    fun `an answer longer than the first buffer is read whole`() {
        val long = "x".repeat(20_000)
        server { channel ->
            readLine(channel)
            write(channel, "$long\n")
        }.use { server ->
            assertEquals(long, link(server).exchange("request"))
        }
    }

    @Test
    fun `an answer of exactly the longest line is read and one byte more is not readable`() {
        val limit = CredentialBrokerWire.MAX_RESPONSE_LINE_BYTES
        val exact = "y".repeat(limit)
        val over = "y".repeat(limit + 1)

        server { channel ->
            readLine(channel)
            write(channel, "$exact\n")
        }.use { server ->
            assertEquals(exact, link(server).exchange("request"), "the limit itself is a line")
        }
        server { channel ->
            readLine(channel)
            write(channel, "$over\n")
            Thread.sleep(500)
        }.use { server ->
            val failure = assertThrows<CredentialBrokerRefusedException> { link(server).exchange("request") }

            assertEquals(CredentialBrokerRefusal.BROKER_ANSWER_NOT_READABLE, failure.refusal)
            assertFalse(failure.message.orEmpty().contains("yyyy"), "nothing of the line is in the failure")
        }
    }

    @Test
    fun `a broker that answers nothing is a timeout and the call ends near the deadline`() {
        server { channel ->
            readLine(channel)
            hold.await(30, TimeUnit.SECONDS)
        }.use { server ->
            val started = System.nanoTime()

            val failure = assertThrows<CredentialBrokerRefusedException> {
                link(server, deadlineMillis = 300).exchange("request")
            }

            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertEquals(CredentialBrokerRefusal.BROKER_TIMED_OUT, failure.refusal)
            assertTrue(elapsedMillis >= 250, "the call waited for its deadline and not less: $elapsedMillis ms")
            assertTrue(elapsedMillis < 5_000, "the call did not wait for the broker: $elapsedMillis ms")
        }
    }

    @Test
    fun `the deadline belongs to the whole exchange and a broker that trickles bytes does not extend it`() {
        server { channel ->
            readLine(channel)
            repeat(40) {
                write(channel, "x")
                Thread.sleep(100)
            }
        }.use { server ->
            val started = System.nanoTime()

            val failure = assertThrows<CredentialBrokerRefusedException> {
                link(server, deadlineMillis = 500).exchange("request")
            }

            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertEquals(CredentialBrokerRefusal.BROKER_TIMED_OUT, failure.refusal)
            assertTrue(elapsedMillis < 3_000, "every read returned, yet the call ended at its deadline: $elapsedMillis ms")
        }
    }

    @Test
    fun `a broker that closes without answering is unreachable and not a timeout`() {
        server { channel ->
            readLine(channel)
            channel.close()
        }.use { server ->
            val failure = assertThrows<CredentialBrokerRefusedException> { link(server).exchange("request") }

            assertEquals(CredentialBrokerRefusal.BROKER_UNREACHABLE, failure.refusal)
        }
    }

    @Test
    fun `an answer cut before its newline is not an answer and its bytes are not in the failure`() {
        server { channel ->
            readLine(channel)
            write(channel, "{\"secret\":\"s3cr3t-bytes")
            channel.close()
        }.use { server ->
            val failure = assertThrows<CredentialBrokerRefusedException> { link(server).exchange("request") }

            assertEquals(CredentialBrokerRefusal.BROKER_UNREACHABLE, failure.refusal)
            assertFalse(failure.message.orEmpty().contains("s3cr3t"))
            assertEquals(null, failure.cause)
        }
    }

    @Test
    fun `a socket nobody listens on is unreachable`() {
        val absent = directory.resolve("absent.sock")
        val regularFile = Files.writeString(directory.resolve("regular.sock"), "not a socket")

        for (path in listOf(absent, regularFile)) {
            val failure = assertThrows<CredentialBrokerRefusedException>(path.fileName.toString()) {
                UnixSocketBrokerLink(path, deadlineMillis = 2_000).exchange("request")
            }

            assertEquals(CredentialBrokerRefusal.BROKER_UNREACHABLE, failure.refusal, path.fileName.toString())
        }
    }

    @Test
    fun `an exchange leaves no thread behind`() {
        server { channel ->
            readLine(channel)
            write(channel, "answer\n")
        }.use { server ->
            repeat(5) { link(server).exchange("request") }
        }

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (watchdogThreads().isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(25)

        assertEquals(emptyList<String>(), watchdogThreads())
    }

    private fun link(server: ServerDouble, deadlineMillis: Long = 5_000): UnixSocketBrokerLink =
        UnixSocketBrokerLink(server.path, deadlineMillis)

    private val hold = CountDownLatch(1)

    private fun server(behavior: ServerDouble.(SocketChannel) -> Unit): ServerDouble =
        ServerDouble(directory.resolve("s${counter++}.sock"), hold, behavior)

    private var counter = 0

    private fun watchdogThreads(): List<String> =
        Thread.getAllStackTraces().keys
            .filter { it.isAlive && it.name == "pipelinek-credential-link-watchdog" }
            .map { it.name }

    /** A server that runs [behavior] on every connection it accepts, and records what it was sent. */
    private class ServerDouble(
        val path: Path,
        private val release: CountDownLatch,
        private val behavior: ServerDouble.(SocketChannel) -> Unit,
    ) : AutoCloseable {
        val received: MutableList<String> = CopyOnWriteArrayList()
        private val server: ServerSocketChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)

        init {
            server.bind(UnixDomainSocketAddress.of(path))
            Thread({ acceptLoop() }, "link-test-server").apply { isDaemon = true }.start()
        }

        fun readLine(channel: SocketChannel): String {
            val out = ByteArrayOutputStream()
            val one = ByteBuffer.allocate(1)
            while (true) {
                one.clear()
                if (channel.read(one) < 0) break
                val byte = one.get(0)
                if (byte == '\n'.code.toByte()) break
                out.write(byte.toInt())
            }
            return out.toString(Charsets.UTF_8)
        }

        fun write(channel: SocketChannel, text: String) {
            val buffer = ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))
            while (buffer.hasRemaining()) channel.write(buffer)
        }

        private fun acceptLoop() {
            while (true) {
                val channel = try {
                    server.accept()
                } catch (_: Exception) {
                    return
                }
                Thread({
                    try {
                        channel.use { behavior(it) }
                    } catch (_: Exception) {
                        // The client gave up first; that is the case under trial in several rows.
                    }
                }).apply { isDaemon = true }.start()
            }
        }

        override fun close() {
            release.countDown()
            server.close()
            Files.deleteIfExists(path)
        }
    }
}
