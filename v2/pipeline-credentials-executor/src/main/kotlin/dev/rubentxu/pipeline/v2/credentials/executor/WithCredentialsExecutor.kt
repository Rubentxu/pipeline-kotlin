package dev.rubentxu.pipeline.v2.credentials.executor

import dev.rubentxu.pipeline.v2.credentials.spi.CredentialMaterialization
import dev.rubentxu.pipeline.v2.credentials.spi.CredentialProvider
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialBindingSpec
import dev.rubentxu.pipeline.v2.domain.credentials.boundPurpose
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
    private val redactionRegistry: dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry? = null,
) {

    /**
     * Convenience constructor: wires a [DefaultCredentialProjector] from the
     * existing [CredentialMaterialization] SPI port AND the LF-0403
     * [dev.rubentxu.pipeline.v2.domain.credentials.CredentialLinkedSecretResolver]
     * port (ADR-0097).
     *
     * Existing call sites (composition root in `Main.kt`) construct
     * `WithCredentialsExecutor(provider, materialization, clock)`. We keep
     * that signature working by adapting the SPI ports to the domain ports
     * via thin adapters.
     */
    constructor(
        provider: CredentialProvider,
        materialization: CredentialMaterialization,
        clock: Clock,
        redactionRegistry: dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry? = null,
    ) : this(
        provider = provider,
        projector = DefaultCredentialProjector(
            materialization = SpiMaterializationAdapter(materialization),
            linkedSecretResolver = SpiCredentialLinkedSecretResolver(provider),
        ),
        clock = clock,
        redactionRegistry = redactionRegistry,
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

        // WU-RP-020: NO local sequence counter. The durable sequence authority is
        // the store (`SqliteEventStore.appendAssigned`), which assigns when the
        // incoming sequence is 0. A local counter starting at 1 bypasses that
        // authority entirely, so CredentialBound always claimed sequence 1 of a
        // run and collided with the run's first real event. That corruption was
        // silent until UNIQUE(run_id, sequence) made it fail closed.
        // See bl-bl-01M3RK3NZV000387H65JWN9XM0.
        try {
            for (spec in specs) {
                val credentialsId = spec.credentialsId
                credentialIds.add(credentialsId)
                // AUD-07: single domain authority for kind → BoundPurpose. The
                // local 7-arm copy was removed so there is exactly one mapping.
                val purpose = spec.boundPurpose

                // Emit CredentialBound BEFORE projection (ADR-0051 D8 ordering)
                val boundEvent = CredentialBound(
                    eventId = UUID.randomUUID().toString(),
                    runId = runId,
                    sequence = 0L,
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

            registerBoundSecrets(env, retainedProjections)

            return BoundCredentials(
                env = env.toMap(),
                credentials = credentialIds.toList(),
                retainedProjections = retainedProjections.toList(),
                closeAction = {
                    val failure = emitCredentialUnbound(runId, credentialIds, eventSink)
                    if (failure != null) throw failure
                },
            )
        } catch (t: Throwable) {
            // On failure: close any already-created projections (reverse-LIFO), then emit events.
            //
            // `drainProjections` folds each wipe failure into `t` as a SUPPRESSED exception rather
            // than discarding it. The previous `catch (_: Throwable) { /* swallow */ }` meant a
            // materialized key that could not be wiped left no trace: the operator heard only about
            // the missing credential.
            drainProjections(retainedProjections, t)

            // `emitCredentialUnbound` RETURNS the throwable instead of throwing it, precisely so
            // that this line is the live thrower. Seeded with a non-null primary it always returns
            // non-null, and `requireNotNull` says so as a checked precondition: the tempting `?: t`
            // would rethrow the ACQUISITION failure and hide the very append failure it was
            // standing in for.
            throw requireNotNull(emitCredentialUnbound(runId, credentialIds, eventSink, t)) {
                "emitCredentialUnbound returned null despite a non-null primaryThrowable"
            }
        }
    }

    /**
     * Registers every bound SECRET with the redaction registry, so the value cannot reach a
     * durable surface in plain text.
     *
     * Two shapes, because there are two shapes of secret:
     *
     *  - **Env handles** ([SecretHandle.isMasked] false) carry the value itself. Registered whole:
     *    a password or a token is tens of bytes, and whole-value matching is exact.
     *  - **Materialised files** are reached through a MASKED env handle — a key file binds as its
     *    PATH, and redacting the path would break the binding (and this class already asserts a
     *    bound key path DOES appear in the transcript). The CONTENT is a separate secret, so it is
     *    registered here from the retained materialization, record by record.
     *
     * ## Why this seam and not the store
     *
     * A binding is the moment a secret becomes *visible*: it is the last point where the value
     * exists and the first where a child process can read it. Registering here means the registry
     * holds exactly what was exposed — no earlier (a store would claim lookahead for credentials
     * the pipeline never binds, and every claimed byte is output latency paid on every step) and
     * no later (by the time a transcript exists, the value is already durable).
     */
    private fun registerBoundSecrets(
        env: Map<String, SecretHandle>,
        projections: List<dev.rubentxu.pipeline.v2.domain.credentials.ProjectionResult>,
    ) {
        val registry = redactionRegistry ?: return

        for (handle in env.values) {
            if (!handle.isMasked) {
                registry.addSecret(handle)
            }
        }

        for (materialization in projections.asSequence().flatMap { it.retainedMaterializations }) {
            val path = materialization.path ?: continue
            // Only a regular FILE carries credential content. A directory materialization (Zip)
            // has no single content to register.
            if (!java.nio.file.Files.isRegularFile(path)) continue
            // Best-effort, deliberately. Failing the BINDING because an observability concern
            // could not be registered would turn a redaction gap into a pipeline outage — the
            // credential still works, it is merely less protected. The gap is documented rather
            // than thrown.
            runCatching { java.nio.file.Files.readString(path) }
                .onSuccess { registry.addSecretRecords(it) }
        }
    }

    /**
     * Emits one [CredentialUnbound] per binding and REPORTS the outcome instead of throwing it.
     *
     * This is the SOLE owner of [CredentialUnbound] emission (erratum-1, design E-19).
     *
     * ## Why it returns rather than throws
     *
     * It is called from two places with two different obligations. As the `closeAction` of a live
     * [BoundCredentials] it must not throw, because [runTeardown] has phases after it whose work
     * would be skipped. On the acquisition-rollback path it must produce the throwable the caller
     * propagates, and that throwable has to be the one already in flight — the one the caller is
     * `throw`ing — so that wipe failures attached to it by [drainProjections] survive.
     *
     * A function that only threw could not serve both without the second caller keeping a `throw`
     * after a call that had already thrown, which is unreachable code that reads as a live guard.
     *
     * @param primaryThrowable the throwable already in flight, or `null` on the normal teardown
     *   path. Every append failure is suppressed onto it.
     * @return the throwable to propagate — [primaryThrowable] when given, otherwise the first
     *   append failure — or `null` when every append succeeded.
     */
    private fun emitCredentialUnbound(
        runId: String,
        credentialIds: List<CredentialsId>,
        eventSink: EventSink,
        primaryThrowable: Throwable? = null,
    ): Throwable? {
        var firstThrowable = primaryThrowable

        // WU-RP-020: no local sequence counter here either. Restarting at 1 per
        // teardown was the same authority bypass as the bind path, and it also
        // made the first CredentialUnbound of every teardown collide with
        // sequence 1 of the run. The store assigns the durable sequence.
        // See bl-bl-01M3RK3NZV000387H65JWN9XM0.

        // Emit CredentialUnbound for each binding (exactly once per binding)
        for (credentialsId in credentialIds) {
            val unboundEvent = CredentialUnbound(
                eventId = UUID.randomUUID().toString(),
                runId = runId,
                sequence = 0L,
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

        return firstThrowable
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
     * Every phase runs even when an earlier one threw. That is the whole contract: each phase
     * releases something no later phase can, so a throw from phase 2 used to leave every
     * [SecretHandle] byte array resident — and since `closed` is already set, the retry was a
     * no-op. See [runTeardown].
     *
     * Non-silent: a wipe failure is reported as [CleanupException] carrying the orphan paths. A
     * failure outside the wipe propagates unwrapped, which is the shape this class had before
     * [runTeardown] existed and the shape `WithCredentialsExecutorScopeAdapter` in
     * `:pipeline-application` already maps.
     *
     * Idempotent — calling multiple times is safe.
     */
    fun close() {
        if (!closed) {
            closed = true

            // One phase per projection so that each projection's WipeException contributes its own
            // orphan paths, rather than only the first one's surviving into the report.
            val phases = mutableListOf<TeardownPhase>()
            for (projection in retainedProjections.asReversed()) {
                phases += TeardownPhase(TeardownPhaseName.Wipe) { projection.close() }
            }
            phases += TeardownPhase(TeardownPhaseName.EmitUnbound) { closeAction() }
            phases += TeardownPhase(TeardownPhaseName.CloseHandles) { closeHandles() }

            val outcome = runTeardown(phases)
            val failed = outcome as? TeardownOutcome.Failed ?: return

            val wipe = failed.wipeFailure
            if (wipe != null) {
                throw CleanupException(
                    orphanPaths = failed.orphanPaths,
                    detail = "Secure wipe failed: ${wipe.throwable.message}",
                    cause = wipe.throwable,
                )
            }

            throw failed.firstFailure.throwable
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
