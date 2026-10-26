package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import dev.rubentxu.pipeline.v2.domain.SecretHandle
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.EnumSet

/**
 * Prepares one durable shell launch: the files the child will run, and the environment it will see.
 *
 * ## What this owns
 *
 * A launch has two preparations that happen before the child exists, and both of them are
 * policies rather than plumbing:
 *
 *  - the FILES: the user's script, the copy that the original kept, and the wrapper that binds
 *    them to the protocol's paths;
 *  - the ENVIRONMENT: the durable sentinels, the sandbox profile's deny-list and path
 *    normalisation, and the caller's variables.
 *
 * Both live here so that [DurableShellExecutor] reads as a coordinator. The executor still owns
 * the launch, the watchdog and the terminal decision; it does not own the rules that decide what
 * the child is handed.
 *
 * ## Why the environment is composed as a function
 *
 * The composition used to be a method that mutated the launcher's process environment in place.
 * That made the policy impossible to state as a rule and impossible to check without starting a
 * process: the only way to observe the result was to launch a child and look at its environment.
 * Here the composition takes the ambient environment and returns the child's environment, so the
 * rules can be read, and checked, as rules.
 *
 * The order is the guarantee and it is unchanged: the durable sentinels first (so the wrapper's
 * own machinery is protected from the cookie-scan kill), then the sandbox profile (its deny-list
 * and its path normalisation), then the caller's variables, and finally the path is normalised
 * again, because a caller-supplied path would otherwise smuggle entries past the keep-set.
 *
 * ## What this does NOT own
 *
 * The transcript's name belongs to [DurableShellFiles]; the names of the files themselves belong
 * to [ShellProtocolLayout]; the launch's identity belongs to [LaunchedShell]. Nothing here
 * duplicates any of them.
 */
internal class ShellLaunchPreparation(
    private val protocol: ShellProtocolLayout,
    private val opId: String,
    private val heartbeatCheckInterval: Long,
    private val pumpedViaAgent: Boolean,
) {

    /**
     * Writes the user's script, its copy, and the wrapper, and makes the script and the wrapper
     * executable.
     *
     * The copy exists because the original kept one, so dropping it would be a compatibility
     * difference nobody asked for. The wrapper embeds only the cookie, never the script text,
     * which is what keeps the user's script out of the process arguments.
     *
     * The paths are not spelled here: they come from [ShellProtocolLayout], and the same layout
     * is handed to the wrapper builder, so the file that is written and the file the wrapper
     * refers to cannot drift apart.
     */
    fun materialise(scriptContent: String) {
        val executable = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )
        Files.writeString(protocol.scriptFile, scriptContent)
        Files.writeString(protocol.scriptCopyFile, scriptContent)
        Files.setPosixFilePermissions(protocol.scriptFile, executable)

        Files.writeString(protocol.wrapperFile, wrapperContent(protocol.scriptFile))
        Files.setPosixFilePermissions(protocol.wrapperFile, executable)
    }

    /**
     * Builds the wrapper's content: the script that binds the protocol's paths, runs the user's
     * script, and publishes the exit code by renaming onto the result file.
     *
     * The real cookie value is embedded in the content rather than in the process arguments, so
     * the watchdog can find the victim's processes by scanning their environment, while the
     * sentinel protects the wrapper's own machinery from being killed.
     *
     * @param scriptPath The path the wrapper runs the script by. It is an argument because the
     *   caller's own protocol may place the script outside the control directory.
     * @return The wrapper's content.
     */
    fun wrapperContent(scriptPath: Path): String {
        val cookieFileEscaped = escapeForShell(protocol.cookieFile.toString())
        val logFileEscaped = escapeForShell(protocol.consoleLog.toString())
        val resultFileEscaped = escapeForShell(protocol.resultFile.toString())
        val resultTmpEscaped = escapeForShell(protocol.resultTmpFile.toString())
        val scriptPathEscaped = escapeForShell(scriptPath.toString())

        // The child's descriptors are redirected by the LAUNCHER, not by the wrapper: the
        // launcher decides whether the bytes go to the value file, to the transcript or to a
        // pipe it pumps itself. The wrapper therefore only BINDS the names it publishes; it
        // never opens the transcript for writing, because when the bytes are piped the
        // composed sink is the single writer of only sanitized bytes, and a second writer here
        // would make the same bytes durable in two places.
        // When the bytes go through the ingest agent the wrapper also ignores the broken-pipe
        // signal, so the user's process survives the agent's death instead of dying on the
        // next write.

        return buildString {
            append("#!/bin/bash\n")
            if (pumpedViaAgent) {
                // The disposition is inherited across exec, so the user's script, and everything
                // it spawns, runs with the broken-pipe signal ignored. The trap is the first
                // non-comment line so it is in effect for every subsequent fork.
                append("trap '' PIPE\n")
            }
            // The real cookie is embedded in the content, never in the arguments. This is the
            // value the watchdog looks for when it scans a process's environment.
            append("export PIPELINE_OP_COOKIE='$opId'\n")
            append("COOKIE_FILE='$cookieFileEscaped'; ")
            append("LOG_FILE='$logFileEscaped'; ")
            append("RESULT_FILE='$resultFileEscaped'; ")
            append("RESULT_TMP='$resultTmpEscaped'; ")
            append("CHECK_INTERVAL=$heartbeatCheckInterval; ")
            // The cookie file holds the heartbeat's process id, and it is also what tells the
            // watchdog that the wrapper's machinery is alive.
            append("echo \$\$ > \"\$COOKIE_FILE\"; ")
            // The heartbeat's process id is captured so it can be killed explicitly at exit: when
            // the transcript is piped, the heartbeat subshell holds the pipe's write end, and
            // leaving it running delays the end of the stream by up to the check interval.
            append("HB_PID=\$!; ")
            // The user's script runs by path. Its content is never in the arguments.
            append("'$scriptPathEscaped'; ")
            append("EXIT_CODE=\$?; ")
            // The exit code is published atomically: written to the temporary file, then renamed.
            append("echo \$EXIT_CODE > \"\$RESULT_TMP\"; ")
            append("mv \"\$RESULT_TMP\" \"\$RESULT_FILE\"; ")
            // The cookie file is removed to signal completion. The removal is silenced: the
            // cookie is control-plane bookkeeping, not the user's output, so it must never write
            // into the channel that becomes the captured value and the transcript. An unsilenced
            // removal leaks whenever the host's removal command is verbose, wrapped or shimmed.
            append("rm -f \"\$COOKIE_FILE\" >/dev/null 2>&1; ")
            append("kill \$HB_PID 2>/dev/null; ")
            append("exit \$EXIT_CODE")
        }
    }

    /**
     * Composes the environment the child will see.
     *
     * @param ambient The environment the launcher inherited. It is the child's starting point,
     *   exactly as it was when the composition used to mutate the launcher's own map in place.
     * @param sandbox The active profile, its allow-extra set and its path keep-set.
     * @param userEnv The caller's variables, still secret-bearing.
     * @return The child's environment. It is a new map: the caller's handles are materialised at
     *   this single choke point, and the caller's map is never modified.
     */
    fun composeEnvironment(
        ambient: Map<String, String>,
        sandbox: SandboxConfig,
        userEnv: Map<String, SecretHandle>,
    ): Map<String, String> {
        val child = LinkedHashMap(ambient)

        // The sentinel is inherited by the wrapper and by the heartbeat, and it is what protects
        // the wrapper's own machinery from the cookie-scan kill. The real cookie is written by
        // the wrapper itself, into the wrapper's content, never into the arguments.
        child["PIPELINE_OP_COOKIE"] = SENTINEL_COOKIE
        child["DURABLE_SH_COOKIE"] = "please-do-not-kill-me-$opId"
        child["DURABLE_SH_OPID"] = opId

        // The profile branch: the local profile applies the deny-list and the path
        // normalisation; no profile is pass-through. The operating-system profile cannot be
        // instantiated, so reaching here means no profile or the local one.
        if (sandbox.profile == SandboxProfile.LOCAL) {
            val filtered = child.applyDenyList(sandbox.allowExtra)
            val javaHome = filtered["JAVA_HOME"]
            val m2Home = filtered["M2_HOME"]
            child.clear()
            child.putAll(filtered.normalizePath(sandbox.pathKeep, javaHome, m2Home))
        }

        // The caller's variables arrive last, and they are the only secret-bearing ones: the
        // handles are materialised here, at the single choke point.
        if (userEnv.isEmpty()) return child

        val coerced: Map<String, String> = EnvModel.apply(userEnv)
            .mapValues { entry -> entry.value.materialize() }
        child.putAll(coerced)

        // A caller-supplied path would otherwise smuggle entries past the keep-set, so the
        // normalisation is applied again after the merge.
        if (coerced.containsKey("PATH") && sandbox.profile == SandboxProfile.LOCAL) {
            val callerPath = coerced["PATH"] ?: ""
            val normalised = mapOf("PATH" to callerPath).normalizePath(
                sandbox.pathKeep,
                child["JAVA_HOME"],
                child["M2_HOME"],
            )["PATH"] ?: ""
            if (normalised != callerPath) {
                child["PATH"] = normalised
            }
        }

        // The handles are NOT wiped here. The credentials executor shares one environment map
        // across nested shell invocations, so wiping at this point would destroy the bytes a
        // later invocation still needs. The wipe belongs to the credentials' own close.
        return child
    }

    /** Escapes a path for a single-quoted shell word. */
    private fun escapeForShell(path: String): String {
        return path.replace("'", "'\\''")
    }

    private companion object {
        /**
         * The cookie value the wrapper's machinery inherits until the wrapper writes the real
         * one. The watchdog looks for the real value, so this one identifies the machinery it
         * must not kill.
         */
        const val SENTINEL_COOKIE: String = "__pipeline_protected__"
    }
}
