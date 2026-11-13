package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.StepSchema
import dev.rubentxu.pipeline.v2.domain.step.JenkinsDivergence
import dev.rubentxu.pipeline.v2.domain.step.JenkinsForm
import dev.rubentxu.pipeline.v2.domain.step.JenkinsForms
import dev.rubentxu.pipeline.v2.domain.step.StepCatalogue
import dev.rubentxu.pipeline.v2.domain.step.StepCatalogueEntry
import dev.rubentxu.pipeline.v2.runtime.NoopPipelineTelemetry
import dev.rubentxu.pipeline.v2.runtime.PreResolvedComposition
import dev.rubentxu.pipeline.v2.runtime.RuntimeApiVersion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.PrintStream
import java.nio.file.InvalidPathException

/**
 * The usage `steps` prints when its request is refused.
 */
internal const val STEPS_USAGE: String =
    "Usage: pipelinek steps [--json] [--plugin-jar <jar>]... [--plugin-trust <file>] [--require-plugin-trust]"

/**
 * The usage `describe` prints when its request is refused.
 */
internal const val DESCRIBE_USAGE: String =
    "Usage: pipelinek describe <step> [--plugin-jar <jar>]... [--plugin-trust <file>] [--require-plugin-trust]"

/**
 * The requests `steps` accepts.
 *
 * `--json` asks for the document instead of the index. The three plugin
 * options are the ones `run` reads with the same names, and they mean the same:
 * the jars of the operator's plugins, the trust file that vouches for them, and
 * the demand that every one of them is vouched for.
 */
internal val STEPS_REQUEST: CliRequest = CliRequest(
    options = listOf(
        CliOption("--json"),
        CliOption("--plugin-jar", takesValue = true, repeatable = true),
        CliOption("--plugin-trust", takesValue = true),
        CliOption("--require-plugin-trust"),
    ),
)

/**
 * The requests `describe` accepts: the plugin options of `steps`, and the name
 * of the entry to read.
 */
internal val DESCRIBE_REQUEST: CliRequest = CliRequest(
    options = listOf(
        CliOption("--plugin-jar", takesValue = true, repeatable = true),
        CliOption("--plugin-trust", takesValue = true),
        CliOption("--require-plugin-trust"),
    ),
    positional = PositionalArgument.ONE,
)

/**
 * `pipelinek steps` — the index of what this build supports.
 *
 * ## What it answers
 *
 * The entries of the EFFECTIVE composition: the core, the plugins of the
 * installation, and the plugins the operator named with `--plugin-jar`. The
 * composition is composed the way a run composes it, through the same
 * admission, so the catalogue cannot describe a composition a run would refuse.
 *
 * ## What it does not do
 *
 * It starts nothing, writes nothing and runs no step of a pipeline. It does
 * load the plugins' classes, because a plugin's steps exist only in its code:
 * the catalogue that could not see a plugin would be a catalogue of the core
 * with a plugin's name in it.
 */
internal object MainStepsCli {

    /**
     * The schema of the document `--json` publishes.
     *
     * It is `pipelinek.steps/11` since the reason of the DIVERGENCE of the
     * DECLARATION of a variable says what the DECLARATION reaches: the
     * declaration of a stage wins over the one of the pipeline, name by name,
     * and only the `env(name, value)` call declares a variable the stage, its
     * gate and its steps can see. The reason that said that the declaration
     * of the pipeline reached the steps of EVERY stage told the reader with a
     * stage of its own that it did not, and the sentence that said that the
     * name had to be declared invited a `val` that declares a local of the
     * block and nothing else. The reason of the DIVERGENCE of the environment
     * a script reads says now WHEN a read sees a declaration. A consumer that
     * matched on the text it read has to read it again.
     *
     * It was `pipelinek.steps/10` since the reason of the DIVERGENCE of the
     * CONTROL FLOW names the block `steps { }` beside the block `script { }`:
     * the block a Jenkinsfile writes declares the steps of a stage, and its
     * Kotlin is evaluated while the PLAN is built, the same way the body of a
     * `script { }` block is. The reason that named only the `script { }` block
     * sent the reader of a `steps { }` block to the wrong conclusion about
     * when his `if` decides, and a consumer that matched on the text it read
     * has to read it again.
     *
     * It was `pipelinek.steps/9` since the reason of the DIVERGENCE of the
     * DECLARATION of a variable says the form of Jenkins and the form of this
     * product: the assignment `FOO = 'x'` does not compile, and the compiler
     * answers `Unresolved reference 'FOO'`. The document that did not carry
     * that difference sent the reader to a diagnostic with no pointer to the
     * form that works, and a consumer that matched on the text it read has to
     * read it again.
     *
     * It was `pipelinek.steps/8` since the reason of the DIVERGENCE of the
     * workspace says what the product does with the workspace of a stage: the
     * stages of a run SHARE one workspace by default, and the run that gives
     * every stage a workspace of its own is the one that asks for it with
     * `--isolated`. The text that said the opposite published a fact the code
     * does not keep: a reader who migrated a Jenkinsfile against it expected
     * a stage not to see the files another stage wrote, and the product lets
     * it see them. A consumer that matched on the text it read has to read it
     * again.
     *
     * It was `pipelinek.steps/7` since the reason of the DIVERGENCE of the gate
     * says what the gate does with a variable that nobody declares: the stage
     * does not run, and the gate does not fail the run. The reason of the
     * DIVERGENCE of the branch says that the product has no branch of its own,
     * and that the variable of the branch is one the SCRIPT declares — the
     * text that sent the reader to a variable the product does not declare
     * published a promise the code does not keep. A consumer that matched on
     * the text it read has to read it again.
     *
     * It was `pipelinek.steps/6` since the reason of the DIVERGENCE of the dollar
     * says what the compiler does: a string of several lines is a raw string,
     * and a raw string reads a dollar as a template, so the text that said the
     * opposite published a fact the compiler does not keep. The reason also
     * names the spelling that reaches the shell in each of the two strings, and
     * a consumer that matched on the text it read has to read it again.
     *
     * It was `pipelinek.steps/5` since a schema of a step is PUBLISHED only
     * when the product declares it: the key `inputSchema` is ABSENT from the
     * entry of a step whose arguments nobody declared, and it is PRESENT —
     * with the arguments it names, or with none at all — when the codec or
     * the descriptor declares them. Before `/5` the key was always there, and
     * its empty value said two things at once: "this step takes no
     * arguments" and "nobody said". A reader could not tell them apart, and a
     * document that cannot tell them apart publishes an empty fact.
     *
     * It was `pipelinek.steps/4` since the reason of a FORM says why the
     * compiler stops at that shape, and not why the two products differ: the
     * divergences own that reason, and a document that printed the same
     * sentence in both sections would publish one fact twice. It was
     * `pipelinek.steps/3` when the document carried the forms WITH the reason
     * of the divergence they realise, and the two sections said the same
     * thing. It was `pipelinek.steps/2` when the document carried the
     * divergences: the entries carry the equivalent in Jenkins and the
     * divergence of THAT entry, and the document carries the section of the
     * divergences that are the same for every script. A consumer that parses
     * `/1` reads an entry without its divergence, a consumer that parses `/2`
     * does not read the forms, and a consumer that parses `/3` reads a reason
     * in `forms` that said what `divergences` says; the version names that
     * change.
     */
    const val SCHEMA: String = "pipelinek.steps/11"

    /**
     * The published document, as a value.
     *
     * It is a function and not a block inside [main] so that the contract can
     * be READ: the shape a consumer parses is a value this build names, and a
     * shape that only the code knows about is not a shape.
     */
    fun document(
        entries: List<StepCatalogueEntry>,
        divergences: List<JenkinsDivergence> = StepCatalogue.divergences(),
        forms: List<JenkinsForm> = JenkinsForms.global(),
    ): JsonObject = buildJsonObject {
        put("schema", JsonPrimitive(SCHEMA))
        put("version", JsonPrimitive(RuntimeApiVersion.readImplementationVersion() ?: "unknown"))
        put("entries", JsonArray(entries.map { entry -> entryOf(entry) }))
        put("divergences", JsonArray(divergences.map { divergence -> divergenceOf(divergence) }))
        put("forms", JsonArray(forms.map { form -> formOf(form) }))
    }

    /**
     * One form of Jenkins, as the document carries it.
     *
     * The suggestion travels with it because it is the sentence the compiler
     * prints: a reader of the document can see the exact text the author will
     * read, instead of a form they have to imagine.
     *
     * The reason travels with it too, and it is the reason the COMPILER stops
     * at that shape: the reason the two products differ is published with the
     * divergences, and a document that carried the same sentence in the two
     * sections would publish one fact twice.
     *
     * It is INTERNAL and not private because the report of the migration
     * renders the forms with it: two documents that carry the same form have
     * to say the same thing about it, and two renderings of one form are two
     * texts that can drift.
     */
    internal fun formOf(form: JenkinsForm): JsonObject = buildJsonObject {
        put("shape", JsonPrimitive(form.shape.name.lowercase()))
        put("jenkinsForm", JsonPrimitive(form.jenkinsForm))
        put("pipelineKForm", JsonPrimitive(form.pipelineKForm))
        put("reason", JsonPrimitive(form.reason))
        put("suggestion", JsonPrimitive(form.suggestion))
    }

    /**
     * One global divergence, as the document carries it.
     *
     * The scope is carried because a reader who has to guess whether a
     * difference is imposed by the language or by the product cannot tell
     * whether a workaround applies to their script.
     *
     * It is INTERNAL and not private because the report of the migration
     * renders the differences with it: two documents that carry the same
     * difference have to say the same thing about it, and two renderings of
     * one difference are two texts that can drift.
     */
    internal fun divergenceOf(divergence: JenkinsDivergence): JsonObject = buildJsonObject {
        put("name", JsonPrimitive(divergence.name))
        put("scope", JsonPrimitive(divergence.scope.name.lowercase()))
        put("reason", JsonPrimitive(divergence.reason))
    }

    /**
     * The entry of one step, as the document carries it.
     *
     * The facts are the ones the descriptor and the codec declared, read and
     * not copied. The schema of the payload is carried as the TEXT the codec
     * declares, because that is the contract the product itself carries: a
     * consumer that wants an object parses it.
     *
     * The schema is carried ONLY when the product declares one. The ABSENCE
     * of the key says that nobody declared the arguments of the step, and a
     * schema that is present says what they are — including the case of a
     * step that takes none, whose schema carries no arguments at all. The
     * two are different facts and the document tells them apart.
     */
    private fun entryOf(entry: StepCatalogueEntry): JsonObject = buildJsonObject {
        put("key", JsonPrimitive(entry.key))
        put("kind", JsonPrimitive(entry.kind.name.lowercase()))
        put("name", JsonPrimitive(entry.name))
        put("provider", JsonPrimitive(entry.provider))
        when (entry) {
            is StepCatalogueEntry.Step -> {
                put("effects", JsonArray(entry.effects.map { JsonPrimitive(it) }))
                put("replayPolicy", JsonPrimitive(entry.replayPolicy))
                put("recoveryPolicy", JsonPrimitive(entry.recoveryPolicy))
                put(
                    "requiredCapabilities",
                    JsonArray(entry.requiredCapabilities.map { JsonPrimitive(it) }),
                )
                put("executionLocation", JsonPrimitive(entry.executionLocation))
                put("body", bodyOf(entry))
                StepSchema.declared(entry.inputSchema)?.let { put("inputSchema", JsonPrimitive(it)) }
                StepSchema.declared(entry.outputSchema)?.let { put("outputSchema", JsonPrimitive(it)) }
            }
            is StepCatalogueEntry.Directive -> {
                put("phase", JsonPrimitive(entry.phase))
                put("policy", JsonPrimitive(entry.policy))
                put("policyDetail", JsonPrimitive(entry.policyDetail))
            }
        }
        put("jenkinsSurface", JsonPrimitive(entry.jenkinsSurface))
        put("jenkinsDivergence", JsonPrimitive(entry.jenkinsDivergence))
        put("deprecation", JsonPrimitive(entry.deprecation))
    }

    /** The body a step declares, as the document carries it. */
    private fun bodyOf(entry: StepCatalogueEntry.Step): JsonObject = buildJsonObject {
        put("kind", JsonPrimitive(entry.body.kind))
        if (entry.body.kind == "declared") {
            put("invocation", JsonPrimitive(entry.body.invocation))
            put("owner", JsonPrimitive(entry.body.owner))
            put("policy", JsonPrimitive(entry.body.policy))
            put("introduces", JsonPrimitive(entry.body.introduces))
            put("catchesInterruptions", JsonPrimitive(entry.body.catchesInterruptions))
        }
    }

    /**
     * The index, one line per entry.
     *
     * The kind comes first because it is what tells the two families apart, and
     * the key comes before the name because the key is the identity: two
     * entries can share a name (`core.pwd` and `core.pwd.tmp` are both called
     * `pwd`) and none can share a key.
     */
    fun index(entries: List<StepCatalogueEntry>): List<String> {
        val keyWidth = entries.maxOfOrNull { it.key.length } ?: 0
        return entries.map { entry ->
            val kind = entry.kind.name.lowercase().padEnd(DIRECTIVE_KIND.length)
            "$kind  ${entry.key.padEnd(keyWidth)}  ${entry.name}"
        }
    }

    /** The width of the longest kind, so the two columns line up. */
    private const val DIRECTIVE_KIND = "directive"

    /**
     * Runs the verb and writes what it answers to the streams it is given.
     *
     * @return 0 on success.
     */
    fun main(args: Array<String>, out: PrintStream, err: PrintStream): Int {
        val admission = CliOptionAdmission.admit(args, STEPS_REQUEST)
        val accepted = when (admission) {
            is CliAdmission.Rejected -> {
                err.println(admission.error.refusalLines("steps").joinToString("\n"))
                err.println(STEPS_USAGE)
                return CliExit.INVALID_ARGUMENT.code
            }
            is CliAdmission.Admitted -> admission
        }
        val catalogue = when (val read = readCatalogue(accepted, err)) {
            is CatalogueRead.Refused -> return read.exit.code
            is CatalogueRead.Read -> read
        }
        if ("--json" in accepted.flags) {
            val line = Json { prettyPrint = false; encodeDefaults = true }
                .encodeToString(JsonObject.serializer(), document(catalogue.entries)) + "\n"
            out.write(line.toByteArray(Charsets.UTF_8))
            out.flush()
            return CliExit.SUCCESS.code
        }
        index(catalogue.entries).forEach { out.println(it) }
        return CliExit.SUCCESS.code
    }
}

/**
 * `pipelinek describe` — the card of one entry of the catalogue.
 *
 * ## How the name is resolved
 *
 * The key first (`core.sh`), because the key is the identity. The name
 * second (`sh`), because the name is what the author writes, and a name that
 * two entries share is refused with both of them named rather than answered
 * with one of them at random.
 */
internal object MainDescribeCli {

    /**
     * The card of one entry.
     *
     * The fields are the ones the entry carries, in the order a reader asks
     * for them: what it is, what it does to the world, what happens when the
     * run is resumed, and what it needs.
     */
    fun card(entry: StepCatalogueEntry): List<String> {
        val lines = mutableListOf<String>()
        lines += entry.key
        lines += field("kind", entry.kind.name.lowercase())
        lines += field("name", entry.name)
        lines += field("provider", entry.provider)
        when (entry) {
            is StepCatalogueEntry.Step -> {
                lines += field("effects", entry.effects.joinToString(", ").ifEmpty { NONE })
                lines += field("replay", entry.replayPolicy)
                lines += field("recovery", entry.recoveryPolicy)
                lines += field(
                    "capabilities",
                    entry.requiredCapabilities.joinToString(", ").ifEmpty { NONE },
                )
                lines += field("location", entry.executionLocation)
                lines += field("body", bodyLine(entry))
                lines += "  input schema:"
                lines += schemaLines(entry.inputSchema)
                lines += "  output schema:"
                lines += schemaLines(entry.outputSchema)
            }
            is StepCatalogueEntry.Directive -> {
                lines += field("phase", entry.phase)
                lines += field(
                    "policy",
                    if (entry.policyDetail.isEmpty()) entry.policy else "${entry.policy}(${entry.policyDetail})",
                )
            }
        }
        lines += field("jenkins", entry.jenkinsSurface.ifEmpty { NONE })
        lines += field("divergence", entry.jenkinsDivergence.ifEmpty { NONE })
        lines += field("deprecation", entry.deprecation.ifEmpty { NONE })
        return lines
    }

    /** The body of a step, as one line: `none`, or the shape it declares. */
    private fun bodyLine(entry: StepCatalogueEntry.Step): String {
        if (entry.body.kind == "none") return "none"
        return "${entry.body.invocation} body, ${entry.body.policy}, owner ${entry.body.owner}"
    }

    /**
     * The schema of a payload, as the codec declared it, indented under its label.
     *
     * A schema that nobody declared is NOT a schema: `{}` is the marker the
     * domain uses to say so, and publishing it would publish a marker of
     * another contract as if it were a schema. The card says what the other
     * empty fields say: `(none)`.
     *
     * The criterion is the one of the DOMAIN, so the card and the document
     * `pipelinek steps --json` publishes cannot disagree about which entries
     * declare a schema: the document omits the key of the ones that do not,
     * and the card says that it is absent.
     */
    private fun schemaLines(schema: String): List<String> {
        val declared = StepSchema.declared(schema) ?: return listOf("    $NONE")
        return declared.trim().lines().map { "    $it" }
    }

    /** One field of the card, with its label padded so the values line up. */
    private fun field(label: String, value: String): String =
        "  ${label.padEnd(FIELD_WIDTH)}$value"

    /** The width of the longest label, so the values line up. */
    private const val FIELD_WIDTH = 14

    /** What a field that carries nothing says. */
    private const val NONE = "(none)"

    /**
     * Runs the verb and writes what it answers to the streams it are given.
     *
     * @return 0 on success.
     */
    fun main(args: Array<String>, out: PrintStream, err: PrintStream): Int {
        val admission = CliOptionAdmission.admit(args, DESCRIBE_REQUEST)
        val accepted = when (admission) {
            is CliAdmission.Rejected -> {
                err.println(admission.error.refusalLines("describe").joinToString("\n"))
                err.println(DESCRIBE_USAGE)
                return CliExit.INVALID_ARGUMENT.code
            }
            is CliAdmission.Admitted -> admission
        }
        val asked = accepted.positionals.firstOrNull()
        if (asked == null) {
            val error = CliError.MissingRequiredArgument(
                "step",
                "name a step by its key (core.sh) or by its name (sh)",
            )
            err.println(error.refusalLines("describe").joinToString("\n"))
            err.println(DESCRIBE_USAGE)
            return error.toCliExit().code
        }
        val catalogue = when (val read = readCatalogue(accepted, err, "describe")) {
            is CatalogueRead.Refused -> return read.exit.code
            is CatalogueRead.Read -> read
        }
        return when (val found = resolve(asked, catalogue.entries)) {
            is StepResolution.Found -> {
                card(found.entry).forEach { out.println(it) }
                CliExit.SUCCESS.code
            }
            is StepResolution.Refused -> {
                err.println(found.error.refusalLines("describe").joinToString("\n"))
                err.println(DESCRIBE_USAGE)
                found.error.toCliExit().code
            }
        }
    }
}

/**
 * How the name the operator wrote is resolved against the catalogue.
 */
internal sealed interface StepResolution {

    /** The name named exactly one entry. */
    data class Found(val entry: StepCatalogueEntry) : StepResolution

    /** The name named no entry, or more than one. */
    data class Refused(val error: CliError) : StepResolution
}

/**
 * The entry [asked] names, or the refusal of a name that names none or many.
 *
 * The key is matched first: it is the identity, and it is unique. The name is
 * matched second and case-insensitively, because the name is what the author
 * writes. A name that two entries share is refused with both of them named,
 * because answering with one of them would be a coin toss dressed as an
 * answer.
 */
internal fun resolve(asked: String, entries: List<StepCatalogueEntry>): StepResolution {
    entries.firstOrNull { it.key == asked }?.let { return StepResolution.Found(it) }
    val byName = entries.filter { it.name.equals(asked, ignoreCase = true) }
    if (byName.size == 1) return StepResolution.Found(byName.first())
    if (byName.size > 1) {
        return StepResolution.Refused(
            CliError.UnknownStep(asked, byName.map { it.key }, ambiguous = true),
        )
    }
    return StepResolution.Refused(
        CliError.UnknownStep(asked, nearMatches(asked, entries), ambiguous = false),
    )
}

/**
 * The entries whose key or name CONTAINS [asked], case-insensitively.
 *
 * Containment and not equality: the operator who typed `sh` wanted `core.sh`,
 * and an index that only answered exact names would leave the near ones
 * unnamed. The list is bounded, because a name nobody typed can be contained
 * by many entries and a refusal that lists everything is a refusal that says
 * nothing.
 */
private fun nearMatches(asked: String, entries: List<StepCatalogueEntry>): List<String> {
    val needle = asked.lowercase()
    return entries
        .filter { it.key.lowercase().contains(needle) || it.name.lowercase().contains(needle) }
        .map { it.key }
        .sorted()
        .take(NEAR_MATCH_LIMIT)
}

/** How many near matches a refusal names. */
private const val NEAR_MATCH_LIMIT = 10

/**
 * What the catalogue of this process holds.
 */
internal sealed interface CatalogueRead {

    /** The entries of the composition. */
    data class Read(val entries: List<StepCatalogueEntry>) : CatalogueRead

    /** The composition could not be built, and the exit it answers with. */
    data class Refused(val exit: CliExit) : CatalogueRead
}

/**
 * The catalogue of the composition this process would run.
 *
 * The composition is composed through the SAME admission a run goes through,
 * so a plugin the admission refuses is refused here with the same words, and
 * the catalogue never describes a composition a run would refuse.
 *
 * The refusals it prints name the [verb] that asked for the catalogue: a verb
 * that read the catalogue and refused a plugin of it said so with the name of
 * another verb, and the operator read a refusal of a request they had not
 * made.
 */
internal fun readCatalogue(
    accepted: CliAdmission.Admitted,
    err: PrintStream,
    verb: String = "steps",
): CatalogueRead {
    val pluginJars = try {
        operatorPluginJars(accepted.repeated["--plugin-jar"].orEmpty(), PROCESS_WORKING_DIRECTORY)
    } catch (invalid: InvalidPathException) {
        err.println(
            CliError.InvalidOptionValue("--plugin-jar", "not a path: ${invalid.message}")
                .refusalLines(verb)
                .joinToString("\n"),
        )
        return CatalogueRead.Refused(CliExit.INVALID_ARGUMENT)
    }
    val trustFile = try {
        accepted.values["--plugin-trust"]?.let {
            PROCESS_WORKING_DIRECTORY.resolve(it).toAbsolutePath().normalize()
        }
    } catch (invalid: InvalidPathException) {
        err.println(
            CliError.InvalidOptionValue("--plugin-trust", "not a path: ${invalid.message}")
                .refusalLines(verb)
                .joinToString("\n"),
        )
        return CatalogueRead.Refused(CliExit.INVALID_ARGUMENT)
    }
    val admission = try {
        admitThenCompose(
            pluginJars = pluginJars.map { it.toString() },
            trust = pluginTrustSource(trustFile?.toString()),
            telemetry = NoopPipelineTelemetry,
            requireTrust = "--require-plugin-trust" in accepted.flags,
            err = err,
        )
    } catch (refused: CommandRefused) {
        // The bundled classpath is in conflict or was rejected. The refusal
        // was already rendered by the function that decided it; what is left is
        // the exit it owns.
        return CatalogueRead.Refused(refused.exit)
    }
    return when (admission) {
        is PluginAdmission.Admitted -> CatalogueRead.Read(entriesOf(admission.composition))
        is PluginAdmission.Refused -> CatalogueRead.Refused(admission.exit)
    }
}

/**
 * The entries of [composition], read from the registries it holds.
 */
private fun entriesOf(composition: PreResolvedComposition): List<StepCatalogueEntry> =
    StepCatalogue.entries(composition.steps, composition.directives)
