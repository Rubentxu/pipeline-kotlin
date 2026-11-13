package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.step.JenkinsDivergence
import dev.rubentxu.pipeline.v2.domain.step.StepCatalogue
import dev.rubentxu.pipeline.v2.domain.step.StepCatalogueEntry
import dev.rubentxu.pipeline.v2.runtime.RuntimeApiVersion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.PrintStream

/**
 * The usage `migrate` prints when its request is refused.
 */
internal const val MIGRATE_USAGE: String =
    "Usage: pipelinek migrate --jenkins <name> [--jenkins <name>]... [--json] " +
        "[--plugin-jar <jar>]... [--plugin-trust <file>] [--require-plugin-trust]"

/**
 * The requests `migrate` accepts.
 *
 * `--jenkins` names a step of Jenkins and may be given more than once: the
 * report is about the steps the operator asks about, and an operator migrating
 * a script has several of them. `--json` asks for the document instead of the
 * text. The three plugin options are the ones `steps` and `describe` read with
 * the same names, and they mean the same: a step of a plugin is only in the
 * catalogue if the plugin's code is in the composition.
 */
internal val MIGRATE_REQUEST: CliRequest = CliRequest(
    options = listOf(
        CliOption("--jenkins", takesValue = true, repeatable = true),
        CliOption("--json"),
        CliOption("--plugin-jar", takesValue = true, repeatable = true),
        CliOption("--plugin-trust", takesValue = true),
        CliOption("--require-plugin-trust"),
    ),
)

/**
 * `pipelinek migrate` — what this product offers for the steps of Jenkins.
 *
 * ## What it answers
 *
 * For every step of Jenkins it is asked about: the equivalent the catalogue
 * declares (the key the runtime resolves and the name the author writes), the
 * divergence of that equivalent with its motive, and the FORMS a migrated
 * script has to be written in.
 *
 * ## What it does NOT say
 *
 * It does not say that a step of Jenkins cannot be migrated, and it does not
 * say that Jenkins does not publish it. The catalogue declares the equivalents
 * it knows and is silent about the rest: the product cannot tell a step of a
 * plugin that is not installed from a step that has no equivalent here, and a
 * report that claimed either would be making a claim it cannot back. So a name
 * the catalogue does not declare is reported as exactly that.
 *
 * ## What it does not do
 *
 * It reads no file. The INVENTORY of the steps a Jenkinsfile uses is the
 * reader's work, and the reader is not the product: the report starts from the
 * names it is given.
 */
internal object MainMigrateCli {

    /**
     * The schema of the document `--json` publishes.
     *
     * It is `pipelinek.migrate/7`: the document carries the DIFFERENCES with
     * their reason, and the reason of the divergence of the CONTROL FLOW
     * changed what it says — it names the block `steps { }` beside the block
     * `script { }`, so the reader of the block a Jenkinsfile writes sees when
     * his `if` decides — so the version is the warning to the consumer that
     * reads it again. It was `pipelinek.migrate/6`: the document carried ONE
     * MORE difference — the divergence of the DECLARATION of a variable, whose
     * reason says that the assignment of Jenkins does not compile and that the
     * product declares with a call — so the version was the warning to the
     * consumer that reads it again. It was
     * `pipelinek.migrate/5`: the reason of the divergence of the workspace
     * changed what it says — the stages of a run share one workspace by
     * default, and `--isolated` is the run that gives each stage its own — so
     * the version was the warning to the consumer that reads it again. It was
     * `pipelinek.migrate/4`: the reasons of the divergence of the gate and of
     * the divergence of the branch changed what they say, so the version was
     * the warning to the consumer that reads them again. It was
     * `pipelinek.migrate/3`: the reason of the divergence of the dollar changed
     * what it says, so the version was the warning to the consumer that reads
     * it again. It was `pipelinek.migrate/2`: the document carries the
     * classification of the names the operator asked about, the aggregate of
     * that classification and the DIFFERENCES that apply to every script. It
     * was `pipelinek.migrate/1` when the document carried the forms: the forms
     * are three of those differences written in another way, and a document
     * that carried both would carry one sentence twice —MEASURED, the reason of
     * the form of the named argument is byte-identical to the reason of the
     * difference of the named arguments.
     */
    const val SCHEMA: String = "pipelinek.migrate/7"

    /**
     * The classification of the names the operator asked about.
     *
     * The names keep the order of the request and appear once: an operator
     * that asks about the same name twice wants one answer about it, and the
     * aggregate counts NAMES.
     *
     * A name is matched EXACTLY, because the name is the one Jenkins itself
     * publishes and the catalogue declares it with those letters: a reader
     * that lowered the case would report a name the catalogue does not carry
     * for a step it does carry.
     */
    fun classify(
        names: List<String>,
        entries: List<StepCatalogueEntry>,
    ): List<Migration> = names.distinct().map { name ->
        Migration(name, entries.filter { entry -> entry.jenkinsSurface == name })
    }

    /**
     * The report, one line per fact.
     *
     * The facts of a name come first, and the differences come after: a reader
     * asks "what do I write instead" before it asks "what else is different".
     *
     * The differences are the ONES that apply to every script, and they are
     * all of them: the ones that are imposed by the language and the ones the
     * product decides. The forms of Jenkins are not repeated here —they are
     * three of these differences in another wording, and the sentence the
     * compiler prints for each one belongs to the compiler.
     */
    fun report(
        migrations: List<Migration>,
        divergences: List<JenkinsDivergence> = StepCatalogue.divergences(),
    ): List<String> {
        val lines = mutableListOf<String>()
        migrations.forEachIndexed { index, migration ->
            if (index > 0) lines += ""
            lines += migration.jenkinsStep
            lines += field(STATUS, if (migration.declaresEquivalent) EQUIVALENT_DECLARED else EQUIVALENT_ABSENT)
            if (migration.entries.isEmpty()) {
                lines += field(EQUIVALENT, NONE)
                lines += field(DIVERGENCE, NONE)
            } else {
                migration.entries.forEach { entry ->
                    lines += field(EQUIVALENT, "${entry.key}  (name: ${entry.name})")
                    lines += field(DIVERGENCE, entry.jenkinsDivergence.ifEmpty { NONE })
                }
            }
        }
        lines += ""
        lines += aggregate(migrations)
        lines += ""
        lines += DIVERGENCES_HEADING
        divergences.forEach { divergence ->
            lines += "  [${divergence.scope.name.lowercase()}] ${divergence.name}"
            lines += "    ${divergence.reason}"
        }
        lines += ""
        lines += FORMS_HEADING
        return lines
    }

    /**
     * The report as a document.
     *
     * The differences are rendered by the catalogue's own renderer, so the text
     * of a difference cannot differ between the two documents that carry it.
     */
    fun document(
        migrations: List<Migration>,
        divergences: List<JenkinsDivergence> = StepCatalogue.divergences(),
    ): JsonObject = buildJsonObject {
        put("schema", JsonPrimitive(SCHEMA))
        put("version", JsonPrimitive(RuntimeApiVersion.readImplementationVersion() ?: "unknown"))
        put("names", JsonArray(migrations.map { migration -> nameOf(migration) }))
        put("summary", summaryOf(migrations))
        put("divergences", JsonArray(divergences.map { divergence -> MainStepsCli.divergenceOf(divergence) }))
    }

    /** One name of Jenkins, as the document carries it. */
    private fun nameOf(migration: Migration): JsonObject = buildJsonObject {
        put("jenkinsStep", JsonPrimitive(migration.jenkinsStep))
        put(
            "status",
            JsonPrimitive(if (migration.declaresEquivalent) STATUS_EQUIVALENT else STATUS_ABSENT),
        )
        put("equivalents", JsonArray(migration.entries.map { entry -> equivalentOf(entry) }))
    }

    /** One equivalent, as the document carries it. */
    private fun equivalentOf(entry: StepCatalogueEntry): JsonObject = buildJsonObject {
        put("key", JsonPrimitive(entry.key))
        put("name", JsonPrimitive(entry.name))
        put("divergence", JsonPrimitive(entry.jenkinsDivergence))
    }

    /** The aggregate, as the document carries it. */
    private fun summaryOf(migrations: List<Migration>): JsonObject {
        val with = migrations.count { migration -> migration.declaresEquivalent }
        return buildJsonObject {
            put("asked", JsonPrimitive(migrations.size))
            put("withEquivalent", JsonPrimitive(with))
            put("withoutEquivalent", JsonPrimitive(migrations.size - with))
        }
    }

    /**
     * The aggregate, in one line.
     *
     * It counts NAMES and not entries: a name that two entries declare is one
     * name, and the report shows both of its entries above.
     */
    private fun aggregate(migrations: List<Migration>): String {
        val with = migrations.count { migration -> migration.declaresEquivalent }
        val names = if (migrations.size == 1) "name" else "names"
        return "${migrations.size} $names: $with with an equivalent, ${migrations.size - with} without one"
    }

    /** One field of the report, with its label padded so the values line up. */
    private fun field(label: String, value: String): String =
        "  ${label.padEnd(FIELD_WIDTH)}$value"

    /**
     * Runs the verb and writes what it answers to the streams it is given.
     *
     * @return 0 on success.
     */
    fun main(args: Array<String>, out: PrintStream, err: PrintStream): Int {
        val admission = CliOptionAdmission.admit(args, MIGRATE_REQUEST)
        val accepted = when (admission) {
            is CliAdmission.Rejected -> {
                err.println(admission.error.refusalLines("migrate").joinToString("\n"))
                err.println(MIGRATE_USAGE)
                return CliExit.INVALID_ARGUMENT.code
            }
            is CliAdmission.Admitted -> admission
        }
        val names = accepted.repeated["--jenkins"].orEmpty()
        if (names.isEmpty()) {
            val error = CliError.MissingRequiredArgument(
                "--jenkins",
                "name at least one step of Jenkins to ask about",
            )
            err.println(error.refusalLines("migrate").joinToString("\n"))
            err.println(MIGRATE_USAGE)
            return error.toCliExit().code
        }
        val blank = names.firstOrNull { name -> name.isBlank() }
        if (blank != null) {
            val error = CliError.InvalidOptionValue("--jenkins", "not the name of a step of Jenkins")
            err.println(error.refusalLines("migrate").joinToString("\n"))
            err.println(MIGRATE_USAGE)
            return error.toCliExit().code
        }
        val catalogue = when (val read = readCatalogue(accepted, err, "migrate")) {
            is CatalogueRead.Refused -> return read.exit.code
            is CatalogueRead.Read -> read
        }
        val migrations = classify(names, catalogue.entries)
        if ("--json" in accepted.flags) {
            val line = Json { prettyPrint = false; encodeDefaults = true }
                .encodeToString(JsonObject.serializer(), document(migrations)) + "\n"
            out.write(line.toByteArray(Charsets.UTF_8))
            out.flush()
            return CliExit.SUCCESS.code
        }
        report(migrations).forEach { out.println(it) }
        return CliExit.SUCCESS.code
    }

    /** The width of the longest label, so the values line up. */
    private const val FIELD_WIDTH = 14

    /** The label of the line that says what the catalogue declares. */
    private const val STATUS = "status"

    /** The label of the line that names the equivalent. */
    private const val EQUIVALENT = "equivalent"

    /** The label of the line that names the divergence. */
    private const val DIVERGENCE = "divergence"

    /** What a fact the report does not carry says. */
    private const val NONE = "(none)"

    /** What the report says of a name the catalogue declares an equivalent for. */
    private const val EQUIVALENT_DECLARED = "the catalogue declares an equivalent"

    /** What the report says of a name the catalogue declares no equivalent for. */
    private const val EQUIVALENT_ABSENT = "the catalogue declares no equivalent"

    /** The status a consumer of the document reads for a name with an equivalent. */
    private const val STATUS_EQUIVALENT = "equivalent"

    /** The status a consumer of the document reads for a name without one. */
    private const val STATUS_ABSENT = "not-declared"

    /** The heading of the section that carries the differences of every script. */
    private const val DIVERGENCES_HEADING = "the differences that apply to every script:"

    /**
     * The heading of the section that says where the forms are.
     *
     * The forms are three of the differences written in another way, and the
     * sentence the compiler prints for each one belongs to the compiler: the
     * catalogue publishes both, and the report names where they live instead
     * of carrying a second copy.
     */
    private const val FORMS_HEADING =
        "the forms the compiler recognizes, and the sentence it prints for each one, " +
            "are published with the catalogue: pipelinek steps --json"
}

/**
 * One step of Jenkins, and what the catalogue offers for it.
 *
 * @property jenkinsStep the name of the step of Jenkins the operator asked about.
 * @property entries the entries of the catalogue that declare that name. Empty when the
 *   catalogue declares no equivalent for it, and MORE THAN ONE when two entries declare
 *   the same name: `pwd` is declared by `core.pwd` and by `core.pwd.tmp`.
 */
internal data class Migration(
    val jenkinsStep: String,
    val entries: List<StepCatalogueEntry>,
) {
    /** Whether the catalogue declares an equivalent for this name. */
    val declaresEquivalent: Boolean get() = entries.isNotEmpty()
}
