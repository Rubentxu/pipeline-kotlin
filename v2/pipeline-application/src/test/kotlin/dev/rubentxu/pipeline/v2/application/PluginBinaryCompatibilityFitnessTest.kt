package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator
import dev.rubentxu.pipeline.v2.application.durable.CanonicalNodeDispatcher
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeFailure
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeOutcome
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.dsl.pipeline
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest

/**
 * LFC-2E3-P / P1 — PLUGIN ABI COMPATIBILITY fitness.
 *
 * Enforces the law in AGENTS.md § PLUGIN ABI COMPATIBILITY:
 *
 * ```text
 * existing plugin SPI changes  ->  additive NEW SPI / interface
 * old SPI remains binary-compatible
 * ```
 *
 * Two independent guards, because they fail differently:
 *
 * 1. **Golden ABI manifest** freezes the member set of every public plugin SPI. It catches the
 *    *intent* to change an SPI at review time, with no binary involved.
 * 2. **Preserved plugin JAR** is a plugin built against the SPI shape at freeze time, committed as
 *    a resource and NEVER recompiled by this test. It must still load, register, be admitted and
 *    EXECUTE against the current host. It catches the *consequence*, which no source-level test
 *    can:
 *
 *    ```text
 *    existing SPI + new abstract/default member
 *      -> source-compatible (every source-level test still passes)
 *      -> old prebuilt JAR  ->  AbstractMethodError at runtime
 *    ```
 *
 * The JAR is loaded in an isolated [URLClassLoader] and is deliberately NOT on the test classpath:
 * putting it there would pollute every other suite's registry counts, and the isolated loader is
 * also the more faithful model of a host loading a plugin it was never compiled against.
 *
 * The execution assertion goes through the REAL production path — `registerInto` discovery, then a
 * pipeline run through [CanonicalDurableRunCoordinator] — with **zero fixture types referenced at
 * compile time**. The host knows the plugin only through `PluginStepId` and encoded JSON, exactly as
 * in production.
 *
 * ## Regenerating the golden artifacts (deliberate, reviewed)
 *
 * ```bash
 * # manifest - only when an SPI change was explicitly approved as an ABI event
 * ./gradlew -p v2 :pipeline-application:test \
 *     --tests '*PluginBinaryCompatibilityFitnessTest*' -Ppipeline.abi.writeGolden=true
 *
 * # fixture JAR - only when the fixture itself must change
 * ./gradlew -p v2 :buildAbiFixturePlugin
 * cp examples/abi-fixture-plugin/build/libs/abi-fixture-plugin-1.0.0.jar \
 *    v2/pipeline-application/src/test/resources/abi/
 * # then update FIXTURE_JAR_SHA256 below
 * ```
 */
@Timeout(60)
class PluginBinaryCompatibilityFitnessTest {

    companion object {
        /**
         * Digest of the preserved fixture JAR. Pinned so the artifact cannot be silently swapped
         * for a freshly compiled one, which would defeat the entire guard.
         */
        const val FIXTURE_JAR_SHA256: String =
            "62daf4d6c8980cd2be11b4ae188437eb10c751519fee59d5cbbe37b3f1d5acf4"

        const val FIXTURE_JAR_RESOURCE: String = "/abi/abi-fixture-plugin-1.0.0.jar"

        /** `abi.fixture` — the coordinate of the frozen plugin. */
        const val FIXTURE_COORDINATE: String = "abi.fixture"

        /** `abi.fixture.echo` — the StepKey the frozen plugin contributes. */
        const val FIXTURE_STEP_KEY: String = "abi.fixture.echo"

        /**
         * Public SPIs a plugin either IMPLEMENTS or is handed. Changing any of these is an ABI
         * event: it requires a new additive interface, not a modification.
         */
        val FROZEN_SPIS: List<String> = listOf(
            "dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor",
            "dev.rubentxu.pipeline.v2.domain.step.StepCapabilityContributor",
            "dev.rubentxu.pipeline.v2.domain.step.StepDefinition",
            "dev.rubentxu.pipeline.v2.domain.step.StepCodec",
            "dev.rubentxu.pipeline.v2.domain.step.StepHandler",
            "dev.rubentxu.pipeline.v2.domain.step.StepRegistry",
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Guard 1 — golden ABI manifest
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Renders `interface` then its sorted member signatures.
     *
     * Signatures include erased parameter and return types, and therefore the Kotlin value-class
     * mangling suffix (e.g. `encode-9TXO0do`) — which IS part of the ABI: a change to a value
     * class's underlying representation changes it, and that is precisely the kind of change a
     * prebuilt plugin cannot survive.
     */
    private fun liveAbiManifest(): String = buildString {
        for (name in FROZEN_SPIS) {
            val type = Class.forName(name)
            appendLine(name)
            type.declaredMethods
                .map { method ->
                    val params = method.parameterTypes.joinToString(",") { it.name }
                    "  ${method.name}($params): ${method.returnType.name}"
                }
                .sorted()
                .forEach { appendLine(it) }
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun goldenManifestFile(): java.nio.file.Path =
        Paths.get(System.getProperty("user.dir")).resolve("src/test/resources/abi/plugin-spi-abi.txt")

    @Test
    fun `public plugin SPI member sets are frozen - changing an existing SPI is an ABI event`() {
        val live = liveAbiManifest()
        val golden = goldenManifestFile()

        if (System.getProperty("pipeline.abi.writeGolden") == "true") {
            golden.parent.toFile().mkdirs()
            golden.toFile().writeText(live)
            // Deliberately fail on regeneration so the change is never silent.
            throw AssertionError(
                "Golden ABI manifest regenerated at $golden. Review the diff: an existing plugin " +
                    "SPI MUST NOT change. New capability belongs in a NEW additive SPI.",
            )
        }

        assertTrue(
            Files.exists(golden),
            "missing golden ABI manifest at $golden; regenerate with " +
                "-Ppipeline.abi.writeGolden=true after reviewing the change",
        )
        assertEquals(
            golden.toFile().readText(),
            live,
            "Public plugin SPI ABI drift detected. Changing an existing SPI breaks every " +
                "already-built plugin JAR (source compatibility is NOT ABI compatibility). " +
                "Add a NEW additive SPI/interface instead. Frozen SPIs: $FROZEN_SPIS",
        )
    }

    @Test
    fun `the SPI plugins implement keeps exactly its original member set`() {
        // Belt and braces on the specific interface that regressed once: it must never gain a
        // member, default value or not.
        assertEquals(
            setOf("getId", "definitions"),
            StepDefinitionContributor::class.java.declaredMethods.map { it.name }.toSet(),
            "StepDefinitionContributor must keep exactly {id, definitions}. Capability " +
                "contribution lives in the separate StepCapabilityContributor SPI.",
        )
        val capabilitySpi = Class.forName(
            "dev.rubentxu.pipeline.v2.domain.step.StepCapabilityContributor",
        )
        assertFalse(
            StepDefinitionContributor::class.java.isAssignableFrom(capabilitySpi),
            "StepCapabilityContributor must not extend StepDefinitionContributor",
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Guard 2 — preserved plugin JAR against the current host
    // ─────────────────────────────────────────────────────────────────────────

    private fun fixtureJarPath(): java.nio.file.Path {
        val url = javaClass.getResource(FIXTURE_JAR_RESOURCE)
        assertNotNull(url, "preserved ABI fixture JAR missing from test resources: $FIXTURE_JAR_RESOURCE")
        return Paths.get(url!!.toURI())
    }

    @Test
    fun `the preserved plugin JAR is byte-identical to the frozen artifact`() {
        assertEquals(
            FIXTURE_JAR_SHA256,
            sha256Hex(Files.readAllBytes(fixtureJarPath())),
            "The ABI fixture JAR changed. It MUST stay frozen: a rebuilt plugin always matches the " +
                "current SDK and can no longer observe the regression it guards. If the fixture " +
                "genuinely must change, regenerate it deliberately, update FIXTURE_JAR_SHA256, " +
                "and record why in a receipt.",
        )
    }

    @Test
    fun `old plugin JAR loads registers and is admitted by the current host`() {
        withFixtureLoaded { registry, loader ->
            // ── load: the class really comes from the preserved JAR ──
            val contributorClass = Class.forName("abi.fixture.AbiFixtureContributor", true, loader)
            val codeSource = contributorClass.protectionDomain.codeSource.location.file
            assertTrue(
                codeSource.endsWith("abi-fixture-plugin-1.0.0.jar"),
                "the fixture must be loaded from the preserved JAR, but came from: $codeSource",
            )
            assertTrue(
                StepDefinitionContributor::class.java.isAssignableFrom(contributorClass),
                "the fixture must implement StepDefinitionContributor",
            )
            // It models a plugin built BEFORE the capability SPI existed: it must not implement it.
            assertFalse(
                Class.forName("dev.rubentxu.pipeline.v2.domain.step.StepCapabilityContributor")
                    .isAssignableFrom(contributorClass),
                "the fixture must NOT implement the newer capability SPI",
            )

            // ── register: through the SAME production discovery adapter (already composed by
            // the helper, which is exactly how the host composition root does it) ──
            assertTrue(
                ExternalStepPluginDiscovery.registerInto(InMemoryStepRegistry()).contains(FIXTURE_COORDINATE),
                "the preserved contributor must be discoverable through the production adapter",
            )
            val key = PluginStepId(FIXTURE_STEP_KEY)
            assertTrue(registry.contains(key), "the fixture Step must register")

            // ── admit: a no-capability Step must be admissible with the canonical bridge alone ──
            val definition = registry.definition(key)
            assertNotNull(definition, "the fixture Step must be resolvable")
            assertEquals(
                setOf<dev.rubentxu.pipeline.v2.domain.step.StepCapability>(),
                definition!!.contract.requiredCapabilities,
                "the frozen fixture declares no capabilities; if this changes, the fixture drifted",
            )
            val admission = dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionPreparation
                .prepare(
                    registry = registry,
                    key = key,
                    encodedInput = EncodedStepValue("""{"text":"abi-compat"}"""),
                    availableCapabilities = emptySet(),
                )
            assertTrue(
                admission is dev.rubentxu.pipeline.v2.application.durable.ExecutionPreparation.Ready,
                "the frozen plugin must be admitted by the current host, was: $admission",
            )
        }
    }

    @Test
    fun `old plugin JAR EXECUTES end-to-end through the canonical durable spine`() {
        withFixtureLoaded { registry, _ ->
            // The host references the frozen plugin ONLY by StepKey and encoded JSON — exactly as
            // production does. No fixture type is named at compile time.
            val spec: PipelineSpec = pipeline {
                stages {
                    stage("AbiCompatibility") {
                        registryStep(
                            stepKey = PluginStepId(FIXTURE_STEP_KEY),
                            encodedInput = EncodedStepValue("""{"text":"abi-compat"}"""),
                        )
                    }
                }
            }
            val compiled = DslCompiledPipelineCompiler.compile(
                spec = spec,
                sourcePath = "abi-compat.pipeline.kts",
                sourceContent = spec.toString(),
                pluginLockDigest = dev.rubentxu.pipeline.v2.domain.Digest("abi"),
            )

            val runId = RunId("abi-compatibility")
            val workDir = Files.createTempDirectory("abi-compat-")
            val clock = SystemClock()
            val eventStore = InMemoryEventStore()
            val coordinator = CanonicalDurableRunCoordinator(
                dispatcher = CanonicalNodeDispatcher(),
                journal = InMemoryOperationJournal(clock),
                cursorStore = InMemoryReplayCursorStore(clock),
                clock = clock,
                effectReplayPolicy = DefaultEffectReplayPolicy(),
                eventSink = eventStore,
                credentialScopePort = { _, _ ->
                    CredentialScopeOutcome.Unavailable(
                        CredentialScopeFailure.StoreUnavailable("abi-compat stub"),
                    )
                },
                controlDirRoot = workDir.resolve("control"),
                shOptions = ShOptions.EMPTY,
                stepRegistry = registry,
            )

            assertEquals(
                RunOutcome.Success,
                runBlocking { coordinator.run(compiled, runId) },
                "a plugin JAR built against an earlier SDK must still EXECUTE on the current host",
            )
        }
    }

    /**
     * Loads the frozen JAR in an isolated classloader, points the discovery adapter at it (the
     * production adapter resolves contributors from the thread context classloader), and provides a
     * registry containing core Steps plus the frozen plugin.
     */
    private fun <T> withFixtureLoaded(
        body: (registry: StepRegistry, loader: ClassLoader) -> T,
    ): T {
        val jar = fixtureJarPath()
        val loader = URLClassLoader(arrayOf(jar.toUri().toURL()), javaClass.classLoader)
        val previous = Thread.currentThread().contextClassLoader
        try {
            Thread.currentThread().contextClassLoader = loader
            // Model the host composition root: core Steps first, then discovered plugin
            // contributions. Every caller therefore receives a fully composed registry.
            val registry = CoreStepRegistryFactory.registry()
            ExternalStepPluginDiscovery.registerInto(registry)
            return body(registry, loader)
        } finally {
            Thread.currentThread().contextClassLoader = previous
            loader.close()
        }
    }
}
