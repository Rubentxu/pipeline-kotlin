package dev.rubentxu.pipeline.v2.runtime.durable

import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShellExecutor
import java.nio.file.Path

/**
 * The confirmation that the journal holds an operation's result.
 *
 * ## What this owns
 *
 * The moment after the durable row of an operation has been written. That row is the point at
 * which an operation's result stops being a runtime fact and becomes a durable one, and the
 * substrate's leftovers for that operation may go.
 *
 * ## Why the confirmation exists at all
 *
 * A `sh` step that asks for its stdout publishes the captured value IN the row, and the row is
 * written after the execution ended: the capture is read, projected, encoded and only then
 * appended. Until that append, the control directory that holds the capture is the value's ONLY
 * durable copy. Releasing it when the execution ends destroys the value before anybody recorded
 * it, and a process that dies in that span leaves a resumed run with nothing to recover: the
 * reconciler finds no control directory, the observation is LOST, and a rerun policy repeats an
 * effect that already happened.
 *
 * So the release of that directory waits for this confirmation, and the executor keeps it.
 *
 * ## What this does NOT own
 *
 * The rule that says what may be released is the substrate's: this port says WHEN, and the
 * substrate says WHAT. A confirmation never decides that a failed execution's evidence may go,
 * and it never decides that a capture the journal never recorded may be dropped.
 */
fun interface JournalConfirmation {

    /**
     * The journal holds the result of the operation named by [opId], so that operation's
     * leftovers may go.
     *
     * The identity is the [OpId] and not the journal's operation id: the substrate's home for
     * an operation is named by the [OpId]'s formatted form, and on the scripted surface the
     * journal names the same operation by a different string. The port carries the identity that
     * NAMES the leftovers, and the substrate resolves them.
     *
     * The call is idempotent: confirming an operation whose leftovers are already gone is a
     * no-op, and confirming twice releases once.
     */
    fun confirm(opId: OpId)
}

/**
 * The confirmation that releases the control directory of a confirmed operation.
 *
 * The control directory is the substrate's own, and the operation's [OpId] names it: the
 * directory's name is the operation's [OpId] in its formatted form. The directory is resolved
 * here, with the same derivation the shell writes into, because a path that travels through the
 * durable protocol would be a second authority for where the substrate keeps its evidence.
 *
 * What the release disposes of is the substrate's decision, not this class's: a failed or expired
 * execution keeps its directory, because its evidence is what tells a resumable run what
 * happened.
 */
class ControlDirectoryConfirmation(private val controlDirRoot: Path) : JournalConfirmation {

    override fun confirm(opId: OpId) {
        DurableShellExecutor().confirmCapture(controlDirOf(controlDirRoot, opId))
    }
}
