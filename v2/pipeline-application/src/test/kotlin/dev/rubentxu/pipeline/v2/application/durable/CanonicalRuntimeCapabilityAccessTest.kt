package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.EVENT_SINK_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.EXECUTION_LOCATION_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.WORKSPACE_IDENTITY_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.WorkspaceIdentity
import dev.rubentxu.pipeline.v2.domain.workspace.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspaceLease
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspaceOwnership
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import dev.rubentxu.pipeline.v2.application.FileLockCoordinator
import dev.rubentxu.pipeline.v2.application.LOCK_COORDINATION_CAPABILITY
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * CDE.3-d1: freezes the small, explicit capability bridge from a [CanonicalRuntimeContext] to a
 * [dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess]. The bridge exposes ONLY the capabilities
 * the canonical runtime declares and provides (EVENT_SINK today), never the raw runtime context, and
 * fails closed on any other lookup.
 */
@Timeout(10)
class CanonicalRuntimeCapabilityAccessTest {

    private fun runtime(eventSink: EventSink): CanonicalRuntimeContext = CanonicalRuntimeContext(
        opId = OpId("cap-bridge", 0, 0),
        runId = "cap-bridge",
        stageName = "build",
        stageIndex = 0,
        stepIndex = 0,
        shOptions = ShOptions.EMPTY,
        controlDirRoot = null,
        eventSink = eventSink,
    )

    private val unknownCapability = StepCapability("some.other.capability")

    @Test
    fun `bridge exposes the canonical capability set (event sink + shell + workspace ops + identities)`() {
        // CDE.3-d1 originally pinned EVENT_SINK as the single exposed capability. Since
        // then the bridge has accreted the canonical runtime capability set:
        //   - EVENT_SINK_CAPABILITY
        //   - SHELL_OPERATIONS_CAPABILITY  (LB-02 / A4)
        //   - WORKSPACE_OPERATIONS_CAPABILITY  (S2-A3 / G1)
        //   - STAGE_IDENTITY_CAPABILITY    (S2-A4 / G1)
        //   - PLATFORM_IDENTITY_CAPABILITY  (S2-A5 / G1)
        //   - WORKSPACE_IDENTITY_CAPABILITY (S2-A6 / G1)
        //   - TEMPORARY_WORKSPACE_OPERATIONS_CAPABILITY (S2-A6 / G3T post-correction)
        //   - EXECUTION_LOCATION_CAPABILITY (RP034-C / ADR-0100)
        //   - EXECUTION_LANE_CAPABILITY (RP6-A / WU-091) — the durable execution
        //     lane `core.lock` needs to decide re-entrancy. Added deliberately:
        //     without it the lock cannot tell a nested acquire from a sibling
        //     `parallel` branch. The set below must name it for the same reason it
        //     names every other entry: so the growth is visible, not silent.
        //   - LOCK_COORDINATION_CAPABILITY (RP6-A / WU-091) is deliberately ABSENT
        //     here: this context carries no control-dir anchor, and the lock
        //     coordinator is exposed ONLY when one exists (conditional, like the
        //     delete-dir workspace operations). See the lock-anchor test below.
        //
        // The bridge must expose EXACTLY this set — adding/removing a capability requires
        // updating both this test and the bridge together. The set must NEVER silently
        // grow or shrink across cycles.
        val expected = setOf(
            EVENT_SINK_CAPABILITY,
            dev.rubentxu.pipeline.v2.application.SHELL_OPERATIONS_CAPABILITY,
            dev.rubentxu.pipeline.v2.application.WORKSPACE_OPERATIONS_CAPABILITY,
            dev.rubentxu.pipeline.v2.application.STAGE_IDENTITY_CAPABILITY,
            dev.rubentxu.pipeline.v2.application.PLATFORM_IDENTITY_CAPABILITY,
            dev.rubentxu.pipeline.v2.application.WORKSPACE_IDENTITY_CAPABILITY,
            dev.rubentxu.pipeline.v2.application.TEMPORARY_WORKSPACE_OPERATIONS_CAPABILITY,
            dev.rubentxu.pipeline.v2.domain.step.EXECUTION_LOCATION_CAPABILITY,
            dev.rubentxu.pipeline.v2.application.EXECUTION_LANE_CAPABILITY,
            // EXECUTION_BUDGET_CAPABILITY (RP6-A / WU-091) is exposed
            // UNCONDITIONALLY with a null budget: "no scope budget" is a real value,
            // so admission of a Step that declares it is deterministic everywhere.
            dev.rubentxu.pipeline.v2.application.EXECUTION_BUDGET_CAPABILITY,
        )
        val access = CanonicalRuntimeCapabilityAccess(runtime(InMemoryEventStore()))
        assertEquals(expected, access.available())
    }

    @Test
    fun `lock coordination capability is exposed only with a control-dir anchor`(@TempDir tempDir: Path) {
        // RP6-A / WU-091 G4: the lock coordinator anchors its POSIX lock namespace
        // under the control root (`<controlDirRoot>/locks`). Without a control dir
        // the capability must be ABSENT — capability admission for core.lock then
        // fails closed before any handler runs, instead of inventing a namespace.
        val anchored = CanonicalRuntimeContext(
            opId = OpId("cap-bridge", 0, 0),
            runId = "cap-bridge",
            stageName = "build",
            stageIndex = 0,
            stepIndex = 0,
            shOptions = ShOptions.EMPTY,
            controlDirRoot = tempDir,
            eventSink = InMemoryEventStore(),
        )
        val access = CanonicalRuntimeCapabilityAccess(anchored)
        // The coordinator binds; the UAT lock scenarios prove the namespace is
        // rooted under `<controlDirRoot>/locks` behaviourally.
        access.get<FileLockCoordinator>(LOCK_COORDINATION_CAPABILITY)

        val anchorless = CanonicalRuntimeContext(
            opId = OpId("cap-bridge", 0, 0),
            runId = "cap-bridge",
            stageName = "build",
            stageIndex = 0,
            stepIndex = 0,
            shOptions = ShOptions.EMPTY,
            controlDirRoot = null,
            eventSink = InMemoryEventStore(),
        )
        val anchorlessAccess = CanonicalRuntimeCapabilityAccess(anchorless)
        assertEquals(
            false,
            LOCK_COORDINATION_CAPABILITY in anchorlessAccess.available(),
            "lock coordination must not be exposed without a control-dir anchor",
        )
        assertThrows(
            IllegalArgumentException::class.java,
        ) { anchorlessAccess.get<FileLockCoordinator>(LOCK_COORDINATION_CAPABILITY) }
    }

    @Test
    fun `workspace identity follows nested working directory`() {
        val workspaceRoot = Files.createTempDirectory("cap-bridge-root")
        val nested = Files.createDirectory(workspaceRoot.resolve("hello-world"))
        val context = runtime(InMemoryEventStore()).copy(
            shOptions = ShOptions.EMPTY.copy(
                workspaceRoot = workspaceRoot,
                workingDirectory = nested,
            ),
        )

        val identity = CanonicalRuntimeCapabilityAccess(context)
            .get<WorkspaceIdentity>(WORKSPACE_IDENTITY_CAPABILITY)
        assertEquals(nested, identity.workspaceRoot)
    }

    @Test
    fun `event sink capability lookup returns the runtime event sink`() {
        val store = InMemoryEventStore()
        val access = CanonicalRuntimeCapabilityAccess(runtime(store))
        val sink: EventSink = access.get(EVENT_SINK_CAPABILITY)
        assertSame(store, sink, "the bridge must hand back the runtime event sink, typed")
    }

    @Test
    fun `lookup of a capability the runtime does not supply fails closed`() {
        val access = CanonicalRuntimeCapabilityAccess(runtime(InMemoryEventStore()))
        assertThrows(IllegalArgumentException::class.java) {
            access.get<String>(unknownCapability)
        }
    }

    @Test
    fun `admission passes when required capabilities are supplied and fails otherwise`() {
        val access = CanonicalRuntimeCapabilityAccess(runtime(InMemoryEventStore()))
        val available = access.available()

        // Mirrors RegistryStepInvoker admission: handler must not start when required - available != empty.
        assertEquals(emptySet<StepCapability>(), setOf(EVENT_SINK_CAPABILITY) - available)
        assertEquals(setOf(unknownCapability), setOf(unknownCapability) - available)
    }

    @Nested
    @DisplayName("the execution location carries the stated ownership (RP034-Id)")
    inner class OwnershipTransport {

        private fun locationFor(ownership: WorkspaceOwnership): ExecutionLocation {
            val root = Files.createTempDirectory("cap-bridge-owned")
            val context = runtime(InMemoryEventStore()).copy(
                shOptions = ShOptions.EMPTY.copy(
                    workspaceRoot = root,
                    workspaceOwnership = ownership,
                ),
            )
            return CanonicalRuntimeCapabilityAccess(context)
                .get<ExecutionLocation>(EXECUTION_LOCATION_CAPABILITY)
        }

        @Test
        fun `a USER-owned root reaches the bridge as an Attached lease`() {
            // The regression this freezes: the bridge used to pin Managed for every
            // invocation, so the ADR-0102 guard never fired and deleteDir() erased a
            // user's project under the local-first default. Verified against the
            // installed distribution before the fix (RP034-Id).
            assertTrue(
                locationFor(WorkspaceOwnership.USER).workspace is WorkspaceLease.Attached,
                "a USER-owned root must arrive Attached so root deletion fails closed",
            )
        }

        @Test
        fun `a PIPELINEK-owned root reaches the bridge as a Managed lease`() {
            assertTrue(locationFor(WorkspaceOwnership.PIPELINEK).workspace is WorkspaceLease.Managed)
        }

        @Test
        fun `an unstated owner still fails closed to Managed`() {
            val context = runtime(InMemoryEventStore()).copy(
                shOptions = ShOptions.EMPTY.copy(workspaceOwnership = null),
            )
            val location = CanonicalRuntimeCapabilityAccess(context)
                .get<ExecutionLocation>(EXECUTION_LOCATION_CAPABILITY)
            assertTrue(location.workspace is WorkspaceLease.Managed)
        }
    }
}
