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
 * about the runs it names, and about no other. [MayStart] adds the right to
 * START a run, and keeps the reading rights of the scope it wraps.
 *
 * ## Why a scope is a TYPE and not a flag
 *
 * "authorized" and "not authorized" are two states, and a scope has three.
 * A boolean would make "authorized for nothing" indistinguishable from "not
 * authorized at all", and those are different facts: the first is a client
 * that may still ask `version`, the second is a client that may ask nothing.
 *
 * ## Why starting is a WRAPPER and not a flag of its own
 *
 * Because "may read these runs" and "may start runs" are two independent
 * rights, and a scope that could only say one of them would force the
 * operator to give up one to get the other. [MayStart] wraps the reading
 * scope, so `all+start` and `none+start` are both expressible and neither
 * of them is a special case of the other.
 *
 * ## What a scope does NOT promise
 *
 * A scope says what a client may ASK about over the socket, and what it may
 * START. It does not make a run private: the same substrate is readable
 * from the command line, with no authorization at all. The scope is a
 * boundary over the SERVICE, not over the DATA, and a consumer that needs
 * the second has to ask for it there.
 */
internal sealed interface ServiceScope {

    /** Every run the service knows. */
    data object All : ServiceScope

    /** No run at all: only the questions about the service itself. */
    data object None : ServiceScope

    /** The runs it names, and no other. */
    data class Runs(val runIds: Set<String>) : ServiceScope

    /**
     * May start a run, and may read what [over] allows.
     *
     * The wrapper exists so that the reading rights and the right to start
     * are independent: a client that may start runs and read nothing is
     * [MayStart] over [None], and one that may start and read everything is
     * [MayStart] over [All].
     */
    data class MayStart(val over: ServiceScope) : ServiceScope

    /** Whether this scope lets a client START a run. */
    val mayStartRuns: Boolean get() = this is MayStart

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
        is MayStart -> over.covers(runId)
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
 * The four cases are the four facts a client acts on differently: it may
 * ask, it has to present another authorization, it has to ask for a scope
 * that covers the run, or it has to ask for an authorization that may start
 * runs.
 */
internal sealed interface ServiceAuthorizationOutcome {

    /** The authorization is one the service knows, and its scope covers the run. */
    data object Authorized : ServiceAuthorizationOutcome

    /** No authorization was presented, or the one presented is not one the service knows. */
    data object NotAuthorized : ServiceAuthorizationOutcome

    /** The authorization is one the service knows, and its scope does NOT cover the run. */
    data class RunNotAuthorized(val runId: String) : ServiceAuthorizationOutcome

    /**
     * The authorization is one the service knows, and its scope does not let
     * it start a run.
     *
     * It is NOT [NotAuthorized]: the token was accepted, and what it lacks
     * is the right to start. A client that could not tell the two apart
     * would learn which authorizations exist by presenting tokens until one
     * is accepted.
     */
    data object StartNotAuthorized : ServiceAuthorizationOutcome
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
 * The scope has ALREADY grown once, and it grew here: `+start` is the right
 * to start a run, and it is a wrapper over the reading scope, so the
 * vocabulary of the file now says both what a client may read and what it
 * may start. That is what a vocabulary that was kept unpublished is FOR.
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

    /**
     * The DIGEST of the credential [presentedToken] carries, when the
     * service knows it.
     *
     * It is what makes a request attributable to a client WITHOUT carrying
     * the secret: the digest is the same value the service looks the token
     * up by, and it cannot be turned back into a token.
     */
    fun digestOfAuthorized(presentedToken: String?): String? {
        if (presentedToken == null) return null
        return if (scopesByDigest.containsKey(digestOf(presentedToken))) {
            digestOf(presentedToken)
        } else {
            null
        }
    }

    /**
     * Whether [presentedToken] may START a run.
     *
     * A null token means the client presented none. The decision is about
     * the RIGHT and not about a run: starting a run is not about any run in
     * particular, so there is no run to be out of scope of.
     */
    fun decideStart(presentedToken: String?): ServiceAuthorizationOutcome {
        if (presentedToken == null) return ServiceAuthorizationOutcome.NotAuthorized
        val scope = scopesByDigest[digestOf(presentedToken)]
            ?: return ServiceAuthorizationOutcome.NotAuthorized
        return if (scope.mayStartRuns) {
            ServiceAuthorizationOutcome.Authorized
        } else {
            ServiceAuthorizationOutcome.StartNotAuthorized
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
                    "line $number: the scope is 'all', 'none' or a list of run ids, " +
                        "optionally with '+start' to let the client start runs; " +
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
         *
         * ## The `+start` suffix
         *
         * The scope may end with `+start`, which adds the right to start a
         * run. The separator cannot be part of a run id —the alphabet is
         * `[a-z0-9._-]`— so the word is read one way and not two, and a
         * file that says `all` still says `all`.
         */
        private fun parseScope(text: String): ServiceScope? {
            val parts = text.split('+')
            if (parts.size > 2) return null
            val reading = parseReadingScope(parts[0]) ?: return null
            if (parts.size == 1) return reading
            if (parts[1] != START_CAPABILITY) return null
            return ServiceScope.MayStart(reading)
        }

        /**
         * The reading scope [text] names, or null when it names none.
         */
        private fun parseReadingScope(text: String): ServiceScope? = when {
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
            is ServiceScope.MayStart -> "${scopeText(scope.over)}$START_SUFFIX"
        }

        /** The scope that names every run. */
        private const val ALL_SCOPE: String = "all"

        /** The scope that names no run. */
        private const val NO_SCOPE: String = "none"

        /** The capability that lets a client start a run. */
        private const val START_CAPABILITY: String = "start"

        /** The suffix that carries [START_CAPABILITY]. */
        private const val START_SUFFIX: String = "+start"

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
