package dev.rubentxu.pipeline.v2.application.release

import dev.rubentxu.pipeline.v2.application.support.AppBinSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.jar.JarFile

/**
 * The installed distribution MUST carry exactly ONE product version.
 *
 * ## The defect this exists for
 *
 * The admitted `0.48.0` candidate ZIP contained, side by side:
 *
 * ```text
 * lib/pipeline-application-0.48.0.jar
 * lib/scm-git-0.36.0.jar
 * lib/runtime-0.36.0.jar
 * lib/api-0.36.0.jar
 * lib/files-0.36.0.jar
 * lib/http-0.36.0.jar
 * lib/utilities-0.36.0.jar
 * ```
 *
 * One release train shipping two product versions. Six Step SDK subprojects assigned
 * their own `version = "0.36.0"`, which overrode the inherited `rootProject.version`
 * that `v2/build.gradle.kts` documents as the sole authority.
 *
 * ## Why this file and not only the build-script guard
 *
 * [SingleVersionAuthorityFitnessTest] protects the SOURCE: no subproject may declare a
 * version. That guard is necessary and it is not sufficient, for a reason measured on
 * this defect:
 *
 * - `F5_1_ScmGitProviderProvenanceTest` asserts `assertEquals("0.36.0", prov.releaseVersion)`
 *   and stayed GREEN both before and after the fix.
 * - It stayed green because it never read the artifact: it constructs
 *   `PluginReleaseRef(version = SemVer(0, 36, 0))` by hand as INPUT and asserts the
 *   reader projects it unchanged. It is a projection test, not a provenance-of-artifact
 *   test. The literal `0.36.0` there is a fixture, so it could neither detect nor
 *   follow the defect.
 *
 * So before this file existed, NO test in the repository detected the mixed versions
 * inside a distribution. This row reads the jars the product actually ships, which is
 * the only claim that matters for a candidate.
 *
 * ## Authority
 *
 * `AGENTS.md` PRODUCT IDENTITY LAW (release candidates §11):
 * `ProductVersion == AssetVersion == ArchiveRootVersion == EmbeddedVersion == RuntimeVersion`.
 * The JAR filenames in `lib/` are the `AssetVersion` surface for library artifacts, so
 * they are in scope for that law, and they were the surface that violated it.
 *
 * ## Harness fidelity
 *
 * HF1, in-process: it reads the real installed distribution produced by `installDist`
 * from [AppBinSupport], the same discovery every installed-distribution UAT uses. It
 * does NOT recompute the version from the build script and compare — asserting the
 * declared version equals the expected literal would be a second authority that can
 * agree with a wrong build. It reads what was BUILT.
 *
 * `assumeTrue` on a missing install is deliberate: this row needs `installDist`, which
 * is not part of `check`. A skipped row is reported, never silently green, and the
 * release gate runs it with the distribution present.
 */
class InstalledDistributionSingleVersionTest {

    /**
     * `AppBinSupport.discover()` returns `<installRoot>/bin/<app>`, so the distribution
     * root is its PARENT. Climbing three levels (as a first draft did) landed on
     * `build/install`, which has no `lib/`, and every row then reported
     * `Assumption failed: no installDist output` and SKIPPED on a perfectly healthy
     * distribution. That is the worst shape this file could have taken: a path bug
     * dressed as a missing artifact, converting the release check into a silent no-op.
     * Resolved by the expected structure instead of by counting levels.
     */
    private val installRoot: File? = runCatching {
        AppBinSupport.discover().toFile().parentFile.parentFile
    }.getOrNull()?.takeIf { File(it, "lib").isDirectory }

    private fun libDir(): File? = installRoot?.resolve("lib")?.takeIf { it.isDirectory }

    /**
     * ONLY this project's own jars are in scope.
     *
     * A first draft asserted over every jar in the lib directory and reported 11
     * product versions on a HEALTHY distribution — `annotations-23.0.0`, `kotlin-reflect-1.6.10`,
     * `bcprov-1.86`, `slf4j-2.0.16`. Those are third-party dependencies carrying their
     * own correct upstream versions; a distribution pinning them all to 0.48.0 would be
     * wrong in a different way. The law constrains the surfaces THIS product owns,
     * which is exactly the set of modules that declared their own `version` and caused
     * the defect.
     */
    private val projectOwnedPrefixes = listOf(
        "pipeline-application", "api", "files", "http", "runtime", "scm-git", "utilities", "workflow-control",
    )

    private fun projectJars(lib: File): List<File> = lib.listFiles().orEmpty()
        .filter { it.name.endsWith(".jar") }
        .filter { f -> projectOwnedPrefixes.any { p -> f.name.startsWith("$p-") } }
        .sortedBy { it.name }

    private fun jarNameVersion(file: File): Pair<String, String?> {
        val stem = file.name.removeSuffix(".jar")
        val lastDash = stem.lastIndexOf('-')
        if (lastDash <= 0) return stem to null
        val candidate = stem.substring(lastDash + 1)
        val looksLikeVersion = candidate.any(Char::isDigit) && candidate.contains('.')
        return if (looksLikeVersion) stem.substring(0, lastDash) to candidate else stem to null
    }

    @Test
    fun `installed lib jars declare a single product version`() {
        val lib = libDir()
        assumeTrue(lib != null, "no installDist output at ${installRoot?.path}; run :pipeline-application:installDist")
        // `assumeTrue` returns Unit and does not smart-cast, so the non-null is asserted
        // explicitly here rather than sprinkling `!!` at each use site.
        requireNotNull(lib) { "libDir() returned null after assumeTrue; guard is broken" }

        val versioned = projectJars(lib)
            .mapNotNull { f -> jarNameVersion(f).second?.let { v -> f.name to v } }

        assertTrue(
            versioned.isNotEmpty(),
            "no project jars under ${lib.path}; the distribution would not be runnable " +
                "and this row would otherwise pass vacuously",
        )

        val versions = versioned.map { it.second }.toSortedSet()
        assertEquals(
            1,
            versions.size,
            "the installed distribution ships ${versions.size} product versions " +
                "(${versions.joinToString()}) among its OWN artifacts, violating " +
                "PRODUCT IDENTITY LAW. Offending jars: " +
                versioned.groupBy { it.second }
                    .map { (v, jars) -> "$v -> ${jars.map { it.first }.sorted()}" }
                    .joinToString("; "),
        )
    }

    /**
     * The root jar carries the product version, so it anchors the single version the
     * row above proves exists. Read from the built artifact rather than from
     * `v2/build.gradle.kts`, because a build script is a declaration and this is a
     * shipped byte.
     */
    @Test
    fun `installed application jar carries the product version and matches the lib jars`() {
        val lib = libDir()
        assumeTrue(lib != null, "no installDist output at ${installRoot?.path}; run :pipeline-application:installDist")
        requireNotNull(lib) { "libDir() returned null after assumeTrue; guard is broken" }

        val appJars = projectJars(lib).filter { it.name.startsWith("pipeline-application-") }
        assertEquals(
            1, appJars.size,
            "expected exactly one pipeline-application jar, found ${appJars.map { it.name }}",
        )

        val appVersion = jarNameVersion(appJars.single()).second
        assertTrue(appVersion != null, "application jar ${appJars.single().name} declares no version")

        val libVersions = projectJars(lib)
            .mapNotNull { jarNameVersion(it).second }
            .toSortedSet()

        assertEquals(
            setOf(appVersion), libVersions.toSet(),
            "application jar is $appVersion but the lib jars declare $libVersions; " +
                "a candidate whose own artifact disagrees with its libraries is not " +
                "installable in any meaningful sense",
        )

        // The version is also embedded, not only carried in the filename. A jar whose
        // manifest still claims the old version would ship two identities for one file.
        JarFile(appJars.single()).use { jar ->
            val mf = jar.manifest?.mainAttributes?.getValue("Implementation-Version")
            assertEquals(
                appVersion, mf,
                "application jar filename says $appVersion but its manifest says $mf",
            )
        }
    }
}
