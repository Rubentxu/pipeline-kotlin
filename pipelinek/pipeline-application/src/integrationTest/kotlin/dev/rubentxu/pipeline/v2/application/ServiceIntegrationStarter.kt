package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.service.ServiceRefusal

/**
 * A starter that starts nothing.
 *
 * ## What it is for
 *
 * The rows of the file that holds it are about the questions the service
 * answers over a real socket, and a question that is not about a start does
 * not need a run. The door of the starts is a seam, and a seam can be a door
 * that is closed.
 */
internal class ServiceIntegrationStarter(
    private val outcome: ServiceStartOutcome = ServiceStartOutcome.Refused(
        ServiceRefusal.RunStartRefused("no-run-here", "this service starts no run"),
    ),
) : ServiceRunStarter {

    override fun start(request: ServiceStartRequest): ServiceStartOutcome = outcome
}
