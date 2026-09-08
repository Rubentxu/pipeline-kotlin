package dev.rubentxu.pipeline.v2.credentials.executor

import dev.rubentxu.pipeline.v2.credentials.spi.CredentialMaterialization
import dev.rubentxu.pipeline.v2.credentials.spi.CredentialProvider
import dev.rubentxu.pipeline.v2.domain.BoundPurpose
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialBindingSpec
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialProjector
import dev.rubentxu.pipeline.v2.domain.credentials.DefaultCredentialProjector
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialMaterializationDomain
import dev.rubentxu.pipeline.v2.events.CredentialBound
import dev.rubentxu.pipeline.v2.events.CredentialUnbound
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import dev.rubentxu.pipeline.v2.dsl.toSpec
import java.util.UUID

/**
 * Port-driven executor for withCredentials block execution.
 *
 * Design (design §8, research §4, erratum-1, ADR-0051 §D8):
 * - Takes SPI ports: [CredentialProvider], [Clock], and a [CredentialProjector].
 * - Does NOT take concrete implementations (LocalSecretStore, CredentialMaterializer).
 * - Returns [BoundCredentials] with env map and idempotent close.
 *
 * ## LF-0404 changes
 *
 *  - The executor now consumes the typed [CredentialBindingSpec] sealed shape
 *    from `:pipeline-domain` (instead of the flat DSL `CredentialsBinding`).
 *  - The per-kind env-var switch (`kindToPurpose`, `kindToMaterializationKind`,
 *    `injectEnvVar`) is GONE. All binding→env mapping is delegated to the
 *    injected [CredentialProjector] which produces a [dev.rubentxu.pipeline.v2.domain.credentials.ProjectionResult].
 *  - The NUL-byte bug for STRING bindings (where the V2 envelope was forwarded
 *    into env) is fixed in [DefaultCredentialProjector], which extracts inner
 *    bytes via `resolveToCredential(id).bytes`.
 *
 * ## Responsibilities
 * - Resolves credentials via [CredentialProvider]
 * - Delegates per-kind projection to [CredentialProjector]
 * - Emits [CredentialBound] events BEFORE projection (INV-L10-CR-001 ordering)
 * - [BoundCredentials.close] is the SOLE owner of [CredentialUnbound] emission
 * - Reverse-LIFO cleanup with [addSuppressed] chaining
 *
 * ## What this executor does NOT do (application retains inner durable loop)
 * - No callback signature
 * - No executor-side spawn/fork/await
 * - No step execution
 *
 * @param provider The credential resolution port
 * @param projector The credential binding→env projection port (LF-0403)
 * @param clock The clock port for event timestamps
 */
class WithCredentialsExecutor(
    private val provider: CredentialProvider,
    private val projector: CredentialProjector,
    private val clock: Clock,
) {

    /**
     * Convenience constructor: wires a [DefaultCredentialProjector] from the
     * existing [CredentialMaterialization] SPI port.
     *
     * Existing call sites (composition root in `Main.kt`) construct
     * `WithCredentialsExecutor(provider, materialization, clock)`. We keep
     * that signature working by adapting the SPI port to the new domain port
     * via a thin adapter that satisfies [CredentialMaterializationDomain].
     */
    constructor(
        provider: CredentialProvider,
        materialization: CredentialMaterialization,
        clock: Clock,
    ) : this(
        provider = provider,
        projector = DefaultCredentialProjector(SpiMaterializationAdapter(materialization)),
        clock = clock,
    )

    /**
     * Binds credentials to environment variables and returns a [BoundCredentials].
     *
     * Design (research §4 positive scope):
     * - Emits [CredentialBound] for each binding BEFORE projection (INV-L10-CR-001 ordering)
     * - Returns [BoundCredentials] containing env map and idempotent close
     * - [BoundCredentials.close] is SOLE owner of [CredentialUnbound] emission
     *
     * @param bindings The credential bindings (DSL flat shape) to resolve
     * @param runId The pipeline run ID for event attribution
     * @param eventSink The event sink for audit trail events
     * @return [BoundCredentials] with env vars and close handler
     */
    suspend fun bind(
        bindings: List<StepSpec.CredentialsBinding>,
        runId: String,
        eventSink: EventSink,
    ): BoundCredentials = bindSpecs(
        specs = bindings.map { it.toSpec() },
        runId = runId,
        eventSink = eventSink,
    )

    /**
     * Binds credentials to environment variables directly from the typed domain
     * [CredentialBindingSpec] sealed shape.
     *
     * EM-7 / LFC-5.3 — this is the entry point consumed by the canonical
     * `CredentialScopePort` adapter. It shares the same acquire/emit/retain
     * core as [bind], but receives already-validated domain specs (no DSL
     * round-trip), so the coordinator can pass through the sealed specs it
     * decoded from the compiled node payload.
     *
     * @param specs The typed credential binding specifications (domain sealed shape)
     * @param runId The pipeline run ID for event attribution
     * @param eventSink The event sink for audit trail events
     * @return [BoundCredentials] with env vars and close handler
     */
    suspend fun bindSpecs(
        specs: List<CredentialBindingSpec>,
        runId: String,
        eventSink: EventSink,
    ): BoundCredentials {
        val env = mutableMapOf<String, SecretHandle>()
        val credentialIds = mutableListOf<CredentialsId>()
        val retainedProjections = mutableListOf<dev.rubentxu.pipeline.v2.domain.credentials.ProjectionResult>()
        var sequence = 1L

        try {
            for (spec in specs) {
                val credentialsId = spec.credentialsId
                credentialIds.add(credentialsId)
                val purpose = kindToPurpose(spec.kind)

                // Emit CredentialBound BEFORE projection (ADR-0051 D8 ordering)
                val boundEvent = CredentialBound(
                    eventId = UUID.randomUUID().toString(),
                    runId = runId,
                    sequence = sequence++,
                    occurredAt = clock.now(),
                    credentialsId = credentialsId,
                    purpose = purpose,
                )
                eventSink.append(boundEvent)

                // Resolve credential via provider (typed — no envelope)
                val credential = provider.resolveToCredential(credentialsId)

                // Delegate the per-kind projection to the port
                val projection = projector.project(spec, credential, runId)
                env.putAll(projection.bindings)
                // EM-7: retain materializations for deferred cleanup
                retainedProjections.add(projection)
            }

            return BoundCredentials(
                env = env.toMap(),
                credentials = credentialIds.toList(),
                retainedProjections = retainedProjections.toList(),
                closeAction = {
                    closeBoundCredentials(runId, credentialIds, eventSink)
                },
            )
        } catch (t: Throwable) {
            // On failure: close any already-created projections (reverse-LIFO), then emit events
            for (projection in retainedProjections.asReversed()) {
                try { projection.close() } catch (_: Throwable) { /* swallow: best-effort cleanup */ }
            }
            closeBoundCredentials(runId, credentialIds, eventSink, t)
            throw t
        }
    }

    /**
     * Maps a [CredentialBindingSpec] kind string to a [BoundPurpose] (per ADR-0051 §D8).
     *
     * Replaces the legacy 7-arm `when` that lived in the executor; the kind
     * labels here match the new domain sealed type exactly.
     */
    private fun kindToPurpose(kind: String): BoundPurpose = when (kind) {
        "string" -> BoundPurpose.API_KEY
        "usernamePassword" -> BoundPurpose.USERNAME_PASSWORD
        "sshUserPrivateKey" -> BoundPurpose.SSH_KEY
        "file" -> BoundPurpose.FILE
        "certificate" -> BoundPurpose.CERTIFICATE
        "zip" -> BoundPurpose.ZIP
        "usernameColonPassword" -> BoundPurpose.USERNAME_COLON_PASSWORD
        else -> throw IllegalArgumentException("Unknown CredentialBindingSpec kind: $kind")
    }

    /**
     * Closes all bound credentials in reverse-LIFO order.
     * This is the SOLE owner of [CredentialUnbound] emission (erratum-1, design E-19).
     * On failure path: also closes any already-created projections.
     */
    private fun closeBoundCredentials(
        runId: String,
        credentialIds: List<CredentialsId>,
        eventSink: EventSink,
        primaryThrowable: Throwable? = null,
    ) {
        var firstThrowable = primaryThrowable
        var sequence = 1L

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
 * Thin adapter that promotes the SPI [CredentialMaterialization] (which works
 * in `(credential, kind)` pairs) to the LF-0403 domain [CredentialMaterializationDomain]
 * (which dispatches on credential subtype only).
 *
 * The adapter derives the [dev.rubentxu.pipeline.v2.credentials.spi.MaterializationKind]
 * from the credential's runtime type:
 *  - SecretFile → MaterializationKind.SecretFile
 *  - SshPrivateKey → MaterializationKind.SshPrivateKey
 *  - Certificate → MaterializationKind.Certificate
 *  - Zip → MaterializationKind.Zip
 *
 * Anything else throws [IllegalArgumentException] — the executor's per-kind
 * switch is GONE so unsupported types fail loudly at the port boundary.
 */
private class SpiMaterializationAdapter(
    private val delegate: CredentialMaterialization,
) : CredentialMaterializationDomain {

    override fun materialize(
        credential: dev.rubentxu.pipeline.v2.domain.credentials.Credential,
    ): dev.rubentxu.pipeline.v2.domain.credentials.MaterializedCredentialDomain {
        val kind = when (credential) {
            is dev.rubentxu.pipeline.v2.domain.credentials.SecretFile ->
                dev.rubentxu.pipeline.v2.credentials.spi.MaterializationKind.SecretFile
            is dev.rubentxu.pipeline.v2.domain.credentials.SshPrivateKey ->
                dev.rubentxu.pipeline.v2.credentials.spi.MaterializationKind.SshPrivateKey
            is dev.rubentxu.pipeline.v2.domain.credentials.Certificate ->
                dev.rubentxu.pipeline.v2.credentials.spi.MaterializationKind.Certificate
            is dev.rubentxu.pipeline.v2.domain.credentials.Zip ->
                dev.rubentxu.pipeline.v2.credentials.spi.MaterializationKind.Zip
            else -> throw IllegalArgumentException(
                "Cannot materialize ${credential::class.simpleName} as a file-based credential",
            )
        }
        val materialized = delegate.materialize(credential, kind)
        return dev.rubentxu.pipeline.v2.domain.credentials.MaterializedCredentialDomain(
            path = materialized.path,
            handle = materialized.handle,
        )
    }

    override fun close() {
        delegate.close()
    }
}

/**
 * Result of [WithCredentialsExecutor.bind] — contains env vars, acquired credential IDs,
 * and idempotent close handler.
 *
 * Design (research §4 positive scope, EM-7 §Scoped acquisition):
 * - [env]: Map of environment variable names to secret handles
 * - [credentials]: the credential IDs that were acquired
 * - [retainedProjections]: retained [ProjectionResult] objects whose [ProjectionResult.close]
 *   performs secure file wipe in reverse-LIFO order
 * - [close]: Idempotent close handler that wipes materializations, emits
 *   [CredentialUnbound] events, and closes handles. Non-silent: accumulated
 *   wipe exceptions are thrown as [CredentialScopeCleanup.Failed].
 *
 * ## Thread Safety
 * [close] is safe to call from any thread. Idempotent — multiple calls are safe.
 */
class BoundCredentials(
    private val env: Map<String, SecretHandle>,
    private val credentials: List<CredentialsId>,
    private val retainedProjections: List<dev.rubentxu.pipeline.v2.domain.credentials.ProjectionResult>,
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
     * The credential IDs that were acquired by this binding.
     */
    fun credentials(): List<CredentialsId> = credentials

    /**
     * Closes all bound credentials and emits [CredentialUnbound] events.
     *
     * Ordering (per ADR-0051 D8):
     * 1. Close retained [ProjectionResult]s (secure file wipe, reverse-LIFO)
     * 2. Emit [CredentialUnbound] for each binding
     * 3. Close [SecretHandle]s
     *
     * Non-silent: wipe failures are accumulated and thrown as [CleanupException]
     * wrapping a [CredentialScopeCleanup.Failed] for the application adapter
     * to map to a typed [StepOutcome].
     *
     * Idempotent — calling multiple times is safe.
     */
    fun close() {
        if (!closed) {
            closed = true

            // 1. Close retained projections (secure file wipe, reverse-LIFO)
            // This may throw CleanupException
            val failedWipes = mutableListOf<java.nio.file.Path>()
            var firstWipeThrowable: Throwable? = null
            for (projection in retainedProjections.asReversed()) {
                try {
                    projection.close()
                } catch (t: Throwable) {
                    // Collect orphan paths from WipeException; others contribute nothing
                    if (t is dev.rubentxu.pipeline.v2.domain.credentials.ProjectionResult.WipeException) {
                        failedWipes.addAll(t.orphanPaths)
                    }
                    if (firstWipeThrowable == null) {
                        firstWipeThrowable = t
                    } else {
                        firstWipeThrowable.addSuppressed(t)
                    }
                }
            }

            // If wipe failed, propagate as CleanupException
            if (firstWipeThrowable != null) {
                // Emit unbound events even on wipe failure (audit trail)
                closeAction()
                // Then close handles
                closeHandles()
                // Then propagate
                throw CleanupException(
                    orphanPaths = failedWipes.distinct(),
                    detail = "Secure wipe failed: ${firstWipeThrowable.message}",
                    cause = firstWipeThrowable,
                )
            }

            // 2. Emit CredentialUnbound events
            closeAction()

            // 3. Close secret handles
            closeHandles()
        }
    }

    private fun closeHandles() {
        for (handle in env.values) {
            try {
                handle.close()
            } catch (_: Exception) {
                // SecretHandle wipe failure is non-fatal (WS-S-024 invariant)
            }
        }
    }

    /**
     * Thrown when secure wipe fails. The application adapter maps this to
     * [dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeCleanup.Failed].
     */
    class CleanupException(
        val orphanPaths: List<java.nio.file.Path>,
        val detail: String,
        override val cause: Throwable,
    ) : RuntimeException("Credential cleanup failed: $detail", cause)
}
