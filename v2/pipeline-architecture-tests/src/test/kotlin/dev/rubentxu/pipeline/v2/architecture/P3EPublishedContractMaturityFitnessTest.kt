package dev.rubentxu.pipeline.v2.architecture

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * P3-E E5 — published-contract maturity is EXECUTABLE, not documented.
 *
 * ## What this test exists to make impossible
 *
 * Before this, `publishedContractModules` in `build.gradle.kts` said *what is published* and
 * nothing said *what may change about it*. Binary-compatibility validation froze four ABI
 * dumps and stopped there: it could tell you a change broke a consumer, but it could not tell
 * you whether that change was allowed, because the concept "allowed" was not written down
 * anywhere. Every such question therefore became an argument, and the arguments were settled
 * in commit messages rather than in a rule.
 *
 * This block earned its existence the day it was written: `apiCheck` rejected a change to
 * `pipeline-domain`, and the immediate question was not "how do I regenerate the dump" but
 * "is this change allowed". There was no answer to give, because nothing declared it. The
 * classification in `published-contract-maturity.json` is the answer, and the exception in
 * `published-contract-exceptions.json` is the receipt.
 *
 * ## What this test deliberately does NOT do
 *
 * It does not judge whether a recorded decision was *wise*. No static test can: the point of
 * the exception ledger is that a human decided, and the machine's job is to guarantee the
 * decision was written down, carries a SHA, and names a module that is actually published.
 * Claiming more would be a test that lies about its own scope.
 *
 * ## Why not DSL_SURFACE_MANIFEST
 *
 * That manifest classifies DSL constructs a pipeline author types. This classifies the
 * artifacts an external consumer links against. They are different surfaces with different
 * blast radii: a DSL function can be deprecated in a minor release because the author gets a
 * compile error they can fix, while a binary break costs a consumer a runtime
 * `NoSuchMethodError`. Reusing one manifest for both would apply the stricter policy to the
 * wrong surface and quietly weaken the one that matters.
 *
 * `kotlinx.serialization.json` arrives transitively through `pipeline-events`, which exposes
 * it with `api(...)`. It is used rather than a hand-rolled parser because a parser that only
 * handles the subset of JSON this repository happens to write is a parser that will silently
 * misread the first escape sequence somebody adds.
 */
@DisplayName("P3-E E5 — published contract maturity is declared and complete")
class P3EPublishedContractMaturityFitnessTest {

    private val v2 = FitnessPaths.v2Root()

    private val maturityFile = v2.resolve("contract/published-contract-maturity.json")
    private val exceptionsFile = v2.resolve("contract/published-contract-exceptions.json")

    private val json = Json { ignoreUnknownKeys = true }

    private val maturity: Map<String, String>
        get() = readObject(maturityFile).getValue("contracts").jsonObject
            .mapValues { (_, v) -> v.jsonPrimitive.content }

    private val exceptions: List<JsonObject>
        get() = readObject(exceptionsFile).getValue("entries").jsonArray
            .map { it.jsonObject }

    /** The canonical taxonomy. Anything outside it is a typo, not a new maturity. */
    private val taxonomy = setOf(
        "STABLE",
        "PARTIAL",
        "EXPERIMENTAL",
        "DEPRECATED",
        "UNSUPPORTED_FAIL_CLOSED",
    )

    private fun readObject(path: Path): JsonObject {
        assertTrue(Files.isRegularFile(path), "fichero de contrato ausente: $path")
        return json.parseToJsonElement(path.readText()).jsonObject
    }

    private fun parsePublishedModules(): List<String> {
        val raw = v2.resolve("build.gradle.kts").readText()
        val start = raw.indexOf("val publishedContractModules = listOf(")
        assertTrue(start > 0, "could not find publishedContractModules in build.gradle.kts")
        val open = raw.indexOf('(', start)

        // Strip line comments BEFORE looking for the closing paren. The declaration carries
        // `// BLOCK 2: the event contract (envelope, cursor, read/paging) ...`, and the
        // parenthesis inside that comment closed the scan early: the fitness then reported
        // pipeline-events and pipeline-output as unclassified, which is a false RED that reads
        // like a real policy hole. The scan has to be robust against the file it guards
        // carrying comments, not just against a clean list of strings.
        val body = raw.substring(open + 1)
            .lineSequence()
            .map { it.substringBefore("//") }
            .joinToString("\n")
        val close = body.indexOf(')')
        assertTrue(close > 0, "publishedContractModules list is not closed")

        return body.substring(0, close)
            .split(',')
            .map { it.trim().trim('"') }
            .filter { it.isNotEmpty() }
    }

    @Test
    fun `todo modulo publicado tiene clasificacion de madurez`() {
        val published = parsePublishedModules()

        assertTrue(
            published.isNotEmpty(),
            "publishedContractModules is empty; if that is deliberate the whole maturity " +
                "authority is moot and this test should be deleted, not weakened",
        )

        val missing = published.filterNot { maturity.containsKey(it) }
        assertEquals(
            emptyList<String>(),
            missing,
            "estos modulos se publican pero no tienen madurez declarada en " +
                "$maturityFile: $missing. Un contrato publicado sin clasificar es uno donde " +
                "nadie ha decidido si puede romperse, que es justo el estado que este test " +
                "existe para hacer imposible. Clasifícalo o deja de publicarlo.",
        )
    }

    @Test
    fun `ninguna madurez queda fuera de la taxonomia`() {
        val illegal = maturity.filterValues { it !in taxonomy }.map { "${it.key}=${it.value}" }

        assertEquals(
            emptyList<String>(),
            illegal,
            "madurez fuera de la taxonomia canonica: $illegal. La taxonomia es $taxonomy; " +
                "ampliarla es una decision, no una errata.",
        )
    }

    @Test
    fun `no se clasifica nada que no se publique`() {
        val published = parsePublishedModules().toSet()
        val orphans = maturity.keys.filterNot { it in published }

        assertEquals(
            emptyList<String>(),
            orphans,
            "modulos clasificados que ya no se publican: $orphans. Una clasificacion sin " +
                "contrato que proteger es commentary, y el commentary se pudre.",
        )
    }

    @Test
    fun `cada ruptura deliberada lleva SHA, modulo, superficie y razon`() {
        exceptions.forEachIndexed { index, entry ->
            val where = "published-contract-exceptions.json entries[$index]"
            for (field in listOf("module", "sha", "surface", "change", "permitting_maturity")) {
                assertTrue(
                    entry.containsKey(field),
                    "$where no declara '$field'. Una excepcion sin SHA ni razon no es una " +
                        "decision registrada: es un cambio de ABI que llego sin papeleo.",
                )
            }
            val sha = entry.getValue("sha").jsonPrimitive.content
            assertTrue(
                sha.length == 40 && sha.all { it in "0123456789abcdef" },
                "$where tiene un sha mal formado: '$sha'",
            )
            val reasoning = entry["reasoning"]?.jsonArray
            assertTrue(
                reasoning != null && reasoning.isNotEmpty(),
                "$where tiene una lista de razon vacia",
            )
        }
    }

    @Test
    fun `cada excepcion nombra un modulo realmente publicado`() {
        val published = parsePublishedModules().toSet()
        val offenders = exceptions.map { it.getValue("module").jsonPrimitive.content }
            .filterNot { it in published }
            .distinct()

        assertEquals(
            emptyList<String>(),
            offenders,
            "excepciones que nombran modulos no publicados: $offenders. Una excepcion sobre " +
                "algo que no se publica es ruido que esconde las que si importan.",
        )
    }

    @Test
    fun `cada excepcion invoca la madurez que el modulo declara`() {
        val mismatches = exceptions.mapNotNull { entry ->
            val module = entry.getValue("module").jsonPrimitive.content
            val claimed = entry.getValue("permitting_maturity").jsonPrimitive.content
            val declared = maturity[module]
            if (declared != claimed) "$module declara ${declared ?: "nada"} pero la excepcion invoca $claimed" else null
        }

        assertEquals(
            emptyList<String>(),
            mismatches,
            "una excepcion no puede invocar una madurez distinta de la declarada. Si la " +
                "clasificacion ha cambiado, cambiala primero y razonala.\n" +
                mismatches.joinToString("\n"),
        )
    }

    @Test
    fun `una madurez STABLE no puede coexistir con una ruptura registrada`() {
        val stableWithBreaks = exceptions
            .map { it.getValue("module").jsonPrimitive.content }
            .distinct()
            .filter { maturity[it] == "STABLE" }

        assertEquals(
            emptyList<String>(),
            stableWithBreaks,
            "modulos clasificados STABLE con ruptura registrada: $stableWithBreaks. STABLE " +
                "significa que la ruptura necesita una boundary mayor explicita; si ya se " +
                "cruzo esa boundary, el modulo no es STABLE todavia.",
        )
    }
}
