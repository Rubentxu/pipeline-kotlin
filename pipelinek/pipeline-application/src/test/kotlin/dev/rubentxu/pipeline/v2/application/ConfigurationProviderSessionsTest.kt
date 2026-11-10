package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.runtime.RunConfiguration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The provider sessions: how a controller that is connected to the service answers the
 * configuration keys a run asks for.
 *
 * ## The guarantee under trial
 *
 * A key reaches a run only from the session that declared it, only if it was declared as
 * something other than a secret, only with the shape it was declared with, and only from the
 * ONE session the resolution asked. When the session is gone, or does not answer in time,
 * the resolution is refused with a name: no other session of the same name picks it up, and
 * nothing is invented in its place.
 *
 * ## The clock
 *
 * The sessions read time from an injected clock, so the lease and the deadline are driven by
 * the test and never by a sleep. The waiting threads wake in short real-time slices and
 * compare the injected clock, so advancing it is what ends a wait.
 *
 * The limit of the class is the net under every row: a row that waits for an answer nobody
 * sends fails by timeout instead of hanging the lane.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ConfigurationProviderSessionsTest {

    private val now = AtomicLong(1_000L)
    private val limits = ConfigurationProviderLimits(
        leaseMillis = 1_000L,
        maxPollMillis = 200L,
        resolveDeadlineMillis = 500L,
        maxSessions = 3,
    )
    private val sessions = ConfigurationProviderSessions(clock = { now.get() }, limits = limits)
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "provider-sessions-test").apply { isDaemon = true }
    }

    @AfterEach
    fun tearDown() {
        executor.shutdownNow()
    }

    // ---------------------------------------------------------------- value types

    @Test
    fun `an integer is the canonical text of a long and nothing else`() {
        val accepted = listOf("0", "7", "-3", "9223372036854775807", "-9223372036854775808")
        val refused = listOf("", " 1", "1 ", "+1", "007", "-0", "1.5", "0x10", "1e3", "9223372036854775808", "abc")
        for (text in accepted) assertTrue(ProviderValueType.Whole.accepts(text), "accepts '$text'")
        for (text in refused) assertFalse(ProviderValueType.Whole.accepts(text), "refuses '$text'")
    }

    @Test
    fun `a boolean is the word true or the word false and nothing else`() {
        assertTrue(ProviderValueType.Flag.accepts("true"))
        assertTrue(ProviderValueType.Flag.accepts("false"))
        for (text in listOf("", "TRUE", "True", "1", "0", "yes", " true")) {
            assertFalse(ProviderValueType.Flag.accepts(text), "refuses '$text'")
        }
    }

    @Test
    fun `a string accepts any text and the words map to the types`() {
        assertTrue(ProviderValueType.Text.accepts(""))
        assertTrue(ProviderValueType.Text.accepts("anything at all"))
        assertEquals(ProviderValueType.Text, ProviderValueType.ofWord("string"))
        assertEquals(ProviderValueType.Whole, ProviderValueType.ofWord("integer"))
        assertEquals(ProviderValueType.Flag, ProviderValueType.ofWord("boolean"))
        assertNull(ProviderValueType.ofWord("float"))
        assertNull(ProviderValueType.ofWord("String"))
    }

    @Test
    fun `a lease that does not outlast a poll is not a configuration`() {
        val failure = runCatching { ConfigurationProviderLimits(leaseMillis = 200L, maxPollMillis = 200L) }
        assertIs<IllegalArgumentException>(failure.exceptionOrNull())
    }

    // ---------------------------------------------------------------- registration

    @Test
    fun `a provider that declares plain keys registers and learns its lease`() {
        val outcome = sessions.register(OWNER, "cfg", listOf(key("REGION"), key("RETRIES", "integer")))

        val registered = assertIs<ProviderRegistrationOutcome.Registered>(outcome)
        assertTrue(registered.sessionId.isNotBlank())
        assertEquals(limits.leaseMillis, registered.leaseMillis)
        assertEquals(1, sessions.liveSessions())
    }

    @Test
    fun `a key labelled secret is refused by name and nothing is registered`() {
        val outcome = sessions.register(
            OWNER,
            "cfg",
            listOf(key("REGION"), key("API_TOKEN", label = "secret")),
        )

        val refused = assertIs<ProviderRegistrationOutcome.Refused>(outcome)
        assertEquals("a-secret-is-not-configuration", refused.reason)
        assertTrue(refused.detail.contains("API_TOKEN"), "the detail names the key: ${refused.detail}")
        assertEquals(0, sessions.liveSessions())
    }

    @Test
    fun `every spelling of a secret label is refused as a secret and nothing is registered`() {
        for (label in listOf("secret", "Secret", "SECRET", "sensitive", "credential", "password", "token")) {
            val refused = assertIs<ProviderRegistrationOutcome.Refused>(
                sessions.register(OWNER, "cfg", listOf(key("REGION", label = label))),
                "label '$label'",
            )
            assertEquals("a-secret-is-not-configuration", refused.reason, "label '$label'")
        }
        assertEquals(0, sessions.liveSessions())
    }

    @Test
    fun `a label the product does not know is refused and never read as plain`() {
        for (label in listOf("", "public", "plain", "NON-SECRET", "non secret", "whatever")) {
            val refused = assertIs<ProviderRegistrationOutcome.Refused>(
                sessions.register(OWNER, "cfg", listOf(key("REGION", label = label))),
                "label '$label'",
            )
            assertEquals("sensitivity-label-unknown", refused.reason, "label '$label'")
        }
        assertEquals(0, sessions.liveSessions())
    }

    @Test
    fun `an unknown type is refused`() {
        val refused = assertIs<ProviderRegistrationOutcome.Refused>(
            sessions.register(OWNER, "cfg", listOf(key("RATIO", "float"))),
        )
        assertEquals("provider-key-type-unknown", refused.reason)
        assertEquals(0, sessions.liveSessions())
    }

    @Test
    fun `a key name the configuration refuses is refused with the configuration reason`() {
        val cases = mapOf(
            "PATH" to "the-configuration-name-is-a-path-anchor",
            "not legal" to "the-configuration-name-is-not-legal",
            "PIPELINEK_X" to "the-configuration-name-is-reserved",
            "A".repeat(RunConfiguration.MAX_NAME_LENGTH + 1) to "the-configuration-name-is-too-long",
        )
        for ((name, reason) in cases) {
            val refused = assertIs<ProviderRegistrationOutcome.Refused>(
                sessions.register(OWNER, "cfg", listOf(key(name))),
                "name '$name'",
            )
            assertEquals(reason, refused.reason, "name '$name'")
        }
        assertEquals(0, sessions.liveSessions())
    }

    @Test
    fun `a key declared twice is refused`() {
        val refused = assertIs<ProviderRegistrationOutcome.Refused>(
            sessions.register(OWNER, "cfg", listOf(key("REGION"), key("REGION", "integer"))),
        )
        assertEquals("provider-key-duplicated", refused.reason)
    }

    @Test
    fun `a provider with no keys or with too many keys is refused`() {
        val none = assertIs<ProviderRegistrationOutcome.Refused>(sessions.register(OWNER, "cfg", emptyList()))
        assertEquals("provider-has-no-keys", none.reason)

        val tooMany = (1..RunConfiguration.MAX_ENTRIES + 1).map { key("K$it") }
        val many = assertIs<ProviderRegistrationOutcome.Refused>(sessions.register(OWNER, "cfg", tooMany))
        assertEquals("provider-has-too-many-keys", many.reason)

        val atTheLimit = (1..RunConfiguration.MAX_ENTRIES).map { key("K$it") }
        assertIs<ProviderRegistrationOutcome.Registered>(sessions.register(OWNER, "cfg", atTheLimit))
    }

    @Test
    fun `a provider name that is not a name is refused`() {
        for (name in listOf("", " ", "a b", "1cfg", "cfg/../x", "a".repeat(65), "cfg\n")) {
            val refused = assertIs<ProviderRegistrationOutcome.Refused>(
                sessions.register(OWNER, name, listOf(key("REGION"))),
                "name '$name'",
            )
            assertEquals("provider-name-not-legal", refused.reason, "name '$name'")
        }
        assertIs<ProviderRegistrationOutcome.Registered>(sessions.register(OWNER, "a".repeat(64), listOf(key("REGION"))))
    }

    @Test
    fun `a live session of the same owner holds the name and the other owner has its own`() {
        assertIs<ProviderRegistrationOutcome.Registered>(sessions.register(OWNER, "cfg", listOf(key("REGION"))))

        val taken = assertIs<ProviderRegistrationOutcome.Refused>(
            sessions.register(OWNER, "cfg", listOf(key("REGION"))),
        )
        assertEquals("provider-name-taken", taken.reason)

        assertIs<ProviderRegistrationOutcome.Registered>(sessions.register(OTHER, "cfg", listOf(key("REGION"))))
        assertEquals(2, sessions.liveSessions())
    }

    @Test
    fun `the name is free again once the lease of its session has lapsed`() {
        assertIs<ProviderRegistrationOutcome.Registered>(sessions.register(OWNER, "cfg", listOf(key("REGION"))))

        now.addAndGet(limits.leaseMillis + 1)

        assertIs<ProviderRegistrationOutcome.Registered>(sessions.register(OWNER, "cfg", listOf(key("REGION"))))
        assertEquals(1, sessions.liveSessions())
    }

    @Test
    fun `the number of live sessions is bounded`() {
        for (index in 1..limits.maxSessions) {
            assertIs<ProviderRegistrationOutcome.Registered>(
                sessions.register("owner-$index", "cfg", listOf(key("REGION"))),
            )
        }
        val refused = assertIs<ProviderRegistrationOutcome.Refused>(
            sessions.register("owner-extra", "cfg", listOf(key("REGION"))),
        )
        assertEquals("too-many-provider-sessions", refused.reason)
    }

    // ---------------------------------------------------------------- resolution

    @Test
    fun `a resolution with no session of that name is refused at once`() {
        val refused = assertIs<ProviderResolution.Refused>(sessions.resolve(OWNER, "cfg", listOf("REGION")))
        assertEquals("provider-not-registered", refused.reason)
        assertEquals(0, sessions.pendingResolutions())
    }

    @Test
    fun `a resolution never reaches a session of another owner`() {
        val other = registerAs(OTHER)

        val refused = assertIs<ProviderResolution.Refused>(sessions.resolve(OWNER, "cfg", listOf("REGION")))

        assertEquals("provider-not-registered", refused.reason)
        assertEquals(ProviderPollOutcome.Idle, sessions.poll(OTHER, other, 0))
    }

    @Test
    fun `a key the session did not declare is refused and nothing is asked`() {
        val session = register("cfg", key("REGION"))

        val refused = assertIs<ProviderResolution.Refused>(sessions.resolve(OWNER, "cfg", listOf("REGION", "ZONE")))

        assertEquals("key-not-declared", refused.reason)
        assertTrue(refused.detail.contains("ZONE"), refused.detail)
        assertEquals(0, sessions.pendingResolutions())
        assertEquals(ProviderPollOutcome.Idle, sessions.poll(OWNER, session, 0))
    }

    @Test
    fun `a resolution that asks for nothing is refused`() {
        register("cfg", key("REGION"))
        val refused = assertIs<ProviderResolution.Refused>(sessions.resolve(OWNER, "cfg", emptyList()))
        assertEquals("no-keys-asked", refused.reason)
    }

    @Test
    fun `the provider answers the asked keys and the resolution carries values and provenance`() {
        val session = register("cfg", key("REGION"), key("RETRIES", "integer"), key("VERBOSE", "boolean"))
        val pending = resolveAsync("cfg", "REGION", "RETRIES")
        awaitPending(1)

        val request = assertIs<ProviderPollOutcome.Request>(sessions.poll(OWNER, session, 0))
        assertEquals(listOf("REGION", "RETRIES"), request.keys)
        val answered = sessions.answer(
            OWNER,
            session,
            request.requestId,
            "rev-7",
            mapOf("REGION" to "eu-west", "RETRIES" to "3"),
        )

        assertEquals(ProviderSessionOutcome.Accepted, answered)
        val resolved = assertIs<ProviderResolution.Resolved>(pending.get(5, TimeUnit.SECONDS))
        val values = mapOf("REGION" to "eu-west", "RETRIES" to "3")
        assertEquals(values, resolved.values)
        assertEquals("cfg", resolved.provenance.provider)
        assertEquals("rev-7", resolved.provenance.revision)
        assertEquals(mapOf("REGION" to "string", "RETRIES" to "integer"), resolved.provenance.types)
        assertEquals(sha256(RunConfiguration.canonicalText(values)), resolved.provenance.digest)
        assertEquals(0, sessions.pendingResolutions())
    }

    @Test
    fun `an answer that breaks a rule is refused to the provider and fails the resolution`() {
        val cases = listOf(
            Case("an undeclared key", mapOf("REGION" to "eu", "RETRIES" to "3", "ZONE" to "a"), "answer-key-unexpected"),
            Case("a key the request asked but the answer lacks", mapOf("REGION" to "eu"), "answer-key-missing"),
            Case("a key of the declared set that was not asked", mapOf("REGION" to "eu", "RETRIES" to "3", "VERBOSE" to "true"), "answer-key-unexpected"),
            Case("a value of the wrong type", mapOf("REGION" to "eu", "RETRIES" to "three"), "answer-value-wrong-type"),
            Case("a value with a NUL", mapOf("REGION" to "e\u0000u", "RETRIES" to "3"), "the-configuration-value-has-a-nul"),
            Case(
                "a value that is too long",
                mapOf("REGION" to "x".repeat(RunConfiguration.MAX_VALUE_LENGTH + 1), "RETRIES" to "3"),
                "the-configuration-value-is-too-long",
            ),
        )
        for (case in cases) {
            val session = register("cfg", key("REGION"), key("RETRIES", "integer"), key("VERBOSE", "boolean"))
            val pending = resolveAsync("cfg", "REGION", "RETRIES")
            awaitPending(1)
            val request = assertIs<ProviderPollOutcome.Request>(sessions.poll(OWNER, session, 0), case.label)

            val answered = sessions.answer(OWNER, session, request.requestId, "rev-1", case.values)

            val refusal = assertIs<ProviderSessionOutcome.Refused>(answered, case.label)
            assertEquals(case.reason, refusal.reason, case.label)
            val resolution = assertIs<ProviderResolution.Refused>(pending.get(5, TimeUnit.SECONDS), case.label)
            assertEquals("provider-answer-refused", resolution.reason, case.label)
            assertEquals(0, sessions.pendingResolutions(), case.label)
            sessions.close(OWNER, session)
        }
    }

    @Test
    fun `a revision that is empty, too long or carries a control character is refused`() {
        for (revision in listOf("", "r".repeat(65), "rev\n1", "rev\u0000")) {
            val session = register("cfg", key("REGION"))
            val pending = resolveAsync("cfg", "REGION")
            awaitPending(1)
            val request = assertIs<ProviderPollOutcome.Request>(sessions.poll(OWNER, session, 0))

            val answered = sessions.answer(OWNER, session, request.requestId, revision, mapOf("REGION" to "eu"))

            val refusal = assertIs<ProviderSessionOutcome.Refused>(answered, "revision '$revision'")
            assertEquals("revision-not-legal", refusal.reason, "revision '$revision'")
            assertIs<ProviderResolution.Refused>(pending.get(5, TimeUnit.SECONDS))
            sessions.close(OWNER, session)
        }
    }

    @Test
    fun `a request is answered once and the first answer stands`() {
        val session = register("cfg", key("REGION"))
        val pending = resolveAsync("cfg", "REGION")
        awaitPending(1)
        val request = assertIs<ProviderPollOutcome.Request>(sessions.poll(OWNER, session, 0))

        assertEquals(
            ProviderSessionOutcome.Accepted,
            sessions.answer(OWNER, session, request.requestId, "rev-1", mapOf("REGION" to "first")),
        )
        val second = sessions.answer(OWNER, session, request.requestId, "rev-2", mapOf("REGION" to "second"))

        assertEquals("request-unknown", assertIs<ProviderSessionOutcome.Refused>(second).reason)
        val resolved = assertIs<ProviderResolution.Resolved>(pending.get(5, TimeUnit.SECONDS))
        assertEquals(mapOf("REGION" to "first"), resolved.values)
        assertEquals("rev-1", resolved.provenance.revision)
    }

    @Test
    fun `a request that was never polled cannot be answered`() {
        val session = register("cfg", key("REGION"))
        val pending = resolveAsync("cfg", "REGION")
        awaitPending(1)
        val guessed = "00000000-0000-0000-0000-000000000000"

        val answered = sessions.answer(OWNER, session, guessed, "rev-1", mapOf("REGION" to "eu"))

        assertEquals("request-unknown", assertIs<ProviderSessionOutcome.Refused>(answered).reason)
        assertFalse(pending.isDone, "the resolution keeps waiting for the real answer")
        val request = assertIs<ProviderPollOutcome.Request>(sessions.poll(OWNER, session, 0))
        sessions.answer(OWNER, session, request.requestId, "rev-1", mapOf("REGION" to "eu"))
        assertIs<ProviderResolution.Resolved>(pending.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun `an answer from another owner or to another session is refused and the resolution still waits`() {
        val session = register("cfg", key("REGION"))
        val other = registerAs(OTHER)
        val pending = resolveAsync("cfg", "REGION")
        awaitPending(1)
        val request = assertIs<ProviderPollOutcome.Request>(sessions.poll(OWNER, session, 0))

        val fromOtherOwner = sessions.answer(OTHER, session, request.requestId, "rev-1", mapOf("REGION" to "evil"))
        val toOtherSession = sessions.answer(OWNER, other, request.requestId, "rev-1", mapOf("REGION" to "evil"))

        assertIs<ProviderSessionOutcome.Refused>(fromOtherOwner)
        assertIs<ProviderSessionOutcome.Refused>(toOtherSession)
        assertFalse(pending.isDone)
        sessions.answer(OWNER, session, request.requestId, "rev-1", mapOf("REGION" to "eu"))
        val resolved = assertIs<ProviderResolution.Resolved>(pending.get(5, TimeUnit.SECONDS))
        assertEquals(mapOf("REGION" to "eu"), resolved.values)
    }

    @Test
    fun `a provider that does not answer in time is a timeout and the request dies with it`() {
        val session = register("cfg", key("REGION"))
        val pending = resolveAsync("cfg", "REGION")
        awaitPending(1)
        val request = assertIs<ProviderPollOutcome.Request>(sessions.poll(OWNER, session, 0))

        // Keep the lease alive while the deadline passes: the provider is alive, only silent.
        now.addAndGet(limits.resolveDeadlineMillis - 100)
        assertEquals(ProviderPollOutcome.Idle, sessions.poll(OWNER, session, 0))
        now.addAndGet(101)

        val refused = assertIs<ProviderResolution.Refused>(pending.get(5, TimeUnit.SECONDS))
        assertEquals("provider-timeout", refused.reason)
        assertEquals(0, sessions.pendingResolutions())
        val late = sessions.answer(OWNER, session, request.requestId, "rev-1", mapOf("REGION" to "eu"))
        assertEquals("request-unknown", assertIs<ProviderSessionOutcome.Refused>(late).reason)
    }

    @Test
    fun `a session whose lease lapses before the deadline is gone and not a timeout`() {
        val slow = ConfigurationProviderSessions(
            clock = { now.get() },
            limits = ConfigurationProviderLimits(
                leaseMillis = 300L,
                maxPollMillis = 100L,
                resolveDeadlineMillis = 10_000L,
                maxSessions = 3,
            ),
        )
        val registered = assertIs<ProviderRegistrationOutcome.Registered>(
            slow.register(OWNER, "cfg", listOf(key("REGION"))),
        )
        val pending = CompletableFuture.supplyAsync({ slow.resolve(OWNER, "cfg", listOf("REGION")) }, executor)
        awaitPending(slow, 1)
        assertIs<ProviderPollOutcome.Request>(slow.poll(OWNER, registered.sessionId, 0))

        now.addAndGet(301)

        val refused = assertIs<ProviderResolution.Refused>(pending.get(5, TimeUnit.SECONDS))
        assertEquals("provider-gone", refused.reason)
        assertEquals(0, slow.liveSessions())
    }

    @Test
    fun `closing a session fails its pending resolutions as gone`() {
        val session = register("cfg", key("REGION"))
        val pending = resolveAsync("cfg", "REGION")
        awaitPending(1)

        assertEquals(ProviderSessionOutcome.Accepted, sessions.close(OWNER, session))

        val refused = assertIs<ProviderResolution.Refused>(pending.get(5, TimeUnit.SECONDS))
        assertEquals("provider-gone", refused.reason)
        assertEquals(0, sessions.liveSessions())
        assertEquals(0, sessions.pendingResolutions())
    }

    @Test
    fun `a resolution is never rerouted to a later session of the same name`() {
        val first = register("cfg", key("REGION"))
        val pending = resolveAsync("cfg", "REGION")
        awaitPending(1)
        sessions.close(OWNER, first)

        val second = register("cfg", key("REGION"))

        val refused = assertIs<ProviderResolution.Refused>(pending.get(5, TimeUnit.SECONDS))
        assertEquals("provider-gone", refused.reason)
        assertEquals(ProviderPollOutcome.Idle, sessions.poll(OWNER, second, 0), "the new session is asked nothing")
    }

    @Test
    fun `a resolution is not rerouted after the lease of its session lapsed either`() {
        val first = register("cfg", key("REGION"))
        val pending = resolveAsync("cfg", "REGION")
        awaitPending(1)

        now.addAndGet(limits.leaseMillis + 1)
        val second = register("cfg", key("REGION"))

        val refused = assertIs<ProviderResolution.Refused>(pending.get(5, TimeUnit.SECONDS))
        assertEquals("provider-gone", refused.reason)
        assertEquals(ProviderPollOutcome.Idle, sessions.poll(OWNER, second, 0))
        assertEquals("provider-session-unknown", assertIs<ProviderPollOutcome.Refused>(sessions.poll(OWNER, first, 0)).reason)
    }

    // ---------------------------------------------------------------- polling

    @Test
    fun `a poll with nothing to ask is idle and a poll of an unknown or foreign session is refused`() {
        val session = register("cfg", key("REGION"))

        assertEquals(ProviderPollOutcome.Idle, sessions.poll(OWNER, session, 0))
        val unknown = assertIs<ProviderPollOutcome.Refused>(sessions.poll(OWNER, "no-such-session", 0))
        val foreign = assertIs<ProviderPollOutcome.Refused>(sessions.poll(OTHER, session, 0))
        assertEquals("provider-session-unknown", unknown.reason)
        assertEquals("provider-session-unknown", foreign.reason)
    }

    @Test
    fun `a poll that waits is woken by a resolution`() {
        val session = register("cfg", key("REGION"))
        val polling = CompletableFuture.supplyAsync({ sessions.poll(OWNER, session, limits.maxPollMillis) }, executor)
        awaitWaitingPolls(1)
        assertFalse(polling.isDone, "nothing to ask yet")

        val pending = resolveAsync("cfg", "REGION")

        val request = assertIs<ProviderPollOutcome.Request>(polling.get(5, TimeUnit.SECONDS))
        assertEquals(listOf("REGION"), request.keys)
        sessions.answer(OWNER, session, request.requestId, "rev-1", mapOf("REGION" to "eu"))
        assertIs<ProviderResolution.Resolved>(pending.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun `the wait of a poll is capped at the longest poll the limits allow`() {
        val session = register("cfg", key("REGION"))
        val polling = CompletableFuture.supplyAsync({ sessions.poll(OWNER, session, 3_600_000L) }, executor)
        awaitWaitingPolls(1)

        now.addAndGet(limits.maxPollMillis - 1)
        Thread.sleep(150)
        assertFalse(polling.isDone, "one millisecond short of the cap the poll still waits")

        now.addAndGet(1)

        // A poll that honoured the hour it was asked for would still be waiting and this would time out.
        assertEquals(ProviderPollOutcome.Idle, polling.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun `a session has at most one poll waiting and the second is refused without disturbing the first`() {
        val session = register("cfg", key("REGION"))
        val first = CompletableFuture.supplyAsync({ sessions.poll(OWNER, session, limits.maxPollMillis) }, executor)
        awaitWaitingPolls(1)

        val second = assertIs<ProviderPollOutcome.Refused>(sessions.poll(OWNER, session, limits.maxPollMillis))

        assertEquals("poll-already-waiting", second.reason)
        assertEquals(1, sessions.waitingPolls(), "the refused poll never joined the waiting ones")
        assertFalse(first.isDone, "the first poll is still waiting")
        assertEquals(1, sessions.liveSessions(), "a refused poll does not close the session")

        val pending = resolveAsync("cfg", "REGION")
        val request = assertIs<ProviderPollOutcome.Request>(first.get(5, TimeUnit.SECONDS))
        sessions.answer(OWNER, session, request.requestId, "rev-1", mapOf("REGION" to "eu"))
        assertIs<ProviderResolution.Resolved>(pending.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun `a session can poll again once its previous poll has ended`() {
        val session = register("cfg", key("REGION"))

        assertEquals(ProviderPollOutcome.Idle, sessions.poll(OWNER, session, 0))
        assertEquals(ProviderPollOutcome.Idle, sessions.poll(OWNER, session, 0))
        assertEquals(0, sessions.waitingPolls())
    }

    @Test
    fun `the single poll rule is per session so another session may poll while one waits`() {
        val first = register("cfg", key("REGION"))
        val second = registerAs(OTHER, "cfg")
        val waiting = CompletableFuture.supplyAsync({ sessions.poll(OWNER, first, limits.maxPollMillis) }, executor)
        awaitWaitingPolls(1)

        assertEquals(ProviderPollOutcome.Idle, sessions.poll(OTHER, second, 0))

        assertFalse(waiting.isDone)
        sessions.close(OWNER, first)
        waiting.get(5, TimeUnit.SECONDS)
    }

    @Test
    fun `a polling provider keeps its lease and a silent one loses it`() {
        val session = register("cfg", key("REGION"))

        now.addAndGet(600)
        assertEquals(ProviderPollOutcome.Idle, sessions.poll(OWNER, session, 0))
        now.addAndGet(600)
        assertEquals(1, sessions.liveSessions(), "the poll renewed the lease")
        assertEquals(ProviderPollOutcome.Idle, sessions.poll(OWNER, session, 0))

        now.addAndGet(limits.leaseMillis + 1)
        assertEquals(0, sessions.liveSessions(), "silence lapsed it")
    }

    @Test
    fun `a request goes to one poll only and requests come out in the order they were asked`() {
        val session = register("cfg", key("REGION"), key("ZONE"))
        val first = resolveAsync("cfg", "REGION")
        awaitPending(1)
        val second = resolveAsync("cfg", "ZONE")
        awaitPending(2)

        val one = assertIs<ProviderPollOutcome.Request>(sessions.poll(OWNER, session, 0))
        val two = assertIs<ProviderPollOutcome.Request>(sessions.poll(OWNER, session, 0))

        assertEquals(listOf("REGION"), one.keys)
        assertEquals(listOf("ZONE"), two.keys)
        assertTrue(one.requestId != two.requestId)
        assertEquals(ProviderPollOutcome.Idle, sessions.poll(OWNER, session, 0))
        sessions.answer(OWNER, session, one.requestId, "rev-1", mapOf("REGION" to "eu"))
        sessions.answer(OWNER, session, two.requestId, "rev-1", mapOf("ZONE" to "a"))
        assertIs<ProviderResolution.Resolved>(first.get(5, TimeUnit.SECONDS))
        assertIs<ProviderResolution.Resolved>(second.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun `concurrent resolutions each receive their own answer`() {
        val session = register("cfg", *(1..8).map { key("K$it") }.toTypedArray())
        val resolutions = (1..8).map { index -> index to resolveAsync("cfg", "K$index") }
        awaitPending(8)

        repeat(8) {
            val request = assertIs<ProviderPollOutcome.Request>(sessions.poll(OWNER, session, 0))
            val name = request.keys.single()
            assertEquals(
                ProviderSessionOutcome.Accepted,
                sessions.answer(OWNER, session, request.requestId, "rev-1", mapOf(name to "value-of-$name")),
            )
        }

        for ((index, future) in resolutions) {
            val resolved = assertIs<ProviderResolution.Resolved>(future.get(5, TimeUnit.SECONDS))
            assertEquals(mapOf("K$index" to "value-of-K$index"), resolved.values)
        }
        assertEquals(0, sessions.pendingResolutions())
    }

    // ---------------------------------------------------------------- helpers

    private fun key(name: String, type: String = "string", label: String = "non-secret") =
        ProviderKeyDeclaration(name, type, label)

    private fun register(provider: String, vararg keys: ProviderKeyDeclaration): String {
        val outcome = sessions.register(OWNER, provider, keys.toList())
        return assertIs<ProviderRegistrationOutcome.Registered>(outcome).sessionId
    }

    private fun registerAs(owner: String, provider: String = "cfg"): String {
        val outcome = sessions.register(owner, provider, listOf(key("REGION")))
        return assertIs<ProviderRegistrationOutcome.Registered>(outcome).sessionId
    }

    private fun resolveAsync(provider: String, vararg keys: String): CompletableFuture<ProviderResolution> =
        CompletableFuture.supplyAsync({ sessions.resolve(OWNER, provider, keys.toList()) }, executor)

    private fun awaitPending(count: Int) = awaitPending(sessions, count)

    private fun awaitWaitingPolls(count: Int) {
        val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (sessions.waitingPolls() != count) {
            if (System.nanoTime() > limit) fail<Unit>("expected $count waiting polls, saw ${sessions.waitingPolls()}")
            Thread.sleep(2)
        }
    }

    private fun awaitPending(target: ConfigurationProviderSessions, count: Int) {
        val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (target.pendingResolutions() != count) {
            if (System.nanoTime() > limit) fail<Unit>("expected $count pending resolutions, saw ${target.pendingResolutions()}")
            Thread.sleep(2)
        }
    }

    private fun sha256(text: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))

    private inline fun <reified T : Any> assertIs(actual: Any?, message: String? = null): T =
        assertInstanceOf(T::class.java, actual, message)

    private data class Case(val label: String, val values: Map<String, String>, val reason: String)

    private companion object {
        const val OWNER = "owner-digest-a"
        const val OTHER = "owner-digest-b"
    }
}
