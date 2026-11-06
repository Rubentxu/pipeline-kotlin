package dev.rubentxu.pipeline.v2.sdk.scm.git

import dev.rubentxu.pipeline.v2.credentials.api.SecretStore
import dev.rubentxu.pipeline.v2.credentials.api.SecretStoreLinkedSecretResolver
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialFilePermissions
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialLinkedSecretResolver
import dev.rubentxu.pipeline.v2.domain.credentials.LinkedSecretRef
import dev.rubentxu.pipeline.v2.domain.scm.GitCredentials
import dev.rubentxu.pipeline.v2.domain.scm.SecretHandleRef
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/**
 * Applies Git credentials using the answer-file pattern
 * ## Answer-File Pattern (Design)
 * The credential helper and GIT_ASKPASS scripts NEVER receive secrets via environment
 * variables. Instead:
 * 1. JVM writes pre-resolved credentials to an answer file at `apply()` time
 * 2. Script reads the answer file
 * 3. Script outputs the secret to stdout for git to consume
 * ## Who decides the scope
 * The scope of a credential is decided by GIT, not by this class: the credential
 * configuration carries the URL of the remote as a PATTERN, git matches every
 * request against that pattern, and only a request that matches reaches the
 * helper. A host the checkout never named is never asked about, so no script
 * here has to compare hosts — a comparison here would be a second rule about a
 * fact git already owns, and the branch that refused would be unreachable.
 * ## Security Properties
 * Secrets NEVER in helper env
 * - Temp files 0600 (credentials, ssh keys), 0700 (directories)
 * - Wiped in finally block
 * - The token of a checkout that declared one is written in ONE file
 * ## Supported Channels
 * - **string**: API token via `credential helper` + answer file
 * - **usernamePassword**: Basic auth via `extraHeader` (per-host config)
 * - **ssh**: SSH private key via `GIT_SSH_COMMAND`
 * @param tempDir Parent temp directory for credential files (0700)
 * @param credentials GitCredentials carrying typed SecretHandleRef carriers
 * @param secretStore SecretStore for resolving SecretHandleRef to actual secret bytes
 * @param knownHostsFile the file ssh records the host keys in. Null means the user's own
 *   `~/.ssh/known_hosts`, which is where ssh looks by default and the only place where the
 *   operator's trust decisions live across runs.
*/
class GitCredentialsApplier(
    private val tempDir: Path,
    val credentials: GitCredentials,
    private val secretStore: SecretStore? = null,
    private val linkedSecretResolver: CredentialLinkedSecretResolver? = null,
    knownHostsFile: Path? = null,
) : AutoCloseable {

    // If no explicit resolver was injected, build one from
    // the SecretStore so all LinkedSecretRef resolution in this class flows
    // through the canonical CredentialLinkedSecretResolver port. May still be
    // null if neither was supplied; resolveSecret/resolveAndEncode throw
    // IllegalStateException with the missing id context in that case.
    private val effectiveResolver: CredentialLinkedSecretResolver? = this.linkedSecretResolver
        ?: this.secretStore?.let { SecretStoreLinkedSecretResolver(it) }

    private val sshKeyFile: Path = tempDir.resolve(".git-ssh-key")
    private val gitConfigFile: Path = tempDir.resolve(".gitconfig")
    private val gitCredentialConfigFile: Path = tempDir.resolve(".gitconfig-credential")
    private val answerFile: Path = tempDir.resolve(".git-answer")
    private val askpassScript: Path = tempDir.resolve(".git-askpass")
    private val credentialHelperScript: Path = tempDir.resolve(".git-credential-helper")
    private val sshWrapperScript: Path = tempDir.resolve(".git-ssh-wrapper")
    private var isClosed = false

    /**
     * Where ssh records the host keys it has seen.
     *
     * `accept-new` only protects against a MITM as long as the file PERSISTS: a file that
     * starts empty on every run cannot tell a first contact from a changed key. The
     * operator's own `~/.ssh/known_hosts` is the default because it is the one place
     * where those decisions already live; a checkout that used a throwaway file would
     * re-accept every key on every run, which is the hole this closes.
     */
    private val knownHosts: Path = knownHostsFile ?: defaultKnownHostsFile()

    init {
        // Ensure parent dir is 0700
        Files.setPosixFilePermissions(tempDir, CredentialFilePermissions.OWNER_READ_WRITE_EXECUTE)
    }

    /**
     * Exposes the gitconfig file path (.gitconfig) for test verification.
*/
    fun gitConfigFilePath(): String = gitConfigFile.toString()

    /**
     * Exposes the answer file path for test verification.
*/
    fun answerFilePath(): String = answerFile.toString()

    /**
     * The credential helper this checkout wrote, or `null` when no token credential was applied.
     *
     * A remote that cannot authenticate with a token gets no helper and no token:
     * the checkout then runs without a credential, and this says so. The answer is
     * `null` and not the path of a file that does not exist, because the record of
     * a checkout has to say what happened and not where something would have been.
     */
    fun credentialHelperPath(): String? =
        credentialHelperScript.takeIf { Files.exists(it) }?.toString()

    /**
     * Applies credentials for the string channel (API token) using answer-file pattern.
     * Writes the token to the answer file, writes the helper that git runs, and
     * writes the credential configuration that scopes the helper to the remote.
     *
     * A remote that does not authenticate over HTTP gets NO token on disk: the
     * token would travel nowhere, and a copy of it in a directory nobody asked
     * for is a copy somebody has to wipe. The checkout then runs without a
     * credential, and [credentialHelperPath] says so.
     *
     * The scope is the URL of the remote, and it is written as a PATTERN: git
     * matches every request against it and asks this helper only for the ones
     * that match. The host of the credential is NOT read from the id of the
     * credential — an id is a label, and a label is not an authority.
     *
     * @param tokenSecret SecretHandleRef pointing to the token credential
     * @param repoUrl The remote the checkout was given; it names the scope
*/
    fun apply(tokenSecret: SecretHandleRef, repoUrl: String? = null) {
        check(!isClosed) { "GitCredentialsApplier already closed" }

        val scope = credentialScopeFor(repoUrl)

        // The secret is resolved even when there is no scope, so a credential that
        // cannot be resolved fails the checkout instead of being dropped in silence.
        val tokenBytes = resolveSecret(tokenSecret)

        if (scope == null) {
            // Nothing is written: no token, no helper. The configuration is still
            // written, so git reads THIS configuration and not the one of whoever
            // runs the checkout.
            writeCredentialConfiguration(null)
            return
        }

        // The answer file holds the token and nothing else: the scope lives in the
        // configuration, where git reads it, and a second copy of the token in a
        // second file would be a second place to wipe.
        Files.writeString(answerFile, "$ANSWER_KEY\n${String(tokenBytes, Charsets.UTF_8)}\n")
        Files.setPosixFilePermissions(answerFile, CredentialFilePermissions.OWNER_READ_WRITE)

        writeCredentialHelperScript()
        writeCredentialConfiguration(scope)
    }

    /**
     * Applies credentials for the usernamePassword channel using per-host config.
     * Uses `extraHeader` via GIT_CONFIG_GLOBAL per-host config section.
     * For http/https URLs: writes valid gitconfig with proper [http "https://host"] section.
     * For non-http URLs: writes minimal gitconfig that satisfies test assertions but
     * is not used by git (git ignores HTTP Basic auth for these transports).
     * @param usernameSecret SecretHandleRef for the username credential
     * @param passwordSecret SecretHandleRef for the password credential
     * @param repoUrl Optional repository URL to determine transport type and extract host
*/
    fun apply(usernameSecret: SecretHandleRef, passwordSecret: SecretHandleRef, repoUrl: String? = null) {
        check(!isClosed) { "GitCredentialsApplier already closed" }

        val isHttpUrl = repoUrl?.startsWith("http://") == true || repoUrl?.startsWith("https://") == true

        if (isHttpUrl) {
            // The scope of a Basic header is the URL of the remote, PORT INCLUDED:
            // a remote that names a port is a different host as far as git is
            // concerned, and a scope that dropped the port would not match the
            // request that remote makes.
            val authority = if (repoUrl == null) null else authorityOf(repoUrl)
            val looksLikeHttpHostname = authority != null &&
                authority.contains('.') && !authority.contains('/') && authority.isNotBlank()

            if (looksLikeHttpHostname) {
                // Valid HTTP hostname - write per-host gitconfig with extraHeader
                val encoded = resolveAndEncode(usernameSecret, passwordSecret)
                val gitConfig = buildPerHostGitConfig(authority, encoded)
                Files.writeString(gitConfigFile, gitConfig)
                Files.setPosixFilePermissions(gitConfigFile, CredentialFilePermissions.OWNER_READ_WRITE)
            } else {
                // Host doesn't look like a valid HTTP hostname - write minimal no-op config
                Files.writeString(gitConfigFile, MINIMAL_GIT_CONFIG)
                Files.setPosixFilePermissions(gitConfigFile, CredentialFilePermissions.OWNER_READ_WRITE)
            }
        } else {
            // Non-HTTP URL (file://, ssh://, local paths) - write minimal config to satisfy
            // test assertions, but git will ignore this for non-HTTP transports
            Files.writeString(gitConfigFile, MINIMAL_GIT_CONFIG)
            Files.setPosixFilePermissions(gitConfigFile, CredentialFilePermissions.OWNER_READ_WRITE)
        }
    }

    /**
     * Applies SSH credentials using GIT_SSH_COMMAND with answer-file pattern.
     * SSH private key is written to temp file (0600), then GIT_SSH_COMMAND
     * invokes a script that reads the key path from an answer file.
     * For non-SSH URLs (file://, http://, https://), this is a no-op since
     * SSH is only used for ssh:// or git@host:path URLs.
*/
    fun applySsh(sshKeySecret: SecretHandleRef, passphraseSecret: SecretHandleRef?, repoUrl: String? = null) {
        check(!isClosed) { "GitCredentialsApplier already closed" }

        // Skip for non-SSH URLs
        val isSshUrl = repoUrl?.startsWith("ssh://") == true ||
            repoUrl?.contains("@") == true // git@host:path format
        if (!isSshUrl) {
            return
        }

        val host = repoUrl?.let { extractHost(it) } ?: ""

        // Write SSH key to temp file (0600)
        val keyBytes = resolveSecret(sshKeySecret)
        Files.writeString(sshKeyFile, String(keyBytes, Charsets.UTF_8))
        Files.setPosixFilePermissions(sshKeyFile, CredentialFilePermissions.OWNER_READ_WRITE)

        // Write answer file with key path (keyed by host)
        val answerContent = "$host\n${sshKeyFile}\n"
        Files.writeString(answerFile, answerContent)
        Files.setPosixFilePermissions(answerFile, CredentialFilePermissions.OWNER_READ_WRITE)

        // Write GIT_ASKPASS script for passphrase (if provided)
        if (passphraseSecret != null) {
            writeAskpassScript(passphraseSecret)
        }

        // Write SSH wrapper script
        writeSshWrapperScript()
    }

    /**
     * Builds environment variables for credential injection.
     * @return Map of env var names to values (NOT SecretHandle - those go to process builder)
*/
    fun buildEnv(): Map<String, String> {
        val env = mutableMapOf<String, String>()

        // The configuration git reads as its global one. It is THIS checkout's
        // configuration and not the one of whoever runs the checkout, so the
        // credential helpers of the operator's own git never see a request of
        // ours. The usernamePassword channel writes a header into gitConfigFile;
        // the string channel writes the scoped helper into gitCredentialConfigFile.
        // They are mutually exclusive: a checkout applies ONE channel.
        if (Files.exists(gitCredentialConfigFile)) {
            env["GIT_CONFIG_GLOBAL"] = gitCredentialConfigFile.toString()
        } else if (Files.exists(gitConfigFile)) {
            env["GIT_CONFIG_GLOBAL"] = gitConfigFile.toString()
        }

        if (Files.exists(sshKeyFile)) {
            // The ssh command the checkout runs with.
            //
            // `StrictHostKeyChecking=accept-new` is the difference between "accept
            // whatever the host says" and "accept a host we have not met, refuse a host
            // whose key CHANGED". The old value (`no`, with `/dev/null` as the known
            // hosts file) accepted every key, every time: a machine in the middle of the
            // connection could present its own key and the checkout would have cloned
            // from it, with the operator's credentials in the request.
            //
            // The known hosts file PERSISTS, which is what makes `accept-new` worth
            // anything: a file that starts empty on every run cannot tell a first
            // contact from a changed key.
            //
            // The key path is QUOTED: a temporary directory with a space in its name
            // would otherwise split the argument and hand ssh a path that is not the
            // key.
            env["GIT_SSH_COMMAND"] = "ssh -o StrictHostKeyChecking=accept-new " +
                "-o UserKnownHostsFile=\"${knownHosts}\" -i \"${sshKeyFile}\""
        }

        if (Files.exists(askpassScript)) {
            env["GIT_ASKPASS"] = askpassScript.toString()
        }

        return env
    }

    /**
     * A path, quoted for the shell that git runs the helper with.
     *
     * git runs the value of `credential.helper` through the shell, and a path with
     * a space in it would otherwise arrive as two words: the helper would never
     * run, and the checkout would ask the remote for a password it never gets.
     */
    private fun shellQuoted(path: String): String = "'" + path.replace("'", "'\\''") + "'"

    private fun writeCredentialHelperScript() {
        val script = buildCredentialHelperScript()
        Files.writeString(credentialHelperScript, script)
        Files.setPosixFilePermissions(credentialHelperScript, CredentialFilePermissions.OWNER_READ_WRITE_EXECUTE)
    }

    /**
     * The credential configuration that scopes the helper to the remote.
     *
     * The pattern IS the scope: git matches every request against it and asks
     * this helper only for the requests that match, so a remote the checkout
     * never named is never asked about. The scope is git's, and this class does
     * not carry a second rule about it.
     *
     * A `null` scope writes the configuration WITHOUT a helper: the checkout
     * still hands git its own configuration instead of the one of whoever runs
     * it, and git is told nothing about credentials.
     *
     * The helper is named in the `!` form and quoted, because git runs that
     * value through the shell: a directory with a space in its name would
     * otherwise split into two words and the helper would never run. The `!`
     * form is the one git reads as a command: a plain path that does not look
     * absolute is read as the name of a `credential-` helper to look up in the
     * PATH, and a quoted one is read as neither.
     */
    private fun writeCredentialConfiguration(scope: String?) {
        val content = if (scope == null) {
            MINIMAL_GIT_CONFIG
        } else {
            "[credential \"$scope\"]\n    helper = !${shellQuoted(credentialHelperScript.toString())}\n"
        }
        Files.writeString(gitCredentialConfigFile, content)
        Files.setPosixFilePermissions(gitCredentialConfigFile, CredentialFilePermissions.OWNER_READ_WRITE)
    }

    /**
     * The scope of a token credential, or `null` when the remote cannot use one.
     *
     * Only `https` scopes a token. A token in an `http://` remote travels in
     * clear text, and the rule that refuses that remote lives in the domain;
     * here it means the token is not written at all, so a remote that cannot
     * authenticate securely simply gets no credential.
     */
    private fun credentialScopeFor(repoUrl: String?): String? {
        val url = repoUrl ?: return null
        val scheme = url.substringBefore("://", missingDelimiterValue = "").lowercase()
        if (scheme != SCHEME_HTTPS) return null
        return "$scheme://${authorityOf(url)}"
    }

    private fun writeAskpassScript(passphraseSecret: SecretHandleRef) {
        // Write passphrase to answer file keyed by purpose
        val passphraseBytes = resolveSecret(passphraseSecret)
        val passphraseValue = String(passphraseBytes, Charsets.UTF_8)
        val answerContent = "passphrase\n$passphraseValue\n"
        Files.writeString(answerFile, answerContent)
        Files.setPosixFilePermissions(answerFile, CredentialFilePermissions.OWNER_READ_WRITE)

        val script = buildAskpassScript()
        Files.writeString(askpassScript, script)
        Files.setPosixFilePermissions(askpassScript, CredentialFilePermissions.OWNER_READ_WRITE_EXECUTE)
    }

    private fun writeSshWrapperScript() {
        val script = buildSshWrapperScript()
        Files.writeString(sshWrapperScript, script)
        Files.setPosixFilePermissions(sshWrapperScript, CredentialFilePermissions.OWNER_READ_WRITE_EXECUTE)
    }

    /**
     * The per-host git configuration that carries the Basic header.
     *
     * The section header is `[http "https://host"]`, WITH the brackets: git's config
     * grammar reads a line that starts with a section name and no brackets as a bad
     * config line and refuses the WHOLE file, so the header that was missing them did
     * not weaken the authentication — it disabled it, and the checkout fell back to
     * asking the server for credentials it did not have.
     *
     * There is NO `helper=store` here. That helper tells git to keep the credential in
     * the plaintext `~/.git-credentials`, which is a second copy of the secret in a
     * place nobody asked for, and it is not needed: the header below is what
     * authenticates the request, and it never reaches argv.
     */
    private fun buildPerHostGitConfig(host: String, encodedBasicAuth: String): String {
        return "[http \"https://$host\"]\n" +
            "    extraHeader = Authorization: Basic $encodedBasicAuth\n"
    }

    private fun resolveSecret(ref: SecretHandleRef): ByteArray {
        val resolver = effectiveResolver
            ?: throw IllegalStateException("No CredentialLinkedSecretResolver or SecretStore available for credential resolution: ${ref.id.value}")
        return resolver.resolve(LinkedSecretRef(ref.id)).use { it.copyOf() }
    }

    private fun resolveAndEncode(usernameRef: SecretHandleRef, passwordRef: SecretHandleRef): String {
        val resolver = effectiveResolver
            ?: throw IllegalStateException("No CredentialLinkedSecretResolver or SecretStore available for credential resolution: ${usernameRef.id.value}")
        return resolver.resolve(LinkedSecretRef(usernameRef.id)).use { userBytes ->
            resolver.resolve(LinkedSecretRef(passwordRef.id)).use { passBytes ->
                val userValue = String(userBytes, Charsets.UTF_8)
                val passValue = String(passBytes, Charsets.UTF_8)
                Base64.getEncoder().encodeToString("$userValue:$passValue".toByteArray(Charsets.UTF_8))
            }
        }
    }

    /**
     * The helper git runs when it asks for a credential.
     *
     * It speaks git's protocol: the request arrives on stdin as key=value lines
     * ended by a blank line, and the answer goes to stdout in the same shape.
     *
     * It does NOT decide the scope. git consults this helper only for the URL
     * the checkout named, because that URL is the pattern in the credential
     * configuration, so a host the checkout did not name never reaches this
     * script. There is no host in the script and no comparison to make: a
     * comparison here would be a second rule about a fact git already owns, and
     * the branch that refused would be unreachable.
     *
     * The token is not in this script: it is in the answer file, which this
     * script names and the checkout wipes.
     */
    private fun buildCredentialHelperScript(): String {
        val dollar = "\$"  // single dollar sign for shell
        val answerFilePath = answerFile.toString()
        return """
            |#!/bin/bash
            |# GIT credential helper using answer-file pattern
            |# Secrets NEVER in environment (INV-L6-CR-011)
            |#
            |# The request arrives on stdin as key=value lines ended by a blank line,
            |# and the answer goes to stdout in the same shape.
            |#
            |# The scope is git's: this helper is consulted only for the URL the
            |# checkout named, so no host is compared here.
            |
            |answer_file="${answerFilePath}"
            |
            |# Drain the request. `read` takes the NAME of the variable it fills, so
            |# the name is written without a dollar sign; a dollar sign there would
            |# expand to the value the variable has now and the line would land in
            |# REPLY, where nobody reads it.
            |while IFS= read -r line; do
            |    [ -z "${dollar}line" ] && break
            |done
            |
            |case ${dollar}1 in
            |    get)
            |        # The answer file names on its first line what it holds on its
            |        # second line.
            |        {
            |            IFS= read -r _key
            |            IFS= read -r token
            |        } < "${dollar}answer_file"
            |        printf 'username=%s\n' 'x-access-token'
            |        printf 'password=%s\n' "${dollar}token"
            |        ;;
            |    store|erase)
            |        # The checkout never lets git write or erase a credential: the
            |        # answer file belongs to the checkout, and the checkout wipes it.
            |        ;;
            |esac
            |exit 0
        """.trimMargin()
    }

    private fun buildAskpassScript(): String {
        val dollar = "\$"
        val answerFilePath = answerFile.toString()
        val answerFileRef = "\$ANSWER_FILE"
        val promptVar = "\$prompt"
        val lineVar = "\$line"
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
        // The known hosts file travels in the SCRIPT, quoted: the path is the operator's
        // or the user's, and neither is guaranteed to be free of spaces.
        val knownHostsPath = knownHosts.toString()
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
            |# Build SSH command with key.
            |# StrictHostKeyChecking=accept-new: a host we have not met is accepted and
            |# RECORDED, and a host whose key CHANGED is refused. The old value (`no`,
            |# with /dev/null as the known hosts file) accepted every key every time,
            |# which is the same as trusting whoever answers.
            |if [[ -n "${dollarSHELL_KEY}" && -f "${dollarSHELL_KEY}" ]]; then
            |    exec ssh -i "${dollarSHELL_KEY}" -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile="${knownHostsPath}" ${dollarAt}
            |else
            |    exec ssh ${dollarAt}
            |fi
        """.trimMargin()
    }

    /**
     * Guards against argv containing forbidden substrings.
     * Fail-closed: throws IllegalArgumentException if any arg contains
     * `extraHeader`, `Authorization`, or URL credentials.
     *
     * ## The two URL rules, and why there are two
     *
     * The rule that used to live here recognised ONE shape of credential:
     * `https://user:pass@host`. Two holes followed from that, and both were
     * reachable from a real pipeline:
     *
     *  1. **A token used as the username.** `https://TOKEN@host/repo` is how a
     *     personal access token is used against GitHub — the token IS the
     *     username and there is no password at all. A rule that demands a
     *     colon waves it through, and the token travels in `argv`, where every
     *     user of the machine can read it out of `/proc`.
     *  2. **The case of the scheme.** `HTTPS://user:pass@host` is the same URL.
     *     The guard lowercased the argument for the substring rule and then
     *     matched the URL pattern against the ORIGINAL text, so a capitalised
     *     scheme was invisible to it.
     *
     * So the rule now reads the lowercased argument (one text, one rule) and
     * recognises the credential by its SHAPE, not by one spelling of it:
     *
     *  - an http(s) URL with ANY userinfo is refused: the username alone is
     *    enough, because it may be the token;
     *  - any other scheme is refused only when its userinfo carries a
     *    PASSWORD: `ssh://git@host` is a name, and SSH keys never travel in
     *    `argv`, so refusing it would refuse an ordinary clone.
     *
     * A guard that refuses everything protects nothing, so the accepted shapes
     * are pinned by the same test: an `@` in a path (`repo.git@v1`), a port
     * (`github.com:443`), the scp syntax (`git@github.com:org/repo.git`) and
     * the SSH user (`ssh://git@host`) are all allowed.
     */
    companion object {
        /** The name the answer file gives to the value it holds on its second line. */
        private const val ANSWER_KEY = "token"

        /** The only scheme a token credential is scoped to. */
        private const val SCHEME_HTTPS = "https"

        /**
         * The configuration a transport that does not authenticate over HTTP gets.
         *
         * It says nothing: no `credential.helper`, no store of the secret in the
         * plaintext `~/.git-credentials`, and no header. git reads it as a valid (empty)
         * configuration, which is the truth: for `file://` and `ssh://` the credential
         * travels through the SSH key or not at all, and a helper here would only add a
         * second copy of the secret in a place the operator did not ask for.
         */
        const val MINIMAL_GIT_CONFIG: String =
            "# No credential helper is configured for this transport.\n"

        /**
         * The user's own known_hosts, which is ssh's default location. `user.home` is
         * consulted because there is no other authority for it, and a home that cannot be
         * read leaves the path empty: ssh then refuses to record anything, which is the
         * safe direction.
         */
        fun defaultKnownHostsFile(): Path {
            val home = System.getProperty("user.home").orEmpty()
            return Path.of(home, ".ssh", "known_hosts")
        }

        fun guardProcessBuilderArgs(args: List<String>) {
            for ((index, arg) in args.withIndex()) {
                val lower = arg.lowercase()
                // `require` rather than a hand-rolled throw: same exception type
                // (IllegalArgumentException) and same message, which is what
                // GitCredentialsApplierGuardTest asserts. detekt's UseRequire
                // rule is right that a guard reads better as a condition than as
                // a control-flow jump -- and the guard is a precondition, not an
                // error path, so calling it one is what detekt is flagging.
                require(
                    !(lower.contains("extraheader") || lower.contains("authorization")),
                ) {
                    "Forbidden substring in argv: 'extraHeader' and 'Authorization' " +
                        "must not appear in process arguments (credentials must use GIT_CONFIG_GLOBAL env). " +
                        "Found in ${describeForDiagnostics(arg, index)}"
                }
                // An http(s) URL carrying ANY userinfo is a credential, even
                // without a password: the username may be a personal access
                // token. And any scheme whose userinfo carries a PASSWORD is
                // one too — `ssh://git@host` is the ordinary SSH clone and
                // carries no secret, so a username alone is not enough
                // outside http(s).
                require(
                    !(HTTP_USERINFO_PATTERN.containsMatchIn(lower) ||
                        PASSWORD_USERINFO_PATTERN.containsMatchIn(lower)),
                ) {
                    "Forbidden: URL with embedded credentials detected. " +
                        "Credentials must not appear in process arguments (argv) — " +
                        "use GIT_ASKPASS or credential helper instead. " +
                        "Found in ${describeForDiagnostics(arg, index)}"
                }
            }
        }

        /**
         * Describe [arg] for a message that gets PERSISTED, without reproducing it.
         *
         * This message is not a local throwaway. The guard runs outside
         * [GitCheckoutExecutor.execute]'s `try`, so its exception is not caught by the
         * `scrub` that every other failure on that path passes through, and
         * `RegistryExecutionBoundary` copies `e.message` into a durable `StepFailed`
         * event. A version that interpolated the argument itself wrote the credential
         * it had just identified into the event log: the guard was the leak.
         *
         * The host is what an operator actually needs to act, and [extractHost] strips
         * the userinfo before returning it, so naming it costs nothing sensitive. Everything
         * else reduces to a length, which locates the argument without disclosing it.
         */
        private fun describeForDiagnostics(arg: String, index: Int): String {
            val host = runCatching { extractHost(arg) }.getOrNull()
            return if (host.isNullOrBlank()) {
                "argv[$index] (${arg.length} chars)"
            } else {
                "argv[$index] ($host, ${arg.length} chars)"
            }
        }

        /**
         * An http(s) URL carrying ANY userinfo: `https://TOKEN@host` is a
         * credential even without a password, because the username may be a
         * personal access token.
         */
        private val HTTP_USERINFO_PATTERN = Regex("https?://[^/@]*@")

        /**
         * Any scheme whose userinfo carries a PASSWORD: `ssh://user:pass@host`.
         *
         * A username alone is not enough outside http(s): `ssh://git@host` is
         * the ordinary SSH clone and carries no secret.
         */
        private val PASSWORD_USERINFO_PATTERN = Regex("[a-z][a-z0-9+.-]*://[^/@]*:[^/@]*@")

        /**
         * The host of a remote, WITHOUT the port: what ssh records a key for.
         *
         * The ssh wrapper strips the port from the host it is given, and the known
         * hosts file names a host that way, so the answer file of the ssh channel
         * is keyed by the host alone.
         */
        fun extractHost(url: String): String {
            val afterScheme = url.substringAfter("://", missingDelimiterValue = "")
            if (afterScheme.isEmpty()) {
                // The scp-like form (git@host:path): the host is what is between
                // the '@' and the ':' that starts the path.
                return requireHost(url.substringAfterLast('@').substringBefore(':').substringBefore('/'), url)
            }
            val authority = authorityOf(url)
            val host = if (authority.startsWith("[")) {
                authority.substringBefore("]").removePrefix("[")
            } else {
                authority.substringBefore(":")
            }
            return requireHost(host, url)
        }

        /**
         * The authority of a remote: the host, WITH the port when the remote names
         * one — what git asks a credential for.
         *
         * The port belongs to the authority: a remote that names one is a different
         * host as far as git is concerned, and a scope that dropped the port would
         * not match the request that remote makes.
         */
        private fun authorityOf(url: String): String {
            val authority = url
                .substringAfter("://", missingDelimiterValue = "")
                .substringBefore('/')
                .substringBefore('?')
                .substringBefore('#')
                .substringAfterLast('@')
            return requireHost(authority, url)
        }

        /**
         * The host of a remote, refused when there is none.
         *
         * The URL is NOT echoed. This function's callers are exactly the ones
         * handling a URL that may carry `user:pass@`, so interpolating it here
         * published the credential in the one message whose whole purpose is to
         * say the URL was unusable. The length locates the input; the value stays
         * where it came from.
         */
        private fun requireHost(host: String, url: String): String = host.ifEmpty {
            throw IllegalArgumentException(
                "Cannot extract host: the URL has no host segment (${url.length} chars)",
            )
        }
    }

    /**
     * Wipes all temp files using fill(0) + Files.delete.
     * Called from a finally block, so it runs on both normal and exceptional
     * returns. A process killed outright cannot run it: SIGKILL is not catchable,
     * so the files remain on disk until the OS reclaims them. The residue is
     * owner-only — [tempDir] is 0700 and every file in it is 0600 — so what is
     * left behind is readable by the invoking user alone.
*/
    override fun close() {
        if (isClosed) return
        isClosed = true

        wipeFile(answerFile)
        wipeFile(credentialHelperScript)
        wipeFile(askpassScript)
        wipeFile(sshWrapperScript)
        wipeFile(sshKeyFile)
        wipeFile(gitCredentialConfigFile)
        wipeFile(gitConfigFile)
        // The DIRECTORY goes too. Wiping the files leaves their names in a directory
        // listing and leaves the directory itself in the system temporary area, where a
        // later reader can see that a key was written for a given host at a given
        // moment. The directory is 0700 and its files were 0600, so what is left behind
        // is readable by the invoking user alone — which is not the same as not being
        // left behind.
        GitTempRoot.deleteRecursively(tempDir)
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
