package dev.rubentxu.pipeline.v2.scripting

import dev.rubentxu.pipeline.v2.events.CompilationFinished
import dev.rubentxu.pipeline.v2.events.CompilationStarted
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.domain.digest.Sha256
import dev.rubentxu.pipeline.v2.events.NullEventSink
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.ScriptEvaluator
import kotlin.script.experimental.api.SourceCode
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.compilerOptions
import kotlin.script.experimental.api.with
import kotlin.script.experimental.host.StringScriptSource
import kotlin.script.experimental.jvm.BasicJvmScriptEvaluator
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.updateClasspath
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate
import kotlin.script.experimental.jvmhost.createJvmEvaluationConfigurationFromTemplate
import dev.rubentxu.pipeline.v2.scripting.definition.PipelineScript
import dev.rubentxu.pipeline.v2.scripting.definition.PipelineScriptCompilationConfiguration

/**
 * Kotlin 2.4.10 adapter that wraps [CachingJvmScriptingHost] (a
 * [kotlin.script.experimental.jvmhost.BasicJvmScriptingHost] that may reuse a
 * stored compilation) and
 * exposes the [ScriptingHost] contract from `:pipeline-scripting-api`.
 * Design contract:
 *  - Compiles with the published definition of a `.pipeline.kts`,
 *    [PipelineScript] and its [PipelineScriptCompilationConfiguration]: the
 *    default imports and the compiler options are read from there, the same
 *    place an IDE reads them from, and are not repeated here.
 *  - Builds the classpath via `jvm { dependenciesFromCurrentContext() }`
 *    (default `wholeClasspath = false` — i.e. the current compilation
 *    context classpath only: kotlin-stdlib, kotlin-script-runtime,
 *    kotlin-reflect, the scripting-jvm-host artifacts on this module's
 *    compile classpath). No `wholeClasspath = true` appears anywhere in
 *    production.
 *  - Adds the location of [PipelineScript] itself, because every script
 *    compiles as a subclass of it.
 *  - Per-call jars supplied via [ScriptDefinition.classpath] are appended
 *    through `jvm { updateClasspath(files) }` inside the eval body.
 *  - Returns a stable [ScriptCompilationResult.cacheKey] computed from
 *    sha256(scriptText | sortedClasspath | kotlinVersion | hostVersion).
 *  - Maps [ScriptDiagnostic] fields 1:1 to [ScriptingDiagnostic] so an
 *    editor can render source-mapped errors.
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
     * Bumped to 1.1.0 when `-Xreturn-value-checker=check` was added, and
     * Bumped to 1.2.0 when `-Xuse-fast-jar-file-system=false` was added. In both
     * cases the bump was the ONLY thing invalidating previously compiled scripts, because the
     * Options themselves were not part of the key — OBSERVED in: the flag was in the
     * binary, yet a script compiled before the change still discarded its result silently.
     * That defence is now a second line rather than the only one: [compilerFlags] is its own
     * cache-key dimension (see [cacheIdentityFor]), so an option change invalidates the key whether
     * or not anyone remembers this value. The bumps above stay, because [hostVersion] still
     * covers host changes that are not options — a different classpath assembly, a different
     * compiler invocation — and because a stale `v1` label is still on old event payloads.
     * Bumped to 1.3.0 when the script's base class moved from `Any` to [PipelineScript]: the
     * compiled class of a script changes with its base class, and that is not an option.
*/
    private val hostVersion = "1.3.0"

    /**
     * The compiler options of a `.pipeline.kts`, read from the definition that owns them
     * ([PipelineScriptCompilationConfiguration]). The host does not declare a second list: the
     * definition says why each option is there, and an IDE that loads the same definition
     * receives the same options.
     * The option list is read directly into the compilation identity (see [cacheIdentityFor]),
     * so an option added to the definition invalidates the key by construction. It was
     * previously covered only by the [hostVersion] bump discipline, which is a comment asking
     * every future author to remember something that the key can enforce instead.
*/
    private val compilerFlags: List<String> =
        PipelineScriptCompilationConfiguration[ScriptCompilationConfiguration.compilerOptions].orEmpty()

    /**
     * Where the class every script extends was loaded from: a jar in a distribution, a directory
     * of classes in a build.
     *
     * The host chose [PipelineScript] as the template, so the host is the one that puts it on the
     * script's classpath. `dependenciesFromCurrentContext()` brings the Kotlin runtime and not the
     * template, and the per-call jars of the runner are the domain, the DSL and the plugins.
     * Without this entry most scripts still compile, which is why its absence hides: the compiler
     * resolves the base class only when it looks at the script's own receiver, and a fully
     * qualified name at the top level of a script (`java.io.File(...)`) is the common way that
     * happens. That script is then refused with an unresolved reference to [PipelineScript].
     *
     * In a distribution the template is a jar on this JVM's class path, and [cacheIdentityFor]
     * digests that class path with the rest of the context.
     */
    private val templateClasspath: File =
        File(
            requireNotNull(PipelineScript::class.java.protectionDomain.codeSource) {
                "the definition of a .pipeline.kts was loaded without a code source"
            }.location.toURI(),
        ).canonicalFile

    /**
     * The compilation configuration of one compilation: the definition, plus the classpath of
     * this call.
     *
     * The classpath is ADDED to the definition with `with { }` on the configuration the template
     * produced. Declaring it inside the template's own builder block would not be the same thing,
     * and it is the trap: there it replaces the dependencies instead of adding to them.
     *
     * The definition declares no classpath on purpose (see [PipelineScript]). What a script of the
     * runner compiles against is the host's own compilation context
     * (`dependenciesFromCurrentContext()`, `wholeClasspath = false`), the template it extends
     * ([templateClasspath]) and the per-call jars of [ScriptDefinition.classpath], the DSL and the
     * plugins among them.
     */
    private fun compilationConfigurationFor(classpathFiles: List<File>): ScriptCompilationConfiguration =
        createJvmCompilationConfigurationFromTemplate<PipelineScript>().with {
            jvm {
                dependenciesFromCurrentContext()
                updateClasspath(listOf(templateClasspath) + classpathFiles)
            }
        }

    /**
     * Compile the definition AND RUN it.
     *
     * The two happen together because the script's body is what builds the
     * pipeline: a `.pipeline.kts` that calls the eager DSL produces its
     * [dev.rubentxu.pipeline.v2.dsl.PipelineSpec] while it runs, so a
     * compilation that does not evaluate the body produces no pipeline at all.
     *
     * What this does with the author's BODY is the trust model of the
     * product, and it is stated once, where an operator reads it — not here.
     */
    override fun compile(definition: ScriptDefinition): ScriptCompilationResult =
        compileScript(definition, BasicJvmScriptEvaluator())

    /**
     * Compile the definition and DO NOT RUN IT.
     *
     * ## What is swapped and what is not
     *
     * The EVALUATOR is the only thing that changes: [CompileOnlyEvaluator]
     * answers "compiled, not evaluated" instead of running the author's body.
     * The COMPILATION is the same one [compile] performs — same source, same
     * default imports, same per-call classpath, same compiler options, same
     * cache identity — because the compilation configuration is built by
     * [compilationConfigurationFor] and that call is shared by both paths.
     *
     * A second compilation, done differently, would answer a different
     * question: "does a program that resembles the script compile?" instead of
     * "does this script compile?". Only the first question is worth asking,
     * and only the second can be asked about the script the author wrote.
     *
     * ## What it answers
     *
     * The result carries the compiler's diagnostics and nothing else. There is
     * no script instance, because nothing was evaluated to produce one, and
     * [ScriptCompilationResult.Success.scriptInstance] is therefore null. A
     * caller that needs a pipeline cannot have one from this path — and that is
     * the point: a compile-only validation promises that the body did not run.
     */
    fun compileOnly(definition: ScriptDefinition): ScriptCompilationResult =
        compileScript(definition, CompileOnlyEvaluator())

    /**
     * The ONE compilation body, with the evaluator as the only difference.
     *
     * Everything that decides HOW a `.pipeline.kts` is compiled lives here:
     * the escaping, the source, the classpath, the cache identity, the single
     * lookup, the event pair and the compilation configuration. [compile] and
     * [compileOnly] are two calls to it with two evaluators, so the two paths
     * cannot drift apart in the part that decides whether a script compiles.
     */
    private fun compileScript(
        definition: ScriptDefinition,
        evaluator: ScriptEvaluator,
    ): ScriptCompilationResult {
        val effectiveRunId = runId ?: definition.sourcePath?.fileName?.toString() ?: UUID.randomUUID().toString()
        val compilationStartedId = UUID.randomUUID().toString()
        val compilationFinishedId = UUID.randomUUID().toString()
        // The phase starts HERE, before anything is read, so the duration the
        // telemetry measures is the whole phase and not the part of it that
        // happens after the cache was consulted. The event that announces the
        // phase is published a little later, once the cache has answered: the
        // console has to say whether the script is being COMPILED or REUSED,
        // and that answer does not exist before the lookup.
        val compilationStartedAt = Instant.now()

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

        // The compilation is configured by the published definition
        // ([PipelineScript]) and nothing else: its default imports and its
        // compiler options reach the compiler through the configuration the
        // template produces. `-Xreturn-value-checker=check` on its own reports
        // a discarded `@MustUseReturnValues` result as a WARNING, which does not
        // stop a compilation; the definition also raises both diagnostics of a
        // discard to errors, so the compiler refuses it with its line and column.
        // A fail-closed DSL guard that throws while
        // the script BODY is being built (e.g. the Pure Builder Consumption
        // gate rejecting an unconsumed MUST_CONSUME carrier) escapes the entry
        // point as a plain exception, BEFORE the evaluator can wrap it in
        // ResultValue.Error.
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
        // The identity is content-verified: each classpath entry contributes
        // its digest, not its path. When an entry's content cannot be read,
        // there is no verified identity, and the compilation runs with the
        // cache disabled rather than reuse an artifact it cannot vouch for.
        val identity = cacheIdentityFor(scriptText, classpathFiles)
        val cacheKey = identity.key
        val cacheForThisCompilation =
            if (identity is CacheIdentity.Verified) cache else CompiledScriptCache.Disabled

        // The ONE lookup of this compilation. The owner of the compilation asks
        // the store once and keeps the answer: the event published below and
        // the line the console prints both read it. Asking the store a second
        // time — once here to know what to announce and once inside the
        // caching host to know what to run — would be two answers to one
        // question, and the two could disagree: the console would announce a
        // reuse the run does not perform.
        val lookup = cacheForThisCompilation.load(cacheKey)
        val plannedOrigin = when (lookup) {
            is CacheLookup.Hit -> ScriptOrigin.REUSED
            is CacheLookup.Miss -> ScriptOrigin.COMPILED
        }

        eventSink.append(
            CompilationStarted(
                eventId = compilationStartedId,
                runId = effectiveRunId,
                sequence = 0L,
                occurredAt = compilationStartedAt,
                origin = plannedOrigin,
            )
        )

        // A fresh host per compilation: the cache key is fixed data for this one
        // compilation, so this object carries no mutable state across compiles.
        // The lookup travels with it, already resolved, and so does the
        // evaluator: the host compiles the same way either way, and the
        // evaluator is the only thing that decides whether the author's body
        // runs.
        val host = CachingJvmScriptingHost(cacheForThisCompilation, cacheKey, lookup, evaluator)

        val rwd: ResultWithDiagnostics<*> = try {
            host.eval(
                source,
                compilationConfigurationFor(classpathFiles),
                createJvmEvaluationConfigurationFromTemplate<PipelineScript>(),
            )
        } catch (t: Throwable) {
            // The host has already recorded how this run obtained its artifact,
            // even though the compilation blew up: a throwable that escaped the
            // evaluation carries the origin the host had reached.
            return compilationFailure(t, effectiveRunId, identity, definition, host.effectiveOrigin)
        }

        val compilationFinishedAt = Instant.now()

        val diagnostics = rwd.reports
            .filter { it.severity >= ScriptDiagnostic.Severity.INFO }
            .map(::mapDiagnostic)
        val result = if (rwd is ResultWithDiagnostics.Success) {
            @Suppress("UNCHECKED_CAST")
            val evalResult = rwd.value as kotlin.script.experimental.api.EvaluationResult

            // A script body that throws during DSL
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
                    diagnostics = diagnostics + evaluationErrorDiagnostic(evalError.error, definition),
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
                // The RESULT's diagnostics, not only the compiler's: the console and the JSON
                // document render this event and nothing else, so a reason that lives only in
                // the result (the error the body raised while it was evaluated) is a refusal
                // nobody can read.
                diagnostics = result.diagnostics,
                // The OUTCOME, not the decision: the host reports what this
                // run really did. It differs from the announced origin in
                // exactly one case — a stored artifact that loads but cannot
                // be evaluated, which makes the run compile after all.
                origin = host.effectiveOrigin,
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
     * Reports a body-build throwable as an honest compilation failure:
     * emits the CompilationFinished diagnostic and returns Failure with the
     * author's message verbatim. See the catch-site comment in [compile].
*/
    private fun compilationFailure(
        t: Throwable,
        effectiveRunId: String,
        identity: CacheIdentity,
        definition: ScriptDefinition,
        origin: ScriptOrigin,
    ): ScriptCompilationResult.Failure {
        val diag = evaluationErrorDiagnostic(t, definition)
        // One key, computed once before the compilation and used by BOTH the
        // event and the result. Re-deriving it here would be a second
        // construction of the same fact, free to disagree with the first.
        val cacheKey = identity.key
        eventSink.append(
            CompilationFinished(
                eventId = UUID.randomUUID().toString(),
                runId = effectiveRunId,
                sequence = 0L,
                occurredAt = Instant.now(),
                cacheKey = cacheKey,
                diagnostics = listOf(diag),
                origin = origin,
            )
        )
        return ScriptCompilationResult.Failure(
            diagnostics = listOf(diag),
            cacheKey = cacheKey,
        )
    }

    /**
     * The diagnostic of a throwable raised while the script was evaluated, with the author's
     * message verbatim.
     *
     * The line is the one of the first frame of the script in the stack trace: the compiler names
     * the classes of a script after its file, so that frame is the line of the script the throwable
     * came through. A gate the DSL raises while it builds a stage points at the call to that
     * stage. The JVM records no column, and a throwable with no frame of the script (an inline
     * script, or one raised outside its body) has no line either: both stay 0, which is what
     * "unknown" means in these diagnostics.
     */
    private fun evaluationErrorDiagnostic(t: Throwable, definition: ScriptDefinition): ScriptingDiagnostic {
        val sourcePath = definition.sourcePath
        // A frame with no file name must not match a script with no file: both are null.
        val line = sourcePath?.fileName?.toString()?.let { scriptFileName ->
            t.stackTrace.firstOrNull { frame -> frame.fileName == scriptFileName && frame.lineNumber > 0 }
        }?.lineNumber ?: 0
        return ScriptingDiagnostic(
            severity = ScriptDiagnosticSeverity.ERROR,
            message = t.message ?: t.toString(),
            line = line,
            column = 0,
            path = sourcePath?.toString() ?: "<inline>",
        )
    }

    /**
     * The JVM's own classpath as canonical files, resolved once per host:
     * the process lives and dies with one classpath, and re-reading a
     * constant per compilation would be pure cost. Only regular files are
     * kept; anything else on the path cannot be content-verified and its
     * presence makes the identity unavailable rather than guessed.
     */
    private val contextClasspathFiles: List<File> =
        (System.getProperty("java.class.path") ?: "")
            .split(File.pathSeparatorChar)
            .filter { it.isNotBlank() }
            .map { File(it).canonicalFile }
            .filter { it.isFile }

    /**
     * The one place this host establishes a compilation identity.
     * Reading [compilerFlags] from here rather than from the caller is what makes the option
     * dimension unskippable. It used to be covered only by the [hostVersion] bump discipline,
     * which is a comment asking every future author to remember something.
     * The file reads are the adapter's one effect here: an entry whose bytes
     * cannot be read yields no digest, which the verifier turns into an
     * unavailable identity. The digest is the authority and the byte count a
     * companion fact; both are read before any compilation begins.
     *
     * The visibility is `internal` and not `private` for one reason: the cache's
     * promise is about this value — the same key must mean the same compilation —
     * and a promise that no check can read is a promise that no check can keep.
     * The production path calls this very function, so what a check reads here
     * is the decision the product makes and not a second derivation of it.
     */
    internal fun cacheIdentityFor(scriptText: String, classpathFiles: List<File>): CacheIdentity {
        // The compilation classpath is the JVM's own context PLUS the
        // per-call entries, in that resolution order — so the identity is
        // built over exactly the same list, and a swapped jar anywhere in
        // the chain changes the key instead of surviving it.
        val effectiveClasspath = contextClasspathFiles + classpathFiles
        val digests = digestsOf(effectiveClasspath)
        val entries = effectiveClasspath.mapIndexed { index, file ->
            val digest = digests[index]
            ClasspathEntry(file.canonicalPath, digest, digest?.let { file.length() })
        }
        return verifyCompilationIdentity(
            sourceDigest = Sha256.ofText(scriptText),
            compilerIdentity = kotlinVersion,
            adapterIdentity = hostVersion,
            compilerFlags = compilerFlags,
            entries = entries,
        )
    }

    /**
     * The digest of every file of [files], in the order of the list.
     *
     * The files are independent: the digest of one says nothing about the
     * digest of the next, and the identity is built over the results in the
     * order of the classpath and not in the order the bytes arrived. So the
     * files are read at the same time instead of one after the other.
     *
     * ## Why this matters, in figures
     *
     * The classpath of a run is over sixty jars, and the identity cannot be
     * established until every one of them has been read. MEASURED on this
     * tree, over the 64 jars and 101 MiB of the distribution's own lib, the
     * digest read one file after the other cost 383 ms and the same files
     * read four at a time cost 212 ms.
     *
     * ## What this does not change
     *
     * The digests are the digests of the same bytes, in the same order, and
     * the key is the key of the same manifest. A file whose bytes cannot be
     * read yields no digest, exactly as it did when the reads were in a row,
     * and the identity that names it is unavailable.
     */
    private fun digestsOf(files: List<File>): List<String?> {
        if (files.size < 2) return files.map { file -> digestOf(file) }
        val pool = Executors.newFixedThreadPool(minOf(files.size, DIGEST_READERS)) { runnable ->
            Thread(runnable, "pipelinek-script-digest").apply { isDaemon = true }
        }
        return try {
            val pending = files.map { file -> pool.submit(Callable { digestOf(file) }) }
            pending.mapIndexed { index, future ->
                try {
                    future.get()
                } catch (failure: ExecutionException) {
                    // The read failed for a reason the row-by-row read would
                    // have failed for as well, and the caller is owed THAT
                    // failure and not a wrapper: the file is read again here,
                    // on this thread, and the exception surfaces as it did
                    // before the reads were spread over threads.
                    digestOf(files[index])
                }
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            // The wait was interrupted. The flag says so, and the files are
            // read here instead: an identity built over half the classpath is
            // not an identity.
            files.map { file -> digestOf(file) }
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * The digest of the bytes of [file], or null when they cannot be read.
     *
     * A file that cannot be read is not an error of the caller: the identity
     * it belongs to is unavailable, and the verifier names the entry that
     * could not be read.
     */
    private fun digestOf(file: File): String? = try {
        Sha256.ofFile(file.toPath())
    } catch (_: java.io.IOException) {
        null
    }

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

    private companion object {
        /**
         * How many files of the classpath are read at the same time.
         *
         * MEASURED on this tree, over the 64 jars and 101 MiB of the
         * distribution's own lib: 383 ms read one file after the other, 219
         * ms read two at a time, 212 ms read four, 210 ms read eight and 211
         * ms read sixteen. Past four the number does not move: the limit is
         * what the machine can read at once and not the number of threads
         * that ask for it.
         */
        private const val DIGEST_READERS: Int = 4
    }
}
