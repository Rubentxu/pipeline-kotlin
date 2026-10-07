package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * S6/H — there is no compiler layer that synthesizes a second Step-metadata authority.
 *
 * ## What this replaces, and why the replacement is not "the same law, new location"
 *
 * `StepDescriptorGeneratorNoNameSemanticsFitnessTest` banned a name-keyed `when` inside the KSP
 * processor. That was a real defect — the processor branched on `"echo"`, `"sh"`, `"error"` and
 * `"sleep"` to pick `ExecutionLocation`, `Effect` and `ReplayPolicy` — and BLOCK 1-A removed it.
 *
 * The ban survived the fix but the thing it guarded did not deserve to survive at all. Measured:
 *
 * - `GeneratedStepDescriptors.all` had **zero consumers** anywhere in the repository.
 * - The emitted descriptor **contradicted** the real authority. For `core.echo` the real
 *   `StepDefinition.contract` declares `requiredCapabilities = setOf(EVENT_SINK_CAPABILITY)`; the
 *   generated file said `requiredCapabilities = emptyList()`, because the processor hard-coded it
 *   for every Step it ever processed.
 * - Two further values were **invented** rather than transported: `configRef = "$id.config"`
 *   (the real descriptor carries `""`) and `jenkinsSurface = "echo|workflow-durable-task-step|F3"`,
 *   a wire format the processor chose, with an `F` prefix nobody declared.
 *
 * `CoreShSingleAdmissionAuthorityTest` had already diagnosed this exact shape for `core.sh` — "KSP
 * turned it into a descriptor with `requiredCapabilities = emptyList()` where the canonical
 * descriptor demands `SHELL_OPERATIONS_CAPABILITY`" — and fixed it by removing the duplicate
 * *declaration*. The KSP that manufactured the wrong descriptor stayed.
 *
 * So the defect was never the duplicate `@Step`. It was that the processor emitted an empty
 * capability set unconditionally. Removing the processor removes the whole class.
 *
 * ## Why the law is now about ABSENCE
 *
 * A ban on a `when` inside a file that no longer exists is satisfied by an empty scan — the
 * greenest possible nothing. This file instead asserts that the things are gone, and says
 * explicitly what would bring them back.
 */
@DisplayName("S6/H — Step metadata has exactly one authority: StepDefinition.contract")
class NoSecondStepMetadataAuthorityFitnessTest {

    private val v2: Path = FitnessPaths.v2Root()

    private fun Path.relativeToV2(): String =
        toString().removePrefix("$v2/").removePrefix("$v2\\")

    private fun kotlinSources(root: Path): List<Path> =
        Files.walk(root).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
                .filter { !it.toString().contains("/build/") }
                .toList()
        }

    /** Production sources only — this is a law about the product, not about its tests. */
    private fun productionSources(): List<Path> =
        kotlinSources(v2).filter { it.toString().contains("/src/main/") }

    /** Strip prose so a KDoc sentence naming the removed generator is not a use of it. */
    private fun codeOf(line: String): String {
        val trimmed = line.trimStart()
        if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) return ""
        return line.substringBefore("//")
    }

    /**
     * NON-VACUITY. Without this row every assertion below is satisfied by scanning nothing,
     * which is the exact failure mode a source-scan fitness is most vulnerable to.
     */
    @Test
    fun `the scan actually reaches production sources`() {
        val sources = productionSources()
        assertTrue(
            sources.size > 200,
            "NON-VACUITY: the walk found ${sources.size} production Kotlin files, which is too few " +
                "to conclude that a second metadata authority is absent.",
        )
    }

    /**
     * MUTATION THAT KILLS THIS: re-adding a module that builds Step descriptors from Step
     * identity — the KSP that was removed, or any replacement that writes them from an
     * annotation rather than reading `StepDefinition.contract`.
     */
    @Test
    fun `no production source synthesizes a StepDescriptor list`() {
        val offenders = productionSources()
            .filter { source ->
                Files.readAllLines(source).any { codeOf(it).contains("GeneratedStepDescriptors") }
            }
            .map { it.relativeToV2() }

        assertEquals(
            emptyList<String>(),
            offenders,
            "A Step's declared metadata lives in StepDefinition.contract, which the runtime " +
                "resolves from. A generated object listing StepDescriptors would be a second " +
                "authority for the same facts, and the one that was removed had already been " +
                "observed to disagree with the real one on requiredCapabilities.",
        )
    }

    /**
     * MUTATION THAT KILLS THIS: wiring `ksp(...)` back into a build script, or restoring the
     * `@Step` / `@JenkinsSurface` annotations, which are the only things that could feed such a
     * generator.
     *
     * Line comments are stripped before matching, because the honest way to record what was
     * removed here is to write a comment naming it — and a scan that made that a build failure
     * would forbid the explanation it exists to preserve.
     */
    @Test
    fun `no build applies a KSP processor for Step metadata`() {
        val offenders = Files.walk(v2).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".gradle.kts") }
                .filter { !it.toString().contains("/build/") }
                .filter { source ->
                    Files.readAllLines(source).any { line ->
                        codeOf(line).contains("com.google.devtools.ksp")
                    }
                }
                .map { v2.relativize(it).toString() }
                .toList()
        }

        assertEquals(
            emptyList<String>(),
            offenders,
            "No KSP processor is applied for Step metadata. Bringing one back would reintroduce " +
                "the second authority this block removed.",
        )
    }

    @Test
    fun `no production source declares a Step through an annotation`() {
        val offenders = productionSources()
            .filter { source ->
                Files.readAllLines(source).any { code ->
                    val c = codeOf(code)
                    c.startsWith("@Step(") || c.startsWith("@JenkinsSurface(")
                }
            }
            .map { it.relativeToV2() }

        assertEquals(
            emptyList<String>(),
            offenders,
            "The @Step / @JenkinsSurface annotations had exactly one reader — the KSP that " +
                "produced an unread file contradicting StepDefinition.contract. With no reader " +
                "they would be a declaration surface that declares nothing, which is worse than " +
                "no surface at all.",
        )
    }

    /**
     * The positive half: the authority that replaced them is still there and still declares
     * capabilities. A "fix" that removed the annotations AND dropped a requirement would pass
     * every absence row above.
     *
     * MUTATION THAT KILLS THIS: emptying `requiredCapabilities` on any Step contract.
     */
    @Test
    fun `core echo still declares the capability its handler reads`() {
        val echo = v2.resolve(
            "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreEchoStep.kt",
        )
        assertTrue(Files.isRegularFile(echo), "NON-VACUITY: CoreEchoStep.kt is missing")

        val text = Files.readString(echo)
        assertTrue(
            text.contains("requiredCapabilities = setOf(EVENT_SINK_CAPABILITY)"),
            "core.echo's contract must still declare the event-sink capability. The removed " +
                "generator claimed emptyList() for it; if the real one ever does the same, the " +
                "absence rows above would still pass.",
        )
    }
}
