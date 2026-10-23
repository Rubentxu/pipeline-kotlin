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
 * The health of an installation, and the exit code that reports it.
 */
object InstallationHealth {

    /** The exit code of an installation with no defect. */
    const val HEALTHY_EXIT: Int = 0

    /** The exit code of an installation with a defect. */
    const val DEFECTIVE_EXIT: Int = 2

    /**
     * The exit code the report answers with.
     *
     * The verdicts decide it and nothing else does. A reading that happens to
     * contain the word "NOT WRITABLE" is still a healthy reading when that is
     * what was measured: the sentence a report prints is not the judgement it
     * makes.
     */
    fun exitCode(checks: List<HealthCheck>): Int =
        if (checks.any { it.verdict == HealthVerdict.DEFECTIVE }) DEFECTIVE_EXIT else HEALTHY_EXIT

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
