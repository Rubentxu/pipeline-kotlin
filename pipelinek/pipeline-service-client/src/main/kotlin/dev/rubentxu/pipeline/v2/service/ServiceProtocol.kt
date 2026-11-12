package dev.rubentxu.pipeline.v2.service

import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.events.identity.EventCursor
import dev.rubentxu.pipeline.v2.service.CredentialProviderWire.putAnswer
import dev.rubentxu.pipeline.v2.service.CredentialProviderWire.putClose
import dev.rubentxu.pipeline.v2.service.CredentialProviderWire.putPoll
import dev.rubentxu.pipeline.v2.service.CredentialProviderWire.putRegister
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The wire contract of the local service.
 *
 * ## What this is
 *
 * ONE line in, ONE line out, in JSON. The product already speaks JSON on the
 * wire (`pipelinek.capabilities/2`, the `core.*` payloads) and this contract
 * follows the same convention: the version of the document travels in a
 * `schema` field whose value is a string "name/version", and the discriminator
 * of what the line is travels in a `kind`.
 *
 * ## Why the version is a string and not a number
 *
 * `pipelinek capabilities` publishes `pipelinek.capabilities/2`, and the
 * `core.*` payloads publish their own discriminator with the same shape. A
 * number would be a THIRD way of saying the same thing, and the reader that
 * has to learn three conventions is the reader that gets one of them wrong.
 *
 * ## Why there is one version rule
 *
 * The server serves `pipelinek.service/2` and refuses every other schema with
 * `incompatible-protocol`. The rule is an EXACT match, and not "compatible
 * major": with a single version number, "compatible major" admits two opposite
 * readings (a smaller client IS compatible / any different one is NOT), and a
 * contract that contradicts itself in two consecutive sentences is not a
 * contract. Relaxing the rule is a change of the protocol version, not a
 * decision a slice takes.
 *
 * ## Why the version is 2 and not 1
 *
 * `/1` was the protocol of the service that answers and closes: one request per
 * connection, one answer per connection. A SUBSCRIPTION changes that — the
 * connection stays open and the server writes a street of frames on it — so a
 * client has to be able to tell from the schema alone which life the connection
 * will have. The version is the field that tells it.
 *
 * The jump costs no consumer outside this repository, and that is measured
 * rather than assumed: the published release 0.95.0 was cut at
 * 2026-11-09T15:10:34Z, the verb `serve` landed after that cut, and
 * `pipeline-application` is not published. So `pipelinek.service/1` was never
 * in an artifact anybody could hold.
 *
 * ## What the refusal carries
 *
 * Every refusal has a `kind`, and the kinds do not collide: a client tells
 * apart a run nobody knows from a substrate that is not configured from a
 * store that failed, and it does it by reading a word, not by parsing prose.
 * The two vocabularies that meet here — the run's own negatives and the
 * protocol's — are separate, and their names do not overlap: the protocol's
 * unknown operation is `unknown-op` and the run's is `unknown-operation`.
 */
object ServiceProtocol {

    /** The schema this build serves. The version travels with every answer. */
    const val SCHEMA: String = "pipelinek.service/2"

    /**
     * The biggest line of request this build reads, in bytes.
     *
     * A line bigger than this is refused before it is parsed: reading it whole
     * would let a client make the service hold whatever it wants in memory.
     */
    const val MAX_LINE_BYTES: Int = 64 * 1024

    /**
     * The longest authorization a request may carry.
     *
     * A token is a secret the operator issued, and its length is bounded
     * because the service compares nothing: a token that is longer than this
     * is not a token this service issued, and it is refused as a malformed
     * request instead of being carried around.
     */
    const val MAX_TOKEN_LENGTH: Int = 512

    /**
     * The longest run id a request may carry.
     *
     * A run id is a KEY, not a path and not a text: a client that sends a
     * megabyte of it is not asking about a run. The bound is what makes
     * "zero trust in the client's data" a fact rather than an intention.
     */
    const val MAX_RUN_ID_LENGTH: Int = 256

    /**
     * The longest name of a script a request may carry.
     *
     * A script is named by its name INSIDE the directory the operator
     * admitted, never by a path: the name is resolved against that
     * directory and the result has to stay inside it. A name longer than
     * this is not a name, and it is refused before anything is resolved.
     */
    const val MAX_SCRIPT_NAME_LENGTH: Int = 256

    /**
     * The longest name of a workspace a request may carry.
     *
     * The same rule as the script's name, for the same reason: a workspace
     * is a name inside a directory the operator admitted, and a name that
     * does not fit in a path is not one.
     */
    const val MAX_WORKSPACE_NAME_LENGTH: Int = 256

    /**
     * The longest idempotency key a request may carry.
     *
     * The key is what makes a repeated request the SAME request, and it is
     * a label the client chooses: a key of a megabyte is not a label. The
     * bound is what keeps the record of the starts small enough to be a
     * file a human can read.
     */
    const val MAX_IDEMPOTENCY_KEY_LENGTH: Int = 128

    /**
     * The longest reason a cancellation may carry.
     *
     * The reason is the operator's own words, and they are recorded
     * VERBATIM in the run's history: a reason longer than this is not a
     * reason but a document, and a document in a terminal fact is a thing
     * every reader of the run has to carry. The bound is the one the
     * product already imposes on its own annotated cancellations.
     */
    const val MAX_CANCEL_REASON_LENGTH: Int = 256

    /**
     * The longest operation id an answer may name.
     *
     * The operation id says WHICH question of a run is answered: it is built
     * from the run id and from the position of the step, and a step nested in
     * several parallel branches makes it longer than a run id. It is a KEY and
     * never a path, so a line that carries more than this is not naming an
     * operation of any run.
     */
    const val MAX_INPUT_OP_ID_LENGTH: Int = 1024

    /**
     * The longest message an answer to a question may carry.
     *
     * The message is the responder's own words and the run records it in its
     * history. It is bounded for the reason a cancellation's reason is: a
     * message longer than this is a document, and every reader of the run
     * would have to carry it.
     */
    const val MAX_INPUT_ANSWER_MESSAGE_LENGTH: Int = 256

    /**
     * The longest name, id or revision a configuration provider request may carry in one field.
     *
     * The provider's name, the id of its session, the id of what a poll asked and the
     * revision of the source are all LABELS: a key and never a path or a document. The
     * bound is about the line, which is what arrives; the rules of what a name may BE
     * —its alphabet, its length in the registry— are the service's decision and it
     * refuses them by name.
     */
    const val MAX_PROVIDER_FIELD_LENGTH: Int = 256

    /**
     * The longest wait a poll may ask for, in milliseconds.
     *
     * A poll is a question the service holds open; a number bigger than this is not a wait
     * but a way to keep a connection for ever. The service also caps the wait by its own
     * limit, which is smaller, and this bound is only what keeps the number a number.
     */
    const val MAX_PROVIDER_POLL_WAIT_MILLIS: Long = 60_000L

    /**
     * The longest secret, in bytes, a credential provider may hand over in one answer.
     *
     * A credential is a token or a password, never a document: a value longer than this is
     * refused before it is held. The bound is chosen so that the secret, once written as
     * Base64, fits in [MAX_LINE_BYTES] together with the rest of the line.
     */
    const val MAX_CREDENTIAL_VALUE_BYTES: Int = 32 * 1024

    /**
     * The longest text a credential value travels as: the Base64 form of
     * [MAX_CREDENTIAL_VALUE_BYTES] bytes.
     */
    const val MAX_CREDENTIAL_VALUE_ENCODED_LENGTH: Int = (MAX_CREDENTIAL_VALUE_BYTES + 2) / 3 * 4
}

/**
 * A key a configuration provider declares it can supply.
 *
 * The declaration carries the three facts the service checks BEFORE any value travels: the
 * [name] of the key, the [type] its value has and the [sensitivity] the provider gives it.
 * A key that is not labelled `non-secret` is refused when the provider registers, because
 * the channel carries configuration and configuration is not where a secret belongs.
 *
 * @property name The name of the key, with the same alphabet as the configuration's names.
 * @property type The type of the value: `string`, `integer` or `boolean`.
 * @property sensitivity The label the provider gives the key; only `non-secret` is accepted.
 */
data class ServiceProviderKey(
    val name: String,
    val type: String,
    val sensitivity: String,
)

/**
 * The provider a run start asks for the keys the request did not set itself.
 *
 * ## What it is
 *
 * The NAME of a provider session the same credential registered and the [keys] to resolve
 * through it. The values are never in the request: the service asks the provider for them
 * at the start, before the run exists, and the run keeps exactly the snapshot it validated.
 *
 * ## What it is not
 *
 * A lookup by name across credentials. A provider of that name registered under another
 * credential is not this provider, and the service never falls back to it.
 *
 * @property provider The name the session registered with.
 * @property keys The keys to resolve, in the order the request lists them.
 */
data class ServiceProviderReference(
    val provider: String,
    val keys: List<String>,
)

/**
 * What a client decides about a question a run asked.
 *
 * ## Why two words and nothing else
 *
 * Because a question of a run is closed: the run proceeds or it aborts. A
 * third word would be a decision the run cannot take, so the wire does not
 * have it, and a line that carries one is a malformed request and not a
 * decision to be interpreted.
 *
 * @property word The word the decision travels as on the wire.
 */
enum class ServiceInputDecision(val word: String) {

    /** The run goes on with the body of the question. */
    PROCEED("proceed"),

    /** The run does not go on: the question is closed without running the body. */
    ABORT("abort"),
    ;

    /** Reads a decision back from the word that travels on the wire. */
    companion object {

        /** The decision [word] names, or null when it names none. */
        fun ofWord(word: String): ServiceInputDecision? = entries.firstOrNull { it.word == word }
    }
}

/**
 * What a client asked for.
 *
 * Every case carries the [schema] the client spoke, because the answer says
 * which schema it speaks and a client that cannot compare the two has nothing
 * to compare.
 */
sealed interface ServiceRequest {

    /** The schema the client says it speaks. */
    val schema: String

    /** The version of the product and the version of the protocol. */
    data class Version(override val schema: String) : ServiceRequest

    /** The health of the SERVICE and of the substrate it was composed with. */
    data class Health(override val schema: String) : ServiceRequest

    /** The state of one run. */
    data class RunState(override val schema: String, val runId: String) : ServiceRequest

    /**
     * The request to START a run.
     *
     * ## What the request carries
     *
     * Three names and a configuration: the script to run, the workspace to
     * run it in, the key that makes a repeated request the same request, and
     * the configuration the steps of the run will see. The service owns
     * everything else — the argv, the deadline, the policy — because
     * everything else is a decision of the operator and not of the client.
     *
     * ## Why the script is a NAME and not a path
     *
     * Because the client does not get to say where the script is. It says
     * WHICH script, by its name inside the directory the operator admitted,
     * and the service resolves that name against that directory and refuses
     * anything that lands outside. A request that carried a path would be a
     * request to run whatever the client can name, which is exactly what a
     * socket on a machine must not be.
     *
     * ## Why the workspace is optional
     *
     * Because a run without a workspace is a run in the product's own
     * scratch, and that is a legitimate thing to ask for. When the client
     * names one, the name is resolved with the same rule as the script's.
     *
     * ## What the configuration is
     *
     * A set of NAME/VALUE pairs that the steps of THIS run will see in
     * their environment. It is a snapshot: it is read once, when the run
     * starts, and it does not change while the run lives.
     *
     * It is NOT a secret, and the wire does not pretend it might be: a
     * value is text, and there is no oracle that says whether a text is a
     * secret. The channel is public —the values end up in the environment
     * of the steps— and a client that puts a secret here has published it
     * to whoever can read that process.
     *
     * The RULES (how many entries, what a name may look like, which names
     * are refused) are the service's, and it refuses them by name: see
     * [ServiceRefusal.RunConfigurationRefused].
     *
     * ## What the parameters are
     *
     * The values the caller delivered for the parameters the pipeline
     * DECLARES, together with the declaration the caller knows.
     *
     * The declaration travels with the values because the service does not
     * compile the script: describing a script's parameters is compiling
     * it, and compiling it is running the author's code inside the
     * service. The contract the service checks the values against is
     * therefore the one the client carries, and it is DATA and not
     * authority — the authority is the declaration of the script, and the
     * child verifies the values against it before any STEP of the run runs.
     * The child's check cannot happen earlier: the declaration is built
     * while the author's own body runs.
     *
     * The values are text, and they are not a secret: a value is a text,
     * and there is no oracle that says whether a text is a secret.
     */
    data class StartRun(
        override val schema: String,
        val script: String,
        val workspace: String?,
        val idempotencyKey: String?,
        /**
         * The configuration the steps of the run will see.
         *
         * EMPTY means "no configuration", which is not the same as "an
         * empty configuration": the first is a request that asks for
         * nothing, and the second is a request that asks for nothing and
         * says so. The wire carries no difference between them, because
         * there is none to carry.
         */
        val configuration: Map<String, String> = emptyMap(),
        /**
         * The values the caller delivered for the parameters the pipeline declares.
         *
         * EMPTY means the request asks for no parameters, which is not the same as "the
         * pipeline declares none": the first is a statement about the request and the second
         * is a statement about the script, and the wire carries the first.
         */
        val parameters: Map<String, String> = emptyMap(),
        /**
         * The declaration the caller knows the pipeline has.
         *
         * It travels because the service cannot read the script's own declaration, and it is
         * DATA and not authority: the rules that check a value against it are the product's
         * (see [dev.rubentxu.pipeline.v2.dsl.RunInputs]), and the child checks the same
         * values against the script itself before any STEP of the run runs.
         */
        val parameterContract: List<dev.rubentxu.pipeline.v2.domain.ParameterSpec> = emptyList(),
        /**
         * The provider the service asks for the keys the request did not set itself.
         *
         * `null` means the request asks no provider, which is the request that always was.
         * A reference names a provider session that the SAME credential registered and the
         * keys to resolve through it; the service never looks for a provider of that name
         * under another credential.
         */
        val configurationProvider: ServiceProviderReference? = null,
        /**
         * The credential provider session whose controller holds the credentials of the run.
         *
         * `null` means the run's credentials, if it asks for any, are the service's own. A name
         * means each credential the run asks for is requested, at the moment the run asks, from
         * the controller that registered a session of that name under the SAME authorization.
         * The name is never a lookup across authorizations and there is no fallback to the
         * service's own store.
         */
        val credentialProvider: String? = null,
    ) : ServiceRequest {

        /**
         * The request WITHOUT the values of its configuration.
         *
         * The generated one would render them, and a line of log that
         * carried them would publish them to whoever reads the log. The
         * channel is public, and public is not the same as printed: the
         * values belong to the steps of the run, not to a diagnostic.
         *
         * The same is true of the parameters' values, and for the same reason.
         */
        override fun toString(): String =
            "StartRun(schema=$schema, script=$script, workspace=$workspace, " +
                "idempotencyKey=$idempotencyKey, configuration=${configuration.size} entries, " +
                "parameters=${parameters.size} values, " +
                "configurationProvider=${configurationProvider?.provider}, " +
                "credentialProvider=$credentialProvider)"
    }

    /**
     * The request to REGISTER a configuration provider session.
     *
     * ## What the request carries
     *
     * The [provider] name the session answers to and the [keys] it declares it can supply,
     * each with the type of its value and the sensitivity label the provider gives it.
     *
     * ## What a session is
     *
     * A lease the controller holds by polling. The service never opens a connection to the
     * controller: the controller asks `config.provider.poll` and the service answers with
     * the keys a run is waiting for. A controller that stops polling lets its lease lapse,
     * and a run that asked it for values is refused instead of waiting for ever.
     *
     * ## Why a secret key is refused here
     *
     * Because the channel carries configuration, and configuration is public to whoever can
     * read the run's environment. A key the provider labels secret is refused by name when
     * it is declared, before any value travels.
     */
    data class ProviderRegister(
        override val schema: String,
        val provider: String,
        val keys: List<ServiceProviderKey>,
    ) : ServiceRequest

    /**
     * The request to POLL a provider session: "has a run asked for something?".
     *
     * The service holds the request for at most [waitMillis] —bounded by its own limit— and
     * answers either with the keys a run is waiting for or with an idle answer. The poll
     * renews the lease of the session.
     */
    data class ProviderPoll(
        override val schema: String,
        val sessionId: String,
        val waitMillis: Long,
    ) : ServiceRequest

    /**
     * The request to ANSWER what a poll asked for.
     *
     * Carries the [requestId] the poll returned, the [revision] of the source the values
     * come from and the [values] of exactly the keys that were asked. The values are text,
     * checked against the type the provider declared, and they are never rendered by
     * [toString].
     */
    data class ProviderAnswer(
        override val schema: String,
        val sessionId: String,
        val requestId: String,
        val revision: String,
        val values: Map<String, String>,
    ) : ServiceRequest {

        /** The request WITHOUT its values: a diagnostic is not where configuration belongs. */
        override fun toString(): String =
            "ProviderAnswer(schema=$schema, sessionId=$sessionId, requestId=$requestId, " +
                "revision=$revision, values=${values.size} values)"
    }

    /** The request to CLOSE a provider session instead of letting its lease lapse. */
    data class ProviderClose(
        override val schema: String,
        val sessionId: String,
    ) : ServiceRequest

    /**
     * The request to REGISTER a credential provider session.
     *
     * ## What a credential provider is
     *
     * The controller that keeps the credentials of the runs it starts. The [provider] name is
     * the one a later `run.start` names; the session is a lease the controller holds by polling,
     * exactly as for a configuration provider, because the service never opens a connection to
     * the controller.
     *
     * ## What the session never holds
     *
     * A credential. The controller hands each one over when a run asks, and the service gives
     * it to that run and keeps nothing.
     */
    data class CredentialRegister(
        override val schema: String,
        val provider: String,
    ) : ServiceRequest

    /**
     * The request to POLL a credential provider session: "has a run asked for a credential?".
     *
     * The service holds the request for at most [waitMillis] —bounded by its own limit— and
     * answers either with the credential a run is waiting for or with an idle answer. The poll
     * renews the lease of the session.
     */
    data class CredentialPoll(
        override val schema: String,
        val sessionId: String,
        val waitMillis: Long,
    ) : ServiceRequest

    /**
     * The request to ANSWER what a poll asked: to hand the credential over or to deny it.
     *
     * ## The shape
     *
     * [granted] is the decision, written on the wire as `grant` or `deny` and nothing else. A
     * grant carries the secret in [value], as the Base64 text of its bytes; a denial carries
     * none. The request that was answered is named by [requestId] and is answered once.
     *
     * ## What it never does
     *
     * It never renders the [value]: [toString] shows the identity of the answer and its decision.
     */
    data class CredentialAnswer(
        override val schema: String,
        val sessionId: String,
        val requestId: String,
        val granted: Boolean,
        val value: String?,
    ) : ServiceRequest {

        /** The request WITHOUT its value: a diagnostic is not where a secret belongs. */
        override fun toString(): String =
            "CredentialAnswer(schema=$schema, sessionId=$sessionId, requestId=$requestId, " +
                "decision=${if (granted) "grant" else "deny"}, value=<redacted>)"
    }

    /** The request to CLOSE a credential provider session instead of letting its lease lapse. */
    data class CredentialClose(
        override val schema: String,
        val sessionId: String,
    ) : ServiceRequest

    /**
     * The request to ask where the configuration of a run came from.
     *
     * The answer carries the provenance of every key —its origin and its type— and the
     * revision and the digests of the snapshot, and never a value.
     */
    data class RunConfigurationQuery(
        override val schema: String,
        val runId: String,
    ) : ServiceRequest

    /**
     * The request to CANCEL a run.
     *
     * ## What the request carries
     *
     * The [runId] of the run to cancel, and optionally the [reason] —the
     * operator's own words for why, recorded verbatim in the run's own
     * history. Everything else is the service's decision.
     *
     * ## Why the runId is the RUN's and not the supervision's
     *
     * Because the client only ever learns the run's own identity: it is
     * what the answer of a start carries, and it is what the substrate
     * knows. The supervision is the service's own business and the client
     * never sees it.
     *
     * ## Why the reason is optional
     *
     * Because a cancellation does not need a cause to be a cancellation, and
     * because the product already substitutes one when the caller states
     * none: every cancelled run names a reason instead of carrying an empty
     * field.
     */
    data class CancelRun(
        override val schema: String,
        val runId: String,
        val reason: String?,
    ) : ServiceRequest

    /**
     * The request to LIST the questions of a run that are waiting for an answer.
     *
     * ## What the request carries
     *
     * The [runId] of the run, and nothing else. The questions are the ones the
     * run itself published when its pipeline reached an `input` step; the
     * service reads them and does not make any up.
     *
     * ## Why it needs the right to answer
     *
     * Because the text of a question is the pipeline author's and it can hold
     * what only the person who is going to answer should read. Reading the
     * questions is therefore the right of whoever may answer them, and a
     * credential that may only follow a run is refused.
     */
    data class ListInputs(
        override val schema: String,
        val runId: String,
    ) : ServiceRequest

    /**
     * The request to ANSWER one question of a run.
     *
     * ## What the request carries
     *
     * The [runId], the [opId] of the question and the [decision]. The
     * [message] is the responder's own words and is optional.
     *
     * ## What the request does NOT carry
     *
     * Who is answering. The service knows it from the credential the line was
     * authenticated with, and a name written by the client would be a claim
     * and not a fact: the answer is attributed to the authorization, never to
     * what the line says.
     *
     * ## Why the operation id and not the position of the step
     *
     * Because two steps of a run can share a position: the same step in two
     * stages, or in two branches of a `parallel`, are two questions. The
     * operation id is the durable identity of the dispatch and tells them
     * apart.
     */
    data class AnswerInput(
        override val schema: String,
        val runId: String,
        val opId: String,
        val decision: ServiceInputDecision,
        val message: String?,
    ) : ServiceRequest

    /**
     * The request to DESCRIBE the parameters of a script.
     *
     * ## What the request carries
     *
     * The NAME of the script, and nothing else. A description is a read of the script's
     * own declaration, so it asks about one script by name.
     *
     * ## Why the service REFUSES this request
     *
     * Because describing the parameters of a script means COMPILING it, and compiling it
     * means running the author's code inside the service. A socket on a machine that
     * compiled whatever it was asked about would run untrusted code by answering a
     * question, and a question is easier to ask than a start.
     *
     * The service therefore answers this request with a NAMED refusal —see
     * [ServiceRefusal.DescribeParametersRefused]— that says what it cannot do and why. The
     * name is reserved in the vocabulary on purpose: a build that can describe a script
     * because it admits an artifact that was authorized beforehand answers this same
     * request, and a client written against the name works with both.
     *
     * The name is NOT the one the VALUES are refused with: the values of a request that
     * break a rule are fixed and sent again, and a description this service cannot produce
     * is asked of somebody else.
     *
     * A client that needs the description before it starts a run gets it from the
     * compiler —the command line compiles the script it is about to run— or from the
     * contract it already carries.
     */
    data class DescribeParameters(
        override val schema: String,
        val script: String,
    ) : ServiceRequest
}

/**
 * What a client asked to SUBSCRIBE to.
 *
 * ## Why a second ADT and not two more cases of [ServiceRequest]
 *
 * A request that is answered is a request whose answer is ONE line. A
 * subscription is a request whose answer is a STREET of lines, and the two
 * differ from the first moment: the first is closed with its answer, the
 * second lives until the street ends. One ADT for both would force every
 * reader of [ServiceRequest] to carry a case with no answer, and the compiler
 * could not tell which case is which.
 *
 * ## Why the cursor travels DECODED
 *
 * The cursor is the position the client resumes from, and it is checked
 * before the service does any work: a token of the other plane, a token that
 * does not decode, and a token minted for another run are three DIFFERENT
 * facts, and all three are refusals with a name. A cursor that reached the
 * street undecoded would be a cursor the street would have to distrust, and
 * distrusting a cursor is what this contract exists to prevent.
 */
sealed interface ServiceSubscriptionRequest {

    /** The schema the client says it speaks. */
    val schema: String

    /** The run whose street the client wants. */
    val runId: String

    /**
     * The events of [runId], from [after] onwards.
     *
     * [after] is `null` when the client named no cursor, and then the street
     * starts at the first row the run ever wrote.
     */
    data class EventsFollow(
        override val schema: String,
        override val runId: String,
        val after: EventCursor?,
    ) : ServiceSubscriptionRequest

    /**
     * The console of [runId], from [after] onwards.
     *
     * [after] is the frame ordinal the street starts after, and `-1` is the
     * position before the first frame of the run.
     */
    data class OutputFollow(
        override val schema: String,
        override val runId: String,
        val after: Long,
    ) : ServiceSubscriptionRequest
}

/**
 * Why a street ended.
 *
 * ## The vocabulary is one word, and that is the claim
 *
 * The end of a street says WHY the street ended, and there is exactly one
 * reason a street of this service can end: the run's terminal fact was
 * observed. A street that cannot say that does NOT end — it refuses, with the
 * name of the fact that stopped it.
 *
 * ## Why there is no word for "the run's outcome"
 *
 * Because the outcome is not a fact about the street. A street ending says
 * that the run is OVER; it says nothing about whether the run SUCCEEDED, and
 * a client that reads it as a success is reading a word the wire never said.
 * The outcome is another authority's answer — `run.state`, or the `outcome` of
 * the terminal event — and this vocabulary has no room for a second opinion
 * about it.
 *
 * ## Why the other outcome of the command line is not here
 *
 * The command line's follow has two terminal outcomes: the run finished, or
 * the output was sealed while the run's fate stayed unknown. The second one
 * needs an execution authority that cannot be reached, and this service's
 * substrate always has one — the store is what it was composed with, and the
 * verb refuses to start without it. So the street cannot produce that fact,
 * and a vocabulary that named it would be promising a case no client of this
 * service could ever see.
 */
enum class ServiceEndReason(val word: String) {

    /** The run's terminal fact was observed. The only way a street ends. */
    TerminalObserved("terminal-observed"),
}

/**
 * Why the service did not answer.
 *
 * ## What the kinds are for
 *
 * A client acts differently on each one: a version to upgrade to, a request to
 * fix, a load to shed, a run to give up on. Collapsing them into one "error"
 * would leave that decision to a string comparison, and two clients would
 * compare it differently.
 *
 * ## Why the payload travels with the case
 *
 * Each case carries its own facts, and [ServiceCodec] writes them as they
 * are. A projection written apart from the cases would be a second copy of
 * every refusal, free to disagree with the one the code decides.
 */
sealed interface ServiceRefusal {

    /** The word the wire carries for this refusal. */
    val kind: String

    /** The facts the refusal carries, besides its kind. */
    val payload: JsonObject

    /** The run is not known to this instance. */
    data class UnknownRun(val runId: String) : ServiceRefusal {
        override val kind: String = "unknown-run"
        override val payload: JsonObject = buildJsonObject { put("runId", JsonPrimitive(runId)) }
    }

    /** The substrate has no control root, so there is no lease to consult. */
    data object NotConfiguredControlRoot : ServiceRefusal {
        override val kind: String = "not-configured-control-root"
        override val payload: JsonObject = buildJsonObject { }
    }

    /** The substrate has no event plane configured for this instance. */
    data object NotConfiguredEventPlane : ServiceRefusal {
        override val kind: String = "not-configured-event-plane"
        override val payload: JsonObject = buildJsonObject { }
    }

    /** The substrate has no output plane configured for this instance. */
    data object NotConfiguredOutputPlane : ServiceRefusal {
        override val kind: String = "not-configured-output-plane"
        override val payload: JsonObject = buildJsonObject { }
    }

    /** The storage failed while answering. */
    data class StorageFailed(val cause: String) : ServiceRefusal {
        override val kind: String = "storage-failed"
        override val payload: JsonObject = buildJsonObject { put("cause", JsonPrimitive(cause)) }
    }

    /** Another process is publishing the run, and it holds the publishing authority. */
    data class HeldByAnother(val heldBy: String, val fencingToken: Long) : ServiceRefusal {
        override val kind: String = "held-by-another"
        override val payload: JsonObject = buildJsonObject {
            put("heldBy", JsonPrimitive(heldBy))
            put("fencingToken", JsonPrimitive(fencingToken))
        }
    }

    /** The substrate disagrees with itself about the run. */
    data class Inconsistent(val details: String) : ServiceRefusal {
        override val kind: String = "inconsistent-lease-state"
        override val payload: JsonObject = buildJsonObject { put("details", JsonPrimitive(details)) }
    }

    /** The journal holds no row for the (operation, attempt) pair that was asked for. */
    data class UnknownRunOperation(val operationId: String, val attempt: Int) : ServiceRefusal {
        override val kind: String = "unknown-operation"
        override val payload: JsonObject = buildJsonObject {
            put("operationId", JsonPrimitive(operationId))
            put("attempt", JsonPrimitive(attempt))
        }
    }

    /** The client speaks a schema this build does not serve. */
    data object IncompatibleProtocol : ServiceRefusal {
        override val kind: String = "incompatible-protocol"
        override val payload: JsonObject = buildJsonObject { }
    }

    /** The line is not a request this build can read. */
    data object MalformedRequest : ServiceRefusal {
        override val kind: String = "malformed-request"
        override val payload: JsonObject = buildJsonObject { }
    }

    /** The line is bigger than the service reads. */
    data object RequestTooLarge : ServiceRefusal {
        override val kind: String = "request-too-large"
        override val payload: JsonObject = buildJsonObject { }
    }

    /** The service already has as many connections as it admits. */
    data object TooManyConnections : ServiceRefusal {
        override val kind: String = "too-many-connections"
        override val payload: JsonObject = buildJsonObject { }
    }

    /** The client asked for an operation this build does not have. */
    data object UnknownOp : ServiceRefusal {
        override val kind: String = "unknown-op"
        override val payload: JsonObject = buildJsonObject { }
    }

    /**
     * The cursor the client sent is not a cursor this street can resume from.
     *
     * Three facts land here, and the client acts on them differently, so all
     * three are told apart by the facts this case carries:
     *  - the token does not decode at all;
     *  - the token belongs to the OTHER plane (a byte cursor in the events
     *    street, a frame ordinal in the output street), and resuming from it
     *    would advance the client to a position in the wrong bytes;
     *  - the token names a DIFFERENT run.
     *
     * A cursor that is refused is a cursor the client has to rebuild, and
     * rebuilding it from the frames the street already sent is the client's
     * own job: the service keeps no state per client, so it has no other
     * cursor to offer.
     */
    data class InvalidCursor(val cursor: String, val expected: String) : ServiceRefusal {
        override val kind: String = "invalid-cursor"
        override val payload: JsonObject = buildJsonObject {
            put("cursor", JsonPrimitive(cursor))
            put("expected", JsonPrimitive(expected))
        }
    }

    /**
     * The cursor the client sent is past the end of the history that is there.
     *
     * The run EXISTS and the position does not: the run wrote fewer rows than
     * the cursor names. A client that is told this can reset the cursor to the
     * tail and continue, which is a different action from giving up on the run.
     */
    data class CursorBeyondTail(
        val runId: String,
        val requestedSequence: Long,
        val tailSequence: Long,
    ) : ServiceRefusal {
        override val kind: String = "cursor-beyond-tail"
        override val payload: JsonObject = buildJsonObject {
            put("runId", JsonPrimitive(runId))
            put("requestedSequence", JsonPrimitive(requestedSequence))
            put("tailSequence", JsonPrimitive(tailSequence))
        }
    }

    /**
     * The Output Plane refused to answer, and the refusal says which fact stopped it.
     *
     * ## Why the plane's own word travels
     *
     * The plane has a closed vocabulary of nine refusals and no wire names for
     * them. The service does not INVENT nine names of its own: it carries the
     * plane's word in [reason] and the facts that word came with in [detail],
     * so a client reads the plane's own diagnosis instead of a paraphrase of
     * it. The translation is a total `when`, one case per plane refusal, so
     * no refusal of the plane can lose its way on the wire.
     */
    data class OutputPlaneRefused(val reason: String, val detail: String) : ServiceRefusal {
        override val kind: String = "output-plane-refused"
        override val payload: JsonObject = buildJsonObject {
            put("reason", JsonPrimitive(reason))
            put("detail", JsonPrimitive(detail))
        }
    }

    /**
     * The client presented no authorization, or one this service does not know.
     *
     * ## Why it says nothing about WHICH
     *
     * A refusal that said "the token is wrong" and a refusal that said "there
     * is no token" would let a client tell apart two things it has no right
     * to tell apart: whether an authorization exists at all. One name covers
     * both, and the client learns the only thing it can act on: that it has
     * to present an authorization this service accepts.
     */
    data object NotAuthorized : ServiceRefusal {
        override val kind: String = "not-authorized"
        override val payload: JsonObject = buildJsonObject { }
    }

    /**
     * The authorization is one this service knows, and its scope does not
     * cover the run that was asked about.
     *
     * ## Why the run travels and not the authorization
     *
     * The run is the fact the client has to act on: it asked about a run it
     * may not ask about. The authorization does NOT travel, because a name
     * that travels can be enumerated by a client that presents tokens until
     * one is accepted.
     *
     * ## What the client cannot learn from this refusal
     *
     * The scope is checked BEFORE the store is read, so this refusal says
     * the same thing about a run that exists and about a run that does not:
     * a client out of scope cannot use the service to find out which runs
     * exist.
     */
    data class RunNotAuthorized(val runId: String) : ServiceRefusal {
        override val kind: String = "run-not-authorized"
        override val payload: JsonObject = buildJsonObject { put("runId", JsonPrimitive(runId)) }
    }

    /**
     * The service already holds as many subscriptions as it admits.
     *
     * ## Why it is not `too-many-connections`
     *
     * Because the remedy is different: the client has to close a
     * subscription and come back, while `too-many-connections` asks it to
     * wait for a free slot. The service keeps a budget for subscriptions
     * SEPARATE from the one for connections precisely so that a client can
     * always ask a question; this refusal is what says which of the two
     * budgets it ran into.
     */
    data object TooManySubscriptions : ServiceRefusal {
        override val kind: String = "too-many-subscriptions"
        override val payload: JsonObject = buildJsonObject { }
    }

    /**
     * The authorization is one this service knows, and its scope does not
     * cover STARTING a run.
     *
     * ## Why it is not `not-authorized`
     *
     * Because the two say different things and the client acts differently
     * on them: `not-authorized` says "present an authorization this service
     * accepts", and this one says "this authorization does not let you
     * start runs". A client that could not tell them apart would learn
     * which authorizations exist by presenting tokens until one is
     * accepted — and that is the enumeration this refusal vocabulary exists
     * to prevent.
     */
    data object StartNotAuthorized : ServiceRefusal {
        override val kind: String = "start-not-authorized"
        override val payload: JsonObject = buildJsonObject { }
    }

    /**
     * The script the client named is not one this service admits.
     *
     * ## Why the name travels back
     *
     * Because the name is what the client sent and what it has to fix: a
     * client that asked for a script the service does not admit has to
     * know WHICH name was refused, or it can only guess. The name is the
     * client's own text, so carrying it back leaks nothing.
     */
    data class ScriptNotAdmitted(val script: String) : ServiceRefusal {
        override val kind: String = "script-not-admitted"
        override val payload: JsonObject = buildJsonObject { put("script", JsonPrimitive(script)) }
    }

    /**
     * The workspace the client named is not one this service admits.
     *
     * The same contract as [ScriptNotAdmitted], for the same reason: the
     * workspace is a name inside a directory the operator admitted, and the
     * name the client sent is what it has to fix.
     */
    data class WorkspaceNotAdmitted(val workspace: String) : ServiceRefusal {
        override val kind: String = "workspace-not-admitted"
        override val payload: JsonObject = buildJsonObject { put("workspace", JsonPrimitive(workspace)) }
    }

    /**
     * The idempotency key was used before with a DIFFERENT payload.
     *
     * ## Why it is not a second run
     *
     * Because the key's promise is that one key means one request: the
     * first request with that key decided what would run, and a second
     * request with the same key and a different payload is a request to
     * run something else under a name that already means something. The
     * service refuses it instead of guessing which of the two the client
     * meant.
     */
    data class IdempotencyConflict(val key: String) : ServiceRefusal {
        override val kind: String = "idempotency-conflict"
        override val payload: JsonObject = buildJsonObject { put("key", JsonPrimitive(key)) }
    }

    /**
     * The service already supervises as many runs as it admits.
     *
     * ## Why it is not `too-many-connections`
     *
     * Because the budget is of a different thing: a connection is a client
     * that is here NOW, and a supervised run is a CHILD PROCESS the service
     * is watching. The remedy is the same — come back later — but the
     * client has to know which budget it ran into, because the two are
     * spent by different things.
     */
    data object TooManySupervisedRuns : ServiceRefusal {
        override val kind: String = "too-many-supervised-runs"
        override val payload: JsonObject = buildJsonObject { }
    }

    /**
     * The run did not start.
     *
     * ## What the reason is
     *
     * A word that says WHICH step failed: the child could not be launched,
     * it died before it owned the run, or it did not report its ownership
     * in time. The [detail] is the service's own sentence about it.
     *
     * ## What does NOT travel here
     *
     * The child's own output. A client that starts a run does not get to
     * read the output of the operator's script: the child's stderr is in
     * the operator's log, under the service's own directory.
     */
    data class RunStartRefused(val reason: String, val detail: String) : ServiceRefusal {
        override val kind: String = "run-start-refused"
        override val payload: JsonObject = buildJsonObject {
            put("reason", JsonPrimitive(reason))
            put("detail", JsonPrimitive(detail))
        }
    }

    /**
     * The configuration the client sent breaks a rule.
     *
     * ## Why the reason travels inside
     *
     * Because the rules are many and a client acts differently on each one:
     * a name that is too long is fixed by shortening it, a reserved name is
     * fixed by renaming it, and a value with a NUL is fixed by removing it.
     * One word for every rule would say only "no", and the client would
     * have to guess. The [reason] says WHICH rule, and the [detail] says
     * which one in prose.
     *
     * ## Why it is NOT a shape refusal
     *
     * Because a shape is a property of the LINE and the codec owns the
     * shape of a line ([MalformedRequest]). The rules of the configuration
     * are a decision of the service, and the service decides them.
     *
     * ## What the client learns
     *
     * Only that its configuration was refused, and which rule it broke. The
     * rules are not secret: they are published, in the documentation of the
     * configuration itself.
     */
    data class RunConfigurationRefused(val reason: String, val detail: String) : ServiceRefusal {
        override val kind: String = "run-configuration-refused"
        override val payload: JsonObject = buildJsonObject {
            put("reason", JsonPrimitive(reason))
            put("detail", JsonPrimitive(detail))
        }
    }

    /**
     * The parameters of the request break a rule, or the service cannot check them.
     *
     * ## Why the reason travels inside
     *
     * Because the rules are many and a client acts differently on each one: a value of the
     * wrong type is fixed by delivering another value, a name the pipeline does not declare
     * is fixed by declaring it or by not sending it, and a missing value is fixed by
     * delivering it. One word for every rule would say only "no", and the client would have
     * to guess. The [reason] says WHICH rule, and the [detail] says which one in prose.
     *
     * ## Why it is NOT a shape refusal
     *
     * Because a shape is a property of the LINE and the codec owns the shape of a line
     * ([MalformedRequest]). The rules of the parameters are a decision of the service, and
     * the service decides them.
     *
     * ## Why it is NOT the refusal of a request to DESCRIBE
     *
     * Because the two facts are not the same for the client: this refusal says that the
     * values the client sent break a rule, and the client has to fix a value and send it
     * again. A refusal that says that the service cannot DESCRIBE the parameters of a script
     * says that there is nothing to fix: the client has to ask somebody else for the
     * contract. One word for both would make the client read the reason to tell them apart,
     * and the reason is read by a machine far more often than by a person.
     *
     * The refusal of a description is [DescribeParametersRefused].
     */
    data class ParametersRefused(val reason: String, val detail: String) : ServiceRefusal {
        override val kind: String = "parameters-refused"
        override val payload: JsonObject = buildJsonObject {
            put("reason", JsonPrimitive(reason))
            put("detail", JsonPrimitive(detail))
        }
    }

    /**
     * The request to DESCRIBE the parameters of a script was refused.
     *
     * ## What it says
     *
     * That this service does not serve the description, and why: describing the parameters of
     * a script means compiling it, and compiling it means running the author's code inside
     * the service.
     *
     * ## Why it is a word of its own
     *
     * Because the client's two actions are different and neither of them is a retry: the
     * values of a request that break a rule are fixed and sent again, and a description this
     * service cannot produce is asked of somebody else —the compiler, or the contract the
     * client already carries. A word that covered both would leave the client reading the
     * reason to learn which of the two happened, and the reason is a field of the refusal
     * that a machine reads before a person does.
     */
    data class DescribeParametersRefused(val reason: String, val detail: String) : ServiceRefusal {
        override val kind: String = "describe-parameters-refused"
        override val payload: JsonObject = buildJsonObject {
            put("reason", JsonPrimitive(reason))
            put("detail", JsonPrimitive(detail))
        }
    }

    /**
     * A request about a configuration provider session was refused.
     *
     * ## What it says
     *
     * Which rule the request broke —the name is not legal, a key is labelled as a secret, the
     * session is unknown or has lapsed, the answer carries a key nobody asked— in the [reason],
     * and the same fact in prose in the [detail]. The [detail] never carries a value of the
     * configuration.
     *
     * ## Why it is NOT [RunConfigurationRefused]
     *
     * Because the client's action is different: a refused run start fixes the configuration of
     * THAT run, and a refused provider request fixes the provider session. A start that fails
     * because the provider did not answer is still a [RunConfigurationRefused], since the run
     * is what was refused.
     */
    data class ConfigurationProviderRefused(val reason: String, val detail: String) : ServiceRefusal {
        override val kind: String = "configuration-provider-refused"
        override val payload: JsonObject = buildJsonObject {
            put("reason", JsonPrimitive(reason))
            put("detail", JsonPrimitive(detail))
        }
    }

    /**
     * A request about a credential provider session was refused.
     *
     * ## What it says
     *
     * Which rule the request broke —the name is not legal or is taken, the session is unknown or
     * has lapsed, the request was answered already or belongs to another session, the secret is
     * too long or is not Base64— in the [reason], and the same fact in prose in the [detail]. The
     * [detail] never carries a value of the answer.
     *
     * ## Why it is NOT [ConfigurationProviderRefused]
     *
     * Because the client's action is different: a credential provider keeps secrets and a
     * configuration provider does not, and a client that read one refusal as the other would
     * repair the wrong session. A run start that is refused because its credential provider is
     * not there is still a [RunStartRefused], since the run is what was refused.
     */
    data class CredentialProviderRefused(val reason: String, val detail: String) : ServiceRefusal {
        override val kind: String = "credential-provider-refused"
        override val payload: JsonObject = buildJsonObject {
            put("reason", JsonPrimitive(reason))
            put("detail", JsonPrimitive(detail))
        }
    }

    /**
     * The authorization is one this service knows, and its scope does not
     * cover CANCELLING a run.
     *
     * The same contract as [StartNotAuthorized], for the same reason: the
     * token was accepted and what it lacks is the RIGHT, and a client that
     * could not tell the two apart would learn which authorizations exist
     * by presenting tokens until one is accepted.
     */
    data object CancelNotAuthorized : ServiceRefusal {
        override val kind: String = "cancel-not-authorized"
        override val payload: JsonObject = buildJsonObject { }
    }

    /**
     * The run was not cancelled.
     *
     * ## What the reason is
     *
     * A word that says WHICH fact stopped it: the run is not known to the
     * service, it already reached a terminal of its own, another process
     * is still publishing it, or its journal could not be written. The
     * [detail] is the service's own sentence about it.
     *
     * ## Why it is ONE refusal and not six
     *
     * Because the reason is what a client reads to decide what to do next,
     * and six sealed cases would freeze six names into a published artifact
     * for a vocabulary that is still growing. The reason travels inside.
     */
    data class RunCancelRefused(val reason: String, val detail: String) : ServiceRefusal {
        override val kind: String = "run-cancel-refused"
        override val payload: JsonObject = buildJsonObject {
            put("reason", JsonPrimitive(reason))
            put("detail", JsonPrimitive(detail))
        }
    }

    /**
     * The authorization is one this service knows, and its scope does not
     * cover ANSWERING the questions of this run.
     *
     * The same contract as [CancelNotAuthorized]: the token was accepted and
     * what it lacks is the RIGHT. It is the refusal of listing the questions
     * too, because reading them is the right of whoever may answer them.
     */
    data object AnswerNotAuthorized : ServiceRefusal {
        override val kind: String = "answer-not-authorized"
        override val payload: JsonObject = buildJsonObject { }
    }

    /**
     * The question was not answered, or the questions could not be listed.
     *
     * ## What the reason is
     *
     * A word that says WHICH fact stopped it: the service has no way to answer
     * questions, the run is not one it knows, the question is not one the run
     * is waiting on, it was already answered, it expired, or the run reached
     * its terminal. The [detail] is the service's own sentence about it.
     *
     * ## Why it is ONE refusal
     *
     * For the reason of [RunCancelRefused]: the vocabulary of reasons is still
     * growing and it travels inside, so it does not freeze a name per fact
     * into a published artifact.
     */
    data class InputAnswerRefused(val reason: String, val detail: String) : ServiceRefusal {
        override val kind: String = "input-answer-refused"
        override val payload: JsonObject = buildJsonObject {
            put("reason", JsonPrimitive(reason))
            put("detail", JsonPrimitive(detail))
        }
    }
}

/**
 * What the service answers.
 *
 * An answer and a refusal are different types, so a client that reads the
 * answer can never read a refusal as one, and the encoding of each is decided
 * in one place.
 */
sealed interface ServiceResponse {

    /** The schema this build speaks. It travels in every answer. */
    val schema: String

    /** The service answered. */
    data class Answered(override val schema: String, val result: JsonObject) : ServiceResponse

    /** The service refused. */
    data class Refused(override val schema: String, val refusal: ServiceRefusal) : ServiceResponse
}

/**
 * What a line turned out to be.
 *
 * A line is a request or a refusal, and never an exception: a client that
 * sends nonsense gets a refusal it can read, not a stack trace and a closed
 * connection.
 */
sealed interface ServiceDecoding {

    /**
     * The line is a request the service can answer.
     *
     * The [credential] is the authorization the client presented, or null
     * when it presented none. It travels with the request and not INSIDE it:
     * the request says WHAT is asked for, and the credential says WHO is
     * asking, and a request that carried the credential inside would make
     * every operation a different type.
     */
    data class Decoded(val request: ServiceRequest, val credential: String?) : ServiceDecoding

    /**
     * The line is a request for a street, and the street's request is known.
     *
     * The [credential] is the authorization the client presented, or null
     * when it presented none — the same contract as [Decoded], because a
     * street is asked for by a client like any other question.
     */
    data class Subscribed(val request: ServiceSubscriptionRequest, val credential: String?) : ServiceDecoding

    /** The line is not a request this build can answer. */
    data class Refused(val refusal: ServiceRefusal) : ServiceDecoding
}

/**
 * What a line of the wire turned out to be, read by a CLIENT.
 *
 * ## Why the client needs its own reading
 *
 * The service's [ServiceDecoding] reads a line as a REQUEST, because that is
 * what arrives on the service's side. The client reads the other direction:
 * an answer, a refusal, a record of a street, or the end of a street. The two
 * readings are of the same lines and they are not the same function, so they
 * are two types and not one type with a flag.
 *
 * ## Why there is no "anything else"
 *
 * A line that is none of the four is not a line of this protocol. The decoder
 * answers `null` and the client names the fact, because a client that read a
 * foreign line as an answer would report a success that never happened.
 */
sealed interface ServiceLine {

    /** The service answered. */
    data class Answered(val schema: String, val result: JsonObject) : ServiceLine

    /** The service refused. */
    data class Refused(val schema: String, val refusal: ServiceRefusal) : ServiceLine

    /** A record of a street. */
    data class Record(
        val cursor: String,
        val record: JsonObject,
        val hasMore: Boolean,
    ) : ServiceLine

    /** The end of a street. */
    data class End(val reason: ServiceEndReason, val cursor: String) : ServiceLine
}

/**
 * The codec of the service's wire.
 *
 * ## What it owns
 *
 * The two directions of the contract: a line becomes a [ServiceRequest] or a
 * [ServiceRefusal], and a [ServiceResponse] becomes a line. It is a pure
 * function of its input — no socket, no store, no clock — so the contract can
 * be read and tested without a service running.
 *
 * The client's direction lives here too, because the wire has ONE codec: a
 * client that encoded requests by hand would be a second authority about the
 * shape of a request, and the service would answer a line the client did not
 * quite mean.
 *
 * ## What it does not own
 *
 * What the service DOES with a request, and how the line travelled.
 */
object ServiceCodec {

    /**
     * What [line] is.
     *
     * The order of the checks is the contract: first whether the line is JSON
     * at all, then which schema it speaks, then the authorization it carries,
     * and only then what it asks for. A line that is not JSON has no schema,
     * so asking it for one would answer a question the line never asked.
     *
     * ## Why nothing here throws
     *
     * Every field is read as a TEXT or not at all: a client that sends
     * `"schema": {}` is answered with a refusal and not with an exception,
     * because a line is not a request or a refusal, and NEVER an exception.
     */
    fun decode(line: String): ServiceDecoding {
        val parsed = try {
            Json.parseToJsonElement(line)
        } catch (_: Exception) {
            return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)
        }
        val request = parsed as? JsonObject
            ?: return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)

        val schema = request.text("schema")
            ?: return ServiceDecoding.Refused(ServiceRefusal.IncompatibleProtocol)
        if (schema != ServiceProtocol.SCHEMA) {
            return ServiceDecoding.Refused(ServiceRefusal.IncompatibleProtocol)
        }

        // The authorization is read BEFORE the operation, because it belongs
        // to every operation: a line that carries a malformed authorization
        // is a malformed request whatever it asks for.
        val credential = request.text("auth")
        if (credential != null && (credential.isEmpty() || credential.length > ServiceProtocol.MAX_TOKEN_LENGTH)) {
            return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)
        }

        val op = request.text("op")
            ?: return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)

        return when (op) {
            "version" -> ServiceDecoding.Decoded(ServiceRequest.Version(schema), credential)
            "health" -> ServiceDecoding.Decoded(ServiceRequest.Health(schema), credential)
            "run.state" -> {
                val runId = request.runId() ?: return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)
                ServiceDecoding.Decoded(ServiceRequest.RunState(schema, runId), credential)
            }
            "events.follow" -> decodeEventsFollow(request, schema, credential)
            "output.follow" -> decodeOutputFollow(request, schema, credential)
            "run.start" -> decodeStartRun(request, schema, credential)
            "run.cancel" -> decodeCancelRun(request, schema, credential)
            "inputs.list" -> {
                val runId = request.runId() ?: return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)
                ServiceDecoding.Decoded(ServiceRequest.ListInputs(schema, runId), credential)
            }
            "input.answer" -> decodeAnswerInput(request, schema, credential)
            "describe.parameters" -> decodeDescribeParameters(request, schema, credential)
            else -> ProviderWire.decode(op, request, schema, credential)
        }
    }

    /**
     * The line a record travels as.
     *
     * The cursor is the position the client resumes from, and it travels with
     * the record because a record without its position is a fact the client
     * cannot place. [hasMore] says whether MORE of this lane follows this
     * record — the consumer's continuation, which for a subscription that
     * delivers everything it reads is the store's own flag.
     */
    fun encodeRecord(cursor: String, record: JsonObject, hasMore: Boolean): String {
        val document = buildJsonObject {
            put("schema", JsonPrimitive(ServiceProtocol.SCHEMA))
            put("kind", JsonPrimitive("record"))
            put("cursor", JsonPrimitive(cursor))
            put("hasMore", JsonPrimitive(hasMore))
            put("record", record)
        }
        return Json.encodeToString(JsonObject.serializer(), document)
    }

    /**
     * The line the end of a street travels as.
     */
    fun encodeEnd(reason: ServiceEndReason, cursor: String): String {
        val document = buildJsonObject {
            put("schema", JsonPrimitive(ServiceProtocol.SCHEMA))
            put("kind", JsonPrimitive("end"))
            put("reason", JsonPrimitive(reason.word))
            put("cursor", JsonPrimitive(cursor))
        }
        return Json.encodeToString(JsonObject.serializer(), document)
    }

    /**
     * The line [response] travels as.
     *
     * The schema travels in every line, and the `kind` says what the line IS:
     * an answer, a refusal, a record of a street, or the end of a street. The
     * discriminator is a WORD and not a boolean, because a protocol with a
     * boolean and a word is a protocol with two rules, and because a client
     * reads a word without having to know which of two shapes it is reading.
     *
     * The refusal carries its own facts besides its kind: a client that reads
     * the kind knows what to do, and a client that reads the facts knows what
     * it was refused about.
     */
    fun encode(response: ServiceResponse): String {
        val document = when (response) {
            is ServiceResponse.Answered -> buildJsonObject {
                put("schema", JsonPrimitive(response.schema))
                put("kind", JsonPrimitive("answer"))
                put("result", response.result)
            }
            is ServiceResponse.Refused -> buildJsonObject {
                put("schema", JsonPrimitive(response.schema))
                put("kind", JsonPrimitive("refused"))
                put("refusal", refusalDocument(response.refusal))
            }
        }
        return Json.encodeToString(JsonObject.serializer(), document)
    }

    /**
     * The line [request] travels as.
     *
     * The `op` word says what is asked for, and the schema travels with every
     * line because a service that serves another schema has to be able to
     * say so before it refuses.
     *
     * ## Why a request without an authorization exists at all
     *
     * Because the service asks EVERY question for an authorization, and a
     * line without one is answered with `not-authorized`. This overload is
     * the way to write that line on purpose: a test that checks the refusal,
     * and a client that wants to see it.
     */
    fun encode(request: ServiceRequest): String = encode(request, null)

    /**
     * The line [request] travels as, presented with [credential].
     *
     * The authorization travels with the request and not in a second line:
     * one question per connection is the wire's rule, and a handshake before
     * the question would make every client two round trips for one fact.
     */
    fun encode(request: ServiceRequest, credential: String?): String {
        val document = buildJsonObject {
            put("schema", JsonPrimitive(request.schema))
            credential?.let { put("auth", JsonPrimitive(it)) }
            when (request) {
                is ServiceRequest.Version -> put("op", JsonPrimitive("version"))
                is ServiceRequest.Health -> put("op", JsonPrimitive("health"))
                is ServiceRequest.RunState -> {
                    put("op", JsonPrimitive("run.state"))
                    put("runId", JsonPrimitive(request.runId))
                }
                is ServiceRequest.StartRun -> putStartRun(request)
                is ServiceRequest.CancelRun -> {
                    put("op", JsonPrimitive("run.cancel"))
                    put("runId", JsonPrimitive(request.runId))
                    request.reason?.let { put("reason", JsonPrimitive(it)) }
                }
                is ServiceRequest.ListInputs -> {
                    put("op", JsonPrimitive("inputs.list"))
                    put("runId", JsonPrimitive(request.runId))
                }
                is ServiceRequest.AnswerInput -> {
                    put("op", JsonPrimitive("input.answer"))
                    put("runId", JsonPrimitive(request.runId))
                    put("opId", JsonPrimitive(request.opId))
                    put("decision", JsonPrimitive(request.decision.word))
                    request.message?.let { put("message", JsonPrimitive(it)) }
                }
                is ServiceRequest.DescribeParameters -> {
                    put("op", JsonPrimitive("describe.parameters"))
                    put("script", JsonPrimitive(request.script))
                }
                is ServiceRequest.ProviderRegister -> putProviderRegister(request)
                is ServiceRequest.ProviderPoll -> {
                    put("op", JsonPrimitive("config.provider.poll"))
                    put("sessionId", JsonPrimitive(request.sessionId))
                    put("waitMillis", JsonPrimitive(request.waitMillis))
                }
                is ServiceRequest.ProviderAnswer -> putProviderAnswer(request)
                is ServiceRequest.ProviderClose -> {
                    put("op", JsonPrimitive("config.provider.close"))
                    put("sessionId", JsonPrimitive(request.sessionId))
                }
                is ServiceRequest.RunConfigurationQuery -> {
                    put("op", JsonPrimitive("run.configuration"))
                    put("runId", JsonPrimitive(request.runId))
                }
                is ServiceRequest.CredentialRegister -> putRegister(request)
                is ServiceRequest.CredentialPoll -> putPoll(request)
                is ServiceRequest.CredentialAnswer -> putAnswer(request)
                is ServiceRequest.CredentialClose -> putClose(request)
            }
        }
        return Json.encodeToString(JsonObject.serializer(), document)
    }

    /**
     * The line [request] travels as.
     *
     * The cursor travels ENCODED, and only when there is one: a street that
     * was asked for from the beginning names no cursor, and a street that was
     * asked to resume names the position it resumes from.
     */
    fun encode(request: ServiceSubscriptionRequest): String = encode(request, null)

    /**
     * The line [request] travels as, presented with [credential].
     *
     * A street is asked for by a client like any other question, so it is
     * authorized the same way.
     */
    fun encode(request: ServiceSubscriptionRequest, credential: String?): String {
        val document = buildJsonObject {
            put("schema", JsonPrimitive(request.schema))
            credential?.let { put("auth", JsonPrimitive(it)) }
            when (request) {
                is ServiceSubscriptionRequest.EventsFollow -> {
                    put("op", JsonPrimitive("events.follow"))
                    put("runId", JsonPrimitive(request.runId))
                    request.after?.let { put("after", JsonPrimitive(it.encode())) }
                }
                is ServiceSubscriptionRequest.OutputFollow -> {
                    put("op", JsonPrimitive("output.follow"))
                    put("runId", JsonPrimitive(request.runId))
                    put("after", JsonPrimitive(FrameOrdinalToken.encode(request.after)))
                }
            }
        }
        return Json.encodeToString(JsonObject.serializer(), document)
    }

    /**
     * What [line] is, read by a client.
     *
     * `null` when the line is not one of this protocol's four kinds. The
     * client turns that into a named negative: a client that read a foreign
     * line as an answer would report a success that never happened.
     */
    fun decodeLine(line: String): ServiceLine? {
        val parsed = try {
            Json.parseToJsonElement(line)
        } catch (_: Exception) {
            return null
        }
        val document = parsed as? JsonObject ?: return null
        return when (document["kind"]?.jsonPrimitive?.contentOrNull) {
            "answer" -> {
                val schema = document["schema"]?.jsonPrimitive?.contentOrNull ?: return null
                val result = document["result"] as? JsonObject ?: return null
                ServiceLine.Answered(schema, result)
            }
            "refused" -> {
                val schema = document["schema"]?.jsonPrimitive?.contentOrNull ?: return null
                val refusal = (document["refusal"] as? JsonObject)?.let { decodeRefusal(it) } ?: return null
                ServiceLine.Refused(schema, refusal)
            }
            "record" -> {
                val cursor = document["cursor"]?.jsonPrimitive?.contentOrNull ?: return null
                val record = document["record"] as? JsonObject ?: return null
                val hasMore = document["hasMore"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
                ServiceLine.Record(cursor, record, hasMore)
            }
            "end" -> {
                val word = document["reason"]?.jsonPrimitive?.contentOrNull ?: return null
                val reason = ServiceEndReason.entries.firstOrNull { it.word == word } ?: return null
                val cursor = document["cursor"]?.jsonPrimitive?.contentOrNull ?: return null
                ServiceLine.End(reason, cursor)
            }
            else -> null
        }
    }

    /**
     * The document a refusal travels as: its kind and the facts it carries.
     */
    private fun refusalDocument(refusal: ServiceRefusal): JsonObject = buildJsonObject {
        put("kind", JsonPrimitive(refusal.kind))
        refusal.payload.forEach { (name, value) -> put(name, value) }
    }
}

/** The family of the cursor the events street resumes from. */
private const val EVENT_CURSOR_FAMILY: String = "evt-cursor-v1"

/** The family of the cursor the output street resumes from. */
private val FRAME_ORDINAL_FAMILY: String = FrameOrdinalToken.PREFIX

/** The position before the first frame of a run. */
private const val FIRST_FRAME_ORDINAL: Long = -1L

/**
 * The start request this line asks for.
 *
 * ## The script is read BEFORE anything else of the request
 *
 * Because a request without a script is not a request: the script is
 * what the client asks to run, and a line that does not name it names
 * nothing to start.
 *
 * ## Why the names are bounded here and not later
 *
 * Because the bound is about the LINE, and the line is what arrives.
 * A name of a megabyte is refused before it reaches the service's
 * admission, which is the same rule the run id follows.
 *
 * ## Why the workspace and the key may be absent
 *
 * Because both are optional in the CONTRACT: a run without a workspace
 * runs in the product's own scratch, and a request without a key is a
 * request that does not ask to be repeatable. An absent field is not a
 * malformed field; a field of the wrong SHAPE is.
 */


private fun decodeStartRun(request: JsonObject, schema: String, credential: String?): ServiceDecoding {
    val script = request.text("script") ?: return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)
    if (script.isEmpty() || script.length > ServiceProtocol.MAX_SCRIPT_NAME_LENGTH) {
        return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)
    }
    val workspace = request.text("workspace")
    if (workspace != null && (workspace.isEmpty() || workspace.length > ServiceProtocol.MAX_WORKSPACE_NAME_LENGTH)) {
        return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)
    }
    val key = request.text("idempotencyKey")
    if (key != null && (key.isEmpty() || key.length > ServiceProtocol.MAX_IDEMPOTENCY_KEY_LENGTH)) {
        return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)
    }
    val configuration = request.configuration()
        ?: return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)
    val parameters = request.parameters()
        ?: return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)
    val parameterContract = request.parameterContract()
        ?: return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)
    val providerElement = request["configurationProvider"]
    val configurationProvider = if (providerElement == null) {
        null
    } else {
        ProviderWire.reference(providerElement)
            ?: return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)
    }
    val credentialProvider = when (val named = CredentialProviderWire.startProvider(request)) {
        CredentialProviderWire.Named.Absent -> null
        CredentialProviderWire.Named.Malformed -> return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)
        is CredentialProviderWire.Named.Present -> named.name
    }
    return ServiceDecoding.Decoded(
        ServiceRequest.StartRun(
            schema = schema,
            script = script,
            workspace = workspace,
            idempotencyKey = key,
            configuration = configuration,
            parameters = parameters,
            parameterContract = parameterContract,
            configurationProvider = configurationProvider,
            credentialProvider = credentialProvider,
        ),
        credential,
    )
}

/**
 * The description this line asks for.
 *
 * The script is read with the same rule the start reads it with: the name is bounded and it
 * is not blank, because a request about no script is not a request.
 */
private fun decodeDescribeParameters(
    request: JsonObject,
    schema: String,
    credential: String?,
): ServiceDecoding {
    val script = request.text("script") ?: return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)
    if (script.isEmpty() || script.length > ServiceProtocol.MAX_SCRIPT_NAME_LENGTH) {
        return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)
    }
    return ServiceDecoding.Decoded(
        ServiceRequest.DescribeParameters(schema, script),
        credential,
    )
}

/** Writes the `run.start` fields of [request] into this document. */
private fun JsonObjectBuilder.putStartRun(request: ServiceRequest.StartRun) {
    put("op", JsonPrimitive("run.start"))
    put("script", JsonPrimitive(request.script))
    request.workspace?.let { put("workspace", JsonPrimitive(it)) }
    request.idempotencyKey?.let { put("idempotencyKey", JsonPrimitive(it)) }
    if (request.configuration.isNotEmpty()) {
        put(
            "configuration",
            buildJsonObject {
                request.configuration.forEach { (name, value) ->
                    put(name, JsonPrimitive(value))
                }
            },
        )
    }
    if (request.parameters.isNotEmpty()) {
        put(
            "parameters",
            buildJsonObject {
                request.parameters.forEach { (name, value) ->
                    put(name, JsonPrimitive(value))
                }
            },
        )
    }
    request.configurationProvider?.let { reference ->
        put(
            "configurationProvider",
            buildJsonObject {
                put("provider", JsonPrimitive(reference.provider))
                put("keys", JsonArray(reference.keys.map { JsonPrimitive(it) }))
            },
        )
    }
    request.credentialProvider?.let { put("credentialProvider", JsonPrimitive(it)) }
    if (request.parameterContract.isNotEmpty()) {
        put("parameterContract", JsonArray(request.parameterContract.map { spec ->
            buildJsonObject {
                put("name", JsonPrimitive(spec.name))
                put("type", JsonPrimitive(spec.type))
                spec.defaultValue?.let { put("defaultValue", JsonPrimitive(it)) }
                if (spec.choices.isNotEmpty()) {
                    put("choices", JsonArray(spec.choices.map { JsonPrimitive(it) }))
                }
            }
        }))
    }
}

/** Writes the `config.provider.register` fields of [request] into this document. */
private fun JsonObjectBuilder.putProviderRegister(request: ServiceRequest.ProviderRegister) {
    put("op", JsonPrimitive("config.provider.register"))
    put("provider", JsonPrimitive(request.provider))
    put(
        "keys",
        JsonArray(
            request.keys.map { key ->
                buildJsonObject {
                    put("name", JsonPrimitive(key.name))
                    put("type", JsonPrimitive(key.type))
                    put("sensitivity", JsonPrimitive(key.sensitivity))
                }
            },
        ),
    )
}

/** Writes the `config.provider.answer` fields of [request] into this document. */
private fun JsonObjectBuilder.putProviderAnswer(request: ServiceRequest.ProviderAnswer) {
    put("op", JsonPrimitive("config.provider.answer"))
    put("sessionId", JsonPrimitive(request.sessionId))
    put("requestId", JsonPrimitive(request.requestId))
    put("revision", JsonPrimitive(request.revision))
    put(
        "values",
        buildJsonObject {
            request.values.forEach { (name, value) -> put(name, JsonPrimitive(value)) }
        },
    )
}

/**
 * The decoding of the operations of a provider session and of the question about the
 * configuration of a run.
 *
 * They are kept together because they share one rule: only the SHAPE of a line is decided here,
 * and what a legal name, type or label is belongs to the service.
 */
private object ProviderWire {

    /**
     * Decodes the operations that belong to the controller-supplied
     * configuration, or refuses [op] as unknown when it is none of them.
     */
    fun decode(
        op: String,
        request: JsonObject,
        schema: String,
        credential: String?,
    ): ServiceDecoding = when (op) {
        "config.provider.register" -> decodeProviderRegister(request, schema, credential)
        "config.provider.poll" -> decodeProviderPoll(request, schema, credential)
        "config.provider.answer" -> decodeProviderAnswer(request, schema, credential)
        "config.provider.close" -> {
            val sessionId = request.providerField("sessionId")
            if (sessionId == null) {
                ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)
            } else {
                ServiceDecoding.Decoded(ServiceRequest.ProviderClose(schema, sessionId), credential)
            }
        }
        "run.configuration" -> {
            val runId = request.runId()
            if (runId == null) {
                ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)
            } else {
                ServiceDecoding.Decoded(ServiceRequest.RunConfigurationQuery(schema, runId), credential)
            }
        }
        else -> CredentialProviderWire.decode(op, request, schema, credential)
    }

    /**
     * The label [key] holds, or null when it holds none, is empty or is longer than the bound.
     *
     * Used for the names, the ids and the revision of the provider requests: they are keys of the
     * registry and never paths, and what a legal one looks like is the service's decision.
     */
    private fun JsonObject.providerField(key: String): String? {
        val value = text(key) ?: return null
        if (value.isEmpty() || value.length > ServiceProtocol.MAX_PROVIDER_FIELD_LENGTH) return null
        return value
    }

    /**
     * The registration this line asks for.
     *
     * Only the SHAPE is decided here: a provider name, and an array of objects each one with a
     * name, a type and a sensitivity read as texts. Whether the name is legal, whether the types
     * are known and whether a key is labelled as a secret are rules, and the service refuses them
     * by name.
     */
    private fun decodeProviderRegister(
        request: JsonObject,
        schema: String,
        credential: String?,
    ): ServiceDecoding {
        val provider = request.providerField("provider")
            ?: return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)
        val entries = request["keys"] as? JsonArray
            ?: return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)
        val keys = mutableListOf<ServiceProviderKey>()
        for (entry in entries) {
            val declaration = entry as? JsonObject
                ?: return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)
            keys.add(
                ServiceProviderKey(
                    name = declaration.text("name")
                        ?: return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest),
                    type = declaration.text("type")
                        ?: return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest),
                    sensitivity = declaration.text("sensitivity")
                        ?: return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest),
                ),
            )
        }
        return ServiceDecoding.Decoded(ServiceRequest.ProviderRegister(schema, provider, keys), credential)
    }

    /**
     * The poll this line asks for.
     *
     * The wait is a non-negative number of milliseconds no bigger than the protocol's bound; an
     * absent wait is a poll that does not wait. The service caps it again with its own limit.
     */
    private fun decodeProviderPoll(request: JsonObject, schema: String, credential: String?): ServiceDecoding {
        val sessionId = request.providerField("sessionId")
            ?: return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)
        val waitMillis = if (request["waitMillis"] == null) {
            0L
        } else {
            (request["waitMillis"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
                ?: return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)
        }
        if (waitMillis < 0L || waitMillis > ServiceProtocol.MAX_PROVIDER_POLL_WAIT_MILLIS) {
            return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)
        }
        return ServiceDecoding.Decoded(ServiceRequest.ProviderPoll(schema, sessionId, waitMillis), credential)
    }

    /**
     * The answer this line carries.
     *
     * The values have the shape of the configuration's: an OBJECT whose entries are all PRIMITIVES,
     * each read as its text. The type of each value is checked by the service against the type the
     * provider declared.
     */
    private fun decodeProviderAnswer(request: JsonObject, schema: String, credential: String?): ServiceDecoding {
        val sessionId = request.providerField("sessionId")
            ?: return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)
        val requestId = request.providerField("requestId")
            ?: return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)
        val revision = request.providerField("revision")
            ?: return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)
        val entries = request["values"] as? JsonObject
            ?: return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)
        val values = LinkedHashMap<String, String>()
        for ((name, value) in entries) {
            values[name] = (value as? JsonPrimitive)?.contentOrNull
                ?: return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)
        }
        return ServiceDecoding.Decoded(
            ServiceRequest.ProviderAnswer(schema, sessionId, requestId, revision, values),
            credential,
        )
    }

    /**
     * The provider reference [element] carries, or null when it is not one.
     *
     * The shape is an object with the provider's name and an ARRAY of key names. The keys are read
     * as texts; which names are legal and how many there may be are the service's decision.
     */
    fun reference(element: JsonElement): ServiceProviderReference? {
        val entry = element as? JsonObject ?: return null
        val provider = entry.providerField("provider") ?: return null
        val names = entry["keys"] as? JsonArray ?: return null
        val keys = mutableListOf<String>()
        for (name in names) {
            keys.add((name as? JsonPrimitive)?.contentOrNull ?: return null)
        }
        return ServiceProviderReference(provider, keys)
    }
}

/**
 * The parameters this line carries, or null when it carries a shape the protocol does not
 * admit.
 *
 * The shape is the one of the configuration: an OBJECT whose entries are all PRIMITIVES, and
 * a value is read as its text. What the values may BE is not decided here: the rules of the
 * parameters are the service's decision, and the service refuses them by name.
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

/**
 * The declaration this line carries, or null when it carries a shape the protocol does not
 * admit.
 *
 * ## What the shape is
 *
 * An ARRAY of objects, each one a parameter: a name, a type, an optional default and an
 * optional set of choices. Every field is read as a TEXT, and a field that is not a text is
 * a shape the protocol does not admit.
 *
 * ## What is NOT decided here
 *
 * Whether the declaration is one the product can check a value against. That is a RULE, and
 * the rules are the service's decision: it refuses them by name — `parameters-refused` —
 * and not here.
 */
private fun JsonObject.parameterContract(): List<dev.rubentxu.pipeline.v2.domain.ParameterSpec>? {
    val element = this["parameterContract"] ?: return emptyList()
    val entries = element as? JsonArray ?: return null
    val specs = mutableListOf<dev.rubentxu.pipeline.v2.domain.ParameterSpec>()
    for (entry in entries) {
        val spec = entry as? JsonObject ?: return null
        val name = spec.text("name") ?: return null
        val type = spec.text("type") ?: return null
        val defaultValue = spec.text("defaultValue")
        val choicesElement = spec["choices"]
        val choices = if (choicesElement == null) {
            emptyList()
        } else {
            val choiceEntries = choicesElement as? JsonArray ?: return null
            val texts = mutableListOf<String>()
            for (choice in choiceEntries) {
                texts.add((choice as? JsonPrimitive)?.contentOrNull ?: return null)
            }
            texts
        }
        specs.add(
            dev.rubentxu.pipeline.v2.domain.ParameterSpec(
                name = name,
                type = type,
                defaultValue = defaultValue,
                choices = choices,
            ),
        )
    }
    return specs
}

/**
 * The configuration this line carries, or null when it carries a shape the
 * protocol does not admit.
 *
 * ## What the shape is
 *
 * An OBJECT whose entries are all PRIMITIVES. The value of an entry is read
 * as its text, exactly as every other field of the wire is read: a number
 * is the text of that number and a boolean is the text of that boolean. That
 * is the house reading of a primitive and it is not a special case of the
 * configuration.
 *
 * ## What is refused
 *
 * A field that is not an object, and an entry whose value is not a
 * primitive. Both are a property of the LINE, and the codec is the owner of
 * the shape of a line: `malformed-request` is the word for it.
 *
 * ## What the RULES are NOT
 *
 * The count of the entries, the shape of a name, the length of a value and
 * the names that are refused are the service's decision, and the service
 * refuses them by name — `run-configuration-refused` — and not here. The
 * difference matters: a rule is a DECISION, and a decision the service does
 * not take is not taken by the codec either.
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
 * The cancellation this line asks for.
 *
 * ## The run id is read BEFORE the reason
 *
 * Because a request without a run id is not a request: there is nothing
 * to cancel. The reason is optional and is read after, because a request
 * with a run id and no reason is a perfectly good cancellation.
 *
 * ## Why the reason is bounded here
 *
 * Because the bound is about the LINE, and the line is what arrives: a
 * reason of a megabyte is refused before it reaches the run's history,
 * where every reader of the run would have to carry it.
 */
private fun decodeCancelRun(request: JsonObject, schema: String, credential: String?): ServiceDecoding {
    val runId = request.runId() ?: return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)
    val reason = request.text("reason")
    // The bounds are the ones the product imposes on its own annotated
    // reason: a length and a line break. An EMPTY reason is legal, because
    // the product accepts it —a cancel with nothing to say is a cancel with
    // nothing to say, and refusing it would make the wire stricter than the
    // thing it carries.
    if (reason != null &&
        (reason.length > ServiceProtocol.MAX_CANCEL_REASON_LENGTH || '\n' in reason)
    ) {
        return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)
    }
    return ServiceDecoding.Decoded(ServiceRequest.CancelRun(schema, runId, reason), credential)
}

/**
 * The request to answer a question of a run.
 *
 * ## What is refused here
 *
 * A run id that does not name a run, an operation id that is empty or longer
 * than the bound, a decision that is not one of the two words, and a message
 * that is longer than the bound or holds a line break. All of them are
 * refused as a malformed request BEFORE the service looks at any question: a
 * word that is not `proceed` or `abort` is not a decision to be guessed.
 *
 * ## Why the operation id is not checked against an alphabet
 *
 * Because it is never used as a path or as a name of anything: the service
 * looks for it among the questions the run published and finds none when it
 * is not one of them. The bound is what keeps the line small.
 */
private fun decodeAnswerInput(request: JsonObject, schema: String, credential: String?): ServiceDecoding {
    val runId = request.runId() ?: return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)
    val opId = request.text("opId")
    if (opId.isNullOrEmpty() || opId.length > ServiceProtocol.MAX_INPUT_OP_ID_LENGTH) {
        return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)
    }
    val decision = request.text("decision")?.let(ServiceInputDecision::ofWord)
        ?: return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)
    val message = request.text("message")
    if (message != null &&
        (message.length > ServiceProtocol.MAX_INPUT_ANSWER_MESSAGE_LENGTH || '\n' in message)
    ) {
        return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)
    }
    return ServiceDecoding.Decoded(ServiceRequest.AnswerInput(schema, runId, opId, decision, message), credential)
}

/**
 * The text [key] holds, or null when it holds none.
 *
 * A field that is not a string is a field that holds no text: the
 * element is read as a primitive or not at all, so a client cannot make
 * the decoder raise by sending an object where a string belongs.
 */
private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

/**
 * The run this request is about, or null when it names none.
 *
 * A run id has to be a run id: it has to NAME a durable location, in the
 * alphabet the product uses for that, and it has to be short enough to
 * be one. The alphabet is [RunId]'s and not a second copy of it: the
 * Output Plane builds a path out of the run id, and the one authority
 * about which run ids may name a location is the identity itself.
 *
 * A run id that does not qualify is refused HERE, before anything is
 * opened: a request that named a path would otherwise travel until the
 * plane refused it, and the refusal would arrive as a closed connection
 * instead of as a word the client can read.
 */
private fun JsonObject.runId(): String? {
    val runId = text("runId") ?: return null
    if (runId.length > ServiceProtocol.MAX_RUN_ID_LENGTH) return null
    if (!RunId.isNameable(runId)) return null
    return runId
}

/**
 * The events street this line asks for.
 *
 * The cursor is decoded HERE and not in the street, because a cursor that
 * does not belong is a fact about the REQUEST, and the client that sent
 * it has to be told before the street opens.
 */
private fun decodeEventsFollow(request: JsonObject, schema: String, credential: String?): ServiceDecoding {
    val runId = request.runId()
        ?: return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)
    val token = request.text("after") ?: return subscribed(
        ServiceSubscriptionRequest.EventsFollow(schema, runId, null),
        credential,
    )
    val cursor = EventCursor.decode(token)
        ?: return ServiceDecoding.Refused(
            ServiceRefusal.InvalidCursor(token, EVENT_CURSOR_FAMILY),
        )
    if (cursor.runId != runId) {
        // The cursor names a run this street is not. Resuming from it would
        // skip the history of the run that WAS asked for, silently.
        return ServiceDecoding.Refused(
            ServiceRefusal.InvalidCursor(token, EVENT_CURSOR_FAMILY),
        )
    }
    return subscribed(ServiceSubscriptionRequest.EventsFollow(schema, runId, cursor), credential)
}

/**
 * The output street this line asks for.
 */
private fun decodeOutputFollow(request: JsonObject, schema: String, credential: String?): ServiceDecoding {
    val runId = request.runId()
        ?: return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)
    val token = request.text("after")
        ?: return subscribed(
            ServiceSubscriptionRequest.OutputFollow(schema, runId, FIRST_FRAME_ORDINAL),
            credential,
        )
    val ordinal = FrameOrdinalToken.decode(token)
        ?: return ServiceDecoding.Refused(
            ServiceRefusal.InvalidCursor(token, FRAME_ORDINAL_FAMILY),
        )
    return subscribed(ServiceSubscriptionRequest.OutputFollow(schema, runId, ordinal), credential)
}

/**
 * The decoding of a street's request.
 */
private fun subscribed(request: ServiceSubscriptionRequest, credential: String?): ServiceDecoding =
    ServiceDecoding.Subscribed(request, credential)


/**
 * The refusal [document] carries.
 *
 * A total reading: the kinds of [ServiceRefusal] are closed, and a
 * document whose kind is none of them is not a refusal of this protocol.
 * The facts each kind carries are read here and nowhere else, so a
 * refusal cannot lose a fact on the way in.
 *
 * ## Why the vocabulary is read in THREE families
 *
 * Because the refusals are about three different things —the facts of a
 * RUN, the names of a START and the door of the SERVICE— and a single flat
 * reading of them grows with every kind that is added until it stops
 * being readable. The families are read apart and the KIND says which one
 * reads it, so the next refusal is added in the family it belongs to.
 */
private fun decodeRefusal(document: JsonObject): ServiceRefusal? {
    val kind = document["kind"]?.jsonPrimitive?.contentOrNull ?: return null
    return when (kind) {
        "unknown-run", "held-by-another", "inconsistent-lease-state",
        "unknown-operation", "run-not-authorized", "invalid-cursor",
        "cursor-beyond-tail" -> decodeRunRefusal(kind, document)

        "start-not-authorized", "script-not-admitted", "workspace-not-admitted",
        "idempotency-conflict", "too-many-supervised-runs", "run-start-refused",
        "run-configuration-refused", "parameters-refused" ->
            decodeStartRefusal(kind, document)

        "cancel-not-authorized", "run-cancel-refused" ->
            decodeCancelRefusal(kind, document)

        "answer-not-authorized", "input-answer-refused" ->
            decodeInputRefusal(kind, document)

        "configuration-provider-refused", "credential-provider-refused" ->
            decodeProviderRefusal(kind, document)

        else -> decodeServiceRefusal(kind, document)
    }
}

/**
 * The refusal about a PROVIDER session, of configuration or of credentials, or null when [kind]
 * is not one.
 *
 * The two live together because they have the same shape: a reason and a detail, and neither
 * of them ever carries a value. A client that read one as the other would repair the wrong
 * session, which is why they are two words and not one.
 */
private fun decodeProviderRefusal(kind: String, document: JsonObject): ServiceRefusal? {
    return when (kind) {
    "configuration-provider-refused" -> ServiceRefusal.ConfigurationProviderRefused(
        document.str("reason") ?: return null,
        document.str("detail") ?: return null,
    )
    "credential-provider-refused" -> ServiceRefusal.CredentialProviderRefused(
        document.str("reason") ?: return null,
        document.str("detail") ?: return null,
    )
        else -> null
    }
}

/**
 * The refusal about the QUESTIONS of a run, or null when [kind] is not one.
 */
private fun decodeInputRefusal(kind: String, document: JsonObject): ServiceRefusal? {
    return when (kind) {
        "answer-not-authorized" -> ServiceRefusal.AnswerNotAuthorized
        "input-answer-refused" -> ServiceRefusal.InputAnswerRefused(
            document.str("reason") ?: return null,
            document.str("detail") ?: return null,
        )
        else -> null
    }
}

/**
 * The refusal about the CANCELLATION of a run, or null when [kind] is not one.
 */
private fun decodeCancelRefusal(kind: String, document: JsonObject): ServiceRefusal? {
    return when (kind) {
    "cancel-not-authorized" -> ServiceRefusal.CancelNotAuthorized
    "run-cancel-refused" -> ServiceRefusal.RunCancelRefused(
        document.str("reason") ?: return null,
        document.str("detail") ?: return null,
    )
        else -> null
    }
}

/**
 * The refusal about the facts of a RUN, or null when [kind] is not one.
 */
private fun decodeRunRefusal(kind: String, document: JsonObject): ServiceRefusal? {
    return when (kind) {
    "unknown-run" -> ServiceRefusal.UnknownRun(document.str("runId") ?: return null)
    "held-by-another" -> ServiceRefusal.HeldByAnother(
        document.str("heldBy") ?: return null,
        document.long("fencingToken") ?: return null,
    )
    "inconsistent-lease-state" -> ServiceRefusal.Inconsistent(document.str("details") ?: return null)
    "unknown-operation" -> ServiceRefusal.UnknownRunOperation(
        document.str("operationId") ?: return null,
        document.long("attempt")?.toInt() ?: return null,
    )
    "run-not-authorized" -> ServiceRefusal.RunNotAuthorized(document.str("runId") ?: return null)
    "invalid-cursor" -> ServiceRefusal.InvalidCursor(
        document.str("cursor") ?: return null,
        document.str("expected") ?: return null,
    )
    "cursor-beyond-tail" -> ServiceRefusal.CursorBeyondTail(
        document.str("runId") ?: return null,
        document.long("requestedSequence") ?: return null,
        document.long("tailSequence") ?: return null,
    )
        else -> null
    }
}

/**
 * The refusal about the names of a START, or null when [kind] is not one.
 */
private fun decodeStartRefusal(kind: String, document: JsonObject): ServiceRefusal? {
    return when (kind) {
    "start-not-authorized" -> ServiceRefusal.StartNotAuthorized
    "script-not-admitted" -> ServiceRefusal.ScriptNotAdmitted(document.str("script") ?: return null)
    "workspace-not-admitted" -> ServiceRefusal.WorkspaceNotAdmitted(
        document.str("workspace") ?: return null,
    )
    "idempotency-conflict" -> ServiceRefusal.IdempotencyConflict(document.str("key") ?: return null)
    "too-many-supervised-runs" -> ServiceRefusal.TooManySupervisedRuns
    "run-start-refused" -> ServiceRefusal.RunStartRefused(
        document.str("reason") ?: return null,
        document.str("detail") ?: return null,
    )
    "run-configuration-refused" -> ServiceRefusal.RunConfigurationRefused(
        document.str("reason") ?: return null,
        document.str("detail") ?: return null,
    )
    "parameters-refused" -> ServiceRefusal.ParametersRefused(
        document.str("reason") ?: return null,
        document.str("detail") ?: return null,
    )
        else -> null
    }
}

/**
 * The refusal about the door of the SERVICE, or null when [kind] is not one.
 */
private fun decodeServiceRefusal(kind: String, document: JsonObject): ServiceRefusal? {
    return when (kind) {
    "not-configured-control-root" -> ServiceRefusal.NotConfiguredControlRoot
    "not-configured-event-plane" -> ServiceRefusal.NotConfiguredEventPlane
    "not-configured-output-plane" -> ServiceRefusal.NotConfiguredOutputPlane
    "storage-failed" -> ServiceRefusal.StorageFailed(document.str("cause") ?: return null)
    "incompatible-protocol" -> ServiceRefusal.IncompatibleProtocol
    "malformed-request" -> ServiceRefusal.MalformedRequest
    "request-too-large" -> ServiceRefusal.RequestTooLarge
    "too-many-connections" -> ServiceRefusal.TooManyConnections
    "unknown-op" -> ServiceRefusal.UnknownOp
    "output-plane-refused" -> ServiceRefusal.OutputPlaneRefused(
        document.str("reason") ?: return null,
        document.str("detail") ?: return null,
    )
    "not-authorized" -> ServiceRefusal.NotAuthorized
    "too-many-subscriptions" -> ServiceRefusal.TooManySubscriptions
    "describe-parameters-refused" -> ServiceRefusal.DescribeParametersRefused(
        document.str("reason") ?: return null,
        document.str("detail") ?: return null,
    )
    "configuration-provider-refused" -> ServiceRefusal.ConfigurationProviderRefused(
        document.str("reason") ?: return null,
        document.str("detail") ?: return null,
    )
        else -> null
    }
}

/** The string [key] holds, or null when it does not hold a string. */
private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

/** The long [key] holds, or null when it does not hold one. */
private fun JsonObject.long(key: String): Long? =
    this[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
