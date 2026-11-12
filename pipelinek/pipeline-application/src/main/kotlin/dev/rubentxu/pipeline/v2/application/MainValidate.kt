package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.observation.CompiledObservationQuery
import dev.rubentxu.pipeline.v2.credentials.api.RedactingEventSink
import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.dsl.DslRuntimeConfigScope
import dev.rubentxu.pipeline.v2.dsl.ParameterDescription
import dev.rubentxu.pipeline.v2.events.durable.InMemoryEventStore
import dev.rubentxu.pipeline.v2.runtime.SystemRuntimeConfig
import dev.rubentxu.pipeline.v2.scripting.CompiledScriptCache
import dev.rubentxu.pipeline.v2.scripting.Kotlin24ScriptingHost
import dev.rubentxu.pipeline.v2.scripting.ScriptDefinition
import dev.rubentxu.pipeline.v2.scripting.ScriptDiagnosticSeverity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.PrintStream
import java.nio.file.Path

/**
 * The `validate` verb: compiles the script, reports on [out] and [err], and returns the exit the
 * process ends with.
 *
 * It writes to the streams it is given and ends nothing, like [handleInventoryVerb]: `main` owns
 * the process and turns the returned exit into its exit code, and a test reaches the verb through
 * the same function `main` calls.
 *
 * The parser, the protocol negotiation and the check that the script exists have already run; this
 * function starts where `main` hands the verb its inputs.
 *
 * [cache] is where a compiled script is looked up and stored. `main` hands it the one the
 * environment names, as every other compilation of the product uses; a test hands it a cache of its
 * own, so it neither reads nor writes the operator's.
 */
@Suppress("LongParameterList")
internal fun validateScript(
    config: CliFlags,
    scriptPath: Path,
    secretPatternRegistry: SecretPatternRegistry,
    compiledQuery: CompiledObservationQuery,
    outputProtocol: OutputProtocol,
    cache: CompiledScriptCache,
    out: PrintStream,
    err: PrintStream,
): CliExit {
    // What `validate` does with the author's BODY is the trust model, and
    // the model is owned by [ScriptTrust] — the help renders it, and
    // nothing here restates it. Two forms, both real:
    //
    //   - the default one compiles the script AND evaluates it, so the
    //     author's top-level body runs inside this process;
    //   - `--compile-only` compiles the script and does NOT run it.
    val rawStore = InMemoryEventStore()
    val store = RedactingEventSink(rawStore, secretPatternRegistry)
    val validateRunId = java.util.UUID.randomUUID().toString()
    val host = Kotlin24ScriptingHost(store, validateRunId, cache = cache)
    val dslClasspath = try {
        computeScriptClasspath(config.pluginJars)
    } catch (refused: CommandRefused) {
        // Validation holds nothing, so the refusal is the verdict right here.
        return refused.exit
    }
    val definition = ScriptDefinition.file(scriptPath, classpath = dslClasspath)
    // Inject the production RuntimeConfig so DSL `pwd()` / `isUnix()` synchronous
    // return values reflect the host environment during validation.
    DslRuntimeConfigScope.set(SystemRuntimeConfig())
    val compileResult = try {
        // The EVALUATOR is the only difference between the two forms, and
        // it is swapped inside the host: the compilation is the same one
        // either way, so a compile-only validation answers "does THIS
        // script compile?" and not "does a program that resembles it
        // compile?".
        if (config.compileOnly) host.compileOnly(definition) else host.compile(definition)
    } finally {
        DslRuntimeConfigScope.clear()
    }
    // The DESCRIPTION of the parameters the script declares.
    //
    // It is a projection of the declaration the compilation just produced, and it is
    // printed INSTEAD of the run's events: the operator asked for the contract, not
    // for what a run did.
    //
    // A script that did not compile is NOT described here: the diagnostics of the
    // failure are what the operator has to read, and they are printed below.
    if (config.describeParameters && compileResult.isSuccess) {
        val declaration = pipelineSpecOf(compileResult)?.parameters.orEmpty()
        out.println(
            Json.encodeToString(
                JsonObject.serializer(),
                ParameterDescription.of(declaration),
            ),
        )
        return CliExit.SUCCESS
    }
    val events = store.eventsFor(validateRunId).toList()
    writeEventDocument(
        events = events,
        view = config.view,
        format = config.format,
        query = compiledQuery,
        protocol = outputProtocol,
        out = out,
        err = err,
    )
    // a script that compiles but throws while building
    // its IR reaches here with isSuccess == true and no usable spec.
    // Reporting SUCCESS for it was the same DEFAULT_SUCCESS the run path
    // turned into an NPE.
    //
    // A compilation produces a LIST of diagnostics. Measured on the built
    // distribution, the previous shape collapsed it to one message with
    // `lastOrNull`: a script with nine errors printed exactly one of them —
    // the LAST one, which is the least likely to be the one
    // the author wrote first — while the cardinality stayed visible. This
    // verdict only has to answer "may it run, and if not, how many
    // reasons"; the renderer above already printed each one with its
    // position.
    val evalFailures = compileResult.diagnostics
        .filter { it.severity == ScriptDiagnosticSeverity.ERROR }
        .mapNotNull { it.message.takeIf(String::isNotBlank) }
    // A compile-only validation has NO script instance on purpose: nothing
    // was evaluated, so there is no instance to inspect. Asking it for a
    // pipeline would be asking a question the form deliberately did not
    // answer, and the verdict below would report a missing pipeline for a
    // script that may declare a perfectly good one.
    val noSpec = !config.compileOnly && compileResult.isSuccess &&
        compileResult.scriptInstance?.javaClass?.methods?.any { it.name == "get\$\$result" } != true
    if (!compileResult.isSuccess || evalFailures.isNotEmpty() || noSpec) {
        // compile failure is an invocation/admission error,
        // not a pipeline execution failure. Exit 2 per the canonical
        // contract (0 success / 1 pipeline fail / 2 invocation+compile).
        //
        // The verdict is ONE line. The reasons were just printed above by
        // the renderer, each with path:line:column — the compiler's and the
        // one the body raised while it was evaluated, because the host puts
        // both in the event the renderer reads. Repeating them here would
        // print every error twice, and a doubled diagnostic reads as two
        // distinct problems.
        val verdict = when {
            evalFailures.size == 1 -> "1 error"
            evalFailures.size > 1 -> "${evalFailures.size} errors"
            config.compileOnly -> "the script did not compile"
            else -> "the script produced no pipeline"
        }
        err.println("VALIDATION FAILED: $verdict")
        return CliExit.INVALID_ARGUMENT
    }
    // Two validations that did DIFFERENT things must not print the
    // same line: the operator has to be able to tell from the output
    // alone whether the author's body ran.
    err.println(
        if (config.compileOnly) {
            "VALIDATION SUCCESSFUL (${ScriptTrust.COMPILE_ONLY_NOTE})"
        } else {
            "VALIDATION SUCCESSFUL"
        },
    )
    return CliExit.SUCCESS
}
