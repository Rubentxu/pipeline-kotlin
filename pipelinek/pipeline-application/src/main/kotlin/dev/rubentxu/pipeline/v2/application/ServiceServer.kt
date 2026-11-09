package dev.rubentxu.pipeline.v2.application

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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
 * ## Why the requests are served one at a time
 *
 * The substrate is composed once, at startup, and it is a SQLite store. A
 * store reused by several requests at the same time raises a question about
 * thread safety that this slice has no reason to answer. So the service has
 * ONE worker, and a connection waits its turn. The limit of connections is the
 * limit of clients WAITING, not the limit of work in flight.
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
    private val limits: ServiceLimits = ServiceLimits(),
) : AutoCloseable {

    /** The connections this service holds open right now. */
    private val openConnections = AtomicInteger(0)

    /** The single worker. One thread, because the substrate is one store. */
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
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
            worker.execute { serveConnection(connection) }
        }
    }

    /**
     * Answers one connection and closes it.
     *
     * One request per connection: the connection is closed after its answer,
     * and a client that wants another question opens another one. The
     * streaming operations of a later slice change this, and that is why the
     * protocol is versioned from the first day.
     */
    private fun serveConnection(connection: ServiceConnection) {
        try {
            when (val read = connection.readLine(limits.maxLineBytes, limits.readDeadlineMillis)) {
                is ServiceRead.Line -> connection.writeLine(answerOf(read.text))
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
     * The line that answers [line].
     */
    private fun answerOf(line: String): String = when (val decoded = ServiceCodec.decode(line)) {
        is ServiceDecoding.Decoded -> ServiceCodec.encode(operations.answer(decoded.request))
        is ServiceDecoding.Refused -> answerOfRefusal(decoded.refusal)
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
