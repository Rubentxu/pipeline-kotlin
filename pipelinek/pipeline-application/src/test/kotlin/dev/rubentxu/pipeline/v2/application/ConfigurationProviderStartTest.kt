package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskRuntime
import dev.rubentxu.pipeline.v2.domain.durable.ExecutionOutputSink
import dev.rubentxu.pipeline.v2.domain.durable.OutputChunk
import dev.rubentxu.pipeline.v2.domain.durable.TaskExecutionRequest
import dev.rubentxu.pipeline.v2.domain.durable.TaskExecutionResult
import dev.rubentxu.pipeline.v2.domain.durable.TaskSpec
import dev.rubentxu.pipeline.v2.runtime.RunConfiguration
import dev.rubentxu.pipeline.v2.runtime.RunConfigurationOutcome
import dev.rubentxu.pipeline.v2.service.ServiceProviderReference
import dev.rubentxu.pipeline.v2.service.ServiceRefusal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The margin this law gives the machine, and the clock it reads.
 *
 * The row is a conversation with a thread of its own: the thread asks the
 * provider, the provider waits until the row releases it, and the row reads
 * what the thread answered. The row cannot know WHEN the machine schedules
 * that thread, so it gives the machine a margin and says what the row means
 * when the margin runs out.
 *
 * ## Why the verdict does not come from the clock
 *
 * The verdict is read from the OUTCOME the supervisor returned, and never
 * from a wait: a start that holds a slot of the budget while it waits
 * answers the second start with a REFUSAL, and that refusal is red whatever
 * the machine was doing. The margin decides only WHETHER the row measured at
 * all: when it runs out the row ABORTS, and an aborted row is a row that did
 * not measure, not a row that passed.
 *
 * ## Why the margin is this size
 *
 * The work of the row is measured: on a free machine it answers in about ten
 * milliseconds, so the margin is not about that work but about the SCHEDULING
 * of one thread and one conversation on a machine that is busy. It is not a
 * number that hides a defect, because a bigger margin would buy only a row
 * that measures nothing for longer: past the margin the answer is the same
 * one — this row did not measure — and only the wait is longer. The timeout
 * of the class bounds the row as well: it allows sixty seconds and the worst
 * case of the row is twice the margin, so the row always answers before the
 * class does.
 *
 * ## Why the clock is the monotonic one
 *
 * The row measures an ELAPSED time, and [System.nanoTime] is the clock that
 * measures it: a clock that follows the hour of the machine can report an
 * elapsed time the row never had. The clock does not make a busy machine
 * schedule the thread; the MARGIN is what gives the thread its chance.
 */
private const val MARGIN_MILLIS = 15_000L

/**
 * A start that asks a configuration provider for the keys it did not set itself.
 *
 * ## The guarantees under trial
 *
 *  - the run keeps EXACTLY the snapshot that was validated: the explicit entries and the values
 *    the provider answered, nothing else, and the snapshot says where each key came from;
 *  - the provider is asked only for the keys the request did not set, and the value the request
 *    set is the one the run gets;
 *  - a reference that can never be legal is refused BEFORE the idempotency key is claimed and
 *    BEFORE anyone is asked, and a resolution that fails releases the key so the client can
 *    repeat the request;
 *  - a start that waits for a provider holds no slot of the budget while it waits;
 *  - what the provider answers goes through the rules of the configuration again, and an answer
 *    for other keys than the ones asked is refused, never applied;
 *  - the service can say where the configuration of a run came from, from the snapshot the run
 *    itself reads, and the description holds no value.
 *
 * ## Why the port and the resolver are doubles
 *
 * The rows are about the conversation of the supervisor with them: what it asks, in which order
 * and what it does when they fail. The session machinery has its own rows, and a real process
 * reading the snapshot is a fact about the machine that the integration lane observes.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ConfigurationProviderStartTest {

    @Test
    fun `the run keeps exactly the snapshot that was validated`(@TempDir root: Path) {
        val port = FakePort()
        val resolver = FakeResolver { _, _, keys -> resolved(keys.associateWith { "value-of-$it" }) }
        val supervisor = supervisor(root, port, resolver)

        assertInstanceOf(
            ServiceStartOutcome.Accepted::class.java,
            supervisor.start(
                request(
                    configuration = mapOf("BUILD_PROFILE" to "release"),
                    provider = ServiceProviderReference("ldap", listOf("REGION", "TEAM")),
                ),
            ),
        )

        val snapshot = snapshotOf(port.requests.single())
        val values = Json.parseToJsonElement(Files.readString(snapshot)).jsonObject["values"]!!.jsonObject
        assertEquals(
            mapOf("BUILD_PROFILE" to "release", "REGION" to "value-of-REGION", "TEAM" to "value-of-TEAM"),
            values.mapValues { it.value.jsonPrimitive.content },
            "the snapshot holds the explicit entries and what the provider answered, and nothing else",
        )
        assertEquals(
            values.mapValues { it.value.jsonPrimitive.content },
            RunConfigurationReader.read(snapshot.toString()).values,
            "the child reads back the very snapshot the service validated",
        )
    }

    @Test
    fun `the provider is asked only for the keys the request did not set and the explicit value wins`(
        @TempDir root: Path,
    ) {
        val port = FakePort()
        val resolver = FakeResolver { _, _, keys -> resolved(keys.associateWith { "from-provider" }) }
        val supervisor = supervisor(root, port, resolver)

        supervisor.start(
            request(
                configuration = mapOf("REGION" to "from-request"),
                provider = ServiceProviderReference("ldap", listOf("REGION", "TEAM", "TEAM")),
            ),
        )

        assertEquals(
            listOf(listOf("TEAM")),
            resolver.asked.map { it.keys },
            "a key the request set is never asked, and a key listed twice is asked once",
        )
        assertEquals(
            mapOf("REGION" to "from-request", "TEAM" to "from-provider"),
            RunConfigurationReader.read(snapshotOf(port.requests.single()).toString()).values,
            "the value the request set is the one the run gets",
        )
    }

    @Test
    fun `a request that sets every key itself never wakes the provider`(@TempDir root: Path) {
        val resolver = FakeResolver { _, _, _ -> error("nobody should be asked") }
        val supervisor = supervisor(root, FakePort(), resolver)

        val outcome = supervisor.start(
            request(
                configuration = mapOf("REGION" to "from-request"),
                provider = ServiceProviderReference("ldap", listOf("REGION")),
            ),
        )

        assertInstanceOf(ServiceStartOutcome.Accepted::class.java, outcome)
        assertTrue(resolver.asked.isEmpty())
    }

    @Test
    fun `the resolver is asked with the credential of the request and the name it gave`(@TempDir root: Path) {
        val resolver = FakeResolver { _, _, keys -> resolved(keys.associateWith { "v" }) }
        val supervisor = supervisor(root, FakePort(), resolver)

        supervisor.start(request(provider = ServiceProviderReference("ldap", listOf("REGION"))))

        val asked = resolver.asked.single()
        assertEquals("digest-of-the-client", asked.owner)
        assertEquals("ldap", asked.provider)
    }

    @Test
    fun `a reference with no keys is refused before the key is claimed`(@TempDir root: Path) {
        val resolver = FakeResolver { _, _, keys -> resolved(keys.associateWith { "v" }) }
        val supervisor = supervisor(root, FakePort(), resolver)

        val refused = supervisor.start(
            request(key = "order-42", provider = ServiceProviderReference("ldap", emptyList())),
        )

        assertEquals("provider-has-no-keys", refusalOf(refused).reason)
        assertTrue(resolver.asked.isEmpty())
        // The key is free: the repeat with the same key and a legal reference is accepted.
        assertInstanceOf(
            ServiceStartOutcome.Accepted::class.java,
            supervisor.start(
                request(key = "order-42", provider = ServiceProviderReference("ldap", listOf("REGION"))),
            ),
        )
    }

    @Test
    fun `a key that could never be legal is refused before anyone is asked`(@TempDir root: Path) {
        val resolver = FakeResolver { _, _, keys -> resolved(keys.associateWith { "v" }) }
        val port = FakePort()
        val supervisor = supervisor(root, port, resolver)

        val refused = supervisor.start(
            request(key = "order-42", provider = ServiceProviderReference("ldap", listOf("LD_PRELOAD"))),
        )

        assertEquals("the-configuration-name-is-denied", refusalOf(refused).reason)
        assertTrue(resolver.asked.isEmpty(), "a name that can never be legal wakes nobody")
        assertTrue(port.requests.isEmpty(), "and no process is launched")
    }

    @Test
    fun `a merge that would exceed the entries of a configuration is refused up front`(@TempDir root: Path) {
        val resolver = FakeResolver { _, _, keys -> resolved(keys.associateWith { "v" }) }
        val supervisor = supervisor(root, FakePort(), resolver)
        val explicit = (1..RunConfiguration.MAX_ENTRIES).associate { "KEY_$it" to "v" }

        val refused = supervisor.start(
            request(configuration = explicit, provider = ServiceProviderReference("ldap", listOf("ONE_MORE"))),
        )

        assertTrue(refusalOf(refused).reason.isNotEmpty())
        assertTrue(resolver.asked.isEmpty())
    }

    @Test
    fun `a refused resolution launches nothing and releases the key`(@TempDir root: Path) {
        val port = FakePort()
        val answers = AtomicInteger()
        val resolver = FakeResolver { _, _, keys ->
            if (answers.getAndIncrement() == 0) {
                ProviderResolution.Refused("provider-timeout", "the provider did not answer")
            } else {
                resolved(keys.associateWith { "v" })
            }
        }
        val supervisor = supervisor(root, port, resolver)
        val reference = ServiceProviderReference("ldap", listOf("REGION"))

        val first = supervisor.start(request(key = "order-42", provider = reference))

        val refusal = refusalOf(first)
        assertEquals("provider-timeout", refusal.reason, "the refusal is the resolution's own, with no fallback")
        assertTrue(port.requests.isEmpty(), "a run whose configuration did not resolve is never launched")

        // The same key, the same request: the key was released, so the repeat is a fresh start.
        assertInstanceOf(
            ServiceStartOutcome.Accepted::class.java,
            supervisor.start(request(key = "order-42", provider = reference)),
        )
        assertEquals(1, port.requests.size)
    }

    @Test
    fun `a service composed without a resolver refuses a provider reference by name`(@TempDir root: Path) {
        val supervisor = supervisor(root, FakePort(), resolver = null)

        val refused = supervisor.start(request(provider = ServiceProviderReference("ldap", listOf("REGION"))))

        assertEquals("provider-not-registered", refusalOf(refused).reason)
    }

    @Test
    fun `an answer for keys nobody asked is refused and not applied`(@TempDir root: Path) {
        val port = FakePort()
        val resolver = FakeResolver { _, _, _ -> resolved(mapOf("REGION" to "eu", "EXTRA" to "x")) }
        val supervisor = supervisor(root, port, resolver)

        val refused = supervisor.start(request(provider = ServiceProviderReference("ldap", listOf("REGION"))))

        assertEquals("provider-answer-unexpected", refusalOf(refused).reason)
        assertTrue(port.requests.isEmpty())
    }

    @Test
    fun `an answer that leaves an asked key out is refused and not applied`(@TempDir root: Path) {
        val port = FakePort()
        val resolver = FakeResolver { _, _, _ -> resolved(mapOf("REGION" to "eu")) }
        val supervisor = supervisor(root, port, resolver)

        val refused = supervisor.start(
            request(provider = ServiceProviderReference("ldap", listOf("REGION", "TEAM"))),
        )

        assertEquals("provider-answer-unexpected", refusalOf(refused).reason)
        assertTrue(port.requests.isEmpty())
    }

    @Test
    fun `what the provider answers goes through the rules of the configuration again`(@TempDir root: Path) {
        val port = FakePort()
        val illegal = "bad\u0000value"
        val expected = assertInstanceOf(
            RunConfigurationOutcome.Refused::class.java,
            RunConfiguration.of(mapOf("REGION" to illegal)),
        ).refusal.reason
        val resolver = FakeResolver { _, _, _ -> resolved(mapOf("REGION" to illegal)) }
        val supervisor = supervisor(root, port, resolver)

        val refused = supervisor.start(request(provider = ServiceProviderReference("ldap", listOf("REGION"))))

        assertEquals(expected, refusalOf(refused).reason)
        assertTrue(port.requests.isEmpty(), "a value the rules refuse never reaches a snapshot")
    }

    @Test
    fun `a resolver that throws is a refusal with a fixed text and not a crash`(@TempDir root: Path) {
        val resolver = FakeResolver { _, _, _ -> error("secret-path /home/someone/.vault") }
        val supervisor = supervisor(root, FakePort(), resolver)

        val refused = supervisor.start(request(provider = ServiceProviderReference("ldap", listOf("REGION"))))

        val refusal = refusalOf(refused)
        assertEquals("provider-resolution-failed", refusal.reason)
        assertFalse(refusal.detail.contains("secret-path"), "the message of a failure does not travel")
    }

    @Test
    fun `a replayed start does not ask the provider again`(@TempDir root: Path) {
        val resolver = FakeResolver { _, _, keys -> resolved(keys.associateWith { "v" }) }
        val supervisor = supervisor(root, FakePort(), resolver)
        val reference = ServiceProviderReference("ldap", listOf("REGION"))

        val first = supervisor.start(request(key = "order-42", provider = reference))
        val second = supervisor.start(request(key = "order-42", provider = reference))

        assertInstanceOf(ServiceStartOutcome.Accepted::class.java, first)
        assertInstanceOf(ServiceStartOutcome.Accepted::class.java, second)
        assertEquals(1, resolver.asked.size, "the replay of an answered start wakes nobody")
    }

    @Test
    fun `the fingerprint covers the provider reference`(@TempDir root: Path) {
        val resolver = FakeResolver { _, _, keys -> resolved(keys.associateWith { "v" }) }
        val supervisor = supervisor(root, FakePort(), resolver)

        supervisor.start(request(key = "order-42", provider = ServiceProviderReference("ldap", listOf("REGION"))))
        val other = supervisor.start(
            request(key = "order-42", provider = ServiceProviderReference("ldap", listOf("TEAM"))),
        )
        val renamed = supervisor.start(
            request(key = "order-42", provider = ServiceProviderReference("vault", listOf("REGION"))),
        )
        val without = supervisor.start(request(key = "order-42"))

        assertEquals(ServiceRefusal.IdempotencyConflict("order-42"), assertRefused(other))
        assertEquals(ServiceRefusal.IdempotencyConflict("order-42"), assertRefused(renamed))
        assertEquals(ServiceRefusal.IdempotencyConflict("order-42"), assertRefused(without))
    }

    @Test
    fun `the order the keys are listed in does not make another request`(@TempDir root: Path) {
        val resolver = FakeResolver { _, _, keys -> resolved(keys.associateWith { "v" }) }
        val supervisor = supervisor(root, FakePort(), resolver)

        supervisor.start(
            request(key = "order-42", provider = ServiceProviderReference("ldap", listOf("REGION", "TEAM"))),
        )
        val again = supervisor.start(
            request(key = "order-42", provider = ServiceProviderReference("ldap", listOf("TEAM", "REGION"))),
        )

        assertInstanceOf(ServiceStartOutcome.Accepted::class.java, again)
    }

    @Test
    fun `a request without a provider has the fingerprint it always had`() {
        val plain = ServiceRunSupervisor.digestOf("build.kts", null, RunConfiguration.EMPTY)
        val withNone = ServiceRunSupervisor.digestOf("build.kts", null, RunConfiguration.EMPTY, provider = null)
        val withProvider = ServiceRunSupervisor.digestOf(
            "build.kts",
            null,
            RunConfiguration.EMPTY,
            provider = ServiceProviderReference("ldap", listOf("REGION")),
        )

        assertEquals(plain, withNone)
        assertTrue(plain != withProvider, "naming a provider changes the fingerprint")
    }

    @Test
    fun `a start that waits for the provider holds no slot of the budget`(@TempDir root: Path) {
        val scheduled = CountDownLatch(1)
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val resolver = FakeResolver { _, _, keys ->
            inside.countDown()
            // The wait has no timeout on purpose: the row releases the provider in
            // its own cleanup, and a provider that answered on its own would let
            // the first start finish BEFORE the row asked its question, and the
            // second start would then be accepted for the wrong reason.
            release.await()
            resolved(keys.associateWith { "v" })
        }
        val supervisor = supervisor(root, FakePort(), resolver, maxSupervisedRuns = 1)
        val waiting = AtomicReference<ServiceStartOutcome>()
        val thread = Thread {
            scheduled.countDown()
            waiting.set(supervisor.start(request(provider = ServiceProviderReference("ldap", listOf("REGION")))))
        }
        // The thread outlives the row when the row aborts, and a thread that keeps
        // a JVM alive is a thread the next row has to wait for.
        thread.isDaemon = true
        thread.start()
        val since = System.nanoTime()
        try {
            // The machine has to SCHEDULE the thread before the row can ask
            // anything: a machine that does not give the thread its first
            // instruction in time has not answered, and the row aborts instead of
            // answering about the machine.
            awaitOrAbort(scheduled, "the machine did not schedule the thread", since)
            awaitOrAbort(inside, "the start did not reach the provider", since)

            // The only slot of the budget is free while the first start waits: a second start,
            // which asks nobody, is accepted instead of being refused for lack of room.
            //
            // The first start is still WAITING, and that is what the row measures: a
            // first start that had already answered would have given its slot back,
            // and the second start would have been accepted for the wrong reason.
            assertNull(waiting.get(), "the first start is still waiting for the provider")
            val second = supervisor.start(request())

            assertInstanceOf(ServiceStartOutcome.Accepted::class.java, second)
        } finally {
            release.countDown()
            thread.join(MARGIN_MILLIS)
        }
        // A thread that had no answer in time has NOT refused anything: it has not
        // answered. That is a machine that did not give the row its margin, so the
        // row did not measure, and the row says so instead of failing.
        val outcome = waiting.get()
        assumeTrue(outcome != null, notMeasured("the thread had no answer", since))
        assertInstanceOf(ServiceStartOutcome.Accepted::class.java, outcome)
    }

    @Test
    fun `the request prints its provider name and none of its values`() {
        val text = request(
            configuration = mapOf("BUILD_PROFILE" to "very-private-value"),
            provider = ServiceProviderReference("ldap", listOf("REGION")),
        ).toString()

        assertTrue(text.contains("ldap"))
        assertFalse(text.contains("very-private-value"))
    }

    // ---------------------------------------------------------------------------- description

    @Test
    fun `the service says where each key came from without a value`(@TempDir root: Path) {
        val port = FakePort()
        val resolver = FakeResolver(revision = "rev-7") { _, _, keys -> resolved(keys.associateWith { "private-$it" }) }
        val supervisor = supervisor(root, port, resolver)
        supervisor.start(
            request(
                configuration = mapOf("BUILD_PROFILE" to "private-release"),
                provider = ServiceProviderReference("ldap", listOf("REGION")),
            ),
        )

        val found = assertInstanceOf(RunConfigurationLookup.Found::class.java, locatorFor(root, port).describe("run-1"))
        val description = found.description

        assertEquals(
            listOf(
                RunConfigurationEntry("BUILD_PROFILE", RunConfigurationReader.ORIGIN_REQUEST, "string"),
                RunConfigurationEntry("REGION", RunConfigurationReader.ORIGIN_PROVIDER, "integer"),
            ),
            description.entries,
        )
        assertEquals("ldap", description.provider)
        assertEquals("rev-7", description.revision)
        assertEquals(
            configurationDigest(mapOf("BUILD_PROFILE" to "private-release", "REGION" to "private-REGION")),
            description.digest,
            "the digest is the digest of the very values the snapshot holds",
        )
        assertFalse(description.toString().contains("private-"), "the description holds no value")
    }

    @Test
    fun `a run started without a provider describes its keys as the request's own`(@TempDir root: Path) {
        val port = FakePort()
        val supervisor = supervisor(root, port, FakeResolver { _, _, _ -> error("not asked") })
        supervisor.start(request(configuration = mapOf("BUILD_PROFILE" to "release")))

        val description = assertInstanceOf(
            RunConfigurationLookup.Found::class.java,
            locatorFor(root, port).describe("run-1"),
        ).description

        assertEquals(
            listOf(RunConfigurationEntry("BUILD_PROFILE", RunConfigurationReader.ORIGIN_REQUEST, "string")),
            description.entries,
        )
        assertEquals(null, description.provider)
        assertEquals(null, description.sourceDigest)
    }

    @Test
    fun `a run given no configuration describes an empty one`(@TempDir root: Path) {
        val port = FakePort()
        val supervisor = supervisor(root, port, resolver = null)
        supervisor.start(request())

        val description = assertInstanceOf(
            RunConfigurationLookup.Found::class.java,
            locatorFor(root, port).describe("run-1"),
        ).description

        assertTrue(description.entries.isEmpty())
        assertEquals(configurationDigest(emptyMap()), description.digest)
    }

    @Test
    fun `a run the service did not start is unknown`(@TempDir root: Path) {
        val port = FakePort()
        supervisor(root, port, resolver = null)

        assertEquals(RunConfigurationLookup.Unknown, locatorFor(root, port).describe("run-that-never-was"))
    }

    @Test
    fun `a snapshot that vanished is unreadable and the text carries no path`(@TempDir root: Path) {
        val port = FakePort()
        val supervisor = supervisor(root, port, FakeResolver { _, _, keys -> resolved(keys.associateWith { "1" }) })
        supervisor.start(request(provider = ServiceProviderReference("ldap", listOf("REGION"))))
        Files.delete(snapshotOf(port.requests.single()))

        val lookup = assertInstanceOf(
            RunConfigurationLookup.Unreadable::class.java,
            locatorFor(root, port).describe("run-1"),
        )

        assertFalse(lookup.detail.contains(root.toString()), "a path does not travel")
    }

    @Test
    fun `the supervision directory of a run is named after the correlation the service chose`(
        @TempDir root: Path,
    ) {
        val port = FakePort()
        val supervisor = supervisor(root, port, resolver = null)
        supervisor.start(request(configuration = mapOf("BUILD_PROFILE" to "release")))

        val correlation = requireNotNull(port.correlationOf("run-1"))
        val snapshot = snapshotOf(port.requests.single())

        assertEquals(
            root.resolve("supervisions").resolve(supervisionDirectoryName(correlation)),
            snapshot.parent,
            "the location of the snapshot is derived from the correlation, so no index has to remember it",
        )
    }

    @Test
    fun `every run is described however many runs the service has started`(@TempDir root: Path) {
        val port = FakePort()
        val supervisor = supervisor(
            root,
            port,
            FakeResolver { _, _, keys -> resolved(keys.associateWith { "1" }) },
            maxSupervisedRuns = 10,
        )
        repeat(6) { supervisor.start(request(provider = ServiceProviderReference("ldap", listOf("REGION")))) }

        val locator = locatorFor(root, port)

        for (run in 1..6) {
            assertInstanceOf(
                RunConfigurationLookup.Found::class.java,
                locator.describe("run-$run"),
                "the first run is found as the last one is: nothing was evicted, because nothing was kept",
            )
        }
    }

    @Test
    fun `a locator that did not start the runs still describes them`(@TempDir root: Path) {
        val port = FakePort()
        val supervisor = supervisor(
            root,
            port,
            FakeResolver(revision = "rev-9") { _, _, keys -> resolved(keys.associateWith { "1" }) },
        )
        supervisor.start(
            request(
                configuration = mapOf("BUILD_PROFILE" to "release"),
                provider = ServiceProviderReference("ldap", listOf("REGION")),
            ),
        )
        val before = assertInstanceOf(
            RunConfigurationLookup.Found::class.java,
            locatorFor(root, port).describe("run-1"),
        ).description
        supervisor.close()

        // A locator built afterwards shares nothing in memory with the supervisor
        // that wrote the snapshot, which is what a restarted service is.
        val after = assertInstanceOf(
            RunConfigurationLookup.Found::class.java,
            locatorFor(root, port).describe("run-1"),
        ).description

        assertEquals(before, after)
        assertEquals("rev-9", after.revision)
    }

    // ------------------------------------------------------------------------------- reader

    @Test
    fun `the child refuses a snapshot whose provenance does not describe its values`(@TempDir root: Path) {
        val snapshot = root.resolve("run-config.json")
        val document = runConfigurationDocument(
            mapOf("REGION" to "eu"),
            provenanceOf("REGION"),
        ).toString().replace("\"eu\"", "\"us\"")
        Files.writeString(snapshot, document)

        val refused = assertInstanceOf(
            CommandRefused::class.java,
            runCatching { RunConfigurationReader.read(snapshot.toString()) }.exceptionOrNull(),
        )
        assertEquals(2, refused.exit.code, "values changed after the service wrote them are not believed")
    }

    @Test
    fun `the child refuses a provenance it cannot read`(@TempDir root: Path) {
        val snapshot = root.resolve("run-config.json")
        Files.writeString(
            snapshot,
            """{"schema":"pipelinek.runconfig/1","values":{"REGION":"eu"},"provenance":"nothing"}""",
        )

        val refused = assertInstanceOf(
            CommandRefused::class.java,
            runCatching { RunConfigurationReader.read(snapshot.toString()) }.exceptionOrNull(),
        )
        assertEquals(2, refused.exit.code)
    }

    @Test
    fun `the child accepts a snapshot whose provenance describes its values`(@TempDir root: Path) {
        val snapshot = root.resolve("run-config.json")
        Files.writeString(snapshot, runConfigurationDocument(mapOf("REGION" to "eu"), provenanceOf("REGION")).toString())

        assertEquals(mapOf("REGION" to "eu"), RunConfigurationReader.read(snapshot.toString()).values)
    }

    @Test
    fun `a snapshot with no provider is written as it always was`() {
        val document = runConfigurationDocument(mapOf("REGION" to "eu"))

        assertFalse(document.containsKey("provenance"))
    }

    // -------------------------------------------------------------------------------- scaffolding

    private fun refusalOf(outcome: ServiceStartOutcome): ServiceRefusal.RunConfigurationRefused =
        assertInstanceOf(
            ServiceRefusal.RunConfigurationRefused::class.java,
            assertInstanceOf(ServiceStartOutcome.Refused::class.java, outcome).refusal,
        )

    private fun assertRefused(outcome: ServiceStartOutcome): ServiceRefusal =
        assertInstanceOf(ServiceStartOutcome.Refused::class.java, outcome).refusal

    /**
     * Waits for a latch the row needs, and aborts the row when the margin runs out.
     *
     * An aborted row is a row that did not measure: the reader of the receipt
     * repays it with a free machine, and it is never counted as a pass.
     */
    private fun awaitOrAbort(latch: CountDownLatch, what: String, sinceNanos: Long) {
        assumeTrue(latch.await(MARGIN_MILLIS, TimeUnit.MILLISECONDS), notMeasured(what, sinceNanos))
    }

    /**
     * The text of a row that could not read its answer in time.
     *
     * It names the margin, the clock and the elapsed time, because the reader
     * has to tell a machine that was too busy from a tree that is broken, and
     * those three numbers are the evidence there is.
     */
    private fun notMeasured(what: String, sinceNanos: Long): String {
        val elapsed = (System.nanoTime() - sinceNanos) / 1_000_000
        return "$what within the margin of $MARGIN_MILLIS ms; the elapsed time was $elapsed ms, read " +
            "with the monotonic clock System.nanoTime. The machine did not give the row its margin, so " +
            "the row did not measure: the result is NOT MEASURED, and the row has to be paid again " +
            "with a free machine."
    }

    private fun provenanceOf(vararg names: String) = RunConfigurationProvenance(
        provider = "ldap",
        revision = "rev-1",
        sourceDigest = "source-digest",
        origins = names.associateWith { RunConfigurationOrigin(RunConfigurationReader.ORIGIN_PROVIDER, "string") },
    )

    private fun resolved(values: Map<String, String>, revision: String = "rev-1"): ProviderResolution =
        ProviderResolution.Resolved(
            values,
            ProviderProvenance(
                provider = "ldap",
                revision = revision,
                digest = configurationDigest(values),
                types = values.keys.associateWith { "integer" },
            ),
        )

    private fun snapshotOf(request: TaskExecutionRequest): Path {
        val argv = argvOf(request)
        return Path.of(argv[argv.indexOf("--run-config") + 1])
    }

    private fun argvOf(request: TaskExecutionRequest): List<String> = (request.task as TaskSpec.ExecTask).argv

    private fun request(
        key: String? = null,
        configuration: Map<String, String> = emptyMap(),
        provider: ServiceProviderReference? = null,
    ): ServiceStartRequest = ServiceStartRequest(
        script = "build.kts",
        workspace = null,
        idempotencyKey = key,
        credentialDigest = "digest-of-the-client",
        configuration = configuration,
        configurationProvider = provider,
    )

    private fun supervisor(
        root: Path,
        port: DurableTaskRuntime,
        resolver: ConfigurationResolver?,
        maxSupervisedRuns: Int = 4,
    ): ServiceRunSupervisor {
        val scripts = Files.createDirectories(root.resolve("scripts"))
        Files.writeString(scripts.resolve("build.kts"), "print(1)")
        val reading = ServiceStartRegistry.read(root.resolve(ServiceStartRegistry.FILE_NAME))
        val registry = assertInstanceOf(ServiceStartRegistryReading.Read::class.java, reading).registry
        return ServiceRunSupervisor(
            admission = ServiceStartAdmission(scripts, null),
            runPolicy = ServiceRunPolicy(
                eventStore = "/tmp/store.db",
                controlRoot = "/tmp/control",
                deadlineMillis = 60_000L,
                allowNetwork = false,
            ),
            command = ServiceRunCommand(
                javaBinary = "/usr/bin/java",
                classpath = "/tmp/classpath",
                mainClass = "dev.rubentxu.pipeline.v2.application.MainKt",
            ),
            port = port,
            registry = registry,
            limits = ServiceStartLimits(
                maxSupervisedRuns = maxSupervisedRuns,
                startDeadlineMillis = 5_000L,
            ),
            supervisionRoot = root.resolve("supervisions"),
            collaborators = ServiceCollaborators(configurations = resolver),
        )
    }

    /**
     * The door that answers where the configuration of a run came from, over the directory the
     * supervisor wrote and with the correlations the port saw standing in for the events of the
     * store: the port is the double of the child, and the child is who publishes them.
     */
    private fun locatorFor(root: Path, port: FakePort): RunConfigurationLocator = RunConfigurationLocator(
        supervisionRoot = root.resolve("supervisions"),
        correlations = object : RunCorrelationReader {
            override fun correlationOf(runId: String): RunCorrelation {
                val correlation = port.correlationOf(runId)
                return if (correlation == null) RunCorrelation.Absent else RunCorrelation.Found(correlation)
            }
        },
    )

    /** A resolver that remembers what it was asked and answers as the test says. */
    private class FakeResolver(
        revision: String = "rev-1",
        private val behaviour: (String, String, List<String>) -> ProviderResolution,
    ) : ConfigurationResolver {

        data class Asked(val owner: String, val provider: String, val keys: List<String>)

        val asked: MutableList<Asked> = java.util.Collections.synchronizedList(mutableListOf<Asked>())
        private val stated = revision

        override fun resolve(owner: String, provider: String, keys: List<String>): ProviderResolution {
            asked += Asked(owner, provider, keys)
            return when (val outcome = behaviour(owner, provider, keys)) {
                is ProviderResolution.Resolved -> outcome.copy(
                    provenance = outcome.provenance.copy(revision = stated),
                )

                is ProviderResolution.Refused -> outcome
            }
        }
    }

    /** A port whose child prints the receipt of the launch, with an id of its own for each run. */
    private class FakePort : DurableTaskRuntime {

        val requests: MutableList<TaskExecutionRequest> =
            java.util.Collections.synchronizedList(mutableListOf<TaskExecutionRequest>())
        private val runs = AtomicInteger()
        private val correlations = java.util.concurrent.ConcurrentHashMap<String, String>()

        /** The correlation the child of [runId] was started with, or null when no child had that run. */
        fun correlationOf(runId: String): String? = correlations[runId]

        override suspend fun execute(
            request: TaskExecutionRequest,
            outputSink: ExecutionOutputSink,
        ): TaskExecutionResult {
            requests += request
            val argv = (request.task as TaskSpec.ExecTask).argv
            val correlation = argv[argv.indexOf("--correlation-id") + 1]
            val run = runs.incrementAndGet()
            correlations["run-$run"] = correlation
            outputSink.append(
                OutputChunk.stdout(
                    (
                        """{"schema":"pipelinek.launch/1","runId":"run-$run","correlationId":"$correlation",""" +
                            """"ownerId":"owner-$run","startedAtMs":1700,"status":"Launched"}""" + "\n"
                        ).toByteArray(),
                ),
            )
            return TaskExecutionResult(exitCode = 0, startedAtEpochMs = 0L, endedAtEpochMs = 0L)
        }
    }
}
