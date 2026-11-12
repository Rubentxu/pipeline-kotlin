package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.step.PluginManifestCodec
import dev.rubentxu.pipeline.v2.runtime.OperatorPluginFixture
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * The `ide init` verb: what it accepts, what it refuses, and what it says.
 *
 * ## The guarantee under trial
 *
 * That the verb writes the project only from an installation and only into a directory that does
 * not already hold something else, and that each refusal leaves the directory as it was and exits
 * with the code of its kind.
 *
 * ## Why this instrument
 *
 * The verb reads two facts from its process, where the script definition was loaded from and the
 * working directory, and both are given to it here, so an installation can be built in a
 * temporary directory. That the launcher of a real installation reaches the same answer is
 * measured by the integration row that runs it.
 *
 * The plugins the installation bundles are given to it too, because in the test process the
 * classpath is the build's and not an installation's.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class MainIdeInitCliTest {

    @TempDir
    lateinit var root: Path

    @Test
    fun `an unknown option is refused before anything is looked at`() {
        val answer = ask(definition = installation(), "--force")

        assertEquals(2, answer.exit.code)
        assertTrue(answer.stderr.contains("ide init: unknown option: --force"), answer.stderr)
        assertTrue(answer.stderr.contains(IDE_INIT_USAGE), answer.stderr)
        assertEquals(emptyList<String>(), entries(root.resolve("work")))
    }

    @Test
    fun `a positional argument is refused`() {
        val answer = ask(definition = installation(), "elsewhere")

        assertEquals(2, answer.exit.code)
        assertTrue(answer.stderr.contains("unexpected argument: elsewhere"), answer.stderr)
    }

    @Test
    fun `outside an installation the verb refuses and writes nothing`() {
        val jar = Files.createDirectories(root.resolve("build").resolve("libs")).resolve(DEFINITION_JAR)
        Files.writeString(jar, "")

        val answer = ask(definition = jar.toUri().toURL(), "--dir", "project")

        assertEquals(4, answer.exit.code, answer.stderr)
        assertTrue(answer.stderr.contains("ide init: no installation to compile against"), answer.stderr)
        assertTrue(answer.stderr.contains("bin/pipelinek launcher of an installed distribution"), answer.stderr)
        assertFalse(Files.exists(root.resolve("work").resolve("project")), "a refused request writes nothing")
    }

    @Test
    fun `in an installation the project is written into the directory named, relative to the working directory`() {
        val definition = installation()

        val answer = ask(definition, "--dir", "project")

        val project = root.resolve("work").resolve("project")
        assertEquals(0, answer.exit.code, answer.stderr)
        assertEquals(
            listOf(
                "ide-project $project: ready",
                "created settings.gradle.kts",
                "created build.gradle.kts",
                "created gradle.properties",
                "created pipelines/",
                "installation ${root.resolve("pipelinek").resolve("lib")}",
                "scripts ${project.resolve("pipelines")}",
            ),
            answer.stdout.lines().filter { it.isNotEmpty() },
        )
        assertTrue(
            Files.readString(project.resolve("build.gradle.kts"))
                .contains("File(${kotlinStringLiteral(root.resolve("pipelinek").resolve("lib").toString())})"),
            "the classpath is the lib directory of the installation the definition came from",
        )
    }

    @Test
    fun `without --dir the project is written into the working directory`() {
        val answer = ask(definition = installation())

        assertEquals(0, answer.exit.code, answer.stderr)
        assertTrue(Files.isRegularFile(root.resolve("work").resolve("build.gradle.kts")))
    }

    @Test
    fun `a second run on the same directory changes nothing and says so`() {
        val definition = installation()
        ask(definition, "--dir", "project")

        val answer = ask(definition, "--dir", "project")

        assertEquals(0, answer.exit.code, answer.stderr)
        assertFalse(answer.stdout.contains("created "), answer.stdout)
        assertTrue(answer.stdout.contains("unchanged build.gradle.kts"), answer.stdout)
    }

    @Test
    fun `a directory that holds another build is refused by name with the code of an invalid argument`() {
        val definition = installation()
        val project = Files.createDirectories(root.resolve("work").resolve("project"))
        Files.writeString(project.resolve("build.gradle.kts"), "// mine\n")

        val answer = ask(definition, "--dir", "project")

        assertEquals(2, answer.exit.code, answer.stderr)
        assertTrue(
            answer.stderr.contains("the directory already holds other contents at: build.gradle.kts"),
            answer.stderr,
        )
        assertTrue(answer.stderr.contains("never replaces a file"), answer.stderr)
        assertEquals(listOf("build.gradle.kts"), entries(project))
    }

    @Test
    fun `a directory Gradle cannot reach is refused with the code of an invalid argument and nothing is written`() {
        val answer = ask(definition = installation(), "--dir", "back\\slash")

        assertEquals(2, answer.exit.code, answer.stderr)
        assertTrue(answer.stderr.contains("Gradle cannot reach ${root.resolve("work").resolve("back\\slash")}"), answer.stderr)
        assertTrue(answer.stderr.contains("reads the backslash in the path as a separator"), answer.stderr)
        assertEquals(emptyList<String>(), entries(root.resolve("work")))
    }

    @Test
    fun `an installation Gradle cannot reach is refused with the code of an unavailable runtime and nothing is written`() {
        val answer = ask(definition = installation(home = root.resolve("pipe\\linek")), "--dir", "project")

        assertEquals(4, answer.exit.code, answer.stderr)
        assertTrue(
            answer.stderr.contains("Gradle cannot reach the installation at ${root.resolve("pipe\\linek").resolve("lib")}"),
            answer.stderr,
        )
        assertTrue(answer.stderr.contains("install the distribution under a path without a backslash"), answer.stderr)
        assertFalse(Files.exists(root.resolve("work").resolve("project")), "a refused request writes nothing")
    }

    @Test
    fun `the plugin jars the operator names are written into the build by their absolute path, once each`() {
        val definition = installation()
        val plugins = Files.createDirectories(root.resolve("work").resolve("plugins"))
        val first = OperatorPluginFixture.jar(plugins, "first.jar", thing = "first")
        val second = OperatorPluginFixture.jar(plugins, "we\$ird \"second\".jar", thing = "second")

        val answer = ask(
            definition,
            "--dir", "project",
            "--plugin-jar", "plugins/first.jar",
            "--plugin-jar", "plugins/we\$ird \"second\".jar",
            "--plugin-jar", "plugins/../plugins/first.jar",
        )

        val project = root.resolve("work").resolve("project")
        assertEquals(0, answer.exit.code, answer.stderr)
        assertEquals(
            listOf(
                "ide-project $project: ready",
                "created settings.gradle.kts",
                "created build.gradle.kts",
                "created gradle.properties",
                "created pipelines/",
                "installation ${root.resolve("pipelinek").resolve("lib")}",
                "plugin $first",
                "plugin $second",
                "scripts ${project.resolve("pipelines")}",
            ),
            answer.stdout.lines().filter { it.isNotEmpty() },
        )
        val build = Files.readString(project.resolve("build.gradle.kts"))
        listOf(first, second).forEach { jar ->
            assertEquals(
                1,
                Regex(Regex.escape("File(${kotlinStringLiteral(jar.toString())})")).findAll(build).count(),
                "the build names $jar once, by its absolute path:\n$build",
            )
        }
        assertTrue(build.contains("implementation(files(pipelinekPluginJars))"), build)
    }

    @Test
    fun `without a plugin option nothing is admitted and the build names no plugin of the operator`() {
        val answer = ask(installation(), "--dir", "project", bundled = { error("nothing asked for an admission") })

        assertEquals(0, answer.exit.code, answer.stderr)
        assertEquals("", answer.stderr)
        assertFalse(answer.stdout.lines().any { it.startsWith("plugin ") }, answer.stdout)
        val build = Files.readString(root.resolve("work").resolve("project").resolve("build.gradle.kts"))
        assertFalse(build.contains("pipelinekPluginJars"), build)
    }

    @Test
    fun `a jar that is not a plugin is refused by its path and nothing is written`() {
        val definition = installation()
        val plugins = Files.createDirectories(root.resolve("work").resolve("plugins"))
        Files.writeString(plugins.resolve("not-a-plugin.jar"), "not a jar")

        val answer = ask(definition, "--dir", "project", "--plugin-jar", "plugins/not-a-plugin.jar")

        assertEquals(2, answer.exit.code, answer.stderr)
        assertTrue(
            answer.stderr.contains("Plugin admission refused for ${plugins.resolve("not-a-plugin.jar")}"),
            answer.stderr,
        )
        assertFalse(answer.stderr.contains(IDE_INIT_USAGE), "a refused plugin is not a misuse of the verb: ${answer.stderr}")
        assertEquals(listOf("plugins"), entries(root.resolve("work")))
    }

    @Test
    fun `a jar that is not there is refused by its path and nothing is written`() {
        val answer = ask(installation(), "--dir", "project", "--plugin-jar", "plugins/missing.jar")

        assertEquals(2, answer.exit.code, answer.stderr)
        assertTrue(
            answer.stderr.contains("Plugin admission refused for ${root.resolve("work").resolve("plugins").resolve("missing.jar")}"),
            answer.stderr,
        )
        assertEquals(emptyList<String>(), entries(root.resolve("work")))
    }

    @Test
    fun `a plugin no trust decision covers is written, and the operator is told`() {
        val definition = installation()
        OperatorPluginFixture.jar(Files.createDirectories(root.resolve("work").resolve("plugins")))

        val answer = ask(definition, "--dir", "project", "--plugin-jar", "plugins/operator-plugin.jar")

        assertEquals(0, answer.exit.code, answer.stderr)
        assertTrue(answer.stderr.contains("${OperatorPluginFixture.PLUGIN_ID} was loaded WITHOUT a trust decision"), answer.stderr)
    }

    @Test
    fun `when the operator demands trust, a plugin no trust decision covers is refused and nothing is written`() {
        val definition = installation()
        OperatorPluginFixture.jar(Files.createDirectories(root.resolve("work").resolve("plugins")))

        val answer = ask(
            definition,
            "--dir", "project",
            "--plugin-jar", "plugins/operator-plugin.jar",
            "--require-plugin-trust",
        )

        assertEquals(2, answer.exit.code, answer.stderr)
        assertTrue(answer.stderr.contains("required every plugin they brought to be verified"), answer.stderr)
        assertEquals(listOf("plugins"), entries(root.resolve("work")))
    }

    @Test
    fun `a plugin the operator's trust file approves is written under the demand`() {
        val definition = installation()
        val jar = OperatorPluginFixture.jar(Files.createDirectories(root.resolve("work").resolve("plugins")))
        OperatorPluginFixture.approvingTrustFile(jar, Files.createDirectories(root.resolve("work").resolve("trust")))

        val answer = ask(
            definition,
            "--dir", "project",
            "--plugin-jar", "plugins/operator-plugin.jar",
            "--plugin-trust", "trust/trust.json",
            "--require-plugin-trust",
        )

        assertEquals(0, answer.exit.code, answer.stderr)
        assertTrue(answer.stderr.contains("admitted under the operator's trust policy"), answer.stderr)
        assertTrue(answer.stdout.contains("plugin $jar"), answer.stdout)
    }

    @Test
    fun `a plugin jar Gradle cannot reach is refused with the code of an invalid argument and nothing is written`() {
        val answer = ask(installation(), "--dir", "project", "--plugin-jar", "back\\slash.jar")

        assertEquals(2, answer.exit.code, answer.stderr)
        assertTrue(
            answer.stderr.contains("Gradle cannot reach the plugin jar at ${root.resolve("work").resolve("back\\slash.jar")}"),
            answer.stderr,
        )
        assertEquals(emptyList<String>(), entries(root.resolve("work")))
    }

    @Test
    fun `a refused classpath of the bundled plugins ends the request with its own exit and nothing is written`() {
        val answer = ask(
            installation(),
            "--dir", "project",
            "--require-plugin-trust",
            bundled = { throw CommandRefused(CliExit.InvalidArgument(message = "the bundled plugin classpath is in conflict")) },
        )

        assertEquals(2, answer.exit.code, answer.stderr)
        assertEquals(emptyList<String>(), entries(root.resolve("work")))
    }

    @Test
    fun `a plugin of the operator that claims the identity of a bundled plugin with other bytes is refused and nothing is written`() {
        val definition = installation()
        val bundledCopy = root.resolve("pipelinek").resolve("lib").resolve("bundled-plugin.jar")
        JarOutputStream(Files.newOutputStream(bundledCopy)).use { out ->
            out.putNextEntry(JarEntry(PluginManifestCodec.RESOURCE_PATH))
            out.write(PluginManifestCodec.encode(OperatorPluginFixture.manifest()).toByteArray(Charsets.UTF_8))
            out.closeEntry()
            out.putNextEntry(JarEntry("installation.txt"))
            out.write("the copy the installation bundles".toByteArray(Charsets.UTF_8))
            out.closeEntry()
        }
        OperatorPluginFixture.jar(Files.createDirectories(root.resolve("work").resolve("plugins")))

        val answer = ask(
            definition,
            "--dir", "project",
            "--plugin-jar", "plugins/operator-plugin.jar",
            bundled = { listOf(bundledCopy.toString()) },
        )

        assertEquals(2, answer.exit.code, answer.stderr)
        assertTrue(answer.stderr.contains("Plugin admission refused for "), answer.stderr)
        assertTrue(answer.stderr.contains("DuplicateIdentity"), answer.stderr)
        assertEquals(listOf("plugins"), entries(root.resolve("work")))
    }

    @Test
    fun `the family word on its own names its form`() {
        val door = requireNotNull(cliFamilyDoor(arrayOf("ide"))) { "the family word 'ide' has no refusal" }

        assertEquals(IDE_INIT_USAGE, door.usage)
        assertTrue(
            door.error.refusalLines("pipeline").any { it.contains("'ide init'") },
            "the refusal names the form the family owns: ${door.error}",
        )
    }

    @Test
    fun `the verb is the one the words ide init ask for`() {
        assertEquals("ide init", cliVerbFor(arrayOf("ide", "init", "--dir", "x"))?.label)
    }

    // --- fixtures ---

    private data class Answer(val exit: CliExit, val stdout: String, val stderr: String)

    private fun ask(
        definition: URL,
        vararg words: String,
        bundled: () -> List<String> = { emptyList() },
    ): Answer {
        val outBytes = ByteArrayOutputStream()
        val errBytes = ByteArrayOutputStream()
        val out = PrintStream(outBytes, true, Charsets.UTF_8.name())
        val err = PrintStream(errBytes, true, Charsets.UTF_8.name())
        val working = Files.createDirectories(root.resolve("work"))

        val exit = runIdeInit(words.toList().toTypedArray(), out, err, definition, working, bundledPlugins = bundled)

        return Answer(exit, outBytes.toString(Charsets.UTF_8), errBytes.toString(Charsets.UTF_8))
    }

    /** An installation in the temporary directory, as the location of its definition jar. */
    private fun installation(home: Path = root.resolve("pipelinek")): URL {
        val jar = Files.createDirectories(home.resolve("lib")).resolve(DEFINITION_JAR)
        if (!Files.exists(jar)) Files.writeString(jar, "")
        val launcher = Files.createDirectories(home.resolve("bin")).resolve("pipelinek")
        if (!Files.exists(launcher)) Files.writeString(launcher, "#!/bin/sh\n")
        return jar.toUri().toURL()
    }

    private fun entries(directory: Path): List<String> =
        if (!Files.isDirectory(directory)) {
            emptyList()
        } else {
            Files.list(directory).use { files -> files.map { it.fileName.toString() }.sorted().toList() }
        }

    private companion object {
        const val DEFINITION_JAR: String = "pipeline-scripting-definition-0.0.0.jar"
    }
}
