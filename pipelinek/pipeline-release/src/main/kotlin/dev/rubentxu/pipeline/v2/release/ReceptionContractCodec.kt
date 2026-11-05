package dev.rubentxu.pipeline.v2.release

import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * What reading a reception contract may yield.
 *
 * The vocabulary of the refusal is the shared one: a document that crosses
 * the boundary between repositories is refused in the same words whoever
 * reads it, and a receiver that has to log a refusal learns one vocabulary
 * and not two that could drift apart.
 */
sealed interface ReceptionContractDecodeResult {

    /** The contract is one this build understands. */
    data class Accepted(val contract: ReceptionContract) : ReceptionContractDecodeResult

    /** The contract is not one this build understands, and [rejection] says why. */
    data class Rejected(val rejection: CertificationRejection) : ReceptionContractDecodeResult
}

/**
 * The reading of the reception contract.
 *
 * Reading is not a negotiation: the reader either understands the whole
 * contract or refuses it, and the refusal names what it did not understand.
 * There are two kinds of refusal and they are of a different nature:
 *
 * 1. **The shape** — a field the contract does not define, or a schema
 *    version this build does not read, is refused.
 * 2. **The agreement** — a well-formed contract that declares a message
 *    other than the one this build emits, a file name other than the one this
 *    build writes, an identity other than the one this build carries, or a
 *    sweep it does not require, is refused too.
 *
 * The second kind is the reason the contract exists as a document rather than
 * as a paragraph. A contract that says the receiver will look in one place
 * while the product writes the message in another is not a contract that is
 * slightly out of date: it is a promise nobody keeps, and only a reader that
 * compares the two can say so.
 */
object ReceptionContractCodec {

    private val reader = Json {
        ignoreUnknownKeys = false
    }

    /**
     * Read the contract out of [text].
     *
     * A document that carries an unknown field is refused by name. A document
     * that does not parse, that declares another schema, or that disagrees
     * with what this build emits is refused with the reason. A contract is
     * returned as its value.
     */
    fun decode(text: String): ReceptionContractDecodeResult = try {
        val element = reader.parseToJsonElement(text)
        val fields = element as? JsonObject
        if (fields == null) {
            rejected(
                "the document is not a JSON object, so it names no reception contract.",
            )
        } else {
            // The names the schema defines, read from the serializer itself, so
            // the check and the document cannot disagree.
            val known = ReceptionContract.serializer().descriptor.elementNames.toSet()
            val unknown = fields.keys - known
            if (unknown.isNotEmpty()) {
                ReceptionContractDecodeResult.Rejected(
                    CertificationRejection.UnknownField(unknown.sorted()),
                )
            } else {
                readContract(fields)
            }
        }
    } catch (e: SerializationException) {
        rejected(e.message ?: "the document is not valid JSON.")
    } catch (e: IllegalArgumentException) {
        rejected(e.message ?: "the document is not valid JSON.")
    }

    /**
     * Read the contract out of a document whose shape is already known to be
     * this build's.
     */
    private fun readContract(fields: JsonObject): ReceptionContractDecodeResult = try {
        val contract = reader.decodeFromJsonElement(ReceptionContract.serializer(), fields)
        if (contract.schemaVersion != ReceptionContract.SCHEMA_VERSION) {
            rejected(
                "the contract declares schema '${contract.schemaVersion}' and this build reads " +
                    "'${ReceptionContract.SCHEMA_VERSION}'. A contract written for another schema " +
                    "is not a contract this build can act on.",
            )
        } else {
            checkAgreement(contract)
        }
    } catch (e: SerializationException) {
        rejected(e.message ?: "the document does not describe a reception contract.")
    } catch (e: IllegalArgumentException) {
        // A value the contract's own invariants refuse -- a blank name, a
        // directory that is not one. The message is the invariant's own, and
        // it is the useful part.
        rejected(e.message ?: "the document does not describe a reception contract.")
    }

    /**
     * Check that the contract says what this build does.
     *
     * Every refusal here names the two things that disagree. A contract that
     * names another message is not a contract with a typo: it is a contract
     * for another conversation, and reading it as this one would put a
     * receiver and a publisher in two places at once.
     */
    private fun checkAgreement(contract: ReceptionContract): ReceptionContractDecodeResult {
        if (contract.contractId != ReceptionContract.CONTRACT_ID) {
            return rejected(
                "the contract declares '${contract.contractId}' and this build reads " +
                    "'${ReceptionContract.CONTRACT_ID}'.",
            )
        }

        val message = contract.message
        if (message.name != CertificationRequestedEvent.NAME) {
            return rejected(
                "the contract names the message '${message.name}' and this build emits " +
                    "'${CertificationRequestedEvent.NAME}'. A receiver that watches one message " +
                    "and a publisher that writes another leave a request nobody answers.",
            )
        }
        if (message.formatVersion != CertificationRequestedEvent.SCHEMA_VERSION) {
            return rejected(
                "the contract declares the message format '${message.formatVersion}' and this " +
                    "build emits '${CertificationRequestedEvent.SCHEMA_VERSION}'. A receiver that " +
                    "reads a document this build does not write cannot answer the question in it.",
            )
        }
        if (message.protocolVersion != CertificationRequestedEvent.PROTOCOL_VERSION) {
            return rejected(
                "the contract declares the protocol version '${message.protocolVersion}' and this " +
                    "build speaks '${CertificationRequestedEvent.PROTOCOL_VERSION}'.",
            )
        }
        if (message.format != RECEPTION_MESSAGE_FORMAT) {
            return rejected(
                "the contract declares the format '${message.format}' and this build writes " +
                    "'$RECEPTION_MESSAGE_FORMAT'.",
            )
        }
        if (message.fileName != CertificationRequestedEvent.FILE_NAME_TEMPLATE) {
            return rejected(
                "the contract names the file '${message.fileName}' and this build writes " +
                    "'${CertificationRequestedEvent.FILE_NAME_TEMPLATE}'. A receiver that looks " +
                    "for one name and a publisher that writes another leave the message unseen.",
            )
        }
        if (message.fileNamePattern != CertificationRequestedEvent.FILE_NAME_PATTERN) {
            return rejected(
                "the contract declares the file name pattern '${message.fileNamePattern}' and " +
                    "this build writes '${CertificationRequestedEvent.FILE_NAME_PATTERN}'. A " +
                    "receiver that filters by another pattern drops the messages it should read.",
            )
        }

        val identity = contract.identity
        if (identity.rule != RECEPTION_IDENTITY_RULE) {
            return rejected(
                "the contract declares the identity '${identity.rule}' and this build carries " +
                    "'$RECEPTION_IDENTITY_RULE'. The identity of a message has to survive a copy, " +
                    "and a modification time does not.",
            )
        }
        if (identity.carriedBy.toSet() != RECEPTION_IDENTITY_CARRIED_BY) {
            return rejected(
                "the contract carries the identity by ${identity.carriedBy.joinToString()} and " +
                    "this build carries it by ${RECEPTION_IDENTITY_CARRIED_BY.joinToString()}.",
            )
        }
        if (identity.never != RECEPTION_IDENTITY_NEVER) {
            return rejected(
                "the contract declares that the identity is never '${identity.never}' and this " +
                    "build never uses '$RECEPTION_IDENTITY_NEVER'.",
            )
        }

        if (!contract.pendingSweep.required) {
            return rejected(
                "the contract does not require the sweep of the pending messages. A unit that " +
                    "fires on a change never fires for a change that already happened, and a " +
                    "message written during an outage would wait for a change that will never " +
                    "come.",
            )
        }
        if (contract.pendingSweep.why.isBlank()) {
            return rejected(
                "the contract requires the sweep and does not say why. A rule the receiver " +
                    "cannot see the reason of is a rule it implements on trust.",
            )
        }

        if (contract.states.pending.isBlank() ||
            contract.states.claimed.isBlank() ||
            contract.states.processed.isBlank()
        ) {
            return rejected(
                "the contract declares a state and does not say what it is. A receiver cannot " +
                    "tell a pending message from a processed one by reading the name of the state.",
            )
        }

        val inbox = contract.inbox
        if (inbox.environmentVariable.isBlank()) {
            return rejected("the contract names no variable for the inbox.")
        }
        if (inbox.default.baseEnvironmentVariable.isBlank()) {
            return rejected("the contract names no variable for the base of the inbox.")
        }
        if (inbox.default.baseDefaultDirectory.isBlank()) {
            return rejected("the contract declares no default base directory for the inbox.")
        }
        if (inbox.default.path.isBlank()) {
            return rejected("the contract declares no path for the inbox below its base.")
        }

        return ReceptionContractDecodeResult.Accepted(contract)
    }

    /** A refusal, in the words the refusals use. */
    private fun rejected(detail: String): ReceptionContractDecodeResult =
        ReceptionContractDecodeResult.Rejected(CertificationRejection.MalformedDocument(detail))
}

/** The format a message of this contract is written in. */
const val RECEPTION_MESSAGE_FORMAT: String = "json"
