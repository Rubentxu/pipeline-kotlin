package dev.rubentxu.pipeline.v2.service

import dev.rubentxu.pipeline.v2.domain.AttemptId
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import dev.rubentxu.pipeline.v2.runtime.inspect.IntrospectionFailure
import dev.rubentxu.pipeline.v2.runtime.inspect.JournalPosition
import dev.rubentxu.pipeline.v2.runtime.inspect.LeaseHolder
import dev.rubentxu.pipeline.v2.runtime.inspect.OutputTailView
import dev.rubentxu.pipeline.v2.runtime.inspect.ProcessRef
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeObservation
import dev.rubentxu.pipeline.v2.runtime.inspect.TailState
import dev.rubentxu.pipeline.v2.runtime.inspect.TerminalObservation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The `run.state` document: the observation of a run, as it travels.
 *
 * ## Why this document has ONE owner
 *
 * The service PROJECTS an observation and the client DE-PROJECTS it, and the
 * two sides have to agree on the shape or the client would read a different
 * fact than the service saw. A projection written apart from its inverse is
 * two shapes, and the one that drifts is the one nobody notices.
 *
 * So the shape lives HERE, in the artifact the client resolves, and the
 * server projects through [encode] and the client reads through [decode].
 * There is no second copy of these fields anywhere.
 *
 * ## Why the observation travels WHOLE
 *
 * The command line renders a run's state from a
 * [dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeObservation], and the
 * remote path has to render the SAME line from the SAME type. A document that
 * carried only part of the observation would make the remote line a
 * DIFFERENT line, and a consumer that compares both would see a difference
 * that is not about the run.
 *
 * The whole observation is therefore here: the attempt, the lease and its
 * fencing token, the journal position, the output tails, the terminal and its
 * outcome, and the reason a run could not be observed at all.
 *
 * ## What the words are
 *
 * The `state` word says which of the four things was observed, and the rest
 * of the document carries the facts of that one. The words are the ones the
 * runtime already uses, so the document does not invent a second vocabulary
 * for the same four facts.
 */
object ServiceRunState {

    /** The word of a run that is in flight and observed. */
    const val RUNNING: String = "running"

    /** The word of a run that reached a terminal state. */
    const val TERMINAL: String = "terminal"

    /** The word of a run that is in flight and holds no live evidence. */
    const val EMPTY: String = "empty"

    /** The word of a substrate that could not be inspected. */
    const val UNOBSERVABLE: String = "unobservable"

    /**
     * The document [observation] travels as.
     */
    fun encode(observation: RuntimeObservation): JsonObject = buildJsonObject {
        when (observation) {
            is RuntimeObservation.Running -> {
                put("state", JsonPrimitive(RUNNING))
                put("attempt", JsonPrimitive(observation.attempt.value))
                put("fencingToken", JsonPrimitive(observation.fencingToken))
                observation.leaseHolder?.let { holder ->
                    put(
                        "leaseHolder",
                        buildJsonObject {
                            put("ownerId", JsonPrimitive(holder.ownerId))
                            put("fencingToken", JsonPrimitive(holder.fencingToken))
                            holder.alive?.let { put("alive", JsonPrimitive(it)) }
                        },
                    )
                }
                put(
                    "journalPosition",
                    buildJsonObject {
                        put("operations", JsonPrimitive(observation.journalPosition.operations))
                        observation.journalPosition.latestOpId?.let { put("latestOpId", JsonPrimitive(it)) }
                        observation.journalPosition.latestTerminalAtMs?.let {
                            put("latestTerminalAtMs", JsonPrimitive(it))
                        }
                    },
                )
                put(
                    "outputTails",
                    JsonArray(
                        observation.outputTails.map { tail ->
                            buildJsonObject {
                                put("stream", JsonPrimitive(tail.stream.value))
                                put(
                                    "state",
                                    JsonPrimitive(
                                        when (val state = tail.state) {
                                            is TailState.Open -> "open"
                                            is TailState.Sealed -> "sealed"
                                        },
                                    ),
                                )
                                put("end", JsonPrimitive(tailExtent(tail.state)))
                                tail.lastOrdinal?.let { put("lastOrdinal", JsonPrimitive(it)) }
                            }
                        },
                    ),
                )
                observation.process?.let { process ->
                    put(
                        "process",
                        buildJsonObject {
                            put("host", JsonPrimitive(process.host))
                            put("pid", JsonPrimitive(process.pid))
                        },
                    )
                }
            }

            is RuntimeObservation.Terminal -> {
                put("state", JsonPrimitive(TERMINAL))
                put("attempt", JsonPrimitive(observation.attempt.value))
                put("at", JsonPrimitive(observation.terminalAtMs))
                put("outcome", JsonPrimitive(outcomeWord(observation.terminal)))
                put("terminalAt", JsonPrimitive(terminalAt(observation.terminal)))
                when (val terminal = observation.terminal) {
                    is TerminalObservation.Failed -> put("failureKind", JsonPrimitive(terminal.failureKind))
                    is TerminalObservation.Other -> put("rawOutcome", JsonPrimitive(terminal.rawOutcome))
                    else -> Unit
                }
            }

            is RuntimeObservation.LiveButEmpty -> {
                put("state", JsonPrimitive(EMPTY))
                put("attempt", JsonPrimitive(observation.attempt.value))
                put("reason", JsonPrimitive(observation.reason))
            }

            is RuntimeObservation.Unobservable -> {
                put("state", JsonPrimitive(UNOBSERVABLE))
                put("failure", JsonPrimitive(failureWord(observation.reason)))
                val cause = (observation.reason as? IntrospectionFailure.StorageError)?.cause
                if (cause != null) put("cause", JsonPrimitive(cause))
            }
        }
    }

    /**
     * The observation [document] carries, or the word that says it does not.
     *
     * The negative is a WORD and not an exception: a document that arrived
     * from another build, or a document that arrived truncated, is a fact the
     * client has to be able to NAME, and a client that cannot name it would
     * either guess or crash.
     */
    fun decode(document: JsonObject): ServiceRunStateDecoding {
        val state = document.string("state") ?: return ServiceRunStateDecoding.Unreadable("no state")
        return when (state) {
            RUNNING -> decodeRunning(document)
            TERMINAL -> decodeTerminal(document)
            EMPTY -> decodeEmpty(document)
            UNOBSERVABLE -> decodeUnobservable(document)
            else -> ServiceRunStateDecoding.Unreadable("unknown state '$state'")
        }
    }

    /** The running observation of [document]. */
    private fun decodeRunning(document: JsonObject): ServiceRunStateDecoding {
        val attempt = document.int("attempt") ?: return unreadable("attempt")
        val fencingToken = document.long("fencingToken") ?: return unreadable("fencingToken")
        val holder = document["leaseHolder"]?.let { value ->
            val holderDocument = value as? JsonObject ?: return unreadable("leaseHolder")
            val ownerId = holderDocument.string("ownerId") ?: return unreadable("leaseHolder.ownerId")
            val holderToken = holderDocument.long("fencingToken") ?: return unreadable("leaseHolder.fencingToken")
            val alive = when (val raw = holderDocument["alive"]) {
                null -> null
                else -> raw.jsonPrimitive.contentOrNull?.toBooleanStrictOrNull()
                    ?: return unreadable("leaseHolder.alive")
            }
            LeaseHolder(ownerId, holderToken, alive)
        }
        val position = document["journalPosition"]?.let { value ->
            val positionDocument = value as? JsonObject ?: return unreadable("journalPosition")
            val operations = positionDocument.int("operations") ?: return unreadable("journalPosition.operations")
            JournalPosition(
                operations = operations,
                latestOpId = positionDocument.string("latestOpId"),
                latestTerminalAtMs = positionDocument.long("latestTerminalAtMs"),
            )
        } ?: return unreadable("journalPosition")
        val tails = document["outputTails"]?.let { value ->
            val array = value as? JsonArray
                ?: return unreadable("outputTails")
            val tails = mutableListOf<OutputTailView>()
            for (element in array) {
                val tailDocument = element as? JsonObject ?: return unreadable("outputTails")
                val stream = tailDocument.string("stream") ?: return unreadable("outputTails.stream")
                val tailState = when (tailDocument.string("state")) {
                    "open" -> TailState.Open(tailDocument.long("end") ?: return unreadable("outputTails.end"))
                    "sealed" -> TailState.Sealed(tailDocument.long("end") ?: return unreadable("outputTails.end"))
                    else -> return unreadable("outputTails.state")
                }
                tails += OutputTailView(
                    stream = OutputStreamId(stream),
                    state = tailState,
                    lastOrdinal = tailDocument.long("lastOrdinal"),
                )
            }
            tails
        } ?: return unreadable("outputTails")
        val process = document["process"]?.let { value ->
            val processDocument = value as? JsonObject ?: return unreadable("process")
            val host = processDocument.string("host") ?: return unreadable("process.host")
            val pid = processDocument.long("pid") ?: return unreadable("process.pid")
            ProcessRef(host, pid)
        }
        return ServiceRunStateDecoding.Observed(
            RuntimeObservation.Running(
                attempt = AttemptId(attempt),
                leaseHolder = holder,
                fencingToken = fencingToken,
                journalPosition = position,
                outputTails = tails,
                process = process,
            ),
        )
    }

    /** The terminal observation of [document]. */
    private fun decodeTerminal(document: JsonObject): ServiceRunStateDecoding {
        val attempt = document.int("attempt") ?: return unreadable("attempt")
        val at = document.long("at") ?: return unreadable("at")
        val terminalAt = document.long("terminalAt") ?: at
        val outcome = document.string("outcome") ?: return unreadable("outcome")
        val terminal = when (outcome) {
            "succeeded" -> TerminalObservation.Succeeded(terminalAt)
            "failed" -> TerminalObservation.Failed(
                document.string("failureKind") ?: return unreadable("failureKind"),
                terminalAt,
            )
            "unstable" -> TerminalObservation.Unstable(terminalAt)
            "aborted" -> TerminalObservation.Aborted(terminalAt)
            "cancelled" -> TerminalObservation.Cancelled(terminalAt)
            "other" -> TerminalObservation.Other(
                document.string("rawOutcome") ?: return unreadable("rawOutcome"),
                terminalAt,
            )
            else -> return unreadable("outcome")
        }
        return ServiceRunStateDecoding.Observed(
            RuntimeObservation.Terminal(AttemptId(attempt), terminal, at),
        )
    }

    /** The empty observation of [document]. */
    private fun decodeEmpty(document: JsonObject): ServiceRunStateDecoding {
        val attempt = document.int("attempt") ?: return unreadable("attempt")
        val reason = document.string("reason") ?: return unreadable("reason")
        return ServiceRunStateDecoding.Observed(
            RuntimeObservation.LiveButEmpty(AttemptId(attempt), reason),
        )
    }

    /** The unobservable answer of [document]. */
    private fun decodeUnobservable(document: JsonObject): ServiceRunStateDecoding {
        val failure = document.string("failure") ?: return unreadable("failure")
        val reason = when (failure) {
            "no-control-root" -> IntrospectionFailure.NoControlRoot
            "no-event-store" -> IntrospectionFailure.NoEventStore
            "no-output-plane" -> IntrospectionFailure.NoOutputPlane
            "no-journal" -> IntrospectionFailure.NoJournal
            "storage-error" -> IntrospectionFailure.StorageError(
                document.string("cause") ?: return unreadable("cause"),
            )
            else -> return unreadable("failure")
        }
        return ServiceRunStateDecoding.Observed(RuntimeObservation.Unobservable(reason))
    }

    /** The refusal of a document that does not carry an observation. */
    private fun unreadable(field: String): ServiceRunStateDecoding =
        ServiceRunStateDecoding.Unreadable("missing or unreadable '$field'")

    /** The extent a tail's state carries. */
    private fun tailExtent(state: TailState): Long = when (state) {
        is TailState.Open -> state.committedEnd
        is TailState.Sealed -> state.finalEnd
    }

    /** The word a terminal carries. */
    private fun outcomeWord(terminal: TerminalObservation): String = when (terminal) {
        is TerminalObservation.Succeeded -> "succeeded"
        is TerminalObservation.Failed -> "failed"
        is TerminalObservation.Unstable -> "unstable"
        is TerminalObservation.Aborted -> "aborted"
        is TerminalObservation.Cancelled -> "cancelled"
        is TerminalObservation.Other -> "other"
    }

    /** The moment a terminal carries. */
    private fun terminalAt(terminal: TerminalObservation): Long = when (terminal) {
        is TerminalObservation.Succeeded -> terminal.terminalAtMs
        is TerminalObservation.Failed -> terminal.terminalAtMs
        is TerminalObservation.Unstable -> terminal.terminalAtMs
        is TerminalObservation.Aborted -> terminal.terminalAtMs
        is TerminalObservation.Cancelled -> terminal.terminalAtMs
        is TerminalObservation.Other -> terminal.terminalAtMs
    }

    /** The word a failure of the substrate carries. */
    private fun failureWord(failure: IntrospectionFailure): String = when (failure) {
        IntrospectionFailure.NoControlRoot -> "no-control-root"
        IntrospectionFailure.NoEventStore -> "no-event-store"
        IntrospectionFailure.NoOutputPlane -> "no-output-plane"
        IntrospectionFailure.NoJournal -> "no-journal"
        is IntrospectionFailure.StorageError -> "storage-error"
    }

    /** The string [key] holds, or null when it does not hold a string. */
    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    /** The int [key] holds, or null when it does not hold one. */
    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

    /** The long [key] holds, or null when it does not hold one. */
    private fun JsonObject.long(key: String): Long? =
        this[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
}

/**
 * What the `run.state` document turned out to be.
 *
 * Two cases and no third: an observation, or the word that says the document
 * does not carry one. A document that cannot be read is not a refusal of the
 * service — the service ANSWERED, and what it answered is not something this
 * build knows how to read.
 */
sealed interface ServiceRunStateDecoding {

    /** The document carried an observation. */
    data class Observed(val observation: RuntimeObservation) : ServiceRunStateDecoding

    /** The document did not carry an observation this build can read. */
    data class Unreadable(val reason: String) : ServiceRunStateDecoding
}
