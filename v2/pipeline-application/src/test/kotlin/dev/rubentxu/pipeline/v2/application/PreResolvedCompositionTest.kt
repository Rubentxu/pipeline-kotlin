package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/**
 * S6/G — the composition authority.
 *
 * ## What is under test
 *
 * `PluginComposition.resolve` is the ONE place that answers "what does this installation
 * contribute?", for Steps, Directives and Event kinds together. Before this block the question
 * had three answers in three files, one of them defaulted, and `runCanonicalPipeline` would
 * happily run a CORE-ONLY registry if its caller forgot an argument.
 *
 * ## Harness fidelity
 *
 * Rows 1 and 2 cross the production entry point `PluginComposition.resolve` with NO substitute:
 * row 1 uses the real CORE definitions, row 2 uses a real JAR on a real classloader whose
 * `META-INF/services` entry names a class that does not exist. Nothing here reimplements
 * discovery in order to check it. This is HF1 (in-process) — it does not fork the installed
 * distribution, so it characterises the composition decision and does not certify installed-
 * distribution behaviour; BLOCK 2 owns that.
 *
 * ## Non-vacuity
 *
 * Each row names the mutation that would stop it firing. The structural law — "there is exactly
 * one composition authority" — cannot be observed from inside this module, so it is pinned by
 * `FArchPreResolvedCompositionAuthorityTest` in the architecture tests instead of being asserted
 * here as a comment.
 */
class PreResolvedCompositionTest {

    // ---- the CORE-only case is a decision, not a default -------------------------------

    /**
     * MUTATION THAT KILLS THIS: dropping either `add(...)` from `coreDirectives()`. `core.when`
     * and `core.agent` would vanish from the open registry and a script using them would be
     * denied — a silent removal of core behaviour behind a refactor about composition.
     */
    @Test
    fun `no plugins resolves to the CORE set of core Steps, both core Directives and no external events`() {
        val composition = PluginComposition.resolve(pluginClassLoader = null)

        assertTrue(
            composition.steps.contains(dev.rubentxu.pipeline.v2.domain.PluginStepId("core.echo")),
            "the CORE-only composition must still carry CORE Steps",
        )
        assertEquals(
            setOf("core.when", "core.agent"),
            composition.directives.keys().map { it.value }.toSet(),
            "both CORE directives enter the SAME open registry as any vendor directive",
        )
        assertEquals(0, composition.events.size(), "no plugin means no external event kinds")
        assertEquals(emptyList<String>(), composition.discoveredStepPlugins)
        assertEquals(emptyList<String>(), composition.discoveredDirectivePlugins)
    }

    /**
     * MUTATION THAT KILLS THIS: making `reportTo` print the three lines unconditionally. The
     * CORE-only run would then claim to have discovered plugins it did not, which is the
     * diagnostic half of a lie an operator reads at startup.
     */
    @Test
    fun `a CORE-only composition reports nothing, because nothing was discovered`() {
        val lines = mutableListOf<String>()
        PluginComposition.resolve(pluginClassLoader = null).reportTo { lines += it }
        assertEquals(emptyList<String>(), lines, "no discovery means no discovery lines")
    }

    // ---- fail-closed, and no leaked classloader ----------------------------------------

    /**
     * A REAL broken JAR on a REAL classloader, not a stubbed contributor.
     *
     * The entry names a class that does not exist, so `ServiceLoader` raises
     * `ServiceConfigurationError` and `ExternalStepPluginDiscovery` wraps it. The assertion names
     * the missing class, so this row can only pass if the failure came from THIS artifact — a
     * classpath that happened to contain another broken plugin could not satisfy it.
     *
     * MUTATION THAT KILLS THIS: swallowing the discovery failure and returning a partially
     * built composition. The run would proceed with some plugins admitted and one silently
     * absent, which is the exact shape "the plugin is installed but never runs" takes.
     */
    @Test
    fun `a broken contributor aborts the whole composition, naming the broken class`(@TempDir dir: Path) {
        val missing = "dev.rubentxu.pipeline.v2.application.NoSuchContributor_9f3a2b"
        val jar = brokenContributorJar(dir, missing)
        val loader = java.net.URLClassLoader(arrayOf(jar.toUri().toURL()), javaClassLoader)

        val thrown = assertThrows(IllegalStateException::class.java) {
            PluginComposition.resolve(loader)
        }
        assertTrue(
            thrown.message.orEmpty().contains(missing),
            "the diagnostic must name the class this JAR claims to provide: ${thrown.message}",
        )
    }

    /**
     * MUTATION THAT KILLS THIS: moving the `finally` restore out of the `try`, or dropping it.
     * The process would keep running with the plugin classloader installed as its TCCL, so every
     * later `ServiceLoader` lookup — including for plugins this run never asked about — would
     * resolve against a loader it should not see. A failure that leaks loader state is worse
     * than the failure it came from, because it is invisible.
     */
    @Test
    fun `a failed composition restores the thread classloader it borrowed`(@TempDir dir: Path) {
        val jar = brokenContributorJar(dir, "dev.rubentxu.pipeline.v2.application.AlsoMissing_7c1d")
        val loader = java.net.URLClassLoader(arrayOf(jar.toUri().toURL()), javaClassLoader)
        val before = Thread.currentThread().contextClassLoader

        assertThrows(IllegalStateException::class.java) { PluginComposition.resolve(loader) }

        assertEquals(
            before,
            Thread.currentThread().contextClassLoader,
            "a throwing composition must not leave the plugin loader installed as the TCCL",
        )
    }

    /** The plain application classloader, named so the intent reads at the call site. */
    private val javaClassLoader: ClassLoader
        get() = PreResolvedCompositionTest::class.java.classLoader

    /**
     * A JAR whose only content is a `META-INF/services` entry naming [providerClass].
     *
     * Nothing is stubbed: the artifact is well-formed, its service declaration is well-formed
     * UTF-8, and the only defect is that the named class is absent. That is what a half-installed
     * or version-skewed plugin JAR actually looks like.
     */
    private fun brokenContributorJar(dir: Path, providerClass: String): Path {
        val jar = dir.resolve("broken-contributor.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            out.putNextEntry(
                JarEntry("META-INF/services/dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor"),
            )
            out.write("$providerClass\n".toByteArray())
            out.closeEntry()
        }
        return jar
    }

    @Test
    fun `the composition carries a value a caller can read before deciding to run`() {
        val composition = PluginComposition.resolve(pluginClassLoader = null)
        assertNotNull(
            composition.events,
            "a composition whose registries are nullable could be half-built by a caller",
        )
    }
}
