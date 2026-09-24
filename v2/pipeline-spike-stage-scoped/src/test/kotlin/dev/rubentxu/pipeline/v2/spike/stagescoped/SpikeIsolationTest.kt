package dev.rubentxu.pipeline.v2.spike.stagescoped

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

/**
 * L4-style isolation test for the spike module.
 *
 * The PLAN promised that this module MUST NOT import:
 *  - `:pipeline-application` (canonical runtime entry point)
 *  - `:pipeline-scripting-kotlin24` (Kotlin scripting host)
 *
 * We assert that at TWO levels:
 *  1. **Source/compile-time**: this test class's package is the only one
 *     the module declares; if anything imports the forbidden packages, the
 *     compile step has already failed.
 *  2. **Runtime classpath**: the JVM classloader in which these tests run
 *     carries the resolved module graph. We assert that none of the
 *     forbidden classes is reachable.
 *
 * The forbidden-class probe is the one that catches the "leaked transitive
 * dependency" failure mode — where someone adds `:pipeline-application`
 * to the spike module's deps and the compile still passes (because nobody
 * references the new package directly).
 */
class SpikeIsolationTest {

    private val forbiddenPackages = listOf(
        // Canonical runtime entry point. If this class is loadable, the
        // spike has gained transitive access to the full CLI/runtime stack.
        "dev.rubentxu.pipeline.v2.application.MainKt",
        "dev.rubentxu.pipeline.v2.application.Main",
        // Kotlin scripting host. Anything that pulls this in has wired
        // the spike to the scripted-frontend path.
        "dev.rubentxu.pipeline.v2.scripting.kotlin24.PipelineScriptingHost",
        // The coordinator itself. The spike must never reach this.
        "dev.rubentxu.pipeline.v2.application.coordinator.CanonicalDurableRunCoordinator",
    )

    private val forbiddenProjectClasspathMarkers = listOf(
        // These show up as resource/jar markers when a forbidden project
        // is on the resolved classpath.
        "pipeline-application",
        "pipeline-scripting-kotlin24",
    )

    @Test
    @DisplayName("runtime classpath does NOT expose any canonical runtime entry point")
    fun forbiddenClassesAreNotOnClasspath() {
        val cl = ClassLoader.getSystemClassLoader()
        for (fqn in forbiddenPackages) {
            val reachable = try {
                Class.forName(fqn, false, cl)
                true
            } catch (_: ClassNotFoundException) {
                false
            }
            assertFalse(reachable, "forbidden class $fqn is reachable from the spike classpath")
        }
    }

    @Test
    @DisplayName("test classpath does NOT expose forbidden project classpath markers")
    fun forbiddenProjectMarkersAreNotOnClasspath() {
        val cl = ClassLoader.getSystemClassLoader()
        // java.class.path is the canonical Gradle/JUnit property and is
        // populated for every JVM the test worker spawns. We assert no
        // forbidden marker appears as a *path* (with separator or .jar).
        val cp = System.getProperty("java.class.path") ?: ""
        for (marker in forbiddenProjectClasspathMarkers) {
            val needle = "$marker" // exact segment, not a substring match
            val hit = cp.split(File.pathSeparatorChar).any { segment ->
                segment.endsWith("$needle.jar") || segment.contains("$needle${File.separator}")
            }
            assertFalse(
                hit,
                "forbidden project marker '$needle' is on the spike classpath: $cp",
            )
        }
        // Positive sanity: the modules we DO depend on are visible.
        assertTrue(
            Class.forName("dev.rubentxu.pipeline.v2.dsl.StepSpec", false, cl) != null,
            "expected :pipeline-scripting-api StepSpec to be on the spike classpath",
        )
    }
}
