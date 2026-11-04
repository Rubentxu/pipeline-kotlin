package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * The confinement a run is put inside: the tool that applies it, the workspace that
 * is the only place of the operator's the run may write, and the argv that runs a
 * command inside it.
 *
 * ## Why this is a value and not a function that builds an argv
 *
 * TWO things need the SAME confinement: the launcher, which starts the run inside
 * it, and the provider, which probes the host to find out whether this host can
 * confine at all. A probe that measured a different plan than the one that runs
 * would attest to nothing — it would measure one boundary and promise another.
 * So the plan is a value, and both sides build it from this class.
 *
 * ## The order of the mounts is part of the plan
 *
 * The mounts are applied in the order they are written, and the order is not
 * cosmetic. It was MEASURED on the host this was written against:
 *
 *  - the root is bound READ-ONLY, so the whole system is visible and nothing in
 *    it can be written;
 *  - `/tmp` is a private tmpfs, so the run can scratch without touching the
 *    host's `/tmp`;
 *  - the paths the run must READ are bound read-only AFTER the tmpfs, because a
 *    tmpfs mounted LATER hides them: the run's own script lives in the control
 *    directory, which usually lives under `/tmp`, and a run that cannot see its
 *    own script does not run at all;
 *  - the workspace is bound read-write AFTER those read-only binds, because a
 *    read-only bind of a directory that CONTAINS the workspace would otherwise
 *    leave the workspace read-only too, and the run would have nowhere to write.
 *
 * ## What the confinement does NOT do
 *
 * It does not confine the EVALUATION of a pipeline script: that happens in the
 * product's own process, before any step exists. A run whose author is not
 * trusted is therefore still refused by the admission core, and the capability
 * that would be missing is named there.
 */
class SandboxConfinement(
    /** The absolute path of the tool that applies the confinement. */
    val binary: Path,
    /** The only place of the operator's that the run may write. */
    val workspace: Path,
    /**
     * The paths the run must be able to READ that the private `/tmp` would hide.
     *
     * The control directory of the run is one of them, and it has to be: the
     * user's script lives there, and a run that cannot see its own script does
     * not run at all. It is bound READ-ONLY, so the run can read what it is
     * about to execute and can write nothing there — the exit code the run
     * publishes is written by the wrapper, outside the boundary.
     */
    val readablePaths: List<Path> = emptyList(),
) {

    /**
     * The argv that runs [command] inside the confinement.
     *
     * @param command the command to run, as it would be run without a confinement.
     * @return the argv, with the confinement's flags in front of the command.
     */
    fun argv(command: List<String>): List<String> = buildList {
        add(binary.toString())
        addAll(NAMESPACE_FLAGS)
        add("--die-with-parent")
        // The root, read-only: the whole system is visible and none of it can be written.
        addAll(listOf("--ro-bind", "/", "/"))
        // A private /tmp, so the run can scratch without touching the host's.
        addAll(listOf("--tmpfs", "/tmp"))
        // The paths the run must read, read-only. AFTER the tmpfs, or the tmpfs hides
        // them and a run that cannot see its own script does not run at all.
        readablePaths.forEach { path ->
            addAll(listOf("--ro-bind", path.toString(), path.toString()))
        }
        // The workspace, read-write. AFTER the readable paths: a read-only bind of a
        // directory that CONTAINS the workspace would otherwise leave the workspace
        // read-only too, and the run would have nowhere to write (measured).
        addAll(listOf("--bind", workspace.toString(), workspace.toString()))
        // A private /proc and /dev, so the run reads its own process tree and no device.
        addAll(listOf("--proc", "/proc"))
        addAll(listOf("--dev", "/dev"))
        addAll(listOf("--chdir", workspace.toString()))
        add("--")
        addAll(command)
    }

    companion object {
        /** The name of the tool that applies the confinement. */
        const val TOOL: String = "bwrap"

        /**
         * The namespaces the run gets its own.
         *
         * The user namespace is what makes the rest mean anything: inside it the
         * run holds no capability over the host, so the process and network
         * namespaces it gets are namespaces it cannot leave.
         */
        private val NAMESPACE_FLAGS: List<String> = listOf(
            "--unshare-user",
            "--unshare-pid",
            "--unshare-net",
            "--unshare-ipc",
            "--unshare-uts",
        )

        /**
         * The absolute path of the tool on this host, or null when this host has none.
         *
         * The path is ABSOLUTE and never the bare name: the child that applies the
         * confinement has a normalised PATH (the profile keeps only the prefixes it
         * was told to keep), so a bare name would be looked up in a PATH that may
         * not carry it.
         *
         * @param name the tool to look for.
         * @return the path of the first executable with that name on the PATH, or null.
         */
        fun locate(name: String = TOOL): Path? {
            val searchPath = System.getenv("PATH") ?: return null
            return searchPath
                .split(File.pathSeparatorChar)
                .filter { it.isNotBlank() }
                .asSequence()
                .map { File(it, name).toPath() }
                .firstOrNull { Files.isExecutable(it) }
        }
    }
}
