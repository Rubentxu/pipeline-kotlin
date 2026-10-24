package dev.rubentxu.pipeline.v2.credentials.local

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.credentials.Credential
import java.nio.file.Path

/**
 * Main credentials CLI — subcommand dispatcher for credential store operations.
 * ## Exit Codes
 * - 0: the request was answered, or there was nothing to answer
 * - 1: the request could not be carried out (no such credential, bad arguments)
 * - 2: no passphrase is available to open the store
 * - 3: the passphrase does not match the store
 * - 4: the store is tampered
 * ## Subcommands
 * - `add [--kind <kind>] [--from-stdin] <id>` — stores a credential. Without
 *   `--from-stdin` the value is asked for at the terminal; with it, the value
 *   is read from standard input.
 * - `list` — lists all credential IDs with kind and scope (never values)
 * - `remove <id>` — removes a credential
 * - `rotate [--kind <kind>] [--from-stdin] <id>` — replaces a stored credential
 *   with a new value, read the same way `add` reads it.
 * ## The passphrase is the authorization
 * The passphrase is what opens the store, so the verbs that read or change
 * what is inside need it — and a passphrase that does not match changes
 * nothing and says so. A store that is not there has nothing to open, so
 * asking for a passphrase there would be asking for the key to a door that
 * does not exist. The rule lives in [CredentialsVerb] and [CredentialsOutcome],
 * where it can be stated once and checked.
 * ## Seeding without a terminal
 * `--from-stdin` exists so a store can be prepared by a script. The passphrase
 * already arrives through the environment, but the value itself used to have
 * exactly one possible source, an interactive terminal, which left every
 * non-interactive use of `add` -- provisioning, containers, continuous
 * integration -- with no way in. The value never reaches a log either way: it
 * is read once, held in memory for as long as the call takes, and handed
 * straight to the store.
 * ## Supported Kinds (--kind)
 * - `secret-text`: raw string secret (default if --kind omitted)
 * - `username-password`: username + password pair
 * - `ssh-private-key`: SSH private key with optional passphrase
 * - `secret-file`: file contents as secret
 * - `certificate`: PKCS#12 keystore
 * - `zip`: ZIP archive
 * - `username-colon-password`: colon-joined user:pass string
*/
object MainCredentialsCli {

    // The store location is decided by CredentialsStorePath, the ONE authority.
    // This comment used to spell the rule out here AND claim the runtime agreed
    // And the runtime resolved a
    // different default, because it took <controlRoot>/../credentials.bin. Two
    // copies of one rule is how `credentials add` came to write somewhere the run
    // never looked. See CredentialsStorePath's KDoc.
    private val STORE_FILE: Path = resolveStoreFile()

    /**
     * The CLI's store path, delegated to [CredentialsStorePath] rather than
     * recomputed. Internal visibility exists for the contract regression test
     * CLI and runtime MUST resolve the same store.
*/
    internal fun resolveStoreFile(): Path = CredentialsStorePath.fromEnvironment(System.getenv())

    // Supported credential kinds
    enum class CredentialKind {
        SECRET_TEXT,
        USERNAME_PASSWORD,
        SSH_PRIVATE_KEY,
        SECRET_FILE,
        CERTIFICATE,
        ZIP,
        USERNAME_COLON_PASSWORD;

        companion object {
            fun fromString(s: String): CredentialKind? = when (s.lowercase().replace("-", "")) {
                "secrettext", "secret-text", "secret_text" -> SECRET_TEXT
                "usernamepassword", "username-password", "username_password" -> USERNAME_PASSWORD
                "sshprivatekey", "ssh-private-key", "ssh_private_key" -> SSH_PRIVATE_KEY
                "secretfile", "secret-file", "secret_file" -> SECRET_FILE
                "certificate" -> CERTIFICATE
                "zip" -> ZIP
                "usernamecolonpassword", "username-colon-password", "username_colon_password" -> USERNAME_COLON_PASSWORD
                else -> null
            }

            const val ALL_KINDS = "secret-text, username-password, ssh-private-key, secret-file, certificate, zip, username-colon-password"
        }

        /**
         * How the bytes of this kind arrive, and therefore what a newline the
         * transport writes means for them.
         *
         * A value that is closed by a newline arrives line-wise: the shell adds
         * one, and dropping it is what keeps `echo secret |` from storing a
         * secret one byte longer than the caller meant. A value that IS a file
         * has no terminator — its last byte is content like any other — and a
         * file that ends in a newline is a file, not a line.
         */
        internal val framing: CredentialFraming
            get() = when (this) {
                SECRET_TEXT, USERNAME_PASSWORD, USERNAME_COLON_PASSWORD, SSH_PRIVATE_KEY ->
                    CredentialFraming.TERMINATED

                SECRET_FILE, CERTIFICATE, ZIP -> CredentialFraming.WHOLE
            }
    }

    @JvmStatic
    fun main(args: Array<String>): Int {
        return when (args.firstOrNull()) {
            "add" -> add(args.drop(1))
            "list" -> list()
            "remove" -> remove(args.drop(1))
            "rotate" -> rotate(args.drop(1))
            else -> {
                println("Usage: pipeline credentials {add|list|remove|rotate} [args]")
                println("Add:    pipeline credentials add [--kind <kind>] [--from-stdin] <id>")
                println("        --kind values: ${CredentialKind.ALL_KINDS}")
                println("        --from-stdin reads the value from standard input")
                println("List:   pipeline credentials list")
                println("Remove: pipeline credentials remove <id>")
                println("Rotate: pipeline credentials rotate [--kind <kind>] [--from-stdin] <id>")
                CredentialsOutcome.NOT_CARRIED_OUT.exitCode
            }
        }
    }

    private fun add(args: List<String>): Int {
        val parsed = parseWriteArguments(args) ?: return CredentialsOutcome.NOT_CARRIED_OUT.exitCode
        return try {
            val store = openStore()
            // The key is checked before the operator is asked for a secret: a
            // passphrase that does not match should not cost them the typing.
            store.verifyPassphrase()
            store.add(CredentialsId.from(parsed.id), readCredential(parsed.kind, parsed.fromStdin))
            println("Credential '${parsed.id}' stored successfully.")
            CredentialsOutcome.ANSWERED.exitCode
        } catch (e: PassphraseResolver.CredentialsStorePassphraseUnavailableException) {
            println("Error: ${e.message}")
            CredentialsOutcome.NO_PASSPHRASE.exitCode
        } catch (e: LocalSecretStore.SecretStorePassphraseMismatchException) {
            println("Error: ${e.message}")
            CredentialsOutcome.WRONG_PASSPHRASE.exitCode
        } catch (e: LocalSecretStore.SecretStoreTamperException) {
            println("Error: store tampered: ${e.message}")
            CredentialsOutcome.TAMPERED.exitCode
        } catch (e: LocalSecretStore.CredentialsStoreEmptySecretException) {
            println("Error: empty secret not allowed")
            CredentialsOutcome.NOT_CARRIED_OUT.exitCode
        } catch (e: IllegalArgumentException) {
            println("Error: ${e.message}")
            CredentialsOutcome.NOT_CARRIED_OUT.exitCode
        } catch (e: Exception) {
            println("Error: ${e.message}")
            CredentialsOutcome.NOT_CARRIED_OUT.exitCode
        }
    }

    private fun list(): Int {
        // Nothing to list means nothing to unlock: a store that is not there
        // does not need a key, and asking for one would be asking for the key
        // to a door that does not exist.
        if (!storeHoldsAnything()) {
            println("No credentials stored.")
            return CredentialsOutcome.ANSWERED.exitCode
        }
        return try {
            val store = openStore()
            val ids = store.list()
            if (ids.isEmpty()) {
                println("No credentials stored.")
            } else {
                println("Stored credentials:")
                println("%-40s %-20s %-10s".format("ID", "KIND", "SCOPE"))
                println("-".repeat(70))
                for (id in ids) {
                    try {
                        val cred = store.get(id)
                        val kind = cred::class.simpleName ?: "Unknown"
                        val scope = cred.scope.name
                        println("%-40s %-20s %-10s".format(id.value, kind, scope))
                    } catch (e: Exception) {
                        println("%-40s %-20s %-10s".format(id.value, "Unknown", "Unknown"))
                    }
                }
            }
            CredentialsOutcome.ANSWERED.exitCode
        } catch (e: PassphraseResolver.CredentialsStorePassphraseUnavailableException) {
            println("Error: ${e.message}")
            CredentialsOutcome.NO_PASSPHRASE.exitCode
        } catch (e: LocalSecretStore.SecretStorePassphraseMismatchException) {
            println("Error: ${e.message}")
            CredentialsOutcome.WRONG_PASSPHRASE.exitCode
        } catch (e: LocalSecretStore.SecretStoreTamperException) {
            println("Error: store tampered: ${e.message}")
            CredentialsOutcome.TAMPERED.exitCode
        } catch (e: Exception) {
            println("Error listing credentials: ${e.message}")
            CredentialsOutcome.NOT_CARRIED_OUT.exitCode
        }
    }

    private fun remove(args: List<String>): Int {
        val id = args.firstOrNull() ?: run {
            println("Usage: pipeline credentials remove <id>")
            return CredentialsOutcome.NOT_CARRIED_OUT.exitCode
        }
        if (!storeHoldsAnything()) {
            println("No credentials stored.")
            return CredentialsOutcome.NOT_CARRIED_OUT.exitCode
        }
        return try {
            val store = openStore()
            val credentialsId = CredentialsId.from(id)
            // Listing is also how the passphrase gets checked, so a key that
            // does not match is refused here, before anything is removed.
            if (credentialsId !in store.list()) {
                println("No credential named '$id'.")
                return CredentialsOutcome.NOT_CARRIED_OUT.exitCode
            }
            store.remove(credentialsId)
            println("Credential '$id' removed.")
            CredentialsOutcome.ANSWERED.exitCode
        } catch (e: PassphraseResolver.CredentialsStorePassphraseUnavailableException) {
            println("Error: ${e.message}")
            CredentialsOutcome.NO_PASSPHRASE.exitCode
        } catch (e: LocalSecretStore.SecretStorePassphraseMismatchException) {
            println("Error: ${e.message}")
            CredentialsOutcome.WRONG_PASSPHRASE.exitCode
        } catch (e: LocalSecretStore.SecretStoreTamperException) {
            println("Error: store tampered: ${e.message}")
            CredentialsOutcome.TAMPERED.exitCode
        } catch (e: Exception) {
            println("Error: ${e.message}")
            CredentialsOutcome.NOT_CARRIED_OUT.exitCode
        }
    }

    private fun rotate(args: List<String>): Int {
        val parsed = parseWriteArguments(args) ?: return CredentialsOutcome.NOT_CARRIED_OUT.exitCode
        if (!storeHoldsAnything()) {
            println("No credentials stored.")
            return CredentialsOutcome.NOT_CARRIED_OUT.exitCode
        }
        return try {
            val store = openStore()
            // The key is checked before the operator is asked for a secret: a
            // passphrase that does not match should not cost them the typing.
            store.verifyPassphrase()
            val credentialsId = CredentialsId.from(parsed.id)
            if (credentialsId !in store.list()) {
                println("No credential named '${parsed.id}'.")
                return CredentialsOutcome.NOT_CARRIED_OUT.exitCode
            }
            store.rotate(credentialsId, readCredential(parsed.kind, parsed.fromStdin))
            println("Credential '${parsed.id}' rotated successfully.")
            CredentialsOutcome.ANSWERED.exitCode
        } catch (e: PassphraseResolver.CredentialsStorePassphraseUnavailableException) {
            println("Error: ${e.message}")
            CredentialsOutcome.NO_PASSPHRASE.exitCode
        } catch (e: LocalSecretStore.SecretStorePassphraseMismatchException) {
            println("Error: ${e.message}")
            CredentialsOutcome.WRONG_PASSPHRASE.exitCode
        } catch (e: LocalSecretStore.SecretStoreTamperException) {
            println("Error: store tampered: ${e.message}")
            CredentialsOutcome.TAMPERED.exitCode
        } catch (e: LocalSecretStore.CredentialsStoreEmptySecretException) {
            println("Error: empty secret not allowed")
            CredentialsOutcome.NOT_CARRIED_OUT.exitCode
        } catch (e: IllegalArgumentException) {
            println("Error: ${e.message}")
            CredentialsOutcome.NOT_CARRIED_OUT.exitCode
        } catch (e: Exception) {
            println("Error: ${e.message}")
            CredentialsOutcome.NOT_CARRIED_OUT.exitCode
        }
    }

    /** The two commands that write a value share these arguments exactly. */
    private data class WriteArguments(
        val kind: CredentialKind,
        val id: String,
        val fromStdin: Boolean,
    )

    /**
     * Reads the arguments of `add` and `rotate`, which differ only in what they
     * do with the result. Returns null after printing the reason, so a caller
     * never has to invent a usage string of its own and two commands cannot
     * disagree about which flags exist.
*/
    private fun parseWriteArguments(args: List<String>): WriteArguments? {
        var kind: CredentialKind? = null
        var id: String? = null
        var fromStdin = false

        val it = args.iterator()
        while (it.hasNext()) {
            val arg = it.next()
            when {
                arg == "--kind" && it.hasNext() -> {
                    val kindStr = it.next()
                    kind = CredentialKind.fromString(kindStr)
                    if (kind == null) {
                        println("Error: unknown kind '$kindStr'. Valid kinds: ${CredentialKind.ALL_KINDS}")
                        return null
                    }
                }

                arg == "--from-stdin" -> fromStdin = true
                !arg.startsWith("--") && id == null -> id = arg
                // A flag whose value is missing is a typo the user should see,
                // not a silently ignored argument.
                arg.startsWith("--") -> {
                    println("Error: $arg needs a value")
                    return null
                }
            }
        }

        val idArg = id ?: run {
            println("Error: missing credential id")
            return null
        }
        return WriteArguments(kind ?: CredentialKind.SECRET_TEXT, idArg, fromStdin)
    }

    private fun openStore(): LocalSecretStore =
        LocalSecretStore(STORE_FILE, PassphraseResolver.resolve())

    /**
     * Whether there is a store with something in it.
     *
     * A store that is not there, or that is there and empty, has nothing to
     * unlock and nothing to change: the verbs that read or change it have
     * nothing to do, and asking for a passphrase would be asking for the key to
     * a door that does not exist. It also keeps those verbs from bringing a
     * store file into existence just by looking.
     */
    private fun storeHoldsAnything(): Boolean =
        java.nio.file.Files.exists(STORE_FILE) && java.nio.file.Files.size(STORE_FILE) > 0L

    private fun readCredential(kind: CredentialKind, fromStdin: Boolean): Credential =
        if (fromStdin) StreamCredentialPrompts.read(kind, System.`in`)
        else CredentialPrompts.read(kind)
}
