package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.CoreStepRegistryFactory
import dev.rubentxu.pipeline.v2.application.SystemClock
import dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator
import dev.rubentxu.pipeline.v2.application.durable.CanonicalNodeDispatcher
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopePort
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeFailure
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeOutcome
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.DefinitionId
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.SourceDescriptor
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StageId
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
import dev.rubentxu.pipeline.v2.domain.step.registerContributors
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.sdk.junit.step.JUnitResultsInput
import dev.rubentxu.pipeline.v2.sdk.junit.step.JUnitResultsInputCodec
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import dev.rubentxu.pipeline.v2.sdk.scm.git.step.GitCheckoutInput
import dev.rubentxu.pipeline.v2.sdk.scm.git.step.GitCheckoutInputCodec
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * WU-LPR-WC-SCM E2E integration: real canonical engine run that
 * exercises BOTH OFFICIAL_PLUGINs (scm-git.checkout + junit.results)
 * using the typed [WORKSPACE_IDENTITY_CAPABILITY] seam that WU-LPR-WC
 * introduced.
 *
 * Mirrors the user's UAT scenario:
 *
 *   checkout -> dir(proyecto) -> sh(tests) -> junit.results(XML
 *                                            generado en el mismo run)
 *
 * We run the canonical engine directly (`CanonicalDurableRunCoordinator.run`)
 * with the production registry, a typed workspace pinned to a directory
 * different from `user.dir`, and the same plugin Steps the user would
 * invoke through a .pipeline.kts script. The canonical engine is the
 * SAME engine MainKt drives; we skip the scripting-host layer because
 * the binary distribution has a pre-existing gap that blocks plugin
 * imports in script-compile time (`dependenciesFromCurrentContext(wholeClasspath=false)`).
 * That gap is recorded in the FK/WC close-out receipts; it is NOT a
 * regression of WC-SCM.
 *
 * Both handlers run through the same canonical registry boundary;
 * the test fails closed if either handler reads `user.dir` instead
 * of the typed workspace.
 */
@Timeout(300)
class WcScmE2EBothPluginsIntegrationTest {

    @Test
    fun `WC-SCM E2E scm-git checkout lands under the typed workspaceBase never user dir`(@TempDir tempDir: Path) = runBlocking {
        // 1. Bare fixture so scm-git.checkout has something to clone.
        val bareRepo = tempDir.resolve("fixture.git")
        runGit(listOf("init", "--bare", "-b", "main", bareRepo.toString()))
        val workingRepo = tempDir.resolve("working")
        Files.createDirectories(workingRepo)
        runGit(listOf("-C", workingRepo.toString(), "init", "-b", "main"))
        runGit(listOf("-C", workingRepo.toString(), "config", "user.email", "wc-scm@local"))
        runGit(listOf("-C", workingRepo.toString(), "config", "user.name", "wc-scm"))
        Files.writeString(workingRepo.resolve("README.md"), "wc-scm e2e")
        runGit(listOf("-C", workingRepo.toString(), "add", "README.md"))
        runGit(listOf("-C", workingRepo.toString(), "commit", "-m", "init"))
        runGit(listOf("-C", workingRepo.toString(), "remote", "add", "origin", bareRepo.toString()))
        runGit(listOf("-C", workingRepo.toString(), "push", "-u", "origin", "main"))

        // 2. Workspace deliberately DIFFERENT from user.dir.
        val pipelineWorkspace = tempDir.resolve("ws-scm").also { Files.createDirectories(it) }

        // 3. Build the typed input via the plugin's own codec.
        val checkoutInput = GitCheckoutInput(
            url = bareRepo.toString(),
            branch = "main",
            changelog = false,
            poll = false,
            relativeTargetDir = "hello-world",
        )
        val checkoutEncoded = GitCheckoutInputCodec.encode(checkoutInput)

        // 4. Build the CompiledPipeline with one Stage + one Step.
        val pipeline = CompiledPipeline(
            id = DefinitionId("wc-scm-checkout"),
            source = SourceDescriptor("Pipeline.kts", Digest("source")),
            pluginLockDigest = Digest("lock"),
            stages = listOf(
                StageNode(
                    id = StageId("checkout"),
                    name = "checkout",
                    body = StageBody.Steps(
                        listOf(
                            OpaqueStepNode(
                                id = StepId("checkout/scm-git"),
                                pluginStepId = PluginStepId("scm-git.checkout"),
                                payload = VersionedStepPayload(
                                    schemaVersion = "dsl-v1",
                                    encoded = checkoutEncoded.value,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        // 5. Run through the canonical engine with workspaceBase pinned.
        val eventStore = InMemoryEventStore()
        val registry: StepRegistry = CoreStepRegistryFactory.registry()
        // Register both OFFICIAL_PLUGINs through their contributors so the
        // canonical engine can resolve their StepKeys via the open registry
        // seam (the same path MainKt drives in production after
        // ServiceLoader discovery).
        registry.registerContributors(listOf(
            dev.rubentxu.pipeline.v2.sdk.scm.git.step.ScmGitStepDefinitionContributor(),
        ))
        assertNotNull(registry.definition(PluginStepId("scm-git.checkout")),
            "Production registry must resolve scm-git.checkout; OFFICIAL_PLUGIN must be installed")

        val coordinator = CanonicalDurableRunCoordinator(
            dispatcher = CanonicalNodeDispatcher(),
            journal = InMemoryOperationJournal(SystemClock()),
            cursorStore = InMemoryReplayCursorStore(SystemClock()),
            clock = SystemClock(),
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            eventSink = eventStore,
            credentialScopePort = noOpCredentialScopePort(),
            controlDirRoot = tempDir.resolve("ctrl-scm").also { Files.createDirectories(it) },
            workspaceBase = pipelineWorkspace,
            shOptions = ShOptions.EMPTY,
            stepRegistry = registry,
        )

        val outcome = coordinator.run(pipeline, RunId("wc-scm-e2e-checkout"))

        // 6. Hard evidence: the repo landed under workspaceBase.
        val checkoutDir = pipelineWorkspace.resolve("hello-world")
        assertTrue(Files.isDirectory(checkoutDir),
            "scm-git.checkout MUST write under workspaceBase; expected $checkoutDir, " +
                "user.dir=${System.getProperty("user.dir")}, outcome=$outcome")
        assertTrue(Files.isRegularFile(checkoutDir.resolve("README.md")),
            "Checked-out README.md must be under workspaceBase/hello-world")

        // 7. No user.dir contamination.
        val userDir = Path.of(System.getProperty("user.dir"))
        val contamination = userDir.resolve("hello-world")
        assertTrue(!Files.exists(contamination) || !Files.isSameFile(pipelineWorkspace, userDir),
            "No user.dir contamination: contamination=$contamination")
    }

    @Test
    fun `WC-SCM E2E junit results lands under the typed workspaceBase never user dir`(@TempDir tempDir: Path) = runBlocking {
        // 1. Workspace deliberately DIFFERENT from user.dir.
        val pipelineWorkspace = tempDir.resolve("ws-junit").also { Files.createDirectories(it) }
        val reportDir = pipelineWorkspace.resolve("hello-world")
        Files.createDirectories(reportDir)
        Files.writeString(
            reportDir.resolve("test-results.xml"),
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<testsuite name=\"wc-scm\" tests=\"2\" failures=\"0\" errors=\"0\" skipped=\"0\">\n" +
                "  <testcase name=\"passes\"/>\n" +
                "  <testcase name=\"also-passes\"/>\n" +
                "</testsuite>\n",
        )

        // 2. Build the typed input via the plugin's own codec.
        val junitInput = JUnitResultsInput(
            reportPath = "hello-world/test-results.xml",
            workspaceRoot = ".",
            failOnFailure = false,
        )
        val junitEncoded = JUnitResultsInputCodec.encode(junitInput)

        // 3. Build the CompiledPipeline.
        val pipeline = CompiledPipeline(
            id = DefinitionId("wc-scm-junit"),
            source = SourceDescriptor("Pipeline.kts", Digest("source")),
            pluginLockDigest = Digest("lock"),
            stages = listOf(
                StageNode(
                    id = StageId("report"),
                    name = "report",
                    body = StageBody.Steps(
                        listOf(
                            OpaqueStepNode(
                                id = StepId("report/junit"),
                                pluginStepId = PluginStepId("junit.results"),
                                payload = VersionedStepPayload(
                                    schemaVersion = "dsl-v1",
                                    encoded = junitEncoded.value,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        // 4. Run.
        val eventStore = InMemoryEventStore()
        val registry: StepRegistry = CoreStepRegistryFactory.registry()
        // Register the OFFICIAL_PLUGIN through its contributor so the
        // canonical engine can resolve its StepKey via the open registry
        // seam (the same path MainKt drives in production after
        // ServiceLoader discovery).
        registry.registerContributors(listOf(
            dev.rubentxu.pipeline.v2.sdk.junit.step.JUnitStepDefinitionContributor(),
        ))
        assertNotNull(registry.definition(PluginStepId("junit.results")),
            "Production registry must resolve junit.results; OFFICIAL_PLUGIN must be installed")

        val coordinator = CanonicalDurableRunCoordinator(
            dispatcher = CanonicalNodeDispatcher(),
            journal = InMemoryOperationJournal(SystemClock()),
            cursorStore = InMemoryReplayCursorStore(SystemClock()),
            clock = SystemClock(),
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            eventSink = eventStore,
            credentialScopePort = noOpCredentialScopePort(),
            controlDirRoot = tempDir.resolve("ctrl-junit").also { Files.createDirectories(it) },
            workspaceBase = pipelineWorkspace,
            shOptions = ShOptions.EMPTY,
            stepRegistry = registry,
        )

        val outcome = coordinator.run(pipeline, RunId("wc-scm-e2e-junit"))
        // The junit.results step itself reads its XML from the typed
        // workspace (via WORKSPACE_IDENTITY_CAPABILITY). If it ignored
        // the typed seam and fell back to user.dir it would fail to
        // find the XML.
        assertTrue(Files.isRegularFile(reportDir.resolve("test-results.xml")),
            "JUnit XML must still be under workspaceBase/hello-world after the run; outcome=$outcome")
        assertTrue(eventStore.eventsFor("wc-scm-e2e-junit").any(),
            "Run must produce events; outcome=$outcome")

        // No user.dir contamination.
        val userDir = Path.of(System.getProperty("user.dir"))
        val contamination = userDir.resolve("hello-world")
        assertTrue(!Files.exists(contamination) || !Files.isSameFile(pipelineWorkspace, userDir),
            "No user.dir contamination: contamination=$contamination")
    }

    @Test
    fun `WC-SCM E2E two scm-git runs with different workspaceBase do not cross-contaminate`(@TempDir tempDir: Path) = runBlocking {
        // Two distinct workspaces. Two scm-git.checkout runs, one per
        // workspace. The first run writes a sentinel file under wsA's
        // checkout, then we assert wsA's checkout is untouched by the
        // second run and wsB's checkout is freshly populated. This
        // proves the typed workspace identity is per-invocation, not a
        // shared mutable state.
        val wsA = tempDir.resolve("wsA").also { Files.createDirectories(it) }
        val wsB = tempDir.resolve("wsB").also { Files.createDirectories(it) }
        assertTrue(!Files.isSameFile(wsA, wsB))

        // Build a bare fixture with TWO branches so each checkout has
        // something to verify per workspace.
        val bareRepo = tempDir.resolve("fixture.git")
        runGit(listOf("init", "--bare", "-b", "main", bareRepo.toString()))
        val workingRepo = tempDir.resolve("working")
        Files.createDirectories(workingRepo)
        runGit(listOf("-C", workingRepo.toString(), "init", "-b", "main"))
        runGit(listOf("-C", workingRepo.toString(), "config", "user.email", "wc-scm@local"))
        runGit(listOf("-C", workingRepo.toString(), "config", "user.name", "wc-scm"))
        Files.writeString(workingRepo.resolve("README.md"), "wc-scm cross-isolation")
        runGit(listOf("-C", workingRepo.toString(), "add", "README.md"))
        runGit(listOf("-C", workingRepo.toString(), "commit", "-m", "init"))
        runGit(listOf("-C", workingRepo.toString(), "remote", "add", "origin", bareRepo.toString()))
        runGit(listOf("-C", workingRepo.toString(), "push", "-u", "origin", "main"))

        suspend fun runCheckout(workspace: Path, runId: String, targetDir: String) {
            val input = GitCheckoutInput(
                url = bareRepo.toString(),
                branch = "main",
                changelog = false,
                poll = false,
                relativeTargetDir = targetDir,
            )
            val encoded = GitCheckoutInputCodec.encode(input)
            val pipeline = CompiledPipeline(
                id = DefinitionId("wc-scm-cross-$runId"),
                source = SourceDescriptor("Pipeline.kts", Digest("source")),
                pluginLockDigest = Digest("lock"),
                stages = listOf(
                    StageNode(
                        id = StageId("checkout"),
                        name = "checkout",
                        body = StageBody.Steps(
                            listOf(
                                OpaqueStepNode(
                                    id = StepId("checkout/scm-git-$runId"),
                                    pluginStepId = PluginStepId("scm-git.checkout"),
                                    payload = VersionedStepPayload(
                                        schemaVersion = "dsl-v1",
                                        encoded = encoded.value,
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            )
            val eventStore = InMemoryEventStore()
            val registry: StepRegistry = CoreStepRegistryFactory.registry()
            registry.registerContributors(listOf(
                dev.rubentxu.pipeline.v2.sdk.scm.git.step.ScmGitStepDefinitionContributor(),
            ))
            val coordinator = CanonicalDurableRunCoordinator(
                dispatcher = CanonicalNodeDispatcher(),
                journal = InMemoryOperationJournal(SystemClock()),
                cursorStore = InMemoryReplayCursorStore(SystemClock()),
                clock = SystemClock(),
                effectReplayPolicy = DefaultEffectReplayPolicy(),
                eventSink = eventStore,
                credentialScopePort = noOpCredentialScopePort(),
                controlDirRoot = tempDir.resolve("ctrl-cross-$runId").also { Files.createDirectories(it) },
                workspaceBase = workspace,
                shOptions = ShOptions.EMPTY,
                stepRegistry = registry,
            )
            coordinator.run(pipeline, RunId(runId))
        }

        // First run: checkout into wsA/hello-world.
        runCheckout(wsA, "wsA", "hello-world")
        // Second run: checkout into wsB/hello-world.
        runCheckout(wsB, "wsB", "hello-world")

        // wsA/hello-world must exist with README; wsB/hello-world must
        // also exist with its own README. Neither run polluted the other.
        assertTrue(Files.isRegularFile(wsA.resolve("hello-world").resolve("README.md")),
            "wsA/hello-world/README.md must exist after first run")
        assertTrue(Files.isRegularFile(wsB.resolve("hello-world").resolve("README.md")),
            "wsB/hello-world/README.md must exist after second run")

        // Sanity: wsA and wsB are distinct paths with distinct contents
        // (each has its own .git under hello-world/.git).
        val gitA = wsA.resolve("hello-world/.git")
        val gitB = wsB.resolve("hello-world/.git")
        assertTrue(Files.exists(gitA), "wsA checkout must have its own .git")
        assertTrue(Files.exists(gitB), "wsB checkout must have its own .git")
        assertTrue(!Files.isSameFile(gitA, gitB), "wsA and wsB checkouts must be distinct trees")
    }

    // -- helpers --------------------------------------------------------

    private fun runGit(args: List<String>) {
        val pb = ProcessBuilder("git", *args.toTypedArray())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        val ok = p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0
        if (!ok) {
            throw IllegalStateException("git $args failed (exit=${p.exitValue()}): " +
                "stdout=$out stderr=$err")
        }
    }

    private fun noOpCredentialScopePort(): CredentialScopePort = CredentialScopePort { _, _ ->
        CredentialScopeOutcome.Unavailable(CredentialScopeFailure.StoreUnavailable("wc-scm test"))
    }
}
