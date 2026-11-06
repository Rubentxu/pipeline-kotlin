package dev.rubentxu.pipeline.v2.sdk.runtime.durable

/**
 * The agent's process is launched with an environment the runtime composed, and with nothing else.
 *
 * ## The guarantee
 *
 * The agent's process is launched with an environment the runtime COMPOSED: the variables the
 * protocol needs, and the agent's own secret. It does not inherit the runtime's environment, and
 * so no credential the controller owns can reach it — not the passphrase that opens the
 * credential store, and not anything else that happens to sit in the controller's environment.
 *
 * ## Why the list is a list of PERMISSION and not a list of vetoes
 *
 * A list of vetoes can only remove what someone remembered to name. The controller's environment
 * holds credentials that this file has never heard of, and a credential added to it after this
 * list was written would cross into the child untouched. A list of permission cannot have that
 * hole: a variable that is not named here does not pass, whatever it holds.
 *
 * ## The compatibility list, and why each name is on it
 *
 * The names below are the ones the agent's process receives from the host. Each one is there for
 * a reason that was MEASURED on the reference host, not for a reason that was assumed:
 *
 *  - `PATH` — the command that starts the agent is resolved against an environment, and which
 *    environment that is, is a detail of the launcher rather than a contract: measured on the
 *    reference host, a `ProcessBuilder` resolves that command against the LAUNCHER's own PATH
 *    (a command that exists only in the child's PATH is not found, and one that exists only in
 *    the launcher's PATH is found even when the child's PATH names no directory that holds it).
 *    The name is kept so that the launch does not depend on that detail of the JDK, and so that
 *    the agent's process carries the PATH of the host it runs on.
 *  - `LANG`, `LC_ALL`, `LC_CTYPE` — the locale decides the JVM's encoding for FILE NAMES. Without
 *    them the agent's JVM decodes a path that came in the argv as ASCII: measured on the
 *    reference host, a path with a non-ASCII character arrives as `dir-caf??` and the file it
 *    names does not exist, while with the locale present the path arrives intact and the file
 *    exists. The agent opens the FIFOs by paths that arrive in the argv, so without the locale a
 *    control directory that is not plain ASCII breaks the rendezvous.
 *
 * The variables that are NOT on the list, and what was measured about each:
 *
 *  - `JAVA_HOME` — the launcher does not use an inherited `JAVA_HOME` to choose the runtime
 *    image: measured on the reference host, a child launched from one JDK's own binary reports
 *    that JDK's `java.home` and `java.version` even when `JAVA_HOME` names a different, real
 *    JDK.
 *  - `HOME` — the JVM takes `user.home` from the host's account database, not from the
 *    environment: measured, a JVM with no `HOME` still reports the account's home directory.
 *  - `TMPDIR` — the JVM's `java.io.tmpdir` is `/tmp` whether or not the variable is there:
 *    measured, a JVM with `TMPDIR` pointing somewhere else still reports `/tmp`.
 *
 * ## What the agent's own secret is
 *
 * The secret the agent registers so it can redact the child's bytes travels in the environment,
 * under [AgentRendezvous.AGENT_SECRET_ENV], and never in the argv: an argument is readable
 * through `/proc/<pid>/cmdline` by every user on the host, while an environment is exposed to
 * its owner alone. It is the runtime's own value: a copy that came in with the inherited
 * environment is somebody else's value, and it does not survive the composition.
 *
 * ## What this does not own
 *
 *  - The NAME of the variable that carries the secret, which belongs to
 *    [AgentRendezvous] because the agent reads it there.
 *  - The environment of the SHELL's child, which belongs to
 *    [ShellLaunchPreparation.composeEnvironment] and is a different child with a different
 *    authority.
 *  - The credentials the PIPELINE declares for a step: those are delivered to the shell's child,
 *    and the agent never sees them.
 *
 * ## The instrument that can fail this
 *
 * [AgentProcessEnvironmentTest] reads the builder the agent would be launched with, and asserts
 * the CLOSED set of names that crosses into it.
 */
internal object AgentProcessEnvironment {

    /**
     * The names the agent's process inherits from the host.
     *
     * The reasons each one is here are written above, with the measurement that established
     * them. A name that is not on this list does not pass, whatever its value holds.
     */
    private val INHERITED: Set<String> = setOf("PATH", "LANG", "LC_ALL", "LC_CTYPE")

    /**
     * Composes the environment the agent's process is launched with.
     *
     * The inherited variables come first and the secret last, and a variable that is not on the
     * list is not composed at all: the result is a NEW map, and the ambient one is not modified.
     *
     * @param ambient The environment of the process that launches the agent. The plan selects
     *   from it; it does not cross into the agent wholesale.
     * @param secret The runtime's own secret for the agent.
     * @return The environment the agent's process is launched with.
     */
    fun compose(ambient: Map<String, String>, secret: String): Map<String, String> {
        val plan = LinkedHashMap<String, String>()
        for (name in INHERITED) {
            val value = ambient[name] ?: continue
            plan[name] = value
        }
        plan.putAll(AgentRendezvous.agentEnvironment(secret))
        return plan
    }
}
