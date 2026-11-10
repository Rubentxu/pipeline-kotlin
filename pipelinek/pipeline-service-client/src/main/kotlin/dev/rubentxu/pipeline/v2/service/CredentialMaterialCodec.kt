package dev.rubentxu.pipeline.v2.service

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.credentials.Certificate
import dev.rubentxu.pipeline.v2.domain.credentials.Credential
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialScope
import dev.rubentxu.pipeline.v2.domain.credentials.LinkedSecretRef
import dev.rubentxu.pipeline.v2.domain.credentials.SecretFile
import dev.rubentxu.pipeline.v2.domain.credentials.SecretText
import dev.rubentxu.pipeline.v2.domain.credentials.SshPrivateKey
import dev.rubentxu.pipeline.v2.domain.credentials.Zip

/**
 * The form a credential travels in, and the only code that reads and writes it.
 *
 * ## What this is
 *
 * A credential that is more than one secret --a private key and its passphrase, a
 * keystore and its password, an archive of several entries-- cannot travel as ONE
 * opaque value. This is the form that carries all of its parts, and it is the
 * form the controller of a run uses to hand over a credential it holds.
 *
 * ## The shape
 *
 * ```
 * byte  0        the version of the form
 * byte  1        how many bytes the word of the kind takes
 * bytes 2..      the word of the kind, in UTF-8
 * byte           the scope of the credential (0 GLOBAL, 1 SYSTEM)
 * rest           the parts of that kind
 * ```
 *
 * Every length is a four-byte number, and a length of -1 means "there is none".
 * The word of the kind is INSIDE the form, so a form says what it is: a reader
 * never has to be told, and a form of one kind cannot be read as another.
 *
 * ## One word, one owner
 *
 * The words below are the same words the operator writes in the authorizations
 * of the service (`credentials=deploy-key:private-key`), the same words that
 * travel in the question to a controller, and the same words this form carries.
 * One vocabulary, written down once, is here.
 *
 * ## What the form does NOT carry
 *
 * The bytes of a linked secret. A passphrase or a keystore password is a
 * credential of its own, and this form names it --it carries its id-- instead
 * of carrying its value. The reader resolves that id where it resolves every
 * other credential, so the value travels once and only to whoever is entitled
 * to it.
 *
 * ## What reading is
 *
 * Total. A form that is truncated, that names a kind this code does not know,
 * that carries a length that does not fit, or that carries a part it cannot
 * read, is NOT a credential: [decode] answers null and keeps nothing. A reader
 * that guessed would hand a caller a credential nobody wrote.
 */
object CredentialMaterialCodec {

    /** The kind of a credential that is ONE secret. */
    const val KIND_TEXT: String = "string"

    /** The kind of a credential that is a file and the name it had. */
    const val KIND_FILE: String = "file"

    /** The kind of a credential that is a private key and the passphrase it may carry. */
    const val KIND_PRIVATE_KEY: String = "private-key"

    /** The kind of a credential that is a keystore and the password it may carry. */
    const val KIND_KEYSTORE: String = "keystore"

    /** The kind of a credential that is an archive of several entries. */
    const val KIND_ARCHIVE: String = "archive"

    /** The words this form knows, for a sentence that tells a reader what to write. */
    val kinds: List<String> = listOf(KIND_TEXT, KIND_FILE, KIND_PRIVATE_KEY, KIND_KEYSTORE, KIND_ARCHIVE)

    /**
     * The word of the kind [credential] is, or null when its kind is not one this form carries.
     *
     * A kind this form does not carry is not an error here: it is a fact about the credential, and
     * the caller decides what to do with a credential it cannot hand over.
     */
    fun kindOf(credential: Credential): String? = when (credential) {
        is SecretText -> KIND_TEXT
        is SecretFile -> KIND_FILE
        is SshPrivateKey -> KIND_PRIVATE_KEY
        is Certificate -> KIND_KEYSTORE
        is Zip -> KIND_ARCHIVE
        else -> null
    }

    /**
     * The form [credential] travels in.
     *
     * The result holds the material of the credential: a caller that encodes it wipes it as soon as
     * it has written it down, and the form itself says nothing about what it holds.
     *
     * @throws IllegalArgumentException when the kind of [credential] is not one this form carries.
     */
    fun encode(credential: Credential): ByteArray {
        val word = kindOf(credential)
            ?: throw IllegalArgumentException(
                "a credential of kind ${credential::class.simpleName} has no form to travel in",
            )
        val out = Writer()
        out.byte(VERSION)
        val wordBytes = word.toByteArray(Charsets.UTF_8)
        out.byte(wordBytes.size)
        out.raw(wordBytes)
        out.byte(scopeOf(credential.scope))
        when (credential) {
            is SecretText -> out.bytes(credential.bytes)
            is SecretFile -> {
                out.text(credential.originalName)
                out.bytes(credential.bytes)
            }
            is SshPrivateKey -> {
                out.text(credential.username)
                out.bytes(credential.privateKey)
                out.text(credential.passphraseRef?.credentialsId?.value)
            }
            is Certificate -> {
                out.text(credential.alias)
                out.bytes(credential.keystore)
                out.text(credential.passwordRef?.credentialsId?.value)
            }
            is Zip -> {
                out.int(credential.entries.size)
                for ((name, bytes) in credential.entries) {
                    out.text(name)
                    out.bytes(bytes)
                }
            }
            else -> throw IllegalArgumentException(
                "a credential of kind ${credential::class.simpleName} has no form to travel in",
            )
        }
        return out.toByteArray()
    }

    /**
     * The credential the form [bytes] carries, under the name [id], or null when it carries none.
     *
     * The name is NOT in the form: the form travels inside a question that already names the
     * credential, and a name carried twice is a name that can disagree with itself.
     */
    fun decode(id: CredentialsId, bytes: ByteArray): Credential? {
        val reader = Reader(bytes)
        if (reader.byte() != VERSION) return null
        val wordLength = reader.byte() ?: return null
        if (wordLength <= 0 || wordLength > MAX_WORD_BYTES) return null
        val word = reader.raw(wordLength)?.toString(Charsets.UTF_8) ?: return null
        val scope = when (reader.byte()) {
            SCOPE_GLOBAL -> CredentialScope.GLOBAL
            SCOPE_SYSTEM -> CredentialScope.SYSTEM
            else -> return null
        }
        val credential: Credential? = when (word) {
            KIND_TEXT -> decodeText(id, scope, reader)
            KIND_FILE -> decodeFile(id, scope, reader)
            KIND_PRIVATE_KEY -> decodePrivateKey(id, scope, reader)
            KIND_KEYSTORE -> decodeKeystore(id, scope, reader)
            KIND_ARCHIVE -> decodeArchive(id, scope, reader)
            else -> null
        }
        return if (reader.failed) null else credential
    }

    /** The credential of one secret: the bytes, and nothing else. */
    private fun decodeText(id: CredentialsId, scope: CredentialScope, reader: Reader): Credential? =
        reader.bytes()?.let { SecretText(id = id, scope = scope, bytes = it) }

    /** The credential of a file: the name it had, and the bytes. */
    private fun decodeFile(id: CredentialsId, scope: CredentialScope, reader: Reader): Credential? {
        val name = reader.text()
        val content = reader.bytes() ?: return null
        return SecretFile(id = id, scope = scope, bytes = content, originalName = name)
    }

    /** The credential of a private key: the user, the key, and the passphrase it may carry. */
    private fun decodePrivateKey(id: CredentialsId, scope: CredentialScope, reader: Reader): Credential? {
        val username = reader.text() ?: return null
        val key = reader.bytes() ?: return null
        val passphrase = reader.text()?.let { LinkedSecretRef(CredentialsId(it)) }
        return SshPrivateKey(
            id = id,
            scope = scope,
            username = username,
            privateKey = key,
            passphraseRef = passphrase,
        )
    }

    /** The credential of a keystore: the alias, the keystore, and the password it may carry. */
    private fun decodeKeystore(id: CredentialsId, scope: CredentialScope, reader: Reader): Credential? {
        val alias = reader.text()
        val keystore = reader.bytes() ?: return null
        val password = reader.text()?.let { LinkedSecretRef(CredentialsId(it)) }
        return Certificate(
            id = id,
            scope = scope,
            keystore = keystore,
            passwordRef = password,
            alias = alias,
        )
    }

    /** The credential of an archive: the entries it holds. */
    private fun decodeArchive(id: CredentialsId, scope: CredentialScope, reader: Reader): Credential? {
        val count = reader.int() ?: return null
        if (count < 0 || count > MAX_ENTRIES) return null
        val entries = LinkedHashMap<String, ByteArray>()
        var malformed = false
        repeat(count) {
            if (malformed) return@repeat
            val name = reader.text()
            val content = reader.bytes()
            if (name == null || content == null) {
                malformed = true
            } else {
                entries[name] = content
            }
        }
        return if (malformed) null else Zip(id = id, scope = scope, entries = entries)
    }

    /**
     * Wipes the material of [credential] that lives in memory.
     *
     * The form of a credential is a COPY of its material, and whoever encodes it wipes the copy.
     * The credential itself came from the provider, and the caller of the provider is the one
     * that holds it: this is how it gives it back. A credential that is not of a kind this form
     * carries has nothing here to wipe.
     */
    fun wipe(credential: Credential) {
        when (credential) {
            is SecretText -> credential.bytes.fill(0)
            is SecretFile -> credential.bytes.fill(0)
            is SshPrivateKey -> credential.privateKey.fill(0)
            is Certificate -> credential.keystore.fill(0)
            is Zip -> credential.entries.values.forEach { it.fill(0) }
            else -> Unit
        }
    }

    /** The number of the scope [scope] travels as. */
    private fun scopeOf(scope: CredentialScope): Int = when (scope) {
        CredentialScope.GLOBAL -> SCOPE_GLOBAL
        CredentialScope.SYSTEM -> SCOPE_SYSTEM
    }

    /** The version of the form this code reads and writes. */
    private const val VERSION: Int = 1

    /** The scope of a credential that may be used anywhere. */
    private const val SCOPE_GLOBAL: Int = 0

    /** The scope of a credential that may only be used by the system. */
    private const val SCOPE_SYSTEM: Int = 1

    /** The longest word of a kind this code accepts. */
    private const val MAX_WORD_BYTES: Int = 32

    /** The most entries an archive may carry. */
    private const val MAX_ENTRIES: Int = 4096

    /** The number that means "there is no length here". */
    private const val ABSENT: Int = -1

    /** Writes the parts of a form. */
    private class Writer {

        private val bytes = java.io.ByteArrayOutputStream()

        fun byte(value: Int) {
            bytes.write(value and 0xff)
        }

        fun int(value: Int) {
            bytes.write((value ushr 24) and 0xff)
            bytes.write((value ushr 16) and 0xff)
            bytes.write((value ushr 8) and 0xff)
            bytes.write(value and 0xff)
        }

        fun raw(value: ByteArray) {
            bytes.write(value)
        }

        /** The bytes. */
        fun bytes(value: ByteArray) {
            int(value.size)
            bytes.write(value)
        }

        /** The text, or nothing when there is none. */
        fun text(value: String?) {
            if (value == null) {
                int(ABSENT)
                return
            }
            val encoded = value.toByteArray(Charsets.UTF_8)
            int(encoded.size)
            bytes.write(encoded)
        }

        fun toByteArray(): ByteArray = bytes.toByteArray()
    }

    /** Reads the parts of a form, and remembers that it could not. */
    private class Reader(private val bytes: ByteArray) {

        private var position = 0

        /** Whether a part of the form was not there. */
        var failed: Boolean = false
            private set

        fun byte(): Int? {
            if (position >= bytes.size) {
                failed = true
                return null
            }
            return bytes[position++].toInt() and 0xff
        }

        fun int(): Int? {
            val first = byte() ?: return null
            val second = byte() ?: return null
            val third = byte() ?: return null
            val fourth = byte() ?: return null
            return (first shl 24) or (second shl 16) or (third shl 8) or fourth
        }

        fun raw(length: Int): ByteArray? {
            if (length < 0 || position + length > bytes.size) {
                failed = true
                return null
            }
            val value = bytes.copyOfRange(position, position + length)
            position += length
            return value
        }

        fun bytes(): ByteArray? {
            val length = int() ?: return null
            if (length < 0) {
                failed = true
                return null
            }
            return raw(length)
        }

        /** The text, or null when there is none. */
        fun text(): String? {
            val length = int() ?: return null
            if (length == ABSENT) return null
            if (length < 0) {
                failed = true
                return null
            }
            val raw = raw(length) ?: return null
            return String(raw, Charsets.UTF_8)
        }
    }
}
