package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.PLATFORM_IDENTITY_CAPABILITY
import dev.rubentxu.pipeline.v2.application.PlatformIdentity
import dev.rubentxu.pipeline.v2.application.EVENT_SINK_CAPABILITY
import dev.rubentxu.pipeline.v2.application.SHELL_OPERATIONS_CAPABILITY
import dev.rubentxu.pipeline.v2.application.STAGE_IDENTITY_CAPABILITY
import dev.rubentxu.pipeline.v2.application.StageIdentity
import dev.rubentxu.pipeline.v2.application.ShellOperations
import dev.rubentxu.pipeline.v2.application.WORKSPACE_OPERATIONS_CAPABILITY
import dev.rubentxu.pipeline.v2.application.WorkspaceOperations
import dev.rubentxu.pipeline.v2.application.WorkspaceOperationsAdapter
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions

/**
 * CDE.3-d1: explicit, small capability bridge from a [CanonicalRuntimeContext] to a
 * [StepCapabilityAccess].
 *
 * It exposes ONLY the capabilities the canonical runtime actually declares and provides today
 * ([EVENT_SINK_CAPABILITY] backed by the runtime's [CanonicalRuntimeContext.eventSink], and — since
 * LB-02 / A4 — [SHELL_OPERATIONS_CAPABILITY] backed by a fresh [ShOperationsAdapter] constructed
 * from the runtime context). It derives the capability values from the runtime context WITHOUT
 * handing the whole [CanonicalRuntimeContext] to a handler and without rebuilding a pipeline
 * context. Direction is contract.requiredCapabilities -> capability admission ->
 * [StepCapabilityAccess] -> handler; a handler must never receive the raw runtime context.
 *
 * Lookup is fail-closed: [get] on a capability that is not available throws rather than returning a
 * nullable/`Any?` sentinel, so a handler can only ever start once capability admission has confirmed
 * availability.
 */
open class CanonicalRuntimeCapabilityAccess(
    context: CanonicalRuntimeContext,
) : StepCapabilityAccess {

    private val provided: Map<StepCapability, Any> = buildProvided(context)

    override fun available(): Set<StepCapability> = provided.keys

    @Suppress("UNCHECKED_CAST")
    open override fun <T : Any> get(key: StepCapability): T =
        provided[key] as? T
            ?: throw IllegalArgumentException("capability unavailable to this invocation: $key")

    /**
     * Build the capability table for a given runtime context.
     *
     * [SHELL_OPERATIONS_CAPABILITY] is exposed ONLY when the runtime context can produce a
     * working [ShellOperations] (i.e. when the adapter can be constructed). The adapter binds
     * the runtime's [runId], [ShOptions], [controlDirRoot] and [EventSink] — exactly the
     * four inputs the existing durable shell substrate expects. If any of those are absent,
     * the adapter still constructs (controlDirRoot=null is the documented non-durable fallback
     * path) but the capability stays exposed so `core.sh` retain its declared require-set.
     */
    // protected (not private) so test harnesses can substitute a synthetic platform
    // observation without touching production logic; production always uses this impl.
    protected fun buildProvided(context: CanonicalRuntimeContext): Map<StepCapability, Any> {
        val builder: MutableMap<StepCapability, Any> = mutableMapOf(
            EVENT_SINK_CAPABILITY to context.eventSink,
        )
        val shellOps: ShellOperations = ShOperationsAdapter(
            runIdString = context.runId,
            opId = context.opId,
            shOptions = context.shOptions,
            controlDirRoot = context.controlDirRoot,
            eventSink = context.eventSink,
        )
        builder[SHELL_OPERATIONS_CAPABILITY] = shellOps
        // S2-A3 / G1: workspace file operations bound to the current stage identity.
        val workspaceOps: WorkspaceOperations = WorkspaceOperationsAdapter(
            stageName = context.stageName,
            stageIndex = context.stageIndex,
            controlDirRoot = context.controlDirRoot,
            eventSink = context.eventSink,
        )
        builder[WORKSPACE_OPERATIONS_CAPABILITY] = workspaceOps
        // S2-A4 / G1: narrow stage identity (name + index) for handlers needing the current
        // stage as a default (core.emit.event StageMarkedUnstable fallback). Derived from the
        // runtime context; never exposes the context itself.
        builder[STAGE_IDENTITY_CAPABILITY] = StageIdentity(
            name = context.stageName,
            index = context.stageIndex,
        )
        // S2-A5 / G1: raw environmental observation for platform-classification handlers
        // (core.isUnix). The single remaining System.getProperty("os.name") read lives HERE,
        // in the bridge adapter — never inside a handler.
        builder[PLATFORM_IDENTITY_CAPABILITY] = PlatformIdentity(
            osName = System.getProperty("os.name", ""),
        )
        return builder.toMap()
    }
}
