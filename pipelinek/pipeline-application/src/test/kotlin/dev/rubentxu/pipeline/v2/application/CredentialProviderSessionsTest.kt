package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The credential provider sessions: how a controller that is connected to the service hands a
 * run the credential it asks for, while the secret stays with the controller until that moment.
 *
 * ## The guarantee under trial
 *
 * A credential reaches the run only from the ONE session the request was handed to, only when
 * that session answers the request it was handed, and only once. When the session is gone, or
 * does not answer in time, the request is refused with a name: no other session of the same
 * name picks it up, no other credential owner is asked, and nothing is invented in its place.
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
class CredentialProviderSessionsTest {

    private val now = AtomicLong(1_000L)
    private val limits = CredentialProviderLimits(
        leaseMillis = 1_000L,
        maxPollMillis = 200L,
        askDeadlineMillis = 500L,
        maxSessions = 3,
        maxPendingPerSession = 2,
    )
    private val sessions = CredentialProviderSessions(clock = { now.get() }, limits = limits)
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "credential-sessions-test").apply { isDaemon = true }
    }

    @AfterEach
    fun tearDown() {
        executor.shutdownNow()
    }

    // ---------------------------------------------------------------- limits

    @Test
    fun `a lease that does not outlast a poll is not a configuration`() {
        val failure = runCatching { CredentialProviderLimits(leaseMillis = 200L, maxPollMillis = 200L) }
        assertIs<IllegalArgumentException>(failure.exceptionOrNull())
    }

    @Test
    fun `an ask deadline that is not positive is not a configuration`() {
        val failure = runCatching { CredentialProviderLimits(askDeadlineMillis = 0L) }
        assertIs<IllegalArgumentException>(failure.exceptionOrNull())
    }

    @Test
    fun `the default ask deadline ends before the deadline the run itself waits for the broker`() {
        assertTrue(
            CredentialProviderLimits().askDeadlineMillis < UnixSocketBrokerLink.DEFAULT_DEADLINE_MILLIS,
            "the service must deny before the run gives up, or the run reports a timeout the service never decided",
        )
    }

    // ---------------------------------------------------------------- registration

    @Test
    fun `a provider registers and learns its lease`() {
        val registered = assertIs<ProviderRegistrationOutcome.Registered>(sessions.register(OWNER, "vault"))

        assertTrue(registered.sessionId.isNotBlank())
        assertEquals(limits.leaseMillis, registered.leaseMillis)
        assertEquals(1, sessions.liveSessions())
        assertTrue(sessions.hasSession(OWNER, "vault"))
    }

    @Test
    fun `a provider name the registry does not admit is refused and nothing is registered`() {
        for (name in listOf("", " ", "1vault", "va ult", "va/ult", "a".repeat(65))) {
            val refused = assertIs<ProviderRegistrationOutcome.Refused>(sessions.register(OWNER, name), "name '$name'")
            assertEquals("provider-name-not-legal", refused.reason, "name '$name'")
        }
        assertEquals(0, sessions.liveSessions())
    }

    @Test
    fun `a live name is taken for its owner and free for nobody else's view of it`() {
        register("vault")

        val taken = assertIs<ProviderRegistrationOutcome.Refused>(sessions.register(OWNER, "vault"))
        assertEquals("provider-name-taken", taken.reason)

        // Another credential has a name space of its own: it neither sees nor shadows this one.
        assertIs<ProviderRegistrationOutcome.Registered>(sessions.register(OTHER, "vault"))
        assertTrue(sessions.hasSession(OWNER, "vault"))
        assertTrue(sessions.hasSession(OTHER, "vault"))
    }

    @Test
    fun `the service keeps a bounded number of sessions`() {
        register("one")
        register("two")
        register("three")

        val refused = assertIs<ProviderRegistrationOutcome.Refused>(sessions.register(OWNER, "four"))
        assertEquals("too-many-provider-sessions", refused.reason)
        assertEquals(3, sessions.liveSessions())
    }

    // ---------------------------------------------------------------- the grant path

    @Test
    fun `the provider answers when it is asked and the run receives exactly the bytes it was given`() {
        val session = register("vault")
        val asking = askAsync("vault", "run-1", "deploy-token")
        awaitAsks(1)

        val asked = assertIs<CredentialPollOutcome.Asked>(sessions.poll(OWNER, session, 0))
        assertEquals("run-1", asked.execution)
        assertEquals("deploy-token", asked.credentialId)
        assertEquals("string", asked.kind)
        assertTrue(asked.requestId.isNotBlank())

        assertEquals(ProviderSessionOutcome.Accepted, sessions.answer(OWNER, session, asked.requestId, true, SECRET.copyOf()))

        val granted = assertIs<CredentialResolution.Granted>(asking.get(5, TimeUnit.SECONDS))
        assertArrayEquals(SECRET, granted.value)
        assertEquals(0, sessions.pendingAsks())
    }

    @Test
    fun `the registry keeps its own copy so wiping the buffer of the caller does not wipe the answer`() {
        val session = register("vault")
        val asking = askAsync("vault", "run-1", "deploy-token")
        awaitAsks(1)
        val asked = assertIs<CredentialPollOutcome.Asked>(sessions.poll(OWNER, session, 0))
        val buffer = SECRET.copyOf()

        sessions.answer(OWNER, session, asked.requestId, true, buffer)
        buffer.fill(0)

        val granted = assertIs<CredentialResolution.Granted>(asking.get(5, TimeUnit.SECONDS))
        assertArrayEquals(SECRET, granted.value)
    }

    @Test
    fun `a granted answer never renders its bytes`() {
        val granted = CredentialResolution.Granted(SECRET.copyOf())

        assertFalse(granted.toString().contains(String(SECRET, Charsets.UTF_8)), granted.toString())
        assertFalse(granted.toString().contains(SECRET.joinToString(",")), granted.toString())
    }

    @Test
    fun `a provider that denies makes the ask a refusal with its own word`() {
        val session = register("vault")
        val asking = askAsync("vault", "run-1", "deploy-token")
        awaitAsks(1)
        val asked = assertIs<CredentialPollOutcome.Asked>(sessions.poll(OWNER, session, 0))

        assertEquals(ProviderSessionOutcome.Accepted, sessions.answer(OWNER, session, asked.requestId, false, null))

        val refused = assertIs<CredentialResolution.Refused>(asking.get(5, TimeUnit.SECONDS))
        assertEquals("provider-denied", refused.reason)
    }

    // ---------------------------------------------------------------- one answer, one session

    @Test
    fun `a request id is answered once and the first answer stands`() {
        val session = register("vault")
        val asking = askAsync("vault", "run-1", "deploy-token")
        awaitAsks(1)
        val asked = assertIs<CredentialPollOutcome.Asked>(sessions.poll(OWNER, session, 0))

        assertEquals(ProviderSessionOutcome.Accepted, sessions.answer(OWNER, session, asked.requestId, true, SECRET.copyOf()))
        val second = assertIs<ProviderSessionOutcome.Refused>(
            sessions.answer(OWNER, session, asked.requestId, true, "another-secret".toByteArray()),
        )

        assertEquals("request-unknown", second.reason)
        val granted = assertIs<CredentialResolution.Granted>(asking.get(5, TimeUnit.SECONDS))
        assertArrayEquals(SECRET, granted.value)
    }

    @Test
    fun `a request that was never polled cannot be answered`() {
        val session = register("vault")
        val asking = askAsync("vault", "run-1", "deploy-token")
        awaitAsks(1)
        val queued = sessions.queuedRequestIds(session).single()

        val refused = assertIs<ProviderSessionOutcome.Refused>(sessions.answer(OWNER, session, queued, true, SECRET.copyOf()))

        assertEquals("request-unknown", refused.reason)
        assertEquals(1, sessions.pendingAsks(), "the ask still waits for the session it was queued on")
        asking.cancel(true)
    }

    @Test
    fun `an answer from another session or another owner is refused and the ask still waits`() {
        val session = register("vault")
        val intruderSameName = assertIs<ProviderRegistrationOutcome.Registered>(sessions.register(OTHER, "vault")).sessionId
        val intruderOtherName = assertIs<ProviderRegistrationOutcome.Registered>(sessions.register(OTHER, "other")).sessionId
        val asking = askAsync("vault", "run-1", "deploy-token")
        awaitAsks(1)
        val asked = assertIs<CredentialPollOutcome.Asked>(sessions.poll(OWNER, session, 0))

        val attempts = listOf(
            // The id of the session is the owner's: another owner cannot use it.
            sessions.answer(OTHER, session, asked.requestId, true, "stolen".toByteArray()),
            // Another owner's session of the same name does not hold the request.
            sessions.answer(OTHER, intruderSameName, asked.requestId, true, "stolen".toByteArray()),
            // Another owner's session of another name does not either.
            sessions.answer(OTHER, intruderOtherName, asked.requestId, true, "stolen".toByteArray()),
        )

        for (attempt in attempts) assertIs<ProviderSessionOutcome.Refused>(attempt)
        assertEquals(1, sessions.pendingAsks())
        assertEquals(ProviderSessionOutcome.Accepted, sessions.answer(OWNER, session, asked.requestId, true, SECRET.copyOf()))
        val granted = assertIs<CredentialResolution.Granted>(asking.get(5, TimeUnit.SECONDS))
        assertArrayEquals(SECRET, granted.value)
    }

    @Test
    fun `a second session of the same owner cannot answer what the first was asked`() {
        val first = register("vault")
        val second = register("backup")
        val asking = askAsync("vault", "run-1", "deploy-token")
        awaitAsks(1)
        val asked = assertIs<CredentialPollOutcome.Asked>(sessions.poll(OWNER, first, 0))

        val refused = assertIs<ProviderSessionOutcome.Refused>(
            sessions.answer(OWNER, second, asked.requestId, true, "stolen".toByteArray()),
        )

        assertEquals("request-unknown", refused.reason)
        assertEquals(1, sessions.pendingAsks())
        asking.cancel(true)
    }

    @Test
    fun `a request goes to one poll only and requests come out in the order they were asked`() {
        val session = register("vault")
        val first = askAsync("vault", "run-1", "first")
        awaitAsks(1)
        val second = askAsync("vault", "run-1", "second")
        awaitAsks(2)

        val one = assertIs<CredentialPollOutcome.Asked>(sessions.poll(OWNER, session, 0))
        val two = assertIs<CredentialPollOutcome.Asked>(sessions.poll(OWNER, session, 0))

        assertEquals("first", one.credentialId)
        assertEquals("second", two.credentialId)
        assertTrue(one.requestId != two.requestId)
        assertEquals(CredentialPollOutcome.Idle, sessions.poll(OWNER, session, 0))
        first.cancel(true)
        second.cancel(true)
    }

    // ---------------------------------------------------------------- an answer that breaks a rule

    @Test
    fun `a grant without bytes, with empty bytes or with too many is refused and fails the ask`() {
        val cases = mapOf(
            "no bytes" to null,
            "empty bytes" to ByteArray(0),
            "too many bytes" to ByteArray(CredentialProviderSessions.MAX_VALUE_BYTES + 1) { 1 },
        )
        for ((label, bytes) in cases) {
            val session = register("vault-${label.replace(' ', '-')}")
            val asking = askAsync("vault-${label.replace(' ', '-')}", "run-1", "deploy-token")
            awaitAsks(1)
            val asked = assertIs<CredentialPollOutcome.Asked>(sessions.poll(OWNER, session, 0))

            val refused = assertIs<ProviderSessionOutcome.Refused>(
                sessions.answer(OWNER, session, asked.requestId, true, bytes),
                label,
            )

            assertEquals("answer-value-not-legal", refused.reason, label)
            val failed = assertIs<CredentialResolution.Refused>(asking.get(5, TimeUnit.SECONDS), label)
            assertEquals("provider-answer-refused", failed.reason, label)
            sessions.close(OWNER, session)
        }
    }

    @Test
    fun `the largest legal value is handed over whole`() {
        val session = register("vault")
        val asking = askAsync("vault", "run-1", "deploy-token")
        awaitAsks(1)
        val asked = assertIs<CredentialPollOutcome.Asked>(sessions.poll(OWNER, session, 0))
        val largest = ByteArray(CredentialProviderSessions.MAX_VALUE_BYTES) { (it % 251).toByte() }

        assertEquals(ProviderSessionOutcome.Accepted, sessions.answer(OWNER, session, asked.requestId, true, largest.copyOf()))

        assertArrayEquals(largest, assertIs<CredentialResolution.Granted>(asking.get(5, TimeUnit.SECONDS)).value)
    }

    // ---------------------------------------------------------------- nobody to ask

    @Test
    fun `an ask with no live session is refused by name and nobody else is asked`() {
        val other = register("vault", OTHER)

        val refused = assertIs<CredentialResolution.Refused>(sessions.ask(OWNER, "vault", "run-1", "deploy-token", "string"))

        assertEquals("provider-not-registered", refused.reason)
        // The session of another owner under the same name was never offered the request.
        assertEquals(CredentialPollOutcome.Idle, sessions.poll(OTHER, other, 0))
        assertEquals(0, sessions.pendingAsks())
    }

    @Test
    fun `a provider that does not answer in time is a timeout and a late answer is refused`() {
        val session = register("vault")
        val asking = askAsync("vault", "run-1", "deploy-token")
        awaitAsks(1)
        val asked = assertIs<CredentialPollOutcome.Asked>(sessions.poll(OWNER, session, 0))

        now.addAndGet(limits.askDeadlineMillis + 1)

        // The answer arrives after the deadline: it is refused even if the waiting thread has not woken yet.
        val late = assertIs<ProviderSessionOutcome.Refused>(sessions.answer(OWNER, session, asked.requestId, true, SECRET.copyOf()))
        assertEquals("request-unknown", late.reason)
        val refused = assertIs<CredentialResolution.Refused>(asking.get(5, TimeUnit.SECONDS))
        assertEquals("provider-timeout", refused.reason)
        assertEquals(0, sessions.pendingAsks())
    }

    @Test
    fun `a session whose lease lapses before the deadline is gone and not a timeout`() {
        val session = register("vault")
        val asking = askAsync("vault", "run-1", "deploy-token")
        awaitAsks(1)
        sessions.poll(OWNER, session, 0)

        now.addAndGet(limits.leaseMillis + 1)

        val refused = assertIs<CredentialResolution.Refused>(asking.get(5, TimeUnit.SECONDS))
        assertEquals("provider-gone", refused.reason)
        assertEquals(0, sessions.liveSessions())
    }

    @Test
    fun `closing a session fails its pending asks as gone`() {
        val session = register("vault")
        val asking = askAsync("vault", "run-1", "deploy-token")
        awaitAsks(1)

        assertEquals(ProviderSessionOutcome.Accepted, sessions.close(OWNER, session))

        assertEquals("provider-gone", assertIs<CredentialResolution.Refused>(asking.get(5, TimeUnit.SECONDS)).reason)
    }

    @Test
    fun `an ask is never rerouted to a later session of the same name`() {
        val session = register("vault")
        val asking = askAsync("vault", "run-1", "deploy-token")
        awaitAsks(1)
        sessions.close(OWNER, session)
        val replacement = register("vault")

        assertEquals("provider-gone", assertIs<CredentialResolution.Refused>(asking.get(5, TimeUnit.SECONDS)).reason)
        assertEquals(CredentialPollOutcome.Idle, sessions.poll(OWNER, replacement, 0))
    }

    @Test
    fun `an ask is not rerouted after the lease of its session lapsed either`() {
        register("vault")
        val asking = askAsync("vault", "run-1", "deploy-token")
        awaitAsks(1)
        now.addAndGet(limits.leaseMillis + 1)
        val replacement = register("vault")

        assertEquals("provider-gone", assertIs<CredentialResolution.Refused>(asking.get(5, TimeUnit.SECONDS)).reason)
        assertEquals(CredentialPollOutcome.Idle, sessions.poll(OWNER, replacement, 0))
    }

    // ---------------------------------------------------------------- the run ends

    @Test
    fun `releasing a run fails its pending asks and leaves the asks of other runs alone`() {
        val session = register("vault")
        val ending = askAsync("vault", "run-1", "deploy-token")
        awaitAsks(1)
        val staying = askAsync("vault", "run-2", "deploy-token")
        awaitAsks(2)

        sessions.release(OWNER, "run-1")

        assertEquals("run-ended", assertIs<CredentialResolution.Refused>(ending.get(5, TimeUnit.SECONDS)).reason)
        assertEquals(1, sessions.pendingAsks())
        val asked = assertIs<CredentialPollOutcome.Asked>(sessions.poll(OWNER, session, 0))
        assertEquals("run-2", asked.execution)
        sessions.answer(OWNER, session, asked.requestId, true, SECRET.copyOf())
        assertIs<CredentialResolution.Granted>(staying.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun `another owner cannot release the asks of a run it did not start`() {
        register("vault")
        val asking = askAsync("vault", "run-1", "deploy-token")
        awaitAsks(1)

        sessions.release(OTHER, "run-1")

        assertEquals(1, sessions.pendingAsks())
        assertFalse(asking.isDone)
        asking.cancel(true)
    }

    @Test
    fun `a session holds a bounded number of pending asks and the rest are refused busy`() {
        register("vault")
        val first = askAsync("vault", "run-1", "a")
        awaitAsks(1)
        val second = askAsync("vault", "run-1", "b")
        awaitAsks(2)

        val refused = assertIs<CredentialResolution.Refused>(sessions.ask(OWNER, "vault", "run-1", "c", "string"))

        assertEquals("provider-busy", refused.reason)
        assertEquals(2, sessions.pendingAsks())
        first.cancel(true)
        second.cancel(true)
    }

    // ---------------------------------------------------------------- polling

    @Test
    fun `a poll with nothing to ask is idle and a poll of an unknown or foreign session is refused`() {
        val session = register("vault")

        assertEquals(CredentialPollOutcome.Idle, sessions.poll(OWNER, session, 0))
        assertEquals("provider-session-unknown", assertIs<CredentialPollOutcome.Refused>(sessions.poll(OWNER, "nope", 0)).reason)
        assertEquals("provider-session-unknown", assertIs<CredentialPollOutcome.Refused>(sessions.poll(OTHER, session, 0)).reason)
    }

    @Test
    fun `a poll that waits is woken by an ask`() {
        val session = register("vault")
        val polling = CompletableFuture.supplyAsync({ sessions.poll(OWNER, session, limits.maxPollMillis) }, executor)
        awaitWaitingPolls(1)

        val asking = askAsync("vault", "run-1", "deploy-token")

        val asked = assertIs<CredentialPollOutcome.Asked>(polling.get(5, TimeUnit.SECONDS))
        assertEquals("deploy-token", asked.credentialId)
        asking.cancel(true)
    }

    @Test
    fun `a session has at most one poll waiting and the second is refused without disturbing the first`() {
        val session = register("vault")
        val first = CompletableFuture.supplyAsync({ sessions.poll(OWNER, session, limits.maxPollMillis) }, executor)
        awaitWaitingPolls(1)

        val second = assertIs<CredentialPollOutcome.Refused>(sessions.poll(OWNER, session, 0))

        assertEquals("poll-already-waiting", second.reason)
        assertEquals(1, sessions.waitingPolls())
        now.addAndGet(limits.maxPollMillis + 1)
        assertEquals(CredentialPollOutcome.Idle, first.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun `a polling provider keeps its lease and a silent one loses it`() {
        val session = register("vault")
        now.addAndGet(limits.leaseMillis - 100)
        sessions.poll(OWNER, session, 0)
        now.addAndGet(limits.leaseMillis - 100)
        assertEquals(1, sessions.liveSessions(), "a poll renewed the lease")

        now.addAndGet(limits.leaseMillis + 1)

        assertEquals(0, sessions.liveSessions(), "a silent session lapsed")
    }

    // ---------------------------------------------------------------- helpers

    private fun register(provider: String, owner: String = OWNER): String =
        assertIs<ProviderRegistrationOutcome.Registered>(sessions.register(owner, provider)).sessionId

    private fun askAsync(provider: String, execution: String, credentialId: String): CompletableFuture<CredentialResolution> =
        CompletableFuture.supplyAsync({ sessions.ask(OWNER, provider, execution, credentialId, "string") }, executor)

    private fun awaitAsks(count: Int) {
        val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (sessions.pendingAsks() != count) {
            if (System.nanoTime() > limit) fail<Unit>("expected $count pending asks, saw ${sessions.pendingAsks()}")
            Thread.sleep(2)
        }
    }

    private fun awaitWaitingPolls(count: Int) {
        val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (sessions.waitingPolls() != count) {
            if (System.nanoTime() > limit) fail<Unit>("expected $count waiting polls, saw ${sessions.waitingPolls()}")
            Thread.sleep(2)
        }
    }

    private inline fun <reified T : Any> assertIs(actual: Any?, message: String? = null): T =
        assertInstanceOf(T::class.java, actual, message)

    private companion object {
        const val OWNER = "owner-digest-a"
        const val OTHER = "owner-digest-b"
        val SECRET = "s3cr3t-deploy-token-value".toByteArray(Charsets.UTF_8)
    }
}
