package dev.rubentxu.pipeline.v2.sdk

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.PluginStepException
import dev.rubentxu.pipeline.v2.domain.workspace.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.workspace.PathAnchor
import dev.rubentxu.pipeline.v2.domain.workspace.PathResolution
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspacePathError
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspacePathResolver
import java.nio.file.Path

/**
 * The single path-resolution entry point for OFFICIAL_PLUGIN Steps.
 *
 * **RP034-E / ADR-0100.** A workspace-aware Step resolves a user path by
 * declaring an anchor and reading the current [ExecutionLocation]. It never
 * rebuilds its own base out of a capability value, and it never consults
 * process-global state.
 *
 * Before this seam existed, each plugin carried its own resolver. The utilities
 * family shared one — `CoreUtilsReadJsonStepDefinition.resolvePath` — which was
 * `if (absolute) path else workspaceRoot.resolve(path)`: no confinement, no `..`
 * check, no normalisation. `core-utils.zip` and `core-utils.unzip` each
 * compensated with a hand-rolled `path.startsWith(workspaceRoot)` guard, which
 * was applied against the *effective working directory* rather than the
 * workspace boundary. Inside a `dir(...)` scope those two notions diverge, so a
 * path legitimately inside the workspace could be refused, and an unnormalised
 * `sub/../..` path could pass the prefix check.
 *
 * [WorkspacePathResolver] is pure and total, so confinement is decided before
 * any byte is read and is testable without a filesystem. This file adds only the
 * binding from a typed rejection to the plugin failure algebra — no policy.
 *
 * Deliberately dependency-inward: the plugins consume `ExecutionLocation`
 * straight from `:pipeline-domain` rather than the application-side
 * `ExecutionLocationCapability` interface, so the plugin never depends on the
 * adapter that produces it.
 */
object WorkspacePathAnchors {

    /**
     * Resolve [userPath] against [location.cwd].
     *
     * The `CURRENT_DIRECTORY` anchor is what `dir(...)` moves: relative inputs
     * follow the active scope, while the workspace root stays the boundary a
     * resolved path must not escape.
     */
    fun currentDirectory(
        location: ExecutionLocation,
        stepKey: String,
        userPath: String,
    ): Path = resolve(location, PathAnchor.CURRENT_DIRECTORY, stepKey, userPath)

    /**
     * Resolve [userPath] against [anchor], returning an authorised absolute path
     * or raising a USER-class plugin failure carrying the typed reason.
     */
    fun resolve(
        location: ExecutionLocation,
        anchor: PathAnchor,
        stepKey: String,
        userPath: String,
    ): Path = when (val resolution = WorkspacePathResolver.resolve(location, anchor, userPath)) {
        is PathResolution.Resolved -> resolution.path
        is PathResolution.Rejected -> throw PluginStepException(
            failure = PipelineFailure(
                kind = FailureKind.USER,
                message = "$stepKey: ${describe(resolution.reason)}",
            ),
        )
    }

    /**
     * Project a typed refusal into the diagnostic a pipeline author sees.
     *
     * Total and exhaustive over [WorkspacePathError]: adding a case to the ADT
     * breaks this function at compile time rather than silently falling through
     * to a generic message.
     */
    private fun describe(reason: WorkspacePathError): String = when (reason) {
        is WorkspacePathError.InvalidPath ->
            "path is empty"

        is WorkspacePathError.AbsolutePathNotAllowed ->
            "absolute path outside the workspace root is not allowed: '${reason.raw}'"

        is WorkspacePathError.EscapesWorkspace ->
            "path escapes the workspace root '${reason.root}': '${reason.raw}'"

        is WorkspacePathError.SymlinkEscape ->
            "path resolves outside the workspace root via a link: '${reason.path}'"

        is WorkspacePathError.ProtectedWorkspaceRoot ->
            "'${reason.operation}' refuses to destroy the protected workspace root '${reason.root}'"

        is WorkspacePathError.WorkspaceUnavailable ->
            "workspace is unavailable: ${reason.reason}"

        is WorkspacePathError.ConflictingWorkspaceMode ->
            "conflicting workspace modes: invocation directory '${reason.invocationDirectory}' " +
                "and explicit '${reason.explicit}'"
    }
}
