package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.runtime.PipelineTelemetry
import dev.rubentxu.pipeline.v2.runtime.scripted.ScriptedFrontendRunner
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.scripting.CompiledScriptCache
import dev.rubentxu.pipeline.v2.scripting.CompiledScriptedEntryPoint
import dev.rubentxu.pipeline.v2.scripting.KotlinScriptedSourceMapper
import dev.rubentxu.pipeline.v2.scripting.ScriptCompilationResult
import dev.rubentxu.pipeline.v2.scripting.ScriptDefinition
import dev.rubentxu.pipeline.v2.scripting.ScriptedFrontendForm
import dev.rubentxu.pipeline.v2.scripting.ScriptedSource
import dev.rubentxu.pipeline.v2.scripting.ScriptedSourceId
import dev.rubentxu.pipeline.v2.scripting.ScriptedSourceLowering
import dev.rubentxu.pipeline.v2.scripting.ScriptedSourceLowering.LoweringResult
import java.nio.file.Path

/**
 * The source of a durable run, compiled and classified.
 *
 * The DEFINITION is compiled BEFORE the composition is admitted — the order the durable
 * branch has always had. A definition the runtime refuses must not first print the
 * plugin-discovery lines: the operator has to read the refusal, not a preamble.
 */
internal data class DurableScript(
    val compilation: ScriptCompilation,
    val scripted: ScriptedFrontend,
    val pipeline: CompiledPipeline?,
)

/**
 * The scripted frontend of a durable run, when the source is generator-level.
 *
 * frontend FORM selection. The compiler-backed mapping decides
 * whether this source contains runtime-effectful scripted calls; the CLI only
 * picks the closed artifact FORM, never an execution authority and never a
 * concrete Step (the CLI may choose the frontend, never the backend).
 */
internal data class ScriptedFrontend(
    val artifact: ScriptedFrontendRunner.EntryPointArtifact?,
    val entryPoint: CompiledScriptedEntryPoint?,
)

/**
 * Compile the script of a durable run, in the ONE way its shape allows.
 *
 * ## The shape is decided BEFORE anything is compiled
 *
 * A `.pipeline.kts` source is one of two programs, and the difference is not
 * cosmetic: the eager DSL builds a [dev.rubentxu.pipeline.v2.dsl.PipelineSpec]
 * while the script runs, and the generator-level form builds an entry point
 * whose body runs INSIDE the run, with its runtime calls rewritten onto the
 * scripted facade.
 *
 * The shape is read from the STRUCTURE the compiler already parsed — does the
 * source CALL the eager DSL's entry point, and does it hold runtime-returning
 * calls? — and never from a match over the script's text: a comment or a
 * string that mentions `pipeline {` is prose, and prose does not decide which
 * program runs.
 *
 * ## One host, one compilation
 *
 * The shape used to be decided AFTER the original script was compiled, and the
 * generator-level form then compiled the LOWERED source with a SECOND host.
 * That bought nothing: the lowered source is the program that runs, so the
 * original was a program nobody ran, compiled and evaluated for nothing — and
 * evaluating it ran the author's body at compile time, so a runtime call
 * happened once before the run and once inside it.
 *
 * Deciding the shape first is what makes ONE compilation possible: the eager
 * form compiles the source it was given, and the generator-level form lowers
 * the source and compiles the lowered program, which is the program that runs
 * and the program that is cached.
 */
internal fun compileDurableScript(
    eventStore: EventSink,
    runId: String,
    scriptPath: Path,
    scriptContent: String,
    pluginJars: List<String>,
    telemetry: PipelineTelemetry,
    cache: CompiledScriptCache = CompiledScriptCache.fromEnvironment(),
): DurableScript {
    val sourceId = ScriptedSourceId(scriptPath.fileName.toString())
    val form = ScriptedFrontendForm.select(ScriptedSource(sourceId, scriptContent))
    return when (form) {
        ScriptedFrontendForm.Form.EAGER_DSL -> compileEagerScript(
            eventStore = eventStore,
            runId = runId,
            scriptPath = scriptPath,
            scriptContent = scriptContent,
            pluginJars = pluginJars,
            telemetry = telemetry,
            cache = cache,
        )
        ScriptedFrontendForm.Form.GENERATOR_LEVEL -> compileGeneratorLevelScript(
            eventStore = eventStore,
            runId = runId,
            sourceId = sourceId,
            scriptContent = scriptContent,
            pluginJars = pluginJars,
            telemetry = telemetry,
            cache = cache,
        )
    }
}

/**
 * The eager DSL: the source IS the program, so it is compiled as it was
 * written, once.
 */
private fun compileEagerScript(
    eventStore: EventSink,
    runId: String,
    scriptPath: Path,
    scriptContent: String,
    pluginJars: List<String>,
    telemetry: PipelineTelemetry,
    cache: CompiledScriptCache,
): DurableScript {
    val compilation = compileScriptForRun(
        eventStore = eventStore,
        runId = runId,
        scriptPath = scriptPath,
        pluginJars = pluginJars,
        telemetry = telemetry,
        cache = cache,
    )
    val pipeline = compilation.pipelineSpec?.let { spec ->
        compileOrRefuse(
            spec = spec,
            sourcePath = scriptPath.toString(),
            sourceContent = scriptContent,
        )
    }
    return DurableScript(
        compilation = compilation,
        scripted = ScriptedFrontend(artifact = null, entryPoint = null),
        pipeline = pipeline,
    )
}

/**
 * The generator-level form: the source is LOWERED into a program whose body
 * runs inside the run, and THAT program is compiled, once.
 *
 * ## Why the lowering can refuse
 *
 * The lowering reads the source with the compiler's own parser, and a source
 * it cannot map is refused rather than half-lowered: a body whose runtime call
 * survived unmapped would fail to compile in generated code the author never
 * wrote. The refusal is a process refusal, not a run failure, because nothing
 * was admitted to run.
 */
private fun compileGeneratorLevelScript(
    eventStore: EventSink,
    runId: String,
    sourceId: ScriptedSourceId,
    scriptContent: String,
    pluginJars: List<String>,
    telemetry: PipelineTelemetry,
    cache: CompiledScriptCache,
): DurableScript {
    val lowered = ScriptedSourceLowering.lower(
        sourceId = sourceId,
        sourceText = scriptContent,
        mapper = KotlinScriptedSourceMapper(),
        facadeSchemaVersion = ScriptedSourceLowering.FACADE_SCHEMA_VERSION,
    )
    val generated = when (lowered) {
        is LoweringResult.Generated -> lowered
        is LoweringResult.InvalidSyntax -> {
            System.err.println("Error: scripted source lowering failed: ${lowered.diagnostics}")
            throw CommandRefused(CliExit.InvalidArgument(message = "the scripted source could not be lowered"))
        }
    }
    val definitionScript = """
        ${generated.source}
    """.trimIndent()
    val definition = ScriptDefinition.inline(
        text = definitionScript,
        classpath = computeScriptClasspath(pluginJars),
    )
    val compilation = compileScriptedForRun(
        eventStore = eventStore,
        runId = runId,
        definition = definition,
        telemetry = telemetry,
        cache = cache,
    )
    if (compilation.failure != null) {
        val diagnostics = (compilation.result as? ScriptCompilationResult.Failure)?.diagnostics
        System.err.println("Error: scripted frontend compilation failed: $diagnostics")
        throw CommandRefused(CliExit.InvalidArgument(message = "the scripted frontend did not compile"))
    }
    val entryPoint = compilation.entryPoint
    if (entryPoint == null) {
        System.err.println("Error: scripted frontend did not produce a compiled entry point")
        throw CommandRefused(CliExit.InvalidArgument(message = "the scripted frontend did not produce a compiled entry point"))
    }
    return DurableScript(
        compilation = compilation,
        scripted = ScriptedFrontend(
            artifact = ScriptedFrontendRunner.EntryPointArtifact(
                loweredSource = generated.source,
                identity = generated.artifact,
            ),
            entryPoint = entryPoint,
        ),
        pipeline = null,
    )
}
