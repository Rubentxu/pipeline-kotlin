package dev.rubentxu.pipeline.v2.application

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
 * Today they are projected and read by nobody in production: the structural
 * decoder takes the waitUntil SHAPE from the declared descriptor policy
 * ([dev.rubentxu.pipeline.v2.domain.step.BodyExecutionPolicy.WaitUntilShape]),
 * which is a property of the Step KIND, not of runtime data. This type exists to
 * give the block carrier one owner and one decoder, so that the typed reader
 * these fields need can be added without another hand-written format appearing
 * somewhere new.
 */
data class CoreWaitUntilInput(
    val initialRecurrencePeriod: Long = 1L,
    val quiet: Boolean = false,
)