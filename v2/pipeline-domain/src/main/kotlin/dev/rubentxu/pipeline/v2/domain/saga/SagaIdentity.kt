package dev.rubentxu.pipeline.v2.domain.saga

/**
 * The identity vocabulary of a Saga.
 *
 * ## Why these are types and not strings
 *
 * A Saga's identity travels through three layers — the plugin that declares it, the SDK port
 * that records it, and the durable store that keeps it — and every one of those layers has a
 * different reason to be suspicious of a bare [String]. `sagaId` and `actionId` are keys, not
 * labels: two different values that compare equal are two different obligations, so a key that
 * silently normalises whitespace or case would merge two obligations that must stay apart. A
 * value class makes that class of mistake a compile error rather than a production incident.
 *
 * The constructors validate. They are deliberately *not* lenient: an identifier that arrives
 * blank, padded or containing whitespace is refused at the boundary, where the diagnostic can
 * name the offending value, instead of being normalised into something that could collide with
 * another identifier in a diagnostic later.
 *
 * @see SagaId
 * @see SagaActionId
 * @see CompensationId
 */
private object SagaIdentifier {

    /** Long enough for any sane scope name, short enough that a runaway value is caught. */
    const val MAX_LENGTH: Int = 128

    /**
     * Validates an identifier and returns the trimmed form.
     *
     * Trimming is the ONE normalisation applied, and it is applied symmetrically on both the
     * write and the read path, so a padded identifier still addresses the same obligation.
     * Everything else is refused rather than repaired.
     */
    fun validate(raw: String, label: String): String {
        val trimmed = raw.trim()
        require(trimmed.isNotEmpty()) { "$label must not be blank" }
        require(trimmed.length <= MAX_LENGTH) {
            "$label must be at most $MAX_LENGTH characters, got ${trimmed.length}"
        }
        require(trimmed.none { it.isWhitespace() }) {
            "$label must not contain whitespace: '$trimmed'"
        }
        require(trimmed.none { it.isISOControl() }) {
            "$label must not contain control characters"
        }
        return trimmed
    }
}

/**
 * Identifies the Saga an obligation belongs to.
 *
 * A Saga is a scope of forward actions and their compensating obligations. It is NOT a pipeline,
 * a stage or a transaction: it is the unit that owns compensation semantics, and it is stable for
 * the lifetime of a run.
 */
@JvmInline
value class SagaId private constructor(val value: String) {

    override fun toString(): String = value

    companion object {
        /**
         * A validated Saga id.
         *
         * @throws IllegalArgumentException when [raw] is blank, over-long, or carries whitespace.
         */
        fun of(raw: String): SagaId = SagaId(SagaIdentifier.validate(raw, "SagaId"))

        /** The nullable form, for identifiers that arrive from an untrusted source. */
        fun ofOrNull(raw: String): SagaId? = runCatching { of(raw) }.getOrNull()
    }
}

/**
 * Identifies ONE forward action inside a Saga.
 *
 * Stable by DECLARED KEY, not by position in a list that may change: an action that moves in the
 * source must keep its identity, or a replay would arm a second obligation for the same action
 * under a different name.
 */
@JvmInline
value class SagaActionId private constructor(val value: String) {

    override fun toString(): String = value

    companion object {
        /**
         * A validated action id.
         *
         * @throws IllegalArgumentException when [raw] is blank, over-long, or carries whitespace.
         */
        fun of(raw: String): SagaActionId = SagaActionId(SagaIdentifier.validate(raw, "SagaActionId"))

        /** The nullable form, for identifiers that arrive from an untrusted source. */
        fun ofOrNull(raw: String): SagaActionId? = runCatching { of(raw) }.getOrNull()
    }
}

/**
 * Identifies the COMPENSATION of one action.
 *
 * Distinct from [SagaActionId] on purpose: the compensation is a different thing with a
 * different lifetime (it may run in a later process, after a restart) and a different plan. An
 * action and its compensation share a saga, not an identity.
 */
@JvmInline
value class CompensationId private constructor(val value: String) {

    override fun toString(): String = value

    companion object {
        /**
         * A validated compensation id.
         *
         * @throws IllegalArgumentException when [raw] is blank, over-long, or carries whitespace.
         */
        fun of(raw: String): CompensationId =
            CompensationId(SagaIdentifier.validate(raw, "CompensationId"))

        /** The nullable form, for identifiers that arrive from an untrusted source. */
        fun ofOrNull(raw: String): CompensationId? = runCatching { of(raw) }.getOrNull()
    }
}

/**
 * The fingerprint of a compensation PLAN.
 *
 * ## Why this is not [dev.rubentxu.pipeline.v2.domain.durable.Fingerprint]
 *
 * Both are 64-character lowercase SHA-256 hex, and using one type for both would be shorter. It
 * would also be wrong: an operation fingerprint identifies the INPUT of one attempt, while a plan
 * fingerprint identifies WHAT WOULD BE COMPENSATED. They are compared against different things
 * (an operation against its replayed input, a plan against the plan declared at arm time), and
 * the durable row carries both side by side. Two distinct types make the confusion of one for
 * the other a compile error instead of a runtime acceptance of a foreign digest.
 *
 * A plan fingerprint changes when the compensation changes: the resource it restores, the body it
 * invokes, the version of the codec. That is what makes "same key, different plan" detectable
 * instead of silently overwriting an obligation that was armed under another plan.
 */
@JvmInline
value class SagaPlanFingerprint(val hex: String) {

    init {
        require(hex.length == 64) {
            "SagaPlanFingerprint hex must be exactly 64 characters, got ${hex.length}"
        }
        require(hex.all { it in '0'..'9' || it in 'a'..'f' }) {
            "SagaPlanFingerprint hex must contain only 0-9 and a-f, got: $hex"
        }
    }

    override fun toString(): String = hex

    companion object {
        /** The nullable form, for digests that arrive from an untrusted source. */
        fun ofOrNull(hex: String): SagaPlanFingerprint? =
            runCatching { SagaPlanFingerprint(hex) }.getOrNull()
    }
}

/**
 * A stable reference to an EXTERNAL resource a Saga acts on.
 *
 * This is an identifier, never a credential and never a body: a resource reference says WHICH
 * thing, so ownership can be checked before a destructive compensation, and it never carries a
 * secret, a script or a payload.
 */
@JvmInline
value class ResourceRef private constructor(val value: String) {

    override fun toString(): String = value

    companion object {
        /** Long enough for a qualified resource name, short enough to catch a runaway value. */
        const val MAX_LENGTH: Int = 512

        /**
         * A validated resource reference.
         *
         * @throws IllegalArgumentException when [raw] is blank or over-long.
         */
        fun of(raw: String): ResourceRef {
            val trimmed = raw.trim()
            require(trimmed.isNotEmpty()) { "ResourceRef must not be blank" }
            require(trimmed.length <= MAX_LENGTH) {
                "ResourceRef must be at most $MAX_LENGTH characters, got ${trimmed.length}"
            }
            require(trimmed.none { it.isISOControl() }) {
                "ResourceRef must not contain control characters"
            }
            return ResourceRef(trimmed)
        }

        /** The nullable form, for references that arrive from an untrusted source. */
        fun ofOrNull(raw: String): ResourceRef? = runCatching { of(raw) }.getOrNull()
    }
}

/**
 * A small, opaque reference to the receipt of an OBSERVED external effect.
 *
 * What the receipt actually says depends on the provider, so this type carries only the
 * reference to it — never the provider's payload, never a stdout capture, never a token. The
 * point of the reference is correlation ("this effect, that receipt"), not content.
 */
@JvmInline
value class EffectReceiptRef private constructor(val value: String) {

    override fun toString(): String = value

    companion object {
        /** Long enough for a provider receipt id, short enough to catch a runaway value. */
        const val MAX_LENGTH: Int = 256

        /**
         * A validated receipt reference.
         *
         * @throws IllegalArgumentException when [raw] is blank or over-long.
         */
        fun of(raw: String): EffectReceiptRef {
            val trimmed = raw.trim()
            require(trimmed.isNotEmpty()) { "EffectReceiptRef must not be blank" }
            require(trimmed.length <= MAX_LENGTH) {
                "EffectReceiptRef must be at most $MAX_LENGTH characters, got ${trimmed.length}"
            }
            require(trimmed.none { it.isISOControl() }) {
                "EffectReceiptRef must not contain control characters"
            }
            return EffectReceiptRef(trimmed)
        }

        /** The nullable form, for references that arrive from an untrusted source. */
        fun ofOrNull(raw: String): EffectReceiptRef? = runCatching { of(raw) }.getOrNull()
    }
}
