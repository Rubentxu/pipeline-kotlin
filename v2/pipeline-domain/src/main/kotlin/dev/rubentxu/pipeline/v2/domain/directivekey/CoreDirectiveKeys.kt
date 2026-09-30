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
