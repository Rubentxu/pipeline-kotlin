package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.service.ServiceProtocol
import java.security.MessageDigest

/**
 * The authorization of the local service: what a client may ask about.
 *
 * ## What one authorization may ask about
 *
 * [All] names no run in particular: it may ask about every run the service
 * knows. [None] may ask about no run at all: it may ask the service about
 * ITSELF —its version and its health— and about nothing else. [Runs] may ask
 * about the runs it names, and about no other.
 *
 * ## Why a scope is a TYPE and not a flag
 *
 * "authorized" and "not authorized" are two states, and a scope has three.
 * A boolean would make "authorized for nothing" indistinguishable from "not
 * authorized at all", and those are different facts: the first is a client
 * that may still ask `version`, the second is a client that may ask nothing.
 *
 * ## What a scope does NOT promise
 *
 * A scope says what a client may ASK about over the socket. It does not make
 * a run private: the same substrate is readable from the command line, with
 * no authorization at all. The scope is a boundary over the SERVICE, not over
 * the DATA, and a consumer that needs the second has to ask for it there.
 */
internal sealed interface ServiceScope {

    /** Every run the service knows. */
    data object All : ServiceScope

    /** No run at all: only the questions about the service itself. */
    data object None : ServiceScope

    /** The runs it names, and no other. */
    data class Runs(val runIds: Set<String>) : ServiceScope

    /**
     * Whether this scope covers [runId].
     *
     * The question the service asks BEFORE it looks at the store: a run that
     * is out of scope is refused the same way whether it exists or not, so
     * the scope cannot be used to find out which runs exist.
     */
    fun covers(runId: String): Boolean = when (this) {
        All -> true
        None -> false
        is Runs -> runId in runIds
    }
}

/**
 * One authorization of the local service: a token, a label for the operator
 * and the scope it carries.
 *
 * ## What the token is
 *
 * A secret the client presents and the service recognizes. It is NOT a
 * pipeline credential: those are secrets a STEP uses to reach something
 * else, and this one authorizes a question ABOUT the service.
 *
 * ## What the label is
 *
 * A name for the operator, and nothing more: it never travels on the wire,
 * because a label that travels can be enumerated by a client that presents
 * tokens until one is accepted.
 *
 * ## Why the scope travels with the token
 *
 * Because the two are one decision: the operator decides both WHO may ask
 * and WHAT may be asked. A token that carried no scope would authorize a
 * client to ask about every run, which is the decision the operator most
 * often does NOT want to make.
 */
internal data class ServiceAuthorization(
    /** The secret the client presents. */
    val token: String,
    /** The label the operator reads; it never travels on the wire. */
    val label: String,
    /** What this authorization may ask about. */
    val scope: ServiceScope,
)

/**
 * What the service decided about a request that carried an authorization.
 *
 * The three cases are the three facts a client acts on differently: it may
 * ask, it has to present another authorization, or it has to ask for a scope
 * that covers the run.
 */
internal sealed interface ServiceAuthorizationOutcome {

    /** The authorization is one the service knows, and its scope covers the run. */
    data object Authorized : ServiceAuthorizationOutcome

    /** No authorization was presented, or the one presented is not one the service knows. */
    data object NotAuthorized : ServiceAuthorizationOutcome

    /** The authorization is one the service knows, and its scope does NOT cover the run. */
    data class RunNotAuthorized(val runId: String) : ServiceAuthorizationOutcome
}

/**
 * The authorizations a service knows, and the decision they produce.
 *
 * ## What this holds
 *
 * The SCOPE of every known authorization, keyed by the DIGEST of its token.
 * The token itself is not kept: the service never has to compare a token
 * with a token, and a service that does not hold the secrets does not leak
 * them when it is dumped, listed or serialized.
 *
 * ## Why the digest and not the token
 *
 * A comparison of secrets that returns early says, by how long it took,
 * how many leading characters were right. Looking the DIGEST up in a map
 * says nothing about the token: the digest is a fixed-size value, and the
 * map is keyed by it.
 *
 * ## Why this is NOT in the published SDK
 *
 * Because the vocabulary of a scope is not finished, and publishing it
 * finishes it.
 *
 * The published artifact —`pipeline-service-client`— carries the WIRE: the
 * credential that travels on it and the NAMES of the refusals it answers
 * with. A client of that artifact needs both and nothing else: it presents
 * the token the operator gave it and it reads the refusal by its name.
 *
 * The SCOPE is the service's own business, and the service is about to grow
 * doors it does not have yet: starting a run, cancelling a run, answering a
 * question a run is waiting for. A scope of `all`, `none` and a list of run
 * ids cannot express any of those — "may start a run", "may cancel THIS
 * run", "may answer THIS question" — and every one of them would add a case
 * to a SEALED interface that is already published, which is a versioned break
 * of a published ABI with its entry in the ledger of deliberate breaks.
 *
 * Keeping the scope here means the vocabulary can grow while the service
 * grows, and the only thing frozen in the published artifact is the thing
 * that will not change: the wire.
 *
 * ## What this does not decide
 *
 * It does not read a file, and it does not touch a socket. It is a pure
 * function of the authorizations it was given, so the decision it takes can
 * be tested without a service running — and the file that holds those
 * authorizations is somebody else's problem.
 */
internal class ServiceAuthorizations private constructor(
    private val scopesByDigest: Map<String, ServiceScope>,
    private val knownLabels: List<String>,
) {

    /** How many authorizations the service knows. */
    val size: Int get() = scopesByDigest.size

    /** The labels of the authorizations the service knows, for the operator. */
    val labels: List<String> get() = knownLabels

    /**
     * What [presentedToken] may ask about [runId].
     *
     * A null token means the client presented none. A null [runId] means the
     * question is about the service itself —its version or its health— and
     * not about a run, so only the token decides.
     */
    fun decide(presentedToken: String?, runId: String?): ServiceAuthorizationOutcome {
        if (presentedToken == null) return ServiceAuthorizationOutcome.NotAuthorized
        val scope = scopesByDigest[digestOf(presentedToken)]
            ?: return ServiceAuthorizationOutcome.NotAuthorized
        if (runId == null) return ServiceAuthorizationOutcome.Authorized
        return if (scope.covers(runId)) {
            ServiceAuthorizationOutcome.Authorized
        } else {
            ServiceAuthorizationOutcome.RunNotAuthorized(runId)
        }
    }

    companion object {

        /**
         * The longest token an authorization may carry.
         *
         * The WIRE's bound and not a second one: the token in the file is
         * the token that travels on the socket, so a token the wire would
         * refuse is a token the file must not accept either. The authority
         * is [ServiceProtocol.MAX_TOKEN_LENGTH] and this name is only a
         * second spelling of it.
         */
        const val MAX_TOKEN_LENGTH: Int = ServiceProtocol.MAX_TOKEN_LENGTH

        /** The longest label an authorization may carry. */
        const val MAX_LABEL_LENGTH: Int = 64

        /**
         * The longest run id a scope may name.
         *
         * The WIRE's bound and not a second one: the run id in the scope is
         * the run id that travels on the socket.
         */
        const val MAX_RUN_ID_LENGTH: Int = ServiceProtocol.MAX_RUN_ID_LENGTH

        /**
         * The authorizations [authorizations] describes.
         *
         * The tokens are digested and dropped: what the service keeps is the
         * scope of every authorization and nothing that could be presented
         * back to it.
         */
        fun of(authorizations: List<ServiceAuthorization>): ServiceAuthorizations {
            val scopes = LinkedHashMap<String, ServiceScope>()
            val labels = mutableListOf<String>()
            for (authorization in authorizations) {
                scopes[digestOf(authorization.token)] = authorization.scope
                labels += authorization.label
            }
            return ServiceAuthorizations(scopes, labels.sorted())
        }

        /**
         * The authorizations [text] holds.
         *
         * A refusal, and not an exception: the text is the operator's, and a
         * file the operator wrote wrong has to be answered with a sentence
         * about the file, not with a stack trace.
         */
        fun parse(text: String): ServiceAuthorizationsReading {
            val authorizations = mutableListOf<ServiceAuthorization>()
            val digests = mutableSetOf<String>()
            text.lineSequence().forEachIndexed { index, raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
                val number = index + 1
                val fields = line.split(Regex("\\s+"))
                if (fields.size != 3) {
                    return ServiceAuthorizationsReading.Refused(
                        "line $number: an authorization is a token, a label and a scope",
                    )
                }
                val (token, label, scope) = fields
                if (token.length > MAX_TOKEN_LENGTH) {
                    return ServiceAuthorizationsReading.Refused(
                        "line $number: the token is longer than $MAX_TOKEN_LENGTH characters",
                    )
                }
                if (label.length > MAX_LABEL_LENGTH || !LABEL.matches(label)) {
                    return ServiceAuthorizationsReading.Refused(
                        "line $number: the label may hold letters, digits, dots, dashes and underscores",
                    )
                }
                val parsed = parseScope(scope) ?: return ServiceAuthorizationsReading.Refused(
                    "line $number: the scope is 'all', 'none' or a list of run ids; " +
                        "a run id may hold only letters, digits, '.', '_' and '-'",
                )
                val digest = digestOf(token)
                if (!digests.add(digest)) {
                    return ServiceAuthorizationsReading.Refused(
                        "line $number: this token is already on line ${authorizations.size + 1}",
                    )
                }
                authorizations += ServiceAuthorization(token, label, parsed)
            }
            return ServiceAuthorizationsReading.Read(authorizations)
        }

        /**
         * The text [authorizations] travels as.
         *
         * The inverse of [parse], and the format the service writes its own
         * file in: one authorization per line, so a file a human reads is a
         * file a human can edit.
         */
        fun format(authorizations: List<ServiceAuthorization>): String =
            authorizations.joinToString("\n") { authorization ->
                "${authorization.token} ${authorization.label} ${scopeText(authorization.scope)}"
            }

        /**
         * The digest of [token].
         *
         * SHA-256 over the token's bytes, in hexadecimal. The digest is what
         * the service stores and looks up; the token is never compared.
         */
        internal fun digestOf(token: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
            val text = StringBuilder(digest.size * 2)
            for (byte in digest) {
                val value = byte.toInt() and 0xff
                text.append(HEX[value ushr 4])
                text.append(HEX[value and 0x0f])
            }
            return text.toString()
        }

        /**
         * The scope [text] names, or null when it names none.
         *
         * A run id in a scope is checked against the SAME authority the wire
         * checks a run id against —[RunId.isNameable]—, so a scope cannot
         * name a run the service would refuse to answer about. A scope that
         * names such a run would be a scope that could never cover anything,
         * and a file that says it is wrong is refused rather than read.
         */
        private fun parseScope(text: String): ServiceScope? = when {
            text == ALL_SCOPE -> ServiceScope.All
            text == NO_SCOPE -> ServiceScope.None
            else -> {
                val runIds = text.split(',').map { it.trim() }
                if (runIds.isEmpty() || runIds.any { it.isEmpty() || it.length > MAX_RUN_ID_LENGTH }) {
                    null
                } else if (runIds.any { !RunId.isNameable(it) }) {
                    null
                } else {
                    ServiceScope.Runs(runIds.toSet())
                }
            }
        }

        /** The text [scope] travels as. */
        private fun scopeText(scope: ServiceScope): String = when (scope) {
            ServiceScope.All -> ALL_SCOPE
            ServiceScope.None -> NO_SCOPE
            is ServiceScope.Runs -> scope.runIds.joinToString(",")
        }

        /** The scope that names every run. */
        private const val ALL_SCOPE: String = "all"

        /** The scope that names no run. */
        private const val NO_SCOPE: String = "none"

        /** The characters a label may hold. */
        private val LABEL: Regex = Regex("[A-Za-z0-9._-]+")

        /** The digits and letters a digest is written with. */
        private const val HEX: String = "0123456789abcdef"
    }
}

/**
 * What the authorizations file held.
 *
 * Two shapes, and a caller has to act on both, so it reads them with an
 * exhaustive `when`: the authorizations, or the sentence about the line that
 * is wrong.
 */
internal sealed interface ServiceAuthorizationsReading {

    /** The file held authorizations. */
    data class Read(val authorizations: List<ServiceAuthorization>) : ServiceAuthorizationsReading

    /** The file held something that is not an authorization, and this says which line. */
    data class Refused(val detail: String) : ServiceAuthorizationsReading
}
