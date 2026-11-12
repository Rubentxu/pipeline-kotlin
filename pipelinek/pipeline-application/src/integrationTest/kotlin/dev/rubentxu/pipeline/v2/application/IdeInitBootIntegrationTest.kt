package dev.rubentxu.pipeline.v2.application

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * The project `ide init` writes, compiled by Gradle against the installation that wrote it.
 *
 * ## The guarantee under trial
 *
 * That the project the launcher of an installed distribution writes is a Gradle build that
 * compiles a `.pipeline.kts` in its `pipelines/` directory against the jars of that installation,
 * accepts a script `pipelinek validate` accepts, and refuses a script `pipelinek validate`
 * refuses at the same line and column with the same message. That the path of the
 * installation reaches the build as the path it is, whatever characters of a Kotlin string it
 * holds, and that a build whose installation is gone says so instead of compiling against nothing.
 * That a plugin the operator names with `--plugin-jar` reaches the build, so a script that imports
 * one of its Steps compiles there, that without it the same script does not, and that a build whose
 * plugin jar is gone says so.
 *
 * ## Why these rows are NOT in the gate
 *
 * They start the installed product and a Gradle build of their own, and both can go red for a
 * reason the code does not have: a busy machine, a Gradle cache that has to be filled from the
 * network. They are declared in `scripts/check-product-boot-layer.py` and run on demand:
 *
 *     ./pipelinek/gradlew -p pipelinek :pipeline-application:integrationTest \
 *         --tests '*IdeInitBoot*' --rerun
 *
 * ## Why this instrument
 *
 * The question is what Gradle does with the files, and only Gradle answers it. The Gradle is the
 * one this repository builds with, started through its wrapper on the JVM the lane runs on; the
 * project carries no wrapper of its own, so any Gradle the operator brings is as good a witness.
 *
 * ## What this does not freeze
 *
 * The words Gradle prints around a diagnostic, and the text of the diagnostic itself: the row
 * reads the message from `validate` and asks Gradle for the same one.
 */
@Timeout(value = 10, unit = TimeUnit.MINUTES)
@Tag("boot-check")
class IdeInitBootIntegrationTest {

    @TempDir
    lateinit var root: Path

    @Test
    fun `the project the launcher writes compiles what validate accepts and refuses what validate refuses, where it does`() {
        val launcher = launcher()
        val project = root.resolve("project")

        val written = run(launcher, "ide", "init", "--dir", project.toString())
        assertEquals(0, written.exit, written.output)
        assertTrue(written.output.contains("ide-project $project: ready"), written.output)

        val scripts = project.resolve(IDE_PROJECT_SCRIPTS_DIRECTORY)
        Files.copy(controlScript(), scripts.resolve("writeFile.pipeline.kts"))
        val accepted = gradle(project)
        assertEquals(0, accepted.exit, "the project does not compile a script validate accepts:\n${accepted.output}")

        val discarded = scripts.resolve("discard.pipeline.kts")
        Files.writeString(discarded, DISCARDED_CARRIER)
        val validated = run(launcher, "validate", discarded.toString())
        assertEquals(CliExit.INVALID_ARGUMENT.code, validated.exit, validated.output)
        val message = requireNotNull(
            validated.output.lines().firstNotNullOfOrNull { line ->
                line.substringAfter("$discarded:$DISCARD_POSITION: ", "").takeIf { it.isNotEmpty() }
            },
        ) { "validate does not refuse the discarded carrier at $DISCARD_POSITION:\n${validated.output}" }
        assertTrue(message.contains("'scmGit'"), "validate refuses for another reason: $message")

        val refused = gradle(project)
        assertTrue(refused.exit != 0, "the project compiles a script validate refuses:\n${refused.output}")
        assertTrue(
            refused.output.contains("discard.pipeline.kts:$DISCARD_POSITION $message"),
            "Gradle does not refuse at the place and with the words validate does:\n${refused.output}",
        )
        assertEquals(
            1,
            refused.output.lines().count { it.startsWith("e: ") },
            "Gradle refuses more than the discarded carrier:\n${refused.output}",
        )

        val again = run(launcher, "ide", "init", "--dir", project.toString())
        assertEquals(0, again.exit, again.output)
        assertTrue(
            again.output.lines().none { it.startsWith("created ") },
            "a second run on the project it wrote changed it:\n${again.output}",
        )
    }

    @Test
    fun `an installation whose path holds the characters of a Kotlin string reaches the build, and one that is gone is named`() {
        val home = launcher().parent.parent
        val strange = Files.createSymbolicLink(root.resolve("pipe\$lin \"ek\" home"), home)
        val project = root.resolve("project")
        val lib = strange.resolve("lib")

        val write = writeIdeProject(project, ideProject(lib, installedKotlinVersion(), definitionCompilerOptions()))
        assertTrue(write is IdeProjectWrite.Written, "the project was not written: $write")
        Files.copy(controlScript(), project.resolve(IDE_PROJECT_SCRIPTS_DIRECTORY).resolve("writeFile.pipeline.kts"))

        val compiled = gradle(project)
        assertEquals(0, compiled.exit, "the build does not find the installation by its path:\n${compiled.output}")

        Files.delete(strange)
        val gone = gradle(project)
        assertTrue(gone.exit != 0, "the build configured without its installation:\n${gone.output}")
        assertTrue(
            gone.output.contains("the PipelineK installation this project was written for is not at $lib"),
            "the build does not name the installation it lost:\n${gone.output}",
        )
    }

    @Test
    fun `a script that imports the Step of a plugin the operator names compiles, and without the plugin it does not`() {
        val launcher = launcher()
        val lib = launcher.parent.parent.resolve("lib")
        val bundled = Files.newDirectoryStream(lib, "example-uppercase-plugin-*.jar").use { it.toList() }
        assertEquals(1, bundled.size, "the installation does not carry exactly one uppercase plugin: $bundled")
        val plugins = Files.createDirectories(root.resolve("plugins"))
        val jar = Files.move(bundled.single(), plugins.resolve(bundled.single().fileName))

        val bare = root.resolve("bare")
        val withoutPlugin = run(launcher, "ide", "init", "--dir", bare.toString())
        assertEquals(0, withoutPlugin.exit, withoutPlugin.output)
        Files.copy(pluginScript(), bare.resolve(IDE_PROJECT_SCRIPTS_DIRECTORY).resolve("uppercase.pipeline.kts"))
        val unresolved = gradle(bare)
        assertTrue(unresolved.exit != 0, "the project compiles a Step of a plugin it was not written with:\n${unresolved.output}")
        assertTrue(
            unresolved.output.lines().any { it.startsWith("e: ") && it.contains("uppercase.pipeline.kts:") },
            "the project without the plugin refuses for a reason that is not the script:\n${unresolved.output}",
        )

        val project = root.resolve("project")
        val withPlugin = run(launcher, "ide", "init", "--dir", project.toString(), "--plugin-jar", jar.toString())
        assertEquals(0, withPlugin.exit, withPlugin.output)
        assertTrue(withPlugin.output.lines().contains("plugin $jar"), withPlugin.output)
        Files.copy(pluginScript(), project.resolve(IDE_PROJECT_SCRIPTS_DIRECTORY).resolve("uppercase.pipeline.kts"))
        val compiled = gradle(project)
        assertEquals(0, compiled.exit, "the project does not compile a Step of the plugin it was written with:\n${compiled.output}")

        Files.delete(jar)
        val gone = gradle(project)
        assertTrue(gone.exit != 0, "the build configured without the plugin jar it was written with:\n${gone.output}")
        assertTrue(
            gone.output.contains("the plugin jar this project was written with is not at $jar"),
            "the build does not name the plugin jar it lost:\n${gone.output}",
        )
    }

    // --- fixtures ---

    private data class Finished(val exit: Int, val output: String)

    /** A script of the catalogue `validate` accepts. */
    private fun controlScript(): Path = repository()
        .resolve("examples/catalogue/features/steps/writeFile/writeFile.pipeline.kts")
        .also { check(Files.isRegularFile(it)) { "the catalogue does not hold the control script at $it" } }

    /** A script of the catalogue that imports the Step of the uppercase plugin. */
    private fun pluginScript(): Path = repository()
        .resolve("examples/catalogue/features/plugins/uppercase/uppercase.pipeline.kts")
        .also { check(Files.isRegularFile(it)) { "the catalogue does not hold the plugin script at $it" } }

    /**
     * `compileKotlin` on [project], with the Gradle this repository builds with.
     *
     * The JVM is the one the lane runs on, for the reason the launcher is given it: the build is
     * measured against itself and not against whatever JVM the machine puts first on its PATH.
     */
    private fun gradle(project: Path): Finished = finish(
        ProcessBuilder(
            repository().resolve("pipelinek").resolve("gradlew").toString(),
            "-p", project.toString(),
            "compileKotlin",
            "--no-daemon",
            "--console=plain",
        ),
        "gradle",
    )

    private fun run(launcher: Path, vararg arguments: String): Finished =
        finish(ProcessBuilder(listOf(launcher.toString()) + arguments), "launcher")

    private fun finish(builder: ProcessBuilder, name: String): Finished {
        val output = Files.createTempFile(root, name, ".out")
        builder.redirectErrorStream(true).redirectOutput(output.toFile())
        builder.environment()["JAVA_HOME"] = System.getProperty("java.home")
        val process = builder.start()
        if (!process.waitFor(PROCESS_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            error("the $name did not finish within $PROCESS_TIMEOUT_MINUTES minutes:\n${Files.readString(output)}")
        }
        return Finished(process.exitValue(), Files.readString(output))
    }

    /**
     * The launcher of the distribution the build handed over, unpacked into [root] with the
     * executable bit the installer sets on it.
     */
    private fun launcher(): Path {
        val unpacked = Files.createDirectories(root.resolve("distribution"))
        ZipFile(distribution().toFile()).use { archive ->
            val entries = archive.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val destination = unpacked.resolve(entry.name).normalize()
                check(destination.startsWith(unpacked)) {
                    "the distribution carries an entry that leaves the directory it is unpacked in: ${entry.name}"
                }
                if (entry.isDirectory) {
                    Files.createDirectories(destination)
                    continue
                }
                Files.createDirectories(destination.parent)
                archive.getInputStream(entry).use { bytes -> Files.copy(bytes, destination) }
            }
        }
        val launchers = Files.newDirectoryStream(unpacked).use { directories ->
            directories.filter { Files.isDirectory(it) }
                .map { it.resolve("bin").resolve("pipelinek") }
                .filter { Files.isRegularFile(it) }
                .toList()
        }
        assertEquals(1, launchers.size, "the distribution does not carry exactly one launcher: $launchers")
        val launcher = launchers.first()
        val permissions = Files.getPosixFilePermissions(launcher).toMutableSet()
        permissions.add(PosixFilePermission.OWNER_EXECUTE)
        Files.setPosixFilePermissions(launcher, permissions)
        return launcher
    }

    private fun distribution(): Path = Path.of(
        requireNotNull(System.getProperty("pipelinek.distribution.zip")) {
            "the lane was run without the distribution: the build has to hand over the ZIP it measured"
        },
    )

    private fun repository(): Path = Path.of(
        requireNotNull(System.getProperty("pipelinek.repository.root")) {
            "the lane was run without the root of the repository"
        },
    )

    private companion object {
        const val PROCESS_TIMEOUT_MINUTES: Long = 4

        /** Where [DISCARDED_CARRIER] discards the carrier `scmGit` returns. */
        const val DISCARD_POSITION: String = "4:13"

        /** A stage that calls `scmGit` and drops what it returns. */
        val DISCARDED_CARRIER: String = """
            |pipeline {
            |    stages {
            |        stage("build") {
            |            scmGit(url = "https://example.invalid/r.git")
            |        }
            |    }
            |}
            |
        """.trimMargin()
    }
}
