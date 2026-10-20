package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.step.StepCapability

/**
 * Capability key under which the engine supplies a typed [ShellOperations]
 * seam to a handler that must execute a shell process (LB-02 / G3).
 *
 * Mirrors the [EVENT_SINK_CAPABILITY] shape:
 *
 * - Kept at a neutral owner so the durable runtime capability bridge and
 *   the registry step definitions can both reference the SAME token
 *   without the execution seam depending on any concrete plugin
 *   definition (e.g. `core.sh`).
 * - A handler may use the capability only if it declares it in its
 *   [dev.rubentxu.pipeline.v2.domain.step.StepContract.requiredCapabilities];
 *   admission is fail-closed before the handler runs when it is not
 *   available.
 * - The corresponding `StepHandlerContext.capabilities.get<ShellOperations>(SHELL_OPERATIONS_CAPABILITY)`
 *   is the only call-site a registry-routed sh handler is allowed to use.
 */
val SHELL_OPERATIONS_CAPABILITY: StepCapability = StepCapability("shellOperations")
