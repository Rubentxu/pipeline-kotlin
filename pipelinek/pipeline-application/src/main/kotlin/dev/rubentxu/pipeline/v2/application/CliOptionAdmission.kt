package dev.rubentxu.pipeline.v2.application

/**
 * One option a verb owns.
 *
 * @property token the option as the operator types it.
 * @property takesValue whether the option carries a value.
 */
internal data class CliOption(
    val token: String,
    val takesValue: Boolean = false,
)

/**
 * What a verb's own options said about a request.
 *
 * ## How a caller reads this
 *
 * The outcome has two shapes and a caller has to act on both of them, so it
 * reads them with an exhaustive `when`. A verb that needs the admitted data
 * takes it from the `when`:
 *
 * ```
 * val accepted = when (admission) {
 *     is CliAdmission.Rejected -> { refuse(admission.error); return 2 }
 *     is CliAdmission.Admitted -> admission
 * }
 * ```
 *
 * and a verb that needs nothing from an admitted request reads the `when` as
 * a statement. Neither of them uses `if (admission is CliAdmission.Rejected)
 * return`: the negative branch of a type check does not narrow the value on
 * this compiler, so after such an `if` the admission is still the sealed type
 * and the data it carries is not in reach. The `when` names both shapes, so
 * the compiler also checks that both of them are handled.
 */
internal sealed interface CliAdmission {

    /**
     * The request was made of options the verb owns.
     *
     * @property flags the options that carry no value.
     * @property values the options that carry a value, with the value each was given.
     * @property positionals the arguments that are not options.
     */
    data class Admitted(
        val flags: Set<String>,
        val values: Map<String, String>,
        val positionals: List<String>,
    ) : CliAdmission

    /** The request carried an option the verb does not own, or one it cannot read. */
    data class Rejected(val error: CliError) : CliAdmission
}

/**
 * How many positional arguments a verb owns.
 *
 * ## Why the count and not a yes
 *
 * A verb that owns a positional argument has to say HOW MANY it owns, because
 * "it owns one" and "it owns some" are different promises to the operator.
 * Measured on the built distribution, `operations <runId> extra-arg`
 * published the operations of `<runId>` and said NOTHING about `extra-arg`:
 * the verb read the first positional and dropped the rest in silence. The
 * operator asked about two runs and read an answer about one, with no word
 * saying that the other had been ignored.
 *
 * The verbs that read through the shared parser already refuse a second
 * positional argument: `observe`, `inspect`, `runs cancel`, `saga inspect`
 * and `events` all answer 2 and name the token. Among the verbs that read
 * through THIS admission, `operations` was the only one that swallowed one.
 *
 * ## Why the admission has to know
 *
 * The admission used to collect the positional arguments and hand them to the
 * verb, and each verb decided on its own what to do with them. Four of the six
 * verbs that read through this admission own none of them, and each of those
 * four DROPPED the positional without a word: measured on the built
 * distribution, `doctor foo`, `version foo`, `capabilities foo` and
 * `sdk-info foo` answered 0 and printed exactly what the bare verb prints.
 *
 * A verb that owns no positional argument has to SAY that a request carrying
 * one is not one of its requests. The silence is the failure mode here, the
 * same as it is for an option the verb does not own: the operator believes they
 * asked something and reads an answer about something else.
 *
 * ## Why the default is [NONE]
 *
 * The default is the safe half: a verb that says nothing owns no positional
 * argument, and a request that carries one is refused. A verb that DOES own
 * one has to say so, out loud, in the shape of the request it accepts.
 *
 * ## The invariant of this admission
 *
 * No verb that reads through THIS ADMISSION owns two positional arguments, so
 * the whole vocabulary of positional ownership here is these two cases. A verb
 * that ever needs a second one grows this type, and the admission's `when`
 * over it — exhaustive by construction — makes the author decide what a
 * request carrying too many of them means.
 *
 * The CLI as a whole is not the same, and the difference is not academic:
 * `console` owns TWO — the run id and the operation id — and reads them
 * through the shared parser, which has its own rule and already refuses a
 * third (`console <runId> <opId> extra` answers "unexpected extra argument").
 * That verb does not read through here, and how many positional arguments it
 * owns is not this type's business.
 */
internal enum class PositionalArgument(val capacity: Int) {
    /** The verb owns no positional argument: a request that carries one is refused. */
    NONE(0),

    /** The verb owns exactly one positional argument: a request that carries another is refused. */
    ONE(1),
}

/**
 * The shape of the requests a verb accepts: the options it owns and the
 * positional argument it owns.
 *
 * ## Why the two travel together
 *
 * The shape of a request is ONE thing, and a verb that has to declare half of
 * it in one place and the other half in another will declare one of the two
 * halves by accident. The first shape of this cut declared the positional
 * argument at the CALL SITE of the verb and left the options in a table, and
 * the two unit rows that read the table through the admission broke the moment
 * the table was used by a caller that had not repeated the declaration: the
 * table said "options" and the caller had to remember something the table did
 * not say.
 *
 * The default is the safe half — [PositionalArgument.NONE] — so a request
 * shape that says nothing owns no positional argument and a request that
 * carries one is refused.
 *
 * @property options the options this verb owns.
 * @property positional how many positional arguments this verb owns.
 */
internal data class CliRequest(
    val options: List<CliOption> = emptyList(),
    val positional: PositionalArgument = PositionalArgument.NONE,
)

/**
 * The admission of the verbs that own a small, explicit set of options.
 *
 * ## What this exists for
 *
 * Measured on the built distribution, five verbs answered a request that
 * carried an option they do not own without refusing it:
 *
 *  - `operations <runId> --control-root <dir> --bogus-flag` published an empty
 *    listing and exited 0. The operator asked a question with a typo in it and
 *    read "this run has no operations" — a false answer about a run they never
 *    asked about;
 *  - `operations --control-root --format json` exited 0 with an empty stdout:
 *    `--format` was taken as the VALUE of `--control-root`, so the flag the
 *    operator asked for was consumed instead of refused;
 *  - `doctor --json` answered 0 and printed the report the verb always prints,
 *    `version --db <path>` answered 0 and printed the version, and so did
 *    `capabilities --view full` and `sdk-info --db <path>`: a flag the operator
 *    believed in was dropped without a word;
 *
 *  - `events verify --db <db> --run <r> --contract <c> --bogus` printed
 *    EXACTLY what the same invocation without `--bogus` printed. That verb was
 *    the last one reading its options with a hand-written loop with no branch
 *    for what it did not recognise, and it is the CLI's acceptance gate: a
 *    dropped option there is a verdict about a request nobody made.
 *
 * A verb that owns no options has to SAY that a request is not one of its
 * requests. Silence is the failure mode here, not the message.
 *
 * ## Why one admission and not another parser
 *
 * The CLI decides the arity of its options in eight places. Seven write it BY
 * HAND: the shared parser carries one per family (`observation`, `inspect`,
 * `events`, `console`), and the `events` inspection flags, the `runs` family
 * and `saga inspect` carry their own. Each of those writes its option tokens
 * in its own body, so there are seven places to drift. The eighth is this
 * table, where the six verbs that do not use the shared parser read the shape
 * of the requests they accept: it is written ONCE, here, and every verb reads
 * it.
 *
 * ## Why the arity is declared here and not read from the shared parser
 *
 * [knownOptionArity] answers for the tokens the shared parser knows
 * (`--control-root`, `--format`, `--db`, ...), and the shared parser is the
 * authority for those: the same token cannot be valued in one place and a flag
 * in another. `--json` is not a token the shared parser knows — it belongs to
 * `version` alone, and admitting it to the shared parser would make `run
 * --json` a recognised option of a verb that does not own it. So this table
 * declares the arity of the tokens ITS verbs own, and the unit test checks
 * this table against the shared parser for every token they share: a verb that
 * owns `--db` and the shared parser that also knows `--db` cannot disagree
 * about what `--db` is.
 */
internal object CliOptionAdmission {

    /**
     * The admission of [args] for a verb that accepts [request].
     *
     * Pure: it reads the arguments and answers with a typed outcome. It opens
     * nothing, reads nothing and writes nothing, which is what makes it
     * checkable without a process.
     *
     * The shape of the request is [request], and it says both things about it:
     * the options the verb owns and the positional argument the verb owns. The
     * verb does not declare either half at its call site, so it cannot declare
     * one of the two by accident.
     */
    fun admit(
        args: Array<String>,
        request: CliRequest,
    ): CliAdmission {
        val owned = request.options
        val flags = linkedSetOf<String>()
        val values = linkedMapOf<String, String>()
        val positionals = mutableListOf<String>()
        var index = 0
        while (index < args.size) {
            val token = args[index]
            if (!token.startsWith("--")) {
                positionals += token
                index += 1
                continue
            }
            val option = owned.firstOrNull { it.token == token }
                ?: return CliAdmission.Rejected(CliError.UnknownOption(token))
            if (!option.takesValue) {
                flags += token
                index += 1
                continue
            }
            val value = args.getOrNull(index + 1)
                ?: return CliAdmission.Rejected(CliError.MissingOptionValue(token))
            // A valued option that was handed another option of THIS BUILD is
            // refused rather than consumed: storing it as the value would drop
            // a flag the operator asked for and still exit 0. The check is
            // scoped to the tokens this build knows, exactly as the shared
            // parser scopes it — a token that names nothing is far more likely
            // to be a path than a mistyped flag.
            //
            // The verb's OWN tokens join that set, and they are the stronger
            // half of it: a token this request's verb owns is even less likely
            // to be a path than one the shared parser happens to know.
            // `events verify --db --run r1` used to take "--run" for the value
            // of "--db", and the operator was told about the "r1" left over
            // instead of about the option that ate the other one.
            if (knownOptionArity(value) != null || owned.any { it.token == value }) {
                return CliAdmission.Rejected(CliError.OptionValueIsAnOption(token, value))
            }
            values[token] = value
            index += 2
        }
        // A positional argument is part of the request too, and a verb has to
        // say, in the shape of the request it accepts, HOW MANY of them it
        // owns. A verb that owns none refuses any; a verb that owns exactly
        // one refuses the SECOND, naming it.
        //
        // The four verbs that own none used to drop it without a word:
        // `doctor foo` answered 0 and printed the health report, exactly as
        // `doctor` alone does, and the operator read an answer about a
        // request nobody made. `operations` owns the run id and read the
        // FIRST positional it was given and dropped the rest in silence:
        // `operations <runId> extra-arg` published the operations of <runId>
        // and said nothing at all about `extra-arg`. The operator asked about
        // two runs and read an answer about one.
        //
        // The refusal names the token because the token is what the operator
        // has to remove.
        val extra = positionals.getOrNull(request.positional.capacity)
        if (extra != null) {
            return CliAdmission.Rejected(
                when (request.positional) {
                    PositionalArgument.NONE -> CliError.UnexpectedPositional(extra)
                    PositionalArgument.ONE -> CliError.TooManyPositionals(extra)
                },
            )
        }
        return CliAdmission.Admitted(flags, values, positionals)
    }
}

/**
 * The requests `doctor`, `capabilities` and `sdk-info` accept: no options and
 * no positional argument.
 *
 * Each of these verbs answers one question that takes no argument: how the
 * installation is, what this build admits, and what a plugin builds against.
 * A flag handed to them has no meaning, and a flag with no meaning is a flag
 * the operator believed in.
 *
 * They own no positional argument either, and the shape says so here: a
 * request that carries one is refused by the admission.
 */
internal val NO_ARGUMENTS_REQUEST: CliRequest = CliRequest()

/**
 * The requests `version` accepts.
 *
 * `--json` is the same fact as the text line, in a shape a harness can read.
 */
internal val VERSION_REQUEST: CliRequest = CliRequest(
    options = listOf(CliOption("--json")),
)

/**
 * The requests `operations` accepts.
 *
 * `--control-root` names the directory the Output Plane lives under, and
 * `--format` names the rendering. Both carry a value, and both are tokens the
 * shared parser also knows, so the shared parser's arity for them is the one
 * this table has to agree with.
 *
 * This verb also owns the run id, which is a POSITIONAL argument: it is the
 * one verb of the six that reads one, and the shape says so here, with
 * [PositionalArgument.ONE]. It owns EXACTLY one, and the shape says that too:
 * `operations <runId> extra-arg` used to publish the operations of `<runId>`
 * and to say nothing about `extra-arg`, and the operator who asked about two
 * runs read an answer about one.
 */
internal val OPERATIONS_REQUEST: CliRequest = CliRequest(
    options = listOf(
        CliOption("--control-root", takesValue = true),
        CliOption("--format", takesValue = true),
    ),
    positional = PositionalArgument.ONE,
)

/**
 * The requests `events verify` accepts.
 *
 * All five options carry a value. `--db` is a token the shared parser also
 * knows, so the shared parser's arity for it is the one this table has to
 * agree with; `--run`, `--contract`, `--from-sequence` and `--scope` belong to
 * this verb alone.
 *
 * The verb used to read these with a hand-written loop that had no branch for
 * anything it did not recognise, so an option it did not own was dropped
 * without a word and the verb answered about a request nobody had made.
 */
internal val EVENTS_VERIFY_REQUEST: CliRequest = CliRequest(
    options = listOf(
        CliOption("--db", takesValue = true),
        CliOption("--run", takesValue = true),
        CliOption("--contract", takesValue = true),
        CliOption("--from-sequence", takesValue = true),
        CliOption("--scope", takesValue = true),
    ),
)
