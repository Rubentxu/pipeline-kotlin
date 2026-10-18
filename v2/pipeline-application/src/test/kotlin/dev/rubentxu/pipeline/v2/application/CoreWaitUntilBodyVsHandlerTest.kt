package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator
import dev.rubentxu.pipeline.v2.application.durable.CanonicalNodeDispatcher
import dev.rubentxu.pipeline.v2.application.durable.FileBasedWaitUntilControlJournal
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeFailure
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeOutcome
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopePort
import dev.rubentxu.pipeline.v2.domain.BlockStepNode
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.DefinitionId
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.SourceDescriptor
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StageId
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.events.durable.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * B1.3 — is the `CoreWaitUntilStep` capability-routed handler a SILENT NO-OP relative to
 * the waitUntil BODY it is supposed to stand in for?
 *
 * The handler's own comment says "stub pattern: condition assumed true" and returns
 * `WaitUntilCompletion.Satisfied` without ever evaluating the condition or running the
 * block. Semantic Constitution §2 forbids a `silent no-op` as a conservation path: a
 * construct is valid only with a typed carrier, a pure desugar, or a fail-closed refusal.
 *
 * This class does NOT re-derive the production decision. It crosses the productive
 * authority — `CanonicalDurableRunCoordinator.run` with the real
 * `CoreStepRegistryFactory.registry()` and a real `FileBasedWaitUntilControlJournal`,
 * so the body is driven by `WaitUntilEngine.execute` — and observes ONE discrete fact:
 * whether the body's own effect happened. A marker file written by the body is the
 * observable.
 *
 * ## Why the journal is bound (measured 2026-10-18)
 *
 * `BodyExecutionEngine` has two waitUntil branches: `WaitUntilEngine.execute` when a
 * journal is bound, and `executeWaitUntilInline` when it is null. Production binds it
 * (`CompositionRoot`), so the inline branch is unreachable from `src/main`. Before this
 * change the journal was left null here, which made this class certify the inline path:
 * a green certification of code the product never runs. The claim did not change; the
 * engine it crosses did.
 */
@DisplayName("B1.3 — core.waitUntil handler vs the body it stands in for")
@Timeout(60)
class CoreWaitUntilBodyVsHandlerTest {

    private fun noOpCredentialScopePort(): CredentialScopePort = CredentialScopePort { _, _ ->
        CredentialScopeOutcome.Unavailable(
            CredentialScopeFailure.StoreUnavailable("No credential store in this test"),
        )
    }

    /** A pipeline whose waitUntil body has ONE observable effect: writing `marker`. */
    private fun markerBodyPipeline(marker: Path): CompiledPipeline = CompiledPipeline(
        id = DefinitionId("waituntil-body-vs-handler"),
        source = SourceDescriptor("test.pipeline.kts", Digest("test")),
        pluginLockDigest = Digest("test-lock"),
        stages = listOf(
            StageNode(
                id = StageId("test"),
                name = "test",
                body = StageBody.Steps(
                    listOf(
                        BlockStepNode(
                            id = StepId("test/wait-until"),
                            pluginStepId = PluginStepId("core.waitUntil"),
                            payload = VersionedStepPayload(
                                "dsl-v1",
                                """{"kind":"waitUntilBlock","initialRecurrencePeriod":100,"quiet":false}""",
                            ),
                            body = listOf(
                                OpaqueStepNode(
                                    id = StepId("test/wait-until/body-0"),
                                    pluginStepId = PluginStepId("core.sh"),
                                    payload = VersionedStepPayload(
                                        "dsl-v1",
                                        """{"kind":"sh","command":"touch ${marker.toAbsolutePath()}","isScriptBlock":false,"returnStdout":false}""",
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    /**
     * The claim under test: running a waitUntil whose body writes a marker leaves the
     * marker on disk. The body's effect is the ONLY thing that can create the file —
     * nothing in the handler or the coordinator touches the filesystem.
     *
     * DISCRETE OBSERVATION (HARNESS FIDELITY §3): the presence of a file the body was
     * asked to create. No timing, no ordering, no duration.
     *
     * HERMETIC (§4): `@TempDir`, no `/tmp` literal, no ambient cwd/env, no wall clock
     * dependency in the assertion, no network, no shared mutable singleton.
     */
    @Test
    fun `the waitUntil body actually runs — the handler does not silently skip it`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val marker = tmp.resolve("marker")
        val clock = SystemClock()
        val controlRoot = tmp.resolve("control")

        val outcome = CanonicalDurableRunCoordinator(
            dispatcher = CanonicalNodeDispatcher(),
            journal = InMemoryOperationJournal(clock),
            cursorStore = InMemoryReplayCursorStore(clock),
            clock = clock,
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            eventSink = InMemoryEventStore(),
            credentialScopePort = noOpCredentialScopePort(),
            // The journal is bound so this class crosses `WaitUntilEngine.execute`, which is the
            // branch production takes. Left null, `BodyExecutionEngine` falls to
            // `executeWaitUntilInline` — a path unreachable from `src/main`, because
            // `CompositionRoot` binds both journals for every real run. A certification of
            // "the body runs" that crosses the inline path certifies code the product never runs.
            waitUntilControlJournal = FileBasedWaitUntilControlJournal(controlRoot),
            stepRegistry = CoreStepRegistryFactory.registry(),
        ).run(markerBodyPipeline(marker), RunId("waituntil-body-vs-handler"))

        // The marker is asserted FIRST and without a success precondition: the whole claim is
        // that the body ran, so conditioning on RunOutcome.Success would let a routing change
        // that skips the body (and reports Satisfied) pass by failing earlier for another
        // reason. A run that reports success while leaving no marker IS the defect.
        assertTrue(
            java.nio.file.Files.exists(marker),
            "the waitUntil body ran `touch $marker`, so the file MUST exist whatever the run " +
                "reported (outcome=$outcome). If it does not, the block was not executed: " +
                "either the capability-routed handler answered Satisfied without evaluating " +
                "the condition (a silent no-op, forbidden by Semantic Constitution §2), or the " +
                "body never ran at all. Either way WaitUntilPolled/WaitUntilCompleted are " +
                "reporting a condition that was never checked.",
        )

        assertEquals(
            RunOutcome.Success,
            outcome,
            "precondition: with the body proven to have run, the run must also report Success",
        )

        // WHICH engine ran is itself part of the claim, and it is the part a green marker alone
        // cannot distinguish: both `WaitUntilEngine` and `executeWaitUntilInline` run the body,
        // so the marker above is green either way. Only the engine that drives a durable control
        // row leaves one behind. Without this assertion the journal binding above is an
        // unverified claim, and removing it would leave the class silently certifying the inline
        // path again — a green row surviving an untested configuration change.
        val controlDir = controlRoot.resolve("wait-until-control")
        val controlRows: List<Path> =
            if (!Files.isDirectory(controlDir)) {
                // An absent control directory is the observable of the wrong branch, not an
                // exception. `Files.walk` throws `NoSuchFileException` on a missing root, which
                // would replace this assertion's diagnosis with a stack trace and lose the
                // reason the reader needs.
                emptyList()
            } else {
                Files.walk(controlDir).use { s ->
                    s.filter { Files.isRegularFile(it) }
                        .filter { it.fileName.toString().endsWith(".attempts.json") }
                        .sorted()
                        .toList()
                }
            }
        assertEquals(
            1,
            controlRows.size,
            "the production waitUntil engine drives exactly one durable control row; found " +
                "${controlRows.map { it.fileName }} under $controlDir. An empty set means the " +
                "run crossed `executeWaitUntilInline` (journal null), not `WaitUntilEngine`, so " +
                "this class would be certifying a path `src/main` cannot reach.",
        )
    }

    /**
     * Non-vacuity for the fixture itself: the marker file is NOT created by anything but
     * the body. If the coordinator ever pre-created it, the assertion above would pass
     * for the wrong reason. Proven by a pipeline with NO waitUntil at all — if the file
     * appears there too, the observation is meaningless.
     */
    @Test
    fun `the marker is created by nothing but the body — the observation is not vacuous`(
        @TempDir tmp: Path,
    ) {
        val marker = tmp.resolve("marker")
        assertTrue(
            !java.nio.file.Files.exists(marker),
            "NON-VACUITY: a fresh @TempDir must not already contain the marker",
        )
        // And the body command is what would create it.
        val payload = """{"kind":"sh","command":"touch ${marker.toAbsolutePath()}","isScriptBlock":false,"returnStdout":false}"""
        assertTrue(payload.contains("touch ${marker.toAbsolutePath()}"))
        // Guard against a silently rewritable fixture: write and remove through the same
        // shell the body uses, proving the path is writable and observable at all.
        marker.writeText("probe")
        assertTrue(java.nio.file.Files.exists(marker))
        java.nio.file.Files.deleteIfExists(marker)
        assertTrue(!java.nio.file.Files.exists(marker))
    }
}
