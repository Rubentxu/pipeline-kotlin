package dev.rubentxu.pipeline.v2.domain

import dev.rubentxu.pipeline.v2.domain.post.PostCondition
import kotlinx.serialization.Serializable

/** Immutable, inspectable pipeline definition produced before execution. */
@Serializable
data class CompiledPipeline(
    val id: DefinitionId,
    val source: SourceDescriptor,
    val environment: EnvironmentSpec = EnvironmentSpec.empty(),
    val options: List<StageOption> = emptyList(),
    val parameters: List<ParameterSpec> = emptyList(),
    val tools: List<ToolSpec> = emptyList(),
    val stages: List<StageNode>,
    val post: PostSpec? = null,
    val pluginLockDigest: Digest,
) {
    init {
        require(stages.map(StageNode::id).toSet().size == stages.size) {
            "CompiledPipeline stages must have unique ids"
        }
    }
}

/**
 * The pipeline source a compiled definition came from.
 *
 * The path is where the definition was read from and the digest fingerprints
 * its content, so two compilations of the same file are recognisable as the
 * same source. The path must not be blank; the digest is whatever the
 * compiler hashed the file into.
 */
@Serializable
data class SourceDescriptor(val path: String, val digest: Digest) {
    init { require(path.isNotBlank()) { "SourceDescriptor.path must not be blank" } }
}

/**
 * An opaque content fingerprint, used wherever a compiled definition has to
 * name the thing it was built from.
 *
 * The value is text the domain never interprets: it is compared, hashed
 * further and embedded in identities, but no reader parses it. That is why
 * the only rule is that it is not blank — a fingerprint with no text is not a
 * fingerprint of anything.
 */
@JvmInline
@Serializable
value class Digest(val value: String) {
    init { require(value.isNotBlank()) { "Digest value must not be blank" } }
}

/**
 * The environment a stage or a body scope contributes.
 *
 * The names are the variable names the steps inside the scope will see, and
 * no name may be blank: an environment entry nobody can name is an entry
 * nothing can read. The values are plain text; nothing here interprets them.
 */
@Serializable
data class EnvironmentSpec(val values: Map<String, String>) {
    init { require(values.keys.none(String::isBlank)) { "Environment keys must not be blank" } }
    /**
     * The factory that builds the environment that contributes nothing.
     */
    companion object {
        /**
         * The environment that contributes nothing.
         *
         * A scope with no declared variables uses `empty()`, which yields an
         * environment equal to every other empty one.
         */
        fun empty() = EnvironmentSpec(emptyMap())
    }
}

/**
 * A name and an optional value. DEPRECATED: use [StageOption].
 *
 * The name must not be blank, and the value may be absent. The pair states
 * nothing about which options exist, so it cannot say whether anything will
 * read it — that is what [StageOption] states.
 *
 * Why it is wrong, stated so the deprecation is informative rather than a bare
 * marker: a `name: String` / `value: String?` pair cannot express WHICH options
 * exist, so an option that no interpreter reads is indistinguishable from one that
 * is. It compiles, serializes, and does nothing — the silent drop the contract
 * names as a defect class.
 *
 * It stays because it is PUBLISHED API: it is a public class in
 * `dev.rubentxu.pipeline.v2.domain`, so removing it outright breaks every
 * external plugin author who named it, and the binary-compatibility validator
 * is right to refuse.
 *
 * Nothing enforces that it stays unused, and that is a gap rather than an
 * oversight: what would enforce it is a source-text fitness, and a fitness that
 * greps the tree does not belong inside a unit test. What holds the line today is
 * only the deprecation itself: a construction site would have to name a
 * deprecated type on purpose. The residual risk is real and small, and it is
 * stated here rather than closed.
 *
 * @see StageOption for the carrier to use instead.
*/
@Deprecated(
    message = "OptionSpec is a name/value bag with no type-level statement of which options " +
        "exist, so an option nothing reads is indistinguishable from one that is. Use " +
        "StageOption, whose cases are sealed and therefore force an interpreter to exist.",
    replaceWith = ReplaceWith("StageOption.Timeout(milliseconds)", "dev.rubentxu.pipeline.v2.domain.StageOption"),
    level = DeprecationLevel.WARNING,
)
@Serializable
data class OptionSpec(val name: String, val value: String? = null) {
    init { require(name.isNotBlank()) { "OptionSpec.name must not be blank" } }
}

/**
 * A stage option, as a CLOSED set of cases rather than a name/value bag.
 *
 * A name/value pair forces a stringly-typed hole through the whole path: a
 * typed value has to be flattened to a string and recovered from it, a name a
 * typo away from silence, and the recovery needs a parse that can fail for what
 * are ordinary author mistakes. Here the KIND is the type, so an option that no
 * interpreter reads cannot be written down in the first place.
 * ## Why only one case
 * The rule that decides it: *"Every option must map to a real
 * policy/interpreter or be rejected."* Only `Timeout` has one
 * (`StageNode.projectShellOptions` -> `ShOptions.timeoutMs`). The stage DSL also
 * offers `retry` and `timestamps`, but those are BODY decorators
 * (`StepSpec.RetryBlock`, `StepSpec.Timestamps`) with their own interpreters, not
 * stage options, so this ADT carries no case for them. A case for an option that
 * has no interpreter would BE the defect the rule forbids — a declared option
 * with no interpreter — inside a more respectable-looking type. The ADT grows a
 * case on the day an interpreter exists, not on the day a design note lists it.
 * ## Why the sealed-ness is load-bearing
 * The interpreter reads options through a closed `when` over this interface,
 * with no `else` to absorb an unknown case. So the rule is enforced by the
 * build, not by discipline: an option cannot be added without the interpreter
 * being forced to name it, and the compiler names the branch that is missing.
 * ## Why the invariant lives in the constructor
 * `Timeout` rejects a non-positive duration at construction, so an invalid
 * deadline is unrepresentable rather than representable-and-rejected later.
 * The interpreter therefore needs no "multiple timeout options" check, no parse,
 * no positivity check and no overflow guard: a closed `when` over this interface
 * is total by construction rather than by an `else` that hides an unhandled case.
*/
@Serializable
sealed interface StageOption {

    /**
     * A stage-wide shell deadline.
     * Distinct from the `timeout()` BLOCK Step, which is a body with its own
     * `TimeoutScheduled`/`TimeoutTriggered` events. This produces no such
     * event: a breach surfaces as the governed step's own
     * `StepFailed(failureKind = TIMEOUT)`.
*/
    @Serializable
    data class Timeout(val milliseconds: Long) : StageOption {
        init {
            require(milliseconds > 0) {
                "StageOption.Timeout must be positive, was ${milliseconds}ms. A deadline of " +
                    "zero or less is not a deadline: it would either never fire or fire before " +
                    "the stage starts, and both look like a working timeout to the author."
            }
        }
    }
}

/**
 * A parameter the pipeline declares it accepts.
 *
 * The name and the type must not be blank: a parameter nobody can name, or
 * whose type is empty, is a declaration no caller could satisfy. The type is
 * a name and not a parsed type — the domain does not interpret it. The
 * default is the text a caller may supply instead of a value; `null` means
 * the parameter declares none.
 */
@Serializable
data class ParameterSpec(val name: String, val type: String, val defaultValue: String? = null) {
    init {
        require(name.isNotBlank()) { "ParameterSpec.name must not be blank" }
        require(type.isNotBlank()) { "ParameterSpec.type must not be blank" }
    }
}

/**
 * A tool the pipeline declares it needs, at one version.
 *
 * Neither the name nor the version may be blank: a tool nobody can name, or
 * one named without a version, is a requirement no resolver can satisfy.
 * The version is the one the declaration pins; nothing here compares it
 * against anything.
 */
@Serializable
data class ToolSpec(val name: String, val version: String) {
    init {
        require(name.isNotBlank()) { "ToolSpec.name must not be blank" }
        require(version.isNotBlank()) { "ToolSpec.version must not be blank" }
    }
}

/**
 * The `post` block as it appears in the compiled IR
 * TYPED over [PostCondition] rather than `Map<String, ...>`: a bare string key
 * would let `post { alwyas { } }` compile into a block that silently never runs,
 * which is the exact fake-fallback class the contract forbids: a condition that
 * is accepted and never matched. The closed enum makes an unrecognised condition
 * a compile error instead.
 * Values are [StepNode] lists because the IR must round-trip through
 * serialization without knowing any execution detail. The pure
 * [dev.rubentxu.pipeline.v2.domain.post.PostPlanner] decides which of these
 * nodes run, in which order, for a given stage outcome; the coordinator is the
 * interpreter that dispatches them. There is NO second reference layer: the
 * nodes here are the same values the stage body would carry, so a durable
 * rerun replays the identical program.
*/
@Serializable
data class PostSpec(
    val conditions: Map<PostCondition, List<StepNode>> = emptyMap(),
) {
    init {
        require(conditions.keys.none { it !in PostCondition.EXECUTION_ORDER }) {
            "Post condition names must be known: ${conditions.keys - PostCondition.EXECUTION_ORDER.toSet()}"
        }
    }

    val isEmpty: Boolean get() = conditions.isEmpty()

    /**
     * The pure projection into the runtime [dev.rubentxu.pipeline.v2.domain.post.PostPlan].
     * Total, effect-free, and the ONLY place the two representations meet.
*/
    fun toPostPlan(): dev.rubentxu.pipeline.v2.domain.post.PostPlan =
        dev.rubentxu.pipeline.v2.domain.post.PostPlan(bodies = conditions)
}

/**
 * The condition a stage declares, kept as text.
 *
 * The expression must not be blank. The domain carries the text; whether a
 * condition is evaluated, and against what, belongs to whoever reads it.
 */
@Serializable
data class ConditionSpec(val expression: String) {
    init { require(expression.isNotBlank()) { "ConditionSpec.expression must not be blank" } }
}

/**
 * The prompt a stage shows when it asks the caller for input.
 *
 * The message must not be blank: a request nobody can read is not a request.
 * What the answer does to the stage is decided where the stage is executed,
 * not here.
 */
@Serializable
data class InputSpec(val message: String) {
    init { require(message.isNotBlank()) { "InputSpec.message must not be blank" } }
}

/**
 * The axes a matrix body expands over.
 *
 * Each entry names one axis and the values it takes. There must be at least
 * one axis, no axis name may be blank, and no axis may have an empty list of
 * values — an axis with no values expands to nothing. The body is not
 * expanded yet: the planner refuses a matrix stage.
 */
@Serializable
data class MatrixSpec(val axes: Map<String, List<String>>) {
    init {
        require(axes.isNotEmpty()) { "MatrixSpec.axes must not be empty" }
        require(axes.keys.none(String::isBlank) && axes.values.none(List<String>::isEmpty)) {
            "Matrix axes must have names and values"
        }
    }
}

/**
 * One stage of a compiled pipeline: the unit a run executes and reports on.
 *
 * The identity must be unique among the stages of its pipeline, and the name
 * must not be blank — a stage nobody can name cannot be reported on. The
 * environment, the options and the condition are the stage's own
 * declarations; the body is what it runs and the post block is what runs
 * after it.
 */
@Serializable
data class StageNode(
    val id: StageId,
    val name: String,
    val environment: EnvironmentSpec = EnvironmentSpec.empty(),
    val options: List<StageOption> = emptyList(),
    val whenCondition: ConditionSpec? = null,
    val input: InputSpec? = null,
    val body: StageBody,
    val post: PostSpec? = null,

    /**
     * Directives declared on this stage, in declaration order.
     * Declarative data only: a key plus its already-encoded arguments. The
     * canonical coordinator admits every entry against the [DirectiveRegistry]
     * BEFORE the stage starts (fail-closed); interpretation per phase is read
     * from the closed [DirectiveExecutionPolicy], never from the key.
*/
    val directives: List<StageDirective> = emptyList(),
) {
    init { require(name.isNotBlank()) { "StageNode.name must not be blank" } }
}

/**
 * A directive invocation as declared on a stage (carrier)
 * The key is an open-world string so a stage can name any contributed
 * directive; the arguments are an opaque encoded payload owned by the
 * definition's codec. Deliberately serializable plain data: the IR must
 * round-trip without knowing any concrete directive.
*/
@Serializable
data class StageDirective(
    val key: String,
    val encodedArguments: String = "{}",
) {
    init {
        require(key.isNotBlank()) { "StageDirective.key must not be blank" }
    }
}

/**
 * What a stage's body is, as a closed set of shapes.
 *
 * The set is closed so that every reader of a body has to name every case:
 * a body that could also be something else would let a reader handle the
 * cases it knows and silently ignore the rest.
 */
@Serializable
sealed interface StageBody {
    /**
     * A body of steps, run one after another.
     *
     * The order is the order the steps are listed in, and it is the order
     * they run in.
     */
    @Serializable
    data class Steps(val steps: List<StepNode>) : StageBody

    /**
     * A body of stages, each with a body of its own.
     *
     * The stages are the children of this one: the enclosing stage declares
     * them. The planner flattens their units into the same list, and the
     * coordinator refuses a nested body at run time.
     */
    @Serializable
    data class NestedStages(val stages: List<StageNode>) : StageBody

    /**
     * A body of branches that run at the same time.
     *
     * The compiler's validator is what requires at least two branches and a
     * single step per branch: a branch that ran a sequence of its own would
     * not be a branch but a stage, and the shape is refused before a run
     * sees it.
     */
    @Serializable
    data class Parallel(val branches: List<StageNode>) : StageBody

    /**
     * A body declared over a matrix's axis values.
     *
     * The planner does not expand it yet: a matrix stage is refused at
     * planning time.
     */
    @Serializable
    data class Matrix(val matrix: MatrixSpec) : StageBody

    /**
     * A stage whose body is a COMPILED SCRIPTED ARTIFACT.
     * A distinct case rather than a flag, and the distinction is not cosmetic: it makes a hybrid
     * body unrepresentable instead of merely discouraged. A stage that mixes eager steps with
     * runtime calls has no defined lexical ordering between them, and a flag would let it be
     * written down before anyone had decided what that ordering means.
     * The payload is an IDENTITY, not the artifact. `pipeline-domain` deliberately depends on
     * nothing but kotlinx-serialization and coroutines, so it cannot name `ScriptedArtifactIdentity`
     * (owned by `pipeline-scripting-api`) or `CompiledScriptedEntryPoint` (owned by
     * `pipeline-application`) without inverting the dependency. The IR therefore carries the key
     * the artifact's own identity already produced, and the EDGE resolves it — the same arrangement
     * as `PluginStepId` naming a Step that lives in a registry.
*/
    @Serializable
    data class Scripted(val ref: ScriptedStageRef) : StageBody
}

/**
 * Reference to a compiled scripted artifact, carrying only what the canonical spine needs
 * in order to RECONCILE a stage: which artifact, and which entry point inside it.
 * ## Why this holds a key and not the six identity fields
 * `ScriptedArtifactIdentity` owns `sourceDigest`, `dslApiVersion`, `compilerAdapterVersion`,
 * `runtimeCompatibilityVersion`, `pluginLockDigest` and `facadeSchemaDigest`, plus
 * `fingerprintMaterial()` — the collision-free material the replay fingerprint already consumes.
 * Copying those six fields here would create a SECOND authority for the same identity, and two
 * authorities that decide the same thing get eliminated rather than synchronised. So the computation
 * stays where it lives and this type transports its result.
 * ## What this boundary does and does not lose
 * It loses the ARTIFACT, which is executable and not serialisable, and keeps its IDENTITY, which is
 * the only thing the spine needs. The same rule as `classifyShellTerminal` receiving a terminal
 * instead of an `OperationStatus`: the fact travels, its meaning is applied by whoever owns it.
*/
@Serializable
data class ScriptedStageRef(
    /** `ScriptedArtifactIdentity.fingerprintMaterial()`. Never recomputed here. */
    val artifactKey: String,
    val entryPointId: String,
) {
    init {
        require(artifactKey.isNotBlank()) { "ScriptedStageRef.artifactKey must not be blank" }
        require(entryPointId.isNotBlank()) { "ScriptedStageRef.entryPointId must not be blank" }
    }
}

/**
 * One step of a compiled pipeline, as a node the engine can dispatch.
 *
 * The set of shapes is closed, so the engine's dispatch is a `when` over it
 * with no `else` to absorb a case nobody handled: adding a shape forces the
 * dispatch to name it, and the compiler names the branch that is missing.
 */
@kotlinx.serialization.Polymorphic
@Serializable
sealed interface StepNode {
    val id: StepId

    /**
     * The kind of this step: WHAT runs.
     *
     * This is the key the engine dispatches by, and it is unique per kind, not per node.
     */
    val pluginStepId: PluginStepId

    val payload: VersionedStepPayload

    /**
     * The name this step is ADDRESSED by inside the body that owns it: HOW it is called.
     *
     * It is `null` when nobody named it, and then the step is addressed by its [pluginStepId].
     * It is not `null` when the plugin that wrote the body gave the step a name of its own,
     * and then THAT is the address, and two steps of the same [pluginStepId] can live in the
     * same body without colliding.
     *
     * The difference matters because the two answer different questions: [pluginStepId] says what
     * to run, [slot] says which of the plugin's own bodies this is. A plugin that chains actions
     * of the same kind needs [slot]; a plugin that does not keeps working untouched.
     */
    val slot: PluginStepId?

    /**
     * The address this step is called by inside the body that owns it.
     *
     * It is the name the plugin gave it, or its kind when nobody named it — one rule.
     */
    val address: PluginStepId get() = slot ?: pluginStepId
}

/**
 * A step that runs a plugin and has no body of its own.
 *
 * The plugin step id is what the engine dispatches by, and the payload is
 * the step's input in the plugin's own encoding. The encoded payload must
 * not be blank: a step with nothing to run would be dispatched and then do
 * nothing.
 */
@Serializable
data class OpaqueStepNode(
    override val id: StepId,
    override val pluginStepId: PluginStepId,
    override val payload: VersionedStepPayload,
    override val slot: PluginStepId? = null,
) : StepNode

/**
 * A step that owns a body of other steps.
 *
 * The plugin step id names the scope the body runs under, and the payload
 * carries that scope's declared contract; the body carries the steps the
 * scope governs. The step id of a block is its own, so the body is
 * addressed under the block and not in its place.
 */
@Serializable
data class BlockStepNode(
    override val id: StepId,
    override val pluginStepId: PluginStepId,
    override val payload: VersionedStepPayload,
    val body: List<StepNode>,
    override val slot: PluginStepId? = null,
) : StepNode

/**
 * The payload of a step, together with the version of its schema.
 *
 * The schema version names the encoding of the encoded text, and it is what
 * lets the runtime refuse a payload it cannot read instead of guessing at
 * it. The version must not be blank: an unversioned payload has no encoding
 * to refuse.
 */
@Serializable
data class VersionedStepPayload(val schemaVersion: String, val encoded: String) {
    init {
        require(schemaVersion.isNotBlank()) { "VersionedStepPayload.schemaVersion must not be blank" }
    }
}

/**
 * Typed overlay for body execution scope tracking.
 * Each variant carries only the minimal metadata needed for scope restoration.
*/
@kotlinx.serialization.Polymorphic
@Serializable
sealed interface ContextOverlay {
    /**
     * The environment a body scope introduces around its children.
     *
     * The values are the scope's own, and they apply to the body beneath the
     * frame and to nothing outside it.
     */
    @Serializable
    data class Environment(val values: EnvironmentSpec) : ContextOverlay

    /**
     * The working directory a body scope introduces around its children.
     *
     * The path is where the body beneath the frame runs.
     */
    @Serializable
    data class Cwd(val path: String) : ContextOverlay

    /**
     * The credential binding a body scope introduces around its children.
     *
     * The binding id names the binding the body runs under; the lease that
     * grants it is taken by the credential preamble, not by this frame.
     */
    @Serializable
    data class Credentials(val bindingId: String) : ContextOverlay

    /**
     * The output decoration a body scope applies to its children's outcome.
     *
     * The kind names the decoration. A decorated scope turns whatever the
     * body produced into a published warning rather than into a containment.
     */
    @Serializable
    data class OutputDecorator(val kind: String) : ContextOverlay

    /**
     * The cancellation scope a body scope runs under.
     *
     * The scope id names the scope, so a reader can tell two cancellation
     * scopes apart. Whether an interruption ends the body or is absorbed by
     * it is the scope's own decision.
     */
    @Serializable
    data class CancellationScope(val scopeId: String) : ContextOverlay

    // Catch-error overlay.
    //
    // It has no producer and no consumer in the current runtime: the scope's own fold
    // owns the CatchErrorTriggered decision, from inside the scope, so nothing pushes
    // this frame and nothing reads it.
    //
    // It stays because it is PUBLISHED ABI and because it is part of the wire format: a
    // decoder must accept an overlay even when nothing produces it. Both results are
    // typed, and both keep travelling as BARE JSON STRINGS. The explicit serializer is
    // load-bearing — kotlinx would otherwise emit `{"type":"UNSTABLE"}` and change the
    // compiled-pipeline format this overlay has always had.
    /**
     * The frame a catch-error scope carries: what it caught and what it
     * decided about it.
     *
     * The build result is what the scope reports about the failure, the
     * stage result is what the stage reports, the message is the scope's own
     * text, and the timestamp is when the scope was entered. Both results
     * travel as bare JSON strings, so an unknown one is refused rather than
     * read as one of the known ones.
     */
    @Serializable
    data class CatchErrorOverlay(
        @Serializable(with = CatchErrorBuildResult.Serializer::class)
        val buildResult: CatchErrorBuildResult,
        @Serializable(with = CatchErrorBuildResult.Serializer::class)
        val stageResult: CatchErrorBuildResult,
        val message: String?,
        val enteredAt: Long,
    ) : ContextOverlay

    /**
     * The deadline a body scope runs under.
     *
     * The time and the unit are the budget as the scope declared it; the
     * body beneath the frame is what the deadline governs.
     */
    @Serializable
    data class TimeoutOverlay(val time: Long, val unit: String) : ContextOverlay

    /**
     * The retry budget a body scope runs under.
     *
     * The count is how many attempts the scope allows, and the conditions,
     * when they are there, name the circumstances under which an attempt is
     * repeated.
     */
    @Serializable
    data class RetryOverlay(val count: Int, val conditions: List<String>?) : ContextOverlay
}

/**
 * Immutable stack of [ContextOverlay] values.
 * Used by DurableRunCoordinator to track active scopes during body execution.
 * The immutability guarantee ensures trivially correct `finally`-restoration:
 * `val parentStack = contextStack; try { ... } finally { contextStack = parentStack }`.
*/
@Serializable
data class ContextStack(val frames: List<ContextOverlay>) {
    fun push(overlay: ContextOverlay): ContextStack = ContextStack(frames + overlay)
    fun pop(): ContextStack = ContextStack(frames.dropLast(1))
    fun peek(): ContextOverlay? = frames.lastOrNull()
    val size: Int get() = frames.size
    val isEmpty: Boolean get() = frames.isEmpty()

    /**
     * The stack with no frames.
     *
     * [EMPTY] is the value a scope starts from before anything is pushed
     * onto it, and the value a scope returns to when it pops the last frame
     * it owns.
     */
    companion object {
        val EMPTY = ContextStack(emptyList())
    }
}
