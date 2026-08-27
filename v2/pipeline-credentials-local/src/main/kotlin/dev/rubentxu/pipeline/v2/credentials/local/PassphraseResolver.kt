package dev.rubentxu.pipeline.v2.credentials.local

/**
 * Resolves the store passphrase from environment or TTY.
 *
 * ## Resolution Order
 *
 * 1. `PIPELINE_STORE_PASSPHRASE` env var — no TTY prompt, preferred for automation
 * 2. TTY reader — for interactive use (Console.readPassword)
 * 3. Neither → throws [CredentialsStorePassphraseUnavailableException]
 *
 * ## Security Notes
 *
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
        message: String = "Passphrase required: set PIPELINE_STORE_PASSPHRASE or run interactively in a TTY",
    ) : Exception(message)

    /**
     * Resolves the passphrase.
     *
     * @param env The environment map (default: System.getenv())
     * @param ttyReader Lambda that reads password from TTY, or null if unavailable
     * @return CharArray containing the passphrase (must be zeroed by caller)
     * @throws LocalSecretStore.CredentialsStorePassphraseUnavailableException if no passphrase can be obtained
     */
    fun resolve(
        env: Map<String, String> = System.getenv(),
        ttyReader: () -> CharArray? = { null },
    ): CharArray {
        // 1. Check env var first (preferred for automation)
        val envPassphrase = env[ENV_VAR]
        if (envPassphrase != null) {
            return envPassphrase.toCharArray().copyOf()
        }

        // 2. Try TTY
        val ttyPassphrase = ttyReader()
        if (ttyPassphrase != null) {
            return ttyPassphrase.copyOf()
        }

        // 3. Neither available
        throw CredentialsStorePassphraseUnavailableException(
            "Passphrase required: set PIPELINE_STORE_PASSPHRASE or run interactively in a TTY",
        )
    }
}
