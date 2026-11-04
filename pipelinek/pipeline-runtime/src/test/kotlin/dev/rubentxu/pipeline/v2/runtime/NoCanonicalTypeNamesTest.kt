package dev.rubentxu.pipeline.v2.runtime

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * No type of the UNPUBLISHED plane is named `Canonical*`, and no file of it either.
 *
 * ## The guarantee
 *
 * In every module that does NOT publish an ABI dump, no type declaration and no
 * source file carries the `Canonical` prefix.
 *
 * ## Why the prefix was retired
 *
 * The prefix used to mean "this belongs to the engine" — the durable spine that
 * is THE way a run executes, as opposed to the scripted frontend that runs the
 * same steps. That distinction was real, but the prefix carried it in every
 * single name, and a reader could no longer tell a type that IS the engine from
 * a type that merely LIVES next to it. `CanonicalRunRequest` was not more
 * canonical than `RunRequest`; it was only the request the durable run is given.
 *
 * What replaced the prefix is the name itself: the type says what it IS
 * (`RuntimeContext`, `RunState`, `StageContinuation`, `CursorAdvanceOwner`), and
 * the module says where it LIVES. The distinction survives; the noise does not.
 *
 * ## Why the published plane is exempt
 *
 * `pipeline-domain` publishes `CanonicalToDescriptorAdapter`,
 * `CanonicalBlockShellScope`, `CanonicalBodyExecutionProjection`,
 * `CanonicalSecretHandle` and `CanonicalPlainSecretHandle`. Renaming them is a
 * VERSIONED ABI BREAK, and the product has not authorised one: the published
 * surface may only change under an explicit, versioned decision. So those names
 * stay, and this test is what makes that exemption visible instead of silent.
 *
 * A module that publishes an ABI dump is detected by the presence of that dump,
 * not by a list kept in this file: the list would rot the day a module is
 * published or unpublished, and a stale list would either let a leak through or
 * forbid a legitimate name.
 *
 * ## What this test looks at
 *
 * It looks at CODE, not at prose. Comment lines are dropped before the search,
 * because the word "canonical" is legitimate DOMAIN VOCABULARY in this codebase:
 * `canonicalStepIds`, `canonicalBodyStepIds` and `NonCanonicalStep` name the
 * canonical CORE step set (the one the engine owns, against the external plugin
 * steps), and that meaning is anchored by the published constant
 * `BodyExecutionOwner.CANONICAL_ENGINE`. Those stay, and so does every sentence
 * that explains them.
 *
 * What may not come back is the PREFIX on a type NAME, in the plane where the
 * prefix was retired.
 */
class NoCanonicalTypeNamesTest {

    @Test
    @DisplayName("no type and no file of the unpublished plane carries the Canonical prefix")
    fun `no type and no file of the unpublished plane carries the Canonical prefix`() {
        val offences = unpublishedModules().flatMap { module ->
            val published = publishedModules().contains(module.fileName.toString())
            moduleSources(module).flatMap { source ->
                val offences = mutableListOf<Offence>()
                if (!published && source.path.fileName.toString().startsWith(PREFIX)) {
                    offences += Offence(
                        file = source.path,
                        number = 0,
                        detail = "the file is named '${source.path.fileName}'",
                    )
                }
                if (!published) {
                    codeLines(source).forEach { (number, line) ->
                        val declaration = typeDeclarationNamed(line)
                        if (declaration != null && declaration.startsWith(PREFIX)) {
                            offences += Offence(
                                file = source.path,
                                number = number,
                                detail = "the type is named '$declaration'",
                            )
                        }
                    }
                }
                offences
            }
        }

        assertEquals(
            emptyList<Offence>(),
            offences,
            buildString {
                append("the unpublished plane carries the '$PREFIX' prefix in ${offences.size} place(s).")
                append(" The prefix used to mark 'this belongs to the engine', and it marked it so")
                append(" widely that a reader could not tell a type that IS the engine from a type")
                append(" that merely lives next to it. Name the type after what it IS; the module")
                append(" already says where it lives.\n")
                offences.forEach { offence ->
                    val where = if (offence.number == 0) "" else ":${offence.number}"
                    append("  ${offence.file.fileName}$where — ${offence.detail}\n")
                }
            },
        )
    }

    /**
     * The name of the type [line] declares, or null when it declares none.
     *
     * Only a DECLARATION counts: a mention of a type in a signature, a cast or a
     * call is not a new name, and the published plane's names are still
     * mentioned all over this codebase.
     */
    private fun typeDeclarationNamed(line: String): String? {
        val match = DECLARATION.find(line) ?: return null
        return match.groupValues[1]
    }

    /**
     * The lines of [source] that are CODE, each with its number in the FILE.
     *
     * A comment line is dropped: a KDoc may legitimately NAME a type while
     * explaining why the type is not called that any more. What the plane may
     * not do is DECLARE one.
     *
     * The number is the line's position in the file, not in the filtered list:
     * a report that names a line the reader cannot open is a report nobody can
     * act on.
     */
    private fun codeLines(source: SourceFile): List<Pair<Int, String>> =
        source.text.lines()
            .mapIndexedNotNull { index, line ->
                val trimmed = line.trimStart()
                val isComment = trimmed.startsWith("*") ||
                    trimmed.startsWith("//") ||
                    trimmed.startsWith("/*")
                if (isComment) null else (index + 1) to line
            }

    /** Every `.kt` file of [module], in its main and its test source sets. */
    private fun moduleSources(module: Path): List<SourceFile> =
        listOf("src/main/kotlin", "src/test/kotlin")
            .map { module.resolve(it) }
            .filter { Files.isDirectory(it) }
            .flatMap { root ->
                Files.walk(root).use { paths ->
                    paths
                        .filter { it.fileName.toString().endsWith(".kt") }
                        .map { SourceFile(it, Files.readString(it)) }
                        .collect(Collectors.toList())
                }
            }

    /**
     * The modules of the repository that do NOT publish an ABI dump.
     *
     * A module that publishes one has a frozen, versioned surface: renaming a
     * published type is a versioned break, which the product must authorise
     * explicitly. A module that publishes none has no such constraint.
     */
    private fun unpublishedModules(): List<Path> {
        val root = gradleRoot()
        return Files.list(root).use { paths ->
            paths
                .filter { Files.isDirectory(it) }
                .filter { Files.isDirectory(it.resolve("src/main/kotlin")) }
                .filter { !publishesApi(it) }
                .collect(Collectors.toList())
        }
    }

    /** The modules of the repository that publish an ABI dump. */
    private fun publishedModules(): Set<String> {
        val root = gradleRoot()
        return Files.list(root).use { paths ->
            paths
                .filter { Files.isDirectory(it) }
                .filter { publishesApi(it) }
                .map { it.fileName.toString() }
                .collect(Collectors.toSet())
        }
    }

    /**
     * Whether [module] publishes an ABI dump.
     *
     * The dump is the evidence, not a list of module names: the list would rot
     * the day a module is published or unpublished.
     */
    private fun publishesApi(module: Path): Boolean {
        val apiDir = module.resolve("api")
        if (!Files.isDirectory(apiDir)) return false
        return Files.list(apiDir).use { paths ->
            paths.anyMatch { it.fileName.toString().endsWith(".api") }
        }
    }

    /**
     * The root of the Gradle build, found by walking up from the working
     * directory, so the test keeps working whether it runs from the module
     * directory or from the repository root.
     */
    private fun gradleRoot(): Path {
        var candidate: Path? = Paths.get("").toAbsolutePath()
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) return candidate
            candidate = candidate.parent
        }
        error("the Gradle root was not found from '${Paths.get("").toAbsolutePath()}'")
    }

    /** One source file and its text. */
    private data class SourceFile(val path: Path, val text: String)

    /** One place where the prefix came back. */
    private data class Offence(
        val file: Path,
        val number: Int,
        val detail: String,
    )

    private companion object {
        /** The prefix this test keeps out of the unpublished plane. */
        const val PREFIX = "Canonical"

        /**
         * A type DECLARATION, and the name it declares.
         *
         * The modifiers are optional and the keyword list covers the shapes
         * Kotlin allows: a class, an object, an interface, a fun interface, and
         * the `data`/`sealed`/`enum`/`annotation`/`value` flavours of them.
         */
        val DECLARATION = Regex(
            """(?:class|object|interface)\s+([A-Za-z_]\w*)""",
        )
    }
}
