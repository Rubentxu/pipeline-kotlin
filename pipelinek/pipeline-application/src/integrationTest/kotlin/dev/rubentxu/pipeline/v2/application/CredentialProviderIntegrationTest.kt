package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.events.durable.DurableStores
import dev.rubentxu.pipeline.v2.events.durable.EventStoreAdmission
import dev.rubentxu.pipeline.v2.events.durable.EventStorePurpose
import dev.rubentxu.pipeline.v2.events.durable.admitEventStore
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeObservation
import dev.rubentxu.pipeline.v2.runtime.inspect.TerminalObservation
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.task.ProcessDurableTaskRuntime
import dev.rubentxu.pipeline.v2.service.ServiceAnswer
import dev.rubentxu.pipeline.v2.service.ServiceClient
import dev.rubentxu.pipeline.v2.service.ServiceCredentialPoll
import dev.rubentxu.pipeline.v2.service.ServiceProtocol
import dev.rubentxu.pipeline.v2.service.ServiceProviderSession
import dev.rubentxu.pipeline.v2.service.ServiceRefusal
import dev.rubentxu.pipeline.v2.service.ServiceRunStart
import dev.rubentxu.pipeline.v2.service.ServiceStreet
import dev.rubentxu.pipeline.v2.service.ServiceStreetItem
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A controller that keeps the credentials of a run, over a real socket and with a real child.
 *
 * ## The guarantee under trial
 *
 * That the credential a controller hands out is the one the STEP of the run receives, that it is
 * handed out exactly when the run asks for it and not before, that a request is answered once
 * and only by the session it was handed to, that a controller that disappears leaves the run
 * denied instead of looking the credential up somewhere else, and that the secret itself is
 * written nowhere but the socket that carried it.
 *
 * ## The capacity this serves
 *
 * An operator whose credentials live in an IDE does not copy them into the service: the run asks
 * for the credential at the moment it needs it and the controller that started it answers. A
 * service that fell back to a local store when the controller was gone would hand the run a
 * secret nobody chose for it, and a service that let a second controller answer the request of
 * the first would hand the run a secret somebody else chose.
 *
 * ## Why this instrument is an INTEGRATION test
 *
 * The facts are about a MACHINE: a real socket that carries the four operations of a session, a
 * run that really asks its broker for a credential, a broker that really blocks until the
 * controller answers, and a child that really receives the bytes in its environment. The rules
 * of the registry are proved by the unit lane against doubles; whether the four operations
 * compose into a running step is only observable here.
 *
 * ## What this does not freeze
 *
 * How long a refusal takes, and the words of the detail of a refusal: a refusal is read by its
 * name.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class CredentialProviderIntegrationTest {

    @TempDir
    lateinit var root: Path

    /** The store the service reads and the run writes. */
    private lateinit var db: Path

    /** The control root the service reads and the run writes. */
    private lateinit var control: Path

    /** The directory of the scripts a client may name. */
    private lateinit var scripts: Path

    /** The socket the service listens on. */
    private lateinit var socket: Path

    private val clock: Clock = FixedClock

    @BeforeEach
    fun material() {
        db = root.resolve("store.db")
        control = Files.createDirectories(root.resolve("control"))
        scripts = Files.createDirectories(root.resolve("scripts"))
        socket = root.resolve("service.sock")
        // The step prints the LENGTH of the credential and nothing else: a step that found no
        // variable prints nothing, and a length never carries the secret it measures.
        Files.writeString(
            scripts.resolve("credential.pipeline.kts"),
            "pipeline {\n    stages {\n        stage(\"eager\") {\n" +
                "            environment(\"deploy-key\", \"DEPLOY_KEY\") {\n" +
                "                sh(\"printenv DEPLOY_KEY | wc -c\")\n" +
                "            }\n        }\n    }\n}\n",
        )
        val admission = admitEventStore(db.toString(), EventStorePurpose.Produce)
        val writable = (admission as EventStoreAdmission.Admitted).store
        DurableStores.open(writable, control, clock)
        writable.close()
    }

    @Test
    fun `a controller hands the credential over and the run goes on with it`() {
        val service = start()
        val pool = Executors.newFixedThreadPool(1)
        try {
            val session = registered(STARTER_TOKEN, PROVIDER)
            // BEFORE the run: the session is asked for nothing, because nobody asked for
            // anything yet.
            assertEquals(
                ServiceCredentialPoll.Idle,
                pollValue(session.sessionId, STARTER_TOKEN),
                "the session was asked for nothing before the run asked for it",
            )

            val controller = pool.submit(
                Callable { grantWhenAsked(session.sessionId, STARTER_TOKEN, SECRET) },
            )
            val answer = client(STARTER_TOKEN).startRun(
                script = "credential.pipeline.kts",
                idempotencyKey = "key-granted",
                credentialProvider = PROVIDER,
            )
            val run = started(answer)
            assertTrue(controller.get(120, TimeUnit.SECONDS), "the controller was asked and answered once")

            val terminal = awaitTerminal(run.runId)
            assertInstanceOf(
                TerminalObservation.Succeeded::class.java,
                terminal.terminal,
                "the run went on with the credential the controller handed over",
            )
            val output = outputOf(run.runId)
            assertTrue(
                output.contains(EXPECTED_LENGTH),
                "the step received exactly the bytes the controller sent: $output",
            )
        } finally {
            pool.shutdownNow()
            service.stop()
        }
    }

    @Test
    fun `the secret is written nowhere but the socket that carried it`() {
        val service = start()
        val pool = Executors.newFixedThreadPool(1)
        try {
            val session = registered(STARTER_TOKEN, PROVIDER)
            val controller = pool.submit(
                Callable { grantWhenAsked(session.sessionId, STARTER_TOKEN, SECRET) },
            )
            val run = started(
                client(STARTER_TOKEN).startRun(
                    script = "credential.pipeline.kts",
                    idempotencyKey = "key-secrets",
                    credentialProvider = PROVIDER,
                ),
            )
            assertTrue(controller.get(120, TimeUnit.SECONDS), "the controller answered")
            awaitTerminal(run.runId)

            assertFalse(
                outputOf(run.runId).contains(String(SECRET)),
                "the output of the run never carries the secret",
            )
            val written = filesHolding(root, String(SECRET))
            assertTrue(
                written.read > 0,
                "the sweep read the tree of the service: it read ${written.read} files",
            )
            assertTrue(
                written.hits.isEmpty(),
                "the secret is written in no file of the service: ${written.hits}",
            )
        } finally {
            pool.shutdownNow()
            service.stop()
        }
    }

    @Test
    fun `a request is answered once and the second answer is refused`() {
        val service = start()
        val pool = Executors.newFixedThreadPool(1)
        try {
            val session = registered(STARTER_TOKEN, PROVIDER)
            val asked = pool.submit(Callable { awaitAsked(session.sessionId, STARTER_TOKEN) })

            val run = started(
                client(STARTER_TOKEN).startRun(
                    script = "credential.pipeline.kts",
                    idempotencyKey = "key-twice",
                    credentialProvider = PROVIDER,
                ),
            )
            val request = asked.get(120, TimeUnit.SECONDS)
            val first = client(STARTER_TOKEN).grantCredential(session.sessionId, request.requestId, SECRET)
            assertInstanceOf(ServiceAnswer.Answered::class.java, first, "the first answer was accepted")

            val second = client(STARTER_TOKEN).grantCredential(session.sessionId, request.requestId, SECRET)
            assertEquals(
                "request-unknown",
                assertInstanceOf(
                    ServiceRefusal.CredentialProviderRefused::class.java,
                    refusalOf(second),
                ).reason,
                "the same request is not answered twice",
            )
            awaitTerminal(run.runId)
        } finally {
            pool.shutdownNow()
            service.stop()
        }
    }

    @Test
    fun `a controller that closes its session denies the run and nothing is looked up elsewhere`() {
        val service = start()
        val pool = Executors.newFixedThreadPool(1)
        try {
            val session = registered(STARTER_TOKEN, PROVIDER)
            val asked = pool.submit(Callable { awaitAsked(session.sessionId, STARTER_TOKEN) })

            val run = started(
                client(STARTER_TOKEN).startRun(
                    script = "credential.pipeline.kts",
                    idempotencyKey = "key-gone",
                    credentialProvider = PROVIDER,
                ),
            )
            asked.get(120, TimeUnit.SECONDS)
            val closed = client(STARTER_TOKEN).closeCredentialProvider(session.sessionId)
            assertInstanceOf(ServiceAnswer.Answered::class.java, closed, "the session was closed")

            val terminal = awaitTerminal(run.runId)
            assertInstanceOf(
                TerminalObservation.Failed::class.java,
                terminal.terminal,
                "the run was denied: the controller was gone",
            )
            assertFalse(
                outputOf(run.runId).contains(EXPECTED_LENGTH),
                "no credential reached the step: there was none to reach it",
            )
        } finally {
            pool.shutdownNow()
            service.stop()
        }
    }

    @Test
    fun `another credential cannot answer the request of a run it did not start`() {
        val service = start()
        val pool = Executors.newFixedThreadPool(1)
        try {
            val session = registered(STARTER_TOKEN, PROVIDER)
            val asked = pool.submit(Callable { awaitAsked(session.sessionId, STARTER_TOKEN) })

            val run = started(
                client(STARTER_TOKEN).startRun(
                    script = "credential.pipeline.kts",
                    idempotencyKey = "key-impersonated",
                    credentialProvider = PROVIDER,
                ),
            )
            val request = asked.get(120, TimeUnit.SECONDS)

            val stolen = client(OTHER_STARTER_TOKEN).grantCredential(session.sessionId, request.requestId, SECRET)
            assertEquals(
                "provider-session-unknown",
                assertInstanceOf(
                    ServiceRefusal.CredentialProviderRefused::class.java,
                    refusalOf(stolen),
                ).reason,
                "the session belongs to the credential that registered it",
            )
            val polled = client(OTHER_STARTER_TOKEN).pollCredentialProvider(session.sessionId, 200L)
            assertEquals(
                "provider-session-unknown",
                assertInstanceOf(
                    ServiceRefusal.CredentialProviderRefused::class.java,
                    refusalOf(polled),
                ).reason,
                "the session of another credential is not a session of this one",
            )

            // The rightful controller still answers, and the run goes on.
            val granted = client(STARTER_TOKEN).grantCredential(session.sessionId, request.requestId, SECRET)
            assertInstanceOf(ServiceAnswer.Answered::class.java, granted)
            assertInstanceOf(
                TerminalObservation.Succeeded::class.java,
                awaitTerminal(run.runId).terminal,
            )
        } finally {
            pool.shutdownNow()
            service.stop()
        }
    }

    @Test
    fun `a run of another credential cannot use the session of the first`() {
        val service = start()
        try {
            registered(STARTER_TOKEN, PROVIDER)

            val answer = client(OTHER_STARTER_TOKEN).startRun(
                script = "credential.pipeline.kts",
                idempotencyKey = "key-stranger",
                credentialProvider = PROVIDER,
            )

            assertEquals(
                "provider-not-registered",
                assertInstanceOf(
                    ServiceRefusal.CredentialProviderRefused::class.java,
                    refusalOf(answer),
                ).reason,
                "the name belongs to the credential that registered it: nobody else gets its answer",
            )
        } finally {
            service.stop()
        }
    }

    @Test
    fun `a client that may not start runs cannot open a session`() {
        val service = start()
        try {
            val answer = client(READER_TOKEN).registerCredentialProvider(PROVIDER)

            assertEquals(
                ServiceRefusal.StartNotAuthorized,
                refusalOf(answer),
                "opening a session is a right of the credentials that may start runs",
            )
        } finally {
            service.stop()
        }
    }

    @Test
    fun `a service that was not started for credential sessions refuses the start by name`() {
        val closed = start(credentialSessions = false, socketName = "closed.sock")
        try {
            val answer = client(STARTER_TOKEN, "closed.sock").startRun(
                script = "credential.pipeline.kts",
                idempotencyKey = "key-closed",
                credentialProvider = PROVIDER,
            )

            assertEquals(
                "credential-sessions-not-composed",
                assertInstanceOf(
                    ServiceRefusal.CredentialProviderRefused::class.java,
                    refusalOf(answer),
                ).reason,
                "a capability nobody asked for is refused by name, not opened by accident",
            )
        } finally {
            closed.stop()
        }
    }

    @Test
    fun `the opt-in of the composition serves the sessions and never opens the store`() {
        val opened = java.util.concurrent.atomic.AtomicInteger(0)
        val composed = composeRealService { openings ->
            openings.copy(
                brokerOpening = {
                    opened.incrementAndGet()
                    ServiceCredentialAccess.Refused("the store is not there")
                },
            )
        }
        try {
            assertEquals(
                0,
                opened.get(),
                "the local store was not opened: the passphrase of the operator was not even asked for",
            )
            val session = registered(STARTER_TOKEN, PROVIDER)
            assertTrue(
                session.sessionId.isNotEmpty(),
                "the service composed with the opt-in opened a session",
            )
        } finally {
            composed.close()
        }
    }

    /**
     * The service, composed by the REAL composition root, and the way to stop it.
     *
     * The rows above build the service by hand; this one composes it the way the verb does, so
     * the opt-in of the operator is measured on the path the operator takes.
     */
    private fun composeRealService(
        openings: (ServeOpenings) -> ServeOpenings = { it },
    ): ServeComposition.Composed {
        val registry = when (val read = ServiceStartRegistry.read(root.resolve(ServiceStartRegistry.FILE_NAME))) {
            is ServiceStartRegistryReading.Read -> read.registry
            is ServiceStartRegistryReading.Refused -> error(read.detail)
        }
        val composed = composeServiceToServe(
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
                deadlineMillis = 240_000L,
                startDeadlineMillis = 120_000L,
                maxSupervisedRuns = 2,
                allowNetwork = false,
                registry = registry,
            ),
            authorizations = authorizations(),
            clock = SystemClock(),
            err = java.io.PrintStream(java.io.ByteArrayOutputStream(), true),
            openings = openings(
                ServeOpenings(
                    environment = emptyMap(),
                    credentialSessions = true,
                ),
            ),
        )
        val service = assertInstanceOf(ServeComposition.Composed::class.java, composed)
        Thread({ service.server.serve() }, "service-under-test").apply {
            isDaemon = true
            start()
        }
        return service
    }

    /** The session [token] opened for [provider], which has to be a success. */
    private fun registered(token: String, provider: String): ServiceProviderSession =
        assertInstanceOf(
            ServiceProviderSession::class.java,
            assertInstanceOf(
                ServiceAnswer.Answered::class.java,
                client(token).registerCredentialProvider(provider),
            ).value,
        )

    /**
     * Polls [sessionId] until a run asks for a credential, then hands over [value].
     * Whether it answered is what it returns.
     */
    private fun grantWhenAsked(sessionId: String, token: String, value: ByteArray): Boolean {
        val asked = awaitAsked(sessionId, token)
        return client(token).grantCredential(sessionId, asked.requestId, value) is ServiceAnswer.Answered
    }

    /** Polls [sessionId] until a run asks for a credential. */
    private fun awaitAsked(sessionId: String, token: String): ServiceCredentialPoll.Asked {
        val deadline = System.currentTimeMillis() + ASKED_DEADLINE_MILLIS
        while (System.currentTimeMillis() < deadline) {
            val poll = client(token).pollCredentialProvider(sessionId, 1_000L)
            if (poll is ServiceAnswer.Answered) {
                val state = poll.value
                if (state is ServiceCredentialPoll.Asked) return state
            }
        }
        error("nobody asked the session $sessionId for a credential")
    }

    /** What one poll of [sessionId] found. */
    private fun pollValue(sessionId: String, token: String): ServiceCredentialPoll =
        assertInstanceOf(
            ServiceCredentialPoll::class.java,
            assertInstanceOf(
                ServiceAnswer.Answered::class.java,
                client(token).pollCredentialProvider(sessionId, 200L),
            ).value,
        )

    /** The refusal [answer] is, which it has to be. */
    private fun refusalOf(answer: ServiceAnswer<*>): ServiceRefusal =
        assertInstanceOf(ServiceAnswer.Refused::class.java, answer).refusal

    /** The run the answer names. */
    private fun started(answer: ServiceAnswer<ServiceRunStart>): ServiceRunStart =
        assertInstanceOf(
            ServiceRunStart::class.java,
            assertInstanceOf(ServiceAnswer.Answered::class.java, answer).value,
        )

    /** The stdout and the stderr of [runId], read over the socket. */
    private fun outputOf(runId: String): String {
        val answer = client(STARTER_TOKEN).output(runId)
        val street = assertInstanceOf(
            ServiceStreet::class.java,
            assertInstanceOf(ServiceAnswer.Answered::class.java, answer).value,
        )
        val text = StringBuilder()
        street.use {
            while (true) {
                when (val item = it.next()) {
                    is ServiceStreetItem.Record -> {
                        val document = item.document
                        val channel = document["channel"]?.jsonPrimitive?.content
                        val line = document["text"]?.jsonPrimitive?.content
                        if (channel == "stdout" || channel == "stderr") {
                            if (line != null) text.append(line).append('\n')
                        }
                    }

                    is ServiceStreetItem.End -> return text.toString()
                    is ServiceStreetItem.Refused -> error("the street was refused: ${item.refusal}")
                    null -> return text.toString()
                }
            }
        }
    }

    /** The terminal observation of [runId], asked for until it arrives. */
    private fun awaitTerminal(runId: String): RuntimeObservation.Terminal {
        val deadline = System.currentTimeMillis() + TERMINAL_DEADLINE_MILLIS
        while (System.currentTimeMillis() < deadline) {
            when (val answer = client(STARTER_TOKEN).runState(runId)) {
                is ServiceAnswer.Answered -> {
                    val observation = answer.value
                    if (observation is RuntimeObservation.Terminal) return observation
                }

                else -> Unit
            }
            Thread.sleep(500L)
        }
        error("the run $runId did not finish in time")
    }

    /** The service, over a real socket, and the way to stop it. */
    private inner class Service(
        private val server: ServiceServer,
        private val store: dev.rubentxu.pipeline.v2.events.durable.SqliteEventStore,
        private val supervisor: ServiceRunSupervisor,
    ) {

        private val thread = Thread({ server.serve() }, "service-under-test").apply {
            isDaemon = true
            start()
        }

        /** Stops the service and the runs it supervises. */
        fun stop() {
            server.close()
            supervisor.close()
            thread.join(10_000L)
            store.close()
        }
    }

    /**
     * Starts the service over a real socket, with the real supervisor and the sessions.
     *
     * With [credentialSessions] the service serves them and holds NO local store: the broker of
     * every run is opened with no provider behind it, so the only source of a credential is the
     * controller that registered the session of the run.
     */
    private fun start(
        credentialSessions: Boolean = true,
        socketName: String = "service.sock",
    ): Service {
        val serviceSocket = root.resolve(socketName)
        val admission = admitEventStore(db.toString(), EventStorePurpose.Read)
        val store = (admission as EventStoreAdmission.Admitted).store
        val supervisionRoot = root.resolve("supervisions")
        val registry = when (val read = ServiceStartRegistry.read(root.resolve(ServiceStartRegistry.FILE_NAME))) {
            is ServiceStartRegistryReading.Read -> read.registry
            is ServiceStartRegistryReading.Refused -> error(read.detail)
        }
        val sessions = if (credentialSessions) CredentialProviderSessions() else null
        val authorizations = authorizations()
        val supervisor = ServiceRunSupervisor(
            admission = ServiceStartAdmission(scripts, null),
            runPolicy = ServiceRunPolicy(
                eventStore = db.toAbsolutePath().toString(),
                controlRoot = control.toAbsolutePath().toString(),
                deadlineMillis = 240_000L,
                allowNetwork = false,
            ),
            command = ServiceRunCommand(
                javaBinary = Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                classpath = System.getProperty("java.class.path"),
                mainClass = "dev.rubentxu.pipeline.v2.application.MainKt",
            ),
            port = ProcessDurableTaskRuntime(supervisionRoot, clock),
            registry = registry,
            limits = ServiceStartLimits(maxSupervisedRuns = 2, startDeadlineMillis = 120_000L),
            supervisionRoot = supervisionRoot,
            brokers = RunBrokerFactory(
                root.resolve("brokers"),
                provider = null,
                grantsOf = authorizations::grantsOfDigest,
            ),
            credentialSessions = sessions,
        )
        val operations = ServiceOperations(
            introspection = composeRuntimeIntrospectionPort(
                eventStore = store,
                controlRoot = control,
                clock = clock,
            ),
            substrate = {
                ServiceSubstrate(
                    eventStore = db.toString(),
                    controlRoot = control.toString(),
                    eventStorePresent = Files.exists(db),
                    controlRootPresent = Files.exists(control),
                )
            },
            starts = supervisor,
            cancellations = null,
            providers = ConfigurationProviderSessions(),
            configurations = supervisor,
            credentialSessions = sessions,
        )
        val stores = DurableStores.open(store, control, clock)
        val streets = ServiceSubscription(
            lanes = ComposeLanes.forService(store, control),
            events = composeEventRecordReadPort(store, stores.lease),
        )
        val transport = UnixSocketTransport.bind(serviceSocket)
        return Service(
            ServiceServer(transport, operations, streets, authorizations),
            store,
            supervisor,
        )
    }

    /**
     * The authorizations this service knows: a client that only reads, and two that may also
     * start runs — and so open provider sessions and hold the grant of one credential.
     */
    private fun authorizations(): ServiceAuthorizations = ServiceAuthorizations.of(
        listOf(
            ServiceAuthorization(READER_TOKEN, "reader", ServiceScope.All),
            ServiceAuthorization(
                STARTER_TOKEN,
                "starter",
                ServiceScope.WithRights(ServiceScope.All, setOf(ServiceScope.Right.Start)),
                setOf(CredentialGrant(CREDENTIAL_ID, BrokeredCredentialKind.STRING)),
            ),
            ServiceAuthorization(
                OTHER_STARTER_TOKEN,
                "other-starter",
                ServiceScope.WithRights(ServiceScope.All, setOf(ServiceScope.Right.Start)),
                setOf(CredentialGrant(CREDENTIAL_ID, BrokeredCredentialKind.STRING)),
            ),
        ),
    )

    /** A client that presents [token] to the service on [socketName]. */
    private fun client(token: String, socketName: String = "service.sock"): ServiceClient = ServiceClient(
        endpoint = root.resolve(socketName),
        credential = token,
        deadline = java.time.Duration.ofSeconds(240),
        schema = ServiceProtocol.SCHEMA,
    )

    /** The files below [directory] whose bytes hold [text], and how many files were read. */
    private fun filesHolding(directory: Path, text: String): Sweep {
        val needle = text.toByteArray(Charsets.UTF_8)
        val found = mutableListOf<Path>()
        var read = 0
        Files.walk(directory).use { paths ->
            paths.filter { Files.isRegularFile(it) }.forEach { file ->
                read++
                if (Files.readAllBytes(file).contains(needle)) found.add(file)
            }
        }
        return Sweep(found, read)
    }

    /**
     * What a sweep of a tree found.
     *
     * [read] is how many files were looked at: a sweep that read nothing has proved nothing,
     * and the row that uses it says so.
     */
    private data class Sweep(val hits: List<Path>, val read: Int)

    /** The clock this lane stamps its material with: it does not move. */
    private object FixedClock : Clock {
        override fun now(): Instant = Instant.EPOCH
    }

    private companion object {
        /** The token of the client that may only read. */
        private const val READER_TOKEN: String = "token-of-the-reader"

        /** The token of the client that may start runs. */
        private const val STARTER_TOKEN: String = "token-of-the-starter"

        /** The token of another client that may start runs. */
        private const val OTHER_STARTER_TOKEN: String = "token-of-the-other-starter"

        /** The name of the credential provider the controllers register. */
        private const val PROVIDER: String = "controller"

        /** The credential the run asks for. */
        private const val CREDENTIAL_ID: String = "deploy-key"

        /** The secret the controller keeps and hands over when the run asks. */
        private val SECRET: ByteArray = "s3cr3t-of-the-controller".toByteArray()

        /**
         * The length the step prints: the secret plus the newline `printenv` writes.
         */
        private const val EXPECTED_LENGTH: String = "25"

        /** How long the test waits for a run to finish. */
        private const val TERMINAL_DEADLINE_MILLIS: Long = 180_000L

        /** How long the test waits for a run to ask a session for a credential. */
        private const val ASKED_DEADLINE_MILLIS: Long = 60_000L
    }
}

/**
 * Whether the bytes of [haystack] hold the bytes of [needle].
 *
 * The sweep of AC#3 needs a search that does not stop at the first difference and that says
 * nothing about the content it found.
 */
private fun ByteArray.contains(needle: ByteArray): Boolean {
    if (needle.isEmpty() || needle.size > size) return false
    for (start in 0..(size - needle.size)) {
        var same = true
        for (offset in needle.indices) {
            if (this[start + offset] != needle[offset]) {
                same = false
                break
            }
        }
        if (same) return true
    }
    return false
}
