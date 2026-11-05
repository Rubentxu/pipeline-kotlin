package dev.rubentxu.pipeline.v2.runtime.telemetry

import dev.rubentxu.pipeline.v2.domain.RunId

/**
 * The version of the OpenTelemetry semantic conventions this product speaks.
 *
 * The CI/CD conventions are **Release Candidate**: their attribute names may
 * still move between two versions of the specification, so the version they
 * were taken from is part of what this product promises. A consumer that
 * finds a version it does not know treats the export as historical — it
 * never guesses a name.
 *
 * The version is pinned, not tracked: it moves only when a product decision
 * moves it, never because an upstream release happened.
 *
 * The pin is this product's half of the agreement, and the agreement is
 * read HERE: the exported document states the schema it speaks
 * (`telemetry.schema`), and the upstream conventions version is not part
 * of that document. A consumer that needs to know which conventions an
 * export speaks therefore reads the pin from the product, not from the
 * payload — so the product that pins a version it cannot show is honest
 * about it here rather than leaving a collector to guess.
 */
const val OTEL_SEMCONV_CICD_VERSION: String = "1.44.0"

/**
 * The stability of the pinned conventions.
 *
 * Written down rather than assumed, because a candidate and a stable
 * contract are different promises: a candidate's names may still move, and
 * the product that pins one says so instead of letting the consumer find out
 * from a rename.
 */
const val OTEL_SEMCONV_CICD_STATUS: String = "release-candidate"

/**
 * The namespace of the product's own signal and metric names.
 *
 * The upstream `cicd.` names describe the CI/CD system's vocabulary; the
 * engine's own vocabulary is the engine's, and it lives here. A name under
 * this namespace is PipelineK's to change; a name under `cicd.` is the
 * specification's.
 */
const val PIPELINEK_FIELD_NAMESPACE: String = "pipelinek."

/**
 * The counter where the historical projection counts an interval it refuses
 * to measure.
 *
 * The name lives here because the anomaly is part of what telemetry may
 * say: a projection that withheld a duration without counting it would look
 * exactly like one where nothing contradicted itself.
 */
const val TELEMETRY_INVALID_INTERVAL_COUNTER: String = "pipelinek.invalid_interval_total"

/**
 * The identities the product knows, and the ONE authority of each.
 *
 * Every one of them exists already; what was missing was the statement of
 * who owns each and what it may be used for. Two of them — the run id and
 * the correlation id — are minted by different authorities (the engine and
 * the invoker), and the rest are minted by a third (the lease), a fourth
 * (the historical projection) or a fifth (the W3C propagator). An identity
 * without a named authority is an identity two parts of the product may
 * mint, and two parts minting the same identity is a second authority.
 */
enum class TelemetryIdentityKind {
    /** The durable identity of one execution of a pipeline. */
    RUN_ID,

    /** The join key the invoker assigns to one invocation. */
    CORRELATION_ID,

    /** The identity of one effect in the durable journal. */
    OPERATION_ID,

    /** The identity of one attempt of an operation. */
    ATTEMPT_ID,

    /** The token that authorizes writing under a lease. */
    FENCING_TOKEN,

    /** The trace id the historical projection derives from the run id. */
    HISTORICAL_TRACE_ID,

    /** The W3C trace id of a live execution. */
    LIVE_TRACE_ID,

    /** The W3C span id of a live span. */
    LIVE_SPAN_ID,
}

/**
 * Who mints an identity.
 *
 * The owner is the part that cannot be faked: an identity whose owner is
 * the invoker may not be minted by the engine, and an identity whose owner
 * is the lease may not be minted by anybody else. This is what keeps the
 * durable identities and the telemetry identities apart.
 */
enum class TelemetryIdentityOwner {
    /** The engine mints it. */
    ENGINE,

    /** The invoker supplies it. */
    INVOKER,

    /** The durable lease issues it. */
    LEASE,

    /** The historical projection derives it. */
    PROJECTION,

    /** The W3C propagator carries it in from the boundary. */
    PROPAGATOR,
}

/**
 * What an identity may be used for.
 *
 * The vocabulary is small on purpose: an identity is either something that
 * JOINS facts, something that makes an effect IDEMPOTENT, something that
 * authorizes a WRITE, or something that describes CAUSALITY between
 * distributed parts. An identity that could be used for several of those
 * would be a key that joins two unrelated things by accident.
 */
enum class TelemetryIdentityUse {
    /** Relate facts about the same execution. */
    JOIN_RUN,

    /** Relate launches the same invoker made. */
    JOIN_INVOCATION,

    /** Identify one effect in the journal, so a retry does not repeat it. */
    IDEMPOTENCY,

    /** Authorize a write under a lease. */
    WRITE_AUTHORITY,

    /** Describe the distributed causality of an invocation or attempt. */
    CAUSALITY,

    /** Correlate a historical export with the run it describes. */
    HISTORICAL_JOIN,
}

/**
 * Where an identity may appear in what leaves the process.
 *
 * The slots are exclusive on purpose. The historical trace id and the live
 * W3C trace id are both "the trace of this run", and a slot that admitted
 * both would let one of them be published as the other — which is the
 * conflation this table exists to prevent.
 */
sealed interface TelemetryTravel {
    /** It never leaves: the identity stays inside the product. */
    data object Nowhere : TelemetryTravel

    /** It travels as the attribute under this key. */
    data class Attribute(val key: String) : TelemetryTravel

    /** It travels as the export's own trace field: the derived, historical trace. */
    data object HistoricalTraceField : TelemetryTravel

    /** It travels as the W3C trace id of a live trace. */
    data object LiveTraceField : TelemetryTravel

    /** It travels as the W3C span id of a live span. */
    data object LiveSpanField : TelemetryTravel
}

/**
 * One row of the identity table: who owns an identity, what it may be used
 * for, what it may never be used for, what it is minted from, and where it
 * may travel.
 *
 * The two use sets are both present, and both are read: the forbidden set
 * says what an identity must NOT become, and that is the half that keeps a
 * convenient shortcut from becoming the contract.
 */
data class TelemetryIdentityRule(
    val kind: TelemetryIdentityKind,
    val owner: TelemetryIdentityOwner,
    val allowedUses: Set<TelemetryIdentityUse>,
    val forbiddenUses: Set<TelemetryIdentityUse>,
    val derivedFrom: TelemetryIdentityKind?,
    val travel: TelemetryTravel,
)

/**
 * What a key of the signal vocabulary carries.
 *
 * The vocabulary is closed: a key that is not named here does not travel,
 * and naming one is a product decision rather than a configuration one. That
 * is why the step names, the commands, the paths and the tokens are absent —
 * not because they were filtered out on the way, but because they were never
 * in the list.
 */
enum class TelemetryVocabularyRole {
    /** The key carries an identity, and only that identity. */
    IDENTITY,

    /** The key carries an ordinal, which is bounded by the run's shape. */
    INDEX,

    /** The key carries an outcome, drawn from a closed set of words. */
    OUTCOME,

    /** The key carries a class, drawn from a closed set of words. */
    CLASS,
}

/**
 * One key of the signal vocabulary, and what it is for.
 */
data class TelemetryVocabularyEntry(
    val key: String,
    val role: TelemetryVocabularyRole,
)

/**
 * The formal table of identities, and the vocabulary of what the signals
 * may carry.
 *
 * The table is the ONE place that says which identity may travel where. The
 * policy that governs the export reads its allowlists from here, so the
 * vocabulary of the product and the vocabulary of the export cannot drift
 * apart: adding a key to the export is a change to THIS table, and a change
 * to this table is visible in the diff.
 *
 * A table that breaks the rules is REFUSED, not repaired: a table whose
 * identities contradict each other is a statement nobody can act on, and the
 * honest thing to do with it is to say which row is wrong and stop.
 */
data class TelemetryIdentityTable(
    val identities: List<TelemetryIdentityRule>,
    val attributeVocabulary: List<TelemetryVocabularyEntry>,
    val dimensionVocabulary: List<TelemetryVocabularyEntry>,
) {
    init {
        validate()
    }

    /**
     * The attribute keys the signals may carry: the keys that carry an
     * identity, plus the coarse ones.
     */
    fun allowedAttributeKeys(): Set<String> =
        identities.mapNotNull { (it.travel as? TelemetryTravel.Attribute)?.key }.toSet() +
            attributeVocabulary.map { it.key }

    /**
     * The dimension keys the counters and histograms may carry. Every one of
     * them is a class: a metric label is a closed set of words, never an
     * identity.
     */
    fun allowedDimensionKeys(): Set<String> = dimensionVocabulary.map { it.key }.toSet()

    /**
     * The rule of one identity, or null when the table has none.
     */
    fun ruleOf(kind: TelemetryIdentityKind): TelemetryIdentityRule? =
        identities.firstOrNull { it.kind == kind }

    /**
     * The refusals of a malformed table, in the order they are found. Each
     * one names the identity it is about, because "the table is invalid" is
     * not something anybody can act on.
     */
    private fun validate() {
        val seen = mutableSetOf<TelemetryIdentityKind>()
        for (rule in identities) {
            require(seen.add(rule.kind)) {
                "the identity table has two rules for ${rule.kind.name}: an identity has ONE authority"
            }
            require(rule.allowedUses.isNotEmpty()) {
                "the identity ${rule.kind.name} is allowed no use at all"
            }
            val both = rule.allowedUses intersect rule.forbiddenUses
            require(both.isEmpty()) {
                "the identity ${rule.kind.name} is allowed and forbidden for " +
                    "${both.joinToString(", ") { it.name }} at the same time"
            }
            require(rule.derivedFrom != TelemetryIdentityKind.LIVE_SPAN_ID) {
                "the identity ${rule.kind.name} is minted from a span id: a durable identity is " +
                    "never derived from a span"
            }
        }
        for (kind in TelemetryIdentityKind.entries) {
            require(seen.contains(kind)) {
                "the identity table has no rule for ${kind.name}"
            }
        }

        val slots = mutableMapOf<TelemetryTravel, TelemetryIdentityKind>()
        for (rule in identities) {
            if (rule.travel is TelemetryTravel.Nowhere) continue
            val owner = slots.put(rule.travel, rule.kind)
            require(owner == null) {
                "the identities ${owner?.name} and ${rule.kind.name} occupy the same travel slot " +
                    "(${slotName(rule.travel)}): the historical trace id and the live trace id are " +
                    "different things and may not be published as if they were one"
            }
        }

        val identityKeys = attributeVocabulary.filter { it.role == TelemetryVocabularyRole.IDENTITY }.map { it.key }
        val claimed = identities.mapNotNull { (it.travel as? TelemetryTravel.Attribute)?.key }
        for (rule in identities) {
            val key = (rule.travel as? TelemetryTravel.Attribute)?.key ?: continue
            require(key in identityKeys) {
                "the identity ${rule.kind.name} travels under '$key', which the vocabulary does not " +
                    "name as an identity key"
            }
        }
        for (key in identityKeys) {
            require(claimed.count { it == key } == 1) {
                "the vocabulary names '$key' as an identity key, and ${
                    claimed.count { it == key }
                } identities claim it"
            }
        }
        for (entry in attributeVocabulary.filter { it.role != TelemetryVocabularyRole.IDENTITY }) {
            require(entry.key !in claimed) {
                "the vocabulary names '${entry.key}' as a coarse key, and an identity claims it"
            }
        }

        for (entry in dimensionVocabulary) {
            require(entry.role != TelemetryVocabularyRole.IDENTITY) {
                "the dimension '${entry.key}' would carry an identity: a metric label is a class, " +
                    "never an identity"
            }
            require(entry.key !in claimed) {
                "the dimension '${entry.key}' would carry an identity: a metric label is a class, " +
                    "never an identity"
            }
        }
    }

    /**
     * The name of a slot, for a refusal that has to say which one.
     */
    private fun slotName(slot: TelemetryTravel): String = when (slot) {
        is TelemetryTravel.Attribute -> "attribute '${slot.key}'"
        TelemetryTravel.HistoricalTraceField -> "the historical trace field"
        TelemetryTravel.LiveTraceField -> "the live W3C trace id field"
        TelemetryTravel.LiveSpanField -> "the live W3C span id field"
        TelemetryTravel.Nowhere -> "nowhere"
    }

    /**
     * The table this product ships, and the only one a consumer needs to
     * consult to know which identity it may join on, which one it may not,
     * and where each of them travels.
     */
    companion object {
        /**
         * The shipped table.
         *
         * Every row is a fact about the product that already exists; what
         * this table adds is the authority that says so, in one place, with
         * the rules that keep two of those facts apart.
         */
        val Default: TelemetryIdentityTable = TelemetryIdentityTable(
            identities = listOf(
                TelemetryIdentityRule(
                    kind = TelemetryIdentityKind.RUN_ID,
                    owner = TelemetryIdentityOwner.ENGINE,
                    allowedUses = setOf(TelemetryIdentityUse.JOIN_RUN, TelemetryIdentityUse.HISTORICAL_JOIN),
                    forbiddenUses = setOf(TelemetryIdentityUse.CAUSALITY, TelemetryIdentityUse.WRITE_AUTHORITY),
                    derivedFrom = null,
                    travel = TelemetryTravel.Attribute("run.id"),
                ),
                TelemetryIdentityRule(
                    kind = TelemetryIdentityKind.CORRELATION_ID,
                    owner = TelemetryIdentityOwner.INVOKER,
                    allowedUses = setOf(TelemetryIdentityUse.JOIN_INVOCATION),
                    forbiddenUses = setOf(
                        TelemetryIdentityUse.WRITE_AUTHORITY,
                        TelemetryIdentityUse.IDEMPOTENCY,
                        TelemetryIdentityUse.CAUSALITY,
                    ),
                    derivedFrom = null,
                    travel = TelemetryTravel.Nowhere,
                ),
                TelemetryIdentityRule(
                    kind = TelemetryIdentityKind.OPERATION_ID,
                    owner = TelemetryIdentityOwner.ENGINE,
                    allowedUses = setOf(TelemetryIdentityUse.IDEMPOTENCY),
                    forbiddenUses = setOf(TelemetryIdentityUse.CAUSALITY, TelemetryIdentityUse.JOIN_INVOCATION),
                    derivedFrom = TelemetryIdentityKind.RUN_ID,
                    travel = TelemetryTravel.Nowhere,
                ),
                TelemetryIdentityRule(
                    kind = TelemetryIdentityKind.ATTEMPT_ID,
                    owner = TelemetryIdentityOwner.ENGINE,
                    allowedUses = setOf(TelemetryIdentityUse.IDEMPOTENCY),
                    forbiddenUses = setOf(TelemetryIdentityUse.CAUSALITY, TelemetryIdentityUse.WRITE_AUTHORITY),
                    derivedFrom = TelemetryIdentityKind.RUN_ID,
                    travel = TelemetryTravel.Nowhere,
                ),
                TelemetryIdentityRule(
                    kind = TelemetryIdentityKind.FENCING_TOKEN,
                    owner = TelemetryIdentityOwner.LEASE,
                    allowedUses = setOf(TelemetryIdentityUse.WRITE_AUTHORITY),
                    forbiddenUses = setOf(
                        TelemetryIdentityUse.CAUSALITY,
                        TelemetryIdentityUse.JOIN_INVOCATION,
                        TelemetryIdentityUse.IDEMPOTENCY,
                    ),
                    derivedFrom = null,
                    travel = TelemetryTravel.Nowhere,
                ),
                TelemetryIdentityRule(
                    kind = TelemetryIdentityKind.HISTORICAL_TRACE_ID,
                    owner = TelemetryIdentityOwner.PROJECTION,
                    allowedUses = setOf(TelemetryIdentityUse.HISTORICAL_JOIN),
                    forbiddenUses = setOf(TelemetryIdentityUse.CAUSALITY, TelemetryIdentityUse.JOIN_INVOCATION),
                    derivedFrom = TelemetryIdentityKind.RUN_ID,
                    travel = TelemetryTravel.HistoricalTraceField,
                ),
                TelemetryIdentityRule(
                    kind = TelemetryIdentityKind.LIVE_TRACE_ID,
                    owner = TelemetryIdentityOwner.PROPAGATOR,
                    allowedUses = setOf(TelemetryIdentityUse.CAUSALITY),
                    forbiddenUses = setOf(
                        TelemetryIdentityUse.IDEMPOTENCY,
                        TelemetryIdentityUse.WRITE_AUTHORITY,
                        TelemetryIdentityUse.HISTORICAL_JOIN,
                    ),
                    derivedFrom = null,
                    travel = TelemetryTravel.LiveTraceField,
                ),
                TelemetryIdentityRule(
                    kind = TelemetryIdentityKind.LIVE_SPAN_ID,
                    owner = TelemetryIdentityOwner.PROPAGATOR,
                    allowedUses = setOf(TelemetryIdentityUse.CAUSALITY),
                    forbiddenUses = setOf(
                        TelemetryIdentityUse.IDEMPOTENCY,
                        TelemetryIdentityUse.WRITE_AUTHORITY,
                    ),
                    derivedFrom = TelemetryIdentityKind.LIVE_TRACE_ID,
                    travel = TelemetryTravel.LiveSpanField,
                ),
            ),
            attributeVocabulary = listOf(
                TelemetryVocabularyEntry("run.id", TelemetryVocabularyRole.IDENTITY),
                TelemetryVocabularyEntry("stage.index", TelemetryVocabularyRole.INDEX),
                TelemetryVocabularyEntry("step.index", TelemetryVocabularyRole.INDEX),
                TelemetryVocabularyEntry("compile.outcome", TelemetryVocabularyRole.OUTCOME),
                TelemetryVocabularyEntry("failure.class", TelemetryVocabularyRole.CLASS),
            ),
            dimensionVocabulary = listOf(
                TelemetryVocabularyEntry("operation_kind", TelemetryVocabularyRole.CLASS),
                TelemetryVocabularyEntry("status", TelemetryVocabularyRole.CLASS),
                TelemetryVocabularyEntry("failure_class", TelemetryVocabularyRole.CLASS),
            ),
        )
    }
}

/**
 * The live W3C context of an ACTIVE execution: the trace id and the span id
 * the propagator carried in, and the run they belong to.
 *
 * The two shapes are types, not strings, and the type is the point: the
 * historical trace id and the live trace id are both 32 hexadecimal
 * characters, and a consumer that receives one of them cannot tell which it
 * holds by looking. Here they cannot even be confused at compile time, and
 * they cannot be confused at runtime either: the context carries the run it
 * belongs to, so it can tell a live trace id from the derived one, and it
 * refuses the derived one.
 *
 * The refusal lives in the CONSTRUCTION, not in one factory that a caller
 * could step around. A context that knew the run only because a factory
 * had been asked to build it would still be buildable without that factory,
 * and the hole would be the obvious path: the constructor itself. So the
 * constructor carries the run, and every way of building a context — the
 * constructor, the copy — goes through the same refusal.
 */
data class LiveTraceContext(
    val runId: RunId,
    val traceId: String,
    val spanId: String,
) {
    init {
        require(TRACE_ID.matches(traceId)) {
            "the live trace id must be 32 lowercase hexadecimal characters, and '$traceId' is not"
        }
        require(SPAN_ID.matches(spanId)) {
            "the live span id must be 16 lowercase hexadecimal characters, and '$spanId' is not"
        }
        // The historical trace id is a digest of the run id, and a live
        // trace id is 32 hexadecimal characters: the two have the same
        // shape, and a collector cannot tell them apart by looking. A live
        // context that carried the derived id would publish the synthetic
        // trace as if it were a real one, and every fact correlated through
        // it would be a fact about a trace that never existed.
        val historical = TelemetryTrace.traceIdOf(runId.value)
        require(traceId != historical) {
            "the live trace id is the HISTORICAL trace id derived from the run id ('$historical'): " +
                "a derived id may not be published as a live W3C trace id"
        }
    }

    /**
     * The shapes a live trace id and a live span id must have: 32 and 16
     * lowercase hexadecimal characters, the shape W3C trace context gives
     * them.
     */
    companion object {
        private val TRACE_ID: Regex = Regex("^[0-9a-f]{32}$")
        private val SPAN_ID: Regex = Regex("^[0-9a-f]{16}$")
    }
}
