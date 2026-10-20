package dev.rubentxu.pipeline.v2.credentials.local

import dev.rubentxu.pipeline.v2.domain.credentials.Credential
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Collects one credential of each supported kind by asking for it at an
 * interactive terminal.
 * This object decides only where the values come from. Whether a value is
 * acceptable is [CredentialValues]' decision, shared with the caller that reads
 * the same kinds from a redirected stream: two callers asking the same question
 * must get the same answer, or a store seeded one way would differ from a store
 * seeded the other.
 * Every function is pure in the sense that matters here: each returns a fresh
 * value with no file handle, lock or cached state. They live apart from
 * [MainCredentialsCli] so the dispatch table there stays about dispatching.
*/
internal object CredentialPrompts {

    fun read(kind: MainCredentialsCli.CredentialKind): Credential = when (kind) {
        MainCredentialsCli.CredentialKind.SECRET_TEXT -> {
            print("Enter secret value: ")
            CredentialValues.secretText(consoleSecret())
        }

        MainCredentialsCli.CredentialKind.USERNAME_PASSWORD -> {
            print("Enter username: ")
            val username = consoleLine()
            print("Enter password: ")
            CredentialValues.usernamePassword(username, consoleSecret())
        }

        MainCredentialsCli.CredentialKind.USERNAME_COLON_PASSWORD -> {
            print("Enter username: ")
            val username = consoleLine()
            print("Enter password: ")
            CredentialValues.usernameColonPassword(username, consoleSecret())
        }

        MainCredentialsCli.CredentialKind.SSH_PRIVATE_KEY -> {
            print("Enter SSH username: ")
            val username = consoleLine()
            println("Enter SSH private key (paste PEM content, end with a line containing '.':")
            val lines = mutableListOf<String>()
            while (true) {
                val line = consoleLine()
                if (line.trim() == ".") break
                lines.add(line)
            }
            CredentialValues.sshPrivateKey(username, lines.joinToString("\n").toByteArray())
        }

        MainCredentialsCli.CredentialKind.SECRET_FILE -> {
            print("Enter file path: ")
            val file = Paths.get(consoleLine())
            CredentialValues.secretFile(Files.readAllBytes(file), file.fileName.toString())
        }

        MainCredentialsCli.CredentialKind.CERTIFICATE -> {
            print("Enter keystore file path (PKCS#12): ")
            val keystore = Files.readAllBytes(Paths.get(consoleLine()))
            print("Enter key alias (leave empty for default): ")
            CredentialValues.certificate(keystore, consoleLine())
        }

        MainCredentialsCli.CredentialKind.ZIP -> {
            print("Enter ZIP file path: ")
            CredentialValues.zip(Files.readAllBytes(Paths.get(consoleLine())))
        }
    }

    private fun consoleLine(): String = readLine() ?: error("no TTY available")

    private fun consoleSecret(): ByteArray =
        String(System.console()?.readPassword() ?: error("no TTY available"))
            .toByteArray()
}
