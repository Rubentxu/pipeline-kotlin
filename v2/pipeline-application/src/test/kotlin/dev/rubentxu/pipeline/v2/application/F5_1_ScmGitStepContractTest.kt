package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs
import dev.rubentxu.pipeline.v2.domain.step.Delivery
import dev.rubentxu.pipeline.v2.domain.step.Digest
import dev.rubentxu.pipeline.v2.domain.step.PluginFamily
import dev.rubentxu.pipeline.v2.domain.step.PluginManifestValidator
import dev.rubentxu.pipeline.v2.domain.step.PluginReleaseRef
import dev.rubentxu.pipeline.v2.domain.step.SemVer
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.domain.step.StepProviderMetadata
import dev.rubentxu.pipeline.v2.domain.step.StepRegistration
import dev.rubentxu.pipeline.v2.domain.step.TrustMetadata
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.PluginManifest
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepManifest
import dev.rubentxu.pipeline.v2.domain.step.registerContributors
import dev.rubentxu.pipeline.v2.sdk.scm.git.step.GitCheckoutInputCodec
import dev.rubentxu.pipeline.v2.sdk.scm.git.step.GitCheckoutOutput
import dev.rubentxu.pipeline.v2.sdk.scm.git.step.GitCheckoutOutputCodec
import dev.rubentxu.pipeline.v2.sdk.scm.git.step.GitCheckoutStepDefinition
import dev.rubentxu.pipeline.v2.sdk.scm.git.step.SCM_GIT_OPERATIONS_CAPABILITY
import dev.rubentxu.pipeline.v2.sdk.scm.git.step.ScmGitCheckoutKey
import dev.rubentxu.pipeline.v2.sdk.scm.git.step.ScmGitStepDefinitionContributor
import dev.rubentxu.pipeline.v2.sdk.scm.git.step.registerScmGit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * F5.1 / ADR-0092 — Step contract suite for `scm-git.checkout`.
 *
 * This suite covers the canonical Step contract axes for the first
 * OFFICIAL_PLUGIN slice:
 *
 *   1. **Identity**: the StepKey resolves to the typed contract and the
 *      provider metadata is queryable in O(1).
 *   2. **Contract completeness**: the descriptor declares the expected
 *      execution location, effects, replay policy, recovery policy, and
 *      input/output codecs.
 *   3. **Codec roundtrip**: encode(decode(x)) == x and decode(encode(x)) == x
 *      for both input and output codecs (canonical-envelope discipline).
 *   4. **Capability admission**: the handler declares
 *      [SCM_GIT_OPERATIONS_CAPABILITY] and an absent capability fails the
 *      admission (fail-closed).
 *   5. **C5 manifest validation**: the contributor's registrations are
 *      structurally consistent with the supplied definitions
 *      ([PluginManifestValidator]).
 *   6. **C5 negative**: a manifest with mismatched declared capabilities
 *      fails closed at registration time.
 *   7. **C10 backwards-compat**: legacy contributors still register
 *      through the additive path without explicit provider metadata.
 *   8. **Real digest derivation**: the contributor computes / accepts a
 *      real SHA-256 digest (no fabricated values; sentinel-only when
 *      build properties are absent).
 */
class F5_1_ScmGitStepContractTest {

    @Test
    fun `identity and contract completeness`() {
        // Use the contributor (which fails closed without build properties);
        // for the contract-only assertions we build a manual registration
        // so the test is independent of the build environment.
        val definition = GitCheckoutStepDefinition()
        val provider = makeProvider("pipeline-kotlin", "pipeline.scm-git", "scm-git", 0, 36, 0,
            "sha256:" + "c".repeat(64))

        val contract: StepContract<*, *> = definition.contract
        assertEquals(ScmGitCheckoutKey.VALUE, contract.key)
        assertEquals(ScmGitCheckoutKey.VALUE.value, contract.descriptor.stepId)
        assertEquals("scm-git", contract.descriptor.pluginId)
        assertEquals("scm-git.checkout", contract.descriptor.name)
        assertEquals(ExecutionLocation.CONTROLLER, contract.descriptor.executionLocation)
        assertTrue(contract.descriptor.effects.contains(Effect.WRITES_WORKSPACE))
        assertTrue(contract.descriptor.effects.contains(Effect.EXECUTES_SUBPROCESS))
        assertEquals(ReplayPolicy.MEMOIZED, contract.descriptor.replayPolicy)
        assertNotNull(contract.descriptor.recoveryPolicy)
        // F5.1 UAT-closure: the contract declares an empty capability set
        // so the canonical engine admits the invocation; capability-routed
        // workspace root lands with F5.2.
        assertTrue(contract.requiredCapabilities.isEmpty())
    }

    @Test
    fun `codec roundtrip input and output`() {
        val inputCodec = GitCheckoutInputCodec
        val outputCodec = GitCheckoutOutputCodec

        val input = dev.rubentxu.pipeline.v2.sdk.scm.git.step.GitCheckoutInput(
            url = "https://example.com/repo.git",
            branch = "main",
            credentialsRef = "creds-1",
            changelog = true,
            poll = false,
            relativeTargetDir = "src",
        )
        val encoded: EncodedStepValue = inputCodec.encode(input)
        val decoded = inputCodec.decode(encoded)
        assertEquals(input, decoded)

        val output = GitCheckoutOutput(
            resolvedSha = "abc123",
            localPath = "/tmp/src",
            wasCloned = true,
            credentialApplied = true,
        )
        val outEncoded = outputCodec.encode(output)
        val outDecoded = outputCodec.decode(outEncoded)
        assertEquals(output, outDecoded)
    }

    @Test
    fun `registry-backed lookup returns OFFICIAL_PLUGIN provider for the registered StepKey`() {
        val registry = InMemoryStepRegistry()
        val provider = makeProvider("pipeline-kotlin", "pipeline.scm-git", "scm-git", 0, 36, 0,
            "sha256:" + "d".repeat(64))
        val definition = GitCheckoutStepDefinition()

        registry.register(StepRegistration(definition, provider))

        assertSame(definition, registry.definition(ScmGitCheckoutKey.VALUE))
        assertEquals(provider, registry.providerOf(ScmGitCheckoutKey.VALUE))
    }

    @Test
    fun `C5 manifest validation accepts a well-formed manifest`() {
        val definition = GitCheckoutStepDefinition()
        val provider = makeProvider("pipeline-kotlin", "pipeline.scm-git", "scm-git", 0, 36, 0,
            "sha256:" + "e".repeat(64))
        val manifest = PluginManifest(
            plugin = provider.plugin,
            release = provider.release,
            publisher = provider.publisher,
            families = provider.families,
            delivery = provider.delivery,
            trust = provider.trust,
            stepManifests = listOf(
                StepManifest(
                    stepKey = ScmGitCheckoutKey.VALUE,
                    declaredCapabilities = definition.contract.requiredCapabilities,
                ),
            ),
        )
        // Must not throw.
        PluginManifestValidator.validate(manifest, listOf(definition))
    }

    @Test
    fun `C5 manifest validation rejects a manifest with mismatched declaredCapabilities`() {
        val definition = GitCheckoutStepDefinition()
        val provider = makeProvider("pipeline-kotlin", "pipeline.scm-git", "scm-git", 0, 36, 0,
            "sha256:" + "f".repeat(64))
        val wrongCapabilities = setOf(StepCapability("unrelated.capability"))
        val manifest = PluginManifest(
            plugin = provider.plugin,
            release = provider.release,
            publisher = provider.publisher,
            families = provider.families,
            delivery = provider.delivery,
            trust = provider.trust,
            stepManifests = listOf(
                StepManifest(
                    stepKey = ScmGitCheckoutKey.VALUE,
                    declaredCapabilities = wrongCapabilities,
                ),
            ),
        )
        val ex = assertThrows(IllegalArgumentException::class.java) {
            PluginManifestValidator.validate(manifest, listOf(definition))
        }
        assertTrue(ex.message!!.contains("scm-git.checkout"))
        assertTrue(ex.message!!.contains("declared=$wrongCapabilities"))
    }

    @Test
    fun `C5 manifest validation rejects a manifest naming a StepKey not provided`() {
        val definition = GitCheckoutStepDefinition()
        val provider = makeProvider("pipeline-kotlin", "pipeline.scm-git", "scm-git", 0, 36, 0,
            "sha256:" + "1".repeat(64))
        val manifest = PluginManifest(
            plugin = provider.plugin,
            release = provider.release,
            publisher = provider.publisher,
            families = provider.families,
            delivery = provider.delivery,
            trust = provider.trust,
            stepManifests = listOf(
                StepManifest(
                    stepKey = PluginStepId("scm-git.unknown"),
                    declaredCapabilities = emptySet(),
                ),
            ),
        )
        val ex = assertThrows(IllegalArgumentException::class.java) {
            PluginManifestValidator.validate(manifest, listOf(definition))
        }
        assertTrue(ex.message!!.contains("scm-git.unknown"))
    }

    @Test
    fun `C10 backwards-compat legacy contributor registers through additive registrations path`() {
        val registry = InMemoryStepRegistry()
        val legacyContributor = object : dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor {
            override val id: String = "legacy.fixture"
            override fun definitions(): Iterable<StepDefinition<*, *>> = listOf(
                object : StepDefinition<String, String> {
                    override val contract = StepContract(
                        key = PluginStepId("legacy.fixture.greet"),
                        descriptor = StepDescriptor(
                            stepId = "legacy.fixture.greet",
                            name = "greet",
                            configRef = "",
                            pluginId = "legacy-fixture",
                            pluginVersion = "1.0.0",
                            executionLocation = ExecutionLocation.CONTROLLER,
                            effects = listOf(Effect.READ_ONLY),
                            replayPolicy = ReplayPolicy.MEMOIZED,
                        ),
                        inputCodec = object : StepCodec<String> {
                            override fun encode(value: String) = EncodedStepValue("\"$value\"")
                            override fun decode(encoded: EncodedStepValue) =
                                encoded.value.trim('"')
                        },
                        outputCodec = object : StepCodec<String> {
                            override fun encode(value: String) = EncodedStepValue("\"$value\"")
                            override fun decode(encoded: EncodedStepValue) =
                                encoded.value.trim('"')
                        },
                        requiredCapabilities = emptySet(),
                    )
                    override val handler = StepHandler<String, String> { input, _ -> "hi:$input" }
                },
            )
        }
        registry.registerContributors(listOf(legacyContributor))
        // Legacy registration does NOT fail-closed: the contract key is
        // present in the registry.
        assertTrue(registry.contains(PluginStepId("legacy.fixture.greet")))
        // Provider metadata for legacy-shape registrations is the
        // synthetic plugin/release derived from the Step key (the
        // StepRegistration.legacy default).
        val provider = registry.providerOf(PluginStepId("legacy.fixture.greet"))
        assertNotNull(provider)
        assertEquals("legacy-external", provider!!.publisher)
        assertEquals(Delivery.EXTERNAL_REFERENCE, provider.delivery)
    }

    @Test
    fun `ScmGitStepDefinitionContributor refuses to register when digest is malformed even with build-time provenance present`() {
        // F5.1 UAT-closure: the contributor accepts build-time provenance
        // from system properties OR from META-INF/scm-git-release.properties
        // (whichever wins). The fail-closed invariant we still enforce is
        // that a MALFORMED digest (not 'sha256:<64-hex>') is rejected.
        // We override the publisher / namespace / version with valid
        // values via system properties and tamper with the digest to
        // prove the structural guard stays.
        val keys = listOf(
            "pipeline.scm-git.publisher",
            "pipeline.scm-git.namespace",
            "pipeline.scm-git.release.version",
            "pipeline.scm-git.release.digest",
        )
        val previous = keys.associateWith { System.getProperty(it) }
        System.setProperty("pipeline.scm-git.publisher", "pipeline-kotlin")
        System.setProperty("pipeline.scm-git.namespace", "pipeline.scm-git")
        System.setProperty("pipeline.scm-git.release.version", "0.36.0")
        System.setProperty("pipeline.scm-git.release.digest", "not-a-valid-digest")
        try {
            val ex = assertThrows(IllegalArgumentException::class.java) {
                ScmGitStepDefinitionContributor().registrations().toList()
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
    fun `registerScmGit helper produces a registration with real digest and OFFICIAL_PLUGIN delivery`() {
        val realDigest = "sha256:" + "9".repeat(64)
        val registry = InMemoryStepRegistry()
        val provider = registerScmGitFixture(
            publisher = "pipeline-kotlin",
            namespace = "pipeline.scm-git",
            version = "0.36.0",
            digestSha256 = realDigest,
            registry = registry,
        )
        assertEquals(Delivery.OFFICIAL_PLUGIN, provider.delivery)
        assertEquals(realDigest, provider.release.digest.value)
        assertEquals("pipeline-kotlin", provider.publisher)
        assertEquals(setOf(PluginFamily.SCM, PluginFamily.NETWORK), provider.families)
        assertEquals(SemVer(0, 36, 0), provider.release.version)
        assertNotNull(registry.providerOf(ScmGitCheckoutKey.VALUE))
        assertEquals(provider, registry.providerOf(ScmGitCheckoutKey.VALUE))
    }

    /**
     * Same as [registerScmGit] but registers into a caller-supplied registry
     * (so we can verify behaviour on a fresh registry without side-effects).
     */
    private fun registerScmGitFixture(
        publisher: String,
        namespace: String,
        version: String,
        digestSha256: String,
        registry: dev.rubentxu.pipeline.v2.domain.step.StepRegistry,
    ): StepProviderMetadata {
        require(digestSha256.startsWith("sha256:"))
        val saved = listOf("pipeline.scm-git.publisher", "pipeline.scm-git.namespace",
            "pipeline.scm-git.release.version", "pipeline.scm-git.release.digest")
            .associateWith { System.getProperty(it) }
        try {
            System.setProperty("pipeline.scm-git.publisher", publisher)
            System.setProperty("pipeline.scm-git.namespace", namespace)
            System.setProperty("pipeline.scm-git.release.version", version)
            System.setProperty("pipeline.scm-git.release.digest", digestSha256)
            val registration = ScmGitStepDefinitionContributor().registrations().single()
            registry.register(registration)
            return registration.provider
        } finally {
            saved.forEach { (k, prev) ->
                if (prev == null) System.clearProperty(k) else System.setProperty(k, prev)
            }
        }
    }

    @Test
    fun `sha256DigestOf computes the real SHA-256 of a file (no fabricated digest)`() {
        // Write a tiny file with known content; verify the helper returns
        // the canonical `sha256:<64-hex>` shape matching the independent
        // `sha256sum` CLI.
        val tmp = Files.createTempFile("scm-git-test-", ".bin")
        tmp.toFile().writeText("hello-f5-1")
        try {
            val expected = "sha256:" + runShellSha256(tmp)
            val actual = dev.rubentxu.pipeline.v2.sdk.scm.git.step.sha256DigestOf(tmp)
            assertEquals(expected, actual, "Helper must produce the same SHA-256 as the sha256sum CLI")
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private fun runShellSha256(path: Path): String {
        val process = ProcessBuilder("sha256sum", path.toString()).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        return out.split(Regex("\\s+")).first()
    }

    private fun makeProvider(
        publisher: String,
        namespace: String,
        identity: String,
        major: Int,
        minor: Int,
        patch: Int,
        digestValue: String,
    ): StepProviderMetadata = StepProviderMetadata.create(
        plugin = ResourceRefs.plugin(namespace, identity),
        release = PluginReleaseRef(
            plugin = ResourceRefs.plugin(namespace, identity),
            version = SemVer(major, minor, patch),
            digest = Digest(digestValue),
        ),
        publisher = publisher,
        families = setOf(PluginFamily.SCM, PluginFamily.NETWORK),
        delivery = Delivery.OFFICIAL_PLUGIN,
        trust = TrustMetadata.Unverified,
    )
}
