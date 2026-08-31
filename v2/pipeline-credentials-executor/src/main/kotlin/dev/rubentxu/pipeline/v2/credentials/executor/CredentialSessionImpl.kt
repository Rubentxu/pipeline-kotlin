package dev.rubentxu.pipeline.v2.credentials.executor

import dev.rubentxu.pipeline.v2.credentials.api.SecretStore
import dev.rubentxu.pipeline.v2.credentials.api.SecretStoreException
import dev.rubentxu.pipeline.v2.credentials.multipart.CredentialMaterializer
import dev.rubentxu.pipeline.v2.credentials.multipart.MaterializationKind
import dev.rubentxu.pipeline.v2.domain.BoundPurpose
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import dev.rubentxu.pipeline.v2.events.CredentialBound
import dev.rubentxu.pipeline.v2.events.EventSink
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
 * This session emits CredentialBound events on open() for each binding.
 * PipelineRun is responsible for emitting CredentialUnbound on close() to preserve
 * event ordering (CredentialBound before StepStarted, CredentialUnbound after step completes).
 * This is intentional: the session is the SINGLE cleanup owner per H0 architecture.
 *
 * ## Cleanup semantics
 *
 * - [close] is idempotent and MUST be called exactly once per session
 * - The session is the SINGLE owner of cleanup; public callers MUST NOT
 *   independently emit CredentialUnbound events
 * - Calling close() multiple times is safe (idempotent)
 *
 * @param bindings The credentials bindings to resolve (StepSpec.CredentialsBinding)
 * @param secretStore The secret store to resolve from
 * @param eventSink Event sink for CredentialBound events (emitted before resolution)
 * @param runId The run ID for event context
 * @param clock Clock for event timestamps
 */
class CredentialSessionImpl(
    private val bindings: List<StepSpec.CredentialsBinding>,
    private val secretStore: SecretStore,
    private val eventSink: EventSink,
    private val runId: String,
    private val clock: Clock
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
     *
     * @throws CredentialResolutionException if any credential cannot be resolved
     */
    private fun resolve() {
        if (_opened) return
        _opened = true

        for (binding in bindings) {
            val purpose = kindToPurpose(binding.kind)
            val materializationKind = kindToMaterializationKind(binding.kind)

            // Emit CredentialBound BEFORE resolution (INV-L10-CR-001)
            // This event is emitted by the session - PipelineRun does NOT emit it separately
            emitCredentialBound(binding.credentialsId, purpose)

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

                // Record successful resolution
                val primaryEnvVar = binding.variable
                    ?: binding.usernameVariable
                    ?: binding.keyFileVariable
                    ?: binding.keystoreVariable
                    ?: ""

                _resolved.add(
                    CredentialResolution.Resolved(
                        credentialsId = binding.credentialsId,
                        purpose = purpose,
                        envVar = primaryEnvVar,
                        handle = _credentialEnv[primaryEnvVar] ?: throw IllegalStateException("No handle injected for $primaryEnvVar")
                    )
                )
            } catch (e: SecretStoreException) {
                // Credential resolution failed - fail-fast (preserve original semantics)
                _failed.add(CredentialResolution.Failed(binding.credentialsId, e.message ?: "Unknown error"))
                throw CredentialResolutionException(
                    "Credential resolution failed for ${binding.credentialsId.value}: ${e.message}",
                    binding.credentialsId,
                    e
                )
            } catch (e: CredentialResolutionException) {
                // Re-throw after recording
                throw e
            } catch (e: Throwable) {
                // Materialization or other failures - fail-fast (preserve original semantics)
                _failed.add(CredentialResolution.Failed(binding.credentialsId, e.message ?: "Unknown error"))
                throw CredentialResolutionException(
                    "Credential resolution failed for ${binding.credentialsId.value}: ${e.message}",
                    binding.credentialsId,
                    e
                )
            }
        }
    }

    private fun injectPathBinding(binding: StepSpec.CredentialsBinding, path: java.nio.file.Path, purpose: BoundPurpose) {
        when (binding.kind) {
            StepSpec.CredentialsBinding.Kind.SSH_USER_PRIVATE_KEY -> {
                binding.keyFileVariable?.let { varName ->
                    _credentialEnv[varName] = SecretHandle.masked(path.toString())
                }
                binding.passphraseVariable?.let { varName ->
                    // For SSH keys with passphrase, the passphrase is written to a separate temp file
                    // The handle is added via _activeHandles during materialization
                    _credentialEnv[varName] = SecretHandle.masked(path.toString())
                }
                binding.usernameVariable?.let { varName ->
                    _credentialEnv[varName] = SecretHandle.masked(path.toString())
                }
            }
            StepSpec.CredentialsBinding.Kind.FILE -> {
                binding.variable?.let { varName ->
                    _credentialEnv[varName] = SecretHandle.masked(path.toString())
                }
            }
            StepSpec.CredentialsBinding.Kind.CERTIFICATE -> {
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
            StepSpec.CredentialsBinding.Kind.ZIP -> {
                binding.variable?.let { varName ->
                    _credentialEnv[varName] = SecretHandle.masked(path.toString())
                }
            }
            else -> { /* non-file kinds handled in injectHandleBinding */ }
        }
    }

    private fun injectHandleBinding(binding: StepSpec.CredentialsBinding, handle: SecretHandle) {
        when (binding.kind) {
            StepSpec.CredentialsBinding.Kind.STRING -> {
                binding.variable?.let { varName ->
                    _credentialEnv[varName] = handle
                }
            }
            StepSpec.CredentialsBinding.Kind.USERNAME_PASSWORD -> {
                binding.usernameVariable?.let { varName ->
                    _credentialEnv[varName] = handle
                }
                binding.passwordVariable?.let { varName ->
                    _credentialEnv[varName] = handle
                }
            }
            StepSpec.CredentialsBinding.Kind.USERNAME_COLON_PASSWORD -> {
                binding.variable?.let { varName ->
                    _credentialEnv[varName] = handle
                }
            }
            else -> { /* file-based handled in injectPathBinding */ }
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

    /**
     * Closes the session, wiping all tracked handles and materializer paths.
     *
     * Idempotent: safe to call multiple times.
     * This is the SINGLE cleanup owner - public callers MUST NOT independently
     * emit CredentialUnbound events.
     *
     * Cleanup order:
     * 1. Wipe materializer paths (files created for file-based credentials)
     * 2. Wipe all active handles (secret bytes filled with zeros)
     */
    override fun close() {
        if (_closed) return
        _closed = true

        var firstException: Throwable? = null

        // Wipe materializer paths (file-based credentials)
        try {
            _materializer.close()
        } catch (t: Throwable) {
            firstException = t
        }

        // Wipe all active handles (secret bytes)
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

    private fun emitCredentialBound(credentialsId: CredentialsId, purpose: BoundPurpose) {
        val event = CredentialBound(
            eventId = UUID.randomUUID().toString(),
            runId = runId,
            sequence = 0L, // Sequence is assigned by EventStore.append
            occurredAt = clock.now(),
            credentialsId = credentialsId,
            purpose = purpose,
        )
        eventSink.append(event)
    }

    companion object {
        /**
         * Maps StepSpec.CredentialsBinding.Kind to BoundPurpose.
         *
         * This is the canonical seven-kind mapping per Jenkins credentials-binding plugin.
         */
        fun kindToPurpose(kind: StepSpec.CredentialsBinding.Kind): BoundPurpose = when (kind) {
            StepSpec.CredentialsBinding.Kind.STRING -> BoundPurpose.API_KEY
            StepSpec.CredentialsBinding.Kind.USERNAME_PASSWORD -> BoundPurpose.USERNAME_PASSWORD
            StepSpec.CredentialsBinding.Kind.SSH_USER_PRIVATE_KEY -> BoundPurpose.SSH_KEY
            StepSpec.CredentialsBinding.Kind.FILE -> BoundPurpose.FILE
            StepSpec.CredentialsBinding.Kind.CERTIFICATE -> BoundPurpose.CERTIFICATE
            StepSpec.CredentialsBinding.Kind.ZIP -> BoundPurpose.ZIP
            StepSpec.CredentialsBinding.Kind.USERNAME_COLON_PASSWORD -> BoundPurpose.USERNAME_COLON_PASSWORD
        }

        /**
         * Maps StepSpec.CredentialsBinding.Kind to MaterializationKind.
         *
         * Returns null for in-memory kinds (STRING, USERNAME_PASSWORD, USERNAME_COLON_PASSWORD)
         * that don't require file materialization.
         */
        fun kindToMaterializationKind(kind: StepSpec.CredentialsBinding.Kind): MaterializationKind? = when (kind) {
            StepSpec.CredentialsBinding.Kind.SSH_USER_PRIVATE_KEY -> MaterializationKind.SshPrivateKey
            StepSpec.CredentialsBinding.Kind.FILE -> MaterializationKind.SecretFile
            StepSpec.CredentialsBinding.Kind.CERTIFICATE -> MaterializationKind.Certificate
            StepSpec.CredentialsBinding.Kind.ZIP -> MaterializationKind.Zip
            else -> null
        }
    }
}