package dev.rubentxu.pipeline.v2.sdk.runtime

import dev.rubentxu.pipeline.v2.domain.step.PipelineKApiRange
import dev.rubentxu.pipeline.v2.domain.step.SemVer

/**
 * The loader for the policy-derived compatibility resource.
 * <h2>Why this exists</h2>
 * The four official plugins used to declare their `apiRange` as a hand-typed
 * `PipelineKApiRange(...)` constant in the declaration object. When the product
 * version was bumped from 0.53.x to 0.54.x, the constant was not updated, and
 * the v0.56.0-rc3 candidate shipped a manifest with `apiRange=[0.47.0, 0.53.0)`
 * while the host product was 0.56.0 — `PluginAdmissionGate` would refuse every
 * official plugin on its own distribution.
 * The fix is: a pure policy in `buildSrc` (`PluginCompatibilityPolicy`) reads
 * `rootProject.version` and emits `META-INF/pipelinek/plugin-compatibility.json`
 * at build time. THIS class is the runtime reader of that document. Every
 * official plugin declaration calls [load] instead of constructing a literal,
 * so a future product-version bump is observable from the runtime, and the
 * Gradle wiring of the policy is the only authority on the value.
 * <h2>Fail-closed</h2>
 * A plugin without the resource cannot be installed correctly. The loader
 * refuses the call rather than constructing a default; admission would have
 * refused the plugin anyway, and a default would let the drift return.
 * <h2>External plugins</h2>
 * External plugins (any plugin not in `:pipeline-step-sdk/`) keep their manual
 * `apiRange`. They are not consumers of this resource, and their build does
 * not emit one.
*/
object PluginCompatibilityResource {

    const val RESOURCE_PATH: String = "META-INF/pipelinek/plugin-compatibility.json"

    /**
     * Load the compatibility resource from the given [classLoader]'s classpath
     * and return the parsed `apiRange`. Throws when the resource is absent,
     * malformed, or carries a range that cannot be parsed back into a
     * [PipelineKApiRange]. Plugins MUST call this from their declaration
     * instead of typing the range.
*/
    fun load(classLoader: ClassLoader = PluginCompatibilityResource::class.java.classLoader): PipelineKApiRange {
        val resource = classLoader.getResource(RESOURCE_PATH)
            ?: throw IllegalStateException(
                "$RESOURCE_PATH is absent on the classpath. The plugin was built without the " +
                    "PK-RC-PLUGIN-001 compatibility resource. Run a clean build via Gradle so the " +
                    "policy can emit the resource from rootProject.version. A plugin that does not " +
                    "declare compatibility cannot be admitted.",
            )
        val text = resource.openStream().use { it.readBytes().toString(Charsets.UTF_8) }
        val rawRange = parseApiRangeField(text)
            ?: throw IllegalStateException(
                "$RESOURCE_PATH does not contain a parseable \"apiRange\" field. The plugin was built " +
                    "from a malformed policy; rebuild via Gradle so the policy re-emits the resource.",
            )
        return parseRangeLiteral(rawRange)
            ?: throw IllegalStateException(
                "$RESOURCE_PATH declares apiRange='$rawRange', which is not a '[X.Y.Z, A.B.C)' literal. " +
                    "The policy must produce a well-formed range; check buildSrc.",
            )
    }

    /**
     * Minimal extraction: find `"apiRange": "[..."` and return the value. Robust
     * enough for the resource shape `PluginCompatibilityPolicy.toJson()` emits;
     * the production codec lives in `PluginManifestCodec` for richer documents.
*/
    internal fun parseApiRangeField(json: String): String? {
        val key = "\"apiRange\""
        val keyIdx = json.indexOf(key)
        if (keyIdx < 0) return null
        val colonIdx = json.indexOf(':', keyIdx + key.length)
        if (colonIdx < 0) return null
        val openIdx = json.indexOf('"', colonIdx + 1)
        if (openIdx < 0) return null
        val closeIdx = json.indexOf('"', openIdx + 1)
        if (closeIdx < 0) return null
        return json.substring(openIdx + 1, closeIdx)
    }

    /** Parse a `[X.Y.Z, A.B.C)` literal. Returns null on any deviation. */
    internal fun parseRangeLiteral(raw: String): PipelineKApiRange? {
        val match = RANGE_REGEX.matchEntire(raw.trim()) ?: return null
        val (lower, upper) = match.destructured
        val from = parseSemVer(lower) ?: return null
        val until = parseSemVer(upper) ?: return null
        return runCatching { PipelineKApiRange(from, until) }.getOrNull()
    }

    private val RANGE_REGEX = Regex("""\[\s*(\d+\.\d+\.\d+)\s*,\s*(\d+\.\d+\.\d+)\s*\)""")

    private fun parseSemVer(raw: String): SemVer? {
        val parts = raw.split('.')
        if (parts.size != 3) return null
        val numbers = parts.map { it.toIntOrNull() ?: return null }
        if (numbers.any { it < 0 }) return null
        return SemVer(numbers[0], numbers[1], numbers[2])
    }
}
