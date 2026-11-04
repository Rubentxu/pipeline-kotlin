package dev.rubentxu.pipeline.v2.runtime

import dev.rubentxu.pipeline.v2.domain.workspace.WorkspaceOwnership
import java.nio.file.Path

/**
 * What crosses the CLI boundary into the runtime: where stages share a
 * directory, and who owns that directory.
 * Grouped into one value because the two were previously decided in two places
 * and could disagree — which is exactly what happened, and what allowed
 * `deleteDir()` to erase a user's project under the local-first default.
 *
 * ## Why it lives in its own file
 *
 * It is a FACT that crosses a boundary, and the boundary is the interesting
 * part: this value is what the CLI DECIDES and the runtime RECEIVES. It used
 * to live in the same file as the CLI's own decision procedure
 * (`WorkspaceIntent`), which made the dependency go the wrong way — the runtime
 * reached into a file that belonged to the CLI, and the CLI's decision
 * procedure reached into a type that belonged to the runtime.
 *
 * Splitting them makes the direction visible: the CLI reads this value and
 * hands it over; the runtime reads this value and never reads the CLI.
*/
data class RuntimeWorkspaceTransport(
    val base: Path?,
    val ownership: WorkspaceOwnership,
)
