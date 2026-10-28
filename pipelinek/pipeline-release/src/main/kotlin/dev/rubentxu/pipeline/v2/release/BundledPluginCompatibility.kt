package dev.rubentxu.pipeline.v2.release

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarInputStream
import java.util.zip.ZipInputStream

/**
 * Fail-closed bundled plugin compatibility gate.
 *
 * The product version is the HOST for every bundled plugin. The contract that
 * the host admits a plugin is the plugin's `apiRange`; it is the plugin, not
 * the host, that names the range of host versions it can run against. A
 * candidate that ships with a plugin whose range excludes the host is the
 * same defect as a candidate that ships with a missing plugin manifest, and
 * the runtime's admission pass would refuse the plugin at runtime — a failure
 * the user would see AFTER download, not at candidate admission.
 *
 * This check moves that refusal to the producer side, where the build can
 * fail closed with a diagnostic that names the offending plugin and its
 * observed range. It is the one check that is mechanical, observable
 * from the distribution ZIP alone, and reproducible.
 *
 * ## What this check is NOT
 *
 * It does NOT assert that all bundled plugins are at the SAME releaseVersion
 * (e.g. junit is intentionally `0.1.0` while http is `0.51.0`). Plugins have
 * their own release cadence; the host's product version is the one the
 * plugin's `apiRange` must accept. Asserting version equality would have
 * rejected junit on every v0.5x release.
 *
 * It also does NOT check anything about the host's identity; that is
 * [evaluateDistributionIdentity]'s job. The two verdicts are reported
 * independently so a divergence in one does not hide a divergence in the
 * other.
 */
object BundledPluginCompatibility {

    /**
     * The path pattern that marks a JAR inside the distribution's `lib/`
     * directory as a bundled plugin. The current build layout ships the
     * official plugins as `<plugin>-<version>.jar` (e.g. `http-0.51.0.jar`,
     * `scm-git-0.51.0.jar`, `junit-0.1.0.jar`); the `pipeline-step-sdk/`
     * directory layout from earlier revisions has been replaced. The
     * probe is therefore "every JAR under lib/ that carries a
     * `META-INF/pipelinek/plugin-manifest.json`" — a content check, not
     * a name check. The name is kept as a secondary filter so a future
     * layout that names JARs `pipeline-step-sdk-*` keeps working.
     */
    private const val PLUGIN_JAR_HINT = "pipeline-step-sdk"
    private const val LIB_DIR_HINT = "/lib/"

    /**
     * The manifest path inside a plugin JAR. Mirrors
     * the manifest resource the runtime reads, and is the only path the
     * check looks at; a plugin with no manifest would have been refused
     * by admission at runtime, and is therefore also refused here.
     */
    private const val PLUGIN_MANIFEST_RESOURCE = "META-INF/pipelinek/plugin-manifest.json"

    /**
     * Probe the distribution ZIP for every bundled plugin manifest and
     * decode the minimum the gate needs: plugin id, release version, and
     * the raw apiRange string. Missing values stay null so a candidate
     * with a partially-emitted manifest is recorded as incomplete, not
     * silently coerced into compatibility.
     *
     * Also accepts a single JAR as input, since a JAR is structurally a
     * ZIP and the same probe is useful at lower levels (one plugin under
     * test, no surrounding distribution).
     */
    fun probeZip(zip: Path): List<BundledPluginEntry> {
        if (!Files.isRegularFile(zip)) return emptyList()
        val entries = mutableListOf<BundledPluginEntry>()

        // The input may be a single plugin JAR (low-level probe) or a
        // distribution ZIP that contains bundled plugin JARs (high-level
        // probe). Both are valid ZIPs, but the matching rule differs: a
        // JAR has its manifest at the root, a ZIP has plugin JARs nested
        // under `lib/`. The name heuristic on the top-level call is the
        // same — `pipeline-step-sdk` in the file name — and selecting the
        // appropriate scan path from there keeps both shapes supported
        // with one entry point.
        val fileName = zip.fileName?.toString().orEmpty()
        if (fileName.contains(PLUGIN_JAR_HINT) && fileName.endsWith(".jar")) {
            // Single plugin JAR — look for the manifest at the root.
            val entry = pluginEntryOf(Files.readAllBytes(zip), fileName)
            return if (entry == null) emptyList() else listOf(entry)
        }

        ZipInputStream(Files.newInputStream(zip)).use { outer ->
            var entry = outer.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory &&
                    name.endsWith(".jar") &&
                    (name.contains(PLUGIN_JAR_HINT) || name.contains(LIB_DIR_HINT))
                ) {
                    val bytes = outer.readBytes()
                    val plugin = pluginEntryOf(bytes, Path.of(name).fileName.toString())
                    if (plugin != null) entries += plugin
                }
                outer.closeEntry()
                entry = outer.nextEntry
            }
        }
        return entries.sortedBy { it.pluginId }
    }

    /**
     * What ONE plugin JAR says about itself, as the gate needs to read it.
     *
     * Pure on purpose: it takes BYTES, so the decision can be exercised without
     * a filesystem, without a JAR on disk and without the distribution it
     * belongs to. This module's test lane is built on that rule — the JUnit
     * lane that once certified the shipped artifact needed the distribution
     * ZIP, the SBOM staging step and an environment variable, and it executed
     * zero classes while naming a real ZIP in its output. A decision that can
     * only be reached through a 93 MB artefact is a decision nobody will
     * exercise, and the one that decides whether a candidate may ship is the
     * last place for that.
     *
     * The two answers are kept apart on purpose:
     *
     *  - **null** — these bytes carry no plugin manifest, so they are not a
     *    plugin. A library JAR in `lib/` looks exactly like this, and the
     *    distribution ships a lot of them.
     *  - **an entry** — these bytes carry a manifest, so they DECLARE
     *    themselves a plugin, and the gate has to say what they declare.
     *
     * What the manifest declares is read field by field, and a field it does
     * not declare stays absent rather than being defaulted: an absent range is
     * not "no constraint", it is "nothing shows that this plugin admits the
     * host", which is a different fact and a different verdict.
     */
    internal fun pluginEntryOf(jarBytes: ByteArray, jarName: String): BundledPluginEntry? {
        val manifestBytes = readResourceFromJar(jarBytes, PLUGIN_MANIFEST_RESOURCE) ?: return null
        val parsed = parseManifestJson(String(manifestBytes, Charsets.UTF_8))
        return BundledPluginEntry(
            // A manifest that does not name its plugin is still a plugin
            // manifest — it sits at the plugin manifest path — so it is
            // reported under the JAR it came from, which is the name the
            // operator can act on.
            pluginId = parsed.pluginId?.let { shortPluginName(it) } ?: jarName,
            releaseVersion = parsed.releaseVersion.orEmpty(),
            apiRange = parsed.apiRange,
            jarName = jarName,
        )
    }

    /**
     * Extract the user-facing plugin namespace from the
     * `v1:plugin:<namespace>/plugin/<name>` resource ref. The full ref
     * is a stable identity token, but the `<namespace>` segment is the
     * name an operator recognises on a diagnostic and the one the
     * original `ResourceRef.plugin` form exposes. A manifest that does
     * not match the ref shape is left as the raw value rather than
     * null, so a single broken field still surfaces something to act on.
     */
    internal fun shortPluginName(ref: String): String {
        // "v1:plugin:<namespace>/plugin/<name>" — return <namespace>.
        val withoutScheme = ref.removePrefix("v1:plugin:")
        val pluginIdx = withoutScheme.indexOf("/plugin/")
        return if (pluginIdx > 0) withoutScheme.substring(0, pluginIdx) else withoutScheme
    }

    /**
     * Decide whether the host product version is admitted by every bundled
     * plugin's apiRange. Pure: takes the entries, returns a verdict; no I/O.
     */
    fun evaluate(
        expectedHost: ProductVersion,
        entries: List<BundledPluginEntry>,
    ): BundledPluginCompatibilityVerdict {
        if (entries.isEmpty()) {
            return BundledPluginCompatibilityVerdict.NoPluginsFound(expectedHost)
        }
        val conflicts = entries
            .mapNotNull { entry ->
                val host = expectedHost.value
                val raw = entry.apiRange
                if (!apiRangeContains(raw, host)) {
                    PluginRangeConflict(
                        pluginId = entry.pluginId,
                        releaseVersion = entry.releaseVersion,
                        apiRange = raw,
                        hostVersion = host,
                    )
                } else {
                    null
                }
            }
        return if (conflicts.isEmpty()) {
            BundledPluginCompatibilityVerdict.Compatible(expectedHost, entries)
        } else {
            BundledPluginCompatibilityVerdict.Incompatible(expectedHost, conflicts)
        }
    }

    /**
     * Parse a `PipelineKApiRange` literal of the form `[X.Y.Z, A.B.C)`.
     * Returns null when the literal does not match the shape so a malformed
     * range is recorded as a conflict (unknown range == not containable),
     * never as compatibility.
     *
     * The lower bound is inclusive and the upper bound is exclusive. The
     * producer side cannot rely on the host-side admission pass to
     * enforce this — a candidate that ships a malformed range would let
     * the host say "unknown" and admit silently. The check fails closed
     * on any literal it cannot parse.
     */
    internal fun apiRangeContains(raw: String?, version: String): Boolean {
        if (raw == null) return false
        val match = API_RANGE_REGEX.matchEntire(raw.trim()) ?: return false
        val (lower, upper) = match.destructured
        return semverCompare(version, lower) >= 0 && semverCompare(version, upper) < 0
    }

    private val API_RANGE_REGEX = Regex(
        """\[\s*(\d+\.\d+\.\d+)\s*,\s*(\d+\.\d+\.\d+)\s*\)""",
    )

    /**
     * Compare two dotted SemVer strings numerically. Returns negative if
     * `a < b`, zero if equal, positive if `a > b`. Pre-release suffixes
     * (`-rc1`, `-SNAPSHOT`) are ignored on the candidate path because
     * `apiRange` is never expressed with suffixes and the producer
     * refuses candidate-suffixed product versions upstream.
     */
    internal fun semverCompare(a: String, b: String): Int {
        val pa = a.trim().split(".").map { it.toIntOrNull() ?: 0 }
        val pb = b.trim().split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val va = pa.getOrElse(i) { 0 }
            val vb = pb.getOrElse(i) { 0 }
            if (va != vb) return va - vb
        }
        return 0
    }

    private fun readResourceFromJar(jarBytes: ByteArray, resource: String): ByteArray? {
        JarInputStream(jarBytes.inputStream()).use { jar ->
            var entry = jar.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name == resource) {
                    val out = ByteArray(4096)
                    val buffer = mutableListOf<Byte>()
                    val stream: InputStream = jar
                    while (true) {
                        val n = stream.read(out)
                        if (n <= 0) break
                        for (i in 0 until n) buffer += out[i]
                    }
                    return buffer.toByteArray()
                }
                jar.closeEntry()
                entry = jar.nextEntry
            }
        }
        return null
    }

    /**
     * Minimal manifest parser: only the three fields the gate reads. Anything
     * richer belongs in the SDK; this check is producer-side and only needs
     * the contract line.
     *
     * The three fields are read INDEPENDENTLY and each one is allowed to be
     * absent. That is the whole point of the change: a manifest that names a
     * plugin but does not declare a range is still a plugin manifest, and the
     * gate has to be able to say "this plugin declares no range" instead of
     * "there is no such plugin". The first is a conflict the operator can act
     * on; the second is a plugin that vanishes from the check, and the refusal
     * then happens in the host, after the download.
     */
    internal fun parseManifestJson(text: String): ParsedPluginManifest = ParsedPluginManifest(
        pluginId = REGEX_PLUGIN_ID.find(text)?.groupValues?.get(1),
        releaseVersion = REGEX_RELEASE_VERSION.find(text)?.groupValues?.get(1),
        apiRange = REGEX_API_RANGE.find(text)?.groupValues?.get(1),
    )

    private val REGEX_PLUGIN_ID = Regex(""""plugin"\s*:\s*"([^"]+)"""")
    private val REGEX_RELEASE_VERSION = Regex(""""releaseVersion"\s*:\s*"([^"]+)"""")
    private val REGEX_API_RANGE = Regex(""""apiRange"\s*:\s*"([^"]+)"""")

    /**
     * What a plugin manifest says, read field by field.
     *
     * A null is an ABSENCE, and the three absences are different facts: a
     * manifest that does not name a plugin, one that does not say its
     * release version, and one that does not declare a range. Only the first
     * one means "this document is not a plugin manifest"; the other two mean
     * "this plugin does not say", which is a conflict.
     */
    data class ParsedPluginManifest(
        val pluginId: String?,
        val releaseVersion: String?,
        val apiRange: String?,
    )
}

/** One bundled plugin observed in the distribution ZIP. */
data class BundledPluginEntry(
    val pluginId: String,
    val releaseVersion: String,
    /**
     * The range the plugin declares, or null when its manifest declares none.
     *
     * Null is not "no constraint": it is "nothing shows that this plugin
     * admits the host", which is a conflict, not a permission. An empty string
     * is a different fact — a range the parser cannot read — and it is
     * refused for a different reason.
     */
    val apiRange: String?,
    val jarName: String,
)

/**
 * Result of the bundled-plugin compatibility check. Distinct from
 * [DistributionIdentityVerdict] on purpose: identity is equality of strings,
 * compatibility is containment of a host version in each plugin's range.
 * Conflating them would have hidden the junit@0.1.0 / host@0.51.0 case
 * (different `releaseVersion`, same `apiRange`-membership) as a divergence.
 */
sealed interface BundledPluginCompatibilityVerdict {

    /**
     * The distribution carried no bundled plugin manifests. Refusing on
     * absence is the fail-closed default: a distribution that does not
     * ship its official plugins is missing required surface, not a green
     * candidate with no plugins to check.
     */
    data class NoPluginsFound(val expectedHost: ProductVersion) : BundledPluginCompatibilityVerdict

    /** Every bundled plugin admits [expectedHost]. */
    data class Compatible(
        val expectedHost: ProductVersion,
        val plugins: List<BundledPluginEntry>,
    ) : BundledPluginCompatibilityVerdict

    /**
     * At least one bundled plugin's [BundledPluginEntry.apiRange] excludes
     * [expectedHost]. [conflicts] lists every offender so the operator
     * sees the full set in one diagnostic, not one round-trip per plugin.
     */
    data class Incompatible(
        val expectedHost: ProductVersion,
        val conflicts: List<PluginRangeConflict>,
    ) : BundledPluginCompatibilityVerdict
}

data class PluginRangeConflict(
    val pluginId: String,
    val releaseVersion: String,
    /** The declared range, or null when the plugin declared none at all. */
    val apiRange: String?,
    val hostVersion: String,
)

/** Operator-facing render. Kept separate from the verdict for symmetry with identity. */
fun BundledPluginCompatibilityVerdict.render(): String = when (this) {
    is BundledPluginCompatibilityVerdict.NoPluginsFound ->
        "bundled-plugin compatibility INCOMPLETE: expected host $expectedHost but no " +
            "META-INF/pipelinek/plugin-manifest.json was found inside any pipeline-step-sdk-*.jar " +
            "in the distribution. A distribution that ships no official plugins is missing " +
            "required surface and MUST NOT be admitted."

    is BundledPluginCompatibilityVerdict.Compatible ->
        "bundled-plugin compatibility: $expectedHost admitted by ${plugins.size} bundled " +
            "plugin(s) (${plugins.joinToString { "${it.pluginId}@${it.releaseVersion} " + "api=${it.apiRange}" }})"

    is BundledPluginCompatibilityVerdict.Incompatible -> buildString {
        appendLine("bundled-plugin compatibility FAILED for host $expectedHost:")
        conflicts.forEach {
            val declared = it.apiRange?.takeIf { range -> range.isNotBlank() }
            if (declared == null) {
                // A plugin that declares NO range is a different fact from one
                // that declares a range the host is outside of, and it needs a
                // different fix: there is nothing to widen, the manifest has to
                // say which hosts the plugin admits.
                appendLine(
                    "  - ${it.pluginId} declares no apiRange, so nothing shows that it admits " +
                        "host version '${it.hostVersion}'",
                )
            } else {
                appendLine(
                    "  - ${it.pluginId}@${it.releaseVersion} declares apiRange='$declared' " +
                        "which excludes host version '${it.hostVersion}'",
                )
            }
        }
        append(
            "A candidate whose bundled plugins refuse its own host version is a build " +
                "defect and MUST NOT be admitted (fail-closed: plugins must admit the " +
                "shipped host). Re-extend the plugin's apiRange if the contract genuinely " +
                "covers the host, or rebuild the plugin against a compatible host.",
        )
    }
}
