package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.durable.EventStoreAdmission
import dev.rubentxu.pipeline.v2.events.durable.EventStorePurpose
import dev.rubentxu.pipeline.v2.events.durable.admitEventStore
import dev.rubentxu.pipeline.v2.service.ServiceAnswer
import dev.rubentxu.pipeline.v2.service.ServiceClient
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.time.Duration
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * The secret of the service, over a real socket, with the service running.
 *
 * ## The guarantee under trial
 *
 * That the token that opens the service opens it and nothing else, and that
 * the service does not PUBLISH it while it does: the token travels in the
 * file the operator named, it opens the socket, and it appears in none of
 * the lines the service prints.
 *
 * ## The capacity this serves
 *
 * The service prints its boundary when it starts, so that the operator knows
 * what he is talking to. A boundary that carries the secret in clear is a
 * boundary that publishes the secret to every log the line travels through,
 * and the line the operator reads on his terminal is the line everybody
 * else reads in the log.
 *
 * ## Why this instrument is an INTEGRATION test
 *
 * The unit lane already knows that the TEXT of an authorization says nothing
 * of the token. What only a running service can say is whether the token
 * that opens it is the same token that is kept out of everything the service
 * says. That is a fact about a real process, a real file, a real socket and
 * a real client, so the instrument has to have all four.
 *
 * ## What this does not freeze
 *
 * The format of the file of the authorizations: it is written here by hand,
 * as an operator writes it, and the rows say that the service reads what an
 * operator wrote. The scope and the rights are not under trial either.
 */
@Timeout(60)
class ServiceAuthorizationSecretIntegrationTest {

    @TempDir
    lateinit var root: Path

    /** The durable store the service reads. */
    private lateinit var db: Path

    /** The socket the service listens on. */
    private lateinit var socket: Path

    /** The file the operator named and the service reads the token from. */
    private lateinit var authorizationFile: Path

    /** What the service prints when it starts. */
    private val outBytes = ByteArrayOutputStream()

    /** What the service prints when it refuses. */
    private val errBytes = ByteArrayOutputStream()

    @BeforeEach
    fun material() {
        db = root.resolve("store.db")
        socket = root.resolve("service.sock")
        authorizationFile = root.resolve("authorizations-of-the-operator")
        val workspace = Files.createDirectories(root.resolve("workspace"))
        val admission = admitEventStore(db.toString(), EventStorePurpose.Produce)
        val store = (admission as EventStoreAdmission.Admitted).store
        store.append(
            RunStarted(
                eventId = "event-1",
                runId = RUN_ID,
                sequence = 0L,
                occurredAt = Instant.EPOCH,
                scriptPath = workspace.resolve("pipeline.kts").toString(),
            ),
        )
        store.flush()
        store.close()
        // The file an operator writes by hand: a token, a label and a scope.
        // It is `0600` because a file any local user can read guards nothing,
        // and the service refuses to start with one that is.
        Files.writeString(authorizationFile, "$TOKEN operator all\n")
        Files.setPosixFilePermissions(
            authorizationFile,
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
        )
    }

    @Test
    fun `a client that presents the token of the file is answered`() {
        start()

        val answer = ServiceClient(socket, TOKEN, Duration.ofSeconds(10)).version()

        assertTrue(
            answer is ServiceAnswer.Answered,
            "the token of the file opened the service: $answer",
        )
    }

    @Test
    fun `a client that presents another token is refused by name`() {
        start()

        val answer = ServiceClient(socket, "token-of-another-client", Duration.ofSeconds(10)).version()

        assertTrue(
            answer is ServiceAnswer.Refused,
            "the token of the file is the one that opens the service, and no other: $answer",
        )
    }

    @Test
    fun `nothing the service prints carries the token`() {
        start()
        ServiceClient(socket, TOKEN, Duration.ofSeconds(10)).version()

        val printed = outBytes.toString(Charsets.UTF_8) + errBytes.toString(Charsets.UTF_8)

        assertTrue(
            printed.contains("authorizations 1 in"),
            "the service declared its boundary, so what is checked is what it said: $printed",
        )
        assertFalse(
            printed.contains(TOKEN),
            "the token opened the service and it is in none of the lines the service says: $printed",
        )
    }

    /**
     * Starts the service over a real socket and waits until it listens.
     *
     * The verb occupies the process, so it runs in its own thread and the
     * rows wait for the line that says the socket is bound.
     */
    private fun start() {
        val out = PrintStream(outBytes, true, Charsets.UTF_8.name())
        val err = PrintStream(errBytes, true, Charsets.UTF_8.name())
        Thread(
            {
                runServeVerb(
                    arrayOf(
                        "--db", db.toString(),
                        "--control-root", root.toString(),
                        "--socket", socket.toString(),
                        "--authorization-file", authorizationFile.toString(),
                    ),
                    out,
                    err,
                )
            },
            "service-under-test",
        ).apply {
            isDaemon = true
            start()
        }
        awaitListening()
    }

    /**
     * Waits for the line that says the service listens, and fails with what
     * the service said when it does not come.
     */
    private fun awaitListening() {
        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        while (System.nanoTime() < deadline) {
            if (outBytes.toString(Charsets.UTF_8).contains("service listening on")) return
            val refusal = errBytes.toString(Charsets.UTF_8)
            if (refusal.isNotEmpty()) {
                fail<Unit>("the service refused to start: $refusal")
            }
            Thread.sleep(RETRY_MILLIS)
        }
        fail<Unit>(
            "the service did not start: ${outBytes.toString(Charsets.UTF_8)}" +
                errBytes.toString(Charsets.UTF_8),
        )
    }

    private companion object {
        /** The run the store holds, so the service has something to answer about. */
        private const val RUN_ID: String = "run-authorization-secret-1"

        /** The token the operator wrote in the file. */
        private const val TOKEN: String = "token-marker-of-the-integration-lane"

        /** How long a row waits between two looks, in milliseconds. */
        private const val RETRY_MILLIS: Long = 50L
    }
}
