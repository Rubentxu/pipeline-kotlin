package dev.rubentxu.pipeline.v2.scripting

import dev.rubentxu.pipeline.v2.events.CompilationFinished
import dev.rubentxu.pipeline.v2.events.CompilationStarted
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.NullEventSink
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.SourceCode
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.compilerOptions
import kotlin.script.experimental.host.StringScriptSource
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.updateClasspath
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate

/**
 * Kotlin 2.4.10 adapter that wraps [CachingJvmScriptingHost] (a
 * [kotlin.script.experimental.jvmhost.BasicJvmScriptingHost] that may reuse a
 * stored compilation) and
 * exposes the [ScriptingHost] contract from `:pipeline-scripting-api`.
 *
 * Design contract:
 *  - Uses the canonical V1 pattern: `createJvmCompilationConfigurationFromTemplate<Any>`
 *    which resolves to the built-in Kotlin script definition (no custom
 *    `@KotlinScript` annotation needed — the file extension is conveyed
 *    via [SourceCodeFactory] producing a [kotlin.script.experimental.host.FileScriptSource]
 *    whose `name` is the file's basename; the host recognises the
 *    `.pipeline.kts` extension through that).
 *  - Builds the classpath via `jvm { dependenciesFromCurrentContext() }`
 *    (default `wholeClasspath = false` — i.e. the current compilation
 *    context classpath only: kotlin-stdlib, kotlin-script-runtime,
 *    kotlin-reflect, the scripting-jvm-host artifacts on this module's
 *    compile classpath). No `wholeClasspath = true` appears anywhere in
 *    production.
 *  - Per-call jars supplied via [ScriptDefinition.classpath] are appended
 *    through `jvm { updateClasspath(files) }` inside the eval body.
 *  - Returns a stable [ScriptCompilationResult.cacheKey] computed from
 *    sha256(scriptText | sortedClasspath | kotlinVersion | hostVersion).
 *  - Maps [ScriptDiagnostic] fields 1:1 to [ScriptingDiagnostic] so the
 *    editor/UAT harness can render source-mapped errors.
 */
class Kotlin24ScriptingHost(
    private val eventSink: EventSink = NullEventSink,
    private val runId: String? = null,
    private val cache: CompiledScriptCache = CompiledScriptCache.fromEnvironment(),
) : ScriptingHost {

    /** Kotlin language version fed into the cache key. */
    private val kotlinVersion = "2.4.10"

    /**
     * Host implementation version fed into the cache key.
     *
     * S0-C1: bumped to 1.1.0 when `-Xreturn-value-checker=check` was added, and
     * M1-F.1: bumped to 1.2.0 when `-Xuse-fast-jar-file-system=false` was added. In both
     * cases the bump was the ONLY thing invalidating previously compiled scripts, because the
     * options themselves were not part of the key — OBSERVED in S0-C1: the flag was in the
     * binary, yet a script compiled before the change still discarded its result silently.
     *
     * That defence is now a second line rather than the only one: [compilerFlags] is its own
     * cache-key dimension (see [cacheKeyFor]), so an option change invalidates the key whether
     * or not anyone remembers this value. The bumps above stay, because [hostVersion] still
     * covers host changes that are not options — a different classpath assembly, a different
     * compiler invocation — and because a stale `v1` label is still on old event payloads.
     */
    private val hostVersion = "1.2.0"

    /**
     * The compiler options the host always passes to the Kotlin scripting host.
     *
     * M1-F.1: `-Xuse-fast-jar-file-system=false` is mandatory. Kotlin 2.4.10's
     * [FastJarFileSystemKt] (in kotlin-stdlib) calls
     * `sun.misc.Unsafe::invokeCleaner`, which on JDK 21+ emits a warning and
     * is scheduled for removal. Disabling the fast jar file system routes
     * jar reads through the regular `JarFile` path, which is the documented
     * workaround and removes the call entirely. See ADR-M1-F D1.
     *
     * The option list is read directly into the compilation identity (see [cacheKeyFor]), so
     * adding an option invalidates the key by construction. It was previously covered only by
     * the [hostVersion] bump discipline described above, which is a comment asking every future
     * author to remember something that the key can enforce instead.
     */
    private val compilerFlags: List<String> = listOf(
        // Pure Builder Consumption Gate: an unconsumed return value from
        // a MUST_CONSUME PURE_BUILDER is a compile error in a
        // `.pipeline.kts`. This is Kotlin's own return-value checker
        // (`@MustUseReturnValues` + `-Xreturn-value-checker=check`) —
        // no custom compiler plugin and no second source of truth.
        // The annotation is only enforced where the checker is enabled,
        // so other consumers of the DSL library are unaffected.
        "-Xreturn-value-checker=check",
        // M1-F.1: real elimination of `sun.misc.Unsafe::invokeCleaner`
        // calls from Kotlin 2.4.10's [FastJarFileSystemKt]. Routes jar
        // reads through the legacy `JarFile` path, which does not
        // touch Unsafe. See the field KDoc above.
        "-Xuse-fast-jar-file-system=false",
    )

    override fun compile(definition: ScriptDefinition): ScriptCompilationResult {
        val effectiveRunId = runId ?: definition.sourcePath?.fileName?.toString() ?: UUID.randomUUID().toString()
        val compilationStartedId = UUID.randomUUID().toString()
        val compilationFinishedId = UUID.randomUUID().toString()
        val compilationStartedAt = Instant.now()

        eventSink.append(
            CompilationStarted(
                eventId = compilationStartedId,
                runId = effectiveRunId,
                sequence = 0L,
                occurredAt = compilationStartedAt,
            )
        )

        // Extract env vars from the original script text before any escaping.
        // This must happen before creating `source` so we pass escaped text to the compiler.
        val scriptText = definition.sourceText
            ?: definition.sourcePath?.toFile()?.readText()
            ?: ""
        val envVars = EnvVarNameExtractor.extract(scriptText)
        val escapedText = ScriptTextEscaper.escape(scriptText, envVars)

        // Pass the escaped text to the Kotlin compiler; the original scriptText
        // (above) is preserved verbatim for the cache key computation below.
        val source: SourceCode = StringScriptSource(
            escapedText,
            name = definition.sourcePath?.toString() ?: "<inline>",
            locationId = definition.sourcePath?.toString() ?: "<inline>"
        )

        // Per-call classpath files from the script definition (may be empty).
        // We resolve to absolute canonical paths so the cache key stays stable
        // across relative/absolute invocations of the same logical script.
        val classpathFiles = definition.classpath.map { File(it).canonicalFile }
        val sortedClasspath = classpathFiles.map { it.canonicalPath }.sorted().joinToString(",")

        // S0-C1 (Pure Builder Consumption Gate)
        //
        // WHY `evalWithTemplate` AND NOT A PRE-BUILT CONFIGURATION
        // ========================================================
        // OBSERVED: with `createJvmCompilationConfigurationFromTemplate` the
        // `compilerOptions` key is accepted, stored, and then IGNORED — a
        // discarded `@MustUseReturnValues` result still compiled silently.
        //
        // ROOT CAUSE (verified against the 2.4.10 artifacts, not guessed):
        //   - `kotlin-scripting-jvm` and `kotlin-scripting-jvm-host` contain
        //     ZERO references to "compilerOptions".
        //   - `kotlin-scripting-compiler-impl-embeddable` references it only in
        //     `org.jetbrains.kotlin.scripting.definitions.ScriptDefinition`.
        // So the option is read from a `ScriptDefinition`, which only
        // `evalWithTemplate` constructs. Handing the host a pre-built
        // `ScriptCompilationConfiguration` bypasses that read entirely.
        //
        // `evalWithTemplate` keeps ONE compilation body — the DSL default
        // imports, the per-call classpath and the gate flag are all declared in
        // the same builder — so there is still a single source of truth for how
        // a `.pipeline.kts` is compiled.
        // S0-C1 / TRAIN-DSL-HONESTY: a fail-closed DSL guard that throws while
        // the script BODY is being built (e.g. the Pure Builder Consumption
        // gate rejecting an unconsumed MUST_CONSUME carrier) escapes the entry
        // point as a plain exception, BEFORE the evaluator can wrap it in
        // ResultValue.Error.
        //
        // Without this, the throwable left `compile()` entirely, the exception
        // surfaced inside the durable run, and a BUILD-TIME rejection was
        // misreported to the author as a StepFailed — a construction error
        // dressed up as a runtime step failure, with a StepStarted emitted for
        // a step that never legitimately existed. Mapping it to a compilation
        // Failure restores the honest category: the pipeline could not be
        // BUILT, and nothing was admitted.
        // Identity is computed BEFORE the compilation so the same value can be
        // handed to the cache (as the store key) and to the emitted event. A key
        // derived inside the caching host instead would be a second derivation
        // of one fact, and the two could disagree without any visible symptom.
        val cacheKey = cacheKeyFor(scriptText, sortedClasspath)

        // A fresh host per compilation: the cache key is fixed data for this one
        // compilation, so this object carries no mutable state across compiles.
        val host = CachingJvmScriptingHost(cache, cacheKey)

        val rwd: ResultWithDiagnostics<*> = try {
            host.evalWithTemplate<Any>(
                source,
            {
                // Pure Builder Consumption Gate + M1-F.1 fast-jar-file-system
                // option. Both live in [compilerFlags] so they share the same
                // authority and the same cache-key input. The S0-C1 / M1-F.1
                // comments on those fields explain why each option is here.
                this[compilerOptions] = compilerFlags
                jvm {
                    dependenciesFromCurrentContext()
                    if (classpathFiles.isNotEmpty()) {
                        updateClasspath(classpathFiles)
                    }
                }
                defaultImports(
                    "dev.rubentxu.pipeline.v2.dsl.pipeline",
                    "dev.rubentxu.pipeline.v2.dsl.stages",
                    "dev.rubentxu.pipeline.v2.dsl.stage",
                    "dev.rubentxu.pipeline.v2.dsl.echo",
                    "dev.rubentxu.pipeline.v2.dsl.sh",
                    "dev.rubentxu.pipeline.v2.dsl.PipelineSpec",
                    "dev.rubentxu.pipeline.v2.dsl.StageSpec",
                    "dev.rubentxu.pipeline.v2.dsl.StepSpec",
                    "dev.rubentxu.pipeline.v2.dsl.PipelineScope",
                    "dev.rubentxu.pipeline.v2.dsl.StagesScope",
                    "dev.rubentxu.pipeline.v2.dsl.StageScope",
                    "dev.rubentxu.pipeline.v2.dsl.StageBuilder",
                    "dev.rubentxu.pipeline.v2.scripting.CompiledScriptedEntryPoint",
                    "dev.rubentxu.pipeline.v2.scripting.ScriptedArtifactIdentity",
                    "dev.rubentxu.pipeline.v2.scripting.ScriptedStepFacade",
                    "dev.rubentxu.pipeline.v2.scripting.ScriptedCallSiteId",
                    "dev.rubentxu.pipeline.v2.scripting.ScriptedDynamicScopeId",
                    "dev.rubentxu.pipeline.v2.scripting.ScriptedSourceId",
                    "dev.rubentxu.pipeline.v2.scripting.ScriptedBlockName",
                    "dev.rubentxu.pipeline.v2.scripting.ScriptedSourceLocation",
                    "dev.rubentxu.pipeline.v2.scripting.ReturnStdout",
                    "dev.rubentxu.pipeline.v2.scripting.ReturnStatus",
                    "dev.rubentxu.pipeline.v2.domain.CredentialsId",
                    "dev.rubentxu.pipeline.v2.domain.CredentialsRef"
                )
            },
            {},
        )
        } catch (t: Throwable) {
            return compilationFailure(t, effectiveRunId, scriptText, sortedClasspath, definition)
        }

        val compilationFinishedAt = Instant.now()

        val diagnostics = rwd.reports
            .filter { it.severity >= ScriptDiagnostic.Severity.INFO }
            .map(::mapDiagnostic)
        val result = if (rwd is ResultWithDiagnostics.Success) {
            @Suppress("UNCHECKED_CAST")
            val evalResult = rwd.value as kotlin.script.experimental.api.EvaluationResult

            // TRAIN-DSL-HONESTY: a script body that throws during DSL
            // construction (any `require(false)` in a stage body, or a
            // fail-closed DSL guard such as `whenCondition`) surfaces here as a
            // ResultValue.Error while `rwd` still reports Success. Mapping that
            // to Success with a spec-less instance was the DEFAULT_SUCCESS that
            // produced an NPE at the canonical run (`runCanonical`, then
            // `runCanonicalPipeline(compiledPipeline!!)`) with exit 0, and made
            // `validate` report VALIDATION SUCCESSFUL for a script that cannot
            // build its IR.
            val evalError = evalResult.returnValue as? kotlin.script.experimental.api.ResultValue.Error
            if (evalError != null) {
                ScriptCompilationResult.Failure(
                    diagnostics = diagnostics + ScriptingDiagnostic(
                        severity = ScriptDiagnosticSeverity.ERROR,
                        message = evalError.error.message ?: evalError.error.toString(),
                        line = 0,
                        column = 0,
                        path = definition.sourcePath?.toString() ?: "<inline>",
                    ),
                    cacheKey = cacheKey,
                )
            } else {
                ScriptCompilationResult.Success(
                    output = mapEvaluationOutput(evalResult.returnValue),
                    scriptInstance = evalResult.returnValue.scriptInstance,
                    diagnostics = diagnostics,
                    cacheKey = cacheKey,
                )
            }
        } else {
            ScriptCompilationResult.Failure(
                diagnostics = diagnostics,
                cacheKey = cacheKey,
            )
        }

        eventSink.append(
            CompilationFinished(
                eventId = compilationFinishedId,
                runId = effectiveRunId,
                sequence = 0L,
                occurredAt = compilationFinishedAt,
                cacheKey = cacheKey,
                diagnostics = diagnostics,
            )
        )

        return result
    }

    private fun mapEvaluationOutput(returnValue: ResultValue): ScriptEvaluationOutput = when (returnValue) {
        is ResultValue.Value -> when (val value = returnValue.value) {
            is CompiledScriptedEntryPoint -> ScriptEvaluationOutput.CompiledEntryPoint(value)
            null -> ScriptEvaluationOutput.ReturnedNull
            else -> ScriptEvaluationOutput.ReturnedValue(value)
        }
        is ResultValue.Unit -> ScriptEvaluationOutput.Unit
        else -> ScriptEvaluationOutput.NoValue
    }

    /**
     * S0-C1: reports a body-build throwable as an honest compilation failure:
     * emits the CompilationFinished diagnostic and returns Failure with the
     * author's message verbatim. See the catch-site comment in [compile].
     */
    private fun compilationFailure(
        t: Throwable,
        effectiveRunId: String,
        scriptText: String,
        sortedClasspath: String,
        definition: ScriptDefinition,
    ): ScriptCompilationResult.Failure {
        val diag = ScriptingDiagnostic(
            severity = ScriptDiagnosticSeverity.ERROR,
            message = t.message ?: t.toString(),
            line = 0,
            column = 0,
            path = definition.sourcePath?.toString() ?: "<inline>",
        )
        // One key, used by BOTH the event and the result. These were two independent
        // hand-written constructions of the same expression; nothing forced them to agree, so
        // a future edit to one could report a compilation identity the other denied.
        val cacheKey = cacheKeyFor(scriptText, sortedClasspath)
        eventSink.append(
            CompilationFinished(
                eventId = UUID.randomUUID().toString(),
                runId = effectiveRunId,
                sequence = 0L,
                occurredAt = Instant.now(),
                cacheKey = cacheKey,
                diagnostics = listOf(diag),
            )
        )
        return ScriptCompilationResult.Failure(
            diagnostics = listOf(diag),
            cacheKey = cacheKey,
        )
    }

    /**
     * The one place this host derives a compilation identity.
     *
     * It was three hand-written constructions of the same expression — in [compile] and twice in
     * [compilationFailure] — and each one had to remember the same four dimensions. That is the
     * duplication `Sha256` (B0) was created to remove for digesting, one layer up: a divergence
     * between two spellings is a divergence nobody notices until the keys disagree.
     *
     * Reading [compilerFlags] from here rather than from the caller is what makes the option
     * dimension unskippable. It used to be covered only by the [hostVersion] bump discipline,
     * which is a comment asking every future author to remember something.
     */
    private fun cacheKeyFor(scriptText: String, sortedClasspath: String): CacheKey =
        CacheKey.Companion.v2.compute(
            CompilationIdentity(
                pipelineSource = scriptText,
                kotlinVersion = kotlinVersion,
                hostVersion = hostVersion,
                approvedClasspath = sortedClasspath,
                compilerOptions = compilerFlags,
            )
        )

    private fun mapDiagnostic(diag: ScriptDiagnostic): ScriptingDiagnostic {
        val severity = when (diag.severity) {
            ScriptDiagnostic.Severity.DEBUG -> ScriptDiagnosticSeverity.DEBUG
            ScriptDiagnostic.Severity.INFO -> ScriptDiagnosticSeverity.INFO
            ScriptDiagnostic.Severity.WARNING -> ScriptDiagnosticSeverity.WARNING
            ScriptDiagnostic.Severity.ERROR -> ScriptDiagnosticSeverity.ERROR
            ScriptDiagnostic.Severity.FATAL -> ScriptDiagnosticSeverity.FATAL
        }

        val location = diag.location
        val line = location?.start?.line ?: 0
        val column = location?.start?.col ?: 0
        val path = diag.sourcePath ?: "<synthetic>"

        return ScriptingDiagnostic(
            severity = severity,
            message = diag.message,
            line = line,
            column = column,
            path = path
        )
    }
}
