package dev.rubentxu.pipeline.v2.credentials.local

/**
 * How the bytes a caller sends become the value of a credential.
 *
 * ## Why there are two
 * A shell writes a newline after every value it pipes in. For a value that
 * arrives line-wise that newline TERMINATES the value, and dropping it is what
 * makes `echo secret |` store the secret the caller meant. For a value that IS
 * a file there is no terminator: the bytes are the value, the last one
 * included, and a file that ends in a newline is a file, not a line.
 *
 * ## Why the framing belongs to the kind and not to the reader
 * A reader is one transport among several — a terminal reads the same kinds
 * from a path — and the two have to agree on what the value is, or a store
 * seeded one way differs from a store seeded the other. When the rule lived in
 * the reader alone, the reader that dropped the newline and the reader that
 * read a path disagreed about the very same kind, and the disagreement was a
 * file stored one byte short of the one that was given. The framing is a fact
 * about the KIND, so every transport asks the same question and gets the same
 * answer.
 */
internal enum class CredentialFraming {

    /** The value is closed by a newline, and the newline is not part of it. */
    TERMINATED,

    /** The value is the bytes, whole: a file, a keystore, an archive. */
    WHOLE;

    /**
     * The bytes that carry the value, once the newline the transport writes has
     * been given the meaning this framing gives it.
     */
    fun value(bytes: ByteArray): ByteArray = when (this) {
        TERMINATED -> bytes.withoutTrailingNewline()
        WHOLE -> bytes
    }
}

/**
 * Drops the one newline that closes a value delivered line-wise.
 *
 * Exactly one, and only at the end: a value that contains newlines keeps them,
 * because a password is read as "everything after the first newline" and the
 * caller sends one more newline to close it.
 */
private fun ByteArray.withoutTrailingNewline(): ByteArray =
    if (isNotEmpty() && this[size - 1] == '\n'.code.toByte()) copyOf(size - 1) else this
