package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.SandboxConfigResolver
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.SandboxProfile
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import java.nio.file.Path

/**
 * D-032 (audit D-011 H3.d): scripted-frontend run wrapper extracted from Main.kt.
 *
 * Single classloader for the plugin JAR list (LB-02 / EP-6): parented on the
 * application classloader so the plugin sees the SDK contracts; installed as
 * the thread-context classloader during composition so ServiceLoader discovery
 * finds the contributed descriptors. The SAME jars are also given to the Kotlin
 * script compiler via ScriptDefinition.classpath — one flag, one classpath,
 * two consumers.
 */
internal fun runScriptedFrontend(
    entryPoint: dev.rubentxu.pipeline.v2.scripting.CompiledScriptedEntryPoint,
    runId: String,
    artifact: dev.rubentxu.pipeline.v2.scripting.ScriptedArtifactIdentity,
    stepRegistry: InMemoryStepRegistry,
    journal: OperationJournal,
    eventSink: EventSink,
    clock: Clock,
    controlDirRoot: Path,
    sandboxProfile: SandboxProfile,
): RunOutcome? {
    // Frontend runs on the SAME durable authority: identical journal instance,
    // identical composed registry, identical event sink and control root.
    val outcome = kotlinx.coroutines.runBlocking {
        dev.rubentxu.pipeline.v2.application.scripted.ScriptedFrontendRunner.run(
            entryPoint = entryPoint,
            runId = runId,
            expectedArtifact = artifact,
            registry = stepRegistry,
            journal = journal,
            eventSink = eventSink,
            clock = clock,
            shOptions = ShOptions(
                workspaceRoot = controlDirRoot.resolve("workspace"),
                captureStdout = false,
                timeoutMs = null,
                env = emptyMap(),
                sandbox = SandboxConfigResolver.resolve(sandboxProfile),
            ),
            controlDirRoot = controlDirRoot,
        )
    }
    return when (outcome) {
        // S4-D2: the aggregate is ALREADY a RunOutcome, produced by RunOutcomeReducer inside
        // `runBody`. This branch used to re-derive it from a StepOutcome with a second
        // hand-written Success/Unstable/Failure table — precedence written twice, with the copy
        // here able to disagree with the reducer that is supposed to own it. The reducer is now
        // the only place precedence is expressed, and `Unstable` reaches this line because it was
        // never dropped upstream rather than because this `when` happens to have a case for it.
        is dev.rubentxu.pipeline.v2.application.scripted.ScriptedFrontendRunner.Outcome.Completed ->
            outcome.aggregate
        is dev.rubentxu.pipeline.v2.application.scripted.ScriptedFrontendRunner.Outcome.ArtifactIncompatible -> {
            // Fail closed (compatibility law): never silently replay an incompatible artifact.
            System.err.println("Error: ${outcome.message}")
            System.exit(2)
            null // unreachable
        }
    }
}
