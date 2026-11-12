package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.scripting.definition.PipelineScript
import java.io.PrintStream
import java.net.URL
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * The usage the `ide init` verb prints when its request is refused.
 */
internal const val IDE_INIT_USAGE: String =
    "Usage: pipelinek ide init [--dir <path>]"

/**
 * The requests `ide init` accepts.
 *
 * `--dir` names the directory the project is written into; without it, the project goes into the
 * working directory.
 */
internal val IDE_INIT_REQUEST: CliRequest = CliRequest(
    options = listOf(CliOption("--dir", takesValue = true)),
)

/**
 * `ide init` — writes a Gradle project IntelliJ IDEA opens `.pipeline.kts` files with.
 *
 * ## Why this verb exists
 *
 * The editor resolves a script against the classpath of the module the file belongs to. A
 * `.pipeline.kts` outside any module gets no definition, no DSL and no diagnostics, and a module
 * the operator writes by hand drifts from the installation that runs the script. The project this
 * verb writes takes the jars of this installation and the compiler options of its script
 * definition, so the editor and `gradle compileKotlin` refuse what `pipelinek validate` refuses.
 *
 * ## What it writes, and what it never does
 *
 * `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties` and an empty `pipelines/`
 * directory. It never replaces a file: a directory that holds other contents at one of those paths
 * is refused by name and nothing is written, and a directory that already holds the same project
 * is left as it is. It writes no Gradle wrapper, so the build runs with the Gradle the IDE or the
 * operator provides.
 *
 * ## Where the jars come from
 *
 * From the `lib/` directory the script definition of this process was loaded from. Run from
 * anything but the launcher of an installed distribution there is no such directory, and the
 * verb refuses without writing.
 */
internal fun runIdeInitVerb(args: Array<String>, out: PrintStream, err: PrintStream): CliExit =
    runIdeInit(
        args = args,
        out = out,
        err = err,
        definitionLocation = PipelineScript::class.java.protectionDomain?.codeSource?.location,
        workingDirectory = Path.of("").toAbsolutePath(),
    )

/**
 * [runIdeInitVerb] with the two facts it reads from the process given to it: where the script
 * definition was loaded from, and the directory a relative `--dir` is resolved against.
 */
internal fun runIdeInit(
    args: Array<String>,
    out: PrintStream,
    err: PrintStream,
    definitionLocation: URL?,
    workingDirectory: Path,
): CliExit {
    val accepted = when (val admission = CliOptionAdmission.admit(args, IDE_INIT_REQUEST)) {
        is CliAdmission.Rejected -> return refuseIdeInit(admission.error, err)
        is CliAdmission.Admitted -> admission
    }

    val directory = try {
        workingDirectory.resolve(accepted.values["--dir"] ?: ".").toAbsolutePath().normalize()
    } catch (invalid: InvalidPathException) {
        return refuseIdeInit(CliError.InvalidOptionValue("--dir", "not a path: ${invalid.message}"), err)
    }
    if (outOfGradleReach(directory)) return refuseIdeInit(IdeProjectRefusal.DirectoryOutOfGradleReach(directory), err)

    val installation = when (val lookup = locateInstallation(definitionLocation)) {
        is InstallationLookup.NotFound -> return refuseIdeInit(IdeProjectRefusal.NotAnInstallation(lookup.reason), err)
        is InstallationLookup.Found -> lookup
    }
    if (outOfGradleReach(installation.lib)) {
        return refuseIdeInit(IdeProjectRefusal.InstallationOutOfGradleReach(installation.lib), err)
    }

    val project = ideProject(installation.lib, installedKotlinVersion(), definitionCompilerOptions())
    return when (val write = writeIdeProject(directory, project)) {
        is IdeProjectWrite.Refused -> refuseIdeInit(write.refusal, err)
        is IdeProjectWrite.Written -> {
            out.println("ide-project ${write.directory}: ready")
            write.created.forEach { out.println("created $it") }
            write.unchanged.forEach { out.println("unchanged $it") }
            out.println("installation ${installation.lib}")
            out.println("scripts ${write.directory.resolve(project.scriptsDirectory)}")
            CliExit.Success()
        }
    }
}

private fun refuseIdeInit(error: CliError, err: PrintStream): CliExit {
    err.println(error.refusalLines("ide init").joinToString("\n"))
    err.println(IDE_INIT_USAGE)
    return error.toCliExit()
}
