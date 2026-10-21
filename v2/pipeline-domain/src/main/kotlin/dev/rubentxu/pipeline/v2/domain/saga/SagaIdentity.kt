package dev.rubentxu.pipeline.v2.domain.saga

import java.util.Locale

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
 *
 * The digest is DERIVED, not chosen: [Companion.compute] derives it from the plan's
 * [canonicalFormOf][Companion.canonicalFormOf], whose version is
 * [CANONICAL_FORM_VERSION][Companion.CANONICAL_FORM_VERSION]. A fingerprint that is not derived
 * from a published form is a fingerprint two implementations will not agree on, and the whole
 * point of this type is that they do.
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
        /**
         * The version of the CANONICAL FORM this fingerprint is derived from.
         *
         * ## Why the version is a published constant and not a string in the code
         *
         * The form is a CONTRACT with whoever computes a fingerprint outside this repository: a
         * consumer that arms a compensation has to produce the same digest the engine will
         * compare against, or the engine will read its own promise as a different plan and
         * refuse it. A consumer that cannot know WHICH form it is reproducing has to guess,
         * and two consumers that guess differently produce two digests for one plan.
         *
         * So the version travels WITH the form. When the form changes, this constant changes
         * with it, and a consumer that still implements the old one says so out loud instead
         * of quietly disagreeing.
         */
        const val CANONICAL_FORM_VERSION: String = "saga-v1"

        /**
         * The CANONICAL FORM of a plan: the text the fingerprint is derived from.
         *
         * ## The form, exactly
         *
         * Eight fields, in this order, separated by `|`:
         *
         * ```text
         * <version>|<sagaId>|<actionId>|<compensationId>|<resourceRef>|<forwardSlot>|<compensateSlot>|<kind>
         * ```
         *
         * The version is [CANONICAL_FORM_VERSION]. The other seven are the plan itself: which
         * Saga the action belongs to, which action it is, which compensation undoes it, what
         * resource it touches, WHICH BODIES run it, and what kind of compensation it is.
         *
         * The bodies are in the form because the fingerprint is the only place the engine
         * records them: the durable obligation carries the digest, not the bodies, and the
         * digest is what lets a later process tell "the same promise" from "the same key with
         * another plan under it".
         *
         * ## Why a field containing `|` is refused
         *
         * The separator is not escaped, so a field that carries one would let two DIFFERENT
         * plans spell the same text — and then the same digest, and the engine would accept
         * the second plan as the first. A silent collision is worse than a refusal, so the
         * form is only defined for fields that do not carry the separator, and a field that
         * does is refused HERE, by name, before any digest is computed.
         *
         * Refusing here and not in the identifier types is deliberate: the identifiers stay
         * what they were, and the rule that matters — "this text means one plan" — is enforced
         * where the text is built.
         */
        fun canonicalFormOf(sagaId: SagaId, plan: SagaActionPlan): String {
            val fields = listOf(
                "sagaId" to sagaId.value,
                "actionId" to plan.actionId.value,
                "compensationId" to plan.compensationId.value,
                "resourceRef" to plan.resourceRef.value,
                "forwardSlot" to plan.forwardSlot.value,
                "compensateSlot" to plan.compensateSlot.value,
                "kind" to plan.kind.name,
            )
            fields.forEach { (name, value) ->
                require(SEPARATOR !in value) {
                    "SagaPlanFingerprint canonical form: $name must not contain '$SEPARATOR', " +
                        "because the separator is not escaped and two different plans would " +
                        "spell the same text. Got: '$value'"
                }
            }
            return (listOf(CANONICAL_FORM_VERSION) + fields.map { it.second }).joinToString(SEPARATOR)
        }

        /**
         * The fingerprint of a plan, derived from its canonical form.
         *
         * The digest is SHA-256 over the UTF-8 bytes of [canonicalFormOf], rendered as
         * lowercase hexadecimal. Both halves are part of the contract: an implementer that
         * hashed the same text with another algorithm, or rendered the bytes in another case,
         * would produce a different digest for the same plan.
         *
         * The rendering is pinned to [Locale.ROOT], so it does not depend on the
         * operator's locale. That is the guarantee, and it is STRUCTURAL: the locale
         * is named, not assumed. The rendering would happen to be the same in any
         * locale today — hexadecimal digits are not localized, only the decimal
         * conversion is — but a promise that rests on a property of
         * `java.util.Formatter` is a promise a JDK can break underneath it, and this
         * one does not have to be. A digest that moved with the locale would be a
         * promise that changed shape on a different machine.
         */
        fun compute(sagaId: SagaId, plan: SagaActionPlan): SagaPlanFingerprint {
            val canonical = canonicalFormOf(sagaId, plan)
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }
            return SagaPlanFingerprint(digest)
        }

        /** The separator between the fields of the canonical form. */
        private const val SEPARATOR: String = "|"

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
