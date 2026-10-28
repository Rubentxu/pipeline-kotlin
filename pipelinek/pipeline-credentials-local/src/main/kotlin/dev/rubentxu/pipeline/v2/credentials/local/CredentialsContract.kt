package dev.rubentxu.pipeline.v2.credentials.local

/**
 * The contract of the credentials command: which verb needs the key that opens
 * the store, and what each outcome costs the caller.
 *
 * The rule is one sentence: **the passphrase is the authorization to touch the
 * store**. A verb that creates the store needs the key, because the key is
 * what seals it. A verb that reads or changes what is inside needs the key
 * only when there is something inside to read or change: a store that is not
 * there has nothing to unlock, so asking for a key would be asking for the
 * key to a door that does not exist. And a key that does not match changes
 * nothing and says so.
 *
 * Both decisions are pure, so the contract can be stated once, checked without
 * a disk, a clock or a passphrase, and cannot drift from what the command
 * does.
 */

/**
 * The options the credentials command knows about.
 *
 * An option is a NAME plus the shape of the value that follows it: `--kind`
 * takes one and `--from-stdin` takes none. The command knows them all; a verb
 * knows which of them it owns.
 *
 * The distinction between an option the command knows and an option the VERB
 * owns is what the refusal rests on: an option the command does not know is
 * unknown, and an option it knows but the verb does not own is unknown TOO.
 * Neither of them is an option whose value is missing, and answering that
 * way sends the operator to the wrong place.
 */
internal enum class CredentialsOption(val flag: String, val value: String?) {
    /** The kind of credential being written. */
    KIND("--kind", "kind"),

    /** Reads the value from standard input instead of the terminal. */
    FROM_STDIN("--from-stdin", null);

    /** How the operator writes this option: `--kind <kind>`, or `--from-stdin`. */
    val form: String
        get() = if (value == null) flag else "$flag <$value>"

    companion object {
        /** The option the operator wrote, or null when the token is not one. */
        fun from(token: String): CredentialsOption? = entries.firstOrNull { it.flag == token }
    }
}

/** What the operator asked the credentials command to do. */
internal enum class CredentialsVerb {
    /** Stores a new credential, creating the store when it is not there. */
    ADD,

    /** Reports what the store holds. */
    LIST,

    /** Removes one credential from the store. */
    REMOVE,

    /** Replaces the value of one credential, keeping its identity. */
    ROTATE;

    /**
     * Whether this verb needs the key that opens the store.
     *
     * @param storeExists whether there is a store to open. The verb that
     *   creates it always needs the key, because the key is what seals it; the
     *   others need it only when there is something there to open.
     */
    fun needsKey(storeExists: Boolean): Boolean = when (this) {
        ADD -> true
        LIST, REMOVE, ROTATE -> storeExists
    }

    /** The word the operator writes to ask for this verb. */
    val token: String
        get() = name.lowercase()

    /**
     * The options this verb owns.
     *
     * `add` and `rotate` write a value and own the two options that say HOW to
     * write it. `list` and `remove` own NO option at all, so every option they
     * are given is one they do not own — including one the command knows.
     */
    val ownedOptions: List<CredentialsOption>
        get() = when (this) {
            ADD, ROTATE -> listOf(CredentialsOption.KIND, CredentialsOption.FROM_STDIN)
            LIST, REMOVE -> emptyList()
        }

    /** Whether this verb owns the option the operator wrote. */
    fun owns(option: CredentialsOption): Boolean = ownedOptions.contains(option)

    /**
     * The option the operator wrote, when THIS verb owns it.
     *
     * A verb that owns no option owns no token that looks like one, so for
     * `list` and `remove` the answer is null for every option-shaped token:
     * the refusal names it, and the verb does not take it for an id.
     */
    fun ownedOption(token: String): CredentialsOption? =
        CredentialsOption.from(token)?.takeIf { owns(it) }

    /** Whether this verb takes the credential id as its positional argument. */
    val takesId: Boolean
        get() = this != LIST

    companion object {
        /** The verb the operator asked for, or null when the token is not one. */
        fun from(token: String?): CredentialsVerb? =
            token?.let { candidate -> entries.firstOrNull { it.token == candidate } }
    }
}

/** How an attempt to carry out a request ended. */
internal enum class CredentialsOutcome {
    /** The request was carried out, or there was nothing to carry out. */
    ANSWERED,

    /** The request could not be carried out: no such credential, bad arguments. */
    NOT_CARRIED_OUT,

    /** No passphrase is available to open the store. */
    NO_PASSPHRASE,

    /** The passphrase does not match the store. */
    WRONG_PASSPHRASE,

    /** The store is tampered. */
    TAMPERED;

    /** The exit code the caller sees. */
    val exitCode: Int
        get() = when (this) {
            ANSWERED -> 0
            NOT_CARRIED_OUT -> 1
            NO_PASSPHRASE -> 2
            WRONG_PASSPHRASE -> 3
            TAMPERED -> 4
        }
}
