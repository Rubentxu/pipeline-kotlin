package dev.rubentxu.pipeline.v2.credentials.local

import dev.rubentxu.pipeline.v2.credentials.local.LocalSecretStore.SecretStoreTamperException
import dev.rubentxu.pipeline.v2.credentials.local.LocalSecretStore.StoreHeader
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.credentials.Certificate
import dev.rubentxu.pipeline.v2.domain.credentials.Credential
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialScope
import dev.rubentxu.pipeline.v2.domain.credentials.SecretFile
import dev.rubentxu.pipeline.v2.domain.credentials.SecretText
import dev.rubentxu.pipeline.v2.domain.credentials.SshPrivateKey
import dev.rubentxu.pipeline.v2.domain.credentials.UsernameColonPassword
import dev.rubentxu.pipeline.v2.domain.credentials.UsernamePassword
import dev.rubentxu.pipeline.v2.domain.credentials.Zip
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The kinds of credential the store file can carry, and the ID each one has
 * on the wire.
 *
 * The ID is a value of the FORMAT: it is the two bytes that follow the
 * credential ID in an entry, and it is what says which content form the
 * entry holds. There is ONE table, and the serializer that writes the content
 * and the codec that frames the entry both read it, so the two cannot say
 * different things about the same kind.
 *
 * The numbers are a wire contract: a store written by an earlier release
 * carries these numbers, and a store that carried another one would be read
 * as a different kind of credential.
 */
internal object CredentialKinds {

    /** The kind of a credential that is one secret. */
    const val SECRET_TEXT: Short = 1

    /** The kind of a credential that is a username and a password. */
    const val USERNAME_PASSWORD: Short = 2

    /** The kind of a credential that is a private key and its owner. */
    const val SSH_PRIVATE_KEY: Short = 3

    /** The kind of a credential that is a file and its name. */
    const val SECRET_FILE: Short = 4

    /** The kind of a credential that is a keystore and its alias. */
    const val CERTIFICATE: Short = 5

    /** The kind of a credential that is an archive. */
    const val ZIP: Short = 6

    /** The kind of a credential that is a username and a password joined by a colon. */
    const val USERNAME_COLON_PASSWORD: Short = 7

    /**
     * The ID a credential is written with.
     *
     * @param credential the credential whose kind is asked for
     * @return the ID of its kind
     */
    fun idOf(credential: Credential): Short = when (credential) {
        is SecretText -> SECRET_TEXT
        is UsernamePassword -> USERNAME_PASSWORD
        is SshPrivateKey -> SSH_PRIVATE_KEY
        is SecretFile -> SECRET_FILE
        is Certificate -> CERTIFICATE
        is Zip -> ZIP
        is UsernameColonPassword -> USERNAME_COLON_PASSWORD
    }

    /**
     * The name of a kind, as the credential names it.
     *
     * @param kindId the ID whose name is asked for
     * @return the name, or null when the ID names no kind of credential
     */
    fun nameOf(kindId: Short): String? = when (kindId) {
        SECRET_TEXT -> "SecretText"
        USERNAME_PASSWORD -> "UsernamePassword"
        SSH_PRIVATE_KEY -> "SshPrivateKey"
        SECRET_FILE -> "SecretFile"
        CERTIFICATE -> "Certificate"
        ZIP -> "Zip"
        USERNAME_COLON_PASSWORD -> "UsernameColonPassword"
        else -> null
    }
}

/**
 * One entry of the store file.
 *
 * The entry carries the kind of credential it holds, whether or not the
 * format stores it. The legacy format stores no kind: its entries hold one
 * secret, and the kind of that secret is the secret text's, so the kind of a
 * legacy entry is DERIVED and not read.
 */
internal data class StoreEntry(
    val id: CredentialsId,
    val sealed: AeadCipher.SealedBlob,
    val kindId: Short,
)

/**
 * The walk over a chain of linked secrets.
 *
 * The format asks for a part by name and the walk answers it, following the
 * references of the chain until it reaches the secret at the end of it.
 */
internal fun interface LinkedSecretWalker {
    /**
     * The part of the credential, by name.
     *
     * @param plaintext the open bytes of the credential
     * @param kindId the kind of the credential
     * @param id the credential the part belongs to
     * @param partName the name of the part
     * @param visited the references already walked
     * @return the bytes of the part
     */
    fun extract(
        plaintext: ByteArray,
        kindId: Short,
        id: CredentialsId,
        partName: String,
        visited: MutableSet<CredentialsId>,
    ): SecretHandle
}

/**
 * The behaviour of ONE version of the store file format.
 *
 * Everything that differs between versions lives behind this seam: how an
 * entry is framed, with what AAD it is sealed, and what its bytes mean once
 * they are open. A caller asks the format for those things and never asks
 * "which version is this?" by itself, so the version is decided in one place
 * and not in every caller.
 */
internal interface StoreFormat {

    /** The version this format is published under. */
    val version: Short

    /**
     * The entries the file holds.
     *
     * The reader consumes the WHOLE body or refuses. A file whose body is not
     * the body this version describes is not a store that holds fewer
     * credentials: it is a store that cannot be read, and the refusal says
     * so instead of answering with an empty listing.
     *
     * @param fileBytes the bytes of the store file
     * @return the entries, by credential ID
     * @throws SecretStoreTamperException when the body is not the body this
     *   version describes, or when it carries a kind that names no credential
     */
    fun read(fileBytes: ByteArray): Map<String, StoreEntry>

    /**
     * The bytes one entry is written as.
     *
     * @param entry the entry to write
     * @return the bytes of the entry
     */
    fun encode(entry: StoreEntry): ByteArray

    /**
     * The AAD one entry is sealed under.
     *
     * The AAD binds the entry to the header it lives under and to the
     * credential it belongs to, so an entry moved from one place to another
     * does not open.
     *
     * @param header the header the entry lives under
     * @param id the credential the entry belongs to
     * @param kindId the kind of the credential
     * @return the AAD
     */
    fun aad(header: StoreHeader, id: CredentialsId, kindId: Short): ByteArray

    /**
     * The credential one entry holds, once its bytes are open.
     *
     * @param entry the entry the bytes came from
     * @param plaintext the open bytes of the entry
     * @param id the credential the entry belongs to
     * @return the credential
     */
    fun credential(entry: StoreEntry, plaintext: ByteArray, id: CredentialsId): Credential

    /**
     * The reason this format's entries have no part by name, or null when they
     * have.
     *
     * The reason is asked BEFORE the credential is looked up, so a store that
     * cannot answer the question at all says so, and does not answer about a
     * credential that is not there.
     */
    fun partsRefusal(): String?

    /**
     * The part of a credential, by name.
     *
     * @param entry the entry the bytes came from
     * @param plaintext the open bytes of the entry
     * @param id the credential the entry belongs to
     * @param partName the name of the part
     * @param walk the walk over the chain of linked secrets
     * @return the bytes of the part
     * @throws SecretStoreTamperException when this format's entries have no
     *   part by name
     */
    fun part(
        entry: StoreEntry,
        plaintext: ByteArray,
        id: CredentialsId,
        partName: String,
        walk: LinkedSecretWalker,
    ): SecretHandle
}

/**
 * The authority of the store file format.
 *
 * This is the ONE place that decides which version a file is in, and the ONE
 * place that knows how a version frames its header, its entries and their
 * seal. The store asks it; the store does not decide.
 *
 * ## The two versions
 * - **The legacy version** holds one secret per entry, and the secret IS the
 *   bytes of the entry. Its AAD carries no kind.
 * - **The current version** holds a credential in the canonical part form, and
 *   its AAD carries the kind of that credential.
 *
 * ## The header
 * Both versions share the header layout, which is 74 bytes:
 * `magic(4) + version(2) + argon2M(4) + argon2T(4) + argon2P(4) + salt(16) +
 * wrappedDek(40)`. Only the version field differs.
 */
internal object StoreCodec {

    /** The version of the format that holds one secret per entry. */
    const val VERSION_LEGACY: Short = 1

    /** The version of the format that holds a credential in the canonical part form. */
    const val VERSION_CURRENT: Short = 2

    /** The size of the salt the header carries, in bytes. */
    const val SALT_SIZE: Int = 16

    /** The size of the header, in bytes. Both versions share the layout. */
    const val HEADER_SIZE: Int = 4 + 2 + 4 + 4 + 4 + SALT_SIZE + CredentialCrypto.WRAPPED_DEK_SIZE

    /**
     * The format of one version.
     *
     * @param version the version of the file
     * @return the format that version describes
     * @throws SecretStoreTamperException when the version is one this store
     *   does not know
     */
    fun of(version: Short): StoreFormat = when (version) {
        VERSION_LEGACY -> LegacyStoreFormat
        VERSION_CURRENT -> CurrentStoreFormat
        else -> throw SecretStoreTamperException("Unsupported store version: $version")
    }

    /**
     * The header the file carries.
     *
     * @param bytes the bytes of the store file
     * @return the header
     * @throws SecretStoreTamperException when the magic is not the store's, or
     *   the version is one the store does not know
     */
    fun readHeader(bytes: ByteArray): StoreHeader {
        if (bytes.size < HEADER_SIZE) {
            throw SecretStoreTamperException("Store file is shorter than its header")
        }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4)
        buf.get(magic)
        if (!magic.contentEquals(MAGIC)) {
            throw SecretStoreTamperException("Invalid store magic")
        }
        val version = buf.short
        if (version != VERSION_LEGACY && version != VERSION_CURRENT) {
            throw SecretStoreTamperException("Unsupported store version: $version")
        }
        val m = buf.int
        val t = buf.int
        val p = buf.int
        val salt = ByteArray(SALT_SIZE)
        buf.get(salt)
        val wrappedDek = ByteArray(WRAPPED_DEK_SIZE)
        buf.get(wrappedDek)
        return StoreHeader(magic, version, m, t, p, salt, wrappedDek)
    }

    /**
     * Writes the header of the store file.
     *
     * @param out where the header is written
     * @param header the header to write
     */
    fun writeHeader(out: ByteArrayOutputStream, header: StoreHeader) {
        out.write(header.magic)
        out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(header.version).array())
        val kdf = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(header.kdfM).putInt(header.kdfT).putInt(header.kdfP)
        out.write(kdf.array())
        out.write(header.kdfSalt)
        out.write(header.wrappedDek)
    }

    /**
     * The header of a store that does not exist yet.
     *
     * A new store is published in the CURRENT version, with the key
     * derivation floor and a wrapped key that is still empty: the key is
     * wrapped when the first entry is written.
     *
     * @param salt the salt the header carries
     * @param wrappedDek the wrapped data key the header carries
     * @return the header
     */
    fun newHeader(salt: ByteArray, wrappedDek: ByteArray): StoreHeader = StoreHeader(
        magic(),
        VERSION_CURRENT,
        CredentialCrypto.OWASP_M,
        CredentialCrypto.OWASP_T,
        CredentialCrypto.OWASP_P,
        salt,
        wrappedDek,
    )

    /**
     * The magic the header carries: "PKCR" (Pipeline CRedentials).
     *
     * @return a copy of the magic
     */
    fun magic(): ByteArray = MAGIC.copyOf()

    /** The magic the header carries: "PKCR" (Pipeline CRedentials). */
    private val MAGIC = byteArrayOf(0x50, 0x4B, 0x43, 0x52)

    /** The size of the wrapped data key the header carries. */
    private const val WRAPPED_DEK_SIZE = CredentialCrypto.WRAPPED_DEK_SIZE
}

/**
 * The format that holds one secret per entry.
 *
 * The legacy format is READ, never written: the product opens a store of
 * this format and migrates it to the current one. The bytes it produces are
 * the bytes an earlier release produced, and the fixture that writes one
 * uses this format to do it.
 */
private object LegacyStoreFormat : StoreFormat {

    override val version: Short = StoreCodec.VERSION_LEGACY

    /** The reason a store of this format cannot answer a question about a part. */
    private const val NO_PARTS =
        "the format this store carries holds one secret per entry, and it has no part by name"

    override fun read(fileBytes: ByteArray): Map<String, StoreEntry> =
        readEntries(fileBytes, version, carriesKind = false)

    override fun encode(entry: StoreEntry): ByteArray {
        val idBytes = entry.id.value.toByteArray(Charsets.UTF_8)
        val blobBytes = entry.sealed.toByteArray()
        val plaintextLen = blobBytes.size - AeadCipher.NONCE_SIZE_BYTES - AeadCipher.TAG_SIZE_BYTES
        // Entry format: idLen(2) + idBytes + plaintextLen(4) + nonce(12) + ciphertext + tag(16)
        return ByteBuffer.allocate(2 + idBytes.size + 4 + blobBytes.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(idBytes.size.toShort())
            .put(idBytes)
            .putInt(plaintextLen)
            .put(blobBytes)
            .array()
    }

    /**
     * The AAD of the legacy format: the header and the credential, and NO
     * kind. The kind of a legacy entry is derived, and the seal of one does
     * not carry it.
     */
    override fun aad(header: StoreHeader, id: CredentialsId, kindId: Short): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(header.magic)
        out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(header.version).array())
        val kdf = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(header.kdfM).putInt(header.kdfT).putInt(header.kdfP)
        out.write(kdf.array())
        out.write(header.kdfSalt)
        out.write(id.value.toByteArray(Charsets.UTF_8))
        return out.toByteArray()
    }

    /** A legacy entry holds one secret, and the secret IS its bytes. */
    override fun credential(entry: StoreEntry, plaintext: ByteArray, id: CredentialsId): Credential =
        SecretText(id, CredentialScope.GLOBAL, plaintext)

    override fun partsRefusal(): String? = NO_PARTS

    override fun part(
        entry: StoreEntry,
        plaintext: ByteArray,
        id: CredentialsId,
        partName: String,
        walk: LinkedSecretWalker,
    ): SecretHandle = throw SecretStoreTamperException(NO_PARTS)
}

/**
 * The format that holds a credential in the canonical part form.
 *
 * The entry carries the kind of the credential it holds, and the seal carries
 * that kind too, so an entry moved from one kind to another does not open.
 */
private object CurrentStoreFormat : StoreFormat {

    override val version: Short = StoreCodec.VERSION_CURRENT

    override fun read(fileBytes: ByteArray): Map<String, StoreEntry> =
        readEntries(fileBytes, version, carriesKind = true)

    override fun encode(entry: StoreEntry): ByteArray {
        val idBytes = entry.id.value.toByteArray(Charsets.UTF_8)
        val blobBytes = entry.sealed.toByteArray()
        val plaintextLen = blobBytes.size - AeadCipher.NONCE_SIZE_BYTES - AeadCipher.TAG_SIZE_BYTES
        // Entry format: idLen(2) + idBytes + kind(2) + plaintextLen(4) + nonce(12) + ciphertext + tag(16)
        return ByteBuffer.allocate(2 + idBytes.size + 2 + 4 + blobBytes.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(idBytes.size.toShort())
            .put(idBytes)
            .putShort(entry.kindId)
            .putInt(plaintextLen)
            .put(blobBytes)
            .array()
    }

    /**
     * The AAD of the current format: the header, the credential, and the kind
     * of the credential behind a colon.
     */
    override fun aad(header: StoreHeader, id: CredentialsId, kindId: Short): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(header.magic)
        out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(header.version).array())
        val kdf = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(header.kdfM).putInt(header.kdfT).putInt(header.kdfP)
        out.write(kdf.array())
        out.write(header.kdfSalt)
        out.write(id.value.toByteArray(Charsets.UTF_8))
        out.write(':'.code)
        out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(kindId).array())
        return out.toByteArray()
    }

    /** The credential of the current entry is its canonical part form. */
    override fun credential(entry: StoreEntry, plaintext: ByteArray, id: CredentialsId): Credential =
        CredentialSerializer.deserializeCredential(plaintext, entry.kindId, id)

    override fun partsRefusal(): String? = null

    override fun part(
        entry: StoreEntry,
        plaintext: ByteArray,
        id: CredentialsId,
        partName: String,
        walk: LinkedSecretWalker,
    ): SecretHandle = walk.extract(plaintext, entry.kindId, id, partName, mutableSetOf(id))
}

/**
 * Reads the entries of a store file, in the framing of one version.
 *
 * The reader consumes the WHOLE body or refuses. A length that does not fit,
 * an entry that is cut short and an ID length that no credential can have are
 * all the same fact seen from three sides: the body is not the body this
 * version describes.
 *
 * @param fileBytes the bytes of the store file
 * @param version the version whose framing is read
 * @param carriesKind whether the entries of that version carry a kind
 * @return the entries, by credential ID
 * @throws SecretStoreTamperException when the body is not the body this
 *   version describes, or when it carries a kind that names no credential
 */
private fun readEntries(
    fileBytes: ByteArray,
    version: Short,
    carriesKind: Boolean,
): Map<String, StoreEntry> {
    val result = mutableMapOf<String, StoreEntry>()
    var offset = StoreCodec.HEADER_SIZE
    while (offset < fileBytes.size) {
        val buf = ByteBuffer.wrap(fileBytes, offset, fileBytes.size - offset)
            .order(ByteOrder.LITTLE_ENDIAN)
        val idLen = buf.short.toInt()
        if (idLen <= 0 || idLen > MAX_ID_LENGTH) {
            refuse(version, offset, "the length of its credential id ($idLen) is not one a credential can have")
        }
        if (buf.remaining() < idLen) {
            refuse(version, offset, "its credential id is cut short")
        }
        val idBytes = ByteArray(idLen)
        buf.get(idBytes)
        val id = String(idBytes, Charsets.UTF_8)
        val kindId = if (carriesKind) {
            if (buf.remaining() < 2) {
                refuse(version, offset, "the kind of its credential is cut short")
            }
            val read = buf.short
            if (CredentialKinds.nameOf(read) == null) {
                throw SecretStoreTamperException(
                    "the entry '$id' carries kind $read, which names no kind of credential",
                )
            }
            read
        } else {
            // The legacy format stores no kind: its entries hold one secret,
            // and the kind of that secret is the secret text's.
            CredentialKinds.SECRET_TEXT
        }
        if (buf.remaining() < 4) {
            refuse(version, offset, "the length of its secret is cut short")
        }
        val plaintextLen = buf.int
        if (plaintextLen <= 0 || plaintextLen > MAX_PLAINTEXT_LENGTH) {
            refuse(version, offset, "the length of its secret ($plaintextLen) is not one a secret can have")
        }
        val blobLen = AeadCipher.NONCE_SIZE_BYTES + plaintextLen + AeadCipher.TAG_SIZE_BYTES
        if (buf.remaining() < blobLen) {
            refuse(version, offset, "its secret is cut short")
        }
        val sealedBytes = ByteArray(blobLen)
        buf.get(sealedBytes)
        result[id] = StoreEntry(CredentialsId.from(id), AeadCipher.SealedBlob(sealedBytes), kindId)
        offset += 2 + idLen + (if (carriesKind) 2 else 0) + 4 + blobLen
    }
    return result
}

/**
 * The refusal of a body that is not the body its version describes.
 *
 * The refusal names the version whose framing failed and where it failed, so
 * the operator knows that the file is not the file it says it is.
 */
private fun refuse(version: Short, offset: Int, reason: String): Nothing =
    throw SecretStoreTamperException(
        "the store's body is not the body the version $version format describes: " +
            "at offset $offset, $reason",
    )

/** The longest credential ID an entry may carry. */
private const val MAX_ID_LENGTH = 1024

/** The longest secret an entry may carry. */
private const val MAX_PLAINTEXT_LENGTH = 1_000_000
