package dev.rubentxu.pipeline.v2.application

import java.nio.file.Path

/**
 * What an installation says about itself.
 *
 * A health report has two halves that used to be one: the reading, which is
 * written for a person, and the verdict, which is the exit code. The verdict
 * used to be READ OFF the reading — the report printed its lines and then
 * searched them for a marker to decide whether the installation was healthy.
 *
 * A report that reads its own sentences to decide can contradict itself: an
 * installation whose working directory was named `NOT WRITABLE` printed
 * "writable" on that very line and answered "broken". The verdict is a value
 * here, and the reading is its rendering. Nothing reads the text to decide
 * anything.
 */
enum class HealthVerdict {

    /** Nothing wrong was found. */
    HEALTHY,

    /** The installation cannot do its job as it stands. */
    DEFECTIVE,
}

/**
 * One thing the report says about the installation.
 *
 * @property name what is being reported, e.g. `workdir`.
 * @property reading what was observed, written for a person.
 * @property verdict what the observation MEANS. This is what the exit code
 *   answers with, and it is never derived from [reading].
 */
data class HealthCheck(
    val name: String,
    val reading: String,
    val verdict: HealthVerdict,
) {
    /** The line a person reads. */
    fun render(): String = "$name: $reading"
}

/**
 * The health of an installation, and the decision that reports it.
 */
object InstallationHealth {

    /**
     * The decision the report answers with.
     *
     * The verdicts decide it and nothing else does. A reading that happens to
     * contain the word "NOT WRITABLE" is still a healthy reading when that is
     * what was measured: the sentence a report prints is not the judgement it
     * makes.
     *
     * The decision is a [CliExit] and not a number, so the code the process
     * ends with is the one the taxonomy declares rather than a literal
     * written here: a defect in the installation is a request this build
     * cannot serve as it stands, which is the case the CLI already has.
     *
     * The case is the one whose code the doctor has always answered with, and
     * the code is observed: the E2E example reads it. A case that carried a
     * different number would change what an operator's script sees, so the
     * case was chosen to keep the number and drop the literal.
     */
    fun decision(checks: List<HealthCheck>): CliExit =
        if (checks.any { it.verdict == HealthVerdict.DEFECTIVE }) {
            CliExit.InvalidArgument(
                message = "the installation is not healthy",
                hint = "the checks above name the defect",
            )
        } else {
            CliExit.Success(message = "the installation is healthy")
        }

    /**
     * The check for the directory the product writes into.
     *
     * [writable] is what the probe found. The reading and the verdict come
     * from that one fact together, so the sentence the report prints and the
     * judgement it makes cannot drift apart.
     */
    fun workdir(directory: Path, writable: Boolean): HealthCheck =
        HealthCheck(
            name = "workdir",
            reading = "$directory (${if (writable) "writable" else "NOT WRITABLE"})",
            verdict = if (writable) HealthVerdict.HEALTHY else HealthVerdict.DEFECTIVE,
        )

    /**
     * A check that only reports a fact.
     *
     * The product has nothing to judge about these: the report says which JDK
     * it runs on and which operating system it found, and a fact that is not
     * a defect carries the verdict that says so.
     */
    fun fact(name: String, value: String): HealthCheck =
        HealthCheck(name = name, reading = value, verdict = HealthVerdict.HEALTHY)
}
