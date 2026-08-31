package dev.rubentxu.pipeline.v2.credentials.executor

import dev.rubentxu.pipeline.v2.credentials.multipart.CredentialMaterializer
import dev.rubentxu.pipeline.v2.credentials.multipart.MaterializationKind
import dev.rubentxu.pipeline.v2.domain.BoundPurpose
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.credentials.api.SecretStore
import dev.rubentxu.pipeline.v2.credentials.api.SecretStoreException
import java.time.Instant
import java.util.UUID

/**
 * H0 Slice 1: CredentialSession implementation.
 *
 * Opens and resolves credentials for the given bindings, returning:
 * - credentialEnv: Map<String, SecretHandle> for shOptions injection
 * - boundaries: ResolutionBoundaries tracking success/failure per binding
 * - cleanup handles: materializer + active handles for finally cleanup
 *
 * ## Event emission note
 *
 * This session does NOT emit CredentialBound events - that is PipelineRun's
 * responsibility to preserve event ordering (CredentialBound before StepStarted).
 * The session only resolves credentials and provides the environment map.
 *
 * @param bindings The credentials bindings to resolve
 * @param secretStore The secret store to resolve from
 * @param eventSink Event sink for CredentialBound events (emitted before resolution)
 * @param runId The run ID for event context
 * @param clock Clock for event timestamps
 */
class CredentialSessionImpl(
    private val bindings: List<CredentialsBinding>,
    private val secretStore: SecretStore,
    private val eventSink: dev.rubentxu.pipeline.v2.events.EventSink,
    private val runId: String,
    private val clock: dev.rubentxu.pipeline.v2.domain.durable.Clock
) : CredentialSession {

    private val _credentialEnv = mutableMapOf<String, SecretHandle>()
    private val _activeHandles = mutableListOf<SecretHandle>()
    private val _materializer = CredentialMaterializer(secretStore)

    private val _resolved = mutableListOf<CredentialResolution.Resolved>()
    private val _failed = mutableListOf<CredentialResolution.Failed>()

    private var _opened = false
    private var _closed = false

    init {
        // Resolve credentials eagerly on construction
        resolve()
    }

    /**
     * Resolves all credentials and builds the environment map.
     *
     * Emits CredentialBound events BEFORE resolution per INV-L10-CR-001.
     * If any resolution fails, the failure is recorded but resolution continues
     * for remaining bindings (fail-fast on first error is NOT the behavior).
     */
    private fun resolve() {
        if (_opened) return
        _opened = true

        for (binding in bindings) {
            val purpose = kindToPurpose(binding.kind)
            val materializationKind = kindToMaterializationKind(binding.kind)

            // Emit CredentialBound BEFORE resolution (INV-L10-CR-001)
            eventSink.append(
                dev.rubentxu.pipeline.v2.events.CredentialBound(
                    eventId = UUID.randomUUID().toString(),
                    runId = runId,
                    sequence = 0L,
                    occurredAt = clock.now(),
                    credentialsId = binding.credentialsId,
                    purpose = purpose,
                )
            )

            try {
                if (materializationKind != null) {
                    // File-based kinds: materialize to temp path and inject path
                    val credential = secretStore.get(binding.credentialsId)
                    val materialized = _materializer.materialize(credential, materializationKind)
                    val path = materialized.path

                    if (path != null) {
                        // Inject path as masked handle
                        injectPathBinding(binding, path, purpose)
                    }
                } else {
                    // Non-file kinds: use SecretHandle directly
                    val handle = secretStore.getAsSecretHandle(binding.credentialsId)
                    _activeHandles.add(handle)
                    injectHandleBinding(binding, handle)
                }

                _resolved.add(
                    CredentialResolution.Resolved(
                        credentialsId = binding.credentialsId,
                        purpose = purpose,
                        envVar = binding.variable ?: binding.usernameVariable ?: "",
                        handle = _credentialEnv[binding.variable ?: binding.usernameVariable ?: ""]!!
                    )
                )
            } catch (e: SecretStoreException) {
                // Credential resolution failed - fail-fast (preserve original semantics)
                throw CredentialResolutionException(
                    "Credential resolution failed for ${binding.credentialsId.value}: ${e.message}",
                    binding.credentialsId,
                    e
                )
            } catch (e: Throwable) {
                // Materialization or other failures - fail-fast (preserve original semantics)
                throw CredentialResolutionException(
                    "Credential resolution failed for ${binding.credentialsId.value}: ${e.message}",
                    binding.credentialsId,
                    e
                )
            }
        }
    }

    private fun injectPathBinding(binding: CredentialsBinding, path: java.nio.file.Path, purpose: BoundPurpose) {
        when (binding.kind) {
            CredentialsBinding.Kind.SSH_USER_PRIVATE_KEY -> {
                binding.keyFileVariable?.let { varName ->
                    _credentialEnv[varName] = SecretHandle.masked(path.toString())
                }
                binding.passphraseVariable?.let { varName ->
                    _credentialEnv[varName] = SecretHandle.masked(path.toString())
                }
                binding.usernameVariable?.let { varName ->
                    _credentialEnv[varName] = SecretHandle.masked(path.toString())
                }
            }
            CredentialsBinding.Kind.FILE -> {
                binding.variable?.let { varName ->
                    _credentialEnv[varName] = SecretHandle.masked(path.toString())
                }
            }
            CredentialsBinding.Kind.CERTIFICATE -> {
                binding.keystoreVariable?.let { varName ->
                    _credentialEnv[varName] = SecretHandle.masked(path.toString())
                }
                binding.aliasVariable?.let { varName ->
                    _credentialEnv[varName] = SecretHandle.masked(path.toString())
                }
                binding.passwordVariable?.let { varName ->
                    _credentialEnv[varName] = SecretHandle.masked(path.toString())
                }
            }
            CredentialsBinding.Kind.ZIP -> {
                binding.variable?.let { varName ->
                    _credentialEnv[varName] = SecretHandle.masked(path.toString())
                }
            }
            else -> { /* handled above */ }
        }
    }

    private fun injectHandleBinding(binding: CredentialsBinding, handle: SecretHandle) {
        when (binding.kind) {
            CredentialsBinding.Kind.STRING -> {
                binding.variable?.let { varName ->
                    _credentialEnv[varName] = handle
                }
            }
            CredentialsBinding.Kind.USERNAME_PASSWORD -> {
                binding.usernameVariable?.let { varName ->
                    _credentialEnv[varName] = handle
                }
                binding.passwordVariable?.let { varName ->
                    _credentialEnv[varName] = handle
                }
            }
            CredentialsBinding.Kind.USERNAME_COLON_PASSWORD -> {
                binding.variable?.let { varName ->
                    _credentialEnv[varName] = handle
                }
            }
            else -> { /* file-based handled above */ }
        }
    }

    override fun credentialEnv(): Map<String, SecretHandle> = _credentialEnv.toMap()

    override fun activeHandles(): List<SecretHandle> = _activeHandles.toList()

    override fun materializer(): CredentialMaterializer = _materializer

    override fun boundaries(): ResolutionBoundaries = ResolutionBoundaries(
        resolved = _resolved.toList(),
        failed = _failed.toList(),
        success = _failed.isEmpty()
    )

    override fun close() {
        if (_closed) return
        _closed = true

        var firstException: Throwable? = null

        // Wipe materializer paths
        try {
            _materializer.close()
        } catch (t: Throwable) {
            firstException = t
        }

        // Wipe all active handles
        for (handle in _activeHandles) {
            try {
                handle.close()
            } catch (t: Throwable) {
                if (firstException == null) {
                    firstException = t
                } else {
                    firstException.addSuppressed(t)
                }
            }
        }

        // If there was an exception, rethrow it
        firstException?.let { throw it }
    }

    companion object {
        fun kindToPurpose(kind: CredentialsBinding.Kind): BoundPurpose = when (kind) {
            CredentialsBinding.Kind.STRING -> BoundPurpose.API_KEY
            CredentialsBinding.Kind.USERNAME_PASSWORD -> BoundPurpose.USERNAME_PASSWORD
            CredentialsBinding.Kind.SSH_USER_PRIVATE_KEY -> BoundPurpose.SSH_KEY
            CredentialsBinding.Kind.FILE -> BoundPurpose.FILE
            CredentialsBinding.Kind.CERTIFICATE -> BoundPurpose.CERTIFICATE
            CredentialsBinding.Kind.ZIP -> BoundPurpose.ZIP
            CredentialsBinding.Kind.USERNAME_COLON_PASSWORD -> BoundPurpose.USERNAME_COLON_PASSWORD
        }

        fun kindToMaterializationKind(kind: CredentialsBinding.Kind): MaterializationKind? = when (kind) {
            CredentialsBinding.Kind.SSH_USER_PRIVATE_KEY -> MaterializationKind.SshPrivateKey
            CredentialsBinding.Kind.FILE -> MaterializationKind.SecretFile
            CredentialsBinding.Kind.CERTIFICATE -> MaterializationKind.Certificate
            CredentialsBinding.Kind.ZIP -> MaterializationKind.Zip
            else -> null
        }
    }
}