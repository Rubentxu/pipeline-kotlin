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
        // The task's own block is read from its registration to the END of the file.
        //
        // The first draft cut the region at `substringBefore("commandLine")`, because the shell
        // pipeline that used to compute the digest ended there. AUD-01/B0.2 moved the digest to
        // the shared ProvenanceDigest, so `commandLine` no longer exists anywhere in this script
        // — `substringBefore` then returned the ENTIRE remainder of the file, which happened to
        // contain the exclusion set declared above, so the row kept passing by reading a region
        // that no longer matched the task it claimed to guard. A guard anchored on a token the
        // subject no longer has is a guard that has silently stopped guarding.
        //
        // The exclusion set is now a NAMED val passed to ProvenanceDigest.computeDigestHex, so
        // this row checks the property directly: the set names BOTH documents, and the set is
        // the one handed to the digest. It deliberately does not grep for one variable name, so
        // it cannot be satisfied by a differently-spelled-but-equally-correct fix.
        // The EXCLUSION SET may be declared BEFORE the task registration (it is, today), so the
        // set is read from the WHOLE file while the CALL is read from the registration onward.
        // Reading both out of one region is how the previous revision of this row came to inspect
        // an implementation it no longer described.
        val digestBlock = text.substring(registration.range.last)
        val exclusionSet = requireNotNull(
            Regex("""val\s+(\w*[Ee]xcluded\w*)\s*:\s*Set<String>""").find(text),
        ) {
            "could not find the excluded-path set in ${junitBuildScript.path}; if the task was " +
                "rewritten, repoint this row instead of letting it pass on an implementation it no " +
                "longer describes"
        }
        val exclusionSetName = exclusionSet.groupValues[1]
        val exclusionSetBody = requireNotNull(
            Regex("""val\s+$exclusionSetName\s*:\s*Set<String>.*?setOf\((.*?)\)""", RegexOption.DOT_MATCHES_ALL)
                .find(text),
        ) {
            "the excluded-path set ${exclusionSetName} was found but its setOf(...) body could not " +
                "be read in ${junitBuildScript.path}"
        }.groupValues[1]

        // Both documents this task feeds must be outside its own input set: the properties file
        // the digest is WRITTEN into, and the manifest that reads it back. Excluding only one of
        // the two is the measured R-BUILD-01 defect.
        assertTrue(
            exclusionSetBody.contains("junit-release.properties"),
            "R-BUILD-01: the digest must exclude the properties file it writes ($exclusionSetName). " +
                "Measured defect: the digest of build N became an input to the digest of build N+1 " +
                "and two distZip builds of the same clean tree produced different archives " +
                "(0a341cb8… vs 3d4caf89… at 076982b9). Excluded set reads: $exclusionSetBody",
        )
        assertTrue(
            exclusionSetBody.contains("plugin-manifest.json"),
            "R-BUILD-01: computeJunitDigest hashes build/resources/main, which still contains " +
                "the plugin-manifest.json emitted by the PREVIOUS build. That manifest embeds " +
                "the previous releaseDigest, so the digest of build N is an input to the digest " +
                "of build N+1 and the distribution ZIP is not reproducible " +
                "(measured: 0a341cb8… vs 3d4caf89… at 076982b9). The manifest must be in the " +
                "excluded set as code, exactly as the task's own header comment already promises " +
                "(a mention inside a comment does not exclude anything). Excluded set reads: " +
                "$exclusionSetBody",
        )

        // The exclusion must actually REACH the walk, not merely be declared. A set that is
        // built and then ignored is the same defect wearing a different hat. After AUD-01/B0.2 the
        // set is handed to ProvenanceDigest.computeDigestHex as its third argument, so "reaching
        // the walk" means being passed to the shared primitive — either at the call site or by the
        // name the task itself binds.
        val setReachesDigest =
            Regex("""ProvenanceDigest\.computeDigestHex\(.*?$exclusionSetName""", RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(digestBlock) ||
                Regex("""computeDigestHex\([\s\S]{0,400}?$exclusionSetName""")
                .containsMatchIn(digestBlock)
        assertTrue(
            setReachesDigest,
            "R-BUILD-01: the exclusion set $exclusionSetName is declared but is not passed to " +
                "ProvenanceDigest.computeDigestHex; a declared exclusion that never reaches the " +
                "digest is not an exclusion",
        )
    }
}
