package dev.rubentxu.pipeline.v2.credentials.local

import dev.rubentxu.pipeline.v2.credentials.local.LocalSecretStore.SecretStoreTamperException
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.credentials.Certificate
import dev.rubentxu.pipeline.v2.domain.credentials.Credential
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialScope
import dev.rubentxu.pipeline.v2.domain.credentials.LinkedSecretRef
import dev.rubentxu.pipeline.v2.domain.credentials.SecretFile
import dev.rubentxu.pipeline.v2.domain.credentials.SecretText
import dev.rubentxu.pipeline.v2.domain.credentials.SshPrivateKey
import dev.rubentxu.pipeline.v2.domain.credentials.UsernameColonPassword
import dev.rubentxu.pipeline.v2.domain.credentials.UsernamePassword
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Canonical part serialization of a credential into the local store format (ADR-0049 D2).
 *
 * Pure and state-free: it reads no file, takes no lock and touches no cached
 * value. The credential kind ids are duplicated here rather than widened in
 * [LocalSecretStore] so that both sides of the format stay explicit and the
 * shared values cannot drift apart silently.
 */
internal object CredentialSerializer {

    private const val KIND_SECRET_TEXT: Short = 1
    private const val KIND_USERNAME_PASSWORD: Short = 2
    private const val KIND_SSH_PRIVATE_KEY: Short = 3
    private const val KIND_SECRET_FILE: Short = 4
    private const val KIND_CERTIFICATE: Short = 5
    private const val KIND_ZIP: Short = 6
    private const val KIND_USERNAME_COLON_PASSWORD: Short = 7

    // === Credential serialization ===

// === Credential serialization ===

// Canonical part format (D2):
//   normal part:  [nameLen:1][name][len:4 BE][bytes]
//   linked-ref:   [nameLen:1][name][0xFFFFFFFF:4][refIdLen:4][refId]
//   absent opt:   [nameLen:1][name][0x00000000:4]
fun writeLinkedRefMarker(out: ByteArrayOutputStream) {
    val marker = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(0xFFFFFFFF.toInt())
    out.write(marker.array())
}

fun writeAbsentMarker(out: ByteArrayOutputStream) {
    val marker = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(0x00000000.toInt())
    out.write(marker.array())
}

/**
 * Writes one length-prefixed part: a name, then a payload, each prefixed by
 * its length.
 *
 * Layout, pinned by `CredentialPartLayoutTest`:
 * ```
 * 1 byte   name length
 * n bytes  name, UTF-8
 * 4 bytes  payload length, big-endian
 * m bytes  payload
 * ```
 *
 * This is the on-disk format of the store (magic "PKCR", versions V1 and
 * V2), so the byte sequence is a persistence contract. The name length is
 * `String.length`, which counts UTF-16 code units rather than UTF-8 bytes.
 * Every part name in the current vocabulary is ASCII, where the two agree;
 * a non-ASCII name would desynchronise a reader. That is recorded as a
 * pinned property rather than silently corrected, because changing the
 * prefix would change bytes that already exist on disk.
 */
fun writePart(out: ByteArrayOutputStream, name: String, payload: ByteArray) {
    out.write(name.length)
    out.write(name.toByteArray(Charsets.UTF_8))
    out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(payload.size).array())
    out.write(payload, 0, payload.size)
}

/**
 * Writes a part whose payload is a link to another credential rather than
 * inline bytes.
 *
 * The part name is still written, because the reader always consumes it
 * before reading the marker. The payload is a marker followed by either
 * the referenced id or nothing at all, so it deliberately has no length
 * prefix: [writePart] does not apply here.
 */
fun writeRefPart(
    out: ByteArrayOutputStream,
    name: String,
    ref: dev.rubentxu.pipeline.v2.domain.credentials.LinkedSecretRef?,
) {
    out.write(name.length)
    out.write(name.toByteArray(Charsets.UTF_8))
    if (ref != null) {
        writeLinkedRefMarker(out)
        val refIdBytes = ref.credentialsId.value.toByteArray(Charsets.UTF_8)
        out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(refIdBytes.size).array())
        out.write(refIdBytes, 0, refIdBytes.size)
    } else {
        writeAbsentMarker(out)
    }
}

fun serializeCredential(credential: Credential): ByteArray {
    val out = ByteArrayOutputStream()
    when (credential) {
        is SecretText -> {
            out.write(1) // part count
            writePart(out, "value", credential.bytes)
        }
        is dev.rubentxu.pipeline.v2.domain.credentials.UsernamePassword -> {
            out.write(2) // 2 parts
            writePart(out, "username", credential.username.toByteArray(Charsets.UTF_8))
            writePart(out, "password", credential.password)
        }
        is dev.rubentxu.pipeline.v2.domain.credentials.SshPrivateKey -> {
            out.write(3) // 3 parts: username, privateKey, passphrase
            writePart(out, "username", credential.username.toByteArray(Charsets.UTF_8))
            writePart(out, "privateKey", credential.privateKey)
            // passphrase: linked-ref or absent
            writeRefPart(out, "passphrase", credential.passphraseRef)
        }
        is dev.rubentxu.pipeline.v2.domain.credentials.SecretFile -> {
            out.write(2) // 2 parts: originalName, content
            writePart(
                out,
                "originalName",
                (credential.originalName ?: "").toByteArray(Charsets.UTF_8),
            )
            writePart(out, "content", credential.bytes)
        }
        is dev.rubentxu.pipeline.v2.domain.credentials.Certificate -> {
            out.write(3) // 3 parts: keystore, alias, password
            writePart(out, "keystore", credential.keystore)
            writePart(out, "alias", (credential.alias ?: "").toByteArray(Charsets.UTF_8))
            // password: linked-ref or absent
            writeRefPart(out, "password", credential.passwordRef)
        }
        is dev.rubentxu.pipeline.v2.domain.credentials.Zip -> {
            out.write(1 + credential.entries.size) // 1 + n parts
            // _entryCount metadata: normal
            val metaPartName = "_entryCount"
            out.write(metaPartName.length)
            out.write(metaPartName.toByteArray(Charsets.UTF_8))
            val countBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(credential.entries.size)
            out.write(countBuf.array())
            // metadata content = 1 (as int)
            val metaContentBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(1)
            out.write(metaContentBuf.array())
            for ((entryName, entryBytes) in credential.entries) {
                val entryPartName = entryName
                val entryNameBytes = entryPartName.toByteArray(Charsets.UTF_8)
                if (entryNameBytes.size !in 1..255) {
                    throw SecretStoreTamperException(
                        "Zip entry name must be 1-255 UTF-8 bytes, got ${entryNameBytes.size} bytes for entry '$entryPartName'")
                }
                out.write(entryNameBytes.size)
                out.write(entryNameBytes)
                val entryLenBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(entryBytes.size)
                out.write(entryLenBuf.array())
                out.write(entryBytes, 0, entryBytes.size)
            }
        }
        is dev.rubentxu.pipeline.v2.domain.credentials.UsernameColonPassword -> {
            out.write(2) // 2 parts
            writePart(out, "username", credential.user.toByteArray(Charsets.UTF_8))
            writePart(out, "password", credential.pass)
        }
    }
    return out.toByteArray()
}

// Reads a 4-byte big-endian marker from the buffer.
// Returns: 0xFFFFFFFF = linked-ref, 0x00000000 = absent, other = inline byte count
fun readPartMarker(buf: ByteBuffer): Int {
    return buf.int
}

fun deserializeCredential(bytes: ByteArray, kindId: Short, id: CredentialsId): Credential {
    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
    return when (kindId) {
        KIND_SECRET_TEXT -> {
            val partCount = buf.get().toInt()
            // name
            val nameLen = buf.get().toInt()
            buf.position(buf.position() + nameLen)
            // value (inline)
            val valueLen = readPartMarker(buf)
            val valueBytes = ByteArray(valueLen); buf.get(valueBytes)
            SecretText(id, CredentialScope.GLOBAL, valueBytes)
        }
        KIND_USERNAME_PASSWORD -> {
            val partCount = buf.get().toInt()
            // username: normal
            val userNameLen = buf.get().toInt()
            buf.position(buf.position() + userNameLen) // skip name
            val userLen = readPartMarker(buf)
            val userBytes = ByteArray(userLen); buf.get(userBytes)
            val username = String(userBytes, Charsets.UTF_8)
            // password: normal
            val passNameLen = buf.get().toInt()
            buf.position(buf.position() + passNameLen) // skip name
            val passLen = readPartMarker(buf)
            val passBytes = ByteArray(passLen); buf.get(passBytes)
            dev.rubentxu.pipeline.v2.domain.credentials.UsernamePassword(id, CredentialScope.GLOBAL, username, passBytes)
        }
        KIND_SSH_PRIVATE_KEY -> {
            val partCount = buf.get().toInt()
            // username: normal
            val userNameLen = buf.get().toInt()
            buf.position(buf.position() + userNameLen)
            val userLen = readPartMarker(buf)
            val userBytes = ByteArray(userLen); buf.get(userBytes)
            val username = String(userBytes, Charsets.UTF_8)
            // privateKey: normal
            val keyNameLen = buf.get().toInt()
            buf.position(buf.position() + keyNameLen)
            val keyLen = readPartMarker(buf)
            val keyBytes = ByteArray(keyLen); buf.get(keyBytes)
            // passphrase: linked-ref or absent
            val passphraseNameLen = buf.get().toInt()
            buf.position(buf.position() + passphraseNameLen) // skip name
            val passphraseMarker = readPartMarker(buf)
            val passphraseRef = when (passphraseMarker) {
                0xFFFFFFFF.toInt() -> {
                    val refIdLen = buf.int
                    val refIdBytes = ByteArray(refIdLen); buf.get(refIdBytes)
                    LinkedSecretRef(CredentialsId.from(String(refIdBytes, Charsets.UTF_8)))
                }
                0x00000000.toInt() -> null
                else -> throw SecretStoreTamperException("SshPrivateKey passphrase must be linked-ref or absent")
            }
            dev.rubentxu.pipeline.v2.domain.credentials.SshPrivateKey(id, CredentialScope.GLOBAL, username, keyBytes, passphraseRef)
        }
        KIND_SECRET_FILE -> {
            val partCount = buf.get().toInt()
            // originalName: normal
            val origNameLen = buf.get().toInt()
            buf.position(buf.position() + origNameLen)
            val origNameLen2 = readPartMarker(buf)
            val origNameBytes = ByteArray(origNameLen2); buf.get(origNameBytes)
            val originalName = String(origNameBytes, Charsets.UTF_8)
            // content: normal
            val contentNameLen = buf.get().toInt()
            buf.position(buf.position() + contentNameLen)
            val contentLen = readPartMarker(buf)
            val contentBytes = ByteArray(contentLen); buf.get(contentBytes)
            dev.rubentxu.pipeline.v2.domain.credentials.SecretFile(id, CredentialScope.GLOBAL, contentBytes, originalName.ifEmpty { null })
        }
        KIND_CERTIFICATE -> {
            val partCount = buf.get().toInt()
            // keystore: normal
            val ksNameLen = buf.get().toInt()
            buf.position(buf.position() + ksNameLen)
            val ksLen = readPartMarker(buf)
            val ksBytes = ByteArray(ksLen); buf.get(ksBytes)
            // alias: normal
            val aliasNameLen = buf.get().toInt()
            buf.position(buf.position() + aliasNameLen)
            val aliasLen = readPartMarker(buf)
            val aliasBytes = ByteArray(aliasLen); buf.get(aliasBytes)
            val alias = String(aliasBytes, Charsets.UTF_8)
            // password: linked-ref or absent
            val pwNameLen = buf.get().toInt()
            buf.position(buf.position() + pwNameLen)
            val pwMarker = readPartMarker(buf)
            val passwordRef = when (pwMarker) {
                0xFFFFFFFF.toInt() -> {
                    val refIdLen = buf.int
                    val refIdBytes = ByteArray(refIdLen); buf.get(refIdBytes)
                    LinkedSecretRef(CredentialsId.from(String(refIdBytes, Charsets.UTF_8)))
                }
                0x00000000.toInt() -> null
                else -> throw SecretStoreTamperException("Certificate password must be linked-ref or absent")
            }
            dev.rubentxu.pipeline.v2.domain.credentials.Certificate(id, CredentialScope.GLOBAL, ksBytes, passwordRef, alias.ifEmpty { null })
        }
        KIND_ZIP -> {
            val partCount = buf.get().toInt()
            // _entryCount metadata: normal
            val metaNameLen = buf.get().toInt()
            buf.position(buf.position() + metaNameLen)
            buf.int // skip count
            readPartMarker(buf) // consume metadata content int (1) — serialized as 4-byte int
            val entries = mutableMapOf<String, ByteArray>()
            repeat(partCount - 1) {
                val entryNameLen = buf.get().toInt()
                val entryNameBytes = ByteArray(entryNameLen); buf.get(entryNameBytes)
                val entryName = String(entryNameBytes, Charsets.UTF_8)
                val entryLen = readPartMarker(buf)
                val entryBytes = ByteArray(entryLen); buf.get(entryBytes)
                entries[entryName] = entryBytes
            }
            dev.rubentxu.pipeline.v2.domain.credentials.Zip(id, CredentialScope.GLOBAL, entries)
        }
        KIND_USERNAME_COLON_PASSWORD -> {
            val partCount = buf.get().toInt()
            // username: normal
            val userNameLen = buf.get().toInt()
            buf.position(buf.position() + userNameLen)
            val userLen = readPartMarker(buf)
            val userBytes = ByteArray(userLen); buf.get(userBytes)
            val username = String(userBytes, Charsets.UTF_8)
            // password: normal
            val passNameLen = buf.get().toInt()
            buf.position(buf.position() + passNameLen)
            val passLen = readPartMarker(buf)
            val passBytes = ByteArray(passLen); buf.get(passBytes)
            dev.rubentxu.pipeline.v2.domain.credentials.UsernameColonPassword(id, CredentialScope.GLOBAL, username, passBytes)
        }
        else -> throw SecretStoreTamperException("Unknown credential kind: $kindId")
    }
}
}
