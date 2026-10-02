package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeCapabilityAccess
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext
import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.credentials.api.BASIC_CREDENTIALS_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.CompositeCapabilityContributor
import dev.rubentxu.pipeline.v2.domain.step.NETWORK_EGRESS_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.AllowAll
import dev.rubentxu.pipeline.v2.domain.step.DenyAll
import dev.rubentxu.pipeline.v2.domain.step.NetworkEgressGate
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.sdk.http.HTTP_TRANSPORT_CAPABILITY
import dev.rubentxu.pipeline.v2.sdk.http.HttpRequestStep
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * H4.5 — the seam has to be reachable in the DISTRIBUTION, not only in a test.
 *
 * ## The gap this pins shut
 *
 * `NetworkEgressFailClosedTest` passed for two slices while `http.request` could not
 * run, because it built the contributor itself:
 *
 * ```kotlin
 * CompositeCapabilityContributor(listOf(HttpCapabilityContributor()))
 * ```
 *
 * A test that hand-assembles the wiring proves the wiring works when it is present.
 * It says nothing about whether anything PRESENTs it, and that was the whole defect:
 * `CompositionRoot` defaulted `capabilityContributors` to `emptyList()`, the CLI
 * never passed the argument, and `HttpCapabilityContributor` was instantiated in
 * exactly one place in the repository — inside a test.
 *
 * So admission refused `http.request` for a missing `http.transport`, with or
 * without `--allow-network`. It failed closed, which is why it was a defect and not
 * an incident, but a Step that cannot execute is not delivered.
 *
 * Every assertion below therefore goes through DISCOVERY. None of them constructs
 * the contributor by hand, because a hand-built contributor is precisely the thing
 * that hid the bug.
 */
class HttpCapabilityProductionWiringTest {

    /**
     * The composed set as PRODUCTION sees it.
     *
     * Two sources, because the seams have genuinely different lifetimes:
     *
     * ```text
     * discovery            http.transport        — the plugin's own, from the classpath
     * composition root     credentials.basic      — the operator's store, from this run
     * ```
     *
     * The credential seam cannot come from classpath discovery: it wraps the store
     * THIS invocation opened, and a classpath has no idea whether the operator
     * configured one. Composing only the discovered half is a real configuration —
     * it is what a test harness builds — and it is why the assertions below name
     * which source each capability came from.
     */
    private fun productionCapabilities(
        egress: NetworkEgressGate = DenyAll,
        withCredentialStore: Boolean = true,
    ): Set<dev.rubentxu.pipeline.v2.domain.step.StepCapability> {
        val controlDir: Path = Files.createTempDirectory("h45-wiring")
        val context = CanonicalRuntimeContext(
            opId = OpId("h45", 0, 0),
            runId = "h45",
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
            eventSink = InMemoryEventStore(),
        )
        val contributors = buildList {
            addAll(ExternalCapabilityContributorDiscovery.discover())
            if (withCredentialStore) {
                add(
                    dev.rubentxu.pipeline.v2.credentials.executor
                        .BasicCredentialsCapabilityContributor(null),
                )
            }
        }
        return CanonicalRuntimeCapabilityAccess(
            context,
            capabilityContributor = CompositeCapabilityContributor(contributors),
        ).available()
    }

    @Test
    fun `discovery finds the http transport on the runtime classpath`() {
        val available = productionCapabilities()

        assertTrue(
            available.contains(HTTP_TRANSPORT_CAPABILITY),
            "discovery did not supply http.transport. The plugin must ship " +
                "META-INF/services/${dev.rubentxu.pipeline.v2.domain.step.RuntimeCapabilityContributor::class.java.name}. " +
                "Without it the Step is refused at admission in the installed distribution " +
                "even though the plugin is present and discovered as a Step plugin.",
        )
    }

    @Test
    fun `discovery is not empty, so an omitted argument cannot silently disable every plugin`() {
        val contributors = ExternalCapabilityContributorDiscovery.discover()

        assertFalse(
            contributors.isEmpty(),
            "no runtime capability contributor was discovered. This is the exact shape of the " +
                "H4.5 defect: an empty composition that reads as 'no plugin needs a seam' when it " +
                "actually means 'nothing presented one'.",
        )
    }

    @Test
    fun `a denied run is refused for EGRESS and never for the transport`() {
        val denied = productionCapabilities(DenyAll)
        val required = HttpRequestStep.definition.contract.requiredCapabilities

        val missing = required - denied
        assertEquals(
            setOf(NETWORK_EGRESS_CAPABILITY),
            missing,
            "on a denied run the ONLY unsatisfied requirement may be the egress permission; " +
                "missing=$missing. Anything else means a seam is not wired and the operator " +
                "is told the wrong thing about why the Step was refused.",
        )
    }

    @Test
    fun `an allowed run satisfies every declared requirement`() {
        val allowed = productionCapabilities(AllowAll)
        val required = HttpRequestStep.definition.contract.requiredCapabilities

        val missing = required - allowed
        assertTrue(
            missing.isEmpty(),
            "with --allow-network every requirement must be satisfiable; missing=$missing. " +
                "This is the run that used to be impossible.",
        )
    }

    @Test
    fun `the credential seam is per-run state, not something a classpath can supply`() {
        // Worth pinning because it looks like an omission. It is not: the seam wraps
        // the store THIS invocation opened. A ServiceLoader implementation would have
        // to invent a store, and inventing one is how a run ends up with credentials
        // nobody configured.
        val discovered = ExternalCapabilityContributorDiscovery.discover()
            .flatMap { it.capabilities().keys }

        assertFalse(
            BASIC_CREDENTIALS_CAPABILITY in discovered,
            "credentials.basic must not be discoverable from the classpath; got $discovered",
        )
        assertTrue(
            BASIC_CREDENTIALS_CAPABILITY in productionCapabilities(),
            "but the composition root must always supply it, with or without a store",
        )
    }
}
