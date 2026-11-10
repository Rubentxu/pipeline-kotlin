package dev.rubentxu.pipeline.v2.runtime

import dev.rubentxu.pipeline.v2.domain.ClientInputRules
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.EnvModel
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.SandboxEnvDenyList

/**
 * The configuration a client delivered with a request to start a run.
 *
 * ## What this is
 *
 * A set of NAME/VALUE pairs that the steps of ONE execution will see in
 * their environment. It is a snapshot: it is read once, when the run starts,
 * and it does not change while the run lives.
 *
 * ## What this is NOT
 *
 * It is not a secret, and nothing here can tell a secret from a plain text:
 * a secret IS a text, and there is no oracle. It is not a credential, it is
 * not a parameter with a declared type, and it is not a channel of internal
 * configuration: the argv of a step, the deadline, the sandbox profile, the
 * network and the credentials are the operator's, and this type cannot
 * reach any of them.
 *
 * ## Why the rules live HERE and not in the caller
 *
 * Because there are three callers and they must not disagree: the service,
 * which refuses a configuration that breaks the rules; the command line,
 * which re-validates the snapshot it reads from the disk; and the runtime,
 * which projects the accepted values into the base environment of the run.
 * One type, one set of rules, one answer.
 *
 * ## Why the values are plain and not secret-bearing
 *
 * The channel is PUBLIC by construction: the values end up in the
 * environment of the steps, which every process of the step inherits. A
 * value that arrived here as a secret would be a secret that the product
 * published, and there is no way to make that honest after the fact. The
 * handles this type produces are therefore plain, and the projection says so
 * out loud rather than pretending.
 */
class RunConfiguration private constructor(
    /**
     * The accepted values, by name.
     *
     * The map is unmodifiable and its iteration order is the order the names
     * were accepted in; the CANONICAL order (sorted by name) is computed by
     * [canonicalText] and is the only order that ever reaches a fingerprint.
     */
    val values: Map<String, String>,
) {

    /** Whether this configuration carries nothing. */
    val isEmpty: Boolean get() = values.isEmpty()

    /** How many values this configuration carries. */
    val size: Int get() = values.size

    /**
     * The values as the environment of a step receives them.
     *
     * The handles are PLAIN, and this is the honest projection of a public
     * channel: the value is not a secret, so a handle that claims it is one
     * would be a lie the redaction machinery would act on.
     */
    fun asEnvironment(): Map<String, SecretHandle> =
        values.mapValues { (_, value) -> SecretHandle.plain(value) }

    override fun equals(other: Any?): Boolean =
        this === other || (other is RunConfiguration && other.values == values)

    override fun hashCode(): Int = values.hashCode()

    /**
     * The shape of this configuration, without its values.
     *
     * The values do not travel: they are the client's own text, and a log
     * line that carried them would publish them to whoever reads the log.
     */
    override fun toString(): String = "RunConfiguration(${values.size} entries)"

    companion object {

        /** The configuration that carries nothing. */
        val EMPTY: RunConfiguration = RunConfiguration(emptyMap())

        /** How many entries a configuration may carry. */
        const val MAX_ENTRIES: Int = 32

        /** How long the NAME of an entry may be. */
        const val MAX_NAME_LENGTH: Int = 64

        /** How long the VALUE of an entry may be. */
        const val MAX_VALUE_LENGTH: Int = 512

        /**
         * The shape of the name of an entry.
         *
         * The rule is the PRODUCT's, and it lives in the domain with the other
         * channel that obeys it —the parameters a pipeline declares— so that
         * the two checkers cannot disagree about what a name may look like.
         */
        private val NAME_PATTERN: Regex = ClientInputRules.NAME_PATTERN

        /**
         * The prefixes the product owns.
         *
         * They are the PRODUCT's, and they live in the domain with the other
         * channel that obeys them, for the same reason: a prefix list written
         * twice is a list that drifts.
         */
        private val RESERVED_PREFIXES: List<String> = ClientInputRules.RESERVED_PREFIXES

        /**
         * The names the launcher consumes to BUILD the PATH of a step.
         *
         * `JAVA_HOME` and `M2_HOME` are not the product's own names, but the
         * launcher prepends `$JAVA_HOME/bin` and `$M2_HOME/bin` to the PATH
         * AFTER the sandbox's keep-set has filtered it. A configuration that
         * named them would put a directory of its own at the front of the
         * PATH of every step, in front of the paths the operator kept, and
         * the keep-set would never see it.
         *
         * `PATH` is here for the same reason and for one more. The sandbox
         * filters and normalises a caller's PATH only under the LOCAL
         * profile: under the pass-through profile —which is the default, and
         * the profile the supervised runs use, because the supervisor never
         * names one— a declared PATH reaches the step verbatim. A
         * configuration that named `PATH` would therefore replace the PATH
         * of every step of the run with a directory of the client's, and the
         * operator's own directories would not be in it. The PATH of a step
         * is the launcher's, and so is the rule that builds it.
         */
        private val PATH_ANCHORS: Set<String> = setOf("JAVA_HOME", "M2_HOME", "PATH")

        /**
         * The configuration of [raw], or the refusal that it breaks a rule.
         *
         * The checks are pure and they have no effect: a configuration is
         * either accepted whole or refused whole, and nothing is written
         * anywhere by the answer.
         *
         * ## The order of the checks is the contract
         *
         * The order is fixed so that a given configuration always produces
         * the same reason, and so that the reasons are ordered from the
         * cheapest and most general to the most specific:
         *
         *  1. how many entries there are;
         *  2. how long the name is;
         *  3. whether the name has the shape of a name;
         *  4. whether the name belongs to the product;
         *  5. whether the name is an anchor of the PATH of a step;
         *  6. whether the name is denied by the sandbox;
         *  7. how long the value is;
         *  8. whether the value carries a NUL.
         */
        fun of(raw: Map<String, String>): RunConfigurationOutcome {
            if (raw.size > MAX_ENTRIES) {
                return RunConfigurationOutcome.Refused(RunConfigurationRefusal.TooManyEntries)
            }
            for ((name, value) in raw) {
                if (name.length > MAX_NAME_LENGTH) {
                    return RunConfigurationOutcome.Refused(RunConfigurationRefusal.NameIsTooLong)
                }
                if (!NAME_PATTERN.matches(name)) {
                    return RunConfigurationOutcome.Refused(RunConfigurationRefusal.NameIsNotLegal)
                }
                if (isReserved(name)) {
                    return RunConfigurationOutcome.Refused(RunConfigurationRefusal.NameIsReserved)
                }
                if (name in PATH_ANCHORS) {
                    return RunConfigurationOutcome.Refused(RunConfigurationRefusal.NameIsAPathAnchor)
                }
                if (SandboxEnvDenyList.denies(name)) {
                    return RunConfigurationOutcome.Refused(RunConfigurationRefusal.NameIsDenied)
                }
                if (value.length > MAX_VALUE_LENGTH) {
                    return RunConfigurationOutcome.Refused(RunConfigurationRefusal.ValueIsTooLong)
                }
                if (!EnvModel.isValueSafeForProcessBuilder(value)) {
                    return RunConfigurationOutcome.Refused(RunConfigurationRefusal.ValueHasANul)
                }
            }
            return RunConfigurationOutcome.Accepted(RunConfiguration(raw.toMap()))
        }

        /**
         * Whether [name] begins with one of the product's own prefixes.
         *
         * ## Why this is a loop and not `any { }`
         *
         * Because the rule is about EVERY prefix, and `any { }` is a
         * function the compiler inlines into the caller: the branch it
         * introduces is not a line of this file, and a rule whose branch
         * nobody can point at is a rule whose branch nobody can test. The
         * loop says the same thing in words the reader can check, and it
         * gives the mutation lane a branch that has a source line.
         */
        private fun isReserved(name: String): Boolean {
            for (prefix in RESERVED_PREFIXES) {
                if (name.startsWith(prefix)) return true
            }
            return false
        }

        /**
         * The canonical text of [values], the one that reaches a fingerprint.
         *
         * ## Why the encoding is not a plain join
         *
         * Because a plain join is not injective, and a fingerprint that
         * collides is a fingerprint that says two different configurations are
         * the same request. `{"A":"1","B":"2"}` and `{"A":"1\nB=2"}` joined as
         * `name=value` are the same text, and so would be the same
         * fingerprint: the second request would be answered with the run of
         * the first, running with the first's configuration.
         *
         * ## What the encoding is
         *
         * The entries sorted by name, and every field LENGTH-PREFIXED —the
         * length of the name, then the name, then the length of the value,
         * then the value— joined with `|`. With the lengths in front, the
         * text says exactly where each field ends, so no value can imitate a
         * separator and two different configurations cannot produce the same
         * text. This is the convention the product's own fingerprint of a
         * request already uses, and this one inherits it.
         *
         * The empty configuration produces the EMPTY text, and a fingerprint
         * that has nothing to say about the configuration says nothing: a
         * request without a configuration keeps the fingerprint it had
         * before this type existed.
         */
        fun canonicalText(values: Map<String, String>): String =
            values.entries
                .sortedBy { it.key }
                .joinToString("|") { (name, value) ->
                    name.length.toString() + ":" + name + "=" + value.length.toString() + ":" + value
                }
    }
}

/**
 * What the configuration of a request turned out to be.
 *
 * Two shapes, and a caller has to act on both: the configuration is
 * accepted, or it is refused with a name.
 */
sealed interface RunConfigurationOutcome {

    /** The configuration broke no rule. */
    data class Accepted(val configuration: RunConfiguration) : RunConfigurationOutcome

    /** The configuration broke a rule, and the refusal says which one. */
    data class Refused(val refusal: RunConfigurationRefusal) : RunConfigurationOutcome
}

/**
 * The rules a configuration may break.
 *
 * ## Why the reasons are WORDS and not exceptions
 *
 * Because the refusal travels: it is what the service tells the client, and
 * a client that receives an exception has to read a stack trace to learn
 * what to fix. Each word says which rule was broken, and the detail says
 * which one in prose.
 *
 * ## Why the words are not a sealed hierarchy
 *
 * Because the reason travels INSIDE the refusal of the wire, which is a
 * word plus a detail. A new rule adds a word and not a case to a published
 * interface, so the vocabulary of the wire does not grow with the rules.
 */
enum class RunConfigurationRefusal(val reason: String, val detail: String) {

    /** The configuration carries more entries than the product accepts. */
    TooManyEntries(
        "the-configuration-has-too-many-entries",
        "a configuration may carry at most ${RunConfiguration.MAX_ENTRIES} entries",
    ),

    /** The name of an entry is longer than the product accepts. */
    NameIsTooLong(
        "the-configuration-name-is-too-long",
        "the name of an entry may be at most ${RunConfiguration.MAX_NAME_LENGTH} characters",
    ),

    /** The name of an entry is not the shape of a name. */
    NameIsNotLegal(
        "the-configuration-name-is-not-legal",
        "the name of an entry must match [A-Za-z_][A-Za-z0-9_]*",
    ),

    /** The name of an entry belongs to the product. */
    NameIsReserved(
        "the-configuration-name-is-reserved",
        "the name of an entry may not begin with PIPELINE_, PIPELINEK_ or DURABLE_SH_",
    ),

    /** The name of an entry is one the launcher consumes to build the PATH. */
    NameIsAPathAnchor(
        "the-configuration-name-is-a-path-anchor",
        "JAVA_HOME, M2_HOME and PATH are the launcher's own names for the PATH of a step",
    ),

    /** The name of an entry is one the sandbox removes from an environment. */
    NameIsDenied(
        "the-configuration-name-is-denied",
        "the sandbox removes this name from the environment of a step, and a configuration " +
            "may not carry it",
    ),

    /** The value of an entry is longer than the product accepts. */
    ValueIsTooLong(
        "the-configuration-value-is-too-long",
        "the value of an entry may be at most ${RunConfiguration.MAX_VALUE_LENGTH} characters",
    ),

    /** The value of an entry carries a NUL, which the launcher refuses. */
    ValueHasANul(
        "the-configuration-value-has-a-nul",
        "the value of an entry may not carry a NUL",
    ),
}
