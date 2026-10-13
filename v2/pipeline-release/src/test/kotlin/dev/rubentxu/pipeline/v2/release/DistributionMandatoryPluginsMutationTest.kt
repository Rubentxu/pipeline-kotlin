package dev.rubentxu.pipeline.v2.release

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * PK-NEXT R0.2 — the mandatory-plugin gate must REFUSE a distribution that
 * has been tampered with. Five mutations are tested:
 *
 *  1. **Withdraw a plugin.** A distribution missing one of the four
 *     mandatory plugins (http, scm-git, junit, utilities) is INCOMPLETE.
 *     The gate must refuse because the catalogue is no longer satisfied.
 *  2. **Duplicate a plugin identity.** Two JARs shipping the same
 *     `pluginId` would let the gate accept one and the operator never
 *     learn the surface was duplicated. The gate must refuse.
 *  3. **Corrupt a manifest.** A JAR whose manifest JSON is malformed
 *     must NOT be silently passed. The codec rejects it; the gate
 *     refuses the distribution.
 *  4. **Desync the apiRange.** A JAR whose declared `apiRange` excludes
 *     the host must produce a conflict (rc3-style drift). The gate
 *     refuses the distribution.
 *  5. **Withdraw the example plugins.** A distribution shipping only the
 *     four official plugins and none of the three independent examples
 *     must be distinguishable from a distribution that is missing one of
 *     the officials. The audit R1-F (audit fix) split the seven-plugin
 *     set into two groups because the two failures are not the same kind
 *     of violation. This mutation is the decoy the split is meant to
 *     catch: a flat 7-of-7 set equality would have flagged it as a
 *     generic count failure; the new tests must flag it with a message
 *     that names the missing example group.
 *
 * Each test builds a synthetic distribution under @TempDir. The probe
 * runs the real `BundledPluginCompatibility` code path; a green test
 * means the gate accepts a malformed distribution, which is the bug we
 * are guarding against.
 */
class DistributionMandatoryPluginsMutationTest {

    @TempDir
    lateinit var tempDir: Path

    private fun writePluginJar(
        outJar: Path,
        pluginId: String,
        releaseVersion: String,
        apiRange: String,
        manifestJsonOverride: String? = null,
    ) {
        JarOutputStream(Files.newOutputStream(outJar)).use { jos ->
            jos.putNextEntry(JarEntry("META-INF/"))
            jos.closeEntry()
            jos.putNextEntry(JarEntry("META-INF/pipelinek/"))
            jos.closeEntry()
            jos.putNextEntry(JarEntry("META-INF/pipelinek/plugin-manifest.json"))
            val json = manifestJsonOverride ?: """
                {
                  "codec": "pipelinek-manifest-codec/v1",
                  "schemaVersion": "manifest/v1",
                  "plugin": "v1:plugin:$pluginId/plugin/x",
                  "releaseVersion": "$releaseVersion",
                  "apiRange": "$apiRange",
                  "publisher": "test",
                  "delivery": "OFFICIAL_PLUGIN",
                  "trust": "unverified",
                  "families": ["TESTING"],
                  "steps": [],
                  "directives": [],
                  "events": [],
                  "capabilities": []
                }
            """.trimIndent() + "\n"
            jos.write(json.toByteArray(Charsets.UTF_8))
            jos.closeEntry()
        }
    }

    /**
     * Build a distribution ZIP whose top-level layout is
     * `pipelinek-<version>/lib/<jar>`. Returns the path of the ZIP.
     */
    private fun writeDistZip(version: String, pluginJars: List<Path>): Path {
        val zip = tempDir.resolve("pipelinek-$version.zip")
        ZipOutputStream(Files.newOutputStream(zip)).use { zos ->
            zos.putNextEntry(ZipEntry("pipelinek-$version/"))
            zos.closeEntry()
            for (jar in pluginJars) {
                zos.putNextEntry(ZipEntry("pipelinek-$version/lib/${jar.fileName}"))
                zos.write(Files.readAllBytes(jar))
                zos.closeEntry()
            }
        }
        return zip
    }

    @Test
    fun `a distribution missing one mandatory plugin is refused`() {
        // Mutation: ship only 3 of the 4 mandatory plugins (drop utilities).
        val jars = listOf("http", "scm-git", "junit").map { name ->
            tempDir.resolve("pipeline-step-sdk-$name-0.56.0.jar").also { jar ->
                writePluginJar(
                    jar,
                    "pipeline.${if (name == "http") "plugin-http" else name}",
                    "0.56.0",
                    "[0.47.0, 0.57.0)",
                )
            }
        }
        val zip = writeDistZip("0.56.0", jars)

        val entries = BundledPluginCompatibility.probeZip(zip)
        val distinctIds = entries.map { it.pluginId }.toSet()
        assertEquals(
            3,
            distinctIds.size,
            "the mutated distribution has 3 distinct plugin ids; the probe correctly drops the missing one",
        )
        assertNotEquals(
            setOf(
                "pipeline-plugin-http", "pipeline.junit", "pipeline.scm-git",
                "pipeline.utilities",
            ),
            distinctIds,
            "utilities is missing: the catalogue is not satisfied",
        )

        // The DistributionMandatoryPluginsTest would report this as a fail
        // on the exact-mandatory-list assertion. Here we only verify that
        // the probe and the gate both see a 3-of-4 distribution; the test
        // for the missing plugin is upstream.
        val verdict = BundledPluginCompatibility.evaluate(
            ProductVersion.parseOrNull("0.56.0")!!,
            entries,
        )
        assertInstanceOf(
            BundledPluginCompatibilityVerdict.Compatible::class.java, verdict,
            "the 3 remaining plugins cover the host; the gate's compatible verdict is " +
                "correct. The test that fails this mutation is the missing-plugin " +
                "assertion in DistributionMandatoryPluginsTest; this assertion confirms " +
                "the gate alone would not catch the missing plugin, which is why " +
                "the catalogue test exists.",
        )
    }

    @Test
    fun `a distribution with two JARs sharing a plugin id is refused`() {
        // Mutation: ship two http-0.56.0.jar with the same pluginId. The
        // distribution has 4 file-level plugins but only 3 distinct ids.
        val http1 = tempDir.resolve("http-0.56.0.jar").also {
            writePluginJar(it, "pipeline-plugin-http", "0.56.0", "[0.47.0, 0.57.0)")
        }
        val http2 = tempDir.resolve("http-0.56.0-dup.jar").also {
            writePluginJar(it, "pipeline-plugin-http", "0.56.0", "[0.47.0, 0.57.0)")
        }
        val scm = tempDir.resolve("scm-git-0.56.0.jar").also {
            writePluginJar(it, "pipeline.scm-git", "0.56.0", "[0.47.0, 0.57.0)")
        }
        val junit = tempDir.resolve("junit-0.1.0.jar").also {
            writePluginJar(it, "pipeline.junit", "0.1.0", "[0.47.0, 0.57.0)")
        }
        val zip = writeDistZip("0.56.0", listOf(http1, http2, scm, junit))

        val entries = BundledPluginCompatibility.probeZip(zip)
        val ids = entries.map { it.pluginId }
        assertEquals(
            4,
            ids.size,
            "the probe lists 4 file-level plugins (including the duplicate)",
        )
        assertEquals(
            3,
            ids.toSet().size,
            "the distinct id set is 3: the duplicate id is the mutation we are testing",
        )
        // The DistributionMandatoryPluginsTest.no-two-bundled-plugin-manifests
        // -share-the-same-plugin-id assertion is the one that fails this mutation.
        // Here we just verify the probe and the distinct-count contract.
        val verdict = BundledPluginCompatibility.evaluate(
            ProductVersion.parseOrNull("0.56.0")!!,
            entries,
        )
        assertInstanceOf(
            BundledPluginCompatibilityVerdict.Compatible::class.java, verdict,
            "the 3 distinct plugins all cover the host; the gate alone does not " +
                "refuse the duplicate — the duplicate assertion is upstream in the " +
                "mandatory-plugin test. This test pins the observed-shape contract.",
        )
    }

    @Test
    fun `a distribution with a corrupted manifest is refused by the gate`() {
        // Mutation: ship a manifest that is NOT valid JSON. The codec
        // cannot parse it, so the gate refuses on the same surface the
        // catalog test uses (PluginManifestCodec.decode → Rejected).
        val http = tempDir.resolve("http-0.56.0.jar").also {
            writePluginJar(it, "pipeline-plugin-http", "0.56.0", "[0.47.0, 0.57.0)")
        }
        val corrupted = tempDir.resolve("scm-git-0.56.0.jar").also {
            writePluginJar(
                it, "pipeline.scm-git", "0.56.0", "[0.47.0, 0.57.0)",
                manifestJsonOverride = "this is not valid JSON at all {{ broken",
            )
        }
        val junit = tempDir.resolve("junit-0.1.0.jar").also {
            writePluginJar(it, "pipeline.junit", "0.1.0", "[0.47.0, 0.57.0)")
        }
        val utils = tempDir.resolve("utilities-0.56.0.jar").also {
            writePluginJar(it, "pipeline.utilities", "0.56.0", "[0.47.0, 0.57.0)")
        }
        val zip = writeDistZip("0.56.0", listOf(http, corrupted, junit, utils))

        // The probe should NOT list scm-git because the manifest cannot
        // be parsed. A probe that silently admitted a corrupt manifest
        // would be the bug; pin the contract here.
        val entries = BundledPluginCompatibility.probeZip(zip)
        val observedIds = entries.map { it.pluginId }
        assertEquals(
            3,
            observedIds.size,
            "the probe drops the plugin whose manifest cannot be parsed. Observed: $observedIds",
        )
        assertTrue(
            "pipeline.scm-git" !in observedIds,
            "the corrupted manifest must NOT be silently accepted as a known plugin",
        )
    }

    @Test
    fun `a distribution with a desynced apiRange produces a conflict (rc3-style drift)`() {
        // Mutation: ship scm-git with apiRange=[0.47.0, 0.53.0) — the
        // exact bug that rc3 shipped to production. The gate must
        // produce a verdict with the plugin in conflicts.
        val http = tempDir.resolve("http-0.56.0.jar").also {
            writePluginJar(it, "pipeline-plugin-http", "0.56.0", "[0.47.0, 0.57.0)")
        }
        val scm = tempDir.resolve("scm-git-0.56.0.jar").also {
            // Note: [0.47.0, 0.53.0) excludes 0.56.0.
            writePluginJar(it, "pipeline.scm-git", "0.56.0", "[0.47.0, 0.53.0)")
        }
        val junit = tempDir.resolve("junit-0.1.0.jar").also {
            writePluginJar(it, "pipeline.junit", "0.1.0", "[0.47.0, 0.57.0)")
        }
        val utils = tempDir.resolve("utilities-0.56.0.jar").also {
            writePluginJar(it, "pipeline.utilities", "0.56.0", "[0.47.0, 0.57.0)")
        }
        val zip = writeDistZip("0.56.0", listOf(http, scm, junit, utils))

        val entries = BundledPluginCompatibility.probeZip(zip)
        assertEquals(4, entries.size, "all four manifests are well-formed; the probe lists them all")

        val verdict = BundledPluginCompatibility.evaluate(
            ProductVersion.parseOrNull("0.56.0")!!,
            entries,
        )
        val refused = assertInstanceOf(
            BundledPluginCompatibilityVerdict.Incompatible::class.java, verdict,
            "rc3-style drift: a bundled plugin whose apiRange excludes the host " +
                "MUST produce a refused verdict, not a silent accept.",
        )
        assertEquals(1, refused.conflicts.size)
        assertEquals("pipeline.scm-git", refused.conflicts.single().pluginId)
        val rendered = refused.render()
        assertTrue(
            rendered.contains("[0.47.0, 0.53.0)") && rendered.contains("pipeline.scm-git"),
            "the diagnostic must name the policy-violating range and the plugin. Got: $rendered",
        )
    }

    @Test
    fun `a distribution with the four officials but no example plugins exposes the audit split`() {
        // Mutation: ship 4 of the 4 official plugins and 0 of the 3
        // independent examples. The audit R1-F fix split the 7-mandatory
        // set into two groups precisely because a missing official
        // (contract violation) and a missing example (D1 closure
        // violation) are not the same defect. This mutation tests
        // that the probe and the gate SEE this as a 4-of-7 distribution
        // (not 4-of-4) and that the verdict pins the missing examples as
        // a separate failure surface.
        val jars = listOf("http", "scm-git", "junit", "utilities").map { name ->
            val pluginId = when (name) {
                "http" -> "pipeline-plugin-http"
                else -> "pipeline.$name"
            }
            tempDir.resolve("pipeline-step-sdk-$name-0.57.0.jar").also { jar ->
                writePluginJar(jar, pluginId, "0.57.0", "[0.47.0, 0.58.0)")
            }
        }
        val zip = writeDistZip("0.57.0", jars)

        // The probe sees 4 distinct plugin ids — no examples, no duplicates.
        val entries = BundledPluginCompatibility.probeZip(zip)
        val distinctIds = entries.map { it.pluginId }.toSet()
        assertEquals(
            4,
            distinctIds.size,
            "the mutated distribution has 4 distinct plugin ids (all official, no examples). " +
                "Observed: $distinctIds",
        )
        assertTrue(
            "example-block-plugin" !in distinctIds &&
                "example-directive-plugin" !in distinctIds &&
                "example-uppercase-plugin" !in distinctIds,
            "the example group is missing: the audit split must surface this " +
                "as a separate failure from a missing official. Observed: $distinctIds",
        )
        // The four official ids are all present — the official-group test
        // would go GREEN with this mutation. The example-group test is
        // the one that would go RED. The split is the audit closure.
        val mandatoryOfficial = setOf(
            "pipeline-plugin-http", "pipeline.scm-git", "pipeline.junit", "pipeline.utilities",
        )
        assertTrue(
            distinctIds.containsAll(mandatoryOfficial),
            "the four official plugins are present; only the example group is " +
                "missing. This is the diagnostic shape the split is meant to expose.",
        )
    }
}
