package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.step.DEFAULT_WAIT_UNTIL_INITIAL_RECURRENCE_PERIOD_MS
import dev.rubentxu.pipeline.v2.domain.step.DEFAULT_WAIT_UNTIL_MAX_BACKOFF_MS

/**
 * Typed payload of the `core.waitUntil` **block** carrier.
 *
 * ## Why this is not `WaitUntilInput`
 *
 * `WaitUntilInput` ([CoreWaitUntilStep.kt]) types the **registry invocation**
 * carrier: `encodedInput`, decoded by the Step's own `StepCodec`, whose
 * discriminator is `kind="waitUntil"`. The `waitUntil { }` construct is a block;
 * it lowers to `BlockStepNode` and its body-carrying IR is a **different**
 * carrier with a different discriminator, `kind="waitUntilBlock"`, owned by
 * [CoreWaitUntilWireCodec].
 *
 * The two types have the same fields today, and that is not the point. The
 * registry decoder fails closed when the discriminator is not
 * `kind="waitUntil"`; if both carriers shared one type, nothing would stop a
 * block payload being handed to the registry decoder and the refusal would
 * surface late, as a `require` at decode time rather than at the type level.
 * Distinct types make that confusion unrepresentable (AGENTS.md §8).
 *
 * Not annotated `@Serializable` on purpose: `pipeline-application` does not
 * apply the kotlinx-serialization compiler plugin, so a generated
 * `.serializer()` would not exist. The codec in [CoreWaitUntilWireCodec] is
 * written out explicitly, exactly as [CoreLockInput] and [CoreInputInput] are.
 *
 * ## What these fields are for
 *
 * All three fields are DECODED INPUT. The declared
 * [dev.rubentxu.pipeline.v2.domain.step.BodyExecutionPolicy.WaitUntilShape] is the Step
 * KIND's default; a call site that asks for something else says so here, and the projection
 * reads what this carrier says.
 *
 * That sentence was false until 2026-10-18, when this KDoc claimed the first two fields were
 * STRUCTURE and that "the structural projection reads the declared shape, not this carrier".
 * The projection did read the shape — for the cadence — so `StageScope.waitUntil(
 * initialRecurrencePeriod = 7)` compiled, was encoded, and reached neither polling branch.
 * A dead semantic parameter (SEMANTIC CONSTITUTION §2), and a documented one.
 *
 * [maxBackoffMs] is the field that disproved the claim: it is RUNTIME DATA — a per-block
 * ceiling the shape cannot express — and before it existed the only code that could set it
 * was a regex over the raw payload whose result reached exclusively the INLINE polling
 * branch; the durable branch, which is the one production reaches, polled at
 * [DEFAULT_WAIT_UNTIL_MAX_BACKOFF_MS] no matter what the payload said. The projection now
 * reads all three here, through this carrier, so both branches get the same numbers from
 * one authority.
 */
data class CoreWaitUntilInput(
    val initialRecurrencePeriod: Long = DEFAULT_WAIT_UNTIL_INITIAL_RECURRENCE_PERIOD_MS,
    val quiet: Boolean = false,
    val maxBackoffMs: Long = DEFAULT_WAIT_UNTIL_MAX_BACKOFF_MS,
)
