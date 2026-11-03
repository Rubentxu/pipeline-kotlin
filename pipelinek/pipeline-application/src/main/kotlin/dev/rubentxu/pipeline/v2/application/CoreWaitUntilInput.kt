package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.step.DEFAULT_WAIT_UNTIL_INITIAL_RECURRENCE_PERIOD_MS
import dev.rubentxu.pipeline.v2.domain.step.DEFAULT_WAIT_UNTIL_MAX_BACKOFF_MS

/**
 * Typed payload of the `core.waitUntil` **block** carrier.
 * ## Why this is not `WaitUntilInput`
 * `WaitUntilInput` ([CoreWaitUntilStep.kt]) types the **registry invocation**
 * carrier: `encodedInput`, decoded by the Step's own `StepCodec`, whose
 * discriminator is `kind="waitUntil"`. The `waitUntil { }` construct is a block;
 * it lowers to `BlockStepNode` and its body-carrying IR is a **different**
 * carrier with a different discriminator, `kind="waitUntilBlock"`, owned by
 * [CoreWaitUntilWireCodec].
 * The two types have the same fields today, and that is not the point. The
 * registry decoder fails closed when the discriminator is not
 * `kind="waitUntil"`; if both carriers shared one type, nothing would stop a
 * block payload being handed to the registry decoder and the refusal would
 * surface late, as a `require` at decode time rather than at the type level.
 * Distinct types make that confusion unrepresentable (AGENTS.md).
 * Not annotated `@Serializable` on purpose: `pipeline-application` does not
 * apply the kotlinx-serialization compiler plugin, so a generated
 * `.serializer()` would not exist. The codec in [CoreWaitUntilWireCodec] is
 * written out explicitly, exactly as [CoreLockInput] and [CoreInputInput] are.
 * ## What these fields are for
 * All three fields are DECODED INPUT. The declared
 * [dev.rubentxu.pipeline.v2.domain.step.BodyExecutionPolicy.WaitUntilShape] is the Step
 * KIND's default; a call site that asks for something else says so here, and the projection
 * reads what this carrier says.
 * Every field is read through this carrier, so the inline branch and the durable
 * branch get the same numbers from one authority. A field the code declares and
 * no branch reads would be a lie no reader can detect, which is why the
 * projection reads every field here rather than taking the shape's defaults for
 * the ones it ignores.
 * [maxBackoffMs] is RUNTIME DATA — a per-block ceiling the shape cannot express —
 * so it has to travel in the payload rather than in the declared shape. The
 * durable branch polls at [DEFAULT_WAIT_UNTIL_MAX_BACKOFF_MS] when the payload
 * says nothing.
*/
data class CoreWaitUntilInput(
    val initialRecurrencePeriod: Long = DEFAULT_WAIT_UNTIL_INITIAL_RECURRENCE_PERIOD_MS,
    val maxBackoffMs: Long = DEFAULT_WAIT_UNTIL_MAX_BACKOFF_MS,
)
