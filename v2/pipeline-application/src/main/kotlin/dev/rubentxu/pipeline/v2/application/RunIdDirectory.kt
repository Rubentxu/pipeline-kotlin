package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.DefinitionId
import dev.rubentxu.pipeline.v2.domain.RunId
import java.nio.file.Files
import java.nio.file.Path

/**
 * Persistent mapping from a pipeline [DefinitionId] to the [RunId] of its
 * most recent invocation (LF-0206).
 *
 * ## Why this exists
 *
 * Per `CANONICAL_CONTRACTS_SPEC.md §Identity`, the script-derived
 * deterministic hash is a **DefinitionId**, and a **RunId is unique per
 * invocation**. Once fresh runs stop reusing the derived hash as their
 * run id, resume needs somewhere to discover the prior invocation's id:
 * this directory is that place. One file per definition, containing the
 * latest run id; written by the CLI **before** the run starts so that a
 * run killed mid-flight is still resumable.
 *
 * ## Storage
 *
 * A flat directory of small text files. The file name is the definition
 * id value; the content is the run id value. This is deliberately not a
 * SQLite table: the mapping is a single pointer per definition, written
 * atomically by one process at a time, and must remain readable even when
 * the journal database is corrupt or locked.
 *
 * ## Failure policy
 *
 * Everything fails closed: a missing record on `--resume`, a blank
 * record, or a definition id with characters unsafe for a file name are
 * all hard errors with actionable messages.
 */
class RunIdDirectory(private val root: Path) {

    /**
     * Records [runId] as the latest invocation of [definitionId],
     * replacing any previous record.
     *
     * @throws IllegalArgumentException if the definition id contains
     *         characters unsafe for a file name.
     * @throws java.io.UncheckedIOException if the directory or file cannot
     *         be written.
     */
    fun record(definitionId: DefinitionId, runId: RunId) {
        Files.createDirectories(root)
        Files.writeString(root.resolve(fileNameFor(definitionId)), runId.value)
    }

    /**
     * Returns the [RunId] of the most recent invocation of [definitionId].
     *
     * @throws IllegalArgumentException when no run has been recorded for
     *         the definition, or when the recorded value is blank — both
     *         make `--resume` impossible and must not silently start a
     *         fresh run.
     */
    fun lastRunId(definitionId: DefinitionId): RunId {
        val file = root.resolve(fileNameFor(definitionId))
        if (!Files.isRegularFile(file)) {
            throw IllegalArgumentException(
                "No prior run recorded for this pipeline definition; " +
                    "--resume requires a previous run executed with the same --db/--control-root"
            )
        }
        val recorded = Files.readString(file).trim()
        if (recorded.isBlank()) {
            throw IllegalArgumentException(
                "Recorded run id for this pipeline definition is blank; " +
                    "delete the corrupted record $file or run without --resume"
            )
        }
        return RunId(recorded)
    }

    private fun fileNameFor(definitionId: DefinitionId): String {
        val value = definitionId.value
        require(value.matches(SAFE_FILENAME)) {
            "DefinitionId value contains characters unsafe for the run-id directory file name"
        }
        return value
    }

    private companion object {
        /** Definition ids are hex hashes or simple identifiers; keep file names path-safe. */
        val SAFE_FILENAME = Regex("""[A-Za-z0-9._-]+""")
    }
}
