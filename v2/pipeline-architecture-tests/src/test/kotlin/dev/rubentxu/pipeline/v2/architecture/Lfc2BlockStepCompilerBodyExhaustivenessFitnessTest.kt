package dev.rubentxu.pipeline.v2.architecture

import dev.rubentxu.pipeline.v2.application.DslCompiledPipelineCompiler
import dev.rubentxu.pipeline.v2.domain.BlockStepNode
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.stream.Stream

/**
 * LFC-2 / B11 — closed-family exhaustiveness for `DslCompiledPipelineCompiler.blockStepNode(...)`.
 *
 * ## Law under test
 *
 * ```
 * Every StepSpec variant in the closed body-aware family that is lowered as a BlockStepNode
 * MUST compile its children into a non-empty BlockStepNode.body when given non-empty DSL
 * children.
 * ```
 *
 * The "closed body-aware family" is the set of body-bearing `StepSpec` variants (those that
 * carry a `steps: List<StepSpec>` field) that the OUTER `stepNode(step, ...)` dispatch routes
 * to `blockStepNode(...)`. The body-bearing-but-routed-elsewhere variants
 * (`CatchError`, `WarnError` → `rewriteWorkflowControl`; `Unstable` → `rewriteUnstable`;
 * `AnsiColor` → opaque fallback) are NOT in this family and are explicitly excluded.
 *
 * This is the mechanical half of the B11 exit criteria: the inner `when (step) { ... }` in
 * `blockStepNode(...)` MUST enumerate every variant the outer dispatch hands it, otherwise
 * the body of a future (or newly-added) body-bearing variant silently drops to
 * `else -> emptyList()` and the child steps never execute at runtime. The B11 defect was
 * exactly this: `WithEnv` and `Timestamps` were dispatched to `blockStepNode(...)` but not
 * enumerated inside it, so their `BlockStepNode.body` was always empty.
 *
 * ## How the family is discovered
 *
 * 1. Reflect all sealed subclasses of `dev.rubentxu.pipeline.v2.dsl.StepSpec`.
 * 2. Filter to those carrying a `steps: List<StepSpec>` field (the body-bearing variants).
 * 3. Filter further to those whose `StepSpec.X` name appears as a routing target of the
 *    outer `stepNode(...)` dispatch (parsed from the compiler source — see
 *    [outerDispatchRoutedVariants]).
 *
 * For each variant in the resulting set, [blockStepNodeCompilesChildren] compiles a minimal
 * DSL pipeline using that variant with at least one child step and asserts that the
 * resulting `BlockStepNode.body` is non-empty AND contains the expected child id.
 *
 * Adding a new body-bearing StepSpec without wiring it into the inner `when` makes the
 * parameterized test fail loudly (compile succeeds, but body is empty).
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class Lfc2BlockStepCompilerBodyExhaustivenessFitnessTest {

    private val compilerSource = ScannerSupport.v2Root().resolve(
        "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/DslCompiledPipelineCompiler.kt",
    )

    private fun read(path: java.nio.file.Path): String {
        require(Files.exists(path)) { "Expected source not found: $path" }
        return Files.readString(path)
    }

    /** Comment-blind view of a source file. */
    private fun codeOnly(text: String): String =
        text.lineSequence()
            .filter { !it.trimStart().startsWith("//") && !it.trimStart().startsWith("*") }
            .joinToString("\n")

    /**
     * The closed family — body-bearing StepSpec variants the OUTER `stepNode(...)` dispatch
     * routes to `blockStepNode(...)`. Parsed from the compiler source.
     *
     * If the outer dispatch changes (adds/removes a variant from `blockStepNode(...)`),
     * this set must change too — that is the point. The parse is intentionally narrow:
     * we look only at the outer `stepNode(...)` body (between the `private fun stepNode(...)`
     * declaration and the next top-level function), and collect every `is StepSpec.<Name>`
     * branch whose next line starts a `blockStepNode(` call.
     */
    private fun outerDispatchRoutedVariants(): Set<String> {
        val source = codeOnly(read(compilerSource))
        // Slice from `private fun stepNode(` through the next top-level function
        // (indented `\n    private fun ...` inside the surrounding `object`).
        val stepNodeStart = source.indexOf("private fun stepNode(")
        require(stepNodeStart >= 0) { "Outer dispatch 'private fun stepNode(' not found in compiler source" }
        val rest = source.substring(stepNodeStart)
        val restAfter = rest.substring("private fun stepNode(".length)
        // Allow up to 12 leading spaces (the functions live inside an `object`).
        val nextFn = Regex("""\n[ \t]{0,12}private fun [A-Za-z]""").find(restAfter)
        val outerBody = if (nextFn != null) {
            rest.substring(0, nextFn.range.first + "private fun stepNode(".length)
        } else {
            rest
        }
        // Find every `is StepSpec.<Name>` branch whose body starts with `blockStepNode(`.
        // We look for `is StepSpec.<Name> ->` followed (eventually) by `blockStepNode(`.
        val branchRegex = Regex("""is\s+StepSpec\.([A-Za-z0-9_]+)\s*->""")
        val blockRegex = Regex("""blockStepNode\s*\(""")
        val routed = mutableSetOf<String>()
        for (match in branchRegex.findAll(outerBody)) {
            val variant = match.groupValues[1]
            // Search forward from the branch for a `blockStepNode(` call before the next branch.
            val nextBranch = branchRegex.find(outerBody, match.range.last + 1)
            val slice = if (nextBranch != null) {
                outerBody.substring(match.range.last, nextBranch.range.first)
            } else {
                outerBody.substring(match.range.last)
            }
            if (blockRegex.containsMatchIn(slice)) {
                routed.add(variant)
            }
        }
        return routed
    }

    /**
     * All body-bearing StepSpec variants — those carrying a `steps: List<StepSpec>` field.
     * Discovered via Kotlin reflection so the test tracks the closed sealed family
     * mechanically.
     */
    private fun bodyBearingVariants(): Set<String> {
        val stepSpecClass = StepSpec::class
        @Suppress("UNCHECKED_CAST")
        val sealedSubclasses = stepSpecClass.sealedSubclasses as Collection<KClass<out StepSpec>>
        return sealedSubclasses
            .filter { kClass ->
                // Body-bearing iff the variant declares a constructor parameter named `steps`
                // whose type is `List<StepSpec>`.
                val ctor = kClass.primaryConstructor ?: return@filter false
                val stepsParam = ctor.parameters.firstOrNull { it.name == "steps" } ?: return@filter false
                val typeName = stepsParam.type.toString()
                // The Kotlin type string is `List<dev.rubentxu.pipeline.v2.dsl.StepSpec>`;
                // match on the trailing type name only.
                typeName.endsWith("List<dev.rubentxu.pipeline.v2.dsl.StepSpec>") ||
                    typeName.endsWith("List<StepSpec>")
            }
            .map { it.simpleName!! }
            .toSet()
    }

    /**
     * Per-variant factory: given a body (list of child StepSpec), build the body-bearing
     * StepSpec variant. Each entry corresponds to a variant in the closed family.
     *
     * If a new variant is added to the closed family without a factory here, the
     * parameterized test will fail loudly with "no factory for variant ...".
     */
    private val factoryByVariant: Map<String, (List<StepSpec>) -> StepSpec> = mapOf(
        "TimeoutBlock" to { body ->
            StepSpec.TimeoutBlock(time = 60, unit = "SECONDS", activity = null, steps = body)
        },
        "RetryBlock" to { body ->
            StepSpec.RetryBlock(count = 3, conditions = null, steps = body)
        },
        "Dir" to { body ->
            StepSpec.Dir(path = "/tmp", steps = body)
        },
        "WithCredentialsBlock" to { body ->
            StepSpec.WithCredentialsBlock(
                credentialsId = CredentialsId("test-credentials-id"),
                purpose = "test-purpose",
                bindings = listOf(
                    StepSpec.CredentialsBinding.string("cred-id", "TEST_VAR"),
                ),
                steps = body,
            )
        },
        "WithEnv" to { body ->
            StepSpec.WithEnv(overrides = listOf("LFC_B11_TEST=1"), steps = body)
        },
        "Timestamps" to { body ->
            StepSpec.Timestamps(steps = body)
        },
    )

    /** Variants that are body-bearing but intentionally NOT routed to `blockStepNode`. */
    private val nonBlockRoutedVariants: Set<String> = setOf(
        // CatchError / WarnError → rewriteWorkflowControl (linear sequence of marker events).
        "CatchError",
        "WarnError",
        // AnsiColor → outer dispatch falls through to `else -> OpaqueStepNode` (decorator
        // semantics not implemented in this slice).
        "AnsiColor",
        // NodeNoOp → outer dispatch falls through to `else -> OpaqueStepNode` (agent routing
        // step; body is the node label scope, not a child IR body).
        "NodeNoOp",
    )

    @Test
    @DisplayName("closed family: outer dispatch routes exactly the body-bearing variants the factory covers")
    fun `closed family matches outer dispatch`() {
        val bodyBearing = bodyBearingVariants()
        val routed = outerDispatchRoutedVariants()
        val coveredByFactory = factoryByVariant.keys

        assertTrue(
            bodyBearing.containsAll(routed),
            "The outer dispatch routed variants $routed must all be body-bearing. " +
                "Body-bearing set: $bodyBearing. " +
                "If this fails, an `is StepSpec.<X> -> blockStepNode(...)` branch was added for " +
                "a variant that does not actually carry a `steps: List<StepSpec>` field.",
        )

        assertTrue(
            routed.containsAll(coveredByFactory),
            "Factory covers $coveredByFactory but the outer dispatch only routes $routed. " +
                "Remove the factory entry for the variant that was moved out of blockStepNode, " +
                "or fix the outer dispatch.",
        )

        // Every routed variant must have a factory entry — otherwise the parameterized test
        // would silently skip it.
        assertEquals(
            routed,
            coveredByFactory,
            "Outer dispatch routes $routed to blockStepNode; factory must cover exactly the " +
                "same set (covered=$coveredByFactory).",
        )

        // Body-bearing-but-not-routed variants must be in the explicit allow-list.
        val expectedNotRouted = bodyBearing - routed
        assertEquals(
            nonBlockRoutedVariants,
            expectedNotRouted,
            "Body-bearing variants not routed to blockStepNode should be $nonBlockRoutedVariants; " +
                "found $expectedNotRouted. Update the allow-list if a new variant was added with " +
                "an alternative lowering path.",
        )
    }

    @ParameterizedTest(name = "{0} compiles to BlockStepNode with non-empty body")
    @MethodSource("closedFamilyVariants")
    fun `blockStepNodeCompilesChildren`(variantName: String, factory: (List<StepSpec>) -> StepSpec) {
        // Build a minimal PipelineSpec that uses the body-bearing variant with a single
        // `Echo("lfc2-b11-child")` child. The compiler must lower this to a BlockStepNode
        // whose body contains the echo child as a non-empty OpaqueStepNode.
        val childEcho = StepSpec.Echo("lfc2-b11-child")
        val bodySpec = factory(listOf(childEcho))
        val pipeline: PipelineSpec = makePipelineWithStep(bodySpec)

        val compiled = DslCompiledPipelineCompiler.compile(
            spec = pipeline,
            sourcePath = "lfc2-b11-body-exhaustiveness.pipeline.kts",
            sourceContent = "lfc2-b11-body-exhaustiveness pipeline for variant $variantName",
            pluginLockDigest = Digest("lfc2-b11-lock"),
        )

        // The single stage must have a StepBody.Steps; the first node must be the BlockStepNode
        // for the body-bearing variant.
        val stageBody = compiled.stages.single().body
        assertTrue(
            stageBody is StageBody.Steps,
            "Stage body must be Steps for variant $variantName; got $stageBody",
        )
        val steps = (stageBody as StageBody.Steps).steps
        assertEquals(1, steps.size, "Stage must contain exactly one BlockStepNode for $variantName")
        val block = steps.single()
        assertTrue(
            block is BlockStepNode,
            "Compiled node for $variantName must be a BlockStepNode; got ${block::class.simpleName}",
        )
        val body = (block as BlockStepNode).body
        assertTrue(
            body.isNotEmpty(),
            "BlockStepNode.body for $variantName MUST be non-empty (the bug under repair: " +
                "the inner `when (step)` in blockStepNode(...) did not enumerate this variant, " +
                "so body silently fell to emptyList()).",
        )
        // The body must contain the echo child.
        val containsChild = body.any { node ->
            node.id.value.endsWith("/echo-0")
        }
        assertTrue(
            containsChild,
            "BlockStepNode.body for $variantName must contain the echo child (expected id ending " +
                "with '/echo-0'). Got body ids: ${body.map { it.id.value }}",
        )
    }

    /**
     * Builds a `PipelineSpec` with a single stage named "Lfc2B11" that contains exactly one
     * body-bearing step. We bypass the DSL builder for body-bearing steps (which has its own
     * argument validation) and construct the `PipelineSpec` directly.
     */
    private fun makePipelineWithStep(bodySpec: StepSpec): PipelineSpec {
        // The simplest: build a PipelineSpec directly from data classes.
        return PipelineSpec(
            stages = listOf(
                dev.rubentxu.pipeline.v2.dsl.StageSpec(
                    name = "Lfc2B11",
                    steps = listOf(bodySpec),
                ),
            ),
        )
    }

    companion object {
        @JvmStatic
        fun closedFamilyVariants(): Stream<Arguments> {
            val test = Lfc2BlockStepCompilerBodyExhaustivenessFitnessTest()
            return test.factoryByVariant.entries.stream()
                .map { (name, factory) -> Arguments.of(name, factory) }
        }
    }
}
