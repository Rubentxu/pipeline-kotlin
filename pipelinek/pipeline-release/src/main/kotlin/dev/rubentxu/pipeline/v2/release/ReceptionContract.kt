package dev.rubentxu.pipeline.v2.release

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The contract of reception: what the receiver observes, and what it may
 * assume about what it observes.
 *
 * The contract answers four questions for somebody who cannot read this
 * code: where a message lands, what it is called, how a message that was
 * already processed is told from one that is pending, and what the receiver
 * owes the receiver when it starts.
 *
 * It is a CONTRACT and not a report: it describes the boundary between two
 * repositories, and the other side has to be able to act on it without
 * knowing anything else about this one. That is why it is a document with a
 * version and a closed shape, and not a paragraph in a design note.
 *
 * It declares the RECEIVER's inbox, and only that one. The messages that
 * travel the other way — the defect reports and the promotion certifications
 * that this product reads — arrive somewhere else and belong to another
 * contract. Two contracts for two directions is not a second authority for
 * one fact; a single contract that mixed them would be.
 *
 * The constructor is internal on purpose. A contract is not something a
 * caller may state by hand: what the receiver observes is a fact of the
 * release, and the only way to state this one is to read the contract this
 * product ships.
 */
@Serializable
data class ReceptionContract internal constructor(
    /** The shape this contract claims to follow. */
    @SerialName("schema_version") val schemaVersion: String = SCHEMA_VERSION,
    /** Which contract this is. */
    @SerialName("contract_id") val contractId: String,
    /** The directory the receiver observes. */
    val inbox: ReceptionInbox,
    /** The message that lands in it. */
    val message: ReceptionMessage,
    /** How a message is told from another one. */
    val identity: ReceptionIdentity,
    /** The states a message is in at the receiver. */
    val states: ReceptionStates,
    /** What the receiver owes the messages it finds when it starts. */
    @SerialName("pending_sweep") val pendingSweep: ReceptionPendingSweep,
) {
    companion object {
        const val SCHEMA_VERSION: String = "pipelinek-reception-contract-1"

        /** The identity of this contract. There is one, and it is this one. */
        const val CONTRACT_ID: String = "pipelinek-release-reception"
    }
}

/**
 * The directory the receiver observes.
 *
 * The directory is named by an environment variable, and its DEFAULT is
 * declared in parts rather than as one string: the base is the state
 * directory of the XDG convention, and the path below it is this product's
 * own. A receiver that reads the parts computes the same directory this
 * product computes, and it does not have to know how this product spells it.
 */
@Serializable
data class ReceptionInbox internal constructor(
    /** The variable that names the directory outright, when it is set. */
    @SerialName("environment_variable") val environmentVariable: String,
    /** Where the directory is when that variable says nothing. */
    val default: ReceptionInboxDefault,
)

/**
 * Where the inbox is when nothing says otherwise.
 *
 * [baseEnvironmentVariable] names the directory the inbox hangs from, and
 * [baseDefaultDirectory] is what that variable falls back to, relative to the
 * home directory of the user. [path] is the part below it.
 */
@Serializable
data class ReceptionInboxDefault internal constructor(
    /** The variable that names the base directory. */
    @SerialName("base_environment_variable") val baseEnvironmentVariable: String,
    /** The base directory, relative to the home directory, when the variable is not set. */
    @SerialName("base_default_directory") val baseDefaultDirectory: String,
    /** The part of the inbox below the base. */
    val path: String,
)

/**
 * The message that lands in the inbox.
 *
 * The file name carries the identity of the candidate, so a receiver can tell
 * two messages apart without opening them and can name the file of one
 * candidate from the candidate alone.
 */
@Serializable
data class ReceptionMessage internal constructor(
    /** The name of the message. */
    val name: String,
    /** The name of the file that carries it, with the digest as a placeholder. */
    @SerialName("file_name") val fileName: String,
    /** The pattern every such file name follows. */
    @SerialName("file_name_pattern") val fileNamePattern: String,
    /** The format of the document. */
    val format: String,
    /** The version of the shape of the document. */
    @SerialName("format_version") val formatVersion: String,
    /** The version of the cross-repository event protocol. */
    @SerialName("protocol_version") val protocolVersion: String,
)

/**
 * How a message is told from another one.
 *
 * The identity of a message is the digest of the candidate it is about, and
 * it travels twice: in the name of the file and in the document itself. A
 * receiver that compares the two knows it is looking at one message.
 *
 * The identity is NEVER the modification time of the file. A copy, a sync and
 * a restore all move that time, and a receiver that keys its processed set on
 * it reprocesses a message it already processed, or skips one it has not.
 */
@Serializable
data class ReceptionIdentity internal constructor(
    /** What the identity is. */
    val rule: String,
    /** Where the identity travels. */
    @SerialName("carried_by") val carriedBy: List<String>,
    /** What the identity is not. */
    val never: String,
)

/**
 * The states a message is in at the receiver.
 *
 * These are the states of RECEPTION, not the states of the certification
 * cycle. The cycle has its own document — the certification receipt — and its
 * own authority, the receiver that certifies.
 */
@Serializable
data class ReceptionStates internal constructor(
    /** A message the receiver has not processed yet. */
    val pending: String,
    /** A message the receiver is processing right now. */
    val claimed: String,
    /** A message the receiver has recorded as processed. */
    val processed: String,
)

/**
 * What the receiver owes the messages it finds when it starts.
 *
 * The sweep is not a courtesy: it is the only thing that covers a message
 * written while the receiver was not running. A unit that fires on a CHANGE
 * never fires for a change that already happened.
 */
@Serializable
data class ReceptionPendingSweep internal constructor(
    /** Whether the receiver has to sweep. */
    val required: Boolean,
    /** Why it has to, in words the receiver can act on. */
    val why: String,
)

/**
 * The identity rule this contract must declare.
 *
 * There is one, and it is the digest of the candidate. A contract that
 * declares another rule is refused by the reader: the receiver would key its
 * processed set on something that does not survive a copy.
 */
const val RECEPTION_IDENTITY_RULE: String = "candidate_digest"

/** The places the identity of a message travels. */
val RECEPTION_IDENTITY_CARRIED_BY: Set<String> = setOf("file_name", "document")

/** What the identity of a message is not. */
const val RECEPTION_IDENTITY_NEVER: String = "modification_time"

/** The placeholder the file name of a message carries the digest in. */
const val RECEPTION_DIGEST_PLACEHOLDER: String = "<candidate_digest>"

/**
 * The directory the receiver observes, as the environment resolves it.
 *
 * The variable named by the contract wins when it says something; otherwise
 * the directory is the base below the base directory, and the base falls back
 * to the home directory of the user. The resolution is a pure function of the
 * contract and of the environment it is given: the same environment always
 * yields the same directory, and a receiver that implements these three rules
 * lands on the same place.
 *
 * It returns null when the contract names no base that can be resolved. A
 * caller that receives null has no directory to write to, and saying so is
 * better than guessing one.
 */
fun ReceptionContract.inboxDirectory(environment: Map<String, String>): String? {
    environment[inbox.environmentVariable]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }

    val base = environment[inbox.default.baseEnvironmentVariable]?.trim()?.takeIf { it.isNotEmpty() }
        ?: environment["HOME"]?.trim()?.takeIf { it.isNotEmpty() }?.let { home ->
            "$home/${inbox.default.baseDefaultDirectory}"
        }
        ?: return null

    val path = inbox.default.path.trim()
    if (path.isEmpty()) return null

    return "$base/$path"
}

/**
 * The name of the file that carries the message about [candidateId].
 *
 * The name is the contract's own template with the digest in the place of the
 * placeholder, so the file of a candidate is named by that candidate and by
 * nothing else.
 */
fun ReceptionContract.messageFileName(candidateId: CandidateId): String =
    message.fileName.replace(RECEPTION_DIGEST_PLACEHOLDER, candidateId.digest)

/**
 * The path of the file that carries the message about [candidateId].
 *
 * It is null when the contract names no directory that can be resolved, and
 * that is the answer: a message with no place to land is not a message that
 * can be emitted.
 */
fun ReceptionContract.messagePath(
    candidateId: CandidateId,
    environment: Map<String, String>,
): Path? {
    val directory = inboxDirectory(environment) ?: return null
    return Paths.get(directory).resolve(messageFileName(candidateId))
}
