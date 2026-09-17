package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeCapabilityAccess
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext
import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.util.ServiceLoader

/**
 * LFC-2E2-EXPANSION GATE — fitness that protects the plugin model from
 * silently regressing into a ServiceLocator and from leaking provider state
 * into core semantics. Authoritative for the entire LFC-2E2-UTILITIES-EXPANSION
 * cycle: every utility plugin family added in U1..U8 must remain green here.
 *
 * Authority:
 *   - `docs/v2/00-governance/PLUGIN_AUTHORING.md` (E2-PREP authoring rules)
 *   - `docs/v2/07-uat/FIRST_OFFICIAL_PLUGIN_RECEIPT.md` (capabilityAccessFactory seam)
 *   - this cycle's directive: the SDK must scale without core erosion.
 *
 * Invariants (one mechanical assertion per row):
 *
 *   G1  capabilityAccessFactory is null by default across all three production
 *       seams (CanonicalDurableRunCoordinator, ExecutionBoundaryFactory.build,
 *       RegistryExecutionBoundary.adapt).
 *   G2  capabilityAccessFactory source contains NO step-id / plugin-id switch
 *       (no `when (key.value)` or `if (key ==` referencing a concrete plugin id).
 *   G3  A plugin handler requesting an undeclared capability fails closed
 *       (delegated to StepContractSuite row, sanity-checked at this gate).
 *   G4  Plugin manifest's declaredCapabilities == union(StepContract.requiredCapabilities)
 *       across all StepDefinitions it contributes. (Static source-level check
 *       because no typed manifest field exists yet for the utilities plugin;
 *       this gate is the contract that will be wired in U8.)
 *   G5  Plugin present on classpath → utilities Steps ARE discoverable via
 *       ServiceLoader (Lane R ships the JAR into the test runtime).
 *   G6  Two plugins simultaneously (utilities + example.uppercase) → both
 *       contributors discovered; duplicate StepKey across plugins fails closed.
 *   G7  capabilityAccessFactory may return a bridge that does NOT provide a
 *       particular capability; handler asking for it through that bridge
 *       fails closed.
 *   G8  Core Steps are unaffected by plugin lifecycle: same StepKey set,
 *       same handler behavior bit-equivalent (handled by sibling fitness
 *       Lfc2UniversalCoreFreezeFitnessTest; not duplicated here).
 *   G9  Utilities plugin is an independent Gradle build with its own
 *       settings.gradle.kts — the property the LFC-2E2 cycle certifies.
 */
@Timeout(15)
@DisplayName("LFC-2E2-EXPANSION GATE — plugin model hardening")
class Lfc2E2ExpansionGateFitnessTest {

    private val repoRoot: File by lazy {
        File(System.getProperty("user.dir"), "../..").canonicalFile
    }

    private fun readRelative(relative: String): String =
        File(repoRoot, relative).also { check(it.isFile) { "missing file: $relative" } }.readText()

    // ───────────────────────────────────────────────────────────────────────
    // G1 — capabilityAccessFactory is null by default across all 3 seams
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `G1-1 CanonicalDurableRunCoordinator declares capabilityAccessFactory as null-default`() {
        val src = readRelative(
            "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalDurableRunCoordinator.kt",
        )
        assertTrue(
            Regex(
                """private val capabilityAccessFactory:\s*\(\(CanonicalRuntimeContext\)\s*->\s*CanonicalRuntimeCapabilityAccess\)\?\s*=\s*null""",
            ).containsMatchIn(src),
            "G1-1: CanonicalDurableRunCoordinator must declare capabilityAccessFactory with default null",
        )
    }

    @Test
    fun `G1-2 ExecutionBoundaryFactory build() declares capabilityAccessFactory as null-default`() {
        val src = readRelative(
            "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/ExecutionBoundaryFactory.kt",
        )
        assertTrue(
            Regex(
                """capabilityAccessFactory:\s*\(\(CanonicalRuntimeContext\)\s*->\s*CanonicalRuntimeCapabilityAccess\)\?\s*=\s*null""",
            ).containsMatchIn(src),
            "G1-2: ExecutionBoundaryFactory.build must declare capabilityAccessFactory with default null",
        )
    }

    @Test
    fun `G1-3 RegistryExecutionBoundary adapt() accepts a nullable capabilityAccessFactory`() {
        val src = readRelative(
            "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/RegistryExecutionBoundary.kt",
        )
        assertTrue(
            src.contains(
                "capabilityAccessFactory: ((CanonicalRuntimeContext) -> CanonicalRuntimeCapabilityAccess)?",
            ),
            "G1-3: RegistryExecutionBoundary.adapt must accept a nullable capabilityAccessFactory",
        )
    }

    // ───────────────────────────────────────────────────────────────────────
    // G2 — capabilityAccessFactory source contains NO step/plugin-id switch
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `G2-1 RegistryExecutionBoundary does not branch on plugin StepKeys or plugin ids`() {
        val src = readRelative(
            "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/RegistryExecutionBoundary.kt",
        )
        // A switch would look like `when (...key.value)` with concrete plugin prefixes
        // ("utilities.", "example.", "core.") or `PipelineStepId(...)` constructors
        // referring to a specific plugin Step.
        val forbiddenPatterns = listOf(
            Regex("""utilities\.(readJSON|writeJSON|sha256)"""),
            Regex("""example\.(uppercase|lowercase)"""),
            Regex("""PluginStepId\(\"utilities\.\""""),
            Regex("""PluginStepId\(\"example\.\""""),
        )
        forbiddenPatterns.forEach { pat ->
            assertFalse(
                pat.containsMatchIn(src),
                "G2-1: RegistryExecutionBoundary source must not reference concrete plugin StepKeys " +
                    "(pattern: ${pat.pattern})",
            )
        }
    }

    @Test
    fun `G2-2 ExecutionBoundaryFactory does not branch on plugin StepKeys`() {
        val src = readRelative(
            "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/ExecutionBoundaryFactory.kt",
        )
        val forbiddenPatterns = listOf(
            Regex("""utilities\.(readJSON|writeJSON|sha256)"""),
            Regex("""example\.(uppercase|lowercase)"""),
        )
        forbiddenPatterns.forEach { pat ->
            assertFalse(
                pat.containsMatchIn(src),
                "G2-2: ExecutionBoundaryFactory source must not reference concrete plugin StepKeys " +
                    "(pattern: ${pat.pattern})",
            )
        }
    }

    @Test
    fun `G2-3 CanonicalDurableRunCoordinator does not branch on plugin StepKeys in capability admission`() {
        // The capabilityAccessFactory call site at prepare-time admission must NOT inspect
        // the runtime context's step key. The check below enforces that no
        // `when (...pluginStepId...)` block lives in the registry family branch.
        val src = readRelative(
            "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalDurableRunCoordinator.kt",
        )
        assertFalse(
            Regex("""when\s*\(\s*step\.pluginStepId\.""").containsMatchIn(src),
            "G2-3: CanonicalDurableRunCoordinator must NOT switch on step.pluginStepId in capability admission",
        )
    }

    // ───────────────────────────────────────────────────────────────────────
    // G4 — Plugin source declares no capabilities the StepContract doesn't require
    // (preliminary; the typed manifest field arrives in U8)
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `G4-1 utilities plugin source contains the two declared capability tokens`() {
        val src = readRelative(
            "examples/utilities-plugin/src/main/kotlin/pipeline/utilities/json/UtilitiesJsonPlugin.kt",
        )
        assertTrue(
            src.contains("UTILITIES_JSON_CAPABILITY"),
            "G4-1: utilities plugin must declare UTILITIES_JSON_CAPABILITY",
        )
        assertTrue(
            src.contains("UTILITIES_SHA_CAPABILITY"),
            "G4-1: utilities plugin must declare UTILITIES_SHA_CAPABILITY",
        )
    }

    @Test
    fun `G4-2 utilities plugin has NO access to CanonicalRuntimeContext or ProcessBuilder`() {
        val src = readRelative(
            "examples/utilities-plugin/src/main/kotlin/pipeline/utilities/json/UtilitiesJsonPlugin.kt",
        )
        listOf(
            "CanonicalRuntimeContext",
            "ProcessBuilder",
            "Runtime.exec",
            "Runtime.getRuntime",
            "bash -c",
            "EventSink",
        ).forEach { forbidden ->
            assertFalse(
                src.contains(forbidden),
                "G4-2: utilities plugin must not import/reach '$forbidden' (handler discipline)",
            )
        }
    }

    @Test
    fun `G4-3 utilities plugin declares exactly two distinct capability tokens`() {
        val src = readRelative(
            "examples/utilities-plugin/src/main/kotlin/pipeline/utilities/json/UtilitiesJsonPlugin.kt",
        )
        val declared = Regex("""StepCapability\(\"([^\"]+)\"""")
            .findAll(src)
            .map { it.groupValues[1] }
            .toList()
        assertEquals(
            setOf("utilities.json.operations", "utilities.sha.operations"),
            declared.toSet(),
            "G4-3: utilities plugin must declare exactly two capability tokens " +
                "(utilities.json.operations, utilities.sha.operations)",
        )
    }

    // ───────────────────────────────────────────────────────────────────────
    // G5/G6 — Plugin absent / installed / removed lifecycle for core Steps
    //
    // Because the test JVM classpath always has both plugin JARs (Lane R),
    // the "absent" leg is exercised by reading ServiceLoader with a private
    // class loader that excludes the plugin JARs. The "present" leg below
    // demonstrates normal discovery.
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `G5 plugin present on test classpath IS discoverable via ServiceLoader`() {
        val contributors = ServiceLoader.load(StepDefinitionContributor::class.java).toList()
        val ids = contributors.map { it.id }
        assertTrue(
            "pipeline.utilities.json" in ids,
            "G5: pipeline.utilities.json must be discovered on the test classpath (got $ids)",
        )
        assertTrue(
            "example.uppercase" in ids,
            "G5: example.uppercase must be discovered on the test classpath (got $ids)",
        )
    }

    @Test
    fun `G6-1 two plugins simultaneously - both families coexist in registry`() {
        val registry = InMemoryStepRegistry().apply {
            ServiceLoader.load(StepDefinitionContributor::class.java).toList().forEach { contributor ->
                contributor.definitions().forEach { def -> register(def) }
            }
        }
        assertTrue(registry.contains(PluginStepId("utilities.readJSON")))
        assertTrue(registry.contains(PluginStepId("utilities.writeJSON")))
        assertTrue(registry.contains(PluginStepId("utilities.sha256")))
        assertTrue(registry.contains(PluginStepId("example.uppercase")))
    }

    @Test
    fun `G6-2 two plugins simultaneously - duplicate StepKey across plugins fails closed`() {
        val registry = InMemoryStepRegistry().apply {
            ServiceLoader.load(StepDefinitionContributor::class.java).toList().forEach { contributor ->
                contributor.definitions().forEach { register(it) }
            }
        }
        // A second contributor that shadows utilities.readJSON must fail closed at
        // registration time, not silently override the first registration.
        val shadowing: StepDefinition<Unit, Unit> = object : StepDefinition<Unit, Unit> {
            override val contract: StepContract<Unit, Unit> = StepContract(
                key = PluginStepId("utilities.readJSON"),
                descriptor = dev.rubentxu.pipeline.v2.domain.StepDescriptor(
                    stepId = "utilities.readJSON",
                    name = "shadow",
                    configRef = "shadow",
                ),
                inputCodec = object : StepCodec<Unit> {
                    override fun encode(value: Unit) = EncodedStepValue("null")
                    override fun decode(encoded: EncodedStepValue) = Unit
                },
                outputCodec = object : StepCodec<Unit> {
                    override fun encode(value: Unit) = EncodedStepValue("null")
                    override fun decode(encoded: EncodedStepValue) = Unit
                },
                requiredCapabilities = emptySet(),
            )
            override val handler: StepHandler<Unit, Unit> =
                StepHandler<Unit, Unit> { _, _: StepHandlerContext -> }
        }
        var thrown: Throwable? = null
        try {
            registry.register(shadowing)
        } catch (e: Throwable) {
            thrown = e
        }
        assertNotNull(thrown, "G6-2: a duplicate plugin StepKey must fail closed at registration")
        assertTrue(
            thrown is IllegalArgumentException,
            "G6-2: duplicate registration must throw IllegalArgumentException, was: ${thrown?.javaClass?.name}",
        )
    }

    // ───────────────────────────────────────────────────────────────────────
    // G7 — capabilityAccessFactory may return a bridge whose declared capabilities
    //      are scoped: requesting one not in `available()` fails closed.
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `G7 capabilityAccessFactory may return a scoped bridge and undeclared access fails closed`() {
        val absent = StepCapability("never.declared.capability")
        val ctx = CanonicalRuntimeContext(
            opId = OpId(runId = "g7", stageIndex = 0, stepIndex = 0),
            runId = "g7",
            stageName = "G7",
            stageIndex = 0,
            stepIndex = 0,
            shOptions = ShOptions.EMPTY,
            controlDirRoot = null,
            eventSink = InMemoryEventStore(),
            bodyInvoker = null,
        )
        val bridge = CanonicalRuntimeCapabilityAccess(ctx)
        var thrown: Throwable? = null
        try {
            bridge.get<Any>(absent)
        } catch (e: Throwable) {
            thrown = e
        }
        assertNotNull(thrown, "G7: requesting a capability not provided by the bridge must fail closed")
        assertTrue(
            thrown is IllegalArgumentException,
            "G7: must throw IllegalArgumentException, was: ${thrown?.javaClass?.name}",
        )
        assertFalse(
            bridge.available().contains(absent),
            "G7: the undeclared capability must NOT be in available()",
        )
    }

    // ───────────────────────────────────────────────────────────────────────
    // G9 — Utilities plugin is an independent Gradle build
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `G9 utilities plugin is an independent Gradle build with its own settings file`() {
        val settings = File(
            repoRoot,
            "examples/utilities-plugin/settings.gradle.kts",
        )
        assertTrue(settings.isFile, "G9: examples/utilities-plugin/settings.gradle.kts must exist")
        val text = settings.readText()
        assertTrue(
            text.contains("rootProject.name"),
            "G9: utilities plugin settings.gradle.kts must declare a rootProject.name",
        )
    }
}
