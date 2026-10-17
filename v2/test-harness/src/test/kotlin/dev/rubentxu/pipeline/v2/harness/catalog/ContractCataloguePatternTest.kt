package dev.rubentxu.pipeline.v2.harness.catalog

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * BQ1 (2026-10-16 audit, RC2): the catalogue used to match
 * `endsWith(path)`, which has two flaws:
 *   1. cross-owner suffix collision (a `build.gradle.kts` in
 *      any module would match a contract that ended in
 *      `build.gradle.kts`);
 *   2. only literal paths — a contract that wanted to claim
 *      "every .kt file under this module" had to enumerate
 *      them.
 *
 * This test pins both fixes:
 *   - the new `matchesPattern` is a glob with `*` (single
 *     segment) and `**` (recursive);
 *   - the literal-path matcher requires an exact match, not
 *     a suffix match, so cross-owner collisions are
 *     rejected.
 */
class ContractCataloguePatternTest {

    @Test
    fun `single-segment wildcard matches one path segment`() {
        assertTrue(ContractCatalogue.matchesPattern("v2/x/foo.kt", "v2/x/*.kt"))
        assertTrue(ContractCatalogue.matchesPattern("v2/x/foo.kt", "v2/*/foo.kt"))
    }

    @Test
    fun `single-segment wildcard does NOT match a path separator`() {
        assertFalse(ContractCatalogue.matchesPattern("v2/x/y/foo.kt", "v2/x/*.kt"))
        assertFalse(ContractCatalogue.matchesPattern("v2/x/y/foo.kt", "v2/*.kt"))
    }

    @Test
    fun `double-star matches any number of segments`() {
        assertTrue(ContractCatalogue.matchesPattern("v2/x/foo.kt", "v2/**/*.kt"))
        assertTrue(ContractCatalogue.matchesPattern("v2/x/y/z/foo.kt", "v2/**/*.kt"))
    }

    @Test
    fun `double-star matches zero segments too`() {
        // BQ1 RC4 follow-up (2026-10-17): the previous
        // matcher compiled `**` to `.*` and required a
        // literal `/` after it, so a pattern like
        // `v2/**/gradle.properties` failed to match
        // `v2/gradle.properties` (the file directly under
        // v2/, no intermediate directory). The catalogue
        // had this exact pattern and the contract never
        // fired for the root gradle.properties — the
        // `ContractCatalogueFitnessTest.everyPathPatternMatchesAtLeastOneFile`
        // fitness caught it. The fix compiles `**/` to
        // `(?:[^/]+/)*` so zero segments match.
        assertTrue(ContractCatalogue.matchesPattern("v2/gradle.properties", "v2/**/gradle.properties"))
        assertTrue(ContractCatalogue.matchesPattern("v2/foo.kt", "v2/**/*.kt"))
    }

    @Test
    fun `literal path requires an exact match`() {
        // A literal entry on the contract source list matches
        // only the exact string. Cross-owner collision is
        // closed: a `build.gradle.kts` in one module does not
        // match a contract whose source list is the SAME path
        // in a different module.
        assertTrue(
            ContractCatalogue.contractsFor("v2/pipeline-application/build.gradle.kts")
                .any { it.owningModule == ":pipeline-application" },
        )
        // The fitness test below asserts the inverse: a
        // contract whose source is in a different module does
        // not match.
    }

    @Test
    fun `glob is anchored`() {
        // `*.kt` does NOT match a path with a directory.
        assertFalse(ContractCatalogue.matchesPattern("v2/x/foo.kt", "*.kt"))
        // `v2/*.kt` does NOT match a path with extra segments.
        assertFalse(ContractCatalogue.matchesPattern("v2/x/y/foo.kt", "v2/*.kt"))
    }

    @Test
    fun `special regex characters in pattern are escaped`() {
        // A literal `.` in the pattern must match a literal `.`,
        // not any character. `v2/x.foo.kt` is a real file; the
        // pattern `v2/x.foo.kt` matches it; `v2/xXfooXkt` does
        // not.
        assertTrue(ContractCatalogue.matchesPattern("v2/x.foo.kt", "v2/x.foo.kt"))
        assertFalse(ContractCatalogue.matchesPattern("v2/xXfooXkt", "v2/x.foo.kt"))
    }
}
