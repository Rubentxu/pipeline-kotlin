package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.RuntimeCapabilityContributor
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/**
 * S6/G follow-up — is the capability seam a FOURTH authority?
 *
 * ## The suspicion, and why the obvious probe would have lied
 *
 * `PluginComposition.resolve` discovers Steps, Directives and Event kinds under ONE classloader
 * swap. `ExternalCapabilityContributorDiscovery` uses `ServiceLoader` with NO swap, and is
 * evaluated as a default argument AFTER `resolve` has restored the loader. On its face that is a
 * fourth authority resolved against a different loader than the Steps it backs.
 *
 * The obvious probe — "does the http plugin's capability get discovered?" — would have answered
 * the wrong question and produced a comfortable green: `pipeline-application` has
 * `implementation(project(":pipeline-step-sdk:http"))`, so the app's own classpath already
 * carries the contributor AND its services file. Discovery succeeds regardless of the loader,
 * which hides the defect rather than disproving it.
 *
 * So this probe uses a plugin the app does NOT have. Its classes come from the parent — the
 * test's own classloader — and only its SERVICE DECLARATIONS live in a child JAR. That isolates
 * one variable: can `ServiceLoader` see a declaration that exists only below the TCCL?
 *
 * ## Why this matters
 *
 * `ExternalCapabilityContributorDiscovery`'s own KDoc records this exact failure once already:
 * `http.request` was "discovered by ServiceLoader, appears in the plugin list the CLI prints,
 * and still cannot run" because `http.transport` was never wired. A plugin supplied through
 * `--plugin-jars` rather than as a bundled dependency is in precisely that position.
 */
class PluginCapabilityLoaderAlignmentTest {

    /** The capability this plugin owns. Chosen so it cannot collide with a real one. */
    private val probeCapability = StepCapability("probe.scoped.transport")

    private val probeStepKey = PluginStepId("probe.scoped.step")

    @Test
    fun `a plugin declared only below the TCCL keeps BOTH its Step and its capability`(
        @TempDir dir: Path,
    ) {
        val jar = declaredOnlyJar(dir)
        val scoped = java.net.URLClassLoader(
            arrayOf(jar.toUri().toURL()),
            javaClassLoader,
        )
        try {
            val composition = PluginComposition.resolve(scoped)

            assertTrue(
                composition.steps.contains(probeStepKey),
                "the Step must be resolved; if this fails the probe is not measuring what it claims",
            )

            // TRANSITION, EXPLICIT. This row began as the opposite assertion:
            //
            //     assertFalse(ownsProbeCapability, "MEASURED DEFECT: ...")
            //
            // It measured a real failure — the Step was admitted into the registry while no
            // discovered contributor supplied `probe.scoped.transport`, because capability
            // discovery ran `ServiceLoader` outside the composition's classloader window.
            // `ExternalCapabilityContributorDiscovery` records the same failure for `http.request`
            // in its own KDoc, which is why it was recognised rather than rediscovered from scratch.
            //
            // It is now a NON-REGRESSION row. The expectation was not silently rewritten: the
            // defect closed when capability discovery moved into `PluginComposition.resolve`, under
            // the same swap that resolves the Step. If anyone reintroduces a fourth authority, this
            // row is what notices — and the message says so, because a reader who only sees the
            // green would otherwise have no idea a defect ever lived here.
            val ownsProbeCapability = composition.capabilityContributors.any { contributor ->
                contributor.capabilities().containsKey(probeCapability)
            }

            assertTrue(
                ownsProbeCapability,
                "REGRESSION: ${probeStepKey.value} is registered but no contributor in the " +
                    "composition supplies ${probeCapability.key}. Capability discovery has left " +
                    "the composition window again. A plugin that arrives below the TCCL is the " +
                    "only kind that can show this, which is why the probe uses one.",
            )

            // And the old path must still be genuinely blind, or the row above proves nothing:
            // if discovery under the app loader also found it, the fix would be unfalsifiable.
            val discoveredUnderAppLoader = ExternalCapabilityContributorDiscovery.discover()
            assertFalse(
                discoveredUnderAppLoader.any { it.capabilities().containsKey(probeCapability) },
                "NON-VACUITY: the app loader now finds this contributor on its own, so the row " +
                    "above no longer proves that the composition window is what made it work. " +
                    "The probe has stopped isolating its variable.",
            )
        } finally {
            scoped.close()
        }
    }

    /** A JAR carrying ONLY service declarations; the classes themselves come from the parent. */
    private fun declaredOnlyJar(dir: Path): Path {
        val jar = dir.resolve("declared-only-plugin.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            out.putNextEntry(JarEntry("META-INF/services/${StepDefinitionContributor::class.java.name}"))
            out.write("${ProbeStepContributor::class.java.name}\n".toByteArray())
            out.closeEntry()
            out.putNextEntry(JarEntry("META-INF/services/${RuntimeCapabilityContributor::class.java.name}"))
            out.write("${ProbeCapabilityContributor::class.java.name}\n".toByteArray())
            out.closeEntry()
        }
        return jar
    }

    private val javaClassLoader: ClassLoader
        get() = PluginCapabilityLoaderAlignmentTest::class.java.classLoader
}

/**
 * Declared only inside the probe JAR. Its class is visible to the parent loader, so the ONLY
 * thing the child JAR contributes is the service declaration — which is exactly the variable
 * under study.
 */
class ProbeStepContributor : StepDefinitionContributor {
    override val id: String = "probe.scoped"

    override fun definitions(): Iterable<StepDefinition<*, *>> = listOf(ProbeStepDefinition())

    private class ProbeStepDefinition : StepDefinition<String, String> {
        private val codec = object : StepCodec<String> {
            override fun encode(value: String): EncodedStepValue = EncodedStepValue(value)
            override fun decode(encoded: EncodedStepValue): String = encoded.value
        }

        override val contract: StepContract<String, String> = StepContract(
            key = PluginStepId("probe.scoped.step"),
            descriptor = StepDescriptor(
                stepId = "probe.scoped.step",
                name = "probeScoped",
                configRef = "",
            ),
            inputCodec = codec,
            outputCodec = codec,
            requiredCapabilities = setOf(StepCapability("probe.scoped.transport")),
        )

        override val handler: StepHandler<String, String> = StepHandler { input, _ -> input }
    }
}

/** Also declared only inside the probe JAR, for the same reason. */
class ProbeCapabilityContributor : RuntimeCapabilityContributor {
    override fun capabilities(): Map<StepCapability, Any> =
        mapOf(StepCapability("probe.scoped.transport") to Any())
}
