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

@Serializable
data class SourceDescriptor(val path: String, val digest: Digest) {
    init { require(path.isNotBlank()) { "SourceDescriptor.path must not be blank" } }
}

@JvmInline
@Serializable
value class Digest(val value: String) {
    init { require(value.isNotBlank()) { "Digest value must not be blank" } }
}

@Serializable
data class EnvironmentSpec(val values: Map<String, String>) {
    init { require(values.keys.none(String::isBlank)) { "Environment keys must not be blank" } }
    companion object { fun empty() = EnvironmentSpec(emptyMap()) }
}

/**
 * S3.3 — DEPRECATED. Superseded by [StageOption]; nothing constructs this.
 *
 * Retained rather than deleted, for one reason: it is PUBLISHED API. It was a
 * public class in `dev.rubentxu.pipeline.v2.domain`, so removing it outright
 * breaks every external plugin author who named it, and
 * `:pipeline-domain:apiCheck` (binary-compatibility validator) is right to
 * refuse. This is the same rule already applied to the deprecated
 * `AgentResolved` event in S3.1: supersede, do not silently break.
 *
 * Why it was wrong, stated so the deprecation is informative rather than a
 * bare marker: a `name: String` / `value: String?` pair cannot express WHICH
 * options exist, so an option that no interpreter reads is indistinguishable
 * from one that is. It compiled, serialized, and did nothing — the silent drop
 * the Semantic Constitution names as a defect class.
 *
 * @see StageOption for the carrier that replaced it. No production source
 *   constructs this type; `FArchS3TypedOptionCarrierFitnessTest` pins that, so
 *   it cannot quietly return as a producer.
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
 * S3.3 — a stage option, as a CLOSED set of cases rather than a name/value bag.
 *
 * This replaces `OptionSpec(name: String, value: String?)`, which forced a
 * stringly-typed hole through the whole path: the DSL held a typed `Long`,
 * the compiler flattened it to `OptionSpec("timeout", it.toString())`, and the
 * interpreter recovered it with `options.filter { it.name == "timeout" }`
 * followed by `toLongOrNull`. A name a typo away from silence, a payload that
 * could not survive the trip, and three exception escapes for what are ordinary
 * author mistakes.
 *
 * ## Why only one case
 *
 * `02-directive-model.md` sketches `Timeout`, `Retry`, `SkipDefaultCheckout`
 * and `Timestamps`, then states the rule that decides it: *"Every option must
 * map to a real policy/interpreter or be rejected."* Only `Timeout` has one
 * (`StageNode.projectShellOptions` -> `ShOptions.timeoutMs`); the other three
 * were removed from the DSL surface in WU-RP-032 precisely because nothing
 * read them. Adding their cases here would re-create the defect the previous
 * shape had — a declared option with no interpreter — inside a more
 * respectable-looking type. The ADT grows a case on the day an interpreter
 * exists, not on the day the document lists it.
 *
 * ## Proof that the sealed-ness is load-bearing
 *
 * Mutation M-s3-3 added `data object Timestamps : StageOption` — a declared case
 * with no interpreter, precisely the §8 violation. It did not compile:
 *
 * ```
 * e: CanonicalStructuralDecisions.kt:292:12 'when' expression must be exhaustive.
 *    Add the 'Timestamps' branch or an 'else' branch.
 * ```
 *
 * So the §8 rule is enforced by the build, not by discipline. An uninterpreted
 * option cannot be added without the interpreter being forced to name it, and
 * the interpreter has no `else` to absorb it.
 *
 * ## Why the invariant lives in the constructor
 *
 * `Timeout` rejects a non-positive duration at construction, so an invalid
 * deadline is unrepresentable rather than representable-and-rejected later.
 * That deletes the interpreter's whole failure surface: it no longer needs a
 * "multiple timeout options" check, a parse, a positivity check or an overflow
 * guard, so a closed `when` over this interface is total by construction
 * rather than by an `else` that hides an unhandled case.
 */
@Serializable
sealed interface StageOption {

    /**
     * A stage-wide shell deadline.
     *
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

@Serializable
data class ParameterSpec(val name: String, val type: String, val defaultValue: String? = null) {
    init {
        require(name.isNotBlank()) { "ParameterSpec.name must not be blank" }
        require(type.isNotBlank()) { "ParameterSpec.type must not be blank" }
    }
}

@Serializable
data class ToolSpec(val name: String, val version: String) {
    init {
        require(name.isNotBlank()) { "ToolSpec.name must not be blank" }
        require(version.isNotBlank()) { "ToolSpec.version must not be blank" }
    }
}

/**
 * The `post` block as it appears in the compiled IR (S2-B).
 *
 * TYPED over [PostCondition] rather than `Map<String, ...>`: a bare string key
 * would let `post { alwyas { } }` compile into a block that silently never runs,
 * which is the exact fake-fallback class the Step Constitution forbids. The
 * closed enum makes an unrecognised condition a compile error instead.
 *
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

@Serializable
data class ConditionSpec(val expression: String) {
    init { require(expression.isNotBlank()) { "ConditionSpec.expression must not be blank" } }
}

@Serializable
data class InputSpec(val message: String) {
    init { require(message.isNotBlank()) { "InputSpec.message must not be blank" } }
}

@Serializable
data class MatrixSpec(val axes: Map<String, List<String>>) {
    init {
        require(axes.isNotEmpty()) { "MatrixSpec.axes must not be empty" }
        require(axes.keys.none(String::isBlank) && axes.values.none(List<String>::isEmpty)) {
            "Matrix axes must have names and values"
        }
    }
}

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
     * S1-B: directives declared on this stage, in declaration order.
     *
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
 * A directive invocation as declared on a stage (S1-B carrier).
 *
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

@Serializable
sealed interface StageBody {
    @Serializable
    data class Steps(val steps: List<StepNode>) : StageBody
    @Serializable
    data class NestedStages(val stages: List<StageNode>) : StageBody
    @Serializable
    data class Parallel(val branches: List<StageNode>) : StageBody
    @Serializable
    data class Matrix(val matrix: MatrixSpec) : StageBody

    /**
     * S4-F2 (ADR-S4-F2) — a stage whose body is a COMPILED SCRIPTED ARTIFACT.
     *
     * A distinct case rather than a flag, and the distinction is not cosmetic: it makes a hybrid
     * body unrepresentable instead of merely discouraged. A stage that mixes eager steps with
     * runtime calls has no defined lexical ordering between them, and a flag would let it be
     * written down before anyone had decided what that ordering means.
     *
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
 * S4-F2 — reference to a compiled scripted artifact, carrying only what the canonical spine needs
 * in order to RECONCILE a stage: which artifact, and which entry point inside it.
 *
 * ## Why this holds a key and not the six identity fields
 *
 * `ScriptedArtifactIdentity` owns `sourceDigest`, `dslApiVersion`, `compilerAdapterVersion`,
 * `runtimeCompatibilityVersion`, `pluginLockDigest` and `facadeSchemaDigest`, plus
 * `fingerprintMaterial()` — the collision-free material the replay fingerprint already consumes.
 *
 * Copying those six fields here would create a SECOND authority for the same identity, and two
 * authorities that decide the same thing get eliminated rather than synchronised. So the computation
 * stays where it lives and this type transports its result.
 *
 * ## What this boundary does and does not lose
 *
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

@kotlinx.serialization.Polymorphic
@Serializable
sealed interface StepNode {
    val id: StepId
    val pluginStepId: PluginStepId
    val payload: VersionedStepPayload
}

@Serializable
data class OpaqueStepNode(
    override val id: StepId,
    override val pluginStepId: PluginStepId,
    override val payload: VersionedStepPayload,
) : StepNode

@Serializable
data class BlockStepNode(
    override val id: StepId,
    override val pluginStepId: PluginStepId,
    override val payload: VersionedStepPayload,
    val body: List<StepNode>,
) : StepNode

@Serializable
data class VersionedStepPayload(val schemaVersion: String, val encoded: String) {
    init {
        require(schemaVersion.isNotBlank()) { "VersionedStepPayload.schemaVersion must not be blank" }
    }
}

/**
 * Typed overlay for body execution scope tracking.
 *
 * Replaces the untyped `ScopeFrame` marker stack in CanonicalDurableRunCoordinator
 * with a sealed family of typed overlays. Each variant carries only the minimal
 * metadata needed for scope restoration.
 */
@kotlinx.serialization.Polymorphic
@Serializable
sealed interface ContextOverlay {
    @Serializable
    data class Environment(val values: EnvironmentSpec) : ContextOverlay

    @Serializable
    data class Cwd(val path: String) : ContextOverlay

    @Serializable
    data class Credentials(val bindingId: String) : ContextOverlay

    @Serializable
    data class OutputDecorator(val kind: String) : ContextOverlay

    @Serializable
    data class CancellationScope(val scopeId: String) : ContextOverlay

    // Legacy catch-error overlay for migration compatibility.
    // EM-5/EM-6 (catcherror-semantics-em56): carries stageResult + message so the coordinator's
    // catchError fold-walk can publish the CatchErrorTriggered domain event at the point of the
    // real failure (re-throw/suppress decision) instead of at a later IR marker step.
    @Serializable
    data class CatchErrorOverlay(
        val buildResult: String,
        val stageResult: String,
        val message: String?,
        val enteredAt: Long,
    ) : ContextOverlay

    @Serializable
    data class TimeoutOverlay(val time: Long, val unit: String) : ContextOverlay

    @Serializable
    data class RetryOverlay(val count: Int, val conditions: List<String>?) : ContextOverlay
}

/**
 * Immutable stack of [ContextOverlay] values.
 *
 * Used by CanonicalDurableRunCoordinator to track active scopes during body execution.
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

    companion object {
        val EMPTY = ContextStack(emptyList())
    }
}
