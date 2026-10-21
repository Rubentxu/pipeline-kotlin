package dev.rubentxu.pipeline.v2.credentials.local

import java.nio.file.Path

/**
 * ONE authority for where the credentials store lives.
 *
 * ## Why this object exists
 *
 * Three readers used to answer "where is the credentials store" and they did not
 * agree:
 *
 * ```text
 * MainCredentialsCli.resolveStoreFile()   -> PIPELINE_CREDENTIALS_STORE, else ~/.pipeline/credentials.bin
 * composeCredentialSeam()                 -> PIPELINE_CREDENTIALS_STORE, else <controlRoot>/../credentials.bin
 * Main.kt (in-memory branch, same rule)   -> PIPELINE_CREDENTIALS_STORE, else <controlRoot>/../credentials.bin
 * ```
 *
 * All three honoured the env var, so the divergence lived entirely in the default.
 * Two of the three were a verbatim copy of each other, which is what let them
 * drift apart without anyone noticing.
 *
 * ## The symptom it caused
 *
 * `pipelinek credentials add` writes with no `--control-root` in scope, so it can
 * only write the user-home default. `pipelinek run` with no `--control-root`
 * creates a FRESH TEMPORARY directory per run (`Files.createTempDirectory`
 * in `Main.kt`), so the runtime default resolved to `/tmp/credentials.bin`. A user
 * who followed the documented flow — add a credential, then run a pipeline — got
 * no credentials, and nothing said why: the documented "store file does not exist"
 * branch returns `null` silently by design.
 *
 * That is the silent-no-op defect in a credentials path, and
 * the two KDoc blocks that described the two rules each cited the other as the
 * authority, so neither was falsifiable by reading.
 *
 * ## The rule
 *
 * The store is located by the ENVIRONMENT, and by nothing else. It is emphatically
 * not a sibling of the run's journal: on the default in-memory path there is no
 * user-predictable control root at all, so a per-run directory cannot be a
 * location a user can populate before running anything.
 *
 * [resolve] therefore takes no control root. Adding one back would reintroduce the
 * defect with a parameter, which is why the value is also the only thing the
 * fitness in `pipeline-credentials-local` checks for.
 */
object CredentialsStorePath {

    /** The env var that overrides the default, honoured by every reader. */
    const val ENV_VAR: String = "PIPELINE_CREDENTIALS_STORE"

    private const val DIRECTORY = ".pipeline"
    private const val FILE = "credentials.bin"

    /**
     * The store location for a given environment. Pure and total: no I/O, no
     * ambient state, no filesystem probe — a store that does not exist yet is
     * resolved the same as one that does.
     *
     * A blank [envValue] is treated as ABSENT, not as a path to the current
     * directory. `PIPELINE_CREDENTIALS_STORE=` in a CI job must not silently
     * redirect the store to `credentials.bin` relative to whatever the runner's
     * working directory happens to be; this matches the opt-in semantics already
     * declared for `PIPELINEK_SCRIPT_CACHE_DIR`.
     */
    fun resolve(envValue: String?, userHome: Path): Path =
        envValue?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
            ?: userHome.resolve(DIRECTORY).resolve(FILE)

    /**
     * [resolve] over the ambient environment, for the composition roots that have
     * no other way to be told where the store is.
     */
    fun fromEnvironment(env: Map<String, String>): Path =
        resolve(
            envValue = env[ENV_VAR],
            userHome = Path.of(env["HOME"] ?: System.getProperty("user.home")),
        )
}