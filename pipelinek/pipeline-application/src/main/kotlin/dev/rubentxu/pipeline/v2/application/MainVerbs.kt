package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.credentials.local.MainCredentialsCli
import java.nio.file.Paths
import java.nio.file.Files
import java.util.Locale

internal fun printUsage(out: Appendable) {
    // Locale.ROOT: the usage text is a machine-readable surface, and a locale that
    // renders digits differently would make the same command print differently on
    // two machines. `%s` is unaffected today, but the rule is what keeps it that way.
    out.append("Usage: pipelinek <verb> [options] [args]\n\nVerbs:\n")
    CLI_VERBS.forEach { verb ->
        out.append("  %-13s %s\n".format(Locale.ROOT, verb.label, verb.summary))
    }
    out.append("\nEnvironment:\n")
    CLI_ENVIRONMENT.forEach { (name, purpose) -> out.append("  %-28s %s\n".format(Locale.ROOT, name, purpose)) }
    out.append("\nEnvironment (internal, set by the product itself):\n")
    CLI_ENVIRONMENT_INTERNAL.forEach { (name, purpose) ->
        out.append("  %-28s %s\n".format(Locale.ROOT, name, purpose))
    }
    out.append("\nEach verb prints its own usage when called without a required argument.\n")
}

/**
 * Whether [args] asks for the usage surface.
 *
 * Extracted so the hints printed on a rejection can be CHECKED against it. A hint that
 * names a flag the CLI does not accept is a promise with nothing behind it, and the
 * cheapest way to keep that from returning is to make the question answerable in a test.
 */
internal fun isHelpRequest(args: Array<String>): Boolean =
    args.firstOrNull() in HELP_TOKENS

private val HELP_TOKENS = setOf("--help", "-h", "help")

/**
 * What the read-only verbs decided.
 *
 * ## Why this is a value and not a Boolean
 *
 * The read-only verbs answer, and the process ends. The dispatcher used to end
 * the process ITSELF, from inside a helper: it swallowed the result of the
 * verb and ended the JVM before its caller had decided anything. A helper
 * that ends the process takes a decision that is not its own, and it takes it
 * away from the one place that owns the process.
 *
 * The dispatcher now says WHAT happened and lets the entry point decide what
 * to do with it. Two facts, not one: whether a read-only verb answered, and
 * the decision it answered with.
 */
internal sealed interface ReadOnlyVerbOutcome {

    /** No read-only verb answers these words; the request is not one of ours. */
    data object NotHandled : ReadOnlyVerbOutcome

    /** A read-only verb answered, and this is the decision it answered with. */
    data class Answered(val exit: CliExit) : ReadOnlyVerbOutcome
}

/**
 * The read-only verbs: the ones that answer without writing.
 *
 * ## Why they live here
 *
 * A read-only verb is not part of running a pipeline: it admits nothing,
 * composes no store and writes nothing. It was inside `fun main` because
 * `main` had grown to be a whole file, and in there anything fits if nobody
 * looks at the size.
 *
 * ## What dispatches them
 *
 * The inventory in [CLI_VERBS] carries a handler for each of them, so the
 * table IS the dispatch: a form that is in it is answered, and a form that
 * is not in it is not a command of this CLI. The comparison each verb used
 * to spell out is gone, and with it the chance of the two lists disagreeing
 * about what a verb is.
 *
 * ## Why it returns a decision and does not end the process
 *
 * The verb that answers is not the owner of the process. The dispatcher
 * returns what the verb decided, and the entry point — the only place that
 * ends the process — decides what to do with it. A verb that forgets to
 * answer can still not fall through into the run path: the caller stops on
 * the outcome, whatever the outcome is.
 */
internal fun handleReadOnlyVerb(args: Array<String>): ReadOnlyVerbOutcome {
    val verb = cliVerbFor(args)
    if (verb != null && verb.handler != null) {
        return ReadOnlyVerbOutcome.Answered(verb.handler(args.drop(verb.words.size).toTypedArray()))
    }
    // The family word on its own is not a command. Saying so, and naming the
    // form that is, is the refusal the operator gets. The refusal is a
    // [CliError] and the code is the one the taxonomy declares: the door used
    // to print a sentence this repository wrote and exit with a literal.
    //
    // The door PRINTS the refusal and the usage; printing is not ending the
    // process. The decision travels back to the entry point, which owns the
    // process and is the only place that ends it.
    val door = cliFamilyDoor(args)
    if (door != null) {
        System.err.println(door.error.refusalLines("pipeline").joinToString("\n"))
        System.err.println(door.usage)
        return ReadOnlyVerbOutcome.Answered(door.error.toCliExit())
    }
    return ReadOnlyVerbOutcome.NotHandled
}

/**
 * `version` — the product version, and the same fact as a machine-readable
 * object.
 *
 * The version MUST be produced by the build from project.version. The legacy
 * "0.1.0-SNAPSHOT" sentinel is removed: if the generated resource is missing,
 * that is a packaging defect, NOT a fallback case. Fail-closed: print an
 * explicit error and refuse with a typed decision, so CI cannot ship an
 * unversioned artifact.
 *
 * ONE authority for "what version is this". Admission reads the same value
 * through RuntimeApiVersion, so the number a plugin is judged against and
 * the number the CLI reports cannot come from two readers free to disagree.
 *
 * The source moved from the jar manifest to a generated RESOURCE, because 34
 * harnesses run the CLI from a classes directory where
 * Package.getImplementationVersion() is null. The manifest attribute is still
 * populated for pipeline-release and external inspection, and
 * RuntimeApiVersionDriftTest fails if the two ever diverge.
 *
 * `--json` reports the same authority as an object, because a line a human
 * reads and a version that identifies a release LINE answer neither of them
 * the question a harness has to ask: which commit is this binary. Absent
 * provenance is reported as `null`, not as an empty string and not as an
 * error: a working-tree build has no commit to report, and the candidate
 * gate decides whether that is acceptable for what it is certifying.
 */
internal fun runVersionVerb(args: Array<String>): CliExit {
    val admission = CliOptionAdmission.admit(args, VERSION_REQUEST)
    val accepted = when (admission) {
        is CliAdmission.Rejected -> {
            System.err.println(admission.error.refusalLines("version").joinToString("\n"))
            return admission.error.toCliExit()
        }
        is CliAdmission.Admitted -> admission
    }
    if ("--json" in accepted.flags) {
        val identity = RuntimeApiVersion.buildIdentity()
        val commitField = when (identity) {
            is RuntimeApiVersion.BuildIdentity.Recorded -> "\"${identity.commit}\""
            RuntimeApiVersion.BuildIdentity.Absent -> "null"
        }
        val versionField =
            RuntimeApiVersion.readImplementationVersion()?.let { "\"$it\"" } ?: "null"
        println(
            """{"productVersion":$versionField,"buildCommit":$commitField}""",
        )
        return CliExit.Success()
    }
    val manifestVersion = RuntimeApiVersion.readImplementationVersion()
    if (manifestVersion.isNullOrBlank()) {
        System.err.println(
            "pipeline: FATAL — the generated version resource is missing. " +
                "Refusing to report a version derived from a hard-coded sentinel. " +
                "Rebuild via Gradle so it is generated from project.version."
        )
        return CliExit.IncompatibleVersion(
            message = "the generated version resource is missing",
            hint = "rebuild via Gradle so the version is generated from project.version",
        )
    }
    println("pipeline $manifestVersion")
    return CliExit.Success()
}

/**
 * `capabilities` — the capability keys this build admits, as one JSON line.
 *
 * Side-effect free. Machine-verifiable surface for external consumers.
 */
internal fun runCapabilitiesVerb(args: Array<String>): CliExit =
    verbExit(MainCapabilitiesCli.main(args))

/**
 * `sdk-info` — the SDK coordinates a plugin builds against, as one JSON line.
 *
 * Side-effect free.
 */
internal fun runSdkInfoVerb(args: Array<String>): CliExit =
    verbExit(MainSdkInfoCli.main(args))

/**
 * `doctor` — local-only health diagnostics.
 *
 * JDK version, operating system, working directory writability probe. Exits 0
 * when the local runtime is healthy, 2 on an environment defect.
 *
 * The host environment is read through the canonical RuntimeConfig adapter
 * (single authority); a direct System.getProperty here would be a second
 * runtime-value authority.
 *
 * The probe answers a question about the filesystem, and every way it can
 * fail to answer is the same answer: this directory cannot be written into. A
 * diagnostic that crashes on the very defect it is meant to report would
 * answer nothing at all.
 *
 * The exit code comes from the verdicts. It is not read off the text the
 * report printed, because a report that reads its own sentences can contradict
 * them.
 *
 * The verb owns no option, and a request that carries one is refused: `doctor
 * --json` used to answer 0 and print the report, so a flag the operator
 * believed in was dropped without a word.
 */
internal fun runDoctorVerb(args: Array<String>): CliExit {
    val admission = CliOptionAdmission.admit(args, NO_ARGUMENTS_REQUEST)
    when (admission) {
        is CliAdmission.Rejected -> {
            System.err.println(admission.error.refusalLines("doctor").joinToString("\n"))
            return admission.error.toCliExit()
        }
        is CliAdmission.Admitted -> Unit
    }
    val runtimeConfig = dev.rubentxu.pipeline.v2.application.SystemRuntimeConfig()
    val cwd = Paths.get("").toAbsolutePath()
    val writable = try {
        val probe = Files.createTempFile(cwd, "pipelinek-doctor-", ".probe")
        Files.deleteIfExists(probe)
        true
    } catch (_: java.io.IOException) {
        false
    } catch (_: SecurityException) {
        false
    }
    val checks = listOf(
        InstallationHealth.fact(
            "jdk",
            "${runtimeConfig.property("java.version")} (${runtimeConfig.property("java.vendor")})",
        ),
        InstallationHealth.fact(
            "os",
            "${runtimeConfig.osName()} ${runtimeConfig.property("os.version")}",
        ),
        InstallationHealth.workdir(cwd, writable),
    )
    checks.forEach { println(it.render()) }
    return InstallationHealth.decision(checks)
}

/**
 * `events` — the run's domain events, as text or as json.
 *
 * Structured local history inspection.
 */
internal fun runEventsVerb(args: Array<String>): CliExit =
    verbExit(MainEventsCli.main(args))

/**
 * `events verify` — the run's events against a contract.
 *
 * A verb of its own because it answers a different question from `events`:
 * not what happened, but whether what happened matches what was promised.
 */
internal fun runEventsVerifyVerb(args: Array<String>): CliExit =
    verbExit(MainEventsVerifyCli.main(args))

/**
 * `console` — the current release: read process output from the Output Plane
 * by OutputCursor.
 *
 * Deliberately a SEPARATE verb from `events`. The two planes are different
 * orders and a different authority: `events` continues on a store-assigned
 * sequence, `console` continues on a committed byte offset and never reads an
 * event. Folding console into `events` — as a view, or as a flag — is the
 * conflation the byte-offset rule exists to prevent, and it is exactly what
 * the deprecated EventViewProjection.CONSOLE was.
 */
internal fun runConsoleVerb(args: Array<String>): CliExit =
    verbExit(MainConsoleCli.main(args))

/**
 * `operations` — the run's operations, with their complete identity.
 *
 * It is a verb of its own because the fact it publishes belongs to no other
 * verb: `console` reads the bytes of an operation the caller already knows
 * about, and `inspect` reads what a run DID. This one answers the question
 * that comes first — WHICH operations are there — and it answers it with the
 * identity as it was minted, not with a name derived from the Output Plane's
 * directory layout, which folds every separator into an underscore.
 */
internal fun runOperationsVerb(args: Array<String>): CliExit =
    verbExit(MainOperationsCli.main(args))

/**
 * `observe` — one public verb over both durable authorities.
 *
 * NOT a third universe alongside `events` and `console` — it reads the same
 * two planes and composes them read-side. What it refuses is the thing
 * neither of the other two can promise: a document claiming an interleaving.
 * `Event.sequence` and `OutputFrame.ordinal` have no total order, so
 * `--view full` stays refused here exactly as it is in `run`.
 */
internal fun runObserveVerb(args: Array<String>): CliExit =
    verbExit(MainObserveCli.main(args))

/**
 * `credentials` — the registered credential material, redacted.
 */
internal fun runCredentialsVerb(args: Array<String>): CliExit =
    verbExit(MainCredentialsCli.main(args))

/**
 * `inspect` — a bounded diagnostic surface.
 *
 * The verb is the same workflow for `inspect` that `observe`, `events`, and
 * `console` are for their lanes: a typed parser + a typed query + a typed
 * projection.
 */
internal fun runInspectVerb(args: Array<String>): CliExit =
    verbExit(MainInspectCli.main(args))

/**
 * `saga inspect` — what a run still owes, read from the durable promises.
 *
 * It is a VERB of its own rather than an option of `inspect` because it reads
 * a different authority: `inspect` reads what a run DID, and this one reads
 * what a run PROMISED and has not yet discharged.
 */
internal fun runSagaInspectVerb(args: Array<String>): CliExit =
    verbExit(MainSagaInspectCli.main(args))
