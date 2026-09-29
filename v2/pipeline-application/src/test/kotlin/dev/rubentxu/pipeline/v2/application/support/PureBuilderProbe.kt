package dev.rubentxu.pipeline.v2.application.support

import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.JsonEventLog
import java.nio.file.Files
import java.nio.file.Path

/**
 * S0-C1 probe: runs a real `.pipeline.kts` through the installed distribution
 * binary and reports what the run OBSERVED — whether it was rejected, and how
 * many steps it emitted.
 *
 * The probe deliberately does NOT decide what the correct answer is. It reads
 * the manifest for the expected `ResultConsumption` and the caller asserts the
 * contract, so the rule stays expressed in the fitness test rather than being
 * hardcoded into a helper.
 */
object PureBuilderProbe {

    data class Outcome(
        val exitCode: Int,
        val rejected: Boolean,
        val stepCount: Int,
        val summary: String,
    ) {
        val admitted: Boolean get() = !rejected
    }

    private val appBin: Path by lazy { AppBinSupport.discover() }

    val isAvailable: Boolean get() = runCatching { appBin.toFile().canExecute() }.getOrDefault(false)

    fun compileAndRun(script: String): Outcome {
        val dir = Files.createTempDirectory("s0c1probe")
        val scriptPath = dir.resolve("probe.pipeline.kts")
        Files.writeString(scriptPath, script.trimIndent())
        val stdoutFile = dir.resolve("events.json")
        val process = ProcessBuilder(appBin.toString(), "run", scriptPath.toAbsolutePath().toString())
            .redirectOutput(ProcessBuilder.Redirect.to(stdoutFile.toFile()))
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()
        val exitCode = process.waitFor()
        val stdout = Files.readString(stdoutFile).trim()
        val stderr = runCatching { process.errorStream.bufferedReader().readText() }.getOrDefault("")

        val events: List<DomainEvent> = if (
            stdout.startsWith("[") && stdout.endsWith("]")
        ) {
            runCatching { JsonEventLog.decode(stdout) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        val stepCount = events.count { it::class.simpleName == "StepStarted" }
        val summary = buildString {
            append("exit=").append(exitCode)
            append(" steps=").append(stepCount)
            append(" events=").append(events.size)
            val kinds = events.mapNotNull { it::class.simpleName }
            if (kinds.isNotEmpty()) append(" kinds=").append(kinds.joinToString(","))
            val rejection = stdout + stderr
            if (rejection.isNotBlank()) {
                append(" diag=").append(rejection.take(600).replace('\n', ' '))
            }
        }

        // A rejection is observable either as a non-zero exit with no completed
        // run, or as a failed run. A clean exit-0 with an emitted timeline is
        // never a rejection.
        val runFinished = events.lastOrNull()
        val rejected = when {
            events.isEmpty() -> exitCode != 0 || stderr.isNotBlank()
            else -> exitCode != 0 || (runFinished is dev.rubentxu.pipeline.v2.events.RunFinished &&
                runFinished.outcome != "success")
        }

        return Outcome(exitCode, rejected, stepCount, summary)
    }
}
