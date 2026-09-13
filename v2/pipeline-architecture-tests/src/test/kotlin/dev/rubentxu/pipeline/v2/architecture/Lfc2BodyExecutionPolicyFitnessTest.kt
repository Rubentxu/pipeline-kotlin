package dev.rubentxu.pipeline.v2.architecture

import dev.rubentxu.pipeline.v2.domain.StepBody
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.StepDescriptorRegistry
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionPolicy
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionSupport
import dev.rubentxu.pipeline.v2.domain.step.BodyPolicyRejection
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

    private val stepBodyModule = v2Root.resolve(
        "pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/StepBody.kt",
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
        val bodyRows = registry.keys().map { it to registry.get(it)!! }
            .filter { it.second.body.declared != null }

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

    /**
     * W1d: the old form of this law ("a Step with no body must not declare a body execution
     * shape") described a combination that no longer compiles. What remains to be asserted is
     * the consequence: a terminal row carries NO body value at all, so there is nothing left
     * to contradict. The default of a descriptor that declares nothing is [StepBody.None].
     */
    @Test
    fun `a terminal descriptor row carries no body value at all`() {
        val registry = StepDescriptorRegistry.standard()
        val terminalRows = registry.keys().map { it to registry.get(it)!! }
            .filter { it.second.body == StepBody.None }

        assertTrue(terminalRows.isNotEmpty(), "The canonical registry must declare terminal Steps")

        val offenders = terminalRows.filter { (it.second.body as? StepBody.Declared) != null }
        assertTrue(
            offenders.isEmpty(),
            "A terminal Step has no field in which to carry body metadata: ${offenders.map { it.first }}",
        )
    }

    /**
     * The W1d exit criterion, mechanically. Ownership, shape and cardinality MUST NOT carry
     * defaults, so a body Step cannot acquire an owner or a shape by omission; [StepBody.None]
     * MUST carry no fields, so a terminal Step cannot hold body metadata. Both halves are read
     * off the real declaration, so re-introducing either default fails here.
     */
    @Test
    fun `a body declaration cannot be defaulted into existence`() {
        val code = codeOnly(read(stepBodyModule))

        val defaulted = Regex("val (execution|owner|policy|invocation)\\s*:\\s*[A-Za-z.]*\\s*=")
            .findAll(code).map { it.value }.toList()
        assertTrue(
            defaulted.isEmpty(),
            "Ownership, shape and cardinality must be stated by every body row; a default is how " +
                "an unowned body becomes representable again: $defaulted",
        )

        val noneBody = Regex("data object None\\s*:\\s*StepBody\\s*\\{([^}]*)\\}")
            .find(code)?.groupValues?.get(1).orEmpty()
        assertTrue(
            noneBody.isBlank(),
            "StepBody.None must carry no body metadata at all; found '$noneBody'",
        )
    }

    /**
     * W1d: a Step that declares nothing is terminal, and asking for the body policy of a
     * terminal Step is a typed rejection rather than a permissive `Sequential`. The old law
     * asserted the opposite (a "sequential default" for every Step), which is exactly the
     * defaulting W1d removed.
     */
    @Test
    fun `the descriptor default is no body, and its policy is a typed rejection`() {
        val bare = StepDescriptor(stepId = "example.bare", name = "bare", configRef = "")

        assertEquals(StepBody.None, bare.body, "A Step that declares nothing declares no body")

        val resolution = resolveBodyExecutionPolicy(bare, BodyExecutionSupport.FULL)
        assertTrue(
            resolution.rejectionOrNull is BodyPolicyRejection.NotABodyStep,
            "A terminal Step must be rejected as NotABodyStep, never answered with a default: $resolution",
        )
        assertTrue(
            resolution.policyOrNull == null,
            "A rejection must never carry a policy",
        )
    }

    // ===== scope firewall =====

    /**
     * W1b created the mechanism; W1c migrated the consumers. The coordinator now resolves
     * bodies through the policy port, and this law is the inverse of the W1b firewall it
     * replaces: the routing MUST go through the declared policy, and the Step-identity
     * switch that used to key it must stay gone (pinned by the W1a ledger scan below).
     */
    @Test
    fun `the coordinator resolves body policies through the port`() {
        val code = codeOnly(read(coordinatorSource))

        val required = listOf("BodyPolicyResolver", "BodyExecutionOwner")
        val missing = required.filterNot { code.contains(it) }

        assertEquals(
            emptyList<String>(),
            missing,
            "W1c: body routing is policy-driven. A coordinator that stopped resolving the " +
                "declared policy, or that re-introduced a hard-coded body Step set, must fail here",
        )
    }

    /**
     * W1a's ledger is the authority for the debt number, and the real coordinator is re-scanned
     * here. W1b was debt-neutral (18), W1c left 4, and W1d retires the remaining four: the
     * credential bypass is folded into the shared body path and the two concrete durable
     * identities are reclassified as typed
     * [dev.rubentxu.pipeline.v2.domain.step.BodyAggregateIdentity] values (guarded by
     * `Lfc2DurableAggregateIdentityFitnessTest`, not deleted).
     */
    @Test
    fun `W1d lowers the pinned concrete routing debt to zero`() {
        val text = read(coordinatorSource)
        val discovered = ConcreteBodyRoutingScanner.scan(text)
        val pinned = PinnedConcreteBodyRoutingDebt.value

        assertEquals(
            pinned.total,
            discovered.total,
            "The ledger must equal the coordinator's measured concrete routing debt",
        )
        assertEquals(
            0,
            discovered.total,
            "W1d burns the credential bypass and reclassifies the two durable identities; the " +
                "coordinator contains no concrete Step literal, no step-id switch, no " +
                "dispatch*Block identifier and no hard-coded body id set",
        )
        assertEquals(
            BodyChildLoopInventory.EXPECTED,
            BodyChildLoopScanner.scan(text),
            "Zero debt is only honest while the body path is genuinely shared",
        )
    }
}
