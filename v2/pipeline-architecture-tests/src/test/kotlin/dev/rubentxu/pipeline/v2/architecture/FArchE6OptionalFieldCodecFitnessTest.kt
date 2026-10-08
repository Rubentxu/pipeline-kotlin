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

    /**
     * Fields the writer absence-encodes, read off the helper's call sites.
     *
     * The anchor is the field name, and the argument is deliberately NOT pinned to end at the
     * name. This scanner was born one migration too early: it matched `optionalJsonString(
     * event.buildResult)` and reported a false RED the day `CatchErrorBuildResult` replaced that
     * `String` with a typed value and the call became `event.buildResult?.wireToken`. The writer
     * had not drifted — it absence-encodes exactly the same field — but the scan could no longer
     * SEE the writer half, so `onlyRead` came back non-empty and the law fired at a scanner, not
     * at a codec.
     *
     * A test that cannot see the half it exists to compare is not a weaker law; it is a broken
     * instrument. Matching the field name and tolerating whatever projection is applied to it keeps
     * the law intact AND makes it fire when a real field is added on one side only, which is the
     * defect the law was written for.
     */
    private fun writtenOptionalFields(): Set<String> =
        Regex("""optionalJsonString\(event\.(\w+)""")
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
     * The `CatchErrorTriggered` decoder branch, as source text.
     *
     * Scoped to the branch on purpose. "The file somewhere contains `?: return null`" is the vacuous
     * version of this law — the reader has a dozen fail-closed reads and would satisfy it no matter
     * what happened to `stageResult`. Every assertion below has to be answerable by the branch alone.
     *
     * The branch is delimited by the next branch opener at the same 12-space indent; the reads inside
     * sit at 16, so an inner string literal can never be mistaken for the end of the branch.
     */
    private fun catchErrorDecoder(): String {
        val reader = readOrFail(readerFile())
        val start = reader.indexOf("\"CatchErrorTriggered\" -> {")
        assertTrue(start >= 0, "no se encontro la rama decodificadora de CatchErrorTriggered")

        val nextBranch = reader.indexOf("\n            \"", start + 1)
        val branch = if (nextBranch < 0) reader.substring(start) else reader.substring(start, nextBranch)

        assertTrue(
            branch.contains("CatchErrorTriggered("),
            "la rama de CatchErrorTriggered no construye el evento; el recorte por sangria esta mal: $branch",
        )
        return branch
    }

    /**
     * The reader's REQUIRED fields must not have been quietly converted to the optional helper.
     *
     * That would be the opposite defect: a required field whose absence is corruption would start
     * reading corruption as a legitimate `null`. `stageResult` is the field this protects — its
     * reader refuses on absence today, and that refusal is the reason four historical records are
     * readable rather than four fabricated ones.
     *
     * The pin used to be one exact line ending in `?: return null`. That was a third spelling lock
     * from before `stageResult` became a closed vocabulary: the read is now followed by a token
     * parse and only THEN the fail-closed terminator, spread over three lines, while the property it
     * protects — a present token, an absent key, and a JSON null all leave the reader refusing — is
     * exactly as true. Pinning the line would have failed the migration without the defect existing.
     *
     * What replaces it discriminates the same three degradations without naming a layout:
     *
     *  - required helper downgraded to the optional one  → assertion 2 fails;
     *  - fail-closed terminator removed                  → assertion 4 fails;
     *  - terminator left in place but detached from this read (moved above it, so it guards
     *    `buildResult` instead)                         → the ordering assertion fails, because
     *    `?: return null` must sit AFTER the `stageResult` read and BEFORE the constructor call.
     */
    @Test
    fun `los campos requeridos siguen fallando cerrados y no degradaron a opcionales`() {
        val branch = catchErrorDecoder()
        val read = "EventJsonFields.stringField(s, \"stageResult\")"

        assertTrue(
            branch.contains(read),
            "stageResult es NO-NULL en el evento y el escritor emite la clave incondicionalmente, " +
                "asi que su ausencia es corrupcion, no una version que este runtime ignore. El " +
                "lector debe seguir leyendolo con el helper REQUERIDO stringField.",
        )
        assertTrue(
            !branch.contains("optionalStringField(s, \"stageResult\")"),
            "stageResult degradó a campo OPCIONAL: su ausencia es corrupcion y pasaria a leerse " +
                "como un null legitimo, que es el defecto opuesto al que esta ley protege.",
        )

        val readAt = branch.indexOf(read)
        val builtAt = branch.indexOf("CatchErrorTriggered(")
        // The guard that protects THIS read, not the first one in the branch: the nullable
        // `buildResult` above carries its own refusal, and matching that one would let a
        // `stageResult` left unguarded pass as long as the neighbouring field stayed fail-closed.
        //
        // Matched as a REFUSAL rather than as the literal `?: return null`. The decoder now returns
        // a typed failure — `?: return absent("stageResult")` for the missing field and
        // `?: return unreadable("stageResult", it)` for a corrupt token — so pinning the old
        // `null` spelling would fail this row while the behaviour it protects is intact. What the
        // row actually asserts is that the read is CLOSED before the event is built, and that is
        // what is matched.
        val closeAfter = listOf("?: return absent(", "?: return unreadable(", "?: return null")
            .map { branch.indexOf(it, readAt) }
            .filter { it >= 0 }
        val closedAt = closeAfter.minOrNull() ?: -1

        assertTrue(
            readAt in 0 until builtAt,
            "no se-localizo la lectura de stageResult dentro de la rama del constructor.",
        )
        assertTrue(
            closedAt in (readAt + 1) until builtAt,
            "el rechazo de stageResult debe estar DESPUES de su lectura y ANTES de construir el " +
                "evento; asi una ausencia o un token corrupto devuelven un rechazo tipado en vez de " +
                "un hecho fabricado. readAt=$readAt closedAt=$closedAt builtAt=$builtAt",
        )
        assertTrue(
            !branch.substring(readAt, builtAt).contains("?: " + "\"UNSTABLE\""),
            "stageResult no debe degradar a un default semantico: UNSTABLE significa que la " +
                "ejecucion continua y FAILURE que aborta, asi que un default seria una " +
                "afirmacion sobre el resultado del run que el registro nunca hizo.",
        )
    }
}
