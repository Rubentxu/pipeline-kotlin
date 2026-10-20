package dev.rubentxu.pipeline.v2.domain.step

import dev.rubentxu.pipeline.v2.domain.PluginStepId

import dev.rubentxu.pipeline.v2.domain.directive.DirectiveKey

/**
 * S6/B1 — what a plugin CONTRIBUTS, as distinct from what it IS.
 *
 * ## Why this is separate from [PluginFamily]
 *
 * [PluginFamily] answers "what domain is this plugin for" (SCM, NETWORK, TESTING,
 * ...). Its own KDoc records that the runtime does not branch on it and that it is
 * metadata rather than a verdict. These types answer "which structural shapes does
 * this plugin add to the pipeline". The two are orthogonal and neither is derivable
 * from the other: a TESTING family plugin contributes Steps, and a NETWORK family
 * plugin contributes a Step plus a Capability. Keeping both, rather than merging
 * them, is what lets admission check structure without having to invent a taxonomy.
 *
 * ## Why these are typed lists and not a `Map<String, Any?>`
 *
 * Every entry names one contribution and carries only the payload that contribution
 * means. A Step declares the capabilities it requires; a Directive and an Event do
 * not, so they do not get a nullable capability field. The union is a sealed set of
 * shapes, not an open map of anything.
 *
 * ## Why Reactor is absent
 *
 * S5.5 is `DEFERRED` by ADR-0104, which named S6 as where a plugin-contributed
 * reactive construct belongs. This is that place, and it still does not declare one:
 * a manifest slot with no consumer is the same defect ADR-0104 refused to let S5.5
 * commit, reached by another road. When ADR-0104's reopening condition is met,
 * Reactor joins this hierarchy as a fifth shape with a real producer behind it.
 */
data class PluginStepContribution(
    val stepKey: PluginStepId,
    val declaredCapabilities: Set<StepCapability>,
)

data class PluginDirectiveContribution(
    val directiveKey: DirectiveKey,
)

data class PluginEventContribution(
    val eventKind: String,
) {
    init {
        require(eventKind.isNotBlank()) { "PluginEventContribution.eventKind must not be blank" }
    }
}

/**
 * The four contribution families this release supports: Step, Directive, Event and
 * Capability.
 *
 * Duplicate detection lives here, at construction, because a manifest that declares
 * the same Step twice is not a document that a consumer can interpret — it is a
 * contradiction, and it must fail before any contributor is loaded rather than
 * becoming a first-wins/last-wins question at registration time.
 */
data class PluginContributions(
    val steps: List<PluginStepContribution> = emptyList(),
    val directives: List<PluginDirectiveContribution> = emptyList(),
    val events: List<PluginEventContribution> = emptyList(),
    val capabilities: Set<StepCapability> = emptySet(),
) {
    init {
        requireDistinct("Step", steps.map { it.stepKey.value })
        requireDistinct("Directive", directives.map { it.directiveKey.value })
        requireDistinct("Event", events.map { it.eventKind })
    }

    /** True when the plugin contributes nothing at all. Admission rejects this shape. */
    val isEmpty: Boolean
        get() = steps.isEmpty() && directives.isEmpty() && events.isEmpty() && capabilities.isEmpty()

    private fun requireDistinct(family: String, keys: List<String>) {
        val duplicates = keys.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        require(duplicates.isEmpty()) {
            "PluginContributions declares duplicate $family entries: $duplicates. " +
                "A duplicate declaration is a contradiction, not a precedence question."
        }
    }
}

/**
 * S6/B1 — the PipelineK API range a plugin declares compatibility with.
 *
 * §2 of the plugin SDK package requires `apiRange`, and it is what makes admission
 * able to refuse a plugin before its classes load: a plugin built against PipelineK
 * 0.48 is not silently loadable into 0.52. Exclusive upper bound, because a
 * compatibility range states where a plugin stops being supported, not where it
 * stops being parseable.
 */
data class PipelineKApiRange(
    val fromInclusive: SemVer,
    val untilExclusive: SemVer,
) {
    init {
        require(untilExclusive.major > fromInclusive.major ||
            (untilExclusive.major == fromInclusive.major && untilExclusive.minor > fromInclusive.minor) ||
            (untilExclusive.major == fromInclusive.major &&
                untilExclusive.minor == fromInclusive.minor &&
                untilExclusive.patch > fromInclusive.patch)
        ) {
            "PipelineKApiRange must be non-empty and increasing: " +
                "[$fromInclusive, $untilExclusive)"
        }
    }

    /** Pure compatibility question. No clock, no I/O, no ambient state. */
    fun accepts(runtime: SemVer): Boolean =
        compareVersions(runtime, fromInclusive) >= 0 &&
            compareVersions(runtime, untilExclusive) < 0

    private fun compareVersions(a: SemVer, b: SemVer): Int = when {
        a.major != b.major -> a.major - b.major
        a.minor != b.minor -> a.minor - b.minor
        else -> a.patch - b.patch
    }

    override fun toString(): String = "[$fromInclusive, $untilExclusive)"
}

/**
 * Version of the manifest SCHEMA, not of the plugin.
 *
 * Separate from [PluginReleaseRef.version] on purpose: a plugin can be released
 * twice under the same version with different digests, and the schema that decoded
 * it is a property of the document format. Conflating them would make "which codec
 * read this" indistinguishable from "which build wrote this".
 */
@JvmInline
value class ManifestSchemaVersion(val value: Int) {
    init {
        require(value > 0) { "ManifestSchemaVersion must be positive (was $value)" }
    }

    override fun toString(): String = "manifest/v$value"

    companion object {
        /** The only schema this release admits. Unknown versions are rejected, not guessed. */
        val CURRENT: ManifestSchemaVersion = ManifestSchemaVersion(1)

        /**
         * Parse of the only legal spelling.
         *
         * A decoder that accepted an unrecognised schema would be admitting documents
         * whose meaning it cannot vouch for, which is the fail-open behaviour the whole
         * plugin constitution exists to prevent.
         */
        fun parseOrNull(text: String): ManifestSchemaVersion? {
            val prefix = "manifest/v"
            if (!text.startsWith(prefix)) return null
            val digits = text.removePrefix(prefix)
            if (digits.isEmpty() || digits.any { it !in '0'..'9' }) return null
            val parsed = digits.toIntOrNull() ?: return null
            if (parsed != CURRENT.value) return null
            return CURRENT
        }
    }
}
