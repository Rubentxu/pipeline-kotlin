package dev.rubentxu.pipeline.v2.release

import dev.rubentxu.pipeline.v2.domain.step.SemVer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Path
import java.nio.file.Paths

/**
 * PK-RC-PLUGIN-001 Block 3 — the distribution must carry exactly the four
 * mandatory official plugins, each admitting the running version.
 *
 * <h2>Why this test exists</h2>
 *
 * The v0.56.0-rc3 candidate was published with four bundled plugins whose
 * `apiRange=[0.47.0, 0.53.0)` excluded the host `0.56.0`. The
 * `BundledPluginCompatibility` gate would have refused that ZIP, but the
 * release script never invoked the gate against the published artefact.
 *
 * PK-0-57-RC1 (T2): the current host is 0.57.0. The drift reproducer for
 * the rc3 artefact remains as historical evidence; the in-tree host
 * reference is updated to 0.57.0, so the gate must admit the bundled
 * plugins against the same host identity the distribution declares.
 *
 * This test makes the gate observable on the real distribution: it locates
 * the produced ZIP, probes it for the four mandatory plugins (http, scm-git,
 * junit, utilities), and asserts that each declares a range covering the
 * running version. A new release that breaks this property is RED for the
 * exact reason the user observed in rc3.
 *
 * <h2>Locating the ZIP</h2>
 *
 * The path is read from the env var `PIPELINEK_DIST_ZIP` so the test is
 * environment-agnostic. CI sets it from `:pipeline-application:distZip`
 * output. Local development: `export PIPELINEK_DIST_ZIP=$(find v2/pipeline-application/build/distributions -name "pipelinek-*.zip")`.
 *
 * The test is skipped when the env var is absent.
 */
@EnabledIfEnvironmentVariable(named = "PIPELINEK_DIST_ZIP", matches = ".+")
class DistributionMandatoryPluginsTest {

    private val zip: Path = Paths.get(System.getenv("PIPELINEK_DIST_ZIP")!!)

    /** The four mandatory plugins the distribution must ship. */
    private val mandatory: Set<String> = setOf(
        "pipeline-plugin-http",
        "pipeline.scm-git",
        "pipeline.junit",
        "pipeline.utilities",
    )

    @Test
    fun `the distribution contains exactly the four mandatory official plugins`() {
        val entries = BundledPluginCompatibility.probeZip(zip)
        val pluginIds = entries.map { it.pluginId }.toSet()
        assertEquals(
            mandatory,
            pluginIds,
            "the distribution must carry exactly http, scm-git, junit, utilities. " +
                "Found: $pluginIds. A missing plugin means a required surface was not shipped; " +
                "a duplicate means the build is shipping the same plugin under two identities.",
        )
    }

    @Test
    fun `each mandatory plugin admits the running version`() {
        val running = SemVer(0, 57, 0)  // host identity, not derived: this test must be
                                       // runnable against any ZIP; the gate's job is to
                                       // admit or refuse the same running version the
                                       // distribution itself declares.
        val entries = BundledPluginCompatibility.probeZip(zip)
        val verdict = BundledPluginCompatibility.evaluate(
            ProductVersion.parseOrNull("0.57.0")!!,
            entries,
        )
        assertInstanceOf(
            BundledPluginCompatibilityVerdict.Compatible::class.java,
            verdict,
            "rc3-style drift: at least one bundled plugin refuses the host. " +
                "The build needs the policy-derived apiRange to cover 0.57.0. Verdict: ${verdict.render()}",
        )
    }

    @Test
    fun `the v0_56_0-rc3 published artefact is refused by the same gate`() {
        // The reproducer: when PK_RC_PLUGIN_001_RC3_ZIP points at the downloaded
        // rc3 ZIP, the gate must report Incompatible. Skipped when the env var
        // is absent (CI may not have downloaded the rc3 fixture).
        val rc3Path = System.getenv("PK_RC_PLUGIN_001_RC3_ZIP") ?: return
        val rc3 = Paths.get(rc3Path)
        assertTrue(
            java.nio.file.Files.isRegularFile(rc3),
            "PK_RC_PLUGIN_001_RC3_ZIP=$rc3 does not point to a regular file",
        )
        val entries = BundledPluginCompatibility.probeZip(rc3)
        val verdict = BundledPluginCompatibility.evaluate(
            ProductVersion.parseOrNull("0.56.0")!!,
            entries,
        )
        assertInstanceOf(
            BundledPluginCompatibilityVerdict.Incompatible::class.java,
            verdict,
            "the rc3 ZIP was published with apiRange=[0.47.0, 0.53.0) on a 0.56.0 host; the gate " +
                "MUST refuse it. If this test goes green, the gate has been weakened.",
        )
        val refused = verdict as BundledPluginCompatibilityVerdict.Incompatible
        assertTrue(
            refused.conflicts.size == 4,
            "the rc3 ZIP must produce 4 conflicts (one per official plugin); got ${refused.conflicts.size}",
        )
        assertNotNull(
            refused.render().contains("[0.47.0, 0.53.0)"),
            "diagnostic must name the policy-violating range: ${refused.render()}",
        )
    }
}
