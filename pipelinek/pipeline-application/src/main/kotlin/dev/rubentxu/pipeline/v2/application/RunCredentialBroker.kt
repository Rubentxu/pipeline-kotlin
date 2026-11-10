package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.credentials.api.SecretStoreException
import dev.rubentxu.pipeline.v2.credentials.spi.CredentialProvider
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.credentials.SecretText
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The broker of ONE run: it answers the credential requests of that run and of
 * no other.
 *
 * ## What it decides, and in which order
 *
 * A request is judged in this order, and the order is the contract, because each
 * step reveals less than the one after it:
 *
 * 1. **The session.** The token of the request must be the token of this run. A
 *    process that does not hold it learns nothing else.
 * 2. **The grant.** The client that started the run must have been granted the
 *    credential. A credential that is not granted is refused with the same word
 *    whether or not the store holds it, so asking cannot map the store.
 * 3. **The kind.** The credential must be granted as the kind requested, and the
 *    kind must be one the broker hands out.
 * 4. **The store.** Only now is the provider asked, and only for a credential
 *    that passed the three checks above.
 *
 * ## Why the provider is never reached by a request that was refused
 *
 * Because reaching it is the act of reading a secret. The broker does not read
 * a secret in order to decide whether it may hand it out.
 *
 * ## The secret in this process
 *
 * The bytes the provider returns are zeroed as soon as the answer line is built.
 * The line itself is a `String`, which the JVM cannot wipe: it lives until the
 * garbage collector takes it, and the connection it was written to is the only
 * place it was sent.
 *
 * ## Threads
 *
 * One thread accepts, and a small pool answers. A request answers on a
 * connection of its own and the connection closes after the answer, so a client
 * that is slow occupies a worker for at most the read deadline. A closed broker
 * ends the accept loop, the workers and the connections it holds.
 *
 * @param transport where the connections of the run arrive; the broker closes it.
 * @param sessionToken the token only this run holds.
 * @param grants what the client that started the run may request; copied, so a
 *   later change of the caller's set does not widen what this run may ask for.
 * @param provider the store the credentials come from, or null when there is none.
 *   The broker does NOT close it: the provider belongs to the service.
 * @param readDeadlineMillis how long a client has to send its request line.
 */
internal class RunCredentialBroker(
    private val transport: ServiceTransport,
    private val sessionToken: String,
    grants: Set<CredentialGrant>,
    private val provider: CredentialProvider?,
    private val readDeadlineMillis: Long = DEFAULT_READ_DEADLINE_MILLIS,
) : AutoCloseable {

    private val grants: Set<CredentialGrant> = grants.toSet()

    private val closed = AtomicBoolean(false)

    private val workers = ThreadPoolExecutor(
        WORKERS,
        WORKERS,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(WAITING_CONNECTIONS),
        daemonThreads("pipelinek-credential-broker-worker"),
        ThreadPoolExecutor.AbortPolicy(),
    )

    private val acceptor = Thread(::acceptLoop, "pipelinek-credential-broker-accept").apply {
        isDaemon = true
    }

    /** Starts answering. Returns this broker so that the caller can hold it in one expression. */
    fun start(): RunCredentialBroker {
        acceptor.start()
        return this
    }

    /**
     * The answer line for [line], whatever [line] is.
     *
     * It never throws: a request the broker cannot judge is answered with the
     * word that says so, and a failure inside the broker is answered with the
     * word that says THAT. Neither carries a message, because the failure may
     * have been made of the secret.
     */
    internal fun answerFor(line: String): String =
        when (val reading = CredentialBrokerWire.decodeRequest(line)) {
            CredentialBrokerWire.RequestReading.Unreadable -> refused(CredentialBrokerRefusal.REQUEST_NOT_READABLE)
            is CredentialBrokerWire.RequestReading.Read -> judge(reading.request)
        }

    private fun judge(request: CredentialBrokerWire.BrokerRequest): String {
        if (!sameSession(request.token)) return refused(CredentialBrokerRefusal.SESSION_NOT_RECOGNIZED)
        if (grants.none { it.credentialId == request.credentialId }) {
            return refused(CredentialBrokerRefusal.CREDENTIAL_NOT_GRANTED)
        }
        val kind = BrokeredCredentialKind.ofWord(request.kindWord)
        if (kind == null || CredentialGrant(request.credentialId, kind) !in grants) {
            return refused(CredentialBrokerRefusal.CREDENTIAL_KIND_NOT_BROKERED)
        }
        val source = provider ?: return refused(CredentialBrokerRefusal.CREDENTIAL_NOT_AVAILABLE)
        return handOut(source, request.credentialId, kind)
    }

    /**
     * Asks the store for [credentialId] and builds the answer.
     *
     * A store that does not hold the credential, cannot read it, or holds
     * another kind of credential under that id is a store that has nothing to
     * hand out under this name; the broker says so with the words it has and
     * does not repeat what the store said.
     */
    private fun handOut(source: CredentialProvider, credentialId: String, kind: BrokeredCredentialKind): String {
        val credential = try {
            source.resolveToCredential(CredentialsId(credentialId))
        } catch (_: SecretStoreException) {
            return refused(CredentialBrokerRefusal.CREDENTIAL_NOT_AVAILABLE)
        } catch (_: Exception) {
            return refused(CredentialBrokerRefusal.BROKER_FAILED)
        }
        val secret = credential as? SecretText
            ?: return refused(CredentialBrokerRefusal.CREDENTIAL_KIND_NOT_BROKERED)
        val bytes = secret.bytes
        try {
            if (bytes.size > CredentialBrokerWire.MAX_SECRET_BYTES) {
                return refused(CredentialBrokerRefusal.CREDENTIAL_TOO_LARGE)
            }
            return CredentialBrokerWire.encodeGranted(kind, bytes)
        } finally {
            bytes.fill(0)
        }
    }

    /**
     * Whether [presented] is the token of this run.
     *
     * The comparison does not stop at the first different byte: how long it
     * takes must not tell a process how much of the token it guessed.
     */
    private fun sameSession(presented: String): Boolean =
        MessageDigest.isEqual(
            presented.toByteArray(Charsets.UTF_8),
            sessionToken.toByteArray(Charsets.UTF_8),
        )

    private fun refused(refusal: CredentialBrokerRefusal): String = CredentialBrokerWire.encodeRefused(refusal)

    private fun acceptLoop() {
        while (!closed.get()) {
            val connection = try {
                transport.accept()
            } catch (_: Exception) {
                // The transport is closed, or it cannot accept any more. A broker
                // that cannot accept is a broker the run finds unreachable, which
                // is a named refusal and not a fallback to another store.
                null
            } ?: return
            try {
                workers.execute { serve(connection) }
            } catch (_: RejectedExecutionException) {
                closeQuietly(connection)
            }
        }
    }

    /** Reads the request of one connection, answers it, and closes the connection. */
    private fun serve(connection: ServiceConnection) {
        try {
            when (val read = connection.readLine(CredentialBrokerWire.MAX_REQUEST_LINE_BYTES, readDeadlineMillis)) {
                is ServiceRead.Line -> connection.writeLine(answerFor(read.text))
                ServiceRead.TooLarge -> connection.writeLine(refused(CredentialBrokerRefusal.REQUEST_NOT_READABLE))
                ServiceRead.Closed, ServiceRead.TimedOut -> Unit
            }
        } catch (_: Throwable) {
            // The failure is the connection's, and what failed may have been made
            // of the secret: a best-effort word is all that is said, and the
            // worker goes on to the next connection.
            tryToRefuse(connection)
        } finally {
            closeQuietly(connection)
        }
    }

    private fun tryToRefuse(connection: ServiceConnection) {
        try {
            connection.writeLine(refused(CredentialBrokerRefusal.BROKER_FAILED))
        } catch (_: Throwable) {
            // The peer is gone; there is nobody to tell.
        }
    }

    private fun closeQuietly(connection: ServiceConnection) {
        try {
            connection.close()
        } catch (_: Exception) {
            // A connection that is already closed is a connection that is closed.
        }
    }

    /** Stops answering and releases the transport. Idempotent. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            transport.close()
        } catch (_: Exception) {
            // The transport is going away either way.
        }
        workers.shutdownNow()
        acceptor.interrupt()
        if (Thread.currentThread() !== acceptor) {
            acceptor.join(JOIN_MILLIS)
            workers.awaitTermination(JOIN_MILLIS, TimeUnit.MILLISECONDS)
        }
    }

    private fun daemonThreads(prefix: String): ThreadFactory {
        val counter = AtomicInteger(0)
        return ThreadFactory { task ->
            Thread(task, "$prefix-${counter.incrementAndGet()}").apply { isDaemon = true }
        }
    }

    internal companion object {

        /** How long a client has to send its request line, in milliseconds. */
        const val DEFAULT_READ_DEADLINE_MILLIS: Long = 5_000L

        /** How many connections are answered at once. */
        private const val WORKERS: Int = 4

        /** How many connections may wait for a worker before the next one is refused. */
        private const val WAITING_CONNECTIONS: Int = 8

        /** How long a close waits for the threads to end, in milliseconds. */
        private const val JOIN_MILLIS: Long = 2_000L
    }
}
