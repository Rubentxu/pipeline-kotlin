package dev.rubentxu.pipeline.v2.sdk.http

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * AUD-01 — the plugin provenance digest must be computed over NORMALISED RELATIVE paths.
 *
 * ## Why a fitness test, when the sibling unit test was supposed to carry this
 *
 * `HttpProvenanceDigestPathIndependenceTest` proves that the two FORMULATIONS differ: hashing
 * absolute paths is path-dependent, hashing relative paths is not. That is a true and useful
 * claim, and it is **not enough**.
 *
 * Measured here, not assumed: with the build script MUTATED back to absolute paths —
 * `"${file.absolutePath}"` in place of the relativised form — the sibling test still reported
 * `tests="4" failures="0"`. It stayed green while the defect it was written to prevent was back in
 * the build.
 *
 * The reason is structural, not an oversight: the digest is computed inside a Gradle `doLast`, and
 * that closure is not on this module's test classpath. A unit test of the FORMULA cannot observe
 * which formula the TASK uses. Shipping only that test would have produced a green that certified
 * nothing about the artefact whose provenance it claims to protect.
 *
 * So this law reads the build scripts as text and refuses the shape that caused the defect. It is
 * the same trade the S6 runtime law made — keying on the property "no plugin digest reads an
 * absolute path" — and it fails in the direction that matters.
 *
 * ## What this law does NOT do
 *
 * It does not compute a digest, and it does not run Gradle. It pins the SOURCE. A build script that
 * became path-dependent without containing any of these tokens would evade it, which is the honest
 * bound of a text law and is stated here rather than hidden.
 *
 * ## How to run this law without being lied to
 *
 * `gradlew :pipeline-step-sdk:http:test --tests 'HttpProvenanceDigestSourceLawTest'` can print
 * BUILD SUCCESSFUL and produce NO verdict about the current tree. Measured here: with
 * `classesDir.absolutePath` reintroduced into `utilities`, deleting the result XML and re-running
 * gave `test FROM-CACHE` — Gradle restored the cached PASS from the previous, correct tree. Two
 * consecutive "GREEN" verdicts in this slice were that artefact and nothing else.
 *
 * Deleting the result XML is not enough, because the cache lives above the module. Run with
 * `--rerun-tasks`, or read `Task :…:test` in the log and refuse to believe a result whose line says
 * FROM-CACHE. This is the harness-fidelity rule about result truth, arriving here by measurement.
 */
@DisplayName("AUD-01 — ningún digest de procedencia de plugin se calcula sobre rutas absolutas")
class HttpProvenanceDigestSourceLawTest {

    @Test
    fun `ningún build script de plugin hashea rutas absolutas en su digest de procedencia`() {
        // The subject must not be empty. A law over zero files is a law over nothing, and it would
        // pass for the rest of its life.
        assertTrue(
            PLUGIN_BUILD_SCRIPTS.isNotEmpty(),
            "no plugin build script was found from ${System.getProperty("user.dir")}: this law has " +
                "no subject and every green it produces would be vacuous",
        )

        val offenders = PLUGIN_BUILD_SCRIPTS.mapNotNull { script ->
            val text = stripComments(Files.readString(script))
            val hits = FORBIDDEN.mapNotNull { (token, why) ->
                token.takeIf { text.contains(it) }?.let { "$it ($why)" }
            }
            if (hits.isEmpty()) null else "${script.parent.fileName} -> " + hits.joinToString("; ")
        }

        assertTrue(
            offenders.isEmpty(),
            "these plugin build scripts compute their provenance digest from absolute paths, " +
                "so the same bytes hash differently in two checkouts and a receipt bound to a SHA " +
                "cannot prove the artefact it names:\n" +
                offenders.joinToString("\n") { "  - $it" } +
                "\n\nHash sorted (relative path, content) pairs with MessageDigest instead. " +
                "See AUD-01 in computeHttpDigest for the measured before/after.",
        )
    }

    /**
     * The second half of the law, and it is a separate half because it failed separately.
     *
     * Path-independence was already true when the digest still moved on a manifest change. The
     * manifest carries `releaseDigest`, so hashing it is a fixed-point loop: emit manifest -> it
     * states the digest -> re-hash changes the manifest -> the digest moves. AUD-01 excluded the
     * `.properties` file and not the manifest, so the digest was stable only while the manifest
     * happened to be byte-identical.
     *
     * Measured: appending one byte to `plugin-manifest.json` moved utilities 6c035f25 -> 5efa299f,
     * and RESTORING that byte did not bring 6c035f25 back, because the manifest had by then been
     * regenerated into the hashed tree. The property was luck, not code.
     *
     * The mutation that kills this: drop `!it.name.endsWith("plugin-manifest.json")` from the
     * walk filter. Verified that the mutation is detectable by re-appending the byte and observing
     * the digest move.
     */
    @Test
    fun `el digest de procedencia excluye el manifest que lo transporta`() {
        val offenders = PLUGIN_BUILD_SCRIPTS.mapNotNull { script ->
            val text = stripComments(Files.readString(script))
            if (text.contains("endsWith(\"plugin-manifest.json\")")) {
                null
            } else {
                "${script.parent.fileName} -> the walk does not exclude plugin-manifest.json"
            }
        }

        assertTrue(
            offenders.isEmpty(),
            "these plugin digest tasks hash the plugin manifest, which carries `releaseDigest`. " +
                "That is a fixed-point loop: the digest depends on the manifest, the manifest " +
                "states the digest, and neither settles. Exclude the manifest from the walk:\n" +
                offenders.joinToString("\n") { "  - $it" },
        )
    }

    private companion object {
        /**
         * Names, not a glob: a glob would widen silently the day a fourth plugin appears, which is
         * exactly the failure mode this repository keeps paying for.
         */
        val PLUGIN_BUILD_SCRIPTS: List<Path> = listOf(
            moduleDir("http"),
            moduleDir("scm-git"),
            moduleDir("utilities"),
        ).map { it.resolve("build.gradle.kts") }.filter { Files.isRegularFile(it) }

        /**
         * Resolves the SDK root by WALKING UP until the three module directories are all present.
         *
         * The obvious `System.getProperty("user.dir")` is wrong here and it was measured, not
         * guessed: Gradle does not run the test JVM with the module directory as its working
         * directory, so `here.parent.resolve("scm-git")` resolved to a path that does not exist.
         * `filter { Files.isRegularFile(it) }` then silently dropped all three entries and the law
         * passed over an EMPTY subject — a green with no subject at all, which is the worst shape a
         * fitness test can take.
         *
         * `require` is therefore not defensive noise: without it, this exact failure returns.
         */
        fun sdkRoot(): Path {
            var candidate: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
            while (candidate != null) {
                if (listOf("http", "scm-git", "utilities").all { Files.isDirectory(candidate!!.resolve(it)) }) {
                    return candidate
                }
                candidate = candidate.parent
            }
            error(
                "could not locate the plugin SDK root from ${System.getProperty("user.dir")}: no " +
                    "ancestor holds http/, scm-git/ and utilities/ together",
            )
        }

        fun moduleDir(name: String): Path = sdkRoot().resolve(name)

        /**
         * Tokens, not the whole property, and the token set was widened by MEASUREMENT rather
         * than by taste.
         *
         * The first version of this law listed only `it.absolutePath` / `file.absolutePath`, and
         * the mutation `listOf(classesDir to classesDir.absolutePath)` SURVIVED it: a real
         * absolute path reintroduced into the hashed material, green. A law that can only be
         * defeated by typing its tokens verbatim is not a law, it is a grep. The widened set
         * kills that mutation because it names the operation, not the receiver.
         *
         * `sha256sum $all` is kept as the original crime; the other three entries cover the same
         * defect re-expressed in Kotlin. They are matched against comment-stripped source, so the
         * explanatory comments that MUST remain for readers cannot mask or trigger the law.
         */
        val FORBIDDEN: List<Pair<String, String>> = listOf(
            "sha256sum \$all" to "la forma original: sha256sum imprime el nombre de fichero que recibe",
            ".absolutePath" to "ruta absoluta introducida en el material hasheado",
            ".getAbsolutePath" to "ruta absoluta introducida en el material hasheado",
            ".toAbsolutePath" to "ruta absoluta introducida en el material hasheado",
            "canonicalize" to "ruta absoluta canonizada introducida en el material hasheado",
        )

        val BLOCK_COMMENT = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)

        /** `.` excludes a line terminator, so `.*` already ends at the break; no `$` needed. */
        val LINE_COMMENT_TAIL = Regex("(?<!:)//.*")

        fun stripComments(text: String): String =
            LINE_COMMENT_TAIL.replace(BLOCK_COMMENT.replace(text, ""), "")
    }
}

