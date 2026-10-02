package dev.rubentxu.pipeline.v2.architecture

import java.nio.file.Files
import java.nio.file.Path

/**
 * The set of sources that make up the durable run path, as ONE union.
 *
 * Why this exists. Every extraction in PR-018..020 moved durable execution out of
 * `CanonicalDurableRunCoordinator` into a named engine. A fitness guard that scans
 * only the coordinator therefore stops seeing the code it exists to police, and its
 * verdict becomes an artifact of WHERE the code lives rather than WHAT the code
 * does. That failure mode has already bitten this suite twice: the H2 W1d guard
 * reported zero loops after `dispatchBody` moved (an empty inventory is
 * indistinguishable from a clean coordinator), and the routing-debt guard reported
 * debt that no longer existed.
 *
 * A guard on the durable run path must follow the durable run path. Listing the
 * union in one place means the next extraction updates one list instead of
 * silently weakening four independent guards.
 */
object DurableRunPathSources {

    private const val DURABLE = "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable"

    /**
     * Ordered by the durable protocol, not by convenience. The coordinator is first
     * because it owns the run and stage bookends; the engines follow in the order a
     * run reaches them.
     */
    val files: List<Path> = listOf(
        "$DURABLE/CanonicalDurableRunCoordinator.kt",
        "$DURABLE/RunLifecycleEngine.kt",
        "$DURABLE/BeforeStageDirectiveEngine.kt",
        "$DURABLE/StepDispatchEngine.kt",
        "$DURABLE/ParallelStageEngine.kt",
        "$DURABLE/StageExecutionEngine.kt",
        "$DURABLE/BodyExecutionEngine.kt",
    ).map { ScannerSupport.v2Root().resolve(it) }

    /** Every source in the union, concatenated with a separator so a match cannot span two files. */
    fun text(): String = files.joinToString("\n") { path ->
        require(Files.exists(path)) {
            "Durable run-path source not found: $path. An extraction renamed or moved a file — " +
                "update DurableRunPathSources.files in the SAME commit, or every guard on the " +
                "durable run path silently stops seeing the code it polices."
        }
        Files.readString(path)
    }

    /** Per-file view, for a guard that must attribute a finding to one owner. */
    fun perFile(): Map<Path, String> = files.associateWith { path ->
        require(Files.exists(path)) { "Durable run-path source not found: $path" }
        Files.readString(path)
    }
}
