package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors

/**
 * The trust model of a `.pipeline.kts` is written ONCE, and what is written
 * is TRUE.
 *
 * ## The guarantee
 *
 * A `.pipeline.kts` is CODE: the product compiles it and runs its top-level
 * body inside the product's process, with the product's classpath and the
 * operator's permissions. An operator has to read that before pointing the
 * product at a script they did not write, so the help declares it.
 *
 * The model is declared in exactly ONE place. A model stated twice is two
 * claims free to disagree, and the one that lies is the one nobody re-reads.
 * That is not hypothetical here: the model used to live in a KDoc bullet of
 * `fun main` AND in a comment above the `validate` branch, the two said
 * different things, and the product did NEITHER — `validate` compiled the
 * script AND evaluated it, so the author's body ran inside the validating
 * process.
 *
 * ## Why the instrument reads the module's own sources
 *
 * The guarantee is about WHERE the model lives, and no runtime observation can
 * answer that: a program that prints the model twice prints it twice whether
 * the second copy is in a source file or in a string built at runtime. The
 * only thing that can answer it is the source itself.
 *
 * So the instrument reads the module's own `.kt` files and asks two literal
 * questions:
 *
 *  - does every sentence of the model appear in exactly ONE file, and is that
 *    file the one that declares its owner?
 *  - does the OLD wording — the promise that `validate` starts nothing —
 *    appear in NO file at all?
 *
 * The second question is not redundant with the first. The old wording is not
 * a sentence of the model; it is a PROMISE about the product that was false,
 * and a false promise can sit in a file that says nothing else wrong.
 *
 * The forbidden wording is narrow on purpose: other files legitimately say
 * that a stage, a step or a pass never starts. Only the two spellings that
 * claimed the PRODUCT starts nothing are forbidden.
 */
class ScriptTrustTest {

    @Test
    fun `the help declares the trust model of a script`() {
        val help = renderHelp()

        assertTrue(
            help.contains(ScriptTrust.HEADING),
            "the help does not declare the trust model: it renders no '${ScriptTrust.HEADING}'",
        )
        ScriptTrust.LINES.forEach { line ->
            assertTrue(
                help.contains(line),
                "the help does not declare this line of the model: '$line'",
            )
        }
    }

    @Test
    fun `the model is written in exactly one file of the module, and that file owns it`() {
        val sources = moduleSources()

        (ScriptTrust.LINES + ScriptTrust.HEADING).forEach { sentence ->
            val owners = sources.filter { it.text.contains(sentence) }

            assertEquals(
                1,
                owners.size,
                "the model is written in ${owners.size} files " +
                    "(${owners.joinToString { it.path.fileName.toString() }}): '$sentence'",
            )
            assertTrue(
                owners.single().text.contains("object ScriptTrust"),
                "the model is written in a file that does not own it: ${owners.single().path}",
            )
        }
    }

    @Test
    fun `no source of the module promises that validate starts nothing`() {
        val sources = moduleSources()

        FORBIDDEN_PROMISES.forEach { promise ->
            val owners = sources.filter { it.text.contains(promise, ignoreCase = true) }

            assertTrue(
                owners.isEmpty(),
                "the old promise '$promise' is still in " +
                    "${owners.joinToString { it.path.fileName.toString() }}",
            )
        }
    }

    /**
     * The help as the operator reads it.
     */
    private fun renderHelp(): String {
        val buffer = StringBuilder()
        printUsage(buffer)
        return buffer.toString()
    }

    /**
     * Every `.kt` file of this module's own sources.
     *
     * The module root is found by walking up from the working directory: the
     * sources may be reached from the module directory or from the repository
     * root, and a test that only worked from one of them would be a test that
     * only runs in one checkout.
     */
    private fun moduleSources(): List<SourceFile> {
        val sourceRoot = moduleSourceRoot()
        return Files.walk(sourceRoot).use { paths ->
            paths
                .filter { it.fileName.toString().endsWith(".kt") }
                .map { SourceFile(it, Files.readString(it)) }
                .collect(Collectors.toList())
        }
    }

    /**
     * The root of this module's `src/main/kotlin`.
     */
    private fun moduleSourceRoot(): Path {
        var candidate: Path? = Paths.get("").toAbsolutePath()
        while (candidate != null) {
            MODULE_RELATIVE_PATHS.forEach { relative ->
                val packageDir = candidate.resolve(relative).resolve(PACKAGE_PATH)
                if (Files.isDirectory(packageDir)) {
                    return candidate.resolve(relative)
                }
            }
            candidate = candidate.parent
        }
        throw IllegalStateException(
            "the sources of the application module were not found from '${Paths.get("").toAbsolutePath()}'",
        )
    }

    /**
     * One source file and its text.
     */
    private data class SourceFile(val path: Path, val text: String)

    private companion object {
        /** The package every file of this module lives under. */
        const val PACKAGE_PATH = "dev/rubentxu/pipeline/v2/application"

        /**
         * Where the module's `src/main/kotlin` sits, relative to a directory
         * the test walks up from: inside the module directory itself, or
         * inside the repository that holds it.
         */
        val MODULE_RELATIVE_PATHS = listOf(
            "src/main/kotlin",
            "pipelinek/pipeline-application/src/main/kotlin",
        )

        /**
         * The two spellings of the old promise, in the two places it lived.
         *
         * They are forbidden because they were FALSE: `validate` evaluates
         * the script, so the author's body runs inside it. Other files of the
         * module legitimately say that a stage, a step or a pass never starts,
         * and those are not forbidden — the promise is about the PRODUCT, not
         * about a part of a run.
         */
        val FORBIDDEN_PROMISES = listOf(
            "never starts a process",
            "never starts processes",
        )
    }
}
