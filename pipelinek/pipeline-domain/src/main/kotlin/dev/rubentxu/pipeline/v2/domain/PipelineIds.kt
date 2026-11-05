package dev.rubentxu.pipeline.v2.domain

import dev.rubentxu.pipeline.v2.domain.digest.Sha256
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Stable identity of one pipeline definition. */
@JvmInline
@Serializable
value class DefinitionId(val value: String) {
    init {
        require(value.isNotBlank()) { "DefinitionId value must not be blank" }
    }
}

/**
 * Identity of one invocation of a pipeline definition.
 *
 * A run identity is also a NAME in the durable plane: the lease that grants its
 * publishing authority, the directory that holds its artefacts. So the
 * identity owns the rule that decides whether it can carry that name, and the
 * stores that persist it ask the identity instead of keeping a copy of the
 * rule.
 */
@JvmInline
@Serializable
value class RunId(val value: String) {
    init {
        require(value.isNotBlank()) { "RunId value must not be blank" }
    }

    /**
     * The authority on what may stand as a run identity.
     *
     * A border that needs to know whether a raw string names a run, or may
     * name a durable location, asks here instead of keeping its own copy of
     * the rule. Two authorities answer the same question, and they drift
     * apart the first time one of them refuses a value the other accepted.
     *
     * The alphabet that decides the answer is private, so a store cannot
     * read it and build a second one from it.
     */
    companion object {

        /**
         * The identity [raw] names, or `null` when it names none.
         *
         * A blank value is not an identity: it names no run and no durable
         * location, and the product refuses it wherever it enters. This factory
         * is the ONE place that answers the question, so a border asks the
         * identity instead of keeping its own copy of the rule — the same shape
         * the durable plane's owner id has.
         *
         * The value is kept VERBATIM: no trimming, no folding, no escaping. A
         * transformation here would be a second spelling of the identity, and
         * the durable name IS the identity.
         */
        fun of(raw: String): RunId? = if (raw.isBlank()) null else RunId(raw)

        /**
         * The characters an identity may use to name a durable location.
         *
         * The alphabet is the product's own: a generated run id is a lowercase
         * UUID, so every identity the product creates already lives inside it.
         * What it admits is exactly what a path segment can carry without being
         * rewritten.
         *
         * It admits ASCII lowercase only, because a durable name must not
         * depend on the filesystem's case distinction. A name carrying an
         * uppercase letter names the SAME location as its lowercase twin on a
         * volume that folds case — APFS by default, exFAT — and the durable
         * name IS the identity: two identities differing only in case would
         * then be one location, and the reader of one would be handed the
         * facts of the other. ASCII lowercase is left unchanged by the case
         * folding of every filesystem and by every Unicode normalisation, so
         * a name this alphabet admits is the same name everywhere.
         */
        private val NAMEABLE = Regex("""[a-z0-9._-]+""")

        /**
         * Whether [runId] can name a durable location.
         *
         * The question belongs to the identity and not to the store that happens
         * to persist it: a store that answers it with its own copy of the
         * alphabet is a second authority, and two authorities disagree.
         */
        fun isNameable(runId: String): Boolean = NAMEABLE.matches(runId)

        /**
         * The key [runId] takes in the durable plane, or `null` when it cannot
         * name one.
         *
         * The key IS the identity, and that is the whole point. A key that were a
         * transformation of it — folded by case or by substitution, escaped or
         * truncated — could give two different identities the same key, and then
         * two runs would share one lease: one of them would be refused as
         * "already owned" and the other would take the lease over, advancing
         * the fencing token and fencing out a publisher that had done nothing
         * wrong.
         *
         * So an identity that cannot name a location has NO key: it is refused
         * where it enters the durable plane, rather than folded onto somebody
         * else's.
         */
        fun fileKey(runId: String): String? = runId.takeIf { isNameable(it) }

        /**
         * Why [raw] cannot name a durable location.
         *
         * The identity owns the rule AND the sentence that states it. A store
         * that phrases the rule with its own copy of the words is a second
         * authority, and two authorities drift into saying different things
         * about the same refusal — one of them saying it about a run id that
         * the other one accepted.
         *
         * Ask this only about a value that cannot: whether it can is
         * [isNameable]'s answer, and a caller that already knows the answer
         * does not need the question asked twice.
         *
         * A blank value is answered by the same sentence: it is not a run
         * that lost its name, it never had one.
         */
        fun unnameableReason(raw: String): String =
            "run id '$raw' cannot name a durable location"
    }
}

/**
 * External-supplied correlation token.
 * A [CorrelationId] is the identity an EXTERNAL invoker (CI, Fabric, a
 * script) assigns to a single invocation. PipelineK preserves it through
 * the durable state and emits it in:
 *  - The `RunCorrelated` event.
 *  ## Where it lands in the timeline, and why it is stated rather than inferred
 *  `RunCorrelated` is the FIRST event of a run: it precedes
 *  `CompilationStarted`, which in turn precedes `RunStarted`. That is a
 *  promise this document now makes explicitly, because the previous wording —
 *  "before the first `RunStarted`" — was true but under-determined. It left the
 *  position relative to compilation unstated, and three UATs filled the gap by
 *  asserting an order no document had ever promised.
 *  The reason it goes first is not tidiness. `RunCorrelated` is emitted at the
 *  moment the run acquires a durable identity — after the execution lease is
 *  held, and together with the `last-run.json` control record that `--resume`
 *  and the ownership arbitration both read. Emitting it after compilation would
 *  mean a script that fails to compile leaves no correlation record at all: the
 *  invocation would disappear from the control dir with nothing explaining it
 *  and nothing to resume. The order is what makes an early failure observable.
 *  - The `LaunchReceipt` JSON envelope on stdout.
 *  - The `last-run.json` file in the control dir.
 * A [CorrelationId] is NOT a [RunId] — two concurrent invocations with
 * the same [CorrelationId] get different [RunId]s, because the
 * [RunId] remains the authority. The [CorrelationId] is the
 * invoker-side join key: it groups runs a single CI job initiated, or
 * a single Fabric workflow executes. What an external invoker may do with
 * the runs it correlates is the contract the root README states under
 * "Reading a run from outside".
 * The CLI generates a UUIDv4 when the invoker supplies none;
 * `--correlation-id` is how the invoker supplies one. The value is opaque
 * to the product: only the non-blank invariant is enforced.
*/
@JvmInline
@Serializable
value class CorrelationId(val value: String) {
    init {
        require(value.isNotBlank()) { "CorrelationId value must not be blank" }
    }
}

/** Stable identity of one stage within a compiled pipeline definition. */
@JvmInline
@Serializable
value class StageId(val value: String) {
    init {
        require(value.isNotBlank()) { "StageId value must not be blank" }
    }
}

/** Stable identity of one step node within a compiled pipeline definition. */
@JvmInline
@Serializable
value class StepId(val value: String) {
    init {
        require(value.isNotBlank()) { "StepId value must not be blank" }
    }
}

/**
 * An identifier in the plugin's own namespace.
 *
 * Two things live in that namespace, and they share this type because they share the namespace:
 *
 * - the **kind** of a step — what runs. It is what the engine dispatches by, and it is
 *   [StepNode.pluginStepId].
 * - the **name of a slot** — the name a plugin gives to one of the steps of its own body, so
 *   that two steps of the same kind can be told apart. It is [StepNode.slot], and it is what a
 *   plugin asks for.
 *
 * They answer different questions and they are different fields; the type says **where** the name
 * comes from, not what it is used for. Sharing one type is what lets a plugin address its own
 * bodies without the engine and the plugin having to agree on two vocabularies.
 */
@JvmInline
@Serializable
value class PluginStepId(val value: String) {
    init {
        require(value.isNotBlank()) { "PluginStepId value must not be blank" }
    }
}

/** Zero-based attempt identity attached to a runtime execution context. */
@JvmInline
@Serializable
value class AttemptId(val value: Int) {
    init {
        require(value >= 0) { "AttemptId value must not be negative" }
    }
}

/** Stable runtime operation identity, separate from definition-local node IDs. */
@JvmInline
@Serializable
value class OperationId(val value: String) {
    init {
        require(value.isNotBlank()) { "OperationId value must not be blank" }
    }
}

/**
 * Segment of a block's body path, encoding the child index and plugin step ID
 * For length-prefix body dispatch
 * Format: "{index}:{pluginStepId.value}"
*/
@JvmInline
@Serializable
value class BlockSegment(val encoded: String) {
    init { require(encoded.isNotBlank()) { "BlockSegment encoded must not be blank" } }

    constructor(index: Int, pluginStepId: PluginStepId) : this("${index}:${pluginStepId.value}")
}

/** Generates a new identity for a pipeline invocation. */
fun interface RunIdGenerator {
    fun next(): RunId
}

/**
 * The facts a definition's identity is derived from.
 *
 * The identity is a pure function of these three: the same facts always
 * yield the same identity, and a change in any of them yields a different
 * one. The source is read canonically — a leading byte-order mark is
 * dropped and the line endings are folded — so a file that only changed its
 * line endings keeps the identity it had.
 *
 * The source and the compatibility version must not be blank, and no
 * semantic input may carry a blank key. The order of the semantic inputs
 * does not matter: they are sorted before the identity is derived.
 */
data class DefinitionIdentityInput(
    val source: String,
    val compatibilityVersion: String,
    val semanticInputs: Map<String, String> = emptyMap(),
) {
    init {
        require(canonicalizeSource(source).isNotBlank()) { "source must not be blank" }
        require(compatibilityVersion.isNotBlank()) { "compatibilityVersion must not be blank" }
        require(semanticInputs.keys.none(String::isBlank)) { "semanticInputs keys must not be blank" }
    }
}

/**
 * Deterministic identity functions used by the local-first domain.
 * The definition algorithm intentionally preserves the legacy format so that
 * existing durable data remains addressable while callers migrate to the
 * typed contract. Invocation identity is deliberately not derived here:
 * repeated invocations of one definition must be distinguishable.
*/
object DeterministicIdGenerator {
    fun definitionId(input: DefinitionIdentityInput): DefinitionId {
        val payload = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(TYPED_DEFINITION_MAGIC)
                output.writeInt(TYPED_DEFINITION_FORMAT_VERSION)
                output.writeLengthPrefixed(canonicalizeSource(input.source))
                output.writeLengthPrefixed(input.compatibilityVersion)
                output.writeInt(input.semanticInputs.size)
                input.semanticInputs.toSortedMap().forEach { (key, value) ->
                    output.writeLengthPrefixed(key)
                    output.writeLengthPrefixed(value)
                }
            }
            bytes.toByteArray()
        }

        // The typed definition payload is a BINARY DataOutputStream, not text, so it goes
        // through ofBytes rather than ofText. Bytes unchanged: the previous code hashed
        // exactly this array.
        return DefinitionId(Sha256.ofBytes(payload))
    }

    /**
     * Derives the canonical definition identity from the legacy source tuple.
     * The 36-character SHA-256 prefix is part of the compatibility contract.
*/
    fun definitionId(scriptPath: String, scriptContent: String): DefinitionId {
        require(scriptPath.isNotBlank()) { "scriptPath must not be blank" }
        return DefinitionId(legacyDigest("$scriptPath|$scriptContent"))
    }

    /**
     * The 36-character legacy definition digest.
     * The truncation is the compatibility contract, not a shortcut, and it is preserved here
     * byte for byte: [Sha256] returns the same 64-character lowercase hex the local `toHex`
     * produced, and `.take(36)` still cuts the same prefix.
*/
    private fun legacyDigest(input: String): String = Sha256.ofText(input).take(36)

    private const val TYPED_DEFINITION_FORMAT_VERSION = 1
    private val TYPED_DEFINITION_MAGIC = "pipeline-definition-identity\u0000".toByteArray(Charsets.UTF_8)
}

private fun canonicalizeSource(source: String): String =
    source.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')

private fun DataOutputStream.writeLengthPrefixed(value: String) {
    val encoded = value.toByteArray(Charsets.UTF_8)
    writeInt(encoded.size)
    write(encoded)
}
