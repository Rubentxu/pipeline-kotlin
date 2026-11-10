package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.service.ServiceProtocol
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** What a poll of a credential provider session came to. */
internal sealed interface CredentialPollOutcome {
    /**
     * A run asks for the credential [credentialId], of kind [kind]; the provider answers with
     * [requestId]. It names the run so the controller can show who is asking, and it carries no
     * secret.
     */
    data class Asked(
        val requestId: String,
        val execution: String,
        val credentialId: String,
        val kind: String,
    ) : CredentialPollOutcome

    /** Nothing was asked within the wait. */
    data object Idle : CredentialPollOutcome

    /** The poll was refused: the session is not one this owner holds. */
    data class Refused(val reason: String, val detail: String) : CredentialPollOutcome
}

/** What asking a connected provider for a credential came to. */
internal sealed interface CredentialResolution {
    /**
     * The provider handed the credential over.
     *
     * [value] is the secret itself: it is not copied here, it is never rendered and the caller
     * that takes it wipes it as soon as it has used it.
     */
    class Granted(val value: ByteArray) : CredentialResolution {
        override fun equals(other: Any?): Boolean = other is Granted && value.contentEquals(other.value)

        override fun hashCode(): Int = value.contentHashCode()

        override fun toString(): String = "Granted(value=<redacted>)"
    }

    /** The credential was not handed over, for a named reason; no value was invented and nobody else was asked. */
    data class Refused(val reason: String, val detail: String) : CredentialResolution
}

/** The door a run opens to ask a connected provider for one credential. */
internal interface CredentialAsker {
    /**
     * Asks the provider [provider] that [owner] registered for the credential [credentialId] of
     * kind [kind], on behalf of the run [execution].
     *
     * Blocks the caller until the provider answers, the session is gone or the deadline of the
     * limits passes. It never throws and it never falls back to another provider.
     */
    fun ask(owner: String, provider: String, execution: String, credentialId: String, kind: String): CredentialResolution

    /** Fails every ask of the run [execution] that [owner] started, because the run is over. */
    fun release(owner: String, execution: String)
}

/**
 * The bounds of the credential provider sessions.
 *
 * The lease must outlast the longest poll, as it does for the configuration providers.
 *
 * The ask deadline is shorter than the deadline the run itself waits for the broker: the
 * service must decide and say so before the run gives up, or the run reports a timeout the
 * service never decided.
 *
 * `maxSessions` is 4 for the same reason the configuration providers keep 4: every session may
 * keep one poll waiting and a waiting poll holds a thread of the pool that answers every other
 * question. With the configuration sessions that is eight of sixteen at most.
 */
internal data class CredentialProviderLimits(
    val leaseMillis: Long = 15_000L,
    val maxPollMillis: Long = 5_000L,
    val askDeadlineMillis: Long = 8_000L,
    val maxSessions: Int = 4,
    val maxPendingPerSession: Int = 4,
) {
    init {
        require(maxPollMillis > 0L) { "the longest poll must be positive" }
        require(leaseMillis > maxPollMillis) { "the lease must outlast the longest poll" }
        require(askDeadlineMillis > 0L) { "the ask deadline must be positive" }
        require(maxSessions > 0) { "at least one session must be allowed" }
        require(maxPendingPerSession > 0) { "at least one pending ask must be allowed per session" }
    }
}

/**
 * The credential provider sessions of the service: how a controller connected to it hands a run
 * the credential it asks for while the secret stays with the controller until that moment.
 *
 * ## Why it is a poll and not a held connection
 *
 * The wire carries one request per connection, so a provider holds none. It registers a session,
 * polls it for requests and answers each one with a separate call. The session is a lease the
 * provider renews by being seen; a provider that vanishes lapses and its pending asks fail.
 *
 * ## The rules that make it safe
 *
 *  - A session belongs to the credential that registered it, and so does its name: another
 *    credential has a name space of its own and can neither answer nor be asked.
 *  - An ask is queued on the ONE session it asked. If that session closes or lapses the ask
 *    fails as gone; a later session of the same name is never handed the request.
 *  - A request is answered once, by the session it was handed to, and an answer that arrives
 *    after the deadline is refused: the run was already told that the provider did not answer.
 *  - A grant carries bytes the registry copies and bounds. A grant that breaks a rule is
 *    refused to the provider AND fails the ask: a provider gets no second try.
 *  - An ask is released when its run ends, whatever the reason.
 *
 * Nothing here logs, renders or stores a secret: the value lives in a pending ask until the
 * waiting thread takes it, and the registry never writes it anywhere.
 *
 * Time comes from the injected [clock]; waiting threads wake in short slices and compare it.
 */
internal class CredentialProviderSessions(
    private val clock: () -> Long = System::currentTimeMillis,
    private val limits: CredentialProviderLimits = CredentialProviderLimits(),
) : CredentialAsker {

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val sessionsById = LinkedHashMap<String, Session>()
    private val pendingById = LinkedHashMap<String, Pending>()
    private var polling = 0

    private class Session(val id: String, val owner: String, val name: String, var lastSeen: Long) {
        val queued = ArrayDeque<Pending>()

        /** Whether a poll of this session is inside its wait. */
        var polling = false
    }

    private class Pending(
        val id: String,
        val session: Session,
        val execution: String,
        val credentialId: String,
        val kind: String,
        val deadline: Long,
    ) {
        var asked = false
        var outcome: CredentialResolution? = null
    }

    /** Registers a session of [provider] for [owner]. */
    fun register(owner: String, provider: String): ProviderRegistrationOutcome {
        if (!isLegalProviderName(provider)) {
            return ProviderRegistrationOutcome.Refused(
                "provider-name-not-legal",
                "a provider name matches $PROVIDER_NAME_SHAPE",
            )
        }
        lock.withLock {
            sweep()
            if (liveSession(owner, provider) != null) {
                return ProviderRegistrationOutcome.Refused(
                    "provider-name-taken",
                    "a live session of the provider '$provider' already exists for this credential",
                )
            }
            if (sessionsById.size >= limits.maxSessions) {
                return ProviderRegistrationOutcome.Refused(
                    "too-many-provider-sessions",
                    "the service keeps at most ${limits.maxSessions} credential provider sessions",
                )
            }
            val session = Session(UUID.randomUUID().toString(), owner, provider, clock())
            sessionsById[session.id] = session
            return ProviderRegistrationOutcome.Registered(session.id, limits.leaseMillis)
        }
    }

    /**
     * Waits up to [waitMillis], capped by the limits, for an ask to reach this session. Being
     * inside a poll renews the lease.
     *
     * A session has at most ONE poll inside its wait: a second one is refused at once and the
     * first goes on waiting, so the polls cannot occupy the pool that answers every other question.
     */
    fun poll(owner: String, sessionId: String, waitMillis: Long): CredentialPollOutcome {
        val wait = waitMillis.coerceIn(0L, limits.maxPollMillis)
        lock.withLock {
            sweep()
            val session = ownedSession(owner, sessionId) ?: return unknownSessionForPoll()
            if (session.polling) {
                return CredentialPollOutcome.Refused(
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
                        return CredentialPollOutcome.Asked(next.id, next.execution, next.credentialId, next.kind)
                    }
                    if (clock() - start >= wait || !awaitChange()) return CredentialPollOutcome.Idle
                }
            } finally {
                session.polling = false
                polling--
            }
        }
    }

    /**
     * Answers the request [requestId]: [granted] with the secret in [value], or not granted.
     *
     * The registry copies [value]; the caller keeps its own buffer and wipes it. An answer that
     * breaks a rule is refused to the provider AND fails the ask. A refusal never echoes a value.
     */
    fun answer(owner: String, sessionId: String, requestId: String, granted: Boolean, value: ByteArray?): ProviderSessionOutcome {
        lock.withLock {
            sweep()
            val session = ownedSession(owner, sessionId) ?: return unknownSessionForAnswer()
            session.lastSeen = clock()
            val pending = pendingById[requestId]
            if (pending == null || pending.session !== session || !pending.asked) {
                return ProviderSessionOutcome.Refused("request-unknown", "no request of this session waits for that answer")
            }
            if (!granted) {
                complete(pending, CredentialResolution.Refused("provider-denied", "the provider did not hand the credential out"))
                return ProviderSessionOutcome.Accepted
            }
            val breach = valueRefusal(value)
            if (breach != null) {
                complete(
                    pending,
                    CredentialResolution.Refused("provider-answer-refused", "the provider answered with a refusal: ${breach.first}"),
                )
                return ProviderSessionOutcome.Refused(breach.first, breach.second)
            }
            complete(pending, CredentialResolution.Granted(checkNotNull(value).copyOf()))
            return ProviderSessionOutcome.Accepted
        }
    }

    /** Closes the session; every ask waiting on it fails as gone. */
    fun close(owner: String, sessionId: String): ProviderSessionOutcome {
        lock.withLock {
            sweep()
            val session = ownedSession(owner, sessionId)
                ?: return ProviderSessionOutcome.Refused("provider-session-unknown", "no such session for this credential")
            drop(session)
            return ProviderSessionOutcome.Accepted
        }
    }

    override fun ask(owner: String, provider: String, execution: String, credentialId: String, kind: String): CredentialResolution {
        lock.withLock {
            sweep()
            val session = liveSession(owner, provider)
                ?: return CredentialResolution.Refused(
                    "provider-not-registered",
                    "no live session of the provider '$provider' exists for this credential",
                )
            if (pendingById.values.count { it.session === session } >= limits.maxPendingPerSession) {
                return CredentialResolution.Refused(
                    "provider-busy",
                    "the provider '$provider' already holds ${limits.maxPendingPerSession} asks",
                )
            }
            val pending = Pending(UUID.randomUUID().toString(), session, execution, credentialId, kind, clock() + limits.askDeadlineMillis)
            pendingById[pending.id] = pending
            session.queued.addLast(pending)
            changed.signalAll()
            return await(pending)
        }
    }

    override fun release(owner: String, execution: String) {
        lock.withLock {
            for (pending in pendingById.values.filter { it.session.owner == owner && it.execution == execution }) {
                complete(pending, CredentialResolution.Refused("run-ended", "the run that asked for the credential has ended"))
            }
        }
    }

    /** Whether [owner] holds a live session of [provider]. */
    fun hasSession(owner: String, provider: String): Boolean = lock.withLock {
        sweep()
        liveSession(owner, provider) != null
    }

    /** How many sessions are alive right now. */
    internal fun liveSessions(): Int = lock.withLock {
        sweep()
        sessionsById.size
    }

    /** How many asks wait for an answer right now. */
    internal fun pendingAsks(): Int = lock.withLock { pendingById.size }

    /** How many polls are inside their wait right now. */
    internal fun waitingPolls(): Int = lock.withLock { polling }

    /** The requests queued on [sessionId] that no poll has handed out yet. */
    internal fun queuedRequestIds(sessionId: String): List<String> = lock.withLock {
        sessionsById[sessionId]?.queued?.map { it.id }.orEmpty()
    }

    // ------------------------------------------------------------------ waiting

    private fun await(pending: Pending): CredentialResolution {
        while (pending.outcome == null) {
            sweep()
            if (pending.outcome != null) break
            if (!awaitChange()) {
                complete(pending, CredentialResolution.Refused("ask-interrupted", "the wait for the provider was interrupted"))
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

    private fun complete(pending: Pending, outcome: CredentialResolution) {
        if (pending.outcome != null) return
        pending.outcome = outcome
        pendingById.remove(pending.id)
        pending.session.queued.remove(pending)
        changed.signalAll()
    }

    private fun drop(session: Session) {
        sessionsById.remove(session.id)
        for (pending in pendingById.values.filter { it.session === session }) {
            complete(pending, CredentialResolution.Refused("provider-gone", "the provider '${session.name}' is no longer connected"))
        }
        changed.signalAll()
    }

    /**
     * Drops the sessions whose lease lapsed, then fails the asks whose deadline passed.
     *
     * The order is the information the run gets: a session that is gone says so, and only an
     * ask on a session that is still alive is a timeout.
     */
    private fun sweep() {
        val now = clock()
        for (session in sessionsById.values.filter { now - it.lastSeen > limits.leaseMillis }) {
            drop(session)
        }
        for (pending in pendingById.values.filter { now >= it.deadline }) {
            complete(
                pending,
                CredentialResolution.Refused("provider-timeout", "the provider '${pending.session.name}' did not answer within the deadline"),
            )
        }
    }

    private fun unknownSessionForPoll() =
        CredentialPollOutcome.Refused("provider-session-unknown", "no such session for this credential")

    private fun unknownSessionForAnswer() =
        ProviderSessionOutcome.Refused("provider-session-unknown", "no such session for this credential")

    /** The rule a granted value breaks, as a reason and a detail; null when it breaks none. Never echoes the value. */
    private fun valueRefusal(value: ByteArray?): Pair<String, String>? =
        if (value == null || value.isEmpty() || value.size > MAX_VALUE_BYTES) {
            "answer-value-not-legal" to "a granted credential carries 1 to $MAX_VALUE_BYTES bytes"
        } else {
            null
        }

    internal companion object {
        /**
         * The largest secret a provider may hand over.
         *
         * Half of what the broker wire carries, so that the Base64 text of a secret, a third
         * larger, fits in a service line with the rest of the request.
         */
        const val MAX_VALUE_BYTES = ServiceProtocol.MAX_CREDENTIAL_VALUE_BYTES

        private const val SLICE_MILLIS = 25L
    }
}
