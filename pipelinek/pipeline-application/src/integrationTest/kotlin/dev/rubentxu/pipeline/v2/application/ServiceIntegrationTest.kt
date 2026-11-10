package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.durable.DurableStores
import dev.rubentxu.pipeline.v2.events.durable.EventStoreAdmission
import dev.rubentxu.pipeline.v2.events.durable.EventStorePurpose
import dev.rubentxu.pipeline.v2.events.durable.admitEventStore
import dev.rubentxu.pipeline.v2.runtime.durable.LeaseRequest
import dev.rubentxu.pipeline.v2.runtime.durable.RunOwnerId
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import dev.rubentxu.pipeline.v2.service.ServiceProtocol
import dev.rubentxu.pipeline.v2.service.ServiceSubscriptionRequest

/**
 * The local service, over a real Unix domain socket, over a real store.
 *
 * ## The guarantee under trial
 *
 * The service answers what the command line answers about the SAME run, over
 * the SAME store, and it answers it to a client that speaks a protocol with a
 * version and a vocabulary of names. Three facts have to hold together: the
 * socket is really a socket and really private, a client that misbehaves gets
 * a name instead of a closed connection, and NOTHING the service reads is
 * changed by the reading.
 *
 * ## The capacity this serves
 *
 * A supervisor that watches a machine's runs has to trust the socket it reads.
 * It can only trust it if the socket is one the operator alone can open, if
 * the service says what it cannot do instead of going quiet, and if asking
 * about a run leaves the run exactly as it was. A service that writes to the
 * substrate it serves is a service whose answers cannot be trusted.
 *
 * ## Why this instrument is an INTEGRATION test
 *
 * The unit lane already knows what the answers ARE: the codec over lines, and
 * the operations over a faked port. What only a real machine can say is
 * whether the socket binds, whether its mode is the one the design promises,
 * whether a leftover from a crash is told apart from a file that belongs to
 * somebody else, and whether the reading leaves the tree untouched. Those are
 * facts about the filesystem and about a socket, so the instrument has to have
 * a real one.
 *
 * The only thing faked here is the world OUTSIDE the product: the temporary
 * directory the material lives in, and the clock, which does not move.
 *
 * ## What is NOT here, and why
 *
 * The published distribution is not started: that belongs to the harness that
 * certifies the released artifact. What this lane asserts is the seam that
 * harness will exercise — the service, its socket and its substrate.
 *
 * ## Why one class holds so many rows
 *
 * Every row needs the same expensive fixture —a materialised store, a socket
 * path and a started service— and the rows are independent facts about it.
 * Splitting the class would copy the fixture into each half, and a fixture
 * that exists twice drifts. `TooManyFunctions` counts rows and helpers, and
 * neither is a sign of a class doing too much.
 */
@Suppress("TooManyFunctions")
class ServiceIntegrationTest {

    @TempDir
    lateinit var root: Path

    /** The durable store the service reads. */
    private lateinit var db: Path

    /** The socket the service listens on. */
    private lateinit var socket: Path

    private val runId = "run-service-1"

    private val clock: Clock = FixedClock

    @BeforeEach
    fun material() {
        db = root.resolve("store.db")
        socket = root.resolve("service.sock")
        val workspace = Files.createDirectories(root.resolve("workspace"))
        val admission = admitEventStore(db.toString(), EventStorePurpose.Produce)
        val store = (admission as EventStoreAdmission.Admitted).store
        store.append(
            RunStarted(
                eventId = "event-1",
                runId = runId,
                sequence = 0L,
                occurredAt = Instant.EPOCH,
                scriptPath = workspace.resolve("pipeline.kts").toString(),
            ),
        )
        store.append(
            RunFinished(
                eventId = "event-2",
                runId = runId,
                sequence = 0L,
                occurredAt = Instant.EPOCH,
                outcome = "success",
                diagnostics = emptyList(),
            ),
        )
        // A run that never finishes: a street on it stays open, which is what
        // a client that watches a live run needs.
        store.append(
            RunStarted(
                eventId = "event-3",
                runId = LIVE_RUN_ID,
                sequence = 0L,
                occurredAt = Instant.EPOCH,
                scriptPath = workspace.resolve("pipeline.kts").toString(),
            ),
        )
        store.flush()
        val stores = DurableStores.open(store, root, clock)
        val owner = requireNotNull(RunOwnerId.of("owner-of-the-fixture"))
        val identity = requireNotNull(RunId.of(runId))
        stores.lease.acquire(LeaseRequest(runId = identity, ownerId = owner))
        stores.lease.release(owner)
        store.close()
    }

    @Test
    fun `the client and the service agree on the version of the protocol`() {
        val service = start()

        val answer = service.ask("""{"schema":"pipelinek.service/2","op":"version"}""")

        assertEquals(ServiceProtocol.SCHEMA, answer["schema"]?.jsonPrimitive?.content)
        assertEquals("answer", answer["kind"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a client that presents no credential is refused by name over the socket`() {
        val service = start()

        val answer = service.askRaw("""{"schema":"pipelinek.service/2","op":"version"}""")

        assertEquals("refused", answer["kind"]?.jsonPrimitive?.content)
        assertEquals(
            "not-authorized",
            answer["refusal"]?.jsonObject?.get("kind")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `a run whose id looks like a path is refused by name over the socket`() {
        val service = start()

        val answer = service.ask(
            """{"schema":"pipelinek.service/2","op":"run.state","runId":"../../etc/passwd"}""",
        )

        assertEquals("refused", answer["kind"]?.jsonPrimitive?.content)
        assertEquals(
            "malformed-request",
            answer["refusal"]?.jsonObject?.get("kind")?.jsonPrimitive?.content,
            "a run id that is not a run id is refused by name, and not by a closed connection",
        )
    }

    @Test
    fun `a subscription for a run whose id looks like a path is refused by name over the socket`() {
        val service = start()

        val frames = service.subscribe(
            """{"schema":"pipelinek.service/2","op":"output.follow","runId":"../../etc/passwd"}""",
        )

        assertEquals(1, frames.size, "the street was refused before it delivered anything")
        assertEquals("refused", frames[0]["kind"]?.jsonPrimitive?.content)
        assertEquals(
            "malformed-request",
            frames[0]["refusal"]?.jsonObject?.get("kind")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `a client that speaks another protocol is refused by name`() {
        val service = start()

        val answer = service.ask("""{"schema":"pipelinek.service/9","op":"version"}""")

        assertEquals("refused", answer["kind"]?.jsonPrimitive?.content)
        assertEquals(
            "incompatible-protocol",
            answer["refusal"]?.jsonObject?.get("kind")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `the state of a run that finished is the state the command line reports`() {
        val service = start()

        val answer = service.ask(
            """{"schema":"pipelinek.service/2","op":"run.state","runId":"$runId"}""",
        )

        assertEquals("answer", answer["kind"]?.jsonPrimitive?.content)
        val result = answer["result"]?.jsonObject
        assertEquals("terminal", result?.get("state")?.jsonPrimitive?.content)
        assertEquals("succeeded", result?.get("outcome")?.jsonPrimitive?.content)
    }

    @Test
    fun `a run nobody knows is refused over the socket by name`() {
        val service = start()

        val answer = service.ask(
            """{"schema":"pipelinek.service/2","op":"run.state","runId":"run-nobody-wrote"}""",
        )

        assertEquals("refused", answer["kind"]?.jsonPrimitive?.content)
        assertEquals(
            "unknown-run",
            answer["refusal"]?.jsonObject?.get("kind")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `the socket and its directory belong to the operator alone`() {
        start()

        assertEquals(
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
            ),
            Files.getPosixFilePermissions(socket),
            "the socket is readable and writable by its owner and by nobody else",
        )
        assertEquals(
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
            Files.getPosixFilePermissions(root),
            "the directory of the socket is private, so the socket cannot be found",
        )
    }

    @Test
    fun `a socket left over from a crash is replaced and the service answers`() {
        // The JDK does not delete the socket file when the channel closes, so
        // a crash leaves one behind. This is the NORMAL case, not the exotic
        // one: it is what the next start finds.
        val abandoned = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        abandoned.bind(UnixDomainSocketAddress.of(socket))
        abandoned.close()
        assertTrue(Files.exists(socket), "the leftover is the file the crash left behind")

        val service = start()

        val answer = service.ask("""{"schema":"pipelinek.service/2","op":"version"}""")
        assertEquals("answer", answer["kind"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a service that is already listening is not displaced and its socket is not deleted`() {
        val listening = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        listening.bind(UnixDomainSocketAddress.of(socket))
        try {
            val refusal = assertThrowsServiceStartup {
                UnixSocketTransport.bind(socket)
            }

            assertEquals(ServiceStartupRefusal.AnotherServiceIsListening, refusal.refusal)
            assertTrue(Files.exists(socket), "the socket of the service that is running stays")
        } finally {
            listening.close()
        }
    }

    @Test
    fun `a service without a substrate refuses to start and says why`() {
        val outBytes = ByteArrayOutputStream()
        val errBytes = ByteArrayOutputStream()
        val out = PrintStream(outBytes, true, Charsets.UTF_8.name())
        val err = PrintStream(errBytes, true, Charsets.UTF_8.name())

        val exit = runServeVerb(
            arrayOf(
                "--db",
                root.resolve("there-is-no-store.db").toString(),
                "--control-root",
                root.toString(),
                "--socket",
                root.resolve("never.sock").toString(),
            ),
            out,
            err,
        )
        out.flush()
        err.flush()

        assertEquals(
            4,
            exit.code,
            "a service that cannot read its substrate refuses with the runtime-not-available code",
        )
        assertTrue(
            errBytes.toString(Charsets.UTF_8).contains("the substrate cannot be served"),
            "the refusal says that the substrate is the problem: ${errBytes}",
        )
        assertFalse(
            Files.exists(root.resolve("never.sock")),
            "a service that did not start leaves no socket behind",
        )
    }

    @Test
    fun `a path that is not a socket is neither served nor deleted`() {
        val foreign = root.resolve("not-a-socket")
        Files.writeString(foreign, "a file that belongs to somebody else")

        val refusal = assertThrowsServiceStartup {
            UnixSocketTransport.bind(foreign)
        }

        assertEquals(ServiceStartupRefusal.SocketPathIsNotASocket, refusal.refusal)
        assertEquals(
            "a file that belongs to somebody else",
            Files.readString(foreign),
            "the file of somebody else is still there, with its content",
        )
    }

    @Test
    fun `a client that connects and says nothing is closed`() {
        val service = start(limits = ServiceLimits(readDeadlineMillis = 300L))
        try {
            SocketChannel.open(UnixDomainSocketAddress.of(socket)).use { channel ->
                val buffer = ByteArray(64)
                val read = channel.read(ByteBuffer.wrap(buffer))

                assertEquals(
                    -1,
                    read,
                    "the service closes a client that does not finish its line, and does not wait for it",
                )
            }
        } finally {
            service.stop()
        }
    }

    @Test
    fun `asking about a run changes nothing of the substrate`() {
        val service = start()

        // The photo is taken with the service ALREADY serving, because opening
        // the store is a fact of its own: SQLite puts its WAL sidecars next to
        // the database when the connection opens. Those sidecars are the
        // store being open, not the store being written, and a measurement
        // that took the photo before the service started would be measuring
        // the opening.
        val before = treeOf(root)
        val storeBefore = Files.readAllBytes(db)

        service.ask("""{"schema":"pipelinek.service/2","op":"run.state","runId":"$runId"}""")
        service.ask("""{"schema":"pipelinek.service/2","op":"health"}""")

        assertEquals(
            before,
            treeOf(root),
            "asking about a run left something behind in the tree of the control root",
        )
        assertTrue(
            storeBefore.contentEquals(Files.readAllBytes(db)),
            "asking about a run changed the bytes of the store",
        )
    }

    @Test
    fun `the socket is deleted when the service stops`() {
        val service = start()
        assertTrue(Files.exists(socket), "the socket is there while the service serves")

        service.stop()

        assertFalse(Files.exists(socket), "the socket of a service that stopped is not left behind")
    }

    @Test
    fun `a client that subscribes to the events of a run receives them and the end of the street`() {
        val service = start()

        val frames = service.subscribe(
            """{"schema":"pipelinek.service/2","op":"events.follow","runId":"$runId"}""",
        )

        assertEquals(3, frames.size, "the two events of the run and the end of the street: $frames")
        assertEquals("record", frames[0]["kind"]?.jsonPrimitive?.content)
        assertEquals("record", frames[1]["kind"]?.jsonPrimitive?.content)
        assertEquals("end", frames[2]["kind"]?.jsonPrimitive?.content)
        assertEquals(
            "terminal-observed",
            frames[2]["reason"]?.jsonPrimitive?.content,
            "the end says that the terminal fact was observed, and nothing more about the run",
        )
    }

    @Test
    fun `a client that resumes from the last cursor receives the end of the street and no record`() {
        val service = start()

        val first = service.subscribe(
            """{"schema":"pipelinek.service/2","op":"events.follow","runId":"$runId"}""",
        )
        val cursor = first.last()["cursor"]?.jsonPrimitive?.content

        val second = service.subscribe(
            """{"schema":"pipelinek.service/2","op":"events.follow","runId":"$runId","after":"$cursor"}""",
        )

        assertEquals(1, second.size, "the street had nothing left to deliver: $second")
        assertEquals("end", second[0]["kind"]?.jsonPrimitive?.content)
        assertEquals("terminal-observed", second[0]["reason"]?.jsonPrimitive?.content)
    }

    @Test
    fun `two clients of the same run do not see each other`() {
        val service = start()

        val fromTheStart = service.subscribe(
            """{"schema":"pipelinek.service/2","op":"events.follow","runId":"$runId"}""",
        )
        val fromTheEnd = service.subscribe(
            """{"schema":"pipelinek.service/2","op":"events.follow","runId":"$runId",""" +
                """"after":"${fromTheStart.last()["cursor"]?.jsonPrimitive?.content}"}""",
        )

        assertEquals(
            3,
            fromTheStart.size,
            "the first client received the whole history and the end",
        )
        assertEquals(
            1,
            fromTheEnd.size,
            "the second client received only the end, because that is all that was left",
        )
    }

    @Test
    fun `a client that stops reading does not stop the service from answering another client`() {
        val service = start()
        SocketChannel.open(UnixDomainSocketAddress.of(socket)).use { slow ->
            slow.write(
                ByteBuffer.wrap(
                    (withCredential(
                        """{"schema":"pipelinek.service/2","op":"events.follow","runId":"run-service-live"}""",
                    ) + "\n").toByteArray(Charsets.UTF_8),
                ),
            )
            // The slow client reads ONE frame and then stops reading: its street is
            // live and it is not listening any more.
            readLineOrNull(slow)

            val answer = service.ask("""{"schema":"pipelinek.service/2","op":"version"}""")

            assertEquals(
                "answer",
                answer["kind"]?.jsonPrimitive?.content,
                "a client that stopped reading holds a thread, not the service",
            )
        }
    }

    /**
     * A client that LEAVES while its street has nothing to say gives its slot back.
     *
     * The street of a live run writes only when the run has a row, so a client
     * that closed its end while the run was quiet is a fact the street never
     * learns by writing. The slot has to come back anyway: the budget is eight,
     * and eight departed clients would otherwise refuse every honest one for as
     * long as the run lasts.
     */
    @Test
    @Timeout(value = 30, unit = java.util.concurrent.TimeUnit.SECONDS)
    fun `a subscriber that leaves while its street is quiet gives its slot back`() {
        val service = start(ServiceLimits(maxSubscriptions = 1))
        try {
            SocketChannel.open(UnixDomainSocketAddress.of(socket)).use { leaver ->
                leaver.write(follow(LIVE_RUN_ID))
                assertNotNull(readLineOrNull(leaver), "the street opened and delivered the first row of the live run")
            }

            val served = awaitSlot(LIVE_RUN_ID)

            assertNotNull(served, "the slot of the client that left came back, so another client was served")
        } finally {
            service.stop()
        }
    }

    /**
     * A client that STOPS READING gives its slot back once the write deadline passes.
     *
     * The street blocks writing to a peer whose buffer is full, and a blocked
     * write holds the thread, the slot and the descriptor for as long as the
     * peer stays connected. A deadline on the write is what turns a stalled
     * client into a finished connection.
     */
    @Test
    @Timeout(value = 30, unit = java.util.concurrent.TimeUnit.SECONDS)
    fun `a subscriber that stops reading gives its slot back once the write deadline passes`() {
        val service = start(
            ServiceLimits(maxSubscriptions = 1, writeDeadlineMillis = 300L),
            streets = FloodStreets,
        )
        try {
            SocketChannel.open(UnixDomainSocketAddress.of(socket)).use { stalled ->
                stalled.write(follow(LIVE_RUN_ID))
                // One byte proves the street took the only slot: a refusal would
                // start with a brace and the flood starts with an x. From then on
                // the client never reads, so the street's write cannot finish.
                val first = ByteBuffer.allocate(1)
                assertEquals(1, stalled.read(first), "the street wrote to the client")
                assertEquals('x'.code.toByte(), first.get(0), "the client was served and not refused")

                val served = awaitSlot(LIVE_RUN_ID)

                assertNotNull(served, "the slot of the client that stopped reading came back after the deadline")
            }
        } finally {
            service.stop()
        }
    }

    /**
     * Clients that stall leave the process with the descriptors it had.
     *
     * The first rounds warm the process up (classes, pools, the watchdog),
     * so what the later rounds measure is growth and not start-up. A leak
     * costs descriptors per client; the margin only absorbs the jitter of
     * the machine.
     */
    @Test
    @Timeout(value = 60, unit = java.util.concurrent.TimeUnit.SECONDS)
    fun `stalled subscribers leave the descriptors of the process where they were`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            Files.isDirectory(DESCRIPTORS),
            "this machine does not list the descriptors of a process",
        )
        val service = start(
            ServiceLimits(maxSubscriptions = 8, writeDeadlineMillis = 200L),
            streets = FloodStreets,
        )
        try {
            repeat(2) { stallRound(clients = 8) }
            val baseline = openDescriptors()

            repeat(4) { stallRound(clients = 8) }

            val settled = settle { openDescriptors() <= baseline + DESCRIPTOR_JITTER }
            assertTrue(
                settled,
                "descriptors after the stalled clients: ${openDescriptors()}, before: $baseline",
            )
        } finally {
            service.stop()
        }
    }

    /**
     * Stopping the service while a client stalls leaves no socket and no worker.
     */
    @Test
    @Timeout(value = 30, unit = java.util.concurrent.TimeUnit.SECONDS)
    fun `stopping the service while a subscriber is stalled leaves no socket and no worker behind`() {
        val before = serviceWorkers()
        val service = start(streets = FloodStreets)
        SocketChannel.open(UnixDomainSocketAddress.of(socket)).use { stalled ->
            stalled.write(follow(LIVE_RUN_ID))
            assertTrue(
                settle { (serviceWorkers() - before).isNotEmpty() },
                "the street took a worker of the service",
            )

            service.stop()

            assertFalse(Files.exists(socket), "the socket is gone")
            assertTrue(
                settle { (serviceWorkers() - before).isEmpty() },
                "the workers of the stopped service ended: ${(serviceWorkers() - before).map { it.name }}",
            )
        }
    }

    @Test
    fun `a cursor of the other plane is refused by name over the socket`() {
        val service = start()

        val frames = service.subscribe(
            """{"schema":"pipelinek.service/2","op":"events.follow","runId":"$runId",""" +
                """"after":"out-frame-ord-v1:3"}""",
        )

        assertEquals(1, frames.size, "the street never opened: $frames")
        assertEquals("refused", frames[0]["kind"]?.jsonPrimitive?.content)
        assertEquals(
            "invalid-cursor",
            frames[0]["refusal"]?.jsonObject?.get("kind")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `a run nobody knows is refused by name over the socket in a subscription`() {
        val service = start()

        val frames = service.subscribe(
            """{"schema":"pipelinek.service/2","op":"events.follow","runId":"run-nobody-wrote"}""",
        )

        assertEquals(1, frames.size, "the street was refused before it delivered anything")
        assertEquals("refused", frames[0]["kind"]?.jsonPrimitive?.content)
        assertEquals(
            "unknown-run",
            frames[0]["refusal"]?.jsonObject?.get("kind")?.jsonPrimitive?.content,
        )
    }

    @Test
    @Timeout(value = 30, unit = java.util.concurrent.TimeUnit.SECONDS)
    fun `the console street refuses a run nobody knows over the socket`() {
        val service = start()

        val frames = service.subscribe(
            """{"schema":"pipelinek.service/2","op":"output.follow","runId":"run-nobody-wrote"}""",
        )

        assertEquals(
            1,
            frames.size,
            "the street was refused before it delivered anything, and it ENDED: $frames",
        )
        assertEquals("refused", frames[0]["kind"]?.jsonPrimitive?.content)
        assertEquals(
            "unknown-run",
            frames[0]["refusal"]?.jsonObject?.get("kind")?.jsonPrimitive?.content,
            "the console street refuses by name, like the events street",
        )
    }

    @Test
    @Timeout(value = 30, unit = java.util.concurrent.TimeUnit.SECONDS)
    fun `a console that is refused gives its slot back and the service keeps serving`() {
        val service = start(ServiceLimits(maxSubscriptions = 1))
        SocketChannel.open(UnixDomainSocketAddress.of(socket)).use { first ->
            first.write(
                ByteBuffer.wrap(
                    (withCredential(
                        """{"schema":"pipelinek.service/2","op":"output.follow","runId":"run-nobody-wrote"}""",
                    ) + "\n").toByteArray(Charsets.UTF_8),
                ),
            )
            // The refusal is the FIRST line: the client learns that the run is unknown
            // before the street ends, and it learns it from the street and not from a
            // socket that closed without a word.
            val refusal = readLineOrNull(first)?.let { Json.parseToJsonElement(it).jsonObject }
            assertNotNull(refusal, "the street wrote the refusal before it ended")
            assertEquals("refused", refusal?.get("kind")?.jsonPrimitive?.content)
            assertEquals(
                "unknown-run",
                refusal?.get("refusal")?.jsonObject?.get("kind")?.jsonPrimitive?.content,
            )
            // The street ended and the server closed the connection, but THIS client keeps
            // its channel open: the budget has to be free even so.
            assertNull(readLineOrNull(first), "the street ended and the connection closed")

            val served = service.subscribe(
                """{"schema":"pipelinek.service/2","op":"events.follow","runId":"$runId"}""",
            )

            assertEquals(
                3,
                served.size,
                "the second client was served, so the refused street gave its slot back: $served",
            )
        }
    }

    /**
     * The service, over a real socket, and the way to ask it and to stop it.
     */
    private inner class Service(
        private val server: ServiceServer,
        private val store: dev.rubentxu.pipeline.v2.events.durable.SqliteEventStore,
    ) {

        /** The thread that serves. */
        private val thread = Thread({ server.serve() }, "service-under-test").apply {
            isDaemon = true
            start()
        }

        /**
         * The answer of [request], read as a document.
         */
        fun ask(request: String): Map<String, kotlinx.serialization.json.JsonElement> =
            askRaw(withCredential(request))

        /**
         * The answer of the line [request], written verbatim.
         *
         * The credential is NOT added: a row that needs a line without one
         * says so, and a row that needs one with it says that too.
         */
        fun askRaw(request: String): Map<String, kotlinx.serialization.json.JsonElement> {
            SocketChannel.open(UnixDomainSocketAddress.of(socket)).use { channel ->
                channel.write(ByteBuffer.wrap((request + "\n").toByteArray(Charsets.UTF_8)))
                val line = readLineOrNull(channel).orEmpty()
                return Json.parseToJsonElement(line).jsonObject
            }
        }

        /**
         * The frames of a subscription, read until the street ends.
         *
         * The street ends by closing the connection, so the read ends with
         * the end of the socket.
         */
        fun subscribe(request: String): List<kotlinx.serialization.json.JsonObject> {
            SocketChannel.open(UnixDomainSocketAddress.of(socket)).use { channel ->
                channel.write(
                    ByteBuffer.wrap((withCredential(request) + "\n").toByteArray(Charsets.UTF_8)),
                )
                val frames = mutableListOf<kotlinx.serialization.json.JsonObject>()
                while (true) {
                    val line = readLineOrNull(channel) ?: break
                    frames.add(Json.parseToJsonElement(line).jsonObject)
                }
                return frames
            }
        }

        /**
         * Stops the service.
         */
        fun stop() {
            server.close()
            thread.join(5_000L)
            store.close()
        }
    }

    /**
     * The line that follows the events of [run], with the credential this service knows.
     */
    private fun follow(run: String): ByteBuffer = ByteBuffer.wrap(
        (withCredential("""{"schema":"pipelinek.service/2","op":"events.follow","runId":"$run"}""") + "\n")
            .toByteArray(Charsets.UTF_8),
    )

    /**
     * The first line of a street that was served, or null when no slot came free.
     *
     * A client that is refused for want of a slot gets a name, and it asks
     * again: what the rows measure is whether the slot of an earlier client
     * ever comes back.
     */
    private fun awaitSlot(run: String): String? {
        val limit = System.nanoTime() + SLOT_PATIENCE_NANOS
        while (System.nanoTime() < limit) {
            SocketChannel.open(UnixDomainSocketAddress.of(socket)).use { client ->
                client.write(follow(run))
                val line = readLineOrNull(client)
                if (line != null && !line.contains(BUDGET_REFUSAL)) return line
            }
            Thread.sleep(SLOT_RETRY_MILLIS)
        }
        return null
    }

    /**
     * [clients] connections that ask for a street and never read it.
     *
     * They stay connected for longer than the write deadline of the rows
     * that use this, and then they leave.
     */
    private fun stallRound(clients: Int) {
        val channels = List(clients) { SocketChannel.open(UnixDomainSocketAddress.of(socket)) }
        try {
            channels.forEach { it.write(follow(LIVE_RUN_ID)) }
            Thread.sleep(STALL_MILLIS)
        } finally {
            channels.forEach { it.close() }
        }
    }

    /** How many descriptors this process holds open. */
    private fun openDescriptors(): Int = Files.list(DESCRIPTORS).use { it.count().toInt() }

    /** The live threads that belong to a service of this product. */
    private fun serviceWorkers(): Set<Thread> = Thread.getAllStackTraces().keys
        .filter { it.isAlive && it.name.startsWith("pipelinek-service") }
        .toSet()

    /** Whether [condition] holds before the patience of the lane runs out. */
    private fun settle(condition: () -> Boolean): Boolean {
        val limit = System.nanoTime() + SLOT_PATIENCE_NANOS
        while (System.nanoTime() < limit) {
            if (condition()) return true
            Thread.sleep(SLOT_RETRY_MILLIS)
        }
        return condition()
    }

    /**
     * A street that writes a frame far bigger than a socket buffer.
     *
     * Whoever does not read it leaves the street blocked in its write, which
     * is the state a stalled client puts the service in.
     */
    private object FloodStreets : ServiceStreets {
        override fun serve(connection: ServiceConnection, request: ServiceSubscriptionRequest) {
            connection.writeLine("x".repeat(FLOOD_BYTES))
        }
    }

    /**
     * Starts the service over a real socket.
     */
    private fun start(limits: ServiceLimits = ServiceLimits(), streets: ServiceStreets? = null): Service {
        val admission = admitEventStore(db.toString(), EventStorePurpose.Read)
        val store = (admission as EventStoreAdmission.Admitted).store
        val operations = ServiceOperations(
            introspection = composeRuntimeIntrospectionPort(
                eventStore = store,
                controlRoot = root,
                clock = clock,
            ),
            substrate = {
                ServiceSubstrate(
                    eventStore = db.toString(),
                    controlRoot = root.toString(),
                    eventStorePresent = Files.exists(db),
                    controlRootPresent = Files.exists(root),
                )
            },
            starts = ServiceIntegrationStarter(),
            cancellations = null,
        )
        val stores = DurableStores.open(store, root, clock)
        val served = streets ?: ServiceSubscription(
            lanes = ComposeLanes.forService(store, root),
            events = composeEventRecordReadPort(store, stores.lease),
        )
        val transport = UnixSocketTransport.bind(socket)
        return Service(ServiceServer(transport, operations, served, authorizations(), limits), store)
    }

    /**
     * The authorizations this service knows.
     *
     * One token with the scope `all`, so a row about the service can ask
     * about anything, and a row about the authorization asks with a token
     * this service does NOT know.
     */
    private fun authorizations(): ServiceAuthorizations = ServiceAuthorizations.of(
        listOf(ServiceAuthorization(TOKEN, "operator", ServiceScope.All)),
    )

    /**
     * The line [request] travels as, with the credential this service knows.
     *
     * The credential travels with EVERY request, so the helper that writes a
     * line adds it: a line written by hand without one would be testing a
     * client the service refuses, which is a different row.
     */
    private fun withCredential(request: String): String =
        request.replaceFirst("{", """{"auth":"$TOKEN",""")

    /**
     * The refusal a refused bind carries.
     */
    private fun assertThrowsServiceStartup(block: () -> Unit): ServiceStartupException = try {
        block()
        throw AssertionError("the bind was accepted and it should not have been")
    } catch (e: ServiceStartupException) {
        e
    }

    /**
     * The line the service wrote, or null when the connection ended.
     */
    private fun readLineOrNull(channel: SocketChannel): String? {
        val buffer = ByteArray(8192)
        val accumulated = ByteArrayOutputStream()
        while (true) {
            val read = channel.read(ByteBuffer.wrap(buffer))
            if (read < 0) return null
            val chunk = buffer.copyOf(read)
            val newline = chunk.indexOf('\n'.code.toByte())
            if (newline >= 0) {
                accumulated.write(chunk, 0, newline)
                break
            }
            accumulated.write(chunk, 0, read)
        }
        return accumulated.toString(Charsets.UTF_8)
    }

    /**
     * The tree of [root], with the size of every file in it.
     */
    private fun treeOf(root: Path): Map<String, Long> {
        val tree = mutableMapOf<String, Long>()
        Files.walk(root).use { paths ->
            paths.forEach { path ->
                tree[path.toString()] = if (Files.isRegularFile(path)) {
                    Files.size(path)
                } else {
                    -1L
                }
            }
        }
        return tree
    }

    /** The clock this lane stamps its material with: it does not move. */
    private object FixedClock : Clock {
        override fun now(): Instant = Instant.EPOCH
    }

    private companion object {
        /** The run that never finishes. */
        private const val LIVE_RUN_ID: String = "run-service-live"

        /** The token this service knows. */
        private const val TOKEN: String = "token-of-the-service"

        /** The descriptors of this process, on a machine that lists them. */
        private val DESCRIPTORS: Path = Path.of("/proc/self/fd")

        /** How many descriptors of difference the machine's own jitter may explain. */
        private const val DESCRIPTOR_JITTER: Int = 3

        /** A frame far bigger than the buffer of a socket. */
        private const val FLOOD_BYTES: Int = 8 * 1024 * 1024

        /** How long a stalled client stays connected, in milliseconds. */
        private const val STALL_MILLIS: Long = 500L

        /** How long a row waits for a fact that has to come true, in nanoseconds. */
        private const val SLOT_PATIENCE_NANOS: Long = 8_000_000_000L

        /** How long a row waits between two looks, in milliseconds. */
        private const val SLOT_RETRY_MILLIS: Long = 50L

        /** The word the service refuses with when the budget of subscriptions is spent. */
        private const val BUDGET_REFUSAL: String = "too-many-subscriptions"
    }
}
