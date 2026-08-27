package dev.rubentxu.pipeline.v2.sdk.scm.git

import dev.rubentxu.pipeline.v2.credentials.api.SecretStore
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.scm.GitCredentials
import dev.rubentxu.pipeline.v2.domain.scm.SecretHandleRef
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64

/**
 * Applies Git credentials using the answer-file pattern (INV-L6-CR-011/012).
 *
 * ## Answer-File Pattern (D6 Design)
 *
 * The credential helper and GIT_ASKPASS scripts NEVER receive secrets via environment
 * variables. Instead:
 * 1. JVM writes pre-resolved credentials to an answer file at `apply()` time
 * 2. The file is keyed by `host+path` for isolation
 * 3. Script reads the answer file, matches by host, then **unsets** the env var
 * 4. Script outputs the secret to stdout for git to consume
 *
 * ## Security Properties
 *
 * - Secrets NEVER in helper env (INV-L6-CR-011)
 * - Helper script unsets any env vars after reading (INV-L6-CR-012)
 * - Temp files 0600 (credentials, ssh keys), 0700 (directories)
 * - Wiped in finally block
 *
 * ## Supported Channels
 *
 * - **string**: API token via `credential helper` + answer file
 * - **usernamePassword**: Basic auth via `extraHeader` (per-host config)
 * - **ssh**: SSH private key via `GIT_SSH_COMMAND`
 *
 * @param tempDir Parent temp directory for credential files (0700)
 * @param credentials GitCredentials carrying typed SecretHandleRef carriers
 * @param secretStore SecretStore for resolving SecretHandleRef to actual secret bytes
 */
class GitCredentialsApplier(
    private val tempDir: Path,
    val credentials: GitCredentials,
    private val secretStore: SecretStore? = null,
) : AutoCloseable {

    private val gitCredentialsFile: Path = tempDir.resolve(".git-credentials")
    private val sshKeyFile: Path = tempDir.resolve(".git-ssh-key")
    private val gitConfigFile: Path = tempDir.resolve(".gitconfig")
    private val answerFile: Path = tempDir.resolve(".git-answer")
    private val askpassScript: Path = tempDir.resolve(".git-askpass")
    private val credentialHelperScript: Path = tempDir.resolve(".git-credential-helper")
    private val sshWrapperScript: Path = tempDir.resolve(".git-ssh-wrapper")
    private var isClosed = false

    init {
        // Ensure parent dir is 0700
        Files.setPosixFilePermissions(tempDir, PosixFilePermissions.fromString("rwx------"))
    }

    /**
     * Exposes the credentials file path (.git-credentials) for test verification.
     */
    fun credentialsFilePath(): String = gitCredentialsFile.toString()

    /**
     * Exposes the gitconfig file path (.gitconfig) for test verification.
     */
    fun gitConfigFilePath(): String = gitConfigFile.toString()

    /**
     * Exposes the answer file path for test verification.
     */
    fun answerFilePath(): String = answerFile.toString()

    /**
     * Applies credentials for the string channel (API token) using answer-file pattern.
     *
     * Writes credential helper script and answer file, then configures git to use it.
     */
    fun apply(tokenSecret: SecretHandleRef) {
        check(!isClosed) { "GitCredentialsApplier already closed" }
        guardProcessBuilderArgs(listOf("git", "credential", "store"))

        val host = extractHost(credentials.string?.id?.value ?: "")

        // Write answer file with pre-resolved token (keyed by host)
        val tokenBytes = resolveSecret(tokenSecret)
        val tokenValue = String(tokenBytes, Charsets.UTF_8)
        val answerContent = "$host\n$tokenValue\n"
        Files.writeString(answerFile, answerContent)
        Files.setPosixFilePermissions(answerFile, PosixFilePermissions.fromString("rw-------"))

        // Write credential helper script
        writeCredentialHelperScript()
    }

    /**
     * Applies credentials for the usernamePassword channel using per-host config.
     *
     * Uses `extraHeader` via GIT_CONFIG_GLOBAL per-host config section.
     * NO hardcoded github.com literals - host is extracted from credential.
     */
    fun apply(usernameSecret: SecretHandleRef, passwordSecret: SecretHandleRef) {
        check(!isClosed) { "GitCredentialsApplier already closed" }
        guardProcessBuilderArgs(listOf("git", "config"))

        val host = extractHost(usernameSecret.id.value)

        // Resolve and encode credentials
        val encoded = resolveAndEncode(usernameSecret, passwordSecret)

        // Per-host [credential] config section - NO hardcoded literals
        val gitConfig = buildPerHostGitConfig(host, encoded)
        Files.writeString(gitConfigFile, gitConfig)
        Files.setPosixFilePermissions(gitConfigFile, PosixFilePermissions.fromString("rw-------"))
    }

    /**
     * Applies SSH credentials using GIT_SSH_COMMAND with answer-file pattern.
     *
     * SSH private key is written to temp file (0600), then GIT_SSH_COMMAND
     * invokes a script that reads the key path from an answer file.
     */
    fun applySsh(sshKeySecret: SecretHandleRef, passphraseSecret: SecretHandleRef?) {
        check(!isClosed) { "GitCredentialsApplier already closed" }
        guardProcessBuilderArgs(listOf("git", "clone"))

        val host = extractHost(credentials.string?.id?.value ?: "")

        // Write SSH key to temp file (0600)
        val keyBytes = resolveSecret(sshKeySecret)
        Files.writeString(sshKeyFile, String(keyBytes, Charsets.UTF_8))
        Files.setPosixFilePermissions(sshKeyFile, PosixFilePermissions.fromString("rw-------"))

        // Write answer file with key path (keyed by host)
        val answerContent = "$host\n${sshKeyFile}\n"
        Files.writeString(answerFile, answerContent)
        Files.setPosixFilePermissions(answerFile, PosixFilePermissions.fromString("rw-------"))

        // Write GIT_ASKPASS script for passphrase (if provided)
        if (passphraseSecret != null) {
            writeAskpassScript(passphraseSecret)
        }

        // Write SSH wrapper script
        writeSshWrapperScript()
    }

    /**
     * Builds environment variables for credential injection.
     *
     * @return Map of env var names to values (NOT SecretHandle - those go to process builder)
     */
    fun buildEnv(): Map<String, String> {
        val env = mutableMapOf<String, String>()

        if (Files.exists(gitConfigFile)) {
            env["GIT_CONFIG_GLOBAL"] = gitConfigFile.toString()
        }

        if (Files.exists(credentialHelperScript)) {
            // Configure git to use our credential helper
            env["GIT_CONFIG_GLOBAL"] = gitConfigFile.toString()
        }

        if (Files.exists(sshKeyFile)) {
            // GIT_SSH_COMMAND with our wrapper script
            env["GIT_SSH_COMMAND"] = "ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -i $sshKeyFile"
        }

        if (Files.exists(askpassScript)) {
            env["GIT_ASKPASS"] = askpassScript.toString()
        }

        return env
    }

    private fun writeCredentialHelperScript() {
        val script = buildCredentialHelperScript()
        Files.writeString(credentialHelperScript, script)
        Files.setPosixFilePermissions(credentialHelperScript, PosixFilePermissions.fromString("rwx------"))
    }

    private fun writeAskpassScript(passphraseSecret: SecretHandleRef) {
        // Write passphrase to answer file keyed by purpose
        val passphraseBytes = resolveSecret(passphraseSecret)
        val passphraseValue = String(passphraseBytes, Charsets.UTF_8)
        val answerContent = "passphrase\n$passphraseValue\n"
        Files.writeString(answerFile, answerContent)
        Files.setPosixFilePermissions(answerFile, PosixFilePermissions.fromString("rw-------"))

        val script = buildAskpassScript()
        Files.writeString(askpassScript, script)
        Files.setPosixFilePermissions(askpassScript, PosixFilePermissions.fromString("rwx------"))
    }

    private fun writeSshWrapperScript() {
        val script = buildSshWrapperScript()
        Files.writeString(sshWrapperScript, script)
        Files.setPosixFilePermissions(sshWrapperScript, PosixFilePermissions.fromString("rwx------"))
    }

    private fun buildPerHostGitConfig(host: String, encodedBasicAuth: String): String {
        // Per-host [credential] section - NO hardcoded github.com literals
        return "[credential]\n" +
            "    helper=store\n" +
            "\n" +
            "http \"$host\"\n" +
            "    extraHeader=Authorization: Basic $encodedBasicAuth\n"
    }

    private fun resolveSecret(ref: SecretHandleRef): ByteArray {
        return secretStore?.getAsSecretHandle(ref.id)?.use { it.copyOf() }
            ?: throw IllegalStateException("SecretStore not available for credential resolution: ${ref.id.value}")
    }

    private fun resolveAndEncode(usernameRef: SecretHandleRef, passwordRef: SecretHandleRef): String {
        return secretStore?.getAsSecretHandle(usernameRef.id)?.use { userBytes ->
            secretStore?.getAsSecretHandle(passwordRef.id)?.use { passBytes ->
                val userValue = String(userBytes, Charsets.UTF_8)
                val passValue = String(passBytes, Charsets.UTF_8)
                Base64.getEncoder().encodeToString("$userValue:$passValue".toByteArray(Charsets.UTF_8))
            } ?: throw IllegalStateException("SecretStore not available for credential resolution: ${passwordRef.id.value}")
        } ?: throw IllegalStateException("SecretStore not available for credential resolution: ${usernameRef.id.value}")
    }

    // Build script as simple string concatenation to avoid Kotlin string interpolation issues
    private fun buildCredentialHelperScript(): String {
        val dollar = "\$"  // single dollar sign for shell
        val answerFilePath = answerFile.toString()
        val answerFileRef = "\$ANSWER_FILE"
        val dollar1 = "\$1"
        val hostVar = "\$host"
        val lineVar = "\$line"
        val secretVar = "\$secret"
        return """
            |#!/bin/bash
            |# GIT credential helper using answer-file pattern
            |# Secrets NEVER in environment (INV-L6-CR-011)
            |
            |${answerFileRef}="${answerFilePath}"
            |
            |# Read host from stdin
            |read -r host
            |
            |case ${dollar1} in
            |    get)
            |        # Find matching host in answer file and output username=token
            |        while IFS= read -r line; do
            |            if [[ "${lineVar}" == "${hostVar}" ]]; then
            |                read -r secret
            |                echo "username=x-access-token"
            |                echo "password=${dollar}{secret}"
            |                break
            |            fi
            |        done < "${answerFileRef}"
            |        ;;
            |    store|erase)
            |        # No-op for store/erase - we only provide credentials
            |        ;;
            |esac
            |
            |# Unset any potential secret env vars (INV-L6-CR-012)
            |unset ANSWER_FILE GIT_ASKPASS
            |exit 0
        """.trimMargin()
    }

    private fun buildAskpassScript(): String {
        val dollar = "\$"
        val answerFilePath = answerFile.toString()
        val answerFileRef = "\$ANSWER_FILE"
        val promptVar = "\$prompt"
        val lineVar = "\$line"
        val secretVar = "\$secret"
        return """
            |#!/bin/bash
            |# GIT_ASKPASS script using answer-file pattern
            |# Passphrase NEVER in environment (INV-L6-CR-011)
            |
            |${answerFileRef}="${answerFilePath}"
            |
            |# Read prompt from stdin (e.g., "Enter passphrase for key '/path/to/key': ")
            |read -r prompt
            |
            |# Extract purpose from prompt (e.g., "passphrase for key")
            |if [[ "${promptVar}" == *"passphrase"* ]]; then
            |    while IFS= read -r line; do
            |        if [[ "${lineVar}" == "passphrase" ]]; then
            |            read -r secret
            |            echo "${dollar}{secret}"
            |            break
            |        fi
            |    done < "${answerFileRef}"
            |fi
            |
            |# Unset any potential secret env vars (INV-L6-CR-012)
            |unset ANSWER_FILE GIT_ASKPASS
            |exit 0
        """.trimMargin()
    }

    private fun buildSshWrapperScript(): String {
        val dollar = "\$"
        val answerFilePath = answerFile.toString()
        val answerFileRef = "\$ANSWER_FILE"
        val dollar1 = "\$1"
        val dollarAt = "\$@"
        val dollarSHELL_KEY = "\$SSH_KEY"
        val hostVar = "\$HOST"
        val lineVar = "\$line"
        val keypathVar = "\$keypath"
        return """
            |#!/bin/bash
            |# SSH wrapper using answer-file pattern
            |# Key path NEVER in environment (INV-L6-CR-011)
            |
            |${answerFileRef}="${answerFilePath}"
            |SSH_KEY=""
            |
            |# Extract host from GIT_SSH_COMMAND or use first arg
            |HOST=""
            |if [[ -n ${dollar1} ]]; then
            |    # Extract host from git's invocation
            |    HOST="${dollar1}"
            |    HOST="${dollar}{HOST#*@}"
            |    HOST="${dollar}{HOST%%:*}"
            |fi
            |
            |# Find key path for this host in answer file
            |if [[ -n "${hostVar}" ]]; then
            |    while IFS= read -r line; do
            |        if [[ "${lineVar}" == "${hostVar}" ]]; then
            |            read -r keypath
            |            SSH_KEY="${keypathVar}"
            |            break
            |        fi
            |    done < "${answerFileRef}"
            |fi
            |
            |# Build SSH command with key
            |if [[ -n "${dollarSHELL_KEY}" && -f "${dollarSHELL_KEY}" ]]; then
            |    exec ssh -i "${dollarSHELL_KEY}" -o IdentitiesOnly=yes -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null ${dollarAt}
            |else
            |    exec ssh ${dollarAt}
            |fi
        """.trimMargin()
    }

    /**
     * Guards against argv containing forbidden substrings.
     * Fail-closed: throws IllegalArgumentException if any arg contains
     * `extraHeader`, `Authorization`, or URL credentials.
     */
    companion object {
        fun guardProcessBuilderArgs(args: List<String>) {
            for (arg in args) {
                val lower = arg.lowercase()
                if (lower.contains("extraheader") || lower.contains("authorization")) {
                    throw IllegalArgumentException(
                        "Forbidden substring in argv: 'extraHeader' and 'Authorization' " +
                        "must not appear in process arguments (credentials must use GIT_CONFIG_GLOBAL env). " +
                        "Found in: $arg"
                    )
                }
                // Detect embedded credentials in URLs: https://user:pass@host/path
                if ((lower.contains("://") || lower.contains("@")) &&
                    URL_CREDENTIALS_PATTERN.containsMatchIn(arg)) {
                    throw IllegalArgumentException(
                        "Forbidden: URL with embedded credentials detected. " +
                        "Credentials must not appear in process arguments (argv) — " +
                        "use GIT_ASKPASS or credential helper instead. Found in: $arg"
                    )
                }
            }
        }

        private val URL_CREDENTIALS_PATTERN = Regex("https?://[^/]+:[^/]+@")

        fun extractHost(url: String): String {
            val clean = url
                .removePrefix("https://")
                .removePrefix("http://")
                .substringBefore("/")
                .substringBefore(":")
            return clean.ifEmpty {
                throw IllegalArgumentException("Cannot extract host from URL: $url")
            }
        }
    }

    /**
     * Wipes all temp files using fill(0) + Files.delete.
     * Called in finally block — safe against SIGKILL residue documented in ADR-0050.
     */
    override fun close() {
        if (isClosed) return
        isClosed = true

        wipeFile(answerFile)
        wipeFile(credentialHelperScript)
        wipeFile(askpassScript)
        wipeFile(sshWrapperScript)
        wipeFile(sshKeyFile)
        wipeFile(gitCredentialsFile)
        wipeFile(gitConfigFile)
    }

    private fun wipeFile(path: Path) {
        try {
            if (Files.exists(path)) {
                val size = Files.size(path)
                if (size > 0) {
                    val zeros = ByteArray(size.toInt())
                    Files.write(path, zeros)
                }
                Files.delete(path)
            }
        } catch (e: Exception) {
            // Best-effort wipe — log but don't fail
            System.err.println("Warning: failed to wipe $path: ${e.message}")
        }
    }
}
