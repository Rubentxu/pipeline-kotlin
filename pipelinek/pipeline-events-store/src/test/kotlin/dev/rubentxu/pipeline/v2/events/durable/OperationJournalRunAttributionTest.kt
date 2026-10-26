package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant

/**
 * El journal de un run lista la operación que SIGUE EN CURSO.
 *
 * ## La garantía bajo prueba
 *
 * Un journal que no lista la operación en curso dice que un run terminó cuando
 * su trabajo no terminó. Quien decide —la introspección, la recuperación— lee
 * las filas del journal, y si la operación en curso no está entre ellas el run
 * parece finished: la recuperación lo cerraría publicando un resultado que
 * nadie observó.
 *
 * ## La capacidad que esta garantía sirve
 *
 * Un run que muere a mitad de una operación se puede recuperar sin que nadie
 * cierre un trabajo a medias.
 *
 * ## El defecto que estas filas persiguen
 *
 * La fila RUNNING se escribía SIN la atribución del run, y la lectura filtraba
 * por esa atribución: la fila sólo aparecía cuando la operación terminaba. Un
 * run muerto a mitad de trabajo parecía finished para todo lector que no
 * pudiera reparar la columna — y la respuesta DEPENDÍA DE QUÉ CARRIL
 * preguntaba, porque el carril de escritura repara la columna al abrir y el de
 * lectura no puede escribir. El mismo run, el mismo journal, dos respuestas.
 *
 * ## Por qué hay una fila por implementación
 *
 * El journal en memoria y el SQLite son dos implementaciones que el producto
 * declara espejos: elegir un almacenamiento no puede cambiar el resultado de
 * una reconciliación. Cada implementación lleva su propia fila porque el
 * defecto estaba en las dos, y una fila que sólo cubre una de ellas deja
 * libre la otra.
 *
 * ## Por qué el SQLite es contra un fichero real
 *
 * La garantía es sobre lo que SOBREVIVE y sobre lo que un lector puede ver.
 * Un doble en memoria afirmaría la idea que el código tiene de la garantía en
 * vez de la garantía: el defecto lived en the SQL, y un doble en memoria no
 * tiene SQL.
 */
class OperationJournalRunAttributionTest {

    @TempDir
    lateinit var tempDir: Path

    private fun dbPath(): String = tempDir.resolve("run.sqlite").toString()

    /**
     * Un reloj que no corre: la atribución no depende de la hora, y un reloj
     * que avanza haría depender el resultado del orden de las consultas.
     */
    private class FixedClock : Clock {
        override fun now(): Instant = Instant.parse("2026-10-26T12:00:00Z")
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun inputJson(runId: String, stepId: String = "step-0"): String = json.encodeToString(
        OperationInput.serializer(),
        OperationInput(
            stepId = stepId,
            params = emptyMap(),
            runId = runId,
            attempt = 1,
        ),
    )

    private val fingerprint = "a".repeat(64)

    /**
     * El journal en memoria lista la operación que sigue en curso.
     */
    @Test
    fun `el journal en memoria lista la operacion que sigue en curso`() {
        val journal = InMemoryOperationJournal(FixedClock())

        journal.beginOperation("run-1-s0-0", 1, fingerprint, inputJson("run-1"))

        val rows = journal.listForRun("run-1")

        assertEquals(
            1,
            rows.size,
            "la operación que sigue en curso pertenece al run: es la fila que dice que el " +
                "run NO terminó, y sin ella el run parece finished",
        )
        assertEquals(
            OperationStatus.RUNNING,
            rows.single().status,
            "la fila que se lists es la que está en curso, no una terminada",
        )
    }

    /**
     * El journal SQLite lista la operación que sigue en curso.
     */
    @Test
    fun `el journal sqlite lista la operacion que sigue en curso`() {
        val journal = sqliteJournal()

        journal.beginOperation("run-1-s0-0", 1, fingerprint, inputJson("run-1"))

        val rows = journal.listForRun("run-1")

        assertEquals(
            1,
            rows.size,
            "la operación que sigue en curso pertenece al run: es la fila que dice que el " +
                "run NO terminó, y sin ella el run parece finished",
        )
        assertEquals(
            OperationStatus.RUNNING,
            rows.single().status,
            "la fila que se lista es la que está en curso, no una terminada",
        )
    }

    /**
     * Una fila que una build anterior dejó SIN atribuir sigue siendo del run.
     *
     * La build anterior escribía la fila RUNNING sin la atribución, y sólo la
     * rellenaba cuando la operación terminaba. Un run que murió con esa build
     * dejó una fila que no nombraba ningún run, y un lector no puede
     * repararla: el carril de lectura abre la base por una puerta que el motor
     * no deja escribir. Si la lectura creyera sólo en la columna, ese run
     * seguiría pareciendo finished después de la corrección.
     */
    @Test
    fun `una fila sin atribucion sigue siendo del run que la escribio`() {
        val journal = sqliteJournal()
        journal.beginOperation("run-1-s0-0", 1, fingerprint, inputJson("run-1"))
        SqliteConnectionFactory.open(dbPath()).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate("UPDATE operation_journal SET run_id = NULL WHERE op_id = 'run-1-s0-0'")
            }
        }

        val rows = journal.listForRun("run-1")

        assertEquals(
            1,
            rows.size,
            "la fila dice en su propio input qué run es, aunque la columna quede vacía: el " +
                "lector no puede reparar la columna, así que tiene que leer la atribución que falta",
        )
        assertEquals(
            OperationStatus.RUNNING,
            rows.single().status,
            "y la fila que se lista es la que sigue en curso",
        )
    }

    /**
     * El journal SQLite contra un fichero real.
     */
    private fun sqliteJournal(): SqliteOperationJournalImpl {
        SqliteConnectionFactory.open(dbPath()).use { OperationJournalSchema.create(it) }
        return SqliteOperationJournalImpl(
            connectionFactory = { SqliteConnectionFactory.open(dbPath()) },
            clock = FixedClock(),
            json = json,
            dbPath = dbPath(),
        )
    }
}
