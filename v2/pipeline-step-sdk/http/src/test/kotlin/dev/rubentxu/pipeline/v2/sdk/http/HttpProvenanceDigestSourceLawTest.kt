package dev.rubentxu.pipeline.v2.sdk.http

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * B0.2 — the plugin provenance digest is computed by ONE shared implementation, not three copies.
 *
 * ## Why this law changed shape
 *
 * AUD-01 fixed the absolute-path defect in three near-identical copies of the digest walk. That
 * left the copies as three authorities: the next fix could land in one and miss two. B0.2 moves the
 * walk + framing + exclusion logic into `buildSrc` (`dev.rubentxu.pipeline.build.ProvenanceDigest`)
 * and reduces each build script to its module-specific wiring.
 *
 * The previous version of this law keyed on the literal token `endsWith("plugin-manifest.json")`
 * being PRESENT in each build script. After B0.2 that shape is exactly what must be ABSENT (a broad
 * suffix exclusion also drops `unrelated-plugin-manifest.json`), so the law is retargeted: the
 * scripts must DELEGATE, must declare the manifest exclusion by EXACT relative path and must never
 * re-derive the hashing.
 *
 * ## What this law does NOT do
 *
 * It does not compute a digest and it does not run Gradle. It pins the SOURCE of the wiring. The
 * behaviour of the shared class is proven by `buildSrc` tests that execute the same production
 * class (`ProvenanceDigestTest`, and a real Gradle task in `ProvenanceDigestGradleFixtureTest`).
 * A build script that became path-dependent without containing any of these tokens would evade
 * this law, which is the honest bound of a text law and is stated here rather than hidden.
 *
 * ## How to run this law without being lied to
 *
 * `gradlew :pipeline-step-sdk:http:test --tests 'HttpProvenanceDigestSourceLawTest'` can print
 * BUILD SUCCESSFUL and produce NO verdict about the current tree when the result is served
 * FROM-CACHE. Run with `--rerun-tasks`, or read the `Task :…:test` line and refuse a result whose
 * line says FROM-CACHE.
 */
@DisplayName("B0.2 — los plugins delegan el digest de procedencia en la clase compartida")
class HttpProvenanceDigestSourceLawTest {

    @Test
    fun `los build scripts delegan en la clase compartida y no llevan copia privada del algoritmo`() {
        assertTrue(
            PLUGIN_BUILD_SCRIPTS.isNotEmpty(),
            "no plugin build script was found from ${System.getProperty("user.dir")}: this law has " +
                "no subject and every green it produces would be vacuous",
        )
        assertTrue(
            Files.isRegularFile(SHARED_CLASS),
            "the shared implementation was not found at $SHARED_CLASS: the law has no subject",
        )

        val offenders = PLUGIN_BUILD_SCRIPTS.mapNotNull { script ->
            val text = stripComments(Files.readString(script))
            val problems = mutableListOf<String>()
            if (!text.contains("dev.rubentxu.pipeline.build.ProvenanceDigest")) {
                problems += "no delega en dev.rubentxu.pipeline.build.ProvenanceDigest " +
                    "(parece conservar una copia privada del algoritmo)"
            }
            FORBIDDEN_IN_SCRIPT.forEach { (token, why) ->
                if (text.contains(token)) problems += "$token ($why)"
            }
            if (problems.isEmpty()) null else "${script.parent.fileName} -> " + problems.joinToString("; ")
        }

        assertTrue(
            offenders.isEmpty(),
            "the plugin build scripts must delegate the provenance digest to the shared class and " +
                "must not re-derive hashing material from absolute paths:\n" +
                offenders.joinToString("\n") { "  - $it" },
        )
    }

    @Test
    fun `la exclusion del manifest es por ruta relativa EXACTA, nunca por sufijo`() {
        val offenders = PLUGIN_BUILD_SCRIPTS.mapNotNull { script ->
            val text = stripComments(Files.readString(script))
            val problems = mutableListOf<String>()
            if (!text.contains("META-INF/pipelinek/plugin-manifest.json")) {
                problems += "no declara la exclusión exacta META-INF/pipelinek/plugin-manifest.json"
            }
            if (text.contains("endsWith(\"plugin-manifest.json\")")) {
                problems += "exclusión amplia por sufijo: excluiría también unrelated-plugin-manifest.json"
            }
            if (problems.isEmpty()) null else "${script.parent.fileName} -> " + problems.joinToString("; ")
        }

        assertTrue(
            offenders.isEmpty(),
            "each plugin digest task must exclude its own generated documents by EXACT relative " +
                "path. A broad *plugin-manifest.json suffix silently drops unrelated manifests " +
                "from the identity:\n" +
                offenders.joinToString("\n") { "  - $it" },
        )
    }

    @Test
    fun `la clase compartida no hashea rutas absolutas ni excluye por sufijo`() {
        val text = stripComments(Files.readString(SHARED_CLASS))
        val problems = FORBIDDEN_IN_SHARED.mapNotNull { (token, why) ->
            token.takeIf { text.contains(it) }?.let { "$it ($why)" }
        }

        assertTrue(
            problems.isEmpty(),
            "the shared ProvenanceDigest must not build its hashed material from absolute paths, " +
                "and must exclude only by exact relative path:\n" +
                problems.joinToString("\n") { "  - $it" },
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

        val SHARED_CLASS: Path = sdkRoot().parent
            .resolve("buildSrc/src/main/java/dev/rubentxu/pipeline/build/ProvenanceDigest.java")

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
         * `error` is therefore not defensive noise: without it, this exact failure returns.
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
         * Tokens that mean the build script is re-deriving the hashing instead of delegating, or is
         * feeding absolute paths into the hashed material. Matched against comment-stripped source,
         * so the explanatory comments that MUST remain for readers cannot mask or trigger the law.
         */
        val FORBIDDEN_IN_SCRIPT: List<Pair<String, String>> = listOf(
            "sha256sum" to "vuelve a invocar sha256sum, que imprime el nombre de fichero que recibe",
            "MessageDigest" to "reimplementa el hashing en el script en vez de delegar",
            ".walkTopDown()" to "reimplementa el recorrido del árbol en el script en vez de delegar",
            ".absolutePath" to "ruta absoluta introducida en el material hasheado",
            ".getAbsolutePath" to "ruta absoluta introducida en el material hasheado",
            ".toAbsolutePath" to "ruta absoluta introducida en el material hasheado",
            "canonicalize" to "ruta absoluta canonizada introducida en el material hasheado",
        )

        val FORBIDDEN_IN_SHARED: List<Pair<String, String>> = listOf(
            ".absolutePath" to "ruta absoluta introducida en el material hasheado",
            ".getAbsolutePath" to "ruta absoluta introducida en el material hasheado",
            ".toAbsolutePath" to "ruta absoluta introducida en el material hasheado",
            "canonicalize" to "ruta absoluta canonizada introducida en el material hasheado",
            "sha256sum" to "vuelve a invocar sha256sum, que imprime el nombre de fichero que recibe",
            "endsWith(\"plugin-manifest.json\")" to "exclusión amplia por sufijo en vez de ruta exacta",
        )

        val BLOCK_COMMENT = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)

        /** `.` excludes a line terminator, so `.*` already ends at the break; no `$` needed. */
        val LINE_COMMENT_TAIL = Regex("(?<!:)//.*")

        fun stripComments(text: String): String =
            LINE_COMMENT_TAIL.replace(BLOCK_COMMENT.replace(text, ""), "")
    }
}
