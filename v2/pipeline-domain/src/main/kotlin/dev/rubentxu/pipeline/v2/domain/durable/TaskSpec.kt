package dev.rubentxu.pipeline.v2.domain.durable

/**
 * Interpreter used to run a [TaskSpec.ShellScriptTask].
 *
 * A closed enum — not a free-form binary path — so that task definitions
 * cannot inject arbitrary executables through configuration. New
 * interpreters are a domain decision, not a caller option.
 */
enum class InterpreterPolicy(val binary: String) {
    /** POSIX shell, the Jenkins-`sh` baseline. */
    POSIX_SH("/bin/sh"),

    /** Bash, for scripts that rely on bash-only features. */
    BASH("/bin/bash"),
}

/**
 * Specification of ONE durable task to execute (LF-0301).
 *
 * The closed set is [ShellScriptTask] and [ExecTask] — exactly the two
 * process shapes the durable runtime supports (see
 * `docs/v2/03-specifications/DURABLE_TASK_RUNTIME_SPEC.md`).
 *
 * ## Invariants pinned by the spec
 *
 * - **[ExecTask] preserves argv; there is no `bash -c`.** The argv array
 *   is handed to the OS verbatim: no shell interpolation, no quoting
 *   layer, no re-tokenisation. This is what makes M3-002 ("argv con
 *   spaces/quotes sin shell") hold by construction.
 * - **[ShellScriptTask] is written to a file** by the runtime (never fed
 *   through stdin or `bash -c`), and executed with the chosen
 *   [InterpreterPolicy].
 * - Tasks carry no secrets in plaintext: environment values are typed
 *   [SecretHandle]s at the request boundary so redaction happens BEFORE
 *   persisting or transmitting (spec invariant).
 *
 * @see DurableTaskRuntime
 */
sealed interface TaskSpec {

    /**
     * A shell script executed via [interpreter]. The runtime writes the
     * [script] verbatim to a file and executes that file — the script
     * never travels through another shell's command line.
     */
    data class ShellScriptTask(
        val script: String,
        val interpreter: InterpreterPolicy = InterpreterPolicy.POSIX_SH,
    ) : TaskSpec {
        init {
            require(script.isNotBlank()) { "ShellScriptTask.script must not be blank" }
        }
    }

    /**
     * A direct process: [argv] is preserved exactly, with no shell in
     * between. `argv[0]` is the executable; the rest are its arguments,
     * spaces and quotes included, untouched.
     */
    data class ExecTask(
        val argv: List<String>,
    ) : TaskSpec {
        init {
            require(argv.isNotEmpty()) { "ExecTask.argv must not be empty" }
            require(argv.all { it.isNotEmpty() }) { "ExecTask.argv elements must not be empty strings" }
        }
    }
}
