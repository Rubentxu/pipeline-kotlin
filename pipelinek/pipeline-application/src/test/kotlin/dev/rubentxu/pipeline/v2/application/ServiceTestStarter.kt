package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.service.ServiceRefusal

/**
 * A starter that starts nothing.
 *
 * ## What it is for
 *
 * The rows of the tests around it are about the QUESTIONS the service
 * answers, and a question that is not about a start does not need a run. The
 * door of the starts is a seam, and a seam can be a door that is closed: the
 * refusal it answers with is a named one, so a test that asks for a start
 * over this door learns a name and not an exception.
 */
internal class ServiceTestStarter(
    private val outcome: ServiceStartOutcome = ServiceStartOutcome.Refused(
        ServiceRefusal.RunStartRefused("no-run-here", "this service starts no run"),
    ),
) : ServiceRunStarter {

    /** The requests the door was asked for. */
    val asked = mutableListOf<ServiceStartRequest>()

    override fun start(request: ServiceStartRequest): ServiceStartOutcome {
        asked += request
        return outcome
    }
}
