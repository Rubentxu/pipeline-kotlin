package dev.rubentxu.pipeline.v2.credentials.local

/**
 * The sentences the credentials command answers a request it does not carry
 * out with.
 *
 * ## What this object is and what it is not
 *
 * The DECISION of what a verb owns is not here: it is in [CredentialsVerb] and
 * [CredentialsOption], and this object only renders what those two say. That
 * is why a usage printed here cannot promise an option that does not exist,
 * and why a refusal here cannot complain about an option the verb actually
 * owns: both are derived from the same facts the parser reads.
 *
 * ## The complaint follows the SHAPE of the token
 *
 * An option-shaped token is an option, whatever the verb owns, and an option
 * the verb does not own is UNKNOWN — not an option whose value is missing, and
 * not an argument. A token that is not option-shaped is an argument, and an
 * argument the verb does not own is unexpected. The complaints are the ones
 * the rest of the CLI uses: "unknown option", "unexpected argument" and
 * "unknown subcommand".
 *
 * ## Why the kinds are read from the CLI
 *
 * The kinds a credential can have are the CLI's own business, and the list of
 * them is a compile-time constant of the enum that parses them. This object
 * reads it from there instead of keeping a second copy of the list, because a
 * second copy is a second copy that can drift, and a drifted copy promises a
 * kind that does not exist.
 */
internal object CredentialsRefusal {

    /**
     * The refusal of a request that carries a token the verb does not own.
     *
     * The token is named because the operator has to be able to read WHICH
     * token to remove, and the form is stated because the operator has to be
     * able to read WHAT the verb takes.
     */
    fun of(verb: CredentialsVerb, token: String): String {
        val optionShaped = token.startsWith("--")
        val complaint = if (optionShaped) "unknown option" else "unexpected argument"
        return "Error: $complaint: $token. 'credentials ${verb.token}' ${formOf(verb, optionShaped)}."
    }

    /**
     * The refusal of a request that does not name a verb of this command.
     *
     * A request that names NO verb and a request that names the WRONG one are
     * not the same mistake, and the two say so. The usage follows, because
     * there is no verb to point at and the operator has to be told what to
     * write instead.
     */
    fun ofUnknownVerb(token: String?): String {
        val complaint = if (token == null) "missing subcommand" else "unknown subcommand: $token"
        val verbs = CredentialsVerb.entries.joinToString(", ") { "'${it.token}'" }
        return "Error: $complaint. 'credentials' takes one of: $verbs.\n${usageBlock()}"
    }

    /**
     * The refusal of a request that leaves out the argument the verb owns.
     */
    fun ofMissingArgument(verb: CredentialsVerb): String =
        "Error: missing required argument: $ID_ARGUMENT\nUsage: ${usageOf(verb)}"

    /**
     * The refusal of a request that leaves out the value of an option the verb
     * owns.
     *
     * This is a DIFFERENT mistake from an unknown option, and it is the one
     * the operator can repair by writing the value.
     */
    fun ofMissingOptionValue(verb: CredentialsVerb, option: CredentialsOption): String =
        "Error: option ${option.flag} requires a value\nUsage: ${usageOf(verb)}"

    /**
     * What the verb takes, in the words the refusal uses.
     *
     * An option the verb does not own is an option it does not take AT ALL,
     * so the form says so instead of listing an option the operator would
     * then try to write.
     */
    private fun formOf(verb: CredentialsVerb, optionShaped: Boolean): String {
        val argument = if (verb.takesId) "takes one argument: $ID_ARGUMENT" else "takes no argument"
        if (!optionShaped) return argument
        val options = verb.ownedOptions
        val optionsForm = if (options.isEmpty()) {
            "no option"
        } else {
            "the options ${options.joinToString(", ") { it.form }}"
        }
        return "$argument, and $optionsForm"
    }

    /** The usage of one verb, derived from what the verb owns. */
    private fun usageOf(verb: CredentialsVerb): String {
        val options = verb.ownedOptions.joinToString(" ") { "[${it.form}]" }
        val argument = if (verb.takesId) " <id>" else ""
        return "pipeline credentials ${verb.token}" + (if (options.isEmpty()) "" else " $options") + argument
    }

    /**
     * The usage of the command, derived from what the verbs own.
     *
     * Every fact in it comes from the contract: the verbs from
     * [CredentialsVerb], the options each of them takes from
     * [CredentialsVerb.ownedOptions]. A second copy of them in a string is a
     * second copy that can drift, and a drifted usage promises an option that
     * does not exist.
     */
    private fun usageBlock(): String {
        val lines = mutableListOf<String>()
        val verbs = CredentialsVerb.entries.joinToString("|") { it.token }
        lines += "Usage: pipeline credentials {$verbs} [args]"
        for (verb in CredentialsVerb.entries) {
            lines += "${verb.token.replaceFirstChar { it.uppercase() }}:".padEnd(8) + usageOf(verb)
        }
        lines += "Options:"
        for (option in CredentialsOption.entries) {
            val owners = CredentialsVerb.entries.filter { it.owns(option) }.joinToString(", ") { it.token }
            lines += "        ${option.form.padEnd(16)}${optionHelp(option)} ($owners)"
        }
        return lines.joinToString("\n")
    }

    /** What an option means, in the operator's words. */
    private fun optionHelp(option: CredentialsOption): String = when (option) {
        CredentialsOption.KIND ->
            "the kind of credential being written: ${MainCredentialsCli.CredentialKind.ALL_KINDS}"

        CredentialsOption.FROM_STDIN -> "reads the value from standard input"
    }

    /** The name of the positional argument the verbs that take one take. */
    private const val ID_ARGUMENT = "credential id"
}
