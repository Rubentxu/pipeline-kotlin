package dev.rubentxu.pipeline.v2.domain.step

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveKey
import dev.rubentxu.pipeline.v2.domain.identity.ResourceKind
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRef
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * S6 — the structural cross-check between a manifest and what the plugin actually provides.
 *
 * ## Why this suite is in the DOMAIN module
 *
 * The subject is a pure decision function over two values: it has no I/O, no clock, no ambient
 * state, and it is called by every plugin's contributor. Its tests were living in
 * `pipeline-application`, where the class under test is not a dependency the module owns — which
 * is how `PluginManifest` and `PluginManifestValidator` reached zero domain coverage while the
 * repository's coverage rule still passed on the module that happened to exercise them. A test
 * for a domain decision belongs beside the decision.
 *
 * ## Non-vacuity
 *
 * Each refusal row names the mutation that would make it stop firing. The two capability rows are
 * the reason this suite exists at all: the validator's KDoc promised that a Step's required
 * capabilities appear in the manifest's top-level request, while the code only ever checked the
 * reverse. Deleting the under-claiming check leaves every other row green — which is why it has
 * its own row here rather than being folded into the happy path.
 */
class PluginManifestValidatorTest {

    private val pluginRef = ResourceRef(ResourceKind.PLUGIN, listOf("acme", "example"))
    private val digest = Digest("sha256:" + "a".repeat(64))

    private fun manifestOf(
        steps: List<PluginStepContribution> = emptyList(),
        directives: List<PluginDirectiveContribution> = emptyList(),
        events: List<PluginEventContribution> = emptyList(),
        capabilities: Set<StepCapability> = emptySet(),
    ) = PluginManifest(
        schemaVersion = ManifestSchemaVersion.CURRENT,
        plugin = pluginRef,
        release = PluginReleaseRef(pluginRef, SemVer(1, 0, 0), digest),
        apiRange = PipelineKApiRange(SemVer(0, 47, 0), SemVer(0, 49, 0)),
        publisher = "acme",
        families = setOf(PluginFamily.NETWORK),
        delivery = Delivery.EXTERNAL_REFERENCE,
        trust = TrustMetadata.Unverified,
        contributions = PluginContributions(
            steps = steps,
            directives = directives,
            events = events,
            capabilities = capabilities,
        ),
    )

    private fun definitionOf(
        key: String,
        required: Set<StepCapability> = emptySet(),
    ): StepDefinition<String, String> {
        val codec = object : StepCodec<String> {
            override fun encode(value: String): EncodedStepValue = EncodedStepValue(value)
            override fun decode(encoded: EncodedStepValue): String = encoded.value
        }
        return object : StepDefinition<String, String> {
            override val contract: StepContract<String, String> = StepContract(
                key = PluginStepId(key),
                descriptor = StepDescriptor(stepId = key, name = key, configRef = ""),
                inputCodec = codec,
                outputCodec = codec,
                requiredCapabilities = required,
            )
            override val handler: StepHandler<String, String> = StepHandler { input, _ -> input }
        }
    }

    private fun step(key: String, declared: Set<StepCapability> = emptySet()) =
        PluginStepContribution(PluginStepId(key), declared)

    private fun refusalOf(block: () -> Unit): String {
        val thrown = assertThrows(IllegalArgumentException::class.java) { block() }
        return thrown.message.orEmpty()
    }

    // ---- the shape that must pass ---------------------------------------------------

    @Test
    fun `a manifest that matches its contributions exactly is accepted`() {
        val net = StepCapability("network")
        PluginManifestValidator.validate(
            manifest = manifestOf(
                steps = listOf(step("acme.echo", setOf(net))),
                capabilities = setOf(net),
            ),
            definitions = listOf(definitionOf("acme.echo", setOf(net))),
        )
    }

    @Test
    fun `a plugin declaring nothing but a Step, with no capabilities, is accepted`() {
        PluginManifestValidator.validate(
            manifest = manifestOf(steps = listOf(step("acme.bare"))),
            definitions = listOf(definitionOf("acme.bare")),
        )
    }

    // ---- Steps, both directions -----------------------------------------------------

    /**
     * MUTATION THAT KILLS THIS: dropping the `absent` check, so a manifest could name a Step the
     * plugin never provides and admission would still accept it.
     */
    @Test
    fun `a manifest naming a Step the plugin does not provide is refused`() {
        val message = refusalOf {
            PluginManifestValidator.validate(
                manifest = manifestOf(steps = listOf(step("acme.ghost"))),
                definitions = listOf(definitionOf("acme.present")),
            )
        }
        assertTrue(message.contains("acme.ghost"), "the diagnostic must name the StepKey: $message")
    }

    /**
     * MUTATION THAT KILLS THIS: dropping the `undeclared` check — the drift direction where the
     * plugin registers a Step the artifact never declared, which is how a Step reaches the
     * registry having bypassed the declaration entirely.
     */
    @Test
    fun `a supplied Step with no manifest entry is refused`() {
        val message = refusalOf {
            PluginManifestValidator.validate(
                manifest = manifestOf(steps = listOf(step("acme.declared"))),
                definitions = listOf(definitionOf("acme.declared"), definitionOf("acme.sneaked")),
            )
        }
        assertTrue(message.contains("acme.sneaked"), "the diagnostic must name the StepKey: $message")
    }

    /**
     * MUTATION THAT KILLS THIS: replacing `declared == required` with `declared ⊆ required`,
     * which would let a plugin under-claim and slip past admission on a capability its handler
     * still asks for.
     */
    @Test
    fun `under-claiming a Step capability is refused`() {
        val net = StepCapability("network")
        val message = refusalOf {
            PluginManifestValidator.validate(
                manifest = manifestOf(steps = listOf(step("acme.echo", emptySet()))),
                definitions = listOf(definitionOf("acme.echo", setOf(net))),
            )
        }
        assertTrue(message.contains("acme.echo"), "the diagnostic must name the StepKey: $message")
    }

    @Test
    fun `over-claiming a Step capability is refused`() {
        val net = StepCapability("network")
        val disk = StepCapability("disk")
        val message = refusalOf {
            PluginManifestValidator.validate(
                manifest = manifestOf(
                    steps = listOf(step("acme.echo", setOf(net, disk))),
                    capabilities = setOf(net, disk),
                ),
                definitions = listOf(definitionOf("acme.echo", setOf(net))),
            )
        }
        assertTrue(message.contains("acme.echo"), "the diagnostic must name the StepKey: $message")
    }

    // ---- Directives and Events, both directions --------------------------------------

    @Test
    fun `a declared Directive with no contributor is refused`() {
        val message = refusalOf {
            PluginManifestValidator.validate(
                manifest = manifestOf(
                    steps = listOf(step("acme.echo")),
                    directives = listOf(PluginDirectiveContribution(DirectiveKey("acme.when"))),
                ),
                definitions = listOf(definitionOf("acme.echo")),
                directiveKeys = emptySet(),
            )
        }
        assertTrue(message.contains("acme.when"), "the diagnostic must name the DirectiveKey: $message")
    }

    /**
     * MUTATION THAT KILLS THIS: restoring the early return on an EMPTY supplied set. A plugin
     * that contributes no Directives would otherwise have any declared Directive waved through.
     */
    @Test
    fun `a Directive supplied without a manifest entry is refused`() {
        val message = refusalOf {
            PluginManifestValidator.validate(
                manifest = manifestOf(steps = listOf(step("acme.echo"))),
                definitions = listOf(definitionOf("acme.echo")),
                directiveKeys = setOf(DirectiveKey("acme.undeclared")),
            )
        }
        assertTrue(message.contains("acme.undeclared"), "the diagnostic must name the DirectiveKey: $message")
    }

    @Test
    fun `a declared Event with no contributor is refused`() {
        val message = refusalOf {
            PluginManifestValidator.validate(
                manifest = manifestOf(
                    steps = listOf(step("acme.echo")),
                    events = listOf(PluginEventContribution("acme.observed")),
                ),
                definitions = listOf(definitionOf("acme.echo")),
                eventKinds = emptySet(),
            )
        }
        assertTrue(message.contains("acme.observed"), "the diagnostic must name the Event kind: $message")
    }

    @Test
    fun `an Event supplied without a manifest entry is refused`() {
        val message = refusalOf {
            PluginManifestValidator.validate(
                manifest = manifestOf(steps = listOf(step("acme.echo"))),
                definitions = listOf(definitionOf("acme.echo")),
                eventKinds = setOf("acme.undeclared"),
            )
        }
        assertTrue(message.contains("acme.undeclared"), "the diagnostic must name the Event kind: $message")
    }

    // ---- the capability directions this suite was written for -------------------------

    /**
     * MUTATION THAT KILLS THIS: restoring the single-direction check this row documents as the
     * defect — `topLevel - usedBySteps` only. Every other row stays green while a Step can
     * require a capability the plugin never asked to be supplied.
     */
    @Test
    fun `a top-level capability no Step requires is refused - over-claiming`() {
        val net = StepCapability("network")
        val ghost = StepCapability("telemetry")
        val message = refusalOf {
            PluginManifestValidator.validate(
                manifest = manifestOf(
                    steps = listOf(step("acme.echo", setOf(net))),
                    capabilities = setOf(net, ghost),
                ),
                definitions = listOf(definitionOf("acme.echo", setOf(net))),
            )
        }
        assertTrue(message.contains("telemetry"), "the diagnostic must name the capability: $message")
    }

    /**
     * The direction the KDoc promised and the code never checked: a Step requires a capability
     * the manifest does not request at the top level.
     */
    @Test
    fun `a Step capability the manifest never requests is refused - under-claiming`() {
        val net = StepCapability("network")
        val message = refusalOf {
            PluginManifestValidator.validate(
                manifest = manifestOf(
                    steps = listOf(step("acme.echo", setOf(net))),
                    capabilities = emptySet(),
                ),
                definitions = listOf(definitionOf("acme.echo", setOf(net))),
            )
        }
        assertTrue(message.contains("network"), "the diagnostic must name the capability: $message")
    }

    // ---- the manifest's own invariants -----------------------------------------------

    @Test
    fun `a manifest whose plugin and release identity disagree cannot be constructed`() {
        val other = ResourceRef(ResourceKind.PLUGIN, listOf("acme", "other"))
        val message = assertThrows(
            IllegalArgumentException::class.java,
        ) {
            PluginManifest(
                schemaVersion = ManifestSchemaVersion.CURRENT,
                plugin = pluginRef,
                release = PluginReleaseRef(other, SemVer(1, 0, 0), digest),
                apiRange = PipelineKApiRange(SemVer(0, 47, 0), SemVer(0, 49, 0)),
                publisher = "acme",
                families = setOf(PluginFamily.NETWORK),
                delivery = Delivery.EXTERNAL_REFERENCE,
                trust = TrustMetadata.Unverified,
                contributions = PluginContributions(steps = listOf(step("acme.echo"))),
            )
        }.message.orEmpty()
        assertTrue(message.contains("release.plugin"), "the diagnostic must name the conflicting field: $message")
    }

    /**
     * MUTATION THAT KILLS THIS: dropping `requireDistinct` from [PluginContributions], which
     * would let a manifest name the same Step twice and leave the contradiction to be resolved
     * by whoever registered first.
     */
    @Test
    fun `a manifest declaring the same Step twice cannot be constructed`() {
        val message = assertThrows(
            IllegalArgumentException::class.java,
        ) {
            PluginContributions(
                steps = listOf(step("acme.echo"), step("acme.echo")),
            )
        }.message.orEmpty()
        assertTrue(message.contains("acme.echo"), "the diagnostic must name the StepKey: $message")
    }

    @Test
    fun `a manifest contributing nothing cannot be constructed`() {
        val message = assertThrows(
            IllegalArgumentException::class.java,
        ) { manifestOf() }.message.orEmpty()
        assertTrue(message.contains("at least one contribution"), "unexpected diagnostic: $message")
    }

    // ---- the compatibility range is a real, total answer -----------------------------

    @Test
    fun `the declared API range is half-open and answers membership without a clock`() {
        val range = PipelineKApiRange(SemVer(0, 47, 0), SemVer(0, 49, 0))
        assertTrue(range.accepts(SemVer(0, 47, 0)), "the lower bound is inclusive")
        assertTrue(range.accepts(SemVer(0, 48, 3)), "a version inside the range is accepted")
        assertTrue(!range.accepts(SemVer(0, 49, 0)), "the upper bound is EXCLUSIVE")
        assertTrue(!range.accepts(SemVer(0, 46, 9)), "a version below the range is refused")
    }

    @Test
    fun `an empty or inverted API range cannot be constructed`() {
        assertThrows(IllegalArgumentException::class.java) {
            PipelineKApiRange(SemVer(0, 49, 0), SemVer(0, 47, 0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PipelineKApiRange(SemVer(0, 48, 0), SemVer(0, 48, 0))
        }
    }
}
