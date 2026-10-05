package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.events.EventRecordRead
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.StageFinished
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.UndecodableEventRecordException
import dev.rubentxu.pipeline.v2.events.UndecodableReason
import dev.rubentxu.pipeline.v2.events.identity.EventCursor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant

/**
 * P3-E E4c — a durable row that exists and cannot be decoded is a REFUSAL, never a gap.
 *
 * ## What this proves, and why the shape is 40 / 41 / 42
 *
 * The read-side used to answer only "did this row decode?" and silently answer the other question
 * the store was actually asked — "what rows exist?" — by omission:
 *
 * ```kotlin
 * // SqliteEventStore.eventsFor, before
 * JsonEventLog.decode(payload).firstOrNull()?.let { yield(it) }
 * ```
 *
 * Three rows where the middle one cannot be read therefore became `40, 42`. Nothing in the output
 * marked the hole, so a consumer could not distinguish "no event 41" from "a record 41 exists and
 * we could not interpret it" — and an external observer reading that stream as history is being
 * lied to by subtraction. Those sequences are the smallest case where the two are distinguishable
 * in the output at all, which is why they are the fixture.
 *
 * E4b.3 is what made this reachable rather than theoretical. It removed the semantic defaults from
 * the decoders, so a row missing `stageResult` or `outcome` now REFUSES by design — the decoders
 * stopped inventing facts and in exchange rows that legitimately cannot be read stopped being
 * impossible. E4b.3 alone would have turned a silent hole into a silent hole with more causes.
 *
 * ## The law under test
 *
 * ```
 * persistence ≠ interpretation
 * ```
 *
 * Every durable row produces EXACTLY ONE of `Decoded` or `Undecodable`. Never zero.
 *
 * ## Harness fidelity
 *
 * HF1 (in-process, real production authority): this enters through `SqliteEventStore.readRecords`
 * and `SqliteEventStore.eventsFor` — the code a real external observer calls — over a real SQLite
 * file. The malformed row is written through a direct JDBC `UPDATE` of the `payload` column, which
 * is the only way to produce a row the store itself would never write; it stands for a payload
 * corrupted after COMMIT, or written by a runtime whose codec differs.
 *
 * HF3/§3 (discrete observations, never duration or size): every assertion below is on a sequence
 * number, a sealed case, or an exception type. Nothing here waits, and nothing scales with time.
 *
 * HF4 (hermetic): `@TempDir`, no ambient cwd or env, no network, no wall-clock, no shared
 * singleton, and the store is closed in a `finally`.
 */
class DurableReadTruthTest {

    @TempDir
    lateinit var tempDir: Path

    private val runId = "e4c-run"
    private val at: Instant = Instant.parse("2026-01-01T00:00:00Z")

    private fun validEvents(): List<dev.rubentxu.pipeline.v2.events.DomainEvent> = listOf(
        RunStarted("e40", runId, 40L, at, "p.kts"),
        StageStarted("e41", runId, 41L, at, 0, "s41"),
        StageFinished("e42", runId, 42L, at, 0, "s41", "success"),
    )

    /**
     * Writes the three rows, then replaces row 41's payload with something no decoder can read.
     *
     * The row's identity COLUMNS are left untouched, and that is the point: a refusal has to be
     * buildable from what storage still holds when the payload is gone.
     */
    private fun storeWithMalformedMiddleRow(
        malformedPayload: String = """[{"eventId":"e41","runId":"$runId","sequence":41,"kind":"StageStarted"}]""",
        kindColumn: String = "StageStarted",
    ): SqliteEventStore {
        val db = tempDir.resolve("e4c-${System.nanoTime()}.db").toString()
        val store = SqliteEventStore(db)
        validEvents().forEach { store.appendAssigned(it) }
        store.flush()
        store.close()

        DriverManager.getConnection("jdbc:sqlite:" + db).use { conn ->
            conn.prepareStatement(
                "UPDATE events SET payload = ?, kind = ? WHERE run_id = ? AND sequence = 41"
            ).use { ps ->
                ps.setString(1, malformedPayload)
                ps.setString(2, kindColumn)
                ps.setString(3, runId)
                ps.executeUpdate()
            }
        }
        return SqliteEventStore(db)
    }

    // ------------------------------------------------------------------
    // The row exists: the read reports it
    // ------------------------------------------------------------------

    @Test
    fun `la fila malformada produce un refusal explicito y no desaparece`() {
        val store = storeWithMalformedMiddleRow()
        try {
            val records = store.readRecords(runId, null, 10).records

            assertEquals(
                listOf(40L, 41L, 42L),
                records.map { it.sequence },
                "las tres filas existen: una refusal no es la ausencia de una fila, es la presencia " +
                    "de una fila que no se pudo interpretar. 40 y 42 no puedenappecer como historia " +
                    "continua sin que 41 esté entre ellas.",
            )

            val refusal = records[1] as EventRecordRead.Undecodable
            assertEquals(41L, refusal.sequence)
            assertEquals("e41", refusal.eventId, "la identidad sale de las COLUMNAS de la fila, no del payload")
            assertEquals("StageStarted", refusal.kind)
        } finally {
            store.close()
        }
    }

    @Test
    fun `las filas validas siguen siendo observables y tipadas`() {
        val store = storeWithMalformedMiddleRow()
        try {
            val records = store.readRecords(runId, null, 10).records
            val decoded = records.filterIsInstance<EventRecordRead.Decoded>()

            assertEquals(2, decoded.size, "40 y 42 decodifican: la malformada no ciega a sus vecinas")
            assertTrue(decoded[0].event is RunStarted)
            assertEquals(
                "success",
                (decoded[1].event as StageFinished).outcome,
                "el outcome sigue viajando en la pagina: el refusal no degrada el payload leido",
            )
        } finally {
            store.close()
        }
    }

    @Test
    fun `se distingue kind desconocido de payload malformado`() {
        // A kind no runtime has a decoder for: the shape an OLD binary meets when it reads a history
        // a NEWER one wrote. `StepStarted` would NOT do here — this binary decodes it, so the honest
        // classification for it is malformed, and the store is right to say so.
        val store = storeWithMalformedMiddleRow(kindColumn = "StepCompletedByQuantumTunnel")
        try {
            val refusal = store.readRecords(runId, null, 10).records[1] as EventRecordRead.Undecodable

            assertEquals(
                UndecodableReason.UnknownKind("StepCompletedByQuantumTunnel"),
                refusal.reason,
                "un kind que este binario no decodifica es version skew, no corrupcion: un lector " +
                    "fail-closed aun necesita distinguir los dos para poder reportarlos distinto",
            )
            assertEquals(
                "StepCompletedByQuantumTunnel",
                refusal.kind,
                "el refusal conserva el kind que la fila declaraba, que es lo que hace falta para " +
                    "reportar el skew sin abrir el payload",
            )
        } finally {
            store.close()
        }
    }

    @Test
    fun `un kind conocido con payload invalido se reporta como malformado`() {
        val store = storeWithMalformedMiddleRow()
        try {
            val refusal = store.readRecords(runId, null, 10).records[1] as EventRecordRead.Undecodable

            assertTrue(
                refusal.reason is UndecodableReason.MalformedPayload,
                "StageStarted SI tiene decoder aqui, asi que un payload que no encaja es corrupcion. " +
                    "Reportarlo como UnknownKind seria mentir sobre el binario: el decoder existe",
            )
        } finally {
            store.close()
        }
    }

    @Test
    fun `un kind conocido con payload corrupto no se confunde con version skew`() {
        // StepStarted IS decodable here, so this row is corruption even though its payload is as
        // unreadable as the version-skew row above. Conflating the two would send an operator
        // looking for a version problem that does not exist.
        val store = storeWithMalformedMiddleRow(kindColumn = "StepStarted")
        try {
            val refusal = store.readRecords(runId, null, 10).records[1] as EventRecordRead.Undecodable

            assertTrue(
                refusal.reason is UndecodableReason.MalformedPayload,
                "StepStarted tiene decoder en este binario: un payload ilegible es corrupcion",
            )
        } finally {
            store.close()
        }
    }

    // ------------------------------------------------------------------
    // The cursor: the second half of the defect
    // ------------------------------------------------------------------

    /**
     * The pagination defect, which is separate from the decode defect and was equally real.
     *
     * `readSlice` used to `page.add(decode(payload))` under a `page.size == limit` check, so the
     * bound counted rows that had DECODED. One unreadable row then let its successor into the
     * page: the returned page was neither the requested size nor a prefix of the history, and
     * `hasMore` could report "no more" with rows still unread.
     */
    @Test
    fun `el limite de pagina cuenta filas y no eventos decodificados`() {
        val store = storeWithMalformedMiddleRow()
        try {
            val page = store.readRecords(runId, null, 2)

            assertEquals(
                2,
                page.records.size,
                "limit=2 son DOS FILAS, no dos eventos decodificados: la fila 41 ocupa su sitio",
            )
            assertEquals(listOf(40L, 41L), page.records.map { it.sequence })
            assertTrue(page.hasMore, "la fila 42 existe mas alla del limite, asi que hasMore es true")
        } finally {
            store.close()
        }
    }

    @Test
    fun `el cursor avanza por la ultima fila leida aunque no decodifique`() {
        val store = storeWithMalformedMiddleRow()
        try {
            val page = store.readRecords(runId, null, 2)

            assertEquals(
                EventCursor(runId, 41L),
                page.nextCursor,
                "nextCursor es la secuencia de la ultima FILA, decodificada o no. Si fuera la del " +
                    "ultimo evento decodificado, un consumidor que reanuda volveria a ver 41 para " +
                    "siempre; y si la saltara, la perderia sin saberlo",
            )
        } finally {
            store.close()
        }
    }

    @Test
    fun `reanudar tras el refusal llega a la fila siguiente sin perderla ni repetirla`() {
        val store = storeWithMalformedMiddleRow()
        try {
            val first = store.readRecords(runId, null, 2)
            val second = store.readRecords(runId, first.nextCursor, 2)

            assertEquals(listOf(42L), second.records.map { it.sequence })
            assertFalse(second.hasMore)
            assertEquals(
                listOf(40L, 41L, 42L),
                first.records.map { it.sequence } + second.records.map { it.sequence },
                "paginando de dos en dos, la historia completa aparece una vez: sin perdida y sin " +
                    "duplicado, que es la propiedad que S5.4 dependera",
            )
        } finally {
            store.close()
        }
    }

    @Test
    fun `un refusal con limit 1 no se pierde ni se repite`() {
        val store = storeWithMalformedMiddleRow()
        try {
            val a = store.readRecords(runId, null, 1)
            val b = store.readRecords(runId, a.nextCursor, 1)
            val c = store.readRecords(runId, b.nextCursor, 1)

            assertEquals(listOf(40L, 41L, 42L), listOf(a, b, c).map { it.records.single().sequence })
            assertTrue(
                b.records.single() is EventRecordRead.Undecodable,
                "con limit 1 la pagina ES el refusal: el consumidor ve que existe antes de poder " +
                    "decidir si lo salta, en vez de descubrirlo por un hueco",
            )
        } finally {
            store.close()
        }
    }

    // ------------------------------------------------------------------
    // Consumers that only accept semantic events fail closed
    // ------------------------------------------------------------------

    @Test
    fun `readSlice falla cerrado en vez de devolver una pagina mas corta`() {
        val store = storeWithMalformedMiddleRow()
        try {
            val thrown = assertThrows(UndecodableEventRecordException::class.java) {
                store.readSlice(runId, null, 10)
            }

            assertEquals(41L, thrown.sequence, "la excepcion nombra la fila: un consumidor puede reintentar o parar")
            assertEquals(runId, thrown.runId)
        } finally {
            store.close()
        }
    }

    @Test
    fun `eventsFor falla cerrado en vez de devolver un stream mas corto`() {
        val store = storeWithMalformedMiddleRow()
        try {
            val thrown = assertThrows(UndecodableEventRecordException::class.java) {
                store.eventsFor(runId).toList()
            }

            assertEquals(
                41L,
                thrown.sequence,
                "Sequence<DomainEvent> no tiene caso para un refusal, asi que eventsFor se detiene " +
                    "en la fila. Un salto aqui seria invisible: el consumidor veria un stream mas " +
                    "corto sin ninguna marca, igual que antes de E4c",
            )
        } finally {
            store.close()
        }
    }

    @Test
    fun `una historia sin filas malformadas se lee exactamente igual que antes`() {
        val db = tempDir.resolve("clean.db").toString()
        val store = SqliteEventStore(db)
        try {
            validEvents().forEach { store.appendAssigned(it) }
            store.flush()

            assertEquals(3, store.eventsFor(runId).toList().size, "sin refusals no cambia nada: es el no-op que debe ser")
            assertEquals(
                listOf(40L, 41L, 42L),
                store.readRecords(runId, null, 10).records.map { it.sequence },
            )
            assertEquals(
                listOf(40L, 41L),
                store.readRecords(runId, null, 2).records.map { it.sequence },
            )
        } finally {
            store.close()
        }
    }

    @Test
    fun `una fila con payload no-JSON sigue siendo una fila y no una ausencia`() {
        val store = storeWithMalformedMiddleRow(malformedPayload = "]]]not json at all[[[")
        try {
            val records = store.readRecords(runId, null, 10).records

            assertEquals(3, records.size, "basura no parseable sigue siendo un REGISTRO con una secuencia")
            assertEquals(
                41L,
                records[1].sequence,
                "el consumidor puede saltar por secuencia incluso cuando no hay nada interpretable",
            )
        } finally {
            store.close()
        }
    }
}
