package dev.rubentxu.pipeline.v2.application

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import dev.rubentxu.pipeline.v2.service.ServiceCodec
import dev.rubentxu.pipeline.v2.service.ServiceDecoding
import dev.rubentxu.pipeline.v2.service.ServiceEndReason
import dev.rubentxu.pipeline.v2.service.ServiceProtocol
import dev.rubentxu.pipeline.v2.service.ServiceRefusal
import dev.rubentxu.pipeline.v2.service.ServiceRequest
import dev.rubentxu.pipeline.v2.service.ServiceResponse
import dev.rubentxu.pipeline.v2.service.ServiceSubscriptionRequest

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
 *
 * ## Why the subscriptions have a budget of their own
 *
 * Because a subscription occupies its connection for as long as it lasts,
 * while a question occupies it for a line. With ONE budget for both, sixteen
 * subscribers could take every slot and deny `version` and `health` —the two
 * questions that say whether the service is alive— and a client could not
 * tell a busy service from a broken one. The two budgets are separate, so
 * the slots a subscription may take are always fewer than the slots a
 * question may take.
 */
internal data class ServiceLimits(
    /** How many connections the service holds open at once. */
    val maxConnections: Int = 16,
    /** How many subscriptions the service serves at once. */
    val maxSubscriptions: Int = 8,
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
    private val authorizations: ServiceAuthorizations,
    private val limits: ServiceLimits = ServiceLimits(),
) : AutoCloseable {

    /** The connections this service holds open right now. */
    private val openConnections = AtomicInteger(0)

    /**
     * The subscriptions this service is serving right now.
     *
     * A budget of its own, and the reason is a hole that was measured: with
     * ONE budget, sixteen subscribers took every slot and `version` and
     * `health` —the two questions that say whether the service is alive—
     * were refused, and a client could not tell a busy service from a broken
     * one.
     */
    private val liveSubscriptions = AtomicInteger(0)

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
     *
     * ## Where the authorization happens
     *
     * HERE, and in ONE place: the line is decoded, the authorization is
     * checked, and only then does the request reach the operations or the
     * street. A request that is not authorized never reaches the substrate,
     * and the operations and the streets do not know that authorizations
     * exist: they are handed a request that is already authorized.
     *
     * The order is fixed: a line the CODEC refuses —because it is not JSON,
     * because it speaks another schema, or because it asks for an operation
     * that does not exist— is answered with the codec's own refusal, and the
     * authorization is not even looked at. The vocabulary of operations is
     * published, so refusing a line that names none of it tells a client
     * nothing it did not already know, and it keeps the codec's refusals
     * about the line and the server's refusals about the client.
     */
    private fun serveLine(connection: ServiceConnection, text: String) {
        when (val decoded = ServiceCodec.decode(text)) {
            is ServiceDecoding.Decoded -> {
                when (val decision = decide(decoded.credential, runIdOf(decoded.request))) {
                    is ServiceAuthorizationOutcome.Authorized ->
                        connection.writeLine(ServiceCodec.encode(operations.answer(decoded.request)))

                    is ServiceAuthorizationOutcome.NotAuthorized ->
                        connection.writeLine(answerOfRefusal(ServiceRefusal.NotAuthorized))

                    is ServiceAuthorizationOutcome.RunNotAuthorized ->
                        connection.writeLine(answerOfRefusal(ServiceRefusal.RunNotAuthorized(decision.runId)))
                }
            }

            is ServiceDecoding.Subscribed -> serveSubscription(connection, decoded)

            is ServiceDecoding.Refused -> connection.writeLine(answerOfRefusal(decoded.refusal))
        }
    }

    /**
     * Serves the subscription [decoded] asks for.
     *
     * The authorization is checked BEFORE the subscription takes a slot: a
     * client that is not authorized never occupies the budget of the ones
     * that are.
     *
     * The slot is taken with an ATOMIC increment, and given back in the same
     * `finally` that ends the street: a check-then-increment that two workers
     * could both pass would let the ninth subscriber in, and a decrement that
     * did not happen would leak the budget of a street that ended.
     */
    private fun serveSubscription(connection: ServiceConnection, decoded: ServiceDecoding.Subscribed) {
        when (val decision = decide(decoded.credential, decoded.request.runId)) {
            is ServiceAuthorizationOutcome.NotAuthorized -> {
                connection.writeLine(answerOfRefusal(ServiceRefusal.NotAuthorized))
                return
            }

            is ServiceAuthorizationOutcome.RunNotAuthorized -> {
                connection.writeLine(answerOfRefusal(ServiceRefusal.RunNotAuthorized(decision.runId)))
                return
            }

            is ServiceAuthorizationOutcome.Authorized -> Unit
        }

        if (!takeSubscriptionSlot()) {
            connection.writeLine(answerOfRefusal(ServiceRefusal.TooManySubscriptions))
            return
        }
        try {
            streets.serve(connection, decoded.request)
        } finally {
            liveSubscriptions.decrementAndGet()
        }
    }

    /**
     * Takes a slot of the subscription budget, or says there is none.
     *
     * The increment is atomic and the refusal undoes it, so two workers
     * racing for the last slot cannot both take it.
     */
    private fun takeSubscriptionSlot(): Boolean {
        val live = liveSubscriptions.incrementAndGet()
        if (live <= limits.maxSubscriptions) return true
        liveSubscriptions.decrementAndGet()
        return false
    }

    /**
     * What the client that presented [credential] may ask about [runId].
     *
     * The decision belongs to the authorization core and it is pure: this only
     * asks it, and the answer is a fact about the authorizations the service
     * was given, not about the socket or the clock.
     */
    private fun decide(credential: String?, runId: String?): ServiceAuthorizationOutcome =
        authorizations.decide(credential, runId)

    /**
     * The run [request] is about, or null when it is about the service itself.
     *
     * A question about the version or the health of the service names no
     * run, so only the authorization decides it.
     */
    private fun runIdOf(request: ServiceRequest): String? = when (request) {
        is ServiceRequest.Version -> null
        is ServiceRequest.Health -> null
        is ServiceRequest.RunState -> request.runId
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
