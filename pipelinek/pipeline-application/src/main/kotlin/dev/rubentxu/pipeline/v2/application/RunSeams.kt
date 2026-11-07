package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.runtime.PreResolvedComposition
import dev.rubentxu.pipeline.v2.runtime.RuntimeWorkspaceTransport

/**
 * The seams a run needs before it can execute, and nothing else.
 *
 * ## The invariant
 *
 * A run asks its credential seam first, admits its plugins second, decides where
 * its steps work third, and starts watching the live output last. That order is
 * part of the contract and not an accident of how the code happens to be written:
 * the credential seam is asked BEFORE the admission, so a run that binds a
 * credential whose passphrase is not at hand answers the same way whether or not a
 * plugin artifact would also have been refused. The two branches that run this
 * product answer the same question in the same order, and this value is where
 * that order is written down.
 *
 * The order decides WHICH refusal an operator sees when two things are wrong at
 * once: a run that binds a credential whose passphrase is not at hand, and a
 * plugin artifact that may not be admitted. With the admission first, the branch
 * that persists nothing answered 2 where the durable one answered 3, and the two
 * worlds told the operator different stories about a single run.
 *
 * ## What this value does NOT hold
 *
 * What a run OWNS stays with the branch that owns it: the lease, the event store,
 * the journal, the replay cursor, the Saga obligations, and the credential
 * provider (a branch with a store has one; a branch without a store has none and
 * answers every lookup with "not configured"). Two branches that shared their
 * ownership would be two runs pretending to be one, and the difference between
 * them is exactly what the operator asked for when they named a database.
 *
 * The eligibility of a pipeline for the canonical engine is not here either: it
 * is a decision about the pipeline, not about the seams, and each branch takes it
 * where its own run takes it.
 */
internal data class RunSeams(
    /** What a Step may look up, and what injects a credential into a block. */
    val credentials: CredentialSeam,
    /** The frozen registry of Steps, Directives and Event kinds the run admitted. */
    val composition: PreResolvedComposition,
    /** Where the steps share a directory, and who owns that directory. */
    val workspaceTransport: RuntimeWorkspaceTransport,
    /** The watcher of the live output, and the signal the run announces on. */
    val outputObserver: LiveOutputObserverHandle,
)

/**
 * Compose the seams of a run, in the one order every run uses.
 *
 * The order lives HERE and not in the two branches that call this, because two
 * copies of an order are two chances to disagree, and the disagreement is
 * invisible: both branches would still work, and they would answer the operator
 * differently about a single run.
 *
 * The four steps arrive as suppliers rather than as values, so that the order is
 * decided here while the work stays with the branch that owns it. A branch that
 * owns a lease and a store hands them back on the way out, and the refusal
 * travels as an exception rather than as a value the caller has to remember to
 * unwrap.
 *
 * A refused admission UNWINDS: it throws [CommandRefused] carrying the exit the
 * refusal owns, and the seams after it never run. In particular the live output
 * observer is not started on a refused admission, because a run that refuses has
 * nothing to observe and the observer owns a thread.
 *
 * @throws CommandRefused when the admission refused the run. The exit it carries
 *   is the refusal's own, and the place that owns the process ends the process
 *   with it.
 */
internal fun composeRunSeams(
    credentials: () -> CredentialSeam,
    admission: () -> PluginAdmission,
    workspaceTransport: () -> RuntimeWorkspaceTransport,
    outputObserver: () -> LiveOutputObserverHandle,
): RunSeams {
    val credentialSeam = credentials()
    val admitted = admission()
    val composition = when (admitted) {
        is PluginAdmission.Admitted -> admitted.composition
        is PluginAdmission.Refused -> throw CommandRefused(admitted.exit)
    }
    val transport = workspaceTransport()
    val observer = outputObserver()
    return RunSeams(
        credentials = credentialSeam,
        composition = composition,
        workspaceTransport = transport,
        outputObserver = observer,
    )
}
