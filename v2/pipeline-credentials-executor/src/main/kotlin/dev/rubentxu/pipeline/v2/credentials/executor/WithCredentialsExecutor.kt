package dev.rubentxu.pipeline.v2.credentials.executor

import dev.rubentxu.pipeline.v2.credentials.spi.CredentialMaterialization
import dev.rubentxu.pipeline.v2.credentials.spi.CredentialProvider
import dev.rubentxu.pipeline.v2.domain.BoundPurpose
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.dsl.StepSpec.CredentialsBinding
import dev.rubentxu.pipeline.v2.events.CredentialBound
import dev.rubentxu.pipeline.v2.events.CredentialUnbound
import dev.rubentxu.pipeline.v2.events.EventSink
import java.util.UUID

/**
 * Port-driven executor for withCredentials block execution.
 *
 * Design (design §8, research §4, erratum-1, ADR-0051 §D8):
 * - Takes SPI ports: [CredentialProvider], [CredentialMaterialization], [Clock]
 * - Does NOT take concrete implementations (LocalSecretStore, CredentialMaterializer)
 * - Returns [BoundCredentials] with env map and idempotent close
 *
 * ## Responsibilities
 * - Resolves credentials via [CredentialProvider]
 * - Materializes file-based credentials via [CredentialMaterialization]
 * - Emits [CredentialBound] events BEFORE env injection (INV-L10-CR-001 ordering)
 * - [BoundCredentials.close] is the SOLE owner of [CredentialUnbound] emission
 * - Reverse-LIFO cleanup with [addSuppressed] chaining
 *
 * ## What this executor does NOT do (application retains inner durable loop)
 * - No callback signature
 * - No executor-side spawn/fork/await
 * - No step execution
 *
 * @param provider The credential resolution port
 * @param materialization The credential materialization port
 * @param clock The clock port for event timestamps
 */
class WithCredentialsExecutor(
    private val provider: CredentialProvider,
    private val materialization: CredentialMaterialization,
    private val clock: Clock,
) {

    /**
     * Binds credentials to environment variables and returns a [BoundCredentials].
     *
     * Design (research §4 positive scope):
     * - Emits [CredentialBound] for each binding BEFORE env injection
     * - Returns [BoundCredentials] containing env map and idempotent close
     * - [BoundCredentials.close] is SOLE owner of [CredentialUnbound] emission
     *
     * @param bindings The credential bindings to resolve
     * @param runId The pipeline run ID for event attribution
     * @param eventSink The event sink for audit trail events
     * @return [BoundCredentials] with env vars and close handler
     */
    suspend fun bind(
        bindings: List<CredentialsBinding>,
        runId: String,
        eventSink: EventSink,
    ): BoundCredentials {
        val env = mutableMapOf<String, SecretHandle>()
        val closeables = mutableListOf<AutoCloseable>()
        val credentialIds = mutableListOf<CredentialsId>()
        var sequence = 1L

        try {
            for (binding in bindings) {
                val credentialsId = binding.credentialsId
                credentialIds.add(credentialsId)
                val purpose = kindToPurpose(binding.kind)

                // Emit CredentialBound BEFORE resolution (INV-L10-CR-001 ordering)
                val boundEvent = CredentialBound(
                    eventId = UUID.randomUUID().toString(),
                    runId = runId,
                    sequence = sequence++,
                    occurredAt = clock.now(),
                    credentialsId = credentialsId,
                    purpose = purpose,
                )
                eventSink.append(boundEvent)

                // Resolve credential via provider
                val secretHandle = provider.resolve(credentialsId)

                // Materialize if file-based kind, otherwise use handle directly
                val materializationKind = kindToMaterializationKind(binding.kind)
                val handleToInject: SecretHandle = if (materializationKind != null) {
                    // File-based: materialize to temp file and inject path
                    val credential = provider.resolveToCredential(credentialsId)
                    val materialized = materialization.materialize(credential, materializationKind)
                    closeables.add(materialized)
                    // The temp file path is the env var value (masked, not redacted)
                    val path = materialized.path
                        ?: throw IllegalStateException("MaterializationKind.${materializationKind} must provide a path")
                    dev.rubentxu.pipeline.v2.domain.SecretHandle.masked(path.toString())
                } else {
                    // Non-file-based: use the raw handle directly
                    closeables.add(secretHandle)
                    secretHandle
                }

                // Inject into env map based on binding kind
                injectEnvVar(env, binding, handleToInject)
            }

            return BoundCredentials(env.toMap()) {
                closeBoundCredentials(runId, credentialIds, eventSink, closeables)
            }
        } catch (t: Throwable) {
            // On failure, close everything and rethrow
            closeBoundCredentials(runId, credentialIds, eventSink, closeables, t)
            throw t
        }
    }

    /**
     * Maps DSL Kind to BoundPurpose (per ADR-0051 §D8).
     */
    private fun kindToPurpose(kind: CredentialsBinding.Kind): BoundPurpose = when (kind) {
        CredentialsBinding.Kind.STRING -> BoundPurpose.API_KEY
        CredentialsBinding.Kind.USERNAME_PASSWORD -> BoundPurpose.USERNAME_PASSWORD
        CredentialsBinding.Kind.SSH_USER_PRIVATE_KEY -> BoundPurpose.SSH_KEY
        CredentialsBinding.Kind.FILE -> BoundPurpose.FILE
        CredentialsBinding.Kind.CERTIFICATE -> BoundPurpose.CERTIFICATE
        CredentialsBinding.Kind.ZIP -> BoundPurpose.ZIP
        CredentialsBinding.Kind.USERNAME_COLON_PASSWORD -> BoundPurpose.USERNAME_COLON_PASSWORD
    }

    /**
     * Maps DSL Kind to MaterializationKind for file-based kinds.
     * Returns null for non-file-based kinds.
     */
    private fun kindToMaterializationKind(kind: CredentialsBinding.Kind): dev.rubentxu.pipeline.v2.credentials.spi.MaterializationKind? = when (kind) {
        CredentialsBinding.Kind.SSH_USER_PRIVATE_KEY -> dev.rubentxu.pipeline.v2.credentials.spi.MaterializationKind.SshPrivateKey
        CredentialsBinding.Kind.FILE -> dev.rubentxu.pipeline.v2.credentials.spi.MaterializationKind.SecretFile
        CredentialsBinding.Kind.CERTIFICATE -> dev.rubentxu.pipeline.v2.credentials.spi.MaterializationKind.Certificate
        CredentialsBinding.Kind.ZIP -> dev.rubentxu.pipeline.v2.credentials.spi.MaterializationKind.Zip
        else -> null
    }

    /**
     * Injects environment variables based on binding kind.
     */
    private fun injectEnvVar(
        env: MutableMap<String, SecretHandle>,
        binding: CredentialsBinding,
        handle: SecretHandle,
    ) {
        when (binding.kind) {
            CredentialsBinding.Kind.STRING -> {
                binding.variable?.let { env[it] = handle }
            }
            CredentialsBinding.Kind.USERNAME_PASSWORD -> {
                binding.usernameVariable?.let { env[it] = handle }
                binding.passwordVariable?.let { env[it] = handle }
            }
            CredentialsBinding.Kind.USERNAME_COLON_PASSWORD -> {
                binding.variable?.let { env[it] = handle }
            }
            CredentialsBinding.Kind.SSH_USER_PRIVATE_KEY -> {
                binding.keyFileVariable?.let { env[it] = handle }
                binding.passphraseVariable?.let { env[it] = handle }
                binding.usernameVariable?.let { env[it] = handle }
            }
            CredentialsBinding.Kind.FILE -> {
                binding.variable?.let { env[it] = handle }
            }
            CredentialsBinding.Kind.CERTIFICATE -> {
                binding.keystoreVariable?.let { env[it] = handle }
                binding.aliasVariable?.let { env[it] = handle }
                binding.passwordVariable?.let { env[it] = handle }
            }
            CredentialsBinding.Kind.ZIP -> {
                val zipVar = binding.variable
                if (zipVar != null) {
                    env[zipVar] = handle
                }
            }
        }
    }

    /**
     * Closes all bound credentials in reverse-LIFO order.
     * This is the SOLE owner of [CredentialUnbound] emission (erratum-1, design E-19).
     */
    private fun closeBoundCredentials(
        runId: String,
        credentialIds: List<CredentialsId>,
        eventSink: EventSink,
        closeables: List<AutoCloseable>,
        primaryThrowable: Throwable? = null,
    ) {
        var firstThrowable = primaryThrowable
        var sequence = 1L

        // Reverse-LIFO cleanup: close in reverse order of acquisition
        for (closeable in closeables.reversed()) {
            try {
                closeable.close()
            } catch (t: Throwable) {
                if (firstThrowable == null) {
                    firstThrowable = t
                } else {
                    firstThrowable.addSuppressed(t)
                }
            }
        }

        // Emit CredentialUnbound for each binding (exactly once per binding)
        for (credentialsId in credentialIds) {
            val unboundEvent = CredentialUnbound(
                eventId = UUID.randomUUID().toString(),
                runId = runId,
                sequence = sequence++,
                occurredAt = clock.now(),
                credentialsId = credentialsId,
            )
            try {
                eventSink.append(unboundEvent)
            } catch (t: Throwable) {
                if (firstThrowable == null) {
                    firstThrowable = t
                } else {
                    firstThrowable.addSuppressed(t)
                }
            }
        }

        // Rethrow first throwable if any
        if (firstThrowable != null) {
            throw firstThrowable
        }
    }
}

/**
 * Result of [WithCredentialsExecutor.bind] — contains env vars and close handler.
 *
 * Design (research §4 positive scope):
 * - [env]: Map of environment variable names to secret handles
 * - [close]: Idempotent close handler that emits [CredentialUnbound] events
 *
 * ## Idempotency
 * The [close] handler is idempotent — multiple calls are safe.
 * Uses [@Volatile][volatile] marker for visibility across threads.
 *
 * ## Thread Safety
 * [close] is safe to call from any thread.
 */
class BoundCredentials(
    private val env: Map<String, SecretHandle>,
    private val closeAction: () -> Unit,
) {
    @Volatile
    private var closed = false

    /**
     * Environment variables from bound credentials.
     * Map of env var name to [SecretHandle].
     */
    fun env(): Map<String, SecretHandle> = env

    /**
     * Closes all bound credentials and emits [CredentialUnbound] events.
     *
     * This is the SOLE owner of [CredentialUnbound] emission (design E-19).
     * Idempotent — calling multiple times is safe.
     *
     * @throws Throwable if cleanup throws; suppressed exceptions are chained via [addSuppressed]
     */
    fun close() {
        if (!closed) {
            closed = true
            closeAction()
        }
    }
}
