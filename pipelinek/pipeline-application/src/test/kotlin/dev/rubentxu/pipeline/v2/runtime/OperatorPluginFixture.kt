package dev.rubentxu.pipeline.v2.runtime

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.identity.ResourceKind
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRef
import dev.rubentxu.pipeline.v2.domain.step.Delivery
import dev.rubentxu.pipeline.v2.domain.step.Digest
import dev.rubentxu.pipeline.v2.domain.step.ManifestSchemaVersion
import dev.rubentxu.pipeline.v2.domain.step.PipelineKApiRange
import dev.rubentxu.pipeline.v2.domain.step.PluginContributions
import dev.rubentxu.pipeline.v2.domain.step.PluginFamily
import dev.rubentxu.pipeline.v2.domain.step.PluginManifest
import dev.rubentxu.pipeline.v2.domain.step.PluginManifestCodec
import dev.rubentxu.pipeline.v2.domain.step.PluginReleaseRef
import dev.rubentxu.pipeline.v2.domain.step.PluginStepContribution
import dev.rubentxu.pipeline.v2.domain.step.SemVer
import dev.rubentxu.pipeline.v2.domain.step.TrustMetadata
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/**
 * ## WHY THIS FILE IS A COPY
 *
 * The engine's own unit tests keep the same harness in
 * `pipeline-runtime/src/test`, and the engine's own unit tests cannot reach it: a test source
 * set is not on another module's classpath, and there is no shared test
 * module to hang it on. The harness is copied rather than moved because
 * moving it would take the engine's unit tests' instrument away from them.
 *
 * The plugin an operator brings, as the rows that need one build it.
 *
 * ## Why the manifest is written by the product's own codec
 *
 * A hand-written manifest would be a second authority about what a plugin declares, and a
 * fixture that agrees with the reader it tests proves nothing about it. The codec that
 * encodes the manifest is the one that decodes it, so a row that builds a manifest with it
 * exercises the same grammar the runtime reads.
 *
 * ## What the fixture declares
 *
 * One Step, one family, one publisher, and a compatibility range wide enough for any runtime
 * the test lane runs on. The rows are about what the runtime does with the artifact, not
 * about what the plugin does with its Step, so the declaration is the smallest one the
 * manifest type accepts.
 */
internal object OperatorPluginFixture {

    /** The identity the fixture plugin declares, as the runtime prints it. */
    const val PLUGIN_ID: String = "v1:plugin:example/operator-plugin/thing"

    /** The release the fixture plugin declares. */
    const val RELEASE_VERSION: String = "1.0.0"

    /** The publisher the fixture plugin declares. */
    const val PUBLISHER: String = "example"

    /** The key the operator's trust row names. */
    const val KEY_ID: String = "example-key"

    /** A version every fixture's declared range admits. */
    val RUNTIME_VERSION: SemVer = SemVer(0, 92, 0)

    /**
     * The manifest the fixture plugin declares.
     *
     * @param thing the last segment of the plugin's identity and the name of its Step. Two
     *   fixtures with different [thing]s are two different plugins; [PLUGIN_ID] is the one
     *   named `thing`.
     */
    fun manifest(thing: String = "thing"): PluginManifest {
        val identity = ResourceRef(ResourceKind.PLUGIN, listOf("example", "operator-plugin", thing))
        return PluginManifest(
            schemaVersion = ManifestSchemaVersion.CURRENT,
            plugin = identity,
            release = PluginReleaseRef(
                plugin = identity,
                version = SemVer(1, 0, 0),
                digest = Digest("sha256:" + "0".repeat(64)),
            ),
            apiRange = PipelineKApiRange(SemVer(0, 0, 0), SemVer(99, 0, 0)),
            publisher = PUBLISHER,
            families = setOf(PluginFamily.UTILITIES),
            delivery = Delivery.EXTERNAL_REFERENCE,
            trust = TrustMetadata.Unverified(),
            contributions = PluginContributions(
                steps = listOf(PluginStepContribution(PluginStepId("example.$thing"), emptySet())),
            ),
        )
    }

    /**
     * A REAL plugin artifact: a JAR carrying a manifest the product's own codec wrote.
     *
     * @param into the directory the JAR is written into.
     * @param name the file name of the JAR. The extension matters: the classloader is given a
     *   file URL, and the JDK reads a file URL as a JAR because the file is not a directory.
     * @param thing which plugin the JAR carries: see [manifest].
     */
    fun jar(into: Path, name: String = "operator-plugin.jar", thing: String = "thing"): Path {
        val jar = into.resolve(name)
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            out.putNextEntry(JarEntry(PluginManifestCodec.RESOURCE_PATH))
            out.write(PluginManifestCodec.encode(manifest(thing)).toByteArray(Charsets.UTF_8))
            out.closeEntry()
        }
        return jar
    }

    /**
     * An operator trust file that APPROVES [jar], with a real publisher signature.
     *
     * The trust file names the plugin, the release, the digest of [jar]'s bytes and the key. The
     * public key is written into `keys/` beside the trust file, where the runtime's keyring looks
     * for it, and the publisher's detached signature is written beside [jar]. All three are real
     * files, because the decision is about real bytes and a fixture that faked any of them would
     * prove nothing about the decision.
     *
     * @param jar the artifact to approve. Its bytes are measured here, so it must not change after.
     * @param into the directory the trust file and its `keys/` directory are written into.
     * @return the trust file.
     */
    fun approvingTrustFile(jar: Path, into: Path): Path {
        val digest = requireNotNull(PluginManifestResourceReader.digestOfArtifactAt(jar)) {
            "the fixture jar's bytes could not be measured, so nothing could be decided about them"
        }
        val keys = Files.createDirectories(into.resolve("keys"))
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        Files.writeString(keys.resolve("$KEY_ID.pub"), Base64.getEncoder().encodeToString(pair.public.encoded))
        val trustFile = into.resolve("trust.json")
        Files.writeString(
            trustFile,
            """{"$PLUGIN_ID@$RELEASE_VERSION": """ +
                """{"digest": "${digest.value}", "publisher": "$PUBLISHER", """ +
                """"signed": true, "signedBy": "$KEY_ID"}}""",
        )
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(pair.private)
        signer.update(requireNotNull(digest.rawBytes()) { "the digest is not a SHA-256 this fixture can sign over" })
        Files.writeString(
            PluginArtifactSignatureCodec.sidecarOf(jar),
            """{"keyId": "$KEY_ID", """ +
                """"algorithm": "${PluginArtifactSignatureCodec.ALGORITHM}", """ +
                """"signature": "${Base64.getEncoder().encodeToString(signer.sign())}", """ +
                """"digest": "${digest.value}"}""",
        )
        return trustFile
    }
}
