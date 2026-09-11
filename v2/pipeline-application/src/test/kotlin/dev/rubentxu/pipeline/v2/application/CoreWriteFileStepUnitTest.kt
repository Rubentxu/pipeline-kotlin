package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * G1 registry seam proof for `core.file.writeFile` (S2-A3).
 *
 * Proves the candidate registration contract holds WITHOUT the legacy canonical-core
 * decode/dispatch path: codec round-trip, key uniqueness, descriptor metadata
 * byte-equivalence, canonical envelope byte-identity, and real workspace writes through
 * the [WorkspaceOperations] adapter (binding the certified `FileWriteExecutor` substrate).
 */
@Timeout(30)
class CoreWriteFileStepUnitTest {

    @TempDir
    lateinit var tempDir: java.nio.file.Path

    // ===== identity =====

    @Test
    fun `identity — KEY is core dot file dot writeFile and duplicate registration fails`() {
        assertEquals(dev.rubentxu.pipeline.v2.domain.PluginStepId("core.file.writeFile"), CoreWriteFileStep.KEY)
        val r = InMemoryStepRegistry().apply { CoreWriteFileStep.registerInto(this) }
        assertTrue(
            runCatching { CoreWriteFileStep.registerInto(r) }.isFailure,
            "duplicate registration of core.file.writeFile must fail",
        )
    }

    // ===== contract =====

    @Test
    fun `contract — descriptor metadata is byte-equivalent to the legacy row`() {
        val c = CoreWriteFileStep.definition.contract
        assertEquals("core.file.writeFile", c.descriptor.stepId)
        assertEquals("writeFile", c.descriptor.name)
        assertEquals(setOf(Effect.WRITES_WORKSPACE), c.descriptor.effects.toSet())
        assertEquals(ReplayPolicy.MEMOIZED, c.descriptor.replayPolicy)
        assertEquals(setOf(WORKSPACE_OPERATIONS_CAPABILITY), c.requiredCapabilities)
        assertNotNull(c.inputCodec)
        assertNotNull(c.outputCodec)
    }

    // ===== codec =====

    @Test
    fun `codec input — round-trip preserves file, text, encoding`() {
        val input = CoreWriteFileInput("out.txt", "hello", "UTF-8")
        val encoded = CoreWriteFileStep.definition.contract.inputCodec.encode(input)
        assertEquals(
            """{"kind":"writeFile","file":"out.txt","text":"hello","encoding":"UTF-8"}""",
            encoded.value,
            "envelope MUST be byte-identical to the legacy dsl-v1 payload",
        )
        assertEquals(input, CoreWriteFileStep.definition.contract.inputCodec.decode(encoded))
    }

    @Test
    fun `codec input — decode rejects foreign kind and blank file`() {
        val foreign = dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue(
            """{"kind":"echo","text":"x"}""",
        )
        assertThrows(IllegalArgumentException::class.java) {
            CoreWriteFileStep.definition.contract.inputCodec.decode(foreign)
        }
        val blank = dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue(
            """{"kind":"writeFile","file":"  ","text":"x","encoding":"UTF-8"}""",
        )
        assertThrows(IllegalArgumentException::class.java) {
            CoreWriteFileStep.definition.contract.inputCodec.decode(blank)
        }
    }

    @Test
    fun `codec output — singleton round-trips and rejects non-SUCCESS`() {
        val encoded = CoreWriteFileStep.definition.contract.outputCodec.encode(CoreWriteFileOutput)
        assertEquals("""{"kind":"writeFile","outcome":"SUCCESS"}""", encoded.value)
        assertEquals(CoreWriteFileOutput, CoreWriteFileStep.definition.contract.outputCodec.decode(encoded))
        val bad = dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue(
            """{"kind":"writeFile","outcome":"FAILURE"}""",
        )
        assertThrows(IllegalArgumentException::class.java) {
            CoreWriteFileStep.definition.contract.outputCodec.decode(bad)
        }
    }

    // ===== adapter (real workspace write through the capability seam) =====

    @Test
    fun `adapter — writes atomically into the stage workspace and returns result`() {
        val root = Files.createTempDirectory(tempDir, "wsops-")
        val ops = WorkspaceOperationsAdapter(
            stageName = "build",
            stageIndex = 0,
            controlDirRoot = root,
            eventSink = dev.rubentxu.pipeline.v2.events.NullEventSink,
        )
        val result = ops.writeFile("out/nested.txt", "hello", "UTF-8")
        val written = root.resolve("workspace").resolve("build-0").resolve("out").resolve("nested.txt")
        assertTrue(java.nio.file.Files.exists(written), "file must exist inside the stage workspace")
        assertEquals("hello", written.readText())
        assertEquals(written.toAbsolutePath().normalize(), result.path.toAbsolutePath().normalize())
        assertEquals(5L, result.size)
        assertTrue(result.atomicallyMoved, "write must be atomic (temp+rename)")
        assertTrue(result.sha256.isNotBlank())
    }

    @Test
    fun `adapter — rejects path traversal and reserved dot-v2 targets fail-closed`() {
        val root = Files.createTempDirectory(tempDir, "wsops-guard-")
        val ops = WorkspaceOperationsAdapter("build", 0, root, dev.rubentxu.pipeline.v2.events.NullEventSink)
        assertThrows(IllegalArgumentException::class.java) { ops.writeFile("../escape.txt", "x", "UTF-8") }
        assertThrows(IllegalArgumentException::class.java) { ops.writeFile(".v2/secret.txt", "x", "UTF-8") }
    }

    @Test
    fun `adapter — requires controlDirRoot`() {
        val ops = WorkspaceOperationsAdapter("build", 0, null, dev.rubentxu.pipeline.v2.events.NullEventSink)
        assertThrows(IllegalStateException::class.java) { ops.writeFile("f.txt", "x", "UTF-8") }
    }

    // ===== handler through capability access =====

    @Test
    fun `handler — writes through the capability and returns typed success output`() {
        val root = Files.createTempDirectory(tempDir, "wsops-handler-")
        val ops = WorkspaceOperationsAdapter("st", 3, root, dev.rubentxu.pipeline.v2.events.NullEventSink)
        val access = object : dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess {
            override fun available(): Set<dev.rubentxu.pipeline.v2.domain.step.StepCapability> =
                setOf(WORKSPACE_OPERATIONS_CAPABILITY)

            @Suppress("UNCHECKED_CAST")
            override fun <T : Any> get(key: dev.rubentxu.pipeline.v2.domain.step.StepCapability): T {
                require(key == WORKSPACE_OPERATIONS_CAPABILITY) { "unavailable: $key" }
                return ops as T
            }
        }
        val ctx = dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext(
            runId = dev.rubentxu.pipeline.v2.domain.RunId("wf-g1"),
            stepIndex = 0,
            capabilities = access,
        )
        val out = kotlinx.coroutines.runBlocking {
            CoreWriteFileStep.definition.handler.execute(CoreWriteFileInput("h.txt", "via-handler", "UTF-8"), ctx)
        }
        assertEquals(CoreWriteFileOutput, out)
        val written = root.resolve("workspace").resolve("st-3").resolve("h.txt")
        assertEquals("via-handler", written.readText())
    }
}
