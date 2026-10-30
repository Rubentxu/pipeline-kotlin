package dev.rubentxu.pipeline.v2.domain.step

import kotlin.reflect.KClass

/**
 * The ONE place where the value of a capability is allowed to be heterogeneous.
 *
 * ## Why a wrapper and not a `Map<StepCapability, Any>`
 * A capability table holds values of different types: an event sink, a shell
 * port, a lock coordinator, a stage identity. Kotlin has no existentials, so a
 * table that holds them all needs a hole of type `Any` SOMEWHERE. The question
 * this type answers is not "can the hole be removed" — it cannot — but "where
 * does the hole live, and what happens when the hole is read".
 *
 * Before this type, the hole was the table itself, and the reader of a
 * capability wrote `as? T`. That cast is unchecked in the only sense that
 * matters at runtime: `T` is erased, so the cast could not verify the type and
 * only verified that the value was not null. A mismatch therefore surfaced
 * inside the CONSUMER's handler, as a `ClassCastException` raised by the
 * consumer's own assignment — a fact about the consumer's bytecode, reported
 * against the wrong owner, with the reason ("this value is not a
 * `WorkspaceIdentity`") hidden behind a fact about casts.
 *
 * Here the hole is a named value object with exactly one reader, and that
 * reader CHECKS: the declared type is compared against the value's own type
 * before the value is handed over. A mismatch becomes a
 * [CapabilityRefusal.Incompatible], which names the capability, the declared
 * type and the type that was actually bound.
 *
 * ## Why the value is compared, not cast
 * The check uses [KClass.isInstance] and [java.lang.Class.cast]: a type test
 * that fails is refused before the cast happens, and the cast that follows is
 * safe by construction. No `as` and no unchecked-cast suppression appears
 * anywhere in this file or in the seam that uses it, so the compiler can see
 * that the value handed to a consumer really has the declared type.
 *
 * ## Why identity is observable
 * [holdsSameInstanceAs] exists because "the same capability bound twice" is a
 * question about IDENTITY, not about equality: a plugin contributing the very
 * same instance the runtime already granted is not a conflict, while a
 * different instance under the same key is. Comparing by identity answers the
 * question the runtime actually has to ask, without asking the value itself
 * whether two instances are equal.
 */
class CapabilitySlot private constructor(private val value: Any) {

    /**
     * The bound value, read against the type the consumer declared.
     *
     * Fails closed with a [CapabilityRefusedException] when the bound value is
     * not of the declared type. The refusal names the capability, the declared
     * type and the type that was bound, so the disagreement is attributable
     * without reading the consumer's bytecode.
     */
    fun <T : Any> read(key: StepCapability, type: KClass<T>): T {
        if (!type.isInstance(value)) {
            throw CapabilityRefusedException(
                CapabilityRefusal.Incompatible(
                    key = key,
                    expected = type,
                    actual = value::class,
                ),
            )
        }
        return type.java.cast(value)
    }

    /** Whether [other] holds the very same instance this slot holds. */
    fun holdsSameInstanceAs(other: CapabilitySlot): Boolean = other.value === value

    override fun toString(): String = "CapabilitySlot(${value::class.simpleName ?: "an unnamed type"})"

    companion object {
        /** Binds [value] as the capability payload for one key. */
        fun <T : Any> of(value: T): CapabilitySlot = CapabilitySlot(value)
    }
}
