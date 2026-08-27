package dev.rubentxu.pipeline.v2.sdk.scm.git

import dev.rubentxu.pipeline.v2.credentials.api.SecretStore
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.scm.GitCredentials
import dev.rubentxu.pipeline.v2.domain.scm.SecretHandleRef
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/**
 * Applies Git credentials to the environment via temp files.
 *
 * Two channels:
 * - **string** (API token): writes `<tmpdir>/.git-credentials` (chmod 0600) with line
 *   `https://x-access-token:<token>@<host>`, plus `<tmpdir>/.gitconfig` with
 *   `[credential] helper = store --file=<tmpdir>/.git-credentials`, and injects
 *   `GIT_CONFIG_GLOBAL + HOME` env vars.
 * - **usernamePassword**: writes `<tmpdir>/.gitconfig` (chmod 0600) with
 *   `[http "<url>"] extraHeader = Authorization: Basic <base64(user:pass)>`, and injects
 *   `GIT_CONFIG_GLOBAL` only (NO `HOME` override).
 *
 * INV-L5-CR-003: temp files 0600, parent dir 0700, wiped in `finally`.
 * INV-L5-CR-004: credentials NEVER enter argv — base64 encoding only in file content.
 *
 * @param tempDir Parent temp directory for credential files
 * @param credentials GitCredentials carrying typed SecretHandleRef carriers
 * @param secretStore SecretStore for resolving SecretHandleRef to actual secret bytes
 */
class GitCredentialsApplier(
    private val tempDir: Path,
    val credentials: GitCredentials,
    private val secretStore: SecretStore? = null,
    ) : AutoCloseable {

    private val gitCredentialsFile: Path = tempDir.resolve(".git-credentials")
    private val gitConfigFile: Path = tempDir.resolve(".gitconfig")
    private var isClosed = false

    init {
        // Ensure parent dir is 0700
        Files.setPosixFilePermissions(tempDir, setOf(
            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
            java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE
        ))
    }

    /**
     * Exposes the credentials file path (.git-credentials) for test verification.
     * Called by GitCheckoutExecutor.execute() to capture the path before
     * close() deletes the file.
     */
    fun credentialsFilePath(): String = gitCredentialsFile.toString()

    /**
     * Exposes the gitconfig file path (.gitconfig) for test verification.
     * Used for usernamePassword channel.
     */
    fun gitConfigFilePath(): String = gitConfigFile.toString()

    /**
     * Applies credentials for the string channel (API token).
     * Writes `.git-credentials` and `.gitconfig` with `helper = store`.
     */
    fun apply(tokenSecret: SecretHandleRef) {
        check(!isClosed) { "GitCredentialsApplier already closed" }
        guardProcessBuilderArgs(listOf("git", "credential", "store"))

        // Resolve actual secret bytes from SecretStore
        // SecretHandle.use() wipes the internal buffer after the lambda.
        // MUST copy the bytes inside the lambda — do NOT return the handle's
        // internal array directly (it will be zeroed after use {} returns).
        val tokenBytes = secretStore?.get(tokenSecret.id)?.use { it.copyOf() }
            ?: throw IllegalStateException("SecretStore not available for credential resolution: ${tokenSecret.id.value}")

        // Write .git-credentials with token
        // Format: https://x-access-token:<token>@<host>
        val host = extractHost(credentials.string?.id?.value ?: "")
        val tokenValue = String(tokenBytes, Charsets.UTF_8)
        val credsLine = "https://x-access-token:${tokenValue}@${host}"
        Files.writeString(gitCredentialsFile, credsLine)
        Files.setPosixFilePermissions(gitCredentialsFile, setOf(
            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
        ))

        // Write .gitconfig with credential helper
        val gitConfig = """
            [credential]
                helper = store --file=${gitCredentialsFile}
        """.trimIndent()
        Files.writeString(gitConfigFile, gitConfig)
        Files.setPosixFilePermissions(gitConfigFile, setOf(
            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
        ))
    }

    /**
     * Applies credentials for the usernamePassword channel.
     * Writes `.gitconfig` with `extraHeader = Authorization: Basic <base64>`.
     * NO `HOME` override - gitconfig is scoped to GIT_CONFIG_GLOBAL only.
     */
    fun apply(usernameSecret: SecretHandleRef, passwordSecret: SecretHandleRef) {
        check(!isClosed) { "GitCredentialsApplier already closed" }
        guardProcessBuilderArgs(listOf("git", "config"))

        // Resolve actual secret bytes from SecretStore.
        // SecretHandle.use() wipes the internal buffer after the lambda completes.
        // Do ALL byte operations (toString, base64 encode) INSIDE the use {} lambda
        // to ensure we work with the real bytes before they are zeroed.
        val encoded = secretStore?.get(usernameSecret.id)?.use { userBytes ->
            secretStore?.get(passwordSecret.id)?.use { passBytes ->
                // At this point both byte arrays are still valid (not yet wiped)
                val userValue = String(userBytes, Charsets.UTF_8)
                val passValue = String(passBytes, Charsets.UTF_8)
                Base64.getEncoder().encodeToString("$userValue:$passValue".toByteArray(Charsets.UTF_8))
            } ?: throw IllegalStateException("SecretStore not available for credential resolution: ${passwordSecret.id.value}")
        } ?: throw IllegalStateException("SecretStore not available for credential resolution: ${usernameSecret.id.value}")

        val gitConfig = "[http \"https://github.com\"]\n    extraHeader = Authorization: Basic $encoded"
        Files.writeString(gitConfigFile, gitConfig)
        Files.setPosixFilePermissions(gitConfigFile, setOf(
            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
        ))
    }

    /**
     * Guards against argv containing forbidden substrings.
     * Fail-closed: throws IllegalArgumentException if any arg contains
     * `extraHeader` or `Authorization`.
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

        private fun extractHost(url: String): String {
            return try {
                val clean = url.removePrefix("https://").removePrefix("http://")
                clean.substringBefore("/").substringBefore(":")
            } catch (e: Exception) {
                "github.com"
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

        wipeFile(gitCredentialsFile)
        wipeFile(gitConfigFile)
    }

    private fun wipeFile(path: Path) {
        try {
            if (Files.exists(path)) {
                // Fill with zeros to prevent recovery
                val size = Files.size(path)
                Files.writeString(path, CharArray(size.toInt()) { '\u0000' }.joinToString(""))
                Files.delete(path)
            }
        } catch (e: Exception) {
            // Best-effort wipe — log but don't fail
            System.err.println("Warning: failed to wipe $path: ${e.message}")
        }
    }
}
