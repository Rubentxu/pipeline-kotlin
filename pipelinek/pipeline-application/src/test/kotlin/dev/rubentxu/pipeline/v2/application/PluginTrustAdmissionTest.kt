package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.step.AdmissionRefusal
import dev.rubentxu.pipeline.v2.domain.step.Digest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

/**
 * The plugin the operator brought, and what the runtime tells them about it.
 *
 * ## The guarantee
 *
 * A plugin the operator brought with `--plugin-jar` and that no trust decision approved is
 * LOADED SILENTLY when the run says nothing about it. The operator asked for a plugin, got
 * one, and read nothing about whether anybody vouches for its bytes — and a plugin that
 * arrives without a word about its verification is a plugin the operator cannot tell apart
 * from one the policy approved.
 *
 * Two facts are therefore owed to the operator, and they are the two halves of one decision:
 *
 *  - WITHOUT the demand: the plugin is loaded, and the operator is told on stderr that it was
 *    loaded without a trust decision;
 *  - WITH `--require-plugin-trust`: the plugin is REFUSED, before anything of it is loaded,
 *    and the refusal names the plugin.
 *
 * The second is not a louder first. An operator who demanded verification and got a loaded
 * plugin has been told nothing, so the demand turns the silence into a refusal.
 *
 * ## Why the demand is scoped to the operator's OWN artifacts
 *
 * The plugins bundled with the installation are covered by the distribution the operator
 * installed, not by a row they would have to write for each. The demand is about what the
 * operator BROUGHT: their jar, their bytes, their decision.
 *
 * ## Why this instrument
 *
 * The admission is a pure decision over a list of artifact PATHS: it reads the manifest
 * document out of the artifact and never links a class, so the refusal it produces is a
 * refusal that happened BEFORE any plugin code ran. That is the property the guarantee is
 * about, and it is observable without a classloader, without a composition and without a
 * process.
 *
 * The artifact is a REAL JAR carrying a REAL manifest, written by the product's own codec
 * ([OperatorPluginFixture]) rather than by hand: a hand-written manifest would be a second
 * authority about what a plugin declares, and a fixture that agrees with the reader it tests
 * proves nothing about it.
 *
 * The stream the report is printed on is a PARAMETER of the admission, so the report can be
 * read without capturing a global: the row reads the same bytes an operator would read.
 *
 * ## What this does not freeze
 *
 * The wording of the report and of the refusal. The rows check that the plugin is NAMED and
 * that the absence of a trust decision is STATED, not the sentence that says it. A report
 * that said the same thing differently would still be a report.
 *
 * ## What this instrument cannot see
 *
 * The wiring between the parsed request and this admission. The flag is read by the shared
 * parser ([CliExecutionParser]) into [CliFlags.requirePluginTrust], and the run path carries
 * it into the admission; the rows here observe the two ends of that chain and the decision
 * in the middle.
 */
class PluginTrustAdmissionTest {

    @TempDir
    lateinit var root: Path

    private val script = "pipeline.kts"

    @Test
    fun `the demand reaches the request that runs`() {
        val flags = CliExecutionParser.parse(arrayOf("run", "--require-plugin-trust", script)).parsed()

        assertTrue(
            flags.requirePluginTrust,
            "the operator demanded a trust decision and the request does not carry it",
        )
    }

    @Test
    fun `a request that runs without the demand does not carry it`() {
        val flags = CliExecutionParser.parse(arrayOf("run", script)).parsed()

        assertTrue(
            !flags.requirePluginTrust,
            "the demand is opt-in: a request that does not ask for it must not get it",
        )
    }

    @Test
    fun `a reader refuses the demand by name`() {
        val error = CliObservationParser.parse(arrayOf("run-1", "--require-plugin-trust")).rejected()

        assertEquals(
            CliError.OptionNotReadable("--require-plugin-trust"),
            error,
            "a reader loads no plugin, so the demand is refused rather than dropped",
        )
    }

    @Test
    fun `a validation refuses the demand by naming the verb that owns it`() {
        val error = CliExecutionParser.parse(arrayOf("validate", "--require-plugin-trust", script)).rejected()

        val refusal = assertInstanceOf(
            CliError.OptionBelongsToVerb::class.java,
            error,
            "a validation admits no plugin, so it takes no trust decision to require",
        )
        assertEquals("--require-plugin-trust", refusal.option, "the refusal names the option")
        assertEquals("run", refusal.owner, "the refusal names the verb that owns the option")
    }

    @Test
    fun `an operator jar with no trust decision is loaded and the operator is told`() {
        val jar = OperatorPluginFixture.jar(root)
        val report = ByteArrayOutputStream()
        val outcome = admit(jar, requireTrust = false, err = PrintStream(report))

        assertInstanceOf(
            AdmittedArtifacts.Admitted::class.java,
            outcome,
            "the jar is admitted: the demand was not made, so the plugin is loaded",
        )
        val spoken = report.toString(Charsets.UTF_8)
        assertTrue(
            spoken.contains("WITHOUT a trust decision"),
            "the operator is not told that the jar they brought carries no trust decision: $spoken",
        )
        assertTrue(
            spoken.contains(OperatorPluginFixture.PLUGIN_ID),
            "the report does not name the plugin the operator brought: $spoken",
        )
    }

    @Test
    fun `the same jar is refused when the operator demands a trust decision`() {
        val jar = OperatorPluginFixture.jar(root)
        val outcome = admit(jar, requireTrust = true)

        val refusal = assertInstanceOf(
            AdmittedArtifacts.Refused::class.java,
            outcome,
            "an unverified plugin the operator demanded to be verified must not be admitted",
        )
        assertEquals(jar, refusal.artifact, "the refusal names the artifact that was refused")
        assertInstanceOf(
            AdmissionRefusal.RefusedUntrustedIdentity::class.java,
            refusal.rejection,
            "the refusal is the one the trust policy produces when it has no row for the plugin",
        )
        assertTrue(
            refusal.detail.orEmpty().contains("required every plugin they brought to be verified"),
            "the refusal does not say that the operator asked for verification: ${refusal.detail}",
        )
    }

    @Test
    fun `a jar the operator did not bring is not refused by that demand`() {
        val jar = OperatorPluginFixture.jar(root)
        val outcome = admit(jar, requireTrust = true, operatorNamed = false)

        assertInstanceOf(
            AdmittedArtifacts.Admitted::class.java,
            outcome,
            "the demand is about the operator's OWN artifacts: the bundled ones are covered by " +
                "the distribution they shipped in",
        )
    }

    @Test
    fun `an artifact the operator's own policy approved is loaded even when the demand is made`() {
        val jar = OperatorPluginFixture.jar(root)
        val digest = measuredDigestOf(jar)
        val policy = PluginTrustPolicy(
            source = approvedBy(jar, digest),
            operatorArtifacts = mapOf(digest.value.lowercase() to jar),
        )
        val report = ByteArrayOutputStream()

        val outcome = PreLoadPluginAdmission.admitAll(
            artifacts = listOf(jar),
            measured = mapOf(jar to digest),
            runtimeVersion = OperatorPluginFixture.RUNTIME_VERSION,
            trust = policy,
            journal = null,
            operatorDigests = setOf(digest.value.lowercase()),
            requireTrust = true,
            err = PrintStream(report),
        )

        assertInstanceOf(
            AdmittedArtifacts.Admitted::class.java,
            outcome,
            "the demand must not refuse what the operator's OWN policy approved",
        )
        val spoken = report.toString(Charsets.UTF_8)
        assertTrue(
            spoken.contains("admitted under the operator's trust policy"),
            "the operator is not told that their policy approved the plugin: $spoken",
        )
        assertTrue(
            !spoken.contains("WITHOUT a trust decision"),
            "an approved plugin is reported as approved, not as unverified: $spoken",
        )
    }

    /**
     * The admission of ONE artifact, with the trust decision the operator asked for.
     *
     * The trust policy is absent on purpose: the row is about the artifacts no decision
     * approved, and a policy in force would decide the question by itself.
     */
    private fun admit(
        jar: Path,
        requireTrust: Boolean,
        operatorNamed: Boolean = true,
        err: PrintStream = PrintStream(ByteArrayOutputStream()),
    ): AdmittedArtifacts {
        val digest = measuredDigestOf(jar)
        return PreLoadPluginAdmission.admitAll(
            artifacts = listOf(jar),
            measured = mapOf(jar to digest),
            runtimeVersion = OperatorPluginFixture.RUNTIME_VERSION,
            trust = null,
            journal = null,
            operatorDigests = if (operatorNamed) setOf(digest.value.lowercase()) else emptySet(),
            requireTrust = requireTrust,
            err = err,
        )
    }

    /**
     * A trust source that APPROVES the fixture jar, with a real publisher signature.
     *
     * The operator's file names the plugin, the release, the digest and the key; the publisher's
     * detached signature travels beside the artifact, and the key the operator keeps is the one
     * that verifies it. All three are real files, because the decision is about real bytes and a
     * fixture that faked any of them would prove nothing about the decision.
     */
    private fun approvedBy(jar: Path, digest: Digest): PluginTrustSource {
        val keys = Files.createDirectories(root.resolve("keys"))
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        Files.writeString(
            keys.resolve("${OperatorPluginFixture.KEY_ID}.pub"),
            Base64.getEncoder().encodeToString(pair.public.encoded),
        )
        val trustFile = root.resolve("trust.json")
        Files.writeString(
            trustFile,
            """{"${OperatorPluginFixture.PLUGIN_ID}@${OperatorPluginFixture.RELEASE_VERSION}": """ +
                """{"digest": "${digest.value}", "publisher": "${OperatorPluginFixture.PUBLISHER}", """ +
                """"signed": true, "signedBy": "${OperatorPluginFixture.KEY_ID}"}}""",
        )
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(pair.private)
        signer.update(
            requireNotNull(digest.rawBytes()) { "the digest is not a SHA-256 this test can sign over" },
        )
        Files.writeString(
            PluginArtifactSignatureCodec.sidecarOf(jar),
            """{"keyId": "${OperatorPluginFixture.KEY_ID}", """ +
                """"algorithm": "${PluginArtifactSignatureCodec.ALGORITHM}", """ +
                """"signature": "${Base64.getEncoder().encodeToString(signer.sign())}", """ +
                """"digest": "${digest.value}"}""",
        )
        return PluginTrustSource(
            trustDatabase = LocalFileOperatorTrustDatabase(trustFile),
            keyRing = FilePublisherKeyRing(keys),
        )
    }

    private fun measuredDigestOf(jar: Path): Digest =
        requireNotNull(PluginManifestResourceReader.digestOfArtifactAt(jar)) {
            "the fixture jar's bytes could not be measured, so nothing could be decided about them"
        }

    private fun CliParseResult.parsed(): CliFlags {
        assertInstanceOf(
            CliParseResult.Parsed::class.java,
            this,
            "the request was refused instead of parsed: $this",
        )
        return (this as CliParseResult.Parsed).flags
    }

    private fun CliParseResult.rejected(): CliError {
        assertInstanceOf(
            CliParseResult.Rejected::class.java,
            this,
            "the request was parsed instead of refused: $this",
        )
        return (this as CliParseResult.Rejected).error
    }

    private fun ObservationParseResult.rejected(): CliError {
        assertInstanceOf(
            ObservationParseResult.Rejected::class.java,
            this,
            "the request was parsed instead of refused: $this",
        )
        return (this as ObservationParseResult.Rejected).error
    }
}
