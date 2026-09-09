package dev.rubentxu.pipeline.v2.domain

import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.RecoveryPolicy
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy

/**
 * Static metadata for one plugin step kind.
 *
 * The descriptor belongs to the domain so compiled definitions, the SDK
 * generator, validation, execution planning, and graph projection share one
 * contract. It does not carry command payload or runtime state.
 *
 * ## Pre-decode durable metadata (LB-02 / G3-A4.1)
 *
 * The descriptor is the SINGLE source of pre-decode metadata that the durable protocol
 * may need BEFORE typed input decode. That set is:
 *
 *  - [effects]            — durable side-effect classification (drives replay policy)
 *  - [replayPolicy]       — declared replay/reuse strategy
 *  - [recoveryPolicy]     — declared recovery/reconciliation behaviour for RUNNING
 *                            journaled operations (e.g. `ExternalSubprocess` for `core.sh`)
 *
 * Putting all three on the descriptor mirrors how the legacy
 * [dev.rubentxu.pipeline.v2.application.CanonicalCoreStepMetadata] table carries them
 * and lets the registry metadata resolver propagate them 1:1 without per-Step branches.
 *
 * Defaults preserve backward compatibility: `core.echo` and other Steps that do not
 * need a typed recovery path keep `recoveryPolicy = RecoveryPolicy.None`.
 */
data class StepDescriptor(
    val stepId: String,
    val name: String,
    val configRef: String,
    val pluginId: String = "core",
    val pluginVersion: String = "0.0.0",
    val apiVersion: String = "v1",
    val executionLocation: ExecutionLocation = ExecutionLocation.WORKER,
    val inputSchema: String = "{}",
    val outputSchema: String = "{}",
    val requiredCapabilities: List<String> = emptyList(),
    val effects: List<Effect> = emptyList(),
    val replayPolicy: ReplayPolicy = ReplayPolicy.MEMOIZED,
    /** Pre-decode recovery declaration. See [RecoveryPolicy]. */
    val recoveryPolicy: RecoveryPolicy = RecoveryPolicy.None,
    val idempotencyModel: String = "",
    val timeoutModel: String = "",
    val jenkinsSurface: String = "",
    val securityProfile: String = "",
    val deprecation: String = "",
    // EM-4 body metadata (safe defaults preserve backward compatibility)
    val takesBody: Boolean = false,
    val bodyInvocations: BodyInvocationPolicy = BodyInvocationPolicy.ONCE,
    val introducesContext: ContextKind? = null,
    val catchesInterruptions: Boolean = false,
) {
    /** Legacy terminology retained for consumers of the legacy definition model. */
    val id: String get() = stepId

    /** Legacy terminology retained for consumers of the legacy definition model. */
    val type: String get() = name
}

/**
 * Policy for how many times body children may be invoked.
 */
sealed interface BodyInvocationPolicy {
    /** Body children are invoked exactly once (e.g., catchError). */
    data object ONCE : BodyInvocationPolicy

    /** Body children may be invoked zero or more times (e.g., retry). */
    data object ZERO_OR_MORE : BodyInvocationPolicy

    /** Body children are invoked at most once (e.g., warnError). */
    data object AT_MOST_ONCE : BodyInvocationPolicy
}

/**
 * Kind of context introduced by a block step.
 */
sealed interface ContextKind {
    data object ENVIRONMENT : ContextKind
    data object CWD : ContextKind
    data object CREDENTIALS : ContextKind
    data object OUTPUT_DECORATOR : ContextKind
    data object CANCELLATION : ContextKind
}
