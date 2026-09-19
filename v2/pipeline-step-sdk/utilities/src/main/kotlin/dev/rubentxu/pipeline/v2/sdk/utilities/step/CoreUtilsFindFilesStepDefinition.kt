package dev.rubentxu.pipeline.v2.sdk.utilities.step

import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.PluginStepException
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.RecoveryPolicy
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.domain.step.WORKSPACE_IDENTITY_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.WorkspaceIdentity
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.FileEntry
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.FindFilesInput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.FindFilesOutput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.FindFilesPattern
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.PathMatcher
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo
import kotlin.streams.toList

/**
 * `core-utils.findFiles` OFFICIAL_PLUGIN Step (LFC-2E2 utilities, Slice 2 / S2.3).
 *
 * Jenkins reference: pipeline-utility-steps-plugin::FindFilesStep
 * (MIT, CloudBees). Behaviour summary in
 * `docs/v2/07-uat/S2_FINDFILES_JENKINS_REFERENCE.md`.
 *
 * Strategy:
 *  - Pattern.None       -> direct children of the base directory only.
 *  - Pattern.Glob(g, e) -> recursive scan; PathMatcher("glob:...") keeps
 *                          files matching g; then drops files matching e
 *                          (if e != null). Files.walk does not follow
 *                          symlinks (no FOLLOW_LINKS) — symlinks appear
 *                          in the listing as themselves and are not
 *                          traversed, matching Jenkins' FilePath.list
 *                          semantics.
 *  - maxResults cap of 100_000; on overflow a typed PluginStepException
 *    with FailureKind.USER is raised before the response is constructed.
 */
class CoreUtilsFindFilesStepDefinition : StepDefinition<FindFilesInput, FindFilesOutput> {

    override val contract: StepContract<FindFilesInput, FindFilesOutput> =
        StepContract(
            key = CoreUtilsFindFilesKey.VALUE,
            descriptor = StepDescriptor(
                stepId = CoreUtilsFindFilesKey.VALUE.value,
                name = "core-utils.findFiles",
                configRef = "",
                pluginId = "utilities",
                pluginVersion = "0.0.1-dev",  // overridden by manifest at registration
                executionLocation = ExecutionLocation.CONTROLLER,
                effects = listOf(Effect.READ_ONLY),
                replayPolicy = ReplayPolicy.MEMOIZED,
                recoveryPolicy = RecoveryPolicy.None,
            ),
            inputCodec = CoreUtilsFindFilesInputCodec,
            outputCodec = CoreUtilsFindFilesOutputCodec,
            requiredCapabilities = setOf<StepCapability>(WORKSPACE_IDENTITY_CAPABILITY),
        )

    override val handler: StepHandler<FindFilesInput, FindFilesOutput> =
        StepHandler { input, ctx ->
            val workspaceRoot: Path = ctx.capabilities
                .get<WorkspaceIdentity>(WORKSPACE_IDENTITY_CAPABILITY)
                .workspaceRoot

            val base = CoreUtilsReadJsonStepDefinition.resolvePath(workspaceRoot, input.base)
            if (!base.exists()) {
                throw PluginStepException(
                    failure = PipelineFailure(
                        kind = FailureKind.USER,
                        message = "core-utils.findFiles: base path does not exist: ${base}",
                    ),
                )
            }
            if (!base.isDirectory()) {
                throw PluginStepException(
                    failure = PipelineFailure(
                        kind = FailureKind.USER,
                        message = "core-utils.findFiles: base path is not a directory: ${base}",
                    ),
                )
            }

            val includeMatcher: PathMatcher? = when (val p = input.pattern) {
                is FindFilesPattern.None -> null
                is FindFilesPattern.Glob -> buildMatcher(p.glob)
            }
            val excludeMatcher: PathMatcher? = when (val p = input.pattern) {
                is FindFilesPattern.None -> null
                is FindFilesPattern.Glob -> p.excludes?.let { ex -> buildMatcher(ex) }
            }

            // Build the candidate stream:
            //   Pattern.None  -> Files.list(base)   (direct children)
            //   Pattern.Glob  -> Files.walk(base)   (recursive, no FOLLOW_LINKS)
            val candidates: Sequence<Path> = when (input.pattern) {
                is FindFilesPattern.None -> Files.list(base).use { stream ->
                    stream.toList().asSequence()
                }
                is FindFilesPattern.Glob -> Files.walk(base).use { stream ->
                    stream.toList().asSequence()
                }
            }

            val entries = buildList {
                var count = 0
                for (candidate in candidates) {
                    if (count >= MAX_RESULTS) {
                        throw PluginStepException(
                            failure = PipelineFailure(
                                kind = FailureKind.USER,
                                message = "core-utils.findFiles: result count exceeded the cap of " +
                                    "$MAX_RESULTS — narrow the glob or excludes",
                            ),
                        )
                    }
                    if (candidate == base) continue  // skip the base itself

                    // Apply the include matcher (Pattern.Glob only) against
                    // the workspace-relative path.
                    if (includeMatcher != null) {
                        val rel = candidate.relativeTo(base)
                        if (!includeMatcher.matches(rel)) continue
                    }
                    // Apply the exclude matcher (always optional) against
                    // the same relative path.
                    if (excludeMatcher != null) {
                        val rel = candidate.relativeTo(base)
                        if (excludeMatcher.matches(rel)) continue
                    }

                    add(toEntry(candidate, base))
                    count++
                }
            }

            FindFilesOutput(
                basePath = base.toString(),
                patternEcho = input.pattern,
                files = entries,
            )
        }

    private fun toEntry(candidate: Path, base: Path): FileEntry {
        val rel = candidate.relativeTo(base)
        // Always forward slashes: this is the cross-platform path
        // representation Jenkins' FileWrapper uses too.
        val relString = if (rel.toString() == ".") "" else rel.toString().replace('\\', '/')
        val isDir = candidate.isDirectory()
        val name = candidate.fileName?.toString() ?: relString
        val finalPath = if (isDir && relString.isNotEmpty() && !relString.endsWith("/")) {
            "$relString/"
        } else {
            relString
        }
        val length = if (isDir) 0L else runCatching { Files.size(candidate) }.getOrDefault(0L)
        val lastModified = runCatching {
            candidate.getLastModifiedTime().toMillis()
        }.getOrDefault(0L)

        return FileEntry(
            name = name,
            path = finalPath,
            directory = isDir,
            length = length,
            lastModified = lastModified,
        )
    }

    companion object {
        /** Soft cap on the number of matches returned in a single call. */
        internal const val MAX_RESULTS = 100_000

        /**
         * Build a [PathMatcher] for the given glob. Java NIO's standard
         * glob syntax with two-stars-and-slash requires `**` to consume
         * at least one segment, so a file at the root does NOT match a
         * pattern like `two-stars-slash-star.txt`. This breaks Jenkins
         * compatibility, where `findFiles(glob: ...)` is expected to
         * match files directly under the base too.
         *
         * To preserve the Jenkins contract, when the user-supplied glob
         * starts with `two-stars-slash` we ALSO accept the glob with
         * that prefix stripped. Both matchers are wrapped into a single
         * "matchesAny" [PathMatcher] so a candidate is kept if either
         * one accepts it.
         */
        internal fun buildMatcher(glob: String): PathMatcher {
            val primary = java.nio.file.FileSystems.getDefault()
                .getPathMatcher("glob:$glob")
            val stripped = glob.removePrefix("**/")
            return if (stripped == glob) {
                primary
            } else {
                val secondary = java.nio.file.FileSystems.getDefault()
                    .getPathMatcher("glob:$stripped")
                PathMatcher { path -> primary.matches(path) || secondary.matches(path) }
            }
        }
    }
}
