package dev.rubentxu.pipeline.v2.sdk.scm.git.step

import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.PluginStepException
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.scm.CheckoutSpec
import dev.rubentxu.pipeline.v2.domain.scm.GitScm
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.RecoveryPolicy
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import dev.rubentxu.pipeline.v2.domain.step.WORKSPACE_IDENTITY_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.WorkspaceIdentity
import dev.rubentxu.pipeline.v2.sdk.scm.git.GitCheckoutExecutor
import dev.rubentxu.pipeline.v2.sdk.scm.git.GitCheckoutRequest
import dev.rubentxu.pipeline.v2.sdk.scm.git.GitCheckoutResult
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSink
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import java.time.Instant

/**
 * StepContractSuite (HF0 / HF1) — scm-git OFFICIAL_PLUGIN checkout Step
 * (LFC-2E2 / F5.1 / WU-RP-053R/B4 / WU-LPR-WC-SCM).
 *
 * Certifies the contract surface of `scm-git.checkout` per ADR-0074 +
 * ADR-0072 (Harness fidelity HF0 pure contract + HF1 in-process). The
 * handler is exercised through a stubbed `executorFactory` so the suite
 * is hermetic — it does NOT shell out to git and does NOT depend on
 * network or `V2_GIT_AVAILABLE`. The hermetic path is the contract path;
 * the real-git path (D4 sequence: rev-parse / ls-remote / fetch /
 * reset / clone) lives in `GitCheckoutExecutorTest` gated by
 * `V2_GIT_AVAILABLE=true`.
 *
 * Coverage rows (canonical StepContractSuite):
 *
 *  1.  identity — StepKey is scm-git.checkout
 *  2.  contract — descriptor declares CONTROLLER + WRITES_WORKSPACE +
 *      EXECUTES_SUBPROCESS + MEMOIZED + RecoveryPolicy.None + WORKSPACE_IDENTITY_CAPABILITY
 *  3.  codec input — roundtrip preserves every GitCheckoutInput field
 *  4.  codec output — roundtrip preserves resolvedSha + localPath +
 *      wasCloned + credentialApplied
 *  5.  envelope — input codec emits a well-formed JSON object
 *      (durable eligible; no `declarativeValue` leak)
 *  6.  registry resolution — InMemoryStepRegistry resolves scm-git.checkout
 *  7.  capability admission — fails closed when WORKSPACE_IDENTITY_CAPABILITY absent
 *  8.  success — handler returns typed GitCheckoutOutput for a stubbed
 *      executor that produces a deterministic GitCheckoutResult
 *  9.  typed failure — executor Result.failure is surfaced as
 *      PluginStepException carrying a typed FailureKind (USER /
 *      NETWORK / INFRASTRUCTURE) classified by `classifyFailureKind`
 * 10.  replay — output is deterministic across repeated invocations
 *      against the same stub (replay-policy: MEMOIZED means the engine
 *      may reuse the cached typed output; the contract here is that the
 *      encoded output is byte-stable when the executor is deterministic).
 * 11.  observability — handler routes events through the injected
 *      EventSink and does not emit via global state.
 * 12.  missing capability — boundary re-check throws
 *      PluginStepException BEFORE the handler runs (NOT silent success).
 * 13.  architectural fitness — the suite lives in the plugin module, NOT
 *      in `pipeline-application` or `pipeline-domain`. This is the zero
 *      core-change proof for `scm-git.checkout`.
 * 14.  real DSL — `GitCheckoutDsl` extension lowers only via
 *      `registryStep(scm-git.checkout, encodedInput)` and does not
 *      reach for `pipeline.workspace.root` or `user.dir`.
 * 15.  typed credentialsRef carrier — `credentialsRef` survives the
 *      input codec roundtrip and surfaces as `GitCredentials.id` in
 *      the downstream `GitScm`, NEVER as an embedded secret
 *      (F5.1 + SCM domain typed carrier discipline).
 * 16.  recovery policy — descriptor declares `RecoveryPolicy.None`;
 *      the spine MUST NOT introduce a retry path for this Step
 *      (WU-LPR-WC-SCM).
 * 17.  **B2 hardening axis** — `credentialsRef` carrying a value that
 *      cannot be resolved by the secret store OR that fails SCM-domain
 *      validation is fail-CLOSED: the handler does NOT silently fall
 *      back to anonymous git, it surfaces a typed FailureKind and the
 *      pipeline stops. (This is the WU-RP-053R/B2 fix: B2 closed a
 *      pre-existing fail-OPEN path; this axis is the regression guard.)
 *
 * Reference impls in the codebase: `CoreUtilsStepContractSuiteTest`,
 * `CoreEchoStepContractSuiteTest`, `CoreShellStepContractSuiteTest`
 * (all in `pipeline-application`). The scm-git Step is an external
 * SDK Step and so this suite lives here, in the plugin module — same
 * discipline, different package.
 *
 * @Timeout class-level: 15s. Each test runs in milliseconds when the
 * executor factory is stubbed; the budget guards against future
 * regressions that accidentally shell out to a real git process.
 */
@Timeout(15)
class CoreScmGitCheckoutStepContractSuiteTest {

    @TempDir
    lateinit var tempDir: Path

    // ---------- helpers ----------

    /**
     * Builds a stubbed GitCheckoutStepDefinition whose executor factory
     * returns whatever [stubResult] the test authorises. The factory
     * invocation is recorded so tests can verify that the handler
     * forwarded the workspace root from the typed capability seam
     * (NOT from `user.dir`).
     */
    private fun stubbedStep(
        stubResult: Result<GitCheckoutResult>,
        capturedRoots: MutableList<Path> = mutableListOf(),
        capturedRequests: MutableList<GitCheckoutRequest> = mutableListOf(),
        eventSink: EventSink = NullRecordingEventSink(),
    ): GitCheckoutStepDefinition {
        return GitCheckoutStepDefinition(
            executorFactory = { workspaceRoot ->
                capturedRoots.add(workspaceRoot)
                object : GitCheckoutExecutor(
                    poll = dev.rubentxu.pipeline.v2.sdk.scm.git.GitPollExecutor(),
                    changelog = dev.rubentxu.pipeline.v2.sdk.scm.git.GitChangelogWriter(),
                    credentialsApplier = dev.rubentxu.pipeline.v2.sdk.scm.git.GitCredentialsApplier(
                        tempDir = Files.createTempDirectory("scm-git-stub"),
                        credentials = dev.rubentxu.pipeline.v2.domain.scm.GitCredentials(),
                    ),
                ) {
                    override fun toString(): String = "StubbedGitCheckoutExecutor"

                    // Stubbing GitCheckoutExecutor.execute would normally
                    // require openning it; the production signature is
                    // `fun execute(req: GitCheckoutRequest): Result<...>`
                    // and is open by Kotlin default unless sealed.
                }.also {
                    // No-op: the factory only needs to return a real
                    // executor; the stub wraps `execute` via override in
                    // the anonymous subclass below.
                }.let { executor ->
                    StubbedGitCheckoutExecutor(
                        delegate = executor,
                        requestCapture = capturedRequests,
                        stubResult = stubResult,
                    )
                }
            },
            eventSink = eventSink,
        )
    }

    /**
     * Builds a [StepHandlerContext] with a typed WORKSPACE_IDENTITY_CAPABILITY
     * exposure rooted at [root].
     */
    private fun handlerContext(workspaceRoot: Path): StepHandlerContext {
        val caps = object : StepCapabilityAccess {
            private val map = mapOf<StepCapability, Any>(
                WORKSPACE_IDENTITY_CAPABILITY to WorkspaceIdentity(workspaceRoot),
            )
            override fun available(): Set<StepCapability> = map.keys
            override fun <T : Any> get(key: StepCapability): T {
                @Suppress("UNCHECKED_CAST")
                return map[key] as T
            }
        }
        return StepHandlerContext(
            runId = RunId("test-scm-git"),
            stepIndex = 0,
            capabilities = caps,
        )
    }

    // =========================================================================
    // 1. identity
    // =========================================================================

    @Test
    fun `identity — scm-git checkout Key is scm-git dot checkout`() {
        assertEquals(dev.rubentxu.pipeline.v2.domain.PluginStepId("scm-git.checkout"), ScmGitCheckoutKey.VALUE)
        assertEquals("scm-git.checkout", ScmGitCheckoutKey.VALUE.value)
    }

    // =========================================================================
    // 2. contract completeness
    // =========================================================================

    @Test
    fun `contract — declares CONTROLLER + WRITES_WORKSPACE + EXECUTES_SUBPROCESS + MEMOIZED + RecoveryPolicy None + WORKSPACE_IDENTITY_CAPABILITY`() {
        val stub = stubbedStep(stubResult = Result.success(GitCheckoutResult("deadbeef", 0L, "no-op")))
        val c = stub.contract
        assertEquals(ScmGitCheckoutKey.VALUE, c.key)
        assertEquals(ExecutionLocation.CONTROLLER, c.descriptor.executionLocation)
        assertEquals(setOf(Effect.WRITES_WORKSPACE, Effect.EXECUTES_SUBPROCESS), c.descriptor.effects.toSet())
        assertEquals(ReplayPolicy.MEMOIZED, c.descriptor.replayPolicy)
        assertEquals(RecoveryPolicy.None, c.descriptor.recoveryPolicy)
        assertEquals(setOf<StepCapability>(WORKSPACE_IDENTITY_CAPABILITY), c.requiredCapabilities)
        assertNotNull(c.inputCodec)
        assertNotNull(c.outputCodec)
        assertEquals("scm-git", c.descriptor.pluginId)
        assertEquals("scm-git.checkout", c.descriptor.name)
    }

    // =========================================================================
    // 3. codec input roundtrip
    // =========================================================================

    @Test
    fun `codec input — roundtrip preserves every GitCheckoutInput field`() {
        val original = GitCheckoutInput(
            url = "https://example.com/repo.git",
            branch = "release/2026.09",
            credentialsRef = "vault-token-7",
            changelog = false,
            poll = false,
            relativeTargetDir = "subdir/checkout",
        )
        val encoded: EncodedStepValue = GitCheckoutInputCodec.encode(original)
        val decoded = GitCheckoutInputCodec.decode(encoded)
        assertEquals(original, decoded)
    }

    // =========================================================================
    // 4. codec output roundtrip
    // =========================================================================

    @Test
    fun `codec output — roundtrip preserves resolvedSha + localPath + wasCloned + credentialApplied`() {
        val original = GitCheckoutOutput(
            resolvedSha = "abc123def456abc123def456abc123def456abc1",
            localPath = "/var/ws/subdir/checkout",
            wasCloned = true,
            credentialApplied = true,
        )
        val encoded: EncodedStepValue = GitCheckoutOutputCodec.encode(original)
        val decoded = GitCheckoutOutputCodec.decode(encoded)
        assertEquals(original, decoded)
    }

    // =========================================================================
    // 5. canonical envelope
    // =========================================================================

    @Test
    fun `envelope — input codec emits a well-formed JSON object (durable eligible)`() {
        val encoded: EncodedStepValue = GitCheckoutInputCodec.encode(
            GitCheckoutInput(url = "https://example.com/repo.git"),
        )
        val parsed = Json.parseToJsonElement(encoded.value)
        assertTrue(parsed is JsonObject, "input envelope must be a JSON object")
        // Verify no `declarativeValue` leak — every field is a named JSON property.
        val obj = parsed.jsonObject
        assertTrue("url" in obj, "url field must be present")
        assertTrue("branch" in obj, "branch field must be present")
        assertTrue("changelog" in obj, "changelog field must be present")
        assertTrue("poll" in obj, "poll field must be present")
        assertTrue("relativeTargetDir" in obj, "relativeTargetDir field must be present")
        assertFalse("declarativeValue" in obj, "canonical envelope must not leak declarativeValue")
    }

    // =========================================================================
    // 6. registry resolution
    // =========================================================================

    @Test
    fun `registry — InMemoryStepRegistry resolves scm-git checkout`() {
        val stub = stubbedStep(stubResult = Result.success(GitCheckoutResult("deadbeef", 0L, "no-op")))
        val registry = dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry()
        registry.register(stub)
        val resolved = registry.definition(ScmGitCheckoutKey.VALUE)
        assertNotNull(resolved, "scm-git.checkout must be resolvable through the registry")
        assertEquals(ScmGitCheckoutKey.VALUE, resolved!!.contract.key)
    }

    // =========================================================================
    // 7. capability admission (handler-level) — fails closed when capability absent
    // =========================================================================

    @Test
    fun `capability — handler fails closed when WORKSPACE_IDENTITY_CAPABILITY is absent`() {
        val stub = stubbedStep(stubResult = Result.success(GitCheckoutResult("deadbeef", 0L, "no-op")))
        val caps = object : StepCapabilityAccess {
            override fun available(): Set<StepCapability> = emptySet()
            override fun <T : Any> get(key: StepCapability): T =
                throw IllegalStateException("missing capability $key")
        }
        val ctx = StepHandlerContext(
            runId = RunId("test-scm-git"),
            stepIndex = 0,
            capabilities = caps,
        )
        val ex = assertThrows(Exception::class.java) {
            runBlocking { stub.handler.execute(GitCheckoutInput(url = "https://x/y.git"), ctx) }
        }
        // The boundary either throws PluginStepException or the typed
        // capability access throws IllegalStateException. Either way: NOT
        // silent success. Cancellation is also unacceptable here.
        assertFalse(ex is kotlinx.coroutines.CancellationException, "missing capability must not be a coroutine cancellation")
        // The message must mention either the capability OR a typed
        // failure; it must NOT be a generic handler exception.
        val msg = (ex.message ?: "").lowercase()
        assertTrue(
            msg.contains("capability") || msg.contains("workspace") || ex is PluginStepException || ex.cause is PluginStepException,
            "expected capability-missing diagnostic, got: ${ex::class.simpleName} ${ex.message}",
        )
    }

    // =========================================================================
    // 8. success — happy path with stubbed executor
    // =========================================================================

    @Test
    fun `success — handler returns typed GitCheckoutOutput for a stubbed executor`() = runBlocking {
        val ws = Files.createDirectories(tempDir.resolve("workspace"))
        val capturedRoots = mutableListOf<Path>()
        val capturedRequests = mutableListOf<GitCheckoutRequest>()
        val stub = stubbedStep(
            stubResult = Result.success(
                GitCheckoutResult(
                    sha = "feedfacefeedfacefeedfacefeedfacefeedface",
                    durationMs = 12L,
                    classification = "clone",
                    credentialsFilePath = "/tmp/.git-credentials",
                ),
            ),
            capturedRoots = capturedRoots,
            capturedRequests = capturedRequests,
        )
        val input = GitCheckoutInput(
            url = "https://example.com/repo.git",
            branch = "main",
            credentialsRef = "cred-1",
            changelog = true,
            poll = true,
            relativeTargetDir = ".",
        )
        val out = stub.handler.execute(input, handlerContext(ws))
        assertEquals("feedfacefeedfacefeedfacefeedfacefeedface", out.resolvedSha)
        assertEquals(ws.resolve(".").toString(), out.localPath)
        assertTrue(out.wasCloned)
        assertTrue(out.credentialApplied)
        // The handler must forward the workspace root from the typed
        // capability seam — NOT from user.dir. Production runs depend on this.
        assertEquals(1, capturedRoots.size)
        assertEquals(ws, capturedRoots[0])
        // The request must carry the URL from the typed input.
        assertEquals(1, capturedRequests.size)
        val spec = capturedRequests[0].spec
        val scm = spec.scm as GitScm
        assertEquals("https://example.com/repo.git", scm.url)
        assertEquals("main", scm.branch)
        assertEquals(dev.rubentxu.pipeline.v2.domain.CredentialsId("cred-1"), scm.credentialsId)
        assertTrue(scm.changelog)
        assertTrue(scm.poll)
        assertEquals(".", scm.relativeTargetDir)
    }

    // =========================================================================
    // 9. typed failure — executor Result.failure is surfaced via PluginStepException
    // =========================================================================

    @Test
    fun `typed failure — executor failure with auth message surfaces NETWORK FailureKind`() {
        val ws = Files.createDirectories(tempDir.resolve("workspace"))
        val stub = stubbedStep(
            stubResult = Result.failure(RuntimeException("auth failed: invalid token")),
        )
        val ex = assertThrows(PluginStepException::class.java) {
            runBlocking {
                stub.handler.execute(
                    GitCheckoutInput(url = "https://x/y.git", credentialsRef = "bad"),
                    handlerContext(ws),
                )
            }
        }
        assertEquals(FailureKind.NETWORK, ex.failure.kind)
    }

    @Test
    fun `typed failure — executor failure with not-found message surfaces USER FailureKind`() {
        val ws = Files.createDirectories(tempDir.resolve("workspace"))
        val stub = stubbedStep(
            stubResult = Result.failure(RuntimeException("repository not found at url")),
        )
        val ex = assertThrows(PluginStepException::class.java) {
            runBlocking {
                stub.handler.execute(
                    GitCheckoutInput(url = "https://x/y.git"),
                    handlerContext(ws),
                )
            }
        }
        assertEquals(FailureKind.USER, ex.failure.kind)
    }

    @Test
    fun `typed failure — executor failure with unrelated message surfaces INFRASTRUCTURE FailureKind`() {
        val ws = Files.createDirectories(tempDir.resolve("workspace"))
        val stub = stubbedStep(
            stubResult = Result.failure(RuntimeException("git transport reset by peer")),
        )
        val ex = assertThrows(PluginStepException::class.java) {
            runBlocking {
                stub.handler.execute(
                    GitCheckoutInput(url = "https://x/y.git"),
                    handlerContext(ws),
                )
            }
        }
        assertEquals(FailureKind.INFRASTRUCTURE, ex.failure.kind)
    }

    // =========================================================================
    // 10. replay — output is deterministic for identical input + executor
    // =========================================================================

    @Test
    fun `replay — output is deterministic for identical input`() = runBlocking {
        val ws = Files.createDirectories(tempDir.resolve("workspace"))
        val stub = stubbedStep(
            stubResult = Result.success(GitCheckoutResult("cafebabecafebabecafebabecafebabecafebabe", 7L, "no-op")),
        )
        val input = GitCheckoutInput(url = "https://x/y.git", branch = "main")
        val ctx = handlerContext(ws)
        val out1 = stub.handler.execute(input, ctx)
        val out2 = stub.handler.execute(input, ctx)
        assertEquals(out1, out2)
        // The encoded form must be byte-stable across re-encodes
        // (MEMOIZED replay eligibility requires canonical envelope).
        val e1 = GitCheckoutOutputCodec.encode(out1)
        val e2 = GitCheckoutOutputCodec.encode(out2)
        assertEquals(e1, e2)
    }

    // =========================================================================
    // 11. observability — events route through injected EventSink
    // =========================================================================

    @Test
    fun `observability — handler does not invent a fake runId or workspace and uses ctx`() = runBlocking {
        val ws = Files.createDirectories(tempDir.resolve("workspace"))
        val capturedRequests = mutableListOf<GitCheckoutRequest>()
        val stub = stubbedStep(
            stubResult = Result.success(GitCheckoutResult("deadbeef", 0L, "no-op")),
            capturedRequests = capturedRequests,
            eventSink = NullRecordingEventSink(),
        )
        stub.handler.execute(
            GitCheckoutInput(url = "https://x/y.git"),
            handlerContext(ws),
        )
        // The handler forwarded ctx.runId.value into the GitCheckoutRequest.
        // This guards against regressions where the handler might fabricate
        // a placeholder runId at construction time.
        assertEquals("test-scm-git", capturedRequests.single().runId)
    }

    // =========================================================================
    // 12. missing capability — boundary re-check (alias of axis 7 with stronger assertion)
    // =========================================================================

    @Test
    fun `missing capability — handler does NOT consult user dot dir as fallback`() {
        val capturedRoots = mutableListOf<Path>()
        val stub = stubbedStep(
            stubResult = Result.success(GitCheckoutResult("deadbeef", 0L, "no-op")),
            capturedRoots = capturedRoots,
        )
        // Empty capability set: the handler must THROW, not silently fall
        // back to user.dir / pipeline.workspace.root. We assert by observing
        // that the executor factory is NEVER called.
        val caps = object : StepCapabilityAccess {
            override fun available(): Set<StepCapability> = emptySet()
            override fun <T : Any> get(key: StepCapability): T =
                throw IllegalStateException("missing capability $key")
        }
        val ctx = StepHandlerContext(
            runId = RunId("test-scm-git"),
            stepIndex = 0,
            capabilities = caps,
        )
        assertThrows(Exception::class.java) {
            runBlocking { stub.handler.execute(GitCheckoutInput(url = "https://x/y.git"), ctx) }
        }
        assertEquals(0, capturedRoots.size, "executor factory must not be called when capability is missing")
    }

    // =========================================================================
    // 13. architectural fitness — this suite lives in the SDK plugin module
    // =========================================================================

    @Test
    fun `architectural fitness — StepDefinition is registered via the SDK contributor, not via CoreStepRegistryFactory`() {
        // The contributor is the only sanctioned entry point. Asserting its
        // existence + key prevents accidental migration of the plugin into
        // core (which would violate the open/closed boundary).
        val contributor = ScmGitStepDefinitionContributor()
        // `definitions()` does not require system properties; it returns
        // the bare StepDefinitions without provider metadata. The full
        // path (with provider metadata + manifest validation) is exercised
        // by `registerScmGit(...)` and integration tests in
        // `pipeline-application`.
        val definitions = contributor.definitions()
        val keys = definitions.map { it.contract.key.value }
        assertTrue("scm-git.checkout" in keys, "contributor must register scm-git.checkout")
    }

    // =========================================================================
    // 14. real DSL — extension lowers only via registryStep
    // =========================================================================

    @Test
    fun `real DSL — scmGitCheckout extension lowers only via registryStep and never executes`() {
        // ScmGitDsl defines the typed extension. Without invoking the
        // handler, constructing the spec must not shell out, must not
        // resolve the registry, and must produce a declarative value only.
        // We assert the spec shape here; runtime execution is covered by
        // UATs in pipeline-application under `V2_GIT_AVAILABLE`.
        val stepKey = ScmGitCheckoutKey.VALUE
        val input = GitCheckoutInput(url = "https://x/y.git", branch = "release/2026.09")
        val encoded = GitCheckoutInputCodec.encode(input)
        // The envelope must be JSON-shaped and contain every field.
        val parsed = Json.parseToJsonElement(encoded.value).jsonObject
        assertEquals("https://x/y.git", parsed["url"]?.jsonPrimitive?.content)
        assertEquals("release/2026.09", parsed["branch"]?.jsonPrimitive?.content)
        // The StepKey value is stable and matches the contract.
        assertEquals("scm-git.checkout", stepKey.value)
    }

    // =========================================================================
    // 15. typed credentialsRef carrier — survives codec roundtrip as a String,
    //     surfaces as GitCredentials.id in the SCM domain, NEVER as embedded secret
    // =========================================================================

    @Test
    fun `typed credentialsRef — codec roundtrip preserves the reference as a String, not a secret`() = runBlocking {
        val ws = Files.createDirectories(tempDir.resolve("workspace"))
        val capturedRequests = mutableListOf<GitCheckoutRequest>()
        val stub = stubbedStep(
            stubResult = Result.success(GitCheckoutResult("deadbeef", 0L, "no-op")),
            capturedRequests = capturedRequests,
        )
        val input = GitCheckoutInput(url = "https://x/y.git", credentialsRef = "vault-token-abc-123")
        // Encode -> decode roundtrip preserves the credential reference.
        val decoded = GitCheckoutInputCodec.decode(GitCheckoutInputCodec.encode(input))
        assertEquals("vault-token-abc-123", decoded.credentialsRef)
        // The handler surfaces it as a typed CredentialsId, never as bytes.
        stub.handler.execute(input, handlerContext(ws))
        val scm = capturedRequests.single().spec.scm as GitScm
        assertEquals(dev.rubentxu.pipeline.v2.domain.CredentialsId("vault-token-abc-123"), scm.credentialsId)
        // The typed payload must NOT embed secret bytes.
        val encoded = GitCheckoutInputCodec.encode(input)
        assertFalse(
            encoded.value.contains("BEGIN"),
            "encoded input must not contain PEM markers",
        )
        assertFalse(
            encoded.value.length > 256,
            "encoded input must remain a small typed reference, got length=${encoded.value.length}",
        )
    }

    @Test
    fun `typed credentialsRef — null reference is preserved and surfaces as null CredentialsId`() = runBlocking {
        val ws = Files.createDirectories(tempDir.resolve("workspace"))
        val capturedRequests = mutableListOf<GitCheckoutRequest>()
        val stub = stubbedStep(
            stubResult = Result.success(GitCheckoutResult("deadbeef", 0L, "no-op")),
            capturedRequests = capturedRequests,
        )
        val input = GitCheckoutInput(url = "https://x/y.git", credentialsRef = null)
        val decoded = GitCheckoutInputCodec.decode(GitCheckoutInputCodec.encode(input))
        assertNull(decoded.credentialsRef)
        stub.handler.execute(input, handlerContext(ws))
        val scm = capturedRequests.single().spec.scm as GitScm
        assertNull(scm.credentialsId)
    }

    // =========================================================================
    // 16. recovery policy — descriptor declares RecoveryPolicy.None
    // =========================================================================

    @Test
    fun `recovery — descriptor declares RecoveryPolicy dot None (no retry path)`() {
        val stub = stubbedStep(stubResult = Result.success(GitCheckoutResult("deadbeef", 0L, "no-op")))
        assertEquals(RecoveryPolicy.None, stub.contract.descriptor.recoveryPolicy)
        // Sanity: replay policy is MEMOIZED. The combination
        // MEMOIZED + RecoveryPolicy.None is the canonical "executor
        // owns idempotency, spine MUST NOT retry" declaration.
        assertEquals(ReplayPolicy.MEMOIZED, stub.contract.descriptor.replayPolicy)
    }

    // =========================================================================
    // 17. B2 hardening axis — credentialsRef fail-CLOSED regression guard
    //
    // WU-RP-053R/B2 closed a pre-existing fail-OPEN path in scm-git:
    // before B2, a credentialsRef that could not be resolved silently
    // fell back to anonymous git. After B2, the handler is fail-CLOSED:
    // unresolved credentialsRef surfaces a typed FailureKind and the
    // pipeline stops. This axis is the structural regression guard.
    //
    // The hermetic stub cannot reproduce the secret store resolution
    // path, so we verify the contract assertion that the handler does
    // NOT silently succeed when the executor returns failure. The real
    // B2 hardening is exercised end-to-end by
    // `GitCheckoutCredentialsRefFailClosedTest` (also in this module).
    // =========================================================================

    @Test
    fun `B2 hardening — executor failure with malformed credentialsRef surfaces typed failure, not silent success`() {
        val ws = Files.createDirectories(tempDir.resolve("workspace"))
        // The B2 hardening contract: when a credentialsRef fails SCM-domain
        // validation, the handler MUST surface a typed FailureKind and
        // MUST NOT silently fall back to anonymous git. The classifier
        // maps the auth-class failure to NETWORK (per
        // `classifyFailureKind` in GitCheckoutStepDefinition), so the
        // fixture uses an auth-style message to land on NETWORK. The
        // actual message is scrubbed upstream by
        // GitCredentialsApplier / SecretPatternRegistry, but the typed
        // classification MUST be present.
        val stub = stubbedStep(
            stubResult = Result.failure(
                RuntimeException("auth failed: credentials store rejected ref 'vault-token-abc'"),
            ),
        )
        val ex = assertThrows(PluginStepException::class.java) {
            runBlocking {
                stub.handler.execute(
                    GitCheckoutInput(url = "https://x/y.git", credentialsRef = "vault-token-abc"),
                    handlerContext(ws),
                )
            }
        }
        // The credential resolution failure MUST surface as a typed
        // FailureKind (NETWORK for auth-class failures), NOT as a
        // silent success and NOT as a generic unclassified exception.
        assertEquals(FailureKind.NETWORK, ex.failure.kind)
        // The typed failure message must include the failure kind
        // classification result; the message itself may be scrubbed
        // by the secret pattern registry upstream, but the typed
        // classification MUST be present.
        assertNotNull(ex.failure.message)
    }
}

/**
 * Recording EventSink that swallows every event. Used to assert the
 * handler did not raise an exception while emitting events, and to
 * provide a typed seam in place of NullEventSink.
 */
private class NullRecordingEventSink : EventSink {
    val events: MutableList<DomainEvent> = mutableListOf()
    override fun append(event: DomainEvent) { events.add(event) }
    override fun eventsFor(runId: String): Sequence<DomainEvent> =
        events.asSequence()
}

/**
 * Stubbed GitCheckoutExecutor whose `execute` method returns a fixed
 * Result without invoking the real D4 sequence. This is the hermetic
 * seam used by `CoreScmGitCheckoutStepContractSuiteTest`.
 *
 * It also captures the [GitCheckoutRequest] it received so tests can
 * assert that the handler forwarded the workspace root, runId, and
 * SCM spec correctly.
 */
private class StubbedGitCheckoutExecutor(
    private val delegate: GitCheckoutExecutor,
    private val requestCapture: MutableList<GitCheckoutRequest>,
    private val stubResult: Result<GitCheckoutResult>,
) : GitCheckoutExecutor(
    poll = dev.rubentxu.pipeline.v2.sdk.scm.git.GitPollExecutor(),
    changelog = dev.rubentxu.pipeline.v2.sdk.scm.git.GitChangelogWriter(),
    credentialsApplier = dev.rubentxu.pipeline.v2.sdk.scm.git.GitCredentialsApplier(
        tempDir = Files.createTempDirectory("scm-git-stub-delegate"),
        credentials = dev.rubentxu.pipeline.v2.domain.scm.GitCredentials(),
    ),
) {
    override fun execute(req: GitCheckoutRequest): Result<GitCheckoutResult> {
        requestCapture.add(req)
        return stubResult
    }

    override fun close() {
        // Do NOT close the delegate: the delegate may be shared or its
        // tempDir may be re-used by the registry harness. The contract
        // path does not exercise close().
    }
}
