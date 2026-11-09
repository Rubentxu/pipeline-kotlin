package example.serviceclient

import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeObservation
import dev.rubentxu.pipeline.v2.runtime.inspect.TerminalObservation
import dev.rubentxu.pipeline.v2.service.ServiceAnswer
import dev.rubentxu.pipeline.v2.service.ServiceClient
import java.nio.file.Path
import java.time.Duration

/**
 * An OUTSIDE client of the local service.
 *
 * ## What this proves
 *
 * That a consumer that resolves nothing but the published SDK can ask a
 * service about a run and get a TYPED answer: the same
 * [RuntimeObservation] the command line renders when it reads the same run
 * from the same store.
 *
 * ## What this does NOT do
 *
 * It does not re-implement the wire, and it does not re-implement the
 * command line's parser. The client answers a question about a run and
 * returns a value; the words this example prints are ITS OWN, because a
 * consumer renders the value it receives with the renderer it has.
 *
 * ## How to run it
 *
 * ```
 * PIPELINEK_SERVICE_TOKEN=<token> \
 * ./gradlew -p examples/example-service-client run \
 *     -PsdkRepo=pipelinek/build/sdk-repo -PsdkVersion=0.95.0 \
 *     --args="<socket> <runId>"
 * ```
 *
 * The token travels in the ENVIRONMENT and not in the arguments, because the
 * arguments of a process are visible to every other process of the machine.
 * It is the same token the service wrote next to its socket, and the same
 * variable the command line reads.
 */
fun main(args: Array<String>) {
    if (args.size < 2) {
        System.err.println("usage: example-service-client <socket> <runId>")
        kotlin.system.exitProcess(2)
    }
    val socket = Path.of(args[0])
    val runId = args[1]

    // The service refuses a client that presents no authorization, and this
    // example is a client: it has to present one, the same way any other
    // consumer of the SDK has to.
    val token = System.getenv("PIPELINEK_SERVICE_TOKEN")
    if (token.isNullOrBlank()) {
        System.err.println(
            "the service asks for an authorization: set PIPELINEK_SERVICE_TOKEN to the token of one of them",
        )
        kotlin.system.exitProcess(2)
    }

    val client = ServiceClient(socket, token, Duration.ofSeconds(10))

    when (val answer = client.runState(runId)) {
        is ServiceAnswer.Answered -> {
            println("run $runId: ${describe(answer.value)}")
        }

        is ServiceAnswer.Refused -> {
            System.err.println("the service refused the question: ${answer.refusal.kind}")
            kotlin.system.exitProcess(1)
        }

        is ServiceAnswer.Unreachable -> {
            System.err.println("there is no service at $socket: ${answer.detail}")
            kotlin.system.exitProcess(1)
        }

        is ServiceAnswer.Unreadable -> {
            System.err.println("the service did not answer in this protocol: ${answer.detail}")
            kotlin.system.exitProcess(1)
        }

        ServiceAnswer.TimedOut -> {
            System.err.println("the service did not answer within 10s")
            kotlin.system.exitProcess(1)
        }
    }
}

/**
 * The facts of [observation], in this example's own words.
 *
 * The SDK hands back a VALUE and not a sentence: what a consumer prints of
 * it is the consumer's own business, and two consumers may print the same
 * value differently.
 */
private fun describe(observation: RuntimeObservation): String = when (observation) {
    is RuntimeObservation.Running ->
        "running, attempt ${observation.attempt.value}, lease ${observation.leaseHolder?.ownerId ?: "none"}"

    is RuntimeObservation.Terminal ->
        "terminal, ${outcome(observation.terminal)}, at ${observation.terminalAtMs}"

    is RuntimeObservation.LiveButEmpty ->
        "in flight with no live evidence: ${observation.reason}"

    is RuntimeObservation.Unobservable ->
        "not observable: ${observation.reason}"
}

/**
 * The word a terminal carries.
 */
private fun outcome(terminal: TerminalObservation): String = when (terminal) {
    is TerminalObservation.Succeeded -> "succeeded"
    is TerminalObservation.Failed -> "failed (${terminal.failureKind})"
    is TerminalObservation.Unstable -> "unstable"
    is TerminalObservation.Aborted -> "aborted"
    is TerminalObservation.Cancelled -> "cancelled"
    is TerminalObservation.Other -> terminal.rawOutcome
}
