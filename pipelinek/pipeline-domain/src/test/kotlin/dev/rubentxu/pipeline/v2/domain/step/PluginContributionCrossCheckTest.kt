package dev.rubentxu.pipeline.v2.domain.step

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The rule that compares what a plugin DECLARED against what it actually PROVIDED.
 *
 * The rule is the second gate of the plugin admission, and it is the only one that
 * can see a manifest that lies: the first gate reads the document, and a document
 * that lies is still a well formed document. The lie only exists against the
 * plugin's CODE, so the comparison needs the code to have run, which is why this
 * rule lives after the contributors are instantiated and not inside the
 * pre-load admission.
 *
 * The comparison is EXACT and in BOTH directions. A declared Step with no
 * definition is a lie of omission; a definition with no declaration is a lie of
 * commission, and it is the same lie: the plugin says it is something it is not.
 * A rule that only checked one direction would let a plugin ship a Step the
 * operator never asked for, and the operator's pipeline would resolve it.
 *
 * The names matter as much as the verdict: the refusal an operator reads has to
 * say WHICH key failed, so the rule keeps every difference in a NAMED set
 * instead of collapsing to a boolean that the caller would have to recompute.
 *
 * The manifest here is decoded with the product's own codec, so the document
 * under test is the same shape the runtime reads out of a plugin JAR.
 */
class PluginContributionCrossCheckTest {

    @Test
    fun `a plugin that declares and provides the same Steps is consistent`() {
        val check = admittedPlugin(declaredSteps = listOf("sample.step"))
            .admitContributions(definitions = listOf(definition("sample.step")))

        assertTrue(check.isConsistent, "a plugin that declares exactly what it provides is consistent")
        assertEquals(emptySet<String>(), check.missingImplementation)
        assertEquals(emptySet<String>(), check.undeclaredImplementation)
    }

    @Test
    fun `a declared Step with no implementation is named`() {
        val check = admittedPlugin(declaredSteps = listOf("sample.step", "sample.ghost"))
            .admitContributions(definitions = listOf(definition("sample.step")))

        assertEquals(setOf("sample.ghost"), check.missingImplementation.map { it.value }.toSet())
        assertTrue(!check.isConsistent, "a declared Step with no implementation is a refusal")
    }

    @Test
    fun `an implemented Step with no declaration is named`() {
        val check = admittedPlugin(declaredSteps = listOf("sample.step"))
            .admitContributions(definitions = listOf(definition("sample.step"), definition("sample.surprise")))

        assertEquals(setOf("sample.surprise"), check.undeclaredImplementation.map { it.value }.toSet())
        assertTrue(!check.isConsistent, "an implemented Step with no declaration is a refusal")
    }

    @Test
    fun `a declared Directive with no contributor is named`() {
        val check = admittedPlugin(
            declaredSteps = listOf("sample.step"),
            declaredDirectives = listOf("sample.directive"),
        ).admitContributions(definitions = listOf(definition("sample.step")))

        assertEquals(setOf("sample.directive"), check.missingDirectives.map { it.value }.toSet())
        assertTrue(!check.isConsistent, "a declared Directive with no contributor is a refusal")
    }

    @Test
    fun `a contributed Directive absent from the manifest is named`() {
        val check = admittedPlugin(
            declaredSteps = listOf("sample.step"),
            declaredDirectives = listOf("sample.declared"),
        ).admitContributions(
            definitions = listOf(definition("sample.step")),
            directiveKeys = setOf(DirectiveKey("sample.declared"), DirectiveKey("sample.undeclared")),
        )

        assertEquals(setOf("sample.undeclared"), check.undeclaredDirectives.map { it.value }.toSet())
        assertTrue(!check.isConsistent, "a contributed Directive absent from the manifest is a refusal")
    }

    @Test
    fun `a declared Event with no contributor is named`() {
        val check = admittedPlugin(
            declaredSteps = listOf("sample.step"),
            declaredEvents = listOf("sample.event"),
        ).admitContributions(definitions = listOf(definition("sample.step")))

        assertEquals(setOf("sample.event"), check.missingEvents)
        assertTrue(!check.isConsistent, "a declared Event with no contributor is a refusal")
    }

    @Test
    fun `a capability the contract does not require is named on both sides`() {
        val check = admittedPlugin(
            declaredSteps = listOf("sample.step"),
            declaredCapabilities = mapOf("sample.step" to setOf("sample.ghost.capability")),
        ).admitContributions(definitions = listOf(definition("sample.step")))

        assertEquals(
            listOf("sample.step" to (setOf("sample.ghost.capability") to emptySet<String>())),
            check.mismatchedCapabilities.map { (key, pair) ->
                key.value to (pair.first.map { it.key }.toSet() to pair.second.map { it.key }.toSet())
            },
        )
        assertTrue(!check.isConsistent, "a declared capability the contract does not require is a refusal")
    }

    @Test
    fun `a capability the contract does require is not a drift`() {
        val check = admittedPlugin(
            declaredSteps = listOf("sample.step"),
            declaredCapabilities = mapOf("sample.step" to setOf("sample.wanted")),
        ).admitContributions(definitions = listOf(definition("sample.step", requiredCapabilities = setOf("sample.wanted"))))

        assertEquals(emptyList<Any>(), check.mismatchedCapabilities)
        assertTrue(check.isConsistent, "a declared capability the contract does require is not a drift")
    }

    // --- The fixture ----------------------------------------------------------
    //
    // A manifest decoded from the document the runtime reads, and a Step whose
    // contract is a name and nothing else: the rule compares NAMES, so a
    // handler that does nothing is still a handler that provides its key.

    private fun admittedPlugin(
        declaredSteps: List<String>,
        declaredDirectives: List<String> = emptyList(),
        declaredEvents: List<String> = emptyList(),
        declaredCapabilities: Map<String, Set<String>> = emptyMap(),
    ): AdmittedPlugin {
        val manifest = when (
            val decoded = PluginManifestCodec.decode(
                manifestDocument(declaredSteps, declaredDirectives, declaredEvents, declaredCapabilities),
            )
        ) {
            is PluginManifestDecodeResult.Accepted -> decoded.manifest
            is PluginManifestDecodeResult.Rejected ->
                error("the fixture manifest was refused: ${decoded.rejection}")
        }
        return AdmittedPlugin(
            manifest = manifest,
            identity = MeasuredArtifactIdentity(
                declaredDigest = manifest.release.digest,
                measuredDigest = manifest.release.digest,
                origin = ArtifactOrigin.LocalClasspathEntry("/fixtures/sample-plugin.jar"),
            ),
        )
    }

    private fun manifestDocument(
        declaredSteps: List<String>,
        declaredDirectives: List<String>,
        declaredEvents: List<String>,
        declaredCapabilities: Map<String, Set<String>>,
    ): String = """
        {
          "codec": "pipelinek-manifest-codec/v1",
          "schemaVersion": "manifest/v1",
          "plugin": "v1:plugin:example/sample",
          "releaseVersion": "0.1.0",
          "releaseDigest": "sha256:${"0".repeat(64)}",
          "apiRange": "[0.0.0, 99.0.0)",
          "publisher": "sample",
          "delivery": "EXTERNAL_REFERENCE",
          "trust": "unverified",
          "families": ["UTILITIES"],
          "steps": [${declaredSteps.joinToString(",") { step ->
                val capabilities = declaredCapabilities[step].orEmpty().joinToString(",") { "\"$it\"" }
                """{"stepKey": "$step", "declaredCapabilities": [$capabilities]}"""
            }}],
          "directives": [${declaredDirectives.joinToString(",") { "\"$it\"" }}],
          "events": [${declaredEvents.joinToString(",") { "\"$it\"" }}],
          "capabilities": []
        }
    """.trimIndent()

    private fun definition(
        key: String,
        requiredCapabilities: Set<String> = emptySet(),
    ): StepDefinition<String, String> = object : StepDefinition<String, String> {
        override val contract = StepContract(
            key = PluginStepId(key),
            descriptor = StepDescriptor(stepId = key, name = key, configRef = "sample"),
            inputCodec = StringCodec,
            outputCodec = StringCodec,
            requiredCapabilities = requiredCapabilities.map { StepCapability(it) }.toSet(),
        )
        override val handler = StepHandler<String, String> { _, _ -> "" }
    }

    private object StringCodec : StepCodec<String> {
        override fun encode(value: String): EncodedStepValue = EncodedStepValue(value)
        override fun decode(encoded: EncodedStepValue): String = encoded.value
    }
}
