package dev.rubentxu.pipeline.v2.domain.credentials

import dev.rubentxu.pipeline.v2.domain.SecretHandle
import java.nio.charset.StandardCharsets

/**
 * LF-0403 — Result of projecting a single [CredentialBindingSpec].
 *
 * Maps an env var name to a [SecretHandle]. The handle is the typed channel —
 * the actual materialization / String conversion happens at the ProcessBuilder
 * choke point ([SecretHandle.materialize]).
 *
 * @property bindings The set of env var entries this binding contributes.
 */
data class ProjectionResult(
    val bindings: Map<String, SecretHandle>,
)

/**
 * LF-0403 — Port that maps a typed [CredentialBindingSpec] + resolved
 * [Credential] into the env-var shape that
 * [dev.rubentxu.pipeline.v2.credentials.executor.WithCredentialsExecutor]
 * hands to the durable step.
 *
 * ## Why this exists
 *
 * The pre-M4 path inlined a `when (binding.kind)` switch inside the executor
 * and inside `PipelineRun.kt`, both of which had the same bug: SSH and
 * certificate bindings assigned the SAME masked file-path handle to
 * usernameVariable, passphraseVariable, AND keyFileVariable. The legacy code
 * could not express "two different handles for one credential kind" without
 * duplicating the dispatch logic.
 *
 * A projector centralises the per-kind rules in one place. Adding a new
 * binding kind means writing ONE new sealed subtype and ONE new projector
 * arm — never touching the executor again.
 */
fun interface CredentialProjector {
    fun project(
        spec: CredentialBindingSpec,
        credential: Credential,
        runId: String,
    ): ProjectionResult
}

/**
 * LF-0403 — Default implementation of [CredentialProjector].
 *
 * Per-kind rules:
 *
 *  - [StringBindingSpec]: returns `{ spec.variable -> SecretHandle.secret(credential.bytes) }`.
 *    NOTE: the upstream `provider.resolve(id)` returns the V2 envelope (with NUL
 *    bytes for length-prefixed small secrets), so for STRING credentials we
 *    route through `resolveToCredential(id)` and use `credential.bytes` (the
 *    plaintext body only — no envelope, no NUL).
 *  - [UsernamePasswordBindingSpec]:
 *    `{ usernameVariable -> usernameSecret, passwordVariable -> passwordSecret }`
 *    — TWO different handles, parsed from the null-separated envelope.
 *  - [UsernameColonPasswordBindingSpec]:
 *    `{ variable -> SecretHandle.secret(user + ":" + pass) }`
 *    — joined bytes, no envelope.
 *  - [SshUserPrivateKeyBindingSpec]: materializes to file and returns
 *    `{ keyFileVariable -> masked(path),
 *       passphraseVariable -> masked("") (if non-null),
 *       usernameVariable -> masked(username) (if non-null) }`
 *    — THREE different handles. Passphrase content resolution is deferred to a
 *    follow-up slice (see TODO below).
 *  - [FileBindingSpec]: `{ variable -> masked(materialized.path) }`.
 *  - [CertificateBindingSpec]: `{ keystoreVariable -> masked(path),
 *       aliasVariable -> masked(alias) (if non-null),
 *       passwordVariable -> masked("") (if non-null) }`. Password resolution
 *    is deferred to a follow-up slice (see TODO below).
 *  - [ZipBindingSpec]: `{ variable -> masked(extractedDir) }`.
 *
 * @param materialization The [CredentialMaterializationDomain] port used for
 *   file-based kinds (SSH/FILE/CERT/ZIP).
 */
class DefaultCredentialProjector(
    private val materialization: CredentialMaterializationDomain,
) : CredentialProjector {

    override fun project(
        spec: CredentialBindingSpec,
        credential: Credential,
        runId: String,
    ): ProjectionResult {
        val env = LinkedHashMap<String, SecretHandle>()

        when (spec) {
            is StringBindingSpec -> {
                // STRING: route through resolveToCredential → use raw bytes (no envelope).
                // This is the LF-0403 NUL-byte fix; the previous code forwarded the full
                // V2 envelope (which contains length-prefix NULs) directly into env.
                val text = credential as? SecretText
                    ?: throw IllegalStateException(
                        "STRING binding '${spec.credentialsId.value}' requires a SecretText credential; " +
                            "got ${credential::class.simpleName}",
                    )
                env[spec.variable] = SecretHandle.secret(text.bytes)
            }
            is UsernamePasswordBindingSpec -> {
                val up = credential as? UsernamePassword
                    ?: throw IllegalStateException(
                        "USERNAME_PASSWORD binding '${spec.credentialsId.value}' requires a UsernamePassword credential; " +
                            "got ${credential::class.simpleName}",
                    )
                env[spec.usernameVariable] = SecretHandle.secret(up.username.toByteArray(StandardCharsets.UTF_8))
                env[spec.passwordVariable] = SecretHandle.secret(up.password)
            }
            is SshUserPrivateKeyBindingSpec -> {
                val ssh = credential as? SshPrivateKey
                    ?: throw IllegalStateException(
                        "SSH_USER_PRIVATE_KEY binding '${spec.credentialsId.value}' requires an SshPrivateKey credential; " +
                            "got ${credential::class.simpleName}",
                    )
                val materialized = materialization.materialize(ssh)
                val keyPath = materialized.path
                    ?: throw IllegalStateException(
                        "SSH_USER_PRIVATE_KEY materialization must produce a path",
                    )
                env[spec.keyFileVariable] = SecretHandle.masked(keyPath.toString())
                spec.passphraseVariable?.let { varName ->
                    // TODO LF-0403 follow-up: resolve passphrase via LinkedSecretRef →
                    // ask the materializer to materialize the referenced SecretText
                    // credential into a temp file path. For Slice 1 the legacy bug
                    // carried over as a placeholder so the binding shape remains
                    // total; passphrase injection will be wired in a follow-up.
                    env[varName] = SecretHandle.masked("")
                }
                spec.usernameVariable?.let { varName ->
                    env[varName] = SecretHandle.masked(ssh.username)
                }
                materialized.close()
            }
            is FileBindingSpec -> {
                val file = credential as? SecretFile
                    ?: throw IllegalStateException(
                        "FILE binding '${spec.credentialsId.value}' requires a SecretFile credential; " +
                            "got ${credential::class.simpleName}",
                    )
                val materialized = materialization.materialize(file)
                val filePath = materialized.path
                    ?: throw IllegalStateException(
                        "FILE materialization must produce a path",
                    )
                env[spec.variable] = SecretHandle.masked(filePath.toString())
                materialized.close()
            }
            is CertificateBindingSpec -> {
                val cert = credential as? Certificate
                    ?: throw IllegalStateException(
                        "CERTIFICATE binding '${spec.credentialsId.value}' requires a Certificate credential; " +
                            "got ${credential::class.simpleName}",
                    )
                val materialized = materialization.materialize(cert)
                val keystorePath = materialized.path
                    ?: throw IllegalStateException(
                        "CERTIFICATE materialization must produce a path",
                    )
                env[spec.keystoreVariable] = SecretHandle.masked(keystorePath.toString())
                spec.aliasVariable?.let { varName ->
                    cert.alias?.let { alias -> env[varName] = SecretHandle.masked(alias) }
                }
                spec.passwordVariable?.let { varName ->
                    // TODO LF-0403 follow-up: resolve password via LinkedSecretRef →
                    // ask the materializer to materialize the referenced SecretText
                    // credential into a temp file path. Slice 1 keeps the variable
                    // present so the binding shape stays total.
                    env[varName] = SecretHandle.masked("")
                }
                materialized.close()
            }
            is ZipBindingSpec -> {
                val zip = credential as? Zip
                    ?: throw IllegalStateException(
                        "ZIP binding '${spec.credentialsId.value}' requires a Zip credential; " +
                            "got ${credential::class.simpleName}",
                    )
                val materialized = materialization.materialize(zip)
                val zipPath = materialized.path
                    ?: throw IllegalStateException(
                        "ZIP materialization must produce a path",
                    )
                env[spec.variable] = SecretHandle.masked(zipPath.toString())
                materialized.close()
            }
            is UsernameColonPasswordBindingSpec -> {
                val ucp = credential as? UsernameColonPassword
                    ?: throw IllegalStateException(
                        "USERNAME_COLON_PASSWORD binding '${spec.credentialsId.value}' requires a UsernameColonPassword credential; " +
                            "got ${credential::class.simpleName}",
                    )
                val joined = ByteArray(ucp.user.length + 1 + ucp.pass.size)
                ucp.user.toByteArray(StandardCharsets.UTF_8).copyInto(joined, 0)
                joined[ucp.user.length] = 0x3A // ':'
                ucp.pass.copyInto(joined, ucp.user.length + 1)
                env[spec.variable] = SecretHandle.secret(joined)
            }
        }

        return ProjectionResult(env)
    }
}
