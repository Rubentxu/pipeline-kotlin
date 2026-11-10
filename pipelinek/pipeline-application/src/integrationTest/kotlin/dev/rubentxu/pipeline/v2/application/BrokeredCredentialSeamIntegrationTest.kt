package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.credentials.api.SecretStoreTamperException
import dev.rubentxu.pipeline.v2.credentials.executor.BoundCredentials
import dev.rubentxu.pipeline.v2.credentials.executor.SecretMaskingRefusedException
import dev.rubentxu.pipeline.v2.credentials.executor.SecretMaskingStrictness
import dev.rubentxu.pipeline.v2.credentials.executor.WithCredentialsExecutor
import dev.rubentxu.pipeline.v2.credentials.spi.CredentialProvider
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.credentials.Credential
import dev.rubentxu.pipeline.v2.domain.credentials.SecretText
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.dsl.StageSpec
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import dev.rubentxu.pipeline.v2.events.CredentialBound
import dev.rubentxu.pipeline.v2.events.CredentialUnbound
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSink
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A step that enters `withCredentials` in a run that was given a broker, with the broker of that
 * run answering for real.
 *
 * ## The guarantees under trial
 *
 *  - the run holds no secret until the scope is entered: composing the seam asks the store
 *    nothing, and entering the scope asks it for exactly what the step binds;
 *  - the step receives the secret, the output of the run masks it, and when the scope closes the
 *    handle is wiped and the credential is reported unbound;
 *  - a credential the broker does not grant, or does not hold, or cannot be reached for, stops
 *    the scope BEFORE the body: the cause is named, no byte of any secret is in the words, the
 *    store behind the broker is not asked for what was not granted, and nothing falls back to
 *    another store;
 *  - a run cannot resolve what was granted to another run;
 *  - the strict masking posture still refuses a secret too short to be masked;
 *  - a binding that needs more than a text credential fails by name and writes nothing.
 *
 * ## Why this is an INTEGRATION test
 *
 * The facts are about a socket that really binds, a token that really travels through a file the
 * run deletes, and a scope that really emits its events: composing the real executor over the real
 * provider is what these rows trial. The store behind the broker is a double.
 *
 * ## What this does not freeze
 *
 * The words of a refusal beyond what a run may disclose, and the processes of the same user, which
 * this lane does not claim to separate.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class BrokeredCredentialSeamIntegrationTest {

    @TempDir
    lateinit var directory: Path

    private val leases = CopyOnWriteArrayList<RunBrokerLease>()
    private val providers = CopyOnWriteArrayList<CredentialProvider>()

    @AfterEach
    fun giveBack() {
        providers.forEach { it.close() }
        leases.forEach { it.close() }
    }

    @Test
    fun `a step receives the secret on entering the scope and not before, and the scope gives it back`() {
        val store = StoreDouble(mapOf("deploy-key" to SECRET))
        val lease = open(setOf(grant("deploy-key")), store)
        val registry = SecretPatternRegistry()
        val sink = RecordingEventSink()

        val seam = seamOf(lease, "deploy-key", registry)
        assertTrue(store.asked.isEmpty(), "a run that has not entered the scope holds nothing")
        val bound = bind(seam, "deploy-key", sink)

        val handle = bound.env().getValue("API_KEY")
        assertArrayEquals(SECRET, handle.bytesView())
        assertEquals(listOf("deploy-key"), store.asked)
        assertEquals(
            listOf(CredentialsId("deploy-key")),
            sink.events.filterIsInstance<CredentialBound>().map { it.credentialsId },
        )
        val masked = registry.scrub("the step printed ${String(SECRET)} on its output")
        assertFalse(masked.contains(String(SECRET)), "the output of the run masks the secret")
        assertNotEquals("the step printed ${String(SECRET)} on its output", masked)
        assertFalse(directoryHolds(lease.directory, SECRET), "the secret was written nowhere in the lease")

        bound.close()

        assertTrue(handle.peek { bytes -> bytes.all { it == 0.toByte() } }, "the handle was wiped on closing")
        assertEquals(
            listOf(CredentialsId("deploy-key")),
            sink.events.filterIsInstance<CredentialUnbound>().map { it.credentialsId },
        )
        assertFalse(sink.events.toString().contains(String(SECRET)), "no event carries the secret")
    }

    @Test
    fun `a lease that grants several credentials hands out the one the scope asks for and no other`() {
        val store = StoreDouble(mapOf("first-key" to SECRET, "second-key" to OTHER_SECRET))
        val lease = open(setOf(grant("first-key"), grant("second-key")), store)
        val seam = seamOf(lease, "second-key", SecretPatternRegistry())

        val bound = bind(seam, "second-key", RecordingEventSink())

        assertArrayEquals(OTHER_SECRET, bound.env().getValue("API_KEY").bytesView())
        assertEquals(listOf("second-key"), store.asked, "only what the scope asked for reached the store")
        bound.close()
    }

    @Test
    fun `a credential that was not granted stops the scope before the body and the store is never asked`() {
        val store = StoreDouble(mapOf("deploy-key" to SECRET, "other" to OTHER_SECRET))
        val lease = open(setOf(grant("deploy-key")), store)
        val registry = SecretPatternRegistry()
        val sink = RecordingEventSink()
        val seam = seamOf(lease, "other", registry)

        val failure = enteredScope { bind(seam, "other", sink) }

        assertEquals(CredentialBrokerRefusal.CREDENTIAL_NOT_GRANTED, failure.refusal)
        assertEquals("other", failure.credentialId)
        assertTrue(store.asked.isEmpty(), "what was not granted never reached the store, and no other store answers")
        assertEquals(0, registry.size(), "no secret was registered because none was delivered")
        assertFalse(failure.message.orEmpty().contains(String(OTHER_SECRET)))
        assertFalse(failure.message.orEmpty().contains(lease.directory.toString()))
        assertEquals(
            sink.events.filterIsInstance<CredentialBound>().map { it.credentialsId },
            sink.events.filterIsInstance<CredentialUnbound>().map { it.credentialsId },
            "every credential reported bound is reported unbound, as it is with the local store",
        )
    }

    @Test
    fun `a granted credential the store does not hold stops the scope by name and says nothing the store said`() {
        val lease = open(setOf(grant("deploy-key")), StoreDouble(emptyMap()))
        val seam = seamOf(lease, "deploy-key", SecretPatternRegistry())

        val failure = enteredScope { bind(seam, "deploy-key", RecordingEventSink()) }

        assertEquals(CredentialBrokerRefusal.CREDENTIAL_NOT_AVAILABLE, failure.refusal)
        assertFalse(failure.message.orEmpty().contains("not held"), "what the store said stays in the store")
    }

    @Test
    fun `a broker that is gone stops the scope before the body and names the disconnection`() {
        val store = StoreDouble(mapOf("deploy-key" to SECRET))
        val lease = open(setOf(grant("deploy-key")), store)
        val seam = seamOf(lease, "deploy-key", SecretPatternRegistry())
        lease.close()

        val failure = enteredScope { bind(seam, "deploy-key", RecordingEventSink()) }

        assertEquals(CredentialBrokerRefusal.BROKER_UNREACHABLE, failure.refusal)
        assertTrue(store.asked.isEmpty())
        assertFalse(failure.message.orEmpty().contains(lease.directory.toString()))
    }

    @Test
    fun `a client the service does not know is granted nothing`() {
        val store = StoreDouble(mapOf("deploy-key" to SECRET))
        val factory = RunBrokerFactory(root(), store) { digest ->
            if (digest == "digest-known") setOf(grant("deploy-key")) else emptySet()
        }
        val known = factory.open("digest-known").also { leases += it }
        val stranger = factory.open("digest-unknown").also { leases += it }

        val delivered = bind(seamOf(known, "deploy-key", SecretPatternRegistry()), "deploy-key", RecordingEventSink())
        val failure = enteredScope {
            bind(seamOf(stranger, "deploy-key", SecretPatternRegistry()), "deploy-key", RecordingEventSink())
        }

        assertArrayEquals(SECRET, delivered.env().getValue("API_KEY").bytesView())
        assertEquals(CredentialBrokerRefusal.CREDENTIAL_NOT_GRANTED, failure.refusal)
        assertEquals(listOf("deploy-key"), store.asked, "only the known client's request reached the store")
    }

    @Test
    fun `runs entering their scopes at the same time each receive their own secret and cannot ask for the other`() {
        val store = StoreDouble(mapOf("a-key" to SECRET, "b-key" to OTHER_SECRET))
        val first = open(setOf(grant("a-key")), store)
        val second = open(setOf(grant("b-key")), store)
        val firstSeam = seamOf(first, "a-key", SecretPatternRegistry())
        val secondSeam = seamOf(second, "b-key", SecretPatternRegistry())
        val pool = Executors.newFixedThreadPool(8)
        try {
            val jobs = (0 until 24).map { index ->
                pool.submit<Boolean> {
                    val own = if (index % 2 == 0) firstSeam else secondSeam
                    val id = if (index % 2 == 0) "a-key" else "b-key"
                    val expected = if (index % 2 == 0) SECRET else OTHER_SECRET
                    val bound = bind(own, id, RecordingEventSink())
                    try {
                        bound.env().getValue("API_KEY").bytesView().contentEquals(expected)
                    } finally {
                        bound.close()
                    }
                }
            }

            assertTrue(jobs.all { it.get(60, TimeUnit.SECONDS) }, "every run got its own secret and no other")
        } finally {
            pool.shutdownNow()
        }
        val failure = enteredScope { bind(firstSeam, "b-key", RecordingEventSink()) }
        assertEquals(CredentialBrokerRefusal.CREDENTIAL_NOT_GRANTED, failure.refusal, "a run cannot ask for the grants of another")
    }

    @Test
    fun `the strict posture refuses a secret too short to be masked before the body and names no byte of it`() {
        val tooShort = "q7!".toByteArray()
        val lease = open(setOf(grant("short-key")), StoreDouble(mapOf("short-key" to tooShort)))
        val seam = seamOf(lease, "short-key", SecretPatternRegistry())

        val failure = assertThrows<SecretMaskingRefusedException> { bind(seam, "short-key", RecordingEventSink()) }

        assertFalse(failure.message.orEmpty().contains(String(tooShort)), "the words name the credential and the length")
        assertTrue(failure.message.orEmpty().contains("short-key"))
    }

    @Test
    fun `a binding that needs more than a text credential fails by name, writes nothing and discloses no byte`() {
        val store = StoreDouble(mapOf("login" to SECRET))
        val lease = open(setOf(grant("login")), store)
        val registry = SecretPatternRegistry()
        val seam = seamOf(lease, "login", registry)
        val sink = RecordingEventSink()
        val login = StepSpec.CredentialsBinding.usernamePassword("login", "USR", "PSW")

        val failure = assertThrows<IllegalStateException> {
            runBlocking { executorOf(seam).bind(listOf(login), "run-1", sink) }
        }

        assertTrue(failure.message.orEmpty().contains("login"), "the words name the credential: $failure")
        assertFalse(failure.toString().contains(String(SECRET)), "no secret in the words: $failure")
        assertEquals(setOf(RunBrokerLease.SOCKET_FILE_NAME), names(lease.directory), "nothing was written into the lease")
        assertEquals(0, registry.size())
        assertFalse(sink.events.toString().contains(String(SECRET)))
    }

    private fun root(): Path = directory.resolve("b")

    private fun open(grants: Set<CredentialGrant>, store: CredentialProvider?): RunBrokerLease =
        RunBrokerLease.open(root(), grants, store).also { leases += it }

    /** The seam of a run that binds [credential], over the broker in the directory of [lease]. */
    private fun seamOf(lease: RunBrokerLease, credential: String, registry: SecretPatternRegistry): CredentialSeam {
        val spec = PipelineSpec(
            stages = listOf(
                StageSpec(
                    name = "stage",
                    steps = listOf(
                        StepSpec.WithCredentialsBlock(
                            credentialsId = CredentialsId(credential),
                            purpose = "a secret the step reads",
                            bindings = listOf(StepSpec.CredentialsBinding.string(credential, "API_KEY")),
                            steps = emptyList(),
                        ),
                    ),
                ),
            ),
        )
        return composeCredentialSeam(registry, FIXED_CLOCK, spec, SecretMaskingStrictness.STRICT, lease.directory)
            .also { seam -> seam.provider?.let { providers += it } }
    }

    /** Enters the scope of [credential] as a step would, and returns what the scope holds. */
    private fun bind(seam: CredentialSeam, credential: String, sink: EventSink): BoundCredentials =
        runBlocking {
            executorOf(seam).bind(listOf(StepSpec.CredentialsBinding.string(credential, "API_KEY")), "run-1", sink)
        }

    private fun executorOf(seam: CredentialSeam): WithCredentialsExecutor =
        requireNotNull(seam.executor) { "a run that binds a credential holds an executor" }

    /** Runs [entering] expecting the scope to be refused by the broker, and returns the refusal. */
    private fun enteredScope(entering: () -> BoundCredentials): CredentialBrokerRefusedException =
        assertThrows<CredentialBrokerRefusedException> { entering() }

    private fun grant(id: String) = CredentialGrant(id, BrokeredCredentialKind.STRING)

    private fun names(directory: Path): Set<String> =
        Files.list(directory).use { stream -> stream.map { it.fileName.toString() }.toList().toSet() }

    /** Whether any regular file below [directory] holds [needle] as a run of bytes. */
    private fun directoryHolds(directory: Path, needle: ByteArray): Boolean =
        Files.walk(directory).use { stream ->
            stream.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .anyMatch { indexOf(Files.readAllBytes(it), needle) >= 0 }
        }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        for (start in 0..haystack.size - needle.size) {
            if (needle.indices.all { haystack[start + it] == needle[it] }) return start
        }
        return -1
    }

    /** An event sink that keeps whatever it is given. */
    private class RecordingEventSink : EventSink {
        val events: MutableList<DomainEvent> = CopyOnWriteArrayList()

        override fun append(event: DomainEvent) {
            events += event
        }

        override fun eventsFor(runId: String): Sequence<DomainEvent> = events.asSequence()
    }

    /** A store that holds bytes by name and records what it was asked. */
    private class StoreDouble(private val secrets: Map<String, ByteArray>) : CredentialProvider {
        val asked: MutableList<String> = CopyOnWriteArrayList()

        override val providerId: String = "store-double"

        override fun resolve(id: CredentialsId): SecretHandle = error("the broker resolves credentials, not handles")

        override fun resolveToCredential(id: CredentialsId): Credential {
            asked += id.value
            val bytes = secrets[id.value] ?: throw SecretStoreTamperException("not held")
            return SecretText(id, bytes = bytes.copyOf())
        }

        override fun close() = Unit
    }

    private companion object {
        val SECRET: ByteArray = "s3cr3t-of-the-first".toByteArray()
        val OTHER_SECRET: ByteArray = "s3cr3t-of-the-second".toByteArray()
        val FIXED_CLOCK: Clock = object : Clock {
            override fun now(): Instant = Instant.EPOCH
        }
    }
}
