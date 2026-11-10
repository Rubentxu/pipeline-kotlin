package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.runtime.RunConfiguration
import dev.rubentxu.pipeline.v2.runtime.RunConfigurationOutcome
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The value types a configuration provider may declare for a key.
 *
 * The wire carries every value as text; the type is the shape the text must have. The
 * words are the vocabulary the provider writes when it declares a key.
 */
internal enum class ProviderValueType(val word: String) {
    /** Any text. */
    Text("string"),

    /** The canonical decimal text of a 64-bit integer: no sign but `-`, no leading zeros. */
    Whole("integer"),

    /** The word `true` or the word `false`, in lower case. */
    Flag("boolean"),
    ;

    /** Whether [text] has the shape of this type. */
    fun accepts(text: String): Boolean = when (this) {
        Text -> true
        Whole -> WHOLE_SHAPE.matches(text) && text.toLongOrNull() != null
        Flag -> text == "true" || text == "false"
    }

    companion object {
        private val WHOLE_SHAPE = Regex("0|-?[1-9][0-9]*")

        /** The type spelled [word], or null when the vocabulary has none. */
        fun ofWord(word: String): ProviderValueType? = entries.firstOrNull { it.word == word }
    }
}

/** A key as the provider declares it when it registers. */
internal data class ProviderKeyDeclaration(val name: String, val type: String, val label: String)

/** What registering a provider session came to. */
internal sealed interface ProviderRegistrationOutcome {
    /** The session exists; the provider must renew it within [leaseMillis]. */
    data class Registered(val sessionId: String, val leaseMillis: Long) : ProviderRegistrationOutcome

    /** The registration broke a rule; nothing was registered. */
    data class Refused(val reason: String, val detail: String) : ProviderRegistrationOutcome
}

/** What a poll of a provider session came to. */
internal sealed interface ProviderPollOutcome {
    /** A run asks for [keys]; the provider answers with [requestId]. */
    data class Request(val requestId: String, val keys: List<String>) : ProviderPollOutcome

    /** Nothing was asked within the wait. */
    data object Idle : ProviderPollOutcome

    /** The poll was refused: the session is not one this owner holds. */
    data class Refused(val reason: String, val detail: String) : ProviderPollOutcome
}

/** What an answer or a close of a provider session came to. */
internal sealed interface ProviderSessionOutcome {
    /** The answer was taken, or the session was closed. */
    data object Accepted : ProviderSessionOutcome

    /** The answer or the close was refused. */
    data class Refused(val reason: String, val detail: String) : ProviderSessionOutcome
}

/**
 * Where the values of a resolution came from.
 *
 * It carries no value: only the provider that answered, the revision it stated, the type of
 * every key and a digest of the values that makes the answer comparable without storing it.
 */
internal data class ProviderProvenance(
    val provider: String,
    val revision: String,
    val digest: String,
    val types: Map<String, String>,
)

/** What resolving keys against a provider came to. */
internal sealed interface ProviderResolution {
    /** The provider answered every asked key with a value of the declared type. */
    data class Resolved(val values: Map<String, String>, val provenance: ProviderProvenance) : ProviderResolution

    /** The resolution failed with a named reason; no value was invented and no other provider asked. */
    data class Refused(val reason: String, val detail: String) : ProviderResolution
}

/** The door the start of a run opens to ask a provider for configuration values. */
internal interface ConfigurationResolver {
    /**
     * Resolves [keys] against the provider [provider] that [owner] registered.
     *
     * Blocks the caller until the provider answers, the session is gone or the deadline of the
     * limits passes. It never throws and it never falls back to another provider.
     */
    fun resolve(owner: String, provider: String, keys: List<String>): ProviderResolution
}

/**
 * The bounds of the provider sessions.
 *
 * The lease must outlast the longest poll: a provider that is inside a poll is alive, and a
 * lease shorter than the poll would lapse a session that is doing what it was asked to do.
 *
 * `maxSessions` is 4 and not the size of the question pool because every session may keep one
 * poll waiting, and a waiting poll holds a thread of that pool: with the pool's 16 threads, 16
 * sessions would let polls alone occupy it and starve `run.state`, `health` and the rest. Four
 * sessions leave the other twelve threads to everything else, and a controller needs one.
 */
internal data class ConfigurationProviderLimits(
    val leaseMillis: Long = 15_000L,
    val maxPollMillis: Long = 5_000L,
    val resolveDeadlineMillis: Long = 10_000L,
    val maxSessions: Int = 4,
) {
    init {
        require(maxPollMillis > 0L) { "the longest poll must be positive" }
        require(leaseMillis > maxPollMillis) { "the lease must outlast the longest poll" }
        require(resolveDeadlineMillis > 0L) { "the resolution deadline must be positive" }
        require(maxSessions > 0) { "at least one session must be allowed" }
    }
}

/**
 * The provider sessions of the service: how a controller connected to it answers the
 * configuration keys a run asks for.
 *
 * ## Why it is a poll and not a held connection
 *
 * The wire carries one request per connection. A provider therefore holds no connection: it
 * registers a session, polls it for requests and answers each one with a separate call. The
 * session is a lease the provider renews by being seen; a provider that vanishes lapses and
 * its pending resolutions fail.
 *
 * ## The rules that make it safe
 *
 *  - A session belongs to the credential that registered it, and so does its name: another
 *    credential has a name space of its own and can neither answer nor be asked.
 *  - A key declared with a secret label is refused, and so is a label the product does not
 *    know: only an explicit `non-secret` label is configuration.
 *  - A resolution is queued on the ONE session it asked. If that session closes or lapses the
 *    resolution fails; a later session of the same name is never handed the request.
 *  - A request is answered once, by the session it was handed to, with exactly the keys it
 *    asked, each with the shape it was declared with.
 *
 * Time comes from the injected [clock]; waiting threads wake in short slices and compare it.
 */
internal class ConfigurationProviderSessions(
    private val clock: () -> Long = System::currentTimeMillis,
    private val limits: ConfigurationProviderLimits = ConfigurationProviderLimits(),
) : ConfigurationResolver {

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val sessionsById = LinkedHashMap<String, Session>()
    private val pendingById = LinkedHashMap<String, Pending>()
    private var polling = 0

    private class Session(
        val id: String,
        val owner: String,
        val name: String,
        val declared: Map<String, ProviderValueType>,
        var lastSeen: Long,
    ) {
        val queued = ArrayDeque<Pending>()

        /** Whether a poll of this session is inside its wait. */
        var polling = false
    }

    private class Pending(
        val id: String,
        val session: Session,
        val keys: List<String>,
        val deadline: Long,
    ) {
        var asked = false
        var outcome: ProviderResolution? = null
    }

    /**
     * Registers a session of [provider] for [owner] that declares [keys].
     *
     * The declaration is checked whole before anything is stored, so a refusal leaves no
     * session behind.
     */
    fun register(owner: String, provider: String, keys: List<ProviderKeyDeclaration>): ProviderRegistrationOutcome {
        val refusal = declarationRefusal(provider, keys)
        if (refusal != null) return ProviderRegistrationOutcome.Refused(refusal.first, refusal.second)
        val declared = keys.associate { it.name to checkNotNull(ProviderValueType.ofWord(it.type)) }
        lock.withLock {
            sweepLapsed()
            if (liveSession(owner, provider) != null) {
                return ProviderRegistrationOutcome.Refused(
                    "provider-name-taken",
                    "a live session of the provider '$provider' already exists for this credential",
                )
            }
            if (sessionsById.size >= limits.maxSessions) {
                return ProviderRegistrationOutcome.Refused(
                    "too-many-provider-sessions",
                    "the service keeps at most ${limits.maxSessions} provider sessions",
                )
            }
            val session = Session(UUID.randomUUID().toString(), owner, provider, declared, clock())
            sessionsById[session.id] = session
            return ProviderRegistrationOutcome.Registered(session.id, limits.leaseMillis)
        }
    }

    /**
     * Waits up to [waitMillis], capped by the limits, for a resolution to ask this session
     * something. Being inside a poll renews the lease.
     *
     * A session has at most ONE poll inside its wait: a second one is refused at once and the
     * first goes on waiting. A poll holds a thread of the service for as long as it waits, so
     * the number of waiting polls is bounded by the number of sessions, which the limits bound,
     * and a provider cannot occupy the pool that answers every other question.
     */
    fun poll(owner: String, sessionId: String, waitMillis: Long): ProviderPollOutcome {
        val wait = waitMillis.coerceIn(0L, limits.maxPollMillis)
        lock.withLock {
            sweepLapsed()
            val session = ownedSession(owner, sessionId) ?: return unknownSessionForPoll()
            if (session.polling) {
                return ProviderPollOutcome.Refused(
                    "poll-already-waiting",
                    "a poll of this session is already waiting; a session has one poll at a time",
                )
            }
            val start = clock()
            session.polling = true
            polling++
            try {
                while (true) {
                    if (sessionsById[session.id] !== session) return unknownSessionForPoll()
                    session.lastSeen = clock()
                    val next = session.queued.removeFirstOrNull()
                    if (next != null) {
                        next.asked = true
                        return ProviderPollOutcome.Request(next.id, next.keys)
                    }
                    if (clock() - start >= wait || !awaitChange()) return ProviderPollOutcome.Idle
                }
            } finally {
                session.polling = false
                polling--
            }
        }
    }

    /**
     * Answers the request [requestId] with [revision] and [values].
     *
     * An answer that breaks a rule is refused to the provider AND fails the resolution that
     * asked: a provider is not given a second try with a different value.
     */
    fun answer(
        owner: String,
        sessionId: String,
        requestId: String,
        revision: String,
        values: Map<String, String>,
    ): ProviderSessionOutcome {
        lock.withLock {
            sweepLapsed()
            val session = ownedSession(owner, sessionId) ?: return unknownSessionForAnswer()
            session.lastSeen = clock()
            val pending = pendingById[requestId]
            if (pending == null || pending.session !== session || !pending.asked) {
                return ProviderSessionOutcome.Refused("request-unknown", "no request of this session waits for that answer")
            }
            val breach = answerRefusal(pending, revision, values)
            if (breach != null) {
                complete(
                    pending,
                    ProviderResolution.Refused("provider-answer-refused", "the provider answered with a refusal: ${breach.first}"),
                )
                return ProviderSessionOutcome.Refused(breach.first, breach.second)
            }
            val answered = pending.keys.associateWith { values.getValue(it) }
            val provenance = ProviderProvenance(
                provider = session.name,
                revision = revision,
                digest = digestOf(answered),
                types = pending.keys.associateWith { session.declared.getValue(it).word },
            )
            complete(pending, ProviderResolution.Resolved(answered, provenance))
            return ProviderSessionOutcome.Accepted
        }
    }

    /** Closes the session; every resolution waiting on it fails as gone. */
    fun close(owner: String, sessionId: String): ProviderSessionOutcome {
        lock.withLock {
            sweepLapsed()
            val session = ownedSession(owner, sessionId)
                ?: return ProviderSessionOutcome.Refused("provider-session-unknown", "no such session for this credential")
            drop(session)
            return ProviderSessionOutcome.Accepted
        }
    }

    override fun resolve(owner: String, provider: String, keys: List<String>): ProviderResolution {
        val asked = keys.distinct()
        if (asked.isEmpty()) return ProviderResolution.Refused("no-keys-asked", "a resolution must ask for at least one key")
        lock.withLock {
            sweepLapsed()
            val session = liveSession(owner, provider)
                ?: return ProviderResolution.Refused(
                    "provider-not-registered",
                    "no live session of the provider '$provider' exists for this credential",
                )
            val undeclared = asked.firstOrNull { it !in session.declared }
            if (undeclared != null) {
                return ProviderResolution.Refused(
                    "key-not-declared",
                    "the provider '$provider' did not declare the key '$undeclared'",
                )
            }
            val pending = Pending(UUID.randomUUID().toString(), session, asked, clock() + limits.resolveDeadlineMillis)
            pendingById[pending.id] = pending
            session.queued.addLast(pending)
            changed.signalAll()
            return await(pending, provider)
        }
    }

    /** How many sessions are alive right now. */
    internal fun liveSessions(): Int = lock.withLock {
        sweepLapsed()
        sessionsById.size
    }

    /** How many resolutions wait for an answer right now. */
    internal fun pendingResolutions(): Int = lock.withLock { pendingById.size }

    /** How many polls are inside their wait right now. */
    internal fun waitingPolls(): Int = lock.withLock { polling }

    // ------------------------------------------------------------------ waiting

    private fun await(pending: Pending, provider: String): ProviderResolution {
        while (pending.outcome == null) {
            sweepLapsed()
            if (pending.outcome != null) break
            if (clock() >= pending.deadline) {
                complete(
                    pending,
                    ProviderResolution.Refused("provider-timeout", "the provider '$provider' did not answer within the deadline"),
                )
            } else if (!awaitChange()) {
                complete(pending, ProviderResolution.Refused("resolution-interrupted", "the wait for the provider was interrupted"))
            }
        }
        return checkNotNull(pending.outcome)
    }

    /** Waits one slice for a change; false when the thread was interrupted. */
    private fun awaitChange(): Boolean = try {
        changed.await(SLICE_MILLIS, TimeUnit.MILLISECONDS)
        true
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    // ------------------------------------------------------------------ state

    private fun liveSession(owner: String, provider: String): Session? =
        sessionsById.values.firstOrNull { it.owner == owner && it.name == provider }

    private fun ownedSession(owner: String, sessionId: String): Session? =
        sessionsById[sessionId]?.takeIf { it.owner == owner }

    private fun complete(pending: Pending, outcome: ProviderResolution) {
        if (pending.outcome != null) return
        pending.outcome = outcome
        pendingById.remove(pending.id)
        pending.session.queued.remove(pending)
        changed.signalAll()
    }

    private fun drop(session: Session) {
        sessionsById.remove(session.id)
        for (pending in pendingById.values.filter { it.session === session }) {
            complete(
                pending,
                ProviderResolution.Refused("provider-gone", "the provider '${session.name}' is no longer connected"),
            )
        }
        changed.signalAll()
    }

    private fun sweepLapsed() {
        val now = clock()
        for (session in sessionsById.values.filter { now - it.lastSeen > limits.leaseMillis }) {
            drop(session)
        }
    }

    private fun unknownSessionForPoll() =
        ProviderPollOutcome.Refused("provider-session-unknown", "no such session for this credential")

    private fun unknownSessionForAnswer() =
        ProviderSessionOutcome.Refused("provider-session-unknown", "no such session for this credential")

    // ------------------------------------------------------------------ rules

    /** The first rule the declaration breaks, as a reason and a detail; null when it breaks none. */
    private fun declarationRefusal(provider: String, keys: List<ProviderKeyDeclaration>): Pair<String, String>? {
        if (!PROVIDER_NAME.matches(provider)) {
            return "provider-name-not-legal" to "a provider name matches [A-Za-z][A-Za-z0-9_-]{0,63}"
        }
        if (keys.isEmpty()) return "provider-has-no-keys" to "a provider declares at least one key"
        if (keys.size > RunConfiguration.MAX_ENTRIES) {
            return "provider-has-too-many-keys" to "a provider declares at most ${RunConfiguration.MAX_ENTRIES} keys"
        }
        val seen = HashSet<String>()
        for (key in keys) {
            val refusal = keyRefusal(key)
            if (refusal != null) return refusal
            if (!seen.add(key.name)) return "provider-key-duplicated" to "the key '${key.name}' is declared twice"
            val named = RunConfiguration.of(mapOf(key.name to ""))
            if (named is RunConfigurationOutcome.Refused) {
                return named.refusal.reason to "key '${key.name}': ${named.refusal.detail}"
            }
        }
        return null
    }

    private fun keyRefusal(key: ProviderKeyDeclaration): Pair<String, String>? = when {
        key.label == NON_SECRET -> typeRefusal(key)
        key.label.lowercase() in SECRET_LABELS ->
            "a-secret-is-not-configuration" to "the key '${key.name}' is labelled as a secret, and a secret is not configuration"
        else -> "sensitivity-label-unknown" to "the key '${key.name}' carries a label other than '$NON_SECRET'"
    }

    private fun typeRefusal(key: ProviderKeyDeclaration): Pair<String, String>? =
        if (ProviderValueType.ofWord(key.type) == null) {
            "provider-key-type-unknown" to "the key '${key.name}' declares a type other than string, integer or boolean"
        } else {
            null
        }

    /** The first rule an answer breaks, as a reason and a detail; null when it breaks none. Never echoes a value. */
    private fun answerRefusal(pending: Pending, revision: String, values: Map<String, String>): Pair<String, String>? {
        val refusal = revisionRefusal(revision) ?: keySetRefusal(pending, values) ?: typeShapeRefusal(pending, values)
        if (refusal != null) return refusal
        val rules = RunConfiguration.of(values)
        return if (rules is RunConfigurationOutcome.Refused) rules.refusal.reason to rules.refusal.detail else null
    }

    private fun revisionRefusal(revision: String): Pair<String, String>? =
        if (revision.isEmpty() || revision.length > MAX_REVISION_LENGTH || revision.any { it.isISOControl() }) {
            "revision-not-legal" to "a revision is 1 to $MAX_REVISION_LENGTH characters with no control character"
        } else {
            null
        }

    private fun keySetRefusal(pending: Pending, values: Map<String, String>): Pair<String, String>? {
        val unexpected = values.keys.firstOrNull { it !in pending.keys }
        if (unexpected != null) return "answer-key-unexpected" to "the request did not ask for the key '$unexpected'"
        val missing = pending.keys.firstOrNull { it !in values }
        if (missing != null) return "answer-key-missing" to "the answer lacks the asked key '$missing'"
        return null
    }

    private fun typeShapeRefusal(pending: Pending, values: Map<String, String>): Pair<String, String>? {
        for (key in pending.keys) {
            val type = pending.session.declared.getValue(key)
            if (!type.accepts(values.getValue(key))) {
                return "answer-value-wrong-type" to "the value of '$key' is not a ${type.word}"
            }
        }
        return null
    }

    private fun digestOf(values: Map<String, String>): String = configurationDigest(values)

    private companion object {
        const val SLICE_MILLIS = 25L
        const val MAX_REVISION_LENGTH = 64
        const val NON_SECRET = "non-secret"
        val SECRET_LABELS = setOf("secret", "sensitive", "credential", "password", "token")
        val PROVIDER_NAME = Regex("[A-Za-z][A-Za-z0-9_-]{0,63}")
    }
}

/**
 * The digest of a set of configuration [values]: SHA-256, in lower-case hexadecimal, of their
 * canonical text.
 *
 * It is the one comparison two parties can make about a set of values without either of them
 * showing the values: the provider answer, the snapshot a run reads and the description of a run
 * all carry it, and equal digests mean equal values.
 */
internal fun configurationDigest(values: Map<String, String>): String =
    HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256")
            .digest(RunConfiguration.canonicalText(values).toByteArray(Charsets.UTF_8)),
    )
