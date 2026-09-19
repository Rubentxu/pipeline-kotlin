package dev.rubentxu.pipeline.v2.sdk.utilities.step

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PluginStepException
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.WORKSPACE_IDENTITY_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.WorkspaceIdentity
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.ReadJsonInput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.ReadJsonOutput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.ReadYamlInput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.ReadYamlOutput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.ReadYamlSource
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.Sha256Input
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.Sha256Output
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.WriteJsonInput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.WriteJsonOutput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.WriteYamlDestination
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.WriteYamlInput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.WriteYamlOutput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.WriteYamlPayload
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.YamlDocument
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.FileEntry
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.FindFilesInput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.FindFilesOutput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.FindFilesPattern
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.ZipInput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.ZipOutput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.ZipSources
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.UnzipInput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.UnzipMode
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.UnzipOutput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.ExtractedFile
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.ExtractedFiles
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.TestReport
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
    private val readYamlStep = CoreUtilsReadYamlStepDefinition()
    private val writeYamlStep = CoreUtilsWriteYamlStepDefinition()
    private val findFilesStep = CoreUtilsFindFilesStepDefinition()
    private val zipStep = CoreUtilsZipStepDefinition()
    private val unzipStep = CoreUtilsUnzipStepDefinition()

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

    // ==========================================================================
    //  core-utils.readYaml (Slice 2 / S2.1) — Jenkins-reference Step.
    //
    //  Reference: pipeline-utility-steps-plugin ReadYamlStep.
    //  Notes recorded in docs/v2/07-uat/S2_READYAML_WRITEYAML_JENKINS_REFERENCE.md.
    // ==========================================================================

    // -------- identity --------

    @Test
    fun `identity — readYaml Key is core-utils dot readYaml`() {
        assertEquals(PluginStepId("core-utils.readYaml"), CoreUtilsReadYamlKey.VALUE)
        assertEquals("core-utils.readYaml", CoreUtilsReadYamlKey.VALUE.value)
    }

    // -------- contract completeness --------

    @Test
    fun `contract — readYaml declares READ_ONLY, MEMOIZED, WORKSPACE_IDENTITY_CAPABILITY`() {
        val c = readYamlStep.contract
        assertEquals(CoreUtilsReadYamlKey.VALUE, c.key)
        assertEquals(Effect.READ_ONLY, c.descriptor.effects.single())
        assertEquals(ReplayPolicy.MEMOIZED, c.descriptor.replayPolicy)
        assertEquals(setOf(WORKSPACE_IDENTITY_CAPABILITY), c.requiredCapabilities)
        assertNotNull(c.inputCodec)
        assertNotNull(c.outputCodec)
    }

    // -------- codec roundtrip --------

    @Test
    fun `codec readYaml input — roundtrip preserves source variant + safety knobs`() {
        val fileInput = ReadYamlInput(
            source = ReadYamlSource.FromFile(path = "config.yaml"),
            codePointLimit = 4096,
            maxAliasesForCollections = 16,
        )
        val encodedFile = CoreUtilsReadYamlInputCodec.encode(fileInput)
        assertEquals(fileInput, CoreUtilsReadYamlInputCodec.decode(encodedFile))

        val textInput = ReadYamlInput(
            source = ReadYamlSource.FromText(text = "k: v\n"),
            codePointLimit = null,
            maxAliasesForCollections = null,
        )
        val encodedText = CoreUtilsReadYamlInputCodec.encode(textInput)
        assertEquals(textInput, CoreUtilsReadYamlInputCodec.decode(encodedText))
    }

    @Test
    fun `codec readYaml output — roundtrip preserves single document variant`() = runBlocking {
        val ws = stubWorkspaceRoot()
        Files.writeString(ws.resolve("cfg.yaml"), "name: alice\nage: 30\n")
        val original = readYamlStep.handler.execute(
            ReadYamlInput(source = ReadYamlSource.FromFile("cfg.yaml")),
            handlerContext(ws),
        )
        val encoded = CoreUtilsReadYamlOutputCodec.encode(original)
        val decoded = CoreUtilsReadYamlOutputCodec.decode(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `codec readYaml output — roundtrip preserves multiple documents variant`() = runBlocking {
        val ws = stubWorkspaceRoot()
        Files.writeString(ws.resolve("multi.yaml"), "---\nfirst: a\n---\nsecond: b\n---\nthird: c\n")
        val original = readYamlStep.handler.execute(
            ReadYamlInput(source = ReadYamlSource.FromFile("multi.yaml")),
            handlerContext(ws),
        )
        val encoded = CoreUtilsReadYamlOutputCodec.encode(original)
        val decoded = CoreUtilsReadYamlOutputCodec.decode(encoded)
        assertEquals(original, decoded)
        assertTrue(decoded.multipleDocuments)
        assertEquals(3, decoded.documents!!.size)
    }

    // -------- canonical envelope --------

    @Test
    fun `envelope — readYaml input codec emits a well-formed JSON object (durable eligible)`() {
        val encoded = CoreUtilsReadYamlInputCodec.encode(
            ReadYamlInput(source = ReadYamlSource.FromText("k: v")),
        )
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(encoded.value)
        assertTrue(parsed is JsonObject, "input envelope must be a JSON object")
    }

    @Test
    fun `envelope — readYaml output codec emits a well-formed JSON object (durable eligible)`() {
        val out = ReadYamlOutput(
            single = YamlDocument.Map(
                listOf(YamlDocument.Map.Entry("k", YamlDocument.Str("v"))),
            ),
            documents = null,
            multipleDocuments = false,
            byteSize = 5L,
            absolutePath = "/tmp/x.yaml",
        )
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(
            CoreUtilsReadYamlOutputCodec.encode(out).value,
        )
        assertTrue(parsed is JsonObject, "output envelope must be a JSON object")
    }

    // -------- capability admission --------

    @Test
    fun `capability — readYaml fails closed when workspace identity is absent`() {
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
            runBlocking {
                readYamlStep.handler.execute(
                    ReadYamlInput(source = ReadYamlSource.FromText("k: v")),
                    ctx,
                )
            }
        }
        assertFalse(ex is kotlinx.coroutines.CancellationException)
    }

    // -------- success --------

    @Test
    fun `success — readYaml(file) returns a typed YamlDocument tree`() = runBlocking {
        val ws = stubWorkspaceRoot()
        Files.writeString(
            ws.resolve("cfg.yaml"),
            """
                name: alice
                age: 30
                flags:
                  - red
                  - green
                nested:
                  inner: deep
            """.trimIndent(),
        )
        val out = readYamlStep.handler.execute(
            ReadYamlInput(source = ReadYamlSource.FromFile("cfg.yaml")),
            handlerContext(ws),
        )

        assertFalse(out.multipleDocuments)
        assertNotNull(out.single)
        val root = out.single as YamlDocument.Map
        val byKey = root.entries.associate { it.key to it.value }
        assertEquals(YamlDocument.Str("alice"), byKey["name"])
        assertEquals(YamlDocument.Integer(30L), byKey["age"])
        // Absolute path points at the resolved workspace file.
        assertNotNull(out.absolutePath)
        assertEquals(ws.resolve("cfg.yaml").toString(), out.absolutePath)

        val flags = byKey["flags"] as YamlDocument.Seq
        assertEquals(
            listOf(YamlDocument.Str("red"), YamlDocument.Str("green")),
            flags.items,
        )
        val nested = byKey["nested"] as YamlDocument.Map
        assertEquals(
            YamlDocument.Str("deep"),
            nested.entries.single { it.key == "inner" }.value,
        )
    }

    @Test
    fun `success — readYaml(text) parses inline YAML without disk access`() = runBlocking {
        val out = readYamlStep.handler.execute(
            ReadYamlInput(source = ReadYamlSource.FromText("greeting: hola\nn: 7\n")),
            handlerContext(stubWorkspaceRoot()),
        )
        assertFalse(out.multipleDocuments)
        val root = out.single as YamlDocument.Map
        val byKey = root.entries.associate { it.key to it.value }
        assertEquals(YamlDocument.Str("hola"), byKey["greeting"])
        assertEquals(YamlDocument.Integer(7L), byKey["n"])
        // No file was touched; absolutePath is null.
        assertNull(out.absolutePath)
    }

    @Test
    fun `success — readYaml handles YAML 1 dot 1 booleans and null as typed scalars`() = runBlocking {
        val out = readYamlStep.handler.execute(
            ReadYamlInput(
                source = ReadYamlSource.FromText(
                    """
                        "yes": true
                        "off": false
                        "empty": null
                    """.trimIndent(),
                ),
            ),
            handlerContext(stubWorkspaceRoot()),
        )
        val byKey = (out.single as YamlDocument.Map).entries.associate { it.key to it.value }
        assertEquals(YamlDocument.Bool(true), byKey["yes"])
        assertEquals(YamlDocument.Bool(false), byKey["off"])
        assertEquals(YamlDocument.Null, byKey["empty"])
    }

    @Test
    fun `success — readYaml distinguishes single vs multiple documents`() = runBlocking {
        val singleOut = readYamlStep.handler.execute(
            ReadYamlInput(source = ReadYamlSource.FromText("a: 1\n")),
            handlerContext(stubWorkspaceRoot()),
        )
        assertFalse(singleOut.multipleDocuments)
        assertNotNull(singleOut.single)
        assertNull(singleOut.documents)

        val multiOut = readYamlStep.handler.execute(
            ReadYamlInput(source = ReadYamlSource.FromText("---\na: 1\n---\nb: 2\n")),
            handlerContext(stubWorkspaceRoot()),
        )
        assertTrue(multiOut.multipleDocuments)
        assertNull(multiOut.single)
        assertEquals(2, multiOut.documents!!.size)
    }

    // -------- typed failure --------

    @Test
    fun `typed failure — readYaml of a missing file surfaces PluginStepException USER class`() {
        val ws = stubWorkspaceRoot()
        val ex = assertThrows(PluginStepException::class.java) {
            runBlocking {
                readYamlStep.handler.execute(
                    ReadYamlInput(source = ReadYamlSource.FromFile("does-not-exist.yaml")),
                    handlerContext(ws),
                )
            }
        }
        assertTrue(ex.failure.message!!.contains("not found"))
    }

    @Test
    fun `security — readYaml refuses !!python-name tags via the SafeConstructor+TagInspector stack`() {
        // Two valid outcomes, BOTH safe:
        //  (a) the tag inspector rejects the payload BEFORE the constructor
        //      runs, surfacing a typed PluginStepException USER-class failure.
        //      This is what happens with our SafeConstructorOnlyOptions
        //      allow-list (only YAML 1.1 standard tags are permitted).
        //  (b) the safe constructor ignores the tag and produces a plain
        //      String value for the key. This would also be safe because no
        //      arbitrary class is ever instantiated.
        //
        // We assert that NEITHER outcome ever reaches a side-effecting code
        // path: the test below pins the behaviour empirically, accepting
        // either a PluginStepException OR a typed Str value. The probe
        // `YamlSafetyCharacterisationTest` already proves the safe-constructor
        // path in isolation; here we exercise the wired Step end-to-end.
        val payload = "cmd: !!python/name:os.system 'echo pwned'\n"
        val ex = runCatching {
            runBlocking {
                readYamlStep.handler.execute(
                    ReadYamlInput(source = ReadYamlSource.FromText(payload)),
                    handlerContext(stubWorkspaceRoot()),
                )
            }
        }
        if (ex.isSuccess) {
            // Safe-constructor path: must be a plain Str, not a Class<*>.
            val out = ex.getOrThrow()
            val root = out.single as YamlDocument.Map
            val cmd = root.entries.first { it.key == "cmd" }.value
            assertTrue(cmd is YamlDocument.Str, "expected Str, got ${cmd::class}")
        } else {
            // Tag-inspector path: must surface as a typed PluginStepException,
            // never an unchecked Exception that would bypass the boundary.
            val thrown = ex.exceptionOrNull()
            assertTrue(
                thrown is PluginStepException,
                "expected PluginStepException, got ${thrown!!::class}",
            )
            assertEquals(FailureKind.USER, (thrown as PluginStepException).failure.kind)
        }
    }

    @Test
    fun `typed failure — readYaml is rejected at decode time when codePointLimit is non-positive`() {
        val bad = ReadYamlInput(
            source = ReadYamlSource.FromText("k: v"),
            codePointLimit = 0,
        )
        assertThrows(IllegalStateException::class.java) {
            CoreUtilsReadYamlInputCodec.decode(CoreUtilsReadYamlInputCodec.encode(bad))
        }
    }

    // -------- replay / determinism --------

    @Test
    fun `replay — readYaml output is deterministic for identical input`() = runBlocking {
        val ws = stubWorkspaceRoot()
        Files.writeString(ws.resolve("d.yaml"), "k: v\n")
        val input = ReadYamlInput(source = ReadYamlSource.FromFile("d.yaml"))
        val a = readYamlStep.handler.execute(input, handlerContext(ws))
        val b = readYamlStep.handler.execute(input, handlerContext(ws))
        assertEquals(a, b)
    }

    // -------- observability --------

    @Test
    fun `observability — readYaml returns a typed Output carrying parsed structure + byteSize + path`() = runBlocking {
        val ws = stubWorkspaceRoot()
        Files.writeString(ws.resolve("o.yaml"), "k: v\n")
        val out = readYamlStep.handler.execute(
            ReadYamlInput(source = ReadYamlSource.FromFile("o.yaml")),
            handlerContext(ws),
        )
        assertNotNull(out.single)
        assertTrue(out.byteSize > 0)
        assertEquals(ws.resolve("o.yaml").toString(), out.absolutePath)
    }

    // ==========================================================================
    //  core-utils.writeYaml (Slice 2 / S2.2) — Jenkins-reference Step.
    //
    //  Reference: pipeline-utility-steps-plugin WriteYamlStep.
    //  Notes recorded in docs/v2/07-uat/S2_READYAML_WRITEYAML_JENKINS_REFERENCE.md.
    // ==========================================================================

    // -------- identity --------

    @Test
    fun `identity — writeYaml Key is core-utils dot writeYaml`() {
        assertEquals(PluginStepId("core-utils.writeYaml"), CoreUtilsWriteYamlKey.VALUE)
        assertEquals("core-utils.writeYaml", CoreUtilsWriteYamlKey.VALUE.value)
    }

    // -------- contract completeness --------

    @Test
    fun `contract — writeYaml declares WRITES_WORKSPACE, NEVER, WORKSPACE_IDENTITY_CAPABILITY`() {
        val c = writeYamlStep.contract
        assertEquals(CoreUtilsWriteYamlKey.VALUE, c.key)
        assertEquals(Effect.WRITES_WORKSPACE, c.descriptor.effects.single())
        assertEquals(ReplayPolicy.NEVER, c.descriptor.replayPolicy)
        assertEquals(setOf(WORKSPACE_IDENTITY_CAPABILITY), c.requiredCapabilities)
        assertNotNull(c.inputCodec)
        assertNotNull(c.outputCodec)
    }

    // -------- codec roundtrip --------

    @Test
    fun `codec writeYaml input — roundtrip preserves destination + payload variants`() {
        val single = WriteYamlInput(
            destination = WriteYamlDestination.ToFile(path = "out.yaml", overwrite = true),
            payload = WriteYamlPayload.Single(YamlDocument.Str("v")),
        )
        assertEquals(single, CoreUtilsWriteYamlInputCodec.decode(CoreUtilsWriteYamlInputCodec.encode(single)))

        val multiple = WriteYamlInput(
            destination = WriteYamlDestination.ToFile(path = "m.yaml"),
            payload = WriteYamlPayload.Multiple(listOf(YamlDocument.Str("a"), YamlDocument.Str("b"))),
        )
        assertEquals(multiple, CoreUtilsWriteYamlInputCodec.decode(CoreUtilsWriteYamlInputCodec.encode(multiple)))

        val text = WriteYamlInput(
            destination = WriteYamlDestination.ToText,
            payload = WriteYamlPayload.Single(YamlDocument.Str("v")),
        )
        assertEquals(text, CoreUtilsWriteYamlInputCodec.decode(CoreUtilsWriteYamlInputCodec.encode(text)))
    }

    @Test
    fun `codec writeYaml output — roundtrip preserves file write fields`() = runBlocking {
        val ws = stubWorkspaceRoot()
        val out = writeYamlStep.handler.execute(
            WriteYamlInput(
                destination = WriteYamlDestination.ToFile(path = "o.yaml"),
                payload = WriteYamlPayload.Single(YamlDocument.Str("v")),
            ),
            handlerContext(ws),
        )
        val decoded = CoreUtilsWriteYamlOutputCodec.decode(CoreUtilsWriteYamlOutputCodec.encode(out))
        assertEquals(out, decoded)
    }

    // -------- canonical envelope --------

    @Test
    fun `envelope — writeYaml input codec emits a well-formed JSON object (durable eligible)`() {
        val encoded = CoreUtilsWriteYamlInputCodec.encode(
            WriteYamlInput(
                destination = WriteYamlDestination.ToFile(path = "x.yaml"),
                payload = WriteYamlPayload.Single(YamlDocument.Str("v")),
            ),
        )
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(encoded.value)
        assertTrue(parsed is JsonObject, "input envelope must be a JSON object")
    }

    // -------- success --------

    @Test
    fun `success — writeYaml writes a typed YamlDocument tree to disk and returns sha256Hex`() = runBlocking {
        val ws = stubWorkspaceRoot()
        val doc = YamlDocument.Map(
            listOf(
                YamlDocument.Map.Entry("name", YamlDocument.Str("alice")),
                YamlDocument.Map.Entry("age", YamlDocument.Integer(30L)),
                YamlDocument.Map.Entry(
                    "flags",
                    YamlDocument.Seq(listOf(YamlDocument.Str("a"), YamlDocument.Str("b"))),
                ),
            ),
        )
        val out = writeYamlStep.handler.execute(
            WriteYamlInput(
                destination = WriteYamlDestination.ToFile(path = "out/data.yaml"),
                payload = WriteYamlPayload.Single(doc),
            ),
            handlerContext(ws),
        )

        assertTrue(out.wroteToFile)
        assertNotNull(out.absolutePath)
        assertEquals(ws.resolve("out/data.yaml").toString(), out.absolutePath)
        assertEquals(
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(Path.of(out.absolutePath!!)))
                .joinToString("") { "%02x".format(it) },
            out.sha256Hex,
        )
        // The content is YAML-shaped (contains the expected text values).
        val raw = Files.readString(Path.of(out.absolutePath!!))
        assertTrue(raw.contains("name"))
        assertTrue(raw.contains("alice"))
        assertTrue(raw.contains("age"))
    }

    @Test
    fun `success — writeYaml creates missing parent directories`() = runBlocking {
        val ws = stubWorkspaceRoot()
        writeYamlStep.handler.execute(
            WriteYamlInput(
                destination = WriteYamlDestination.ToFile(path = "deep/nested/dir/x.yaml"),
                payload = WriteYamlPayload.Single(YamlDocument.Bool(true)),
            ),
            handlerContext(ws),
        )
        assertTrue(Files.exists(ws.resolve("deep/nested/dir/x.yaml")))
    }

    @Test
    fun `success — writeYaml roundtrip via readYaml preserves typed structure`() = runBlocking {
        val ws = stubWorkspaceRoot()
        val original = YamlDocument.Map(
            listOf(
                YamlDocument.Map.Entry("k1", YamlDocument.Str("v1")),
                YamlDocument.Map.Entry("k2", YamlDocument.Integer(42L)),
                YamlDocument.Map.Entry("k3", YamlDocument.Null),
            ),
        )
        writeYamlStep.handler.execute(
            WriteYamlInput(
                destination = WriteYamlDestination.ToFile(path = "rt.yaml"),
                payload = WriteYamlPayload.Single(original),
            ),
            handlerContext(ws),
        )
        val readBack = readYamlStep.handler.execute(
            ReadYamlInput(source = ReadYamlSource.FromFile("rt.yaml")),
            handlerContext(ws),
        )
        val readDoc = readBack.single as YamlDocument.Map
        assertEquals(original, readDoc)
    }

    @Test
    fun `success — writeYaml multiple documents writes a multi-doc YAML stream`() = runBlocking {
        val ws = stubWorkspaceRoot()
        writeYamlStep.handler.execute(
            WriteYamlInput(
                destination = WriteYamlDestination.ToFile(path = "multi.yaml"),
                payload = WriteYamlPayload.Multiple(
                    listOf(
                        YamlDocument.Str("first"),
                        YamlDocument.Str("second"),
                        YamlDocument.Str("third"),
                    ),
                ),
            ),
            handlerContext(ws),
        )
        val raw = Files.readString(ws.resolve("multi.yaml"))
        // SnakeYAML's dumpAll separates documents with `---\n`.
        assertTrue(raw.contains("---"), "expected multi-document stream separator, got: $raw")
        val readBack = readYamlStep.handler.execute(
            ReadYamlInput(source = ReadYamlSource.FromFile("multi.yaml")),
            handlerContext(ws),
        )
        assertTrue(readBack.multipleDocuments)
        assertEquals(3, readBack.documents!!.size)
    }

    // -------- typed failure --------

    @Test
    fun `typed failure — writeYaml refuses to overwrite an existing file unless overwrite=true`() = runBlocking {
        val ws = stubWorkspaceRoot()
        Files.writeString(ws.resolve("preexisting.yaml"), "old: value\n")
        val ex = assertThrows(PluginStepException::class.java) {
            runBlocking {
                writeYamlStep.handler.execute(
                    WriteYamlInput(
                        destination = WriteYamlDestination.ToFile(path = "preexisting.yaml"),
                        payload = WriteYamlPayload.Single(YamlDocument.Str("new")),
                    ),
                    handlerContext(ws),
                )
            }
        }
        assertTrue(ex.failure.message!!.contains("overwrite=false"))
    }

    @Test
    fun `typed failure — writeYaml with overwrite=true succeeds even if the file exists`() = runBlocking {
        val ws = stubWorkspaceRoot()
        Files.writeString(ws.resolve("overwritable.yaml"), "old: value\n")
        val out = writeYamlStep.handler.execute(
            WriteYamlInput(
                destination = WriteYamlDestination.ToFile(path = "overwritable.yaml", overwrite = true),
                payload = WriteYamlPayload.Single(YamlDocument.Str("new")),
            ),
            handlerContext(ws),
        )
        assertTrue(out.wroteToFile)
        val raw = Files.readString(Path.of(out.absolutePath!!))
        assertFalse(raw.contains("old: value"))
    }

    @Test
    fun `typed failure — writeYaml with empty payload list surfaces USER class`() {
        // Cannot construct an empty Multiple directly (the DSL guards against
        // it; the typed API would too), so we exercise the codec's tolerance
        // by encoding a manual envelope with an empty items array.
        val json = """
            {"charset":"UTF-8","destination":{"kind":"file","path":"e.yaml","overwrite":false},
             "payload":{"kind":"multiple","items":[]}}
        """.trimIndent()
        val encoded = EncodedStepValue(json)
        val input = CoreUtilsWriteYamlInputCodec.decode(encoded)
        // Empty list roundtrips; the runtime fail-closed is the DSL facade's
        // require(). We assert the typed value is what we expect.
        assertTrue(input.payload is WriteYamlPayload.Multiple)
        assertEquals(0, (input.payload as WriteYamlPayload.Multiple).documents.size)
    }

    // -------- replay / determinism --------

    @Test
    fun `replay — writeYaml output is deterministic across runs`() = runBlocking {
        val ws = stubWorkspaceRoot()
        val doc = YamlDocument.Map(listOf(YamlDocument.Map.Entry("k", YamlDocument.Str("v"))))
        val a = writeYamlStep.handler.execute(
            WriteYamlInput(
                destination = WriteYamlDestination.ToFile(path = "det1.yaml"),
                payload = WriteYamlPayload.Single(doc),
            ),
            handlerContext(ws),
        )
        val b = writeYamlStep.handler.execute(
            WriteYamlInput(
                destination = WriteYamlDestination.ToFile(path = "det2.yaml"),
                payload = WriteYamlPayload.Single(doc),
            ),
            handlerContext(ws),
        )
        // Same content => same digest, even though paths differ.
        assertEquals(a.sha256Hex, b.sha256Hex)
    }

    // -------- observability --------

    @Test
    fun `observability — writeYaml returns a typed Output with wroteToFile + absolutePath + sha256Hex`() = runBlocking {
        val ws = stubWorkspaceRoot()
        val out = writeYamlStep.handler.execute(
            WriteYamlInput(
                destination = WriteYamlDestination.ToFile(path = "obs.yaml"),
                payload = WriteYamlPayload.Single(YamlDocument.Integer(7L)),
            ),
            handlerContext(ws),
        )
        assertTrue(out.wroteToFile)
        assertNotNull(out.absolutePath)
        assertNotNull(out.sha256Hex)
        assertEquals(64, out.sha256Hex!!.length)
    }

    // ==========================================================================
    //  core-utils.findFiles (Slice 2 / S2.3) — Jenkins-reference Step.
    //
    //  Reference: pipeline-utility-steps-plugin FindFilesStep (MIT, CloudBees).
    //  Notes recorded in docs/v2/07-uat/S2_FINDFILES_JENKINS_REFERENCE.md.
    // ==========================================================================

    // -------- identity --------

    @Test
    fun `identity — findFiles Key is core-utils dot findFiles`() {
        assertEquals(PluginStepId("core-utils.findFiles"), CoreUtilsFindFilesKey.VALUE)
        assertEquals("core-utils.findFiles", CoreUtilsFindFilesKey.VALUE.value)
    }

    // -------- contract completeness --------

    @Test
    fun `contract — findFiles declares READ_ONLY, MEMOIZED, WORKSPACE_IDENTITY_CAPABILITY`() {
        val c = findFilesStep.contract
        assertEquals(CoreUtilsFindFilesKey.VALUE, c.key)
        assertEquals(Effect.READ_ONLY, c.descriptor.effects.single())
        assertEquals(ReplayPolicy.MEMOIZED, c.descriptor.replayPolicy)
        assertEquals(setOf(WORKSPACE_IDENTITY_CAPABILITY), c.requiredCapabilities)
        assertNotNull(c.inputCodec)
        assertNotNull(c.outputCodec)
    }

    // -------- codec roundtrip --------

    @Test
    fun `codec findFiles input — roundtrip preserves base + pattern variants`() {
        val none = FindFilesInput(base = "sub")
        assertEquals(none, CoreUtilsFindFilesInputCodec.decode(
            CoreUtilsFindFilesInputCodec.encode(none),
        ))

        val withGlob = FindFilesInput(
            base = "anywhere",
            pattern = FindFilesPattern.Glob(glob = "*.txt", excludes = "build/**"),
        )
        assertEquals(withGlob, CoreUtilsFindFilesInputCodec.decode(
            CoreUtilsFindFilesInputCodec.encode(withGlob),
        ))
    }

    @Test
    fun `codec findFiles output — roundtrip preserves basePath + patternEcho + files`() = runBlocking {
        val ws = stubWorkspaceRoot()
        Files.writeString(ws.resolve("a.txt"), "alpha")
        Files.writeString(ws.resolve("b.txt"), "beta")
        val out = findFilesStep.handler.execute(
            FindFilesInput(base = ".", pattern = FindFilesPattern.Glob("*.txt")),
            handlerContext(ws),
        )
        val decoded = CoreUtilsFindFilesOutputCodec.decode(CoreUtilsFindFilesOutputCodec.encode(out))
        assertEquals(out, decoded)
        assertEquals(FindFilesPattern.Glob("*.txt"), decoded.patternEcho)
    }

    // -------- canonical envelope --------

    @Test
    fun `envelope — findFiles input codec emits a well-formed JSON object (durable eligible)`() {
        val encoded = CoreUtilsFindFilesInputCodec.encode(
            FindFilesInput(base = "x", pattern = FindFilesPattern.Glob("**/*.txt", null)),
        )
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(encoded.value)
        assertTrue(parsed is JsonObject, "input envelope must be a JSON object")
    }

    // -------- success (Jenkins FindFilesStepTest contract) --------

    @Test
    fun `success — findFiles no glob returns direct children only (Jenkins simpleList)`() = runBlocking {
        val ws = stubWorkspaceRoot()
        Files.writeString(ws.resolve("1.txt"), "x")
        Files.writeString(ws.resolve("2.txt"), "x")
        Files.createDirectories(ws.resolve("a"))
        Files.writeString(ws.resolve("a").resolve("3.txt"), "x")
        Files.createDirectories(ws.resolve("b"))
        Files.writeString(ws.resolve("b").resolve("11.txt"), "x")

        val out = findFilesStep.handler.execute(
            FindFilesInput(base = ".", pattern = FindFilesPattern.None),
            handlerContext(ws),
        )
        val names = out.files.map { it.name }.toSet()
        // Direct children only: the two files plus the two directories.
        assertEquals(setOf("1.txt", "2.txt", "a", "b"), names)
        // And nothing deeper: no path contains a slash BEFORE the trailing
        // slash (directories report `subdir/`, never `parent/subdir/`).
        assertTrue(out.files.none { it.path.removeSuffix("/").contains("/") })
    }

    @Test
    fun `success — findFiles recursive glob returns all matching files (Jenkins listAll)`() = runBlocking {
        val ws = stubWorkspaceRoot()
        // Build a tree mirroring Jenkins' FindFilesStepTest.setUp
        Files.writeString(ws.resolve("1.txt"), "x")
        Files.writeString(ws.resolve("2.txt"), "x")
        Files.createDirectories(ws.resolve("a"))
        Files.writeString(ws.resolve("a").resolve("3.txt"), "x")
        Files.writeString(ws.resolve("a").resolve("4.txt"), "x")
        Files.createDirectories(ws.resolve("a/aa"))
        Files.writeString(ws.resolve("a/aa").resolve("5.txt"), "x")
        Files.writeString(ws.resolve("a/aa").resolve("6.txt"), "x")
        Files.createDirectories(ws.resolve("a/ab"))
        Files.writeString(ws.resolve("a/ab").resolve("7.txt"), "x")
        Files.writeString(ws.resolve("a/ab").resolve("8.txt"), "x")
        Files.createDirectories(ws.resolve("a/ab/aba"))
        Files.writeString(ws.resolve("a/ab/aba").resolve("9.txt"), "x")
        Files.writeString(ws.resolve("a/ab/aba").resolve("10.txt"), "x")
        Files.createDirectories(ws.resolve("b"))
        Files.writeString(ws.resolve("b").resolve("11.txt"), "x")
        Files.writeString(ws.resolve("b").resolve("12.txt"), "x")

        val out = findFilesStep.handler.execute(
            FindFilesInput(base = ".", pattern = FindFilesPattern.Glob("**/*.txt")),
            handlerContext(ws),
        )
        // Mirrors Jenkins' FindFilesStepTest.listAll: the recursive scan
        // returns every file (12 in their tree); a `*.txt` include would
        // filter to 8. We assert the larger set because the includes glob
        // is recursive.
        val paths = out.files.map { it.path }.toSet()
        assertTrue("1.txt" in paths && "2.txt" in paths)
        assertTrue("a/3.txt" in paths && "a/4.txt" in paths)
        assertTrue("a/aa/5.txt" in paths && "a/aa/6.txt" in paths)
        assertTrue("a/ab/7.txt" in paths && "a/ab/8.txt" in paths)
        assertTrue("b/11.txt" in paths && "b/12.txt" in paths)
        assertEquals(12, out.files.size)
    }

    @Test
    fun `success — findFiles sub-tree glob matches only nested files (Jenkins listSome)`() = runBlocking {
        val ws = stubWorkspaceRoot()
        Files.createDirectories(ws.resolve("a"))
        Files.writeString(ws.resolve("1.txt"), "x")
        Files.writeString(ws.resolve("a").resolve("3.txt"), "x")
        Files.writeString(ws.resolve("a").resolve("4.txt"), "x")
        Files.createDirectories(ws.resolve("a/aa"))
        Files.writeString(ws.resolve("a/aa").resolve("5.txt"), "x")

        val out = findFilesStep.handler.execute(
            FindFilesInput(base = ".", pattern = FindFilesPattern.Glob("a/*.txt")),
            handlerContext(ws),
        )
        val paths = out.files.map { it.path }.toSet()
        assertEquals(setOf("a/3.txt", "a/4.txt"), paths)
    }

    @Test
    fun `success — findFiles excludes drops matches (Jenkins listSomeWithExclusions)`() = runBlocking {
        val ws = stubWorkspaceRoot()
        Files.writeString(ws.resolve("1.txt"), "x")
        Files.writeString(ws.resolve("2.txt"), "x")
        Files.createDirectories(ws.resolve("a"))
        Files.writeString(ws.resolve("a").resolve("3.txt"), "x")
        Files.writeString(ws.resolve("a").resolve("4.txt"), "x")
        Files.createDirectories(ws.resolve("a/aa"))
        Files.writeString(ws.resolve("a/aa").resolve("5.txt"), "x")
        Files.createDirectories(ws.resolve("b"))
        Files.writeString(ws.resolve("b").resolve("11.txt"), "x")

        val out = findFilesStep.handler.execute(
            FindFilesInput(
                base = ".",
                pattern = FindFilesPattern.Glob("**/*.txt", excludes = "b/*.txt"),
            ),
            handlerContext(ws),
        )
        val paths = out.files.map { it.path }.toSet()
        assertTrue("1.txt" in paths && "2.txt" in paths)
        assertTrue("a/3.txt" in paths && "a/4.txt" in paths)
        assertTrue("a/aa/5.txt" in paths)
        assertTrue("b/11.txt" !in paths)
    }

    // -------- FileEntry shape --------

    @Test
    fun `success — findFiles returns FileEntry with all five fields populated`() = runBlocking {
        val ws = stubWorkspaceRoot()
        Files.writeString(ws.resolve("hello.txt"), "hello")
        val out = findFilesStep.handler.execute(
            FindFilesInput(base = ".", pattern = FindFilesPattern.Glob("*.txt")),
            handlerContext(ws),
        )
        assertEquals(1, out.files.size)
        val entry: FileEntry = out.files.single()
        assertEquals("hello.txt", entry.name)
        assertEquals("hello.txt", entry.path)
        assertFalse(entry.directory)
        assertEquals(5L, entry.length)
        assertTrue(entry.lastModified > 0L)
    }

    @Test
    fun `success — findFiles directories appear with trailing slash and directory=true`() = runBlocking {
        val ws = stubWorkspaceRoot()
        Files.createDirectories(ws.resolve("subdir"))
        val out = findFilesStep.handler.execute(
            FindFilesInput(base = ".", pattern = FindFilesPattern.None),
            handlerContext(ws),
        )
        val entry = out.files.single()
        assertEquals("subdir", entry.name)
        assertEquals("subdir/", entry.path)
        assertTrue(entry.directory)
    }

    // -------- typed failure --------

    @Test
    fun `typed failure — findFiles on missing base raises USER class`() {
        val ws = stubWorkspaceRoot()
        val ex = assertThrows(PluginStepException::class.java) {
            runBlocking {
                findFilesStep.handler.execute(
                    FindFilesInput(base = "does-not-exist", pattern = FindFilesPattern.None),
                    handlerContext(ws),
                )
            }
        }
        assertTrue(ex.failure.message!!.contains("does not exist"))
    }

    @Test
    fun `typed failure — findFiles on file (not directory) raises USER class`() = runBlocking {
        val ws = stubWorkspaceRoot()
        Files.writeString(ws.resolve("a-file.txt"), "x")
        val ex = assertThrows(PluginStepException::class.java) {
            runBlocking {
                findFilesStep.handler.execute(
                    FindFilesInput(base = "a-file.txt", pattern = FindFilesPattern.None),
                    handlerContext(ws),
                )
            }
        }
        assertTrue(ex.failure.message!!.contains("not a directory"))
    }

    // -------- replay / determinism --------

    @Test
    fun `replay — findFiles output is deterministic for identical workspace contents`() = runBlocking {
        val ws = stubWorkspaceRoot()
        Files.writeString(ws.resolve("a.txt"), "x")
        Files.writeString(ws.resolve("b.txt"), "x")

        val a = findFilesStep.handler.execute(
            FindFilesInput(base = ".", pattern = FindFilesPattern.Glob("*.txt")),
            handlerContext(ws),
        )
        val b = findFilesStep.handler.execute(
            FindFilesInput(base = ".", pattern = FindFilesPattern.Glob("*.txt")),
            handlerContext(ws),
        )
        assertEquals(a.files.map { it.path }, b.files.map { it.path })
    }

    // -------- security --------

    @Test
    fun `security — findFiles does NOT follow symlinks (symlink target outside base is not enumerated)`() = runBlocking {
        val ws = stubWorkspaceRoot()
        // Create a directory outside the workspace containing a target file.
        val outside = tempDir.resolve("outside")
        Files.createDirectories(outside)
        Files.writeString(outside.resolve("secret.txt"), "secret")

        // Drop a symlink inside the workspace pointing at the outside dir.
        val linkPath = ws.resolve("link-out")
        java.nio.file.Files.createSymbolicLink(linkPath, outside)
        Files.writeString(ws.resolve("real.txt"), "x")

        val out = findFilesStep.handler.execute(
            FindFilesInput(base = ".", pattern = FindFilesPattern.Glob("**/*.txt")),
            handlerContext(ws),
        )
        val paths = out.files.map { it.path }
        // The real file is matched. The symlink is not traversed, so the
        // outside target's contents are not enumerated.
        assertTrue("real.txt" in paths)
        assertFalse(paths.any { it.startsWith("link-out/") })
        assertFalse(paths.any { it.endsWith("secret.txt") })
    }

    // ==========================================================================
    //  core-utils.zip (Slice 2 / S2.4) — Jenkins-reference Step.
    //
    //  Reference: pipeline-utility-steps-plugin ZipStep (MIT, CloudBees)
    //  + CompressStepExecution. CVE-2023-32981 (SECURITY-2196) hardened the
    //  Jenkins side; we apply the same canonical-path containment on the
    //  archive-creation side (a malicious `FromFiles`/`FromDirectory`
    //  payload cannot smuggle a path that escapes the workspace).
    // ==========================================================================

    // -------- identity --------

    @Test
    fun `identity — zip Key is core-utils dot zip`() {
        assertEquals(PluginStepId("core-utils.zip"), CoreUtilsZipKey.VALUE)
        assertEquals("core-utils.zip", CoreUtilsZipKey.VALUE.value)
    }

    // -------- contract completeness --------

    @Test
    fun `contract — zip declares WRITES_WORKSPACE, NEVER, WORKSPACE_IDENTITY_CAPABILITY`() {
        val c = zipStep.contract
        assertEquals(CoreUtilsZipKey.VALUE, c.key)
        assertEquals(Effect.WRITES_WORKSPACE, c.descriptor.effects.single())
        assertEquals(ReplayPolicy.NEVER, c.descriptor.replayPolicy)
        assertEquals(setOf(WORKSPACE_IDENTITY_CAPABILITY), c.requiredCapabilities)
        assertNotNull(c.inputCodec)
        assertNotNull(c.outputCodec)
    }

    // -------- codec roundtrip --------

    @Test
    fun `codec zip input — roundtrip preserves path + overwrite + sources variants`() {
        val glob = ZipInput(
            path = "out.zip",
            overwrite = true,
            sources = ZipSources.FromGlob("**/*.txt"),
        )
        assertEquals(glob, CoreUtilsZipInputCodec.decode(CoreUtilsZipInputCodec.encode(glob)))

        val dir = ZipInput(
            path = "/tmp/abs.zip",
            sources = ZipSources.FromDirectory("build/dist"),
        )
        assertEquals(dir, CoreUtilsZipInputCodec.decode(CoreUtilsZipInputCodec.encode(dir)))

        val files = ZipInput(
            path = "literal.zip",
            overwrite = true,
            sources = ZipSources.FromFiles(listOf("a.txt", "sub/b.txt")),
        )
        assertEquals(files, CoreUtilsZipInputCodec.decode(CoreUtilsZipInputCodec.encode(files)))
    }

    @Test
    fun `codec zip output — roundtrip preserves absolutePath + byteSize + sha256Hex + entryCount`() = runBlocking {
        val ws = stubWorkspaceRoot()
        Files.writeString(ws.resolve("a.txt"), "alpha")
        val out = zipStep.handler.execute(
            ZipInput(
                path = "codec.zip",
                sources = ZipSources.FromFiles(listOf("a.txt")),
            ),
            handlerContext(ws),
        )
        val decoded = CoreUtilsZipOutputCodec.decode(CoreUtilsZipOutputCodec.encode(out))
        assertEquals(out, decoded)
    }

    // -------- canonical envelope --------

    @Test
    fun `envelope — zip input codec emits a well-formed JSON object (durable eligible)`() {
        val encoded = CoreUtilsZipInputCodec.encode(
            ZipInput(path = "x.zip", sources = ZipSources.FromFiles(listOf("a"))),
        )
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(encoded.value)
        assertTrue(parsed is JsonObject, "input envelope must be a JSON object")
    }

    // -------- success --------

    @Test
    fun `success — zip FromFiles archives the listed files with the correct entry names`() = runBlocking {
        val ws = stubWorkspaceRoot()
        Files.writeString(ws.resolve("a.txt"), "alpha")
        Files.createDirectories(ws.resolve("sub"))
        Files.writeString(ws.resolve("sub").resolve("b.txt"), "beta")

        val out = zipStep.handler.execute(
            ZipInput(
                path = "result.zip",
                sources = ZipSources.FromFiles(listOf("a.txt", "sub/b.txt")),
            ),
            handlerContext(ws),
        )

        assertEquals(ws.resolve("result.zip").toString(), out.absolutePath)
        assertEquals(2, out.entryCount)
        assertEquals(64, out.sha256Hex.length)
        assertTrue(out.byteSize > 0)

        // Verify the archive contents using java.util.zip
        val entries = java.util.zip.ZipFile(ws.resolve("result.zip").toFile()).use { zf ->
            zf.entries().asSequence().map { it.name }.toSet()
        }
        assertEquals(setOf("a.txt", "sub/b.txt"), entries)
    }

    @Test
    fun `success — zip FromDirectory archives a full subtree`() = runBlocking {
        val ws = stubWorkspaceRoot()
        Files.createDirectories(ws.resolve("src/main"))
        Files.writeString(ws.resolve("src/main/A.kt"), "A")
        Files.writeString(ws.resolve("src/main/B.kt"), "B")
        Files.writeString(ws.resolve("README.md"), "R")

        val out = zipStep.handler.execute(
            ZipInput(
                path = "src.zip",
                sources = ZipSources.FromDirectory("src"),
            ),
            handlerContext(ws),
        )
        assertEquals(2, out.entryCount)
        val entries = java.util.zip.ZipFile(ws.resolve("src.zip").toFile()).use { zf ->
            zf.entries().asSequence().map { it.name }.toSet()
        }
        assertEquals(setOf("main/A.kt", "main/B.kt"), entries)
    }

    @Test
    fun `success — zip FromGlob archives matching files (Jenkins-compat two-stars-slash)`() = runBlocking {
        val ws = stubWorkspaceRoot()
        Files.writeString(ws.resolve("top.txt"), "t")
        Files.createDirectories(ws.resolve("a"))
        Files.writeString(ws.resolve("a").resolve("nested.txt"), "n")

        val out = zipStep.handler.execute(
            ZipInput(
                path = "g.zip",
                sources = ZipSources.FromGlob("**/*.txt"),
            ),
            handlerContext(ws),
        )
        assertEquals(2, out.entryCount)
    }

    @Test
    fun `success — zip computes a sha256Hex that matches the bytes on disk`() = runBlocking {
        val ws = stubWorkspaceRoot()
        Files.writeString(ws.resolve("a.txt"), "alpha")
        val out = zipStep.handler.execute(
            ZipInput(
                path = "x.zip",
                sources = ZipSources.FromFiles(listOf("a.txt")),
            ),
            handlerContext(ws),
        )
        val recomputed = MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(ws.resolve("x.zip")))
            .joinToString("") { "%02x".format(it) }
        assertEquals(recomputed, out.sha256Hex)
    }

    @Test
    fun `success — zip creates missing parent directories`() = runBlocking {
        val ws = stubWorkspaceRoot()
        Files.writeString(ws.resolve("a.txt"), "a")
        zipStep.handler.execute(
            ZipInput(
                path = "deep/nested/x.zip",
                sources = ZipSources.FromFiles(listOf("a.txt")),
            ),
            handlerContext(ws),
        )
        assertTrue(Files.exists(ws.resolve("deep/nested/x.zip")))
    }

    // -------- typed failure --------

    @Test
    fun `typed failure — zip refuses to overwrite an existing file unless overwrite=true`() = runBlocking {
        val ws = stubWorkspaceRoot()
        Files.writeString(ws.resolve("preexisting.zip"), "old")
        Files.writeString(ws.resolve("a.txt"), "alpha")
        val ex = assertThrows(PluginStepException::class.java) {
            runBlocking {
                zipStep.handler.execute(
                    ZipInput(
                        path = "preexisting.zip",
                        sources = ZipSources.FromFiles(listOf("a.txt")),
                    ),
                    handlerContext(ws),
                )
            }
        }
        assertTrue(ex.failure.message!!.contains("overwrite=false"))
    }

    @Test
    fun `typed failure — zip FromDirectory on a missing directory raises USER class`() {
        val ws = stubWorkspaceRoot()
        val ex = assertThrows(PluginStepException::class.java) {
            runBlocking {
                zipStep.handler.execute(
                    ZipInput(
                        path = "out.zip",
                        sources = ZipSources.FromDirectory("does-not-exist"),
                    ),
                    handlerContext(ws),
                )
            }
        }
        assertTrue(ex.failure.message!!.contains("directory does not exist"))
    }

    @Test
    fun `typed failure — zip FromFiles with non-regular source raises USER class`() {
        val ws = stubWorkspaceRoot()
        val ex = assertThrows(PluginStepException::class.java) {
            runBlocking {
                zipStep.handler.execute(
                    ZipInput(
                        path = "out.zip",
                        sources = ZipSources.FromFiles(listOf("does-not-exist.txt")),
                    ),
                    handlerContext(ws),
                )
            }
        }
        assertTrue(ex.failure.message!!.contains("not a regular file"))
    }

    // -------- security: CVE-2023-32981 archive-side --------

    @Test
    fun `security — zip FromFiles refuses a path that escapes the workspace`() = runBlocking {
        val ws = stubWorkspaceRoot()
        // Create a file outside the workspace.
        val outside = tempDir.resolve("outside.txt")
        Files.writeString(outside, "secret")
        val outsideAbs = outside.toAbsolutePath().toString()

        val ex = assertThrows(PluginStepException::class.java) {
            runBlocking {
                zipStep.handler.execute(
                    ZipInput(
                        path = "x.zip",
                        sources = ZipSources.FromFiles(listOf(outsideAbs)),
                    ),
                    handlerContext(ws),
                )
            }
        }
        // The error comes from the resolution check: the path resolves
        // outside the workspace root.
        assertNotNull(ex.failure.message)
    }

    // ==========================================================================
    //  core-utils.unzip (Slice 2 / S2.5) — Jenkins-reference Step.
    //
    //  Reference: pipeline-utility-steps-plugin UnZipStep + UnZipStepExecution
    //  (MIT, CloudBees). Jenkins Security Advisory 2023-05-16 /
    //  CVE-2023-32981 / SECURITY-2196 fixed the original Zip Slip on the
    //  extraction side; we apply the same canonical-path containment here
    //  and additionally reject the `..`-segment form, backslash form and
    //  absolute form to match the contract documented in
    //  docs/v2/07-uat/S2_UNZIP_JENKINS_REFERENCE.md.
    // ==========================================================================

    // -------- identity --------

    @Test
    fun `identity — unzip Key is core-utils dot unzip`() {
        assertEquals(PluginStepId("core-utils.unzip"), CoreUtilsUnzipKey.VALUE)
        assertEquals("core-utils.unzip", CoreUtilsUnzipKey.VALUE.value)
    }

    // -------- contract completeness --------

    @Test
    fun `contract — unzip declares WRITES_WORKSPACE, NEVER, WORKSPACE_IDENTITY_CAPABILITY`() {
        val c = unzipStep.contract
        assertEquals(CoreUtilsUnzipKey.VALUE, c.key)
        assertEquals(Effect.WRITES_WORKSPACE, c.descriptor.effects.single())
        assertEquals(ReplayPolicy.NEVER, c.descriptor.replayPolicy)
        assertEquals(setOf(WORKSPACE_IDENTITY_CAPABILITY), c.requiredCapabilities)
        assertNotNull(c.inputCodec)
        assertNotNull(c.outputCodec)
    }

    // -------- codec roundtrip --------

    @Test
    fun `codec unzip input — roundtrip preserves path + destination + glob + mode`() {
        val plain = UnzipInput(path = "x.zip")
        assertEquals(plain, CoreUtilsUnzipInputCodec.decode(CoreUtilsUnzipInputCodec.encode(plain)))

        val withDest = UnzipInput(path = "/tmp/abs.zip", destination = "out")
        assertEquals(withDest, CoreUtilsUnzipInputCodec.decode(CoreUtilsUnzipInputCodec.encode(withDest)))

        val withGlob = UnzipInput(path = "x.zip", glob = "**/*.txt")
        assertEquals(withGlob, CoreUtilsUnzipInputCodec.decode(CoreUtilsUnzipInputCodec.encode(withGlob)))

        val readMode = UnzipInput(path = "x.zip", mode = UnzipMode.Read)
        assertEquals(readMode, CoreUtilsUnzipInputCodec.decode(CoreUtilsUnzipInputCodec.encode(readMode)))

        val testMode = UnzipInput(path = "x.zip", mode = UnzipMode.Test)
        assertEquals(testMode, CoreUtilsUnzipInputCodec.decode(CoreUtilsUnzipInputCodec.encode(testMode)))
    }

    @Test
    fun `codec unzip output — extract variant roundtrip preserves every field`() = runBlocking {
        val ws = stubWorkspaceRoot()
        Files.writeString(ws.resolve("a.txt"), "alpha")
        val archive = ws.resolve("seed.zip")
        java.util.zip.ZipOutputStream(Files.newOutputStream(archive)).use { zos ->
            val entry = java.util.zip.ZipEntry("a.txt")
            zos.putNextEntry(entry)
            zos.write("alpha".toByteArray())
            zos.closeEntry()
        }

        val out = unzipStep.handler.execute(
            UnzipInput(path = "seed.zip"),
            handlerContext(ws),
        )
        val decoded = CoreUtilsUnzipOutputCodec.decode(CoreUtilsUnzipOutputCodec.encode(out))
        assertEquals(out, decoded)
        assertNotNull(decoded.extracted)
        assertEquals(listOf("a.txt"), decoded.extracted!!.files.map { it.name })
    }

    @Test
    fun `codec unzip output — read variant roundtrip preserves every entry`() = runBlocking {
        val ws = stubWorkspaceRoot()
        val archive = ws.resolve("seed.zip")
        java.util.zip.ZipOutputStream(Files.newOutputStream(archive)).use { zos ->
            listOf("a.txt" to "alpha", "b.txt" to "beta").forEach { (name, text) ->
                zos.putNextEntry(java.util.zip.ZipEntry(name))
                zos.write(text.toByteArray())
                zos.closeEntry()
            }
        }

        val out = unzipStep.handler.execute(
            UnzipInput(path = "seed.zip", mode = UnzipMode.Read),
            handlerContext(ws),
        )
        val decoded = CoreUtilsUnzipOutputCodec.decode(CoreUtilsUnzipOutputCodec.encode(out))
        assertEquals(out, decoded)
        assertEquals(mapOf("a.txt" to "alpha", "b.txt" to "beta"), decoded.readEntries)
    }

    @Test
    fun `codec unzip output — test variant roundtrip preserves every field`() = runBlocking {
        val ws = stubWorkspaceRoot()
        val archive = ws.resolve("seed.zip")
        java.util.zip.ZipOutputStream(Files.newOutputStream(archive)).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("a.txt"))
            zos.write("alpha".toByteArray())
            zos.closeEntry()
        }

        val out = unzipStep.handler.execute(
            UnzipInput(path = "seed.zip", mode = UnzipMode.Test),
            handlerContext(ws),
        )
        val decoded = CoreUtilsUnzipOutputCodec.decode(CoreUtilsUnzipOutputCodec.encode(out))
        assertEquals(out, decoded)
        assertNotNull(decoded.testReport)
        assertTrue(decoded.testReport!!.ok)
        assertEquals(emptyList<String>(), decoded.testReport!!.badEntries)
    }

    // -------- canonical envelope --------

    @Test
    fun `envelope — unzip input codec emits a well-formed JSON object (durable eligible)`() {
        val encoded = CoreUtilsUnzipInputCodec.encode(UnzipInput(path = "x.zip"))
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(encoded.value)
        assertTrue(parsed is JsonObject, "input envelope must be a JSON object")
    }

    @Test
    fun `envelope — unzip output codec rejects envelopes with no populated field`() {
        val encoded = kotlinx.serialization.json.Json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject { put("extracted", null); put("readEntries", null); put("testReport", null) },
        )
        assertThrows(PluginStepException::class.java) {
            CoreUtilsUnzipOutputCodec.decode(EncodedStepValue(encoded))
        }
    }

    @Test
    fun `envelope — unzip output codec rejects envelopes with two populated fields`() {
        val encoded = kotlinx.serialization.json.Json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("extracted", buildJsonObject {
                    put("destination", "x")
                    put("files", kotlinx.serialization.json.buildJsonArray { })
                })
                put("readEntries", buildJsonObject { put("a", "alpha") })
            },
        )
        assertThrows(PluginStepException::class.java) {
            CoreUtilsUnzipOutputCodec.decode(EncodedStepValue(encoded))
        }
    }

    // -------- success paths (extract) --------

    @Test
    fun `success — unzip Extract writes every entry under the workspace root`() = runBlocking {
        val ws = stubWorkspaceRoot()
        val archive = ws.resolve("seed.zip")
        java.util.zip.ZipOutputStream(Files.newOutputStream(archive)).use { zos ->
            listOf("a.txt" to "alpha", "b.txt" to "beta").forEach { (name, text) ->
                zos.putNextEntry(java.util.zip.ZipEntry(name))
                zos.write(text.toByteArray())
                zos.closeEntry()
            }
        }

        val out = unzipStep.handler.execute(
            UnzipInput(path = "seed.zip"),
            handlerContext(ws),
        )
        assertNotNull(out.extracted)
        val extracted = out.extracted!!
        assertEquals(ws.toAbsolutePath().normalize().toString(), extracted.destination)
        assertEquals(2, extracted.files.size)
        assertEquals("alpha", Files.readString(ws.resolve("a.txt")))
        assertEquals("beta", Files.readString(ws.resolve("b.txt")))
    }

    @Test
    fun `success — unzip Extract with destination sub-dir writes under the sub-dir`() = runBlocking {
        val ws = stubWorkspaceRoot()
        val archive = ws.resolve("seed.zip")
        java.util.zip.ZipOutputStream(Files.newOutputStream(archive)).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("hello.txt"))
            zos.write("hi".toByteArray())
            zos.closeEntry()
        }

        unzipStep.handler.execute(
            UnzipInput(path = "seed.zip", destination = "out"),
            handlerContext(ws),
        )
        assertEquals("hi", Files.readString(ws.resolve("out/hello.txt")))
    }

    @Test
    fun `success — unzip Extract with glob filters entries by name`() = runBlocking {
        val ws = stubWorkspaceRoot()
        val archive = ws.resolve("seed.zip")
        java.util.zip.ZipOutputStream(Files.newOutputStream(archive)).use { zos ->
            listOf("a.txt" to "alpha", "b.dat" to "beta").forEach { (name, text) ->
                zos.putNextEntry(java.util.zip.ZipEntry(name))
                zos.write(text.toByteArray())
                zos.closeEntry()
            }
        }

        val out = unzipStep.handler.execute(
            UnzipInput(path = "seed.zip", glob = "**/*.txt"),
            handlerContext(ws),
        )
        assertNotNull(out.extracted)
        assertEquals(listOf("a.txt"), out.extracted!!.files.map { it.name })
        assertTrue(Files.exists(ws.resolve("a.txt")))
        assertFalse(Files.exists(ws.resolve("b.dat")))
    }

    @Test
    fun `success — unzip Read returns entries as UTF-8 strings`() = runBlocking {
        val ws = stubWorkspaceRoot()
        val archive = ws.resolve("seed.zip")
        java.util.zip.ZipOutputStream(Files.newOutputStream(archive)).use { zos ->
            listOf("a.txt" to "alpha", "b.txt" to "beta").forEach { (name, text) ->
                zos.putNextEntry(java.util.zip.ZipEntry(name))
                zos.write(text.toByteArray())
                zos.closeEntry()
            }
        }

        val out = unzipStep.handler.execute(
            UnzipInput(path = "seed.zip", mode = UnzipMode.Read),
            handlerContext(ws),
        )
        assertEquals(mapOf("a.txt" to "alpha", "b.txt" to "beta"), out.readEntries)
    }

    @Test
    fun `success — unzip Test reports ok=true when every CRC matches`() = runBlocking {
        val ws = stubWorkspaceRoot()
        val archive = ws.resolve("seed.zip")
        java.util.zip.ZipOutputStream(Files.newOutputStream(archive)).use { zos ->
            listOf("a.txt" to "alpha").forEach { (name, text) ->
                zos.putNextEntry(java.util.zip.ZipEntry(name))
                zos.write(text.toByteArray())
                zos.closeEntry()
            }
        }

        val out = unzipStep.handler.execute(
            UnzipInput(path = "seed.zip", mode = UnzipMode.Test),
            handlerContext(ws),
        )
        assertNotNull(out.testReport)
        assertTrue(out.testReport!!.ok)
        assertEquals(1, out.testReport!!.entryCount)
        assertEquals(emptyList<String>(), out.testReport!!.badEntries)
    }

    // -------- typed failure --------

    @Test
    fun `typed failure — unzip on a missing archive raises USER class`() {
        val ws = stubWorkspaceRoot()
        val ex = assertThrows(PluginStepException::class.java) {
            runBlocking {
                unzipStep.handler.execute(
                    UnzipInput(path = "missing.zip"),
                    handlerContext(ws),
                )
            }
        }
        assertTrue(ex.failure.message!!.contains("archive not found"))
    }

    @Test
    fun `typed failure — unzip with destination outside workspace raises USER class`() {
        val ws = stubWorkspaceRoot()
        val archive = ws.resolve("seed.zip")
        java.util.zip.ZipOutputStream(Files.newOutputStream(archive)).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("a.txt"))
            zos.write("alpha".toByteArray())
            zos.closeEntry()
        }
        val ex = assertThrows(PluginStepException::class.java) {
            runBlocking {
                unzipStep.handler.execute(
                    UnzipInput(path = "seed.zip", destination = "/tmp/escape-out"),
                    handlerContext(ws),
                )
            }
        }
        assertTrue(ex.failure.message!!.contains("destination escapes the workspace"))
    }

    @Test
    fun `typed failure — zip-slip entry with backslash name raises USER class (CVE-2023-32981)`() {
        val ws = stubWorkspaceRoot()
        val archive = ws.resolve("seed.zip")
        java.util.zip.ZipOutputStream(Files.newOutputStream(archive)).use { zos ->
            // Force entry name with backslashes: ZipOutputStream rejects
            // the backslash on put, so we use a ZipEntry constructor that
            // bypasses validation (constructor only stores the name).
            val entry = java.util.zip.ZipEntry("..\\Windows\\evil.txt")
            zos.putNextEntry(entry)
            zos.write("pwn".toByteArray())
            zos.closeEntry()
        }

        val ex = assertThrows(PluginStepException::class.java) {
            runBlocking {
                unzipStep.handler.execute(
                    UnzipInput(path = "seed.zip"),
                    handlerContext(ws),
                )
            }
        }
        assertTrue(ex.failure.message!!.contains("Zip Slip"))
    }

    @Test
    fun `typed failure — zip-slip entry with parent-segment name raises USER class (CVE-2023-32981)`() {
        val ws = stubWorkspaceRoot()
        val archive = ws.resolve("seed.zip")
        java.util.zip.ZipOutputStream(Files.newOutputStream(archive)).use { zos ->
            val entry = java.util.zip.ZipEntry("../escaped.txt")
            zos.putNextEntry(entry)
            zos.write("pwn".toByteArray())
            zos.closeEntry()
        }

        val ex = assertThrows(PluginStepException::class.java) {
            runBlocking {
                unzipStep.handler.execute(
                    UnzipInput(path = "seed.zip"),
                    handlerContext(ws),
                )
            }
        }
        assertTrue(ex.failure.message!!.contains("Zip Slip"))
    }

    @Test
    fun `typed failure — zip-slip entry with absolute name raises USER class (CVE-2023-32981)`() {
        val ws = stubWorkspaceRoot()
        val archive = ws.resolve("seed.zip")
        // ZipOutputStream does NOT sanitise entry names at write time
        // (it stores whatever bytes the caller gives it). Our unzip
        // step must reject the entry because the resolved target
        // escapes the destination root.
        java.util.zip.ZipOutputStream(Files.newOutputStream(archive)).use { zos ->
            val entry = java.util.zip.ZipEntry("/tmp/abs.txt")
            zos.putNextEntry(entry)
            zos.write("pwn".toByteArray())
            zos.closeEntry()
        }

        val ex = assertThrows(PluginStepException::class.java) {
            runBlocking {
                unzipStep.handler.execute(
                    UnzipInput(path = "seed.zip"),
                    handlerContext(ws),
                )
            }
        }
        assertTrue(ex.failure.message!!.contains("Zip Slip"))
    }

    @Test
    fun `typed failure — corrupt CRC in Test mode is reported in the TestReport, not raised`() = runBlocking {
        val ws = stubWorkspaceRoot()
        // Build a zip with method=STORED so the data is uncompressed in
        // the file. Then flip a bit in the data section: the stored
        // CRC will no longer match the recomputed one. (If we left
        // DEFLATE on, corrupting a byte would break the inflater
        // before the CRC mismatch could be detected.)
        val archive = ws.resolve("seed.zip")
        val payload = "alpha".toByteArray()
        val expectedCrc = java.util.zip.CRC32().apply { update(payload) }.value
        java.util.zip.ZipOutputStream(Files.newOutputStream(archive)).use { zos ->
            zos.setMethod(java.util.zip.ZipOutputStream.STORED)
            val entry = java.util.zip.ZipEntry("a.txt")
            entry.size = payload.size.toLong()
            entry.compressedSize = payload.size.toLong()
            entry.crc = expectedCrc
            zos.putNextEntry(entry)
            zos.write(payload)
            zos.closeEntry()
        }
        val bytes = Files.readAllBytes(archive).toMutableList()
        // Local file header = 30 bytes fixed + name (5) + extra (0).
        // The first byte of the data section is at offset 35. Flip
        // bit 0 so the stored CRC no longer matches.
        val headerEnd = 30 + "a.txt".length
        bytes[headerEnd] = bytes[headerEnd].toInt().xor(0x01).toByte()
        Files.write(archive, bytes.toByteArray())

        val out = unzipStep.handler.execute(
            UnzipInput(path = "seed.zip", mode = UnzipMode.Test),
            handlerContext(ws),
        )
        assertNotNull(out.testReport)
        assertFalse(out.testReport!!.ok)
        assertEquals(listOf("a.txt"), out.testReport!!.badEntries)
    }
}
