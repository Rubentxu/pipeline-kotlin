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
 *    believed in was dropped without a word.
 *
 * A verb that owns no options has to SAY that a request is not one of its
 * requests. Silence is the failure mode here, not the message.
 *
 * ## Why one admission and not another parser
 *
 * The CLI already decides the arity of its options BY HAND in eight places:
 * the shared parser carries one per family (`observation`, `inspect`, `events`,
 * `console`), and the `events` inspection flags, `events verify`, the `runs`
 * family and `saga inspect` carry their own. Each of those writes its option
 * tokens in its own body, so there are eight places to drift. This table is a
 * NINTH piece of the same kind: the option surface of the verbs that do not
 * use the shared parser is written ONCE, here, and every verb reads it.
 *
 * ## Why the arity is declared here and not read from the shared parser
 *
 * [knownOptionArity] answers for the tokens the shared parser knows
 * (`--control-root`, `--format`, `--db`, ...), and the shared parser is the
 * authority for those: the same token cannot be valued in one place and a flag
 * in another. `--json` is not a token the shared parser knows — it belongs to
 * `version` alone, and admitting it to the shared parser would make `run
 * --json` a recognised option of a verb that does not own it. So this table
 * declares the arity of the tokens the shared parser does not know, and the
 * unit test checks the two tables against each other for every token they
 * share.
 */
internal object CliOptionAdmission {

    /**
     * The admission of [args] for a verb that owns [owned].
     *
     * Pure: it reads the arguments and answers with a typed outcome. It opens
     * nothing, reads nothing and writes nothing, which is what makes it
     * checkable without a process.
     */
    fun admit(args: Array<String>, owned: List<CliOption>): CliAdmission {
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
            if (knownOptionArity(value) != null) {
                return CliAdmission.Rejected(CliError.OptionValueIsAnOption(token, value))
            }
            values[token] = value
            index += 2
        }
        return CliAdmission.Admitted(flags, values, positionals)
    }
}

/**
 * The options `doctor`, `capabilities` and `sdk-info` own: none.
 *
 * Each of these verbs answers one question that takes no option: how the
 * installation is, what this build admits, and what a plugin builds against.
 * A flag handed to them has no meaning, and a flag with no meaning is a flag
 * the operator believed in.
 */
internal val NO_OPTIONS: List<CliOption> = emptyList()

/**
 * The options `version` owns.
 *
 * `--json` is the same fact as the text line, in a shape a harness can read.
 */
internal val VERSION_OPTIONS: List<CliOption> = listOf(CliOption("--json"))

/**
 * The options `operations` owns.
 *
 * `--control-root` names the directory the Output Plane lives under, and
 * `--format` names the rendering. Both carry a value, and both are tokens the
 * shared parser also knows, so the shared parser's arity for them is the one
 * this table has to agree with.
 */
internal val OPERATIONS_OPTIONS: List<CliOption> = listOf(
    CliOption("--control-root", takesValue = true),
    CliOption("--format", takesValue = true),
)
