package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.runtime.explain.Completeness
import dev.rubentxu.pipeline.v2.runtime.explain.Diagnosis
import dev.rubentxu.pipeline.v2.runtime.explain.DiagnosisFacts
import dev.rubentxu.pipeline.v2.runtime.explain.EvidenceRef
import dev.rubentxu.pipeline.v2.runtime.explain.Fact
import dev.rubentxu.pipeline.v2.runtime.explain.Finding
import dev.rubentxu.pipeline.v2.runtime.explain.Inference
import dev.rubentxu.pipeline.v2.runtime.explain.LaneState
import dev.rubentxu.pipeline.v2.runtime.explain.classify

/**
 * Projects a run's persisted events into the structured facts a diagnosis
 * decides on. The projection extracts, it never judges: which terminal event
 * exists, which failure came first, which step opened without closing. The
 * judgment lives in the classifier, and both stay pure so a wrong diagnosis
 * is attributable to one of them.
 */
object RunExplainProjection {

    const val EVENT_LANE: String = "event-journal"

    fun project(events: List<DomainEvent>): Diagnosis = when {
        events.isEmpty() -> Diagnosis.Refused("unknown-run")
        else -> classify(factsOf(events), Completeness.EventsOnly)
    }

    internal fun factsOf(events: List<DomainEvent>): DiagnosisFacts {
        var runEnded: Fact.RunEnded? = null
        var firstFailure: Fact.StepBroke? = null
        var neverClosed: Inference.StepNeverClosed? = null
        var lastAtMs = 0L
        var lastSequence = -1L
        val openSteps = ArrayDeque<StepStarted>()

        for (event in events) {
            lastAtMs = event.occurredAt.toEpochMilli()
            lastSequence = event.sequence
            when (event) {
                is RunFinished -> if (runEnded == null) {
                    runEnded = Fact.RunEnded(event.outcome, lastAtMs, EvidenceRef(EVENT_LANE, event.sequence))
                }

                is StepFailed -> {
                    if (firstFailure == null) {
                        firstFailure = Fact.StepBroke(
                            failureKind = event.failureKind.name,
                            stepName = event.stepName,
                            message = event.message,
                            evidence = EvidenceRef(EVENT_LANE, event.sequence),
                        )
                    }
                    closeOpenStep(event.stageIndex, event.stepIndex, openSteps)
                }

                is StepStarted -> openSteps.addLast(event)
                is dev.rubentxu.pipeline.v2.events.StepFinished ->
                    closeOpenStep(event.stageIndex, event.stepIndex, openSteps)

                else -> {}
            }
        }

        // A step that opened and was never terminated: the evidence is its
        // own start event, because the absence that makes it dangling is not
        // something a sequence can point at.
        val open = openSteps.firstOrNull()
        if (open != null) {
            neverClosed = Inference.StepNeverClosed(open.stepName, EvidenceRef(EVENT_LANE, open.sequence))
        }
        return DiagnosisFacts(runEnded, firstFailure, neverClosed, lastAtMs, lastSequence)
    }

    /** A terminal step event may carry no stage index; ordinal alone then decides. */
    private fun closeOpenStep(stageIndex: Int?, stepIndex: Int, openSteps: ArrayDeque<StepStarted>) {
        val index = openSteps.indexOfFirst { open ->
            open.stepIndex == stepIndex && (stageIndex == null || open.stageIndex == stageIndex)
        }
        if (index >= 0) openSteps.removeAt(index)
    }
}

/**
 * Renders a diagnosis as stable operator-facing output. Text leads with the
 * status, lists each finding with its evidence, and closes with one short,
 * safe remedy per finding kind — advice about what to read next, never a
 * command that assumes facts the diagnosis does not have.
 */
object RunExplainReport {

    fun textLines(runId: String, diagnosis: Diagnosis): List<String> = when (diagnosis) {
        is Diagnosis.Refused -> listOf("explain run=$runId refused code=${diagnosis.code}")
        is Diagnosis.Explained -> {
            val head = "explain run=$runId status=${diagnosis.status}"
            val rows = diagnosis.findings.map { "  ${row(it)}" }
            val remedy = diagnosis.findings.map { "  remedy ${remedy(it)}" }.distinct()
            val lanes = "  completeness event-journal=${word(diagnosis.completeness.eventLane)} " +
                "output-plane=${word(diagnosis.completeness.outputLane)} " +
                "lease=${word(diagnosis.completeness.leaseLane)}"
            listOf(head) + rows + remedy + lanes
        }
    }

    fun jsonLines(runId: String, diagnosis: Diagnosis): List<String> = when (diagnosis) {
        is Diagnosis.Refused -> listOf(
            "{\"schema\":\"runtime.explain.v1\",\"runId\":\"$runId\",\"refused\":\"${diagnosis.code}\"}",
        )

        is Diagnosis.Explained -> {
            val findings = diagnosis.findings.joinToString(",\n") { f ->
                val kind = if (f is Fact) "fact" else "inference"
                """    {"kind":"$kind","code":"${f.code}","evidence":{"lane":"${f.evidence.lane}","sequence":${f.evidence.sequence}}${payload(f)}}"""
            }
            listOf(
                "{\"schema\":\"runtime.explain.v1\",\"runId\":\"$runId\",\"status\":\"${diagnosis.status}\",",
                " \"findings\":[",
                findings,
                " ]}",
            )
        }
    }

    private fun row(f: Finding): String = when (f) {
        is Fact.RunEnded -> "fact    run-ended outcome=${f.outcome} at=${f.atMs} (#${f.evidence.sequence})"
        is Fact.StepBroke ->
            "fact    step-broke kind=${f.failureKind} step=${f.stepName} message=\"${f.message}\" (#${f.evidence.sequence})"

        is Inference.RunStillOpen ->
            "inference run-still-open last-observed=${f.lastObservedAtMs} (#${f.evidence.sequence})"

        is Inference.StepNeverClosed ->
            "inference step-never-closed step=${f.stepName} (#${f.evidence.sequence})"
    }

    private fun remedy(f: Finding): String = when (f) {
        is Fact.RunEnded -> "the terminal event is the whole verdict; read the steps before it for the story"
        is Fact.StepBroke -> "inspect --failed lists this step's transcript; the failure kind names the owner"
        is Inference.RunStillOpen -> "an open run has no verdict yet; re-ask once it ends, or recover it deliberately"
        is Inference.StepNeverClosed -> "the step's start has no terminal event; treat its effects as undecided"
    }

    private fun payload(f: Finding): String = when (f) {
        is Fact.RunEnded -> ",\"outcome\":\"${f.outcome}\",\"atMs\":${f.atMs}"
        is Fact.StepBroke -> ",\"failureKind\":\"${f.failureKind}\",\"step\":\"${escape(f.stepName)}\",\"message\":\"${escape(f.message)}\""
        is Inference.RunStillOpen -> ",\"lastObservedAtMs\":${f.lastObservedAtMs}"
        is Inference.StepNeverClosed -> ",\"step\":\"${escape(f.stepName)}\""
    }

    private fun word(state: LaneState): String = when (state) {
        LaneState.Read -> "read"
        LaneState.NotConsulted -> "not-consulted"
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}
