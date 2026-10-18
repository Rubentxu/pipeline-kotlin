package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * R-REG-01: el estado de ciclo de vida de cada ADR está declarado, y con un valor del conjunto
 * cerrado. Autoridad: [ADR-PK-REG-001].
 *
 * ## Qué cerró
 *
 * `CONFLICT-ADR-STATUS-REGISTRY.md` registró como conflicto normativo vivo que tres autoridades
 * describían el campo `status` de forma incompatible, y por la regla de `ACTIVE_DOCUMENTS.md`
 * («si existen documentos normativos contradictorios, registrar el conflicto y requerir ADR») toda
 * decisión que dependiera del estado de ratificación de una ADR quedaba bloqueada.
 *
 * **La medición de ese documento medía el parseo, no el corpus.** Su parser sólo aceptaba la forma
 * `- **Status:** valor` en línea, así que contó 61 ADR «sin campo» que sí lo tienen en otra de las
 * tres formas legales, y contó 8 grafías que son casing, negrita y backticks sobre 3 valores.
 *
 * ## Fidelidad
 *
 * `SOURCE-LEVEL, declarado como tal`. No entra por ninguna autoridad productiva de ejecución: lee
 * el estado declarado de un documento normativo y lo compara con el conjunto cerrado que ese mismo
 * documento declara. No reimplementa nada que el producto ejecute, y no pretende: su sujeto es la
 * *declaración*, no el comportamiento. Un fallo aquí significa «el rótulo no coincide con la
 * política», que es exactamente el defecto que la ADR corrige, y nunca «el runtime está roto».
 *
 * No es hermético-de-red ni de-proceso: sólo lee ficheros del checkout. El repo-root se resuelve
 * subiendo hasta encontrar `docs/v2/04-adrs`, de modo que la fila no depende del `user.dir` con el
 * que Gradle lance el worker.
 */
class AdrStatusRegistryFitnessTest {

    /**
     * El conjunto cerrado de ADR-PK-REG-001 §2. Son tres porque son las tres que el corpus usa
     * de hecho, y `deprecated` **no** está: la retirada se expresa con `superseded` más
     * `superseded_by`, que es estructura de frontmatter ya presente. Añadir un cuarto valor sería
     * un synonym para un caso que la estructura cubre, y un synonym en un campo de estado es
     * exactamente la fila que dos agentes pueden leer distinto.
     */
    private val closedStatusValues = setOf("proposed", "accepted", "superseded")

    /**
     * Anti-vacuo por conteo. Si el árbol de ADRs se vaciara, o se moviera, esta fila tiene que
     * ponerse roja — un guard que se pone verde cuando desaparece su sujeto informa de nada.
     * El umbral es deliberadamente holgado respecto de las 111 medidas: es una barrera contra
     * "no encontré ficheros", no un censo que se pueda romper al añadir una ADR.
     */
    private val minimumAdrCount = 100

    private val repoRoot: Path = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .map { it.toPath() }
        .firstOrNull { Files.isDirectory(it.resolve("docs/v2/04-adrs")) }
        ?: error(
            "could not locate docs/v2/04-adrs from ${System.getProperty("user.dir")}; " +
                "this row would otherwise scan nothing and report green"
        )

    /**
     * Las tres formas de superficie que ADR-PK-REG-001 §3 declara legales. Las tres existen medidas
     * en el corpus: frontmatter o viñeta en línea, el sinónimo español `Estado:`, y el encabezado
     * `## Status` con el valor en la línea siguiente.
     *
     * Una cuarta condición gobierna la tercera forma, y sin ella la fila lee prosa. Medido: bajo un
     * `## Status` de `ADR-0075` la línea siguiente empieza por «`status`, so it cannot trigger on
     * arbitrary rows» —el encabezado da nombre a la sección, no al estado— y un escáner que acepta
     * «la primera línea no vacía» lo toma por el valor. La condición es que la línea **empiece por un
     * token del conjunto cerrado**; la prosa no lo hace, así que la forma de encabezado no declara
     * nada y, si es la única, el fichero queda SIN ESTADO en vez de declarar una frase.
     */
    private fun declaredStatuses(text: String): List<Declaration> {
        val out = mutableListOf<Declaration>()

        for (match in INLINE_STATUS.findAll(text)) {
            canonicalToken(match.groupValues[1])?.let { out += Declaration("linea", it) }
        }

        val lines = text.lines()
        for ((index, line) in lines.withIndex()) {
            if (!HEADING_STATUS.matches(line)) continue
            // El valor es la primera línea siguiente con contenido, y puede ir tras una vacía:
            // `ADR-0043` pone una línea en blanco entre el encabezado y su `Accepted`, y exigir la
            // línea inmediatamente posterior marcaría ROJO un ADR que sí declara su estado.
            for (next in lines.drop(index + 1)) {
                if (next.isBlank()) continue
                if (next.startsWith("#")) break // otro encabezado: no hay valor debajo
                val token = canonicalToken(next)
                if (token != null && token in closedStatusValues) {
                    out += Declaration("encabezado", token)
                }
                // Un token que no es del conjunto cerrado no es un valor: es prosa que el
                // encabezado bautizó. Se ignora en vez de declararse.
                break
            }
        }
        return out
    }

    private data class Declaration(val form: String, val value: String)

    /** El valor es el primer token alfabético; el paréntesis explicativo que sigue no forma parte de él. */
    private fun canonicalToken(raw: String): String? {
        val withoutEmphasis = raw.trim()
            .replace("*", "")
            .replace("`", "")
            .trim()
        return WORD.find(withoutEmphasis)?.value?.lowercase()
    }

    private fun adrFiles(): List<Path> =
        Files.list(repoRoot.resolve("docs/v2/04-adrs")).use { stream ->
            stream.filter { it.fileName.toString().startsWith("ADR-") && it.fileName.toString().endsWith(".md") }
                .sorted()
                .toList()
        }

    @Test
    fun `every ADR declares a status drawn from the closed set`() {
        val files = adrFiles()

        assertTrue(
            files.size >= minimumAdrCount,
            "R-REG-01 anti-vacuo: se esperaban al menos $minimumAdrCount ficheros ADR-*.md bajo " +
                "docs/v2/04-adrs y se encontraron ${files.size}. Una guarda que encuentra de menos " +
                "no vigila nada, y por eso esta fila precede a la del conjunto cerrado."
        )

        val offenders = files.mapNotNull { file ->
            val values = declaredStatuses(Files.readString(file)).map { it.value }.distinct()
            when {
                values.isEmpty() ->
                    "${file.fileName} — SIN ESTADO (ninguna de las tres formas de §3)"
                values.size > 1 ->
                    "${file.fileName} — declara ${values.joinToString(" y ")}"
                values.single() !in closedStatusValues ->
                    "${file.fileName} — valor fuera del conjunto cerrado: ${values.single()}"
                else -> null
            }
        }

        assertEquals(
            emptyList<String>(),
            offenders,
            "R-REG-01: ADR-PK-REG-001 §3 exige una de las TRES formas de superficie legales " +
                "(`status: v`, `Estado: v`, o un encabezado `## Status` cuya línea de valor " +
                "empiece por un token del conjunto cerrado). Fallo cerrado ante la ausencia, ante " +
                "un valor ajeno y ante un fichero que declara dos estados distintos: ese último " +
                "caso es el que permite que dos agentes lean la misma ADR en dos versiones."
        )
    }

    @Test
    fun `the corpus still exercises all three closed values`() {
        val present = adrFiles()
            .flatMap { declaredStatuses(Files.readString(it)) }
            .map { it.value }
            .toSet()

        assertEquals(
            emptySet<String>(),
            closedStatusValues - present,
            "R-REG-01 anti-vacuo por valor: los tres valores cerrados deben estar representados. " +
                "Si uno falta, la guarda del conjunto cerrado se está satisfaciendo con un corpus " +
                "que ya no tiene la variedad que la hizo necesaria —y un corpus todo-`accepted` es " +
                "justo la degeneración que induce el corte del rango 0070..0099."
        )
    }

    private companion object {
        /** `- **Status:** Accepted`, `status: proposed`, `- **Estado:** ACCEPTED (…)`, `Status: ACCEPTED`. */
        private val INLINE_STATUS = Regex(
            """^\s*[-*>#]*\s*\**\s*(?:status|estado)\s*\**\s*:\s*(.+)$""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
        )

        private val HEADING_STATUS = Regex(
            """^\s{0,3}#{1,6}\s*\**\s*(?:status|estado)\s*\**\s*:?\s*$""",
            setOf(RegexOption.IGNORE_CASE),
        )

        private val WORD = Regex("""[A-Za-zÁÉÍÓÚÜÑáéíóúüñ]+""")
    }
}