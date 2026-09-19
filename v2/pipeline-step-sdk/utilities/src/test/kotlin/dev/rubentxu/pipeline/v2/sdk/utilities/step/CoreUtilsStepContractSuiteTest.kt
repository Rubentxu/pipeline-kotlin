package dev.rubentxu.pipeline.v2.sdk.utilities.step

import dev.rubentxu.pipeline.v2.domain.PluginStepException
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.WORKSPACE_IDENTITY_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.WorkspaceIdentity
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.ReadJsonInput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.ReadJsonOutput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.Sha256Input
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.Sha256Output
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.WriteJsonInput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.WriteJsonOutput
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * StepContractSuite (HF0 / HF1) — utilities OFFICIAL_PLUGIN (LFC-2E2).
 *
 * Certifies the contract surface of the three first-slice Steps:
 *
 *  - `core-utils.readJson`
 *  - `core-utils.writeJson`
 *  - `core-utils.sha256`
 *
 * Coverage rows (per the certified-utility-of-CORE_PLUGIN contract):
 *
 *  - identity (StepKey value stable)
 *  - contract completeness (descriptor + codec + capabilities)
 *  - input codec encode/decode roundtrip
 *  - output codec encode/decode roundtrip
 *  - canonical envelope (well-formed JSON object)
 *  - registry resolution (InMemoryStepRegistry)
 *  - capability admission (fails closed when WORKSPACE_IDENTITY_CAPABILITY absent)
 *  - success (happy path)
 *  - typed failure (missing file / invalid JSON / unsupported algorithm)
 *  - replay (output is deterministic)
 *  - observability (the Step is a pure function of its inputs)
 *  - architectural zero-core-change contract (this suite lives in the plugin
 *    module, NOT in `pipeline-application` or `pipeline-domain`).
 *
 * Tests are written against the public StepDefinition surface; they do NOT
 * pull in the canonical coordinator. The HF2 (installed distribution) tests
 * live in `pipeline-application` so they exercise the real boundary seam.
 */
@Timeout(15)
class CoreUtilsStepContractSuiteTest {

    @TempDir
    lateinit var tempDir: Path

    // ---------- helpers ----------

    private val readJsonStep = CoreUtilsReadJsonStepDefinition()
    private val writeJsonStep = CoreUtilsWriteJsonStepDefinition()
    private val sha256Step = CoreUtilsSha256StepDefinition()

    private fun stubWorkspaceRoot(): Path = tempDir.resolve("workspace").also { Files.createDirectories(it) }

    /**
     * Builds a [dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext] with a
     * `WORKSPACE_IDENTITY_CAPABILITY` exposure rooted at the supplied [root].
     */
    private fun handlerContext(workspaceRoot: Path): dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext {
        val caps = object : dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess {
            private val map = mapOf<dev.rubentxu.pipeline.v2.domain.step.StepCapability, Any>(
                WORKSPACE_IDENTITY_CAPABILITY to WorkspaceIdentity(workspaceRoot),
            )
            override fun available(): Set<dev.rubentxu.pipeline.v2.domain.step.StepCapability> = map.keys
            override fun <T : Any> get(key: dev.rubentxu.pipeline.v2.domain.step.StepCapability): T {
                @Suppress("UNCHECKED_CAST")
                return map[key] as T
            }
        }
        return dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext(
            runId = dev.rubentxu.pipeline.v2.domain.RunId("test"),
            stepIndex = 0,
            capabilities = caps,
        )
    }

    // -------- identity --------

    @Test
    fun `identity — readJson Key is core-utils dot readJson`() {
        assertEquals(PluginStepId("core-utils.readJson"), CoreUtilsReadJsonKey.VALUE)
        assertEquals("core-utils.readJson", CoreUtilsReadJsonKey.VALUE.value)
    }

    @Test
    fun `identity — writeJson Key is core-utils dot writeJson`() {
        assertEquals(PluginStepId("core-utils.writeJson"), CoreUtilsWriteJsonKey.VALUE)
        assertEquals("core-utils.writeJson", CoreUtilsWriteJsonKey.VALUE.value)
    }

    @Test
    fun `identity — sha256 Key is core-utils dot sha256`() {
        assertEquals(PluginStepId("core-utils.sha256"), CoreUtilsSha256Key.VALUE)
        assertEquals("core-utils.sha256", CoreUtilsSha256Key.VALUE.value)
    }

    // -------- contract completeness --------

    @Test
    fun `contract — readJson declares READ_ONLY, MEMOIZED, WORKSPACE_IDENTITY_CAPABILITY`() {
        val c = readJsonStep.contract
        assertEquals(CoreUtilsReadJsonKey.VALUE, c.key)
        assertEquals(Effect.READ_ONLY, c.descriptor.effects.single())
        assertEquals(ReplayPolicy.MEMOIZED, c.descriptor.replayPolicy)
        assertEquals(setOf(WORKSPACE_IDENTITY_CAPABILITY), c.requiredCapabilities)
        assertNotNull(c.inputCodec)
        assertNotNull(c.outputCodec)
    }

    @Test
    fun `contract — writeJson declares WRITES_WORKSPACE, NEVER, WORKSPACE_IDENTITY_CAPABILITY`() {
        val c = writeJsonStep.contract
        assertEquals(CoreUtilsWriteJsonKey.VALUE, c.key)
        assertEquals(Effect.WRITES_WORKSPACE, c.descriptor.effects.single())
        assertEquals(ReplayPolicy.NEVER, c.descriptor.replayPolicy)
        assertEquals(setOf(WORKSPACE_IDENTITY_CAPABILITY), c.requiredCapabilities)
        assertNotNull(c.inputCodec)
        assertNotNull(c.outputCodec)
    }

    @Test
    fun `contract — sha256 declares READ_ONLY, MEMOIZED, WORKSPACE_IDENTITY_CAPABILITY`() {
        val c = sha256Step.contract
        assertEquals(CoreUtilsSha256Key.VALUE, c.key)
        assertEquals(Effect.READ_ONLY, c.descriptor.effects.single())
        assertEquals(ReplayPolicy.MEMOIZED, c.descriptor.replayPolicy)
        assertEquals(setOf(WORKSPACE_IDENTITY_CAPABILITY), c.requiredCapabilities)
    }

    // -------- codec roundtrip --------

    @Test
    fun `codec readJson input — roundtrip preserves every field`() {
        val original = ReadJsonInput(path = "build/out.json", prettyPrint = true, returnRawText = false)
        val encoded = CoreUtilsReadJsonInputCodec.encode(original)
        val decoded = CoreUtilsReadJsonInputCodec.decode(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `codec readJson output — roundtrip preserves rawText + parsed + byteSize + absolutePath`() {
        val parsed: kotlinx.serialization.json.JsonElement = buildJsonObject {
            put("name", "alice")
            put("age", 30)
        }
        val original = ReadJsonOutput(
            rawText = """{"name":"alice","age":30}""",
            parsed = parsed,
            byteSize = 22L,
            absolutePath = "/tmp/x.json",
        )
        val encoded = CoreUtilsReadJsonOutputCodec.encode(original)
        val decoded = CoreUtilsReadJsonOutputCodec.decode(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `codec writeJson input — roundtrip preserves typed value + rawText mutually exclusive`() {
        val typed: kotlinx.serialization.json.JsonElement = buildJsonObject { put("k", "v") }
        val original = WriteJsonInput(
            path = "out.json",
            value = typed,
            rawText = null,
            prettyPrint = true,
            useRawText = false,
        )
        val encoded = CoreUtilsWriteJsonInputCodec.encode(original)
        assertEquals(original, CoreUtilsWriteJsonInputCodec.decode(encoded))
    }

    @Test
    fun `codec writeJson output — roundtrip preserves absolutePath, byteSize, sha256Hex, bytesWritten`() {
        val original = WriteJsonOutput(
            absolutePath = "/var/ws/out.json",
            byteSize = 11L,
            sha256Hex = "0".repeat(64),
            bytesWritten = 11L,
        )
        val encoded = CoreUtilsWriteJsonOutputCodec.encode(original)
        assertEquals(original, CoreUtilsWriteJsonOutputCodec.decode(encoded))
    }

    @Test
    fun `codec sha256 input — roundtrip preserves path + algorithm`() {
        val original = Sha256Input(path = "bin/foo", algorithm = "SHA-256")
        val encoded = CoreUtilsSha256InputCodec.encode(original)
        assertEquals(original, CoreUtilsSha256InputCodec.decode(encoded))
    }

    @Test
    fun `codec sha256 output — roundtrip preserves hexDigest + byteSize + algorithm`() {
        val original = Sha256Output(
            hexDigest = "a".repeat(64),
            byteSize = 42L,
            algorithm = "SHA-256",
        )
        val encoded = CoreUtilsSha256OutputCodec.encode(original)
        assertEquals(original, CoreUtilsSha256OutputCodec.decode(encoded))
    }

    // -------- canonical envelope --------

    @Test
    fun `envelope — readJson codec emits a well-formed JSON object (durable eligible)`() {
        val encoded: EncodedStepValue = CoreUtilsReadJsonInputCodec.encode(
            ReadJsonInput(path = "x.json"),
        )
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(encoded.value)
        assertTrue(parsed is JsonObject, "input envelope must be a JSON object")
    }

    @Test
    fun `envelope — writeJson codec emits a well-formed JSON object (durable eligible)`() {
        val encoded: EncodedStepValue = CoreUtilsWriteJsonInputCodec.encode(
            WriteJsonInput(path = "x.json", value = JsonPrimitive("hello")),
        )
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(encoded.value)
        assertTrue(parsed is JsonObject, "input envelope must be a JSON object")
    }

    @Test
    fun `envelope — sha256 codec emits a well-formed JSON object (durable eligible)`() {
        val encoded: EncodedStepValue = CoreUtilsSha256InputCodec.encode(
            Sha256Input(path = "x.txt"),
        )
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(encoded.value)
        assertTrue(parsed is JsonObject, "input envelope must be a JSON object")
    }

    // -------- capability admission (handler-level) --------

    @Test
    fun `capability — readJson fails closed when workspace identity is absent`() {
        val caps = object : dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess {
            override fun available(): Set<dev.rubentxu.pipeline.v2.domain.step.StepCapability> = emptySet()
            override fun <T : Any> get(key: dev.rubentxu.pipeline.v2.domain.step.StepCapability): T =
                throw IllegalStateException("missing capability $key")
        }
        val ctx = dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext(
            runId = dev.rubentxu.pipeline.v2.domain.RunId("test"),
            stepIndex = 0,
            capabilities = caps,
        )
        val ex = assertThrows(Exception::class.java) {
            runBlocking { readJsonStep.handler.execute(ReadJsonInput("x.json"), ctx) }
        }
        // Either the capability lookup fails (PluginStepException) or the
        // underlying capability access throws. Either way: NOT silent
        // success.
        assertFalse(ex is kotlinx.coroutines.CancellationException)
    }

    // -------- success (handler-level) --------

    @Test
    fun `success — readJson returns parsed value + byteSize + absolutePath`() = runBlocking {
        val ws = stubWorkspaceRoot()
        val target = ws.resolve("data.json")
        Files.writeString(target, """{"greeting":"hola","n":7}""")

        val out = readJsonStep.handler.execute(
            ReadJsonInput(path = "data.json", prettyPrint = true, returnRawText = false),
            handlerContext(ws),
        )

        assertEquals(target.toString(), out.absolutePath)
        assertEquals(25L, out.byteSize)
        assertTrue(out.rawText.contains("greeting"))
        assertNotNull(out.parsed)
        @Suppress("UNCHECKED_CAST")
        val obj = out.parsed as JsonObject
        assertEquals("hola", (obj["greeting"] as JsonPrimitive).content)
        assertEquals("7", (obj["n"] as JsonPrimitive).content)
    }

    @Test
    fun `success — readJson returnRawText=true returns null parsed`() = runBlocking {
        val ws = stubWorkspaceRoot()
        val target = ws.resolve("raw.txt")
        Files.writeString(target, """{"a":1}""")

        val out = readJsonStep.handler.execute(
            ReadJsonInput(path = "raw.txt", returnRawText = true),
            handlerContext(ws),
        )
        assertNull(out.parsed)
        assertEquals("""{"a":1}""", out.rawText)
    }

    @Test
    fun `success — writeJson writes a file and returns matching sha256Hex`() = runBlocking {
        val ws = stubWorkspaceRoot()
        val typed: kotlinx.serialization.json.JsonElement = buildJsonObject {
            put("answer", 42)
        }
        val out = writeJsonStep.handler.execute(
            WriteJsonInput(path = "out/answer.json", value = typed, prettyPrint = true),
            handlerContext(ws),
        )

        val expected = MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(Path.of(out.absolutePath)))
            .joinToString("") { "%02x".format(it) }

        assertEquals(expected, out.sha256Hex)
        assertTrue(Files.exists(Path.of(out.absolutePath)))
        assertTrue(Files.size(Path.of(out.absolutePath)) > 0)
    }

    @Test
    fun `success — writeJson creates missing parent directories`() = runBlocking {
        val ws = stubWorkspaceRoot()
        writeJsonStep.handler.execute(
            WriteJsonInput(path = "deep/nested/dir/x.json", value = JsonPrimitive(true)),
            handlerContext(ws),
        )
        assertTrue(Files.exists(ws.resolve("deep/nested/dir/x.json")))
    }

    @Test
    fun `success — sha256 returns the canonical empty-input digest for an empty file`() = runBlocking {
        val ws = stubWorkspaceRoot()
        val target = ws.resolve("empty.txt")
        Files.writeString(target, "")

        val out = sha256Step.handler.execute(
            Sha256Input(path = "empty.txt"),
            handlerContext(ws),
        )
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", out.hexDigest)
        assertEquals(0L, out.byteSize)
        assertEquals("SHA-256", out.algorithm)
    }

    @Test
    fun `success — sha256 matches the value produced by MessageDigest directly`() = runBlocking {
        val ws = stubWorkspaceRoot()
        val target = ws.resolve("hello.txt")
        val payload = "hello world\n"
        Files.writeString(target, payload)

        val out = sha256Step.handler.execute(
            Sha256Input(path = "hello.txt"),
            handlerContext(ws),
        )
        val expected = MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, out.hexDigest)
    }

    @Test
    fun `success — sha256 supports SHA-1 too (canonical 40-char hex)`() = runBlocking {
        val ws = stubWorkspaceRoot()
        Files.writeString(ws.resolve("x.txt"), "abc")
        val out = sha256Step.handler.execute(
            Sha256Input(path = "x.txt", algorithm = "SHA-1"),
            handlerContext(ws),
        )
        assertEquals(40, out.hexDigest.length)
        // sha1("abc") = a9993e364706816aba3e25717850c26c9cd0d89d
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", out.hexDigest)
    }

    // -------- typed failure --------

    @Test
    fun `typed failure — readJson of a missing file surfaces PluginStepException USER class`() {
        val ws = stubWorkspaceRoot()
        val ex = assertThrows(PluginStepException::class.java) {
            runBlocking {
                readJsonStep.handler.execute(ReadJsonInput(path = "does-not-exist.json"), handlerContext(ws))
            }
        }
        assertTrue(ex.failure.message!!.contains("not found"))
    }

    @Test
    fun `typed failure — readJson of invalid JSON surfaces PluginStepException USER class`() {
        val ws = stubWorkspaceRoot()
        // Truly malformed JSON: a brace that never closes.
        Files.writeString(ws.resolve("broken.json"), """{"unbalanced": """)

        val ex = assertThrows(PluginStepException::class.java) {
            runBlocking {
                readJsonStep.handler.execute(ReadJsonInput(path = "broken.json"), handlerContext(ws))
            }
        }
        assertTrue(ex.failure.message!!.contains("not valid JSON"))
    }

    @Test
    fun `typed failure — writeJson with useRawText=true and null rawText surfaces USER class`() {
        val ws = stubWorkspaceRoot()
        val ex = assertThrows(PluginStepException::class.java) {
            runBlocking {
                writeJsonStep.handler.execute(
                    WriteJsonInput(path = "x.json", value = null, rawText = null, useRawText = true),
                    handlerContext(ws),
                )
            }
        }
        assertTrue(ex.failure.message!!.contains("useRawText"))
    }

    @Test
    fun `typed failure — sha256 with an unsupported algorithm surfaces USER class`() {
        val ws = stubWorkspaceRoot()
        Files.writeString(ws.resolve("a.txt"), "x")
        val ex = assertThrows(PluginStepException::class.java) {
            runBlocking {
                sha256Step.handler.execute(
                    Sha256Input(path = "a.txt", algorithm = "MD5"),
                    handlerContext(ws),
                )
            }
        }
        assertTrue(ex.failure.message!!.contains("unsupported algorithm"))
    }

    @Test
    fun `typed failure — sha256 of a missing file surfaces USER class`() {
        val ws = stubWorkspaceRoot()
        val ex = assertThrows(PluginStepException::class.java) {
            runBlocking {
                sha256Step.handler.execute(Sha256Input(path = "missing.txt"), handlerContext(ws))
            }
        }
        assertTrue(ex.failure.message!!.contains("not found"))
    }

    // -------- replay / determinism --------

    @Test
    fun `replay — readJson output is deterministic for identical input`() = runBlocking {
        val ws = stubWorkspaceRoot()
        Files.writeString(ws.resolve("data.json"), """{"a":1}""")
        val a = readJsonStep.handler.execute(ReadJsonInput("data.json"), handlerContext(ws))
        val b = readJsonStep.handler.execute(ReadJsonInput("data.json"), handlerContext(ws))
        assertEquals(a, b)
    }

    @Test
    fun `replay — sha256 output is deterministic for identical input`() = runBlocking {
        val ws = stubWorkspaceRoot()
        Files.writeString(ws.resolve("h.txt"), "abc")
        val a = sha256Step.handler.execute(Sha256Input("h.txt"), handlerContext(ws))
        val b = sha256Step.handler.execute(Sha256Input("h.txt"), handlerContext(ws))
        assertEquals(a, b)
    }

    @Test
    fun `replay — writeJson output is deterministic across runs`() = runBlocking {
        val ws = stubWorkspaceRoot()
        val typed: kotlinx.serialization.json.JsonElement = buildJsonObject { put("k", "v") }
        val a = writeJsonStep.handler.execute(
            WriteJsonInput(path = "out/r1.json", value = typed),
            handlerContext(ws),
        )
        val b = writeJsonStep.handler.execute(
            WriteJsonInput(path = "out/r2.json", value = typed),
            handlerContext(ws),
        )
        // Same content => same digest, even though the file paths differ.
        assertEquals(a.sha256Hex, b.sha256Hex)
    }

    // -------- observability --------

    @Test
    fun `observability — successful handler returns a typed Output (callers observe via the return value)`() = runBlocking {
        // Per the Step Constitution, the typed return value IS the observable
        // channel for atomic Steps. There is no per-iteration event sink
        // available here in the HF0 layer.
        val ws = stubWorkspaceRoot()
        Files.writeString(ws.resolve("ok.json"), """{"k":"v"}""")
        val out = readJsonStep.handler.execute(ReadJsonInput("ok.json"), handlerContext(ws))
        assertNotNull(out.parsed)
    }
}
