package dev.rubentxu.pipeline.v2.release

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * What the publication gate SEES.
 *
 * [ApiRangeContainsTest] covers what a range MEANS. This file covers the step
 * before it: which plugins the gate looks at at all. That step is the one with
 * the teeth, because a plugin the gate does not see is a plugin the gate
 * cannot refuse.
 *
 * ## The failure this file exists for
 *
 * The gate exists to move one refusal from AFTER the download to BEFORE it: the
 * host refuses a plugin whose declared range does not admit the host version
 * (`PluginAdmission.admit`), and the producer is supposed to catch the same
 * fact at candidate admission, where the operator can still fix the build. The
 * v0.56.0-rc3 incident is the precedent: a hand-typed `apiRange` shipped in a
 * manifest that excluded the host, and `PluginAdmissionGate` would have
 * refused every official plugin on its own distribution.
 *
 * A plugin whose manifest does not declare a range is the same failure wearing
 * a different hat. The SDK's own reader (`PluginCompatibilityResource.load`)
 * refuses to load such a plugin, and the host's admission refuses it too — so
 * the producer's gate has to SEE it and refuse the candidate, not look the
 * other way.
 *
 * ## Why these are bytes and not files
 *
 * This module's test lane has no filesystem, no JAR on disk and no
 * distribution: the JUnit lane that once needed the distribution ZIP and the
 * SBOM staging step executed zero classes while naming a real ZIP in its
 * output. A decision that can only be reached through a 93 MB artefact is a
 * decision nobody exercises, and the one that decides whether a candidate may
 * ship is the last place for that. So the probe is exercised over in-memory
 * JAR bytes.
 */
class BundledPluginProbeTest {

    private val host = ProductVersion.parseOrThrow("0.70.0")

    // --- what is NOT a plugin -------------------------------------------------------------

    @Test
    fun `a jar without a plugin manifest is not a plugin`() {
        // The distribution's `lib/` is full of library JARs. If "no manifest"
        // were read as "a plugin with nothing to say", every library in the
        // distribution would become a conflict and nothing would ever ship.
        // This is the counterweight to the fail-closed rule below: the two
        // must be told apart, and the thing that tells them apart is the
        // manifest's PRESENCE, not its content.
        val bytes = jarOf("META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n")

        assertNull(
            BundledPluginCompatibility.pluginEntryOf(bytes, "pipeline-domain-0.70.0.jar"),
            "a library jar was read as a plugin",
        )
    }

    @Test
    fun `an empty jar is not a plugin`() {
        assertNull(BundledPluginCompatibility.pluginEntryOf(jarOf(), "empty-0.1.0.jar"))
    }

    // --- what IS a plugin, and what it says ------------------------------------------------

    @Test
    fun `a plugin that declares a range admitting the host is compatible`() {
        val entry = BundledPluginCompatibility.pluginEntryOf(
            jarOf(PLUGIN_MANIFEST to manifest(ranges = "[0.70.0, 0.71.0)")),
            "acme-http-0.70.0.jar",
        )

        assertNotNull(entry, "the plugin vanished from the gate")
        assertEquals("acme", entry!!.pluginId)
        assertEquals("0.70.0", entry.releaseVersion)
        assertEquals("[0.70.0, 0.71.0)", entry.apiRange)

        val verdict = BundledPluginCompatibility.evaluate(host, listOf(entry))
        assertTrue(verdict is BundledPluginCompatibilityVerdict.Compatible, "got $verdict")
    }

    @Test
    fun `a plugin that declares a range excluding the host is a conflict`() {
        val entry = BundledPluginCompatibility.pluginEntryOf(
            jarOf(PLUGIN_MANIFEST to manifest(ranges = "[0.60.0, 0.61.0)")),
            "acme-http-0.70.0.jar",
        )

        assertNotNull(entry, "the plugin vanished from the gate")
        val verdict = BundledPluginCompatibility.evaluate(host, listOf(entry!!))
        assertTrue(verdict is BundledPluginCompatibilityVerdict.Incompatible, "got $verdict")
    }

    // --- the failure this file exists for -------------------------------------------------

    @Test
    fun `a plugin whose manifest declares no range is a conflict, not an absence`() {
        // The plugin says it exists and says nothing about which hosts it
        // admits. Nothing shows that it admits THIS host, so the candidate
        // must not ship with it — exactly the fact the gate exists to
        // establish.
        //
        // If the probe DROPS this plugin instead of recording it, the gate
        // answers "compatible" about a plugin it never looked at, and the
        // refusal happens at runtime, after the download, which is the whole
        // failure the gate was built to prevent.
        val entry = BundledPluginCompatibility.pluginEntryOf(
            jarOf(PLUGIN_MANIFEST to manifest(ranges = null)),
            "acme-http-0.70.0.jar",
        )

        assertNotNull(entry, "the plugin vanished from the gate")
        assertNull(entry!!.apiRange, "a manifest that declares no range was read as one")

        val verdict = BundledPluginCompatibility.evaluate(host, listOf(entry))
        assertTrue(
            verdict is BundledPluginCompatibilityVerdict.Incompatible,
            "a plugin with no declared range produced $verdict",
        )
    }

    @Test
    fun `a plugin whose manifest declares an empty range is a conflict, not an absence`() {
        // `""` is a field that EXISTS and says nothing. A parser that requires
        // a non-empty value turns it into "the field is absent", and the
        // plugin disappears from the check. The gate cannot tell "declared
        // nothing" from "declared an empty range", so it must read both as
        // "nothing shows that this plugin admits the host".
        val entry = BundledPluginCompatibility.pluginEntryOf(
            jarOf(PLUGIN_MANIFEST to manifest(ranges = "")),
            "acme-http-0.70.0.jar",
        )

        assertNotNull(entry, "the plugin vanished from the gate")
        assertNull(entry!!.apiRange, "an empty range was read as a declared one")

        val verdict = BundledPluginCompatibility.evaluate(host, listOf(entry))
        assertTrue(
            verdict is BundledPluginCompatibilityVerdict.Incompatible,
            "a plugin with an empty range produced $verdict",
        )
    }

    @Test
    fun `a plugin whose manifest is not readable at all is a conflict, not an absence`() {
        // The manifest exists — the JAR is at the plugin manifest path — but it
        // is not the document the gate expects. It still declares itself a
        // plugin, and the gate still cannot show that it admits the host.
        val entry = BundledPluginCompatibility.pluginEntryOf(
            jarOf(PLUGIN_MANIFEST to "this is not the manifest you are looking for"),
            "acme-http-0.70.0.jar",
        )

        assertNotNull(entry, "the plugin vanished from the gate")
        assertNull(entry!!.apiRange, "an unreadable manifest was read as a declared range")

        val verdict = BundledPluginCompatibility.evaluate(host, listOf(entry))
        assertTrue(
            verdict is BundledPluginCompatibilityVerdict.Incompatible,
            "a plugin with an unreadable manifest produced $verdict",
        )
    }

    @Test
    fun `the conflict says the plugin declares no range`() {
        // The diagnostic is the operator's only handle on this. A conflict
        // that printed an empty range would read as "it declared something
        // unreadable", which is a different problem with a different fix.
        val entry = BundledPluginCompatibility.pluginEntryOf(
            jarOf(PLUGIN_MANIFEST to manifest(ranges = null)),
            "acme-http-0.70.0.jar",
        )!!

        val verdict = BundledPluginCompatibility.evaluate(host, listOf(entry))
        assertTrue(verdict is BundledPluginCompatibilityVerdict.Incompatible, "got $verdict")

        val rendered = verdict.render()
        assertTrue(rendered.contains("acme"), "the conflict does not name the plugin: $rendered")
        assertTrue(
            rendered.contains("no apiRange") || rendered.contains("NO apiRange"),
            "the conflict does not say the range is absent: $rendered",
        )
    }

    // --- the helpers -----------------------------------------------------------------------

    private fun manifest(ranges: String?): String {
        val rangeField = if (ranges == null) "" else """"apiRange": "$ranges","""
        return """{"plugin": "v1:plugin:acme/plugin/http", "releaseVersion": "0.70.0", $rangeField "trust": "unverified"}"""
    }

    private fun jarOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private companion object {
        const val PLUGIN_MANIFEST = "META-INF/pipelinek/plugin-manifest.json"
    }
}
