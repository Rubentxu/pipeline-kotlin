package dev.rubentxu.pipeline.v2.domain.isolation

import dev.rubentxu.pipeline.v2.domain.isolation.Admission.Allowed
import dev.rubentxu.pipeline.v2.domain.isolation.Admission.Denied
import dev.rubentxu.pipeline.v2.domain.isolation.DenialReason.MissingAttestation
import dev.rubentxu.pipeline.v2.domain.isolation.DenialReason.PolicyConflict
import dev.rubentxu.pipeline.v2.domain.isolation.DenialReason.UnsupportedCapability
import dev.rubentxu.pipeline.v2.domain.isolation.DenialReason.UntrustedPlugin
import dev.rubentxu.pipeline.v2.domain.isolation.FilesystemAccess.ApprovedReadonly
import dev.rubentxu.pipeline.v2.domain.isolation.FilesystemAccess.WorkspaceScoped
import dev.rubentxu.pipeline.v2.domain.isolation.IsolationAttestation.Provider
import dev.rubentxu.pipeline.v2.domain.isolation.IsolationAttestation.TrustedProfileOnly
import dev.rubentxu.pipeline.v2.domain.isolation.IsolationAttestation.Unprobeable
import dev.rubentxu.pipeline.v2.domain.isolation.IsolationCapability.CredentialIsolation
import dev.rubentxu.pipeline.v2.domain.isolation.IsolationCapability.FilesystemConfinement
import dev.rubentxu.pipeline.v2.domain.isolation.IsolationCapability.NetworkConfinement
import dev.rubentxu.pipeline.v2.domain.isolation.IsolationCapability.ResourceLimits
import dev.rubentxu.pipeline.v2.domain.isolation.NetworkAccess.Allowlisted
import dev.rubentxu.pipeline.v2.domain.isolation.NetworkAccess.Denied as NetworkDenied
import dev.rubentxu.pipeline.v2.domain.isolation.PluginAdmissionFact.Attested
import dev.rubentxu.pipeline.v2.domain.isolation.PluginAdmissionFact.NoPlugins
import dev.rubentxu.pipeline.v2.domain.isolation.PluginAdmissionFact.OutOfScope
import dev.rubentxu.pipeline.v2.domain.isolation.PluginAdmissionFact.Unattested
import dev.rubentxu.pipeline.v2.domain.isolation.ResourceBudget.Bounded
import dev.rubentxu.pipeline.v2.domain.isolation.ResourceBudget.Unbounded
import dev.rubentxu.pipeline.v2.domain.isolation.SecretGrant.None
import dev.rubentxu.pipeline.v2.domain.isolation.SecretGrant.Scoped
import dev.rubentxu.pipeline.v2.domain.isolation.TrustIntent.UntrustedIsolated
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

/**
 * The admission core's matrix.
 *
 * The guarantee under test: a run may only start when the host can prove every
 * capability the request demands, and a request that cannot be honoured is
 * refused with a reason that names what is missing. The observable capacity is
 * the verdict itself, so the cheapest instrument that can fail is a pure call
 * over the ADT — no filesystem, no process, no clock.
 *
 * Every row here is a case the contract names, and two of them exist to pin the
 * ORDER of the preflight: a change that moves a check earlier or later has to
 * turn a row red, and a matrix that only asserted the reasons would let the
 * order drift unnoticed.
 */
class AdmissionDeciderTest {

    private fun decide(
        requirements: ExecutionRequirements,
        attestation: IsolationAttestation = TrustedProfileOnly,
        plugins: PluginAdmissionFact = NoPlugins,
    ): Admission = AdmissionDecider.decide(
        AdmissionQuery(
            requirements = requirements,
            attestation = attestation,
            plugins = plugins,
        ),
    )

    private fun deniedReason(admission: Admission): DenialReason =
        assertInstanceOf<Denied>(admission, "expected a refusal, got $admission").reason

    @Nested
    inner class TheLegacyPosture {

        @Test
        fun `a trusted request that asks for nothing new is admitted`() {
            val admission = decide(ExecutionRequirements())

            assertInstanceOf<Allowed>(admission)
        }

        @Test
        fun `the admitted legacy run carries no confinement claim`() {
            val admission = decide(ExecutionRequirements())

            val grant = assertInstanceOf<Allowed>(admission).grant
            assertTrue(
                grant.proven.isEmpty(),
                "the trusted posture confines nothing and must not report a proven capability",
            )
        }

        @Test
        fun `LOCAL is not OS - the trusted posture does not confine the filesystem`() {
            val admission = decide(
                ExecutionRequirements(filesystem = ApprovedReadonly(listOf("/etc"))),
            )

            assertEquals(
                UnsupportedCapability(setOf(FilesystemConfinement)),
                deniedReason(admission),
            )
        }
    }

    @Nested
    inner class TheUntrustedPosture {

        @Test
        fun `an untrusted request without a backend is refused for a missing attestation`() {
            val admission = decide(
                ExecutionRequirements(trustIntent = UntrustedIsolated),
            )

            assertInstanceOf<MissingAttestation>(deniedReason(admission))
        }

        @Test
        fun `an untrusted request met by a full provider is admitted`() {
            val admission = decide(
                ExecutionRequirements(trustIntent = UntrustedIsolated),
                attestation = Provider(ALL_CAPABILITIES_PROVEN),
            )

            val grant = assertInstanceOf<Allowed>(admission).grant
            assertEquals(ALL_CAPABILITIES_PROVEN, grant.proven)
        }

        @Test
        fun `an untrusted request met by a partial provider lists every missing capability`() {
            val admission = decide(
                ExecutionRequirements(trustIntent = UntrustedIsolated),
                attestation = Provider(
                    listOf(
                        ProvenCapability(FilesystemConfinement, "read-only rootfs"),
                        ProvenCapability(NetworkConfinement, "no egress"),
                    ),
                ),
            )

            val missing = assertInstanceOf<UnsupportedCapability>(deniedReason(admission)).missing
            assertEquals(
                IsolationCapability.entries.toSet() - setOf(FilesystemConfinement, NetworkConfinement),
                missing,
                "the set of missing capabilities must be listed, not silenced",
            )
        }

        @Test
        fun `a provider that could not be probed is refused for a missing attestation`() {
            val admission = decide(
                ExecutionRequirements(trustIntent = UntrustedIsolated),
                attestation = Unprobeable("the launcher did not answer"),
            )

            assertInstanceOf<MissingAttestation>(deniedReason(admission))
        }
    }

    @Nested
    inner class TheDemandedDimensions {

        @Test
        fun `a partial filesystem request is refused for the confinement it lacks`() {
            val admission = decide(
                ExecutionRequirements(filesystem = ApprovedReadonly(listOf("/srv/data"))),
            )

            assertEquals(
                UnsupportedCapability(setOf(FilesystemConfinement)),
                deniedReason(admission),
            )
        }

        @Test
        fun `a partial egress request is refused for the confinement it lacks`() {
            val admission = decide(
                ExecutionRequirements(network = Allowlisted(listOf("api.example.test"))),
            )

            assertEquals(
                UnsupportedCapability(setOf(NetworkConfinement)),
                deniedReason(admission),
            )
        }

        @Test
        fun `a CPU budget is refused for the resource limits it lacks`() {
            val admission = decide(
                ExecutionRequirements(resources = Bounded(cpu = CpuLimit(2.0))),
            )

            assertEquals(
                UnsupportedCapability(setOf(ResourceLimits)),
                deniedReason(admission),
            )
        }

        @Test
        fun `a secret grant is refused for the credential isolation it lacks`() {
            val admission = decide(
                ExecutionRequirements(secrets = Scoped(listOf("registry-token"))),
            )

            assertEquals(
                UnsupportedCapability(setOf(CredentialIsolation)),
                deniedReason(admission),
            )
        }

        @Test
        fun `the legacy dimensions demand nothing`() {
            val required = AdmissionDecider.requiredCapabilities(
                ExecutionRequirements(
                    filesystem = WorkspaceScoped,
                    network = NetworkDenied,
                    resources = Unbounded,
                    secrets = None,
                ),
            )

            assertTrue(required.isEmpty(), "the legacy behaviour demands no capability")
        }
    }

    @Nested
    inner class TheIncoherentRequest {

        @Test
        fun `a blank path is refused as a conflict of the request itself`() {
            val admission = decide(
                ExecutionRequirements(filesystem = ApprovedReadonly(listOf(""))),
            )

            assertInstanceOf<PolicyConflict>(deniedReason(admission))
        }

        @Test
        fun `a blank destination is refused as a conflict of the request itself`() {
            val admission = decide(
                ExecutionRequirements(network = Allowlisted(listOf("  "))),
            )

            assertInstanceOf<PolicyConflict>(deniedReason(admission))
        }

        @Test
        fun `a blank credential identifier is refused as a conflict of the request itself`() {
            val admission = decide(
                ExecutionRequirements(secrets = Scoped(listOf(""))),
            )

            assertInstanceOf<PolicyConflict>(deniedReason(admission))
        }
    }

    @Nested
    inner class ThePreflightOrder {

        @Test
        fun `an unattested artifact is refused before the host is asked to prove anything`() {
            // The order this pins: the artifacts are a fact already in hand, so
            // they are answered BEFORE the capability proof. A core that asked
            // the host first would answer "no provider" here and hide the
            // artifact that is actually the problem.
            val admission = decide(
                ExecutionRequirements(trustIntent = UntrustedIsolated),
                attestation = TrustedProfileOnly,
                plugins = Unattested(listOf("example-plugin.jar")),
            )

            assertEquals(
                UntrustedPlugin(listOf("example-plugin.jar")),
                deniedReason(admission),
            )
        }

        @Test
        fun `an attested artifact does not stand in the way of an untrusted request`() {
            val admission = decide(
                ExecutionRequirements(trustIntent = UntrustedIsolated),
                attestation = Provider(ALL_CAPABILITIES_PROVEN),
                plugins = Attested(listOf("example-plugin.jar")),
            )

            assertInstanceOf<Allowed>(admission)
        }

        @Test
        fun `an unattested artifact does not stand in the way of the legacy posture`() {
            // The legacy posture admits plugins on their manifest, and it keeps
            // doing so: this row is what would go red if the artifact fact were
            // consulted without the trust intent.
            val admission = decide(
                ExecutionRequirements(),
                plugins = Unattested(listOf("example-plugin.jar")),
            )

            assertInstanceOf<Allowed>(admission)
        }

        @Test
        fun `a decision that is not about the artifacts is answered by the posture`() {
            // The product decides the admission of an execution BEFORE it admits
            // the artifacts, so the caller has no provenance to report. The core
            // must answer the posture and NOT invent an artifact verdict: a core
            // that reported the artifacts as unattested here would refuse a run
            // for a reason the operator never caused.
            val admission = decide(
                ExecutionRequirements(trustIntent = UntrustedIsolated),
                attestation = TrustedProfileOnly,
                plugins = OutOfScope,
            )

            assertInstanceOf<MissingAttestation>(deniedReason(admission))
        }

        @Test
        fun `a decision that is not about the artifacts admits the legacy posture`() {
            val admission = decide(
                ExecutionRequirements(),
                plugins = OutOfScope,
            )

            assertInstanceOf<Allowed>(admission)
        }
    }

    private companion object {
        val ALL_CAPABILITIES_PROVEN: List<ProvenCapability> = IsolationCapability.entries.map {
            ProvenCapability(it, "proven by the test fixture")
        }
    }
}
