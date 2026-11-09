package dev.rubentxu.pipeline.v2.application

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

/**
 * The transport of the local service.
 *
 * ## What the seam is for
 *
 * The service's BEHAVIOUR — one request per connection, the limits, the
 * deadline — is worth testing without a socket in the way, and the socket's
 * behaviour — the permissions, the stale file, the deletion — is worth
 * testing without a service in the way. The seam is where those two meet: a
 * transport that yields connections, and a connection that yields lines.
 *
 * ## What it does not decide
 *
 * Nothing about the protocol: the lines a connection carries are lines of the
 * contract in [ServiceCodec], and the answer is the contract's.
 */
internal interface ServiceTransport : AutoCloseable {

    /**
     * The next connection, or null when there is none and there will not be
     * another.
     */
    fun accept(): ServiceConnection?
}

/**
 * One connection of the local service.
 */
internal interface ServiceConnection : AutoCloseable {

    /**
     * The next line, or what stopped it from arriving.
     *
     * The outcome is a value and not an exception because every way this can
     * fail is an answer the service owes the client: a closed connection, a
     * client that went quiet, and a line too big to read are three different
     * facts.
     */
    fun readLine(maxBytes: Int, deadlineMillis: Long): ServiceRead

    /**
     * Writes [line] as the answer of this connection.
     */
    fun writeLine(line: String)
}

/**
 * The streets the service serves.
 *
 * ## What the seam is for
 *
 * A subscription is a connection that stays open, and what happens on it is
 * worth testing without a socket in the way —the frames, the cursor and the
 * end of the street— exactly as the transport's behaviour is worth testing
 * without a service in the way.
 *
 * ## What it does not decide
 *
 * Nothing about the protocol: the lines a street carries are lines of the
 * contract in [ServiceCodec].
 */
internal interface ServiceStreets {

    /**
     * Serves [request] on [connection], and returns when the street ends.
     *
     * The street writes the frames; it does not close the connection, which
     * belongs to whoever opened it.
     */
    fun serve(connection: ServiceConnection, request: ServiceSubscriptionRequest)
}

/**
 * What the reading of a line produced.
 */
internal sealed interface ServiceRead {

    /** The line arrived. */
    data class Line(val text: String) : ServiceRead

    /** The peer closed the connection before sending a line. */
    data object Closed : ServiceRead

    /** The peer did not send a line within the deadline. */
    data object TimedOut : ServiceRead

    /** The line is bigger than the service reads. */
    data object TooLarge : ServiceRead
}

/**
 * The limits the service enforces.
 *
 * ## Why they are here and not in the server
 *
 * They are a value, so a test can hold a smaller one and a reader can see
 * what the service promises in one place. The server reads them and never
 * decides a limit of its own.
 */
internal data class ServiceLimits(
    /** How many connections the service holds open at once. */
    val maxConnections: Int = 16,
    /** How long a client has to send its line, in milliseconds. */
    val readDeadlineMillis: Long = 5_000L,
    /** The biggest line the service reads, in bytes. */
    val maxLineBytes: Int = ServiceProtocol.MAX_LINE_BYTES,
)

/**
 * The service: it accepts connections and answers them.
 *
 * ## What it owns
 *
 * The rules of the service, and nothing else: one request per connection, the
 * limits, and the answer to every line. The transport carries the lines and
 * the operations know what they mean.
 *
 * ## Why there is a thread per connection
 *
 * The substrate is composed once, at startup, and it is a SQLite store, and
 * the first version of this server had ONE worker because a store reused by
 * several requests at the same time raises a question about thread safety
 * that a slice of read-only questions had no reason to answer.
 *
 * A subscription DOES have a reason: it occupies its connection while it
 * lasts, and with a single worker one live subscription would block every
 * other client — including a `version` question that takes a millisecond.
 *
 * The answer is measured rather than assumed:
 *
 *  - the event store's READ door opens its own read-only connection per read,
 *    so two readers do not share mutable state;
 *  - the Output Plane takes its locks PER STREAM, so two readers of
 *    different streams do not get in each other's way — within this
 *    process; the writer of a run usually lives in another one, and what
 *    fences two processes is the run's lease, not this;
 *  - the WRITE door is never touched: the socket is read-only.
 *
 * So the service has a thread per connection, bounded by the same limit that
 * bounds the connections. The limit stops meaning "the limit of clients
 * WAITING" and starts meaning "the limit of work IN FLIGHT".
 *
 * ## Why the connection over the limit is refused by name
 *
 * A client that cannot connect gets a refusal it can read, and it learns that
 * the service is busy rather than that something went wrong. Refusing it
 * silently would make a busy service look like a broken one.
 */
internal class ServiceServer(
    private val transport: ServiceTransport,
    private val operations: ServiceOperations,
    private val streets: ServiceStreets,
    private val limits: ServiceLimits = ServiceLimits(),
) : AutoCloseable {

    /** The connections this service holds open right now. */
    private val openConnections = AtomicInteger(0)

    /**
     * The workers. One per connection in flight, and no more: the pool is
     * sized by the same limit that bounds the connections, so a client over
     * the limit is refused rather than queued.
     */
    private val worker: ExecutorService = Executors.newFixedThreadPool(
        limits.maxConnections,
    ) { runnable ->
        Thread(runnable, "pipelinek-service").apply { isDaemon = true }
    }

    /**
     * Serves until the transport has no more connections.
     *
     * The method returns when the transport is exhausted or closed. It never
     * throws for a client's fault: a client that sends nonsense, goes quiet or
     * sends too much is answered and closed, and the service keeps serving.
     */
    fun serve() {
        while (true) {
            val connection = try {
                transport.accept()
            } catch (_: Exception) {
                null
            } ?: return

            if (openConnections.get() >= limits.maxConnections) {
                refuseOverTheLimit(connection)
                continue
            }
            openConnections.incrementAndGet()
            try {
                worker.execute { serveConnection(connection) }
            } catch (_: RejectedExecutionException) {
                // The service is stopping and the worker is already closed: this
                // connection has nobody to serve it. It is closed here, and the
                // count that was taken for it is given back, so a service that
                // is stopping does not leave a client holding a socket that
                // nobody will answer.
                connection.close()
                openConnections.decrementAndGet()
            }
        }
    }

    /**
     * Answers one connection and closes it.
     *
     * One request per connection: the connection is closed after its answer,
     * and a client that wants another question opens another one. A
     * SUBSCRIPTION is the exception that the protocol version announces: its
     * connection stays open and the street writes on it until the street
     * ends, and the connection is closed then — by the same `finally`, so
     * there is one place that closes a connection.
     */
    private fun serveConnection(connection: ServiceConnection) {
        try {
            when (val read = connection.readLine(limits.maxLineBytes, limits.readDeadlineMillis)) {
                is ServiceRead.Line -> serveLine(connection, read.text)
                is ServiceRead.TooLarge ->
                    connection.writeLine(answerOfRefusal(ServiceRefusal.RequestTooLarge))
                is ServiceRead.TimedOut -> Unit
                is ServiceRead.Closed -> Unit
            }
        } catch (_: Exception) {
            // A connection that fails while it is served is a connection that
            // ends. The service keeps serving the others: one broken client
            // is not a reason to stop.
        } finally {
            connection.close()
            openConnections.decrementAndGet()
        }
    }

    /**
     * Serves the line [text] on [connection].
     *
     * A question is answered with one line; a subscription is served by the
     * street, which writes as many lines as the street has.
     */
    private fun serveLine(connection: ServiceConnection, text: String) {
        when (val decoded = ServiceCodec.decode(text)) {
            is ServiceDecoding.Decoded ->
                connection.writeLine(ServiceCodec.encode(operations.answer(decoded.request)))
            is ServiceDecoding.Subscribed -> streets.serve(connection, decoded.request)
            is ServiceDecoding.Refused -> connection.writeLine(answerOfRefusal(decoded.refusal))
        }
    }

    /**
     * The refusal of a client that arrived when the service was full.
     */
    private fun refuseOverTheLimit(connection: ServiceConnection) {
        try {
            connection.writeLine(answerOfRefusal(ServiceRefusal.TooManyConnections))
        } catch (_: Exception) {
            // A client that cannot even be refused is a client that is gone.
        } finally {
            connection.close()
        }
    }

    /**
     * The line that refuses with [refusal].
     */
    private fun answerOfRefusal(refusal: ServiceRefusal): String =
        ServiceCodec.encode(ServiceResponse.Refused(ServiceProtocol.SCHEMA, refusal))

    /**
     * Stops the service and releases the worker.
     */
    override fun close() {
        transport.close()
        worker.shutdownNow()
    }
}
