package dev.rubentxu.pipeline.v2.domain

/**
 * The rules a CLIENT's name/value pairs obey, in one place.
 *
 * ## What this owns
 *
 * Two channels carry what a client delivers to a run: the CONFIGURATION —the
 * environment the steps of the run will see— and the PARAMETERS —the inputs the
 * pipeline declares it accepts. Both are sets of name/value pairs written by
 * somebody who is not the author of the pipeline, and both are checked against
 * the same two rules: the shape of the name, and the namespaces the product
 * owns.
 *
 * ## Why they live HERE and not in the checker that applies them
 *
 * Because there are two checkers and they must not disagree: the configuration
 * is checked by the runtime, and the parameters by the validator of the
 * scripting API. A rule that had two homes would have two answers, and the
 * second one would be the one nobody read. A rule that is written twice is
 * written twice: it is copied, and the copy is the one that survives.
 *
 * The domain is the module both checkers already see: the runtime and the
 * scripting API depend on it, and it is where the DECLARATION a parameter
 * obeys already lives.
 *
 * ## What this is NOT
 *
 * It is not a place where the values are kept: this is a set of rules, and the
 * values that break them are refused with a name.
 */
object ClientInputRules {

    /**
     * The shape of the name of a client-supplied entry.
     *
     * The alphabet is the one of a shell variable, and it is CLOSED on purpose:
     * it has no `+`, so the `PATH+<NAME>` form —which the environment machinery
     * consumes and turns into a PATH prefix— is not expressible through this
     * channel.
     *
     * It is READ by the description of a declaration, so that the description
     * and the checkers cannot disagree about what a name may look like.
     */
    val NAME_PATTERN: Regex = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /**
     * The prefixes the product owns.
     *
     * They are PREFIXES and not a list of names, so the list grows with the
     * product without anybody having to remember to append a name. The three
     * namespaces are the runtime's, the service's and the launcher wrapper's.
     *
     * A name that begins with one of them is not one a client may choose: the
     * product already gave that name to something of its own.
     */
    val RESERVED_PREFIXES: List<String> = listOf(
        "PIPELINE_",
        "PIPELINEK_",
        "DURABLE_SH_",
    )
}
