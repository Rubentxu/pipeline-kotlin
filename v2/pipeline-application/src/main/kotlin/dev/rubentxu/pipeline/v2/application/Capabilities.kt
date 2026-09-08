package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.step.StepCapability

/**
 * Capability key under which the engine supplies the [dev.rubentxu.pipeline.v2.events.EventSink] to a
 * handler that must emit typed output/domain events.
 *
 * Kept at a neutral owner (CDE.3-d1) so the durable runtime capability bridge and the registry step
 * definitions can both reference the SAME token without the execution seam depending on any concrete
 * plugin definition (e.g. `core.echo`). A handler may use the capability only if it declares it in its
 * [dev.rubentxu.pipeline.v2.domain.step.StepContract.requiredCapabilities]; admission is fail-closed
 * before the handler runs when it is not available.
 */
val EVENT_SINK_CAPABILITY: StepCapability = StepCapability("eventSink")
