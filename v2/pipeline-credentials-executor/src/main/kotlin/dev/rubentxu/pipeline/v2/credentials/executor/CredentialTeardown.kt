package dev.rubentxu.pipeline.v2.credentials.executor

import dev.rubentxu.pipeline.v2.domain.credentials.ProjectionResult
import java.nio.file.Path

/**
 * Which phase of a credential teardown a failure came from.
 *
 * The three phases are the ADR-0051 D8 ordering, and they are NOT interchangeable: a wipe
 * failure leaves credential BYTES on disk, while the other two leave an audit or memory
 * residue. [TeardownOutcome.Failed] uses this to decide what the caller is told.
 */
internal enum class TeardownPhaseName {
    /** Secure wipe of a retained [ProjectionResult]. */
    Wipe,

    /** The [dev.rubentxu.pipeline.v2.events.CredentialUnbound] audit append. */
    EmitUnbound,

    /** The in-memory [dev.rubentxu.pipeline.v2.domain.SecretHandle] wipe. */
    CloseHandles,
}

/**
 * One phase of the teardown, as a name and the effect that phase performs.
 *
 * The effect is injected rather than inlined so the ordering rule can be pinned without the
 * filesystem, a subprocess or an event store — AGENTS.md section 2 forbids all three in a unit
 * test, and the rule under test is about WHICH phase runs after a failure, not what any phase does.
 */
internal class TeardownPhase(
    val name: TeardownPhaseName,
    val run: () -> Unit,
)

/** A phase that threw, together with the credential bytes it left behind if it was a wipe. */
internal data class TeardownFailure(
    val phase: TeardownPhaseName,
    val orphanPaths: List<Path>,
    val throwable: Throwable,
)

/** What the teardown decided once every phase had been attempted. */
internal sealed interface TeardownOutcome {

    /** Every phase ran and none threw. */
    data object Clean : TeardownOutcome

    /**
     * At least one phase threw. EVERY phase still ran.
     *
     * @property failures in phase order; never empty.
     */
    class Failed(val failures: List<TeardownFailure>) : TeardownOutcome {

        init {
            require(failures.isNotEmpty()) { "Failed requires at least one failure" }
        }

        /** Every path the wipe could not remove, deduplicated, in the order they were hit. */
        val orphanPaths: List<Path> = failures.flatMap { it.orphanPaths }.distinct()

        /**
         * The first wipe failure, or `null` when the teardown failed only outside the wipe.
         *
         * This is the discriminator between the two things a caller can be told. A wipe failure
         * means bytes survived on disk and the caller must report an orphan path; anything else
         * means the teardown ran to the end and only the audit or the in-memory wipe objected.
         */
        val wipeFailure: TeardownFailure?
            get() = failures.firstOrNull { it.phase == TeardownPhaseName.Wipe }

        /** The failure to propagate when there was no wipe failure. */
        val firstFailure: TeardownFailure = failures.first()
    }
}

/**
 * Runs every teardown phase, in the order given, regardless of what any earlier phase did.
 *
 * ## The rule this exists to enforce
 *
 * ADR-0051 D8 fixes three phases — wipe the materialisations, append `CredentialUnbound`, wipe the
 * handles — and each one releases something that no later phase can release. The previous
 * implementation called them in sequence with no `try/finally`, so the first throwable ended the
 * teardown:
 *
 *  - the append failed → every [dev.rubentxu.pipeline.v2.domain.SecretHandle] byte array stayed
 *    in memory, and because `closed` had already been set, the retry was a no-op;
 *  - the append failed *on the wipe-failure branch* → [BoundCredentials.CleanupException] was never
 *    constructed, so the orphan paths were lost along with the handles.
 *
 * Neither was hypothetical: the event store append really does throw, which is what
 * `UNIQUE(run_id, sequence)` made it do.
 *
 * The first failure keeps its identity as the cause and absorbs the rest as suppressed, so a
 * caller that propagates the first throwable still surfaces every failure in the stack trace.
 */
internal fun runTeardown(phases: List<TeardownPhase>): TeardownOutcome {
    val failures = mutableListOf<TeardownFailure>()

    for (phase in phases) {
        try {
            phase.run()
        } catch (t: Throwable) {
            failures += TeardownFailure(
                phase = phase.name,
                orphanPaths = (t as? ProjectionResult.WipeException)?.orphanPaths ?: emptyList(),
                throwable = t,
            )
        }
    }

    if (failures.isEmpty()) return TeardownOutcome.Clean

    val first = failures.first()
    for (other in failures.drop(1)) {
        first.throwable.addSuppressed(other.throwable)
    }
    return TeardownOutcome.Failed(failures)
}

/**
 * Closes the projections already acquired when an acquisition FAILS partway through, reverse-LIFO,
 * folding each wipe failure into [primary] as a suppressed exception.
 *
 * ## The rule this exists to enforce
 *
 * Bind two credentials and the second can fail — a tampered store, a wrong kind, a missing linked
 * secret — after the first has already written a private key to disk. The previous implementation
 * closed the retained projections with `catch (_: Throwable) { /* swallow */ }`, so a key that
 * could not be wiped produced no trace at all: the operator was told only about the missing
 * credential, never that its predecessor's bytes were still on disk.
 *
 * [primary] is the throwable the caller is already propagating, so a suppressed [ProjectionResult.WipeException]
 * — which names its orphan paths in its own message — lands in the stack trace without inventing
 * a second carrier for "which files survived".
 *
 * @return the orphan paths, deduplicated; empty when every projection wiped cleanly.
 */
internal fun drainProjections(
    projections: List<ProjectionResult>,
    primary: Throwable,
): List<Path> {
    val orphans = mutableListOf<Path>()

    for (projection in projections.asReversed()) {
        try {
            projection.close()
        } catch (t: Throwable) {
            (t as? ProjectionResult.WipeException)?.orphanPaths?.let { orphans.addAll(it) }
            primary.addSuppressed(t)
        }
    }

    return orphans.distinct()
}
