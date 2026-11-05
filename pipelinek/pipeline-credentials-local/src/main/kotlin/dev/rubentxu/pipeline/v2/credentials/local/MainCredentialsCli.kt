package dev.rubentxu.pipeline.v2.credentials.local

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.credentials.Credential
import java.io.InputStream
import java.io.PrintStream
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
 * ## Refusals
 * A request the command does not carry out is refused BEFORE the store is
 * opened, so it never asks for a passphrase and never asks for a secret. The
 * complaint follows the SHAPE of the token the operator wrote: an
 * option-shaped token is an option, and an option the verb does not own is
 * unknown; a token that is not option-shaped is an argument, and an argument
 * the verb does not own is unexpected. A request that names no verb is a
 * missing subcommand, and one that names a verb this command does not have is
 * an unknown subcommand. Which options and arguments each verb owns is stated
 * once, in [CredentialsVerb], and the refusal and the usage are derived from
 * it, so neither can promise an option that does not exist. The sentences
 * themselves are rendered by [CredentialsRefusal], which reads the contract
 * and adds nothing of its own to it.
 * ## The passphrase is the authorization
 * The passphrase is what opens the store, so the verbs that read or change
 * what is inside need it — and a passphrase that does not match changes
 * nothing and says so. A store that is not there has nothing to open, so
 * asking for a passphrase there would be asking for the key to a door that
 * does not exist. The rule lives in [CredentialsVerb] and [CredentialsOutcome],
 * where it can be stated once and checked.
 * ## Asking for the passphrase
 * The passphrase that opens the store is asked for at the terminal when the
 * environment does not name one: an operator who is sitting at a terminal is
 * asked for the key rather than told to go and export it, and the key they
 * give is the key the store is opened with. The environment keeps the first
 * word -- it is the automation path, and a script that names the passphrase
 * never sees a prompt -- and a caller that cannot ask anybody, such as a
 * pipeline run, offers no reader at all and is told only about the
 * environment variable. Which alternatives a refusal names is the resolver's
 * decision, because the resolver is what knows whether this caller had a
 * terminal to offer.
 * ## Seeding without a terminal
 * `--from-stdin` exists so a store can be prepared by a script. The passphrase
 * arrives through the environment, and the value itself used to have exactly
 * one possible source, an interactive terminal, which left every
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

    /**
     * The CLI's store path, delegated to [CredentialsStorePath] rather than
     * recomputed. Internal visibility exists for the contract regression test
     * CLI and runtime MUST resolve the same store.
     *
     * The delegation is the point: the store location is decided by
     * [CredentialsStorePath], the ONE authority, and a reader that decided the
     * path for itself would let the location drift between the command that
     * WRITES a credential and the one that READS it. `credentials add` writes
     * with no `--control-root` in scope, so it can only write the user-home
     * default, and a run's own directory is not a location a user can populate
     * beforehand. Two copies of one rule are how the write and the read end up
     * disagreeing about the file.
     *
     * @param environment the environment that names the store.
*/
    internal fun resolveStoreFile(environment: Map<String, String>): Path =
        CredentialsStorePath.fromEnvironment(environment)

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

    /**
     * Runs the verb and writes what it says to the stream it is given.
     *
     * The destination is a PARAMETER and not a global: a caller that wants to read what this
     * command says hands it its own stream, and what it reads is the same bytes the CLI shows.
     *
     * There is ONE stream and not two: everything this command says, the refusals included, is
     * published on stdout, and that is the shape the refusals of [CredentialsRefusal] are
     * rendered for. A second stream would offer a place to write to that this command does
     * not use.
     *
     * The world this command READS is the ambient one: the environment that names the store and
     * the passphrase, the standard input that carries a value written for the terminal, and the
     * terminal that carries the passphrase when the environment does not name one. The variant
     * that takes them as arguments is the one that can be driven over a store the caller owns.
     */
    @JvmStatic
    fun main(args: Array<String>, out: PrintStream): Int =
        main(args, out, System.getenv(), System.`in`, ConsolePassphraseReader::read)

    /**
     * The command, with the world it reads handed to it.
     *
     * The environment names the store and the passphrase, the stream carries the value of a
     * credential written with `--from-stdin`, and the reader carries the passphrase when the
     * environment does not name one. All three are arguments because all three are the world
     * OUTSIDE the command: the command does not decide where the store is, nor where the value
     * comes from, nor how the operator is asked for the key, it is told. A caller that hands it a
     * store of its own sees the command act on that store, and one that hands it a reader of its
     * own sees the command ask that reader.
     *
     * The reader is asked ONLY when the environment names no passphrase, and only by the verbs
     * that need the key: the environment is the automation path and it wins, and a request the
     * command refuses never asks anybody for anything.
     *
     * @param args the argv, whose first token names the verb.
     * @param out where the command says what it did.
     * @param environment the environment that names the store and the passphrase.
     * @param stdin where the value of a credential comes from when the request names it.
     * @param passphraseReader how the operator is asked for the passphrase when the environment
     *   does not name one. It answers null when there is nobody to ask.
     */
    internal fun main(
        args: Array<String>,
        out: PrintStream,
        environment: Map<String, String>,
        stdin: InputStream,
        passphraseReader: () -> CharArray?,
    ): Int {
        return when (CredentialsVerb.from(args.firstOrNull())) {
            CredentialsVerb.ADD -> add(args.drop(1), out, environment, stdin, passphraseReader)
            CredentialsVerb.LIST -> list(args.drop(1), out, environment, passphraseReader)
            CredentialsVerb.REMOVE -> remove(args.drop(1), out, environment, passphraseReader)
            CredentialsVerb.ROTATE -> rotate(args.drop(1), out, environment, stdin, passphraseReader)
            null -> {
                out.println(CredentialsRefusal.ofUnknownVerb(args.firstOrNull()))
                CredentialsOutcome.NOT_CARRIED_OUT.exitCode
            }
        }
    }

    private fun add(
        args: List<String>,
        out: PrintStream,
        environment: Map<String, String>,
        stdin: InputStream,
        passphraseReader: () -> CharArray?,
    ): Int {
        val parsed = parseWriteArguments(args, CredentialsVerb.ADD, out) ?: return CredentialsOutcome.NOT_CARRIED_OUT.exitCode
        return try {
            val store = openStore(environment, passphraseReader)
            // The key is checked before the operator is asked for a secret: a
            // passphrase that does not match should not cost them the typing.
            store.verifyPassphrase()
            store.add(CredentialsId.from(parsed.id), readCredential(parsed.kind, parsed.fromStdin, stdin))
            out.println("Credential '${parsed.id}' stored successfully.")
            CredentialsOutcome.ANSWERED.exitCode
        } catch (e: PassphraseResolver.CredentialsStorePassphraseUnavailableException) {
            out.println("Error: ${e.message}")
            CredentialsOutcome.NO_PASSPHRASE.exitCode
        } catch (e: LocalSecretStore.SecretStorePassphraseMismatchException) {
            out.println("Error: ${e.message}")
            CredentialsOutcome.WRONG_PASSPHRASE.exitCode
        } catch (e: LocalSecretStore.SecretStoreTamperException) {
            out.println("Error: store tampered: ${e.message}")
            CredentialsOutcome.TAMPERED.exitCode
        } catch (e: LocalSecretStore.CredentialsStoreEmptySecretException) {
            out.println("Error: empty secret not allowed")
            CredentialsOutcome.NOT_CARRIED_OUT.exitCode
        } catch (e: IllegalArgumentException) {
            out.println("Error: ${e.message}")
            CredentialsOutcome.NOT_CARRIED_OUT.exitCode
        } catch (e: Exception) {
            out.println("Error: ${e.message}")
            CredentialsOutcome.NOT_CARRIED_OUT.exitCode
        }
    }

    /**
     * `list` — what the store holds, redacted.
     *
     * It owns NO argument: neither a positional one nor an option. A request
     * that carries one is not a request of this verb, and it is refused naming
     * the token before the store is looked at.
     */
    private fun list(
        args: List<String>,
        out: PrintStream,
        environment: Map<String, String>,
        passphraseReader: () -> CharArray?,
    ): Int {
        val foreign = args.firstOrNull()
        if (foreign != null) {
            out.println(CredentialsRefusal.of(CredentialsVerb.LIST, foreign))
            return CredentialsOutcome.NOT_CARRIED_OUT.exitCode
        }
        // Nothing to list means nothing to unlock: a store that is not there
        // does not need a key, and asking for one would be asking for the key
        // to a door that does not exist.
        if (!storeHoldsAnything(environment)) {
            out.println("No credentials stored.")
            return CredentialsOutcome.ANSWERED.exitCode
        }
        return try {
            val store = openStore(environment, passphraseReader)
            val ids = store.list()
            if (ids.isEmpty()) {
                out.println("No credentials stored.")
            } else {
                out.println("Stored credentials:")
                out.println("%-40s %-20s %-10s".format("ID", "KIND", "SCOPE"))
                out.println("-".repeat(70))
                for (id in ids) {
                    try {
                        val cred = store.get(id)
                        val kind = cred::class.simpleName ?: "Unknown"
                        val scope = cred.scope.name
                        out.println("%-40s %-20s %-10s".format(id.value, kind, scope))
                    } catch (e: Exception) {
                        out.println("%-40s %-20s %-10s".format(id.value, "Unknown", "Unknown"))
                    }
                }
            }
            CredentialsOutcome.ANSWERED.exitCode
        } catch (e: PassphraseResolver.CredentialsStorePassphraseUnavailableException) {
            out.println("Error: ${e.message}")
            CredentialsOutcome.NO_PASSPHRASE.exitCode
        } catch (e: LocalSecretStore.SecretStorePassphraseMismatchException) {
            out.println("Error: ${e.message}")
            CredentialsOutcome.WRONG_PASSPHRASE.exitCode
        } catch (e: LocalSecretStore.SecretStoreTamperException) {
            out.println("Error: store tampered: ${e.message}")
            CredentialsOutcome.TAMPERED.exitCode
        } catch (e: Exception) {
            out.println("Error listing credentials: ${e.message}")
            CredentialsOutcome.NOT_CARRIED_OUT.exitCode
        }
    }

    private fun remove(
        args: List<String>,
        out: PrintStream,
        environment: Map<String, String>,
        passphraseReader: () -> CharArray?,
    ): Int {
        // `remove` owns NO option, so a token that looks like one is not the
        // credential id: it is refused naming it, BEFORE the store is looked
        // at. Taking it for an id would only discover the mistake at the end,
        // when a credential with that name is looked up.
        val option = args.firstOrNull { it.startsWith("--") }
        if (option != null) {
            out.println(CredentialsRefusal.of(CredentialsVerb.REMOVE, option))
            return CredentialsOutcome.NOT_CARRIED_OUT.exitCode
        }
        val id = args.firstOrNull() ?: run {
            out.println(CredentialsRefusal.ofMissingArgument(CredentialsVerb.REMOVE))
            return CredentialsOutcome.NOT_CARRIED_OUT.exitCode
        }
        // `remove` owns ONE argument — the id — and no option. A second token
        // is not a request of this verb, and it is refused naming it before the
        // store is looked at.
        val extra = args.getOrNull(1)
        if (extra != null) {
            out.println(CredentialsRefusal.of(CredentialsVerb.REMOVE, extra))
            return CredentialsOutcome.NOT_CARRIED_OUT.exitCode
        }
        if (!storeHoldsAnything(environment)) {
            out.println("No credentials stored.")
            return CredentialsOutcome.NOT_CARRIED_OUT.exitCode
        }
        return try {
            val store = openStore(environment, passphraseReader)
            val credentialsId = CredentialsId.from(id)
            // Listing is also how the passphrase gets checked, so a key that
            // does not match is refused here, before anything is removed.
            if (credentialsId !in store.list()) {
                out.println("No credential named '$id'.")
                return CredentialsOutcome.NOT_CARRIED_OUT.exitCode
            }
            store.remove(credentialsId)
            out.println("Credential '$id' removed.")
            CredentialsOutcome.ANSWERED.exitCode
        } catch (e: PassphraseResolver.CredentialsStorePassphraseUnavailableException) {
            out.println("Error: ${e.message}")
            CredentialsOutcome.NO_PASSPHRASE.exitCode
        } catch (e: LocalSecretStore.SecretStorePassphraseMismatchException) {
            out.println("Error: ${e.message}")
            CredentialsOutcome.WRONG_PASSPHRASE.exitCode
        } catch (e: LocalSecretStore.SecretStoreTamperException) {
            out.println("Error: store tampered: ${e.message}")
            CredentialsOutcome.TAMPERED.exitCode
        } catch (e: Exception) {
            out.println("Error: ${e.message}")
            CredentialsOutcome.NOT_CARRIED_OUT.exitCode
        }
    }

    private fun rotate(
        args: List<String>,
        out: PrintStream,
        environment: Map<String, String>,
        stdin: InputStream,
        passphraseReader: () -> CharArray?,
    ): Int {
        val parsed = parseWriteArguments(args, CredentialsVerb.ROTATE, out) ?: return CredentialsOutcome.NOT_CARRIED_OUT.exitCode
        if (!storeHoldsAnything(environment)) {
            out.println("No credentials stored.")
            return CredentialsOutcome.NOT_CARRIED_OUT.exitCode
        }
        return try {
            val store = openStore(environment, passphraseReader)
            // The key is checked before the operator is asked for a secret: a
            // passphrase that does not match should not cost them the typing.
            store.verifyPassphrase()
            val credentialsId = CredentialsId.from(parsed.id)
            if (credentialsId !in store.list()) {
                out.println("No credential named '${parsed.id}'.")
                return CredentialsOutcome.NOT_CARRIED_OUT.exitCode
            }
            store.rotate(credentialsId, readCredential(parsed.kind, parsed.fromStdin, stdin))
            out.println("Credential '${parsed.id}' rotated successfully.")
            CredentialsOutcome.ANSWERED.exitCode
        } catch (e: PassphraseResolver.CredentialsStorePassphraseUnavailableException) {
            out.println("Error: ${e.message}")
            CredentialsOutcome.NO_PASSPHRASE.exitCode
        } catch (e: LocalSecretStore.SecretStorePassphraseMismatchException) {
            out.println("Error: ${e.message}")
            CredentialsOutcome.WRONG_PASSPHRASE.exitCode
        } catch (e: LocalSecretStore.SecretStoreTamperException) {
            out.println("Error: store tampered: ${e.message}")
            CredentialsOutcome.TAMPERED.exitCode
        } catch (e: LocalSecretStore.CredentialsStoreEmptySecretException) {
            out.println("Error: empty secret not allowed")
            CredentialsOutcome.NOT_CARRIED_OUT.exitCode
        } catch (e: IllegalArgumentException) {
            out.println("Error: ${e.message}")
            CredentialsOutcome.NOT_CARRIED_OUT.exitCode
        } catch (e: Exception) {
            out.println("Error: ${e.message}")
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
     *
     * The verb is named because the refusal has to say which form of the
     * command the operator wrote, and the caller is the one that knows it.
*/
    private fun parseWriteArguments(
        args: List<String>,
        verb: CredentialsVerb,
        out: PrintStream,
    ): WriteArguments? {
        var kind: CredentialKind? = null
        var id: String? = null
        var fromStdin = false

        val it = args.iterator()
        while (it.hasNext()) {
            val arg = it.next()
            val option = verb.ownedOption(arg)
            when {
                option == CredentialsOption.KIND -> {
                    val kindStr = if (it.hasNext()) it.next() else null
                    if (kindStr == null) {
                        // The verb OWNS this option and the operator left its
                        // value out. That is a typo about a KNOWN option, and
                        // it is a different mistake from an unknown option.
                        out.println(CredentialsRefusal.ofMissingOptionValue(verb, CredentialsOption.KIND))
                        return null
                    }
                    kind = CredentialKind.fromString(kindStr)
                    if (kind == null) {
                        out.println("Error: unknown kind '$kindStr'. Valid kinds: ${CredentialKind.ALL_KINDS}")
                        return null
                    }
                }

                option == CredentialsOption.FROM_STDIN -> fromStdin = true
                // An option this verb does not own is NOT an option whose value
                // is missing: the operator has to be told WHICH token to
                // remove, or the refusal sends them to the wrong place.
                arg.startsWith("--") -> {
                    out.println(CredentialsRefusal.of(verb, arg))
                    return null
                }

                id == null -> id = arg
                // The SECOND positional is not a request of this verb: `add`
                // and `rotate` own ONE, the credential id. It is refused
                // naming the token, and it is refused HERE — before the store
                // is opened — so a request that is not this verb's never asks
                // for a passphrase and never asks for a secret.
                else -> {
                    out.println(CredentialsRefusal.of(verb, arg))
                    return null
                }
            }
        }

        val idArg = id ?: run {
            out.println(CredentialsRefusal.ofMissingArgument(verb))
            return null
        }
        return WriteArguments(kind ?: CredentialKind.SECRET_TEXT, idArg, fromStdin)
    }

    /**
     * Opens the store with the passphrase this request resolved.
     *
     * The passphrase comes from the environment when the environment names one,
     * and from the reader when it does not: the operator is asked once, and the
     * key they give is the key the store is opened with.
     */
    private fun openStore(
        environment: Map<String, String>,
        passphraseReader: () -> CharArray?,
    ): LocalSecretStore =
        LocalSecretStore(
            resolveStoreFile(environment),
            PassphraseResolver.resolve(environment, passphraseReader),
        )

    /**
     * Whether there is a store with something in it.
     *
     * A store that is not there, or that is there and empty, has nothing to
     * unlock and nothing to change: the verbs that read or change it have
     * nothing to do, and asking for a passphrase would be asking for the key to
     * a door that does not exist. It also keeps those verbs from bringing a
     * store file into existence just by looking.
     */
    private fun storeHoldsAnything(environment: Map<String, String>): Boolean {
        val file = resolveStoreFile(environment)
        return java.nio.file.Files.exists(file) && java.nio.file.Files.size(file) > 0L
    }

    private fun readCredential(kind: CredentialKind, fromStdin: Boolean, stdin: InputStream): Credential =
        if (fromStdin) StreamCredentialPrompts.read(kind, stdin)
        else CredentialPrompts.read(kind)
}
