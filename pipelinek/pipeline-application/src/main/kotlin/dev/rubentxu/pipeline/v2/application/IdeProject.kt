package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.scripting.definition.PipelineScriptCompilationConfiguration
import java.io.IOException
import java.net.URL
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.compilerOptions

/** The directory of the generated project that holds the operator's `.pipeline.kts` files. */
internal const val IDE_PROJECT_SCRIPTS_DIRECTORY: String = "pipelines"

/** One file of the generated project: its path relative to the project directory, and its text. */
internal data class IdeProjectFile(val path: String, val content: String)

/**
 * The Gradle project `ide init` writes: the files, and the directory the scripts go in.
 *
 * It is a value and nothing more. Writing it is [writeIdeProject]'s job, so the contents can be
 * checked without touching a disk.
 */
internal data class IdeProject(val files: List<IdeProjectFile>, val scriptsDirectory: String)

/**
 * The compiler options the script definition declares.
 *
 * The runner compiles a `.pipeline.kts` with them, and IntelliJ IDEA reads them from the
 * definition, but the Kotlin Gradle plugin does not: a Gradle module that compiles the same script
 * without them accepts a discarded step the runner refuses. The project copies them so that the
 * build refuses what the runner refuses.
 */
internal fun definitionCompilerOptions(): List<String> =
    PipelineScriptCompilationConfiguration[ScriptCompilationConfiguration.compilerOptions].orEmpty()

/**
 * The version of the Kotlin standard library this process runs on.
 *
 * Inside an installation that is the stdlib of its `lib/` directory, which is the library the
 * scripting compiler of the runner was built against. The project asks for the Kotlin Gradle
 * plugin of the same version, so the build compiles with the compiler the runner compiles with.
 */
internal fun installedKotlinVersion(): String = KotlinVersion.CURRENT.toString()

/**
 * The project that compiles `.pipeline.kts` files against the installation at [lib] and the
 * plugins of the operator at [pluginJars].
 *
 * - The classpath is every jar of [lib] and each of [pluginJars], and nothing else. The
 *   definition, the DSL, the standard library and the example plugins the distribution ships all
 *   live in [lib]; [pluginJars] are the jars the operator hands the runner with `--plugin-jar`,
 *   written as the paths given, in the order given.
 * - The build refuses to configure when [lib] is no longer a directory, or when one of
 *   [pluginJars] is no longer a file, with a message that says how to write the project again: a
 *   `fileTree` over a directory that is gone is empty, a jar that is gone is skipped, and the
 *   editor would mark every word they provide as unresolved instead.
 * - The build reads the jars at the paths it names, whatever their bytes are by then. What the
 *   runner admitted is the jar as it was when the project was written; the build does not admit
 *   it again.
 * - Gradle adds no standard library of its own (`kotlin.stdlib.default.dependency=false`), so
 *   the one on the classpath is the installation's.
 * - [compilerOptions] become `freeCompilerArgs`.
 *
 * Every value that comes from outside the code (the path, the version and the options) is written
 * as a Kotlin string literal by [kotlinStringLiteral], so no character of a path can end the
 * literal or interpolate a template.
 */
internal fun ideProject(
    lib: Path,
    kotlinVersion: String,
    compilerOptions: List<String>,
    pluginJars: List<Path> = emptyList(),
): IdeProject {
    val scripts = kotlinStringLiteral(IDE_PROJECT_SCRIPTS_DIRECTORY)
    val options = compilerOptions.joinToString(",\n") { "                ${kotlinStringLiteral(it)}" }
    val build = buildString {
        appendLine("// Written by `pipelinek ide init`. It compiles the .pipeline.kts files of the")
        appendLine("// pipelines/ directory with the jars of the installation and the compiler options")
        appendLine("// of its script definition. Gradle does not apply the options a script definition")
        appendLine("// declares, so they are copied here; without them the build accepts a discarded")
        appendLine("// step the runner refuses.")
        appendLine()
        appendLine("plugins {")
        appendLine("    kotlin(\"jvm\") version ${kotlinStringLiteral(kotlinVersion)}")
        appendLine("}")
        appendLine()
        appendLine("val pipelinekLib = File(${kotlinStringLiteral(lib.toString())})")
        appendLine("require(pipelinekLib.isDirectory) {")
        appendLine("    \"the PipelineK installation this project was written for is not at \$pipelinekLib: \" +")
        appendLine("        \"delete settings.gradle.kts, build.gradle.kts and gradle.properties, \" +")
        appendLine("        \"and run 'pipelinek ide init' again with the launcher of the installation\"")
        appendLine("}")
        appendLine()
        if (pluginJars.isNotEmpty()) {
            appendLine("val pipelinekPluginJars = listOf(")
            pluginJars.forEach { appendLine("    File(${kotlinStringLiteral(it.toString())}),") }
            appendLine(")")
            appendLine("for (pluginJar in pipelinekPluginJars) {")
            appendLine("    require(pluginJar.isFile) {")
            appendLine("        \"the plugin jar this project was written with is not at \$pluginJar: \" +")
            appendLine("            \"delete settings.gradle.kts, build.gradle.kts and gradle.properties, \" +")
            appendLine("            \"and run 'pipelinek ide init' again with the plugin jars the runner loads\"")
            appendLine("    }")
            appendLine("}")
            appendLine()
        }
        appendLine("repositories {")
        appendLine("    mavenCentral()")
        appendLine("}")
        appendLine()
        appendLine("kotlin {")
        appendLine("    sourceSets.main { kotlin.srcDir($scripts) }")
        appendLine("    compilerOptions {")
        if (compilerOptions.isEmpty()) {
            appendLine("        freeCompilerArgs.addAll(emptyList())")
        } else {
            appendLine("        freeCompilerArgs.addAll(")
            appendLine("            listOf(")
            appendLine("$options,")
            appendLine("            ),")
            appendLine("        )")
        }
        appendLine("    }")
        appendLine("}")
        appendLine()
        appendLine("dependencies {")
        appendLine("    implementation(fileTree(pipelinekLib) { include(\"*.jar\") })")
        if (pluginJars.isNotEmpty()) appendLine("    implementation(files(pipelinekPluginJars))")
        appendLine("}")
    }
    val settings = buildString {
        appendLine("// Written by `pipelinek ide init`.")
        appendLine("rootProject.name = \"pipelines\"")
    }
    val properties = buildString {
        appendLine("# Written by `pipelinek ide init`. The Kotlin standard library is the one in the")
        appendLine("# lib/ directory of the installation; Gradle must not add a second one.")
        appendLine("kotlin.stdlib.default.dependency=false")
    }
    return IdeProject(
        files = listOf(
            IdeProjectFile("settings.gradle.kts", settings),
            IdeProjectFile("build.gradle.kts", build),
            IdeProjectFile("gradle.properties", properties),
        ),
        scriptsDirectory = IDE_PROJECT_SCRIPTS_DIRECTORY,
    )
}

/**
 * [value] as a double-quoted Kotlin string literal that denotes exactly [value].
 *
 * The backslash, the quote and the dollar are escaped, so the literal cannot end early and holds
 * no string template. Control characters and the two Unicode line separators are written as
 * `\uXXXX`, so the literal stays on one line.
 */
internal fun kotlinStringLiteral(value: String): String = buildString {
    append('"')
    for (c in value) {
        when {
            c == '\\' -> append("\\\\")
            c == '"' -> append("\\\"")
            c == '$' -> append("\\$")
            c.isISOControl() || c == '\u2028' || c == '\u2029' ->
                append("\\u").append(c.code.toString(16).padStart(4, '0'))
            else -> append(c)
        }
    }
    append('"')
}

/**
 * Whether Gradle cannot reach [path].
 *
 * Gradle turns a backslash in a path into a separator, so a path that holds one reaches the build
 * as another path: a project directory is "not found", and a `fileTree` over a `lib/` directory is
 * empty without a word.
 */
internal fun outOfGradleReach(path: Path): Boolean = '\\' in path.toString()

/** Why a project was not written. */
internal sealed interface IdeProjectRefusal : CliError {

    /**
     * The code does not run from an installation, so there is no `lib/` to compile against.
     *
     * @property reason what was found where the installation should be.
     */
    data class NotAnInstallation(val reason: String) : IdeProjectRefusal

    /**
     * The directory already holds something other than the project at these paths.
     *
     * A file with other contents, a link, or a directory where a file goes. Nothing is written,
     * and nothing that is there is replaced.
     *
     * @property paths the paths relative to the project directory, in the order of the project;
     *   the scripts directory is written with a trailing `/`.
     */
    data class Different(val paths: List<String>) : IdeProjectRefusal

    /**
     * A path of the project could not be read, created or written.
     *
     * @property path the path the file system refused.
     * @property detail what the file system said.
     */
    data class Unwritable(val path: Path, val detail: String) : IdeProjectRefusal

    /** Gradle cannot reach the project [directory]: see [outOfGradleReach]. */
    data class DirectoryOutOfGradleReach(val directory: Path) : IdeProjectRefusal

    /** Gradle cannot reach the [lib] directory of the installation: see [outOfGradleReach]. */
    data class InstallationOutOfGradleReach(val lib: Path) : IdeProjectRefusal

    /** Gradle cannot reach a plugin [jar] the operator named: see [outOfGradleReach]. */
    data class PluginJarOutOfGradleReach(val jar: Path) : IdeProjectRefusal
}

/** The result of writing a project. */
internal sealed interface IdeProjectWrite {

    /**
     * The project is in [directory]: [created] were written by this call, and [unchanged] were
     * already there with the same contents. Paths are relative to [directory]; the scripts
     * directory carries a trailing `/`.
     */
    data class Written(val directory: Path, val created: List<String>, val unchanged: List<String>) :
        IdeProjectWrite

    /** Nothing was written. */
    data class Refused(val refusal: IdeProjectRefusal) : IdeProjectWrite
}

/**
 * Writes [project] into [directory], or writes nothing.
 *
 * Every path is looked at before anything is written. A path that holds something else is
 * refused, a path that holds the same bytes is left as it is, and only the missing paths are
 * created, so a second call on the same directory changes nothing.
 *
 * - A file is never written through a link: a link where a file goes, dangling or not, is
 *   something else, and the file is created with `CREATE_NEW`, which fails on anything that
 *   appeared there since it was looked at.
 * - The scripts directory may be a link to a directory, because nothing is written into it.
 * - [directory] and its missing parents are created; a link in the path the operator named is
 *   followed, because the operator named it.
 * - When a write fails half-way, what this call created is removed again.
 *
 * [createFile] creates one file that must not exist; it is a parameter so that a failure half-way
 * can be produced on purpose.
 */
internal fun writeIdeProject(
    directory: Path,
    project: IdeProject,
    createFile: (Path, ByteArray) -> Unit = ::createNewFile,
): IdeProjectWrite {
    if (Files.exists(directory) && !Files.isDirectory(directory)) {
        return refused(IdeProjectRefusal.Unwritable(directory, "it is not a directory"))
    }

    val missingFiles = mutableListOf<IdeProjectFile>()
    val unchanged = mutableListOf<String>()
    val different = mutableListOf<String>()
    for (file in project.files) {
        when (val state = stateOf(directory.resolve(file.path), file.content.toByteArray(Charsets.UTF_8))) {
            PathState.Missing -> missingFiles += file
            PathState.Identical -> unchanged += file.path
            PathState.Different -> different += file.path
            is PathState.Unreadable -> return refused(IdeProjectRefusal.Unwritable(state.path, state.detail))
        }
    }
    val scriptsLabel = "${project.scriptsDirectory}/"
    val scripts = directory.resolve(project.scriptsDirectory)
    val scriptsMissing = when (scriptsStateOf(scripts)) {
        PathState.Missing -> true
        PathState.Identical -> false.also { unchanged += scriptsLabel }
        else -> false.also { different += scriptsLabel }
    }
    if (different.isNotEmpty()) return refused(IdeProjectRefusal.Different(different))

    val created = mutableListOf<Path>()
    val createdLabels = mutableListOf<String>()
    var current: Path = directory
    return try {
        createDirectoriesRecording(directory, created)
        for (file in missingFiles) {
            current = directory.resolve(file.path)
            createFile(current, file.content.toByteArray(Charsets.UTF_8))
            created.add(current)
            createdLabels += file.path
        }
        if (scriptsMissing) {
            current = scripts
            Files.createDirectory(scripts)
            created.add(scripts)
            createdLabels += scriptsLabel
        }
        IdeProjectWrite.Written(directory, createdLabels, unchanged)
    } catch (failure: IOException) {
        created.asReversed().forEach(::deleteQuietly)
        refused(IdeProjectRefusal.Unwritable(current, describe(failure)))
    }
}

private fun refused(refusal: IdeProjectRefusal): IdeProjectWrite = IdeProjectWrite.Refused(refusal)

private fun createNewFile(path: Path, bytes: ByteArray) {
    Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
}

/** Creates [directory] and its missing parents, top down, recording each one this call made. */
private fun createDirectoriesRecording(directory: Path, created: MutableList<Path>) {
    val missing = generateSequence(directory.toAbsolutePath()) { it.parent }
        .takeWhile { !Files.exists(it) }
        .toList()
        .asReversed()
    for (path in missing) {
        try {
            Files.createDirectory(path)
            created.add(path)
        } catch (raced: FileAlreadyExistsException) {
            if (!Files.isDirectory(path)) throw raced
        }
    }
}

private fun deleteQuietly(path: Path) {
    try {
        Files.deleteIfExists(path)
    } catch (_: IOException) {
        // A path that cannot be removed again stays; the refusal still names the one that failed.
    }
}

private sealed interface PathState {
    data object Missing : PathState
    data object Identical : PathState
    data object Different : PathState
    data class Unreadable(val path: Path, val detail: String) : PathState
}

/** What is at [path] compared with [expected], without following a link. */
private fun stateOf(path: Path, expected: ByteArray): PathState {
    val attributes = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) {
        return PathState.Missing
    } catch (failure: IOException) {
        return PathState.Unreadable(path, describe(failure))
    }
    if (!attributes.isRegularFile || attributes.size() != expected.size.toLong()) return PathState.Different
    val actual = try {
        Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { it.readAllBytes() }
    } catch (failure: IOException) {
        return PathState.Unreadable(path, describe(failure))
    }
    return if (actual.contentEquals(expected)) PathState.Identical else PathState.Different
}

/** What is at the scripts directory: a directory, or a link to one, is the operator's scripts. */
private fun scriptsStateOf(path: Path): PathState = when {
    Files.isDirectory(path) -> PathState.Identical
    Files.exists(path, LinkOption.NOFOLLOW_LINKS) -> PathState.Different
    else -> PathState.Missing
}

private fun describe(failure: IOException): String =
    "${failure::class.java.simpleName}: ${failure.message ?: "no detail"}"

/** Where the installation is, as far as the location of the script definition says. */
internal sealed interface InstallationLookup {

    /** The installation at [home], whose jars are in [lib]. */
    data class Found(val home: Path, val lib: Path) : InstallationLookup

    /** No installation: [reason] says what is there instead. */
    data class NotFound(val reason: String) : InstallationLookup
}

/** The jar of the script definition, as the distribution names it. */
private val DEFINITION_JAR = Regex("pipeline-scripting-definition-.+\\.jar")

/**
 * The installation the script definition was loaded from, given the [definitionLocation] of its
 * code source.
 *
 * An installation is a `lib/` directory that holds the definition jar, with the launcher at
 * `bin/pipelinek` beside it. A definition loaded from a classes directory or from a `build/libs`
 * jar is the code of a build, not of an installation, and there is no `lib/` to compile against.
 */
internal fun locateInstallation(definitionLocation: URL?): InstallationLookup {
    if (definitionLocation == null) {
        return InstallationLookup.NotFound("the script definition was not loaded from a file")
    }
    val jar = try {
        Path.of(definitionLocation.toURI()).toAbsolutePath().normalize()
    } catch (failure: Exception) {
        return InstallationLookup.NotFound(
            "the script definition was loaded from $definitionLocation (${failure.message})",
        )
    }
    if (!Files.isRegularFile(jar) || !DEFINITION_JAR.matches(jar.fileName.toString())) {
        return InstallationLookup.NotFound("the script definition was loaded from $jar, which is not its jar")
    }
    val lib = jar.parent
    if (lib?.fileName?.toString() != "lib" || lib.parent == null) {
        return InstallationLookup.NotFound(
            "the script definition jar $jar is not in the lib/ directory of an installation",
        )
    }
    val home = lib.parent
    val launcher = home.resolve("bin").resolve("pipelinek")
    if (!Files.isRegularFile(launcher)) {
        return InstallationLookup.NotFound("$home holds the script definition in lib/ but no bin/pipelinek launcher")
    }
    return InstallationLookup.Found(home, lib)
}
