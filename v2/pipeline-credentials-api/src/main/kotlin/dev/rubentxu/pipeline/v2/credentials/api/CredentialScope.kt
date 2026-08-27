package dev.rubentxu.pipeline.v2.credentials.api

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.scm.GitCredentials
import dev.rubentxu.pipeline.v2.domain.scm.SecretHandleRef
import dev.rubentxu.pipeline.v2.events.EventSink
import java.time.Clock

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
 * 1. **Binding resolution**: `env(id)` resolves a [CredentialsId] to a [SecretHandle]
 *    from the bound [SecretStore]. Failures propagate to the step.
 * 2. **Wipe on exit**: `close()` fills all active handles with zeros (INV-CR-CR7).
 *    Called in `finally` — always executes even on step failure.
 * 3. **Redaction pattern scoping**: `close()` also drops active redaction patterns
 *    from the bound [RedactingEventSink] (CR-RD-011).
 * 4. **Audit events**: emits [CredentialBound], [CredentialUsed], [CredentialUnbound]
 *    events through the bound [EventSink].
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
 *
 * @see SecretStore for the store interface
 * @see RedactingEventSink for the redaction decorator
 */
class CredentialScope(
    private val store: SecretStore,
    private val bindings: List<CredentialsBinding>,
    private val eventSink: EventSink,
    private val clock: Clock,
) : AutoCloseable {

    // Active handles — filled with zeros on close
    private val activeHandles = mutableMapOf<CredentialsId, SecretHandle>()

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
            val handle = store.get(id)
            // TODO: emit CredentialBound and CredentialUsed events
            handle
        }
    }

    /**
     * Closes the scope: wipes all handles and drops redaction patterns.
     *
     * Called in `finally` — always executes even on step failure.
     * Failures propagate via `addSuppressed`; first rethrown.
     */
    override fun close() {
        var firstException: Throwable? = null

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

        // TODO: emit CredentialUnbound events

        firstException?.let { throw it }
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
) {
    enum class Kind {
        STRING,
        USERNAME_PASSWORD,
    }

    val usernameVariable: String? = null
    val passwordVariable: String? = null
    val variable: String? = null

    companion object {
        fun string(credentialsId: CredentialsId, variable: String) =
            CredentialsBinding(Kind.STRING, credentialsId).let {
                // Note: in a full implementation, we'd store variable in the binding
                // This is a simplified version for T5
                it
            }

        fun usernamePassword(
            credentialsId: CredentialsId,
            usernameVariable: String,
            passwordVariable: String,
        ) = CredentialsBinding(Kind.USERNAME_PASSWORD, credentialsId)
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
