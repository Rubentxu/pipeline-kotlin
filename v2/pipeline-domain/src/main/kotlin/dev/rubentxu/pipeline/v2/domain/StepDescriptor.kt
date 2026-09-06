package dev.rubentxu.pipeline.v2.domain

import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy

/**
 * Static metadata for one plugin step kind.
 *
 * The descriptor belongs to the domain so compiled definitions, the SDK
 * generator, validation, execution planning, and graph projection share one
 * contract. It does not carry command payload or runtime state.
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
