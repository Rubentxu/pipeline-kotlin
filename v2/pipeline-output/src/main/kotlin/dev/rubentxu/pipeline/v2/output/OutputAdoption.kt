package dev.rubentxu.pipeline.v2.output

/**
 * The adoption contract for S2, and the checkable form of [ADR-M1 D4].
 *
 * `05-§Strategy 2 / Risks` requires reserve/commit or a recoverable intent protocol, because a crash
 * between the byte append and the index commit loses the global order for those bytes. RCE
 * `ADR-0002` compared four candidates and decided **ADOPT D, segment reservation with recovery**,
 * failing none of six invariants across four protocols x eight crash scenarios.
 *
 * So the design exists and the decision is taken. What that ADR did not write is the sentence that
 * connects it to a product: **what the adopter has to do for "S2 is implemented" to be a claim
 * rather than a hope.** This file is that sentence, and it now lives in `src/main` rather than in a
 * test harness — the earlier placement is the reason the gap survived as long as it did.
 *
 * ## Why obligations and not invariants
 *
 * An invariant is a property the result *has*. An obligation is something the adopter *must do*.
 * Conflating them is how a design gets credited with an implementation, so the two vocabularies
 * are kept in separate types here and never mixed in a single claim.
 *
 * ## What moved from RCE
 *
 * The RCE contract was stated over **frames** carrying a **sequence**. This store is stated over
 * **byte ranges** carrying a **committed offset**, because [ADR-M1 D3] makes the committed byte
 * offset the unit of continuation. The obligations are unchanged; the unit they are discharged in
 * is not.
 */

/**
 * The six invariants the strategy was selected on, restated as the acceptance bar.
 *
 * Deliberately not re-derived. They were measured against four candidates under a hostile fault
 * model; re-deriving them here would assert numbers this file cannot produce.
 */
enum class OutputCrashInvariant(
    val id: String,
    val statement: String,
    /** How an adoption can be shown to hold it. A witness, not a test recipe. */
    val witness: String,
) {
    /** A range the writer acknowledged must still be there after a restart. */
    I1_ACKNOWLEDGED_SURVIVES(
        "I1",
        "every acknowledged byte range survives restart",
        "kill the process between acknowledge and the next read; every acknowledged offset is still " +
            "readable and byte-identical",
    ),

    /** Bytes never acknowledged must not appear after recovery. No phantom data. */
    I2_NO_UNACKNOWLEDGED_APPEARS(
        "I2",
        "no unacknowledged byte becomes observable after recovery",
        "the same kill, then compare recovered extent against acknowledged extent; a reader must " +
            "never see more than was acknowledged",
    ),

    /**
     * Density, not monotonicity.
     *
     * The distinction is the point of I3: a permanent hole leaves a reader unable to tell "those
     * bytes are gone" from "those bytes have not arrived yet" — the same conflation the
     * content-identity work forbids on the replication side.
     */
    I3_ORDER_IS_DENSE(
        "I3",
        "the recovered byte order is dense, and new appends continue it without a hole",
        "after a crash the committed offsets over surviving bytes have no gap, and the next append " +
            "continues the same offset rather than restarting or skipping",
    ),

    /** No committed offset may reference bytes that are absent or cut short. */
    I4_NO_DANGLING_COMMIT(
        "I4",
        "no committed offset points at absent or truncated bytes",
        "for every committed offset, the referenced byte range exists in full; a torn tail is " +
            "reported as a retention reason, never as committed content",
    ),

    /** Recovery must not hand the same range out twice. */
    I5_NO_DUPLICATE_ON_RECOVERY(
        "I5",
        "recovery never yields a byte twice, and replay does not duplicate",
        "recover twice from the same durable state and compare; then replay the same effect and " +
            "confirm the reader sees each committed byte once",
    ),

    /** A claimed-but-unwritten reservation is resolved one way or the other, never left in the air. */
    I6_NO_AMBIGUOUS_SLOT(
        "I6",
        "a claimed-but-unwritten reservation is completed or released, never ambiguous",
        "kill between reserve and write, then recover; every reservation is either committed with " +
            "its bytes or released with no reference to it",
    ),
}

/**
 * The three obligations an adoption of D creates.
 *
 * Not invariants. A conjunction, and any single gap disqualifies.
 */
enum class OutputAdoptionObligation(
    val id: String,
    val obligation: String,
    /** What it looks like when genuinely done, as opposed to declared done. */
    val done: String,
) {
    /**
     * The writer reserves before it writes.
     *
     * This is the obligation D exists for. Without it the store is a byte log with no protocol and
     * every invariant below is unbacked.
     */
    WRITER_RESERVES_BEFORE_APPEND(
        "O1",
        "the writer reserves the byte range before writing into it, and the acknowledgement is the " +
            "reservation rather than the append",
        "a range is durable-and-known before any byte of it is visible to a reader",
    ),

    /**
     * The reader resumes from the committed offset, never from a file's size.
     *
     * This is where the byte-authority item and the crash-consistency item become one. An index is
     * what a cursor requires, so implementing a cursor is what *creates* this obligation rather than
     * discharging it.
     */
    READER_RESUMES_FROM_COMMITTED(
        "O2",
        "a reader resumes from a committed offset, not from the size of a byte file",
        "a reader can be handed an offset that means something independent of how many bytes happen " +
            "to be on disk",
    ),

    /**
     * Recovery is an entry point, not a branch inside the reader.
     *
     * I5 and I6 are properties of what happens *after* a crash, and the recovery path is exercised
     * only when something has already gone wrong — which is exactly why it cannot be folded into
     * the reader.
     */
    RECOVERY_IS_AN_ENTRY_POINT(
        "O3",
        "recovery is a distinct entry point that reconciles reservations and the committed offset " +
            "before any reader is served",
        "a crashed process's state is reconciled by a named component, and a reader cannot observe " +
            "an unreconciled state",
    ),
}

/**
 * Properties this adoption explicitly does **not** establish.
 *
 * An ADR's "what this does not establish" section erodes first: nothing is deleted when a claim is
 * inherited, it just stops being written down, and the next reader finds a proven-looking result
 * with no mention of the limits that shaped it.
 */
enum class OutputNotEstablished(
    val notEstablished: String,
    val whatItWouldTake: String,
) {
    /**
     * Not durability across power loss.
     *
     * The fault model is process death. Bytes already issued to a file descriptor live in the page
     * cache; they do not survive a machine crash without an `fsync` discipline. The proven
     * property is *"a process that dies loses nothing it acknowledged"* — real and useful, and not
     * the property most people mean by durable.
     */
    POWER_LOSS_DURABILITY(
        "durability across power loss",
        "an fsync discipline on this store's storage — data, then metadata and index — and a fault " +
            "model that cuts power rather than killing a process. [OutputAdoption] does not " +
            "authorise one, and no conformance artefact may claim the stronger property without it",
    ),

    /**
     * Not cross-stream global order.
     *
     * D recovers order *within* each stream by merging that stream's segments. Two streams have no
     * defined interleaving, because the Output Plane does not claim one: the product's console
     * order is a presentation concern and belongs to whatever renders it, not to the store.
     */
    CROSS_STREAM_GLOBAL_ORDER(
        "a global order across streams",
        "an ordering authority over stream identities themselves. This store deliberately has " +
            "none, because a global console order is a presentation decision and imposing it in the " +
            "store would reintroduce the coupling the Output Plane exists to remove",
    ),
}

/** Whether an adoption may claim crash consistency. */
object OutputAdoption {

    val invariants: List<OutputCrashInvariant> = OutputCrashInvariant.entries.toList()

    val obligations: List<OutputAdoptionObligation> = OutputAdoptionObligation.entries.toList()

    val notEstablished: List<OutputNotEstablished> = OutputNotEstablished.entries.toList()

    /** Whether the product may say "S2 is implemented". Requires every obligation discharged. */
    fun mayClaimCrashConsistency(discharged: Set<OutputAdoptionObligation>): Boolean =
        discharged.containsAll(obligations)

    /**
     * The invariants that are meaningless until the obligations are discharged.
     *
     * The parameter is the **discharged** set and the complement is taken inside. A parameter whose
     * name can be confused with its negation is a defect the compiler will not catch — the first
     * version of this contract took `undischarged`, and its own test passed it the discharged set,
     * type-checked, and reported the exact inverse of the truth.
     *
     * Not a ranking. I3 and I6 describe a reserved-slot world; citing them as satisfied today would
     * be describing the design rather than the system.
     */
    fun invariantsBlockedBy(discharged: Set<OutputAdoptionObligation>): List<OutputCrashInvariant> {
        val undischarged = obligations.toSet() - discharged

        // A union, not an if/else-if chain. The first version returned on the first match, so a
        // writer that reserved but had no recovery entry point reported only I3 blocked and quietly
        // vouched for I5 and I6 — precisely the two that recovery underpins.
        val blocked = buildList {
            if (OutputAdoptionObligation.WRITER_RESERVES_BEFORE_APPEND in undischarged) {
                // Nothing the writer does is recoverable, so nothing is established.
                addAll(invariants)
            }
            if (OutputAdoptionObligation.READER_RESUMES_FROM_COMMITTED in undischarged) {
                add(OutputCrashInvariant.I3_ORDER_IS_DENSE)
            }
            if (OutputAdoptionObligation.RECOVERY_IS_AN_ENTRY_POINT in undischarged) {
                add(OutputCrashInvariant.I5_NO_DUPLICATE_ON_RECOVERY)
                add(OutputCrashInvariant.I6_NO_AMBIGUOUS_SLOT)
            }
        }
        return invariants.filter { it in blocked }
    }

    /** The refusal that must survive into every conformance artefact. */
    val POWER_LOSS_NOT_CLAIMED: String =
        "A process that dies loses nothing it acknowledged. That is the proven property. Durability " +
            "across power loss is NOT claimed and is not authorisable without a demonstrated fsync " +
            "discipline (data, then metadata and index)."
}
