package dev.rubentxu.pipeline.v2.application

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * BLOCK 2.5 / PK-R1 regression: `examples/example-directive-plugin` is byte-reproducible
 * across two consecutive `--rerun-tasks` builds on the same commit.
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
 * ## The fix being verified
 *
 * `computeDirectiveRelease` now excludes `META-INF/pipelinek/plugin-manifest.json` in addition
 * to the release-properties file it is writing. The digest is therefore a pure function of the
 * plugin's source-derived bytes: classes + the source-mirrored resources. Two builds on the same
 * commit produce the same SHA, whether or not the manifest from a previous run is sitting in
 * `resourcesDir`. The manifest is still packed into the JAR (it is the S6/I declaration the
 * admission gate reads); it just does not feed the digest this task measures.
 *
 * ## What this test asserts
 *
 * - Builds the example directive plugin **twice**, in succession, both invocations using
 *   `--rerun-tasks --no-build-cache` so every task runs from scratch.
 * - Reads the SHA-256 of each produced `example-directive-plugin-0.1.0.jar`.
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

    private fun pluginBuildDir(): Path = repoRoot().resolve("examples/example-directive-plugin/build/libs")

    private fun pluginGradlew(): Path = repoRoot().resolve("v2/gradlew")

    /**
     * Run the plugin's `jar` task from a clean state with `--rerun-tasks --no-build-cache`.
     *
     * The SDK repo (where the plugin resolves its `dev.rubentxu.pipeline.v2:pipeline-domain`
     * coordinate) lives at `v2/build/sdk-repo`. The SDK version is read by listing the published
     * `pipeline-domain` artifact directory — that is the same version the parent
     * `v2/build.gradle.kts` sets via `version = "..."`.
     *
     * The test is skipped (via JUnit `assumeTrue`) when the SDK has not been published in the
     * current module's build dir. `:pipeline-application:test` triggers `:publishSdkForExternalPlugin`
     * before its test classpath is realized (see `compileTestKotlin` upstream), so by the time
     * this test runs the SDK repo is normally populated.
     */
    private fun runPluginBuild(label: String): String {
        val gradlew = pluginGradlew().toString()
        val repoRoot = repoRoot()
        val sdkRepo = repoRoot.resolve("v2/build/sdk-repo").toAbsolutePath()
        val domainRepoRoot = sdkRepo.resolve("dev/rubentxu/pipeline/v2/pipeline-domain")
        assumeTrue(
            Files.isDirectory(domainRepoRoot),
            "BLOCK 2.5 test: SDK repo not published under $domainRepoRoot; " +
                "this regression depends on `:pipeline-application:test` having previously run " +
                "(`compileTestKotlin` upstream publishes via `:publishSdkForExternalPlugin`). " +
                "Skipping $label.",
        )

        // The SDK version is whatever `pipeline-domain/` happens to be published at.
        val sdkVersion = Files.newDirectoryStream(domainRepoRoot).use { stream ->
            stream.filter { Files.isDirectory(it) }
                .map { it.fileName.toString() }
                .singleOrNull()
                ?: error("BLOCK 2.5 test: no published pipeline-domain version under $domainRepoRoot")
        }

        val pluginDir = repoRoot.resolve("examples/example-directive-plugin").toAbsolutePath()
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
            "BLOCK 2.5 test: plugin build ($label) failed (exit=${proc.exitValue()}). Last 80 lines of output:\n" +
                output.lineSequence().toList().takeLast(80).joinToString("\n")
        }
        return output
    }

    private fun jarPath(): Path {
        val p = pluginBuildDir().resolve("example-directive-plugin-0.1.0.jar")
        assumeTrue(
            Files.exists(p),
            "BLOCK 2.5 test: directive plugin JAR not found at $p; " +
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
     * Two `--rerun-tasks --no-build-cache` builds on the same commit MUST produce the same JAR.
     *
     * The second invocation happens AFTER the first has already written `plugin-manifest.json` to
     * `build/resources/main/META-INF/pipelinek/plugin-manifest.json`. That is precisely the
     * "polluted resources directory" state `computeDirectiveRelease` had been including in the
     * digest input. With the fix in place, the second digest excludes the manifest and equals the
     * first one byte-for-byte.
     */
    @Test
    fun `directive plugin JAR SHA is identical across two --rerun-tasks builds`() {
        runPluginBuild("build 1")
        val firstSha = sha256Of(jarPath())

        // Simulate "another worker runs the same build later" without any manual cleaning.
        runPluginBuild("build 2")
        val secondSha = sha256Of(jarPath())

        assertEquals(
            firstSha,
            secondSha,
            "example-directive-plugin-0.1.0.jar SHA drifted between two " +
                "--rerun-tasks --no-build-cache builds on the same commit. The defect closed by " +
                "this test was a stale plugin-manifest.json in build/resources/main getting " +
                "included in `computeDirectiveRelease`'s digest input. If this test fails the " +
                "fix in examples/example-directive-plugin/build.gradle.kts (exclude " +
                "META-INF/pipelinek/plugin-manifest.json) has regressed.",
        )
    }
}
