package dev.rubentxu.pipeline.v2.dsl

import dev.rubentxu.pipeline.v2.domain.CatchErrorBuildResult
import dev.rubentxu.pipeline.v2.domain.RuntimeConfig
import dev.rubentxu.pipeline.v2.domain.UnixPlatformClassifier
import dev.rubentxu.pipeline.v2.domain.step.DEFAULT_WAIT_UNTIL_INITIAL_RECURRENCE_PERIOD_MS
import dev.rubentxu.pipeline.v2.domain.step.DEFAULT_WAIT_UNTIL_MAX_BACKOFF_MS

/**
 * Receiver scope for the `directives { }` block inside a stage
 * Pure data construction: each entry records a directive key plus its opaque
 * encoded arguments. No registry, no decoding, no effects — the canonical
 * coordinator performs admission against the DirectiveRegistry before the
 * stage starts, and an unresolved key fails the run closed.
*/
@StepDslMarker
class DirectivesScope {
    private val declared = mutableListOf<dev.rubentxu.pipeline.v2.domain.StageDirective>()

    /**
     * Declares one directive by its open-world key.
     * @param key Namespaced directive key (e.g. `"acme.lock"`). Must be a
     *   literal or a locally computed name; the key is ROUTED BY VALUE, so the
     *   engine never learns concrete keys. Blank keys are rejected here, at
     *   construction, exactly like every other carrier.
     * @param encodedArguments Opaque payload in the directive's own codec
     *   shape; decoded only by the definition that owns the key.
*/
    fun directive(key: String, encodedArguments: String = "{}") {
        declared += dev.rubentxu.pipeline.v2.domain.StageDirective(key, encodedArguments)
    }

    /**
     * The typed `when` gate, as a first-class convenience.
     * The generic [directive] entry point stays available and unchanged; this
     * only removes the need for an author to hand-encode the predicate. It
     * constructs data and nothing else: the verdict is reached by the durable
     * coordinator before the stage body runs.
*/
    fun whenGate(predicate: dev.rubentxu.pipeline.v2.domain.directive.WhenPredicate) {
        directive(
            key = dev.rubentxu.pipeline.v2.domain.directivekey.WHEN_DIRECTIVE_KEY.value,
            encodedArguments =
                dev.rubentxu.pipeline.v2.domain.directive.WhenPredicateEncoder.encode(predicate),
        )
    }

    /** Jenkins-familiar shorthand: gate on an exact variable value. */
    fun whenEnvIs(variable: String, expected: String) {
        whenGate(dev.rubentxu.pipeline.v2.domain.directive.WhenPredicate.VariableEquals(variable, expected))
    }

    /** Gate on a variable being set and non-empty. */
    fun whenEnvPresent(variable: String) {
        whenGate(dev.rubentxu.pipeline.v2.domain.directive.WhenPredicate.VariablePresent(variable))
    }

    fun build(): List<dev.rubentxu.pipeline.v2.domain.StageDirective> = declared.toList()
}

/**
 * Receiver scope for the step block inside `stage("name") { }`.
*/
@StepDslMarker
class StageScope(
    stageName: String,
    runtimeConfig: RuntimeConfig = currentRuntimeConfig(),
) : StageScopeTopSteps(stageName, runtimeConfig) {

    /**
     * `slot("name") { ... }` — NAMES one step of the body it is written in.
     *
     * The name is the ADDRESS the plugin's own handler will use to ask for that step.
     * It is independent of the step's kind, which is what lets a body hold two steps of
     * the same kind: without a name the engine would find two of them and refuse to
     * guess which one was meant.
     *
     * It names EXACTLY ONE step. A block that declares more than one is refused here,
     * at authoring time, where the author can see it — and a body that names nothing
     * keeps working exactly as before, addressed by kind.
     *
     * @param name the address the plugin will use for the step the block declares
     * @throws IllegalArgumentException when the block does not declare exactly one step
     */
    fun slot(name: String, block: StageScope.() -> Unit) {
        val inner = nestedScope()
        inner.block()
        val declared = inner.steps()
        require(declared.size == 1) {
            "A slot names exactly one step: '$name' names ${declared.size}"
        }
        steps.add(StepSpec.Slot(slotName = name, step = declared.single()))
    }

    fun deleteDir(path: String = ".") {
        steps.add(StepSpec.DeleteDir(path = path))
    }

    /**
     * Cleans the workspace with optional Ant-style glob filtering.
     * Jenkins verbatim (catalog line 44):
     * `cleanWs(deleteDirs: Boolean = true, patterns: List<String>? = null)`
     * This DSL lowers directly to `StepSpec.RegistryStepSpec`
     * (open-world registry path). The payload is encoded inline here to match the canonical
     * codec of `CoreCleanWsStep.inputCodec` byte-for-byte, so the durable fingerprint is
     * Preserved across the destructive flip. The legacy `StepSpec.CleanWs` subtype still
     * exists as a sealed-interface member because `CleanWsExecutor` (SDK files) types its
     * parameter against it; this DSL was the only producer that routed through the legacy
     * decoder, and that producer is gone.
     * @param deleteDirs If true, remove empty parent directories after deletion
     * @param patterns Ant-style glob patterns (null = delete all non-.v2 files)
*/
    fun cleanWs(deleteDirs: Boolean = true, patterns: List<String>? = null) {
        // Canonical envelope: {"kind":"cleanWs","deleteDirs":<bool>,"patterns":[...]}
        // Matches CoreCleanWsStep.inputCodec.encode output
        val canonicalPatterns: List<String> = patterns ?: emptyList()
        val sb = StringBuilder()
        sb.append("{\"kind\":\"cleanWs\",\"deleteDirs\":").append(deleteDirs).append(",\"patterns\":[")
        canonicalPatterns.forEachIndexed { i, p ->
            if (i > 0) sb.append(",")
            sb.append('"').append(escapeJsonString(p)).append('"')
        }
        sb.append("]}")
        steps.add(
            StepSpec.RegistryStepSpec(
                stepKey = dev.rubentxu.pipeline.v2.domain.PluginStepId("core.cleanWs"),
                schemaVersion = "dsl-v1",
                encodedInput = dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue(sb.toString()),
            ),
        )
    }

    /**
     * Cleans the workspace with array syntax (Jenkins-faithful overload).
     * Same RegistryStepSpec lowering as the primary overload.
     * @param deleteDirs If true, remove empty parent directories after deletion
     * @param patterns Ant-style glob patterns as vararg
*/
    fun cleanWs(deleteDirs: Boolean = true, vararg patterns: String) {
        cleanWs(deleteDirs = deleteDirs, patterns = patterns.toList())
    }

    // =============================================================================
    // Error-handling DSL
    // =============================================================================

    /**
     * Catches errors from nested steps and optionally downgrades the build result.
     *
     * The body runs ONCE, in the caller's own context, and what this scope does with that
     * outcome is a FOLD: the scope decides the result it publishes, and the declared
     * [buildResult] decides what that result is. The canonical body engine executes that
     * fold under the FOLDING policy with the CONTAINMENT fold, and it is declared rather
     * than implied: the catalogue publishes this step as the entry `core.catchError`, whose
     * body declares the FOLDING policy and the ONCE invocation.
     *
     * Jenkins verbatim (catalog lines 41-43):
     * `catchError(buildResult: String? = null, stageResult: String? = null, message: String? = null) { ... }`
     * Both results are [CatchErrorBuildResult] and no longer `String`. A typo such
     * as `catchError(buildResult = "USR")` used to compile and reach the runtime, where the
     * only thing that read it was an `else` arm that suppressed the caught failure. It is now
     * a compile error.
     * @param buildResult Override build result (null = default Jenkins UNSTABLE)
     * @param stageResult Override stage result (null = use buildResult or default UNSTABLE)
     * @param message User-visible message
     * @param block Nested steps
*/
    fun catchError(
        buildResult: CatchErrorBuildResult,
        stageResult: CatchErrorBuildResult? = null,
        message: String? = null,
        block: StageScope.() -> Unit,
    ) {
        val inner = nestedScope()
        inner.block()
        steps.add(StepSpec.CatchError(
            buildResult = buildResult,
            stageResult = stageResult,
            message = message,
            steps = inner.steps(),
        ))
    }

    /**
     * The STABLE historical spelling, kept as a validated adapter.
     * The published authoring contract documents `catchError(buildResult?, stageResult?,
     * Message?)`. Its spelling is stable: its members may be removed only after the
     * declared removal boundary, not at the convenience of the next migration.
     * It parses IMMEDIATELY — see
     * [LegacyResultVocabulary] — so the token is typed before the pipeline exists and never
     * reaches the IR or the runtime.
     * `catchError { ... }` with no results resolves HERE, because the typed overload requires
     * `buildResult`. That is deliberate rather than accidental: two overloads with all
     * parameters defaulted would make every bare `catchError { }` call ambiguous, which was
     * Measured (mutation failed to compile for exactly that reason). A declared absence
     * produces the same typed state either way, so nothing is lost by routing it through here.
     * Removal is not scheduled. It needs the exit (`try/catch` at the orchestrator)
     * which is the declared replacement path.
     * The body runs ONCE and its outcome is FOLDED, exactly as in the typed overload: the
     * canonical body engine executes the FOLDING policy with the CONTAINMENT fold, and the
     * catalogue publishes the step as the entry `core.catchError`.
*/
    fun catchError(
        buildResult: String? = null,
        stageResult: String? = null,
        message: String? = null,
        block: StageScope.() -> Unit,
    ) {
        val inner = nestedScope()
        inner.block()
        steps.add(StepSpec.CatchError(
            buildResult = LegacyResultVocabulary.catchBuildResult(buildResult, "buildResult"),
            stageResult = LegacyResultVocabulary.catchStageResult(stageResult),
            message = message,
            steps = inner.steps(),
        ))
    }

    /**
     * Catches errors and forces stage result to UNSTABLE (warnError semantics).
     *
     * The body runs AT MOST ONCE, and what this scope does with its outcome is a FOLD:
     * the stage is degraded with the declared [message] whatever the body did. The
     * canonical body engine executes that fold under the FOLDING policy with the
     * OUTPUT_DECORATION fold, and it is declared rather than implied: the catalogue
     * publishes this step as the entry `core.warnError`, whose body declares the FOLDING
     * policy and the AT_MOST_ONCE invocation.
     *
     * Jenkins verbatim:
     * `warnError(message: String, catchInterruptions: Boolean = true) { ... }`
     * @param message User-visible warning message
     * @param catchInterruptions If true, also catch Thread.interrupt
     * @param block Nested steps
*/
    fun warnError(
        message: String,
        catchInterruptions: Boolean = true,
        block: StageScope.() -> Unit,
    ) {
        val inner = nestedScope()
        inner.block()
        steps.add(StepSpec.WarnError(
            message = message,
            catchInterruptions = catchInterruptions,
            steps = inner.steps(),
        ))
    }

    /**
     * Marks the current stage as unstable (soft warning, pipeline continues).
     * Jenkins verbatim:
     * `unstable(message: String)`
     * @param message User-visible message describing the instability
*/
    @Deprecated(
        message = "unstable is rewritten into core.emit.event(StageMarkedUnstable) + core.sh(exit 0). "
            + "Use emitEvent('StageMarkedUnstable', ...) instead.",
        replaceWith = ReplaceWith("unstable(message)"),
    )
    fun unstable(message: String) {
        steps.add(StepSpec.Unstable(message = message))
    }

    /**
     * Prints the current working directory (workspace root).
     * Jenkins verbatim:
     * `pwd()` or `pwd(tmp: Boolean)`
     * Runtime-returning DSL fun. This builder lowers to a
     * registry Step that produces the path as a typed runtime value at
     * execution time.
     *
     * ## What this call answers, and what it cannot answer
     *
     * The eager DSL BUILDS the plan, and the workspace of a stage is allocated
     * when the run happens: while the plan is built there is no path to hand
     * back. So this call answers the SENTINEL `<workspace>`, which NAMES the
     * workspace instead of inventing a path. A sentinel that says what it is
     * is not a lie; the directory of the process that compiles the script
     * would be, because it is not the workspace of the stage.
     *
     * The call still enqueues `core.pwd` (or `core.pwd.tmp`), and that is
     * where the real path lives: the step resolves the workspace when it runs
     * and publishes it as the typed event `PwdResolved(path, workspaceRoot)`.
     *
     * Supported usage:
     *  - inside a `scriptable` block / a compiled scripted runtime context,
     *    where the façade materialises the value before control returns;
     *  - inside the generator form (`.pipeline.kts` lowered to a
     *    `CompiledScriptedEntryPoint`), where `CorePwdStep` / `CorePwdTmpStep`
     *    are invoked through `ScriptedRegistryInvoker`.
     *
     * The usage the eager form cannot serve:
     *  - reading the path synchronously. The eager form answers the sentinel,
     *    and a script that needs the path reads the event of the step.
     * `tmp=false` lowers to `core.pwd` (READ_ONLY + MEMOIZED, replay
     * reproduces the persisted path without re-observing the workspace).
     * `tmp=true` lowers to `core.pwd.tmp` (deterministic
     * `tmp-pwd-<sha256(opId)>` path; `tmp` directories persist across
     * resume and are NOT recreated on REUSE).
     * @param tmp If true, the registry Step creates a deterministic temp
     *   subdirectory under the workspace root and returns its absolute path
*/
    fun pwd(tmp: Boolean = false): String {
        // Both branches lower to the registry path. The legacy
        // StepSpec.Pwd / StepSpec.PwdTmp steps were retired: the legacy form
        // and the deterministic tmp form are both gone. The registry candidates
        // `core.pwd` and `core.pwd.tmp` are the only production authorities.
        if (tmp) {
            val encoded = dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue("{}")
            steps.add(
                StepSpec.RegistryStepSpec(
                    stepKey = dev.rubentxu.pipeline.v2.domain.PluginStepId("core.pwd.tmp"),
                    schemaVersion = "dsl-v1",
                    encodedInput = encoded,
                ),
            )
        } else {
            val encoded = dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue(
                """{"kind":"pwd","tmp":false}""",
            )
            steps.add(
                StepSpec.RegistryStepSpec(
                    stepKey = dev.rubentxu.pipeline.v2.domain.PluginStepId("core.pwd"),
                    schemaVersion = "dsl-v1",
                    encodedInput = encoded,
                ),
            )
        }
        // The workspace does not exist while the plan is built, so there is no
        // path to answer. The sentinel names it; the enqueued step is what
        // resolves it, and its typed output is the real path.
        return RUNTIME_VALUE_PLACEHOLDER
    }

    /**
     * Whether the machine that runs this plan is a Unix-like one.
     * Jenkins verbatim:
     * `isUnix()`
     * Runtime-returning DSL fun. This builder lowers to a
     * registry Step that classifies the platform at execution time.
     *
     * ## What this call answers
     *
     * The platform is a fact of the MACHINE, and the machine is known while the
     * plan is built: this call reads it through the port
     * ([RuntimeConfig.osName]) — the same port the execution-target resolver
     * reads to decide which machine a stage runs on — and classifies it with
     * [UnixPlatformClassifier], the same function the runtime step uses. So
     * the answer is the classification the runtime would observe, and not a
     * constant that happens to look like one: `true` on a machine that is not
     * a Unix one is a value the reader cannot tell from the real one.
     *
     * The runtime is single-host: a stage that asks for a remote machine is
     * REFUSED, so the machine that builds the plan is the machine that runs
     * it, and the classification does not change between the two moments.
     *
     * Supported usage:
     *  - inside a `scriptable` block / a compiled scripted runtime context,
     *    where `CoreIsUnixStep` materialises the Boolean through
     *    `ScriptedRegistryInvoker`;
     *  - inside the generator form, where the Boolean reaches the script
     *    as a typed value with full REUSE replay semantics.
     *
     * The usage the eager form cannot serve:
     *  - a branch on the platform that has to be decided when the step runs:
     *    the eager form decides the branch while the plan is built.
     * @return the classification of the platform, the same one `core.isUnix`
     *   observes when it runs
*/
    fun isUnix(): Boolean {
        val encoded = dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue("{}")
        steps.add(
            StepSpec.RegistryStepSpec(
                stepKey = dev.rubentxu.pipeline.v2.domain.PluginStepId("core.isUnix"),
                schemaVersion = "dsl-v1",
                encodedInput = encoded,
            ),
        )
        return UnixPlatformClassifier.classifyUnix(runtimeConfig.osName())
    }

    internal companion object {
        /**
         * The sentinel [pwd] answers while the plan is built.
         *
         * The workspace of a stage is allocated when the run happens, so no
         * path exists at that moment and this call names the workspace instead
         * of inventing one. The real path is the typed output of the enqueued
         * step `core.pwd` / `core.pwd.tmp`, and the catalogue declares this
         * divergence on the entry of that step.
         */
        const val RUNTIME_VALUE_PLACEHOLDER: String = "<workspace>"
    }

    /**
     * Loads and executes steps from an external pipeline script file.
     * Jenkins verbatim:
     * `load(path: String)`
     * The path is resolved relative to the workspace root. On successful load,
     * the file is compiled and its steps are appended to the current execution scope.
     * @param path Workspace-relative path.pipeline.kts file
*/
    fun load(path: String) {
        steps.add(StepSpec.Load(path = path))
    }

    /**
     * Polls a condition closure until it returns true or the backoff ceiling is reached.
     * Jenkins verbatim:
     * `waitUntil(initialRecurrencePeriod: Long = 1) { condition }`
     * Jenkins also carries a `quiet: Boolean = false`. It is NOT accepted here: this engine's
     * `waitUntil` narration already renders at `Family.NOTE`, and `ObservationView.NORMAL`
     * hides that family, so a `quiet` flag had nothing left to silence in the default view.
     * Accepting it and discarding it would be the metadata-accepted-but-never-interpreted
     * defect; omitting it makes `waitUntil(quiet = true)` fail at compile time instead of
     * compiling into nothing. Polling narration stays visible
     * under `--view events`, which is where a caller who wants to watch it looks.
     * Body is the inner StepSpec list captured at construction time.
     * **Construction-time body execution (finding).** The body
     * lambda is invoked exactly once at DSL construction time, on the
     * `inner` StageScope, to extract its declared `List<StepSpec>` as data.
     * This means the body MUST be pure data construction (calling other
     * DSL builders like `sh("...")` or `echo("...")`); it MUST NOT perform
     * runtime effects such as `pwd().length`, `isUnix()`-driven branches
     * with side-effects, file I/O, network calls, or process execution.
     * For side-effect-bearing predicates, route through the durable
     * runtime predicate contract (the canonical coordinator re-enters the
     * Captured body via `BodyInvoker.invoke`,) — the lambda
     * captures the *shape* of the predicate, not its evaluation result.
     * The captured body is structurally equal to what a pure `() -> List<StepSpec>`
     * would yield. If a future WU replaces this pattern with explicit
     * lambda capture (e.g. `body: () -> List<StepSpec>` passed by the
     * compiler after lowering), this comment and the implementation will
     * converge. Until then, callers MUST honour the "pure data
     * construction" rule above.
     * Why this is not a regression: the same eager-evaluation pattern is
     * used by `retry`, `timeout`, `timestamps`, `dir`, `withCredentials`,
     * `script`, etc. documents the pattern; it does not break
     * consistency by fixing one builder.
     * @param initialRecurrencePeriod The interval of the FIRST poll, in milliseconds
     *   (default 1000ms). The wait sleeps one interval before every poll, and the
     *   interval doubles after each poll whose condition did not hold.
     * @param maxBackoffMs The ceiling the poll interval backs off to (default
     *   60000ms); must be at least `initialRecurrencePeriod`. **This ceiling IS
     *   the wait's deadline.** A poll whose interval has reached the ceiling is
     *   the LAST poll: if the condition still has not held, the wait ends there
     *   with `deadline-exceeded` and the run fails with a TIMEOUT cause. There
     *   is no separate wall-clock deadline in this API, so the wall-clock
     *   duration of a wait is the sum of the intervals it waited, not
     *   `maxBackoffMs` itself.
     * @param body Lambda producing the nested steps whose last step emits
     *   WaitUntilPredicateEvaluated(true/false)
*/
    fun waitUntil(
        initialRecurrencePeriod: Long = DEFAULT_WAIT_UNTIL_INITIAL_RECURRENCE_PERIOD_MS,
        maxBackoffMs: Long = DEFAULT_WAIT_UNTIL_MAX_BACKOFF_MS,
        body: StageScope.() -> Unit,
    ) {
        // Construction-time body capture: invoke the lambda once on a
        // fresh StageScope so its declared steps land in `inner.steps`,
        // then snapshot that list as data on the structural StepSpec.
        // This is the same shape used by retry/timeout/timestamps/dir
        // (see AGENTS.md DSL-vs-runtime section + receipt)
        val inner = nestedScope()
        inner.body()
        steps.add(StepSpec.WaitUntilBlock(
            initialRecurrencePeriod = initialRecurrencePeriod,
            maxBackoffMs = maxBackoffMs,
            body = inner.steps(),
        ))
    }

    // =============================================================================
    // Output-decorator DSL
    // =============================================================================

    /**
     * Decorates captured stdout/stderr with timestamps.
     * Jenkins verbatim: `timestamps { block }`
     * @param block Nested steps to execute with timestamp decoration
*/
    fun timestamps(block: StageScope.() -> Unit) {
        val inner = nestedScope()
        inner.block()
        steps.add(StepSpec.Timestamps(steps = inner.steps()))
    }

    /**
     * Decorates captured stdout/stderr with ANSI color codes.
     * Jenkins verbatim: `ansiColor(colorMapName: String = "xterm") { block }`
     * @param colorMapName Color map name (default "xterm")
     * @param block Nested steps to execute with ANSI color decoration
*/
    fun ansiColor(colorMapName: String = "xterm", block: StageScope.() -> Unit) {
        val inner = nestedScope()
        inner.block()
        steps.add(StepSpec.AnsiColor(colorMapName = colorMapName, steps = inner.steps()))
    }

    /**
     * The node of Jenkins, and a step this product REFUSES.
     *
     * Jenkins verbatim: `node(label?: String) { block }`
     *
     * The step lowers to the key `core.node`, and NO registered definition owns
     * that key: the canonical admission refuses the step, in the RUN and not in
     * the compilation. The script compiles, and the run refuses it naming the
     * form of this product that replaces it.
     *
     * The form that replaces it is the `agent` DIRECTIVE of the stage: the
     * agent of a stage is resolved before its body runs, and it is a
     * declaration of the stage and not a block that wraps one.
     *
     * @param label Agent label (optional)
     * @param block Nested steps to execute
*/
    fun node(label: String? = null, block: StageScope.() -> Unit) {
        val inner = nestedScope()
        inner.block()
        steps.add(StepSpec.NodeNoOp(label = label, steps = inner.steps()))
    }

    // =============================================================================
    // Milestone DSL
    // =============================================================================

    /**
     * Records a milestone for cross-build coordination.
     * Jenkins verbatim: `milestone(ordinal: Int, label: String? = null)`
     * This DSL lowers directly to `StepSpec.RegistryStepSpec` (open-world registry
     * path). The payload is encoded inline here to match the canonical codec of
     * `CoreMilestoneStep.inputCodec` byte-for-byte, so the durable fingerprint is preserved
     * Across the destructive flip. The legacy `StepSpec.Milestone` subtype and its compiler
     * Branch are removed at; this DSL was the only producer
     * @param ordinal The milestone ordinal (must be monotonically increasing)
     * @param label Optional label for the milestone
*/
    fun milestone(ordinal: Int, label: String? = null) {
        require(ordinal > 0) { "milestone ordinal must be positive: $ordinal" }
        // Canonical envelope: {"kind":"milestone","ordinal":N,"label":...?}
        // Matches CoreMilestoneStep.inputCodec.encode output
        val encoded = buildString {
            append("{\"kind\":\"milestone\",\"ordinal\":")
            append(ordinal)
            if (label != null) {
                append(",\"label\":\"")
                append(escapeJsonString(label))
                append("\"")
            }
            append("}")
        }
        steps.add(
            StepSpec.RegistryStepSpec(
                stepKey = dev.rubentxu.pipeline.v2.domain.PluginStepId("core.milestone"),
                schemaVersion = "dsl-v1",
                encodedInput = dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue(encoded),
            ),
        )
    }

    /**
     * Copies workspace files matching an Ant-style `includes` pattern into the
     * run-scoped stash directory, so a later stage (within the same run) can
     * restore them via [unstash].
     * Lowers directly to `StepSpec.RegistryStepSpec`
     * for `core.stash` with the canonical encoded envelope
     * `{"kind":"stash","name":"<name>","includes":"<includes>","excludes":"<excludes>"}` —
     * byte-for-byte identical to `CoreStashStep.inputCodec.encode()` so the
     * Durable fingerprint round-trips through the registry path
     * Jenkins verbatim (catalog.x): `stash(name: String, includes: String, excludes: String = "")`.
     * @param name Stash logical name (re-used by [unstash]). Must be non-blank
     *             and contain no path separators / newlines (validated by the
     *             Step's typed input contract at handler time).
     * @param includes Ant-style pattern to match workspace files
     * @param excludes Comma-separated Ant-style patterns to exclude
*/
    fun stash(name: String, includes: String, excludes: String = "") {
        val sb = StringBuilder()
        sb.append("{\"kind\":\"stash\",\"name\":\"").append(escapeJsonString(name)).append("\",")
        sb.append("\"includes\":\"").append(escapeJsonString(includes)).append("\"")
        if (excludes.isNotEmpty()) {
            sb.append(",\"excludes\":\"").append(escapeJsonString(excludes)).append("\"")
        }
        sb.append("}")
        steps.add(
            StepSpec.RegistryStepSpec(
                stepKey = dev.rubentxu.pipeline.v2.domain.PluginStepId("core.stash"),
                schemaVersion = "dsl-v1",
                encodedInput = dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue(sb.toString()),
            ),
        )
    }

    /**
     * Restores files from a previously-produced stash (same run, any earlier
     * stage) into the current stage workspace.
     * Lowers directly to `StepSpec.RegistryStepSpec`
     * for `core.unstash` with the canonical encoded envelope
     * `{"kind":"unstash","name":"<name>","into":"<into>"}` — byte-for-byte
     * identical to `CoreUnstashStep.inputCodec.encode()` so the durable
     * Fingerprint round-trips through the registry path
     * Jenkins verbatim: `unstash(name: String)`. Pipeline-K local-first extends
     * with an optional `into` parameter that scopes the restore to a
     * subdirectory of the workspace (must not contain `..` segments — Zip-Slip
     * guard).
     * @param name Stash logical name (must match a previous [stash] in this run)
     * @param into Optional subdirectory of the workspace to restore into. Must
     *             be a relative path; the Step's typed contract forbids `..`
     *             segments at handler time.
*/
    fun unstash(name: String, into: String? = null) {
        val sb = StringBuilder()
        sb.append("{\"kind\":\"unstash\",\"name\":\"").append(escapeJsonString(name)).append("\"")
        if (into != null) {
            sb.append(",\"into\":\"").append(escapeJsonString(into)).append("\"")
        }
        sb.append("}")
        steps.add(
            StepSpec.RegistryStepSpec(
                stepKey = dev.rubentxu.pipeline.v2.domain.PluginStepId("core.unstash"),
                schemaVersion = "dsl-v1",
                encodedInput = dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue(sb.toString()),
            ),
        )
    }

    // =============================================================================
    // Core.publishHTML DSL extension
    // =============================================================================

    /**
     * Publishes an HTML report from the stage workspace into the run-scoped
     * reports archive.
     * Jenkins verbatim: `publishHTML(target: HtmlPublisherTarget)` where
     * `target` carries name, reportFiles, reportDir, keepAll, allowMissing,
     * escapeUnderscores. Pipeline-K keeps the public DSL surface narrow and
     * typed (positional + named args) instead of a target POJO.
     * Lowers directly to `StepSpec.RegistryStepSpec` for `core.publishHTML`
     * with the canonical encoded envelope
     * `{"kind":"publishHTML","name":"<n>","reportDir":"<r>","reportFiles":"<f>",...}`
     * — byte-for-byte identical to `CorePublishHtmlStep.inputCodec.encode()`
     * So the durable fingerprint round-trips through the registry path
     * @param name Logical name of the report (used to derive the archive
     *             subdirectory; sanitised by `PublishHtmlSanitiser`).
     * @param reportDir Workspace-relative directory containing the report files.
     * @param reportFiles Ant-style glob (default `**` recursive match).
     * @param keepAll Whether to keep historical reports across runs (v1 runtime
     *   REJECTS keepAll=true: the archive is overwritten per run and the flag
     *   has no interpreter, so it fails closed instead of being dropped).
     * @param allowMissing When true, do not fail the Step if the directory or
     *                     glob is empty (emit `HtmlReportSkipped` instead).
     * @param escapeUnderscores When true, escape `_` in the sanitised name (Jenkins-canonical).
*/
    @JvmOverloads
    fun publishHTML(
        name: String,
        reportDir: String,
        reportFiles: String = "**",
        keepAll: Boolean = false,
        allowMissing: Boolean = false,
        escapeUnderscores: Boolean = false,
    ) {
        val sb = StringBuilder()
        sb.append("{\"kind\":\"publishHTML\",\"name\":\"").append(escapeJsonString(name)).append("\",")
        sb.append("\"reportDir\":\"").append(escapeJsonString(reportDir)).append("\",")
        sb.append("\"reportFiles\":\"").append(escapeJsonString(reportFiles)).append("\"")
        if (keepAll) sb.append(",\"keepAll\":true") // handler fails closed (no v1 interpreter)
        if (allowMissing) sb.append(",\"allowMissing\":true")
        if (escapeUnderscores) sb.append(",\"escapeUnderscores\":true")
        sb.append("}")
        steps.add(
            StepSpec.RegistryStepSpec(
                stepKey = dev.rubentxu.pipeline.v2.domain.PluginStepId("core.publishHTML"),
                schemaVersion = "dsl-v1",
                encodedInput = dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue(sb.toString()),
            ),
        )
    }

    // =============================================================================
    // Timeout/retry DSL
    // =============================================================================

    /**
     * Executes the inner block with a timeout.
     * The Jenkins step takes an optional activity label as a third argument; this scope takes the
     * time and the unit only, so `timeout(time: Long, unit: String) { block }` is the whole
     * spelling a pipeline can use.
     * @param time Timeout value
     * @param unit Time unit (SECONDS, MINUTES, etc.)
     * @param block Nested steps to execute with timeout
*/
    fun timeout(time: Long, unit: String, block: StageScope.() -> Unit) {
        val inner = nestedScope()
        inner.block()
        steps.add(StepSpec.TimeoutBlock(time = time, unit = unit, steps = inner.steps()))
    }

    /**
     * Executes the inner block with retry on failure.
     * Jenkins verbatim: `retry(count: Int) { block }`
     * @param count Maximum retry attempts
     * @param block Nested steps to execute with retry
*/
    fun retry(count: Int, block: StageScope.() -> Unit) {
        val inner = nestedScope()
        inner.block()
        steps.add(StepSpec.RetryBlock(count = count, conditions = null, steps = inner.steps()))
    }

    // =============================================================================
    // Directive DSL (declarative carrier only)
    // =============================================================================

    // detekt ProtectedMemberInFinalClass: StageScope is final (no subclasses),
    // so protected here exposes nothing — private is the honest modifier.
    private var stageDirectives: List<dev.rubentxu.pipeline.v2.domain.StageDirective> = emptyList()

    /**
     * Declares stage directives (directive kernel)
     * DECLARATIVE ONLY: the block runs once at IR-construction time on a fresh
     * [DirectivesScope], and each declared entry lowers to a
     * [dev.rubentxu.pipeline.v2.domain.StageDirective] (key + opaque encoded
     * arguments). This builder MUST NOT resolve a registry, MUST NOT decode
     * arguments, and MUST NOT touch runtime state — admission happens later,
     * on the canonical coordinator, before the stage starts (fail-closed).
     * A script that declares directives and runs against an engine whose
     * composition registers none of them will fail the run with a typed USER
     * failure naming the unresolved key — never a silent skip.
     * Example:
     * ```
     * stage("deploy") {
     *     directives {
     *         directive("acme.lock", """{"resource":"prod"}""")
     *     }
     *     sh("./deploy.sh")
     * }
     * ```
*/
    fun directives(block: DirectivesScope.() -> Unit) {
        val scope = DirectivesScope()
        scope.block()
        // ACCUMULATE, do not assign. Two paths declare stage directives
        // (`directives { }` and the typed `when*` helpers) and both may be
        // used in the same stage. Assigning here would silently discard a `when`
        // declared earlier in the stage body, running the stage unconditionally
        // while the script clearly meant to gate it — a
        // fail-OPEN defect, the worst possible direction for a gate.
        stageDirectives = stageDirectives + scope.build()
    }

    /**
     * Conditional stage execution. The gate is REAL: the predicate is a VALUE, not text.
     * These helpers construct a typed
     * [dev.rubentxu.pipeline.v2.domain.directive.WhenPredicate] and append a
     * `core.when` stage directive carrying its encoded form. Nothing is
     * evaluated here: the gate runs in the durable coordinator before the stage
     * body, and its verdict is observable.
     * A textual predicate such as `whenCondition("1 == 2")` is not accepted,
     * because a `String` cannot carry semantics into the IR: it would have to
     * be re-parsed at run time by an interpreter that does not exist, and the
     * only honest outcome would be a silent no-op — the body would run and the
     * run would report success while the script believed it was gating
     * execution.
     * PURE by construction (the purity rule: no I/O, no process
     * execution, no clock read, and no placeholder runtime value).
     * `whenEnvIs("DEPLOY_ENV", "prod")` stores the NAME and the EXPECTED STRING;
     * it never pretends to know what the variable currently holds.
     * They APPEND rather than replace, so a `directives { }` block and these
     * shorthands can be combined without one silently discarding the other.
*/
    fun whenGate(predicate: dev.rubentxu.pipeline.v2.domain.directive.WhenPredicate) {
        stageDirectives = stageDirectives + appendWhenGate(predicate)
    }

    /**
     * Declare the execution target this stage requires (Jenkins-familiar `agent`).
     * The declaration is honoured because it has both a carrier and a consumer:
     * the resolver seam in the BEFORE_STAGE directive engine reads it. A label
     * that no runtime component reads would be metadata without an interpreter
     * and therefore a silent lie, so the surface accepts only requirements that
     * something resolves, and it is that resolver — not a stub — which makes the
     * call meaningful.
     * This is a PURE builder. It encodes a declared requirement and appends a
     * directive; it performs no effect, reads no clock and manufactures no
     * runtime value, which is what the purity rule requires of a builder. It
     * enforces that structurally: it appends a directive and returns.
     * A [label] becomes `LocalLabels`; a [remoteUri] becomes `Remote`, which is
     * CARRIED AND DECODED but refused at resolution, because this runtime has no
     * Remote allocator until. That refusal is the honest outcome: the stage
     * Fails closed with a diagnostic naming, rather than quietly running on
     * the local host and ignoring the selector the author wrote.
     * @throws IllegalArgumentException if neither is supplied, or if both are,
     *   which is a contradiction rather than a stricter constraint.
*/
    fun agent(label: String? = null, remoteUri: String? = null) {
        stageDirectives = stageDirectives + appendAgentRequirement(AgentRequirements.of(label, remoteUri))
    }

    /**
     * Declare the execution target this stage requires, the way a Jenkinsfile
     * writes it: `agent { label("linux") }`.
     *
     * The block and the call are TWO SPELLINGS OF ONE DECLARATION: the block
     * builds the requirement with the same authority the call uses, and it is
     * carried by the same directive (`core.agent`), so a stage that declares
     * its agent with the block runs with the agent a stage that declares it
     * with the call declares. The block is not a second path: it is the path
     * Jenkins writes, and this product accepts it.
     *
     * The block holds the OPTIONS of the agent and no step, because the block
     * of Jenkins holds no step either: the scope it opens carries a marker of
     * its own, so the calls of this stage are out of reach from inside it.
     *
     * @param block the options of the agent, written with [AgentScope.label]
     * @throws IllegalArgumentException when the block declares no label, or
     *   declares two
     */
    fun agent(block: AgentScope.() -> Unit) {
        val scope = AgentScope()
        scope.block()
        stageDirectives = stageDirectives + appendAgentRequirement(scope.requirement())
    }

    /**
     * Declare an execution target with no constraint beyond "somewhere local".
     * Distinct from leaving the field blank: the author said "any local target",
     * and it encodes differently, so an observer can tell a deliberate
     * [dev.rubentxu.pipeline.v2.domain.directive.ExecutionTargetRequirement.LocalAny]
     * from a stage that never mentioned an agent at all.
*/
    fun agentAny() {
        stageDirectives = stageDirectives + appendAgentRequirement(AgentRequirements.any())
    }

    /**
     * Declare that the target must provide specific capabilities.
     * The requirement is checked against what the run was actually GRANTED, not
     * against what the runtime could provide in principle, so a stage cannot ask
     * for a capability nobody bound and be told yes.
*/
    fun agentWithCapabilities(vararg capabilities: String) {
        stageDirectives = stageDirectives +
            appendAgentRequirement(AgentRequirements.withCapabilities(capabilities))
    }

    /**
     * Jenkins-familiar form: gate a stage on an exact variable value.
     * This is a DECLARATION: the comparison happens at run time against the
     * real context, never during script construction.
*/
    fun whenEnvIs(variable: String, expected: String) {
        whenGate(
            dev.rubentxu.pipeline.v2.domain.directive.WhenPredicate.VariableEquals(variable, expected)
        )
    }

    /** Gate on a variable being set and non-empty. */
    fun whenEnvPresent(variable: String) {
        whenGate(
            dev.rubentxu.pipeline.v2.domain.directive.WhenPredicate.VariablePresent(variable)
        )
    }

    fun toStageBuilder(): StageBuilder {
        // `post { }` is now REAL. It was previously accepted surface whose
        // semantics were not implemented, so it threw here rather than silently
        // Never running. The block is carried as typed
        // data and the coordinator decides which finalizer fires from the pure
        // PostPlanner; the DSL still decides NOTHING about execution.
        //
        // The builder carries what THIS STAGE declares, and nothing else. The
        // declarations of the PIPELINE are applied once, at the end of the
        // pipeline's block, by `PipelineScope.buildStages()`. Applying them in
        // the two places would make the plan the result of two merges, and a
        // merge that is broken in one of them would leave the plan untouched
        // — the mistake would be invisible to every row that measures it.
        return StageBuilder(
            stageName,
            steps.toList(),
            stageOptions,
            stageEnvironment?.values,
            stageDirectives,
            stagePost ?: PostConditionSpec(),
        )
    }
}
