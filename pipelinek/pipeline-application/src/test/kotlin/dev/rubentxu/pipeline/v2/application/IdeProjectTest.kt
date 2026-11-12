package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.scripting.definition.PipelineScriptCompilationConfiguration
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.compilerOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * The Gradle project `ide init` writes, and the way it is written.
 *
 * ## The guarantee under trial
 *
 * That the project compiles a `.pipeline.kts` with the jars and the compiler options the runner
 * compiles it with, and that writing it never replaces a file the operator already has.
 *
 * - The classpath is the `lib/` directory of the installation, written as a Kotlin literal that
 *   no path can turn into code.
 * - The compiler options are the definition's own: Gradle does not apply the options a script
 *   definition declares, so a project without them accepts a script the runner refuses.
 * - A directory that already holds one of the files with other contents is refused, and nothing
 *   is written; a directory that already holds the same project is left as it is.
 *
 * ## Why this instrument
 *
 * The contents are a pure function of the installation, and the writer touches a temporary
 * directory only, so a unit row can fail on each of them. Whether Gradle and the editor accept
 * the project is a fact about Gradle and the editor, and the integration row measures it.
 *
 * ## What this does not freeze
 *
 * The comments of the generated files and the order of the files.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class IdeProjectTest {

    @TempDir
    lateinit var root: Path

    @Test
    fun `the classpath is the lib directory of the installation and nothing else`() {
        val build = buildScript(ideProject(Path.of("/opt/pipelinek/lib"), "2.4.10", emptyList()))

        assertTrue(
            build.contains("val pipelinekLib = File(\"/opt/pipelinek/lib\")"),
            "the build names the installation's lib directory:\n$build",
        )
        assertTrue(
            build.contains("implementation(fileTree(pipelinekLib) { include(\"*.jar\") })"),
            "the classpath is the jars of the installation's lib directory:\n$build",
        )
        assertEquals(
            1,
            Regex("fileTree\\(").findAll(build).count(),
            "the project puts no other directory on the classpath:\n$build",
        )
    }

    @Test
    fun `the plugin jars of the operator are on the classpath by their paths, after a check that each is still there`() {
        val jars = listOf(Path.of("/srv/plugins/a.jar"), Path.of("/srv/we\$ird \"p\"/b.jar"))

        val build = buildScript(ideProject(Path.of("/opt/pipelinek/lib"), "2.4.10", emptyList(), jars))

        val list = build.lines().dropWhile { it != "val pipelinekPluginJars = listOf(" }.takeWhile { it != ")" }
        assertEquals(
            listOf(
                "val pipelinekPluginJars = listOf(",
                "    File(\"/srv/plugins/a.jar\"),",
                "    File(\"/srv/we\\\$ird \\\"p\\\"/b.jar\"),",
            ),
            list,
            "each jar is a literal of its own:\n$build",
        )
        assertTrue(build.contains("    require(pluginJar.isFile) {"), "the build checks every jar:\n$build")
        assertTrue(build.contains("run 'pipelinek ide init' again with the plugin jars"), "the refusal says how to write it again:\n$build")
        assertTrue(
            build.indexOf("require(pluginJar.isFile)") < build.indexOf("dependencies {"),
            "the check runs before the classpath is declared:\n$build",
        )
        assertTrue(build.contains("    implementation(files(pipelinekPluginJars))"), "the jars are on the classpath:\n$build")
    }

    @Test
    fun `without plugin jars the build is the one of the installation alone`() {
        val build = buildScript(ideProject(Path.of("/opt/pipelinek/lib"), "2.4.10", emptyList(), emptyList()))

        assertFalse(build.contains("pipelinekPluginJars"), build)
        assertFalse(build.contains("files("), build)
    }

    @Test
    fun `a path is written as a literal no character of it can turn into code`() {
        val lib = Path.of("/opt/we\$ird \"dir\"\\x\nline/lib")

        val build = buildScript(ideProject(lib, "2.4.10", emptyList()))

        assertTrue(
            build.contains("File(\"/opt/we\\\$ird \\\"dir\\\"\\\\x\\u000aline/lib\")"),
            "the dollar, the quote, the backslash and the line break are escaped:\n$build",
        )
    }

    @Test
    fun `the build refuses to configure, and says how to write it again, when the installation is gone`() {
        val build = buildScript(ideProject(Path.of("/opt/pipelinek/lib"), "2.4.10", emptyList()))

        val check = build.lines().dropWhile { !it.startsWith("require(") }.takeWhile { it != "}" }
        assertEquals("require(pipelinekLib.isDirectory) {", check.firstOrNull(), "the build checks the installation:\n$build")
        val message = check.drop(1).joinToString(" ")
        assertTrue(message.contains("\$pipelinekLib"), "the refusal names the path it looked at: $message")
        assertTrue(message.contains("run 'pipelinek ide init' again"), "the refusal says how to write it again: $message")
        assertTrue(
            build.indexOf("require(pipelinekLib.isDirectory)") < build.indexOf("dependencies {"),
            "the check runs before the classpath is declared:\n$build",
        )
    }

    @Test
    fun `Gradle cannot reach a path that holds a backslash, and reaches the other characters of a literal`() {
        assertTrue(outOfGradleReach(Path.of("/opt/back\\slash/lib")))
        listOf("/opt/pipelinek/lib", "/opt/we\$ird/lib", "/opt/with space/lib", "/opt/quo\"te/lib").forEach { path ->
            assertFalse(outOfGradleReach(Path.of(path)), "Gradle reaches $path")
        }
    }

    @Test
    fun `the compiler options are the options of the script definition`() {
        val options = requireNotNull(PipelineScriptCompilationConfiguration[ScriptCompilationConfiguration.compilerOptions])

        val build = buildScript(ideProject(Path.of("/opt/pipelinek/lib"), "2.4.10", definitionCompilerOptions()))

        assertEquals(
            options,
            definitionCompilerOptions(),
            "the options the project copies are the ones the definition declares",
        )
        options.forEach { option ->
            assertTrue(build.contains("\"$option\""), "the project carries $option:\n$build")
        }
        assertTrue(
            options.contains("-Xwarning-level=RETURN_VALUE_NOT_USED_COERCION:error"),
            "the definition raises a discarded value inside a stage to an error: $options",
        )
    }

    @Test
    fun `the Kotlin plugin is the version of the installation's standard library`() {
        val build = buildScript(ideProject(Path.of("/opt/pipelinek/lib"), "9.8.7", emptyList()))

        assertTrue(build.contains("kotlin(\"jvm\") version \"9.8.7\""), "the plugin version is the one given:\n$build")
        assertEquals(KotlinVersion.CURRENT.toString(), installedKotlinVersion(), "the version is the runtime's stdlib")
    }

    @Test
    fun `the project adds no standard library of its own and the scripts live in pipelines`() {
        val project = ideProject(Path.of("/opt/pipelinek/lib"), "2.4.10", emptyList())
        val properties = project.files.single { it.path == "gradle.properties" }.content

        assertTrue(
            properties.lines().contains("kotlin.stdlib.default.dependency=false"),
            "Gradle must not add a second standard library:\n$properties",
        )
        assertEquals("pipelines", project.scriptsDirectory)
        assertTrue(
            buildScript(project).contains("kotlin.srcDir(\"pipelines\")"),
            "the scripts directory is a source root:\n${buildScript(project)}",
        )
        assertEquals(
            setOf("settings.gradle.kts", "build.gradle.kts", "gradle.properties"),
            project.files.map { it.path }.toSet(),
        )
    }

    @Test
    fun `an empty directory gets the whole project`() {
        val directory = root.resolve("new").resolve("project")

        val written = assertInstanceOf(IdeProjectWrite.Written::class.java, writeIdeProject(directory, sample()))

        assertEquals(
            listOf("settings.gradle.kts", "build.gradle.kts", "gradle.properties", "pipelines/"),
            written.created,
        )
        assertEquals(emptyList<String>(), written.unchanged)
        sample().files.forEach { file ->
            assertEquals(file.content, Files.readString(directory.resolve(file.path)), "the file ${file.path} is written")
        }
        assertTrue(Files.isDirectory(directory.resolve("pipelines")), "the scripts directory is created")
    }

    @Test
    fun `a directory that already holds the same project is left as it is`() {
        writeIdeProject(root, sample())
        val before = Files.getLastModifiedTime(root.resolve("build.gradle.kts"))

        val written = assertInstanceOf(IdeProjectWrite.Written::class.java, writeIdeProject(root, sample()))

        assertEquals(emptyList<String>(), written.created)
        assertEquals(
            listOf("settings.gradle.kts", "build.gradle.kts", "gradle.properties", "pipelines/"),
            written.unchanged,
        )
        assertEquals(before, Files.getLastModifiedTime(root.resolve("build.gradle.kts")))
    }

    @Test
    fun `a file with other contents is refused by name and nothing is written`() {
        Files.writeString(root.resolve("build.gradle.kts"), "// the operator's own build\n")

        val refused = assertInstanceOf(IdeProjectWrite.Refused::class.java, writeIdeProject(root, sample()))

        val different = assertInstanceOf(IdeProjectRefusal.Different::class.java, refused.refusal)
        assertEquals(listOf("build.gradle.kts"), different.paths)
        assertEquals("// the operator's own build\n", Files.readString(root.resolve("build.gradle.kts")))
        assertFalse(Files.exists(root.resolve("settings.gradle.kts")), "a refused project writes no file")
        assertFalse(Files.exists(root.resolve("gradle.properties")), "a refused project writes no file")
        assertFalse(Files.exists(root.resolve("pipelines")), "a refused project creates no directory")
    }

    @Test
    fun `every file that differs is named, and a pipelines that is not a directory differs`() {
        writeIdeProject(root, sample())
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"mine\"\n")
        Files.delete(root.resolve("pipelines"))
        Files.writeString(root.resolve("pipelines"), "not a directory")

        val refused = assertInstanceOf(IdeProjectWrite.Refused::class.java, writeIdeProject(root, sample()))

        assertEquals(
            listOf("settings.gradle.kts", "pipelines/"),
            assertInstanceOf(IdeProjectRefusal.Different::class.java, refused.refusal).paths,
        )
    }

    @Test
    fun `a link where a file goes is not followed and is refused`() {
        val elsewhere = Files.writeString(root.resolve("elsewhere.txt"), "kept")
        val project = Files.createDirectories(root.resolve("project"))
        Files.createSymbolicLink(project.resolve("gradle.properties"), elsewhere)

        val refused = assertInstanceOf(IdeProjectWrite.Refused::class.java, writeIdeProject(project, sample()))

        assertEquals(
            listOf("gradle.properties"),
            assertInstanceOf(IdeProjectRefusal.Different::class.java, refused.refusal).paths,
        )
        assertEquals("kept", Files.readString(elsewhere), "the file the link points to is not written")
        assertTrue(Files.isSymbolicLink(project.resolve("gradle.properties")))
    }

    @Test
    fun `a dangling link where a file goes is refused too`() {
        Files.createSymbolicLink(root.resolve("settings.gradle.kts"), root.resolve("nowhere"))

        val refused = assertInstanceOf(IdeProjectWrite.Refused::class.java, writeIdeProject(root, sample()))

        assertEquals(
            listOf("settings.gradle.kts"),
            assertInstanceOf(IdeProjectRefusal.Different::class.java, refused.refusal).paths,
        )
        assertFalse(Files.exists(root.resolve("nowhere")), "the link is not followed to create its target")
    }

    @Test
    fun `a pipelines directory that links to the operator's scripts is kept`() {
        val scripts = Files.createDirectories(root.resolve("repository").resolve("ci"))
        val project = Files.createDirectories(root.resolve("project"))
        Files.createSymbolicLink(project.resolve("pipelines"), scripts)

        val written = assertInstanceOf(IdeProjectWrite.Written::class.java, writeIdeProject(project, sample()))

        assertEquals(listOf("pipelines/"), written.unchanged)
        assertTrue(Files.isSymbolicLink(project.resolve("pipelines")), "the link is left as it is")
    }

    @Test
    fun `a write that fails half-way keeps nothing this call created`() {
        val directory = root.resolve("fresh").resolve("project")
        var calls = 0
        val failSecond: (Path, ByteArray) -> Unit = { path, bytes ->
            calls += 1
            if (calls == 2) throw java.nio.file.AccessDeniedException(path.toString())
            Files.write(path, bytes, java.nio.file.StandardOpenOption.CREATE_NEW)
        }

        val refused = assertInstanceOf(
            IdeProjectWrite.Refused::class.java,
            writeIdeProject(directory, sample(), failSecond),
        )

        val unwritable = assertInstanceOf(IdeProjectRefusal.Unwritable::class.java, refused.refusal)
        assertEquals(directory.resolve("build.gradle.kts"), unwritable.path)
        assertFalse(Files.exists(root.resolve("fresh")), "the directories this call made are removed again")
    }

    @Test
    fun `a write that fails half-way in an existing directory keeps the directory and what was there`() {
        Files.writeString(root.resolve("notes.txt"), "mine")
        var calls = 0
        val failThird: (Path, ByteArray) -> Unit = { path, bytes ->
            calls += 1
            if (calls == 3) throw java.nio.file.AccessDeniedException(path.toString())
            Files.write(path, bytes, java.nio.file.StandardOpenOption.CREATE_NEW)
        }

        assertInstanceOf(IdeProjectWrite.Refused::class.java, writeIdeProject(root, sample(), failThird))

        assertEquals(listOf("notes.txt"), Files.list(root).use { files -> files.map { it.fileName.toString() }.toList() })
    }

    @Test
    fun `a directory that cannot be made is refused`() {
        val file = Files.writeString(root.resolve("a-file"), "")

        val refused = assertInstanceOf(IdeProjectWrite.Refused::class.java, writeIdeProject(file.resolve("project"), sample()))

        assertInstanceOf(IdeProjectRefusal.Unwritable::class.java, refused.refusal)
    }

    @Test
    fun `the installation is the directory above the lib that holds the definition`() {
        val home = installation(root.resolve("pipelinek"))

        val found = assertInstanceOf(
            InstallationLookup.Found::class.java,
            locateInstallation(home.resolve("lib").resolve(DEFINITION_JAR).toUri().toURL()),
        )

        assertEquals(home.resolve("lib"), found.lib)
    }

    @Test
    fun `a definition outside a lib directory is not an installation`() {
        val jar = Files.createDirectories(root.resolve("build").resolve("libs")).resolve(DEFINITION_JAR)
        Files.writeString(jar, "")

        assertInstanceOf(InstallationLookup.NotFound::class.java, locateInstallation(jar.toUri().toURL()))
    }

    @Test
    fun `a definition loaded from a classes directory is not an installation`() {
        val classes = Files.createDirectories(root.resolve("lib"))

        assertInstanceOf(InstallationLookup.NotFound::class.java, locateInstallation(classes.toUri().toURL()))
    }

    @Test
    fun `a lib directory without the launcher next to it is not an installation`() {
        val home = installation(root.resolve("pipelinek"))
        Files.delete(home.resolve("bin").resolve("pipelinek"))

        val notFound = assertInstanceOf(
            InstallationLookup.NotFound::class.java,
            locateInstallation(home.resolve("lib").resolve(DEFINITION_JAR).toUri().toURL()),
        )
        assertTrue(notFound.reason.contains("bin/pipelinek"), "the refusal names what is missing: ${notFound.reason}")
    }

    @Test
    fun `a definition with no location is not an installation`() {
        assertInstanceOf(InstallationLookup.NotFound::class.java, locateInstallation(null))
    }

    // --- fixtures ---

    private fun sample(): IdeProject = ideProject(Path.of("/opt/pipelinek/lib"), "2.4.10", definitionCompilerOptions())

    private fun buildScript(project: IdeProject): String = project.files.single { it.path == "build.gradle.kts" }.content

    private fun installation(home: Path): Path {
        Files.createDirectories(home.resolve("lib"))
        Files.writeString(home.resolve("lib").resolve(DEFINITION_JAR), "")
        Files.createDirectories(home.resolve("bin"))
        Files.writeString(home.resolve("bin").resolve("pipelinek"), "#!/bin/sh\n")
        assertTrue(Files.isRegularFile(home.resolve("bin").resolve("pipelinek"), LinkOption.NOFOLLOW_LINKS))
        return home
    }

    private companion object {
        const val DEFINITION_JAR: String = "pipeline-scripting-definition-0.0.0.jar"
    }
}
