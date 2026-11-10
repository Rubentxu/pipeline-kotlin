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
 * about the runs it names, and about no other. [WithRights] adds the rights
 * it carries —to start a run, to cancel one, to answer the questions a run
 * asks— and keeps the reading rights of the scope it wraps.
 *
 * ## Why a scope is a TYPE and not a flag
 *
 * "authorized" and "not authorized" are two states, and a scope has three.
 * A boolean would make "authorized for nothing" indistinguishable from "not
 * authorized at all", and those are different facts: the first is a client
 * that may still ask `version`, the second is a client that may ask nothing.
 *
 * ## Why the rights are a SET and not a wrapper each
 *
 * Because the rights are INDEPENDENT of each other and all of them combine
 * with the reading. "May read these runs", "may start runs" and "may cancel
 * runs" are three facts, and a scope that could only say one of them would
 * force the operator to give up one to get the other. One wrapper that
 * carries a SET says all of them at once: `all+start+cancel` is one case
 * and not a nest of wrappers, and the next right does not add a case to a
 * sealed interface.
 *
 * ## What a scope does NOT promise
 *
 * A scope says what a client may ASK about over the socket, what it may
 * START and what it may CANCEL. It does not make a run private: the same
 * substrate is readable from the command line, with no authorization at all.
 * The scope is a boundary over the SERVICE, not over the DATA, and a
 * consumer that needs the second has to ask for it there.
 */
internal sealed interface ServiceScope {

    /** Every run the service knows. */
    data object All : ServiceScope

    /** No run at all: only the questions about the service itself. */
    data object None : ServiceScope

    /** The runs it names, and no other. */
    data class Runs(val runIds: Set<String>) : ServiceScope

    /**
     * May do what [granted] says, and may read what [over] allows.
     *
     * The wrapper exists so that the reading rights and the rights over the
     * runs are independent: a client that may cancel runs and read nothing
     * is [WithRights] over [None], and one that may cancel and read
     * everything is [WithRights] over [All].
     */
    data class WithRights(val over: ServiceScope, val granted: Set<Right>) : ServiceScope

    /**
     * The rights a client may have over the runs.
     *
     * They are rights of the SERVICE, not of the data: they say what a
     * client may ask the service to DO, and the substrate they reach is the
     * one the operator named.
     */
    enum class Right {
        /** May ask the service to start a run. */
        Start,

        /** May ask the service to cancel a run. */
        Cancel,

        /** May list the questions a run is waiting for and answer them. */
        Answer,
    }

    /**
     * The rights this scope grants, over the reading scope it wraps.
     *
     * The rights of a wrapper are its OWN and the rights of what it wraps:
     * a scope that wraps another scope that grants a right grants it too.
     * The parser never nests today, and this says so —the KDoc of
     * [WithRights]— but a scope that lost a right on the way would lose it
     * silently, and the reading scope right below already recurses.
     */
    val grantedRights: Set<Right> get() = when (this) {
        is WithRights -> over.grantedRights + granted
        All, None, is Runs -> emptySet()
    }

    /** Whether this scope lets a client START a run. */
    val mayStartRuns: Boolean get() = Right.Start in grantedRights

    /** Whether this scope lets a client CANCEL a run. */
    val mayCancelRuns: Boolean get() = Right.Cancel in grantedRights

    /** Whether this scope lets a client LIST and ANSWER the questions of a run. */
    val mayAnswerInputs: Boolean get() = Right.Answer in grantedRights

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
        is WithRights -> over.covers(runId)
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
    /**
     * The credentials the runs this authorization starts may request from
     * the broker, each with the kind it may be requested as.
     *
     * Empty means none: a credential is granted by being named, never by
     * being absent from a list. The grants are names and kinds, so this
     * value carries no secret and may be printed.
     */
    val credentialGrants: Set<CredentialGrant> = emptySet(),
)

/**
 * What the service decided about a request that carried an authorization.
 *
 * The cases are the facts a client acts on differently: it may ask, it has
 * to present another authorization, it has to ask for a scope that covers
 * the run, or it has to ask for an authorization that holds the right the
 * operation needs.
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

    /**
     * The authorization is one the service knows, and its scope does not let
     * it cancel a run.
     *
     * It is NOT [NotAuthorized], for the same reason [StartNotAuthorized]
     * is not: the token was accepted, and what it lacks is the right.
     */
    data object CancelNotAuthorized : ServiceAuthorizationOutcome

    /**
     * The authorization is one the service knows, and its scope does not let
     * it list or answer the questions of a run.
     *
     * It is NOT [NotAuthorized], for the same reason [StartNotAuthorized]
     * is not: the token was accepted, and what it lacks is the right.
     */
    data object AnswerNotAuthorized : ServiceAuthorizationOutcome
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
    private val grantsByDigest: Map<String, Set<CredentialGrant>>,
    private val labelsByDigest: Map<String, String>,
    private val knownLabels: List<String>,
) {

    /** How many authorizations the service knows. */
    val size: Int get() = scopesByDigest.size

    /**
     * Whether any authorization lets its runs request a credential.
     *
     * It is the fact that decides whether the service has to open a store
     * at all: a service nobody granted a credential to has no reason to
     * hold the means of reading one.
     */
    val grantsAnyCredential: Boolean get() = grantsByDigest.values.any { it.isNotEmpty() }

    /**
     * The credentials the authorization whose token has [digest] grants.
     *
     * Looked up by the digest and by nothing else: a null digest —a run no
     * client started— and a digest the service does not know both hold
     * nothing, and nothing is the answer for every client that was not
     * granted a credential. The grants of one authorization are never
     * reachable from another's digest.
     */
    fun grantsOfDigest(digest: String?): Set<CredentialGrant> =
        if (digest == null) emptySet() else grantsByDigest[digest].orEmpty()

    /** The labels of the authorizations the service knows, for the operator. */
    val labels: List<String> get() = knownLabels

    /**
     * The label of the authorization whose token has [digest], or null when
     * the service does not know it.
     *
     * It is how the service names WHO answered a question without carrying
     * the name on the wire: the client presents a token, the service looks
     * the digest up, and the name that comes out is the one the OPERATOR
     * gave that token. A label a client could choose would be attribution,
     * not identity.
     */
    fun labelOfDigest(digest: String?): String? = if (digest == null) null else labelsByDigest[digest]

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

    /**
     * Whether [presentedToken] may CANCEL the run [runId].
     *
     * ## Why the right AND the run are asked
     *
     * Because cancelling is about a RIGHT and about a RUN, and both have to
     * hold. The right is the one the operator granted, and the run is the
     * one the scope covers: a client that may cancel every run and may read
     * none has no run to cancel, and one that may read a run and may cancel
     * none may not touch it.
     *
     * ## Why the right is asked FIRST
     *
     * Because the right is a fact about the CREDENTIAL and the coverage is
     * a fact about a RUN, and a client that lacks the right must not learn
     * anything about the runs from the answer: a client that may cancel
     * nothing hears that, whatever run it names.
     */
    fun decideCancel(presentedToken: String?, runId: String): ServiceAuthorizationOutcome {
        if (presentedToken == null) return ServiceAuthorizationOutcome.NotAuthorized
        val scope = scopesByDigest[digestOf(presentedToken)]
            ?: return ServiceAuthorizationOutcome.NotAuthorized
        if (!scope.mayCancelRuns) return ServiceAuthorizationOutcome.CancelNotAuthorized
        if (!scope.covers(runId)) return ServiceAuthorizationOutcome.RunNotAuthorized(runId)
        return ServiceAuthorizationOutcome.Authorized
    }

    /**
     * Whether [presentedToken] may LIST and ANSWER the questions of [runId].
     *
     * The same two facts as [decideCancel] and in the same order: the right
     * is a fact about the credential and the coverage is a fact about a run,
     * and a client that lacks the right hears that whatever run it names.
     */
    fun decideAnswer(presentedToken: String?, runId: String): ServiceAuthorizationOutcome {
        if (presentedToken == null) return ServiceAuthorizationOutcome.NotAuthorized
        val scope = scopesByDigest[digestOf(presentedToken)]
            ?: return ServiceAuthorizationOutcome.NotAuthorized
        if (!scope.mayAnswerInputs) return ServiceAuthorizationOutcome.AnswerNotAuthorized
        if (!scope.covers(runId)) return ServiceAuthorizationOutcome.RunNotAuthorized(runId)
        return ServiceAuthorizationOutcome.Authorized
    }

    /**
     * Whether ANY authorization may answer a question.
     *
     * The service reads this BEFORE it composes the part that reads the
     * questions a run published and writes the answers: a service nobody
     * granted the right to has no reason to hold the means of doing it.
     */
    val mayAnswerAnyInput: Boolean get() = scopesByDigest.values.any { it.mayAnswerInputs }

    /**
     * Whether ANY authorization may cancel a run.
     *
     * The service reads this BEFORE it opens the substrate, because a
     * cancellation makes the service WRITE to it: a service that cannot
     * cancel anything opens the store in read-only mode, and one that can
     * opens it in read-write. The decision is about the store, not about
     * any run.
     */
    val mayCancelAnyRun: Boolean get() = scopesByDigest.values.any { it.mayCancelRuns }

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
            val grants = LinkedHashMap<String, Set<CredentialGrant>>()
            val labelsByDigest = LinkedHashMap<String, String>()
            val labels = mutableListOf<String>()
            for (authorization in authorizations) {
                val digest = digestOf(authorization.token)
                scopes[digest] = authorization.scope
                grants[digest] = authorization.credentialGrants
                labelsByDigest[digest] = authorization.label
                labels += authorization.label
            }
            return ServiceAuthorizations(scopes, grants, labelsByDigest, labels.sorted())
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
                if (fields.size != 3 && fields.size != 4) {
                    return ServiceAuthorizationsReading.Refused(
                        "line $number: an authorization is a token, a label and a scope, " +
                            "and optionally the credentials it grants",
                    )
                }
                val token = fields[0]
                val label = fields[1]
                val scope = fields[2]
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
                        "optionally with '+start', '+cancel' and '+answer' to let the client " +
                        "start runs, cancel them and answer the questions they ask; " +
                        "a run id may hold only letters, digits, '.', '_' and '-'; " +
                        "'+cancel' and '+answer' need a scope that covers a run, so " +
                        "'none+cancel' and 'none+answer' are refused",
                )
                val digest = digestOf(token)
                if (!digests.add(digest)) {
                    return ServiceAuthorizationsReading.Refused(
                        "line $number: this token is already on line ${authorizations.size + 1}",
                    )
                }
                val grants = if (fields.size == 4) {
                    parseGrants(fields[3]) ?: return ServiceAuthorizationsReading.Refused(
                        "line $number: the fourth field is '$GRANTS_PREFIX' and a comma-separated list " +
                            "of '<credential-id>:<kind>', each id holding only letters, digits, '.', '_' " +
                            "and '-' (up to ${CredentialGrant.MAX_CREDENTIAL_ID_LENGTH} characters) " +
                            "and named once; the kind is one of ${BrokeredCredentialKind.words.joinToString(", ")}",
                    )
                } else {
                    emptySet()
                }
                authorizations += ServiceAuthorization(token, label, parsed, grants)
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
                "${authorization.token} ${authorization.label} ${scopeText(authorization.scope)}" +
                    grantsText(authorization.credentialGrants)
            }

        /**
         * The grants [text] names, or null when it names none that the file may carry.
         *
         * The field is `credentials=` and a list of `<id>:<kind>`. A credential
         * named twice is refused even when both mentions agree: a line that
         * repeats itself is a line the operator did not read back. An empty
         * list is refused too, because a grant that is not there cannot be
         * told from a mistake.
         */
        private fun parseGrants(text: String): Set<CredentialGrant>? {
            if (!text.startsWith(GRANTS_PREFIX)) return null
            val entries = text.removePrefix(GRANTS_PREFIX).split(',')
            val grants = LinkedHashSet<CredentialGrant>()
            val ids = mutableSetOf<String>()
            for (entry in entries) {
                val parts = entry.split(':')
                if (parts.size != 2) return null
                val (id, word) = parts
                if (!CredentialGrant.isNameable(id)) return null
                val kind = BrokeredCredentialKind.ofWord(word) ?: return null
                if (!ids.add(id)) return null
                grants += CredentialGrant(id, kind)
            }
            return grants
        }

        /** The field that carries [grants], with its leading space, or nothing when there are none. */
        private fun grantsText(grants: Set<CredentialGrant>): String =
            if (grants.isEmpty()) {
                ""
            } else {
                " " + GRANTS_PREFIX + grants
                    .sortedBy { it.credentialId }
                    .joinToString(",") { "${it.credentialId}:${it.kind.word}" }
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
         * ## The `+start`, `+cancel` and `+answer` suffixes
         *
         * The scope may carry the rights it grants, each of them named after
         * a `+`. The separator cannot be part of a run id —the alphabet is
         * `[a-z0-9._-]`— so the word is read one way and not two, and a
         * file that says `all` still says `all`. A right named twice is a
         * file with a mistake, and it is refused rather than read as one.
         *
         * ## Why `none+cancel` and `none+answer` are refused and `none+start` is not
         *
         * Because the right to START is not about any run —the run it
         * starts does not exist yet— and the rights to CANCEL and to ANSWER
         * are about a run that does exist. A scope that names no run covers
         * no run, so those rights could never be exercised: a line that says
         * `none+cancel` or `none+answer` is a file with a mistake, and the
         * file is refused rather than read as a promise the service cannot
         * keep.
         */
        private fun parseScope(text: String): ServiceScope? {
            val parts = text.split('+')
            val reading = parseReadingScope(parts[0]) ?: return null
            if (parts.size == 1) return reading
            val rights = mutableSetOf<ServiceScope.Right>()
            for (capability in parts.drop(1)) {
                val right = when (capability) {
                    START_CAPABILITY -> ServiceScope.Right.Start
                    CANCEL_CAPABILITY -> ServiceScope.Right.Cancel
                    ANSWER_CAPABILITY -> ServiceScope.Right.Answer
                    else -> return null
                }
                if (!rights.add(right)) return null
            }
            val needsARun = ServiceScope.Right.Cancel in rights || ServiceScope.Right.Answer in rights
            if (needsARun && reading is ServiceScope.None) return null
            return ServiceScope.WithRights(reading, rights)
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
            is ServiceScope.WithRights ->
                scopeText(scope.over) +
                    scope.granted.sortedBy { it.ordinal }.joinToString("") { capability ->
                        when (capability) {
                            ServiceScope.Right.Start -> START_SUFFIX
                            ServiceScope.Right.Cancel -> CANCEL_SUFFIX
                            ServiceScope.Right.Answer -> ANSWER_SUFFIX
                        }
                    }
        }

        /** The scope that names every run. */
        private const val ALL_SCOPE: String = "all"

        /** The scope that names no run. */
        private const val NO_SCOPE: String = "none"

        /** The capability that lets a client start a run. */
        private const val START_CAPABILITY: String = "start"

        /** The capability that lets a client cancel a run. */
        private const val CANCEL_CAPABILITY: String = "cancel"

        /** The suffix that carries [START_CAPABILITY]. */
        private const val START_SUFFIX: String = "+start"

        /** The suffix that carries [CANCEL_CAPABILITY]. */
        private const val CANCEL_SUFFIX: String = "+cancel"

        /** The capability that lets a client list and answer the questions of a run. */
        private const val ANSWER_CAPABILITY: String = "answer"

        /** The suffix that carries [ANSWER_CAPABILITY]. */
        private const val ANSWER_SUFFIX: String = "+answer"

        /** The prefix of the fourth field, the one that grants credentials. */
        private const val GRANTS_PREFIX: String = "credentials="

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
