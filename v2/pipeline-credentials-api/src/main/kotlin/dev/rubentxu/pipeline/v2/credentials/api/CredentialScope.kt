package dev.rubentxu.pipeline.v2.credentials.api

import dev.rubentxu.pipeline.v2.domain.BoundPurpose
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.scm.GitCredentials
import dev.rubentxu.pipeline.v2.domain.scm.SecretHandleRef
import dev.rubentxu.pipeline.v2.events.CredentialBound
import dev.rubentxu.pipeline.v2.events.CredentialUnbound
import dev.rubentxu.pipeline.v2.events.EventSink
import java.util.UUID

/**
 * Lifecycle scope for credential bindings within a pipeline step.
 *
 * ## Design (ADR-0049 D2 — rung ii)
 *
 * [CredentialScope] is the SOLE entry point for [SecretHandle] injection into
 * [ProcessBuilder.environment()]. The engine JVM's [System.getenv] never sees
 * secret bytes (INV-CR-CR7 + /proc/environ same-UID defense).
 *
 * ## Responsibilities
 *
 * 1. **Binding resolution**: `env(binding)` resolves a [CredentialsBinding] to a map of
 *    environment variable names to [SecretHandle]. Variable names are persisted WITHOUT
 *    case coercion (INV-L6-CR-007).
 * 2. **Wipe on exit**: `close()` fills all active handles with zeros (INV-CR-CR7).
 *    Called in `finally` — always executes even on step failure.
 * 3. **Redaction pattern scoping**: `close()` also drops active redaction patterns
 *    from the bound [RedactingEventSink] (CR-RD-011).
 * 4. **Audit events**: emits [CredentialBound], [CredentialUsed], [CredentialUnbound]
 *    events through the bound [EventSink] (INV-L6-CR-008).
 *
 * ## Failures
 *
 * Wipe failures propagate via `addSuppressed` and do NOT prevent step completion.
 * The first exception is rethrown; subsequent exceptions are suppressed.
 *
 * @param store The credential store (e.g., [LocalSecretStore])
 * @param bindings The credential bindings active in this scope
 * @param eventSink The event sink for audit events
 * @param clock Clock for event timestamps
 * @param runId The run identifier for audit events
 *
 * @see SecretStore for the store interface
 * @see RedactingEventSink for the redaction decorator
 */
class CredentialScope(
    private val store: SecretStore,
    private val bindings: List<CredentialsBinding>,
    private val eventSink: EventSink,
    private val clock: Clock,
    private val runId: String,
) : AutoCloseable {

    // Active handles — filled with zeros on close
    private val activeHandles = mutableMapOf<CredentialsId, SecretHandle>()

    // Track sequence for events
    private var sequence: Long = 0L

    // Track bound credentials for audit
    private val boundCredentials = mutableSetOf<CredentialsId>()

    /**
     * Resolves a [CredentialsBinding] to a map of environment variable names to [SecretHandle].
     *
     * The handle is cached — subsequent calls return the same [SecretHandle].
     * Handles are wiped on [close()].
     *
     * ## Variable Name Persistence (INV-L6-CR-007)
     *
     * Variable names are persisted WITHOUT case coercion. The variable name specified
     * in the binding is used exactly as-is for the environment variable name.
     *
     * ## Audit Events (INV-L6-CR-008)
     *
     * Emits [CredentialBound] event when a credential is first resolved.
     *
     * @param binding The credential binding to resolve
     * @return Map of environment variable names to [SecretHandle]
     * @throws SecretStoreException if the credential is not found or tampered
     */
    fun env(binding: CredentialsBinding): Map<String, SecretHandle> {
        val handle = activeHandles.getOrPut(binding.credentialsId) {
            val handle = store.getAsSecretHandle(binding.credentialsId)

            // Emit CredentialBound event (INV-L6-CR-008)
            if (!boundCredentials.contains(binding.credentialsId)) {
                boundCredentials.add(binding.credentialsId)
                emitCredentialBound(binding.credentialsId, binding.boundPurpose)
            }

            handle
        }

        // Return env var entries based on binding kind
        return binding.toEnvEntries(handle)
    }

    /**
     * Resolves a credential ID to its [SecretHandle] within this scope.
     *
     * The handle is cached — subsequent calls return the same [SecretHandle].
     * Handles are wiped on [close()].
     *
     * @param id The credential ID to resolve
     * @return [SecretHandle] wrapping the secret bytes
     * @throws SecretStoreException if the credential is not found or tampered
     */
    fun env(id: CredentialsId): SecretHandle {
        return activeHandles.getOrPut(id) {
            store.getAsSecretHandle(id)
        }
    }

    /**
     * Closes the scope: wipes all handles and drops redaction patterns.
     *
     * Called in `finally` — always executes even on step failure.
     * Failures propagate via `addSuppressed`; first rethrown.
     *
     * Emits [CredentialUnbound] events for all bound credentials (INV-L6-CR-008).
     */
    override fun close() {
        var firstException: Throwable? = null

        // Emit CredentialUnbound events for all bound credentials
        for (credentialsId in boundCredentials) {
            try {
                emitCredentialUnbound(credentialsId)
            } catch (e: Throwable) {
                if (firstException == null) {
                    firstException = e
                } else {
                    firstException.addSuppressed(e)
                }
            }
        }
        boundCredentials.clear()

        // Wipe all active handles (LIFO not required — all independent)
        for ((_, handle) in activeHandles) {
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
        activeHandles.clear()

        firstException?.let { throw it }
    }

    private fun emitCredentialBound(credentialsId: CredentialsId, purpose: BoundPurpose) {
        sequence++
        val event = CredentialBound(
            eventId = UUID.randomUUID().toString(),
            runId = runId,
            sequence = sequence,
            occurredAt = clock.now(),
            credentialsId = credentialsId,
            purpose = purpose,
        )
        eventSink.append(event)
    }

    private fun emitCredentialUnbound(credentialsId: CredentialsId) {
        sequence++
        val event = CredentialUnbound(
            eventId = UUID.randomUUID().toString(),
            runId = runId,
            sequence = sequence,
            occurredAt = clock.now(),
            credentialsId = credentialsId,
        )
        eventSink.append(event)
    }
}

/**
 * Credential binding — describes HOW a credential will be used in a step.
 *
 * ## Kinds
 *
 * - [Kind.STRING]: a single environment variable (e.g., `API_KEY=ghp_xxx`)
 * - [Kind.USERNAME_PASSWORD]: two environment variables (e.g., `USR=admin`, `PSW=secret`)
 *
 * ## Design Note
 *
 * File/ssh/certificate bindings are deferred to ML-R4.1 (DEC-Q1 cut).
 * Only string and usernamePassword are implemented in L4.
 *
 * @see CredentialScope for the lifecycle manager
 */
data class CredentialsBinding(
    val kind: Kind,
    val credentialsId: CredentialsId,
    val variable: String? = null,
    val usernameVariable: String? = null,
    val passwordVariable: String? = null,
) {
    enum class Kind {
        STRING,
        USERNAME_PASSWORD,
    }

    /**
     * Maps this binding kind to a [BoundPurpose] for audit events (INV-L6-CR-008).
     */
    val boundPurpose: BoundPurpose
        get() = when (kind) {
            Kind.STRING -> BoundPurpose.API_KEY
            Kind.USERNAME_PASSWORD -> BoundPurpose.USERNAME_PASSWORD
        }

    /**
     * Converts this binding to a map of environment variable names to [SecretHandle].
     *
     * ## Variable Name Persistence (INV-L6-CR-007)
     *
     * Variable names are persisted WITHOUT case coercion. The variable name specified
     * in the binding is used exactly as-is.
     *
     * @param handle The [SecretHandle] to associate with each env var
     * @return Map of environment variable names to [SecretHandle]
     */
    fun toEnvEntries(handle: SecretHandle): Map<String, SecretHandle> {
        return when (kind) {
            Kind.STRING -> {
                val varName = variable
                    ?: throw IllegalStateException("String binding missing variable name")
                mapOf(varName to handle)
            }
            Kind.USERNAME_PASSWORD -> {
                val userVar = usernameVariable
                    ?: throw IllegalStateException("UsernamePassword binding missing usernameVariable")
                val passVar = passwordVariable
                    ?: throw IllegalStateException("UsernamePassword binding missing passwordVariable")
                // UsernamePassword stores username\0password as null-separated bytes
                // We need to split them into two separate handles
                val handles = mutableMapOf<String, SecretHandle>()

                handle.use { bytes ->
                    val nullIndex = bytes.indexOf(0.toByte())
                    if (nullIndex > 0) {
                        val usernameBytes = bytes.sliceArray(0 until nullIndex)
                        val passwordBytes = bytes.sliceArray(nullIndex + 1 until bytes.size)
                        handles[userVar] = SecretHandle.secret(usernameBytes)
                        handles[passVar] = SecretHandle.secret(passwordBytes)
                    } else {
                        // Fallback: treat entire bytes as username, empty password
                        handles[userVar] = SecretHandle.secret(bytes)
                        handles[passVar] = SecretHandle.secret(ByteArray(0))
                    }
                }

                handles
            }
        }
    }

    companion object {
        fun string(credentialsId: CredentialsId, variable: String) =
            CredentialsBinding(Kind.STRING, credentialsId, variable = variable)

        fun usernamePassword(
            credentialsId: CredentialsId,
            usernameVariable: String,
            passwordVariable: String,
        ) = CredentialsBinding(
            Kind.USERNAME_PASSWORD,
            credentialsId,
            usernameVariable = usernameVariable,
            passwordVariable = passwordVariable
        )
    }
}

/**
 * Converts a [CredentialsBinding] to [GitCredentials] for use by git checkout steps.
 *
 * @return GitCredentials with typed carriers for the credential
 * @throws IllegalArgumentException if the binding kind is not STRING or USERNAME_PASSWORD
 */
fun CredentialsBinding.asGitCredentials(): GitCredentials {
    return when (kind) {
        CredentialsBinding.Kind.STRING -> GitCredentials(
            string = SecretHandleRef(credentialsId),
            user = null,
            pass = null,
        )
        CredentialsBinding.Kind.USERNAME_PASSWORD -> GitCredentials(
            string = null,
            user = SecretHandleRef(credentialsId),
            pass = SecretHandleRef(credentialsId),
        )
        else -> throw IllegalArgumentException(
            "Credentials '${credentialsId.value}' is of type '${kind.name.lowercase()}' where 'string' or 'usernamePassword' was expected."
        )
    }
}
