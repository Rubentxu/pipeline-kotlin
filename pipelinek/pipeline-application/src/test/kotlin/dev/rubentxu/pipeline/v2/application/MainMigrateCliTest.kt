package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.step.StepCatalogue
import dev.rubentxu.pipeline.v2.domain.step.StepCatalogueBody
import dev.rubentxu.pipeline.v2.domain.step.StepCatalogueEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.TimeUnit

/**
 * The report of what this product offers for the steps of Jenkins.
 *
 * ## The guarantee under trial
 *
 * That the report says, for every step of Jenkins it is asked about, WHAT this
 * product offers: the equivalent of the catalogue, the divergence of that
 * equivalent with its motive, and the forms a migrated script has to be
 * written in. And that it says only what it knows: a name the catalogue does
 * not declare is reported as a name the catalogue does not declare, and not as
 * a step that cannot be migrated.
 *
 * ## Why this instrument
 *
 * The report is a pure function of the catalogue and of the names the operator
 * writes, so the whole guarantee can be checked against a catalogue built by
 * hand: the rows say what the report has to carry, and they say it about
 * entries that exist only for the row. The rows that run the verb pay for the
 * path that reads the catalogue of the installation, and they are the ones
 * that prove the published document is parseable.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class MainMigrateCliTest {

    @Test
    fun `a name the catalogue carries names the key and the name the author writes`() {
        val migrations = MainMigrateCli.classify(listOf("sh"), listOf(entry("core.sh", "sh", "sh")))

        val migration = migrations.single()
        assertTrue(migration.declaresEquivalent, "the catalogue declares an equivalent for 'sh'")
        assertEquals("core.sh", migration.entries.single().key, "the entry is the one the catalogue carries")

        val report = MainMigrateCli.report(migrations).joinToString("\n")
        assertTrue(
            report.contains("core.sh") && report.contains("name: sh"),
            "the report names the key and the name the author writes: $report",
        )
    }

    @Test
    fun `a name the catalogue does not declare says the catalogue declares no equivalent`() {
        val migrations = MainMigrateCli.classify(listOf("bat"), listOf(entry("core.sh", "sh", "sh")))

        val migration = migrations.single()
        assertFalse(migration.declaresEquivalent, "the catalogue declares no equivalent for 'bat'")

        val report = MainMigrateCli.report(migrations).joinToString("\n")
        assertTrue(
            report.contains("the catalogue declares no equivalent"),
            "the report says what it knows: $report",
        )
    }

    @Test
    fun `a name the catalogue does not declare does not say that Jenkins does not publish it`() {
        val report = MainMigrateCli.report(MainMigrateCli.classify(listOf("bat"), emptyList())).joinToString("\n")

        assertFalse(
            report.contains("unsupported") || report.contains("not supported"),
            "the report does not claim the step is without support: $report",
        )
        assertFalse(
            report.contains("Jenkins does not publish"),
            "the report does not claim what it cannot know about Jenkins: $report",
        )
    }

    @Test
    fun `a name two entries declare shows both of them`() {
        val entries = listOf(
            entry("core.pwd", "pwd", "pwd"),
            entry("core.pwd.tmp", "pwd.tmp", "pwd", divergence = DIVERGENCE),
        )

        val migration = MainMigrateCli.classify(listOf("pwd"), entries).single()

        assertEquals(2, migration.entries.size, "the two entries that declare the name are both carried")
        val equivalents = MainMigrateCli.report(listOf(migration)).filter { it.trimStart().startsWith(EQUIVALENT) }
        assertEquals(2, equivalents.size, "the report shows one equivalent per entry: $equivalents")
        assertTrue(
            equivalents.any { it.contains("core.pwd  ") } && equivalents.any { it.contains("core.pwd.tmp") },
            "both of the keys are named: $equivalents",
        )
    }

    @Test
    fun `the divergence the report shows comes from the entry that declares it`() {
        val entries = listOf(
            entry("core.pwd", "pwd", "pwd"),
            entry("core.pwd.tmp", "pwd.tmp", "pwd", divergence = DIVERGENCE),
        )

        val report = MainMigrateCli.report(MainMigrateCli.classify(listOf("pwd"), entries))
        val divergences = report.filter { it.trimStart().startsWith(DIVERGENCE_LABEL) }

        assertEquals(
            listOf(NONE, DIVERGENCE),
            divergences.map { it.substringAfter(DIVERGENCE_LABEL).trim() },
            "each entry carries its own divergence, and an entry that declares none says so",
        )
    }

    @Test
    fun `the aggregate counts names and not entries`() {
        val entries = listOf(
            entry("core.pwd", "pwd", "pwd"),
            entry("core.pwd.tmp", "pwd.tmp", "pwd"),
        )

        val report = MainMigrateCli.report(MainMigrateCli.classify(listOf("pwd"), entries)).joinToString("\n")

        assertTrue(
            report.contains("1 name: 1 with an equivalent, 0 without one"),
            "two entries of one name are ONE name in the aggregate: $report",
        )
    }

    @Test
    fun `the report carries the differences that apply to every script`() {
        val report = MainMigrateCli.report(MainMigrateCli.classify(listOf("sh"), emptyList())).joinToString("\n")

        for (divergence in StepCatalogue.divergences()) {
            assertTrue(
                report.contains(divergence.name),
                "the report names the difference '${divergence.name}': $report",
            )
            assertTrue(
                report.contains(divergence.reason),
                "the report says why the two products differ on '${divergence.name}': $report",
            )
        }
    }

    @Test
    fun `the report says the scope of every difference`() {
        val report = MainMigrateCli.report(MainMigrateCli.classify(listOf("sh"), emptyList())).joinToString("\n")

        assertEquals(
            4,
            occurrences(report, LANGUAGE),
            "four of the differences are imposed by the language",
        )
        assertEquals(
            6,
            occurrences(report, PRODUCT),
            "six of the differences are decided by the product",
        )
    }

    @Test
    fun `a difference is written once and not twice`() {
        val report = MainMigrateCli.report(MainMigrateCli.classify(listOf("sh"), emptyList())).joinToString("\n")

        for (divergence in StepCatalogue.divergences()) {
            assertEquals(
                1,
                occurrences(report, divergence.reason),
                "the reason of '${divergence.name}' is written once, and not once per section: $report",
            )
        }
    }

    @Test
    fun `the report names where the forms and the sentence of the compiler are`() {
        val report = MainMigrateCli.report(MainMigrateCli.classify(listOf("sh"), emptyList())).joinToString("\n")

        assertTrue(
            report.contains("pipelinek steps --json"),
            "the report says where the forms are published: $report",
        )
    }

    @Test
    fun `the document names its schema and carries the same facts as the report`() {
        val entries = listOf(
            entry("core.sh", "sh", "sh"),
            entry("core.pwd.tmp", "pwd.tmp", "pwd", divergence = DIVERGENCE),
        )
        val migrations = MainMigrateCli.classify(listOf("sh", "pwd", "bat"), entries)

        val parsed = Json.parseToJsonElement(MainMigrateCli.document(migrations).toString()).jsonObject

        assertEquals(
            "pipelinek.migrate/7",
            parsed["schema"]?.jsonPrimitive?.content,
            "the document names the schema a consumer parses it by",
        )
        val names = parsed["names"]?.jsonArray ?: error("the document carries the names")
        assertEquals(3, names.size, "the document carries one row per name the operator asked about")
        assertEquals(
            "sh",
            names[0].jsonObject["jenkinsStep"]?.jsonPrimitive?.content,
            "the row carries the name of Jenkins it is about",
        )
        assertEquals(
            "equivalent",
            names[0].jsonObject["status"]?.jsonPrimitive?.content,
            "the row says the catalogue declares an equivalent",
        )
        assertEquals(
            "core.sh",
            names[0].jsonObject["equivalents"]?.jsonArray?.single()?.jsonObject?.get("key")?.jsonPrimitive?.content,
            "the row carries the key of the equivalent",
        )
        assertEquals(
            "not-declared",
            names[2].jsonObject["status"]?.jsonPrimitive?.content,
            "the row says the catalogue declares no equivalent",
        )
        val summary = parsed["summary"]?.jsonObject ?: error("the document carries the aggregate")
        assertEquals(3, summary["asked"]?.jsonPrimitive?.content?.toInt(), "three names were asked about")
        assertEquals(2, summary["withEquivalent"]?.jsonPrimitive?.content?.toInt(), "two of them have one")
        assertEquals(1, summary["withoutEquivalent"]?.jsonPrimitive?.content?.toInt(), "one of them has none")
        assertEquals(
            StepCatalogue.divergences().size,
            parsed["divergences"]?.jsonArray?.size,
            "the document carries the same differences the report carries",
        )
        assertEquals(
            StepCatalogue.divergences().map { divergence -> divergence.name },
            parsed["divergences"]?.jsonArray?.map { divergence ->
                divergence.jsonObject["name"]?.jsonPrimitive?.content
            },
            "the differences of the document are the ones the catalogue declares, in the same order",
        )
        assertTrue(
            parsed["forms"] == null,
            "the document carries no second copy of the forms: they are published with the catalogue",
        )
    }

    @Test
    fun `the divergence of the document is the divergence of the entry`() {
        val entries = listOf(entry("core.pwd.tmp", "pwd.tmp", "pwd", divergence = DIVERGENCE))

        val parsed = Json.parseToJsonElement(
            MainMigrateCli.document(MainMigrateCli.classify(listOf("pwd"), entries)).toString(),
        ).jsonObject

        assertEquals(
            DIVERGENCE,
            parsed["names"]?.jsonArray?.single()?.jsonObject
                ?.get("equivalents")?.jsonArray?.single()?.jsonObject
                ?.get("divergence")?.jsonPrimitive?.content,
            "the document carries the divergence the entry declares",
        )
    }

    @Test
    fun `a request that names no step of Jenkins is refused`() {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()

        val code = MainMigrateCli.main(
            arrayOf(),
            PrintStream(out, true, Charsets.UTF_8.name()),
            PrintStream(err, true, Charsets.UTF_8.name()),
        )

        assertEquals(CliExit.INVALID_ARGUMENT.code, code, "a request without a name is refused")
        assertTrue(
            err.toString().contains("--jenkins"),
            "the refusal names the option the operator has to write: ${err.toString()}",
        )
    }

    @Test
    fun `the verb answers about a step of the catalogue of the installation`() {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()

        val code = MainMigrateCli.main(
            arrayOf("--jenkins", "sh"),
            PrintStream(out, true, Charsets.UTF_8.name()),
            PrintStream(err, true, Charsets.UTF_8.name()),
        )

        assertEquals(CliExit.SUCCESS.code, code, "the verb answers with success: ${err.toString()}")
        assertTrue(
            out.toString(Charsets.UTF_8.name()).contains("core.sh"),
            "the report names the equivalent the catalogue carries: ${out.toString()}",
        )
    }

    @Test
    fun `the verb publishes a document a consumer can parse`() {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()

        val code = MainMigrateCli.main(
            arrayOf("--jenkins", "sh", "--json"),
            PrintStream(out, true, Charsets.UTF_8.name()),
            PrintStream(err, true, Charsets.UTF_8.name()),
        )

        assertEquals(CliExit.SUCCESS.code, code, "the verb answers with success: ${err.toString()}")
        val parsed = Json.parseToJsonElement(out.toString(Charsets.UTF_8.name())).jsonObject
        assertEquals(
            "pipelinek.migrate/7",
            parsed["schema"]?.jsonPrimitive?.content,
            "what the verb publishes is the document the schema names",
        )
    }

    @Test
    fun `the verb appears in the help of the CLI`() {
        val buffer = StringBuilder()
        printUsage(buffer)

        assertTrue(
            buffer.toString().contains("migrate"),
            "the help names the verb: ${buffer.toString()}",
        )
    }

    /** An entry built by hand, for the rows that need a shape the core does not have. */
    private fun entry(
        key: String,
        name: String,
        surface: String,
        divergence: String = "",
    ): StepCatalogueEntry.Step = StepCatalogueEntry.Step(
        key = key,
        name = name,
        provider = "core",
        jenkinsSurface = surface,
        deprecation = "",
        effects = emptyList(),
        replayPolicy = "MEMOIZED",
        recoveryPolicy = "None",
        requiredCapabilities = emptyList(),
        executionLocation = "CONTROLLER",
        body = StepCatalogueBody(kind = "none"),
        inputSchema = "{}",
        outputSchema = "{}",
        jenkinsDivergence = divergence,
    )

    /** How many times [text] carries [needle]. */
    private fun occurrences(text: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var index = text.indexOf(needle)
        while (index >= 0) {
            count += 1
            index = text.indexOf(needle, index + needle.length)
        }
        return count
    }

    private companion object {
        const val DIVERGENCE = "the temporary directory is a key of its own"
        const val EQUIVALENT = "equivalent"
        const val DIVERGENCE_LABEL = "divergence"
        const val NONE = "(none)"
        const val LANGUAGE = "[language]"
        const val PRODUCT = "[product]"
    }
}
