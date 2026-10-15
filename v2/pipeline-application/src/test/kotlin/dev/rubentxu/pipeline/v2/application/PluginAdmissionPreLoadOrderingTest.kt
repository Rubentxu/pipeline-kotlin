package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs
import dev.rubentxu.pipeline.v2.domain.step.ArtifactOrigin
import dev.rubentxu.pipeline.v2.domain.step.Delivery
import dev.rubentxu.pipeline.v2.domain.step.Digest
import dev.rubentxu.pipeline.v2.domain.step.ManifestSchemaVersion
import dev.rubentxu.pipeline.v2.domain.step.MeasuredArtifactIdentity
import dev.rubentxu.pipeline.v2.domain.step.PipelineKApiRange
import dev.rubentxu.pipeline.v2.domain.step.PluginAdmission
import dev.rubentxu.pipeline.v2.domain.step.PluginAdmissionResult
import dev.rubentxu.pipeline.v2.domain.step.PluginContributions
import dev.rubentxu.pipeline.v2.domain.step.PluginFamily
import dev.rubentxu.pipeline.v2.domain.step.PluginManifest
import dev.rubentxu.pipeline.v2.domain.step.PluginManifestCodec
import dev.rubentxu.pipeline.v2.domain.step.PluginManifestDecodeResult
import dev.rubentxu.pipeline.v2.domain.step.PluginManifestRejection
import dev.rubentxu.pipeline.v2.domain.step.PluginReleaseRef
import dev.rubentxu.pipeline.v2.domain.step.PluginStepContribution
import dev.rubentxu.pipeline.v2.domain.step.SemVer
import dev.rubentxu.pipeline.v2.domain.step.TrustMetadata
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * S6/D — the ORDERING claim: a plugin refused by admission must never have run its code.
 *
 * ## What is actually under test
 *
 * Not "the manifest was refused" — a claim about a returned value. The claim is that nothing
 * in the refusal path caused plugin code to execute. That is invisible unless something
 * observes initialisation, so the fixture is a real JAR whose class static initialiser writes
 * a sentinel file, loaded by a classloader that cannot see any test class.
 *
 * The assertion that carries the guarantee is the negative one. A test asserting only the
 * refusal would still pass if the provider had already run before the decision — which is
 * exactly the inversion BLOCK 1-D removes.
 *
 * ## Non-vacuity
 *
 * [sentinelIsWrittenWhenThePluginDoesRun] is the control that makes the other assertion mean
 * something: it proves the same mechanism, through the same gate, DOES produce the file when
 * the plugin is admitted. Without it, "no sentinel" could simply mean the mechanism never
 * fires. This control is not decoration — an earlier harness version passed with a sentinel
 * that nothing could ever write.
 *
 * ## Fidelity
 *
 * HF1 in-process, but over REAL artifacts and REAL classloading, not mocks. A mock of
 * [PluginAdmission] would certify the mock. The installed-distribution proof of the same
 * ordering belongs to BLOCK 2 and is not claimed here.
 */
@DisplayName("S6/D admission decides BEFORE plugin code is initialised")
class PluginAdmissionPreLoadOrderingTest {

    @TempDir
    lateinit var tempDir: Path

    private val runtimeVersion = SemVer(0, 47, 0)

    private fun manifestText(
        apiRange: PipelineKApiRange = PipelineKApiRange(SemVer(0, 47, 0), SemVer(0, 49, 0)),
        identity: String = "sentinel",
    ): String {
        val plugin = ResourceRefs.plugin("test", identity)
        val manifest = PluginManifest(
            schemaVersion = ManifestSchemaVersion.CURRENT,
            plugin = plugin,
            release = PluginReleaseRef(plugin, SemVer(0, 1, 0), Digest("sha256:" + "a".repeat(64))),
            apiRange = apiRange,
            publisher = "test",
            families = setOf(PluginFamily.TESTING),
            delivery = Delivery.EXTERNAL_REFERENCE,
            trust = TrustMetadata.Unverified(),
            // Declares a capability and NO Steps, because the fixture genuinely provides no
            // Step. It used to declare `sentinel.step` and implement nothing, which BLOCK 1-E
            // correctly caught as drift — the cross-check found a real inconsistency in a test
            // fixture that had been passing precisely because nothing compared it.
            contributions = PluginContributions(
                capabilities = setOf(dev.rubentxu.pipeline.v2.domain.step.StepCapability("test.sentinel")),
            ),
        )
        return PluginManifestCodec.encode(manifest)
    }

    @Test
    @DisplayName("NON-VACUITY: admitted -> the plugin is initialised and the sentinel DOES appear")
    fun sentinelIsWrittenWhenThePluginDoesRun() {
        val sentinel = tempDir.resolve("positive-control.sentinel")
        val jar = SentinelPluginArtifact.build(tempDir, manifestText(), sentinel)

        val loader = SentinelPluginArtifact.loaderFor(jar)
        try {
            val result = PluginAdmissionGate.admitThenLoad(
                contributorClassName = SentinelPluginArtifact.PLUGIN_CLASS_NAME,
                classLoader = loader,
                runtimeVersion = runtimeVersion,
                alreadyAdmitted = emptySet(),
                strict = true,
            )

            assertTrue(
                result is PluginAdmissionResult.Admitted,
                "a valid manifest on a healthy artifact must be admitted, else the negative case proves " +
                    "nothing. Got $result",
            )
            assertTrue(
                Files.exists(sentinel),
                "admitted means the plugin WAS initialised, so the sentinel must exist. Its absence " +
                    "means the observation mechanism is broken and the negative case below is vacuous",
            )
        } finally {
            loader.close()
        }
    }

    @Test
    @DisplayName("a refused plugin leaves NO sentinel: admission ran before the code")
    fun refusedPluginLeavesNoSentinel() {
        val sentinel = tempDir.resolve("refused.sentinel")
        // The declared range excludes the running 0.47.0, so admission MUST refuse.
        val jar = SentinelPluginArtifact.build(
            tempDir,
            manifestText(apiRange = PipelineKApiRange(SemVer(0, 60, 0), SemVer(0, 70, 0))),
            sentinel,
        )

        val loader = SentinelPluginArtifact.loaderFor(jar)
        try {
            val result = PluginAdmissionGate.admitThenLoad(
                contributorClassName = SentinelPluginArtifact.PLUGIN_CLASS_NAME,
                classLoader = loader,
                runtimeVersion = runtimeVersion,
                alreadyAdmitted = emptySet(),
                strict = true,
            )

            val rejection = (result as PluginAdmissionResult.Refused).rejection
            assertTrue(
                rejection is PluginManifestRejection.IncompatibleApiRange,
                "expected IncompatibleApiRange, got $rejection",
            )
            assertFalse(
                Files.exists(sentinel),
                "the plugin was refused, yet its code ran: admission is NOT happening before class " +
                    "initialisation, which is the defect this block exists to remove",
            )
        } finally {
            loader.close()
        }
    }

    @Test
    @DisplayName("a malformed manifest is refused with a TYPED reason, never an exception")
    fun malformedManifestYieldsTypedRefusal() {
        val sentinel = tempDir.resolve("malformed.sentinel")
        val jar = SentinelPluginArtifact.build(tempDir, "{ this is not a manifest", sentinel)

        val loader = SentinelPluginArtifact.loaderFor(jar)
        try {
            val result = PluginAdmissionGate.admitThenLoad(
                contributorClassName = SentinelPluginArtifact.PLUGIN_CLASS_NAME,
                classLoader = loader,
                runtimeVersion = runtimeVersion,
                alreadyAdmitted = emptySet(),
                strict = true,
            )

            val rejection = (result as PluginAdmissionResult.Refused).rejection
            assertTrue(
                rejection is PluginManifestRejection.MalformedDocument,
                "expected MalformedDocument, got $rejection",
            )
            assertFalse(
                Files.exists(sentinel),
                "a manifest that cannot be read must not lead to running the code it fails to describe",
            )
        } finally {
            loader.close()
        }
    }

    @Test
    @DisplayName("an artifact that declares NOTHING is refused, not admitted on the strength of its class")
    fun artifactWithoutManifestIsRefused() {
        val loader = SentinelPluginArtifact.emptyArtifactLoader(tempDir)
        try {
            val result = PluginAdmissionGate.admitThenLoad(
                contributorClassName = SentinelPluginArtifact.PLUGIN_CLASS_NAME,
                classLoader = loader,
                runtimeVersion = runtimeVersion,
                alreadyAdmitted = emptySet(),
                strict = true,
            )

            assertTrue(
                result is PluginAdmissionResult.Refused,
                "a plugin that declares nothing must be refused; admitting it on the evidence that its " +
                    "class loads is exactly the trust ADR-EVO-003 refuses. Got $result",
            )
        } finally {
            loader.close()
        }
    }

    @Test
    @DisplayName("B3.2: a refused plugin's control row is on disk BEFORE the gate returns")
    fun admissionControlRowIsPersistedBeforeSecurity() {
        // The control row is the durable fact the operator (and a future re-run) needs
        // to read. The row must be on disk before the gate returns the refusal; a
        // refused plugin that left the row only in memory would defeat the whole
        // point of writing it.
        //
        // The fixture is a JAR that holds the Sentinel class but NO manifest at
        // PluginManifestCodec.RESOURCE_PATH, so the gate reaches phase 2 (manifest read)
        // and refuses with RefusedNoManifest. An empty-jar fixture (the existing
        // emptyArtifactLoader) would fail earlier in phase 1 (Class.forName), before
        // the control row gets a chance to be written.
        val controlRoot = tempDir.resolve("control-${System.nanoTime()}")
        val controlJournal = dev.rubentxu.pipeline.v2.application.durable.AdmissionControlJournal(controlRoot)
        val jarWithClassNoManifest = buildJarWithClassNoManifest(tempDir)
        val loader = SentinelPluginArtifact.loaderFor(jarWithClassNoManifest)
        try {
            val result = PluginAdmissionGate.admitThenLoad(
                contributorClassName = SentinelPluginArtifact.PLUGIN_CLASS_NAME,
                classLoader = loader,
                runtimeVersion = runtimeVersion,
                alreadyAdmitted = emptySet(),
                strict = true,
                // B3.2 — pass an empty in-memory trust database. The 4 shipped plugin
                // manifests are not in it, so any real artifact would be refused with
                // RefusedUntrustedIdentity. The no-manifest test below refuses with
                // RefusedNoManifest because the JAR has no manifest at all.
                trustDatabase = InMemoryOperatorTrustDatabase(),
                controlJournal = controlJournal,
            )

            assertTrue(
                result is PluginAdmissionResult.Refused,
                "a no-manifest artifact MUST be refused even with the B3.2 trust layer on; got $result",
            )

            val pluginId = SentinelPluginArtifact.PLUGIN_CLASS_NAME
            val row = controlJournal.readRow(pluginId)
            assertTrue(
                row != null,
                "control row MUST be persisted before the gate returns the refusal; " +
                    "absent row means the gate's pre-load ordering broke under B3.2",
            )
            val r = row!!
            assertEquals(pluginId, r.pluginId, "row's pluginId MUST match the contributor's binary name")
            assertEquals("RefusedNoManifest", r.refusedKind, "row MUST name the first-level refusal case")
        } finally {
            loader.close()
        }
    }

    /**
     * Build a JAR that contains the [SentinelPluginArtifact.PLUGIN_CLASS_NAME] class
     * but no manifest at [dev.rubentxu.pipeline.v2.domain.step.PluginManifestCodec.RESOURCE_PATH].
     *
     * Same compile-from-source pattern as [SentinelPluginArtifact.build], but the JAR
     * omits the manifest entry so phase 2 of the gate sees a missing manifest and
     * refuses with [dev.rubentxu.pipeline.v2.domain.step.AdmissionRefusal.RefusedNoManifest].
     */
    private fun buildJarWithClassNoManifest(jarDir: Path): Path {
        val workDir = Files.createDirectories(jarDir.resolve("work-${System.nanoTime()}"))
        val sourceDir = Files.createDirectories(workDir.resolve("src/pipelinek/test/sentinel"))
        val classesDir = Files.createDirectories(workDir.resolve("classes"))
        val sourceFile = sourceDir.resolve("SentinelPlugin.java")

        // A minimal Sentinel that does NOT need a sentinel-target resource. The plugin
        // never actually runs (the gate refuses before initialize=true), so the static
        // initialiser does not execute.
        Files.writeString(
            sourceFile,
            """
            package pipelinek.test.sentinel;
            public final class SentinelPlugin {
                private SentinelPlugin() {}
            }
            """.trimIndent(),
        )

        val compiler = javax.tools.ToolProvider.getSystemJavaCompiler()
            ?: error("no system Java compiler available")
        val exit = compiler.run(null, null, null, "-d", classesDir.toString(), sourceFile.toString())
        check(exit == 0) { "compile failed (exit $exit)" }

        val classFile = classesDir.resolve("pipelinek/test/sentinel/SentinelPlugin.class")
        check(Files.exists(classFile)) { "compiled class missing at $classFile" }

        val jar = jarDir.resolve("class-no-manifest-${System.nanoTime()}.jar")
        java.util.jar.JarOutputStream(Files.newOutputStream(jar)).use { out ->
            out.putNextEntry(java.util.jar.JarEntry("pipelinek/test/sentinel/SentinelPlugin.class"))
            out.write(Files.readAllBytes(classFile))
            out.closeEntry()
        }
        return jar
    }

    @Test
    @DisplayName("a duplicate plugin identity names BOTH the newcomer and the incumbent")
    fun duplicateIdentityNamesBothParties() {
        val decoded = PluginManifestCodec.decode(manifestText())
        val manifest = (decoded as PluginManifestDecodeResult.Accepted).manifest
        val identityText = manifest.plugin.canonicalText()

        val result = PluginAdmission.admit(
            decoded = decoded,
            identity = MeasuredArtifactIdentity(
                declaredDigest = manifest.release.digest,
                measuredDigest = null,
                origin = ArtifactOrigin.LocalClasspathEntry("test"),
            ),
            runtimeVersion = runtimeVersion,
            alreadyAdmitted = setOf(identityText),
        )

        val duplicate = (result as PluginAdmissionResult.Refused).rejection
            as PluginManifestRejection.DuplicateIdentity
        assertEquals(identityText, duplicate.identity)
        assertEquals(identityText, duplicate.incumbent)
    }
}
