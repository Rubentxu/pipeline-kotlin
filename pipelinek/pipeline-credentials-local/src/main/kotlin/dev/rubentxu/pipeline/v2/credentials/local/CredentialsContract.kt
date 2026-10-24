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
