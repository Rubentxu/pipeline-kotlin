package dev.rubentxu.pipeline.v2.application

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.service.ServiceCodec
import dev.rubentxu.pipeline.v2.service.ServiceDecoding
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

    /**
     * Writes [line], and gives the connection up when the peer has not taken
     * it within [deadlineMillis].
     *
     * A peer that stops reading would otherwise hold the thread that writes
     * to it for as long as it stays connected. The default writes without a
     * deadline, which is right for a connection that cannot stall.
     */
    fun writeLine(line: String, deadlineMillis: Long) {
        writeLine(line)
    }

    /**
     * Whether the peer has closed its end of the connection.
     *
     * A street that is waiting for something to say asks it, because a
     * street writes only when it has a frame and a client that left while
     * the street was quiet would otherwise keep its slot until the run ends.
     * The default says no, which is right for a connection that cannot tell.
     */
    fun peerGone(): Boolean = false
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
 *
 * ## Why a write has a deadline too
 *
 * A client that connects, asks to follow a run and never reads fills the
 * socket buffer, and the thread that writes to it blocks for as long as the
 * client lives. The read deadline protects the service from a client that
 * does not talk; the write deadline protects it from a client that does not
 * listen. Without it, eight such clients would hold every subscription slot
 * for ever.
 */
internal data class ServiceLimits(
    /** How many connections the service holds open at once. */
    val maxConnections: Int = 16,
    /** How many subscriptions the service serves at once. */
    val maxSubscriptions: Int = 8,
    /** How long a client has to send its line, in milliseconds. */
    val readDeadlineMillis: Long = 5_000L,
    /** How long a client has to take a line the service writes, in milliseconds. */
    val writeDeadlineMillis: Long = 10_000L,
    /** The biggest line the service reads, in bytes. */
    val maxLineBytes: Int = ServiceProtocol.MAX_LINE_BYTES,
)

/**
 * A connection whose every write has a deadline.
 *
 * ## Why it wraps the connection
 *
 * An answer, a refusal and a street are written by different code, and a
 * peer that stops reading stalls any of them. Giving the deadline here, once,
 * means no path that writes has to remember to ask for it: the server hands
 * out only connections that cannot hold a thread longer than the limit says.
 */
private class BoundedConnection(
    private val inner: ServiceConnection,
    private val writeDeadlineMillis: Long,
) : ServiceConnection {

    override fun readLine(maxBytes: Int, deadlineMillis: Long): ServiceRead =
        inner.readLine(maxBytes, deadlineMillis)

    override fun writeLine(line: String) {
        inner.writeLine(line, writeDeadlineMillis)
    }

    override fun writeLine(line: String, deadlineMillis: Long) {
        inner.writeLine(line, deadlineMillis)
    }

    override fun peerGone(): Boolean = inner.peerGone()

    override fun close() {
        inner.close()
    }
}

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
 *  - the WRITE door is touched by ONE operation only: the cancellation of a
 *    run, which writes that run's terminal fact. It is the operation that
 *    ends a run, and the terminal of an ended run is a fact and not an
 *    absence — a socket that killed a process and wrote nothing would leave
 *    the client reading an EOF where the product guarantees a fact.
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
 *
 * ## Why a client that leaves gives back what it held
 *
 * A connection holds a slot, a descriptor and, for a street, a worker. They
 * are returned when the work on the connection ends, and the work ends in
 * three ways a client can force: it closes (the next write fails), it stops
 * reading (the write deadline of the limits closes the connection) or it goes
 * quiet on a street with nothing to say (the street asks the connection
 * whether the peer is still there). Stopping the service closes the
 * transport and interrupts the workers, so nothing outlives it.
 *
 * ## What is observed, and what the observer cannot do
 *
 * Every request that decoded is handed to the [observer] once: when it is
 * admitted, and again when it ends, with the class of its ending. A request
 * the codec refused is not observed — it names no operation, so it would be a
 * measurement without a kind — and a request that was refused for want of
 * authorization is observed WITHOUT the run it named, because the run is the
 * text a client that is not authorized chose.
 *
 * The observer is a guest. Whatever it throws is swallowed here, before it
 * can change the line a client reads, the slot a connection gives back or the
 * worker that serves it.
 */
internal class ServiceServer(
    private val transport: ServiceTransport,
    private val operations: ServiceOperations,
    private val streets: ServiceStreets,
    private val authorizations: ServiceAuthorizations,
    private val limits: ServiceLimits = ServiceLimits(),
    private val observer: ServiceObserver = NoServiceObserver,
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
     * The workers of the QUESTIONS. One per connection in flight, and no
     * more: the pool is sized by the same limit that bounds the
     * connections, so a client over the limit is refused rather than queued.
     */
    private val worker: ExecutorService = Executors.newFixedThreadPool(
        limits.maxConnections,
    ) { runnable ->
        Thread(runnable, "pipelinek-service").apply { isDaemon = true }
    }

    /**
     * The workers of the operations that CHANGE something, and a pool of
     * their own.
     *
     * ## Why they do not run in the pool of the questions
     *
     * Because neither of them is an instant: a start waits for the child to
     * take its ownership, and a cancellation waits for the child to be gone
     * before the terminal may be written. An operation that ran in the pool
     * of the questions would hold a thread of it for that whole time, and
     * sixteen clients that ask for a run at once would leave the service
     * with no thread to answer `version` and `health`, the two questions
     * that say whether the service is alive.
     *
     * The budget of the connections still bounds them: both hold their
     * connection open while they wait, so the same limit that refuses the
     * seventeenth client refuses them here too.
     */
    private val mutatingWorker: ExecutorService = Executors.newFixedThreadPool(
        limits.maxConnections,
    ) { runnable ->
        Thread(runnable, "pipelinek-service-mutation").apply { isDaemon = true }
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
            val accepted = try {
                transport.accept()
            } catch (_: Exception) {
                null
            } ?: return
            val connection = BoundedConnection(accepted, limits.writeDeadlineMillis)

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
        var handedOver = false
        try {
            when (val read = connection.readLine(limits.maxLineBytes, limits.readDeadlineMillis)) {
                is ServiceRead.Line -> handedOver = serveLine(connection, read.text)
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
            if (!handedOver) closeConnection(connection)
        }
    }

    /**
     * Closes [connection] and gives back the slot it held.
     *
     * The connection of a START is closed by the worker that answers it,
     * and not here: the answer of a start is written after the wait, and a
     * connection closed before its answer would close the mouth of the
     * client that is waiting for it.
     */
    private fun closeConnection(connection: ServiceConnection) {
        try {
            connection.close()
        } finally {
            openConnections.decrementAndGet()
        }
    }

    /**
     * Serves the line [text] on [connection].
     *
     * A question is answered with one line; a subscription is served by the
     * street, which writes as many lines as the street has.
     *
     * ## What the answer says about the connection
     *
     * Whether the connection was HANDED OVER to another worker: a start is
     * answered by the worker of the starts, and the connection is closed by
     * that worker, because the answer of a start is written after the wait
     * for the ownership. A connection closed before its answer would close
     * the mouth of the client that is waiting for it.
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
    private fun serveLine(connection: ServiceConnection, text: String): Boolean {
        when (val decoded = ServiceCodec.decode(text)) {
            is ServiceDecoding.Decoded -> return serveDecoded(connection, decoded)
            is ServiceDecoding.Subscribed -> serveSubscription(connection, decoded)
            is ServiceDecoding.Refused -> connection.writeLine(answerOfRefusal(decoded.refusal))
        }
        return false
    }

    /**
     * Serves a request that decoded: refuses it, or answers it where its kind is answered.
     *
     * The refusal of a client that is not authorized is observed with no run
     * on it, and the request of one that is, with the run it is about.
     */
    private fun serveDecoded(connection: ServiceConnection, decoded: ServiceDecoding.Decoded): Boolean {
        val refusal = refusalOfDecision(decide(decoded))
        val kind = kindOf(decoded.request)
        if (refusal != null) {
            refuse(connection, refusal, begin(kind, null))
            return false
        }
        val observation = begin(kind, runOf(decoded.request))
        if (kind == ServiceRequestKind.Mutation) return serveMutating(connection, decoded, observation)
        answer(connection, decoded, observation)
        return false
    }

    /**
     * Answers [decoded] on [connection] and ends [observation] with how it went.
     *
     * The observation ends in a `finally`, so a request that throws —because
     * the operation broke, or because the client left before the line was
     * written— is still ended, with the class that says which of the two it
     * was: until the operation answers, the ending is a fault of the engine;
     * from then on, a line that was not delivered is an abandoned request.
     */
    private fun answer(
        connection: ServiceConnection,
        decoded: ServiceDecoding.Decoded,
        observation: ServiceObservation,
    ) {
        var outcome: ServiceOutcome = ServiceOutcome.Faulted(FailureKind.ENGINE)
        try {
            val response = operations.answer(
                decoded.request,
                authorizations.digestOfAuthorized(decoded.credential),
            )
            outcome = ServiceOutcome.Abandoned
            connection.writeLine(ServiceCodec.encode(response))
            outcome = serviceOutcomeOf(response)
        } finally {
            finish(observation, outcome)
        }
    }

    /** Writes the refusal [refusal] and ends [observation] as a refused request, or as an abandoned one. */
    private fun refuse(connection: ServiceConnection, refusal: ServiceRefusal, observation: ServiceObservation) {
        var outcome: ServiceOutcome = ServiceOutcome.Abandoned
        try {
            connection.writeLine(answerOfRefusal(refusal))
            outcome = ServiceOutcome.Refused
        } finally {
            finish(observation, outcome)
        }
    }

    /** The refusal that [decision] stands for, or null when the client may ask. */
    private fun refusalOfDecision(decision: ServiceAuthorizationOutcome): ServiceRefusal? = when (decision) {
        is ServiceAuthorizationOutcome.Authorized -> null
        is ServiceAuthorizationOutcome.NotAuthorized -> ServiceRefusal.NotAuthorized
        is ServiceAuthorizationOutcome.RunNotAuthorized -> ServiceRefusal.RunNotAuthorized(decision.runId)
        is ServiceAuthorizationOutcome.StartNotAuthorized -> ServiceRefusal.StartNotAuthorized
        is ServiceAuthorizationOutcome.CancelNotAuthorized -> ServiceRefusal.CancelNotAuthorized
        is ServiceAuthorizationOutcome.AnswerNotAuthorized -> ServiceRefusal.AnswerNotAuthorized
    }

    /** Opens the observation of a request; an observer that fails leaves the request unobserved. */
    private fun begin(kind: ServiceRequestKind, runId: String?): ServiceObservation =
        try {
            observer.begin(kind, runId)
        } catch (_: Exception) {
            NoServiceObserver.begin(kind, runId)
        }

    /** Ends [observation] with [outcome]; an observer that fails changes nothing the client can see. */
    private fun finish(observation: ServiceObservation, outcome: ServiceOutcome) {
        try {
            observation.end(outcome)
        } catch (_: Exception) {
            // The observer is a guest: what it throws is not the client's concern.
        }
    }

    /**
     * Serves a request that CHANGES something, in the pool of the changes.
     *
     * The connection is handed over to the worker that answers it, and the
     * worker closes it when it is done: the answer of a start is written
     * after the wait for the ownership, and the answer of a cancellation is
     * written after the wait for the child to be gone. The connection has to
     * stay open until then.
     *
     * It says whether the connection was handed over: a service that is
     * stopping has nobody to answer the request, and the connection is
     * closed by the worker that could not take it. The observation of such a
     * request ends as an abandoned one.
     */
    private fun serveMutating(
        connection: ServiceConnection,
        decoded: ServiceDecoding.Decoded,
        observation: ServiceObservation,
    ): Boolean {
        try {
            mutatingWorker.execute {
                try {
                    answer(connection, decoded, observation)
                } catch (_: Exception) {
                    // A connection that fails while it is answered is a
                    // connection that ends, and the client reads it as a
                    // service that closed the connection without answering.
                } finally {
                    closeConnection(connection)
                }
            }
            return true
        } catch (_: RejectedExecutionException) {
            // The service is stopping and the worker is already closed:
            // this request has nobody to answer it, and the connection is
            // closed by the worker that owns it.
            finish(observation, ServiceOutcome.Abandoned)
            return false
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
        val refusal = refusalOfDecision(authorizations.decide(decoded.credential, decoded.request.runId))
        val observation = begin(
            ServiceRequestKind.Subscription,
            if (refusal == null) decoded.request.runId else null,
        )
        if (refusal != null) {
            refuse(connection, refusal, observation)
            return
        }
        if (!takeSubscriptionSlot()) {
            refuse(connection, ServiceRefusal.TooManySubscriptions, observation)
            return
        }
        // A street that returns served its subscription to the end, whatever the
        // end was; one that throws could not finish on this connection.
        var outcome: ServiceOutcome = ServiceOutcome.Abandoned
        try {
            streets.serve(connection, decoded.request)
            outcome = ServiceOutcome.Answered
        } finally {
            liveSubscriptions.decrementAndGet()
            finish(observation, outcome)
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
     * What the client that presented the credential of [decoded] may ask.
     *
     * The decision belongs to the authorization core and it is pure: this only
     * asks it, and the answer is a fact about the authorizations the service
     * was given, not about the socket or the clock.
     *
     * ## Why starting and cancelling are DIFFERENT questions
     *
     * Because asking about a run is about a run IN PARTICULAR, and the scope
     * says which runs may be asked about. Starting a run is about a RIGHT,
     * and no run is out of scope of it: the scope of a client that may
     * start says so in its own words. Cancelling a run is about a right AND
     * about a run: the right to cancel, and the run the cancel names. A
     * client that may cancel every run and may read none has no run to
     * cancel, and one that may read a run and may cancel none may not
     * touch it.
     *
     * Listing the questions of a run and answering one are the same question
     * as cancelling: the right to answer AND the run. Reading a question is
     * the right of whoever may answer it.
     */
    private fun decide(decoded: ServiceDecoding.Decoded): ServiceAuthorizationOutcome =
        when (val request = decoded.request) {
            is ServiceRequest.StartRun -> authorizations.decideStart(decoded.credential)
            is ServiceRequest.CancelRun ->
                authorizations.decideCancel(decoded.credential, request.runId)
            is ServiceRequest.ListInputs ->
                authorizations.decideAnswer(decoded.credential, request.runId)
            is ServiceRequest.AnswerInput ->
                authorizations.decideAnswer(decoded.credential, request.runId)
            is ServiceRequest.RunState ->
                authorizations.decide(decoded.credential, request.runId)
            is ServiceRequest.Version, is ServiceRequest.Health,
            is ServiceRequest.DescribeParameters ->
                authorizations.decide(decoded.credential, null)
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
        mutatingWorker.shutdownNow()
    }
}
