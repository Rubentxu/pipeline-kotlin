package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.ParameterSpec
import dev.rubentxu.pipeline.v2.dsl.RunInputs
import dev.rubentxu.pipeline.v2.dsl.RunInputsOutcome

/**
 * The parameters a run was started with, checked against the declaration the compilation
 * produced.
 *
 * ## Where the values come from
 *
 * From ONE of two places, and never from both: the `--param` options the operator wrote, or
 * the snapshot the service wrote for the child it launched. The snapshot is the way the
 * socket's values reach a run, and the options are the way a local operator delivers them.
 * A request that carries both is refused, because a run whose values came from two places
 * has no single answer to "what were the parameters of this run".
 *
 * ## Why the check happens HERE
 *
 * Because the declaration is a product of the COMPILATION, and the compilation is what
 * produces it: the run is the first moment at which the product knows what the script
 * declares, and it is the first moment at which a value can be checked against it.
 *
 * ## Why a refusal is the CLI's own
 *
 * Because a value that does not fit the declaration is an argument the product did not
 * understand, and the exit code of an argument that was not understood is 2 — the same code
 * the configuration's refusal uses.
 *
 * ## What the values are NOT
 *
 * They are not the environment of the steps, and they are not published in the run's own
 * facts. The body of a pipeline does not read them yet: reading a value that is only known
 * at run time needs a seam the DSL does not have today, and that seam is another slice.
 */
internal object RunParameters {

    /**
     * The parameters of the run, or the refusal that there are none.
     *
     * The [declaration] is the one the compilation produced: the truth, and not the contract
     * a client may have carried.
     */
    fun of(config: CliFlags, declaration: List<ParameterSpec>): RunInputs {
        val fromSnapshot = RunInputsReader.read(config.runInputsPath)
        val fromOptions = config.parameters
        if (fromSnapshot.isNotEmpty() && fromOptions.isNotEmpty()) {
            throw CommandRefused(
                CliExit.InvalidArgument(
                    message = "the run was given parameters twice: a snapshot and an option. " +
                        "The values of a run come from one place.",
                ),
            )
        }
        val delivered = if (fromSnapshot.isNotEmpty()) fromSnapshot else fromOptions
        return when (val outcome = RunInputs.of(declaration, delivered)) {
            is RunInputsOutcome.Accepted -> outcome.inputs
            is RunInputsOutcome.Refused -> throw CommandRefused(
                CliExit.InvalidArgument(
                    message = "the parameters of the run are refused: ${outcome.refusal.reason} " +
                        "(${outcome.refusal.detail})",
                ),
            )
        }
    }
}
