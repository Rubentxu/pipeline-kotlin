package dev.rubentxu.pipeline.v2.credentials.local

import dev.rubentxu.pipeline.v2.domain.credentials.Credential
import java.io.InputStream

/**
 * Collects one credential of each supported kind from bytes already on a
 * stream, so a store can be seeded without an interactive terminal.
 * The stream carries the value's bytes, whole. Nothing is quoted, escaped or
 * re-encoded, which matters because three of the kinds are binary and a text
 * round trip would corrupt them. Two details make that rule usable rather than
 * merely correct:
 * - The newline a shell adds is dropped ONLY from the values that arrive
 *   line-wise, because only those are closed by it. A value that IS a file
 *   has no terminator: its last byte is content, and a file that ends in a
 *   newline is a file like any other. Which kinds are which is the kind's own
 *   answer — [MainCredentialsCli.CredentialKind.framing] — so that a store
 *   seeded from a stream holds the same value as one seeded from a path.
 * - The kinds that carry a username take it from the first line and treat
 *   everything after that newline as the password, verbatim. One separator, one
 *   meaning: a password that itself ends in a newline keeps it, and the caller
 *   sends one more to close the value.
 * As with the terminal, this object decides only where the values come from;
 * [CredentialValues] decides whether each is acceptable, so a value piped in
 * and the same value typed are accepted or refused on identical grounds.
*/
internal object StreamCredentialPrompts {

    fun read(kind: MainCredentialsCli.CredentialKind, stdin: InputStream): Credential {
        val bytes = kind.framing.value(stdin.readBytes())
        return when (kind) {
            MainCredentialsCli.CredentialKind.SECRET_TEXT ->
                CredentialValues.secretText(bytes)

            MainCredentialsCli.CredentialKind.USERNAME_PASSWORD -> {
                val (username, password) = bytes.splitAtFirstNewline("username and password")
                CredentialValues.usernamePassword(username, password)
            }

            MainCredentialsCli.CredentialKind.USERNAME_COLON_PASSWORD -> {
                val (username, password) = bytes.splitAtFirstNewline("username and password")
                CredentialValues.usernameColonPassword(username, password)
            }

            // The key is text, but reading it as bytes costs nothing and keeps
            // this arm free of a rule that applies to no other kind.
            MainCredentialsCli.CredentialKind.SSH_PRIVATE_KEY -> {
                val (username, key) = bytes.splitAtFirstNewline("SSH username and private key")
                CredentialValues.sshPrivateKey(username, key)
            }

            // A name for the entry, not a second source. The bytes are the
            // file; nothing is read from the filesystem here, so a seeded store
            // never depends on a path that existed on the seeding machine.
            MainCredentialsCli.CredentialKind.SECRET_FILE ->
                CredentialValues.secretFile(bytes, "stdin")

            MainCredentialsCli.CredentialKind.CERTIFICATE ->
                CredentialValues.certificate(bytes, alias = null)

            MainCredentialsCli.CredentialKind.ZIP ->
                CredentialValues.zip(bytes)
        }
    }

    private fun ByteArray.splitAtFirstNewline(what: String): Pair<String, ByteArray> {
        val newline = indexOf('\n'.code.toByte())
        require(newline >= 0) { "$what need two values separated by the first newline" }
        val head = String(copyOfRange(0, newline), Charsets.UTF_8)
        return head to copyOfRange(newline + 1, size)
    }
}
