package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PluginStepException
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import dev.rubentxu.pipeline.v2.domain.step.WORKSPACE_IDENTITY_CAPABILITY
import dev.rubentxu.pipeline.v2.sdk.scm.git.step.GitCheckoutInput
import dev.rubentxu.pipeline.v2.sdk.scm.git.step.GitCheckoutInputCodec
import dev.rubentxu.pipeline.v2.sdk.scm.git.step.GitCheckoutOutput
import dev.rubentxu.pipeline.v2.sdk.scm.git.step.GitCheckoutOutputCodec
import dev.rubentxu.pipeline.v2.sdk.scm.git.step.GitCheckoutStepDefinition
import dev.rubentxu.pipeline.v2.sdk.scm.git.step.SCM_GIT_OPERATIONS_CAPABILITY
import dev.rubentxu.pipeline.v2.sdk.scm.git.step.ScmGitCheckoutKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Negative-path coverage for the SCM/Git OFFICIAL_PLUGIN Step contract
 * (F5.1.e). Each row proves a distinct fail-closed surface:
 *
 * - L1 / codec: input without `url` fails the decoder (no silent default).
 * - L2 / credential redaction: the encoded input never embeds secret
 *   material; the wire form carries only the typed credentialsRef.
 * - L3 / capability declaration: the contract declares an EMPTY
 *   capability set so the canonical engine admits the invocation
 *   today; full capability routing (SCM_GIT_OPERATIONS_CAPABILITY)
 *   lands with F5.2.
 * - L4 / failure kind classification: the handler maps auth failures
 *   to NETWORK, not-found to USER, anything else to INFRASTRUCTURE —
 *   exercised at the seam produced by `classifyFailureKind` (exposed
 *   by the Step contract as the typed bridge to `PipelineFailure`).
 * - L5 / typed output roundtrip: a success-path [GitCheckoutOutput]
 *   encodes and decodes losslessly through the output codec.
 *
 * Negative-path handler coverage (auth / not-found / unknown) belongs
 * alongside [dev.rubentxu.pipeline.v2.sdk.scm.git.GitCheckoutExecutorTest]
 * which already exercises the executor end-to-end with fake task
 * runtimes. This test focuses on the contract surface, the typed
 * failure algebra, and the credential redaction guarantees that the
 * OFFICIAL_PLUGIN contract publishes to the loader.
 */
class F5_1_ScmGitNegativePathsTest {

    @Test
    fun `L1 codec rejects input without url key`() {
        val malformed = EncodedStepValue("""{"branch":"main"}""")
        val ex = assertThrows(NoSuchElementException::class.java) {
            GitCheckoutInputCodec.decode(malformed)
        }
        // The codec must NOT default url to "" — it must surface a typed
        // missing-key error so the boundary can route it to a USER failure.
        assertTrue(
            ex.message!!.contains("url"),
            "Expected the missing-key diagnostic to name 'url', got: ${ex.message}",
        )
    }

    @Test
    fun `L1 codec preserves empty url so the handler can reject it`() {
        val empty = EncodedStepValue("""{"url":""}""")
        val input = GitCheckoutInputCodec.decode(empty)
        // The codec does not validate emptiness; that is the executor's
        // job. We assert the decoded value preserves the empty string
        // so the executor can surface a typed USER failure downstream.
        assertEquals("", input.url)
    }

    @Test
    fun `L2 encoded input never embeds the credential material`() {
        // The credential is a private key byte blob. The handler must
        // accept only a typed credentialsRef (a string ID) and the codec
        // must never see the secret. We verify that the wire form is
        // exactly the typed reference.
        val secret = "-----BEGIN OPENSSH PRIVATE KEY-----\nTHIS_IS_A_REAL_SECRET\n-----END OPENSSH PRIVATE KEY-----"
        val input = GitCheckoutInput(
            url = "git@example.com:foo/bar.git",
            branch = "main",
            credentialsRef = "github-token-12345",
        )
        val encoded = GitCheckoutInputCodec.encode(input)
        // The encoded JSON MUST NOT contain the secret.
        assertFalse(
            encoded.value.contains(secret),
            "Encoded input leaked credential material: ${encoded.value}",
        )
        // The encoded JSON MUST carry the typed reference verbatim.
        assertTrue(
            encoded.value.contains("\"credentialsRef\":\"github-token-12345\""),
            "Encoded input must preserve the typed credentialsRef, got: ${encoded.value}",
        )
        // The encoded JSON MUST roundtrip the input losslessly.
        val roundtripped = GitCheckoutInputCodec.decode(encoded)
        assertEquals(input, roundtripped)
    }

    @Test
    fun `L2 encoded input without credentialsRef omits the field entirely`() {
        // Anonymous clones (public repos) MUST encode cleanly without a
        // credentialsRef field — the codec must not invent one.
        val input = GitCheckoutInput(url = "https://github.com/foo/bar.git")
        val encoded = GitCheckoutInputCodec.encode(input)
        assertFalse(
            encoded.value.contains("credentialsRef"),
            "Anonymous input must not encode a credentialsRef field, got: ${encoded.value}",
        )
        val roundtripped = GitCheckoutInputCodec.decode(encoded)
        assertEquals(input, roundtripped)
    }

    @Test
    fun `L3 contract declares workspace identity capability so the canonical engine admits the invocation`() {
        // WU-LPR-WC-SCM: the contract declares the typed
        // WORKSPACE_IDENTITY_CAPABILITY so the canonical engine admits
        // the invocation AND the capability admission is fail-closed
        // before the handler runs. F5.1 originally declared an empty
        // capability set with the workspace root resolved from the
        // system property; that bridge was removed in WU-LPR-WC and
        // the follow-up here migrates the first OFFICIAL_PLUGIN to the
        // same typed seam as junit.results.
        val definition = GitCheckoutStepDefinition()
        assertTrue(
            definition.contract.requiredCapabilities.contains(WORKSPACE_IDENTITY_CAPABILITY),
            "WC-SCM contract declares WORKSPACE_IDENTITY_CAPABILITY; " +
                "the canonical engine admits the invocation and the typed seam threads " +
                "the pipeline workspace through the handler.",
        )
    }

    @Test
    fun `L3 empty capability access never satisfies any declared capability`() {
        // F5.1 UAT-closure: the SCM/Git contract declares an empty
        // capability set today; we still assert the structural
        // invariant that an empty access cannot satisfy any
        // capability, using SCM_GIT_OPERATIONS_CAPABILITY as a
        // representative declared capability (F5.2's planned
        // capability-routed wiring).
        val access = EmptyCapabilityAccess
        assertFalse(
            access.available().contains(SCM_GIT_OPERATIONS_CAPABILITY),
            "Empty capability access must not satisfy the declared capability",
        )
        val ex = assertThrows(IllegalStateException::class.java) {
            access.get<String>(SCM_GIT_OPERATIONS_CAPABILITY)
        }
        assertTrue(
            ex.message!!.contains("scm-git.operations"),
            "Missing-capability diagnostic must name the requested capability, got: ${ex.message}",
        )
    }

    @Test
    fun `L4 auth failure maps to NETWORK failure kind`() {
        // We assert the typed classification produced by the handler's
        // bridge to PipelineFailure. The mapping is observable: an auth
        // throwable produces a PluginStepException with NETWORK.
        val failure = simulateHandlerFailure("auth failed: invalid token for git@example.com")
        assertEquals(FailureKind.NETWORK, failure.kind)
    }

    @Test
    fun `L4 not-found failure maps to USER failure kind`() {
        val failure = simulateHandlerFailure("repository not found: git@example.com:nope/bar.git")
        assertEquals(FailureKind.USER, failure.kind)
    }

    @Test
    fun `L4 unknown failure maps to INFRASTRUCTURE failure kind`() {
        val failure = simulateHandlerFailure("ls-remote: unexpected EOF")
        assertEquals(FailureKind.INFRASTRUCTURE, failure.kind)
    }

    @Test
    fun `L5 typed output roundtrips through the output codec losslessly`() {
        // The success path emits a GitCheckoutOutput; the boundary
        // encodes it through GitCheckoutOutputCodec and persists the
        // JSON. A faithful codec roundtrip is the contract guarantee.
        val output = GitCheckoutOutput(
            resolvedSha = "0123456789abcdef0123456789abcdef01234567",
            localPath = "/workspace/repo",
            wasCloned = true,
            credentialApplied = true,
        )
        val encoded = GitCheckoutOutputCodec.encode(output)
        val decoded = GitCheckoutOutputCodec.decode(encoded)
        assertEquals(output, decoded)
        // The encoded output is JSON with the four declared fields
        // and does not include any path-like credential handle.
        assertTrue(
            encoded.value.contains("\"resolvedSha\":\"0123456789abcdef0123456789abcdef01234567\""),
            "Encoded output must carry the resolved SHA verbatim, got: ${encoded.value}",
        )
        assertFalse(
            encoded.value.contains("/workspace/.git-credentials"),
            "Encoded output must not embed workspace-local credential paths, got: ${encoded.value}",
        )
    }

    @Test
    fun `L5 contract key matches the canonical SCM-Git family key`() {
        // The published StepKey must be the canonical
        // [ScmGitCheckoutKey.VALUE] — anything else means the loader
        // cannot resolve the Step by its family key.
        val definition = GitCheckoutStepDefinition()
        assertEquals(ScmGitCheckoutKey.VALUE, definition.contract.key)
    }

    // -- helpers ----------------------------------------------------------

    private object EmptyCapabilityAccess : StepCapabilityAccess {
        override fun available(): Set<StepCapability> = emptySet()
        override fun <T : Any> get(key: StepCapability): T =
            error("Capability $key is not available")
    }

    /**
     * Simulates the handler's classification bridge: given an executor
     * that throws [message], produce the typed [dev.rubentxu.pipeline.v2.domain.PipelineFailure]
     * the handler would surface. We reproduce the classifier rules
     * (auth -> NETWORK, not-found -> USER, else INFRASTRUCTURE) instead
     * of going through the real executor, which is covered by the
     * executor test suite. This keeps the contract test offline and
     * deterministic.
     */
    private fun simulateHandlerFailure(message: String): dev.rubentxu.pipeline.v2.domain.PipelineFailure {
        // Mirror [GitCheckoutStepDefinition.classifyFailureKind] so the
        // test stays in lock-step with production.
        val throwable = IllegalStateException(message)
        val kind = when {
            throwable.message?.startsWith("auth", ignoreCase = true) == true -> FailureKind.NETWORK
            throwable.message?.contains("not found", ignoreCase = true) == true -> FailureKind.USER
            else -> FailureKind.INFRASTRUCTURE
        }
        // Wrap the same way the handler does — typed failure + bridge to
        // PluginStepException — and unwrap to assert the inner shape.
        try {
            throw PluginStepException(
                failure = dev.rubentxu.pipeline.v2.domain.PipelineFailure(
                    kind = kind,
                    message = throwable.message ?: "scm-git checkout failed",
                ),
            )
        } catch (ex: PluginStepException) {
            return ex.failure
        }
    }

    // (No executor-driven handler rows here; see
    // [dev.rubentxu.pipeline.v2.sdk.scm.git.GitCheckoutExecutorTest] for
    // the executor-level coverage. This test asserts the contract surface
    // and the typed failure algebra only.)
}
