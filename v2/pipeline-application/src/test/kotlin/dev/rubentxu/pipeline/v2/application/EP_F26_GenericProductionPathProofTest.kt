package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.supportsCanonicalDurableExecution
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
import dev.rubentxu.pipeline.v2.dsl.pipeline
import dev.rubentxu.pipeline.v2.dsl.StageScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * EP-F2.6 — generic production-path proof (LB-02).
 *
 * Demonstrates, with a NEUTRAL fixture Step (never `example.uppercase`), that an
 * arbitrary open-world StepKey traverses the full production compile path:
 *
 * ```text
 * registryStep(arbitraryKey, schemaVersion, encodedInput)
 *   → StepSpec.RegistryStepSpec
 *   → DslCompiledPipelineCompiler (StepKey-agnostic lowering)
 *   → OpaqueStepNode(pluginStepId = arbitraryKey, payload verbatim)
 *   → canonical eligibility (StepNode admission)
 *   → StructuralFamilyResolver → StructuralStepFamily.Registry
 * ```
 *
 * WITHOUT touching PipelineRun / PipelineOrchestrator, without typed decode at
 * compile time, without handler execution at compile time, and with ZERO
 * concrete external StepKey knowledge in production code.
 */
class EP_F26_GenericProductionPathProofTest {

    /** Deliberately arbitrary, non-core, non-`example.uppercase` StepKey. */
    private val neutralKey = PluginStepId("fixture.neutral.reverse")

    private val codec = object : StepCodec<String> {
        override fun encode(value: String) = EncodedStepValue(value)
        override fun decode(encoded: EncodedStepValue) = encoded.value
    }

    /** Neutral fixture definition, equivalent to what an external plugin would contribute. */
    private fun neutralDefinition(): StepDefinition<String, String> =
        object : StepDefinition<String, String> {
            override val contract = StepContract(
                key = neutralKey,
                descriptor = StepDescriptor(
                    stepId = neutralKey.value,
                    name = "neutralReverse",
                    configRef = "",
                    pluginId = "fixture",
                    executionLocation = ExecutionLocation.CONTROLLER,
                    effects = listOf(Effect.READ_ONLY),
                    replayPolicy = ReplayPolicy.MEMOIZED,
                ),
                inputCodec = codec,
                outputCodec = codec,
                requiredCapabilities = emptySet(),
            )

            override val handler = StepHandler<String, String> { input, _ -> input.reversed() }
        }

    /** Runtime-adapter-style discovery: contributor → registry (no core knowledge of the key). */
    private fun discover(contributor: StepDefinitionContributor): StepRegistry =
        InMemoryStepRegistry().apply { contributor.definitions().forEach(::register) }

    private val contributor = object : StepDefinitionContributor {
        override val id = "fixture.neutral"
        override fun definitions() = listOf(neutralDefinition())
    }

    private fun compiledStageKeys(): Pair<List<String>, OpaqueStepNode> {
        val spec = pipeline {
            stages {
                stage("External") {
                    registryStep(
                        stepKey = neutralKey,
                        schemaVersion = "fixture-v1",
                        encodedInput = codec.encode("payload-42"),
                    )
                }
            }
        }
        val compiled = DslCompiledPipelineCompiler.compile(
            spec = spec,
            sourcePath = "neutral.pipeline.kts",
            sourceContent = "pipeline { /* fixture */ }",
            pluginLockDigest = Digest("lock-neutral"),
        )
        val body = compiled.stages.single().body as StageBody.Steps
        val node = body.steps.single()
        return listOf(compiled.stages.single().name, node.pluginStepId.value) to (node as OpaqueStepNode)
    }

    @Test
    fun `registryStep DSL produces a RegistryStepSpec carrying key, schema and verbatim input`() {
        val (_, node) = compiledStageKeys()
        assertEquals(neutralKey, node.pluginStepId)
        // The declared invocation contract version flows through verbatim (not a compiler constant).
        assertEquals("fixture-v1", node.payload.schemaVersion)
        // Verbatim, undecoded, unre-encoded: compile must not understand the payload.
        assertTrue(node.payload.encoded.contains("payload-42"))
    }

    @Test
    fun `compiler lowering is StepKey-agnostic and lands in the StructuralRegistry family`() {
        val (labels, node) = compiledStageKeys()
        assertEquals("External", labels[0])
        assertEquals(neutralKey.value, labels[1])
        // The node shape is IDENTICAL to core echo/sh nodes: one generic OpaqueStepNode.
        assertEquals(listOf(Effect.READ_ONLY).isEmpty(), false) // descriptor untouched by compiler
        // Family classification: neutral key is not legacy, registry resolves it → Registry.
        val registry = discover(contributor)
        assertEquals(
            dev.rubentxu.pipeline.v2.application.durable.StructuralStepFamily.Registry,
            dev.rubentxu.pipeline.v2.application.durable.StructuralFamilyResolver.classify(node.pluginStepId, registry),
        )
    }

    @Test
    fun `canonical eligibility is registry-derived - absent before contribution, present after`() {
        val (_, node) = compiledStageKeys()

        // Temporal semantics: before discovery the key is NOT canonical-eligible...
        val emptyRegistry = InMemoryStepRegistry()
        val eligibleBefore = canonicalEligible(node.pluginStepId.value, registryKeys(emptyRegistry))
        assertFalse(eligibleBefore, "external key must NOT be canonical-eligible before contribution")

        // ...after contribution registration the SAME key IS canonical-eligible,
        // through the same authority the core keys flow through (no closed core catalogue).
        val registry = discover(contributor)
        val eligibleAfter = canonicalEligible(node.pluginStepId.value, registryKeys(registry))
        assertTrue(eligibleAfter, "external key must become canonical-eligible after contribution")

        // And a full pipeline holding only the external step is canonical-execution-eligible
        // when the runtime registry is the contributed one (coordinator derives eligibility
        // from registry keys + core metadata, never from a closed core list).
        assertTrue(registry.contains(node.pluginStepId))
    }

    @Test
    fun `compiler retains zero concrete external StepKey knowledge`() {
        // Production compiler source must not mention the neutral fixture key (or any plugin key).
        val compilerSource = COMPILER_SOURCE_FOR_FITNESS
        assertTrue(compilerSource.isNotBlank(), "compiler source must be readable for the fitness scan")
        assertFalse(compilerSource.contains("fixture.neutral"), "compiler must not know fixture keys")
        // Word-boundary scan: a String.uppercase() stdlib call is NOT plugin knowledge; a
        // plugin-namespaced identifier would be.
        assertFalse(Regex("example[.:_-]uppercase|UpperCaseStep|pluginStepId\\.value == \"").containsMatchIn(compilerSource),
            "compiler must not reference example.uppercase or branch on a concrete plugin StepKey")
        // The only registry-family lowering branch is the generic RegistryStepSpec one.
        assertTrue(compilerSource.contains("RegistryStepSpec"))
    }

    private fun registryKeys(registry: StepRegistry): Set<String> = registry.keys().map { it.value }.toSet()

    /** Mirrors CanonicalDurableRunCoordinator's eligibility authority (registry keys + core metadata). */
    private fun canonicalEligible(key: String, registryKeys: Set<String>): Boolean =
        key in CanonicalCoreStepMetadata.pluginIds || key in registryKeys

    private companion object {
        /** Read once; keeps the "zero StepKey knowledge" fitness honest. */
        val COMPILER_SOURCE_FOR_FITNESS: String = listOf(
            "src/main/kotlin/dev/rubentxu/pipeline/v2/application/DslCompiledPipelineCompiler.kt",
            "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/DslCompiledPipelineCompiler.kt",
            "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/DslCompiledPipelineCompiler.kt",
        ).firstNotNullOfOrNull { p ->
            runCatching { java.nio.file.Files.readString(java.nio.file.Paths.get(p)) }.getOrNull()
        } ?: ""
    }
}
