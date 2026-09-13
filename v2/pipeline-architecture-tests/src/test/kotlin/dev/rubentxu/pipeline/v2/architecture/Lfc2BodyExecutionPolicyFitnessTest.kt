package dev.rubentxu.pipeline.v2.architecture

import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.StepDescriptorRegistry
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionPolicy
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionSupport
import dev.rubentxu.pipeline.v2.domain.step.BodyPolicyResolution
import dev.rubentxu.pipeline.v2.domain.step.resolveBodyExecutionPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * LFC-2 / B10 W1b — the body execution policy is a declared, typed, registry-resolved
 * property of a Step family, and the vocabulary in which it is stated names execution
 * SHAPES, never Steps.
 *
 * These laws are the mechanical half of the W1b exit criteria:
 *
 *  - the decision layer is pure and inner (no application, no coroutines, no I/O);
 *  - the policy vocabulary carries no StepKey literal;
 *  - the real descriptor registry is coherent under the declared policy;
 *  - the backward-compatible default is total and sequential;
 *  - W1b creates the mechanism only: the coordinator is untouched (W1c migrates).
 */
class Lfc2BodyExecutionPolicyFitnessTest {

    private val v2Root: Path = ScannerSupport.v2Root()

    private val policyModule = v2Root.resolve(
        "pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/step/BodyExecutionPolicy.kt",
    )

    private val coordinatorSource = v2Root.resolve(
        "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalDurableRunCoordinator.kt",
    )

    private fun read(path: Path): String {
        require(Files.exists(path)) { "Expected source not found: $path" }
        return Files.readString(path)
    }

    /** Comment-blind view of a source file, so prose can name steps and code cannot. */
    private fun codeOnly(text: String): String =
        text.lineSequence()
            .filter { !it.trimStart().startsWith("//") && !it.trimStart().startsWith("*") }
            .joinToString("\n")

    private val concreteStepLiteral = Regex("\"(core|example)\\.[A-Za-z0-9_.]+\"")

    /** A `when` over a StepKey/stepName value is the exact shape of the B10 debt. */
    private val stepKeySwitch = Regex("""when\s*\(\s*[A-Za-z0-9_.]*([Ss]tepId|stepName|stepKey)[A-Za-z0-9_.]*\s*\)""")

    // ===== vocabulary =====

    @Test
    fun `the policy vocabulary names shapes and never steps`() {
        val code = codeOnly(read(policyModule))

        val offenders = concreteStepLiteral.findAll(code).map { it.value }.toList()
        assertEquals(
            emptyList<String>(),
            offenders,
            "The body execution policy must name execution shapes, not Steps; a policy that needs " +
                "to know a StepKey is a concreteness regression",
        )
    }

    @Test
    fun `the policy vocabulary contains no step key switch`() {
        val code = codeOnly(read(policyModule))

        val match = stepKeySwitch.find(code)
        assertTrue(
            match == null,
            "Policy resolution must be a total function of the declaration, not a switch over a " +
                "StepKey or step name: found ${match?.value}",
        )
    }

    @Test
    fun `the policy vocabulary has no else branch hiding an unhandled case`() {
        val code = codeOnly(read(policyModule))

        assertTrue(
            !Regex("""else\s*->""").containsMatchIn(code),
            "Every when over the closed policy family must be exhaustive; an else branch would " +
                "silently absorb a future case",
        )
    }

    // ===== purity / direction =====

    @Test
    fun `the decision layer depends on nothing outward and on no coroutine machinery`() {
        val forbidden = listOf(
            "dev.rubentxu.pipeline.v2.application",
            "kotlinx.coroutines",
            "java.io.",
            "java.nio.",
            "java.util.concurrent",
        )
        val imports = codeOnly(read(policyModule))
            .lineSequence()
            .filter { it.trimStart().startsWith("import ") }
            .toList()

        val offenders = imports.filter { line -> forbidden.any { line.contains(it) } }
        assertEquals(
            emptyList<String>(),
            offenders,
            "Body policy resolution is a decision-layer function: no application, coroutines, or I/O",
        )
    }

    // ===== declaration authority =====

    @Test
    fun `every body-bearing descriptor row declares a coherent executable policy`() {
        val registry = StepDescriptorRegistry.standard()
        val bodyRows = registry.keys().map { it to registry.get(it)!! }.filter { it.second.takesBody }

        assertTrue(bodyRows.isNotEmpty(), "The canonical registry must declare body-bearing Steps")

        val offenders = bodyRows.mapNotNull { (key, descriptor) ->
            val resolution = resolveBodyExecutionPolicy(key, descriptor, BodyExecutionSupport.FULL)
            if (resolution is BodyPolicyResolution.Resolved) null else "$key -> $resolution"
        }

        assertEquals(
            emptyList<String>(),
            offenders,
            "Every body-bearing descriptor must declare a policy that is coherent with its own " +
                "metadata; W1b declares these, so an incoherent row is a declaration defect",
        )
    }

    @Test
    fun `no body-less descriptor row declares an execution reshape`() {
        val registry = StepDescriptorRegistry.standard()
        val nonBodyRows = registry.keys().map { it to registry.get(it)!! }.filter { !it.second.takesBody }

        assertTrue(nonBodyRows.isNotEmpty(), "The canonical registry must declare terminal Steps")

        val offenders = nonBodyRows.mapNotNull { (key, descriptor) ->
            val policy = descriptor.bodyExecutionPolicy
            if (policy == BodyExecutionPolicy.Sequential) null else "$key -> $policy"
        }

        assertEquals(
            emptyList<String>(),
            offenders,
            "A Step with no body must not declare a body execution shape",
        )
    }

    @Test
    fun `the descriptor default is a total sequential policy`() {
        val bare = StepDescriptor(stepId = "example.bare", name = "bare", configRef = "")

        assertEquals(
            BodyExecutionPolicy.Sequential,
            bare.bodyExecutionPolicy,
            "The default must preserve existing behaviour for every Step that declares nothing",
        )
        assertEquals(
            BodyExecutionPolicy.DEFAULT,
            bare.bodyExecutionPolicy,
            "DEFAULT is the single spelling of that default",
        )
    }

    // ===== scope firewall =====

    /**
     * W1b creates the mechanism; W1c migrates the consumers. Until that slice lands, the
     * coordinator must be byte-identical in behaviour and must not reference the policy at
     * all. When W1c starts routing bodies through the policy this law fails on purpose, and
     * the failure names the slice that owns the change.
     */
    @Test
    fun `the coordinator does not yet resolve body policies`() {
        val code = codeOnly(read(coordinatorSource))

        val forbidden = listOf("BodyExecutionPolicy", "BodyPolicyResolver", "resolveBodyExecutionPolicy")
        val offenders = forbidden.filter { code.contains(it) }

        assertEquals(
            emptyList<String>(),
            offenders,
            "W1b introduces the policy mechanism only; routing bodies through it is W1c. " +
                "The coordinator still routes by StepKey and must stay inside the W1a debt ledger",
        )
    }

    /**
     * W1b is debt-neutral: it must not add a single concrete routing site. W1a's ledger is
     * the authority for that number and the real coordinator is re-scanned here.
     */
    @Test
    fun `W1b leaves the pinned concrete routing debt unchanged`() {
        val discovered = ConcreteBodyRoutingScanner.scan(read(coordinatorSource))
        val pinned = PinnedConcreteBodyRoutingDebt.value

        assertEquals(
            pinned.total,
            discovered.total,
            "W1b must be debt-neutral: it neither introduces nor retires concrete routing",
        )
        assertEquals(
            18,
            discovered.total,
            "The measured coordinator debt at W1b must remain the W1a high-water mark",
        )
    }
}
