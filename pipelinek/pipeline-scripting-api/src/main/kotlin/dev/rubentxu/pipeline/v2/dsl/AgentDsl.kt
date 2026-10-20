package dev.rubentxu.pipeline.v2.dsl

import dev.rubentxu.pipeline.v2.domain.StageDirective
import dev.rubentxu.pipeline.v2.domain.directive.ExecutionTargetRequirement
import dev.rubentxu.pipeline.v2.domain.directive.ExecutionTargetRequirementCodec
import dev.rubentxu.pipeline.v2.domain.directivekey.AGENT_DIRECTIVE_KEY

/**
 * S3.1 — the encoded `core.agent` stage directive for [requirement].
 *
 * A total, pure construction step, for the same reason `appendWhenGate` is: it
 * encodes a DECLARED requirement and nothing else. No I/O, no clock, no
 * manufactured runtime value. The DSL describes; the resolver interprets.
 *
 * The key comes from `...domain.directivekey` rather than being written here, so
 * the script and the runtime cannot invent different names for the same
 * directive — the same reasoning that puts `WHEN_DIRECTIVE_KEY` in the domain
 * rather than in either module.
 */
internal fun appendAgentRequirement(
    requirement: ExecutionTargetRequirement,
): StageDirective = StageDirective(
    key = AGENT_DIRECTIVE_KEY.value,
    encodedArguments = ExecutionTargetRequirementCodec.encode(requirement),
)
