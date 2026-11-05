package dev.rubentxu.pipeline.v2.output.store

/**
 * Which run owns a stream's directory.
 *
 * The plane files a stream's bytes under a FOLDED name, and a retention pass reaches those bytes
 * by the name of the directory that holds them. This is the answer that pass asks: are these bytes
 * the run that is releasing them?
 *
 * It has two answers and no third, because the question has two answers: a directory either
 * belongs to the run that claims it, or it belongs to somebody else. "Nobody can tell" is not one
 * of them — a pass that cannot tell keeps the bytes, and a pass that can tell deletes them.
 */
internal sealed interface RunDirectoryOwnership {

    /** The directory belongs to the run that claims it. */
    data object ClaimingRun : RunDirectoryOwnership

    /** The directory belongs to another run. */
    data object AnotherRun : RunDirectoryOwnership
}

/**
 * Decide which run owns the directory named [directoryName].
 *
 * ## Three answers, not two
 * A directory can be declared to the claiming run, declared to another run, or declared to nobody:
 * ```text
 *   directory declared to THIS run          -> ours, unconditionally
 *   directory declared to ANOTHER run       -> NOT ours, even if our prefix matches it
 *   directory nobody declared (legacy shape) -> ours if our prefix matches; nobody can prove
 *                                               otherwise, and the bytes are still this run's
 * ```
 * The middle line is what closes a prefix collision: a pass for "run" still scans by prefix, but a
 * directory "run_x" has declared is excluded before the scan can claim it.
 *
 * ## Why the prefix alone could not answer it
 * Selection used to be `fileName.startsWith(safe(runId) + "_")`, justified as "the prefix is a
 * *filter*, never a parse". The reasoning was about not *inverting* the name, and it was true —
 * but it answered the wrong question. A filter is still an IDENTITY when it is the only thing
 * authorising a `deleteRecursively`, and the shorter safe name is a prefix of the longer one:
 * ```text
 *   runId "run"    -> prefix "run_"
 *   runId "run_x"  -> directory "run_x_step1_stdout"   which STARTS WITH "run_"
 *   prune("run")   -> deletes run_x's stream. Another run's bytes, gone.
 * ```
 * That is a PREFIX COLLISION, not a fold: making the fold injective would NOT have closed it, so
 * no durable-format decision was ever needed — only the SELECTION predicate had to change. The
 * on-disk layout is untouched.
 *
 * ## Why "declared streams only" would be wrong too
 * The obvious repair — selecting exactly what the frame index lists — is wrong, and it was wrong in
 * a way that measured as **a retention leak rather than a lost guarantee**. The frame index refuses
 * an id whose last segment is not a channel token, by design: streams written before the channels
 * were separated carry no channel and must not be given a guessed one. The directories those
 * streams left behind are still on disk, and their bytes are still real. Selecting by declaration
 * alone made that transcript permanently unprunable, which is the failure a retention port exists
 * to prevent, and it fails silently: nothing is deleted and no caller is told.
 *
 * ## The residual, stated rather than papered over
 * Two runs whose safe names collide by prefix and whose streams are BOTH undeclared are
 * indistinguishable on disk — there is no byte inside the directory carrying the run id (a stream
 * directory holds only segment, commit, reservation, seal and ownership-lock files, and the
 * ownership lock is never read as content). Pruning the shorter id deletes the longer one's legacy
 * transcript. That remainder is irreducible without changing the on-disk layout, and it is narrower
 * than the original defect by exactly the set of streams any run declared.
 *
 * The rule is pure: it is a function of what the index declares, of the directory's name and of the
 * run that claims it, so it can be exercised without a filesystem, without a store and without a
 * clock.
 *
 * @param directoryName the name of the directory, as it is on disk.
 * @param declaredOwner the run the frame index declares that directory to, or `null` when the
 *   index declares it to nobody.
 * @param namespace the run that claims the directory.
 * @return [RunDirectoryOwnership.ClaimingRun] when the directory is the claiming run's, and
 *   [RunDirectoryOwnership.AnotherRun] when it belongs to another run.
 */
internal fun decideRunDirectoryOwnership(
    directoryName: String,
    declaredOwner: String?,
    namespace: RunNamespace,
): RunDirectoryOwnership = when {
    declaredOwner != null ->
        if (declaredOwner == namespace.runId) {
            RunDirectoryOwnership.ClaimingRun
        } else {
            RunDirectoryOwnership.AnotherRun
        }

    directoryName.startsWith(namespace.prefix) -> RunDirectoryOwnership.ClaimingRun
    else -> RunDirectoryOwnership.AnotherRun
}
