package dev.rubentxu.pipeline.v2.credentials.local

import java.nio.file.Path

/**
 * ONE authority for where the credentials store lives.
 * ## Why this object exists
 * Two readers answer "where is the credentials store":
 * ```text
 * MainCredentialsCli.resolveStoreFile -> CredentialsStorePath.fromEnvironment(...)
 * composeCredentialSeam              -> CredentialsStorePath.fromEnvironment(...)
 * ```
 * `composeCredentialSeam` is the reader of BOTH run branches, the in-memory one
 * and the durable one, so the two run worlds resolve the same store.
 * Each of them DELEGATES: none of them decides a path on its own, so two of
 * them cannot disagree about the default.
 * ## The symptom a divergent default would cause
 * A reader that computed the default for itself would let the store location
 * drift between the command that WRITES a credential and the command that
 * READS it. `pipelinek credentials add` writes with no `--control-root` in
 * scope, so it can only write the user-home default. `pipelinek run` with no
 * `--control-root` creates a FRESH TEMPORARY directory per run
 * (`Files.createTempDirectory` in `MainVolatileRun.kt`), so a runtime default
 * computed from that directory would resolve to `/tmp/credentials.bin`. A user who
 * follows the documented flow — add a credential, then run a pipeline — would
 * get no credentials, and nothing would say why: the documented "store file
 * does not exist" branch returns `null` silently by design. That is the
 * silent no-op the contract forbids in a credentials path.
 * ## The rule
 * The store is located by the ENVIRONMENT, and by nothing else. It is emphatically
 * not a sibling of the run's journal: on the default in-memory path there is no
 * user-predictable control root at all, so a per-run directory cannot be a
 * location a user can populate before running anything.
 * [resolve] therefore takes no control root. A control root parameter would
 * make the location depend on something the user cannot predict, which is why
 * the value is also the only thing the fitness in `pipeline-credentials-local`
 * checks for.
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