package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.credentials.api.RedactingEventSink
import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.events.durable.InMemoryEventStore
import dev.rubentxu.pipeline.v2.application.observation.SelectorCompileResult
import dev.rubentxu.pipeline.v2.application.observation.compileQuery
import dev.rubentxu.pipeline.v2.scripting.Kotlin24ScriptingHost
import dev.rubentxu.pipeline.v2.scripting.ScriptDefinition
import java.nio.file.Paths
import java.nio.file.Files

/**
 * The CLI's verb set, and the only place it is written down.
 *
 * Measured 2026-10-18: three defects came from this list living in prose. A rejected
 * argument printed `Usage: pipeline <validate|run>` — 2 of 11 verbs, under a name the
 * binary does not have. The hint on the same rejection said "run 'pipeline --help'",
 * and `--help` was itself rejected: a promise with nothing behind it. The list inside
 * [CliExitMapping] meanwhile named 10 verbs and omitted `inspect`.
 *
 * Enumerating verbs in a *message* is the census defect ([see AGENTS.md]): a hand-kept
 * list drifts from the dispatch and nobody notices until an operator is misled. A verb
 * list in prose is worse than no list, because it is read as authoritative.
 *
 * So: one constant, rendered by [printUsage], referenced by the hints. The dispatch
 * chain in [MainVerbs.handleReadOnlyVerb] still spells each verb as its own comparison
 * — collapsing the two is a separate concern from telling the truth about the current
 * shape, and it would change the admission order that the per-verb guards depend on.
 */
internal val CLI_VERBS: List<Pair<String, String>> = listOf(
    "validate" to "compile a script and report every diagnostic",
    "run" to "execute a script durably (--db, --resume|--rerun, --control-root)",
    "observe" to "read a finished run's events, console and output (--view, --format)",
    "console" to "read one step's output plane as frames or lines",
    "events" to "read one run's domain events as text or json",
    "inspect" to "read a run id's digests, steps and provenance",
    "credentials" to "list registered credential material (redacted)",
    "doctor" to "report the installation's health",
    "version" to "print the product version (--json)",
    "capabilities" to "print the capability keys this build admits",
    "sdk-info" to "print the SDK coordinates a plugin builds against",
)

/**
 * Environment variables that change behaviour, rendered by [printUsage].
 *
 * ## Why this is a constant and not prose
 *
 * Measured 2026-10-18 before this existed: six `PIPELINE*` literals in product
 * code, **zero** of them reachable from `--help`. `PIPELINE_STORE_PASSPHRASE`
 * appeared only in two error hints, so it was discoverable exactly when
 * something had already gone wrong; `PIPELINE_CREDENTIALS_STORE` appeared only
 * in a comment; the sandbox pair appeared only in SDK KDoc. An operator could
 * not find any of them without reading the source.
 *
 * That is the same failure mode [CLI_VERBS] exists to prevent, on the other
 * surface: a list nobody reads drifts from the code, and here the drift is
 * invisible because a missing env var looks like a program that simply ignored
 * it. `CredentialsStorePath` now resolves every reader to one place, so this
 * list is the only place a new variable has to be remembered — and a fitness
 * checks that a variable added to the code is added here or to
 * [CLI_ENVIRONMENT_INTERNAL].
 *
 * ## The six, and how the first count of three was wrong
 *
 * A sweep for `"PIPELINE[A-Z0-9_]*"` reports six. An earlier sweep of this
 * session reported three, and the fitness disproved the three by failing with
 * the names it had never returned. The cause was not the character class: it
 * was a one-level glob, `v2/<module>/src/main/`, which matches
 * `v2/pipeline-application` but not `v2/pipeline-step-sdk/runtime`, two levels
 * deep. Three of the six live in the SDK. A denominator gathered by a one-level
 * glob is not a small error; it is an error that reads as a complete answer,
 * which is why the row comparing the two sets is the row that carries this
 * block.
 */
internal val CLI_ENVIRONMENT: List<Pair<String, String>> = listOf(
    "PIPELINE_CREDENTIALS_STORE" to "credentials store file (default ~/.pipeline/credentials.bin)",
    "PIPELINE_STORE_PASSPHRASE" to "passphrase that decrypts the credentials store",
    "PIPELINEK_SCRIPT_CACHE_DIR" to "compiled .pipeline.kts cache directory (default off)",
    "PIPELINE_SANDBOX_ALLOW_EXTRA" to "comma-separated env keys the sandbox keeps despite its deny-list",
    "PIPELINE_SANDBOX_PATH_KEEP" to "comma-separated PATH prefixes the sandbox keeps after normalising",
)

/**
 * `PIPELINE*` names the product reads or writes for its OWN protocol, not for an
 * operator's. These are rendered as well, because an operator who finds one in
 * `/proc/<pid>/environ` and cannot tell whether setting it means anything has
 * the same problem as one who cannot find a variable at all.
 *
 * They live in a second named list rather than being skipped inside the
 * fitness, and that is the point: an exemption that is a name in a list can be
 * audited, whereas an exemption expressed as a pattern or a filename can hide a
 * real variable behind it.
 */
internal val CLI_ENVIRONMENT_INTERNAL: List<Pair<String, String>> = listOf(
    "PIPELINE_OP_COOKIE" to "internal: tags one durable operation's process tree (do not set)",
)

/**
 * Resolve the operator-supplied `--output-protocol` tokens to a
 * concrete [OutputProtocol] or fail closed with
 * [CliExit.InvalidArgument] (exit 2).
 *
 * Rules (ADR-PK1-OUTPUT-FRAMING):
 * - Empty list → [OutputProtocol.DEFAULT] (Published). No change
 *   in behaviour for callers that did not opt in.
 * - The token list is the CLIENT'S PREFERENCE ORDER. The first id
 *   in the list that intersects [OutputProtocol.SUPPORTED_IDS] wins.
 *   Unknown ids in the list are SKIPPED, not rejected — clients
 *   may declare more ids than this binary supports, as long as at
 *   least one matches.
 * - When NO id in the list intersects the supported set, the
 *   process exits with [CliExit.InvalidArgument] (code 2) and an
 *   envelope listing every supported id. The server NEVER falls
 *   back to Published on an unknown request — silent degradation
 *   is the exact defect the ADR was written to prevent.
 *
 * Resolution lives in [OutputProtocol.negotiate]; this wrapper
 * only translates "no intersection" into the typed CLI envelope.
 */
private fun negotiateOutputProtocolOrExit(tokens: List<String>): OutputProtocol {
    val resolved = OutputProtocol.negotiate(tokens)
    if (resolved != null) return resolved
    val supported = OutputProtocol.SUPPORTED_IDS.joinToString(", ")
    val requested = tokens.joinToString(", ")
    val exit = CliExit.InvalidArgument(
        message = "no acceptable --output-protocol in [$requested]. Supported: $supported",
        hint = "see ADR-PK1-OUTPUT-FRAMING for the negotiation contract",
    )
    System.err.write(exit.encodeEnvelope().toByteArray(Charsets.UTF_8))
    System.err.flush()
    System.exit(exit.code)
    // unreachable; System.exit never returns control. The return
    // statement below exists only to satisfy the Kotlin compiler's
    // "missing return on a path" check on a function declared with a
    // non-Unit return type.
    return OutputProtocol.DEFAULT
}


/**
 * Opt-in system property that registers the redaction canaries.
 *
 * ## Why it exists rather than the canaries being unconditional
 *
 * A registered secret is not only something to scrub: its encoded length IS the redactor's
 * lookahead, and the lookahead is the number of bytes that must be seen before the first byte can
 * be published. Registering a 28-character canary therefore claims a 56-byte window (its hex form)
 * and withholds up to 56 bytes of live output on EVERY run — including the runs that have no
 * credentials at all and could not leak anything.
 *
 * So the canaries are test material and are requested by tests. The round-gates that need them set
 * this property, which makes the proof explicit and keeps the default run's latency at
 * `MIN_SECRET_WINDOW`.
 */
private const val REDACTION_CANARY_PROPERTY = "pipelinek.redaction.canaries"


/**
 * The CLI's entry point.
 *
 * ## What this function is, after the split
 *
 * The seam between the operator and the product, and deliberately small: it
 * resolves the verb, it decides the world the run lives in, and it hands
 * over. Everything else lives in a file named after the thing it does.
 *
 * Before the split this function was 1 148 lines and carried two detekt
 * suppressions (`LongMethod` and `CyclomaticComplexMethod`) that the baseline
 * kept quiet. The baseline is where a smell goes to stop being visible, so
 * the split is measured by the baseline entries it removes, not by the number
 * of files it creates.
 *
 * ## The order of the checks is the contract
 *
 *  1. `--help`/`-h`/`help` are real verbs.
 *  2. The read-only verbs answer and exit; they never reach the parser.
 *  3. The parser decides, and its rejection is rendered by [CliExitMapping].
 *  4. `--output-protocol` is validated BEFORE the run starts.
 *  5. The script is checked for existence BEFORE any store, journal or
 *     process is created.
 *  6. `validate` compiles and reports; it never starts a process.
 *  7. `run` picks its world: volatile without `--db`, durable with it.
 */
fun main(args: Array<String>) {
    // `--help`, `-h` and `help` are real. Before this they fell through to the same
    // rejection that told the operator to run them.
    if (isHelpRequest(args)) {
        printUsage(System.out)
        return
    }

    // The verbs that only read. They answer and exit; nothing after this point
    // is reached when one of them is the verb.
    if (handleReadOnlyVerb(args)) {
        return
    }

    val config = when (val parsed = CliParser.parse(args)) {
        is CliParseResult.Parsed -> parsed.flags
        is CliParseResult.Rejected -> {
            // Render the rejection through the ONE mapping that already carries a human
            // message and an operator hint. Interpolating `parsed.error` printed the sealed
            // ADT's toString (`InvalidCommand(value=observ)`) and then a second, hand-written
            // usage line that named 2 of 11 verbs — a second rendering of the same fact,
            // free to disagree with the first, which is how `inspect` and `--help` went missing.
            val exit = parsed.error.toCliExit()
            System.err.println("Invalid CLI arguments: ${exit.message}")
            exit.hint?.let { System.err.println(it) }
            printUsage(System.err)
            System.exit(1)
            return
        }
    }

    // PK-0-57-RC1 / ADR-PK1-OUTPUT-FRAMING: validate --output-protocol
    // BEFORE the run starts. An unknown id is a fail-closed
    // CliExit.InvalidArgument (exit 2); the server never falls back
    // to Published on an unknown request, because that would be the
    // silent-degradation defect the ADR was written to prevent.
    val outputProtocol: OutputProtocol = negotiateOutputProtocolOrExit(config.outputProtocolTokens)

    val command = config.command

    // The parser already refused an unusable query (empty pattern, uncompilable
    // regex, `--grep-invert` with nothing to negate), so this is the
    // interpretation step and not a second validation. Compiling once here means
    // the query is built a single time for every output path below.
    val compiledQuery = (compileQuery(config.query) as SelectorCompileResult.Ok).value

    // Shared secret pattern registry for redaction (T6)
    // Both InMemoryEventStore and SqliteEventStore are wrapped at construction time
    // so all downstream consumers receive already-sanitized events.
    val secretPatternRegistry = SecretPatternRegistry()

    // CR-RD-008 / CR-RD-021 / ARC-CANARY-001 canaries, registered ONLY when a round-gate asks for
    // them. They used to be unconditional, and they were not free.
    //
    // `SecretPatternRegistry.maxLiteralByteLength()` is the redactor's LOOKAHEAD, and the lookahead
    // is the floor below which no live output is observable at all: a byte inside it could still
    // turn out to begin a secret, so the redactor must hold it until more input rules it out.
    // `generateEncodedVariants` includes a hex form, so the 28-character canary below claims a
    // 56-byte window. Measured against the installed distribution, with the canaries registered a
    // step that printed 32 bytes committed NOTHING to the Output Plane until it exited — not
    // because the pump was late, but because 32 < 56. Every real run was paying 49 bytes of
    // observability latency for test material.
    //
    // Gating them removes no protection. These values are synthetic and appear in no real
    // credential; what protects a real credential is the secrets registered from the credential
    // store, which are added independently. And the round-gate that needs them now says so
    // explicitly, so the proof is opt-in and visible instead of ambient.
    if (System.getProperty(REDACTION_CANARY_PROPERTY) == "true") {
        // CR-RD-008 canary: synthetic secret for round-gate verification.
        secretPatternRegistry.addSecret(SecretHandle.plain("GHS6_CANARY_7f3a9c2e1b4d5e6f"))

        // CR-RD-021 ssh canary: synthetic secret for SSH channel round-gate verification.
        secretPatternRegistry.addSecret(SecretHandle.plain("__ssh_canary__"))

        // ARC-CANARY-001 / CR-RD-022 artefact canary: synthetic secret for artefact step round-gate.
        secretPatternRegistry.addSecret(SecretHandle.plain("__artefact_canary__"))
    }

    val scriptPath = Paths.get(config.scriptPath)

    // DEBT-CLI-SCRIPT-NOT-FOUND: a missing/unreadable script used to reach
    // `scriptPath.toFile().readText()` deep in each mode and surface as a raw
    // `NoSuchFileException` stacktrace. Reject it here — at the CLI boundary, before
    // any store, journal or process is created — with a typed, actionable message.
    // Exit code 2 matches the other input/usage rejections (e.g. --control-root).
    if (!Files.isRegularFile(scriptPath) || !Files.isReadable(scriptPath)) {
        System.err.println(
            "Error: pipeline script not found or not readable: ${scriptPath.toAbsolutePath()}",
        )
        System.exit(2)
        return
    }

    if (command == CliCommand.VALIDATE) {
        // M2-002: validate NEVER starts processes. It compiles the script
        // and reports diagnostics — nothing else.
        val rawStore = InMemoryEventStore()
        val store = RedactingEventSink(rawStore, secretPatternRegistry)
        val validateRunId = java.util.UUID.randomUUID().toString()
        val host = Kotlin24ScriptingHost(store, validateRunId)
        val dslClasspath = computeScriptClasspath(config.pluginJars)
        val definition = ScriptDefinition.file(scriptPath, classpath = dslClasspath)
        // Inject the production RuntimeConfig so DSL `pwd()` / `isUnix()` synchronous
        // return values reflect the host environment during validation.
        dev.rubentxu.pipeline.v2.dsl.DslRuntimeConfigScope.set(SystemRuntimeConfig())
        val compileResult = try {
            host.compile(definition)
        } finally {
            dev.rubentxu.pipeline.v2.dsl.DslRuntimeConfigScope.clear()
        }
        val events = store.eventsFor(validateRunId).toList()
        writeEventDocument(
            events = events,
            view = config.view,
            format = config.format,
            query = compiledQuery,
            protocol = outputProtocol,
        )
        // TRAIN-DSL-HONESTY: a script that compiles but throws while building
        // its IR reaches here with isSuccess == true and no usable spec.
        // Reporting SUCCESS for it was the same DEFAULT_SUCCESS the run path
        // turned into an NPE.
        //
        // A compilation produces a LIST of diagnostics. Measured 2026-10-18 on
        // the built distribution, the previous shape collapsed it to one
        // message with `lastOrNull`: a script with nine errors printed exactly
        // one of them — the LAST one, which is the least likely to be the one
        // the author wrote first — while the cardinality stayed visible. This
        // verdict only has to answer "may it run, and if not, how many
        // reasons"; the renderer above already printed each one with its
        // position.
        val evalFailures = compileResult.diagnostics
            .filter { it.severity == dev.rubentxu.pipeline.v2.scripting.ScriptDiagnosticSeverity.ERROR }
            .mapNotNull { it.message.takeIf(String::isNotBlank) }
        val noSpec = compileResult.isSuccess &&
            compileResult.scriptInstance?.javaClass?.methods?.any { it.name == "get\$\$result" } != true
        if (!compileResult.isSuccess || evalFailures.isNotEmpty() || noSpec) {
            // WU-LPR-011 F3: compile failure is an invocation/admission error,
            // not a pipeline execution failure. Exit 2 per the canonical
            // contract (0 success / 1 pipeline fail / 2 invocation+compile).
            //
            // The verdict is ONE line. The reasons were just printed above by
            // the console renderer, each with path:line:column, on this same
            // stream — repeating them here would print every error twice, and
            // a doubled diagnostic reads as two distinct problems.
            val verdict = when {
                evalFailures.size == 1 -> "1 error"
                evalFailures.size > 1 -> "${evalFailures.size} errors"
                else -> "the script produced no pipeline"
            }
            System.err.println("VALIDATION FAILED: $verdict")
            System.exit(2)
        } else {
            System.err.println("VALIDATION SUCCESSFUL")
        }
        return
    }

    // "run" command. The world is decided here and only here: without `--db`
    // the run is volatile, with it the run is durable. Both worlds run the SAME
    // canonical pipeline; what changes is the durability of the promise, not
    // the algorithm.
    if (config.dbPath == null) {
        runVolatilePipeline(
            config = config,
            secretPatternRegistry = secretPatternRegistry,
            compiledQuery = compiledQuery,
            scriptPath = scriptPath,
            outputProtocol = outputProtocol,
        )
        return
    }
    runDurablePipeline(
        config = config,
        dbPath = config.dbPath,
        secretPatternRegistry = secretPatternRegistry,
        compiledQuery = compiledQuery,
        scriptPath = scriptPath,
    )
}
