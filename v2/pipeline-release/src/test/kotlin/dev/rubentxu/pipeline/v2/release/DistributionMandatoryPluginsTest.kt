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

    /**
     * PK-RELEASE-CONSOLIDATION H1.4: the seven mandatory plugins the
     * distribution must ship. The four official ones (http, scm-git,
     * junit, utilities) are the contract surface; the three
     * external examples (block, directive, uppercase) are the
     * independent consumers that close the D1 derivation loop. A
     * candidate missing any of the seven is RED — D1 says the
     * example builds are part of the gate, not optional.
     */
    private val mandatory: Set<String> = setOf(
        // four official plugins
        "pipeline-plugin-http",
        "pipeline.scm-git",
        "pipeline.junit",
        "pipeline.utilities",
        // three independent examples
        "example-block-plugin",
        "example-directive-plugin",
        "example-uppercase-plugin",
    )

    @Test
    fun `the distribution contains exactly the seven mandatory plugins`() {
        val entries = BundledPluginCompatibility.probeZip(zip)
        // PK-RELEASE-CONSOLIDATION R1.2 (audit fix): the audit flagged
        // that a set-equality check does not detect two JARs shipping
        // the SAME pluginId under different identities. The check is
        // now structural: every pluginId must appear exactly once,
        // and every entry must declare a unique identity.
        val pluginIds = entries.map { it.pluginId }
        val pluginIdCounts: Map<String, Int> = pluginIds.groupingBy { it }.eachCount()
        val duplicates = pluginIdCounts.filterValues { it > 1 }
        assertTrue(
            duplicates.isEmpty(),
            "the distribution ships the same pluginId under two or more identities. " +
                "Duplicates: $duplicates. A duplicate pluginId is a build defect — the " +
                "build is shipping the same surface twice, which silently masks the " +
                "absence of another mandatory plugin. Found pluginIds: $pluginIds.",
        )
        assertEquals(
            mandatory,
            pluginIds.toSet(),
            "the distribution must carry exactly the four official plugins " +
                "(http, scm-git, junit, utilities) and the three external examples " +
                "(block, directive, uppercase). " +
                "Found: $pluginIds. A missing plugin means a required surface was not shipped.",
        )
    }

    /**
     * R1.4 helper. Reads the host version from the ZIP filename
     * `pipelinek-X.Y.Z.zip`. The ZIP under test is the single source
     * of truth for the host identity the gate polices; deriving it
     * from a literal in the test file would let the test pass on a
     * drift between the declared version and the source.
     */
    private fun hostVersionFromZip(zip: Path): String {
        val name = zip.fileName.toString()
        val match = Regex("^pipelinek-(\\d+\\.\\d+\\.\\d+)\\.zip$").matchEntire(name)
            ?: error("ZIP filename does not match pipelinek-X.Y.Z.zip: $name. " +
                "The gate cannot derive the host version. Either the artefact " +
                "was misnamed or the distribution contract changed.")
        return match.groupValues[1]
    }

    @Test
    fun `each mandatory plugin admits the running version`() {
        // PK-RELEASE-CONSOLIDATION R1.4 (audit fix): the audit flagged
        // that the host version was hard-coded to 0.56.0 (now 0.57.0
        // after T2b). The test was supposed to be runnable against
        // any ZIP, but the host identity was a literal, not a
        // derivation. The fix is to read the host identity from the
        // ZIP under test: the file name `pipelinek-X.Y.Z.zip` carries
        // the version the gate must police. A drift between the
        // declared version and the project's source is now detectable
        // by the test itself.
        val hostVersion = hostVersionFromZip(zip)
        val entries = BundledPluginCompatibility.probeZip(zip)
        val verdict = BundledPluginCompatibility.evaluate(
            ProductVersion.parseOrNull(hostVersion)!!,
            entries,
        )
        assertInstanceOf(
            BundledPluginCompatibilityVerdict.Compatible::class.java,
            verdict,
            "rc3-style drift: at least one bundled plugin refuses the host. " +
                "The build needs the policy-derived apiRange to cover $hostVersion. " +
                "Verdict: ${verdict.render()}",
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
        // PK-RELEASE-CONSOLIDATION R1.3 (audit fix): the previous form
        // `assertNotNull(false, msg)`
        // was a no-op: assertNotNull on a Boolean checks the boxed
        // reference (always non-null), so the boolean was ignored and
        // the assertion always passed. assertTrue is the only
        // assertion that exercises the boolean contract.
        assertTrue(
            false,
            "diagnostic must name the policy-violating range: ${refused.render()}",
        )
    }
}
