package dev.rubentxu.pipeline.v2.domain.directivekey

import dev.rubentxu.pipeline.v2.domain.directive.DirectiveKey

/**
 * The registered name of the bundled `when` gate.
 *
 * It lives OUTSIDE `.../domain/directive` on purpose. That package is the S1
 * directive KERNEL, and its fitness rule (`the kernel declares no hardcoded
 * directive names`) rejects any namespaced literal inside it: the kernel owns
 * the KEY TYPE and the structural policy ADT, while a concrete name is a
 * composition fact belonging to the bundled core plugin.
 *
 * `pipeline-domain` still hosts it because the DSL (`pipeline-scripting-api`)
 * depends only on this module, and a `when` declared in a script must name the
 * same key the runtime registers — inventing the name in either place alone
 * would be a silent divergence, the exact defect class the predicate codec
 * already guards against.
 */
val WHEN_DIRECTIVE_KEY: DirectiveKey = DirectiveKey("core.when")

/**
 * S3.1 — the registered name of the bundled `agent` execution-target request.
 *
 * Published from the same place, and for the same reason, as
 * [WHEN_DIRECTIVE_KEY]: the DSL module depends only on `pipeline-domain`, so
 * this is the one place both the script and the runtime can read without one
 * of them inventing the name. Divergence here is silent by construction — a
 * script would declare a key nothing resolves — which is exactly the defect the
 * predicate codec already guards against for `when`.
 *
 * S3-R1-D: this paragraph used to say the key was "NOT yet registered" by a
 * [dev.rubentxu.pipeline.v2.domain.directive.DirectiveDefinition], that "the
 * definition, the resolver and the interpreter do not [exist]", and that "the
 * DSL still throws". All three were false from S3.1 onward and had been read as
 * true by anyone consulting the source: `AgentDirectiveDefinition`,
 * `LocalExecutionTargetResolver`, the `DirectiveExecutionPolicy.Resource`
 * interpretation in `BeforeStageDirectiveEngine` and `StageScope.agent(...)`
 * have all existed since then. The KDoc survived because nothing checks prose
 * against code, and a stale capability claim is the same defect class as a stale
 * capability: it tells a reader to expect behaviour that is not there.
 *
 * What the key resolves to today:
 *  - declared phase: BEFORE_STAGE (a body must not start before its target is resolved)
 *  - declared policy: [dev.rubentxu.pipeline.v2.domain.directive.DirectiveExecutionPolicy.Resource]
 *  - carrier: [dev.rubentxu.pipeline.v2.domain.directive.ExecutionTargetRequirement]
 *  - interpreter: the Resource branch of `BeforeStageDirectiveEngine.interpret`
 *
 * A stage declaring `core.agent` is NOT denied fail-closed by the registry any
 * more. It is decoded, composed, and resolved; `Remote` is the case that is
 * refused, and it is refused at resolution with a diagnostic naming RP-8 rather
 * than by failing to resolve the key.
 */
val AGENT_DIRECTIVE_KEY: DirectiveKey = DirectiveKey("core.agent")
