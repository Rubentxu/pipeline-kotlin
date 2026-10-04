package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeCapabilityAccess
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext
import dev.rubentxu.pipeline.v2.domain.step.NETWORK_EGRESS_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.CompositeCapabilityContributor
import dev.rubentxu.pipeline.v2.domain.step.AllowAll
import dev.rubentxu.pipeline.v2.domain.step.DenyAll
import dev.rubentxu.pipeline.v2.domain.step.EgressRule
import dev.rubentxu.pipeline.v2.domain.step.NetworkEgressGate
import dev.rubentxu.pipeline.v2.domain.step.RestrictedEgressGate
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.sdk.http.HTTP_TRANSPORT_CAPABILITY
import dev.rubentxu.pipeline.v2.sdk.http.HttpRequestStep
import dev.rubentxu.pipeline.v2.sdk.http.HttpCapabilityContributor
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * H2 — the fail-closed network gate, measured rather than asserted in prose.
 *
 * ## What is actually under test
 *
 * Two capabilities, two different owners, and the security property depends on
 * which of them is missing:
 *
 * ```text
 * http.transport   supplied by the PLUGIN   — a socket factory, not a permission
 * network.egress   supplied by the RUNTIME  — the permission, and the real gate
 * ```
 *
 * The transport is contributed UNCONDITIONALLY. That is deliberate and it is the
 * part most likely to look wrong on review: withholding it would collapse "this
 * run may not use the network" and "this plugin was never wired" into the same
 * failure, and an operator staring at an admission rejection could not tell
 * which one they were looking at. Keeping the transport always present means
 * every denial of `http.request` is, unambiguously, about egress.
 *
 * The egress verdict is the single gate, and it is enforced as a MISSING
 * capability so the ordinary fail-closed admission rejects the Step before its
 * handler exists — not as an `if` inside a handler, which is a check somebody
 * can forget to write.
 */
class NetworkEgressFailClosedTest {

    private fun contributor() = CompositeCapabilityContributor(listOf(HttpCapabilityContributor()))

    private fun context(egress: NetworkEgressGate): CanonicalRuntimeContext {
        val controlDir: Path = Files.createTempDirectory("egress-test")
        return CanonicalRuntimeContext(
            opId = dev.rubentxu.pipeline.v2.application.durable.OpId("test-op", 0, 0),
            runId = "test-run",
            stageName = "Test",
            stageIndex = 0,
            stepIndex = 0,
            shOptions = ShOptions(
                workspaceRoot = controlDir,
                captureStdout = false,
                timeoutMs = null,
                env = emptyMap(),
                networkEgress = egress,
            ),
            controlDirRoot = controlDir,
            eventSink = dev.rubentxu.pipeline.v2.events.durable.InMemoryEventStore(),
        )
    }

    @Test
    fun `a default run receives the transport but NOT the egress permission`() {
        val access = CanonicalRuntimeCapabilityAccess(
            context(DenyAll),
            capabilityContributor = contributor(),
        )

        assertTrue(
            access.available().contains(HTTP_TRANSPORT_CAPABILITY),
            "the plugin's transport must be contributed unconditionally; otherwise a denial " +
                "would be ambiguous between 'no network' and 'plugin not wired'",
        )
        assertFalse(
            access.available().contains(NETWORK_EGRESS_CAPABILITY),
            "a denied run must NOT receive the egress permission",
        )
    }

    @Test
    fun `--allow-network is the single thing that grants egress`() {
        val access = CanonicalRuntimeCapabilityAccess(
            context(AllowAll),
            capabilityContributor = contributor(),
        )

        assertTrue(
            access.available().contains(NETWORK_EGRESS_CAPABILITY),
            "--allow-network must produce the egress verdict",
        )
        assertTrue(
            access.available().contains(HTTP_TRANSPORT_CAPABILITY),
            "granting egress must not remove the transport it enables",
        )
    }

    @Test
    fun `a restricted run is admitted, and refused per destination instead`() {
        // The half of H2 that only became expressible once the verdict became a gate.
        //
        // The two failure modes this refuses to pick between are both real and both
        // have shipped in products: withhold the capability from a run that has a
        // one-host allowlist, and http.request becomes unusable on it; or hand it out
        // unconditionally, and the allowlist is a comment in a config file. Asking the
        // gate — `permitsAny` for admission, `decide` per destination — is the shape
        // where the allowlist actually restricts.
        val access = CanonicalRuntimeCapabilityAccess(
            context(RestrictedEgressGate(listOf(EgressRule("https", "api.example.test")))),
            capabilityContributor = contributor(),
        )

        assertTrue(
            access.available().contains(NETWORK_EGRESS_CAPABILITY),
            "a run with an allowlist HAS a network. Withholding the permission from it would " +
                "refuse http.request at admission for the whole run, which is not what an " +
                "allowlist says.",
        )
        assertEquals(
            emptySet<StepCapability>(),
            setOf(NETWORK_EGRESS_CAPABILITY) - access.available(),
            "so the egress requirement is satisfied and the refusal happens where it belongs: " +
                "at the destination, with a reason. (The credential seam is absent from this " +
                "fixture by construction; it is covered by HttpCapabilityProductionWiringTest.)",
        )
    }

    @Test
    fun `the gate the bridge hands over is the run's own gate, not a copy`() {
        // Identity, not equality. A runtime that rebuilt the gate here would hold a
        // second opinion about the run's permission, and the question the Step asks
        // would not be reaching the object the operator's flag produced.
        val gate = RestrictedEgressGate(listOf(EgressRule("https", "api.example.test")))
        val access = CanonicalRuntimeCapabilityAccess(
            context(gate),
            capabilityContributor = contributor(),
        )

        assertSame(
            gate,
            access.get<Any>(NETWORK_EGRESS_CAPABILITY),
            "the capability must BE the run's gate",
        )
    }

    @Test
    fun `the step declares both capabilities, so a denied run is rejected at admission`() {
        // The contract is what makes the gate load-bearing: a Step that declared
        // only the transport would run on a denied run, because the transport IS
        // present. Declaring the permission is the whole mechanism.
        val required = HttpRequestStep.definition.contract.requiredCapabilities
        assertTrue(
            required.contains(NETWORK_EGRESS_CAPABILITY),
            "http.request must declare the egress permission, not just the transport",
        )
        assertTrue(
            required.contains(HTTP_TRANSPORT_CAPABILITY),
            "http.request must declare the transport seam",
        )

        val denied = CanonicalRuntimeCapabilityAccess(
            context(DenyAll),
            capabilityContributor = contributor(),
        )
        val missing = required - denied.available()
        assertTrue(
            missing.contains(NETWORK_EGRESS_CAPABILITY),
            "on a denied run the unsatisfied requirement must be the egress permission; " +
                "missing=$missing",
        )
    }

    @Test
    fun `requesting an absent capability throws rather than returning a null implementation`() {
        // Fail-closed means an EXCEPTION at the boundary, not a null that a
        // caller would have to remember to check. A null here would put the
        // "did I remember?" question back in front of every consumer.
        val access = CanonicalRuntimeCapabilityAccess(
            context(DenyAll),
            capabilityContributor = contributor(),
        )
        val failure = runCatching {
            access.get<Any>(NETWORK_EGRESS_CAPABILITY)
        }.exceptionOrNull()
        assertTrue(
            failure is IllegalArgumentException,
            "an absent capability must throw IllegalArgumentException; got $failure",
        )
    }
}
