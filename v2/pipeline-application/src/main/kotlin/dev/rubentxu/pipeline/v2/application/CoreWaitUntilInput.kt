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
 * [initialRecurrencePeriod] and [quiet] are STRUCTURE: they name the cadence of
 * the declared [dev.rubentxu.pipeline.v2.domain.step.BodyExecutionPolicy.WaitUntilShape],
 * which is a property of the Step KIND. They are emitted for the wire to be
 * whole, and the structural projection reads the declared shape, not this carrier.
 *
 * [maxBackoffMs] is the exception, and the reason this carrier earns its keep:
 * it is RUNTIME DATA — a per-block ceiling that the shape cannot express — so it
 * has to travel in the payload and be decoded at the projection. Before this field
 * existed, the only code that could set it was a regex over the raw payload whose
 * result reached exclusively the INLINE polling branch; the durable branch, which is
 * the one production reaches, polled at [DEFAULT_WAIT_UNTIL_MAX_BACKOFF_MS] no
 * matter what the payload said. The projection now reads it here, through this
 * carrier, so both branches get the same number from one authority.
 */
data class CoreWaitUntilInput(
    val initialRecurrencePeriod: Long = 1L,
    val quiet: Boolean = false,
    val maxBackoffMs: Long = DEFAULT_WAIT_UNTIL_MAX_BACKOFF_MS,
)

/**
 * The backoff ceiling a `core.waitUntil` block polls with when its payload does not
 * declare one.
 *
 * One authority: this carrier's default argument, [CoreWaitUntilWireCodec]'s decoder
 * and the structural projection's absent-key branch all read it, so none of the three
 * can drift from the others. It lives beside the carrier rather than beside its reader
 * because the WIRE decides what "undeclared" means — a reader that invented its own
 * default would be a second authority for the same question.
 */
internal const val DEFAULT_WAIT_UNTIL_MAX_BACKOFF_MS: Long = 60_000L