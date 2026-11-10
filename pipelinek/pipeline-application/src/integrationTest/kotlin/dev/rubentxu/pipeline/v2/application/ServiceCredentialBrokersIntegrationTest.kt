package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.credentials.local.CredentialsStorePath
import dev.rubentxu.pipeline.v2.credentials.local.LocalSecretStore
import dev.rubentxu.pipeline.v2.credentials.spi.CredentialProvider
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.credentials.Credential
import dev.rubentxu.pipeline.v2.domain.credentials.SecretText
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.events.durable.DurableStores
import dev.rubentxu.pipeline.v2.events.durable.EventStoreAdmission
import dev.rubentxu.pipeline.v2.events.durable.EventStorePurpose
import dev.rubentxu.pipeline.v2.events.durable.admitEventStore
import dev.rubentxu.pipeline.v2.service.ServiceAnswer
import dev.rubentxu.pipeline.v2.service.ServiceClient
import dev.rubentxu.pipeline.v2.service.ServiceProtocol
import dev.rubentxu.pipeline.v2.service.ServiceRunStart
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The credential brokers of the service, composed over a real store, a real socket and a real
 * child.
 *
 * ## The guarantee under trial
 *
 * That a service whose authorizations grant a credential hands exactly that credential, over the
 * private socket of the run, from the store it opened when it started; that it hands nothing to a
 * client it does not know; and that the way it starts, stops and fails to start leaves nothing
 * open behind it and removes nothing that belongs to a service that is alive.
 *
 * ## The capacity this serves
 *
 * A running execution asks for a credential only when it enters `withCredentials`. What it asks
 * to is a broker that the service opened for it, from a store the service unlocked once. This is
 * the seam between the two: the part of the service that decides whether there are brokers at
 * all.
 *
 * ## Why this instrument is an INTEGRATION test
 *
 * The facts are about a MACHINE: the store is a file sealed with a passphrase, the broker is a
 * socket that really binds under a directory that earlier processes may have left, and the
 * directory of a run's broker really exists while its child compiles. A double would prove that
 * the composition reads what it is given; it would not prove that a secret crosses the socket, or
 * that a second service on the same directory leaves the brokers of the first one alone.
 *
 * ## What this does not freeze
 *
 * The words of the refusals, how long the child takes, and the name of the directory of a lease.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class ServiceCredentialBrokersIntegrationTest {

    @TempDir
    lateinit var root: Path

    private lateinit var db: Path
    private lateinit var control: Path
    private lateinit var scripts: Path
    private lateinit var socket: Path
    private lateinit var brokers: Path

    @BeforeEach
    fun material() {
        db = root.resolve("store.db")
        control = Files.createDirectories(root.resolve("control"))
        scripts = Files.createDirectories(root.resolve("scripts"))
        socket = root.resolve("service.sock")
        brokers = root.resolve(BROKERS_DIRECTORY)
        Files.writeString(
            scripts.resolve("hello.pipeline.kts"),
            "pipeline {\n    stages {\n        stage(\"eager\") {\n            echo(\"hello\")\n        }\n    }\n}\n",
        )
        // The service reads a substrate it is given and never creates one, so the store exists and
        // is complete before any composition runs.
        val writable = (admitEventStore(db.toString(), EventStorePurpose.Produce) as EventStoreAdmission.Admitted).store
        DurableStores.open(writable, control, FixedClock)
        writable.close()
    }

    // --- a real store, a real broker, a real socket ---

    @Test
    fun `a granted credential crosses the socket of the run from the store the service opened`() {
        val composed = composeBrokersOverTheStore(GRANTS)
        try {
            val lease = composed.factory.open(ServiceAuthorizations.digestOf(TOKEN))
            try {
                val provider = SocketCredentialProvider.inDirectory(lease.directory)
                try {
                    val credential = provider.resolveToCredential(CredentialsId("deploy-key"))
                    val text = assertInstanceOf(SecretText::class.java, credential)
                    assertArrayEquals(SECRET, text.bytes, "the run received the secret its client was granted")
                } finally {
                    provider.close()
                }
            } finally {
                lease.close()
            }
        } finally {
            closeServiceCredentials(composed.provider)
        }
    }

    @Test
    fun `a credential the store holds but nobody granted is refused by name`() {
        val composed = composeBrokersOverTheStore(GRANTS)
        try {
            val lease = composed.factory.open(ServiceAuthorizations.digestOf(TOKEN))
            try {
                val provider = SocketCredentialProvider.inDirectory(lease.directory)
                try {
                    val refused = assertThrows<CredentialBrokerRefusedException> {
                        provider.resolveToCredential(CredentialsId("other-key"))
                    }
                    assertEquals(CredentialBrokerRefusal.CREDENTIAL_NOT_GRANTED, refused.refusal)
                } finally {
                    provider.close()
                }
            } finally {
                lease.close()
            }
        } finally {
            closeServiceCredentials(composed.provider)
        }
    }

    @Test
    fun `a client the service does not know gets a broker that grants nothing, not the broker of somebody else`() {
        val composed = composeBrokersOverTheStore(GRANTS)
        try {
            for (digest in listOf("not-the-digest-of-any-token", null)) {
                val lease = composed.factory.open(digest)
                try {
                    val provider = SocketCredentialProvider.inDirectory(lease.directory)
                    try {
                        val refused = assertThrows<CredentialBrokerRefusedException>("client $digest") {
                            provider.resolveToCredential(CredentialsId("deploy-key"))
                        }
                        assertEquals(CredentialBrokerRefusal.CREDENTIAL_NOT_GRANTED, refused.refusal, "client $digest")
                    } finally {
                        provider.close()
                    }
                } finally {
                    lease.close()
                }
            }
        } finally {
            closeServiceCredentials(composed.provider)
        }
    }

    // --- the composition of the service ---

    @Test
    fun `a service sweeps the brokers an earlier process left once it holds its socket, and gives the provider back when it stops`() {
        val stale = Files.createDirectories(brokers.resolve("left-by-a-crash"))
        Files.writeString(stale.resolve(RunBrokerLease.SOCKET_FILE_NAME), "nothing listens here")
        val provider = ProviderDouble()

        val composed = compose(authorizations(GRANTS), opening = { ServiceCredentialAccess.Opened(provider) })

        val service = assertInstanceOf(ServeComposition.Composed::class.java, composed)
        try {
            assertFalse(Files.exists(stale), "what an earlier process left below the brokers directory is swept")
            assertFalse(provider.closed.get(), "the provider stays open while the service serves")
        } finally {
            service.close()
        }
        assertTrue(provider.closed.get(), "the service gives the provider back when it stops")
    }

    @Test
    fun `a service whose store cannot be opened does not start, binds no socket and sweeps nothing`() {
        val stale = Files.createDirectories(brokers.resolve("left-by-a-crash"))

        val composed = compose(
            authorizations(GRANTS),
            opening = { ServiceCredentialAccess.Refused("the credential store could not be unlocked") },
        )

        val refused = assertInstanceOf(ServeComposition.Refused::class.java, composed)
        assertEquals(4, refused.exit.code, "the substrate cannot be served, and the operator is told so")
        assertTrue(
            errors.toString().contains("the credential store could not be unlocked"),
            "the refusal says why: $errors",
        )
        assertFalse(Files.exists(socket), "a service that does not start never answers")
        assertTrue(Files.exists(stale), "a service that does not start removes nothing")
    }

    @Test
    fun `a second service on the same socket neither sweeps the brokers of the first nor keeps its provider`() {
        val first = ProviderDouble()
        val firstService = assertInstanceOf(
            ServeComposition.Composed::class.java,
            compose(authorizations(GRANTS), opening = { ServiceCredentialAccess.Opened(first) }),
        )
        try {
            val alive = Files.createDirectories(brokers.resolve("of-a-run-of-the-first"))
            val second = ProviderDouble()

            val composed = compose(authorizations(GRANTS), opening = { ServiceCredentialAccess.Opened(second) })

            assertInstanceOf(ServeComposition.Refused::class.java, composed)
            assertTrue(Files.exists(alive), "the brokers of the service that is alive are not swept by one that failed to bind")
            assertTrue(second.closed.get(), "a service that did not start gives back the provider it opened")
            assertFalse(first.closed.get(), "and leaves the provider of the first one alone")
        } finally {
            firstService.close()
        }
    }

    @Test
    fun `a service that grants no credential never opens the store and creates no brokers directory`() {
        val opened = AtomicInteger(0)

        val composed = compose(
            authorizations(emptySet()),
            opening = {
                opened.incrementAndGet()
                ServiceCredentialAccess.Refused("the store would have been opened")
            },
        )

        val service = assertInstanceOf(ServeComposition.Composed::class.java, composed)
        try {
            assertEquals(0, opened.get(), "the passphrase of the operator is not asked for")
            assertFalse(Files.exists(brokers), "no run has a broker, so there is no directory for them")
        } finally {
            service.close()
        }
    }

    @Test
    fun `a run started by a client with a grant gets a broker below the brokers directory while its child lives`() {
        val provider = ProviderDouble()
        val composed = compose(
            ServiceAuthorizations.of(
                listOf(
                    ServiceAuthorization(
                        STARTER_TOKEN,
                        "starter",
                        ServiceScope.WithRights(ServiceScope.All, setOf(ServiceScope.Right.Start)),
                        GRANTS,
                    ),
                ),
            ),
            opening = { ServiceCredentialAccess.Opened(provider) },
        )
        val service = assertInstanceOf(ServeComposition.Composed::class.java, composed)
        val serving = Thread({ service.server.serve() }, "service-under-test").apply {
            isDaemon = true
            start()
        }
        // The lease is opened BEFORE the child is launched and closed when the run is over, and the
        // child compiles its script in between: a watcher that starts before the request sees it.
        val seen = AtomicBoolean(false)
        val stopWatching = AtomicBoolean(false)
        val watcher = Thread({
            while (!stopWatching.get() && !seen.get()) {
                if (aBrokerIsListening()) seen.set(true) else Thread.sleep(WATCH_INTERVAL_MILLIS)
            }
        }, "watching-the-brokers").apply {
            isDaemon = true
            start()
        }
        try {
            val answer = client(STARTER_TOKEN).startRun("hello.pipeline.kts")

            val started = assertInstanceOf(ServiceAnswer.Answered::class.java, answer).value
            assertNotNull(assertInstanceOf(ServiceRunStart::class.java, started).runId)
            watcher.join(WATCH_DEADLINE_MILLIS)
            assertTrue(seen.get(), "the run had a broker below the brokers directory of the service")
        } finally {
            stopWatching.set(true)
            service.close()
            serving.join(JOIN_MILLIS)
        }
        assertTrue(provider.closed.get(), "the provider of the service is given back after its children")
    }

    // --- fixtures ---

    private val errors = ByteArrayOutputStream()

    private fun compose(
        authorizations: ServiceAuthorizations,
        opening: (Map<String, String>) -> ServiceCredentialAccess,
    ): ServeComposition {
        val registry = when (val read = ServiceStartRegistry.read(root.resolve(ServiceStartRegistry.FILE_NAME))) {
            is ServiceStartRegistryReading.Read -> read.registry
            is ServiceStartRegistryReading.Refused -> error(read.detail)
        }
        return composeServiceToServe(
            substrate = ServeSubstrate(
                dbPath = db.toString(),
                dbAbsolute = db,
                controlRoot = control.toString(),
                controlAbsolute = control,
                controlRootPath = control,
                socketAbsolute = socket,
                socketPath = socket.toString(),
            ),
            policy = ServiceRunDeclaration(
                scriptsRoot = scripts,
                workspacesRoot = null,
                deadlineMillis = RUN_DEADLINE_MILLIS,
                startDeadlineMillis = START_DEADLINE_MILLIS,
                maxSupervisedRuns = 2,
                allowNetwork = false,
                registry = registry,
            ),
            authorizations = authorizations,
            clock = SystemClock(),
            err = PrintStream(errors, true),
            environment = emptyMap(),
            brokerOpening = opening,
        )
    }

    /** The brokers of the service, composed from the real store the environment names. */
    private fun composeBrokersOverTheStore(grants: Set<CredentialGrant>): OverTheStore {
        val store = root.resolve("credentials.bin")
        val sealed = LocalSecretStore(store, PASSPHRASE.toCharArray())
        try {
            sealed.put(CredentialsId("deploy-key"), SECRET.copyOf())
            sealed.put(CredentialsId("other-key"), "another-secret".toByteArray())
        } finally {
            sealed.close()
        }
        val composed = composeServiceBrokers(
            authorizations = authorizations(grants),
            brokersRoot = brokers,
            environment = mapOf(
                CredentialsStorePath.ENV_VAR to store.toString(),
                PASSPHRASE_VARIABLE to PASSPHRASE,
            ),
        )
        val result = assertInstanceOf(ServiceBrokers.Composed::class.java, composed)
        return OverTheStore(
            requireNotNull(result.factory) { "a granted credential needs a broker per run" },
            result.provider,
        )
    }

    private class OverTheStore(val factory: RunBrokerFactory, val provider: CredentialProvider?)

    private fun authorizations(grants: Set<CredentialGrant>): ServiceAuthorizations =
        ServiceAuthorizations.of(listOf(ServiceAuthorization(TOKEN, "operator", ServiceScope.All, grants)))

    private fun aBrokerIsListening(): Boolean {
        if (!Files.isDirectory(brokers)) return false
        return try {
            Files.newDirectoryStream(brokers).use { leases ->
                leases.any { Files.exists(it.resolve(RunBrokerLease.SOCKET_FILE_NAME)) }
            }
        } catch (_: java.io.IOException) {
            false
        }
    }

    private fun client(token: String): ServiceClient = ServiceClient(
        endpoint = socket,
        credential = token,
        deadline = Duration.ofSeconds(CLIENT_DEADLINE_SECONDS),
        schema = ServiceProtocol.SCHEMA,
    )

    /** A provider that records whether it was closed. */
    private class ProviderDouble : CredentialProvider {
        val closed = AtomicBoolean(false)

        override val providerId: String = "provider-double"

        override fun resolve(id: CredentialsId): SecretHandle = error("not asked in these rows")

        override fun resolveToCredential(id: CredentialsId): Credential = error("not asked in these rows")

        override fun close() {
            closed.set(true)
        }
    }

    private object FixedClock : Clock {
        override fun now(): Instant = Instant.EPOCH
    }

    private companion object {
        const val TOKEN: String = "token-of-the-operator"
        const val STARTER_TOKEN: String = "token-of-the-starter"
        const val PASSPHRASE: String = "the-passphrase-of-the-operator"
        const val PASSPHRASE_VARIABLE: String = "PIPELINE_STORE_PASSPHRASE"
        const val RUN_DEADLINE_MILLIS: Long = 240_000L
        const val START_DEADLINE_MILLIS: Long = 120_000L
        const val CLIENT_DEADLINE_SECONDS: Long = 240L
        const val WATCH_INTERVAL_MILLIS: Long = 20L
        const val WATCH_DEADLINE_MILLIS: Long = 5_000L
        const val JOIN_MILLIS: Long = 10_000L
        val SECRET: ByteArray = "s3cr3t-of-the-store".toByteArray()
        val GRANTS: Set<CredentialGrant> = setOf(CredentialGrant("deploy-key", BrokeredCredentialKind.STRING))
    }
}
