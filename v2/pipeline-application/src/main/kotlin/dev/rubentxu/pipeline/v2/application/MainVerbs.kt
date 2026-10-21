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
    CLI_VERBS.forEach { (verb, summary) -> out.append("  %-13s %s\n".format(Locale.ROOT, verb, summary)) }
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
 * The read-only verbs: `version`, `capabilities`, `sdk-info`, `doctor`,
 * `events`, `console`, `observe`, `credentials` and `inspect`.
 *
 * ## Why they live here
 *
 * A read-only verb is not part of running a pipeline: it admits nothing,
 * composes no store and writes nothing. It was inside `fun main` because
 * `main` had grown to be a whole file, and in there anything fits if nobody
 * looks at the size.
 *
 * ## The order is the contract
 *
 * Each verb is compared against `args[0]` in the sequence it had, and the
 * per-verb guards depend on that sequence (`version --json` is checked apart
 * from `version`, and it is checked after it).
 *
 * ## Why it returns a Boolean
 *
 * Every branch here ends in `System.exit`, so the function does not really
 * return. The Boolean says "this verb is mine" so that a future verb that
 * forgets to exit still cannot fall through into the run path: the caller
 * stops here either way.
 */
internal fun handleReadOnlyVerb(args: Array<String>): Boolean {
    // WU-LPR-011 F1: `version` is a real subcommand. Reports the CLI version from the build-generated
    // version resource (authoritative source = project.version) and exits 0.
    // WU-LPR-071: the version MUST be produced by the build from project.version. The legacy
    // "0.1.0-SNAPSHOT" sentinel is removed: if the generated resource is missing, that is a packaging
    // defect, NOT a fallback case.
    // Fail-closed: print an explicit error and exit non-zero so CI cannot ship an
    // unversioned artifact.
    if (args.firstOrNull() == "version" && args.getOrNull(1) != "--json") {
        // S6-COMPOSITION: ONE authority for "what version is this". Admission reads the same value
        // through RuntimeApiVersion, so the number a plugin is judged against and the number the
        // CLI reports cannot come from two readers free to disagree.
        //
        // S6-COMPOSITION: the source moved from the jar manifest to a generated RESOURCE, because
        // 34 harnesses run the CLI from a classes directory where Package.getImplementationVersion()
        // is null. The manifest attribute is still populated for pipeline-release and external
        // inspection, and RuntimeApiVersionDriftTest fails if the two ever diverge.
        val manifestVersion = RuntimeApiVersion.readImplementationVersion()
        if (manifestVersion.isNullOrBlank()) {
            System.err.println(
                "pipeline: FATAL — the generated version resource is missing. " +
                    "Refusing to report a version derived from a hard-coded sentinel. " +
                    "Rebuild via Gradle so it is generated from project.version."
            )
            System.exit(3)
            return true
        }
        println("pipeline $manifestVersion")
        System.exit(0)
        return true
    }

    // H1.4 — the same authority, reported as a machine-readable object.
    //
    // `version` prints a line a human reads and a version that identifies a release LINE. Neither
    // answers "which commit is this binary", and a harness must be able to ask. Emitting the
    // provenance as JSON keeps the report machine-checkable and keeps the product version as the
    // release identity it actually is, without overloading it into a fake commit identity.
    //
    // Absent provenance is reported as `null`, not as an empty string and not as an error: a
    // working-tree build has no commit to report, and the candidate gate decides whether that is
    // acceptable for what it is certifying.
    if (args.firstOrNull() == "version" && args.getOrNull(1) == "--json") {
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
        System.exit(0)
        return true
    }

    // PK-1 / ADR-PK1-IDENTITY: `capabilities` is a real subcommand.
    // Emits `pipelinek.capabilities/1` JSON line on stdout; side-effect
    // free. Machine-verifiable surface for external consumers.
    if (args.firstOrNull() == "capabilities") {
        val exitCode = MainCapabilitiesCli.main(args.drop(1).toTypedArray())
        System.exit(exitCode)
        return true
    }

    // PK-1 / ADR-PK1-IDENTITY: `sdk-info` is a real subcommand.
    // Emits `pipelinek.sdk/1` JSON line on stdout; side-effect free.
    if (args.firstOrNull() == "sdk-info") {
        val exitCode = MainSdkInfoCli.main(args.drop(1).toTypedArray())
        System.exit(exitCode)
        return true
    }

    // WU-LPR-011 F1: `doctor` is a real subcommand. Local-only health
    // diagnostics: JDK version, working directory writability probe. Exits
    // 0 when the local runtime is healthy, 2 on an environment defect.
    if (args.firstOrNull() == "doctor") {
        // WU-LPR-071: route host-environment reads through the canonical
        // RuntimeConfig adapter (single authority); direct System.getProperty
        // here would be a second runtime-value authority (WULpr402 property 6).
        val runtimeConfig = dev.rubentxu.pipeline.v2.application.SystemRuntimeConfig()
        val checks = buildList {
            add("jdk: ${runtimeConfig.property("java.version")} (${runtimeConfig.property("java.vendor")})")
            add("os: ${runtimeConfig.osName()} ${runtimeConfig.property("os.version")}")
            val cwd = Paths.get("").toAbsolutePath()
            val writable = try {
                val probe = Files.createTempFile(cwd, "pipelinek-doctor-", ".probe")
                Files.deleteIfExists(probe)
                true
            } catch (_: java.io.IOException) {
                false
            }
            add("workdir: $cwd (${if (writable) "writable" else "NOT WRITABLE"})")
        }
        checks.forEach(::println)
        System.exit(if (checks.any { it.contains("NOT WRITABLE") }) 2 else 0)
        return true
    }

    // Events subcommand (EVT-2): structured local history inspection
    if (args.firstOrNull() == "events") {
        if (args.getOrNull(1) == "verify") {
            val exitCode = MainEventsVerifyCli.main(args.drop(2).toTypedArray())
            System.exit(exitCode)
            return true
        }
        val exitCode = MainEventsCli.main(args.drop(1).toTypedArray())
        System.exit(exitCode)
        return true
    }

    // Console subcommand (M1-P3): read process output from the Output Plane by OutputCursor.
    //
    // Deliberately a SEPARATE verb from `events`. The two planes are different orders and a
    // different authority: `events` continues on a store-assigned sequence, `console` continues on
    // a committed byte offset and never reads an event. Folding console into `events` — as a view,
    // or as a flag — is the conflation ADR-M1 D3 exists to prevent, and it is exactly what the
    // deprecated EventViewProjection.CONSOLE was.
    if (args.firstOrNull() == "console") {
        val exitCode = MainConsoleCli.main(args.drop(1).toTypedArray())
        System.exit(exitCode)
        return true
    }

    // Observe subcommand (OBS-E5): one public verb over both durable authorities.
    //
    // NOT a third universe alongside `events` and `console` — it reads the same two planes and
    // composes them read-side. What it refuses is the thing neither of the other two can promise:
    // a document claiming an interleaving. `Event.sequence` and `OutputFrame.ordinal` have no
    // total order, so `--view full` stays refused here exactly as it is in `run`.
    if (args.firstOrNull() == "observe") {
        val exitCode = MainObserveCli.main(args.drop(1).toTypedArray())
        System.exit(exitCode)
        return true
    }

    // Credentials subcommand — delegated to MainCredentialsCli
    if (args.firstOrNull() == "credentials") {
        val exitCode = MainCredentialsCli.main(args.drop(1).toTypedArray())
        System.exit(exitCode)
        return true
    }

    // Inspect subcommand (R4 / WU-R4.1, ADR-0088): bounded diagnostic surface.
    //
    // The verb is the same workflow for `inspect` that `observe`, `events`, and `console`
    // are for their lanes: a typed parser + a typed query + a typed projection. The dispatcher
    // adds it without disturbing the existing CLI surface (R4 / WU-R4.5 is the cross-CLI
    // canary that catches any regression here).
    if (args.firstOrNull() == "inspect") {
        val exitCode = MainInspectCli.main(args.drop(1).toTypedArray())
        System.exit(exitCode)
        return true
    }

    // The saga read-side: what a run still owes, read from the durable promises.
    //
    // It is a VERB of its own rather than an option of `inspect` because it reads a
    // different authority: `inspect` reads what a run DID, and this one reads what a run
    // PROMISED and has not yet discharged.
    if (args.firstOrNull() == "saga") {
        val rest = args.drop(1)
        if (rest.firstOrNull() == "inspect") {
            val exitCode = MainSagaInspectCli.main(rest.drop(1).toTypedArray())
            System.exit(exitCode)
            return true
        }
        System.err.println("pipeline: unknown saga subcommand.")
        System.err.println(MainSagaInspectCli.USAGE)
        System.exit(2)
        return true
    }
    return false
}
