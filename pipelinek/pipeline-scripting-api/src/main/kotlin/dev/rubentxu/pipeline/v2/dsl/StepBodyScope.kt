package dev.rubentxu.pipeline.v2.dsl

import dev.rubentxu.pipeline.v2.domain.RuntimeConfig

/**
 * The receiver of a BODY of steps: the operations a body may declare, and
 * nothing else.
 *
 * ## What this type is for
 *
 * A body of steps —the `steps { }` of a stage, its `script { }`, and the
 * wrappers that carry steps— used to receive the WHOLE stage scope. That
 * scope carries two different kinds of member, and a body can only use one
 * of them:
 *
 * - the OPERATIONS, which declare steps: `echo`, `sh`, `writeFile`, a plugin's
 *   step, a wrapper that carries steps;
 * - the CONFIGURATION of the stage: `options { }`, `post { }`, the declarative
 *   `environment { }`, the `when*` gates, the `directives { }`.
 *
 * The second kind has no authority inside a body: a declaration that belongs
 * to the stage, written inside a body, is DROPPED in silence, and a gate that
 * is dropped is a gate that does not gate. The refusal exists (see
 * `StageAuthority.require`), but a refusal that only happens at RUNTIME is a
 * refusal the AUTHOR never sees: the script compiles, and the gate is gone.
 *
 * This type makes the boundary part of the TYPE. The body receives a
 * `StepBodyScope`, and the configuration of the stage is not in it, so the
 * script that writes it does not compile. The compiler is the cheapest
 * place to refuse a form, and it refuses it BEFORE the plan exists.
 *
 * ## What this type does NOT do
 *
 * It does not REPLACE the fail-closed validation of the plan. The marker of
 * the DSL and this type are ERGONOMICS: they make the invalid form hard to
 * write. The validation that refuses a declaration of the stage inside a body
 * of steps stays where it was, in the construction of the plan, and it keeps
 * refusing what it refused before.
 *
 * ## Why it is SPLIT in three
 *
 * The contract declares one member per operation, and a member per operation
 * is a wide contract. The stage scope answers the same width the same way:
 * it is split into `StageScopeCore`, `StageScopeTopSteps` and `StageScope`,
 * and each part stays inside the width the `TooManyFunctions` rule allows the
 * facades of this product. This contract is split the same way:
 *
 * - [StepBodyScopeOperations] declares the operations: the steps that act on
 *   their own and the steps that touch the workspace;
 * - [StepBodyScopeWrappers] declares the blocks that carry steps;
 * - this type is the NAME an author writes, and it adds the steps of a
 *   PLUGIN through [StepEmitter].
 *
 * The split is not a second hierarchy: there is ONE receiver of a body, this
 * one, and the parts are its parts.
 *
 * ## Why the members have NO value by default
 *
 * A value by default lives in ONE place: in the interface or in the class,
 * never in both. The Kotlin compiler refuses the two at once ("More than one
 * function overridden by 'x' declares a default value for 'y'"), and the
 * synthetic `$default` that carries a value by default is bound to the CLASS
 * that declares it: when the value moves to the interface, the synthetic
 * leaves the class and a consumer compiled against the old class fails with
 * `NoSuchMethodError`.
 *
 * So the values by default STAY in the concrete scopes, where the published
 * surface already carries them, and the forms that OMIT them are offered as
 * extensions over this type: the package [dev.rubentxu.pipeline.v2.dsl]
 * declares them.
 *
 * @see [dev.rubentxu.pipeline.v2.dsl.deleteDir] for an extension that omits a
 *   value by default.
 */
@StepDslMarker
interface StepBodyScope :
    StepBodyScopeOperations,
    StepBodyScopeWrappers,
    StepEmitter<StepBodyScope>

/**
 * The runtime object a body of steps receives.
 *
 * ## Why a SUBCLASS and not the scope itself
 *
 * The scope of a stage is published: its members carry the values by default,
 * and the synthetic `$default` of each one is bound to the class that declares
 * it. A body that received the stage scope itself would need that scope to
 * implement [StepBodyScope], and a member that implements an interface member
 * may not declare a value by default — the compiler refuses it ("An
 * overriding function is not allowed to specify default values for its
 * parameters"). The values would have to move to a superclass, and the
 * synthetics would move with them: the consumer compiled against the old
 * class would fail with `NoSuchMethodError`.
 *
 * This class is that subclass: it INHERITS the members, with their values by
 * default and their synthetics where they always were, and it satisfies the
 * contract with them. The published surface of the stage scope does not move,
 * and a consumer compiled against it keeps linking and keeps running: the
 * object it receives is a stage scope, as it always was.
 *
 * It is `internal`: it is the object the runtime hands to a body, not a type
 * an author names.
 */
internal class StepBodyHostScope(
    stageName: String,
    runtimeConfig: RuntimeConfig,
) : StageScope(stageName, runtimeConfig), StepBodyScope
