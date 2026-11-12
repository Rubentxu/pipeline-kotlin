package dev.rubentxu.pipeline.v2.dsl

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.RuntimeConfig
import dev.rubentxu.pipeline.v2.domain.scm.CheckoutSpec
import dev.rubentxu.pipeline.v2.domain.scm.GitRemotePolicy
import dev.rubentxu.pipeline.v2.domain.scm.GitScm
import dev.rubentxu.pipeline.v2.domain.scm.Scm

/**
 * The MUST_CONSUME carriers registered in a STAGE, and their consumptions.
 *
 * The registry is a STAGE's, not a scope's: `retry`, `dir`,
 * `withCredentials`, `slot`, `catchError` and the rest are lexical blocks of
 * one stage, and the admission point of the gate is the stage. A nested scope
 * therefore SHARES the stage's registry by reference.
 *
 * WHY A CLASS AND NOT A `var` MAP: the map is the RULE, and the rule must not
 * be replaceable from outside — a `var MutableMap` invites a silent
 * `registry = LinkedHashMap()` that drops every registration without any
 * visible symptom. The holder owns the map, exposes only the four operations
 * the gate needs, and the scope only holds a reference to the holder.
 */
internal class CarrierRegistry {

    /**
     * A key that identifies a carrier by REFERENCE, not by value.
     *
     * WHY NOT A PLAIN KEY: the carriers are `data class`es (`CheckoutSpec`
     * wraps `GitScm`), so two carriers built by two calls with the same
     * arguments are EQUAL. A map keyed by equality therefore holds ONE entry
     * for two distinct carriers: consuming one removes that entry, and the
     * other carrier -- discarded -- is never refused. Two `scmGit` calls with
     * the same URL, of which only one is consumed, is exactly that case, and
     * the author would get one checkout where they asked for two.
     *
     * WHY A WRAPPER AND NOT `IdentityHashMap`: the obligations are read back
     * in the diagnostic, and that diagnostic has to be REPRODUCIBLE. An
     * `IdentityHashMap` iterates in an unspecified order; a `LinkedHashMap`
     * keyed by this wrapper keeps the order in which the carriers were
     * produced, so the same stage always refuses in the same order.
     */
    private class IdentityKey(val carrier: Any) {
        private val identityHash = System.identityHashCode(carrier)

        override fun hashCode(): Int = identityHash

        override fun equals(other: Any?): Boolean =
            other is IdentityKey && other.carrier === carrier
    }

    private val unconsumed = LinkedHashMap<IdentityKey, String>()

    /** Records that [carrier] was produced by a pure builder and must be consumed. */
    fun register(carrier: Any, builderName: String) {
        unconsumed[IdentityKey(carrier)] = builderName
    }

    /** Marks [carrier] as consumed. */
    fun markConsumed(carrier: Any) {
        unconsumed.remove(IdentityKey(carrier))
    }

    /** True when no carrier is pending consumption. */
    fun isEmpty(): Boolean = unconsumed.isEmpty()

    /** The human-readable verdict: one clause per discarded carrier. */
    fun discardedDetail(): String = unconsumed.entries.joinToString(", ") { (_, name) ->
        "$name returned a value that was never used (discarded); its return value must be used, " +
            "e.g. by passing it to a consuming builder, or the builder must be called in statement position " +
            "for a builder that declares MAY_DISCARD"
    }
}

/**
 * Shared mutable state for the stage DSL builders.
 * `[MustUseReturnValues]` is applied at
 * CLASS level, not per function, because Kotlin 2.4 restricts the annotation to
 * targets `file` and `class`.
 * Class level is also the semantically right granularity here: it covers every
 * pure builder declared on this scope, so a future `PURE_BUILDER` added to this
 * class inherits the gate without anyone having to remember to annotate it. The
 * class is annotated rather than the file because the file also declares the
 * top-step scope, whose functions are a different concern.
 * The annotation constrains the FATE OF A RETURNED CARRIER, not effects: the
 * `PURE_BUILDER`s here still emit no step and no event. Functions returning
 * `Unit` are unaffected, as are fail-closed stubs returning `Nothing` (a
 * `Nothing` result is never discarded — it never returns).
 * SCOPE HONESTY: this annotation is inherited by the whole scope hierarchy
 * (`StageScope` -> `StageScopeTopSteps` -> this), which also covers
 * `SCRIPTED_RUNTIME_CALL` builders such as `pwd(..)` and `isUnix()` declared in
 * `StageScope`. Those are NOT MUST_CONSUME: each call already emits a step, so
 * discarding the returned value loses nothing. The manifest records them as
 * MAY_DISCARD, and the compile-time checker cannot be scoped per-builder — it is
 * all-or-nothing per compilation, so it stays silent for them.
 * The annotation is the compile-time first line. The definition of a
 * `.pipeline.kts` turns Kotlin's return-value checker on and raises its
 * diagnostics to errors, so a discarded carrier is refused by the compiler with
 * the line and the column of the call. The second line is the admission-time
 * carrier registry below: it classifies each carrier individually, so it CANNOT
 * be over-applied, and it does not depend on how the script was compiled.
*/
@MustUseReturnValues
open class StageScopeCore(
    protected val stageName: String,
    protected val runtimeConfig: RuntimeConfig,
) {
    protected val steps = mutableListOf<StepSpec>()

    // ------------------------------------------------------------------
    // Admission-time enforcement
    // ------------------------------------------------------------------
    // WHY THIS EXISTS NEXT TO `@MustUseReturnValues`
    // ================================================
    // The annotation is the FIRST line of defence and it is not sufficient on
    // its own, because its reach depends on HOW the script is compiled: only a
    // compilation with the return-value checker on, and its diagnostics raised
    // to errors, refuses the discard. The definition of a `.pipeline.kts` does
    // both; a compilation that does not use that definition, or that keeps the
    // checker's default WARNING, lets a discarded `@MustUseReturnValues` result
    // compile. This registry does not depend on the compiler at all, so it
    // holds on every path.
    // The rule is expressed as DATA, not as a per-builder name check:
    // a PURE_BUILDER registers the carrier it hands out, and a consumer
    // marks that exact carrier as consumed. Anything registered and never
    // marked is rejected. Adding a new MUST_CONSUME builder requires no
    // change here, and no builder is named in this file.
    // THE REGISTRY BELONGS TO THE STAGE, NOT TO THE SCOPE.
    // ======================================================
    // The only admission point is the STAGE (`StagesScope.stage(..)` calls
    // `rejectUnconsumedCarriers()` on the root scope), so the registry is the
    // stage's. A nested scope is a lexical block of the same stage, not a
    // stage of its own: it shares the carrier registry BY REFERENCE, so the
    // stage-level verdict sees every carrier the stage produced and every
    // consumption the stage performed. A carrier produced inside a nested
    // block (`retry`, `dir`, `withCredentials`, `slot`, `catchError`, ...) is
    // therefore refused when the stage never consumes it, and a consumption
    // performed inside a nested block counts for the stage.
    // `internal` keeps this off the published ABI: the registry is an
    // implementation detail of the admission gate.
    internal var carrierRegistry: CarrierRegistry = CarrierRegistry()

    /**
     * Records that [carrier] was produced by a pure builder and must be
     * consumed by the end of the stage.
*/
    protected fun <T : Any> registerMustConsume(carrier: T, builderName: String): T {
        carrierRegistry.register(carrier, builderName)
        return carrier
    }

    /**
     * Marks [carrier] as consumed. A carrier is marked by IDENTITY, not by
     * "some builder was called": `checkout(scmGit(..).scm)` reads a
     * PROPERTY of the carrier, so the carrier object itself is what has to
     * be marked, not the `scm` value inside it.
*/
    protected fun markCarrierConsumed(carrier: Any) {
        carrierRegistry.markConsumed(carrier)
    }

    /**
     * Fails closed when a MUST_CONSUME carrier was produced and discarded.
     * Called at stage construction, which is before any step, event or
     * process is admitted — so the gate cannot produce a partial effect.
*/
    internal fun rejectUnconsumedCarriers() {
        if (carrierRegistry.isEmpty()) return
        val detail = carrierRegistry.discardedDetail()
        // `kotlin.error` EXPLICITO, NOT the bare `error(..)`:
        // this class declares a MEMBER `error(message, failureKind)` that
        // APPENDS a `StepSpec.Error` to the stage, and a member shadows the
        // top-level `kotlin.error` inside the class body. The bare call would
        // therefore append a step instead of refusing the pipeline, and a
        // gate that reports its verdict as a step is not a gate.
        kotlin.error("Pure builder consumption gate: $detail")
    }

    protected fun nestedScope(): StageScope =
        StageScope(stageName, runtimeConfig).also {
            it.carrierRegistry = carrierRegistry
            it.inheritedEnvironment = visibleEnvironment()
        }

    fun echo(text: String) {
        steps.add(StepSpec.Echo(text))
    }

    fun sh(command: String) {
        steps.add(StepSpec.Shell(command))
    }

    fun sh(script: String, isScriptBlock: Boolean = false, returnStdout: Boolean = false) {
        steps.add(
            StepSpec.Shell(
                command = script,
                isScriptBlock = isScriptBlock,
                returnStdout = returnStdout,
            ),
        )
    }

    fun error(message: String, failureKind: FailureKind = FailureKind.USER) {
        steps.add(StepSpec.Error(message, failureKind))
    }

    /**
     * The published spelling of `error` that takes the failure kind as a
     * string token, kept as a validated adapter: `error("boom", "USER")` keeps
     * compiling. The token is validated here, at script-construction time, and
     * an unrecognised one refuses the pipeline before a run exists. See
     * [LegacyResultVocabulary].
     * `failureKind` has no default so that `error("boom")` is not ambiguous between the two
     * overloads; the bare call resolves to the typed one.
*/
    fun error(message: String, failureKind: String) {
        steps.add(StepSpec.Error(message, LegacyResultVocabulary.failureKind(failureKind)))
    }

    fun sleep(seconds: Long) {
        steps.add(StepSpec.Sleep(seconds))
    }

    fun checkout(scm: Scm) {
        steps.add(StepSpec.Checkout(scm))
    }

    /**
     * Takes the whole [CheckoutSpec] so the carrier
     * itself can be marked consumed, not just the `scm` inside it.
     * The existing `checkout(scm: Scm)` stays because it is part of the public
     * surface and a caller may legitimately own a bare [Scm] with no carrier to
     * track. Keeping both is why the gate needs no migration of existing
     * scripts.
*/
    fun checkout(spec: CheckoutSpec) {
        markCarrierConsumed(spec)
        checkout(spec.scm)
    }
    /**
     * PURE_CONSTRUCTOR (the purity rule: no I/O, no clock, no emitted step): builds the
     * [CheckoutSpec] and emits nothing.
     * `git(..)` is defined as `checkout(scmGit(..).scm)`, so this builder MUST NOT emit
     * the step itself: emitting here as well would make one checkout produce TWO
     * `Checkout` steps.
     * Purity constrains EFFECTS, not the
     * fate of the carrier. `[MustUseReturnValues]` makes discarding the
     * `[CheckoutSpec]` a compile error inside `.pipeline.kts` (the definition of a
     * `.pipeline.kts` enables `-Xreturn-value-checker=check` and raises its
     * diagnostics to errors), because a statement-position
     * `scmGit(..)` that compiles would give the author a checkout they silently
     * did not get. The annotation does not change purity: this still returns a value and
     * still emits no step and no event.
*/
    fun scmGit(
        url: String,
        branch: String = "master",
        credentialsId: CredentialsId? = null,
        changelog: Boolean = true,
        poll: Boolean = true,
        relativeTargetDir: String = ".",
    ): CheckoutSpec {
        require(url.isNotBlank()) { "Missing required parameter: url" }
        // The remote is checked HERE, where the author is, and again where the argv is
        // built. git reads an argument that starts with `-` as an OPTION, so a remote
        // shaped like `--upload-pack=…` is an instruction to run a program, not a
        // repository to fetch. Refusing it while the pipeline is being written means the
        // refusal names the mistake before anything runs, and nothing is left half done.
        GitRemotePolicy.requireSafeRemote(url, branch)
        // The carrier is registered as MUST_CONSUME. `checkout(..)` marks
        // it consumed. Nothing else in this file names a builder, so a future
        // PURE_BUILDER inherits the gate by calling this one method.
        return registerMustConsume(
            CheckoutSpec(GitScm(url, branch, credentialsId, changelog, poll, relativeTargetDir)),
            "scmGit",
        )
    }

    fun git(
        url: String,
        branch: String = "master",
        credentialsId: CredentialsId? = null,
        changelog: Boolean = true,
        poll: Boolean = true,
    ) {
        // `git(..)` is the consuming shape: it builds the carrier AND routes it
        // to `checkout`, so the carrier is marked consumed here. Reading
        // `..scm` is the consumption, not a separate statement.
        checkout(scmGit(url, branch, credentialsId, changelog, poll, "."))
    }

    /**
     * The variables the stage declares, and the ones the steps of the stage run with.
     *
     * A scope that opens INSIDE the stage inherits them, and the declaration a
     * nested block may carry never reaches the steps — so it is not read either:
     * a read and the steps have to see the same environment.
     */
    internal var inheritedEnvironment: EnvironmentSpec? = null

    /**
     * The variables the PIPELINE declares, which are the DEFAULT of every stage
     * that declares none of its own.
     *
     * The precedence is the one the gate already uses — a declaration beats a
     * value that arrives from outside — and it is decided NAME BY NAME: a stage
     * that declares `TARGET` keeps its own value and still inherits the
     * pipeline's `REGISTRY`.
     *
     * This is the READ view: it carries what the pipeline had declared when
     * this stage was opened. The agent of the pipeline does NOT travel this
     * way — it is applied at the end of the pipeline's block, so a declaration
     * written after the `stages { }` block reaches the stages as well.
     */
    internal var pipelineEnvironment: EnvironmentSpec? = null

    /**
     * The environment the steps of this scope run with.
     *
     * The scope that opens a stage declares it. A scope that opens inside that
     * stage inherits it, and the inherited declaration wins over the one the
     * nested scope carries, because a nested declaration never reaches the steps.
     *
     * A stage that declares nothing of its own runs with the variables the
     * PIPELINE declares, and a stage that declares some of them runs with its
     * own value for those and with the pipeline's value for the others.
     */
    private fun visibleEnvironment(): EnvironmentSpec {
        val inherited = inheritedEnvironment
        if (inherited != null) return inherited
        return EnvironmentSpec(declaredEnvironmentValues().orEmpty())
    }

    /**
     * The variables this scope READS: the ones the pipeline declares, with the
     * ones this scope declares winning over them.
     *
     * This is the READ view, and it is not the same thing as the environment
     * the steps run with: a read happens while the plan is built, and the
     * pipeline's declaration is read as the script runs, so a declaration
     * written AFTER the `stages { }` block reaches the steps and not this
     * read. That difference is the ORDER RULE, and it is a decision.
     *
     * The rule of both views is the same one — the declaration of the stage
     * wins, name by name — and it lives in [DeclaredVariables], so the two
     * views cannot disagree about WHICH declaration wins.
     *
     * `null` means "this scope declares no variable", and it is a different
     * statement from "this scope declares none of them": the first leaves the
     * environment of the steps to the layers beneath it.
     */
    internal fun declaredEnvironmentValues(): Map<String, String>? =
        DeclaredVariables.merged(pipelineEnvironment?.values, stageEnvironment?.values)

    /**
     * The value of the environment variable named [name].
     *
     * The value is the one the STAGE declares, when the stage declares one: a
     * stage that writes `environment { env("TARGET", "staging") }` reads
     * "staging" here, in the stage and in the blocks the stage opens. A stage
     * that declares nothing of its own reads the variables the PIPELINE
     * declares, and what neither of them declares reads the environment of the
     * PROCESS that builds the plan, through the runtime configuration port.
     *
     * The read happens WHILE THE PLAN IS BUILT, so the value travels inside the
     * plan and a replay reproduces it. It is not read again when the step runs,
     * and a variable that only the agent has at runtime is not visible here.
     *
     * A variable that is not defined answers null and not an empty text: "not
     * declared" and "declared as empty" are two different statements, and only
     * the second one is a value.
     *
     * What it cannot answer is the material of a credential. A credential reaches
     * the step through the environment of the child process, and the environment
     * of this process never holds it.
     *
     * The declaration is read as the script runs, so the `environment { }` block
     * has to be written BEFORE the step that reads the variable.
     *
     * @param name the name of the variable, as the operating system spells it
     */
    fun env(name: String): String? =
        visibleEnvironment().values[name] ?: runtimeConfig.env(name)

    fun environment(block: EnvironmentScope.() -> Unit) {
        val scope = EnvironmentScope()
        scope.block()
        stageEnvironment = EnvironmentSpec(scope.build())
    }

    protected var stageEnvironment: EnvironmentSpec? = null

    fun options(block: OptionsScope.() -> Unit) {
        val scope = OptionsScope()
        scope.block()
        stageOptions = scope.build()
    }

    protected var stageOptions: OptionsSpec? = null

    fun post(block: PostScope.() -> Unit) {
        val scope = PostScope()
        scope.block()
        stagePost = scope.build()
    }

    protected var stagePost: PostConditionSpec? = null

    fun parallel(block: ParallelScope.() -> Unit) {
        val scope = ParallelScope()
        scope.block()
        steps.add(StepSpec.Parallel(scope.build().map { StepSpec.BranchSpec(it.name, it.steps) }))
    }

    fun withCredentials(binding: StepSpec.CredentialsBinding, block: StageScope.() -> Unit) {
        withCredentials(listOf(binding), block)
    }

    fun withCredentials(
        vararg bindings: StepSpec.CredentialsBinding,
        block: StageScope.() -> Unit,
    ) {
        withCredentials(bindings.toList(), block)
    }

    fun withCredentials(bindings: List<StepSpec.CredentialsBinding>, block: StageScope.() -> Unit) {
        val innerScope = nestedScope()
        innerScope.block()
        val primaryId = bindings.firstOrNull()?.credentialsId ?: CredentialsId("")
        val purpose = bindings.firstOrNull()?.variable
            ?: bindings.firstOrNull()?.usernameVariable
            ?: ""
        steps.add(
            StepSpec.WithCredentialsBlock(
                credentialsId = primaryId,
                purpose = purpose,
                bindings = bindings,
                steps = innerScope.steps(),
            ),
        )
    }

    fun environment(credentialsId: String, variable: String, block: StageScope.() -> Unit) {
        withCredentials(listOf(StepSpec.CredentialsBinding.string(credentialsId, variable)), block)
    }


    /**
     * The steps of a `script { }` block.
     *
     * The block holds STEPS, the way a stage holds them: every call inside it is a
     * step of its own, with its own identity and its own descriptor. `script {
     * echo("x") }` is a `core.echo` and `script { sh("y") }` is a `core.sh`, and
     * neither of them is folded into a single shell.
     *
     * The steps the block declares are the steps of the stage that opened it: the
     * block opens no scope of its own while the run runs. What it adds is the
     * Kotlin control flow of the author, and that control flow is evaluated while
     * the PLAN is built: an `if` inside the block decides which arm is declared,
     * and only the arm that was taken reaches the plan. A condition that has to
     * be decided while the run runs is written with the `when*` gates.
     *
     * The receiver is the receiver of a stage, so the block carries the whole
     * vocabulary: `echo`, `sh`, `dir`, `retry` and the rest. It does NOT carry a
     * recorder of shell lines: a block of shell lines is ONE step, written with
     * `sh("""…""")`.
     *
     * The `@StepDslMarker` of the scope hides the scope that opened the block
     * inside it, so a call inside the block resolves against the block's own
     * scope and never against the stage that opened it.
     */
    fun script(block: StageScope.() -> Unit) {
        val inner = nestedScope()
        inner.block()
        steps.addAll(inner.steps())
    }

    fun steps(): List<StepSpec> = steps.toList()

}

/** Jenkins top-step builders kept separate from the core stage DSL. */
open class StageScopeTopSteps(
    stageName: String,
    runtimeConfig: RuntimeConfig,
) : StageScopeCore(stageName, runtimeConfig) {

    /**
     * The step-retrofit overload is REFUSED (a policy nobody reads is
     * metadata-without-an-interpreter): it projects a full
     * [dev.rubentxu.pipeline.v2.domain.durable.RetryPolicy] (maxAttempts +
     * baseMs + jitterMs) onto the preceding step, but NO runtime consumer reads
     * that policy — the canonical coordinator projects only `maxAttempts` from
     * the `core.retry` block-step payload, and baseMs/jitterMs are executed by
     * no code path. Declaring a delay that nothing honours is a silent lie.
     * The supported surface is the block form `retry(n) { ... }`, which lowers
     * to a `core.retry` Block Step and is honoured by the coordinator.
     * @throws IllegalArgumentException always.
*/
    @Suppress("UnusedParameter") // parameters retained so a rejected call fails
    // with THIS diagnostic instead of a bare Kotlin signature error.
    fun retry(count: Int, delaySeconds: Long? = null): Nothing = throw IllegalArgumentException(
        "retry(count = $count) at step level was removed: the projected retry policy had no " +
            "runtime consumer (the compiled path reads only the maxAttempts of the core.retry " +
            "block step; delaySeconds was never executed). Use the block form retry($count) { ... }.",
    )

    fun writeFile(file: String, text: String, encoding: String = "UTF-8") {
        steps.add(StepSpec.WriteFile(file = file, text = text, encoding = encoding))
    }

    fun registryStep(
        stepKey: dev.rubentxu.pipeline.v2.domain.PluginStepId,
        encodedInput: dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue,
        schemaVersion: String = "dsl-v1",
    ) {
        steps.add(StepSpec.RegistryStepSpec(stepKey, schemaVersion, encodedInput))
    }

    fun registryBlock(
        stepKey: dev.rubentxu.pipeline.v2.domain.PluginStepId,
        encodedInput: dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue,
        schemaVersion: String = "dsl-v1",
        block: StageScope.() -> Unit,
    ) {
        val inner = nestedScope()
        inner.block()
        steps.add(StepSpec.RegistryBlockSpec(stepKey, schemaVersion, encodedInput, inner.steps()))
    }

    fun readFile(file: String, encoding: String = "UTF-8") {
        steps.add(StepSpec.ReadFile(file = file, encoding = encoding))
    }

    fun fileExists(file: String) {
        steps.add(StepSpec.FileExists(file = file))
    }

    fun withEnv(overrides: List<String>, block: StageScope.() -> Unit) {
        val inner = nestedScope()
        inner.block()
        steps.add(StepSpec.WithEnv(overrides = overrides, steps = inner.steps()))
    }

    fun withEnv(overrides: Array<String>, block: StageScope.() -> Unit) {
        withEnv(overrides.toList(), block)
    }

    fun withEnv(overrides: Map<String, String>, block: StageScope.() -> Unit) {
        withEnv(overrides.map { "${it.key}=${it.value}" }, block)
    }

    fun archiveArtifacts(
        artifacts: String,
        allowEmptyArchive: Boolean = false,
        excludes: String = "",
        fingerprint: Boolean = false,
        name: String? = null,
    ) {
        steps.add(
            StepSpec.ArchiveArtifacts(
                artifacts = artifacts,
                allowEmptyArchive = allowEmptyArchive,
                excludes = excludes,
                fingerprint = fingerprint,
                artifactName = name,
            ),
        )
    }

    fun artifactQuery(name: String) {
        val encoded = "{\"kind\":\"artifactQuery\",\"name\":\"${escapeJsonString(name)}\"}"
        steps.add(
            StepSpec.RegistryStepSpec(
                stepKey = dev.rubentxu.pipeline.v2.domain.PluginStepId("core.artifact.query"),
                schemaVersion = "dsl-v1",
                encodedInput = dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue(encoded),
            ),
        )
    }

    /**
     * Reads the EFFECTIVE value of a parameter the pipeline declares.
     *
     * The value is the one the run was started with: the one a caller delivered, or the
     * default the declaration itself carries when nobody delivered anything. A name the
     * pipeline does not declare is not a value anybody can read — the step fails by name
     * instead of answering one.
     *
     * This builder lowers to the registry Step `core.param`, which reads the run's
     * parameters through the run's own capability. The value is that step's runtime value
     * and NOTHING ELSE: it does not become a variable of the step's process and it does not
     * reach the shell. A pipeline that wants the value in a command asks for it there.
     *
     * The call returns nothing on purpose. A value returned here would be a text the author
     * could put in a command, and no text is knowable while the pipeline is being built —
     * the effective value depends on what the run is given, which is not known until the run
     * starts. Returning a placeholder would be a trap: the value that matters is the one the
     * step reads, and only the step reads it.
     *
     * @param name the name of the declared parameter the step reads
     */
    fun param(name: String) {
        require(name.isNotBlank()) {
            "a parameter read needs the name of the parameter it reads"
        }
        val encoded = "{\"kind\":\"param\",\"name\":\"${escapeJsonString(name)}\"}"
        steps.add(
            StepSpec.RegistryStepSpec(
                stepKey = dev.rubentxu.pipeline.v2.domain.PluginStepId("core.param"),
                schemaVersion = "dsl-v1",
                encodedInput = dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue(encoded),
            ),
        )
    }

    fun dir(path: String, block: StageScope.() -> Unit) {
        val inner = nestedScope()
        inner.block()
        steps.add(StepSpec.Dir(path = path, steps = inner.steps()))
    }

    /**
     * Holds a named resource for the duration of the nested block (/
     * Resource, timeout, reason, skipIfLocked).
     * Declarative construction only: this builder records a [StepSpec.Lock];
     * exclusion semantics, admission and events belong to the `core.lock`
     * handler behind the registry. `timeoutSeconds` is in seconds, the engine's
     * deadline unit (`timeoutUnit` of Jenkins is not exposed). `skipIfLocked`
     * with a `timeoutSeconds` is a contradictory declaration and is rejected as
     * typed input by the Step, not silently resolved here.
     * @param resource name of the resource to hold. Mandatory, as in Jenkins.
     * @param timeoutSeconds maximum wait once the resource is contended;
     *   `null` waits indefinitely.
     * @param reason human-readable motive surfaced in the lock events.
     * @param skipIfLocked when the resource is held, run nothing and succeed.
*/
    fun lock(
        resource: String,
        timeoutSeconds: Int? = null,
        reason: String? = null,
        skipIfLocked: Boolean = false,
        block: StageScope.() -> Unit,
    ) {
        val inner = nestedScope()
        inner.block()
        steps.add(
            StepSpec.Lock(
                resource = resource,
                timeoutSeconds = timeoutSeconds,
                reason = reason,
                skipIfLocked = skipIfLocked,
                steps = inner.steps(),
            ),
        )
    }

    /**
     * Asks a human and runs the body only if they say yes (/; Jenkins
     * `input`: message, ok, submitter, id).
     * Declarative construction only: this builder records a [StepSpec.Input]. Whether
     * the body runs, and what an abort means, belongs to the `core.input` handler
     * behind the registry — including the rejection of a blank `message` or `ok`,
     * which is a typed declaration error, not a silent pass-through.
     * @param message what to ask. Mandatory, and blank is rejected.
     * @param ok label of the affirmative answer, Jenkins `ok`.
     * @param submitter attribution of who is expected to answer. NOT an
     *   authorization boundary: this runner is headless and has no user database.
     * @param id correlation id for the request, Jenkins `id`.
     * @param timeoutSeconds bound on the wait; `null` waits until the enclosing
     *   block budget runs out.
*/
    fun input(
        message: String,
        ok: String = "Proceed",
        submitter: String? = null,
        id: String? = null,
        timeoutSeconds: Int? = null,
        block: StageScope.() -> Unit,
    ) {
        val inner = nestedScope()
        inner.block()
        steps.add(
            StepSpec.Input(
                message = message,
                ok = ok,
                submitter = submitter,
                id = id,
                timeoutSeconds = timeoutSeconds,
                steps = inner.steps(),
            ),
        )
    }

    protected fun escapeJsonString(s: String): String {
        val sb = StringBuilder(s.length + 2)
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c.code < 0x20) sb.append("\\u").append("%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.toString()
    }
}

/**
 * The rule that combines the variables a PIPELINE declares with the ones a
 * STAGE declares.
 *
 * ## One rule and one home
 *
 * The rule is the same for the READ and for the PLAN: the declaration of the
 * stage wins for the names it declares, and the declaration of the pipeline
 * is there for the names it does not. Two answers to one rule are two rules
 * that can drift, and the drift shows up as a stage that reads one value and
 * runs with another.
 *
 * ## What `null` means
 *
 * `null` means "nothing is declared here", and it is a different statement
 * from "declared as empty": the first leaves the variables to the layer
 * beneath it, and the second is a value that says nothing.
 */
internal object DeclaredVariables {

    /**
     * The variables of a stage: the ones the pipeline declares, with the ones
     * the stage declares winning over them, name by name.
     */
    fun merged(pipeline: Map<String, String>?, stage: Map<String, String>?): Map<String, String>? = when {
        pipeline == null -> stage
        stage == null -> pipeline
        else -> pipeline + stage
    }
}
