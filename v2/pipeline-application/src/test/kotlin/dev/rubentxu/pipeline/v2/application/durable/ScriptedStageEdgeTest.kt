package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.CompiledPipelineValidator
import dev.rubentxu.pipeline.v2.domain.DefinitionId
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.ScriptedStageRef
import dev.rubentxu.pipeline.v2.domain.SourceDescriptor
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StageId
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.scripting.CompiledScriptedEntryPoint
import dev.rubentxu.pipeline.v2.scripting.ScriptedArtifactIdentity
import dev.rubentxu.pipeline.v2.scripting.ScriptedStepFacade
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * S4-F2 — the scripted-stage EDGE: from an identity in the IR to an executable artifact.
 *
 * ## What this covers, and what it deliberately does not
 *
 * It covers the resolution, which is the part that must be right before anything is allowed to
 * run: a name in `:pipeline-domain` is turned into a host-compiled object, and every way that
 * can go wrong is a NAMED refusal rather than a degradation.
 *
 * It does **not** cover execution. Whether a scripted stage may run inside a canonical run, and
 * what such a stage does to the canonical replay cursor, is a durable-protocol decision that
 * ADR-0103 D7 explicitly leaves open. Building the fork before that decision would have written
 * resume semantics by analogy, and a resume is exactly the thing that fails silently.
 *
 * ## Fidelity
 *
 * HF0 Pure Contract. The subject under test is a decision over names, so the subject is
 * exercised as a decision: the artifacts are hand-built `CompiledScriptedEntryPoint` doubles whose
 * `execute` is unreachable from these rows, and nothing here re-derives a fingerprint — the
 * registry computes it with the production `fingerprintMaterial()` and compares it to the
 * reference's key.
 */
class ScriptedStageEdgeTest {

    /** A hand-built entry point. `execute` is not reachable from these rows by design. */
    private class FakeEntryPoint(
        override val artifact: ScriptedArtifactIdentity,
        override val entryPointId: String,
    ) : CompiledScriptedEntryPoint {
        override suspend fun execute(steps: ScriptedStepFacade) = throw AssertionError(
            "this row is about resolution, not execution",
        )
    }

    private fun identity(sourceDigest: String = "source-digest") = ScriptedArtifactIdentity(
        sourceDigest = sourceDigest,
        dslApiVersion = "dsl-v1",
        compilerAdapterVersion = "compiler-v1",
        runtimeCompatibilityVersion = "r3-runtime-v1",
        pluginLockDigest = "plugin-lock",
        facadeSchemaDigest = "facade-digest",
    )

    private fun refTo(artifact: ScriptedArtifactIdentity, entryPointId: String = "main") = ScriptedStageRef(
        artifactKey = artifact.fingerprintMaterial(),
        entryPointId = entryPointId,
    )

    // ------------------------------------------------------------- the happy path

    @Test
    fun `the ref the IR carries resolves to the artifact whose identity produced it`() {
        val identity = identity()
        val registry = InMemoryScriptedStageRegistry(
            listOf(FakeEntryPoint(artifact = identity, entryPointId = "main")),
        )

        val resolution = registry.resolve(refTo(identity))

        val resolved = assertInstanceOf(
            ScriptedStageResolution.Resolved::class.java,
            resolution,
            "a named artifact that is registered and identical must resolve: $resolution",
        )
        assertEquals("main", resolved.entryPoint.entryPointId)
        assertEquals(identity, resolved.entryPoint.artifact, "and it must be the very object, not a copy")
    }

    @Test
    fun `the IR needs no copy of the identity because the key is the identity's own fingerprint`() {
        // The whole reason the edge can exist is that the key is DERIVED, not re-declared. If
        // ScriptedStageRef ever grew the six identity fields, the IR would own an identity the
        // artifact also owns, and the two would drift with nothing able to see it.
        val identity = identity()
        val ref = refTo(identity)

        assertEquals(
            identity.fingerprintMaterial(),
            ref.artifactKey,
            "the ref's key is the identity's fingerprint, computed by the scripting API",
        )
    }

    // --------------------------------------------------- the three named refusals

    @Test
    fun `a ref naming an artifact nobody compiled is refused by name`() {
        val identity = identity()
        val registry = InMemoryScriptedStageRegistry(
            listOf(FakeEntryPoint(artifact = identity, entryPointId = "main")),
        )

        val refusal = assertInstanceOf(
            ScriptedStageResolution.UnknownArtifact::class.java,
            registry.resolve(refTo(identity(sourceDigest = "a-different-source"))),
            "a different source is a different artifact, and it was never compiled here",
        )
        assertEquals(
            setOf(identity.fingerprintMaterial()),
            registry.registeredKeys(),
            "the refusal names what IS available, so the repair is a compile rather than a hunt",
        )
        assertTrue(refusal.artifactKey.isNotBlank())
    }

    @Test
    fun `a ref naming a missing entry point is refused apart from an unknown artifact`() {
        // These two must not collapse into one case: the artifact is fine and the pipeline is
        // wrong, which is a different repair from "this artifact does not exist". A single
        // boolean would force the caller to re-derive the distinction to print a useful message.
        val identity = identity()
        val registry = InMemoryScriptedStageRegistry(
            listOf(FakeEntryPoint(artifact = identity, entryPointId = "main")),
        )

        val refusal = assertInstanceOf(
            ScriptedStageResolution.EntryPointMissing::class.java,
            registry.resolve(refTo(identity, entryPointId = "notAnEntryPoint")),
            "the artifact exists and does not have that entry point",
        )
        assertEquals("notAnEntryPoint", refusal.entryPointId, "the refusal names the entry point asked for")
    }

    @Test
    fun `a registry's keys ARE its objects' identities, so a misfile is unrepresentable`() {
        // The dangerous shape is a registry answering with an object it holds under a key it was
        // given rather than one it derived. This row pins the property that makes it impossible:
        // the key is the object's own fingerprint, so the map cannot disagree with itself. Keying
        // by anything else — the entry point id, the position in the list — kills this row, which
        // is the point: the guarantee is structural and the row is what keeps it structural.
        val artifacts = listOf(
            FakeEntryPoint(artifact = identity(sourceDigest = "source-a"), entryPointId = "main"),
            FakeEntryPoint(artifact = identity(sourceDigest = "source-b"), entryPointId = "main"),
        )
        val registry = InMemoryScriptedStageRegistry(artifacts)

        assertEquals(
            artifacts.map { it.artifact.fingerprintMaterial() }.toSet(),
            registry.registeredKeys(),
            "every registered key is the fingerprint of the object filed under it, and nothing else",
        )
        for (artifact in artifacts) {
            val resolved = assertInstanceOf(
                ScriptedStageResolution.Resolved::class.java,
                registry.resolve(refTo(artifact.artifact)),
                "each artifact resolves under its own key: ${artifact.artifact.sourceDigest}",
            )
            assertEquals(artifact.artifact, resolved.entryPoint.artifact, "and to itself, not a neighbour")
        }
        assertNotEquals(
            artifacts[0].artifact.fingerprintMaterial(),
            artifacts[1].artifact.fingerprintMaterial(),
            "two sources are two identities, which is what makes the map's keys unambiguous",
        )
    }

    @Test
    fun `a recompiled artifact that is not the planned one does not resolve`() {
        // The resume case, and the reason the key is derived. A restarted process recompiles the
        // artifact; if it is not byte-identical it must NOT be found under the key the run was
        // planned against, or the resumed run would silently execute something nobody reviewed.
        val planned = identity(sourceDigest = "planned-source")
        val recompiled = identity(sourceDigest = "recompiled-source")
        val registry = InMemoryScriptedStageRegistry(
            listOf(FakeEntryPoint(artifact = recompiled, entryPointId = "main")),
        )

        assertInstanceOf(
            ScriptedStageResolution.UnknownArtifact::class.java,
            registry.resolve(refTo(planned)),
            "the planned key finds nothing, because the recompiled artifact is a different identity",
        )
        assertInstanceOf(
            ScriptedStageResolution.Resolved::class.java,
            registry.resolve(refTo(recompiled)),
            "and the artifact that IS present still resolves under its own key",
        )
    }

    @Test
    fun `two artifacts claiming one identity are refused at registration rather than by order`() {
        // Last-writer-wins would make WHICH artifact runs depend on registration order, which is
        // the definition of an undebuggable. The identity is the address; a duplicate address is a
        // build defect and it says so.
        val identity = identity()
        val duplicate = FakeEntryPoint(artifact = identity, entryPointId = "second")

        val failure = runCatching {
            InMemoryScriptedStageRegistry(
                listOf(
                    FakeEntryPoint(artifact = identity, entryPointId = "main"),
                    duplicate,
                ),
            )
        }.exceptionOrNull()

        assertInstanceOf(
            IllegalArgumentException::class.java,
            failure,
            "a duplicate fingerprint is refused, not silently merged: $failure",
        )
        assertTrue(
            failure?.message.orEmpty().contains("second"),
            "and the message names the colliding entry point: ${failure?.message}",
        )
    }

    // ------------------------------------------------- the IR carries it, and validates

    @Test
    fun `a scripted stage body survives the IR and validation as identity only`() {
        // The IR is a serialised graph, so the row that matters is the round trip: a scripted
        // stage must come back out of JSON as a REF, with the six identity fields absent, and the
        // pipeline validator must accept it. A ref that only works in memory is not an IR case.
        val identity = identity()
        val pipeline = CompiledPipeline(
            id = DefinitionId("scripted-pipeline"),
            source = SourceDescriptor("scripted-pipeline.pipeline.kts", Digest("scripted-pipeline")),
            pluginLockDigest = Digest("plugin-lock"),
            stages = listOf(
                StageNode(
                    id = StageId("deploy"),
                    name = "deploy",
                    options = emptyList(),
                    body = StageBody.Scripted(refTo(identity)),
                    post = null,
                ),
            ),
        )

        val json = Json.encodeToString(CompiledPipeline.serializer(), pipeline)
        val decoded = Json.decodeFromString(CompiledPipeline.serializer(), json)

        val body = decoded.stages.single().body
        val scripted = assertInstanceOf(
            StageBody.Scripted::class.java,
            body,
            "the scripted body must round-trip through the IR: $body",
        )
        assertEquals(identity.fingerprintMaterial(), scripted.ref.artifactKey)
        assertEquals("main", scripted.ref.entryPointId)
        assertDoesNotThrow(
            { CompiledPipelineValidator.validate(decoded) },
            "and the validator must accept a scripted stage, validated BY CONSTRUCTION",
        )
    }

    @Test
    fun `a scripted stage is still fail-closed before the edge ever runs`() {
        // The edge refusing is the second line of defence. The first is that a blank key or a
        // blank entry point cannot exist in a validated pipeline, so a malformed ref never reaches
        // a registry at all.
        val blankKey = runCatching { ScriptedStageRef(artifactKey = "  ", entryPointId = "main") }.exceptionOrNull()
        val blankEntry = runCatching { ScriptedStageRef(artifactKey = "key", entryPointId = "") }.exceptionOrNull()

        assertInstanceOf(IllegalArgumentException::class.java, blankKey, "a blank artifact key is not an identity")
        assertInstanceOf(IllegalArgumentException::class.java, blankEntry, "a blank entry point id is not a name")
    }
}
