package dev.rubentxu.pipeline.v2.credentials.local

/**
 * Resolves the store passphrase from environment or TTY.
 * ## Resolution Order
 * 1. `PIPELINE_STORE_PASSPHRASE` env var — no TTY prompt, preferred for automation
 * 2. TTY reader — for interactive use (Console.readPassword), when the caller offers one
 * 3. Neither → throws [PassphraseResolver.CredentialsStorePassphraseUnavailableException]
 * ## Security Notes
 * - Env var is NEVER written to disk
 * - Env var is NEVER echoed to stdout
 * - The returned CharArray is a copy (caller must fill with zeros after use)
*/
object PassphraseResolver {

    private const val ENV_VAR = "PIPELINE_STORE_PASSPHRASE"

    /**
     * Exception when no passphrase can be obtained.
    */
    class CredentialsStorePassphraseUnavailableException(
        message: String = AUTOMATION_MESSAGE,
    ) : Exception(message)

    /**
     * Resolves the passphrase.
     *
     * A passphrase that is set but empty is still a passphrase: it is a
     * legitimate key for a store sealed with an empty one, and a job that sets
     * the variable to empty says so on purpose. Only a variable that is not
     * there at all means "I have no passphrase".
     *
     * @param env The environment map (default: System.getenv)
     * @param ttyReader How this caller reads a passphrase from a terminal, or
     *   null when this caller has NO interactive path at all. The two nulls are
     *   different facts, and the diagnostic depends on which one it is: a null
     *   READER means the caller cannot ask anybody — a pipeline run must not
     *   block waiting for a human — while a reader that RETURNS null means the
     *   caller can ask, and there is no terminal here to ask at.
     * @return CharArray containing the passphrase (must be zeroed by caller)
     * @throws CredentialsStorePassphraseUnavailableException if no passphrase can be obtained
    */
    fun resolve(
        env: Map<String, String> = System.getenv(),
        ttyReader: (() -> CharArray?)? = null,
    ): CharArray {
        // 1. Check env var first (preferred for automation)
        val envPassphrase = env[ENV_VAR]
        if (envPassphrase != null) {
            return envPassphrase.toCharArray().copyOf()
        }

        // 2. Try the TTY, when this caller has one to offer
        if (ttyReader != null) {
            val ttyPassphrase = ttyReader()
            if (ttyPassphrase != null) {
                return ttyPassphrase.copyOf()
            }
        }

        // 3. Neither available. The diagnostic names only the alternatives this
        // caller really has: a caller that cannot ask at a terminal is told
        // about the environment variable and nothing else.
        throw CredentialsStorePassphraseUnavailableException(
            if (ttyReader != null) INTERACTIVE_MESSAGE else AUTOMATION_MESSAGE,
        )
    }
}

/**
 * What the operator is told when the passphrase is not available and this
 * caller has no way to ask for one.
 *
 * The environment variable is the only alternative that exists for a caller
 * that cannot ask: a pipeline run reads the key the environment names and
 * refuses otherwise, because a run that waited for a human would wait forever
 * in the place it usually runs.
 */
private const val AUTOMATION_MESSAGE = "Passphrase required: set PIPELINE_STORE_PASSPHRASE"

/**
 * What the operator is told when the passphrase is not available and this
 * caller CAN ask at a terminal — there is simply no terminal here.
 *
 * The terminal is a real alternative for this caller: the same command, run
 * from a terminal, asks for the passphrase instead of refusing.
 */
private const val INTERACTIVE_MESSAGE =
    "Passphrase required: set PIPELINE_STORE_PASSPHRASE or run interactively in a TTY"
