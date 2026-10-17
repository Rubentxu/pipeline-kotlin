package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Pins the SUBJECT of every fitness in this module: source files, not build outputs.
 *
 * `FitnessPaths.walkKotlinFiles` is the single traversal behind the architecture censuses, so a
 * scoping error here does not fail one test — it silently changes what all of them mean. Before
 * this test the walk was unpruned and swept in every `build/` and `.gradle/` tree: 41 of 1674
 * Kotlin files (measured 2026-10-18) were build artefacts, and whether a census passed depended
 * on which tasks had last run rather than on the code under fitness.
 *
 * The fixtures are synthetic and written under JUnit's `@TempDir`, never in the repository: this
 * test must not be able to contaminate the census it is pinning.
 */
class FitnessPathsScopingTest {

    private fun write(root: Path, relative: String): Path {
        val target = root.resolve(relative)
        Files.createDirectories(target.parent)
        Files.writeString(target, "// synthetic fixture\n")
        return target
    }

    private fun relativeNames(root: Path, paths: List<Path>): Set<String> =
        paths.map { root.relativize(it).toString().replace('\\', '/') }.toSet()

    @Test
    @DisplayName("build outputs and tooling caches are not the fitness subject")
    fun `kotlin walk excludes non-source trees`(@TempDir root: Path) {
        val source = write(root, "app/src/main/kotlin/Foo.kt")
        write(root, "app/build/generated/sources/Foo.kt")
        write(root, "app/build/tmp/kotlin-classes/Bar.kt")
        write(root, ".gradle/caches/Foo.kts")
        write(root, "app/.worktrees/wt/src/main/kotlin/Baz.kt")

        val found = relativeNames(root, FitnessPaths.walkKotlinFiles(root))

        assertEquals(setOf("app/src/main/kotlin/Foo.kt"), found)
        assertTrue(FitnessPaths.walkKotlinFiles(root).contains(source))
    }

    @Test
    @DisplayName("a build-named directory in the CHECKOUT PREFIX must not prune the walk")
    fun `prefix segments named build do not empty the walk`(@TempDir tmp: Path) {
        // The regression this pins: comparing segments against the ABSOLUTE path lets any
        // `build`-named ancestor of the root prune everything, and a census that sees nothing
        // is indistinguishable from a census with nothing to report.
        val root = Files.createDirectories(tmp.resolve("work/build/checkout/v2"))
        write(root, "app/src/main/kotlin/Foo.kt")
        write(root, "app/build/generated/Foo.kt")

        val found = relativeNames(root, FitnessPaths.walkKotlinFiles(root))

        assertEquals(setOf("app/src/main/kotlin/Foo.kt"), found)
        assertTrue(
            found.isNotEmpty(),
            "the walk returned nothing, which a broken prune and a clean repo are indistinguishable from",
        )
    }

    @Test
    @DisplayName("a SOURCE package legitimately named build is not pruned")
    fun `a source directory named build is kept`(@TempDir root: Path) {
        // The prune is by directory NAME, so this pins the deliberate limit of that choice: a
        // package called `build` under src/ is real source and must stay in the census.
        write(root, "app/src/main/kotlin/company/build/Builder.kt")

        val found = relativeNames(root, FitnessPaths.walkKotlinFiles(root))

        assertEquals(setOf("app/src/main/kotlin/company/build/Builder.kt"), found)
    }

    @Test
    @DisplayName("the build-file walk is scoped by the same authority")
    fun `build file walk excludes non-source trees`(@TempDir root: Path) {
        write(root, "app/build.gradle.kts")
        write(root, "gradle/libs.versions.toml")
        write(root, "app/build/tmp/build.gradle.kts")
        write(root, ".gradle/settings.toml")

        val found = relativeNames(root, FitnessPaths.walkBuildFiles(root))

        assertEquals(setOf("app/build.gradle.kts", "gradle/libs.versions.toml"), found)
    }

    @Test
    @DisplayName("the real census still sees every module's sources")
    fun `real repository source count is not trivially empty`(@TempDir unused: Path) {
        // A guard that can only see nothing passes every other row here. This one row pins that
        // the pruning is not over-broad on the actual repository: it removes the 41 build
        // artefacts and keeps the source tree it is meant to measure.
        val v2 = FitnessPaths.v2Root()
        if (!Files.isDirectory(v2)) return // the override points elsewhere; other modules own that

        val found = FitnessPaths.walkKotlinFiles(v2)

        assertFalse(found.isEmpty(), "the census walked $v2 and found no Kotlin sources at all")
        assertTrue(
            found.size > 1_000,
            "expected the whole v2 source tree, saw ${found.size} files — the prune may be over-broad",
        )
        // `firstOrNull`, not `first`: JUnit's message argument is evaluated eagerly, so a `first`
        // inside the message throws NoSuchElementException on the SUCCESS path. That is how this
        // row failed before the prune was fixed — a green assertion reporting a red result.
        val survivor = found.firstOrNull { it.toString().replace('\\', '/').contains("/build/") }
        assertNull(survivor, "a build artefact survived the prune: $survivor")
    }
}