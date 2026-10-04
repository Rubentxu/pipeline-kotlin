package dev.rubentxu.pipeline.v2.architecture

import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ReplayDecision
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * ADR-0103 D1 — the normative replay table, pinned against the authority.
 *
 * ## Why a table and not a document
 *
 * `EffectReplayPolicy` published a decision matrix on its interface and did not
 * implement it. Both the name `RERUN` and that matrix disagreed with the code,
 * and nothing turned red, because nothing asserted the table. The two
 * contradictions were:
 *
 *  - `RERUN` was documented as *"Always re-executes"* and implemented as
 *    `RERUN + SUCCEEDED -> SKIP`;
 *  - the matrix said `any | ABORTS_PIPELINE | any | any | ABORT`, while the
 *    implementation returned from the `RERUN` and `NEVER` branches before
 *    `ABORTS_PIPELINE` was ever consulted.
 *
 * The second is the dangerous one. A Step declaring `RERUN + ABORTS_PIPELINE`
 * against a `SUCCEEDED` row was skipped from cache instead of aborting. No
 * current Step declares that combination — `CoreErrorStep` is `NEVER` and
 * aborts for the other reason — which is exactly why no test caught it.
 *
 * ## The amendment that made the table correct
 *
 * The first draft of ADR-0103 put `ABORTS_PIPELINE` above every branch,
 * including a fresh first execution. That is wrong, and this test exists partly
 * to keep that mistake from being reintroduced: the effect means *this Step,
 * when executed, aborts the pipeline*, so refusing to execute it would drop the
 * abort silently. `CoreErrorStep` is precisely a fresh invocation that must
 * run.
 *
 * So containment applies to **journalled history**, not to admission, and the
 * table below separates the two states explicitly. Row 1 and row 2 are the
 * amendment; every other row restates behaviour the authority already had.
 *
 * ## Reading the constants
 *
 * `ReplayDecision.RERUN` means "execute the handler now". ADR-0103 D2a keeps
 * the name and D2b defers the rename, so the table is written against the
 * `execute` alias below. When a future compatibility epoch renames the constant,
 * this test moves with it.
 */
class EffectReplayPolicyTableFitnessTest {

    private val policy = DefaultEffectReplayPolicy()

    private fun decide(
        replayPolicy: ReplayPolicy,
        effects: Set<Effect>,
        journaled: OperationStatus?,
    ): ReplayDecision = policy.decide(
        replayPolicy = replayPolicy,
        effects = effects,
        hasJournalEntry = journaled != null,
        journaledOutcome = journaled,
    )

    @ParameterizedTest(name = "{0}")
    @MethodSource("replayTableRows")
    fun `the normative replay table holds`(
        label: String,
        replayPolicy: ReplayPolicy,
        effects: Set<Effect>,
        journaled: OperationStatus?,
        expected: ReplayDecision,
    ) {
        assertEquals(
            expected,
            decide(replayPolicy, effects, journaled),
            "ADR-0103 D1 row violated: $label. The table is normative and overrides the enum name, " +
                "so a change here is a change of durable semantics, not a refactor.",
        )
    }

    /**
     * Witness 1. A fresh aborting step still executes, because the abort IS the
     * effect. This is the case a naive "containment first" reorder breaks.
     */
    @Test
    fun `a fresh aborting step still executes, because the abort is the effect`() {
        assertEquals(
            execute,
            decide(ReplayPolicy.NEVER, setOf(Effect.ABORTS_PIPELINE), null),
            "A first execution must never be suppressed by the replay layer. CoreErrorStep is " +
                "NEVER + ABORTS_PIPELINE: if this returned ABORT the handler would not run, and a " +
                "Step that never runs never aborts.",
        )
        assertEquals(
            execute,
            decide(ReplayPolicy.RERUN, setOf(Effect.ABORTS_PIPELINE), null),
            "Same for a RERUN step: fresh executes.",
        )
    }

    /**
     * Witness 2. A journalled aborting step is never served from cache. This is
     * the defect, and the control case proves the policy branch is what changes.
     */
    @Test
    fun `a journalled aborting step is never served from cache`() {
        assertEquals(
            ReplayDecision.ABORT,
            decide(ReplayPolicy.RERUN, setOf(Effect.ABORTS_PIPELINE), OperationStatus.SUCCEEDED),
            "The RERUN branch returns before ABORTS_PIPELINE is consulted, so an aborting effect is " +
                "SKIPped from cache while the published matrix says ABORT.",
        )
        assertEquals(
            ReplayDecision.SKIP,
            decide(ReplayPolicy.RERUN, setOf(Effect.EXECUTES_SUBPROCESS), OperationStatus.SUCCEEDED),
            "Control: the same policy and journal state without ABORTS_PIPELINE still reuses.",
        )
        assertEquals(
            ReplayDecision.ABORT,
            decide(ReplayPolicy.NEVER, setOf(Effect.ABORTS_PIPELINE), OperationStatus.SUCCEEDED),
            "Control: NEVER already aborts on journalled history, for its own reason.",
        )
    }

    companion object {
        @JvmStatic
fun replayTableRows(): List<Arguments> = listOf(
// 1. A first execution is never suppressed by the replay layer.
Arguments.of("D1-1 fresh MEMOIZED READ_ONLY executes", ReplayPolicy.MEMOIZED, setOf(Effect.READ_ONLY), null, execute),
Arguments.of("D1-1 fresh RERUN subprocess executes", ReplayPolicy.RERUN, setOf(Effect.EXECUTES_SUBPROCESS), null, execute),
Arguments.of("D1-1 fresh NEVER READ_ONLY executes", ReplayPolicy.NEVER, setOf(Effect.READ_ONLY), null, execute),
Arguments.of("D1-1 fresh NEVER ABORTS_PIPELINE executes", ReplayPolicy.NEVER, setOf(Effect.ABORTS_PIPELINE), null, execute),
Arguments.of("D1-1 fresh RERUN ABORTS_PIPELINE executes", ReplayPolicy.RERUN, setOf(Effect.ABORTS_PIPELINE), null, execute),
Arguments.of("D1-1 fresh MEMOIZED ABORTS_PIPELINE executes", ReplayPolicy.MEMOIZED, setOf(Effect.ABORTS_PIPELINE), null, execute),

// 2. Containment applies to journalled history.
Arguments.of(
    "D1-2 journalled RERUN ABORTS_PIPELINE aborts",
    ReplayPolicy.RERUN, setOf(Effect.ABORTS_PIPELINE), OperationStatus.SUCCEEDED, ReplayDecision.ABORT,
),
Arguments.of(
    "D1-2 journalled RERUN ABORTS_PIPELINE aborts on FAILED too",
    ReplayPolicy.RERUN, setOf(Effect.ABORTS_PIPELINE), OperationStatus.FAILED, ReplayDecision.ABORT,
),
Arguments.of(
    "D1-2 journalled MEMOIZED ABORTS_PIPELINE aborts",
    ReplayPolicy.MEMOIZED, setOf(Effect.ABORTS_PIPELINE), OperationStatus.SUCCEEDED, ReplayDecision.ABORT,
),
Arguments.of(
    "D1-2 journalled NEVER ABORTS_PIPELINE aborts",
    ReplayPolicy.NEVER, setOf(Effect.ABORTS_PIPELINE), OperationStatus.SUCCEEDED, ReplayDecision.ABORT,
),

// 3. NEVER constrains history, never the first legitimate execution.
Arguments.of("D1-3 NEVER with a SUCCEEDED row aborts", ReplayPolicy.NEVER, setOf(Effect.READ_ONLY), OperationStatus.SUCCEEDED, ReplayDecision.ABORT),
Arguments.of("D1-3 NEVER with a FAILED row aborts", ReplayPolicy.NEVER, setOf(Effect.READ_ONLY), OperationStatus.FAILED, ReplayDecision.ABORT),

// 4-5. RERUN reuses a journalled SUCCEEDED result; the name that lied.
Arguments.of("D1-4 RERUN reuses SUCCEEDED", ReplayPolicy.RERUN, setOf(Effect.EXECUTES_SUBPROCESS), OperationStatus.SUCCEEDED, ReplayDecision.SKIP),
Arguments.of("D1-5 RERUN re-executes FAILED", ReplayPolicy.RERUN, setOf(Effect.EXECUTES_SUBPROCESS), OperationStatus.FAILED, execute),
Arguments.of("D1-5 RERUN re-executes RUNNING", ReplayPolicy.RERUN, setOf(Effect.EXECUTES_SUBPROCESS), OperationStatus.RUNNING, execute),

// 6-7. MEMOIZED only memoises a purely read-only effect set.
Arguments.of("D1-6 MEMOIZED READ_ONLY reuses SUCCEEDED", ReplayPolicy.MEMOIZED, setOf(Effect.READ_ONLY), OperationStatus.SUCCEEDED, ReplayDecision.SKIP),
Arguments.of("D1-7 MEMOIZED READ_ONLY re-executes FAILED", ReplayPolicy.MEMOIZED, setOf(Effect.READ_ONLY), OperationStatus.FAILED, execute),

// 8. A mixed or effectful set is never memoised, whatever the policy.
Arguments.of("D1-8 MEMOIZED EXECUTES_SUBPROCESS never memoises", ReplayPolicy.MEMOIZED, setOf(Effect.EXECUTES_SUBPROCESS), OperationStatus.SUCCEEDED, execute),
Arguments.of("D1-8 MEMOIZED WRITES_WORKSPACE never memoises", ReplayPolicy.MEMOIZED, setOf(Effect.WRITES_WORKSPACE), OperationStatus.SUCCEEDED, execute),
Arguments.of(
    "D1-8 MEMOIZED with a MIXED read-only + subprocess set never memoises",
    ReplayPolicy.MEMOIZED,
    setOf(Effect.READ_ONLY, Effect.EXECUTES_SUBPROCESS),
    OperationStatus.SUCCEEDED,
    execute,
),
)
    }
}

/** `ReplayDecision.RERUN` means "execute the handler now"; see the class KDoc. */
private val execute = ReplayDecision.RERUN
