package dev.rubentxu.pipeline.v2.service

import dev.rubentxu.pipeline.v2.events.identity.EventCursor
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeObservation
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration

/**
 * The answer of the service, or the negative that stopped it.
 *
 * ## Why every negative is a CASE and not an exception
 *
 * A client that cannot connect, that reads a foreign line, that waits too
 * long, or that is refused by name, has FOUR different facts to tell apart —
 * and the operator acts differently on each one: start the service, upgrade
 * it, wait less, or give up on that run.
 *
 * A single "error" would leave that decision to a string comparison, and two
 * clients would compare it differently. Every negative here is named.
 *
 * ## Why there is no local fallback
 *
 * There is no case that says "and also try locally". A client that asked a
 * service and did not get an answer has to SAY so: a caller that quietly
 * answered from somewhere else would be reporting a fact about a source the
 * caller never named.
 */
sealed interface ServiceAnswer<out T> {

    /** The service answered. */
    data class Answered<out T>(val value: T) : ServiceAnswer<T>

    /** The service refused, and the refusal has a name. */
    data class Refused(val refusal: ServiceRefusal) : ServiceAnswer<Nothing>

    /** There is no service at the endpoint. */
    data class Unreachable(val endpoint: String, val detail: String) : ServiceAnswer<Nothing>

    /** What came back is not this protocol. */
    data class Unreadable(val detail: String) : ServiceAnswer<Nothing>

    /** The deadline went by before the answer arrived. */
    data object TimedOut : ServiceAnswer<Nothing>
}

/**
 * The version the service speaks.
 *
 * Two facts and no more: the schema of the wire and the version of the
 * product. The product's version is read from the product itself, so a
 * socket and a command line cannot disagree about which build they are.
 */
data class ServiceVersion(val schema: String, val productVersion: String)

/**
 * The description of the parameters a script declares.
 *
 * It is a JSON Schema 2020-12 document, and it is the SAME declaration the script carries:
 * a representation of it, and not another contract. A service that cannot describe a script
 * answers the request with a refusal instead of with one of these.
 */
data class ServiceParameterDescription(
    val schema: String,
    val description: JsonObject,
)

/**
 * The health of the service and of the substrate it was composed with.
 *
 * Not the health of the installation: that is `doctor`'s, and it talks about
 * the machine. This one talks about the service and about the material it
 * reads.
 */
data class ServiceHealth(
    val schema: String,
    val productVersion: String,
    val eventStore: String,
    val eventStorePresent: Boolean,
    val controlRoot: String,
    val controlRootPresent: Boolean,
)

/**
 * The identity of a run the service started.
 *
 * ## What the five facts are
 *
 * The [runId] is the run's own identity, and it is the one a client uses to
 * ask about the run afterwards. The [ownerId] is the authority that holds
 * the run's lease. The [correlationId] is the token the service chose and
 * passed to the run, so a reader of the run's own facts can join them to
 * this answer. The [startedAtMs] is when the run took its ownership. The
 * [configuration] is the configuration the service accepted, echoed back.
 *
 * ## Why this is the whole answer
 *
 * Because the service answers a start with the identity of the run and not
 * with the run's outcome: the run is still going when the answer is
 * written, and its outcome is another authority's answer — the one the
 * client's `runState` asks for.
 *
 * ## Why the configuration comes back
 *
 * Because the answer is the only place where a client can learn that the
 * configuration it sent is the configuration the run WILL HAVE. A service
 * that does not know the field ignores it in silence and starts the run
 * without it, and a client that cannot tell the two apart believes in a
 * configuration that is not there. The echo turns that silence into a
 * fact, and the client that receives it compares it with what it sent.
 *
 * ## When the runId cannot be asked about
 *
 * The [runId] is the run's identity, and whether the SAME credential may
 * ask about it is a question of the SCOPE the operator gave that credential,
 * and not of this answer. A credential that may start runs and read none
 * receives a [runId] it cannot ask about with that same credential: the
 * scope that governs reading is the operator's, and the service does not
 * widen it because a run was started. A client that has to follow the run
 * it starts needs a scope that reads runs as well.
 */
data class ServiceRunStart(
    val runId: String,
    val correlationId: String,
    val ownerId: String,
    val startedAtMs: Long,
    /**
     * The configuration the service accepted.
     *
     * EMPTY when the request carried no configuration. It is NEVER null:
     * an answer that does not carry the field is read as an answer that
     * carries no configuration, and the client compares that with what it
     * sent. A service that ignored the field is therefore a mismatch and
     * not a silent success.
     */
    val configuration: Map<String, String> = emptyMap(),
    /**
     * The parameter values the service accepted.
     *
     * EMPTY when the request carried no parameter values. It is NEVER null:
     * an answer that does not carry the field is read as an answer that
     * carries no values, and the client compares that with what it sent. A
     * service that ignored the field is therefore a mismatch and not a
     * silent success.
     *
     * They are the DELIVERED values, and not the effective ones: the
     * effective ones add the defaults of the script's own declaration, and
     * the service does not compile the script, so it does not know them.
     */
    val parameters: Map<String, String> = emptyMap(),
) {

    /**
     * The answer WITHOUT the values of its configuration and of its parameters.
     *
     * The generated one would render them, and a line of log that carried
     * them would publish them to whoever reads the log. The values came from
     * the client and belong to the steps of the run, not to a diagnostic.
     */
    override fun toString(): String =
        "ServiceRunStart(runId=$runId, correlationId=$correlationId, ownerId=$ownerId, " +
            "startedAtMs=$startedAtMs, configuration=${configuration.size} entries, " +
            "parameters=${parameters.size} values)"
}

/**
 * The answer of a cancellation.
 *
 * ## What the three facts are
 *
 * The [runId] is the run that was closed. The [outcome] says whether THIS
 * call closed it (`cancelled`) or found it already closed
 * (`already-cancelled`). The [terminalAtMs] is when the run reached its
 * terminal, read from the durable facts and not from the moment the answer
 * was written.
 *
 * ## Why `already-cancelled` is an answer and not a refusal
 *
 * Because a cancellation repeated is not an error: the run is closed, once,
 * and the second call says so. A client that retries a cancel it already
 * sent —because the answer was lost— must not read the closed connection as
 * a failure of the cancellation.
 */
data class ServiceRunCancel(
    val runId: String,
    val outcome: String,
    val terminalAtMs: Long,
)

/**
 * One item of a street.
 *
 * A record, the end of the street, or a refusal: three facts, and a client
 * that could not tell them apart would keep reading a street that had already
 * been refused.
 */
sealed interface ServiceStreetItem {

    /** A record of the street. */
    data class Record(
        val cursor: String,
        val document: JsonObject,
        val hasMore: Boolean,
    ) : ServiceStreetItem

    /** The street ended. */
    data class End(val reason: ServiceEndReason, val cursor: String) : ServiceStreetItem

    /** The service refused the street. */
    data class Refused(val refusal: ServiceRefusal) : ServiceStreetItem
}

/**
 * A street of the service: the records that follow, one at a time.
 *
 * The street owns the connection it reads on, and closing it closes that
 * connection. It is the client's way of saying "I am done" — and a client
 * that stops reading without closing leaves the service's thread waiting for
 * a line that will never come.
 */
interface ServiceStreet : AutoCloseable {

    /**
     * The next item of the street, or `null` when the street is over.
     *
     * `null` means the connection ended without an end line: the street was
     * cut. The end of a street that ENDED is an item
     * ([ServiceStreetItem.End]), and the two are different facts.
     */
    fun next(): ServiceStreetItem?
}

/**
 * A line-oriented connection to the service.
 *
 * The seam the client is written against: a connection that writes a line
 * and reads a line. It is INTERNAL on purpose — the client is the public
 * surface, and a public seam for "a socket" would be a promise about the
 * transport that the contract does not need.
 */
internal interface ServiceChannel : AutoCloseable {

    /** Writes [line] as one line of the wire. */
    fun write(line: String)

    /** The next line, or what stopped it from arriving. */
    fun readLine(): ServiceRead
}

/** What the reading of a line produced. */
internal sealed interface ServiceRead {

    /** The line arrived. */
    data class Line(val text: String) : ServiceRead

    /** The peer closed the connection. */
    data object Closed : ServiceRead

    /** The deadline went by before the line arrived. */
    data object TimedOut : ServiceRead
}

/**
 * Opens connections to the service.
 *
 * The seam's factory: it exists so that a test can hand the client a
 * connection it controls, and so that the transport is chosen in ONE place.
 */
internal fun interface ServiceChannelFactory {

    /**
     * Opens a connection to [endpoint].
     *
     * Throws [IOException] when there is no service there: the client turns
     * that into a NAMED negative instead of a stack trace.
     */
    fun open(endpoint: Path, deadlineMillis: Long?): ServiceChannel
}

/**
 * The typed client of the local service.
 *
 * ## What it owns
 *
 * The conversation: it writes the request, reads the answer, and hands back
 * a typed value or a NAMED negative. It knows the wire and nothing else — no
 * store, no run, no presentation.
 *
 * ## What it does NOT own
 *
 * The command line's parser, its formats and its exit codes. The client
 * answers questions about runs; it does not know what a verb is, and a
 * consumer that wants the command line's presentation renders the value this
 * client returns with its own renderer.
 *
 * ## Why the client is a value and not a script
 *
 * Every question is a method that returns a [ServiceAnswer]. A client that
 * connected and then wrote its own protocol by hand would be a second codec,
 * and the copy is the one that drifts.
 *
 * ## The deadline
 *
 * The deadline is the CLIENT's, and it is explicit: a client that names none
 * waits, because a street that is alive has no end to guess. A deadline that
 * goes by closes the connection and the service's thread for that connection
 * learns of it through the broken write. Nothing about the RUN is touched:
 * the service is read-only and the client has no line to the substrate.
 */
class ServiceClient internal constructor(
    /** The socket of the service. */
    private val endpoint: Path,
    /** The authorization this client presents; the service refuses a question without one. */
    private val credential: String,
    /** How long the client waits for an answer; `null` waits as long as it takes. */
    private val deadline: Duration?,
    /** The schema this client speaks. */
    private val schema: String,
    /** The seam the connection comes from. */
    private val channels: ServiceChannelFactory,
) {

    /**
     * A client of the service at [endpoint], presenting [credential].
     *
     * The public shape of the client: a socket, the authorization that opens
     * it, and a deadline the caller names. A client that names no deadline
     * waits, because a live service has no end to guess.
     *
     * ## Why the authorization is REQUIRED
     *
     * Because the service asks every question for one: a client that could
     * be built without it would connect, write a line and be refused with
     * `not-authorized`, and a client that cannot work should not compile.
     * The token is a secret the operator issued —it is not a pipeline
     * credential— and a client that presents the wrong one is answered with
     * a named refusal, never with a silent answer from somewhere else.
     */
    constructor(
        endpoint: Path,
        credential: String,
        deadline: Duration? = null,
        schema: String = ServiceProtocol.SCHEMA,
    ) : this(endpoint, credential, deadline, schema, UnixServiceChannels)

    /**
     * The version of the service and of the protocol.
     */
    fun version(): ServiceAnswer<ServiceVersion> =
        ask(ServiceRequest.Version(schema)).narrow { result ->
            val version = result.string("productVersion") ?: return@narrow ServiceAnswer.Unreadable("no productVersion")
            ServiceAnswer.Answered(ServiceVersion(result.string("schema") ?: schema, version))
        }

    /**
     * The health of the service.
     */
    fun health(): ServiceAnswer<ServiceHealth> =
        ask(ServiceRequest.Health(schema)).narrow { result ->
            val eventStore = result.string("eventStore") ?: return@narrow ServiceAnswer.Unreadable("no eventStore")
            val controlRoot = result.string("controlRoot") ?: return@narrow ServiceAnswer.Unreadable("no controlRoot")
            ServiceAnswer.Answered(
                ServiceHealth(
                    schema = result.string("schema") ?: schema,
                    productVersion = result.string("productVersion") ?: "unknown",
                    eventStore = eventStore,
                    eventStorePresent = result.boolean("eventStorePresent") ?: false,
                    controlRoot = controlRoot,
                    controlRootPresent = result.boolean("controlRootPresent") ?: false,
                ),
            )
        }

    /**
     * The state of [runId], as the SAME observation the command line renders.
     *
     * The value is a
     * [dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeObservation], the
     * published type: a client that received a copy of it would be able to
     * disagree with the command line about what a run looks like.
     */
    fun runState(runId: String): ServiceAnswer<RuntimeObservation> =
        ask(ServiceRequest.RunState(schema, runId)).narrow { result ->
            when (val decoded = ServiceRunState.decode(result)) {
                is ServiceRunStateDecoding.Observed -> ServiceAnswer.Answered(decoded.observation)
                is ServiceRunStateDecoding.Unreadable -> ServiceAnswer.Unreadable(decoded.reason)
            }
        }

    /**
     * Starts a run and returns its identity.
     *
     * ## What the client names and what it does not
     *
     * The client names the [script] and, optionally, the [workspace] — by
     * their names inside the directories the operator admitted, never by a
     * path. Everything else is the service's decision: the argv, the
     * deadline, the policy.
     *
     * ## The configuration
     *
     * The [configuration] is the set of name/value pairs the steps of the
     * run will see in their environment. It is a snapshot, and it is NOT a
     * secret: the values end up in the environment of the steps, which
     * every process of the step inherits, and the service cannot tell a
     * secret from a plain text.
     *
     * ## What the client does with the answer
     *
     * It COMPARES the configuration the answer carries with the one it
     * sent, and a difference is an [ServiceAnswer.Unreadable] — never a
     * successful start that quietly runs with another configuration. The
     * comparison happens on EVERY path, including the idempotent one: the
     * answer of a repeated request carries the configuration of the request
     * that is being answered, and a request that repeats a key with
     * another configuration is refused by the service before this point.
     *
     * The detail of that refusal names the run, because the run EXISTS: a
     * service that ignored the configuration started it without one, and
     * the operator is the one who has to cancel it.
     *
     * ## The idempotency key
     *
     * A request that carries an [idempotencyKey] and repeats with the same
     * payload is the SAME request: the service answers with the same
     * `runId` and does not start a second run. A request that repeats the
     * key with a different payload is refused with `idempotency-conflict`.
     * A request with no key is a request that does not ask to be
     * repeatable.
     *
     * ## The deadline
     *
     * This call waits for the run to OWN ITSELF, which is not the same as
     * waiting for it to finish: the answer comes when the run has its
     * lease, and the run keeps going after it. A client that gives this
     * call a short deadline will hear `timed-out` while the run starts
     * anyway, so the deadline here is the client's decision to make.
     */
    fun startRun(
        script: String,
        workspace: String? = null,
        idempotencyKey: String? = null,
        configuration: Map<String, String> = emptyMap(),
        parameters: Map<String, String> = emptyMap(),
        parameterContract: List<dev.rubentxu.pipeline.v2.domain.ParameterSpec> = emptyList(),
    ): ServiceAnswer<ServiceRunStart> =
        ask(
            ServiceRequest.StartRun(
                schema = schema,
                script = script,
                workspace = workspace,
                idempotencyKey = idempotencyKey,
                configuration = configuration,
                parameters = parameters,
                parameterContract = parameterContract,
            ),
        ).narrow { result ->
            val runId = result.string("runId")
                ?: return@narrow ServiceAnswer.Unreadable("no runId")
            val correlationId = result.string("correlationId")
                ?: return@narrow ServiceAnswer.Unreadable("no correlationId")
            val ownerId = result.string("ownerId")
                ?: return@narrow ServiceAnswer.Unreadable("no ownerId")
            val startedAtMs = result.long("startedAtMs")
                ?: return@narrow ServiceAnswer.Unreadable("no startedAtMs")
            val echoed = result.configuration()
            if (echoed != configuration) {
                return@narrow ServiceAnswer.Unreadable(
                    "the answer carries a configuration that is not the one that was sent " +
                        "(run $runId)",
                )
            }
            val echoedParameters = result.parameters()
            if (echoedParameters != parameters) {
                return@narrow ServiceAnswer.Unreadable(
                    "the answer carries parameter values that are not the ones that were sent " +
                        "(run $runId)",
                )
            }
            ServiceAnswer.Answered(
                ServiceRunStart(runId, correlationId, ownerId, startedAtMs, echoed, echoedParameters),
            )
        }

    /**
     * Asks the service to DESCRIBE the parameters of [script].
     *
     * ## What the answer is
     *
     * A refusal, on a service that does not compile the scripts it is asked about:
     * describing the parameters of a script means compiling it, and compiling it means
     * running the author's code inside the service.
     *
     * ## Why the name is in the vocabulary
     *
     * Because a service that CAN describe a script —one that admits an artifact that was
     * authorized beforehand— answers the same request, and a client written against the
     * name works with both. A client that had no way to ask could only find out by
     * starting a run and reading the operator's log.
     *
     * The description a client needs before it starts a run comes from the compiler, or
     * from the contract it already carries.
     */
    fun describeParameters(script: String): ServiceAnswer<ServiceParameterDescription> =
        ask(ServiceRequest.DescribeParameters(schema, script)).narrow { result ->
            val description = result["description"] as? JsonObject
                ?: return@narrow ServiceAnswer.Unreadable("no description")
            ServiceAnswer.Answered(ServiceParameterDescription(schema, description))
        }

    /**
     * Cancels the run [runId] and returns its terminal.
     *
     * ## What the call does
     *
     * It asks the service to close the run: the service ends the process
     * that runs it —if it is one of the runs the service supervises— and
     * the run's own durable facts record the terminal. The answer is the
     * terminal, read from those facts.
     *
     * ## The idempotency of a repeated call
     *
     * Cancelling twice is not an error: the second call answers
     * `already-cancelled` with the SAME terminal. A client that retries a
     * cancel whose answer was lost reads a closed run, not a failure.
     *
     * ## The reason
     *
     * The [reason] is the operator's own words for why, and they are
     * recorded verbatim in the run's history. It is optional: a
     * cancellation without one carries the product's own word for it.
     */
    fun cancelRun(
        runId: String,
        reason: String? = null,
    ): ServiceAnswer<ServiceRunCancel> =
        ask(ServiceRequest.CancelRun(schema, runId, reason)).narrow { result ->
            val answered = result.string("runId")
                ?: return@narrow ServiceAnswer.Unreadable("no runId")
            val outcome = result.string("outcome")
                ?: return@narrow ServiceAnswer.Unreadable("no outcome")
            val terminalAtMs = result.long("terminalAtMs")
                ?: return@narrow ServiceAnswer.Unreadable("no terminalAtMs")
            ServiceAnswer.Answered(ServiceRunCancel(answered, outcome, terminalAtMs))
        }

    /**
     * The events of [runId], from [after] onwards.
     *
     * A `null` [after] asks for the whole history of the run. The street is
     * the client's to close: closing it closes the connection.
     */
    fun events(runId: String, after: EventCursor? = null): ServiceAnswer<ServiceStreet> =
        street(ServiceSubscriptionRequest.EventsFollow(schema, runId, after))

    /**
     * The console of [runId], from the frame ordinal [after] onwards.
     *
     * `-1` is the position before the first frame of the run.
     */
    fun output(runId: String, after: Long = -1L): ServiceAnswer<ServiceStreet> =
        street(ServiceSubscriptionRequest.OutputFollow(schema, runId, after))

    /**
     * The answer of [request], on a connection of its own.
     *
     * One request per connection is the wire's rule, so every question opens
     * its own connection and closes it when it is answered.
     */
    private fun ask(request: ServiceRequest): ServiceAnswer<JsonObject> {
        val channel = open() ?: return ServiceAnswer.Unreachable(endpoint.toString(), "no service at the endpoint")
        return try {
            channel.write(ServiceCodec.encode(request, credential))
            when (val read = channel.readLine()) {
                is ServiceRead.Line -> when (val decoded = ServiceCodec.decodeLine(read.text)) {
                    is ServiceLine.Answered -> if (decoded.schema == schema) {
                        ServiceAnswer.Answered(decoded.result)
                    } else {
                        // The answer is of ANOTHER protocol. Reading it as an
                        // answer would be reporting a success that this
                        // contract never promised.
                        ServiceAnswer.Unreadable("the answer speaks schema '${decoded.schema}'")
                    }
                    is ServiceLine.Refused -> ServiceAnswer.Refused(decoded.refusal)
                    else -> ServiceAnswer.Unreadable("the line is not an answer of this protocol")
                }
                ServiceRead.Closed -> ServiceAnswer.Unreachable(
                    endpoint.toString(),
                    "the service closed the connection without answering",
                )
                ServiceRead.TimedOut -> ServiceAnswer.TimedOut
            }
        } catch (failure: IOException) {
            ServiceAnswer.Unreachable(endpoint.toString(), failure.message ?: failure.javaClass.simpleName)
        } finally {
            channel.close()
        }
    }

    /**
     * The street of [request], on a connection of its own.
     */
    private fun street(request: ServiceSubscriptionRequest): ServiceAnswer<ServiceStreet> {
        val channel = open() ?: return ServiceAnswer.Unreachable(endpoint.toString(), "no service at the endpoint")
        return try {
            channel.write(ServiceCodec.encode(request, credential))
            ServiceAnswer.Answered(SocketServiceStreet(channel))
        } catch (failure: IOException) {
            channel.close()
            ServiceAnswer.Unreachable(endpoint.toString(), failure.message ?: failure.javaClass.simpleName)
        }
    }

    /**
     * A connection to the endpoint, or null when there is none.
     */
    private fun open(): ServiceChannel? = try {
        channels.open(endpoint, deadlineMillis())
    } catch (_: IOException) {
        null
    }

    /** The deadline in milliseconds, or null when there is none. */
    private fun deadlineMillis(): Long? = deadline?.toMillis()

    /**
     * The answer narrowed to the value the caller asked for.
     *
     * The negatives are carried over UNCHANGED: a refusal stays a refusal and
     * an unreachable service stays unreachable, whatever the caller was
     * going to do with the value.
     */
    private fun <T> ServiceAnswer<JsonObject>.narrow(
        read: (JsonObject) -> ServiceAnswer<T>,
    ): ServiceAnswer<T> = when (this) {
        is ServiceAnswer.Answered -> read(value)
        is ServiceAnswer.Refused -> ServiceAnswer.Refused(refusal)
        is ServiceAnswer.Unreachable -> ServiceAnswer.Unreachable(endpoint, detail)
        is ServiceAnswer.Unreadable -> ServiceAnswer.Unreadable(detail)
        ServiceAnswer.TimedOut -> ServiceAnswer.TimedOut
    }
}

/**
 * The street read on a connection of its own.
 *
 * The connection is closed when the street ends and when the street is
 * closed, and closing it twice is not an error: the client's "I am done" and
 * the service's "the street is over" are the same event seen from two sides.
 */
private class SocketServiceStreet(
    private val channel: ServiceChannel,
) : ServiceStreet {

    private var ended = false

    override fun next(): ServiceStreetItem? {
        if (ended) return null
        return when (val read = channel.readLine()) {
            is ServiceRead.Line -> {
                when (val decoded = ServiceCodec.decodeLine(read.text)) {
                    is ServiceLine.Record -> ServiceStreetItem.Record(decoded.cursor, decoded.record, decoded.hasMore)
                    is ServiceLine.End -> {
                        ended = true
                        ServiceStreetItem.End(decoded.reason, decoded.cursor)
                    }
                    is ServiceLine.Refused -> {
                        ended = true
                        ServiceStreetItem.Refused(decoded.refusal)
                    }
                    is ServiceLine.Answered -> {
                        ended = true
                        null
                    }
                    null -> {
                        ended = true
                        null
                    }
                }
            }
            ServiceRead.Closed -> {
                ended = true
                null
            }
            ServiceRead.TimedOut -> {
                ended = true
                null
            }
        }
    }

    override fun close() {
        ended = true
        channel.close()
    }
}

/**
 * The connection over a Unix socket.
 *
 * ## Why the socket and not a stream
 *
 * The service speaks on a Unix socket, and the client speaks on the same
 * one. A TCP client would be a channel that does not exist, and an
 * abstraction for it would be a promise the contract cannot keep.
 */
internal object UnixServiceChannels : ServiceChannelFactory {

    override fun open(endpoint: Path, deadlineMillis: Long?): ServiceChannel {
        val channel = SocketChannel.open(java.net.StandardProtocolFamily.UNIX)
        try {
            // The connection is opened BLOCKING: a non-blocking connect
            // returns before the peer is there, and the client would have to
            // finish the handshake itself.
            channel.connect(UnixDomainSocketAddress.of(endpoint))
        } catch (failure: IOException) {
            channel.close()
            throw failure
        }
        // A channel that is registered with a selector has to be
        // non-blocking: the JDK refuses the registration otherwise, and the
        // selector is what carries the deadline.
        channel.configureBlocking(false)
        val selector = Selector.open()
        return SocketChannelServiceChannel(channel, selector, deadlineMillis)
    }
}

/**
 * One connection of a Unix socket.
 *
 * The deadline is applied to the READING, through a selector: a Unix socket
 * does not carry a read timeout of its own, and a client that promised a
 * deadline it cannot keep is a client that hangs.
 */
private class SocketChannelServiceChannel(
    private val channel: SocketChannel,
    private val selector: Selector,
    private val deadlineMillis: Long?,
) : ServiceChannel {

    /** The key of this connection in the selector. */
    private val key: SelectionKey = channel.register(selector, SelectionKey.OP_READ)

    private val buffer = ByteBuffer.allocate(ServiceProtocol.MAX_LINE_BYTES)
    private val pending = StringBuilder()

    override fun write(line: String) {
        val bytes = (line + "\n").toByteArray(StandardCharsets.UTF_8)
        val outgoing = ByteBuffer.wrap(bytes)
        while (outgoing.hasRemaining()) {
            if (channel.write(outgoing) == 0) {
                // The socket's buffer is full: wait until it can take more.
                await(SelectionKey.OP_WRITE, null)
            }
        }
    }

    override fun readLine(): ServiceRead {
        while (true) {
            val newline = pending.indexOf('\n')
            if (newline >= 0) {
                val line = pending.substring(0, newline)
                pending.delete(0, newline + 1)
                return ServiceRead.Line(line)
            }
            if (!await(SelectionKey.OP_READ, deadlineMillis)) return ServiceRead.TimedOut
            buffer.clear()
            val read = channel.read(buffer)
            if (read < 0) return ServiceRead.Closed
            if (read == 0) continue
            pending.append(String(buffer.array(), 0, read, StandardCharsets.UTF_8))
        }
    }

    /**
     * Waits for the connection to be ready for [op], honouring the deadline.
     *
     * The interest of the key is set to [op] and to nothing else before
     * every wait: a connection that is always writable would otherwise wake
     * the selector for a read that is not ready yet.
     *
     * `false` means the deadline went by: the connection is still open and
     * the client decides what to do with it.
     */
    private fun await(op: Int, timeoutMillis: Long?): Boolean {
        key.interestOps(op)
        val ready = if (timeoutMillis == null) selector.select() else selector.select(timeoutMillis)
        selector.selectedKeys().clear()
        return ready > 0
    }

    override fun close() {
        try {
            channel.close()
        } finally {
            selector.close()
        }
    }
}

/** The string [key] holds, or null when it does not hold a string. */
private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

/**
 * The configuration the answer carries, or null when the answer carries one
 * that cannot be read.
 *
 * An ABSENT field is read as an EMPTY configuration, and that is the whole
 * point of the reading: a service that does not know the field leaves it
 * out, and the client that sent a configuration has to see the difference.
 * A field that is present and is not an object of primitives is `null`,
 * because a value the client cannot read is not a value it may compare.
 */
private fun JsonObject.configuration(): Map<String, String>? {
    val element = this["configuration"] ?: return emptyMap()
    val entries = element as? JsonObject ?: return null
    val values = LinkedHashMap<String, String>()
    for ((name, value) in entries) {
        val text = (value as? JsonPrimitive)?.contentOrNull ?: return null
        values[name] = text
    }
    return values
}

/**
 * The parameter values the answer carries, or null when the answer carries
 * ones that cannot be read.
 *
 * The reading is the configuration's, for the same reason: an ABSENT field is
 * read as NO values, so a service that does not know the field leaves it out
 * and the client that sent values sees the difference instead of a silent
 * success.
 */
private fun JsonObject.parameters(): Map<String, String>? {
    val element = this["parameters"] ?: return emptyMap()
    val entries = element as? JsonObject ?: return null
    val values = LinkedHashMap<String, String>()
    for ((name, value) in entries) {
        val text = (value as? JsonPrimitive)?.contentOrNull ?: return null
        values[name] = text
    }
    return values
}

/** The boolean [key] holds, or null when it does not hold one. */
private fun JsonObject.boolean(key: String): Boolean? =
    this[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()

/** The long [key] holds, or null when it does not hold one. */
private fun JsonObject.long(key: String): Long? =
    this[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
