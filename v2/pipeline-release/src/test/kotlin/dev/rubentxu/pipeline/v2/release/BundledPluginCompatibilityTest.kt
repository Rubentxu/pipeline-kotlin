package dev.rubentxu.pipeline.v2.release

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
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
 * PK-R1 R1-C — the bundled-plugin compatibility gate is fail-closed at the
 * candidate boundary.
 *
 * The three observable properties tested here:
 *
 *  1. A plugin whose `apiRange` covers the host is admitted (positive).
 *  2. A plugin whose `apiRange` excludes the host is refused with a
 *     diagnostic that names the plugin, its range, and the host version
 *     the build declared (negative).
 *  3. A distribution that ships no bundled plugin manifests is refused
 *     on the same terms — absence is not a green light, it is missing
 *     required surface.
 *
 * The synthetic ZIP is small and built in a `@TempDir` so a test cannot
 * silently inherit a real distribution's bytes.
 */
class BundledPluginCompatibilityTest {

    @TempDir
    lateinit var tempDir: Path

    private fun productVersion(raw: String): ProductVersion =
        ProductVersion.parseOrNull(raw) ?: error("not a final product version: $raw")

    private fun writePluginJar(
        outJar: Path,
        pluginId: String,
        releaseVersion: String,
        apiRange: String,
    ) {
        JarOutputStream(Files.newOutputStream(outJar)).use { jos ->
            jos.putNextEntry(JarEntry("META-INF/"))
            jos.closeEntry()
            jos.putNextEntry(JarEntry("META-INF/pipelinek/"))
            jos.closeEntry()
            jos.putNextEntry(JarEntry("META-INF/pipelinek/plugin-manifest.json"))
            jos.write(
                (
                    """
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
                    ).toByteArray(Charsets.UTF_8),
            )
            jos.closeEntry()
        }
    }

    private fun writeDistZip(zip: Path, pluginJars: List<Path>) {
        ZipOutputStream(Files.newOutputStream(zip)).use { zos ->
            zos.putNextEntry(ZipEntry("pipelinek-0.51.0/"))
            zos.closeEntry()
            for (jar in pluginJars) {
                zos.putNextEntry(ZipEntry("pipelinek-0.51.0/lib/${jar.fileName}"))
                zos.write(Files.readAllBytes(jar))
                zos.closeEntry()
            }
        }
    }

    // -------------------------------------------- positive (compatible) cases

    @Test
    fun `a plugin whose apiRange covers the host is admitted`() {
        val plugin = tempDir.resolve("pipeline-step-sdk-http-0.51.0.jar")
        writePluginJar(plugin, "pipeline-plugin-http", "0.51.0", "[0.47.0, 0.52.0)")

        val entries = BundledPluginCompatibility.probeZip(plugin)
        assertEquals(1, entries.size, "exactly one bundled plugin should be observed")
        assertEquals("[0.47.0, 0.52.0)", entries.single().apiRange)

        val verdict = BundledPluginCompatibility.evaluate(productVersion("0.51.0"), entries)
        assertInstanceOf(BundledPluginCompatibilityVerdict.Compatible::class.java, verdict)
    }

    @Test
    fun `a plugin at a different releaseVersion but covering the host is admitted`() {
        // The whole point of the gate: junit is intentionally 0.1.0 while the
        // host is 0.51.0. The check must NOT mistake the version gap for an
        // admission failure; what it asserts is apiRange membership.
        val plugin = tempDir.resolve("pipeline-step-sdk-junit-0.1.0.jar")
        writePluginJar(plugin, "pipeline.junit", "0.1.0", "[0.47.0, 0.52.0)")

        val entries = BundledPluginCompatibility.probeZip(plugin)
        val verdict = BundledPluginCompatibility.evaluate(productVersion("0.51.0"), entries)
        assertInstanceOf(BundledPluginCompatibilityVerdict.Compatible::class.java, verdict)
    }

    @Test
    fun `a plugin whose range upper bound is the host version refuses the host`() {
        // The upper bound is EXCLUSIVE. A range [0.47.0, 0.51.0) does NOT
        // admit 0.51.0; an unaware gate that treated it as inclusive would
        // have let the host through silently.
        val plugin = tempDir.resolve("pipeline-step-sdk-x-0.51.0.jar")
        writePluginJar(plugin, "pipeline-x", "0.51.0", "[0.47.0, 0.51.0)")

        val entries = BundledPluginCompatibility.probeZip(plugin)
        val verdict = BundledPluginCompatibility.evaluate(productVersion("0.51.0"), entries)
        val refused = assertInstanceOf(BundledPluginCompatibilityVerdict.Incompatible::class.java, verdict)
        assertEquals(1, refused.conflicts.size)
        assertEquals("pipeline-x", refused.conflicts.single().pluginId)
        assertTrue(
            refused.render().contains("[0.47.0, 0.51.0)") &&
                refused.render().contains("pipeline-x") &&
                refused.render().contains("0.51.0"),
            "diagnostic must name the plugin, its range, and the host version",
        )
    }

    @Test
    fun `a plugin whose range lower bound is greater than the host refuses the host`() {
        val plugin = tempDir.resolve("pipeline-step-sdk-y-0.60.0.jar")
        writePluginJar(plugin, "pipeline-y", "0.60.0", "[0.55.0, 0.65.0)")

        val entries = BundledPluginCompatibility.probeZip(plugin)
        val verdict = BundledPluginCompatibility.evaluate(productVersion("0.51.0"), entries)
        assertInstanceOf(BundledPluginCompatibilityVerdict.Incompatible::class.java, verdict)
    }

    @Test
    fun `a malformed apiRange is treated as a refusal, not as compatibility`() {
        // An unknown range is not containable. The gate must fail closed so
        // a corrupted manifest cannot masquerade as compatibility.
        val plugin = tempDir.resolve("pipeline-step-sdk-z-0.51.0.jar")
        writePluginJar(plugin, "pipeline-z", "0.51.0", "not-a-range")

        val entries = BundledPluginCompatibility.probeZip(plugin)
        val verdict = BundledPluginCompatibility.evaluate(productVersion("0.51.0"), entries)
        assertInstanceOf(BundledPluginCompatibilityVerdict.Incompatible::class.java, verdict)
    }

    @Test
    fun `a distribution with no bundled plugin manifests is refused`() {
        val zip = tempDir.resolve("pipelinek-0.51.0.zip")
        writeDistZip(zip, emptyList())

        val entries = BundledPluginCompatibility.probeZip(zip)
        assertTrue(entries.isEmpty(), "the synthetic distribution has no plugin manifests")

        val verdict = BundledPluginCompatibility.evaluate(productVersion("0.51.0"), entries)
        val refused = assertInstanceOf(BundledPluginCompatibilityVerdict.NoPluginsFound::class.java, verdict)
        assertTrue(
            refused.render().contains("INCOMPLETE"),
            "absence is an incomplete verdict, not a green light: ${refused.render()}",
        )
    }

    @Test
    fun `a distribution with one good and one refusing plugin is refused once with both conflicts`() {
        val good = tempDir.resolve("pipeline-step-sdk-http-0.51.0.jar")
        writePluginJar(good, "pipeline-plugin-http", "0.51.0", "[0.47.0, 0.52.0)")
        val refusing = tempDir.resolve("pipeline-step-sdk-scm-git-0.51.0.jar")
        writePluginJar(refusing, "pipeline-plugin-scm-git", "0.51.0", "[0.50.0, 0.51.0)")

        val zip = tempDir.resolve("pipelinek-0.51.0.zip")
        writeDistZip(zip, listOf(good, refusing))

        val entries = BundledPluginCompatibility.probeZip(zip)
        assertEquals(2, entries.size)

        val verdict = BundledPluginCompatibility.evaluate(productVersion("0.51.0"), entries)
        val refused = assertInstanceOf(BundledPluginCompatibilityVerdict.Incompatible::class.java, verdict)
        assertEquals(1, refused.conflicts.size, "only the refusing plugin is listed, the good one is silent")
        assertEquals("pipeline-plugin-scm-git", refused.conflicts.single().pluginId)
    }

    // -------------------------------------------- pure-function surface (apiRange)

    @Test
    fun `apiRange contains is inclusive on the lower and exclusive on the upper bound`() {
        assertTrue(BundledPluginCompatibility.apiRangeContains("[0.47.0, 0.52.0)", "0.47.0"))
        assertTrue(BundledPluginCompatibility.apiRangeContains("[0.47.0, 0.52.0)", "0.51.0"))
        assertTrue(BundledPluginCompatibility.apiRangeContains("[0.47.0, 0.52.0)", "0.51.999"))
        assertTrue(!BundledPluginCompatibility.apiRangeContains("[0.47.0, 0.52.0)", "0.46.999"))
        assertTrue(!BundledPluginCompatibility.apiRangeContains("[0.47.0, 0.52.0)", "0.52.0"))
    }

    @Test
    fun `apiRange contains is null-safe and refuses on missing or malformed ranges`() {
        assertTrue(!BundledPluginCompatibility.apiRangeContains(null, "0.51.0"))
        assertTrue(!BundledPluginCompatibility.apiRangeContains("", "0.51.0"))
        assertTrue(!BundledPluginCompatibility.apiRangeContains("0.47.0..0.52.0", "0.51.0"))
        assertTrue(!BundledPluginCompatibility.apiRangeContains("[0.47.0, 0.52.0", "0.51.0"))
    }

    @Test
    fun `semver compare is numeric and order-correct`() {
        assertTrue(BundledPluginCompatibility.semverCompare("0.51.0", "0.50.0") > 0)
        assertTrue(BundledPluginCompatibility.semverCompare("0.51.0", "0.51.0") == 0)
        assertTrue(BundledPluginCompatibility.semverCompare("0.51.0", "0.51.1") < 0)
        assertTrue(BundledPluginCompatibility.semverCompare("0.51.10", "0.51.2") > 0)
    }
}
