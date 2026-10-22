package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.CorrelationId
import dev.rubentxu.pipeline.v2.domain.DefinitionId
import dev.rubentxu.pipeline.v2.domain.RunId
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

sealed interface StoredRunId {
    data class Found(val runId: RunId, val correlationId: CorrelationId? = null) : StoredRunId
    data object Missing : StoredRunId
}

/**
 * Persistent mapping from a pipeline [DefinitionId] to the [RunId] of its
 * Most recent invocation and, since, the [CorrelationId]
 * that the invoker supplied.
 * ## Storage format
 * Each file is two lines:
 * ```
 * <runId.value>
 * <correlationId.value>     # optional; absent for legacy records
 * ```
 * The format is line-based, not JSON, because the file is read by other
 * tools (the `--resume` path, the `pipeline observe` join, the
 * `RunIdDirectory`-based correlation reattachment) and a two-line text
 * file is robust to hand-fix and grep.
 * Legacy records (one line, runId only) are accepted; their
 * correlationId is `null`.
*/
class RunIdDirectory(private val root: Path) {

    /**
     * Records [runId] as the latest invocation of [definitionId],
     * replacing any previous record. Optionally records the
     * [correlationId] supplied by the external invoker.
     * @throws IllegalArgumentException if the definition id contains
     *         characters unsafe for a file name.
     * @throws java.io.UncheckedIOException if the directory or file cannot
     *         be written.
*/
    fun record(
        definitionId: DefinitionId,
        runId: RunId,
        correlationId: CorrelationId? = null,
    ) {
        // Fix: write-to-temp + atomic rename replaces the previous `Files.writeString`
        // which was a non-atomic truncate-then-write that could leave the file partially written
        // if the process died mid-write. After the atomic rename, the file is either the prior value
        // or the new one — never a partial value. Atomic move on POSIX is rename(2); on Windows
        // the existing file is replaced when ATOMIC_MOVE is requested.
        // The temp filename MUST be unique per call: a fixed "<target>.tmp" collides when two
        // concurrent callers share the same definition (each writes to the same temp, then races
        // to move). A monotonic counter plus the thread id makes collisions impossible without
        // needing fs-level locks.
        Files.createDirectories(root)
        val target = root.resolve(fileNameFor(definitionId))
        val temp = Files.createTempFile(root, ".${target.fileName}.", ".tmp")
        try {
            val payload = buildString {
                appendLine(runId.value)
                if (correlationId != null) {
                    appendLine(correlationId.value)
                }
            }
            Files.writeString(temp, payload)
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (t: Throwable) {
            Files.deleteIfExists(temp)
            throw t
        }
    }

    /**
     * Returns the [RunId] of the most recent invocation of [definitionId].
     * @throws IllegalArgumentException when no run has been recorded for
     *         the definition, or when the recorded value is blank — both
     *         make `--resume` impossible and must not silently start a
     *         fresh run.
*/
    fun lastRunId(definitionId: DefinitionId): RunId {
        return when (val stored = findLastRunId(definitionId)) {
            is StoredRunId.Found -> stored.runId
            StoredRunId.Missing -> throw IllegalArgumentException(
                "No prior run recorded for this pipeline definition; " +
                    "--resume requires a previous run executed with the same --db/--control-root"
            )
        }
    }

    fun findLastRunId(definitionId: DefinitionId): StoredRunId {
        val file = root.resolve(fileNameFor(definitionId))
        if (!Files.isRegularFile(file)) {
            return StoredRunId.Missing
        }
        val lines = Files.readString(file).lines()
        val recordedRunId = lines.getOrNull(0)?.trim().orEmpty()
        if (recordedRunId.isBlank()) {
            throw IllegalArgumentException(
                "Recorded run id for this pipeline definition is blank; " +
                    "delete the corrupted record $file or run without --resume"
            )
        }
        val recordedCorrelation = lines.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
        return StoredRunId.Found(
            runId = RunId(recordedRunId),
            correlationId = recordedCorrelation?.let { CorrelationId(it) },
        )
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
