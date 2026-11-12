package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.runtime.CompositionOutcome
import dev.rubentxu.pipeline.v2.runtime.PluginCompositionAdmitter
import dev.rubentxu.pipeline.v2.runtime.PreLoadAdmissionOutcome
import dev.rubentxu.pipeline.v2.scripting.definition.PipelineScript
import java.io.PrintStream
import java.net.URL
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * The usage the `ide init` verb prints when its request is refused.
 */
internal const val IDE_INIT_USAGE: String =
    "Usage: pipelinek ide init [--dir <path>] [--plugin-jar <jar>]... [--plugin-trust <file>] [--require-plugin-trust]"

/**
 * The requests `ide init` accepts.
 *
 * `--dir` names the directory the project is written into; without it, the project goes into the
 * working directory. `--plugin-jar`, `--plugin-trust` and `--require-plugin-trust` are the options
 * `run` reads with the same names, and they mean the same: the jars of the operator's plugins, the
 * trust file that vouches for them, and the demand that every one of them is vouched for.
 */
internal val IDE_INIT_REQUEST: CliRequest = CliRequest(
    options = listOf(
        CliOption("--dir", takesValue = true),
        CliOption("--plugin-jar", takesValue = true, repeatable = true),
        CliOption("--plugin-trust", takesValue = true),
        CliOption("--require-plugin-trust"),
    ),
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
 *
 * The jars of the operator's plugins come from `--plugin-jar`, one jar per option, and the build
 * names each by its absolute path. Before anything is written they go through the admission `run`
 * applies before it loads a class: their bytes are measured, their manifests read, their API range
 * checked, and, with `--plugin-trust` or `--require-plugin-trust`, the operator's trust decision
 * applied. A jar the admission refuses is refused here with the words `run` uses — the path of the
 * artifact included, because both verbs resolve the operator's names against the same working
 * directory — and nothing is written.
 *
 * The admission also covers the plugins of the INSTALLATION, which the project reaches through the
 * `lib/` directory. A request without any of the three options is answered from the installation
 * alone, but it is still admitted first: a plugin of the installation that `run` would refuse is a
 * plugin this project would compile against, and the operator hears about it here rather than with
 * a project that cannot run.
 *
 * The build names the artifacts the admission ADMITTED and the operator brought. The admission
 * measures the bytes and deduplicates by them, so two paths to one jar are one artifact: a build
 * that named both would carry the same class twice.
 *
 * ## What the admission does not cover
 *
 * It is the admission of the jars as they are when the project is written. Gradle and the editor
 * read them later from the paths the build names, and nothing admits them again: a jar replaced
 * after `ide init` is compiled against with the bytes it has then. Running `ide init` again with
 * the same options admits the jars again. The admission never loads a class from a jar, so what
 * `run` learns only by running the plugins' code is not refused here: a Step whose name collides
 * with another's, or a plugin whose code contributes something its manifest does not declare.
 */
internal fun runIdeInitVerb(args: Array<String>, out: PrintStream, err: PrintStream): CliExit =
    runIdeInit(
        args = args,
        out = out,
        err = err,
        definitionLocation = PipelineScript::class.java.protectionDomain?.codeSource?.location,
        workingDirectory = PROCESS_WORKING_DIRECTORY,
    )

/**
 * [runIdeInitVerb] with the facts it reads from the process given to it: where the script
 * definition was loaded from, the directory a relative path is resolved against, and the plugins
 * the installation bundles, which the admission of the operator's plugins measures beside them.
 */
internal fun runIdeInit(
    args: Array<String>,
    out: PrintStream,
    err: PrintStream,
    definitionLocation: URL?,
    workingDirectory: Path,
    bundledPlugins: () -> List<String> = ::computeBundledPlugins,
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

    val pluginJars = try {
        operatorPluginJars(accepted.repeated["--plugin-jar"].orEmpty(), workingDirectory).distinct()
    } catch (invalid: InvalidPathException) {
        return refuseIdeInit(CliError.InvalidOptionValue("--plugin-jar", "not a path: ${invalid.message}"), err)
    }
    pluginJars.firstOrNull(::outOfGradleReach)?.let { jar ->
        return refuseIdeInit(IdeProjectRefusal.PluginJarOutOfGradleReach(jar), err)
    }
    val trustFile = try {
        accepted.values["--plugin-trust"]?.let { workingDirectory.resolve(it).toAbsolutePath().normalize() }
    } catch (invalid: InvalidPathException) {
        return refuseIdeInit(CliError.InvalidOptionValue("--plugin-trust", "not a path: ${invalid.message}"), err)
    }
    val requireTrust = "--require-plugin-trust" in accepted.flags

    val installation = when (val lookup = locateInstallation(definitionLocation)) {
        is InstallationLookup.NotFound -> return refuseIdeInit(IdeProjectRefusal.NotAnInstallation(lookup.reason), err)
        is InstallationLookup.Found -> lookup
    }
    if (outOfGradleReach(installation.lib)) {
        return refuseIdeInit(IdeProjectRefusal.InstallationOutOfGradleReach(installation.lib), err)
    }

    // The admission ALWAYS runs, and it admits the plugins of the installation beside the ones
    // the operator brought. The project names the plugins of the installation through the `lib/`
    // directory, so a plugin of the installation that `run` would refuse is a plugin this project
    // would compile against: refusing here is the only way the operator hears about it before the
    // editor opens a project that cannot run.
    val bundled = try {
        bundledPlugins()
    } catch (refused: CommandRefused) {
        return refused.exit
    }
    val admission = PluginCompositionAdmitter.admit(
        pluginJars = pluginJars.map { it.toString() },
        trust = pluginTrustSource(trustFile?.toString()),
        journal = null,
        requireTrust = requireTrust,
        err = err,
        bundledPlugins = bundled,
    )
    // The build names the artifacts the admission ADMITTED, at the paths those artifacts are at.
    // The admission deduplicates by BYTES, so two paths to one jar are one artifact; a build that
    // named both would put the same class twice on the classpath.
    // A jar of the operator whose bytes are the ones of a plugin of the installation does not
    // survive that deduplication either: the installation's copy is the one that is admitted, and
    // the classpath already carries it through `lib/`.
    val operatorPaths = pluginJars.toSet()
    val admittedOperatorJars = when (admission) {
        is PreLoadAdmissionOutcome.Admitted -> admission.artifacts
            .filter { it.operatorNamed && operatorPaths.contains(it.path) }
            .map { it.path }
        is CompositionOutcome.Refused -> return refusePluginArtifact(admission, err)
        is CompositionOutcome.RuntimeVersionUnavailable -> return refuseRuntimeVersion(admission, err)
    }

    val project = ideProject(
        installation.lib,
        installedKotlinVersion(),
        definitionCompilerOptions(),
        admittedOperatorJars,
    )
    return when (val write = writeIdeProject(directory, project)) {
        is IdeProjectWrite.Refused -> refuseIdeInit(write.refusal, err)
        is IdeProjectWrite.Written -> {
            out.println("ide-project ${write.directory}: ready")
            write.created.forEach { out.println("created $it") }
            write.unchanged.forEach { out.println("unchanged $it") }
            out.println("installation ${installation.lib}")
            admittedOperatorJars.forEach { out.println("plugin $it") }
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
