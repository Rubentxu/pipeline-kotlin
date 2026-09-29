package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator
import dev.rubentxu.pipeline.v2.application.durable.CanonicalNodeDispatcher
import dev.rubentxu.pipeline.v2.application.support.CoordinatorFixture
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.DefinitionId
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.SourceDescriptor
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StageDirective
import dev.rubentxu.pipeline.v2.domain.StageId
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveRegistry
import dev.rubentxu.pipeline.v2.events.DirectiveAdmitted
import dev.rubentxu.pipeline.v2.events.DirectiveDenied
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import example.lock.LockDirectiveDefinition
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * S1-D — external directive plugin contract suite (the directive analogue of
 * UppercaseStepContractSuiteTest).
 *
 * Proves the directive kernel is OPEN BY KEY from outside the build:
 * `acme.lock` comes from the real `example-directive-plugin-0.1.0.jar`, built
 * by `:buildExternalDirectivePlugin` from THIS revision's published SDK, and
 * is discovered through the REAL ServiceLoader mechanism under an isolated
 * plugin classloader — no manual registration of the plugin definition.
 *
 * Isolation pair (AC-5): the same declared stage
 *   - WITHOUT the plugin loader: DirectiveDenied + USER failure, zero effects;
 *   - WITH the plugin loader:   DirectiveAdmitted + Success + body ran.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class DirectivePluginContractSuiteTest {

    private val pluginJar: Path = run {
        val relative = "examples/example-directive-plugin/build/libs/example-directive-plugin-0.1.0.jar"
        // Gradle's test working directory is the MODULE dir (v2/pipeline-application);
        // examples/ lives at the REPO ROOT, two levels up.
        val candidates = listOf(
            Path.of("../../$relative"), // CWD = v2/pipeline-application (Gradle default)
            Path.of("../$relative"),    // CWD = v2
            Path.of(relative),          // CWD = repo root
        )
        candidates.firstOrNull { Files.exists(it) }
            ?: error("plugin JAR not found (tried: $candidates); build it first: v2/gradlew -p v2 :buildExternalDirectivePlugin")
    }

    /**
     * Plugin classloader mirroring production `pluginClassLoaderFor`
     * (MainRuntimeSupport): URLs = plugin JARs, parent = the host (context)
     * loader. The plugin JAR is NEVER self-sufficient: it links against the
     * host SDK at runtime (`compileOnly` at build time) — the platform-loader
     * parent would leave `DirectiveContributor` unresolvable, which is exactly
     * the host-linking contract working as designed.
     */
    private fun pluginLoader(): URLClassLoader = URLClassLoader(
        arrayOf(pluginJar.toUri().toURL()),
        Thread.currentThread().contextClassLoader,
    )

    /** Discovery against a specific loader (mirrors CompositionRoot's TCCL flow). */
    private fun registryFrom(loader: ClassLoader): DirectiveRegistry {
        val previous = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = loader
        try {
            val builder = DirectiveRegistry.Builder()
            val contributed = ExternalDirectivePluginDiscovery.registerInto(builder)
            assertEquals(
                listOf("example.lock.LockContributor"),
                contributed,
                "discovery must find the plugin contributor through ServiceLoader",
            )
            return builder.build()
        } finally {
            Thread.currentThread().contextClassLoader = previous
        }
    }

    /** Host with directive infrastructure composed but ZERO plugin contributions. */
    private fun emptyRegistry(): DirectiveRegistry = DirectiveRegistry.Builder().build()

    private fun pipelineDeclaringLock(): CompiledPipeline = CompiledPipeline(
        id = DefinitionId("s1d-plugin"),
        source = SourceDescriptor("S1D.pipeline.kts", Digest("s1d")),
        pluginLockDigest = Digest("s1d-lock"),
        stages = listOf(
            StageNode(
                id = StageId("deploy"),
                name = "deploy",
                body = StageBody.Steps(
                    listOf(
                        OpaqueStepNode(
                            id = StepId("deploy/sh"),
                            pluginStepId = dev.rubentxu.pipeline.v2.domain.PluginStepId("core.sh"),
                            payload = VersionedStepPayload(
                                "dsl-v1",
                                """{"kind":"sh","command":"echo locked-stage-ran","isScriptBlock":false,"returnStdout":false}""",
                            ),
                        ),
                    ),
                ),
                directives = listOf(StageDirective("acme.lock", """{"resource":"prod-db"}""")),
            ),
        ),
    )

    private data class Wired(val coordinator: CanonicalDurableRunCoordinator, val events: InMemoryEventStore)

    private fun wired(registry: DirectiveRegistry?): Wired {
        val clock = SystemClock()
        val controlRoot = Files.createTempDirectory("s1d-control")
        val events = InMemoryEventStore()
        val coordinator = CanonicalDurableRunCoordinator(
            dispatcher = CanonicalNodeDispatcher(),
            journal = InMemoryOperationJournal(clock),
            cursorStore = InMemoryReplayCursorStore(clock),
            clock = clock,
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            eventSink = events,
            credentialScopePort = CoordinatorFixture.noOpCredentialScopePort(),
            controlDirRoot = controlRoot,
            stepRegistry = CoreStepRegistryFactory.registry(),
            directiveRegistry = registry,
        )
        return Wired(coordinator, events)
    }

    // ---- Row 1: identity + duplicate fail-closed --------------------------------

    @Test
    fun `identity - plugin key is acme dot lock and duplicates fail closed`() {
        assertEquals("acme.lock", LockDirectiveDefinition.KEY.value)

        loader().use { loader ->
            val registry = registryFrom(loader)
            // Re-contributing the same key through a second contributor must
            // fail closed (never shadow).
            val duplicate = DirectiveRegistry.Builder()
                .addAll(registry.keys().map { registry.find(it)!! })
            val second = example.lock.LockContributor()
            assertThrows(DirectiveRegistry.DuplicateKeyException::class.java) {
                duplicate.addContributors(second)
            }
        }
    }

    // ---- Row 2: decode round-trip ------------------------------------------------

    @Test
    fun `decode round-trip - LockInput JSON to typed and back`() {
        val input = example.lock.LockInput(resource = "prod-db", timeoutSeconds = 30)
        val json = kotlinx.serialization.json.Json.encodeToString(
            example.lock.LockInput.serializer(),
            input,
        )
        val decoded = LockDirectiveDefinition.decode(json)
        assertTrue(decoded is dev.rubentxu.pipeline.v2.domain.directive.DirectiveDecodeResult.Decoded<*>)
        assertEquals(input, (decoded as dev.rubentxu.pipeline.v2.domain.directive.DirectiveDecodeResult.Decoded<*>).input)
    }

    // ---- Row 3: malformed decode is typed, not thrown ---------------------------

    @Test
    fun `malformed decode returns Malformed with reason`() {
        val decoded = LockDirectiveDefinition.decode("""{"resource": 42}""")
        assertTrue(
            decoded is dev.rubentxu.pipeline.v2.domain.directive.DirectiveDecodeResult.Malformed,
            "a shape-mismatched payload is a typed Malformed, got $decoded",
        )
        assertTrue(
            (decoded as dev.rubentxu.pipeline.v2.domain.directive.DirectiveDecodeResult.Malformed)
                .reason.contains("LockInput"),
        )
    }

    // ---- Row 4: real ServiceLoader discovery ------------------------------------

    @Test
    fun `real discovery - ServiceLoader finds LockContributor in the real JAR`() {
        val registry = registryFrom(pluginLoader())
        assertTrue(
            registry != null && registry.find(LockDirectiveDefinition.KEY) != null,
            "the real plugin JAR must contribute acme.lock through ServiceLoader",
        )
    }

    // ---- Rows 5+7: WITH plugin — admitted, observed, body runs -------------------

    @Test
    fun `with plugin - stage admits, emits DirectiveAdmitted, and body runs`(@org.junit.jupiter.api.io.TempDir tempDir: Path) {
        val marker = tempDir.resolve("effect.txt")
        val pipeline = pipelineDeclaringLock().let { base ->
            val stage = base.stages.single()
            val body = stage.body as StageBody.Steps
            base.copy(
                stages = listOf(
                    stage.copy(
                        body = StageBody.Steps(
                            body.steps.map {
                                (it as OpaqueStepNode).copy(
                                    payload = VersionedStepPayload(
                                        "dsl-v1",
                                        """{"kind":"sh","command":"echo locked-stage-ran > '${marker}'","isScriptBlock":false,"returnStdout":false}""",
                                    ),
                                )
                            },
                        ),
                    ),
                ),
            )
        }

        pluginLoader().use { loader ->
            val (coordinator, events) = wired(registryFrom(loader))
            val outcome = runBlocking { coordinator.run(pipeline, RunId("s1d-with")) }

            assertEquals(RunOutcome.Success, outcome, "an externally contributed key MUST admit")

            val admitted = events.eventsFor("s1d-with").filterIsInstance<DirectiveAdmitted>().toList()
            assertEquals(1, admitted.size)
            assertEquals("acme.lock", admitted.single().directiveKey)
            assertEquals("BEFORE_STAGE", admitted.single().phase)
            assertEquals("evaluate", admitted.single().policy)
            assertTrue(Files.exists(marker), "the stage body must run after admission")
        }
    }

    // ---- Rows 6+7: WITHOUT plugin — denied fail-closed, no effects ---------------

    @Test
    fun `without plugin - same pipeline denies fail-closed with DirectiveDenied`() {
        // Production shape after S1-D: CompositionRoot ALWAYS composes the
        // directive registry (TCCL); with no plugins installed it is EMPTY —
        // not null — so unknown keys deny fail-closed instead of being skipped
        // by the S1-B legacy path.
        val (coordinator, events) = wired(emptyRegistry())
        val outcome = runBlocking { coordinator.run(pipelineDeclaringLock(), RunId("s1d-without")) }

        assertTrue(outcome is RunOutcome.Failure, "unresolved external key denies: $outcome")
        val failure = (outcome as RunOutcome.Failure).failure
        assertEquals(dev.rubentxu.pipeline.v2.domain.FailureKind.USER, failure.kind)
        assertTrue(failure.message.contains("acme.lock"))

        val denied = events.eventsFor("s1d-without").filterIsInstance<DirectiveDenied>().toList()
        assertEquals(1, denied.size, "exactly one denial event: $denied")
        assertTrue(denied.single().reason.contains("acme.lock"))
        // No cross-emission (S1-C law): a denial never also admits.
        assertTrue(
            events.eventsFor("s1d-without").none { it is DirectiveAdmitted },
        )
        assertFalse(
            events.eventsFor("s1d-without").any { it is dev.rubentxu.pipeline.v2.events.StageStarted },
            "fail-closed: the stage never starts",
        )
    }

    private fun loader(): URLClassLoader = pluginLoader()
}
