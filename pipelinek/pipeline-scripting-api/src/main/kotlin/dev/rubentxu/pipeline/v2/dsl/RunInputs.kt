package dev.rubentxu.pipeline.v2.dsl

import dev.rubentxu.pipeline.v2.domain.ClientInputRules
import dev.rubentxu.pipeline.v2.domain.ParameterSpec

/**
 * The parameters a run was started with, checked against the declaration of the pipeline.
 *
 * ## What this is
 *
 * A set of NAME/VALUE pairs that the pipeline DECLARED it accepts, together with the
 * declaration they were checked against. It is a snapshot: it is read once, when the run
 * starts, and it does not change while the run lives.
 *
 * ## What this is NOT
 *
 * It is not the environment of the run: a parameter is an input of the run, and the
 * environment is a different channel with a different reach (see the configuration, which IS
 * the environment). It is not a secret, and nothing here can tell a secret from a plain text:
 * a secret IS a text, and there is no oracle.
 *
 * ## Why the rules live HERE and not in the caller
 *
 * Because there are three callers and they must not disagree: the command line, which
 * validates the values the operator typed against the declaration it compiled; the service,
 * which validates the values a client delivered against the contract the client knows; and
 * the child, which validates the same values against the declaration of the SCRIPT, which is
 * the truth. One type, one set of rules, one answer.
 *
 * ## Why the declaration is checked too
 *
 * Because the declaration is DATA when it travels: a contract that crossed a socket is
 * whatever the client sent. A declaration the product cannot interpret —a type it does not
 * know, a name declared twice, a closed set with nothing in it— cannot be checked against,
 * so the request that carries it is refused instead of being read as if it were sound.
 */
class RunInputs private constructor(
    /**
     * The declaration the values were checked against.
     *
     * The declaration is kept whole, and not only the values: a consumer that asks what the
     * run accepted has to be able to see what it was accepted AGAINST.
     */
    val declaration: List<ParameterSpec>,
    /**
     * The accepted values, by name.
     *
     * The map holds the EFFECTIVE values: the ones that were delivered plus the defaults of
     * the parameters that were not. A parameter with a default is therefore always present,
     * and a parameter without one is present because the caller had to deliver it.
     */
    val values: Map<String, String>,
) {

    /** Whether these inputs carry nothing. */
    val isEmpty: Boolean get() = values.isEmpty()

    /** How many values these inputs carry. */
    val size: Int get() = values.size

    /**
     * The shape of these inputs, without their values.
     *
     * The values do not travel: they are the caller's own text, and a log line that carried
     * them would publish them to whoever reads the log.
     */
    override fun toString(): String = "RunInputs(${values.size} values)"

    override fun equals(other: Any?): Boolean =
        this === other || (
            other is RunInputs && other.declaration == declaration && other.values == values
            )

    override fun hashCode(): Int = 31 * declaration.hashCode() + values.hashCode()

    /**
     * The rules, the vocabulary of the types and the texts the product owns.
     *
     * They live in the companion because they are the SAME rules for the three
     * callers, and a rule that had two homes would have two answers: the
     * command line, the service and the child all ask this one.
     */
    companion object {

        /** The type of a parameter that accepts any text. */
        const val TYPE_STRING: String = "string"

        /** The type of a parameter that accepts `true` or `false`. */
        const val TYPE_BOOLEAN: String = "boolean"

        /** The type of a parameter that accepts one of a closed set of texts. */
        const val TYPE_CHOICE: String = "choice"

        /** The inputs that carry nothing: no declaration and no values. */
        val EMPTY: RunInputs = RunInputs(emptyList(), emptyMap())

        /**
         * The shape of the name of a parameter.
         *
         * The rule is the PRODUCT's, and it lives in the domain with the other channel that
         * obeys it —the configuration of a run— so that the two checkers cannot disagree
         * about what a name may look like. A rule that has two homes has two answers, and
         * the second one is the one nobody read.
         *
         * It is READ by the description of the declaration, so that the description and the
         * checker cannot disagree about what a name may look like.
         */
        val NAME_PATTERN: Regex = ClientInputRules.NAME_PATTERN

        /**
         * The prefixes the product owns.
         *
         * They are the PRODUCT's, and they live in the domain with the other channel that
         * obeys them, for the same reason the shape of a name does: a prefix list written
         * twice is a list that drifts.
         *
         * They are READ by the description of the declaration, for the same reason the shape
         * of a name is.
         */
        val RESERVED_PREFIXES: List<String> = ClientInputRules.RESERVED_PREFIXES

        /**
         * The inputs of [declaration] and [raw], or the refusal that there are none.
         *
         * The checks are pure and they have no effect: the inputs are either accepted whole
         * or refused whole, and nothing is written anywhere by the answer.
         *
         * ## The order of the checks is the contract
         *
         * The order is fixed so that a given set of inputs always produces the same reason,
         * and so that the reasons are ordered from the most general to the most specific:
         *
         *  1. whether the DECLARATION is one this product can check against;
         *  2. whether the name of every delivered value has the shape of a name and does not
         *     belong to the product;
         *  3. whether every delivered value names a parameter the declaration declares;
         *  4. whether every parameter the declaration requires was delivered;
         *  5. whether every effective value fits the type of its parameter.
         */
        fun of(declaration: List<ParameterSpec>, raw: Map<String, String>): RunInputsOutcome {
            declarationRefusal(declaration)?.let { return RunInputsOutcome.Refused(it) }

            for (name in raw.keys) {
                if (!NAME_PATTERN.matches(name)) {
                    return RunInputsOutcome.Refused(RunInputsRefusal.TheNameIsNotLegal(name))
                }
                if (isReserved(name)) {
                    return RunInputsOutcome.Refused(RunInputsRefusal.TheNameIsReserved(name))
                }
            }

            val declared = LinkedHashMap<String, ParameterSpec>()
            for (spec in declaration) {
                declared[spec.name] = spec
            }

            for (name in raw.keys) {
                if (!declared.containsKey(name)) {
                    return RunInputsOutcome.Refused(RunInputsRefusal.TheParameterIsUnknown(name))
                }
            }

            val effective = LinkedHashMap<String, String>()
            for ((name, spec) in declared) {
                val delivered = raw[name]
                if (delivered != null) {
                    effective[name] = delivered
                    continue
                }
                val default = spec.defaultValue
                if (default == null) {
                    return RunInputsOutcome.Refused(RunInputsRefusal.TheParameterIsMissing(name))
                }
                effective[name] = default
            }

            for ((name, value) in effective) {
                val spec = declared.getValue(name)
                if (!valueFits(spec, value)) {
                    return RunInputsOutcome.Refused(
                        RunInputsRefusal.TheValueHasTheWrongType(name, spec.type),
                    )
                }
            }

            return RunInputsOutcome.Accepted(RunInputs(declaration.toList(), effective))
        }

        /**
         * The refusal of a declaration this product cannot check against, or nothing.
         *
         * A declaration is refused when it says a type the product does not know, when it
         * declares a name twice, when it declares a name that is not the product's to give,
         * when a closed set carries nothing, when a type that is not closed carries a set, or
         * when a default does not fit the type it is declared with.
         *
         * ## Why the default is checked HERE and not with the values
         *
         * Because a default that does not fit its own type is a defect of the AUTHOR and not
         * of the caller: the caller cannot fix it, and a refusal that named the caller would
         * send it to fix something it never wrote. The default is part of the declaration, and
         * the declaration is checked first.
         *
         * ## Why it can be asked on its own
         *
         * Because a declaration can be judged before any value exists: the service judges the
         * declaration of an approved manifest before it describes it, and a description of a
         * declaration [of] would refuse is a form whose every submission is refused. Asking
         * [of] with no values would not answer that question, because a declaration with a
         * required parameter is refused for the missing value and not for itself.
         */
        fun declarationRefusal(declaration: List<ParameterSpec>): RunInputsRefusal? {
            val seen = HashSet<String>()
            for (spec in declaration) {
                if (!NAME_PATTERN.matches(spec.name)) {
                    return RunInputsRefusal.TheNameIsNotLegal(spec.name)
                }
                if (isReserved(spec.name)) {
                    return RunInputsRefusal.TheNameIsReserved(spec.name)
                }
                if (!seen.add(spec.name)) {
                    return RunInputsRefusal.TheDeclarationIsNotLegal(
                        "the parameter '${spec.name}' is declared more than once",
                    )
                }
                when (spec.type) {
                    TYPE_STRING, TYPE_BOOLEAN -> {
                        if (spec.choices.isNotEmpty()) {
                            return RunInputsRefusal.TheDeclarationIsNotLegal(
                                "the parameter '${spec.name}' is of the type '${spec.type}' and " +
                                    "may not carry a set of choices",
                            )
                        }
                    }

                    TYPE_CHOICE -> {
                        if (spec.choices.isEmpty()) {
                            return RunInputsRefusal.TheDeclarationIsNotLegal(
                                "the parameter '${spec.name}' is of the type '$TYPE_CHOICE' and " +
                                    "has to declare the texts it accepts",
                            )
                        }
                    }

                    else -> {
                        return RunInputsRefusal.TheDeclarationIsNotLegal(
                            "the parameter '${spec.name}' declares the type '${spec.type}', which " +
                                "this product does not know",
                        )
                    }
                }
                val default = spec.defaultValue
                if (default != null && !valueFits(spec, default)) {
                    return RunInputsRefusal.TheDeclarationIsNotLegal(
                        "the parameter '${spec.name}' declares the default '$default', which " +
                            "does not fit the type '${spec.type}'",
                    )
                }
            }
            return null
        }

        /**
         * Whether [value] fits the type of [spec].
         *
         * A text fits a text. A text fits a boolean when it is `true` or `false`, and the
         * spelling is EXACT: a value that said `True` would be a text the caller can read but
         * the type cannot hold, and the run would have to guess which one the caller meant.
         * A text fits a closed set when it is one of the texts of the set.
         */
        private fun valueFits(spec: ParameterSpec, value: String): Boolean =
            when (spec.type) {
                TYPE_STRING -> true
                TYPE_BOOLEAN -> value == "true" || value == "false"
                TYPE_CHOICE -> value in spec.choices
                else -> false
            }

        /**
         * Whether [name] begins with one of the product's own prefixes.
         *
         * A loop and not `any { }`: the rule is about EVERY prefix, and `any { }` is a
         * function the compiler inlines into the caller, so the branch it introduces is not a
         * line of this file.
         */
        private fun isReserved(name: String): Boolean {
            for (prefix in RESERVED_PREFIXES) {
                if (name.startsWith(prefix)) return true
            }
            return false
        }

        /**
         * The canonical text of a [declaration] and the [values] delivered against it, the one
         * that reaches a fingerprint.
         *
         * ## What the fingerprint is of
         *
         * Of the REQUEST: the declaration the client carries and the values it delivered. It
         * is NOT of the effective values, because those are derived: two requests that carry
         * the same declaration and the same delivered values are the same request, whatever
         * the defaults of the declaration are.
         *
         * ## Why the encoding is not a plain join
         *
         * Because a plain join is not injective, and a fingerprint that collides is a
         * fingerprint that says two different requests are the same request. `{"A":"1","B":"2"}`
         * and `{"A":"1\nB=2"}` joined as `name=value` are the same text, and so would be the
         * same fingerprint: the second request would be answered with the run of the first.
         *
         * ## What the encoding is
         *
         * The declared parameters sorted by name, and every field LENGTH-PREFIXED —the length
         * of the name, then the name, then the length of the type, then the type, then the
         * length of the set of choices, then the set, then the length of the default, then the
         * default, then the length of the value, then the value— joined with `|`. With the
         * lengths in front, the text says exactly where each field ends, so no value can
         * imitate a separator.
         *
         * ## Why the type, the set and the default travel with the value
         *
         * Because the value alone does not say what was asked for: the text `true` is a
         * boolean for one declaration and a text for another, and two requests that carry
         * the same text under different declarations are not the same request.
         *
         * The empty declaration and the empty values produce the EMPTY text, and a
         * fingerprint that has nothing to say about the parameters says nothing.
         */
        fun canonicalText(declaration: List<ParameterSpec>, values: Map<String, String>): String =
            declaration
                .sortedBy { it.name }
                .joinToString("|") { spec ->
                    val value = values[spec.name]
                    val choices = spec.choices.joinToString(",")
                    val default = spec.defaultValue.orEmpty()
                    spec.name.length.toString() + ":" + spec.name + "=" +
                        spec.type.length.toString() + ":" + spec.type + "=" +
                        choices.length.toString() + ":" + choices + "=" +
                        default.length.toString() + ":" + default + "=" +
                        (value?.length ?: -1).toString() + ":" + value.orEmpty()
                }
    }
}

/**
 * What the parameters of a request turned out to be.
 *
 * Two shapes, and a caller has to act on both: the parameters are accepted, or they are
 * refused with a name.
 */
sealed interface RunInputsOutcome {

    /** The parameters broke no rule. */
    data class Accepted(val inputs: RunInputs) : RunInputsOutcome

    /** The parameters broke a rule, and the refusal says which one. */
    data class Refused(val refusal: RunInputsRefusal) : RunInputsOutcome
}

/**
 * The rules a set of parameters may break.
 *
 * ## Why the reasons are WORDS and not exceptions
 *
 * Because the refusal travels: it is what the service tells the client, and a client that
 * receives an exception has to read a stack trace to learn what to fix. Each word says which
 * rule was broken, and the detail says which one in prose.
 *
 * ## Why each refusal NAMES the parameter
 *
 * Because a pipeline declares several parameters and the caller has to know which one to
 * fix. A reason that says "a parameter is wrong" without saying which leaves the caller
 * comparing the declaration with the values by hand, one pair at a time.
 */
sealed interface RunInputsRefusal {

    /** The word that names the rule that was broken. */
    val reason: String

    /** The sentence that says which rule it was, in prose. */
    val detail: String

    /**
     * The declaration cannot be checked against.
     *
     * The declaration travelled as data and the product cannot read it: a type it does not
     * know, a name declared twice, a closed set with nothing in it. The [why] says which of
     * those it is.
     */
    data class TheDeclarationIsNotLegal(val why: String) : RunInputsRefusal {
        override val reason: String = "the-declaration-is-not-legal"
        override val detail: String = why
    }

    /**
     * A name does not have the shape of a name.
     *
     * The rule is the product's, and it is the same one the configuration obeys: the alphabet
     * of a shell variable, closed, with no `+`.
     */
    data class TheNameIsNotLegal(val parameter: String) : RunInputsRefusal {
        override val reason: String = "the-parameter-name-is-not-legal"
        override val detail: String =
            "the name of a parameter must match [A-Za-z_][A-Za-z0-9_]*; '$parameter' does not"
    }

    /**
     * A name is not the product's to give.
     *
     * It begins with one of the prefixes the product owns, so it is a name the product has
     * already given to something of its own.
     *
     * ## Why this is a different word than the shape of the name
     *
     * Because the two rules are fixed by DIFFERENT owners: the shape is the rule of the
     * alphabet, and the prefixes are the rule of the product's own namespaces. A caller that
     * sent `9mal` has to fix the spelling, and a caller that sent `PIPELINE_TARGET` has to
     * fix the NAME, and a word that says both sends both to look in the same place.
     */
    data class TheNameIsReserved(val parameter: String) : RunInputsRefusal {
        override val reason: String = "the-parameter-name-is-reserved"
        override val detail: String =
            "the name of a parameter may not begin with PIPELINE_, PIPELINEK_ or DURABLE_SH_; " +
                "'$parameter' does"
    }

    /**
     * A delivered value names a parameter the declaration does not declare.
     */
    data class TheParameterIsUnknown(val parameter: String) : RunInputsRefusal {
        override val reason: String = "the-parameter-is-unknown"
        override val detail: String =
            "the parameter '$parameter' was delivered and the pipeline does not declare it"
    }

    /**
     * A parameter the declaration requires was not delivered.
     *
     * A parameter is required when it declares no default: the absence of the default IS the
     * obligation, and it is the only place that obligation is written.
     */
    data class TheParameterIsMissing(val parameter: String) : RunInputsRefusal {
        override val reason: String = "the-parameter-is-missing"
        override val detail: String =
            "the parameter '$parameter' declares no default, so it has to be delivered"
    }

    /**
     * A delivered value does not fit the type of its parameter.
     *
     * The [expected] is the type the declaration names, so the caller knows what it has to
     * deliver instead. A default that does not fit its own type is NOT this refusal: it is a
     * defect of the declaration, and the declaration is checked first.
     */
    data class TheValueHasTheWrongType(
        val parameter: String,
        val expected: String,
    ) : RunInputsRefusal {
        override val reason: String = "the-parameter-has-the-wrong-type"
        override val detail: String =
            "the value of the parameter '$parameter' does not fit the type '$expected'"
    }
}
