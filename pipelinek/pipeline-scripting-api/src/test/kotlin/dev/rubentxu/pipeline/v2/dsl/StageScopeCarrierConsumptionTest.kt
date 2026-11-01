package dev.rubentxu.pipeline.v2.dsl

import dev.rubentxu.pipeline.v2.domain.MapRuntimeConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Whether a discarded MUST_CONSUME carrier is refused in EVERY scope of a stage.
 *
 * ## The guarantee
 * A pure builder hands out a carrier; a consumer marks that carrier as consumed.
 * A carrier that is produced and never consumed is a silent lie: the author
 * asked for a checkout and gets nothing. The refusal belongs to the STAGE,
 * because the stage is the unit that is admitted — and it must see the
 * carriers produced in the stage's NESTED scopes too, because `retry`, `dir`,
 * `withCredentials`, `slot`, `catchError` and the rest are lexical blocks of
 * the same stage, not stages of their own.
 *
 * Two directions, and they are opposite. A discard nobody sees is a lie; a
 * discard that is not one punishes correct code. Both must come out right.
 *
 * ## The failures this covers
 * The registry of carriers used to live in the SCOPE, while the admission
 * point is the STAGE. So a carrier produced inside a nested block was
 * registered in a map nobody ever read: the stage closed, the pipeline ran,
 * and the checkout the author asked for never happened.
 *
 * And a carrier produced in the stage and consumed inside a nested block was
 * marked in the NESTED registry, so the stage kept believing it was
 * unconsumed and refused a pipeline that had used the carrier exactly as it
 * was meant to be used.
 *
 * The refusal itself has to be a REFUSAL. A gate that reports its verdict by
 * appending a step to the stage is not a gate: it turns a construction error
 * into a runtime step failure, and the author sees a failed step instead of
 * a pipeline that was never built.
 *
 * And a carrier is an IDENTITY, not a value. `CheckoutSpec` is a data class,
 * so two carriers built by two calls with the same arguments are EQUAL. The
 * registry used to be keyed by equality, which made two distinct carriers
 * share ONE obligation: consuming one removed that entry, and the other
 * carrier — discarded — was never refused. The stage was admitted with a
 * lie in it. Two carriers are two obligations, and a consumption cancels
 * exactly one of them.
 *
 * ## Why the instrument is this cheap
 * The question is asked of a declaration the author wrote, so it is a pure call
 * over plain data: the DSL is built in memory, no script is compiled, no
 * process starts, no file is read and no clock is consulted. The refusal
 * happens while the stage is being built — before any IR exists — so a
 * scenario that ran a pipeline would measure a fact the unit already
 * measures, at the price of a distribution and a process.
 */
class StageScopeCarrierConsumptionTest {

    private val config = MapRuntimeConfig.empty()

    /** Builds one stage and returns the built pipeline. */
    private fun build(block: StageScope.() -> Unit): PipelineSpec =
        pipeline(config) {
            stages {
                stage("stage", block)
            }
        }

    private fun PipelineSpec.topLevelSteps(): List<StepSpec> = stages.single().steps

    @Test
    @DisplayName("a carrier discarded inside a nested scope is refused before any IR exists")
    fun `a carrier discarded inside a nested scope is refused before any IR exists`() {
        val failure = assertThrows(IllegalStateException::class.java) {
            build {
                retry(1) {
                    scmGit("https://example.test/descartado.git")
                }
            }
        }
        assertTrue(
            failure.message.orEmpty().contains("never used (discarded)"),
            "the refusal names the carrier that was discarded: ${failure.message}",
        )
        assertTrue(
            failure.message.orEmpty().contains("scmGit"),
            "the refusal names the builder that produced it: ${failure.message}",
        )
    }

    @Test
    @DisplayName("a carrier discarded at stage level is refused before any IR exists")
    fun `a carrier discarded at stage level is refused before any IR exists`() {
        val failure = assertThrows(IllegalStateException::class.java) {
            build {
                scmGit("https://example.test/descartado.git")
            }
        }
        assertTrue(
            failure.message.orEmpty().contains("never used (discarded)"),
            "the refusal names the carrier that was discarded: ${failure.message}",
        )
    }

    @Test
    @DisplayName("a carrier produced and consumed inside a nested scope is admitted with its checkout")
    fun `a carrier produced and consumed inside a nested scope is admitted with its checkout`() {
        val spec = build {
            dir("workspace") {
                git("https://example.test/consumido.git")
            }
        }
        assertTrue(
            spec.topLevelSteps().none { it is StepSpec.Error },
            "an admitted stage carries no error step: ${spec.topLevelSteps()}",
        )
        val nested = spec.topLevelSteps().filterIsInstance<StepSpec.Dir>().single().steps
        assertTrue(
            nested.any { it is StepSpec.Checkout },
            "the checkout the author asked for is in the IR: $nested",
        )
    }

    @Test
    @DisplayName("a carrier produced outside and consumed inside a nested scope is admitted with its checkout")
    fun `a carrier produced outside and consumed inside a nested scope is admitted with its checkout`() {
        val spec = build {
            val carrier = scmGit("https://example.test/consumido.git")
            dir("workspace") {
                checkout(carrier)
            }
        }
        assertTrue(
            spec.topLevelSteps().none { it is StepSpec.Error },
            "an admitted stage carries no error step: ${spec.topLevelSteps()}",
        )
        val nested = spec.topLevelSteps().filterIsInstance<StepSpec.Dir>().single().steps
        assertTrue(
            nested.any { it is StepSpec.Checkout },
            "the checkout the author asked for is in the IR: $nested",
        )
    }

    @Test
    @DisplayName("consuming one of two equal carriers does not cancel the obligation of the other")
    fun `consuming one of two equal carriers does not cancel the obligation of the other`() {
        val failure = assertThrows(IllegalStateException::class.java) {
            build {
                val consumed = scmGit("https://example.test/igual.git")
                scmGit("https://example.test/igual.git")
                checkout(consumed)
            }
        }
        assertTrue(
            failure.message.orEmpty().contains("never used (discarded)"),
            "the second, discarded carrier is still refused: ${failure.message}",
        )
    }

    @Test
    @DisplayName("two equal carriers can both be consumed and both checkouts reach the IR")
    fun `two equal carriers can both be consumed and both checkouts reach the IR`() {
        val spec = build {
            val first = scmGit("https://example.test/igual.git")
            val second = scmGit("https://example.test/igual.git")
            checkout(first)
            checkout(second)
        }
        assertTrue(
            spec.topLevelSteps().none { it is StepSpec.Error },
            "an admitted stage carries no error step: ${spec.topLevelSteps()}",
        )
        assertEquals(
            2,
            spec.topLevelSteps().count { it is StepSpec.Checkout },
            "both checkouts the author asked for are in the IR: ${spec.topLevelSteps()}",
        )
    }
}
