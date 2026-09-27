package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.RunIdGenerator

/**
 * D-031 (audit D-011 H3.c): durable run selection helpers extracted from Main.kt.
 *
 * - [selectDurableRun] routes a [DurableRunPolicy] to either a resumed/reused run
 *   or a fresh one. When the policy requires reuse but no prior run is found, it
 *   falls back to [startFreshRun] (the in-memory equivalent of "first run ever").
 * - [startFreshRun] records a new run id into [RunIdDirectory] and returns it
 *   wrapped as a [DurableRunSelection.StartedFresh].
 *
 * Both helpers are stateless (no closures over Main.kt state), so they live
 * alongside the durable-run types ([DurableRunPolicy], [RunIdDirectory],
 * [StoredRunId], [DurableRunSelection], [RunIdGenerator]) in the same package.
 */
internal fun selectDurableRun(
    policy: DurableRunPolicy,
    runIdDirectory: RunIdDirectory,
    definitionId: dev.rubentxu.pipeline.v2.domain.DefinitionId,
    runIdGenerator: RunIdGenerator,
): DurableRunSelection = when (policy) {
    DurableRunPolicy.ReusePriorRun -> when (val stored = runIdDirectory.findLastRunId(definitionId)) {
        is StoredRunId.Found -> DurableRunSelection.Reused(stored.runId)
        StoredRunId.Missing -> startFreshRun(runIdDirectory, definitionId, runIdGenerator)
    }
    DurableRunPolicy.ResumePriorRun -> DurableRunSelection.Reused(runIdDirectory.lastRunId(definitionId))
    DurableRunPolicy.StartFreshRun -> startFreshRun(runIdDirectory, definitionId, runIdGenerator)
}

internal fun startFreshRun(
    runIdDirectory: RunIdDirectory,
    definitionId: dev.rubentxu.pipeline.v2.domain.DefinitionId,
    runIdGenerator: RunIdGenerator,
): DurableRunSelection.StartedFresh {
    val runId = runIdGenerator.next()
    runIdDirectory.record(definitionId, runId)
    return DurableRunSelection.StartedFresh(runId)
}
