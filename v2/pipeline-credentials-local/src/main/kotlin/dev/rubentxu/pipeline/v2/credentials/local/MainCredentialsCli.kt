package dev.rubentxu.pipeline.v2.credentials.local

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Main credentials CLI — subcommand dispatcher for credential store operations.
 *
 * ## Exit Codes
 *
 * - 0: Success
 * - 1: Usage error
 * - 2: Missing store
 * - 3: Wrong passphrase
 * - 4: Tamper detected
 *
 * ## Subcommands
 *
 * - `add <id>` — prompts for secret via Console.readPassword(), stores with passphrase
 * - `list` — lists all credential IDs (never values)
 * - `remove <id>` — removes a credential
 * - `rotate <id>` — re-encrypts with new secret (prompts via Console.readPassword())
 */
object MainCredentialsCli {

    private val STORE_FILE: Path = Path.of(System.getProperty("user.home"), ".pipeline", "credentials.bin")

    @JvmStatic
    fun main(args: Array<String>): Int {
        return when (args.firstOrNull()) {
            "add" -> add(args.drop(1))
            "list" -> list(args.drop(1))
            "remove" -> remove(args.drop(1))
            "rotate" -> rotate(args.drop(1))
            else -> {
                println("Usage: pipeline credentials {add|list|remove|rotate} [args]")
                1
            }
        }
    }

    private fun add(args: List<String>): Int {
        val id = args.firstOrNull() ?: run {
            println("Usage: pipeline credentials add <id>")
            return 1
        }
        print("Enter secret value: ")
        val secret = System.console()?.readPassword() ?: run {
            println("Error: no TTY available")
            return 1
        }
        return try {
            val passphrase = PassphraseResolver.resolve()
            val store = LocalSecretStore(STORE_FILE, passphrase)
            store.put(CredentialsId.from(id), String(secret).toByteArray())
            println("Credential '$id' stored successfully.")
            0
        } catch (e: LocalSecretStore.CredentialsStorePassphraseUnavailableException) {
            println("Error: ${e.message}")
            2
        } catch (e: LocalSecretStore.CredentialsStoreEmptySecretException) {
            println("Error: empty secret not allowed")
            1
        }
    }

    private fun list(args: List<String>): Int {
        return try {
            val passphrase = PassphraseResolver.resolve()
            val store = LocalSecretStore(STORE_FILE, passphrase)
            val ids = store.list()
            if (ids.isEmpty()) {
                println("No credentials stored.")
            } else {
                println("Stored credentials:")
                for (id in ids) {
                    println("  ${id.value}")
                }
            }
            0
        } catch (e: LocalSecretStore.CredentialsStorePassphraseUnavailableException) {
            println("Error: ${e.message}")
            2
        }
    }

    private fun remove(args: List<String>): Int {
        val id = args.firstOrNull() ?: run {
            println("Usage: pipeline credentials remove <id>")
            return 1
        }
        return try {
            val passphrase = PassphraseResolver.resolve()
            val store = LocalSecretStore(STORE_FILE, passphrase)
            store.remove(CredentialsId.from(id))
            println("Credential '$id' removed.")
            0
        } catch (e: LocalSecretStore.CredentialsStorePassphraseUnavailableException) {
            println("Error: ${e.message}")
            2
        } catch (e: LocalSecretStore.SecretStoreTamperException) {
            println("Error: store tampered: ${e.message}")
            4
        }
    }

    private fun rotate(args: List<String>): Int {
        val id = args.firstOrNull() ?: run {
            println("Usage: pipeline credentials rotate <id>")
            return 1
        }
        print("Enter new secret value: ")
        val secret = System.console()?.readPassword() ?: run {
            println("Error: no TTY available")
            return 1
        }
        return try {
            val passphrase = PassphraseResolver.resolve()
            val store = LocalSecretStore(STORE_FILE, passphrase)
            store.rotateBytes(CredentialsId.from(id), String(secret).toByteArray())
            println("Credential '$id' rotated successfully.")
            0
        } catch (e: LocalSecretStore.CredentialsStorePassphraseUnavailableException) {
            println("Error: ${e.message}")
            2
        } catch (e: LocalSecretStore.SecretStoreTamperException) {
            println("Error: store tampered: ${e.message}")
            4
        } catch (e: LocalSecretStore.CredentialsStoreEmptySecretException) {
            println("Error: empty secret not allowed")
            1
        }
    }
}
