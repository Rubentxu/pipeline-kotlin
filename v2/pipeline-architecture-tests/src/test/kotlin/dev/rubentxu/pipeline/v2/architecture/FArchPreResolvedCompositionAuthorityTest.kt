package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * S6/G — there is exactly ONE composition authority.
 *
 * ## The law
 *
 * A run must have a complete, frozen composition or none. Composition used to be three
 * independent operations in three files — Steps in `Main.kt` (twice, once per branch),
 * Events in `CompositionRoot`, Directives in `CompositionRoot` — under three separate classloader
 * swaps. Any of them could throw after the others had already been published, and
 * `runCanonicalPipeline` defaulted its `stepRegistry` to a CORE-ONLY value, so a caller who
 * forgot an argument got a running pipeline that silently ignored every plugin in the
 * installation.
 *
 * None of that is observable from inside a module: a behavioural test can only see one call. The
 * property "exactly one authority" is a property of the TREE, so it is pinned here.
 *
 * ## Non-vacuity
 *
 * Row 1 asserts the scan actually saw the production sources. Without it, every "exactly one"
 * assertion below would be satisfied by an empty scan — which is the failure mode a fitness
 * test is most vulnerable to, and the reason this file states it out loud.
 */
class FArchPreResolvedCompositionAuthorityTest {

    private val authority =
        "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/PreResolvedComposition.kt"

    private val compositionRoot =
        "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CompositionRoot.kt"

    /** Strip prose so a KDoc sentence naming a discovery is not a CALL to it. */
    private fun codeOf(line: String): String {
        val trimmed = line.trimStart()
        if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) return ""
        return line.substringBefore("//")
    }

    private fun productionSources(): List<Path> =
        ScannerSupport.walkKotlinFiles(ScannerSupport.v2Root())
            .filter { it.toString().contains("${File.separator}src${File.separator}main${File.separator}") }

    /** Production files whose CODE (not prose) calls [needle]. */
    private fun callersOf(needle: String): List<String> =
        productionSources().filter { path ->
            Files.readAllLines(path).any { codeOf(it).contains(needle) }
        }.map { ScannerSupport.v2Root().relativize(it).toString() }

    /**
     * NON-VACUITY. If the walk stops finding production sources, every assertion below becomes
     * "the set of offenders is empty" for the wrong reason — the shape of a green that proves
     * nothing, which is worse than a red.
     */
    @Test
    fun `the scan actually reaches production sources`() {
        val sources = productionSources()
        assertTrue(
            sources.size > 100,
            "NON-VACUITY: the walk found ${sources.size} production Kotlin files, which is too few " +
                "to reason about composition authorities with.",
        )
        assertTrue(
            ScannerSupport.v2Root().resolve(authority).let(Files::isRegularFile),
            "NON-VACUITY: $authority does not exist, so 'exactly one authority' would pass vacuously.",
        )
    }

    /**
     * MUTATION THAT KILLS THIS: adding a second `ExternalStepPluginDiscovery.registerInto` call
     * anywhere in production — the exact shape `Main.kt` carried twice before this block.
     */
    @Test
    fun `Step plugin discovery has exactly one production caller, and it is the authority`() {
        assertEquals(listOf(authority), callersOf("ExternalStepPluginDiscovery.registerInto"))
    }

    @Test
    fun `Directive plugin discovery has exactly one production caller, and it is the authority`() {
        assertEquals(listOf(authority), callersOf("ExternalDirectivePluginDiscovery.registerInto"))
    }

    @Test
    fun `Event definition composition has exactly one production caller, and it is the authority`() {
        assertEquals(listOf(authority), callersOf("ExternalEventDefinitionDiscovery.compose"))
    }

    @Test
    fun `the Directive registry has exactly one production construction site`() {
        assertEquals(
            listOf(authority),
            callersOf("DirectiveRegistry.Builder()"),
            "the directive registry must be built by the composition authority and nowhere else",
        )
    }

    /**
     * MUTATION THAT KILLS THIS: reintroducing any composition call into `CompositionRoot.kt`.
     * This is the file that previously composed events and directives in its own body, under its
     * own classloader swap, after the Step registry had already been handed to it.
     */
    @Test
    fun `the composition root composes nothing`() {
        val root = ScannerSupport.v2Root().resolve(compositionRoot)
        val offenders = Files.readAllLines(root)
            .mapIndexed { index, raw -> index + 1 to codeOf(raw) }
            .filter { (_, code) ->
                COMPOSITION_MARKERS.any { code.contains(it) }
            }

        assertEquals(
            emptyList<Any>(),
            offenders,
            "CompositionRoot must consume a pre-resolved composition, not build one. Offending " +
                "lines: " + offenders.joinToString { "line ${it.first}" },
        )
    }

    /**
     * MUTATION THAT KILLS THIS: writing `composition: PreResolvedComposition = <anything>`.
     * Restoring a default here re-opens the fail-open path: a caller who omits it gets a run
     * that looks healthy and silently ignores every external plugin.
     */
    @Test
    fun `runCanonicalPipeline REQUIRES a pre-resolved composition, with no default`() {
        val root = ScannerSupport.v2Root().resolve(compositionRoot)
        val declaration = Files.readAllLines(root)
            .map { codeOf(it) }
            .firstOrNull { it.contains("composition: PreResolvedComposition") }

        assertTrue(
            declaration != null,
            "NON-VACUITY: runCanonicalPipeline no longer declares a PreResolvedComposition",
        )
        assertEquals(
            "composition: PreResolvedComposition,",
            declaration!!.trim(),
            "the parameter must have NO default value; a default here is the fail-open path",
        )
    }

    private companion object {
        val COMPOSITION_MARKERS = listOf(
            "ExternalStepPluginDiscovery",
            "ExternalDirectivePluginDiscovery",
            "ExternalEventDefinitionDiscovery",
            "DirectiveRegistry.Builder()",
            "CoreStepRegistryFactory",
        )
    }
}
