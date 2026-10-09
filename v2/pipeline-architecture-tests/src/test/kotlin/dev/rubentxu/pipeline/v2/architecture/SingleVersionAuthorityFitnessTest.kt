package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * R-BUILD-02 — no subproject may declare its own `version`.
 *
 * ## The defect, measured
 *
 * `v2/build.gradle.kts:89-97` states the policy in its own comment:
 *
 * > Single-version provider. The root project.version is the SOLE authority for
 * > every subproject's publication version ... Subprojects inherit by default;
 * > we make the policy explicit and refuse per-subproject overrides. Any future
 * > subproject MUST NOT declare its own `version = "..."` — that is a
 * > release-time defect.
 *
 * Seven subprojects do exactly that. The consequence is visible in the admitted
 * candidate ZIP, whose entries read:
 *
 * ```text
 * pipelinek-0.48.0/lib/pipeline-application-0.48.0.jar
 * pipelinek-0.48.0/lib/pipeline-scripting-api-0.48.0.jar
 * pipelinek-0.48.0/lib/scm-git-0.36.0.jar          <-- NOT 0.48.0
 * pipelinek-0.48.0/lib/runtime-0.36.0.jar         <-- NOT 0.48.0
 * pipelinek-0.48.0/lib/api-0.36.0.jar             <-- NOT 0.48.0
 * pipelinek-0.48.0/lib/http-0.36.0.jar
 * pipelinek-0.48.0/lib/utilities-0.36.0.jar
 * ```
 *
 * `junit` is the odd one out in the opposite direction: it declares `0.1.0`,
 * which is its plugin release version and is intentional.
 *
 * ## Why the root policy does not stop it
 *
 * `subprojects { version = rootProject.version }` assigns during root evaluation,
 * but a subproject's own `version = "0.36.0"` executes when that subproject's
 * build script is evaluated, which is later. The per-subproject assignment wins.
 * Declaring the policy is therefore not the same as enforcing it; the comment
 * was asserting a property the build did not hold.
 *
 * ## Why this blocks the candidate
 *
 * PRODUCT IDENTITY LAW requires every identity surface to agree. The candidate
 * admits today because the ZIP root, the asset name and the product version all
 * say 0.48.0, and `candidateAdmission` inspects those — not the lib entry names.
 * So the gate is not wrong; it is narrower than the law. Shipping a candidate
 * whose archive contains two different product versions under one release train
 * is exactly the ambiguity that law exists to prevent, and a consumer reading
 * `scm-git-0.36.0.jar` has no way to tell whether that is a stale coordinate or
 * this train's build.
 *
 * ## Fidelity
 *
 * SOURCE-LEVEL, declared as such: the property is "no build script in the tree
 * assigns `version` to itself", which is decided by parsing and is fully legible
 * here. This row does not reimplement Gradle's evaluation order to prove the
 * override wins — that was measured directly from the admitted ZIP, which is the
 * artefact the law is about, and the measurement is recorded in the receipt
 * rather than simulated in a test.
 */
class SingleVersionAuthorityFitnessTest {

    /**
     * Resolves the `v2` build root. NOT the git root: `v2/settings.gradle.kts`
     * exists, so walking up for the first `settings.gradle.kts` lands on `v2`
     * itself. The first draft did that and then prefixed `v2/` again, producing
     * `v2/v2/pipeline-step-sdk/...` and failing the junit row with "not found" —
     * a RED for a broken path rather than for the version defect. Both rows below
     * now share this one root, so they cannot disagree about where the tree is.
     */
    private val buildRoot: File = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .firstOrNull { File(it, "pipeline-architecture-tests").isDirectory }
        ?: error("could not locate the v2 build root from ${System.getProperty("user.dir")}")

    @Test
    fun `only the root and the labelled junit exception declare a version`() {
        // The ROOT build script is the one place a `version = "..."` is legal: it is the
        // authority. Walking every build.gradle.kts including it would make the row fail
        // on the very declaration that establishes the policy, which is the shape of a
        // guard that cannot be satisfied. Skipped by exact parent, not by depth.
        //
        // `junit` is the deliberate exception and is allowed here RATHER than in a
        // separate row. A first draft put it in its own test that asserted junit DOES
        // declare 0.1.0, which contradicts this row asserting nobody does — the suite
        // then reported this row red with `junit/build.gradle.kts` as the offender while
        // the very same commit had removed the other seven. One rule, one row, with the
        // exception named inside it: an exception stated beside the rule it excepts
        // cannot drift away from it the way a second row can.
        val deliberateExceptions = setOf("pipeline-step-sdk/junit/build.gradle.kts")

        val offenders = buildRoot
            .walkTopDown()
            .filter { it.isFile && it.name == "build.gradle.kts" && it.parentFile != buildRoot }
            .filter { file ->
                // Only a TOP-LEVEL `version = "..."` counts. Indented occurrences
                // inside a task, a closure or a comment are not subproject
                // version declarations, and treating them as such would make
                // the row reject correct builds.
                file.readLines().any { line ->
                    val t = line.trim()
                    t.startsWith("version") && !t.startsWith("//") && Regex("""^version\s*=""")
                        .containsMatchIn(t)
                }
            }
            .map { it.relativeTo(buildRoot).path }
            .filterNot { it in deliberateExceptions }
            .sorted()
            .toList()

        assertTrue(
            offenders.isEmpty(),
            "R-BUILD-02: v2/build.gradle.kts:89-97 declares rootProject.version the SOLE " +
                "authority and calls a per-subproject `version = \"...\"` a release-time " +
                "defect, yet these build scripts assign their own version and therefore WIN " +
                "over subprojects { version = rootProject.version } (which ran earlier). " +
                "The admitted 0.48.0 candidate ZIP contains lib/scm-git-0.36.0.jar, " +
                "lib/runtime-0.36.0.jar and lib/api-0.36.0.jar beside its 0.48.0 jars, so one " +
                "release train ships two product versions. Remove the per-subproject " +
                "declarations so they inherit. The only allowed exception is junit's " +
                "plugin release version, which is written into junit-release.properties " +
                "and plugin-manifest.json: ${offenders.joinToString()}",
        )
    }
}

/**
 * `junit`'s `version = "0.1.0"` is a plugin release version, not a product version, and it is
 * the single declared exception in [SingleVersionAuthorityFitnessTest]. It is written into
 * `junit-release.properties` and `plugin-manifest.json`, where admission checks
 * `releaseVersion` against the artifact. This row exists so that a future change there is a
 * deliberate decision rather than a silent drift.
 */
class JunitPluginVersionExceptionTest {

    private val buildRoot: File = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .firstOrNull { File(it, "pipeline-architecture-tests").isDirectory }
        ?: error("could not locate the v2 build root from ${System.getProperty("user.dir")}")

    @Test
    fun `junit keeps its plugin release version`() {
        val junit = File(buildRoot, "pipeline-step-sdk/junit/build.gradle.kts")
        assertTrue(junit.isFile, "junit/build.gradle.kts not found")

        assertTrue(
            junit.readText().contains("version = \"0.1.0\""),
            "junit's plugin release version changed. If it now inherits the product version, " +
                "remove it from deliberateExceptions in SingleVersionAuthorityFitnessTest and " +
                "re-check that plugin-manifest.json releaseVersion still matches what admission " +
                "validates — otherwise the plugin would claim a releaseVersion its artifact " +
                "does not carry.",
        )
    }
}

