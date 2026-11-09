package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.service.ServiceAnswer
import dev.rubentxu.pipeline.v2.service.ServiceClient
import dev.rubentxu.pipeline.v2.service.ServiceRefusal
import java.io.PrintStream
import java.nio.file.Path
import java.time.Duration

/**
 * The remote path of `inspect --state`: ask a SERVICE instead of the local store.
 *
 * ## What this owns
 *
 * The conversation with the service, and the rendering of its answer. The
 * answer it renders is the same line the local path renders, because the
 * value that arrives is the same type: the run's state travels as a
 * [dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeObservation], and
 * [InspectionPresentation.renderRunState] renders it.
 *
 * ## What it does NOT own
 *
 * The wire, the codec and the client's vocabulary: those live in the
 * published SDK, and this verb is a consumer of them like any other.
 *
 * ## Why there is no local path behind this one
 *
 * `--endpoint` is the operator saying "ask THIS service". A refusal that
 * quietly answered from the local store would be an answer about a source
 * nobody named, and the operator would read it as the service's. So every
 * negative here ends the request: there is no fallback, and there is no
 * second attempt.
 *
 * ## The deadline
 *
 * The command line does not wait forever. The service is on this machine and
 * the question is one line, so a service that has not answered in
 * [DEADLINE_MILLIS] is a service that is not answering, and the operator is
 * told so instead of watching a hanging command.
 */
internal object ServiceEndpoint {

    /**
     * How long the command line waits for the service, in milliseconds.
     *
     * A NAMED bound and not an open wait: the request is one line, and a
     * service that cannot answer one line in ten seconds is not going to.
     */
    const val DEADLINE_MILLIS: Long = 10_000L

    /**
     * The state of [runId], read from the service at [endpoint].
     *
     * The answer goes to stdout and the refusal to stderr, like every other
     * answer and refusal of this CLI.
     */
    fun report(endpoint: String, runId: String, out: PrintStream, err: PrintStream): Int {
        val client = ServiceClient(
            endpoint = Path.of(endpoint),
            deadline = Duration.ofMillis(DEADLINE_MILLIS),
        )
        return when (val answer = client.runState(runId)) {
            is ServiceAnswer.Answered -> {
                out.println(InspectionPresentation.renderRunState(answer.value))
                CliExit.SUCCESS.code
            }

            is ServiceAnswer.Refused -> refuse(answer.refusal, runId, err)

            is ServiceAnswer.Unreachable -> {
                err.println("inspect: there is no service at '$endpoint': ${answer.detail}")
                CliExit.RUNTIME_NOT_AVAILABLE.code
            }

            is ServiceAnswer.Unreadable -> {
                err.println("inspect: the service did not answer in this protocol: ${answer.detail}")
                CliExit.INCOMPATIBLE_VERSION.code
            }

            ServiceAnswer.TimedOut -> {
                err.println("inspect: the service did not answer within ${DEADLINE_MILLIS / 1000}s")
                CliExit.READ_FAILURE.code
            }
        }
    }

    /**
     * The refusal of the service, rendered and answered with.
     *
     * A refusal that names the RUN is the same fact the local path refuses,
     * so it is rendered by the same renderer and carries the same exit code:
     * an operator who compares the two paths must not find two sentences and
     * two codes for one fact.
     *
     * A refusal that names the PROTOCOL has no local counterpart, and it is
     * answered by its own word.
     */
    private fun refuse(refusal: ServiceRefusal, runId: String, err: PrintStream): Int {
        val runState = ServiceOperations.runStateRefusalOf(refusal)
        if (runState != null) {
            err.println("inspect: ${InspectionPresentation.renderRunStateRefusal(runState, runId)}")
            return CliExit.INVALID_ARGUMENT.code
        }
        err.println("inspect: the service refused the request: ${refusal.kind}")
        return when (refusal) {
            ServiceRefusal.IncompatibleProtocol -> CliExit.INCOMPATIBLE_VERSION.code
            ServiceRefusal.TooManyConnections -> CliExit.RUNTIME_NOT_AVAILABLE.code
            else -> CliExit.INVALID_ARGUMENT.code
        }
    }
}
