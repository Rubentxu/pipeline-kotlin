package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * LFC-2E3-P / P3.0 — plugin-event extensibility protection (RED + guards).
 *
 * ## The architectural risk this exists to prevent
 *
 * `DomainEvent` is a closed sealed hierarchy that serialization and persistence machinery switches
 * over exhaustively. Adding `DomainEvent.TestReportPublished` (or `CoverageCalculated`, or
 * `ArtifactUploaded`) would force every future plugin to edit:
 *
 * ```text
 * DomainEvent.kt
 * JsonEventLog.kt
 * SqliteEventStore.kt
 * InMemoryEventStore.kt
 * identity/EnvelopeProjector.kt
 * identity/SequenceAssigner.kt
 * ```
 *
 * That would make event extensibility FALSE: the platform would claim "a new plugin needs zero
 * core changes" for Steps while requiring six core edits per plugin event. The whole point of P3 is
 * that core learns a plugin event **once**, through a single generic carrier, and never again.
 *
 * ## Rows
 *
 * 1. **RED** — core exposes one generic plugin-event carrier. Fails today, because none exists.
 *    P3.1 adds `DomainEvent.PluginEvent` and this row passes unchanged.
 * 2. **guard** — the core event catalog is frozen. Any change forces a deliberate update here, so
 *    a plugin-shaped case cannot slip in unnoticed.
 * 3. **guard** — no core event case is named after a plugin domain. This is the law with teeth:
 *    `CoverageCalculated` in core is rejected outright, not merely flagged by a count.
 * 4. **guard** — no core serialization/persistence file switches on a plugin-owned event type.
 */
@Timeout(30)
class Lfc2PluginEventExtensibilityFitnessTest {

    companion object {
        /**
         * Frozen catalog of core `DomainEvent` cases as of LFC-2E3-P/P3.0.
         *
         * Any addition or removal MUST be a deliberate, reviewed edit to this list. A new CORE
         * event (lifecycle, control-flow, runtime) legitimately lands here. A new PLUGIN event
         * belongs on the generic carrier instead, and row 3 rejects it by name.
         */
        val FROZEN_CORE_EVENT_CASES: Set<String> = setOf(
            "AgentResolved", "ArtifactArchived", "ArtifactArchiveFailed", "ArtifactEntry",
            "CatchErrorTriggered", "CompilationFinished", "CompilationStarted", "CredentialBound",
            "CredentialUnbound", "CredentialUsed", "DirDeleted", "DirEntered", "DirExited",
            "EchoOutputCaptured", "FileRead", "FileWritten", "GitCheckoutCompleted",
            "GitCheckoutFailed", "GitCheckoutStarted", "GitPollChanged", "MilestoneAborted",
            "MilestoneReached", "ParallelBranchFinished", "ParallelBranchStarted", "PwdResolved",
            "RetryAttemptFinished", "RetryAttemptStarted", "RunFinished", "RunStarted",
            "StageFinished", "StageMarkedUnstable", "StageStarted", "StepAdmissionObserved",
            "StepFailed", "StepFinished", "StepStarted", "TimeoutScheduled", "TimeoutTriggered",
            "TimestampsEntered", "TimestampsExited", "UnixDetected", "WaitUntilCompleted",
            "WaitUntilPolled", "WorkflowLoaded", "WsCleaned",
        )

        /**
         * Name fragments that identify a PLUGIN domain. A core event case containing any of these
         * means the architecture has been re-closed: that behaviour belongs on the generic carrier.
         */
        val PLUGIN_DOMAIN_MARKERS: List<String> = listOf(
            "Junit", "JUnit", "TestReport", "TestSuite", "TestFailure", "Coverage", "Jacoco",
            "Lcov", "ArtifactUpload", "Scm", "Http", "Container", "Uppercase", "Checksum",
            "PublishHtml", "Report",
        )
    }

    /**
     * The core event catalog, read from the sealed hierarchy's SOURCE rather than by reflection.
     *
     * `declaredClasses` on a Kotlin sealed interface returns a synthetic empty-named entry, so
     * reflection is unreliable here. Source scanning is also the idiom this repository already
     * uses for catalog and architecture fitness, and it names the exact declaration judged.
     */
    private fun coreEventCases(): Set<String> {
        val root = java.io.File(System.getProperty("user.dir"), "../..").canonicalFile
        val source = java.io.File(
            root,
            "v2/pipeline-events/src/main/kotlin/dev/rubentxu/pipeline/v2/events/DomainEvent.kt",
        ).readText()
        val pattern = Regex("""^data (?:class|object)\s+([A-Z][A-Za-z]*)\s*[(:]""", RegexOption.MULTILINE)
        return pattern.findAll(source)
            .map { it.groupValues[1] }
            .toSet()
    }

    // ─────────────── RED ───────────────

    @Test
    fun `RED - core exposes exactly ONE generic carrier for plugin-authored events`() {
        val carriers = coreEventCases().filter { it == "PluginEvent" }
        assertEquals(
            listOf("PluginEvent"),
            carriers,
            "core must expose ONE generic plugin-event carrier so that adding a plugin event type " +
                "never requires editing core. Without it, a plugin event forces six core edits " +
                "(DomainEvent, JsonEventLog, SqliteEventStore, InMemoryEventStore, " +
                "EnvelopeProjector, SequenceAssigner) and the platform's extensibility claim is " +
                "false for events. This row is the P3.1 acceptance criterion.",
        )
    }

    // ─────────────── guards ───────────────

    @Test
    fun `the core event catalog is frozen`() {
        assertEquals(
            FROZEN_CORE_EVENT_CASES,
            coreEventCases(),
            "The core DomainEvent catalog changed. If this added a CORE event (lifecycle, " +
                "control-flow, runtime) update the frozen set deliberately. If it added a " +
                "PLUGIN event, do NOT: route it through the generic carrier instead - see " +
                "AGENTS.md LOCAL EVENT TRANSPORT.",
        )
    }

    @Test
    fun `no core event case is named after a plugin domain`() {
        val violations = coreEventCases().filter { case ->
            PLUGIN_DOMAIN_MARKERS.any { marker -> case.contains(marker) }
        }
        assertTrue(
            violations.isEmpty(),
            "Core gained plugin-specific event case(s) $violations. This re-closes the architecture: " +
                "every future plugin would again need core edits. Plugin events MUST travel as " +
                "DomainEvent.PluginEvent with a plugin-owned eventType.",
        )
    }

    @Test
    fun `core serialization and persistence do not know plugin-owned event types`() {
        val root = java.io.File(System.getProperty("user.dir"), "../..").canonicalFile
        val machinery = listOf(
            "v2/pipeline-events/src/main/kotlin/dev/rubentxu/pipeline/v2/events/DomainEvent.kt",
            "v2/pipeline-events/src/main/kotlin/dev/rubentxu/pipeline/v2/events/JsonEventLog.kt",
            "v2/pipeline-events/src/main/kotlin/dev/rubentxu/pipeline/v2/events/SqliteEventStore.kt",
            "v2/pipeline-events/src/main/kotlin/dev/rubentxu/pipeline/v2/events/InMemoryEventStore.kt",
            "v2/pipeline-events/src/main/kotlin/dev/rubentxu/pipeline/v2/events/identity/EnvelopeProjector.kt",
            "v2/pipeline-events/src/main/kotlin/dev/rubentxu/pipeline/v2/events/identity/SequenceAssigner.kt",
        )
        val offenders = mutableListOf<String>()
        for (relative in machinery) {
            val file = java.io.File(root, relative)
            if (!file.exists()) continue
            val text = file.readText()
            for (marker in PLUGIN_DOMAIN_MARKERS) {
                // Only flag a NAME appearing as code, not in prose explaining this rule.
                if (Regex("\\b[A-Za-z]*$marker[A-Za-z]*\\s*(->|\\(|::)").containsMatchIn(text)) {
                    offenders += "${file.name}:$marker"
                }
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "Core event machinery references plugin-owned event names: $offenders. Serialization " +
                "and persistence must switch on CORE cases only; plugin events are carried " +
                "generically.",
        )
    }
}
