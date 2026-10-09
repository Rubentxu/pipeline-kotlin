package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * R-BUILD-01 — a plugin's provenance digest MUST NOT hash its own output.
 *
 * ## The defect this row was written for, measured
 *
 * Building `:pipeline-application:distZip` twice from the same clean tree at
 * `076982b9` produced two different archive digests:
 *
 * ```text
 * build 1: 0a341cb8f29c07da0c68a942f7d4ad637e91164f7cb8b66f3e5e6baeb8d3671e
 * build 2: 3d4caf89b5f1b6372124acedc60310b42325fa72dda7e994ca1fccd3261fcaf8
 * ```
 *
 * Localising it by per-file SHA-256 rather than by guessing narrowed 45 files to
 * exactly one: `lib/junit-0.1.0.jar`. Inside it, `META-INF/pipelinek/plugin-manifest.json`
 * carried `releaseDigest` `b90dd4f9…` on the first build and `52b0635f…` on the second.
 * Every other file in the archive was byte-identical.
 *
 * ## Why it happens
 *
 * `computeJunitDigest` hashes `build/classes/kotlin/main` plus `build/resources/main`.
 * That resources directory holds `junit-release.properties` AND the
 * `plugin-manifest.json` emitted by the PREVIOUS run — and that manifest embeds the
 * previous run's digest. The build's own task graph states the cycle outright:
 * `emitJunitManifest dependsOn computeJunitDigest`. So the digest of build N is baked
 * into a file that becomes an input to the digest of build N+1.
 *
 * The exclusion that exists — `it.absolutePath != excludedOutput` — omits only the
 * properties file. The header comment of that same task already promises the right
 * thing: *"excluding the provenance file itself to avoid chicken-and-egg"*. The
 * intent was correct; the implementation covered one of the two files. A comment is
 * not a mechanism, which is why this row exists.
 *
 * ## Scope: JUnit only, and the other three were checked rather than assumed
 *
 * An earlier draft of this row asserted the property across all four SDK plugins. It
 * passed immediately, which made it a guard for nothing: the sibling plugins (`scm-git`,
 * `utilities`, `http`) do not use the shell pipeline at all. They call the shared
 * `dev.rubentxu.pipeline.build.ProvenanceDigest` with an explicit `*ExcludedResourcePaths`
 * set, and `http` carries a comment stating that its three generated documents are
 * excluded precisely so they "cannot feed back into the digest". They were never
 * affected, and a row that claimed otherwise would have been a false summary of the
 * codebase. The rule below is therefore stated over the plugins that actually own a
 * digest computation, and it fails if a future one appears without the exclusion.
 *
 * ## Why a fitness row and not only the reproducibility check
 *
 * `CandidateReproducibilityTest` verifies the MATERIALIZER is a pure function of the
 * bytes it measures, and its KDoc is explicit that the packaging side is "verified
 * out-of-band against the real Gradle build". That out-of-band check is exactly what
 * stopped this from being caught: it ran once, by a person, on a quiet machine. Nothing
 * in the suite fails if the exclusion set is narrowed again. This row is what makes the
 * exclusion set a checked property rather than a comment.
 *
 * ## Fidelity
 *
 * This is a SOURCE-LEVEL check over the real build script, classified as such in the
 * class KDoc. It cannot execute a Gradle build — the property it guards is "the
 * digest's input set excludes the digest's own output", which is decided when the input
 * list is built and is therefore legible in the source. The behavioural half (two builds,
 * identical bytes) remains an out-of-band obligation recorded in the candidate receipt,
 * not simulated here: a hermetic re-implementation of the hashing would certify the
 * re-implementation, which is the failure this repository keeps paying for.
 */
class PluginProvenanceDigestSelfReferenceFitnessTest {

    private val repoRoot: File = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: error("could not locate the repository root from ${System.getProperty("user.dir")}")

    private val junitBuildScript: File = File(repoRoot, "pipeline-step-sdk/junit/build.gradle.kts")

    @Test
    fun `the junit provenance digest excludes both documents it feeds`() {
        assertTrue(junitBuildScript.isFile, "junit/build.gradle.kts not found under $repoRoot")
        val text = junitBuildScript.readText()

        assertTrue(
            text.contains("computeJunitDigest"),
            "the digest task this row guards is gone; if it was renamed or replaced, repoint " +
                "this row rather than letting it pass on a task it no longer describes",
        )

        // The properties file is where the digest is WRITTEN. The manifest is where the
        // digest is READ back from. Both travel inside the same resource directory that the
        // task hashes, so both must be outside its input set. Excluding only one of the two
        // is the measured defect.
        //
        // The row checks the EXCLUSION SET rather than one variable name, on purpose. A draft
        // grepped for a literal `excluded = ... plugin-manifest.json` and passed a build that
        // still had the bug while rejecting a correct one that spelled the set differently — a
        // guard on the spelling of the fix rather than on the property. Reading the task's own
        // block, from its registration to the command that consumes the file list, keeps the
        // row about what the task excludes instead of how the author named it.
        //
        // The anchor is the TASK NAME inside `tasks.register<Exec>("computeJunitDigest")`, not
        // a `computeJunitDigest = tasks.register` shape. The first draft used the assignment
        // form, which this script never had: `substringAfter` silently returned the whole file
        // and `substringBefore("commandLine")` then cut the region short, so the row inspected a
        // block that did not contain the walk it claimed to be guarding and reported it clean.
        // A guard that cannot find its subject is worse than no guard — it looks like coverage.
        // Anchoring on the quoted task name survives both `register("name")` and
        // `register<T>("name")` spellings.
        // `requireNotNull` rather than a bare assertTrue: JUnit's assertTrue carries no Kotlin
        // contract, so the nullable result would not smart-cast and the next line would not
        // compile. The precondition is a parse of a file this row already located, not an
        // assertion about the product, so failing the run with a located message is correct.
        val registration = requireNotNull(
            Regex("""tasks\.register(?:<[^>]*>)?\("computeJunitDigest"\)""").find(text),
        ) {
            "could not find the computeJunitDigest task registration in ${junitBuildScript.path}; " +
                "if it was renamed or retyped, repoint this row instead of letting it pass on a " +
                "task it no longer describes"
        }
        val digestBlock = text.substring(registration.range.last)
            .substringBefore("commandLine")

        assertTrue(
            digestBlock.isNotBlank(),
            "the region read for the digest task is empty; the anchors are wrong",
        )

        assertTrue(
            digestBlock.contains("junit-release.properties") || digestBlock.contains("excludedOutput"),
            "the digest must still exclude the properties file it writes",
        )
        // The manifest must enter the exclusion as CODE, not as prose.
        //
        // Two earlier drafts failed here in opposite directions and both failures are worth
        // recording. Asserting the literal `plugin-manifest.json` rejected a CORRECT fix, because
        // the fix names a val (`junitManifest`) declared further down rather than spelling the
        // path inside the task. Loosening it to "any identifier containing manifest" then matched
        // `emitJunitManifest` — a word that appears in the task's own PRE-EXISTING comment, on
        // the buggy file and the fixed file alike — so the row went green against the very defect
        // it was written for. That is the false green this repository has paid for before.
        //
        // The check therefore strips comments first and then requires the manifest identifier to
        // appear in the surviving code, which is the only form in which excluding it can have any
        // effect at all.
        val codeOnly = digestBlock
            .lines()
            .map { it.substringBefore("//") }
            .joinToString("\n")
        val manifestInCode = codeOnly.contains("plugin-manifest.json") ||
            Regex("""\b\w*[Mm]anifest\w*\b""").containsMatchIn(codeOnly)
        assertTrue(
            manifestInCode,
            "R-BUILD-01: computeJunitDigest hashes build/resources/main, which still contains " +
                "the plugin-manifest.json emitted by the PREVIOUS build. That manifest embeds " +
                "the previous releaseDigest, so the digest of build N is an input to the digest " +
                "of build N+1 and the distribution ZIP is not reproducible " +
                "(measured: 0a341cb8… vs 3d4caf89… at 076982b9). The manifest must be in the " +
                "excluded set as code, exactly as the task's own header comment already promises " +
                "(a mention inside a comment does not exclude anything).",
        )

        // The exclusion must actually REACH the walk, not merely be declared. A set that is
        // built and then ignored is the same defect wearing a different hat.
        assertTrue(
            Regex("""!\s*in\s+\w*[Ee]xcluded\w*|!=\s*\w*[Ee]xcluded\w*|exclude\(\s*\w*[Ee]xcluded\w*""")
                .containsMatchIn(digestBlock),
            "R-BUILD-01: the exclusion set is declared but the walk does not apply it; a declared " +
                "exclusion that never reaches the filter is not an exclusion",
        )
    }
}
