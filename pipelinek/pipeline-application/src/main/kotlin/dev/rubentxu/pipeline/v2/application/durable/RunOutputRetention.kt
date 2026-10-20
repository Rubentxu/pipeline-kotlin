package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.output.OutputPruneIntent
import dev.rubentxu.pipeline.v2.output.OutputPruneReport
import dev.rubentxu.pipeline.v2.output.OutputRetentionPort
import dev.rubentxu.pipeline.v2.output.RetainUntil
import dev.rubentxu.pipeline.v2.output.RunLifecycle

/**
 * What the run's terminal state did to its output.
 *
 * A closed vocabulary, not a `Boolean` and not a nullable report: "the policy kept it", "it was
 * released" and "the release could not be carried out" are three different facts, and a caller
 * confirming whether the bytes are gone needs to tell the last one apart from the first. Collapsing
 * them into `report? ` would make a failed release indistinguishable from a deliberate retention —
 * which is the same defect ADR-M1 D2 exists to prevent on the read side.
 */
sealed interface RunOutputDisposition {

    /** The configured [RetainUntil] keeps this run's output. Nothing was attempted. */
    data object Retained : RunOutputDisposition

    /**
     * A release was authorised and the store performed it.
     *
     * [OutputPruneReport.streamsRetained] is non-zero when the filesystem refused a deletion that was
     * authorised: the report says so rather than hiding it, so a zero removal count can still be read
     * correctly as "nothing was there" or "something resisted".
     */
    data class Released(val report: OutputPruneReport) : RunOutputDisposition

    /**
     * The store could not act at all — an unreconciled plane, or a filesystem that refused outright.
     *
     * Carrying the [cause] rather than a message keeps the fact inspectable without inventing a
     * second refusal vocabulary in the output module: [dev.rubentxu.pipeline.v2.output.OutputRefusal]
     * is about reading bytes, and a failure to delete is not one of its cases.
     */
    data class ReleaseFailed(val cause: Throwable) : RunOutputDisposition
}

/**
 * The ONE production path from "a run reached a terminal state" to "its output may be discarded".
 *
 * ## The gap this closes
 *
 * `OutputRetentionPort` shipped with a full closed vocabulary and **no caller**: nothing in the
 * product ever pruned, so the store was never pruned for the life of a control root — the leak
 * `M1_INTEGRATION_REGROUND_RECEIPT` §"the Output Plane is never pruned" recorded as having no owner.
 * A delete capability with no owner is worse than no capability: the first person to wire it would
 * have had to invent their own notion of "is this run finished", and any such notion re-derived from
 * a directory scan or a journal query is a **second authority on run lifecycle** — the exact thing
 * `OutputRetention.kt` refuses to let `SegmentOutputStore` become.
 *
 * So the authority is placed where the fact already lives. The runtime executed the run, so the
 * runtime knows it ended, and the coordinator's `finally` is the single point every exit passes
 * through — including the ones that abort, that catch, and that rethrow an invariant violation.
 *
 * ## Why [RunLifecycle] is not a parameter here
 *
 * [intentFor] passes `RunLifecycle.Terminal` and there is deliberately **no way to hand it
 * `StillRunning`**: the API cannot express "discard the output of a live run", so that mistake is not
 * available to a caller instead of merely discouraged by a comment. The other direction is equally
 * closed — a component that can only reach terminality here cannot become a second lifecycle oracle.
 *
 * ## Why the port arrives as a supplier
 *
 * `[OutputRetentionPort]` rather than an instance, so the store is touched **only if the policy
 * actually authorises a release**. Recovering the plane is not free (`ADR-M1 §D4` O3), and a
 * configuration that retains everything must not pay for — nor be perturbed by — a recovery it never
 * uses. It also keeps the decision separable from the effect: [intentFor] is pure and needs no
 * filesystem at all.
 *
 * ## Why a failed release is a value
 *
 * This runs in a `finally`. An exception escaping here would replace the run's own outcome: a build
 * that succeeded would be reported as failed because a post-run deletion hit a read-only directory.
 * The filesystem throws, and this is the adapter boundary, so the throw is converted to
 * [RunOutputDisposition.ReleaseFailed] and reported. `Error` still propagates — this catches
 * operational failure, not a broken JVM.
 *
 * ## Why the reporting lives here and not in the coordinator
 *
 * The verdict has to be REPORTED, and the reporting is interpretation: "a release that did not fully
 * happen" is a sentence about retention, not about orchestration. Putting it in the coordinator grew
 * that file past its own `CoordinatorGrowthGuardrailTest` ceiling, and the guard was right to object
 * — the coordinator would have been interpreting a collaborator's verdict. So this owns the sentence,
 * through a defaulted sink so a caller can route it (tests assert on the disposition, not on stderr)
 * while the default is the operator's terminal, which is where a post-run diagnostic belongs.
 *
 * ## Why this is public
 *
 * [CanonicalDurableRunCoordinator] is public and takes this as an optional constructor parameter,
 * which is the same convention its other optional collaborators already follow (`StepRegistry`,
 * `DirectiveRegistry`, `WaitUntilControlJournal`). Kotlin will not let a public constructor expose an
 * `internal` type, and the alternatives were worse: a lambda would erase the verdict the coordinator
 * has to report, and a public marker interface plus an internal implementation would add a second
 * name for one thing.
 *
 * Public here means "the runtime's retention seam is nameable", NOT "the output store is published".
 * The half that BLOCK 2 does not publish — `SegmentOutputStore`, its layout and its recovery — moved
 * into `:pipeline-output-store` when the plane was split, and is reached only through the
 * [OutputRetentionPort] interface. Nothing in this type names it, and the published artifact does
 * not contain it.
 */
class RunOutputRetention(
    private val retention: () -> OutputRetentionPort,
    private val policy: RetainUntil,
    private val report: (String) -> Unit = { message -> System.err.println(message) },
) {

    /**
     * The PURE decision: the intent this policy would authorise for a run that has ended.
     *
     * `null` is the answer, not a partial result — it is what [RetainUntil.Forever] and
     * [RetainUntil.ExplicitReleaseOnly] mean at a terminal state, and the caller has no branch to
     * take on a miss because there is nothing to interpret.
     */
    fun intentFor(runId: RunId): OutputPruneIntent? =
        policy.authorize(runId.value, RunLifecycle.Terminal)

    /**
     * The effect: consult the policy, and prune only if it authorised one.
     *
     * Total by construction — the store is not even resolved when the answer is `null` — and the
     * disposition is returned as well as reported, so a caller can act on it rather than parse a
     * message.
     */
    fun onRunTerminal(runId: RunId): RunOutputDisposition {
        val intent = intentFor(runId) ?: return RunOutputDisposition.Retained
        val disposition = try {
            RunOutputDisposition.Released(retention().prune(intent))
        } catch (failure: Exception) {
            RunOutputDisposition.ReleaseFailed(failure)
        }
        when (disposition) {
            RunOutputDisposition.Retained -> Unit
            is RunOutputDisposition.Released -> {
                val resisted = disposition.report.streamsRetained
                if (resisted > 0) {
                    report(
                        "PipelineK: $resisted output stream(s) of run '${runId.value}' survived the " +
                            "release its terminal state authorised",
                    )
                }
            }
            is RunOutputDisposition.ReleaseFailed -> report(
                "PipelineK: could not release the output of run '${runId.value}' after it reached a " +
                    "terminal state: ${disposition.cause}",
            )
        }
        return disposition
    }
}
