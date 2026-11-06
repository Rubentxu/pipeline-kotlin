package dev.rubentxu.pipeline.v2.sdk.files

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.PipelineStepException
import java.nio.file.Path

/**
 * A file the product refuses to read into memory.
 *
 * ## What it means
 *
 * The file IS there: this is not a file that is missing, and no caller may
 * read it as one. What happened is that the file does not fit the limit the
 * product reads under, so the read was refused and nothing of it was read
 * into memory.
 *
 * ## Why it is a failure and not an observation
 *
 * A read that finds nothing answers `exists=false`, which is an observation
 * the caller can carry. A read that finds too much has no content to carry,
 * and reporting it as an observation would leave the caller with a file that
 * is not there — a statement about the world that is false. So this refusal is
 * a typed failure, and its kind is USER: the request was well formed and the
 * file was readable, and the thing that stops it is the size of the file.
 *
 * ## What a caller may take for granted
 *
 * The file was not read: no content, no digest and no size of it were
 * produced, and none of them is in this exception either. What this carries
 * is the boundary — the path, the lower bound of the size, and the limit that
 * was applied — so that a caller can say WHICH file was refused and WHY.
 */
class FileReadTooLargeException(
    /** The path the pipeline asked for. */
    val path: Path,
    /** The size of the file, as a lower bound: the file holds at least this many bytes. */
    val atLeastBytes: Long,
    /** The limit the read was refused against. */
    val limitBytes: Long,
) : PipelineStepException(
    PipelineFailure(
        kind = FailureKind.USER,
        message = "readFile: the file at $path holds at least $atLeastBytes bytes, over the " +
            "limit of $limitBytes bytes; the read was refused and no content was produced.",
    ),
)
