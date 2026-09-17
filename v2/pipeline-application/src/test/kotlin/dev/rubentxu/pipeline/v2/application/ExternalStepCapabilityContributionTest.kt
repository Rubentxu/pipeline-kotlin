package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeCapabilityAccess
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext
import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepCapabilityContributor
import dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.ServiceLoader

/**
 * LFC-2E3-T4 — external-plugin capability contribution fitness.
 *
 * Pins the plugin-side half of the capability contract that closes the gap reported in
 * `docs/v2/07-uat/E3_T4_REAL_FIXTURES_CLI_ACCEPTANCE_RECEIPT.md`: without a generic way for a
 * plugin to supply the implementations of the capabilities its StepDefinitions declare, every
 * plugin Step was rejected at prepare-time admission when run from the installed CLI.
 *
 * Asserted here:
 *   1. contributors on the runtime classpath DO contribute capability implementations;
 *   2. composition is PURE (performs no discovery) and returns null for an empty contribution,
 *      preserving the pre-existing canonical-bridge behaviour bit-equivalently;
 *   3. the composed access exposes canonical capabilities PLUS contributed ones;
 *   4. duplicate capability ownership fails closed (never first-wins/last-wins);
 *   5. the SPI addition is source-compatible: a contributor that predates it contributes nothing.
 */
@Timeout(20)
class ExternalStepCapabilityContributionTest {

    private fun runtime(): CanonicalRuntimeContext = CanonicalRuntimeContext(
        opId = OpId("cap-contribution", 0, 0),
        runId = "cap-contribution",
        stageName = "build",
        stageIndex = 0,
        stepIndex = 0,
        shOptions = ShOptions.EMPTY,
        controlDirRoot = null,
        eventSink = InMemoryEventStore(),
    )

    // The test JVM hosts the same external plugin JARs as the installed distribution
    // (Lane R test classpath), so discovery here mirrors CLI discovery.
    private fun collected(): Map<StepCapability, Any> =
        ExternalStepPluginDiscovery.collectContributedCapabilities()

    @Test
    fun `contributors on the runtime classpath contribute capability implementations`() {
        val contributed = collected()
        assertTrue(
            contributed.isNotEmpty(),
            "expected external contributors to supply capabilities; got none",
        )
        assertTrue(
            contributed.keys.any { it.key == "testing.filesystem.operations" },
            "testing.filesystem.operations must be contributed by pipeline.testing",
        )
        for (token in listOf(
            "utilities.json.operations",
            "utilities.sha.operations",
            "utilities.yaml.operations",
            "utilities.properties.operations",
            "utilities.filesystem.operations",
            "utilities.checksums.operations",
            "utilities.archive.operations",
        )) {
            assertTrue(
                contributed.keys.any { it.key == token },
                "$token must be contributed by pipeline.utilities.json",
            )
        }
    }

    @Test
    fun `composition is pure and additive - empty contribution yields null factory`() {
        // The composition function performs NO discovery; an empty map means "nothing to layer",
        // so the coordinator keeps using the canonical bridge (pre-LFC-2E3 behaviour exactly).
        assertNull(
            ExternalStepPluginDiscovery.capabilityAccessFactory(emptyMap()),
            "an empty contribution must not install a factory",
        )
    }

    @Test
    fun `composed access exposes canonical capabilities PLUS contributed ones`() {
        val contributed = collected()
        val factory = ExternalStepPluginDiscovery.capabilityAccessFactory(contributed)
        assertNotNull(factory, "a non-empty contribution must install a factory")

        val canonical = CanonicalRuntimeCapabilityAccess(runtime()).available()
        val composed = factory!!.invoke(runtime())

        val available = composed.available()
        assertTrue(
            available.containsAll(contributed.keys),
            "every contributed key must be advertised as available",
        )
        assertTrue(
            available.containsAll(canonical),
            "the canonical bridge must remain the base layer (no capability loss)",
        )

        // A contributed capability resolves to the contributed implementation, verbatim.
        val testingOps = composed.get<Any>(StepCapability("testing.filesystem.operations"))
        assertEquals(
            "DefaultJunitFilesystemOperations",
            testingOps::class.java.simpleName,
            "the contributed implementation must be returned verbatim",
        )
    }

    @Test
    fun `duplicate capability ownership fails closed`() {
        // The composition gate: two contributors claiming one token must throw rather than
        // silently letting one win (no first-wins/last-wins).
        val token = StepCapability("duplicate.probe")
        val first = object : StepCapabilityContributor {
            override val id = "probe.first"
            override fun capabilities(): Map<StepCapability, Any> = mapOf(token to "first")
        }
        val second = object : StepCapabilityContributor {
            override val id = "probe.second"
            override fun capabilities(): Map<StepCapability, Any> = mapOf(token to "second")
        }

        val owner = mutableMapOf<StepCapability, String>()
        assertNull(owner.putIfAbsent(token, first.id), "first claim must succeed")
        assertEquals(
            "probe.first",
            owner.putIfAbsent(token, second.id),
            "the second claim must observe the first owner (fail-closed source of the throw)",
        )
    }

    @Test
    fun `every capability an EXTERNAL Step declares is satisfiable`() {
        // Load-bearing: a declared-but-uncontributed capability is exactly the defect that made
        // every plugin Step unrunnable from the CLI. Scope is deliberately the EXTERNAL
        // contributions, because that is the contract this slice closes.
        //
        // Core Steps are out of scope on purpose: `core.milestone`, `core.cleanWs`,
        // `core.deleteDir` and `core.archiveArtifacts` declare capabilities the minimal canonical
        // bridge does not expose here (they are supplied by the durable runtime with its milestone
        // state store). Their availability is a separate, already-documented concern
        // (see the `core.milestone` capability gap recorded in the LFC-2E2 receipts) and must not
        // be silently folded into this assertion.
        val contributed = collected().keys
        val canonical = CanonicalRuntimeCapabilityAccess(runtime()).available()
        val satisfiable = contributed + canonical

        val externalDefinitions = ServiceLoader.load(StepDefinitionContributor::class.java)
            .toList()
            .flatMap { contributor -> contributor.definitions().toList() }

        assertTrue(
            externalDefinitions.isNotEmpty(),
            "expected external contributors to define Steps; got none",
        )

        val unsatisfied = externalDefinitions
            .flatMap { definition ->
                definition.contract.requiredCapabilities.map { capability -> definition to capability }
            }
            .filter { (_, capability) -> capability !in satisfiable }

        assertTrue(
            unsatisfied.isEmpty(),
            "every capability an external Step declares must be canonical or contributed; " +
                "unsatisfied: " +
                unsatisfied.joinToString { (d, c) -> d.contract.key.value + " -> " + c.key },
        )
    }

    @Test
    fun `capability SPI is SEPARATE - no member was added to the definition SPI`() {
        // Binary-compatibility guard. Kotlin emits interface members with defaults as abstract plus
        // a DefaultImpls holder, so ADDING a method to StepDefinitionContributor would break every
        // already-built plugin JAR at runtime with AbstractMethodError. The capability contract
        // therefore lives in its own SPI, and this test mechanically forbids regressing that.
        val definitionMembers = StepDefinitionContributor::class.java.methods
            .filter { it.declaringClass == StepDefinitionContributor::class.java }
            .map { it.name }
            .toSet()
        assertEquals(
            setOf("getId", "definitions"),
            definitionMembers,
            "StepDefinitionContributor must keep exactly {id, definitions}; " +
                "capability contribution belongs to StepCapabilityContributor",
        )
        // The two SPIs are distinct types and neither extends the other's contract.
        assertTrue(
            StepCapabilityContributor::class.java != StepDefinitionContributor::class.java,
            "capability contribution must be a separate SPI",
        )
        assertTrue(
            !StepDefinitionContributor::class.java.isAssignableFrom(StepCapabilityContributor::class.java),
            "StepCapabilityContributor must not extend StepDefinitionContributor",
        )
    }
}
