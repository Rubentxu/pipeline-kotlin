package dev.rubentxu.pipeline.v2.runtime

import dev.rubentxu.pipeline.v2.runtime.telemetry.TelemetryCompatibility
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * No published ABI of this repository names an OpenTelemetry Java type.
 *
 * ## The guarantee
 *
 * The published contracts are what a consumer compiles against. A type of the
 * OpenTelemetry Java SDK in one of them means the consumer has to resolve that
 * type to use the contract: the OpenTelemetry SDK becomes a dependency of
 * every consumer, at a version the consumer did not choose, for a contract
 * that does not need it. The product's own telemetry is an implementation
 * detail behind a port, and the port is the contract — the SDK never has to
 * cross it.
 *
 * The decision to let one of those types in is explicit and lives in
 * [TelemetryCompatibility.otelTypesInPublishedAbi]. The decision is empty
 * today, and that is the guarantee: a consumer of the published contracts
 * resolves no OpenTelemetry type.
 *
 * ## Why the DUMP is the subject
 *
 * The dump is the ABI, and it is the evidence rather than a list of module
 * names: the list would rot the day a module is published or unpublished, and
 * a stale list would either let a leak through or forbid a legitimate type.
 * A module that publishes a dump is found by the dump itself.
 *
 * The dependency is checked as well, and not only the ABI: a published module
 * that depends on the OpenTelemetry artifacts puts them in the POM of the
 * artifact a consumer resolves, even when no public signature names them.
 */
class NoOtelTypesInPublishedAbiTest {

    @Test
    fun `no published ABI names an OpenTelemetry Java type`() {
        val dumps = publishedDumps()
        assertTrue(
            dumps.isNotEmpty(),
            "no published ABI dump was found, so this rule proves nothing",
        )

        val declared = TelemetryCompatibility.otelTypesInPublishedAbi
        val offences = dumps.flatMap { dump ->
            otelTypesIn(dump)
                .filterNot { it in declared }
                .map { type -> "$dump names '$type'" }
        }

        assertEquals(
            emptyList<String>(),
            offences,
            buildString {
                append("a published ABI names an OpenTelemetry Java type in ${offences.size} place(s).")
                append(" The consumer of that contract has to resolve the type, and the OpenTelemetry")
                append(" SDK becomes a dependency of every consumer for a contract that does not need")
                append(" it. The type belongs behind the port, not in the ABI.\n")
                offences.forEach { offence -> append("  $offence\n") }
            },
        )
    }

    @Test
    fun `no published contract depends on the OpenTelemetry libraries`() {
        val modules = publishedModules()
        assertTrue(
            modules.isNotEmpty(),
            "no published module was found, so this rule proves nothing",
        )

        val offences = modules.flatMap { module ->
            val dependency = otelDependencyIn(module)

            val imports = moduleSources(module)
                .flatMap { source -> source.imports().map { "${source.path}: it imports '$it'" } }

            listOfNotNull(dependency) + imports
        }

        assertEquals(
            emptyList<String>(),
            offences,
            buildString {
                append("a published contract reaches the OpenTelemetry libraries in ${offences.size} ")
                append("place(s). The telemetry is behind a port, and the port is the contract: a ")
                append("consumer that compiles against the contract resolves no OpenTelemetry type, ")
                append("and the POM of the artifact carries no OpenTelemetry dependency.\n")
                offences.forEach { offence -> append("  $offence\n") }
            },
        )
    }

    /**
     * The place where the build of a module declares a dependency on the
     * OpenTelemetry libraries, or null when it declares none.
     *
     * The place is reported with its number because a report a reader cannot
     * open is a report nobody can act on.
     */
    private fun otelDependencyIn(module: Path): String? {
        val build = module.resolve("build.gradle.kts")
        if (!Files.isRegularFile(build)) {
            return null
        }
        val declaration = Files.readString(build).lines()
            .withIndex()
            .firstOrNull { (_, line) -> OTEL_DEPENDENCY.containsMatchIn(line) }
            ?: return null
        return "${build}:${declaration.index + 1} declares an OpenTelemetry dependency: ${declaration.value}"
    }

    /**
     * The OpenTelemetry types a dump names.
     *
     * The dump spells a type the way the JVM does, with slashes; the
     * declaration spells it the way a reader does, with dots. The comparison
     * happens in the second spelling, and a nested class is reduced to the
     * class that holds it: the decision is about the type a consumer
     * resolves, and a consumer resolves the outer class.
     */
    private fun otelTypesIn(dump: Path): List<String> =
        OTEL_TYPE.findAll(Files.readString(dump))
            .map { it.groupValues[1].substringBefore('$').replace('/', '.') }
            .distinct()
            .toList()

    /**
     * The dumps of the published ABI, in the tree of the build.
     */
    private fun publishedDumps(): List<Path> {
        val root = gradleRoot()
        return Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().endsWith(".api") }
                .filter { !it.toString().contains("${File.separator}build${File.separator}") }
                .sorted()
                .collect(Collectors.toList())
        }
    }

    /**
     * The modules that publish a dump, found by the dump they publish.
     */
    private fun publishedModules(): List<Path> =
        publishedDumps().map { dump -> moduleOf(dump) }.distinct().sorted()

    /**
     * The module a dump belongs to: the nearest directory above it that
     * declares a build of its own.
     */
    private fun moduleOf(dump: Path): Path {
        var candidate: Path? = dump.parent
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("build.gradle.kts"))) {
                return candidate
            }
            candidate = candidate.parent
        }
        error("the module of the dump '$dump' was not found")
    }

    /**
     * The Kotlin sources of a module, in its main source set.
     */
    private fun moduleSources(module: Path): List<SourceFile> {
        val root = module.resolve("src/main/kotlin")
        if (!Files.isDirectory(root)) {
            return emptyList()
        }
        return Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().endsWith(".kt") }
                .map { SourceFile(it, Files.readString(it)) }
                .collect(Collectors.toList())
        }
    }

    /**
     * The OpenTelemetry imports of a source file.
     *
     * Only an IMPORT counts. A name in a comment explains a contract and does
     * not bring the type into the module, and a rule that read the prose would
     * punish the explanation.
     */
    private fun SourceFile.imports(): List<String> =
        text.lines()
            .map { it.trim() }
            .filter { it.startsWith("import ") }
            .mapNotNull { OTEL_IMPORT.find(it)?.groupValues?.get(1) }
            .distinct()

    /**
     * The root of the build, found by walking up from the working directory,
     * so the rule keeps working whether it runs from the module directory or
     * from the root of the repository.
     */
    private fun gradleRoot(): Path {
        var candidate: Path? = Paths.get("").toAbsolutePath()
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
                return candidate
            }
            candidate = candidate.parent
        }
        error("the Gradle root was not found from '${Paths.get("").toAbsolutePath()}'")
    }

    /** One source file and its text. */
    private data class SourceFile(val path: Path, val text: String)

    private companion object {

        /** An OpenTelemetry type, as a dump spells it. */
        val OTEL_TYPE = Regex("""(io/opentelemetry/[A-Za-z0-9_/$]+)""")

        /** An OpenTelemetry import, as a source spells it. */
        val OTEL_IMPORT = Regex("""^import\s+(io\.opentelemetry[A-Za-z0-9_.]*)""")

        /** A dependency on the OpenTelemetry libraries, as a build file spells it. */
        val OTEL_DEPENDENCY = Regex("""libs\.opentelemetry|io\.opentelemetry:""")
    }
}
