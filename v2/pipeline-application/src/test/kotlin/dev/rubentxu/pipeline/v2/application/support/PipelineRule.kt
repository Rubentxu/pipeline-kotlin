package dev.rubentxu.pipeline.v2.application.support

import dev.rubentxu.pipeline.v2.application.CoreStepRegistryFactory
import dev.rubentxu.pipeline.v2.application.DslCompiledPipelineCompiler
import dev.rubentxu.pipeline.v2.application.SystemClock
import dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator
import dev.rubentxu.pipeline.v2.application.durable.CanonicalNodeDispatcher
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeFailure
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeOutcome
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopePort
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import java.nio.file.Path
import kotlinx.coroutines.runBlocking

/**
 * SPIKE-017 / pipeline-test-rule: in-process JenkinsRule-style harness over the canonical
 * coordinator.
 *
 * Compiles a DSL [PipelineSpec] (built via the in-process `pipeline { }` builder, i.e. the
 * same PipelineSpec the declarative script text yields) with [DslCompiledPipelineCompiler],
 * then runs the compiled pipeline through [CanonicalDurableRunCoordinator] with in-memory
 * journal + cursor store + event store and a real (short) shell under `workDir`. Returns the
 * typed [RunOutcome] + ordered [DomainEvent] timeline + elapsed wall time.
 *
 * No installed binary, no `java -cp`, no 300s subprocess timeout. Pure test-support seam.
 */
object PipelineRule {

    /**
     * Result of an in-process run.
     *
     * @property outcome the typed run outcome
     * @property events ordered typed event timeline observed by the in-memory event store
     * @property elapsedMs wall-clock time of the coordinator run (excludes compile)
     */
    data class PipelineRun(
        val outcome: RunOutcome,
        val events: List<DomainEvent>,
        val elapsedMs: Long,
    )

    /**
     * Compiles and runs a pipeline spec in-process.
     *
     * @param spec DSL spec produced by the in-process `pipeline { }` builder
     * @param sourcePath logical source path (affects step identity)
     * @param sourceContent original script text (diagnostics only)
     * @param runIdValue stable run id for event correlation
     * @param workDir temp root holding control + workspace dirs for real shell steps
     */
    fun run(
        spec: PipelineSpec,
        sourcePath: String,
        sourceContent: String,
        runIdValue: String,
        workDir: Path,
    ): PipelineRun {
        val clock = SystemClock()
        val eventStore = InMemoryEventStore()
        val compiled: CompiledPipeline = DslCompiledPipelineCompiler.compile(
            spec = spec,
            sourcePath = sourcePath,
            sourceContent = sourceContent,
            pluginLockDigest = Digest("builtin"),
        )
        val journal = InMemoryOperationJournal(clock)
        val cursorStore = InMemoryReplayCursorStore(clock)
        val coordinator = CanonicalDurableRunCoordinator(
            dispatcher = CanonicalNodeDispatcher(),
            journal = journal,
            cursorStore = cursorStore,
            clock = clock,
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            eventSink = eventStore,
            credentialScopePort = noOpCredentialScopePort(),
            controlDirRoot = workDir.resolve("control"),
            shOptions = ShOptions(
                workspaceRoot = workDir.resolve("workspace"),
                captureStdout = false,
                timeoutMs = null,
                env = emptyMap(),
            ),
            // B1.2c3-S2.3: the test harness converges on the same core registry authority as production,
            // so the later flip to registry-routed echo is single-authority and deterministic in tests.
            stepRegistry = CoreStepRegistryFactory.registry(),
        )
        val runId = RunId(runIdValue)
        val started = System.nanoTime()
        val outcome = runBlocking { coordinator.run(compiled, runId) }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        return PipelineRun(
            outcome = outcome,
            events = eventStore.eventsFor(runIdValue).toList(),
            elapsedMs = elapsedMs,
        )
    }

    private fun noOpCredentialScopePort(): CredentialScopePort = CredentialScopePort { _, _ ->
        CredentialScopeOutcome.Unavailable(
            CredentialScopeFailure.StoreUnavailable("No credential store in in-process PipelineRule harness"),
        )
    }
}
