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

    /**
     * P3-E E6 — the SURFACE layer: `module -> family -> declaration`.
     *
     * A module is not one thing. `pipeline-domain` is 773 published declarations of which 57 are
     * generated serializers, and `pipeline-scripting-api` is an authoring surface carrying four
     * constructs that have no working implementation at all. One label for both is not a
     * classification, it is a refusal to classify.
     *
     * `covers` is what keeps this from being prose. Every entry must resolve in TWO independent
     * authorities: the module's published ABI dump proves the symbol ships, and
     * DSL_SURFACE_MANIFEST.md proves what it means. Either one alone is forgeable — the ABI can
     * prove a name exists without saying whether it works, and the manifest can declare an
     * intention without proving it reached a consumer. A family that names something invented in
     * either file fails here.
     */
    private val surfaces: Map<String, Map<String, JsonObject>>
        get() = readObject(maturityFile)[surfacesKey]
            ?.jsonObject
            ?.mapValues { (_, families) -> families.jsonObject.mapValues { (_, v) -> v.jsonObject } }
            ?: emptyMap()

    private val manifestFile = v2.resolve("../docs/v2/surface/DSL_SURFACE_MANIFEST.md")

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

    private companion object {
        const val surfacesKey = "surfaces"
    }

    /**
     * How permissive a maturity is about BREAKING the contract.
     *
     * This is a partial order on one axis only — permission to break — because that is the axis the
     * classification exists to govern. It deliberately says nothing about deprecation or refusal:
     * `UNSUPPORTED_FAIL_CLOSED` is not on this ladder at all, because a construct that refuses
     * itself cannot be broken by anything, so ranking it would invent a comparison that does not
     * exist.
     */
    private val permissiveness = mapOf(
        "STABLE" to 0,
        "PARTIAL" to 1,
        "EXPERIMENTAL" to 2,
    )

    /** Effective maturity: the declared surface if there is one, else the module's own default. */
    private fun effectiveMaturity(module: String): String = maturity.getValue(module)

    private fun surfaceDeclarations(module: String): Map<String, JsonObject> =
        surfaces[module].orEmpty()

    private fun apiDump(module: String): String =
        v2.resolve("$module/api/$module.api").takeIf { Files.isRegularFile(it) }?.readText().orEmpty()

    private fun manifestText(): String =
        manifestFile.takeIf { Files.isRegularFile(it) }?.readText().orEmpty()

    /** `retry (retrofit)` -> `retry`: the name a file can actually be searched by. */
    private fun searchToken(cover: String): String = cover.substringBefore(" (").trim()

    /**
     * The manifest row's STATE column, for a construct named exactly [cover].
     *
     * Two things this has to get right, both of which were wrong on its first execution:
     *
     *  - It is column 4 (STATE), not column 3 (CATEGORY). A manifest row carries both, and the
     *    category is a shape — ATOMIC_STEP, BLOCK_STEP — not a maturity. Reading the category
     *    would make every construct look like it had a maturity of "BLOCK_STEP", which is not a
     *    taxonomy value at all.
     *  - The construct column must match EXACTLY. Substring matching makes `retry (retrofit)`
     *    resolve against the working block-form `retry` row, and then the law would be enforcing
     *    a claim about one construct using the evidence of a different one — which is precisely
     *    the substitution this whole layer exists to prevent.
     */
    private fun manifestStateOf(cover: String): String? =
        manifestText()
            .lineSequence()
            .filter { it.trimStart().startsWith("|") }
            .map { it.split("|").map(String::trim) }
            .firstOrNull { cells -> cells.getOrNull(1) == cover }
            ?.getOrNull(4)

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

    // ---------------------------------------------------------------------------------------
    // P3-E E6 — the surface layer. Everything below exists because `contracts` alone could not
    // express the difference between "published ABI that works" and "published ABI that refuses".
    // ---------------------------------------------------------------------------------------

    @Test
    fun `toda superficie pertenece a un modulo realmente publicado`() {
        val published = parsePublishedModules().toSet()

        val orphans = surfaces.keys.filterNot { it in published }

        assertEquals(
            emptyList<String>(),
            orphans,
            "superficies declaradas para modulos que no se publican: $orphans. Una superficie " +
                "sobre un contrato que no existe no refina nada; la protege de la nada.",
        )
    }

    @Test
    fun `ninguna madurez de superficie sale de la taxonomia`() {
        val illegal = surfaces.flatMap { (module, families) ->
            families.mapNotNull { (family, body) ->
                val value = body["maturity"]?.jsonPrimitive?.content ?: return@mapNotNull null
                if (value in taxonomy) null else "$module/$family=$value"
            }
        }

        assertEquals(
            emptyList<String>(),
            illegal,
            "madurez de superficie fuera de la taxonomia canonica: $illegal. La taxonomia es " +
                "$taxonomy; ampliarla es una decision, no una errata.",
        )
    }

    @Test
    fun `toda superficie declara covers, guarantee y promotion_precondition`() {
        val incomplete = surfaces.flatMap { (module, families) ->
            families.mapNotNull { (family, body) ->
                val covers = body["covers"]?.jsonArray
                val missing = buildList {
                    if (covers == null || covers.isEmpty()) add("covers")
                    if (body["guarantee"]?.jsonArray?.isEmpty() != false) add("guarantee")
                    if (body["promotion_precondition"]?.jsonArray?.isEmpty() != false) {
                        add("promotion_precondition")
                    }
                }
                if (missing.isEmpty()) null else "$module/$family sin ${missing.joinToString()}"
            }
        }

        assertEquals(
            emptyList<String>(),
            incomplete,
            "superficies incompletas: $incomplete. Una familia sin `covers` no protege nada; " +
                "sin `guarantee` no dice que garantiza; sin `promotion_precondition` no dice " +
                "que habria que demostrar para subirla, que es cuando el TELEMETRO se convierte " +
                "en otra etiqueta global.",
        )
    }

    /**
     * The load-bearing one: `covers` must resolve in TWO independent authorities.
     *
     * The ABI dump alone is forgeable — it proves a name ships, not that it works. The manifest
     * alone is forgeable — it declares an intention without proving a consumer ever got a value
     * out of it. Requiring both is what stops a family from being invented to look thorough.
     */
    @Test
    fun `cada covers resuelve en la ABI publicada y en el manifiesto`() {
        val problems = surfaces.flatMap { (module, families) ->
            val dump = apiDump(module)
            families.flatMap { (family, body) ->
                body["covers"]!!.jsonArray.mapNotNull { cover ->
                    val name = cover.jsonPrimitive.content
                    val token = searchToken(name)
                    val inAbi = dump.contains("fun $token ") || dump.contains("fun $token(")
                    val state = manifestStateOf(name)
                    when {
                        !inAbi -> "$module/$family cubre '$name', que no esta en $module.api"
                        state == null -> "$module/$family cubre '$name', ausente de DSL_SURFACE_MANIFEST"
                        else -> null
                    }
                }
            }
        }

        assertEquals(
            emptyList<String>(),
            problems,
            "covers que no resuelven en ambas autoridades: $problems. Una superficie tiene que " +
                "existir en el ABI (la prueba de que se publica) y en el manifiesto (la prueba de " +
                "que significa algo). Con una sola de las dos se puede escribir cualquier clasificacion.",
        )
    }

    @Test
    fun `una superficie UNSUPPORTED_FAIL_CLOSED coincide con el manifiesto`() {
        val mismatches = surfaces.flatMap { (module, families) ->
            families.flatMap { (family, body) ->
                if (body["maturity"]?.jsonPrimitive?.content != "UNSUPPORTED_FAIL_CLOSED") {
                    return@flatMap emptyList<String>()
                }
                body["covers"]!!.jsonArray.mapNotNull { cover ->
                    val name = cover.jsonPrimitive.content
                    val state = manifestStateOf(name)
                    if (state == "UNSUPPORTED_FAIL_CLOSED") null
                    else "$module/$family cubre '$name', que el manifiesto clasifica como '${state ?: "?"}'"
                }
            }
        }

        assertEquals(
            emptyList<String>(),
            mismatches,
            "superficices declaradas UNSUPPORTED_FAIL_CLOSED sobre constructos que el manifiesto " +
                "no dice que se nieguen: $mismatches. Esta clasificacion afirma que no hay " +
                "compatibilidad que proteger; si el constructo funciona, la afirmacion es falsa y " +
                "lo que se pierde es un consumidor que si depende de el.",
        )
    }

    @Test
    fun `ninguna superficie es mas permisiva que su modulo`() {
        val looser = surfaces.flatMap { (module, families) ->
            val moduleRank = permissiveness[maturity[module]]
            families.mapNotNull { (family, body) ->
                val surfaceRank = permissiveness[body["maturity"]?.jsonPrimitive?.content]
                if (surfaceRank != null && moduleRank != null && surfaceRank > moduleRank) {
                    "$module/$family declara ${body["maturity"]!!.jsonPrimitive.content} sobre un " +
                        "modulo ${maturity[module]}"
                } else null
            }
        }

        assertEquals(
            emptyList<String>(),
            looser,
            "superficies mas permisivas que su modulo: $looser. Refinar puede TENSAR la politica, " +
                "nunca aflojarla: una superficie mas laxa que su modulo no es una clasificacion " +
                "mas fina, es una puerta trasera con nombre de familia.",
        )
    }

    /**
     * A surface that says the same thing its module says is not a surface. It is a comment with
     * JSON syntax.
     *
     * This is the law that makes the layer impossible to fake in the cheapest way available: split
     * every published module into named families and copy the module's classification into each
     * one. Nothing breaks, every field is populated, every reference resolves — and the policy is
     * exactly as uninformative as it was before, only longer. A family earns its existence by
     * stating something its module does not.
     */
    @Test
    fun `una superficie que repite la madurez de su modulo no aporta nada`() {
        val redundant = surfaces.flatMap { (module, families) ->
            families.mapNotNull { (family, body) ->
                val declared = body["maturity"]?.jsonPrimitive?.content ?: return@mapNotNull null
                if (declared == maturity[module]) "$module/$family=$declared" else null
            }
        }

        assertEquals(
            emptyList<String>(),
            redundant,
            "superficies que repiten la madurez de su modulo: $redundant. Repartir un modulo en " +
                "familias y copiar su clasificacion en cada una no es refinar la politica: es la " +
                "misma politica con mas lineas, y es la forma mas barata de fingir que se ha " +
                "clasificado. Si la garantia no difiere, la familia no debe existir.",
        )
    }

    @Test
    fun `una superficie UNSUPPORTED_FAIL_CLOSED no aloja excepciones`() {
        val hosted = exceptions.mapNotNull { entry ->
            val module = entry.getValue("module").jsonPrimitive.content
            val declared = entry.getValue("surface").jsonPrimitive.content
            surfaceDeclarations(module)
                .filter { (_, body) -> body["maturity"]?.jsonPrimitive?.content == "UNSUPPORTED_FAIL_CLOSED" }
                .keys
                .firstOrNull { family -> declared.startsWith(family) || family.contains(searchToken(declared)) }
                ?.let { "$module: excepcion sobre '$declared' cae en la superficie $it" }
        }

        assertEquals(
            emptyList<String>(),
            hosted,
            "excepciones sobre superficies que se niegan a si mismas: $hosted. Una excepcion de " +
                "ruptura sobre un constructo que ya falla cerrada en cada llamada no documenta " +
                "una ruptura: documenta que no habia contrato.",
        )
    }
}
