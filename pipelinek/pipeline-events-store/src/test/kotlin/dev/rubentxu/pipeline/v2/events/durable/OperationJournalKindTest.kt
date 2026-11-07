package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.CompositeOperation
import dev.rubentxu.pipeline.v2.domain.durable.DurableOperation
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.MemoizedOperation
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationOutput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant

/**
 * El journal no inventa una operación a partir de una clase que no conoce.
 *
 * ## La garantía bajo prueba
 *
 * La columna `kind` de una fila del journal es la PALABRA con la que la fila
 * viaja, y cada palabra lleva su propia política de reejecución. Una palabra
 * que este build no conoce es una fila que este build no puede interpretar, y
 * la lectura la RECHAZA nombrando la palabra.
 *
 * ## El defecto que estas filas persiguen
 *
 * El lector tenía un `else` que devolvía un `RerunOperation` para cualquier
 * palabra que no fuera `MEMOIZED` ni `COMPOSITE`. El esquema no cierra la
 * puerta (`kind TEXT NOT NULL DEFAULT 'RERUN'`, sin `CHECK`), así que una fila
 * de otra build —o escrita a mano— llegaba al lector y salía como RERUN.
 *
 * Y RERUN es la dirección PELIGROSA: RERUN significa "vuelve a ejecutarse". Una
 * fila que ya ocurrió leída como RERUN es un efecto que se repite.
 *
 * El mismo módulo ya tenía el comportamiento correcto para el WIRE DE EVENTOS
 * (allí una palabra desconocida es SKEW DE VERSIÓN y se rechaza). Estas filas
 * llevan el journal de operaciones al mismo sitio.
 *
 * ## Por qué hay una fila por implementación
 *
 * El journal en memoria y el SQLite son dos implementaciones que el producto
 * declara espejos: elegir un almacenamiento no puede cambiar la respuesta. El
 * `else` estaba en las DOS, y una fila que sólo cubriera una dejaría libre la
 * otra. La diferencia entre ellas —el fichero real frente a la memoria— está
 * en lo que cada fila hace, no en dónde vive.
 */
class OperationJournalKindTest {

    @TempDir
    lateinit var tempDir: Path

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Un reloj que AVANZA: el orden de las filas es parte de lo que el journal
     * contesta, y dos filas con la misma marca dejarían el orden en manos del
     * plan de ejecución de la consulta.
     */
    private class AdvancingClock : Clock {
        private var tick = 0L

        override fun now(): Instant = Instant.parse("2026-11-06T12:00:00Z").plusMillis(tick++)
    }

    private fun dbPath(): String = tempDir.resolve("operations.sqlite").toString()

    private fun input(runId: String, stepId: String = "step-0"): OperationInput = OperationInput(
        stepId = stepId,
        params = emptyMap(),
        runId = runId,
        attempt = 1,
    )

    private fun inputJson(runId: String): String = json.encodeToString(
        OperationInput.serializer(),
        input(runId),
    )

    private fun fingerprint(letter: String): Fingerprint = Fingerprint(letter.repeat(64))

    private fun output(): OperationOutput = OperationOutput(
        result = JsonPrimitive("done"),
        durationMs = 7L,
        finishedAt = 1_700_000_000_000L,
    )

    /**
     * Una fila ESCRITA A MANO, con la palabra que este build no conoce.
     *
     * Se escribe por SQL y no por el journal: el journal sólo sabe escribir
     * palabras que sabe leer, y lo que se quiere poner en la base es una fila
     * que este build no escribió. El esquema la acepta porque su `kind` no
     * tiene `CHECK`.
     */
    private fun writeRow(opId: String, kind: String, runId: String) {
        SqliteConnectionFactory.open(dbPath()).use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO operation_journal
                    (op_id, fingerprint, status, kind, attempt, input, output,
                     started_at, created_at, updated_at, run_id)
                VALUES (?, ?, 'SUCCEEDED', ?, 1, ?, NULL, 1, 1, 1, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, opId)
                ps.setString(2, fingerprint("a").hex)
                ps.setString(3, kind)
                ps.setString(4, inputJson(runId))
                ps.setString(5, runId)
                ps.executeUpdate()
            }
        }
    }

    /**
     * Una fila que este build no puede leer NO se lee como RERUN.
     *
     * El rechazo tiene que nombrar la palabra y la fila: un rechazo que no
     * dice qué no pudo leer deja al operador sin saber si el journal está
     * dañado o si es de una build más nueva.
     */
    @Test
    fun `una fila con un kind desconocido no se lee como RERUN`() {
        val journal = sqliteJournal()

        writeRow(opId = "run-1-s0-0", kind = "PARALLEL", runId = "run-1")

        val refusal = assertThrows(UnknownOperationKindException::class.java) {
            journal.get("run-1-s0-0")
        }

        assertTrue(
            refusal.message.orEmpty().contains("PARALLEL"),
            "el rechazo tiene que nombrar la palabra que no se reconoce, y dijo: ${refusal.message}",
        )
        assertTrue(
            refusal.message.orEmpty().contains("run-1-s0-0"),
            "el rechazo tiene que nombrar la fila que no se puede leer, y dijo: ${refusal.message}",
        )
    }

    /**
     * Una fila que no se puede leer hace fallar la lectura DEL RUN, no la esconde.
     *
     * Omitir la fila haría que el run pareciera tener menos trabajo del que
     * tuvo, y un run con menos trabajo del que tuvo es un run al que la
     * recuperación le falta una operación: la que ya ocurrió y se repetiría.
     */
    @Test
    fun `una fila con un kind desconocido hace fallar la lectura del run entero`() {
        val journal = sqliteJournal()

        writeRow(opId = "run-1-s0-0", kind = "RERUN", runId = "run-1")
        writeRow(opId = "run-1-s1-0", kind = "PARALLEL", runId = "run-1")

        val refusal = assertThrows(UnknownOperationKindException::class.java) {
            journal.listForRun("run-1")
        }

        assertTrue(
            refusal.message.orEmpty().contains("PARALLEL"),
            "el rechazo tiene que nombrar la palabra que no se reconoce, y dijo: ${refusal.message}",
        )
        assertTrue(
            refusal.message.orEmpty().contains("run-1-s1-0"),
            "el rechazo tiene que nombrar la fila que no se puede leer, y dijo: ${refusal.message}",
        )
    }

    /**
     * Las TRES clases conocidas se leen con sus campos reales.
     *
     * Rechazar lo desconocido no puede costar lo conocido: cada palabra sigue
     * volviendo a su propia variante, con su identificador, su huella, su
     * entrada, su salida, su estado y su intento. La salida cacheada de la
     * variante memoizada es la salida de la fila, y el compuesto no trae
     * hijos: sus hijos son filas de su propio.
     */
    @Test
    fun `las tres clases conocidas se leen con sus campos reales`() {
        val journal = sqliteJournal()
        val input = input("run-1")
        val output = output()

        journal.append(
            RerunOperation(
                id = "op-rerun",
                fingerprint = fingerprint("a"),
                input = input,
                output = output,
                status = OperationStatus.SUCCEEDED,
                attempt = 1,
            ),
            null,
        )
        journal.append(
            MemoizedOperation(
                id = "op-memoized",
                fingerprint = fingerprint("b"),
                input = input,
                output = output,
                status = OperationStatus.SUCCEEDED,
                attempt = 2,
                cachedOutput = output,
            ),
            null,
        )
        journal.append(
            CompositeOperation(
                id = "op-composite",
                fingerprint = fingerprint("c"),
                input = input,
                output = output,
                status = OperationStatus.SUCCEEDED,
                attempt = 3,
                subOperations = emptyList(),
            ),
            null,
        )

        val rerun = assertInstanceOf(
            RerunOperation::class.java,
            journal.get("op-rerun"),
            "la fila RERUN se lee como la variante que dice",
        )
        assertEquals("op-rerun", rerun.id, "la fila conserva su identificador")
        assertEquals(fingerprint("a"), rerun.fingerprint, "la fila conserva su huella")
        assertEquals(input, rerun.input, "la fila conserva su entrada")
        assertEquals(output, rerun.output, "la fila conserva su salida")
        assertEquals(OperationStatus.SUCCEEDED, rerun.status, "la fila conserva su estado")
        assertEquals(1, rerun.attempt, "la fila conserva su intento")

        val memoized = assertInstanceOf(
            MemoizedOperation::class.java,
            journal.get("op-memoized"),
            "la fila MEMOIZED se lee como la variante que dice",
        )
        assertEquals(fingerprint("b"), memoized.fingerprint, "la fila conserva su huella")
        assertEquals(output, memoized.cachedOutput, "la salida cacheada es la salida de la fila")
        assertEquals(2, memoized.attempt, "la fila conserva su intento")

        val composite = assertInstanceOf(
            CompositeOperation::class.java,
            journal.get("op-composite"),
            "la fila COMPOSITE se lee como la variante que dice",
        )
        assertEquals(fingerprint("c"), composite.fingerprint, "la fila conserva su huella")
        assertEquals(
            emptyList<DurableOperation>(),
            composite.subOperations,
            "el compuesto no trae hijos: sus hijos son filas de su propio",
        )
        assertEquals(3, composite.attempt, "la fila conserva su intento")
    }

    /**
     * Los DOS journals contestan lo mismo.
     *
     * Es el contrato que el producto declara de sus dos implementaciones: elegir
     * un almacenamiento no puede cambiar la respuesta. Si uno rechazara una
     * palabra y el otro la leyera como RERUN, el mismo run daría dos
     * respuestas según el carril que preguntara.
     */
    @Test
    fun `el journal en memoria y el sqlite contestan lo mismo`() {
        val clock = AdvancingClock()
        val sqlite = sqliteJournal(clock)
        val memory = InMemoryOperationJournal(clock)
        val input = input("run-1")
        val output = output()
        val operations = listOf(
            RerunOperation(
                id = "op-rerun",
                fingerprint = fingerprint("a"),
                input = input,
                output = output,
                status = OperationStatus.SUCCEEDED,
                attempt = 1,
            ),
            MemoizedOperation(
                id = "op-memoized",
                fingerprint = fingerprint("b"),
                input = input,
                output = output,
                status = OperationStatus.SUCCEEDED,
                attempt = 2,
                cachedOutput = output,
            ),
            CompositeOperation(
                id = "op-composite",
                fingerprint = fingerprint("c"),
                input = input,
                output = output,
                status = OperationStatus.SUCCEEDED,
                attempt = 3,
                subOperations = emptyList(),
            ),
        )

        operations.forEach { op ->
            sqlite.append(op, null)
            memory.append(op, null)
        }

        assertEquals(
            sqlite.listForRun("run-1"),
            memory.listForRun("run-1"),
            "los dos journals son espejos: el mismo run contesta lo mismo en los dos",
        )
    }

    /**
     * El journal SQLite contra un fichero real.
     */
    private fun sqliteJournal(clock: Clock = AdvancingClock()): SqliteOperationJournalImpl {
        SqliteConnectionFactory.open(dbPath()).use { OperationJournalSchema.create(it) }
        return SqliteOperationJournalImpl(
            connections = DurableConnections.over(
                writeDoor = { SqliteConnectionFactory.open(dbPath()) },
                readDoor = { SqliteConnectionFactory.openReadOnly(dbPath()) },
            ),
            clock = clock,
            json = json,
            dbPath = dbPath(),
        )
    }
}
