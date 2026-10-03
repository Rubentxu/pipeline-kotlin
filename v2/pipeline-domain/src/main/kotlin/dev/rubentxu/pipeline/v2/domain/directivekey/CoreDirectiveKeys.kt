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
 * It is NOT yet registered by a [dev.rubentxu.pipeline.v2.domain.directive.DirectiveDefinition].
 * The carrier exists (see `ExecutionTargetRequirement`); the definition, the
 * resolver and the interpreter do not. A stage declaring `core.agent` is
 * therefore denied fail-closed by the registry, which is the correct behaviour
 * for a key with no interpreter and the reason the DSL still throws.
 */
val AGENT_DIRECTIVE_KEY: DirectiveKey = DirectiveKey("core.agent")
