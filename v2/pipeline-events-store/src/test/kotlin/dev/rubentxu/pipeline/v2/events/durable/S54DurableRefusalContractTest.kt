package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.domain.identity.ResourceRef
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs
import dev.rubentxu.pipeline.v2.events.PluginEventEmitted
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.UndecodableEventRecordException
import dev.rubentxu.pipeline.v2.events.UndecodableReason
import dev.rubentxu.pipeline.v2.events.identity.EventCursor
import dev.rubentxu.pipeline.v2.events.identity.EventHistoryReader
import dev.rubentxu.pipeline.v2.events.identity.EventPage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.Connection
import java.time.Instant

/**
 * S5.4 / R1, R2, R4, R5, R7 — the durable refusal contract against the store that can actually
 * produce one.
 *
 * ## Why this is SQLite-only and not a parameterised store test
 *
 * [EventHistoryContractTest] runs the same scenarios against InMemory and SQLite, which is the right
 * shape for parity. It cannot be the shape here: `InMemoryEventStore.readRecords` inherits the
 * `eventsFor` default and therefore CANNOT refuse, because its rows are `DomainEvent` values that
 * were never text. A parameterised 40/41/42 would pass the in-memory arm without having tested
 * anything, which is the reason this is a separate class with one store named in its KDoc.
 *
 * ## What the unreadable rows are
 *
 * No public API writes a row this runtime cannot read — that is the point of such a row. It stands
 * for history written by a newer runtime (unknown kind) or for corruption (a known kind whose payload
 * is not its schema). The fixture writes the readable rows through [SqliteEventStore.append] and the
 * unreadable ones over the store's own public `underlyingConnectionFactory()`, so nothing about the
 * production write path is bypassed except for the one row that the write path cannot express.
 *
 * ## The mutation that must kill this
 *
 * The store-side falsifiers are already spent and were measured in the previous commit: deleting
 * `refusals = slice.refusals`, projecting the `Undecodable` as an envelope, advancing the cursor by
 * decoded rows, and adding the field without touching `readAfter`. What this class adds is the half
 * those could not reach — the real SQL cut, a real `UnknownKind`, and a real plugin row — so the
 * assertions below are about the store's own behaviour rather than about a double's.
 */
@DisplayName("S5.4 — el store que puede rehusar: 40, 41 ilegible, 42")
class S54DurableRefusalContractTest {

    private val runId = "01987654-3210-fedc-ba98-76543210fedc"
    private val run: ResourceRef = ResourceRefs.run(runId)
    private val at: Instant = Instant.parse("2026-01-01T00:00:00Z")

    private fun db(dir: Path, name: String = "events.db"): String = dir.resolve(name).toString()

    /** Readable rows, written the way the product writes them. */
    private fun appendValid(dbPath: String, count: Int, from: Int = 0) {
        SqliteEventStore(dbPath).use { store ->
            repeat(count) { index ->
                store.append(
                    RunStarted(
                        eventId = "e${from + index}",
                        runId = runId,
                        sequence = 0L,
                        occurredAt = at,
                        scriptPath = "/p.pipeline.kts",
                    ),
                )
            }
        }
    }

    /**
     * One durable row this runtime refuses.
     *
     * @param kind a kind with no decoder here yields `UnknownKind`; a known kind with a payload that
     *   is not its schema yields `MalformedPayload`. The two stay distinguishable on the wire because
     *   version skew and corruption are different facts for whoever reads them.
     */
    private fun insertUnreadable(dbPath: String, sequence: Long, kind: String, payload: String) {
        SqliteEventStore(dbPath).use { store ->
            store.underlyingConnectionFactory()().use { conn: Connection ->
                conn.prepareStatement(
                    "INSERT INTO events (event_id, run_id, sequence, kind, occurred_at, payload) " +
                        "VALUES (?, ?, ?, ?, ?, ?)"
                ).use { ps ->
                    ps.setString(1, "e-unreadable-$sequence")
                    ps.setString(2, runId)
                    ps.setLong(3, sequence)
                    ps.setString(4, kind)
                    ps.setString(5, at.toString())
                    ps.setString(6, payload)
                    ps.executeUpdate()
                }
            }
        }
    }

    /**
     * The mandatory case, at the numbers it names: row 41 is unreadable, with readable history on
     * both sides of it.
     *
     * A first draft of this fixture read `appendValid(path, 40)` as "one valid event at sequence 40",
     * and asserted `envelopes == [40, 42]`. It is not: the store assigns sequences from 1, so that
     * writes rows 1..40 and the page legitimately carries forty-one envelopes. The SHAPE the case is
     * about is unchanged — readable history, then an unreadable row at 41, then readable history —
     * and it is actually the stronger shape, because the refusal now sits after forty readable rows
     * rather than after one, so a consumer cannot mistake a short page for a long one.
     */
    private fun fortyOneMiddle(@TempDir dir: Path): String {
        val path = db(dir)
        appendValid(path, 40)                                     // sequences 1..40
        insertUnreadable(path, 41L, "plugin.from.the.future", "{\"anything\":1}")
        appendValid(path, 1, from = 900)                          // sequence 42
        return path
    }

    private fun readerOver(dbPath: String): EventHistoryReader =
        EventHistoryReader(SqliteEventStore(dbPath))

    @Test
    fun `la fila 41 ilegible no desaparece y la pagina entera sigue en orden`(@TempDir dir: Path) {
        val path = fortyOneMiddle(dir)

        SqliteEventStore(path).use { store ->
            val page = EventHistoryReader(store).readAfter(run, null, limit = 100)

            assertEquals(
                (1L..40L).toList() + 42L,
                page.envelopes.map { it.sequence },
                "every readable row decodes in sequence order, and 41 is absent rather than invented",
            )
            assertEquals(listOf(41L), page.refusals.map { it.sequence }, "and the consumer must be told 41 exists")
            assertEquals(
                UndecodableReason.UnknownKind("plugin.from.the.future"),
                page.refusals.single().reason,
                "the store's own classification, not a flattened string",
            )
            assertEquals(
                "plugin.from.the.future",
                page.refusals.single().kind,
                "identity comes from the row's COLUMNS, which survive a payload this runtime cannot parse",
            )
            assertEquals(42L, page.nextCursor!!.lastSequence, "the cursor is the store's row position")
        }
    }

    @Test
    fun `pagar de uno en uno cruza la fila ilegible sin perdidas ni duplicados`(@TempDir dir: Path) {
        val path = fortyOneMiddle(dir)

        SqliteEventStore(path).use { store ->
            val reader = EventHistoryReader(store)
            val seenRows = mutableListOf<Long>()
            val refused = mutableListOf<Long>()
            var cursor: EventCursor? = null

            while (true) {
                val page = reader.readAfter(run, cursor, limit = 1)
                seenRows += page.envelopes.map { it.sequence }
                refused += page.refusals.map { it.sequence }
                cursor = page.nextCursor
                if (!page.hasMore) break
            }

            assertEquals((1L..40L).toList() + 42L, seenRows, "one row per page, in order, and none delivered twice")
            assertEquals(listOf(41L), refused, "the refusal appears exactly once, in the page that contains it")
            assertEquals(
                (1L..42L).toList(),
                (seenRows + refused).sorted(),
                "every durable row was accounted for exactly once: none lost, none duplicated",
            )
        }
    }

    @Test
    fun `readSlice sigue lanzando - el modo estricto no se relaja para que la vertical pase`(@TempDir dir: Path) {
        val path = fortyOneMiddle(dir)

        SqliteEventStore(path).use { store ->
            val failure = assertThrows(UndecodableEventRecordException::class.java) {
                store.readSlice(runId, null, limit = 100)
            }
            assertEquals(41L, failure.sequence, "the strict mode still names the row it refuses")
        }
    }

    @Test
    fun `un evento de plugin se lee tipado y uno desconocido no se inventa`(@TempDir dir: Path) {
        val path = db(dir, "plugin.db")
        SqliteEventStore(path).use { store ->
            store.append(
                PluginEventEmitted(
                    eventId = "p1",
                    runId = runId,
                    sequence = 0L,
                    occurredAt = at,
                    registryKind = "acme.validated",
                    schemaVersion = 1,
                    payload = "{\"id\":\"a-1\"}",
                    emittedBy = "acme.plugin",
                ),
            )
            store.append(
                RunStarted(
                    eventId = "e1",
                    runId = runId,
                    sequence = 0L,
                    occurredAt = at,
                    scriptPath = "/p.pipeline.kts",
                ),
            )
        }
        insertUnreadable(path, 3L, "acme.future.event", "{\"id\":\"a-2\"}")

        SqliteEventStore(path).use { store ->
            val page = EventHistoryReader(store).readAfter(run, null, limit = 100)

            assertEquals(2, page.envelopes.size, "a built-in event and a plugin event both decode")

            val plugin = page.envelopes.single { it.kind == "PluginEventEmitted" }
            assertEquals(1L, plugin.sequence, "the plugin event keeps its store-assigned position")

            assertEquals(
                listOf(3L),
                page.refusals.map { it.sequence },
                "a registry kind that is not a core event refuses instead of becoming a fabricated one",
            )
            assertEquals(
                UndecodableReason.UnknownKind("acme.future.event"),
                page.refusals.single().reason,
            )
            assertTrue(
                page.envelopes.none { it.kind == "acme.future.event" },
                "an unknown kind must never reach the envelope stream under any name",
            )
        }
    }

    @Test
    fun `una pagina que termina en rechazo deja el cursor despues de el`(@TempDir dir: Path) {
        val path = db(dir, "trailing.db")
        appendValid(path, 40)
        insertUnreadable(path, 41L, "RunStarted", "{ not the RunStarted schema")

        SqliteEventStore(path).use { store ->
            val page: EventPage = EventHistoryReader(store).readAfter(run, null, limit = 100)

            assertEquals((1L..40L).toList(), page.envelopes.map { it.sequence })
            assertEquals(41L, page.refusals.single().sequence)
            assertEquals(
                41L,
                page.nextCursor!!.lastSequence,
                "the last ROW is the unreadable one, so the continuation is 41 and not 40 — otherwise " +
                    "every resume re-reads row 41 for ever",
            )
            val reason = page.refusals.single().reason
            assertInstanceOf(
                UndecodableReason.MalformedPayload::class.java,
                reason,
                "a known kind with an unreadable payload is corruption, not version skew",
            )
            assertTrue(
                reason is UndecodableReason.MalformedPayload &&
                    reason.detail.contains("eventId"),
                "the refusal must name WHICH field could not be read. This row's payload " +
                    "(`{ not the RunStarted schema`) is an object rather than the stored array " +
                    "shape, so decoding reaches the envelope and stops at the first absent field — " +
                    "and the named field is now the decoder's own statement of the cause, not an " +
                    "echo of bytes it could not parse. The kind lives in its own column and is " +
                    "classified by [decodeStoredRow], which is why the reason here is " +
                    "MalformedPayload and not UnknownKind. " +
                    "got detail: ${(reason as? UndecodableReason.MalformedPayload)?.detail}",
            )
        }
    }
}
