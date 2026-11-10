package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.service.ServiceRefusal
import java.nio.file.Files
import java.nio.file.Path

/**
 * The scripts and the workspaces a client may name.
 *
 * ## What this admits
 *
 * A client of the socket names a script and, maybe, a workspace BY NAME:
 * the name of a file inside the directory the operator declared, never a
 * path. This turns that name into the file it is, or refuses it.
 *
 * ## Why the name is not a path
 *
 * Because a request that carried a path would be a request to run whatever
 * the client can name, and a socket on a machine must not be that. The
 * directory the operator declared IS the boundary: everything inside it may
 * be named, and nothing outside it may.
 *
 * ## Why the resolution follows the LINKS
 *
 * Because a name that resolves to a symbolic link is the link's TARGET, and
 * a target outside the directory is outside the boundary. The comparison is
 * made on the real path of the candidate and on the real path of the root,
 * so a link that points out of the directory does not buy the client
 * anything: it is refused exactly like a name with `..` in it.
 *
 * ## What it does NOT decide
 *
 * Whether the script COMPILES, and whether the run succeeds. That is the
 * run's own business, and it is reported by the run's own facts.
 */
internal class ServiceStartAdmission(
    private val scriptsRoot: Path?,
    private val workspacesRoot: Path?,
) {

    /**
     * The file [script] names and the directory [workspace] names.
     *
     * A refusal, and not an exception: the names come from the wire, and a
     * name that does not resolve is an answer the client has to read.
     */
    fun admit(script: String, workspace: String?): ServiceStartAdmissionOutcome {
        val scriptPath = resolve(scriptsRoot, script, expected = Expectation.FILE)
            ?: return ServiceStartAdmissionOutcome.Refused(ServiceRefusal.ScriptNotAdmitted(script))
        val workspacePath = if (workspace == null) {
            null
        } else {
            resolve(workspacesRoot, workspace, expected = Expectation.DIRECTORY)
                ?: return ServiceStartAdmissionOutcome.Refused(ServiceRefusal.WorkspaceNotAdmitted(workspace))
        }
        return ServiceStartAdmissionOutcome.Admitted(scriptPath, workspacePath)
    }

    /**
     * The file [name] names inside [root], or null when it names none.
     *
     * The four refusals are in one place and in this order, because the
     * order is what makes the boundary: the name is checked for SHAPE
     * before it is resolved, and the resolution is checked for CONTAINMENT
     * before the file is checked for being there.
     */
    private fun resolve(root: Path?, name: String, expected: Expectation): Path? {
        if (root == null) return null
        if (name.isBlank()) return null
        val candidate = try {
            Path.of(name)
        } catch (_: Exception) {
            return null
        }
        if (candidate.isAbsolute) return null
        if (candidate.any { it.toString() == PARENT }) return null
        val base = try {
            root.toRealPath()
        } catch (_: Exception) {
            return null
        }
        val resolved = try {
            base.resolve(candidate).toRealPath()
        } catch (_: Exception) {
            return null
        }
        if (!resolved.startsWith(base)) return null
        val isThere = when (expected) {
            Expectation.FILE -> Files.isRegularFile(resolved)
            Expectation.DIRECTORY -> Files.isDirectory(resolved)
        }
        return if (isThere) resolved else null
    }

    /**
     * What the resolved path has to be.
     */
    private enum class Expectation { FILE, DIRECTORY }

    private companion object {
        /** The name of the directory step that walks up. */
        const val PARENT: String = ".."
    }
}

/**
 * What the admission decided about the names of a start request.
 *
 * Two shapes, and a caller has to act on both, so it reads them with an
 * exhaustive `when`.
 */
internal sealed interface ServiceStartAdmissionOutcome {

    /** The names resolve to a script and, maybe, to a workspace. */
    data class Admitted(val script: Path, val workspace: Path?) : ServiceStartAdmissionOutcome

    /** A name does not resolve, and the refusal says which one. */
    data class Refused(val refusal: ServiceRefusal) : ServiceStartAdmissionOutcome
}
