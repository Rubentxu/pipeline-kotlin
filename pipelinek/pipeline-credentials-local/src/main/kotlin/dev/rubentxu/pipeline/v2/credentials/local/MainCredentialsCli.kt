package dev.rubentxu.pipeline.v2.credentials.local

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.credentials.Credential
import java.io.InputStream
import java.io.PrintStream
import java.nio.file.Path

/**
 * How the command opens the store it works on.
 *
 * The command decides WHERE the store is and WITH WHAT key, and this turns those two into a
 * store. A caller that brings a factory of its own sees the command act on the store that
 * factory built, and can hold it afterwards.
 */
internal typealias StoreFactory = (Path, CharArray) -> LocalSecretStore

/**
 * Main credentials CLI — subcommand dispatcher for credential store operations.
 * ## The outcome, and the code the CLI gives it
 * The command answers with the FACT — [CredentialsOutcome] — and the code the
 * process ends with belongs to the CLI's taxonomy, which names the case of
 * each outcome in the module that owns that taxonomy. This module used to
 * carry its own five codes, and the door translated them BY NUMBER, so the
 * number that meant "the request was not carried out" reached the process as
 * the case that means "the pipeline executed and produced a failure" — and
 * nothing had executed. See [CredentialsOutcome] for why the family no longer
 * answers with a number.
 * ## Where each thing is published
 * The ANSWER goes to stdout and the REFUSAL goes to stderr. This command used
 * to publish both on stdout, on the grounds that it had ONE stream and not
 * two; but the command has no door of its own — the distribution ships one
 * launcher, and this family is reached only as `pipelinek credentials` — and
 * every other verb of that CLI publishes its refusals on stderr. A program
 * that reads stdout to parse the answer must not find a refusal inside it.
 * The SENTENCES are not affected: they are rendered by [CredentialsRefusal]
 * and they do not move.
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
 * ## The session
 * A command that opens a store ends its session when the request is over: the store is
 * released, and the key the operator gave it goes with it. The release happens AFTER the
 * answer is published, so the end of a session never changes what the command said, and it
 * happens even when the request was not carried out.
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

        /**
         * What the bytes of this kind are called when a refusal has to name
         * them.
         *
         * The words are this module's own, and they never carry a name the
         * operator wrote: a refusal about a credential says how much was too
         * much, and not what the credential held.
         */
        internal val subject: String
            get() = when (this) {
                SECRET_TEXT -> "the secret"
                USERNAME_PASSWORD, USERNAME_COLON_PASSWORD -> "the username and the password"
                SSH_PRIVATE_KEY -> "the SSH private key"
                SECRET_FILE -> "the secret file"
                CERTIFICATE -> "the keystore"
                ZIP -> "the ZIP archive"
            }

        /**
         * How many bytes of this kind may arrive.
         *
         * A value that is a file is bounded by the store's own ceiling of the
         * secret an entry may hold. An archive is bounded by the size of the
         * archive as it is handed over, because that is what is read whole;
         * the bytes it expands to are bounded by another rule.
         */
        internal fun ceilingOf(limits: CredentialImportLimits): Long = when (this) {
            ZIP -> limits.maxArchiveBytes
            else -> limits.maxContentBytes
        }
    }

    /**
     * Runs the verb and writes what it says to the streams it is given.
     *
     * The destinations are PARAMETERS and not globals: a caller that wants to read what this
     * command says hands it its own streams, and what it reads is the same bytes the CLI shows.
     *
     * There are TWO streams and not one: the ANSWER goes to stdout and the REFUSAL goes to
     * stderr, which is the radio every other verb of this CLI has, and which the family itself
     * declared when it became a verb of that CLI instead of a command with a door of its own.
     * A second stream is not a place the command writes to for decoration: it is where the
     * operator reads the complaint, and where a program that parses the answer will not find it.
     *
     * The world this command READS is the ambient one: the environment that names the store and
     * the passphrase, the standard input that carries a value written for the terminal, and the
     * terminal that carries the passphrase when the environment does not name one. The variant
     * that takes them as arguments is the one that can be driven over a store the caller owns.
     *
     * @param args the argv, whose first token names the verb.
     * @param out where the command publishes what it did.
     * @param err where the command publishes the refusal.
     * @return the FACT of how the request ended; the code the process ends with is the CLI's,
     *   which names the case of each outcome.
     */
    @JvmStatic
    fun main(args: Array<String>, out: PrintStream, err: PrintStream): CredentialsOutcome =
        main(args, out, err, System.getenv(), System.`in`, ConsolePassphraseReader::read)

    /**
     * The command, with the world it reads handed to it.
     *
     * The environment names the store and the passphrase, the stream carries the value of a
     * credential written with `--from-stdin`, and the reader carries the passphrase when the
     * environment does not name one. All three are arguments because all three are the world
     * OUTSIDE the command: the command does not decide where the store is, nor where the value
     * comes from, nor how the operator is asked for the key, it is told. One that hands it a
     * reader of its own sees the command ask that reader.
     *
     * The factory that opens the store is an argument for the same reason. The command decides
     * WHERE the store is and WITH WHAT key, and the factory turns those two into a store: a
     * caller that hands it a factory of its own sees the command act on the store that factory
     * built.
     *
     * The reader is asked ONLY when the environment names no passphrase, and only by the verbs
     * that need the key: the environment is the automation path and it wins, and a request the
     * command refuses never asks anybody for anything.
     *
     * @param args the argv, whose first token names the verb.
     * @param out where the command publishes what it did.
     * @param err where the command publishes the refusal.
     * @param environment the environment that names the store and the passphrase.
     * @param stdin where the value of a credential comes from when the request names it.
     * @param passphraseReader how the operator is asked for the passphrase when the environment
     *   does not name one. It answers null when there is nobody to ask.
     * @param storeFactory how the store is opened. The default opens the product's own store
     *   over the file the environment names.
     */
    internal fun main(
        args: Array<String>,
        out: PrintStream,
        err: PrintStream,
        environment: Map<String, String>,
        stdin: InputStream,
        passphraseReader: () -> CharArray?,
        storeFactory: StoreFactory = { file, passphrase -> LocalSecretStore(file, passphrase) },
    ): CredentialsOutcome {
        return when (CredentialsVerb.from(args.firstOrNull())) {
            CredentialsVerb.ADD -> add(args.drop(1), out, err, environment, stdin, passphraseReader, storeFactory)
            CredentialsVerb.LIST -> list(args.drop(1), out, err, environment, passphraseReader, storeFactory)
            CredentialsVerb.REMOVE -> remove(args.drop(1), out, err, environment, passphraseReader, storeFactory)
            CredentialsVerb.ROTATE -> rotate(args.drop(1), out, err, environment, stdin, passphraseReader, storeFactory)
            null -> {
                err.println(CredentialsRefusal.ofUnknownVerb(args.firstOrNull()))
                CredentialsOutcome.NOT_CARRIED_OUT
            }
        }
    }

    private fun add(
        args: List<String>,
        out: PrintStream,
        err: PrintStream,
        environment: Map<String, String>,
        stdin: InputStream,
        passphraseReader: () -> CharArray?,
        storeFactory: StoreFactory,
    ): CredentialsOutcome {
        val parsed = parseWriteArguments(args, CredentialsVerb.ADD, err) ?: return CredentialsOutcome.NOT_CARRIED_OUT
        return try {
            withStore(environment, passphraseReader, storeFactory) { store ->
                // The key is checked before the operator is asked for a secret: a
                // passphrase that does not match should not cost them the typing.
                store.verifyPassphrase()
                store.add(CredentialsId.from(parsed.id), readCredential(parsed.kind, parsed.fromStdin, stdin))
                out.println("Credential '${parsed.id}' stored successfully.")
                CredentialsOutcome.ANSWERED
            }
        } catch (e: PassphraseResolver.CredentialsStorePassphraseUnavailableException) {
            err.println("Error: ${e.message}")
            CredentialsOutcome.NO_PASSPHRASE
        } catch (e: LocalSecretStore.SecretStorePassphraseMismatchException) {
            err.println("Error: ${e.message}")
            CredentialsOutcome.WRONG_PASSPHRASE
        } catch (e: LocalSecretStore.SecretStoreTamperException) {
            err.println("Error: store tampered: ${e.message}")
            CredentialsOutcome.TAMPERED
        } catch (e: LocalSecretStore.CredentialsStoreEmptySecretException) {
            err.println("Error: empty secret not allowed")
            CredentialsOutcome.NOT_CARRIED_OUT
        } catch (e: IllegalArgumentException) {
            err.println("Error: ${e.message}")
            CredentialsOutcome.NOT_CARRIED_OUT
        } catch (e: Exception) {
            err.println("Error: ${e.message}")
            CredentialsOutcome.NOT_CARRIED_OUT
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
        err: PrintStream,
        environment: Map<String, String>,
        passphraseReader: () -> CharArray?,
        storeFactory: StoreFactory,
    ): CredentialsOutcome {
        val foreign = args.firstOrNull()
        if (foreign != null) {
            err.println(CredentialsRefusal.of(CredentialsVerb.LIST, foreign))
            return CredentialsOutcome.NOT_CARRIED_OUT
        }
        // Nothing to list means nothing to unlock: a store that is not there
        // does not need a key, and asking for one would be asking for the key
        // to a door that does not exist.
        if (!storeHoldsAnything(environment)) {
            out.println("No credentials stored.")
            return CredentialsOutcome.ANSWERED
        }
        return try {
            withStore(environment, passphraseReader, storeFactory) { store ->
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
                CredentialsOutcome.ANSWERED
            }
        } catch (e: PassphraseResolver.CredentialsStorePassphraseUnavailableException) {
            err.println("Error: ${e.message}")
            CredentialsOutcome.NO_PASSPHRASE
        } catch (e: LocalSecretStore.SecretStorePassphraseMismatchException) {
            err.println("Error: ${e.message}")
            CredentialsOutcome.WRONG_PASSPHRASE
        } catch (e: LocalSecretStore.SecretStoreTamperException) {
            err.println("Error: store tampered: ${e.message}")
            CredentialsOutcome.TAMPERED
        } catch (e: Exception) {
            err.println("Error listing credentials: ${e.message}")
            CredentialsOutcome.NOT_CARRIED_OUT
        }
    }

    private fun remove(
        args: List<String>,
        out: PrintStream,
        err: PrintStream,
        environment: Map<String, String>,
        passphraseReader: () -> CharArray?,
        storeFactory: StoreFactory,
    ): CredentialsOutcome {
        // `remove` owns NO option, so a token that looks like one is not the
        // credential id: it is refused naming it, BEFORE the store is looked
        // at. Taking it for an id would only discover the mistake at the end,
        // when a credential with that name is looked up.
        val option = args.firstOrNull { it.startsWith("--") }
        if (option != null) {
            err.println(CredentialsRefusal.of(CredentialsVerb.REMOVE, option))
            return CredentialsOutcome.NOT_CARRIED_OUT
        }
        val id = args.firstOrNull() ?: run {
            err.println(CredentialsRefusal.ofMissingArgument(CredentialsVerb.REMOVE))
            return CredentialsOutcome.NOT_CARRIED_OUT
        }
        // `remove` owns ONE argument — the id — and no option. A second token
        // is not a request of this verb, and it is refused naming it before the
        // store is looked at.
        val extra = args.getOrNull(1)
        if (extra != null) {
            err.println(CredentialsRefusal.of(CredentialsVerb.REMOVE, extra))
            return CredentialsOutcome.NOT_CARRIED_OUT
        }
        if (!storeHoldsAnything(environment)) {
            err.println("No credentials stored.")
            return CredentialsOutcome.NOT_CARRIED_OUT
        }
        return try {
            withStore(environment, passphraseReader, storeFactory) { store ->
                val credentialsId = CredentialsId.from(id)
                // Listing is also how the passphrase gets checked, so a key that
                // does not match is refused here, before anything is removed.
                if (credentialsId !in store.list()) {
                    err.println("No credential named '$id'.")
                    CredentialsOutcome.NOT_CARRIED_OUT
                } else {
                    store.remove(credentialsId)
                    out.println("Credential '$id' removed.")
                    CredentialsOutcome.ANSWERED
                }
            }
        } catch (e: PassphraseResolver.CredentialsStorePassphraseUnavailableException) {
            err.println("Error: ${e.message}")
            CredentialsOutcome.NO_PASSPHRASE
        } catch (e: LocalSecretStore.SecretStorePassphraseMismatchException) {
            err.println("Error: ${e.message}")
            CredentialsOutcome.WRONG_PASSPHRASE
        } catch (e: LocalSecretStore.SecretStoreTamperException) {
            err.println("Error: store tampered: ${e.message}")
            CredentialsOutcome.TAMPERED
        } catch (e: Exception) {
            err.println("Error: ${e.message}")
            CredentialsOutcome.NOT_CARRIED_OUT
        }
    }

    private fun rotate(
        args: List<String>,
        out: PrintStream,
        err: PrintStream,
        environment: Map<String, String>,
        stdin: InputStream,
        passphraseReader: () -> CharArray?,
        storeFactory: StoreFactory,
    ): CredentialsOutcome {
        val parsed = parseWriteArguments(args, CredentialsVerb.ROTATE, err) ?: return CredentialsOutcome.NOT_CARRIED_OUT
        if (!storeHoldsAnything(environment)) {
            err.println("No credentials stored.")
            return CredentialsOutcome.NOT_CARRIED_OUT
        }
        return try {
            withStore(environment, passphraseReader, storeFactory) { store ->
                // The key is checked before the operator is asked for a secret: a
                // passphrase that does not match should not cost them the typing.
                store.verifyPassphrase()
                val credentialsId = CredentialsId.from(parsed.id)
                if (credentialsId !in store.list()) {
                    err.println("No credential named '${parsed.id}'.")
                    CredentialsOutcome.NOT_CARRIED_OUT
                } else {
                    store.rotate(credentialsId, readCredential(parsed.kind, parsed.fromStdin, stdin))
                    out.println("Credential '${parsed.id}' rotated successfully.")
                    CredentialsOutcome.ANSWERED
                }
            }
        } catch (e: PassphraseResolver.CredentialsStorePassphraseUnavailableException) {
            err.println("Error: ${e.message}")
            CredentialsOutcome.NO_PASSPHRASE
        } catch (e: LocalSecretStore.SecretStorePassphraseMismatchException) {
            err.println("Error: ${e.message}")
            CredentialsOutcome.WRONG_PASSPHRASE
        } catch (e: LocalSecretStore.SecretStoreTamperException) {
            err.println("Error: store tampered: ${e.message}")
            CredentialsOutcome.TAMPERED
        } catch (e: LocalSecretStore.CredentialsStoreEmptySecretException) {
            err.println("Error: empty secret not allowed")
            CredentialsOutcome.NOT_CARRIED_OUT
        } catch (e: IllegalArgumentException) {
            err.println("Error: ${e.message}")
            CredentialsOutcome.NOT_CARRIED_OUT
        } catch (e: Exception) {
            err.println("Error: ${e.message}")
            CredentialsOutcome.NOT_CARRIED_OUT
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
        err: PrintStream,
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
                        err.println(CredentialsRefusal.ofMissingOptionValue(verb, CredentialsOption.KIND))
                        return null
                    }
                    kind = CredentialKind.fromString(kindStr)
                    if (kind == null) {
                        err.println("Error: unknown kind '$kindStr'. Valid kinds: ${CredentialKind.ALL_KINDS}")
                        return null
                    }
                }

                option == CredentialsOption.FROM_STDIN -> fromStdin = true
                // An option this verb does not own is NOT an option whose value
                // is missing: the operator has to be told WHICH token to
                // remove, or the refusal sends them to the wrong place.
                arg.startsWith("--") -> {
                    err.println(CredentialsRefusal.of(verb, arg))
                    return null
                }

                id == null -> id = arg
                // The SECOND positional is not a request of this verb: `add`
                // and `rotate` own ONE, the credential id. It is refused
                // naming the token, and it is refused HERE — before the store
                // is opened — so a request that is not this verb's never asks
                // for a passphrase and never asks for a secret.
                else -> {
                    err.println(CredentialsRefusal.of(verb, arg))
                    return null
                }
            }
        }

        val idArg = id ?: run {
            err.println(CredentialsRefusal.ofMissingArgument(verb))
            return null
        }
        return WriteArguments(kind ?: CredentialKind.SECRET_TEXT, idArg, fromStdin)
    }

    /**
     * Runs the work of a verb over the store this request opens, and ends the
     * session when the work is over.
     *
     * The store is opened with the passphrase the request resolved: the
     * passphrase comes from the environment when the environment names one, and
     * from the reader when it does not. The operator is asked once, and the key
     * they give is the key the store is opened with.
     *
     * The store is released when the work ends, whether it ended well or by a
     * refusal: a request that is not carried out still opened a store, and a
     * store that is not released keeps the key the operator gave it.
     */
    private fun withStore(
        environment: Map<String, String>,
        passphraseReader: () -> CharArray?,
        storeFactory: StoreFactory,
        work: (LocalSecretStore) -> CredentialsOutcome,
    ): CredentialsOutcome {
        val store = storeFactory(
            resolveStoreFile(environment),
            PassphraseResolver.resolve(environment, passphraseReader),
        )
        try {
            return work(store)
        } finally {
            closeQuietly(store)
        }
    }

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

/**
 * Releases the store, ignoring whatever it throws.
 *
 * The answer of a verb is published before the store is released, and a failure
 * while releasing must not turn a good answer into a failed one.
 */
private fun closeQuietly(store: LocalSecretStore) {
    try {
        store.close()
    } catch (_: Exception) {
        // The session is over either way.
    }
}
