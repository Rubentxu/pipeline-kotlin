package dev.rubentxu.pipeline.v2.application

/**
 * A command form: the words the operator types, what the form does, and the
 * code that answers it.
 *
 * ## Why a form and not a word
 *
 * `events` and `events verify` are two commands, and `saga inspect` is one
 * command whose first word is also the name of a family of commands. A table
 * of single words cannot hold that: it would have to say "events" twice, or
 * admit that "saga" is a command when the CLI refuses the word on its own.
 * So the key of a row is the WHOLE form, and a row knows the words it
 * answers to.
 *
 * ## What a handler receives
 *
 * A handler receives the arguments AFTER its own words: the row for
 * `events verify` gets what follows "events verify", and the row for
 * `observe` gets what follows "observe". A handler that had to strip its own
 * words could strip them wrongly, and a form of two words is exactly where
 * that mistake hides.
 *
 * ## What a row without a handler means
 *
 * A null handler means another authority dispatches that form: the parser
 * admits `validate` and `run`, and the runs family has a CLI of its own. The
 * row is still in the inventory, because the operator has to be able to
 * FIND a form nobody told them about.
 */
internal class CliVerb(
    /** The whole form, as the operator types it. */
    val label: String,
    /** The line the help prints next to the form. */
    val summary: String,
    /** The code that answers the form, or null when another authority owns it. */
    val handler: ((Array<String>) -> Int)? = null,
    /**
     * The usage the family prints when the family word is given alone.
     *
     * Null means the family owns its own door: the runs family answers with
     * its own words and its own usage, and this table does not second-guess
     * it.
     */
    val familyUsage: String? = null,
) {
    /** The words this form is written with. */
    val words: List<String> = label.split(' ')

    /**
     * The name of the family this form belongs to, when it has one.
     *
     * `saga inspect` belongs to the family `saga`. A family is not a command:
     * the family word on its own is a refusal, and the row that owns the
     * family says what that refusal looks like.
     */
    val family: String?
        get() = words.firstOrNull().takeIf { words.size > 1 }

    /**
     * Whether [args] asks for this form.
     *
     * A one-word form answers to its word whatever follows it, exactly as the
     * comparison it replaces did. A form of two words answers to both of
     * them, and to nothing else.
     */
    fun matches(args: Array<String>): Boolean =
        args.firstOrNull() == words[0] && (words.size == 1 || args.getOrNull(1) == words[1])
}

/**
 * The CLI's inventory: every form the CLI answers, and the line that
 * describes each one.
 *
 * ## What this list is for
 *
 * Measured on the built distribution, the help and the dispatch disagreed
 * four times, and the disagreement always went the same way: the help was
 * missing something the CLI did. `runs recover` was implemented and
 * reachable, and the help did not name it, so an operator had no way to find
 * it. `events verify` was the same. The help named `saga`, and the CLI
 * refused that word and required `saga inspect`. And the hint printed on a
 * rejected command enumerated `runs cancel` and `saga` as if they were
 * command words, which they are not.
 *
 * Every one of those is the same defect: a hand-kept list of what the CLI
 * does, free to drift from what the CLI does. The list was in prose, so
 * nothing failed when it drifted; it was wrong on the day it was written,
 * and no test and no example looked at the help at all.
 *
 * ## How the rows are obtained
 *
 * Every row is DERIVED from the authority that dispatches the form:
 *
 *  - the commands the parser admits come from [CLI_PARSER_COMMANDS], which
 *    the parser itself reads;
 *  - the read-only forms are the rows below, each carrying the handler that
 *    answers it, so the table IS the dispatch;
 *  - the runs family comes from [MainRunsCli.RunsVerb], the place that
 *    already owns that family's spellings.
 *
 * A form that is implemented and not derived here cannot reach the help, and
 * a form that is derived here is dispatched by construction.
 *
 * ## The order
 *
 * The forms are grouped by who dispatches them: the commands the parser
 * admits, the forms this table answers, and the family that has a CLI of its
 * own. The order decides nothing — a form is recognised by its words, and
 * the longest matching form wins — so the order is here to be read, not to be
 * relied upon.
 */
internal val CLI_VERBS: List<CliVerb> = buildList {
    addAll(CLI_PARSER_COMMANDS.map { (spelling, command) -> CliVerb(spelling, command.summary) })

    add(CliVerb("observe", "read a finished run's events, console and output (--view, --format)", ::runObserveVerb))
    add(CliVerb("console", "read one step's output plane as frames or lines", ::runConsoleVerb))
    add(CliVerb("operations", "list a run's operations with their complete identity", ::runOperationsVerb))
    add(CliVerb("events", "read one run's domain events as text or json", ::runEventsVerb))
    add(CliVerb("events verify", "check a run's events against a contract (--db, --run, --contract)", ::runEventsVerifyVerb))
    add(CliVerb("inspect", "read a run id's digests, steps and provenance", ::runInspectVerb))
    add(
        CliVerb(
            "saga inspect",
            "read what a run's saga still owes (--db, --format)",
            ::runSagaInspectVerb,
            MainSagaInspectCli.USAGE,
        ),
    )
    add(CliVerb("credentials", "list registered credential material (redacted)", ::runCredentialsVerb))
    add(CliVerb("doctor", "report the installation's health", ::runDoctorVerb))
    add(CliVerb("version", "print the product version (--json)", ::runVersionVerb))
    add(CliVerb("capabilities", "print the capability keys this build admits", ::runCapabilitiesVerb))
    add(CliVerb("sdk-info", "print the SDK coordinates a plugin builds against", ::runSdkInfoVerb))

    addAll(
        MainRunsCli.RunsVerb.entries.map {
            CliVerb("${MainRunsCli.FAMILY} ${it.spelling}", it.summary)
        },
    )
}

/**
 * The form [args] asks for, or null when it asks for none.
 *
 * When two forms could answer the same words — `events` and `events verify`
 * both answer "events ..." — the longer form wins, because the shorter one
 * is the family of the longer one.
 */
internal fun cliVerbFor(args: Array<String>): CliVerb? =
    CLI_VERBS.filter { it.matches(args) }.maxByOrNull { it.words.size }

/**
 * The refusal for a family word given on its own, or null when there is none.
 *
 * `pipelinek saga` is not a command: the CLI refuses it and names the form
 * that is. The refusal is built from the row that owns the family, so the
 * family word in the message is the family's own word and the usage printed
 * under it is the usage that family prints.
 */
internal fun cliFamilyDoor(args: Array<String>): String? {
    val family = args.firstOrNull() ?: return null
    val owner = CLI_VERBS.firstOrNull { it.family == family } ?: return null
    val usage = owner.familyUsage ?: return null
    return "pipeline: unknown $family subcommand.\n$usage"
}
