package dev.rubentxu.pipeline.v2.application

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * BLOCK 2.5 / PK-R1 regression: every shipped example plugin is byte-reproducible across two
 * consecutive `--rerun-tasks` builds on the same commit.
 *
 * The class name still says `Directive` because that is where the defect was first reported and
 * where the fix was first applied. It was NOT renamed when the guarantee was widened: five live
 * references point at this name (two build-file comments, one build-script comment, one KDoc in
 * the contract suite), and renaming would have churned four files to buy zero additional
 * guarantee. The rows below carry the widened scope.
 *
 * ## The defect being closed
 *
 * Release-receipt v0.51.0-rc2 documents that two worktrees on the same commit produced
 * different JAR SHAs for `example-directive-plugin-0.1.0.jar`. The class files were
 * byte-identical; the only difference lived in the `releaseDigest` field embedded in
 * `META-INF/example-directive-release.properties` and `META-INF/pipelinek/plugin-manifest.json`.
 *
 * Root cause was in `examples/example-directive-plugin/build.gradle.kts` `computeDirectiveRelease`:
 * the task walked `resourcesDir` and excluded only the `release.properties` file it was writing.
 * A stale `plugin-manifest.json` left by a previous run of `emitDirectiveManifest` was therefore
 * included in the digest input set. With a clean resources directory the digest is one value;
 * with a polluted one (the post-`emitDirectiveManifest` state) it is a different one. The task
 * produced different values depending on incremental state, which makes the SHA drift worktree
 * by worktree and build by build.
 *
 * ## Why the scope was widened to all three
 *
 * The defect is a property of the DIGEST TASK, not of one plugin: `computeUppercaseRelease` and
 * `computeBlockRelease` are the same shape, and all three plugins are shipped inside the
 * distribution ZIP. Measured, by reverting each file to its pre-fix form and building twice in
 * succession (`--rerun-tasks --no-build-cache`, same commit, same worktree):
 *
 * ```text
 * plugin     pre-fix build 1            pre-fix build 2            drifted
 * uppercase  ab53b40e9fda52861...       26782dbe54b22f7d0...       YES
 * block      7639321f309f07b5...        24d2a1cb598b14d9...       YES
 * ```
 *
 * so leaving the two siblings unfixed shipped two of the three plugins whose recorded digest was
 * a function of build history rather than of the commit — which is what AGENTS.md's
 * TARGET-VERSION CANDIDATE LAW and the PRODUCT-GATE's distZip reproducibility requirement both
 * rest on.
 *
 * ## The fix being verified
 *
 * Each `compute<Name>Release` now excludes `META-INF/pipelinek/plugin-manifest.json` in addition
 * to the release-properties file it is writing. The digest is therefore a pure function of the
 * plugin's source-derived bytes: classes + the source-mirrored resources. Two builds on the same
 * commit produce the same SHA, whether or not the manifest from a previous run is sitting in
 * `resourcesDir`. The manifest is still packed into the JAR (it is the S6/I declaration the
 * admission gate reads); it just does not feed the digest this task measures.
 *
 * ## What this test asserts
 *
 * - Builds each example plugin **twice**, in succession, both invocations using
 *   `--rerun-tasks --no-build-cache` so every task runs from scratch.
 * - Reads the SHA-256 of each produced JAR.
 * - Asserts they are identical.
 *
 * If the exclusion is ever removed (or the manifest path changes without updating the exclusion),
 * this test fails before the SHA can silently drift again.
 *
 * ## Why this lives in `:pipeline-application:test`
 *
 * This module already owns `DirectivePluginContractSuiteTest`, which is the consumer of the JAR
 * this test produces. Co-locating the build-side regression with the consumer-side contract keeps
 * the two responsibilities of the same SHA visible in one place: the consumer-side test pins the
 * value, the producer-side test (this one) holds the value reproducible. The test publishes its
 * own SDK side via `:buildExternalDirectivePlugin`, so it depends on whatever `:pipeline-application:test`
 * already brings in (gradlew on PATH, the SDK published at `v2/build/sdk-repo`).
 */
@Timeout(value = 600, unit = TimeUnit.SECONDS)
class DirectivePluginShaReproducibilityTest {

    /**
     * The three example plugins that ship inside the distribution ZIP, each with the JAR name its
     * build produces. The JAR names are not derived from the directory name on purpose: they are
     * the literal archive entries, so a rename in the build script surfaces here as a missing file
     * rather than as a silent skip.
     */
    private data class ExamplePlugin(val dirName: String, val jarName: String, val releaseTask: String)

    private val plugins = listOf(
        ExamplePlugin("example-directive-plugin", "example-directive-plugin-0.1.0.jar", "computeDirectiveRelease"),
        ExamplePlugin("example-uppercase-plugin", "example-uppercase-plugin-0.1.0.jar", "computeUppercaseRelease"),
        ExamplePlugin("example-block-plugin", "example-block-plugin-0.1.0.jar", "computeBlockRelease"),
    )

    /**
     * Looked up BY NAME, never by index: a row that reads `plugins[1]` silently starts
     * certifying a different plugin the moment the list is reordered, and the failure would
     * surface as a plausible-looking drift rather than as the wrong subject.
     */
    private fun plugin(dirName: String): ExamplePlugin =
        plugins.firstOrNull { it.dirName == dirName }
            ?: error("no ExamplePlugin entry for $dirName; the list and the rows have diverged")

    /**
     * Repository root: two levels up from the `:pipeline-application` working directory
     * (Gradle default for module tests). Sibling layout matches `DirectivePluginContractSuiteTest`.
     */
    private fun repoRoot(): Path {
        val cwd = Path.of("").toAbsolutePath()
        // CWD is `v2/pipeline-application`; the repo root is two levels up.
        val candidates = listOf(
            cwd.resolve("../../").normalize(),
            cwd.resolve("../").normalize(),
        )
        return candidates.firstOrNull { Files.exists(it.resolve("examples/example-directive-plugin/build.gradle.kts")) }
            ?: error(
                "examples/example-directive-plugin/build.gradle.kts not found relative to $cwd; " +
                    "this test must run from the v2/pipeline-application module.",
            )
    }

    private fun pluginGradlew(): Path = repoRoot().resolve("v2/gradlew")

    /**
     * Run the plugin's `jar` task from a clean state with `--rerun-tasks --no-build-cache`.
     *
     * The SDK repo (where the plugin resolves its `dev.rubentxu.pipeline.v2:pipeline-domain`
     * coordinate) lives at `v2/build/sdk-repo`. The SDK version to use is the one this build just
     * published — that is `project.version` from the parent `v2/build.gradle.kts`, injected into
     * the test JVM as the `pipeline.domain.version` system property by
     * `v2/pipeline-application/build.gradle.kts` `tasks.test { ... }`.
     *
     * Reading the version from a system property (rather than from a directory listing of the
     * `pipeline-domain` artifact) is the only stable answer on a checkout that has accumulated
     * multiple published versions over time. The test is skipped (via JUnit `assumeTrue`) when
     * the SDK has not been published at the expected version — that would mean the upstream
     * `:publishSdkForExternalPlugin` (a transitive dep of `:buildExternalDirectivePlugin`, which
     * `:pipeline-application:test` depends on) did not run before the test.
     */
    private fun runPluginBuild(plugin: ExamplePlugin, label: String): String {
        val gradlew = pluginGradlew().toString()
        val repoRoot = repoRoot()
        val sdkRepo = repoRoot.resolve("v2/build/sdk-repo").toAbsolutePath()
        val domainRepoRoot = sdkRepo.resolve("dev/rubentxu/pipeline/v2/pipeline-domain")

        val sdkVersion = System.getProperty("pipeline.domain.version")
        assumeTrue(
            sdkVersion != null && Files.isDirectory(domainRepoRoot.resolve(sdkVersion)),
            "BLOCK 2.5 test: SDK not published at expected version under $domainRepoRoot; " +
                "expected pipeline.domain.version system property to be set by " +
                "`v2/pipeline-application/build.gradle.kts` `tasks.test { ... }` to " +
                "project.version, and `:publishSdkForExternalPlugin` (transitive dep of " +
                "`:buildExternalDirectivePlugin`, itself a dep of `:pipeline-application:test`) " +
                "to have populated the artifact. Skipping $label.",
        )

        val pluginDir = repoRoot.resolve("examples/${plugin.dirName}").toAbsolutePath()
        val pb = ProcessBuilder(
            gradlew,
            "-p", pluginDir.toString(),
            "--console=plain",
            "--no-daemon",
            "--no-build-cache",
            "--rerun-tasks",
            "-PsdkRepo=$sdkRepo",
            "-PsdkVersion=$sdkVersion",
            "jar",
        )
            .directory(repoRoot.toFile())
            .redirectErrorStream(true)

        val proc = pb.start()
        val output = proc.inputStream.bufferedReader().readText()
        val exit = proc.waitFor(540, TimeUnit.SECONDS)
        check(exit && proc.exitValue() == 0) {
            "BLOCK 2.5 test: plugin build ($label, ${plugin.dirName}) failed " +
                "(exit=${proc.exitValue()}). Last 80 lines of output:\n" +
                output.lineSequence().toList().takeLast(80).joinToString("\n")
        }
        return output
    }

    private fun jarPath(plugin: ExamplePlugin): Path {
        val p = repoRoot().resolve("examples/${plugin.dirName}/build/libs/${plugin.jarName}")
        assumeTrue(
            Files.exists(p),
            "BLOCK 2.5 test: ${plugin.dirName} JAR not found at $p; " +
                "the build invocation above was expected to produce it.",
        )
        return p
    }

    private fun sha256Of(p: Path): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update(Files.readAllBytes(p))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * The one claim all three rows below make, asserted once so the three rows cannot drift apart
     * in what they promise.
     *
     * The second invocation happens AFTER the first has already written `plugin-manifest.json` to
     * `build/resources/main/META-INF/pipelinek/plugin-manifest.json`. That is precisely the
     * "polluted resources directory" state the `compute<Name>Release` task had been including in
     * the digest input. With the exclusion in place, the second digest excludes the manifest and
     * equals the first one byte-for-byte.
     */
    private fun assertStableAcrossTwoRerunBuilds(plugin: ExamplePlugin) {
        runPluginBuild(plugin, "build 1")
        val firstSha = sha256Of(jarPath(plugin))

        // Simulate "another worker runs the same build later" without any manual cleaning.
        runPluginBuild(plugin, "build 2")
        val secondSha = sha256Of(jarPath(plugin))

        assertEquals(
            firstSha,
            secondSha,
            "${plugin.jarName} SHA drifted between two --rerun-tasks --no-build-cache builds " +
                "on the same commit. The defect closed by this row is a stale " +
                "plugin-manifest.json in build/resources/main getting included in " +
                "`${plugin.releaseTask}`'s digest input. If this row fails the exclusion of " +
                "META-INF/pipelinek/plugin-manifest.json in examples/${plugin.dirName}/" +
                "build.gradle.kts has regressed.",
        )
    }

    @Test
    fun `directive plugin JAR SHA is identical across two --rerun-tasks builds`() {
        assertStableAcrossTwoRerunBuilds(plugin("example-directive-plugin"))
    }

    @Test
    fun `uppercase plugin JAR SHA is identical across two --rerun-tasks builds`() {
        assertStableAcrossTwoRerunBuilds(plugin("example-uppercase-plugin"))
    }

    @Test
    fun `block plugin JAR SHA is identical across two --rerun-tasks builds`() {
        assertStableAcrossTwoRerunBuilds(plugin("example-block-plugin"))
    }
}
