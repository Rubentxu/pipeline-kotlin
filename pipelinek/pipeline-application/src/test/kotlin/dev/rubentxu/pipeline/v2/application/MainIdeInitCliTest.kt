package dev.rubentxu.pipeline.v2.application

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
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

    private fun ask(definition: URL, vararg words: String): Answer {
        val outBytes = ByteArrayOutputStream()
        val errBytes = ByteArrayOutputStream()
        val out = PrintStream(outBytes, true, Charsets.UTF_8.name())
        val err = PrintStream(errBytes, true, Charsets.UTF_8.name())
        val working = Files.createDirectories(root.resolve("work"))

        val exit = runIdeInit(words.toList().toTypedArray(), out, err, definition, working)

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
