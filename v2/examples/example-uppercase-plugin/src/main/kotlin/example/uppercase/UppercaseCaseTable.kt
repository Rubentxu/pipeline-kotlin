package example.uppercase

import dev.rubentxu.pipeline.v2.domain.step.RuntimeCapabilityContributor
import dev.rubentxu.pipeline.v2.domain.step.StepCapability

/**
 * S6/I — the plugin's THIRD family, and the one a Step cannot do without.
 *
 * So far this JAR contributed Steps and an Event: two families a host can consume by simply
 * running them. A capability is different — it is the plugin handing the host a VALUE, and the
 * host handing it back to a Step that declared it needed it. That round trip is the one the
 * `EventDefinitionContributor` seam cannot express, and it is why this plugin cannot be proven by
 * adding a fourth Step.
 *
 * ## Why a case table rather than a clock or a network client
 *
 * [RuntimeCapabilityContributor.capabilities] takes no argument, by design: a context-aware
 * contributor would be asked twice and could answer differently, and a Step would then pass
 * admission and find its capability missing at execution. So the value carried here must be the
 * same at every moment in the run — a table satisfies that trivially and a socket would not.
 *
 * The table is also a real thing a plugin wants to own. Uppercasing is not a total function on
 * Unicode: Turkish dotless/dotted i is the textbook case, and a plugin that hardcoded
 * `String.uppercase()` has silently chosen a locale. Owning the mapping is ownership of a decision,
 * not of a convenience.
 */
val EXAMPLE_CASE_TABLE_CAPABILITY: StepCapability = StepCapability("example.uppercase.case-table")

/**
 * The typed value behind [EXAMPLE_CASE_TABLE_CAPABILITY].
 *
 * A real class rather than a `Map<Char, String>` handed across the seam, because the public
 * capability contract is what a Step compiles against: a Step that received a raw map would be
 * coupled to this plugin's storage choice, and the value class below would be a change nobody
 * could make later.
 */
class CaseTable(private val rules: Map<Char, String>) {

    /**
     * Apply the table, falling back to the character itself.
     *
     * A character with no rule is not an error: the table describes the exceptions, and a table
     * that had to enumerate all of Unicode would be a worse default than the identity.
     */
    fun apply(text: String): String =
        buildString(text.length) {
            for (character in text) {
                append(rules[character] ?: character.toString())
            }
        }
}

/**
 * ServiceLoader entry point for the capability family:
 * `META-INF/services/dev.rubentxu.pipeline.v2.domain.step.RuntimeCapabilityContributor`.
 *
 * The value is constructed here, once, and never varies — which is the property the no-argument
 * signature exists to guarantee.
 */
class UppercaseCaseTableContributor : RuntimeCapabilityContributor {

    override fun capabilities(): Map<StepCapability, Any> = mapOf(
        EXAMPLE_CASE_TABLE_CAPABILITY to DEFAULT_TABLE,
    )

    private companion object {
        /**
         * The mapping is the plugin's, and it is spelled out rather than derived from a default
         * locale: `toUpperCase()` would make the same plugin produce different bytes depending on
         * the machine that ran the pipeline, which is precisely the property a deterministic
         * replay is not allowed to have.
         */
        val DEFAULT_TABLE = CaseTable(
            mapOf(
                'i' to "İ", // LATIN CAPITAL LETTER I WITH DOT ABOVE
                'ı' to "I", // LATIN CAPITAL LETTER I
            ),
        )
    }
}
