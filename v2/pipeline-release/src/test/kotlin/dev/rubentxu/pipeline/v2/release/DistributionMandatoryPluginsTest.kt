package dev.rubentxu.pipeline.v2.release

import dev.rubentxu.pipeline.v2.domain.step.PluginManifestCodec
import dev.rubentxu.pipeline.v2.domain.step.PluginManifestDecodeResult
import dev.rubentxu.pipeline.v2.domain.step.SemVer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.ZipFile

/**
 * PK-RC-PLUGIN-001 / R0.2 — the distribution must carry exactly the four
 * mandatory official plugins, each admitting the running version.
 *
 * <h2>Why this test exists</h2>
 *
 * The v0.56.0-rc3 candidate was published with four bundled plugins whose
 * `apiRange=[0.47.0, 0.53.0)` excluded the host `0.56.0`. The
 * `BundledPluginCompatibility` gate would have refused that ZIP, but the
 * release script never invoked the gate against the published artefact.
 *
 * This test makes the gate observable on the real distribution.
 *
 * <h2>Locating the ZIP</h2>
 *
 * The path is read from the env var `PIPELINEK_DIST_ZIP` and the JVM system
 * property `release.dist.zip` (in that order). The test JVM inherits
 * Gradle's system properties, so a properly wired gate passes the path as
 * a system property. The env var is the public surface for an external
 * operator running the test by hand.
 *
 * <h2>Hard fail, never skip</h2>
 *
 * PK-NEXT/R0.2: a missing distribution is a build defect, not a green
 * signal. When neither env var nor system property is set, the test class
 * FAILS its initialisation rather than skipping the whole class. The
 * @EnabledIfEnvironmentVariable guard that made the previous version
 * silently green has been removed.
 */
class DistributionMandatoryPluginsTest {

    private val zip: Path = resolveZip()

    /**
     * Resolve the distribution ZIP from env or system property. Fails the
     * test class (not the JVM) when neither is set, so the gate produces
     * a hard red for a missing distZip rather than a misleading green.
     */
    private fun resolveZip(): Path {
        val fromEnv = System.getenv("PIPELINEK_DIST_ZIP")
        val fromProp = System.getProperty("release.dist.zip")
        val resolved = fromEnv ?: fromProp
        if (resolved.isNullOrEmpty()) {
            fail<Path>(
                "PIPELINEK_DIST_ZIP (env) or release.dist.zip (system property) is unset. " +
                    "DistributionMandatoryPluginsTest is a mandatory gate: it MUST observe the " +
                    "distZip the same gate just built. Wire it in the test task, not as an " +
                    "@EnabledIfEnvironmentVariable skip.",
            )
        }
        val p = Paths.get(resolved)
        check(Files.isRegularFile(p)) {
            "PIPELINEK_DIST_ZIP=$resolved does not point to a regular file"
        }
        return p
    }

    /**
     * The four mandatory plugins the distribution must ship, in
     * alphabetic order (which is what `BundledPluginCompatibility.probeZip`
     * returns — the probe sorts by id before returning). The plugin ids
     * themselves are part of the published SDK contract; a 0.57.0 build
     * must carry the same identities, in the same order.
     */
    private val mandatoryIds: List<String> = listOf(
        "pipeline-plugin-http",
        "pipeline.junit",
        "pipeline.scm-git",
        "pipeline.utilities",
    )

    /**
     * The host version is derived from the version resource packaged
     * inside the application JAR, not a hardcoded literal. The resource
     * is `pipelinek-version.properties`, generated at build time from
     * `rootProject.version`. Parsing it back keeps the test runnable
     * against 0.56.0, 0.57.0, 0.58.0-rc1, etc., without a 0.56.0
     * literal baked into the test source.
     */
    private val hostVersion: ProductVersion by lazy {
        val resource = "dev/rubentxu/pipeline/v2/application/pipelinek-version.properties"
        val props = readVersionFromApplicationJar(resource)
            ?: fail(
                "the application JAR in the distribution does not carry $resource; the " +
                    "version identity cannot be verified. Refusing to assert anything against " +
                    "an unknown host version.",
            )
        val version = props.lines()
            .firstOrNull { it.startsWith("version=") }
            ?.removePrefix("version=")
            ?.trim()
            ?: fail("$resource does not declare a version= key")
        ProductVersion.parseOrNull(version)
            ?: fail("version '$version' in $resource is not MAJOR.MINOR.PATCH")
    }

    /**
     * Read a resource packaged inside the application JAR. The
     * application JAR is the one named `pipeline-application-<host>.jar`
     * under `lib/`; its classpath-root prefix matches the source layout,
     * so a resource at `dev/rubentxu/pipeline/v2/application/...` lives
     * at the same path inside the JAR.
     */
    private fun readVersionFromApplicationJar(resource: String): String? {
        val tmp = Files.createTempFile("app-", ".jar")
        var result: String? = null
        try {
            ZipFile(zip.toFile()).use { outer ->
                val jarEntry = outer.entries().toList().firstOrNull { e ->
                    !e.isDirectory && e.name.startsWith("pipelinek-") && e.name.endsWith(".jar") &&
                        e.name.contains("/lib/pipeline-application-")
                }
                if (jarEntry != null) {
                    Files.copy(outer.getInputStream(jarEntry), tmp,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    result = ZipFile(tmp.toFile()).use { inner ->
                        val resEntry = inner.getEntry(resource)
                        resEntry?.let {
                            inner.getInputStream(it).use {
                                it.readBytes().toString(Charsets.UTF_8)
                            }
                        }
                    }
                }
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
        return result
    }

    @Test
    fun `the distribution contains exactly the four mandatory official plugins`() {
        val entries = BundledPluginCompatibility.probeZip(zip)
        val observedIds = entries.map { it.pluginId }
        assertEquals(
            mandatoryIds,
            observedIds,
            "the distribution must carry exactly http, scm-git, junit, utilities, in that " +
                "order. Observed: $observedIds. A missing plugin means a required surface " +
                "was not shipped; an unexpected id means the build is shipping a plugin " +
                "not in the catalogue; a re-ordering means the gate is not asserting the " +
                "canonical list.",
        )
    }

    @Test
    fun `no two bundled plugin manifests share the same plugin id`() {
        val entries = BundledPluginCompatibility.probeZip(zip)
        val ids = entries.map { it.pluginId }
        val distinct = ids.toSet()
        assertEquals(
            distinct.size,
            ids.size,
            "duplicate bundled plugin ids in the distribution: $ids. Two JARs shipping " +
                "the same pluginId would let the gate accept one and the operator never " +
                "learn the surface was duplicated.",
        )
    }

    @Test
    fun `each mandatory plugin's manifest decodes via the canonical codec`() {
        // The codec path is the only one permitted to construct a PluginManifest;
        // the release gate MUST go through it. If the codec cannot parse a manifest,
        // the gate refuses the candidate — and that refusal must be observable here,
        // not bypassed by a parallel regex parser.
        val entries = BundledPluginCompatibility.probeZip(zip)
        for (entry in entries) {
            assertTrue(
                entry.apiRange.startsWith("[") && entry.apiRange.endsWith(")"),
                "${entry.pluginId} declares an unparseable apiRange literal " +
                    "'${entry.apiRange}'; the canonical codec will refuse to construct a " +
                    "PluginManifest from it.",
            )
            val bounds = parseApiRange(entry.apiRange)
                ?: fail("${entry.pluginId} apiRange literal '${entry.apiRange}' is not '[X.Y.Z, A.B.C)'")
            assertTrue(
                bounds.untilMajor * 1_000_000 + bounds.untilMinor * 1_000 + bounds.untilPatch >
                    bounds.fromMajor * 1_000_000 + bounds.fromMinor * 1_000 + bounds.fromPatch,
                "${entry.pluginId} apiRange is non-increasing: " +
                    "${bounds.fromMajor}.${bounds.fromMinor}.${bounds.fromPatch} .. " +
                    "${bounds.untilMajor}.${bounds.untilMinor}.${bounds.untilPatch}",
            )
        }
    }

    @Test
    fun `each mandatory plugin admits the host version derived from the distribution`() {
        val entries = BundledPluginCompatibility.probeZip(zip)
        val verdict = BundledPluginCompatibility.evaluate(hostVersion, entries)
        assertInstanceOf(
            BundledPluginCompatibilityVerdict.Compatible::class.java,
            verdict,
            "at least one bundled plugin refuses the host $hostVersion. The build needs " +
                "the policy-derived apiRange to cover the host. Verdict: ${verdict.render()}",
        )
    }

    @Test
    fun `the host version parsed from the distribution's releaseVersion field matches the JAR version`() {
        // 3 of 4 official plugins carry the same product version as the host;
        // junit is the one exception (its historical version is 0.1.0). For the
        // other three, a mismatch is a build defect.
        val entries = BundledPluginCompatibility.probeZip(zip)
        val mismatched = entries
            .filter { it.pluginId != "pipeline.junit" }
            .filter { it.releaseVersion != hostVersion.value }
        assertTrue(
            mismatched.isEmpty(),
            "the following bundled plugins declare a releaseVersion other than the host " +
                "$hostVersion: ${mismatched.map { "${it.pluginId}@${it.releaseVersion}" }}",
        )
    }

    @Test
    fun `each mandatory plugin's manifest is parseable to a PluginManifest with non-empty steps`() {
        // The codec-level check goes deeper than the apiRange shape. We open the
        // JAR, read the manifest JSON, decode it through PluginManifestCodec, and
        // assert it produces a manifest with at least one Step contribution. A
        // build that emits a syntactically valid but semantically empty manifest
        // is broken and the gate must reject it.
        val entries = BundledPluginCompatibility.probeZip(zip)
        for (entry in entries) {
            val manifestJson = readManifestJsonFromJar(entry.jarName)
                ?: fail("the distribution's ${entry.pluginId} JAR does not carry a " +
                    "manifest at the expected path; the codec check requires the manifest " +
                    "bytes to be present")
            when (val decoded = PluginManifestCodec.decode(manifestJson)) {
                is PluginManifestDecodeResult.Rejected -> fail(
                    "${entry.pluginId} manifest REJECTED by the codec: ${decoded.rejection}. " +
                        "The codec must accept a manifest the build emitted; a refusal here " +
                        "means the bundle is unparseable and the gate must reject the candidate.",
                )
                is PluginManifestDecodeResult.Accepted -> assertTrue(
                    decoded.manifest.contributions.steps.isNotEmpty(),
                    "${entry.pluginId} manifest decoded to zero Step contributions. " +
                        "The bundle ships an empty plugin.",
                )
            }
        }
    }

    private fun readManifestJsonFromJar(jarName: String): String? {
        // Stage the inner JAR to a temp file because ZipFile's constructor
        // wants a File or Path. A byte-buffer view works too but file I/O is
        // closer to what a CI lane sees and avoids weirdness with mark/reset.
        val tmp = Files.createTempFile("manifest-", ".jar")
        var result: String? = null
        try {
            ZipFile(zip.toFile()).use { outer ->
                // The distribution ZIP lays out plugin JARs under
                // <root>/lib/<jar>. Locate the entry whose path ENDS with
                // /lib/<jar> rather than hard-coding the root prefix, so the
                // same code works for pipelinek-0.56.0/, pipelinek-0.57.0/,
                // pipelinek-0.58.0-rc1/, etc.
                val suffix = "/lib/$jarName"
                val jarEntry = outer.entries().toList().firstOrNull { e ->
                    !e.isDirectory && e.name.endsWith(suffix)
                }
                if (jarEntry != null) {
                    Files.copy(outer.getInputStream(jarEntry), tmp,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    result = ZipFile(tmp.toFile()).use { inner ->
                        val manifestEntry = inner.getEntry(PluginManifestCodec.RESOURCE_PATH)
                        manifestEntry?.let {
                            inner.getInputStream(it).use {
                                it.readBytes().toString(Charsets.UTF_8)
                            }
                        }
                    }
                }
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
        return result
    }

    // ---- helpers --------------------------------------------------------------------

    private val API_RANGE_REGEX = Regex("""\[\s*(\d+)\.(\d+)\.(\d+)\s*,\s*(\d+)\.(\d+)\.(\d+)\s*\)""")

    private data class ApiRangeBounds(
        val fromMajor: Int, val fromMinor: Int, val fromPatch: Int,
        val untilMajor: Int, val untilMinor: Int, val untilPatch: Int,
    )

    private fun parseApiRange(raw: String): ApiRangeBounds? {
        val m = API_RANGE_REGEX.matchEntire(raw.trim()) ?: return null
        return ApiRangeBounds(
            m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt(),
            m.groupValues[4].toInt(), m.groupValues[5].toInt(), m.groupValues[6].toInt(),
        )
    }
}
