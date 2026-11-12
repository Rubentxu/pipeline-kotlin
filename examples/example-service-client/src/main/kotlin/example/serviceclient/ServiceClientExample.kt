package example.serviceclient

import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeObservation
import dev.rubentxu.pipeline.v2.runtime.inspect.TerminalObservation
import dev.rubentxu.pipeline.v2.service.ServiceAnswer
import dev.rubentxu.pipeline.v2.service.ServiceClient
import dev.rubentxu.pipeline.v2.service.ServiceProtocol
import dev.rubentxu.pipeline.v2.service.ServiceRefusal
import java.nio.file.Path
import java.time.Duration
import kotlin.system.exitProcess

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
 * ## How it negotiates
 *
 * The first question is the version. A service of the same protocol answers
 * it with its protocol and its build; a service of another protocol refuses
 * it with `incompatible-protocol`, by name, before any question about a run. A
 * service of this protocol that is a build without a question this SDK asks
 * refuses that question with `unknown-op`. Each negative names what the
 * operator does next, and none of them is read as an answer.
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
 * From the root of the repository, unpack the published SDK and resolve it:
 *
 * ```
 * ./pipelinek/gradlew -p pipelinek unpackPublishedSdk
 * PIPELINEK_SERVICE_TOKEN=<token> \
 * ./pipelinek/gradlew -p examples/example-service-client run \
 *     -PsdkRepo="$PWD/pipelinek/build/sdk-repo-published" -PsdkVersion=0.97.0 \
 *     --args="<socket> <runId>"
 * ```
 *
 * The repository is an ABSOLUTE path: a relative one is read from this
 * example's directory, and a version Maven Central already carries then
 * resolves from Central without a word.
 *
 * The token travels in the ENVIRONMENT and not in the arguments, because the
 * arguments of a process are visible to every other process of the machine.
 * It is the same token the service wrote next to its socket, and the same
 * variable the command line reads.
 */
fun main(args: Array<String>) {
    if (args.size < 2) {
        System.err.println("usage: example-service-client <socket> <runId>")
        exitProcess(2)
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
        exitProcess(2)
    }

    val client = ServiceClient(socket, token, Duration.ofSeconds(10))

    val version = client.version()
    if (version !is ServiceAnswer.Answered) {
        System.err.println(negative(version, socket))
        exitProcess(1)
    }
    println("service ${version.value.productVersion}, protocol ${version.value.schema}")

    val state = client.runState(runId)
    if (state !is ServiceAnswer.Answered) {
        System.err.println(negative(state, socket))
        exitProcess(1)
    }
    println("run $runId: ${describe(state.value)}")
}

/**
 * What [answer], which is not an answer, tells the operator.
 */
private fun negative(answer: ServiceAnswer<*>, socket: Path): String = when (answer) {
    is ServiceAnswer.Answered -> error("an answer is not a negative")
    is ServiceAnswer.Refused -> refused(answer.refusal)
    is ServiceAnswer.Unreachable -> "there is no service at $socket: ${answer.detail}"
    is ServiceAnswer.Unreadable -> "the service did not answer in this protocol: ${answer.detail}"
    ServiceAnswer.TimedOut -> "the service did not answer within 10s"
}

/**
 * What [refusal] tells the operator to do.
 *
 * The refusals of the negotiation get words of their own, because each one
 * sends the operator somewhere else; any other refusal is reported by the
 * name the service gave it.
 */
private fun refused(refusal: ServiceRefusal): String = when (refusal) {
    ServiceRefusal.IncompatibleProtocol ->
        "the service speaks another protocol than ${ServiceProtocol.SCHEMA}: use the SDK of the service's build"

    ServiceRefusal.UnknownOp ->
        "the service is a build without this question: use the SDK of the service's build"

    is ServiceRefusal.UnknownRun -> "the service knows no run ${refusal.runId}"

    else -> "the service refused the question: ${refusal.kind}"
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
