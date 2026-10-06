package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * P3-E E6 — the optional-field wire convention is ONE authority, and both halves are pinned to it.
 *
 * ## What this exists to make impossible
 *
 * An optional `CatchErrorTriggered.buildResult` travels as the empty string and reads back as
 * `null`. Before this, that pair was two independent literals on opposite sides of the codec:
 * **13** `?: ""` in `EventJsonWriter.kt` and **13** `takeIf { it.isNotEmpty() }` in
 * `JsonEventLog.kt`. The counts matched, which is exactly why nobody looked — the agreement was
 * a coincidence of two authors writing the same idiom, and nothing asserted it.
 *
 * The failure it enables is small and nasty. Add an optional field to the writer and forget the
 * reader: the value decodes as `""`, a fabricated string that no consumer can distinguish from
 * one the producer really meant. Add the reader without the writer: absence silently becomes
 * `null`, which happens to be the right answer for the wrong reason. Both compile. Both run.
 * Neither is noticed until someone asks whether `""` and "not declared" are the same fact —
 * which is the question this repository answered for `stageResult` when it refused to read a
 * record whose required field was missing.
 *
 * ## Why counting was not enough, and what replaced it
 *
 * The naive check is "13 == 13", and it is worthless: the two sides could drift together and
 * still be wrong about the same field. What matters is that the SETS agree — every optional field
 * the writer absence-encodes is one the reader absence-decodes, and neither has a private one.
 *
 * The empty string is deliberately kept as the encoding rather than a JSON `null`, because that
 * is what every historical record already carries and wire/history compatibility outranks
 * elegance. Only the declaration is new: [EventJsonFields.ABSENT_ON_WIRE],
 * [EventJsonWriter.optionalJsonString] and [EventJsonFields.optionalStringField] are now the
 * single authority, and this test is what keeps them from drifting apart again.
 */
@DisplayName("P3-E E6 — the optional-field wire convention has one authority and two matching halves")
class FArchE6OptionalFieldCodecFitnessTest {

    private val v2 = FitnessPaths.v2Root()

    private fun source(file: String): Path =
        v2.resolve("pipeline-events-store/src/main/kotlin/dev/rubentxu/pipeline/v2/events/durable/$file")

    private fun readOrFail(path: Path): String {
        assertTrue(Files.isRegularFile(path), "fuente esperada ausente: $path")
        return path.readText()
    }

    private fun writerFile() = source("EventJsonWriter.kt")

    private fun readerFile() = source("JsonEventLog.kt")

    /**
     * KDoc and block comments, matched non-greedily across lines.
     *
     * A scanner that reads its own documentation as code reports a false RED the day someone
     * improves the comment — and this test's KDoc quotes the very literal it forbids, so the
     * failure was immediate rather than hypothetical.
     */
    private val blockComment = Regex("""(?s)/\*(?!/).*?\*/""")

    /** Fields the writer absence-encodes, read off the helper's call sites. */
    private fun writtenOptionalFields(): Set<String> =
        Regex("""optionalJsonString\(event\.(\w+)\)""")
            .findAll(readOrFail(writerFile()))
            .map { it.groupValues[1] }
            .toSet()

    /** Fields the reader absence-decodes, read off the helper's call sites. */
    private fun readOptionalFields(): Set<String> =
        Regex("""optionalStringField\(\w+, "(\w+)"\)""")
            .findAll(readOrFail(readerFile()))
            .map { it.groupValues[1] }
            .toSet()

    @Test
    fun `el escritor y el lector codifican exactamente el mismo conjunto de campos opcionales`() {
        val written = writtenOptionalFields()
        val read = readOptionalFields()

        assertTrue(written.isNotEmpty(), "no se encontro ningun campo opcional escrito; la convencion se perdio")
        assertTrue(read.isNotEmpty(), "no se encontro ningun campo opcional leido; la convencion se perdio")

        val onlyWritten = written - read
        val onlyRead = read - written

        assertEquals(
            emptySet<String>(),
            onlyWritten,
            "el escritor absence-codifica $onlyWritten pero el lector NO lo absence-decodifica: un " +
                "valor opcional de esos campos volveria como la cadena vacia, que es un hecho " +
                "fabricado y no una ausencia. Compila, se ejecuta, y no lo nota nadie.",
        )
        assertEquals(
            emptySet<String>(),
            onlyRead,
            "el lector absence-decodifica $onlyRead pero el escritor NO absence-codifica: esos " +
                "campos nunca viajan como ausentes, asi que la rama es decoracion y su respuesta " +
                "a una ausencia real seria incorrecta por casualidad.",
        )
    }

    /**
     * The literal is what this law exists to retire. A bare `?: ""` next to `jsonString` is the
     * old, undeclared idiom, and it is the exact shape that made the two sides drift apart before.
     */
    @Test
    fun `el escritor no reintroduce el literal de ausencia sin declarar`() {
        // Block comments are stripped FIRST, then line comments. Stripping only "//" was the
        // first version of this test and it failed on its own KDoc, which quotes the literal in
        // order to explain what is forbidden — exactly the false RED the scanner is supposed to
        // be immune to, produced by the scanner itself.
        val code = readOrFail(writerFile())
            .replace(blockComment, " ")
            .lineSequence()
            .map { it.substringBefore("//") }
            .toList()

        val bare = code.filter { it.contains("?: \"\"") }.map { it.trim() }

        assertEquals(
            emptyList<String>(),
            bare,
            "el escritor vuelve a fabricar la ausencia con un literal suelto: $bare. La via " +
                "declarada es EventJsonWriter.optionalJsonString, cuyo par de lectura vive en " +
                "EventJsonFields.optionalStringField.",
        )
    }

    /**
     * The reader's REQUIRED fields must not have been quietly converted to the optional helper.
     *
     * That would be the opposite defect: a required field whose absence is corruption would start
     * reading corruption as a legitimate `null`. `stageResult` is the field this protects — its
     * reader refuses on absence today, and that refusal is the reason four historical records are
     * readable rather than four fabricated ones.
     */
    @Test
    fun `los campos requeridos siguen fallando cerrados y no degradaron a opcionales`() {
        val reader = readOrFail(readerFile())

        assertTrue(
            reader.contains("val stageResult = EventJsonFields.stringField(s, \"stageResult\") ?: return null"),
            "stageResult es NO-NULL en el evento y el escritor emite la clave incondicionalmente, " +
                "asi que su ausencia es corrupcion, no una version que este runtime ignore. El " +
                "lector debe seguir fallando cerrado con '?: return null'.",
        )
    }
}
