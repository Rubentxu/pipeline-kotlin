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
import dev.rubentxu.pipeline.v2.events.durable.InMemoryEventStore
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
 *
 * S2-D extends the contract: an admitted `Evaluate` directive is DECODED in the
 * BEFORE_STAGE seam before the stage starts, so malformed arguments are a typed
 * USER denial (`DirectiveAdmitted` -> `DirectiveDenied`, zero `StageStarted`) —
 * never the S1-EF characterisation E3 outcome ("garbage args -> admitted +
 * success"). E3 was an explicit frontier of S1-EF, closed BY DESIGN in S2-D;
 * the new row below is the re-pin of that characterisation to the new contract
 * (mirrors: the `directives` row of DSL_SURFACE_MANIFEST.md and
 * S2D_INSTALLED_DIRECTIVE_UAT_RECEIPT.md).
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
     * S2-D (R4): the external plugin JAR must be consumed AS SHIPPED, against the
     * current core, without being rebuilt. That is the whole point of the
     * compatibility claim, so it is asserted rather than assumed: if a build ever
     * silently regenerates the plugin, this pin turns "no rebuild" into a failing
     * test instead of an unverifiable sentence in a receipt.
     *
     * ## Re-certified 2026-10-07, SHA c424a9b2 (S6-COMPOSITION)
     *
     * The value moved from `3d244dea…` to `c424a9b2…`, and this time the plugin's SOURCE
     * **did** change, so the earlier justification ("source unchanged since b6e1b28b") does not
     * apply and is not reused. What changed, and why it was unavoidable:
     *
     *  - S6-COMPOSITION broadened `BundledPluginClasspathPlan`'s discriminator from
     *    `StepDefinitionContributor` alone to all four contributor SPIs. This plugin contributes
     *    ONLY a Directive, so before that change it was never admitted — it reached the run
     *    instantiated and composed, having declared nothing. That was measured, not suspected.
     *  - Once discovered, admission refuses any artifact without
     *    `META-INF/pipelinek/plugin-manifest.json`. `LockPluginDeclaration` plus the
     *    `computeDirectiveRelease` / `emitDirectiveManifest` tasks therefore became mandatory,
     *    not optional: the alternative was leaving a known hole in the admission chain open.
     *  - The new bytes are REPRODUCIBLE, measured by building twice with `--rerun-tasks` and
     *    comparing digests: `d0a80b9b87fc8cd741035bc68dbd89a5af90f3f7ded33ca7a9dc394164f89407`
     *    both times. A pin that flapped per run could not be compared with a pin that moved once.
     *    The previous pin (`283f89d7…`, set on `6e8e86bd`) was reproducible TOO, and the digest
     *    moved once because the SDK jar that the plugin compiles against moved once: between
     *    `6e8e86bd` and `a61b4872` four SDK commits landed (`a040c113 refactor(digest)`,
     *    `4d4075d5 feat(pipeline-domain): one SHA-256 utility`, `eaa9bc67 chore(api): registrar
     *    la superficie publica`, `7835ea66 feat(output): el transcript...`), and each legitimately
     *    changes the bytes the plugin links against. Re-certifying with the measured value keeps
     *    the test honest about what THIS revision contains, instead of carrying a constant for a
     *    jar that is no longer the artifact under test.
     *
     * ## Re-certified 2026-10-10, SHA 283f89d7 (B1 / v0.48.0-rc2)
     *
     * Pin moved from `d0a80b9b…` to `283f89d7…` after three SDK changes in B1:
     *
     *  - `5179ff70 fix(application): escritura atomica en RunIdDirectory.record` (RUN-01).
     *    Adds `StandardCopyOption` import and a temp-file + atomic-move dance; SDK bytes move.
     *  - `486ba4a4 fix(application): contencion en WorkspaceResolver.resolveArchiveDir` (PATH-01).
     *    Adds the `RUN_ID_SEGMENT` companion regex and a containment require-block; SDK bytes
     *    move.
     *  - `f53bfdff fix(build): direccionamiento Kover por Gradle path` (COV-01). Build-script only;
     *    does NOT affect the SDK bytes the plugin compiles against, but is recorded for
     *    completeness.
     *
     * The new JAR is REPRODUCIBLE: two consecutive `./gradlew :buildExternalDirectivePlugin
     * --rerun-tasks --console=plain --no-daemon` invocations produced `283f89d7…` both times. The
     * previous pin (`d0a80b9b…`) was reproducible TOO; the digest moved once because the SDK bytes
     * moved once. Source of the plugin is unchanged in this cycle.
     *  - The compatibility claim is re-verified on EVERY run by the seven sibling rows in this
     *    class, which load this jar in a classloader, resolve `LockContributor` through
     *    `ServiceLoader` and execute a real pipeline with it. Measured on this build: 7 of 7
     *    green, including `real discovery - ServiceLoader finds LockContributor in the real JAR`
     *    and `with plugin - stage admits, emits DirectiveAdmitted, and body runs`.
     *
     * What this pin cannot do, and never could: prove compatibility. It proves the jar is the one
     * this revision certified. Compatibility is the other seven rows. Treating one number as both
     * is how a stale toolchain quietly becomes a permanent exemption.
     */
    @Test
    fun `plugin jar is the certified build and was not rebuilt for this core`() {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(pluginJar))
            .joinToString("") { "%02x".format(it) }

        assertEquals(
            "283f89d7aae74580d0f57430d8f6ed7a36f0b9428ed48ee515b49136a78c34a8",
            digest,
            "external directive plugin JAR drifted from the certified bytes; a rebuild " +
                "invalidates the S2-D compatibility claim — restore the certified JAR instead. " +
                "If the plugin legitimately changed, re-certify with evidence, do not just " +
                "update this constant.",
        )
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

            // TRANSITION, stated here because it must never be a silent rewrite: this used to be
            // assertEquals(listOf("example.lock.LockContributor"), contributed). It was an INVENTORY
            // assertion wearing the clothes of a DISCOVERY assertion — it claimed "the lock
            // contributor is found" by way of "lock is the only contributor that exists".
            //
            // BLOCK 1-I gave the uppercase plugin a directive, which is exactly the kind of change
            // the suite is meant to be able to absorb, and the assertion failed on the added
            // contributor rather than on the one it names. The claim it was making did not change.
            assertTrue(
                contributed.contains("example.lock.LockContributor"),
                "discovery must find the plugin contributor through ServiceLoader; discovered " +
                    "$contributed. If lock is genuinely absent that is a real defect — but a second " +
                    "external directive plugin is not one, so this asserts membership, not inventory.",
            )
            return builder.build()
        } finally {
            Thread.currentThread().contextClassLoader = previous
        }
    }

    /** Host with directive infrastructure composed but ZERO plugin contributions. */
    private fun emptyRegistry(): DirectiveRegistry = DirectiveRegistry.Builder().build()

    private fun pipelineDeclaringLock(encodedArgs: String = """{"resource":"prod-db"}"""): CompiledPipeline = CompiledPipeline(
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
                directives = listOf(StageDirective("acme.lock", encodedArgs)),
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

    // ---- Row 8 (S2-D): WITH plugin — malformed args deny fail-closed -------------
    //
    // Re-pin of the S1-EF characterisation E3. That receipt declared "garbage
    // args -> admitted + success" as an explicit frontier; S2-D closes it BY
    // DESIGN: the BEFORE_STAGE seam decodes every admitted Gate|Evaluate
    // directive with its own codec BEFORE the stage starts, so a malformed
    // Evaluate is the DIRECTIVE'S CONTRACT failing (a typed USER denial), not a
    // veto and never a silent run. Observation order is law (D4/S1-C): the
    // admission was correct (key match), so it is observed, THEN denied.

    @Test
    fun `with plugin - malformed evaluate args deny fail-closed before the stage starts`() {
        val (coordinator, events) = wired(registryFrom(pluginLoader()))
        val outcome = runBlocking { coordinator.run(pipelineDeclaringLock("{not-json"), RunId("s1d-malformed")) }

        assertTrue(outcome is RunOutcome.Failure, "malformed args fail closed, got $outcome")
        val failure = (outcome as RunOutcome.Failure).failure
        assertEquals(dev.rubentxu.pipeline.v2.domain.FailureKind.USER, failure.kind)
        assertTrue(
            failure.message.contains("acme.lock") && failure.message.contains("could not be decoded"),
            "the diagnostic names the key and the decode failure: ${failure.message}",
        )

        val stream = events.eventsFor("s1d-malformed").toList()
        val admitted = stream.filterIsInstance<DirectiveAdmitted>().single()
        assertEquals("acme.lock", admitted.directiveKey)
        assertEquals("BEFORE_STAGE", admitted.phase)
        assertEquals("evaluate", admitted.policy)
        val denied = stream.filterIsInstance<DirectiveDenied>().single()
        assertTrue(
            denied.reason.contains("acme.lock") && denied.reason.contains("could not be decoded"),
            "the denial names the key and the decode failure: ${denied.reason}",
        )
        assertTrue(
            stream.indexOf(admitted) < stream.indexOf(denied),
            "DirectiveAdmitted must precede DirectiveDenied",
        )
        assertFalse(
            stream.any { it is dev.rubentxu.pipeline.v2.events.StageStarted },
            "fail-closed: the stage never starts",
        )
    }

    private fun loader(): URLClassLoader = pluginLoader()
}
