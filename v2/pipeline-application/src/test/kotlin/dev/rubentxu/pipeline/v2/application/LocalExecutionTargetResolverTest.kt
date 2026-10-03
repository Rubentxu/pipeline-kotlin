package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.MapRuntimeConfig
import dev.rubentxu.pipeline.v2.domain.RuntimeConfig
import dev.rubentxu.pipeline.v2.domain.directive.AgentLabel
import dev.rubentxu.pipeline.v2.domain.directive.ExecutionTargetRequirement
import dev.rubentxu.pipeline.v2.domain.directive.RemoteSelector
import dev.rubentxu.pipeline.v2.domain.directive.TargetLeaseResult
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * S3.1 — the local execution-target resolver.
 *
 * These are the discriminating cases the directive model cares about. Each one
 * is chosen because the WRONG answer is a plausible implementation:
 *
 *  - a `Remote` request that "succeeds" because the run is already local is the
 *    silent drop this whole train exists to remove;
 *  - a `CapabilitySet` satisfied from a table of what the runtime *could*
 *    provide is the same lie one layer down;
 *  - a label check against a hard-coded list rather than the actual host
 *    simulates the feature instead of implementing it.
 */
class LocalExecutionTargetResolverTest {

    private fun on(osName: String, granted: Set<StepCapability> = emptySet()) =
        LocalExecutionTargetResolver(
            runtimeConfig = MapRuntimeConfig(
                env = emptyMap(),
                properties = mapOf("os.name" to osName),
            ),
            grantedCapabilities = granted,
        )

    private fun linux(granted: Set<StepCapability> = emptySet()) = on("Linux", granted)

    private fun windows(granted: Set<StepCapability> = emptySet()) = on("Windows 11", granted)

    // ------------------------------------------------------------------
    // LocalAny: always satisfied, and says so observably
    // ------------------------------------------------------------------

    @Test
    fun `LocalAny is granted on every platform`() {
        val result = linux().acquire(ExecutionTargetRequirement.LocalAny)
        assertTrue(result is TargetLeaseResult.Granted, "LocalAny must never be refused: $result")
        assertEquals("Linux", (result as TargetLeaseResult.Granted).targetId)
    }

    @Test
    fun `the granted target is the observable platform, not a synthetic index`() {
        // An operator reading a resolution event wants to know which machine
        // ran, not which index it held in a list.
        val result = windows().acquire(ExecutionTargetRequirement.LocalAny)
        assertEquals("Windows 11", (result as TargetLeaseResult.Granted).targetId)
    }

    // ------------------------------------------------------------------
    // LocalLabels: decided against the real host
    // ------------------------------------------------------------------

    @Test
    fun `a label the host advertises is granted`() {
        val result = linux().acquire(
            ExecutionTargetRequirement.LocalLabels(setOf(AgentLabel("linux"))),
        )
        assertTrue(result is TargetLeaseResult.Granted, "the host IS linux: $result")
    }

    @Test
    fun `a label the host does not advertise is refused`() {
        val result = linux().acquire(
            ExecutionTargetRequirement.LocalLabels(setOf(AgentLabel("windows"))),
        )
        val refusal = result as? TargetLeaseResult.Refused
        assertTrue(refusal != null, "a Windows label must be refused on Linux: $result")
        assertTrue(
            refusal!!.reason.contains("windows") && refusal.reason.contains("linux"),
            "the refusal must name both the missing label and what the host does advertise, or " +
                "the author cannot tell what to change: ${refusal.reason}",
        )
    }

    @Test
    fun `label matching is platform-derived, so the same script differs by host`() {
        // The discriminating pair: the same requirement, two hosts, two answers.
        // A hard-coded label list would make both of these agree, which is
        // exactly the "it compiles so it must work" gap this rejects.
        val requirement = ExecutionTargetRequirement.LocalLabels(setOf(AgentLabel("linux")))
        assertTrue(linux().acquire(requirement) is TargetLeaseResult.Granted)
        assertTrue(windows().acquire(requirement) is TargetLeaseResult.Refused)
    }

    @Test
    fun `unix is advertised off Windows, because that is the label authors write`() {
        assertTrue(
            linux().acquire(
                ExecutionTargetRequirement.LocalLabels(setOf(AgentLabel("unix"))),
            ) is TargetLeaseResult.Granted,
        )
        assertTrue(
            windows().acquire(
                ExecutionTargetRequirement.LocalLabels(setOf(AgentLabel("unix"))),
            ) is TargetLeaseResult.Refused,
        )
    }

    @Test
    fun `label matching is containment, so a superset satisfies a subset`() {
        // Jenkins labels are additive; a host advertising both satisfies a
        // requirement for either. Equality would make `agent { label 'linux' }`
        // fail on a host that also advertises `docker`.
        val both = linux().acquire(
            ExecutionTargetRequirement.LocalLabels(
                setOf(AgentLabel("linux"), AgentLabel("unix")),
            ),
        )
        assertTrue(both is TargetLeaseResult.Granted, "the host advertises both: $both")
    }

    // ------------------------------------------------------------------
    // CapabilitySet: checked against GRANTED, never against possible
    // ------------------------------------------------------------------

    @Test
    fun `a granted capability satisfies the requirement`() {
        val resolver = linux(setOf(StepCapability("http.transport")))
        val result = resolver.acquire(
            ExecutionTargetRequirement.CapabilitySet(setOf(StepCapability("http.transport"))),
        )
        assertTrue(result is TargetLeaseResult.Granted, "$result")
    }

    @Test
    fun `a capability the run was not granted is refused, naming it`() {
        val result = linux().acquire(
            ExecutionTargetRequirement.CapabilitySet(setOf(StepCapability("http.transport"))),
        )
        val refusal = result as? TargetLeaseResult.Refused
        assertTrue(refusal != null, "an ungranted capability must be refused: $result")
        assertTrue(
            refusal!!.reason.contains("http.transport"),
            "the refusal must name the missing capability, otherwise the author cannot act: " +
                refusal.reason,
        )
    }

    @Test
    fun `a partially satisfied capability set is refused`() {
        val resolver = linux(setOf(StepCapability("http.transport")))
        val result = resolver.acquire(
            ExecutionTargetRequirement.CapabilitySet(
                setOf(StepCapability("http.transport"), StepCapability("network.egress")),
            ),
        )
        val refusal = result as? TargetLeaseResult.Refused
        assertTrue(refusal != null, "a partial set is not a satisfied set: $result")
        assertTrue(
            !refusal!!.reason.contains("http.transport"),
            "only the MISSING capability belongs in the diagnostic; listing the granted one too " +
                "reads as though both failed: ${refusal.reason}",
        )
    }

    // ------------------------------------------------------------------
    // The platform arrives through the RuntimeConfig PORT, read per acquire
    // ------------------------------------------------------------------

    /**
     * A port that records how often it was asked and can change its answer.
     *
     * A real `RuntimeConfig` is what production uses; this exists so the test
     * can prove the resolver asks the port rather than reading the host, and
     * asks it LATE rather than snapshotting it into a constructor field.
     */
    private class CountingRuntimeConfig(var osName: String) : RuntimeConfig {
        var osNameReads = 0
            private set

        override fun env(name: String): String? = null
        override fun property(name: String): String? = if (name == "os.name") osName else null
        override fun property(name: String, default: String): String = property(name) ?: default
        override fun osName(): String {
            osNameReads++
            return osName
        }

        override fun userDir(): String = "/workspace"
    }

    @Test
    fun `the platform is read at acquire time, so a reconfigured host changes the answer`() {
        // The discriminating property: a resolver that snapshotted the platform
        // into a constructor val would keep granting the label the host had at
        // construction. `RuntimeConfig` is documented as re-reading per call
        // precisely so a mid-flight reconfiguration is observed, and a target
        // resolver that contradicts it would be a second platform authority.
        val config = CountingRuntimeConfig("Linux")
        val resolver = LocalExecutionTargetResolver(runtimeConfig = config)

        assertTrue(
            resolver.acquire(
                ExecutionTargetRequirement.LocalLabels(setOf(AgentLabel("windows"))),
            ) is TargetLeaseResult.Refused,
            "a Linux host does not advertise windows",
        )

        // Reconfigure the host and ask again. A construction-time snapshot
        // cannot express this; a per-acquire read can.
        config.osName = "Windows 11"
        val after = resolver.acquire(
            ExecutionTargetRequirement.LocalLabels(setOf(AgentLabel("linux"))),
        )
        assertTrue(
            after is TargetLeaseResult.Refused,
            "after reconfiguring to Windows the linux label must be refused; a construction-time " +
                "snapshot would still grant it: $after",
        )
    }

    @Test
    fun `each acquisition observes the platform exactly once`() {
        // The platform is hoisted to a single local so every branch of the
        // closed `when` reports the same machine. Re-reading per branch would
        // make a mid-acquire reconfiguration produce a grant whose targetId
        // disagrees with its own diagnostic.
        val config = CountingRuntimeConfig("Linux")
        val resolver = LocalExecutionTargetResolver(runtimeConfig = config)

        ExecutionTargetRequirement.LocalAny.let { resolver.acquire(it) }
        assertEquals(1, config.osNameReads, "LocalAny must read the platform exactly once")

        resolver.acquire(ExecutionTargetRequirement.LocalLabels(setOf(AgentLabel("linux"))))
        assertEquals(2, config.osNameReads, "a second acquisition adds exactly one more read")

        resolver.acquire(ExecutionTargetRequirement.Remote(RemoteSelector("tcp://build-01:9000")))
        assertEquals(
            3,
            config.osNameReads,
            "even a refusal reports a platform, so Remote must read it too — otherwise the " +
                "granted target id and a refusal diagnostic would disagree about the host",
        )
    }

    // ------------------------------------------------------------------
    // Remote: refused, and the diagnostic says why
    // ------------------------------------------------------------------

    @Test
    fun `Remote is refused rather than silently run locally`() {
        val result = linux().acquire(
            ExecutionTargetRequirement.Remote(RemoteSelector("tcp://build-01:9000")),
        )
        val refusal = result as? TargetLeaseResult.Refused
        assertTrue(
            refusal != null,
            "a remote requirement must NOT be granted by a single-host runtime; granting it " +
                "would run the stage somewhere the author did not ask for: $result",
        )
    }

    @Test
    fun `the Remote refusal names RP-8 so the author knows whether to wait or restructure`() {
        val result = linux().acquire(
            ExecutionTargetRequirement.Remote(RemoteSelector("tcp://build-01:9000")),
        )
        val reason = (result as TargetLeaseResult.Refused).reason
        assertTrue(reason.contains("RP-8"), "the refusal must name the milestone: $reason")
        assertTrue(
            reason.contains("build-01:9000"),
            "the refusal must echo the selector the author wrote: $reason",
        )
    }
}
