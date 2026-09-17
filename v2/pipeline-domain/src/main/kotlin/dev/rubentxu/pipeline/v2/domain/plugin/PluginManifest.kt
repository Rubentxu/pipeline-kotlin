package dev.rubentxu.pipeline.v2.domain.plugin

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.identity.ResourceKind
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRef
import dev.rubentxu.pipeline.v2.domain.step.StepCapability

/**
 * LFC-2E2-PREP (C5/C6, 2026-09-17): the immutable, typed declaration of a plugin
 * release.
 *
 * ## What it is
 *
 * A [PluginManifest] is the public, declarative contract of a plugin release:
 * who it is, what families it contributes, and what capabilities each family
 * declares. It is **immutable** and **typed** — there is no string-keyed map
 * and no `Any?` payload.
 *
 * ## What it is NOT
 *
 * - NOT runtime data. The manifest is read at composition time and never
 *   mutated by an invocation.
 * - NOT a [dev.rubentxu.pipeline.v2.domain.step.StepDefinition] (which carries
 *   the executable handler). The manifest declares the release; the
 *   StepDefinition carries the handler.
 * - NOT a registry. [dev.rubentxu.pipeline.v2.domain.step.StepRegistry] is
 *   the only registration authority; the manifest is a *description* that
 *   the registration process can validate against.
 *
 * ## Admission contract (C7)
 *
 * A manifest that satisfies [PluginAdmissionPolicy.READY] (declared by the
 * caller or by [PluginAdmissionPolicyResolver.resolve]) is admitted to the
 * composition step; a manifest that is missing capabilities, declaring
 * families with duplicate StepKeys, or referencing unknown StepKeys is
 * rejected fail-closed before any handler runs.
 *
 * ## Resource identity (C1/C2)
 *
 * A manifest is identified by a [PluginReleaseRef] — a deterministic
 * [ResourceRef] of kind `PLUGIN_RELEASE` carrying the plugin coordinate
 * (`<coordinate>`) and the semver version. One resource per release, even
 * if the same `coordinate` ships multiple versions over time.
 *
 * @property coordinate plugin coordinate (e.g. `pipeline.utilities.json`,
 *   `pipeline.scm.git`). Stable across releases of the same plugin; changes
 *   require a new coordinate.
 * @property version semver version of THIS release (e.g. `1.2.0`).
 * @property contributors the human-readable list of contributors (for diagnostics;
 *   not used in capability admission).
 * @property families the typed Step families this release contributes. Each
 *   [PluginFamily] binds a [PluginStepId] to a set of [StepCapability] tokens
 *   the family requires and a list of [PluginFamilyReference] references
 *   that detail the family.
 * @property declaredCapabilities the union of all capabilities declared by
 *   every family in this release. Computed at construction time so the
 *   admission policy can read it in O(1).
 */

data class PluginManifest(
    val coordinate: PluginCoordinate,
    val version: PluginVersion,
    val contributors: List<String> = emptyList(),
    val families: List<PluginFamily> = emptyList(),
) {
    init {
        require(families.isNotEmpty() || contributors.isNotEmpty()) {
            "PluginManifest requires at least one family or contributor"
        }
        // No duplicate StepKeys within a release.
        val keys = families.map { it.stepKey }
        require(keys.size == keys.toSet().size) {
            "PluginManifest contains duplicate StepKeys: " +
                keys.groupBy { it }.filter { it.value.size > 1 }.keys
        }
    }

    /** Union of every capability declared by every family. O(families * avg-caps). */
    val declaredCapabilities: Set<StepCapability> by lazy {
        families.flatMap { it.requiredCapabilities }.toSet()
    }

    /** All StepKeys contributed by this release, in declaration order. */
    val stepKeys: List<PluginStepId> by lazy { families.map { it.stepKey } }

    /** Canonical [PluginReleaseRef] for this release. */
    fun releaseRef(): PluginReleaseRef = PluginReleaseRef(coordinate, version)
}

/**
 * Stable plugin coordinate. Independent of version: a coordinate identifies a
 * plugin family across releases. Format: `<groupId>.<artifactId>` (dot-separated,
 * lowercase). Examples: `pipeline.utilities.json`, `pipeline.scm.git`.
 */

@JvmInline
value class PluginCoordinate(val value: String) {
    init {
        require(value.isNotBlank()) { "PluginCoordinate must not be blank" }
        require(value.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }) {
            "PluginCoordinate '$value' contains invalid characters; " +
                "only [a-zA-Z0-9.-_] and '.' separators are allowed"
        }
    }

    override fun toString(): String = value
}

/**
 * Semver plugin version. Three positive integers (`major.minor.patch`), with
 * optional pre-release and build metadata. Parsed but not deeply validated —
 * plugin tooling may add additional checks at publish time.
 */

data class PluginVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: String? = null,
    val buildMetadata: String? = null,
) {
    init {
        require(major >= 0 && minor >= 0 && patch >= 0) {
            "PluginVersion components must be non-negative (got $major.$minor.$patch)"
        }
    }

    override fun toString(): String = buildString {
        append(major).append('.').append(minor).append('.').append(patch)
        if (preRelease != null) append('-').append(preRelease)
        if (buildMetadata != null) append('+').append(buildMetadata)
    }

    companion object {
        /**
         * Parse a semver string. Returns null on malformed input (fail-closed at
         * the caller; no exception for expected operational outcomes).
         */
        fun parse(text: String): PluginVersion? {
            val match = SEMVER_REGEX.matchEntire(text) ?: return null
            return PluginVersion(
                major = match.groupValues[1].toInt(),
                minor = match.groupValues[2].toInt(),
                patch = match.groupValues[3].toInt(),
                preRelease = match.groupValues[4].takeIf { it.isNotEmpty() },
                buildMetadata = match.groupValues[5].takeIf { it.isNotEmpty() },
            )
        }

        private val SEMVER_REGEX =
            Regex("""^(\d+)\.(\d+)\.(\d+)(?:-([\w.-]+))?(?:\+([\w.-]+))?$""")
    }
}

/**
 * One Step family contributed by a [PluginManifest].
 *
 * @property stepKey the family's PluginStepId (must be unique within a release).
 * @property requiredCapabilities the [StepCapability] tokens the family's
 *   handler declares. The engine admits capabilities fail-closed before the
 *   handler runs; missing capabilities reject the invocation, not silently
 *   run with reduced capability.
 * @property executionShape the SHAPE of execution this family declares
 *   (atomic, body-bearing, scoped, retrying, parallel). The engine reads this
 *   to project [dev.rubentxu.pipeline.v2.domain.step.BodyExecutionPolicy] and
 *   capability admission. See [PluginFamilyShape].
 * @property references optional, typed cross-references to other entities
 *   (e.g. a `PipelineDefinition` reference for a plugin that augments core).
 *   Used for diagnostics and release-archive cross-referencing, not for
 *   capability admission.
 */

data class PluginFamily(
    val stepKey: PluginStepId,
    val requiredCapabilities: Set<StepCapability> = emptySet(),
    val executionShape: PluginFamilyShape = PluginFamilyShape.ATOMIC,
    val references: List<PluginFamilyReference> = emptyList(),
)

/**
 * Closed ADT classifying the execution SHAPE a plugin family declares.
 *
 * The shape is a property of the family KIND, not of any one invocation. It
 * determines which engine path admits the family:
 *  - ATOMIC: terminal Step, no body. Admitted as a standard [StepDefinition].
 *  - BODY_BEARING: the family carries a `body: List<StepSpec>` payload. Admitted
 *    through the body's [dev.rubentxu.pipeline.v2.domain.step.BodyExecutionPolicy].
 *  - SCOPED: the family projects a scope around its body (e.g. directory,
 *    environment, deadline). Admitted through [dev.rubentxu.pipeline.v2.domain.step.BodyContextProjection].
 *  - RETRYING: the family re-dispatches its body until success or budget
 *    exhaustion. Admitted through [dev.rubentxu.pipeline.v2.domain.step.BodyExecutionPolicy.Retrying].
 *  - PARALLEL: the family fans out branches concurrently. Admitted through
 *    [dev.rubentxu.pipeline.v2.domain.step.BodyExecutionPolicy.Parallel].
 *
 * Adding a case forces every `when` over this ADT to be revisited (closed
 * family, exhaustive by construction).
 */

enum class PluginFamilyShape {
    ATOMIC,
    BODY_BEARING,
    SCOPED,
    RETRYING,
    PARALLEL,
}

/**
 * Optional, typed cross-reference a [PluginFamily] carries. Used for
 * diagnostics and release-archive cross-referencing, not for capability
 * admission.
 */

sealed interface PluginFamilyReference {

    /** Cross-reference to a [ResourceRef] (e.g. a pipeline definition). */
    data class Resource(val ref: ResourceRef) : PluginFamilyReference

    /** Cross-reference to a sibling family's StepKey (within the same release). */
    data class Sibling(val stepKey: PluginStepId) : PluginFamilyReference
}

/**
 * LFC-2E2-PREP (C2, 2026-09-17): the deterministic, hierarchical reference to a
 * specific plugin release.
 *
 * ## What it is
 *
 * A [PluginReleaseRef] identifies a single release of a single plugin: the
 * coordinate (which plugin) and the semver version (which release of it).
 *
 * ## Resource identity
 *
 * [PluginReleaseRef] is a typed wrapper around a [ResourceRef] of kind
 * `PLUGIN_RELEASE`. The canonical text form is
 * `v1:plugin-release:<coordinate>@<version>`.
 */

data class PluginReleaseRef(
    val coordinate: PluginCoordinate,
    val version: PluginVersion,
) {
    fun toResourceRef(): ResourceRef = ResourceRef(
        kind = ResourceKind.PLUGIN_RELEASE,
        segments = listOf("pipeline", "plugin-release", coordinate.value, version.toString()),
    )

    override fun toString(): String = "${coordinate.value}@${version}"

    companion object {
        /**
         * Parse a `<coordinate>@<semver>` text into a [PluginReleaseRef]. Returns
         * null on malformed input (fail-closed at the caller).
         */
        fun parse(text: String): PluginReleaseRef? {
            val atIdx = text.lastIndexOf('@')
            if (atIdx <= 0 || atIdx >= text.length - 1) return null
            val coord = text.substring(0, atIdx)
            val version = PluginVersion.parse(text.substring(atIdx + 1)) ?: return null
            return runCatching { PluginReleaseRef(PluginCoordinate(coord), version) }.getOrNull()
        }
    }
}
