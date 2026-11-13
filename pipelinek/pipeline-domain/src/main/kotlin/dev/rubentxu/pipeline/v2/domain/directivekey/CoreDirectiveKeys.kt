package dev.rubentxu.pipeline.v2.domain.directivekey

import dev.rubentxu.pipeline.v2.domain.directive.DirectiveKey

/**
 * The registered name of the bundled `when` gate.
 * It lives OUTSIDE `.../domain/directive` on purpose. That package is the
 * directive KERNEL, and its fitness rule (`the kernel declares no hardcoded
 * directive names`) rejects any namespaced literal inside it: the kernel owns
 * the KEY TYPE and the structural policy ADT, while a concrete name is a
 * composition fact belonging to the bundled core plugin.
 * `pipeline-domain` still hosts it because the DSL (`pipeline-scripting-api`)
 * depends only on this module, and a `when` declared in a script must name the
 * same key the runtime registers — inventing the name in either place alone
 * would be a silent divergence, the exact defect class the predicate codec
 * already guards against.
*/
val WHEN_DIRECTIVE_KEY: DirectiveKey = DirectiveKey("core.when")

/**
 * The registered name of the bundled `agent` execution-target request.
 * Published from the same place, and for the same reason, as
 * [WHEN_DIRECTIVE_KEY]: the DSL module depends only on `pipeline-domain`, so
 * this is the one place both the script and the runtime can read without one
 * of them inventing the name. Divergence here is silent by construction — a
 * script would declare a key nothing resolves — which is exactly the defect the
 * predicate codec already guards against for `when`.
 *
 * ## What the language does with it, and what it does NOT
 *
 * The LANGUAGE no longer offers a way to author it: the DSL has no `agent(..)`,
 * no `agent { }` and no `agentAny()`. What SURVIVED is the directive — the key,
 * the definition, the resolver and the interpreter — because a plan that
 * carries `core.agent` (one composed before the removal, or one composed
 * through the open seam) still carries a requirement, and the run that resumes
 * it still has to HONOUR it. A key that nothing could resolve would make such
 * a plan fail for the wrong reason; a key that is simply IGNORED would let a
 * stage run on a machine the author excluded.
 *
 * ## What the key resolves to today
 *
 *  - declared phase: BEFORE_STAGE (a body must not start before its target is resolved)
 *  - declared policy: [dev.rubentxu.pipeline.v2.domain.directive.DirectiveExecutionPolicy.Resource]
 *  - carrier: [dev.rubentxu.pipeline.v2.domain.directive.ExecutionTargetRequirement]
 *  - interpreter: the Resource branch of `BeforeStageDirectiveEngine.interpret`
 *
 * A stage declaring `core.agent` is NOT denied fail-closed by the registry.
 * It is decoded, composed, and resolved; `Remote` is the case that is
 * Refused, and it is refused at resolution with a diagnostic naming rather
 * than by failing to resolve the key.
 *
 * This paragraph used to say the key was "NOT yet registered" by a
 * [dev.rubentxu.pipeline.v2.domain.directive.DirectiveDefinition], that "the
 * definition, the resolver and the interpreter do not exist", and that "the
 * DSL still throws". All three were false from.1 onward and had been read as
 * true by anyone consulting the source: `AgentDirectiveDefinition`,
 * `LocalExecutionTargetResolver` and the
 * `DirectiveExecutionPolicy.Resource` interpretation in
 * `BeforeStageDirectiveEngine` have all existed since then, and they still
 * do. The KDoc survived because nothing checks prose against code, and a stale
 * capability claim is the same defect class as a stale capability: it tells a
 * reader to expect behaviour that is not there.
*/
val AGENT_DIRECTIVE_KEY: DirectiveKey = DirectiveKey("core.agent")
