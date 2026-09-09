package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue

/**
 * Generic execution result for a registry-routed Step (LB-02 / G3-A1).
 *
 * Carries the two responsibilities the boundary owns end-to-end, separately,
 * so the durable layer can persist them independently:
 *
 *  - [outcome]: the closed [StepOutcome] algebra (Success | Failure | ...).
 *    This is what [CommonExecutionBoundary.execute] already returns today.
 *  - [encodedOutput]: the typed `O` produced by the registry Step handler,
 *    encoded through `outputCodec.encode(O)` and held under a
 *    [EncodedStepValue] (a value class over `String`; no `Any` crosses the
 *    boundary). `null` when the handler returns nothing durable-visible
 *    (e.g. `EchoInput -> Unit`).
 *
 * The boundary NEVER persists either of these into a journal row, into a
 * process, or onto disk; doing so would couple execution back to durability.
 * Per the durable spine: **execution produces data; the durable layer
 * decides how/when it is persisted.**
 *
 * The boundary keeps them together so the durable coordinator can read
 * them at the same call site (no re-invocation of the handler, no race
 * with another step's execution). The pair is the canonical shape of a
 * registry-only execution result; the legacy compatibility boundary carries
 * only [outcome] (no `O` exists there).
 *
 * @see docs/v2/00-context/LB02_TYPED_OUTPUT_DECISION.md
 */
data class RegistryExecutionResult(
    val outcome: StepOutcome,
    val encodedOutput: EncodedStepValue?,
)
