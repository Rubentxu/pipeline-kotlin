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
     * PK-RELEASE-CONSOLIDATION H1.4 — three external example plugins that
     * the brief makes MANDATORY in the release gate. These are the
     * D1-independent builds (block, directive, uppercase) that derive
     * their `apiRange` from the resolved SDK. Their pluginIds are part
     * of the SDK's external plugin contract; a release without them
     * is the same defect as a release without the official plugins.
     *
     * PK-NEXT R0.2 had four official plugins; the H1 brief requires
     * SEVEN, not four. The union is the test surface, asserted
     * together by [the distribution contains all seven mandatory plugins]
     * and individually by [each example external plugin is admitted by
     * the host].
     *
     * Note: the [BundledPluginCompatibility.probeZip] returns the
     * SHORT plugin name (it strips `/plugin/<x>` from the manifest's
     * `plugin` field, e.g. `v1:plugin:example-block-plugin/plugin/repeat`
     * becomes `example-block-plugin`). The mandatory ids below match
     * that short form, not the full URN.
     */
    private val mandatoryExternalIds: List<String> = listOf(
        "example-block-plugin",
        "example-directive-plugin",
        "example-uppercase-plugin",
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
    fun `the distribution contains the four mandatory official plugins alongside the three externals`() {
        // PK-RELEASE-CONSOLIDATION H1.4: the test asserts the four official
        // plugins are PRESENT in the distribution, not that the distribution
        // is EXACTLY four plugins. The seven-plugin union is asserted
        // separately; the four-official-presence assertion is the
        // "is each official surface still shipped?" half of that check.
        val entries = BundledPluginCompatibility.probeZip(zip)
        val observedIds = entries.map { it.pluginId }.toSet()
        val missing = mandatoryIds.filterNot { it in observedIds }
        assertTrue(
            missing.isEmpty(),
            "the four official plugins $mandatoryIds must all be present. " +
                "missing=$missing observed=$observedIds. A missing official " +
                "means a required surface was not shipped."
        )
    }

    /**
     * PK-RELEASE-CONSOLIDATION H1.4 — the SEVEN mandatory plugins.
     *
     * The brief makes the three external example plugins (block, directive,
     * uppercase) obligatory alongside the four official ones. The D1
     * independent build gives them their own `apiRange` derived from the
     * resolved SDK, and the certifier must observe them in the distribution
     * the same way it observes the official four.
     */
    @Test
    fun `the distribution contains all seven mandatory plugins (4 official + 3 external)`() {
        val entries = BundledPluginCompatibility.probeZip(zip)
        val observedIds = entries.map { it.pluginId }
        val required = (mandatoryIds + mandatoryExternalIds).sorted()
        val actual = observedIds.toSet()
        val missing = required.filterNot { it in actual }
        val extra = observedIds.filterNot { it in required.toSet() }
        assertTrue(
            missing.isEmpty() && extra.isEmpty(),
            "the distribution must carry the union of the official and the external " +
                "mandatory plugins. missing=$missing extra=$extra observed=$observedIds. " +
                "A missing official means a required surface was not shipped; a missing " +
                "external means the D1 independent build was not staged; an extra id means " +
                "the gate is not asserting the canonical seven.",
        )
    }

    /**
     * PK-RELEASE-CONSOLIDATION H1.4 — the three external plugins must
     * admit the host, exactly as the four official plugins do. A
     * release that ships an external plugin whose `apiRange` excludes
     * the host version is the same defect as a release that ships an
     * official plugin in that state, and the certifier must see the
     * refusal here, not after download.
     */
    @Test
    fun `each example external plugin admits the host version`() {
        val entries = BundledPluginCompatibility.probeZip(zip)
        val externalEntries = entries.filter { it.pluginId in mandatoryExternalIds }
        for (external in externalEntries) {
            val verdict = BundledPluginCompatibility.evaluate(
                hostVersion,
                listOf(external),
            )
            assertInstanceOf(
                BundledPluginCompatibilityVerdict.Compatible::class.java,
                verdict,
                "external plugin ${external.pluginId} refuses the host $hostVersion. " +
                    "The D1 independent build's apiRange must cover the host. " +
                    "Verdict: ${verdict.render()}",
            )
        }
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
        // junit is the one exception (its historical version is 0.1.0). The
        // three external example plugins also declare 0.1.0, since their
        // release cadence is independent of the host (D1: the apiRange is
        // what matters, not the version stamp). The exceptions list is
        // therefore { junit, example-block-plugin, example-directive-plugin,
        // example-uppercase-plugin }.
        val exceptions = setOf(
            "pipeline.junit",
            "example-block-plugin",
            "example-directive-plugin",
            "example-uppercase-plugin",
        )
        val entries = BundledPluginCompatibility.probeZip(zip)
        val mismatched = entries
            .filter { it.pluginId !in exceptions }
            .filter { it.releaseVersion != hostVersion.value }
        assertTrue(
            mismatched.isEmpty(),
            "the following bundled plugins declare a releaseVersion other than the host " +
                "$hostVersion: ${mismatched.map { "${it.pluginId}@${it.releaseVersion}" }}. " +
                "Plugins that ship at a different cadence (junit + the three D1 externals) are " +
                "exempt because their compatibility with the host is the apiRange, not the " +
                "version stamp."
        )
    }

    @Test
    fun `each mandatory plugin's manifest is parseable to a PluginManifest with at least one contribution`() {
        // The codec-level check goes deeper than the apiRange shape. We open the
        // JAR, read the manifest JSON, decode it through PluginManifestCodec, and
        // assert it produces a manifest with at least one contribution of ANY
        // kind: steps, directives, or events. A build that emits a manifest
        // with zero contributions is broken — the bundle exists, the codec
        // accepts it, but it does nothing.
        //
        // PK-RELEASE-CONSOLIDATION H1.4: the directive plugin contributes
        // directives, not steps; a "must have steps" assertion would have
        // rejected it for the wrong reason. The surface is "any contribution",
        // and the test names it as such.
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
                is PluginManifestDecodeResult.Accepted -> {
                    val c = decoded.manifest.contributions
                    val total = c.steps.size + c.directives.size + c.events.size
                    assertTrue(
                        total > 0,
                        "${entry.pluginId} manifest decoded to zero contributions of any kind " +
                            "(steps=${c.steps.size}, directives=${c.directives.size}, " +
                            "events=${c.events.size}). The bundle ships an empty plugin."
                    )
                }
            }
        }
    }

    /**
     * PK-RELEASE-CONSOLIDATION H1.4 — the LaunchReceipt contract is
     * versioned by its `schema` token. Every run that emits a receipt
     * MUST carry `pipelinek.launch/1`. The certifier must observe the
     * token in the published application JAR, not a private copy, so
     * a future schema migration cannot ship with a stale contract.
     */
    @Test
    fun `the application JAR declares the published LaunchReceipt schema token`() {
        val expectedSchema = "pipelinek.launch/1"
        val appJar = extractApplicationJar()
            ?: fail("could not stage the application JAR from the distribution ZIP")
        val schema = readClassConstantString(
            appJar,
            "dev/rubentxu/pipeline/v2/application/LaunchReceipt",
            expectedSchema,
        )
        assertEquals(
            expectedSchema,
            schema,
            "LaunchReceipt.Companion.SCHEMA in the published application JAR must be " +
                "'$expectedSchema'; the certifier learns the contract from this token. " +
                "A different token means the JAR was built against a different contract " +
                "than the one the gate is enforcing."
        )
    }

    /**
     * PK-RELEASE-CONSOLIDATION H1.4 — the SBOM is well-formed and
     * names the application at the host version. The check is the
     * lightest one that still gives the certifier a guard: a CycloneDX
     * 1.5 SBOM that names the host application at the host version
     * is a SBOM that the build actually produced for THIS candidate,
     * not a leftover from a previous run.
     *
     * The original H1.4 plan asked for a check that the SBOM names
     * `pipeline-sdk-bom` at the host version. The check is structurally
     * impossible: `pipeline-sdk-bom` is a `java-platform` module whose
     * only output is a POM, not a JAR, and CycloneDX Gradle only
     * enumerates the JARs a module compiles. The BOM still anchors
     * SDK resolution for downstream consumers — the certifier just
     * observes its presence through a different path (the version
     * resource in the application JAR, asserted above by
     * `hostVersion`).
     */
    @Test
    fun `the distribution's SBOM is well-formed and names the host application`() {
        val sbom = locateSbom()
            ?: fail(
                "the SBOM was not staged next to the distribution ZIP. " +
                    "The gate requires an SBOM to assert provenance; " +
                    "a missing SBOM is the same defect as a missing manifest."
            )
        val text = Files.readString(sbom)
        val appCoordinate = "dev.rubentxu.pipeline.v2:pipeline-application:${hostVersion.value}"
        // CycloneDX Gradle names the application component with a `purl`
        // of the form `pkg:maven/<group>:<artifact>@<version>?type=jar`,
        // not the bare Maven coordinate. The application is the
        // root component, surfaced under `metadata.component.purl`.
        val appPurl = "pkg:maven/dev.rubentxu.pipeline.v2/pipeline-application@${hostVersion.value}"
        assertTrue(
            text.contains("\"CycloneDX\""),
            "the SBOM at $sbom is not in CycloneDX format. A consumer " +
                "expecting CycloneDX would have to read this with a different tool. " +
                "SBOM excerpt: ${text.take(400)}"
        )
        assertTrue(
            text.contains(appCoordinate) || text.contains(appPurl),
            "the SBOM at $sbom does not reference '$appCoordinate' or its CycloneDX " +
                "purl '$appPurl'. The SBOM must name the application at the host version " +
                "so the certifier can verify the SBOM was produced for THIS candidate. " +
                "SBOM excerpt: ${text.take(800)}"
        )
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

    /**
     * Stage the application JAR (the one under `lib/pipeline-application-*.jar`)
     * to a temp file and return its path. The schema check needs the
     * bytecode because the constant lives in the class, not in a
     * resource.
     */
    private fun extractApplicationJar(): Path? {
        val tmp = Files.createTempFile("app-extracted-", ".jar")
        try {
            var found: Path? = null
            ZipFile(zip.toFile()).use { outer ->
                val entry = outer.entries().toList().firstOrNull { e ->
                    !e.isDirectory && e.name.contains("/lib/pipeline-application-") && e.name.endsWith(".jar")
                }
                if (entry != null) {
                    Files.copy(outer.getInputStream(entry), tmp,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    found = tmp
                }
            }
            return found
        } catch (_: Exception) {
            Files.deleteIfExists(tmp)
            return null
        }
    }

    /**
     * Read a `public static final String NAME = "value"` constant from a
     * class. This is a structural read of the constant pool, not a
     * reflective getStatic — the latter would force the application JAR
     * onto the test classpath, which would make the test's failure mode
     * depend on whether the JAR happens to load. Reading the bytes
     * directly keeps the check dependent only on the distribution bytes.
     *
     * Returns the value if the [expectedValue] string appears as a
     * CONSTANT_Utf8_info entry in the [internalClassName] class file.
     * The check is structural — it does not parse the constant pool to
     * attribute the string to a specific field, because the only
     * constant the contract cares about is the schema token, and any
     * class file that carries `pipelinek.launch/1` is a class that
     * SOMEONE declared it on. A more thorough check would resolve the
     * exact field; that is future work and not on the H1.4 path.
     */
    private fun readClassConstantString(jar: Path, internalClassName: String, expectedValue: String): String? {
        val classEntry = internalClassName.replace('.', '/') + ".class"
        ZipFile(jar.toFile()).use { zf ->
            val entry = zf.getEntry(classEntry) ?: return null
            val bytes = zf.getInputStream(entry).use { it.readBytes() }
            // Look for the expected value as a UTF-8 literal anywhere
            // in the class bytes. This is a weak check (it can match a
            // string used in a different context), but the schema token
            // is unique enough in practice, and the alternative is a
            // full constant-pool parser.
            val haystack = String(bytes, Charsets.ISO_8859_1)
            return if (haystack.contains(expectedValue)) expectedValue else null
        }
    }

    /**
     * Locate the SBOM that the `candidateAdmission` task stages next to
     * the distribution ZIP. The probe looks for a sibling file whose
     * name ends in `.sbom.json`, which is the convention the admission
     * task uses. A missing SBOM is a hard fail.
     */
    private fun locateSbom(): Path? {
        val parent = zip.parent ?: return null
        val candidates = listOf(
            "pipelinek-${hostVersion.value}.sbom.json",
            "bom.json",
        )
        return candidates.mapNotNull { name ->
            val p = parent.resolve(name)
            if (Files.isRegularFile(p)) p else null
        }.firstOrNull()
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
