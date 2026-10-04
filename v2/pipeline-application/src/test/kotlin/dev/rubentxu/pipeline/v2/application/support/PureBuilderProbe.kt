package dev.rubentxu.pipeline.v2.application.support

import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.durable.JsonEventLog
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
        /**
         * Steps that actually EXECUTED, excluding the one that carries the
         * rejection itself.
         *
         * S0-C1: a build-time rejection is reported as a FAILED run, and the
         * engine records the rejection as a step outcome so the failure is
         * observable. That bookkeeping step is not an EFFECT of the author's
         * script — the discarded builder must still have produced no checkout,
         * no process and no side effect. Counting every `StepStarted` would
         * therefore conflate "the failure was recorded" with "the pipeline did
         * work", which is exactly the dishonesty this gate exists to prevent.
         */
        val executedStepCount: Int = 0,
        /**
         * S3.0: the event kinds the run actually emitted, in order.
         *
         * The S0-C1 gate only needed a step count, which answers "did work
         * happen". It cannot answer "did the builder emit anything", because a
         * builder that emitted an event without emitting a step would be
         * invisible to a step count. The Semantic Conservation Law names events
         * as a separate thing from steps, so the probe reports them separately
         * and the witness classifies them.
         */
        val eventKinds: List<String> = emptyList(),
        /**
         * S3.0: the UNTRUNCATED `stdout + stderr` of the run.
         *
         * [summary] truncates its diagnostic at 600 characters for
         * readability, which is fine for a human reading a failure but not for
         * an assertion: the installed distribution emits a block of JVM
         * `sun.misc.Unsafe` warnings on stderr before any pipeline diagnostic,
         * so a truncated window can lose the very sentence the witness needs to
         * distinguish "refused for a missing capability" from "refused because
         * the plugin key is not registered". Both are exit!=0 with zero events,
         * so only the text separates them.
         */
        val diagnostics: String = "",
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
        // A step whose outcome is the rejection itself did not execute author
        // intent. A step that SUCCEEDED did: that is the real effect signal.
        val executedStepCount = events.count { e ->
            val n = e::class.simpleName
            n == "StepSucceeded" || n == "StepFinished" && e.toString().contains("\"outcome\":\"success\"")
        }
        // S3.0: hoisted out of the summary builder so the caller can classify
        // the event vocabulary, not just read a count of it.
        val kinds = events.mapNotNull { it::class.simpleName }
        val diagnostics = stdout + stderr
        val summary = buildString {
            append("exit=").append(exitCode)
            append(" steps=").append(stepCount)
            append(" events=").append(events.size)
            if (kinds.isNotEmpty()) append(" kinds=").append(kinds.joinToString(","))
            if (diagnostics.isNotBlank()) {
                append(" diag=").append(diagnostics.take(600).replace('\n', ' '))
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

        return Outcome(exitCode, rejected, stepCount, summary, executedStepCount, kinds, diagnostics)
    }
}
