package dev.rubentxu.pipeline.v2.release

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The gate that decides whether a candidate may ship.
 * `BundledPluginCompatibility` is the only check that is mechanical and
 * reproducible from the distribution ZIP alone, and it is the one that runs
 * BEFORE an operator downloads anything. It is also the only thing standing
 * between a candidate and a plugin that refuses to load at runtime -- which is
 * the failure the user would otherwise meet after the download, not at
 * admission.
 * Two deliberate fail-closed choices are pinned below, because both read as
 * bugs to a future reader and both were once tempting enough to "fix":
 * - A range the parser cannot read is NOT compatibility. Unknown range ==
 *   not containable. Otherwise a typo in one plugin's manifest silently
 *   removes that plugin from the check.
 * - Version identity is NOT the rule. `releaseVersion` is ignored; only
 *   `apiRange` membership decides. junit ships as `0.1.0` against a `0.51.0`
 *   host on purpose, and a check that demanded equal versions would have
 *   rejected junit on every v0.5x release.
 * Everything here is pure set-and-string work over three fields, so no
 * filesystem, no JAR and no distribution is needed to test it.
*/
class ApiRangeContainsTest {

    // --- the ordinary case: lower bound inclusive, upper bound exclusive --------------

    @Test
    fun `the lower bound is admitted`() {
        assertTrue(BundledPluginCompatibility.apiRangeContains("[0.51.0, 0.52.0)", "0.51.0"))
    }

    @Test
    fun `the upper bound is refused`() {
        // Half-open on the right. A range spelled `[0.51.0, 0.52.0]` means
        // "the host is 0.51.x and nothing newer", so admitting 0.52.0 would let
        // a candidate ship against a plugin that was never built for it.
        assertFalse(BundledPluginCompatibility.apiRangeContains("[0.51.0, 0.52.0)", "0.52.0"))
    }

    @Test
    fun `a version strictly inside is admitted`() {
        assertTrue(BundledPluginCompatibility.apiRangeContains("[0.51.0, 0.52.0)", "0.51.7"))
    }

    @Test
    fun `a version below the range is refused`() {
        assertFalse(BundledPluginCompatibility.apiRangeContains("[0.51.0, 0.52.0)", "0.50.9"))
    }

    // --- the fail-closed choice: an unreadable range contains nothing -----------------

    @Test
    fun `an absent range contains nothing`() {
        // A manifest emitted without `apiRange` is a partially-written
        // manifest. Treating it as "no constraint" would remove the plugin
        // from the gate, and the plugin would then fail at runtime instead.
        assertFalse(BundledPluginCompatibility.apiRangeContains(null, "0.51.0"))
    }

    @Test
    fun `a range this parser cannot read contains nothing`() {
        // Each of these is refused for a different reason, and all of them are
        // refused. Spelling them out stops a future change from widening the
        // regex in one direction only.
        val unreadable = listOf(
            "0.51.0",             // no brackets at all
            "[0.51.0]",           // no upper bound
            "(0.51.0, 0.52.0]",   // wrong bracket shape: closed, not half-open
            "[0.51.0, 0.52.0]",   // closed upper bound, not the declared shape
            "[0.51, 0.52.0]",     // lower bound is not a triple
            "[v0.51.0, v0.52.0)", // prefixed
            "[0.51.0,0.52.0",     // open lower bracket
            "0.51.0 - 0.52.0",    // a human range, not the declared literal
            "",                   // empty
            "   ",                // blank
            "[0.51.0, 0.52.0) extra", // trailing content after a valid range
        )

        for (range in unreadable) {
            assertFalse(
                BundledPluginCompatibility.apiRangeContains(range, "0.51.0"),
                "range '$range' was read as compatible",
            )
        }
    }

    @Test
    fun `whitespace around a well-formed range does not defeat it`() {
        // The reader trims the whole literal and the pattern allows spaces
        // around each bound, so a hand-edited manifest does not fail closed for
        // a reason an operator cannot see. Pinned as its own test because it is
        // the ONE tolerance: widen it and a cosmetic edit starts silently
        // dropping plugins from the gate.
        val spaced = listOf(
            "  [0.51.0, 0.52.0)  ",
            "[0.51.0, 0.52.0) ",
            "[ 0.51.0 , 0.52.0 )",
            "[  0.51.0,0.52.0  )",
        )

        for (range in spaced) {
            assertTrue(
                BundledPluginCompatibility.apiRangeContains(range, "0.51.0"),
                "range '$range' was refused for spacing alone",
            )
        }
    }

    @Test
    fun `surrounding and interior whitespace does not defeat the range`() {
        // The reader trims the whole literal and the pattern allows spaces
        // around each bound, so a hand-edited manifest does not fail closed for
        // a reason an operator cannot see. Pinned as its own test because it is
        // the one tolerance: without it a cosmetic edit silently drops a
        // plugin from the gate.
        assertTrue(BundledPluginCompatibility.apiRangeContains("  [0.51.0, 0.52.0)  ", "0.51.0"))
        assertTrue(BundledPluginCompatibility.apiRangeContains("[ 0.51.0 , 0.52.0 )", "0.51.0"))
    }

    // --- ranges that cannot contain anything, and must not be "repaired" ---------------

    @Test
    fun `an inverted range contains nothing`() {
        // `[2.0.0, 1.0.0)` is unsatisfiable. Swapping the bounds to be helpful
        // would invent compatibility nobody declared.
        assertFalse(BundledPluginCompatibility.apiRangeContains("[2.0.0, 1.0.0)", "1.5.0"))
        assertFalse(BundledPluginCompatibility.apiRangeContains("[2.0.0, 1.0.0)", "2.0.0"))
    }

    @Test
    fun `an empty range contains nothing`() {
        assertFalse(BundledPluginCompatibility.apiRangeContains("[1.0.0, 1.0.0)", "1.0.0"))
    }

    // --- the comparison underneath, on its own terms ------------------------------------

    @Test
    fun `components compare numerically, not as text`() {
        // The whole reason this is not String.compareTo: "0.9.0" > "0.10.0" as
        // text, and 0.9.0 < 0.10.0 as versions. Every release gate that used
        // the text order would have shipped the wrong plugin set.
        assertTrue(BundledPluginCompatibility.semverCompare("0.10.0", "0.9.0") > 0)
        assertTrue(BundledPluginCompatibility.semverCompare("0.9.0", "0.10.0") < 0)
        assertTrue(BundledPluginCompatibility.semverCompare("1.0.0", "1.0.0") == 0)
        assertTrue(BundledPluginCompatibility.semverCompare("1.0.1", "1.0.0") > 0)
        assertTrue(BundledPluginCompatibility.semverCompare("1.1.0", "1.0.9") > 0)
    }

    @Test
    fun `a missing trailing component is zero`() {
        // `[0.51]`-shaped hosts do not reach here -- ProductVersion is a value
        // class that cannot be built from anything but a triple -- but the
        // comparison is also used on range bounds, and this is the rule it
        // applies when the shapes differ.
        assertTrue(BundledPluginCompatibility.semverCompare("1.0", "1.0.0") == 0)
        assertTrue(BundledPluginCompatibility.semverCompare("1.0.0", "1.0") == 0)
    }

    @Test
    fun `a non-numeric component reads as zero, which fails closed at the range`() {
        // `toIntOrNull() ?: 0` is the tolerance that makes pre-release suffixes
        // comparable. It is also why a malformed HOST is refused: the range
        // bound is always a validated triple, so a host that reads as 0 lands
        // below any real lower bound and is rejected. Pinned because the safety
        // here is a consequence, not a check: if the regex ever admits a
        // non-triple lower bound, this property is what stops being true.
        assertFalse(BundledPluginCompatibility.apiRangeContains("[0.51.0, 0.52.0)", "not-a-version"))
        assertTrue(BundledPluginCompatibility.semverCompare("x.y.z", "0.0.0") == 0)
    }

    // --- the case the KDoc names, which is the trap this file exists for ---------------

    @Test
    fun `a plugin far older than the host is compatible when its range admits it`() {
        // junit is intentionally `0.1.0` against a `0.51.0` host. The rule is
        // the plugin's declared apiRange, never version equality. A check that
        // compared releaseVersion to the host would have rejected junit on
        // every v0.5x release, and the failure would look like "the bundled
        // plugins are inconsistent" rather than "the check is wrong".
        assertTrue(BundledPluginCompatibility.apiRangeContains("[0.1.0, 1.0.0)", "0.51.0"))
    }

    // --- the verdict, which is what the build actually reads ---------------------------

    @Test
    fun `a distribution with no plugin manifests is refused as incomplete`() {
        // Fail-closed on absence, and a verdict of its own rather than an empty
        // Compatible. "No plugins to check" and "all plugins check out" are
        // different facts, and collapsing them is how a distribution missing its
        // official plugins ships as green.
        val verdict = BundledPluginCompatibility.evaluate(ProductVersion.parseOrThrow("0.51.0"), emptyList())

        assertTrue(
            verdict is BundledPluginCompatibilityVerdict.NoPluginsFound,
            "an empty distribution produced $verdict",
        )
    }

    @Test
    fun `plugins of very different release versions are compatible together`() {
        // The junit/http pair the KDoc names, run through the real entry point
        // rather than through apiRangeContains alone: this is the property that
        // a future "also compare the versions" change would break.
        val host = ProductVersion.parseOrThrow("0.51.0")
        val entries = listOf(
            entry("junit", "0.1.0", "[0.1.0, 1.0.0)"),
            entry("http", "0.51.0", "[0.51.0, 0.52.0)"),
            entry("scm-git", "0.51.0", "[0.50.0, 0.52.0)"),
        )

        val verdict = BundledPluginCompatibility.evaluate(host, entries)

        assertTrue(verdict is BundledPluginCompatibilityVerdict.Compatible, "got $verdict")
    }

    @Test
    fun `every offending plugin is named in one verdict`() {
        // One round-trip for the operator, not one per plugin. A check that
        // reported only the first offender would pass CI on a re-run and
        // leave the reader to find the other two by hand.
        val host = ProductVersion.parseOrThrow("0.51.0")
        val entries = listOf(
            entry("junit", "0.1.0", "[0.1.0, 1.0.0)"),      // fine
            entry("http", "0.51.0", "[0.60.0, 0.61.0)"),     // too high
            entry("scm-git", "0.51.0", "[0.1.0, 0.2.0)"),     // too low
            entry("utilities", "0.51.0", "nonsense"),          // unreadable
        )

        val verdict = BundledPluginCompatibility.evaluate(host, entries)

        assertTrue(verdict is BundledPluginCompatibilityVerdict.Incompatible, "got $verdict")
        val conflicts = (verdict as BundledPluginCompatibilityVerdict.Incompatible).conflicts
        assertEquals(
            listOf("http", "scm-git", "utilities"),
            conflicts.map { it.pluginId },
            "the verdict did not name every offender",
        )
        for (conflict in conflicts) {
            assertEquals("0.51.0", conflict.hostVersion)
            assertTrue(conflict.apiRange.isNotBlank(), "${conflict.pluginId} conflict carries no range")
            assertTrue(conflict.releaseVersion.isNotBlank(), "${conflict.pluginId} conflict carries no version")
        }
    }

    @Test
    fun `a plugin with an unreadable range is a conflict, not a pass`() {
        // The single most consequential line in the file: a manifest typo must
        // surface as a refusal naming that plugin. If this ever returned true,
        // the typo would silently delete the plugin from the gate and the
        // refusal would happen at runtime instead of at admission.
        val host = ProductVersion.parseOrThrow("0.51.0")

        val verdict = BundledPluginCompatibility.evaluate(
            host,
            listOf(entry("http", "0.51.0", "[0.51.0, 0.52.0]")),
        )

        assertTrue(verdict is BundledPluginCompatibilityVerdict.Incompatible, "got $verdict")
    }

    // --- the plugin name an operator reads on the diagnostic ---------------------------

    @Test
    fun `the plugin ref collapses to the namespace an operator recognises`() {
        // `v1:plugin:<namespace>/plugin/<name>` is a stable identity token; the
        // namespace is the name a person recognises. A ref that does not match
        // the shape is returned raw rather than nulled, so one broken field
        // still surfaces something to act on.
        assertEquals("acme", BundledPluginCompatibility.shortPluginName("v1:plugin:acme/plugin/http"))
        assertEquals("acme", BundledPluginCompatibility.shortPluginName("v1:plugin:acme/plugin/scm-git"))
    }

    @Test
    fun `a ref outside the declared shape is kept whole`() {
        // Not nulled and not truncated at the first slash: a value the operator
        // did not recognise is still the most useful thing on the diagnostic.
        assertEquals("something-else", BundledPluginCompatibility.shortPluginName("something-else"))
        assertEquals("acme/plugin", BundledPluginCompatibility.shortPluginName("v1:plugin:acme/plugin"))
    }

    private fun entry(id: String, version: String, range: String) = BundledPluginEntry(
        pluginId = id,
        releaseVersion = version,
        apiRange = range,
        jarName = "$id-$version.jar",
    )
}

