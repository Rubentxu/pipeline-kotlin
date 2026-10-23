package dev.rubentxu.pipeline.v2.credentials.api

import dev.rubentxu.pipeline.v2.domain.CatchErrorBuildResult
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.events.ArtifactArchiveFailed
import dev.rubentxu.pipeline.v2.events.CatchErrorTriggered
import dev.rubentxu.pipeline.v2.events.CompilationFinished
import dev.rubentxu.pipeline.v2.events.DirectiveDenied
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.GateEvaluated
import dev.rubentxu.pipeline.v2.events.GitCheckoutCompleted
import dev.rubentxu.pipeline.v2.events.GitCheckoutFailed
import dev.rubentxu.pipeline.v2.events.GitCheckoutStarted
import dev.rubentxu.pipeline.v2.events.GitPollChanged
import dev.rubentxu.pipeline.v2.events.HtmlReportFailed
import dev.rubentxu.pipeline.v2.events.HtmlReportSkipped
import dev.rubentxu.pipeline.v2.events.HttpRequestFailed
import dev.rubentxu.pipeline.v2.events.HttpRequestStarted
import dev.rubentxu.pipeline.v2.events.HttpResponseReceived
import dev.rubentxu.pipeline.v2.events.HttpStatusRejected
import dev.rubentxu.pipeline.v2.events.InputAborted
import dev.rubentxu.pipeline.v2.events.InputDenied
import dev.rubentxu.pipeline.v2.events.InputProceed
import dev.rubentxu.pipeline.v2.events.InputRequested
import dev.rubentxu.pipeline.v2.events.LockAcquireFailed
import dev.rubentxu.pipeline.v2.events.LockRequested
import dev.rubentxu.pipeline.v2.events.LockSkipped
import dev.rubentxu.pipeline.v2.events.MilestoneAborted
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.SagaTerminal
import dev.rubentxu.pipeline.v2.events.StageMarkedUnstable
import dev.rubentxu.pipeline.v2.events.StageSkipped
import dev.rubentxu.pipeline.v2.events.StashFailed
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.scripting.CacheKey
import dev.rubentxu.pipeline.v2.scripting.ScriptDiagnosticSeverity
import dev.rubentxu.pipeline.v2.scripting.ScriptingDiagnostic
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The census of the free text the record of a run can carry.
 *
 * ## The guarantee
 * A registered secret does not survive in the record of a run. Every surface
 * that can hold a sentence — the content of an echo, the message of a failure,
 * the reason a stage was skipped, the URL of a remote — is masked before the
 * event reaches the sink that keeps it.
 *
 * ## Why this is a census and not a sample
 * The list of surfaces is a property of the event taxonomy, not of one step: a
 * kind that is missing from it is a kind whose secret walks into the console
 * and into the record. So the test walks the whole list, one row per kind,
 * and each row says the same thing — the secret is gone and the marker is
 * there.
 *
 * ## Why the check is generic
 * The assertion does not name a field: it looks at the whole event, because
 * the guarantee is about the event and not about one of its properties. The
 * text form of a data class carries every property, so a secret that hid in
 * any of them would show up there.
 *
 * ## What is deliberately NOT here
 * The typed value a step returns to the pipeline is not a surface of this
 * record: it is exact on purpose, and it reaches the pipeline in memory. This
 * census is about what the run KEEPS, not about what the pipeline RECEIVES.
 */
class RedactingEventSinkSurfacesTest {

    @Test
    @DisplayName("no free-text surface of the run's record keeps a registered secret")
    fun `no free-text surface keeps a registered secret`() {
        val registry = SecretPatternRegistry()
        registry.addSecretValue(SECRET)

        val leaks = surfaces.mapNotNull { (kind, event) ->
            val sink = CapturingSink()
            RedactingEventSink(sink, registry).append(event)

            val kept = sink.appended.single().toString()
            when {
                kept.contains(SECRET) -> "$kind conserva el secreto registrado"
                !kept.contains(RedactingEventSink.SCRUB_MARKER) -> "$kind no quedó enmascarado"
                else -> null
            }
        }

        // Every offending surface is reported at once: a census that stops at the
        // first failure would hide how wide the gap is.
        assertTrue(
            leaks.isEmpty(),
            "superficies del registro de la corrida sin limpiar: ${leaks.joinToString(", ")}",
        )
    }

    private companion object {
        const val SECRET = "e2e-fixture-secret-value"
        const val EVENT_ID = "e-1"
        const val RUN_ID = "run-redaction-census"
        val NOW: Instant = Instant.EPOCH

        /**
         * One row per kind of the taxonomy that carries free text, with the secret
         * sitting in that free text and nowhere else.
         */
        val surfaces: List<Pair<String, DomainEvent>> = listOf(
            "ArtifactArchiveFailed" to ArtifactArchiveFailed(
                EVENT_ID, RUN_ID, 0L, NOW, reason = SECRET,
            ),
            "CatchErrorTriggered" to CatchErrorTriggered(
                EVENT_ID, RUN_ID, 0L, NOW, "stage", null, CatchErrorBuildResult.Success, message = SECRET,
            ),
            "CompilationFinished" to CompilationFinished(
                EVENT_ID, RUN_ID, 0L, NOW, CacheKey("k", CacheKey.V1),
                listOf(ScriptingDiagnostic(ScriptDiagnosticSeverity.ERROR, SECRET, 1, 1, "p.pipeline.kts")),
            ),
            "DirectiveDenied" to DirectiveDenied(
                EVENT_ID, RUN_ID, 0L, NOW, 0, "stage", "key", reason = SECRET,
            ),
            "EchoOutputCaptured" to EchoOutputCaptured(
                EVENT_ID, RUN_ID, 0L, NOW, 0, content = SECRET,
            ),
            "GateEvaluated" to GateEvaluated(
                EVENT_ID, RUN_ID, 0L, NOW, 0, "stage", listOf("key"), true, reason = SECRET,
            ),
            "GitCheckoutCompleted" to GitCheckoutCompleted(
                EVENT_ID, RUN_ID, 0L, NOW, url = SECRET, "branch", "sha", "changelog.md", 0L,
            ),
            "GitCheckoutFailed" to GitCheckoutFailed(
                EVENT_ID, RUN_ID, 0L, NOW, url = SECRET, "branch", reason = SECRET, 1,
            ),
            "GitCheckoutStarted" to GitCheckoutStarted(
                EVENT_ID, RUN_ID, 0L, NOW, url = SECRET, "branch", null,
            ),
            "GitPollChanged" to GitPollChanged(
                EVENT_ID, RUN_ID, 0L, NOW, url = SECRET, "branch", null, "sha",
            ),
            "HtmlReportFailed" to HtmlReportFailed(
                EVENT_ID, RUN_ID, 0L, NOW, "stage", "report", "dir", FailureKind.INFRASTRUCTURE, reason = SECRET,
            ),
            "HtmlReportSkipped" to HtmlReportSkipped(
                EVENT_ID, RUN_ID, 0L, NOW, "stage", "report", "dir", reason = SECRET,
            ),
            "HttpRequestFailed" to HttpRequestFailed(
                EVENT_ID, RUN_ID, 0L, NOW, url = SECRET, reason = SECRET,
            ),
            "HttpRequestStarted" to HttpRequestStarted(
                EVENT_ID, RUN_ID, 0L, NOW, url = SECRET, "GET", 0,
            ),
            "HttpResponseReceived" to HttpResponseReceived(
                EVENT_ID, RUN_ID, 0L, NOW, url = SECRET, 200, 0L,
            ),
            "HttpStatusRejected" to HttpStatusRejected(
                EVENT_ID, RUN_ID, 0L, NOW, url = SECRET, 500, "2xx",
            ),
            "InputAborted" to InputAborted(
                EVENT_ID, RUN_ID, 0L, NOW, "human", message = SECRET,
            ),
            "InputDenied" to InputDenied(
                EVENT_ID, RUN_ID, 0L, NOW, reason = SECRET,
            ),
            "InputProceed" to InputProceed(
                EVENT_ID, RUN_ID, 0L, NOW, "human", message = SECRET,
            ),
            "InputRequested" to InputRequested(
                EVENT_ID, RUN_ID, 0L, NOW, message = SECRET, null, null,
            ),
            "LockAcquireFailed" to LockAcquireFailed(
                EVENT_ID, RUN_ID, 0L, NOW, "resource", reason = SECRET,
            ),
            "LockRequested" to LockRequested(
                EVENT_ID, RUN_ID, 0L, NOW, "resource", reason = SECRET, false,
            ),
            "LockSkipped" to LockSkipped(
                EVENT_ID, RUN_ID, 0L, NOW, "resource", reason = SECRET,
            ),
            "MilestoneAborted" to MilestoneAborted(
                EVENT_ID, RUN_ID, 0L, NOW, 0, reason = SECRET,
            ),
            "RunFinished" to RunFinished(
                EVENT_ID, RUN_ID, 0L, NOW, "SUCCESS",
                listOf(ScriptingDiagnostic(ScriptDiagnosticSeverity.WARNING, SECRET, 1, 1, "p.pipeline.kts")),
            ),
            "SagaTerminal" to SagaTerminal(
                EVENT_ID, RUN_ID, 0L, NOW, "saga", "COMPENSATED", reason = SECRET, emptyList(),
            ),
            "StageMarkedUnstable" to StageMarkedUnstable(
                EVENT_ID, RUN_ID, 0L, NOW, "stage", message = SECRET,
            ),
            "StageSkipped" to StageSkipped(
                EVENT_ID, RUN_ID, 0L, NOW, 0, "stage", reason = SECRET,
            ),
            "StashFailed" to StashFailed(
                EVENT_ID, RUN_ID, 0L, NOW, "stage", "name", "stash", reason = SECRET,
            ),
            "StepFailed" to StepFailed(
                EVENT_ID, RUN_ID, 0L, NOW, null, 0, "step", "type", FailureKind.SCRIPT, message = SECRET,
            ),
        )
    }

    /** The sink that keeps what the redactor let through. */
    private class CapturingSink : EventSink {
        val appended = mutableListOf<DomainEvent>()

        override fun append(event: DomainEvent) {
            appended += event
        }

        override fun eventsFor(runId: String): Sequence<DomainEvent> = appended.asSequence()
    }
}
