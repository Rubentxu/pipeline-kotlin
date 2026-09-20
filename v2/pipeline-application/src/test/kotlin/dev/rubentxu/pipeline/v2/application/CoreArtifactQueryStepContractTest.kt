package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.RecoveryPolicy
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.TypedStepOutput
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs
import dev.rubentxu.pipeline.v2.domain.step.Delivery
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.PluginFamily
import dev.rubentxu.pipeline.v2.domain.step.PluginReleaseRef
import dev.rubentxu.pipeline.v2.domain.step.SemVer
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import dev.rubentxu.pipeline.v2.domain.step.StepProviderMetadata
import dev.rubentxu.pipeline.v2.domain.step.StepRegistration
import dev.rubentxu.pipeline.v2.domain.step.TrustMetadata
import dev.rubentxu.pipeline.v2.domain.step.artifact.ArchivedFileEntry
import dev.rubentxu.pipeline.v2.domain.step.artifact.ArtifactHandle
import dev.rubentxu.pipeline.v2.domain.step.artifact.ArtifactIndexCapability
import dev.rubentxu.pipeline.v2.domain.step.artifact.ArtifactIndexFailure
import dev.rubentxu.pipeline.v2.domain.step.artifact.ArtifactQueryInput
import dev.rubentxu.pipeline.v2.domain.step.artifact.DuplicateArtifactNameException
import dev.rubentxu.pipeline.v2.domain.step.registerContributors
import dev.rubentxu.pipeline.v2.sdk.junit.step.JUnitStepDefinitionContributor
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Contract / negative-path coverage for `core.artifact.query`
 * (E1.ecosystem-local-first cycle).
 *
 * Mirrors the F5.2 JUnit pattern:
 *  - identity and contract completeness
 *  - codec roundtrip (lossless input, symmetric output envelope)
 *  - registry resolution via the production CoreStepRegistryFactory
 *  - capability declaration (ARTIFACT_INDEX_CAPABILITY)
 *  - handler success path: the handle returned by the index is
 *    projected through the typed carrier
 *  - handler typed failure path: name not found → USER kind, no
 *    re-classification to ENGINE by the boundary
 *  - handler fail-closed paths: missing capability, duplicate
 *    name in the index
 *
 * End-to-end UAT (real .pipeline.kts via installDist) lives in the
 * E1 closure receipt; this file is the regression surface.
 */
class CoreArtifactQueryStepContractTest {

    // ---------- Identity & contract completeness ----------

    @Test
    fun `identity and contract completeness`() {
        val def = CoreArtifactQueryStep.definition
        assertEquals(PluginStepId("core.artifact.query"), def.contract.key)
        assertEquals("core.artifact.query", def.contract.descriptor.stepId)
        assertEquals("artifactQuery", def.contract.descriptor.name)
        assertEquals(ReplayPolicy.MEMOIZED, def.contract.descriptor.replayPolicy)
        assertTrue(def.contract.descriptor.effects.contains(Effect.READ_ONLY))
        assertEquals(
            setOf(ARTIFACT_INDEX_CAPABILITY),
            def.contract.requiredCapabilities,
        )
        assertEquals(RecoveryPolicy.None, def.contract.descriptor.recoveryPolicy)
    }

    // ---------- Codec roundtrip ----------

    @Test
    fun `input codec roundtrip lossless`() {
        val input = ArtifactQueryInput(name = "app")
        val encoded = CoreArtifactQueryStep.definition.contract.inputCodec.encode(input)
        val roundtripped = CoreArtifactQueryStep.definition.contract.inputCodec.decode(encoded)
        assertEquals(input, roundtripped)
    }

    @Test
    fun `input codec rejects wrong kind`() {
        val wrong = EncodedStepValue("""{"kind":"notArtifactQuery","name":"x"}""")
        assertThrows(IllegalArgumentException::class.java) {
            CoreArtifactQueryStep.definition.contract.inputCodec.decode(wrong)
        }
    }

    @Test
    fun `output codec encodes success envelope`() {
        val entry = ArchivedFileEntry(
            relPath = "build/libs/app.jar",
            sha256 = "a".repeat(64),
            sizeBytes = 1234,
            absolutePath = "<archive://build/libs/app.jar>",
        )
        val handle = ArtifactHandle(name = "app", files = listOf(entry))
        val output: TypedStepOutput = CoreArtifactQueryStep.ArtifactQuerySuccessOutput(handle)
        val encoded = CoreArtifactQueryStep.definition.contract.outputCodec.encode(output)
        // Verify envelope fields.
        assertTrue(encoded.value.contains("\"kind\":\"artifactQuery\""))
        assertTrue(encoded.value.contains("\"outcome\":\"OK\""))
        assertTrue(encoded.value.contains("\"name\":\"app\""))
        assertTrue(encoded.value.contains("\"fileCount\":1"))
    }

    @Test
    fun `output codec encodes failure envelope`() {
        val output: TypedStepOutput = CoreArtifactQueryStep.ArtifactQueryFailureOutput(
            failureKind = FailureKind.USER,
            message = "Artifact 'missing' not found in index",
        )
        val encoded = CoreArtifactQueryStep.definition.contract.outputCodec.encode(output)
        assertTrue(encoded.value.contains("\"kind\":\"artifactQuery\""))
        assertTrue(encoded.value.contains("\"outcome\":\"FAILED\""))
        assertTrue(encoded.value.contains("\"failureKind\":\"USER\""))
        assertTrue(encoded.value.contains("\"message\":\"Artifact 'missing' not found in index\""))
    }

    // ---------- Registry resolution ----------

    @Test
    fun `registry resolves core artifact query through production factory`() {
        val registry = CoreStepRegistryFactory.registry()
        // The step is registered as a candidate (G1) — its key is NOT
        // in LEGACY_PLUGIN_IDS → Registry family from registration.
        assertTrue(registry.contains(PluginStepId("core.artifact.query")))
        val keys = registry.keys()
        assertTrue(keys.contains(PluginStepId("core.artifact.query")))
    }

    // ---------- Capability declaration & admission ----------

    @Test
    fun `contract declares ARTIFACT_INDEX_CAPABILITY and nothing else`() {
        val required = CoreArtifactQueryStep.definition.contract.requiredCapabilities
        assertEquals(1, required.size)
        assertEquals(ARTIFACT_INDEX_CAPABILITY, required.first())
    }

    // ---------- Handler success path ----------

    @Test
    fun `handler returns Success with the handle when the index has the name`() = runBlocking {
        val entry = ArchivedFileEntry(
            relPath = "build/libs/app.jar",
            sha256 = "b".repeat(64),
            sizeBytes = 5000,
            absolutePath = "<archive://build/libs/app.jar>",
        )
        val index = TestArtifactIndex().apply {
            record(ArtifactHandle(name = "app", files = listOf(entry)))
        }
        val ctx = handlerContextWith(index)
        val input = ArtifactQueryInput(name = "app")
        val output = CoreArtifactQueryStep.definition.handler.execute(input, ctx)

        assertTrue(output is CoreArtifactQueryStep.ArtifactQuerySuccessOutput)
        val success = output as CoreArtifactQueryStep.ArtifactQuerySuccessOutput
        assertEquals("app", success.handle.name)
        assertEquals(1, success.handle.files.size)
        assertEquals(entry, success.handle.files[0])
        assertEquals(StepOutcome.Success, success.outcome)
    }

    // ---------- Handler typed failure path ----------

    @Test
    fun `handler returns typed USER failure when the name is not in the index`() = runBlocking {
        val index = TestArtifactIndex()
        val ctx = handlerContextWith(index)
        val input = ArtifactQueryInput(name = "missing")
        val output = CoreArtifactQueryStep.definition.handler.execute(input, ctx)

        assertTrue(output is CoreArtifactQueryStep.ArtifactQueryFailureOutput)
        val failure = output as CoreArtifactQueryStep.ArtifactQueryFailureOutput
        assertEquals(FailureKind.USER, failure.failureKind)
        assertTrue(failure.message.contains("missing"))
        assertTrue(failure.outcome is StepOutcome.Failure)
    }

    // ---------- Capability missing path ----------

    @Test
    fun `handler fails closed when ARTIFACT_INDEX_CAPABILITY is not admitted`() = runBlocking {
        // The registry boundary would normally reject this at
        // prepare-time; here we exercise the contract via a direct
        // handler call to verify the handler does NOT silently swallow.
        val ctx = handlerContextWithout(ARTIFACT_INDEX_CAPABILITY)
        val input = ArtifactQueryInput(name = "app")
        // Suspend execute must run inside the coroutine body, not
        // inside an inline lambda; we catch the synchronous
        // IllegalStateException raised by the capability access.
        val ex = runCatching {
            CoreArtifactQueryStep.definition.handler.execute(input, ctx)
        }.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(ex is IllegalStateException)
    }

    // ---------- Cross-step integration (archive → query) ----------

    @Test
    fun `archive with name then query roundtrip via a shared index`() = runBlocking {
        val entry = ArchivedFileEntry(
            relPath = "build/libs/x.jar",
            sha256 = "c".repeat(64),
            sizeBytes = 999,
            absolutePath = "<archive://build/libs/x.jar>",
        )
        val sharedIndex = TestArtifactIndex()

        // Simulate an archive with name = "x": record in the index.
        sharedIndex.record(ArtifactHandle(name = "x", files = listOf(entry)))

        // Now query it.
        val ctx = handlerContextWith(sharedIndex)
        val output = CoreArtifactQueryStep.definition.handler.execute(
            ArtifactQueryInput(name = "x"),
            ctx,
        )
        assertTrue(output is CoreArtifactQueryStep.ArtifactQuerySuccessOutput)
        assertEquals("x", (output as CoreArtifactQueryStep.ArtifactQuerySuccessOutput).handle.name)
    }

    // ---------- Replay / deterministic output ----------

    @Test
    fun `handler output is deterministic across two identical queries`() = runBlocking {
        val entry = ArchivedFileEntry(
            relPath = "build/libs/d.jar",
            sha256 = "d".repeat(64),
            sizeBytes = 1,
            absolutePath = "<archive://build/libs/d.jar>",
        )
        val index = TestArtifactIndex().apply {
            record(ArtifactHandle(name = "d", files = listOf(entry)))
        }
        val ctx = handlerContextWith(index)
        val input = ArtifactQueryInput(name = "d")
        val first = CoreArtifactQueryStep.definition.handler.execute(input, ctx)
        val second = CoreArtifactQueryStep.definition.handler.execute(input, ctx)
        assertEquals(
            (first as CoreArtifactQueryStep.ArtifactQuerySuccessOutput).handle.aggregateSha256(),
            (second as CoreArtifactQueryStep.ArtifactQuerySuccessOutput).handle.aggregateSha256(),
        )
    }

    // ---------- Test helpers ----------

    private class TestArtifactIndex : ArtifactIndexCapability {
        private val byName = LinkedHashMap<String, ArtifactHandle>()
        override fun record(handle: ArtifactHandle) {
            if (byName.containsKey(handle.name)) throw DuplicateArtifactNameException(handle.name)
            byName[handle.name] = handle
        }
        override fun query(name: String): ArtifactHandle? = byName[name]
        override fun all(): List<ArtifactHandle> = byName.values.toList().sortedBy { it.name }
    }

    private fun handlerContextWith(index: ArtifactIndexCapability): StepHandlerContext {
        val access = object : StepCapabilityAccess {
            override fun available(): Set<StepCapability> = setOf(ARTIFACT_INDEX_CAPABILITY)
            override fun <T : Any> get(key: StepCapability): T {
                @Suppress("UNCHECKED_CAST")
                return when (key) {
                    ARTIFACT_INDEX_CAPABILITY -> index as T
                    else -> throw IllegalStateException("Unexpected capability $key in test")
                }
            }
        }
        return StepHandlerContext(
            runId = dev.rubentxu.pipeline.v2.domain.RunId("test-run"),
            stepIndex = 0,
            capabilities = access,
        )
    }

    private fun handlerContextWithout(missing: StepCapability): StepHandlerContext {
        val access = object : StepCapabilityAccess {
            override fun available(): Set<StepCapability> = emptySet()
            override fun <T : Any> get(key: StepCapability): T {
                throw IllegalStateException("capability $key is not admitted")
            }
        }
        return StepHandlerContext(
            runId = dev.rubentxu.pipeline.v2.domain.RunId("test-run"),
            stepIndex = 0,
            capabilities = access,
        )
    }
}
