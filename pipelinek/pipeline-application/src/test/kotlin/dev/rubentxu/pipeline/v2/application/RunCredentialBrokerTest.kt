package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.credentials.api.SecretStoreTamperException
import dev.rubentxu.pipeline.v2.credentials.spi.CredentialProvider
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.credentials.Credential
import dev.rubentxu.pipeline.v2.domain.credentials.SecretText
import dev.rubentxu.pipeline.v2.domain.credentials.UsernamePassword
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The broker that answers the credential requests of ONE run.
 *
 * ## The guarantee under trial
 *
 * That a request is answered with a secret only when the session is the run's,
 * the client that started the run was granted that credential as that kind, and
 * the store holds it; that every other request gets a fixed word and never
 * reaches the store; and that no answer, refusal or exception carries a byte of
 * what the store held.
 *
 * ## The capacity this serves
 *
 * A running step asks for a credential when it enters its scope and not before.
 * What the broker refuses is what keeps one run from reading what another run
 * was granted, and keeps a client from reading what it was not granted.
 *
 * ## Why this instrument
 *
 * The decision is a pure function of a request line, the grants, the session and
 * a provider, so it is tried with doubles and without a socket: every row that
 * can be answered by calling the decision is. Only the rows about threads — the
 * connection that is read, answered and closed, the broker that closes — go
 * through a transport double, and none of them opens a real socket.
 *
 * ## What this does not freeze
 *
 * How many workers answer, the deadline of the read, and the order in which two
 * connections that arrive together are answered.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class RunCredentialBrokerTest {

    @Test
    fun `a granted credential is handed out with the bytes the store holds`() {
        val store = StoreDouble(mapOf("deploy-key" to "s3cr3t-bytes".toByteArray()))
        val broker = broker(store = store, grants = setOf(grant("deploy-key")))

        val reading = answer(broker, request("deploy-key"))

        val granted = assertInstanceOf(CredentialBrokerWire.ResponseReading.Granted::class.java, reading)
        assertEquals(BrokeredCredentialKind.STRING, granted.kind)
        assertArrayEquals("s3cr3t-bytes".toByteArray(), granted.secret)
        assertEquals(listOf("deploy-key"), store.asked, "the store is asked once, for that name")
    }

    @Test
    fun `a request with another session is refused and the store is not asked`() {
        val store = StoreDouble(mapOf("deploy-key" to "s3cr3t".toByteArray()))
        val broker = broker(store = store, grants = setOf(grant("deploy-key")))

        val reading = answer(broker, request("deploy-key", token = OTHER_TOKEN))

        assertRefused(CredentialBrokerRefusal.SESSION_NOT_RECOGNIZED, reading)
        assertTrue(store.asked.isEmpty(), "a process that is not of the run reaches nothing")
    }

    @Test
    fun `a token that resembles the session token is not the session token`() {
        val store = StoreDouble(mapOf("deploy-key" to "s3cr3t".toByteArray()))
        val broker = broker(store = store, grants = setOf(grant("deploy-key")))
        val lastCharChanged = TOKEN.dropLast(1) + (if (TOKEN.last() == 'A') 'B' else 'A')
        val firstCharChanged = (if (TOKEN.first() == 'A') 'B' else 'A') + TOKEN.drop(1)
        val resemblances = mapOf(
            "same length, last character different" to lastCharChanged,
            "same length, first character different" to firstCharChanged,
            "a prefix of the token" to TOKEN.dropLast(1),
            "the token and one more character" to TOKEN + "A",
            "the token twice" to TOKEN + TOKEN,
            "the token in another case" to TOKEN.uppercase(),
            "empty" to "",
        )

        for ((what, presented) in resemblances) {
            assertRefused(
                CredentialBrokerRefusal.SESSION_NOT_RECOGNIZED,
                answer(broker, request("deploy-key", token = presented)),
                "$what is not the token of the run",
            )
        }
        assertTrue(store.asked.isEmpty(), "none of them reached the store")
    }

    @Test
    fun `a credential is granted by its whole name and not by a part of it`() {
        val store = StoreDouble(
            mapOf(
                "deploy" to "short".toByteArray(),
                "deploy-key" to "long".toByteArray(),
                "Deploy" to "cased".toByteArray(),
            ),
        )
        val broker = broker(store = store, grants = setOf(grant("deploy")))

        assertRefused(CredentialBrokerRefusal.CREDENTIAL_NOT_GRANTED, answer(broker, request("deploy-key")), "a longer name")
        assertRefused(CredentialBrokerRefusal.CREDENTIAL_NOT_GRANTED, answer(broker, request("deplo")), "a shorter name")
        assertRefused(CredentialBrokerRefusal.CREDENTIAL_NOT_GRANTED, answer(broker, request("Deploy")), "another case")
        assertTrue(store.asked.isEmpty(), "none of them reached the store")
        assertInstanceOf(CredentialBrokerWire.ResponseReading.Granted::class.java, answer(broker, request("deploy")))
        assertEquals(listOf("deploy"), store.asked, "only the granted name reached the store")
    }

    @Test
    fun `a credential that was not granted is refused whether the store holds it or not`() {
        val store = StoreDouble(mapOf("held" to "s3cr3t".toByteArray()))
        val broker = broker(store = store, grants = setOf(grant("something-else")))

        val held = answer(broker, request("held"))
        val absent = answer(broker, request("absent"))

        assertRefused(CredentialBrokerRefusal.CREDENTIAL_NOT_GRANTED, held)
        assertRefused(CredentialBrokerRefusal.CREDENTIAL_NOT_GRANTED, absent)
        assertTrue(store.asked.isEmpty(), "asking for a name that is not granted does not read the store")
    }

    @Test
    fun `a run cannot resolve what was granted to another run`() {
        val store = StoreDouble(
            mapOf("mine" to "my-bytes".toByteArray(), "theirs" to "their-bytes".toByteArray()),
        )
        val mine = broker(store = store, grants = setOf(grant("mine")), token = TOKEN)
        val theirs = broker(store = store, grants = setOf(grant("theirs")), token = OTHER_TOKEN)

        assertRefused(CredentialBrokerRefusal.CREDENTIAL_NOT_GRANTED, answer(mine, request("theirs")))
        assertRefused(CredentialBrokerRefusal.CREDENTIAL_NOT_GRANTED, answer(theirs, request("mine", token = OTHER_TOKEN)))
        assertRefused(
            CredentialBrokerRefusal.SESSION_NOT_RECOGNIZED,
            answer(theirs, request("theirs")),
            "the token of one run opens nothing in the broker of the other",
        )
        assertTrue(store.asked.isEmpty())
    }

    @Test
    fun `a kind the broker does not carry is refused and the store is not asked`() {
        val store = StoreDouble(mapOf("deploy-key" to "s3cr3t".toByteArray()))
        val broker = broker(store = store, grants = setOf(grant("deploy-key")))
        val line = """{"schema":"${CredentialBrokerWire.SCHEMA}","token":"$TOKEN","op":"resolve","credentialId":"deploy-key","kind":"file"}"""

        val reading = CredentialBrokerWire.decodeResponse(broker.answerFor(line))

        assertRefused(CredentialBrokerRefusal.CREDENTIAL_KIND_NOT_BROKERED, reading)
        assertTrue(store.asked.isEmpty())
    }

    @Test
    fun `the session is judged before the grant and the grant before the kind`() {
        val store = StoreDouble(mapOf("held" to "s3cr3t".toByteArray()))
        val broker = broker(store = store, grants = setOf(grant("held")))
        val notGrantedAndUnknownKind =
            """{"schema":"${CredentialBrokerWire.SCHEMA}","token":"$TOKEN","op":"resolve","credentialId":"nope","kind":"file"}"""
        val badSessionAndNotGranted = request("nope", token = OTHER_TOKEN)

        assertRefused(
            CredentialBrokerRefusal.CREDENTIAL_NOT_GRANTED,
            CredentialBrokerWire.decodeResponse(broker.answerFor(notGrantedAndUnknownKind)),
            "a name that is not granted is refused as such, whatever kind was asked",
        )
        assertRefused(
            CredentialBrokerRefusal.SESSION_NOT_RECOGNIZED,
            answer(broker, badSessionAndNotGranted),
            "a stranger learns nothing of the grants",
        )
    }

    @Test
    fun `a broker without a store refuses a granted credential as not available`() {
        val broker = broker(store = null, grants = setOf(grant("deploy-key")))

        assertRefused(CredentialBrokerRefusal.CREDENTIAL_NOT_AVAILABLE, answer(broker, request("deploy-key")))
    }

    @Test
    fun `a granted credential the store does not hold is refused as not available with no message from the store`() {
        val store = StoreDouble(emptyMap(), failure = SecretStoreTamperException("the store said: s3cr3t-in-message"))
        val broker = broker(store = store, grants = setOf(grant("deploy-key")))

        val line = broker.answerFor(request("deploy-key"))

        assertRefused(CredentialBrokerRefusal.CREDENTIAL_NOT_AVAILABLE, CredentialBrokerWire.decodeResponse(line))
        assertFalse(line.contains("s3cr3t-in-message"), "what the store said is not repeated: $line")
    }

    @Test
    fun `a failure of the store that is not a store refusal is the broker failing and says nothing of it`() {
        val store = StoreDouble(emptyMap(), failure = IllegalStateException("boom with s3cr3t-in-message"))
        val broker = broker(store = store, grants = setOf(grant("deploy-key")))

        val line = broker.answerFor(request("deploy-key"))

        assertRefused(CredentialBrokerRefusal.BROKER_FAILED, CredentialBrokerWire.decodeResponse(line))
        assertFalse(line.contains("s3cr3t-in-message"), "the message of a failure may be made of the secret: $line")
    }

    @Test
    fun `a credential the store holds as another kind is not handed out as a string`() {
        val store = StoreDouble(
            emptyMap(),
            custom = UsernamePassword(CredentialsId("deploy-key"), username = "u", password = "p4ss".toByteArray()),
        )
        val broker = broker(store = store, grants = setOf(grant("deploy-key")))

        val line = broker.answerFor(request("deploy-key"))

        assertRefused(CredentialBrokerRefusal.CREDENTIAL_KIND_NOT_BROKERED, CredentialBrokerWire.decodeResponse(line))
        assertFalse(line.contains("p4ss"))
    }

    @Test
    fun `a secret bigger than the broker hands out is refused and wiped`() {
        val big = ByteArray(CredentialBrokerWire.MAX_SECRET_BYTES + 1) { 0x41 }
        val store = StoreDouble(mapOf("deploy-key" to big))
        val broker = broker(store = store, grants = setOf(grant("deploy-key")))

        val reading = answer(broker, request("deploy-key"))

        assertRefused(CredentialBrokerRefusal.CREDENTIAL_TOO_LARGE, reading)
        assertTrue(big.all { it == 0.toByte() }, "the bytes the store gave are zeroed even when they are refused")
    }

    @Test
    fun `the bytes the store gave are zeroed once the answer is built`() {
        val bytes = "s3cr3t-bytes".toByteArray()
        val store = StoreDouble(mapOf("deploy-key" to bytes))
        val broker = broker(store = store, grants = setOf(grant("deploy-key")))

        answer(broker, request("deploy-key"))

        assertTrue(bytes.all { it == 0.toByte() }, "the broker does not keep the secret it read")
    }

    @Test
    fun `a line that is not a request is refused as unreadable and the store is not asked`() {
        val store = StoreDouble(mapOf("deploy-key" to "s3cr3t".toByteArray()))
        val broker = broker(store = store, grants = setOf(grant("deploy-key")))

        for (line in listOf("", "not json", "{}", """{"schema":"${CredentialBrokerWire.SCHEMA}","token":"$TOKEN"}""")) {
            assertRefused(
                CredentialBrokerRefusal.REQUEST_NOT_READABLE,
                CredentialBrokerWire.decodeResponse(broker.answerFor(line)),
                "'$line' is not a request",
            )
        }
        assertTrue(store.asked.isEmpty())
    }

    @Test
    fun `the grants are copied so a later change of the set does not widen the run`() {
        val store = StoreDouble(mapOf("deploy-key" to "s3cr3t".toByteArray(), "other" to "x".toByteArray()))
        val mutable = mutableSetOf(grant("deploy-key"))
        val broker = broker(store = store, grants = mutable)

        mutable += grant("other")

        assertRefused(CredentialBrokerRefusal.CREDENTIAL_NOT_GRANTED, answer(broker, request("other")))
    }

    @Test
    fun `a connection is read, answered and closed`() {
        val store = StoreDouble(mapOf("deploy-key" to "s3cr3t-bytes".toByteArray()))
        val transport = TransportDouble()
        val broker = broker(store = store, grants = setOf(grant("deploy-key")), transport = transport).start()
        try {
            val connection = ConnectionDouble(ServiceRead.Line(request("deploy-key")))

            transport.offer(connection)
            connection.awaitClosed()

            val reading = CredentialBrokerWire.decodeResponse(connection.written.single())
            val granted = assertInstanceOf(CredentialBrokerWire.ResponseReading.Granted::class.java, reading)
            assertArrayEquals("s3cr3t-bytes".toByteArray(), granted.secret)
        } finally {
            broker.close()
        }
    }

    @Test
    fun `a request line that is too big is refused as unreadable and the connection closes`() {
        val transport = TransportDouble()
        val broker = broker(store = StoreDouble(emptyMap()), grants = emptySet(), transport = transport).start()
        try {
            val connection = ConnectionDouble(ServiceRead.TooLarge)

            transport.offer(connection)
            connection.awaitClosed()

            assertRefused(
                CredentialBrokerRefusal.REQUEST_NOT_READABLE,
                CredentialBrokerWire.decodeResponse(connection.written.single()),
            )
        } finally {
            broker.close()
        }
    }

    @Test
    fun `a client that closes or goes quiet is answered nothing and its connection closes`() {
        val transport = TransportDouble()
        val broker = broker(store = StoreDouble(emptyMap()), grants = emptySet(), transport = transport).start()
        try {
            val closed = ConnectionDouble(ServiceRead.Closed)
            val quiet = ConnectionDouble(ServiceRead.TimedOut)

            transport.offer(closed)
            transport.offer(quiet)
            closed.awaitClosed()
            quiet.awaitClosed()

            assertTrue(closed.written.isEmpty() && quiet.written.isEmpty(), "nobody is there to hear an answer")
        } finally {
            broker.close()
        }
    }

    @Test
    fun `a connection that fails while it is answered does not stop the broker answering the next`() {
        val store = StoreDouble(mapOf("deploy-key" to "s3cr3t-bytes".toByteArray()))
        val transport = TransportDouble()
        val broker = broker(store = store, grants = setOf(grant("deploy-key")), transport = transport).start()
        try {
            val failing = ConnectionDouble(ServiceRead.Line(request("deploy-key")), failWrites = true)
            val next = ConnectionDouble(ServiceRead.Line(request("deploy-key")))

            transport.offer(failing)
            failing.awaitClosed()
            transport.offer(next)
            next.awaitClosed()

            assertInstanceOf(
                CredentialBrokerWire.ResponseReading.Granted::class.java,
                CredentialBrokerWire.decodeResponse(next.written.single()),
            )
        } finally {
            broker.close()
        }
    }

    @Test
    fun `a connection whose read fails is told the broker failed and says nothing of why`() {
        val transport = TransportDouble()
        val broker = broker(store = StoreDouble(emptyMap()), grants = emptySet(), transport = transport).start()
        try {
            val connection = ConnectionDouble(ServiceRead.Closed, failReads = true)

            transport.offer(connection)
            connection.awaitClosed()

            val line = connection.written.single()
            assertRefused(CredentialBrokerRefusal.BROKER_FAILED, CredentialBrokerWire.decodeResponse(line))
            assertFalse(line.contains("s3cr3t-in-message"), "the failure may be made of the secret: $line")
        } finally {
            broker.close()
        }
    }

    @Test
    fun `closing the broker closes the transport and ends its threads and it can be closed again`() {
        val transport = TransportDouble()
        val before = brokerThreads()
        val broker = broker(store = StoreDouble(emptyMap()), grants = emptySet(), transport = transport).start()
        val connection = ConnectionDouble(ServiceRead.Line(request("x")))
        transport.offer(connection)
        connection.awaitClosed()

        broker.close()
        broker.close()

        assertTrue(transport.closed.get() >= 1, "the broker releases the transport it was given")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (brokerThreads().size > before.size && System.nanoTime() < deadline) Thread.sleep(20)
        assertEquals(before.size, brokerThreads().size, "no thread of the broker outlives it: ${brokerThreads()}")
    }

    @Test
    fun `the broker does not close the store it was lent`() {
        val store = StoreDouble(emptyMap())
        val broker = broker(store = store, grants = emptySet()).start()

        broker.close()

        assertEquals(0, store.closed.get(), "the store belongs to the service and outlives the run")
    }

    private fun broker(
        store: CredentialProvider?,
        grants: Set<CredentialGrant>,
        token: String = TOKEN,
        transport: ServiceTransport = TransportDouble(),
    ): RunCredentialBroker = RunCredentialBroker(
        transport = transport,
        sessionToken = token,
        grants = grants,
        provider = store,
        readDeadlineMillis = 1_000L,
    )

    private fun grant(id: String): CredentialGrant = CredentialGrant(id, BrokeredCredentialKind.STRING)

    private fun request(id: String, token: String = TOKEN): String =
        CredentialBrokerWire.encodeRequest(token, id, BrokeredCredentialKind.STRING)

    private fun answer(broker: RunCredentialBroker, line: String): CredentialBrokerWire.ResponseReading =
        CredentialBrokerWire.decodeResponse(broker.answerFor(line))

    private fun assertRefused(
        expected: CredentialBrokerRefusal,
        reading: CredentialBrokerWire.ResponseReading,
        message: String = "",
    ) {
        val refused = assertInstanceOf(CredentialBrokerWire.ResponseReading.Refused::class.java, reading, message)
        assertEquals(expected, refused.refusal, message)
    }

    private fun brokerThreads(): List<String> =
        Thread.getAllStackTraces().keys
            .filter { it.isAlive && it.name.startsWith("pipelinek-credential-broker") }
            .map { it.name }

    /** A store that holds bytes by name, records what it was asked and can fail or answer another kind. */
    private class StoreDouble(
        private val secrets: Map<String, ByteArray>,
        private val failure: Exception? = null,
        private val custom: Credential? = null,
    ) : CredentialProvider {
        val asked: MutableList<String> = CopyOnWriteArrayList()
        val closed = AtomicInteger(0)

        override val providerId: String = "store-double"

        override fun resolve(id: CredentialsId): SecretHandle = error("the broker resolves credentials, not handles")

        override fun resolveToCredential(id: CredentialsId): Credential {
            asked += id.value
            failure?.let { throw it }
            custom?.let { return it }
            val bytes = secrets[id.value] ?: throw SecretStoreTamperException("not held")
            return SecretText(id, bytes = bytes)
        }

        override fun close() {
            closed.incrementAndGet()
        }
    }

    /** A transport whose connections are the ones the test offers; it ends when it is closed. */
    private class TransportDouble : ServiceTransport {
        private val queue = LinkedBlockingQueue<Any>()
        val closed = AtomicInteger(0)

        fun offer(connection: ServiceConnection) {
            queue.put(connection)
        }

        override fun accept(): ServiceConnection? {
            val next = queue.take()
            return next as? ServiceConnection
        }

        override fun close() {
            closed.incrementAndGet()
            queue.put(END)
        }

        private companion object {
            private val END = Any()
        }
    }

    /** One connection that reads what it was given, records what is written to it and says when it is closed. */
    private class ConnectionDouble(
        private val reading: ServiceRead,
        private val failWrites: Boolean = false,
        private val failReads: Boolean = false,
    ) : ServiceConnection {
        val written: MutableList<String> = CopyOnWriteArrayList()
        private val closedLatch = CountDownLatch(1)

        override fun readLine(maxBytes: Int, deadlineMillis: Long): ServiceRead {
            check(!failReads) { "read failed on s3cr3t-in-message" }
            return reading
        }

        override fun writeLine(line: String) {
            if (failWrites) throw java.io.IOException("the peer is gone")
            written += line
        }

        override fun close() {
            closedLatch.countDown()
        }

        fun awaitClosed() {
            assertTrue(closedLatch.await(10, TimeUnit.SECONDS), "the broker closes every connection it served")
        }
    }

    private companion object {
        private const val TOKEN: String = "dG9rZW4tZGUtcHJ1ZWJhLTAxMjM0NTY3ODlhYmNkZWY"
        private const val OTHER_TOKEN: String = "b3RyYS1zZXNpb24tZGUtcHJ1ZWJhLTAxMjM0NTY3OA"
    }
}
