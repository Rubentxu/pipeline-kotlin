package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * P3-E E4c — the read side may not DROP a durable row, mechanically.
 *
 * ## The defect this prevents
 *
 * The read side answered "did this row decode?" and answered "what rows exist?" by omission:
 *
 * ```kotlin
 * SqliteEventStore.eventsFor:  JsonEventLog.decode(payload).firstOrNull()?.let { yield(it) }
 * SqliteEventStore.readSlice:  JsonEventLog.decode(rs.getString(1)).firstOrNull()?.let { page.add(it) }
 * ```
 *
 * With rows `40 valid, 41 malformed, 42 valid` both produce `40, 42`. There is no marker in the
 * output, so a consumer cannot tell "no event 41" from "a record 41 exists and we could not read
 * it". An external observer reading that stream as history is being lied to by subtraction, which
 * is worse than a wrong value: a wrong value can be checked, a gap cannot be noticed.
 *
 * E4b.3 made the rows exist. Removing the semantic defaults from the decoders means a row missing
 * `stageResult` or `outcome` now REFUSES by design, so `decodeEvent(...) ?: null` stopped being
 * unreachable. Honest decoding and a silent read are not separable: the first is what made the
 * second matter.
 *
 * ## What is pinned, and what is deliberately NOT
 *
 * The scan looks for the two shapes the defect actually took in this repository:
 *
 * 1. a decode whose result is discarded with `?: continue` / `?: return emptySequence()` and
 * 2. a `JsonEventLog.decode(...)` whose result is narrowed with `firstOrNull()` / `mapNotNull`
 *    before it reaches a consumer of durable history.
 *
 * A row that will not decode must become an [dev.rubentxu.pipeline.v2.events.EventRecordRead.Undecodable]
 * or stop the read. It may not become "the next thing".
 *
 * Its limit is stated rather than hidden. This is a text fitness: it can see that a decode result is
 * dropped, and it cannot prove that a returned `DomainEvent` was the one storage held. Detecting
 * that needs whole-program dataflow, which is a compiler plugin's job. What the scan does guarantee is
 * the shape that was actually written down, and a written shape is what survives review.
 *
 * Test sources are EXCLUDED on purpose. A test may legitimately narrow a decode — this very file's
 * subject is a malformed payload — and a fitness that failed on test code would train people to route
 * around it. The scope is production read-side only.
 */
class DurableReadTruthFitnessTest {

    private val v2 = FitnessPaths.v2Root()

    /** Production read-side: the two modules that turn stored rows into observations. */
    private val readSideSources: List<Path> = listOf(
        v2.resolve("pipeline-events/src/main/kotlin"),
        v2.resolve("pipeline-events-store/src/main/kotlin"),
    )

    private fun kotlinSources(root: Path): List<Path> {
        if (!Files.isDirectory(root)) return emptyList()
        Files.walk(root).use { stream ->
            return stream.filter { it.toString().endsWith(".kt") }
                .toList()
        }
    }

    private fun productionSources(): List<Path> =
        readSideSources.flatMap { kotlinSources(it) }.filter { !it.toString().contains("/src/test/") }

    /**
     * A `JsonEventLog.decode(...)` narrowed away instead of reported.
     *
     * `JsonEventLog.decode` itself legitimately returns a shorter list — it is a codec over one JSON
     * array, and a caller that has decided its policy may narrow it. The fitness therefore requires
     * every such narrowing to be VISIBLE in the source rather than invisible: the row must become an
     * `EventRecordRead`, or the read must throw. That is checked behaviourally by
     * `DurableReadTruthTest`; this fitness exists so the shape cannot be reintroduced quietly.
     *
     * ## `JsonEventLog.decode` is EXEMPT, and that exemption is a decision
     *
     * The codec still contains `decodeEvent(trimmed) ?: continue`. It is not scanned, and the reason
     * is that a codec over ONE JSON array has no way to report a refusal: the caller's unit is the
     * document, not the row, and a document that contains a bad element has no sequence to name —
     * the array in `Main.kt:208` comes from stdout, where the store already assigned sequences and
     * this codec is only re-encoding what it read.
     *
     * Removing the `continue` there would mean inventing a refusal without an identity, which is the
     * forbidden shape: an `Undecodable` whose sequence is invented is a second authority for
     * position. The store is where a row's identity exists (columns), so the store is where the
     * refusal is built — see `SqliteEventStore.readRecord`.
     *
     * What is NOT exempt is a durable-store read narrowing a decode. That is what the scan covers.
     */
    @Test
    fun `el read-side no descarta una fila durable con firstOrNull ni mapNotNull`() {
        val offenders = productionSources().flatMap { file ->
            // The codec itself: identity-less by construction, and the exemption is argued above.
            if (file.fileName.toString() == "JsonEventLog.kt") return@flatMap emptyList()

            val out = mutableListOf<String>()
            readTextOrFail(file).lines().forEachIndexed { index, line ->
                val code = line.substringBefore("//").trim()
                if (code.startsWith("*") || code.startsWith("/*")) return@forEachIndexed

                // `JsonEventLog.decode(...)` narrowed with `.firstOrNull()` / `.mapNotNull`.
                //
                // The FIRST version of this regex was `decode\([^)]*\)\.\s*(firstOrNull|mapNotNull)`
                // and it could not find the defect it exists to prevent: `[^)]*` stops at the FIRST
                // `)`, so `decode(rs.getString("payload")).firstOrNull()` never matched because the
                // nested call closed early. A mutation reintroducing exactly the production pattern
                // left the fitness green.
                //
                // The lesson is that a scan must be tried against the real shape of the code it
                // guards. The fix is to look for the DISCRIMINATOR — a narrowing applied to a line
                // that names the codec — and not to try to balance parentheses in a regex.
                val namesTheCodec = Regex("""JsonEventLog\.decode""").containsMatchIn(code)
                val narrows = Regex("""\.\s*(firstOrNull|mapNotNull)\s*\(""").containsMatchIn(code)
                val swallows = Regex("""decodeEvent\([^)]*\)\s*\?:\s*(continue|return\s+emptySequence\(\))""")
                    .containsMatchIn(code)

                if (swallows || (namesTheCodec && narrows)) {
                    out += "${file.fileName}:${index + 1}  $code"
                }
            }
            out
        }

        assertEquals(
            emptyList<String>(),
            offenders,
            "una fila durable que existe no puede desaparecer del read-side: " +
                "firstOrNull/mapNotNull sobre un decode convierte 'no se pudo interpretar' en 'no existe', " +
                "y un consumidor no puede distinguir un hueco de una ausencia. " +
                "Usa JsonEventLog.decodeStoredRow, que devuelve un resultado cerrado con su motivo, y " +
                "devuélvelo como EventRecordRead.Undecodable. " +
                "Offensas:\n" + offenders.joinToString("\n"),
        )
    }

    /**
     * The narrowing that IS the fix must not need a narrowing: [JsonEventLog.decodeStoredRow] is the
     * shape the store is expected to use, and it is the reason the scan above can be unconditional.
     *
     * Two earlier versions of this fitness were wrong and both were caught by mutation rather than by
     * review, which is the point of writing them down:
     *
     * - it granted the narrowing to any file that mentioned `Undecodable` SOMEWHERE, so a correct
     *   `readRecord` excused a lossy `readRecords` in the same file;
     * - it granted it when a refusal appeared within N lines, and N was wrong by 42.
     *
     * Both were permissions wider than the thing they permitted. The fix was not to tune the
     * threshold but to remove the need for one: the store now asks a question that has a closed
     * answer, so there is nothing to excuse.
     */
    @Test
    fun `el store resuelve una fila mediante el resultado cerrado del codec`() {
        val sqlite = v2.resolve(
            "pipeline-events-store/src/main/kotlin/dev/rubentxu/pipeline/v2/events/durable/SqliteEventStore.kt"
        )
        val text = readTextOrFail(sqlite)

        assertTrue(
            Regex("""JsonEventLog\.decodeStoredRow\s*\(""").containsMatchIn(text),
            "SqliteEventStore debe leer cada fila con decodeStoredRow: un resultado cerrado con " +
                "motivo. Estrechar con firstOrNull obligaba al scan anterior a perdonar excepciones, y " +
                "cualquier excepcion es un permiso que puede sobrevivir al defecto que justificaba.",
        )
        assertTrue(
            Regex("""when\s*\(\s*val\s+\w+\s*=\s*JsonEventLog\.decodeStoredRow""").containsMatchIn(text),
            "el resultado debe agotarse con un `when`: Accepted da Decoded, Refused da Undecodable. " +
                "Un `if` sobre un nullable seria el mismo defecto con otra ropa",
        )
    }

    /**
     * The page bound must count ROWS, not rows that decoded.
     *
     * This is the second half of the same defect and it is easy to reintroduce while fixing the
     * first, because the two look like independent concerns. They are not: a page that counts
     * decoded events lets an unreadable row shrink the page, moves `nextCursor` by an amount
     * unrelated to progress, and can report `hasMore = false` with rows still unread. All three make
     * the cursor lie.
     *
     * The scan pins the shape — a `page.add` fed by a decode rather than by a row read — and
     * `DurableReadTruthTest` pins the behaviour.
     */
    @Test
    fun `el limite de pagina no se cuenta sobre eventos decodificados`() {
        val offenders = productionSources().flatMap { file ->
            val text = readTextOrFail(file)
            val out = mutableListOf<String>()
            text.lines().forEachIndexed { index, line ->
                val code = line.substringBefore("//").trim()
                if (code.startsWith("*") || code.startsWith("/*")) return@forEachIndexed
                if (Regex("""(page|records)\.add\([^)]*decode\(""").containsMatchIn(code)) {
                    out += "${file.fileName}:${index + 1}  $code"
                }
            }
            out
        }

        assertEquals(
            emptyList<String>(),
            offenders,
            "el corte de pagina debe hacerse sobre FILAS: una pagina que cuenta solo eventos " +
                "decodificados deja que una fila ilegible la encoja, desplace el cursor y mienta " +
                "sobre hasMore. Lee la fila una vez y anade EventRecordRead.Decoded o .Undecodable. " +
                "Offensas:\n" + offenders.joinToString("\n"),
        )
    }

    /**
     * A store that CAN hold an undecodable row must override the paged read.
     *
     * The inherited default cannot produce a refusal, so a store whose payload is TEXT has to say so
     * by implementing the read that can. This is the guard on the possibility rather than on the
     * occurrence: the file it names is the one place in the read side that decodes a stored payload.
     *
     * It is written as a scan rather than a reflection check because the question is about a
     * capability the type system cannot state: `EventStore` has no member saying "my rows may fail to
     * decode", and adding one would be a contract change for a store that does not need it.
     */
    @Test
    fun `el store cuyo payload es texto implementa la lectura que puede rechazar`() {
        val sqlite = v2.resolve(
            "pipeline-events-store/src/main/kotlin/dev/rubentxu/pipeline/v2/events/durable/SqliteEventStore.kt"
        )
        assertTrue(
            Files.isRegularFile(sqlite),
            "SqliteEventStore debe existir; si se movio, este fitness tiene que seguirlo a mano " +
                "en vez de pasar en silencio",
        )

        val text = readTextOrFail(sqlite)
        assertTrue(
            Regex("""override\s+fun\s+readRecords\s*\(""").containsMatchIn(text),
            "SqliteEventStore guarda el payload como TEXTO y el kind puede venir de un runtime mas " +
                "nuevo, asi que sus filas pueden no decodificar. El default de readRecords no puede " +
                "producir un refusal, luego este store DEBE implementar readRecords; si no, un " +
                "Undecodable volvería a perderse en silencio justo en el unico store que puede " +
                "producirlo.",
        )
    }

    /**
     * Read defensively: a source this fitness cannot read must not be silently treated as compliant.
     *
     * Returning "" would make an unreadable file invisible to the scan, so the read fails the test
     * instead. The same reasoning as the sibling fitnesses, for the same reason.
     */
    private fun readTextOrFail(path: Path): String = try {
        Files.readString(path)
    } catch (e: Exception) {
        throw AssertionError("no se pudo leer $path para el scan: la evidencia no se ignora", e)
    }
}