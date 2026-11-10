package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.credentials.Certificate
import dev.rubentxu.pipeline.v2.domain.credentials.Credential
import dev.rubentxu.pipeline.v2.domain.credentials.LinkedSecretRef
import dev.rubentxu.pipeline.v2.domain.credentials.SecretText
import dev.rubentxu.pipeline.v2.domain.credentials.SshPrivateKey
import dev.rubentxu.pipeline.v2.domain.credentials.Zip
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.events.durable.DurableStores
import dev.rubentxu.pipeline.v2.events.durable.EventStoreAdmission
import dev.rubentxu.pipeline.v2.events.durable.EventStorePurpose
import dev.rubentxu.pipeline.v2.events.durable.admitEventStore
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeObservation
import dev.rubentxu.pipeline.v2.runtime.inspect.TerminalObservation
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.task.ProcessDurableTaskRuntime
import dev.rubentxu.pipeline.v2.service.CredentialMaterialCodec
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
        // The multipart script binds a private key and a certificate, and MEASURES what it
        // receives: the length of the key file, of the passphrase, of the keystore and of the
        // password. A length never carries the secret it measures. The last step CATS the key,
        // so the transcript would carry it if nothing masked it.
        Files.writeString(
            scripts.resolve(MULTIPART_SCRIPT_NAME),
            """
            pipeline {
                stages {
                    stage("eager") {
                        withCredentials(
                            StepSpec.CredentialsBinding.sshUserPrivateKey("ssh-key", "KEY_FILE", "KEY_PASSPHRASE"),
                            StepSpec.CredentialsBinding.certificate("deploy-cert", "KEYSTORE", "KEY_ALIAS", "KEYSTORE_PASSWORD"),
                        ) {
                            sh("cat \"${'$'}KEY_FILE\" | wc -c")
                            sh("printenv KEY_PASSPHRASE | wc -c")
                            sh("cat \"${'$'}KEYSTORE\" | wc -c")
                            sh("printenv KEYSTORE_PASSWORD | wc -c")
                            sh("cat \"${'$'}KEY_FILE\"")
                        }
                    }
                }
            }
            """.trimIndent() + "\n",
        )
        // The archive script binds an archive and looks at what was unpacked: the name of the
        // entry, the mode of the entry and the mode of the root it was unpacked into.
        Files.writeString(
            scripts.resolve(ARCHIVE_SCRIPT_NAME),
            """
            pipeline {
                stages {
                    stage("eager") {
                        withCredentials(StepSpec.CredentialsBinding.zip("deploy-archive", "ARCHIVE_DIR")) {
                            sh("echo the-step-ran")
                            sh("ls \"${'$'}ARCHIVE_DIR\"")
                            sh("stat -c %a \"${'$'}ARCHIVE_DIR/entry.txt\"")
                            sh("stat -c %a \"${'$'}ARCHIVE_DIR\"")
                            sh("cat \"${'$'}ARCHIVE_DIR/entry.txt\"")
                        }
                    }
                }
            }
            """.trimIndent() + "\n",
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
            val first = client(STARTER_TOKEN).grantCredential(session.sessionId, request.requestId, secretMaterial())
            assertInstanceOf(ServiceAnswer.Answered::class.java, first, "the first answer was accepted")

            val second = client(STARTER_TOKEN).grantCredential(session.sessionId, request.requestId, secretMaterial())
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

            val stolen = client(OTHER_STARTER_TOKEN).grantCredential(session.sessionId, request.requestId, secretMaterial())
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
            val granted = client(STARTER_TOKEN).grantCredential(session.sessionId, request.requestId, secretMaterial())
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

    @Test
    fun `a private key and a certificate are materialized with the values the controller holds`() {
        val service = start()
        val pool = Executors.newFixedThreadPool(1)
        try {
            val session = registered(STARTER_TOKEN, PROVIDER)
            val controller = pool.submit(Callable { grantEverything(session.sessionId, STARTER_TOKEN) })

            val run = started(
                client(STARTER_TOKEN).startRun(
                    script = MULTIPART_SCRIPT_NAME,
                    idempotencyKey = "key-multipart",
                    credentialProvider = PROVIDER,
                ),
            )
            val terminal = awaitTerminal(run.runId)
            client(STARTER_TOKEN).closeCredentialProvider(session.sessionId)
            assertEquals(
                setOf(SSH_KEY_ID, SSH_PASSPHRASE_ID, CERT_ID, CERT_PASSWORD_ID),
                controller.get(180, TimeUnit.SECONDS).toSet(),
                "the controller was asked for the key, its passphrase, the certificate and its password",
            )
            assertInstanceOf(
                TerminalObservation.Succeeded::class.java,
                terminal.terminal,
                "the run went on with the credentials the controller handed over: ${outputOf(run.runId)}",
            )
            val output = flatOutput(run.runId)
            assertTrue(
                output.contains(KEY_TEXT.length.toString()),
                "the key file holds the bytes the controller sent: $output",
            )
            assertTrue(
                output.contains((PASSPHRASE.length + 1).toString()),
                "the passphrase is the one the controller holds: $output",
            )
            assertTrue(
                output.contains(KEYSTORE.size.toString()),
                "the keystore holds the bytes the controller sent: $output",
            )
            assertTrue(
                output.contains((PASSWORD.length + 1).toString()),
                "the password of the keystore is the one the controller holds: $output",
            )
            assertFalse(
                output.contains(KEY_BODY),
                "the transcript does not carry the key the controller handed over: $output",
            )
            assertFalse(
                output.contains(KEY_TEXT),
                "the transcript does not carry the key the controller handed over: $output",
            )
        } finally {
            pool.shutdownNow()
            service.stop()
        }
    }

    @Test
    fun `a linked secret the controller does not hold fails the run before the step and is looked up nowhere else`() {
        val service = start()
        val pool = Executors.newFixedThreadPool(1)
        try {
            val session = registered(STARTER_TOKEN, PROVIDER)
            val controller = pool.submit(
                Callable { grantEverything(session.sessionId, STARTER_TOKEN, without = SSH_PASSPHRASE_ID) },
            )

            val run = started(
                client(STARTER_TOKEN).startRun(
                    script = MULTIPART_SCRIPT_NAME,
                    idempotencyKey = "key-missing-passphrase",
                    credentialProvider = PROVIDER,
                ),
            )
            val terminal = awaitTerminal(run.runId)
            client(STARTER_TOKEN).closeCredentialProvider(session.sessionId)
            assertEquals(
                setOf(SSH_KEY_ID, SSH_PASSPHRASE_ID),
                controller.get(180, TimeUnit.SECONDS).toSet(),
                "the controller was asked for the key and for the passphrase it does not hold",
            )
            assertInstanceOf(
                TerminalObservation.Failed::class.java,
                terminal.terminal,
                "the run was denied: the passphrase never came",
            )
            assertFalse(
                outputOf(run.runId).contains(KEY_TEXT.length.toString()),
                "the step never ran: there was no passphrase to run it with",
            )
        } finally {
            pool.shutdownNow()
            service.stop()
        }
    }

    @Test
    fun `an archive is unpacked inside its own root and its files stay private to their owner`() {
        val service = start()
        val pool = Executors.newFixedThreadPool(1)
        try {
            val session = registered(STARTER_TOKEN, PROVIDER)
            val controller = pool.submit(Callable { grantEverything(session.sessionId, STARTER_TOKEN) })

            val run = started(
                client(STARTER_TOKEN).startRun(
                    script = ARCHIVE_SCRIPT_NAME,
                    idempotencyKey = "key-archive",
                    credentialProvider = PROVIDER,
                ),
            )
            val terminal = awaitTerminal(run.runId)
            client(STARTER_TOKEN).closeCredentialProvider(session.sessionId)
            assertEquals(
                setOf(ARCHIVE_ID),
                controller.get(180, TimeUnit.SECONDS).toSet(),
                "the controller was asked for the archive and for nothing else",
            )
            assertInstanceOf(
                TerminalObservation.Succeeded::class.java,
                terminal.terminal,
                "the archive was unpacked and the run went on: ${outputOf(run.runId)}",
            )
            val output = flatOutput(run.runId)
            assertTrue(output.contains(ARCHIVE_ENTRY), "the entry of the archive is there: $output")
            assertTrue(output.contains("600"), "the file of the archive is private to its owner: $output")
            assertTrue(output.contains("700"), "the root of the archive is private to its owner: $output")
            assertFalse(
                output.contains(ARCHIVE_CONTENT),
                "the transcript does not carry the entry of the archive: $output",
            )
        } finally {
            pool.shutdownNow()
            service.stop()
        }
    }

    @Test
    fun `an archive whose entry leaves its root is refused and the run does not go on`() {
        val service = start()
        val pool = Executors.newFixedThreadPool(1)
        try {
            val session = registered(STARTER_TOKEN, PROVIDER)
            val controller = pool.submit(
                Callable { grantEverything(session.sessionId, STARTER_TOKEN, hostileArchive = true) },
            )

            val run = started(
                client(STARTER_TOKEN).startRun(
                    script = ARCHIVE_SCRIPT_NAME,
                    idempotencyKey = "key-hostile-archive",
                    credentialProvider = PROVIDER,
                ),
            )
            val terminal = awaitTerminal(run.runId)
            client(STARTER_TOKEN).closeCredentialProvider(session.sessionId)
            assertEquals(
                setOf(ARCHIVE_ID),
                controller.get(180, TimeUnit.SECONDS).toSet(),
                "the controller was asked for the archive and for nothing else",
            )
            assertInstanceOf(
                TerminalObservation.Failed::class.java,
                terminal.terminal,
                "the archive that leaves its root is refused and the run does not go on",
            )
            val output = flatOutput(run.runId)
            assertFalse(
                output.contains(STEP_MARK),
                "the step never ran: the archive was refused before it was unpacked: $output",
            )
            assertFalse(
                output.contains(ESCAPING_ENTRY),
                "the entry that leaves the root was never written: $output",
            )
        } finally {
            pool.shutdownNow()
            service.stop()
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
     *
     * The value is handed over in the canonical form of a secret text: the form says what the
     * credential is, so the run never has to be told.
     *
     * Whether it answered is what it returns.
     */
    private fun grantWhenAsked(sessionId: String, token: String, value: ByteArray): Boolean {
        val asked = awaitAsked(sessionId, token)
        val material = CredentialMaterialCodec.encode(
            SecretText(id = CredentialsId(CREDENTIAL_ID), bytes = value.copyOf()),
        )
        return client(token).grantCredential(sessionId, asked.requestId, material) is ServiceAnswer.Answered
    }

    /** The material of the secret the controller holds, in the canonical form of a secret text. */
    private fun secretMaterial(): ByteArray = CredentialMaterialCodec.encode(
        SecretText(id = CredentialsId(CREDENTIAL_ID), bytes = SECRET.copyOf()),
    )

    /**
     * Answers every ask of [sessionId] with the material of the credential that was asked for.
     *
     * The controller holds one credential per name and hands over the FORM the authorization
     * granted: the material is the canonical form of the credential, and the form says what it
     * is. A credential named [without] is DENIED instead, so the row that uses it proves that a
     * controller that does not hold a linked secret stops the run instead of running without it.
     *
     * The loop ends when the session is gone: the row closes it once the run has ended.
     *
     * @return the names of the credentials the controller was asked for, in the order it was
     *   asked for them.
     */
    private fun grantEverything(
        sessionId: String,
        token: String,
        without: String? = null,
        hostileArchive: Boolean = false,
    ): List<String> {
        val asked = mutableListOf<String>()
        while (true) {
            val poll = client(token).pollCredentialProvider(sessionId, 1_000L)
            if (poll !is ServiceAnswer.Answered) return asked
            val state = poll.value
            if (state !is ServiceCredentialPoll.Asked) continue
            asked.add(state.credentialId)
            if (state.credentialId == without) {
                client(token).denyCredential(sessionId, state.requestId)
                continue
            }
            val material = materialOf(state.credentialId, state.kind, hostileArchive)
            client(token).grantCredential(sessionId, state.requestId, material)
        }
    }

    /**
     * The material a controller hands over for [credentialId]: the canonical form of the
     * credential, which says what it is.
     *
     * The [kind] is the form the authorization granted, and the material carries that same form:
     * a controller that answered with another one would be answering a question nobody asked.
     */
    private fun materialOf(credentialId: String, kind: String, hostileArchive: Boolean): ByteArray {
        val credential: Credential = when (credentialId) {
            SSH_KEY_ID -> SshPrivateKey(
                id = CredentialsId(SSH_KEY_ID),
                username = "git",
                privateKey = KEY_TEXT.toByteArray(Charsets.UTF_8),
                passphraseRef = LinkedSecretRef(CredentialsId(SSH_PASSPHRASE_ID)),
            )

            SSH_PASSPHRASE_ID -> SecretText(
                id = CredentialsId(SSH_PASSPHRASE_ID),
                bytes = PASSPHRASE.toByteArray(Charsets.UTF_8),
            )

            CERT_ID -> Certificate(
                id = CredentialsId(CERT_ID),
                keystore = KEYSTORE.copyOf(),
                passwordRef = LinkedSecretRef(CredentialsId(CERT_PASSWORD_ID)),
                alias = "deploy",
            )

            CERT_PASSWORD_ID -> SecretText(
                id = CredentialsId(CERT_PASSWORD_ID),
                bytes = PASSWORD.toByteArray(Charsets.UTF_8),
            )

            ARCHIVE_ID -> Zip(
                id = CredentialsId(ARCHIVE_ID),
                entries = if (hostileArchive) {
                    mapOf(ESCAPING_ENTRY to "the entry that leaves the root".toByteArray(Charsets.UTF_8))
                } else {
                    mapOf(ARCHIVE_ENTRY to ARCHIVE_CONTENT.toByteArray(Charsets.UTF_8))
                },
            )

            else -> error("the controller holds no credential named '$credentialId'")
        }
        assertEquals(
            kind,
            CredentialMaterialCodec.kindOf(credential),
            "the form of the answer is the form the authorization granted",
        )
        return CredentialMaterialCodec.encode(credential)
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

    /**
     * The stdout and the stderr of [runId] as ONE text.
     *
     * The transcript carries the output in the pieces the child wrote it, and a piece can end
     * in the middle of a line: the length of a secret and the name of a variable are facts of
     * the run, not of the way the bytes were read. The line breaks are what the run wrote; the
     * breaks BETWEEN the pieces are not, and they are removed here.
     */
    private fun flatOutput(runId: String): String = outputOf(runId).replace("\n", "")

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
                setOf(
                    CredentialGrant(CREDENTIAL_ID, BrokeredCredentialKind.STRING),
                    CredentialGrant(SSH_KEY_ID, BrokeredCredentialKind.PRIVATE_KEY),
                    CredentialGrant(SSH_PASSPHRASE_ID, BrokeredCredentialKind.STRING),
                    CredentialGrant(CERT_ID, BrokeredCredentialKind.KEYSTORE),
                    CredentialGrant(CERT_PASSWORD_ID, BrokeredCredentialKind.STRING),
                    CredentialGrant(ARCHIVE_ID, BrokeredCredentialKind.ARCHIVE),
                ),
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

        /** The name of the script that binds a private key and a certificate. */
        private const val MULTIPART_SCRIPT_NAME: String = "multipart.pipeline.kts"

        /** The name of the script that binds an archive. */
        private const val ARCHIVE_SCRIPT_NAME: String = "archive.pipeline.kts"

        /** The credential the controller holds as a private key. */
        private const val SSH_KEY_ID: String = "ssh-key"

        /** The credential the controller holds as the passphrase of the key. */
        private const val SSH_PASSPHRASE_ID: String = "ssh-passphrase"

        /** The credential the controller holds as a keystore. */
        private const val CERT_ID: String = "deploy-cert"

        /** The credential the controller holds as the password of the keystore. */
        private const val CERT_PASSWORD_ID: String = "cert-password"

        /** The credential the controller holds as an archive. */
        private const val ARCHIVE_ID: String = "deploy-archive"

        /** The entry the well-behaved archive carries. */
        private const val ARCHIVE_ENTRY: String = "entry.txt"

        /** What the entry of the archive holds: the secret the transcript must not carry. */
        private const val ARCHIVE_CONTENT: String = "the content of the entry"

        /** The entry that leaves the root of the archive, which is refused. */
        private const val ESCAPING_ENTRY: String = "../escape.txt"

        /** What the step of the archive prints first: the step ran. */
        private const val STEP_MARK: String = "the-step-ran"

        /** The private key the controller holds, in the shape a PEM has. */
        private val KEY_TEXT: String = "-----BEGIN OPENSSH PRIVATE KEY-----\n" +
            "b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW\n" +
            "-----END OPENSSH PRIVATE KEY-----\n"

        /** The body of the key: the record the transcript would carry if nothing masked it. */
        private const val KEY_BODY: String =
            "b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW"

        /** The passphrase of the key, which is a credential of its own. */
        private const val PASSPHRASE: String = "the-passphrase-of-the-remote-key"

        /** The keystore the controller holds: bytes that are not text. */
        private val KEYSTORE: ByteArray = ByteArray(24) { (it + 1).toByte() }

        /** The password of the keystore, which is a credential of its own. */
        private const val PASSWORD: String = "the-password-of-the-remote-cert"

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
