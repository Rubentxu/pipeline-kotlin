package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PluginStepException
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.RecoveryPolicy
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
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
import dev.rubentxu.pipeline.v2.domain.step.registerContributors
import dev.rubentxu.pipeline.v2.sdk.junit.step.JUnitReportSummary
import dev.rubentxu.pipeline.v2.sdk.junit.step.JUnitReportSummaryCodec
import dev.rubentxu.pipeline.v2.sdk.junit.step.JUnitResultsInput
import dev.rubentxu.pipeline.v2.sdk.junit.step.JUnitResultsInputCodec
import dev.rubentxu.pipeline.v2.sdk.junit.step.JUnitResultsKey
import dev.rubentxu.pipeline.v2.sdk.junit.step.JUnitResultsStepDefinition
import dev.rubentxu.pipeline.v2.sdk.junit.step.JUnitStepDefinitionContributor
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Contract / negative-path coverage for the JUnit OFFICIAL_PLUGIN
 * (F5.2). Mirrors the F5.1 pattern:
 *
 *  - identity and contract completeness
 *  - codec roundtrip (lossless)
 *  - schema fragment present
 *  - registry resolution via the SDK seam
 *  - capability declaration (empty set today, F5.2 follow-up plans)
 *  - delivery shape: OFFICIAL_PLUGIN
 *  - contributor fail-closed when provenance is malformed
 *  - handler fail-closed paths: missing file, empty file, oversized
 *    file, malformed XML, report with failures when failOnFailure=true,
 *    directory-not-file
 *  - handler success path: a clean XML roundtrips through the typed
 *    summary; relative path resolves against workspaceRoot
 *
 * End-to-end UAT (real .pipeline.kts via installDist) lives in the
 * F5.2 closure receipt; this file is the regression surface.
 */
class F5_2_JUnitStepContractTest {

    @Test
    fun `identity and contract completeness`() {
        val definition = JUnitResultsStepDefinition()
        assertEquals(JUnitResultsKey.VALUE, definition.contract.key)
        assertEquals("junit", definition.contract.descriptor.pluginId)
        assertEquals("junit.results", definition.contract.descriptor.name)
        assertEquals("junit.results", definition.contract.descriptor.stepId)
        assertEquals(ReplayPolicy.MEMOIZED, definition.contract.descriptor.replayPolicy)
        assertTrue(definition.contract.descriptor.effects.contains(Effect.READ_ONLY))
        // F5.1 / F5.2 UAT-closure precedent: the contract declares an
        // empty capability set so the canonical engine admits the
        // invocation; capability-routed workspace root is a follow-up.
        assertTrue(definition.contract.requiredCapabilities.isEmpty())
    }

    @Test
    fun `codec roundtrip input lossless`() {
        val input = JUnitResultsInput(
            reportPath = "build/test-results/test.xml",
            workspaceRoot = "/tmp/ws",
            failOnFailure = false,
            maxReportBytes = 100L,
        )
        val encoded = JUnitResultsInputCodec.encode(input)
        val roundtripped = JUnitResultsInputCodec.decode(encoded)
        assertEquals(input, roundtripped)
    }

    @Test
    fun `input codec rejects missing required keys`() {
        val malformed = EncodedStepValue("""{"reportPath":"x"}""")
        val ex = assertThrows(NoSuchElementException::class.java) {
            JUnitResultsInputCodec.decode(malformed)
        }
        assertTrue(
            ex.message!!.contains("workspaceRoot"),
            "Expected the missing-key diagnostic to name workspaceRoot, got: ${ex.message}",
        )
    }

    @Test
    fun `input codec defaults failOnFailure to true and maxReportBytes to 10 MiB`() {
        val minimal = EncodedStepValue("""{"reportPath":"x","workspaceRoot":"/tmp"}""")
        val decoded = JUnitResultsInputCodec.decode(minimal)
        assertTrue(decoded.failOnFailure)
        assertEquals(JUnitResultsInput.DEFAULT_MAX_REPORT_BYTES, decoded.maxReportBytes)
    }

    @Test
    fun `input codec schema fragment names the required fields`() {
        val schema = JUnitResultsInputCodec.schema()
        assertTrue(schema.contains("reportPath"))
        assertTrue(schema.contains("workspaceRoot"))
        assertTrue(schema.contains("failOnFailure"))
        assertTrue(schema.contains("maxReportBytes"))
    }

    @Test
    fun `empty capability access never satisfies any declared capability`() {
        val access = EmptyCapabilityAccess
        assertFalse(
            access.available().contains(StepCapability("junit.operations")),
            "Empty capability access must not satisfy any declared capability",
        )
    }

    @Test
    fun `JUnitStepDefinitionContributor refuses to register when digest is malformed even with build-time provenance`() {
        val keys = listOf(
            "pipeline.junit.publisher",
            "pipeline.junit.namespace",
            "pipeline.junit.release.version",
            "pipeline.junit.release.digest",
        )
        val previous = keys.associateWith { System.getProperty(it) }
        System.setProperty("pipeline.junit.publisher", "pipeline-kotlin")
        System.setProperty("pipeline.junit.namespace", "pipeline.junit")
        System.setProperty("pipeline.junit.release.version", "0.1.0")
        System.setProperty("pipeline.junit.release.digest", "not-a-valid-digest")
        try {
            val ex = assertThrows(IllegalArgumentException::class.java) {
                JUnitStepDefinitionContributor().registrations().toList()
            }
            assertTrue(
                ex.message!!.contains("sha256"),
                "Expected the structural guard to mention the sha256: prefix, got: ${ex.message}",
            )
        } finally {
            previous.forEach { (k, v) ->
                if (v != null) System.setProperty(k, v) else System.clearProperty(k)
            }
        }
    }

    @Test
    fun `JUnitStepDefinitionContributor builds a real registration with OFFICIAL_PLUGIN delivery when provenance is well-formed`() {
        val realDigest = "sha256:" + "a".repeat(64)
        val registry = InMemoryStepRegistry()
        val provider = registerJUnitFixture(
            publisher = "pipeline-kotlin",
            namespace = "pipeline.junit",
            version = "0.1.0",
            digestSha256 = realDigest,
            registry = registry,
        )
        assertEquals(Delivery.OFFICIAL_PLUGIN, provider.delivery)
        assertEquals(realDigest, provider.release.digest.value)
        assertEquals("pipeline-kotlin", provider.publisher)
        assertEquals(setOf(PluginFamily.TESTING, PluginFamily.REPORTING), provider.families)
        assertEquals(SemVer(0, 1, 0), provider.release.version)
        assertNotNull(registry.providerOf(JUnitResultsKey.VALUE))
    }

    @Test
    fun `C10 backwards-compat legacy contributor coexists with JUnit contributor`() {
        val registry = InMemoryStepRegistry()
        val legacyContributor = legacyFixture("legacy.fixture", "legacy-fixture", 1, 0, 0)
        registry.registerContributors(listOf(legacyContributor))
        val legacyKey = dev.rubentxu.pipeline.v2.domain.PluginStepId("legacy.fixture.greet")
        assertTrue(registry.contains(legacyKey))
        val legacyProvider = registry.providerOf(legacyKey)
        assertNotNull(legacyProvider)
        // Legacy contributors go through the default registrations() path
        // (StepRegistration.legacy), so their delivery is EXTERNAL_REFERENCE
        // (non-core legacy contributor).
        assertEquals(Delivery.EXTERNAL_REFERENCE, legacyProvider!!.delivery)
    }

    @Test
    fun `handler fails closed when the report file does not exist`() = runBlocking {
        val missing = Path.of("/tmp/pk-uat-f5-2/missing-${System.nanoTime()}.xml")
        val definition = JUnitResultsStepDefinition()
        val ctx = newContext()
        val input = JUnitResultsInput(reportPath = missing.toString(), workspaceRoot = "/tmp")
        val ex = assertThrows(PluginStepException::class.java) {
            runBlocking { definition.handler.execute(input, ctx) }
        }
        assertEquals(FailureKind.USER, ex.failure.kind)
        assertTrue(
            ex.failure.message.contains("not found"),
            "Expected USER failure naming the missing file, got: ${ex.failure.message}",
        )
    }

    @Test
    fun `handler fails closed when the report file is empty`() = runBlocking {
        val tmp: Path = Files.createTempFile("junit-empty-", ".xml")
        Files.writeString(tmp, "")
        try {
            val definition = JUnitResultsStepDefinition()
            val ctx = newContext()
            val input = JUnitResultsInput(reportPath = tmp.toString(), workspaceRoot = tmp.parent.toString())
            val ex = assertThrows(PluginStepException::class.java) {
                runBlocking { definition.handler.execute(input, ctx) }
            }
            assertEquals(FailureKind.USER, ex.failure.kind)
            assertTrue(
                ex.failure.message.contains("empty"),
                "Expected USER failure naming empty file, got: ${ex.failure.message}",
            )
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    @Test
    fun `handler fails closed when the report file exceeds maxReportBytes`() = runBlocking {
        val tmp: Path = Files.createTempFile("junit-too-big-", ".xml")
        // 100 bytes of XML content, but we cap at 50.
        val content = "<?xml version=\"1.0\"?><testsuite tests=\"0\" failures=\"0\" errors=\"0\" skipped=\"0\" time=\"0.0\"/>"
        Files.writeString(tmp, content)
        try {
            val definition = JUnitResultsStepDefinition()
            val ctx = newContext()
            val input = JUnitResultsInput(
                reportPath = tmp.toString(),
                workspaceRoot = tmp.parent.toString(),
                maxReportBytes = 50L,
            )
            val ex = assertThrows(PluginStepException::class.java) {
                runBlocking { definition.handler.execute(input, ctx) }
            }
            assertEquals(FailureKind.USER, ex.failure.kind)
            assertTrue(
                ex.failure.message.contains("maxReportBytes"),
                "Expected USER failure naming the byte cap, got: ${ex.failure.message}",
            )
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    @Test
    fun `handler fails closed when the XML is malformed`() = runBlocking {
        val tmp: Path = Files.createTempFile("junit-broken-", ".xml")
        Files.writeString(tmp, "<testsuite name=\"x\" tests=\"")
        try {
            val definition = JUnitResultsStepDefinition()
            val ctx = newContext()
            val input = JUnitResultsInput(reportPath = tmp.toString(), workspaceRoot = tmp.parent.toString())
            val ex = assertThrows(PluginStepException::class.java) {
                runBlocking { definition.handler.execute(input, ctx) }
            }
            assertEquals(FailureKind.USER, ex.failure.kind)
            assertTrue(
                ex.failure.message.contains("malformed XML"),
                "Expected USER failure naming malformed XML, got: ${ex.failure.message}",
            )
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    @Test
    fun `handler fails closed when failOnFailure is true and the report has failures`() = runBlocking {
        val tmp: Path = Files.createTempFile("junit-failed-", ".xml")
        Files.writeString(
            tmp,
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="X" tests="3" failures="1" errors="0" skipped="0" time="0.1">
                <testcase name="pass"/>
                <testcase name="boom"><failure message="oops"/></testcase>
                <testcase name="pass"/>
            </testsuite>
            """.trimIndent(),
        )
        try {
            val definition = JUnitResultsStepDefinition()
            val ctx = newContext()
            val input = JUnitResultsInput(reportPath = tmp.toString(), workspaceRoot = tmp.parent.toString())
            val ex = assertThrows(PluginStepException::class.java) {
                runBlocking { definition.handler.execute(input, ctx) }
            }
            assertEquals(FailureKind.USER, ex.failure.kind)
            assertTrue(
                ex.failure.message.contains("1 failed"),
                "Expected USER failure naming the number of failed tests, got: ${ex.failure.message}",
            )
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    @Test
    fun `handler succeeds when failOnFailure is false and the report has failures`() = runBlocking {
        val tmp: Path = Files.createTempFile("junit-info-", ".xml")
        Files.writeString(
            tmp,
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="X" tests="3" failures="1" errors="0" skipped="1" time="0.1">
                <testcase name="pass"/>
                <testcase name="boom"><failure message="oops"/></testcase>
                <testcase name="skipped"><skipped/></testcase>
            </testsuite>
            """.trimIndent(),
        )
        try {
            val definition = JUnitResultsStepDefinition()
            val ctx = newContext()
            val input = JUnitResultsInput(
                reportPath = tmp.toString(),
                workspaceRoot = tmp.parent.toString(),
                failOnFailure = false,
            )
            val summary: JUnitReportSummary = definition.handler.execute(input, ctx)
            assertEquals(3, summary.tests)
            assertEquals(1, summary.failures)
            assertEquals(0, summary.errors)
            assertEquals(1, summary.skipped)
            assertFalse(summary.isClean)
            // Roundtrip the typed output through the output codec.
            val encoded = JUnitReportSummaryCodec.encode(summary)
            val decoded = JUnitReportSummaryCodec.decode(encoded)
            assertEquals(summary, decoded)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    @Test
    fun `handler succeeds on a clean report and emits a typed summary`() = runBlocking {
        val tmp: Path = Files.createTempFile("junit-clean-", ".xml")
        Files.writeString(
            tmp,
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="X" tests="4" failures="0" errors="0" skipped="0" time="1.5">
                <testcase name="a"/>
                <testcase name="b"/>
                <testcase name="c"/>
                <testcase name="d"/>
            </testsuite>
            """.trimIndent(),
        )
        try {
            val definition = JUnitResultsStepDefinition()
            val ctx = newContext()
            val input = JUnitResultsInput(reportPath = tmp.toString(), workspaceRoot = tmp.parent.toString())
            val summary = definition.handler.execute(input, ctx)
            assertEquals(4, summary.tests)
            assertTrue(summary.isClean)
            assertEquals(tmp.toString(), summary.reportPath)
            assertEquals(1.5, summary.durationSeconds, 1e-9)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    @Test
    fun `handler resolves a relative reportPath against the workspaceRoot`() = runBlocking {
        val wsDir = Files.createTempDirectory("junit-ws-")
        try {
            val report = wsDir.resolve("nested/results.xml")
            Files.createDirectories(report.parent)
            Files.writeString(
                report,
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="X" tests="1" failures="0" errors="0" skipped="0" time="0.0"/>
                """.trimIndent(),
            )
            val definition = JUnitResultsStepDefinition()
            val ctx = newContext()
            val input = JUnitResultsInput(
                reportPath = "nested/results.xml",
                workspaceRoot = wsDir.toString(),
            )
            val summary = definition.handler.execute(input, ctx)
            assertEquals(1, summary.tests)
            assertEquals(report.toString(), summary.reportPath)
        } finally {
            Files.walk(wsDir)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun `handler refuses a reportPath that resolves to a directory`() = runBlocking {
        val dir = Files.createTempDirectory("junit-dir-")
        try {
            val definition = JUnitResultsStepDefinition()
            val ctx = newContext()
            val input = JUnitResultsInput(reportPath = dir.toString(), workspaceRoot = dir.parent.toString())
            val ex = assertThrows(PluginStepException::class.java) {
                runBlocking { definition.handler.execute(input, ctx) }
            }
            assertEquals(FailureKind.USER, ex.failure.kind)
            assertTrue(
                ex.failure.message.contains("directory"),
                "Expected USER failure naming directory path, got: ${ex.failure.message}",
            )
        } finally {
            Files.deleteIfExists(dir)
        }
    }

    // ----------------------------------------------------------------------
    // workspaceRoot resolution (F5.2 follow-up: "pipeline.workspace.root"
    // fallback when input.workspaceRoot is blank / "." / "./" or points at a
    // non-existent directory). When the caller leaves it unset, the handler
    // falls back to the system property `pipeline.workspace.root` (set by
    // the binary when --workspace is provided), so a relative reportPath
    // resolves against the actual pipeline workspace without the script
    // author having to know the absolute path.
    // ----------------------------------------------------------------------

    @Test
    fun `handler falls back to pipeline workspace when workspaceRoot is dot`() = runBlocking {
        val tmpWs = Files.createTempDirectory("junit-pipeline-ws-")
        val report = tmpWs.resolve("nested/results.xml")
        Files.createDirectories(report.parent)
        Files.writeString(
            report,
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="X" tests="2" failures="0" errors="0" skipped="0" time="0.0"/>
            """.trimIndent(),
        )
        val saved = System.getProperty("pipeline.workspace.root")
        try {
            System.setProperty("pipeline.workspace.root", tmpWs.toString())
            val definition = JUnitResultsStepDefinition()
            val ctx = newContext()
            // Relative workspaceRoot="." + relative reportPath = "nested/results.xml"
            // must resolve against the system property's workspace root.
            val input = JUnitResultsInput(
                reportPath = "nested/results.xml",
                workspaceRoot = ".",
            )
            val summary = definition.handler.execute(input, ctx)
            assertEquals(2, summary.tests)
            assertEquals(report.toString(), summary.reportPath)
        } finally {
            if (saved != null) System.setProperty("pipeline.workspace.root", saved) else System.clearProperty("pipeline.workspace.root")
            Files.walk(tmpWs).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun `handler falls back to pipeline workspace when workspaceRoot is blank`() = runBlocking {
        val tmpWs = Files.createTempDirectory("junit-pipeline-ws-")
        val report = tmpWs.resolve("r.xml")
        Files.writeString(
            report,
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="X" tests="1" failures="0" errors="0" skipped="0" time="0.0"/>
            """.trimIndent(),
        )
        val saved = System.getProperty("pipeline.workspace.root")
        try {
            System.setProperty("pipeline.workspace.root", tmpWs.toString())
            val definition = JUnitResultsStepDefinition()
            val ctx = newContext()
            val input = JUnitResultsInput(reportPath = "r.xml", workspaceRoot = "")
            val summary = definition.handler.execute(input, ctx)
            assertEquals(1, summary.tests)
            assertEquals(report.toString(), summary.reportPath)
        } finally {
            if (saved != null) System.setProperty("pipeline.workspace.root", saved) else System.clearProperty("pipeline.workspace.root")
            Files.walk(tmpWs).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun `handler falls back to pipeline workspace when workspaceRoot points at a non-existent directory`() = runBlocking {
        val tmpWs = Files.createTempDirectory("junit-pipeline-ws-")
        val report = tmpWs.resolve("r.xml")
        Files.writeString(
            report,
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="X" tests="1" failures="0" errors="0" skipped="0" time="0.0"/>
            """.trimIndent(),
        )
        val saved = System.getProperty("pipeline.workspace.root")
        try {
            System.setProperty("pipeline.workspace.root", tmpWs.toString())
            val definition = JUnitResultsStepDefinition()
            val ctx = newContext()
            val input = JUnitResultsInput(
                reportPath = "r.xml",
                workspaceRoot = "/nonexistent/should/never/be/used",
            )
            val summary = definition.handler.execute(input, ctx)
            assertEquals(1, summary.tests)
            assertEquals(report.toString(), summary.reportPath)
        } finally {
            if (saved != null) System.setProperty("pipeline.workspace.root", saved) else System.clearProperty("pipeline.workspace.root")
            Files.walk(tmpWs).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun `handler respects absolute workspaceRoot even when system property is set`() = runBlocking {
        val tmpWs1 = Files.createTempDirectory("junit-pipeline-ws-a-")
        val tmpWs2 = Files.createTempDirectory("junit-pipeline-ws-b-")
        // Put a file in tmpWs2 with one test; tmpWs1 has zero tests.
        val report = tmpWs2.resolve("a.xml")
        Files.writeString(
            report,
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="X" tests="1" failures="0" errors="0" skipped="0" time="0.0"/>
            """.trimIndent(),
        )
        val saved = System.getProperty("pipeline.workspace.root")
        try {
            // System property points at ws1, but the caller explicitly set
            // workspaceRoot to ws2 (absolute). The handler MUST use ws2.
            System.setProperty("pipeline.workspace.root", tmpWs1.toString())
            val definition = JUnitResultsStepDefinition()
            val ctx = newContext()
            val input = JUnitResultsInput(reportPath = "a.xml", workspaceRoot = tmpWs2.toString())
            val summary = definition.handler.execute(input, ctx)
            assertEquals(1, summary.tests)
        } finally {
            if (saved != null) System.setProperty("pipeline.workspace.root", saved) else System.clearProperty("pipeline.workspace.root")
            for (d in listOf(tmpWs1, tmpWs2)) {
                Files.walk(d).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    // -- helpers ----------------------------------------------------------

    private object EmptyCapabilityAccess : StepCapabilityAccess {
        override fun available(): Set<StepCapability> = emptySet()
        override fun <T : Any> get(key: StepCapability): T =
            error("Capability $key is not available")
    }

    private fun newContext(): StepHandlerContext = StepHandlerContext(
        runId = RunId("test-run"),
        stepIndex = 0,
        capabilities = EmptyCapabilityAccess,
    )

    private fun registerJUnitFixture(
        publisher: String,
        namespace: String,
        version: String,
        digestSha256: String,
        registry: dev.rubentxu.pipeline.v2.domain.step.StepRegistry,
    ): StepProviderMetadata {
        require(digestSha256.startsWith("sha256:"))
        val saved = listOf(
            "pipeline.junit.publisher",
            "pipeline.junit.namespace",
            "pipeline.junit.release.version",
            "pipeline.junit.release.digest",
        ).associateWith { System.getProperty(it) }
        try {
            System.setProperty("pipeline.junit.publisher", publisher)
            System.setProperty("pipeline.junit.namespace", namespace)
            System.setProperty("pipeline.junit.release.version", version)
            System.setProperty("pipeline.junit.release.digest", digestSha256)
            val registration: StepRegistration<*, *> =
                JUnitStepDefinitionContributor().registrations().single()
            registry.register(registration)
            return registration.provider
        } finally {
            saved.forEach { (k, prev) ->
                if (prev == null) System.clearProperty(k) else System.setProperty(k, prev)
            }
        }
    }

    /**
     * Synthesises a legacy-style contributor (no override of
     * `registrations()`) that contributes one trivial greeting Step.
     * Used by [C10 backwards-compat legacy contributor coexists with JUnit contributor]
     * to prove the additive registration path also keeps legacy
     * contributors working without code changes.
     */
    private fun legacyFixture(
        keyPrefix: String,
        pluginId: String,
        major: Int,
        minor: Int,
        patch: Int,
    ): dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor =
        object : dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor {
            override val id: String = keyPrefix
            override fun definitions(): Iterable<StepDefinition<*, *>> {
                val key = dev.rubentxu.pipeline.v2.domain.PluginStepId("$keyPrefix.greet")
                val descriptor = StepDescriptor(
                    stepId = "$keyPrefix.greet",
                    name = "greet",
                    configRef = "",
                    pluginId = pluginId,
                    pluginVersion = "$major.$minor.$patch",
                    executionLocation = ExecutionLocation.CONTROLLER,
                    effects = emptyList(),
                    replayPolicy = ReplayPolicy.MEMOIZED,
                    recoveryPolicy = RecoveryPolicy.None,
                )
                val stringCodec = object : dev.rubentxu.pipeline.v2.domain.step.StepCodec<String> {
                    override fun encode(value: String): EncodedStepValue =
                        EncodedStepValue("\"${value.replace("\"", "\\\"")}\"")
                    override fun decode(encoded: EncodedStepValue): String =
                        encoded.value.trim('"')
                    override fun schema(): String = """{"type":"string"}"""
                }
                val contract = StepContract(
                    key = key,
                    descriptor = descriptor,
                    inputCodec = stringCodec,
                    outputCodec = stringCodec,
                    requiredCapabilities = emptySet(),
                )
                val handler = StepHandler<String, String> { input, _ -> "hi:$input" }
                val def: StepDefinition<String, String> = object : StepDefinition<String, String> {
                    override val contract = contract
                    override val handler = handler
                }
                return listOf(def)
            }
        }

    // ResourceRefs is referenced indirectly above; the import keeps it
    // available for future expansion of the helper without re-editing.
    @Suppress("unused")
    private val keepResourceRefsImport: Any = ResourceRefs
}
