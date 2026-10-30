package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CanonicalRunPolicy
import dev.rubentxu.pipeline.v2.application.RuntimeWorkspaceTransport
import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.step.AllowAll
import dev.rubentxu.pipeline.v2.domain.step.DenyAll
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspaceOwnership
import dev.rubentxu.pipeline.v2.output.OutputRetentionPort
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.SandboxProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant

/**
 * The two facts a run DERIVES from the policy it was given: its shell transport and its output
 * retention.
 *
 * ## The guarantee
 *
 * A run's transport and its retention are **consequences** of the policy, not values a caller may
 * choose. Before this grouping they were constructor parameters, and a caller could therefore
 * hand the run:
 *
 * - a workspace root that was not the run's own (a wrong path), or
 * - a retention policy that discarded what the product keeps (a wrong lifetime).
 *
 * Neither is a capability the caller legitimately owns. So both are computed, and this instrument
 * observes the computation: for a given policy, the transport and the retention are exactly these
 * values and no others.
 *
 * ## The capacity this serves
 *
 * A caller cannot contradict the run's transport or its retention by accident, because there is no
 * argument with which to try. A reviewer reads the consequence in one place instead of
 * reconstructing it from a constructor call.
 *
 * ## Why this instrument
 *
 * The defect was in a COMPOSITION, so the instrument is a composition: the derivations are
 * exercised with a policy whose fields are named, and the rows observe the values that come out.
 * Nothing here touches the disk, a process, the network or the clock: the control root is a path
 * that does not exist, and the clock is fixed. The retention row hands the derivation a supplier
 * that THROWS if it is ever resolved, so "the store is not reached" is observed rather than
 * assumed.
 *
 * ## What this instrument cannot see
 *
 * It does not observe the derivation THROUGH a run: the transport of a live run is the transport of
 * its stages, and the retention of a live run happens in the coordinator's `finally`. It observes
 * the derivation itself, which is where the guarantee lives.
 */
class CanonicalRunDerivationsTest {

    @Test
    @DisplayName("the shell transport runs in the run's own workspace, or in the control root's")
    fun theShellTransportRunsInTheRunsOwnWorkspaceOrInTheControlRoots() {
        val base = Path.of("/run-workspace")

        val withBase = canonicalShellOptions(policy(workspaceBase = base))

        assertEquals(
            base,
            withBase.workspaceRoot,
            "with a workspace base the transport must run in THAT root, not in a directory under it",
        )

        val withoutBase = canonicalShellOptions(policy(workspaceBase = null))

        assertEquals(
            CONTROL_ROOT.resolve("workspace"),
            withoutBase.workspaceRoot,
            "without a workspace base the transport must fall back to the control root's workspace",
        )
    }

    @Test
    @DisplayName("the shell transport carries the policy's sandbox, owner and egress")
    fun theShellTransportCarriesThePolicySandboxOwnerAndEgress() {
        val denied = canonicalShellOptions(
            policy(
                sandboxProfile = SandboxProfile.LOCAL,
                ownership = WorkspaceOwnership.USER,
                allowNetwork = false,
            ),
        )

        assertEquals(
            SandboxProfile.LOCAL,
            denied.sandbox.profile,
            "the sandbox profile is the policy's, not one read from the ambient environment",
        )
        assertEquals(
            WorkspaceOwnership.USER,
            denied.workspaceOwnership,
            "the workspace owner is the policy's, so the destructive guard reads the real owner",
        )
        assertSame(
            DenyAll,
            denied.networkEgress,
            "a run that was not asked for the network must not reach one",
        )

        val allowed = canonicalShellOptions(policy(allowNetwork = true))

        assertSame(
            AllowAll,
            allowed.networkEgress,
            "a run that WAS asked for the network must carry that permission",
        )
    }

    @Test
    @DisplayName("the derived retention authorises no prune and never reaches the store")
    fun theDerivedRetentionAuthorisesNoPruneAndNeverReachesTheStore() {
        val store: UnreachableOutputStore = {
            throw IllegalStateException(
                "the output store must not be resolved: this policy authorises no release",
            )
        }
        val retention = canonicalOutputRetention(store)

        assertNull(
            retention.intentFor(RunId("run-1")),
            "an explicit-release-only policy authorises NO prune, not even at a terminal state",
        )
        assertEquals(
            RunOutputDisposition.Retained,
            retention.onRunTerminal(RunId("run-1")),
            "the run reached its terminal state and its output was kept, without resolving the store",
        )
    }

    /**
     * The policy the derivations read.
     *
     * Every field is named, so a row says which policy it exercised. The control root points at a
     * directory that does NOT exist: nothing in these rows may write to it, and a write would fail
     * the row rather than pass unnoticed.
     */
    private fun policy(
        workspaceBase: Path? = null,
        ownership: WorkspaceOwnership = WorkspaceOwnership.PIPELINEK,
        sandboxProfile: SandboxProfile = SandboxProfile.NONE,
        allowNetwork: Boolean = false,
    ): CanonicalRunPolicy = CanonicalRunPolicy(
        clock = FixedClock,
        effectReplayPolicy = DefaultEffectReplayPolicy(),
        controlDirRoot = CONTROL_ROOT,
        sandboxProfile = sandboxProfile,
        workspace = RuntimeWorkspaceTransport(base = workspaceBase, ownership = ownership),
        credentialProvider = null,
        withCredentialsExecutor = null,
        allowNetwork = allowNetwork,
        secretPatternRegistry = SecretPatternRegistry(),
    )

    /** A clock that does not move, so nothing in these rows depends on the wall clock. */
    private object FixedClock : Clock {
        override fun now(): Instant = Instant.EPOCH
    }

    private companion object {
        val CONTROL_ROOT: Path = Path.of("/nonexistent-control-root")
    }
}

/** The supplier type the derivation takes, named here so the failing row reads as a type error. */
private typealias UnreachableOutputStore = () -> OutputRetentionPort
