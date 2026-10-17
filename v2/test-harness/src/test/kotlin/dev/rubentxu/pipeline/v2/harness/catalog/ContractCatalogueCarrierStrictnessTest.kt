package dev.rubentxu.pipeline.v2.harness.catalog

import dev.rubentxu.pipeline.v2.harness.catalog.ContractCatalogue.Contract
import dev.rubentxu.pipeline.v2.harness.catalog.ContractCatalogue.EscalationRule
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * BQ4 v8 (catalogue carrier self-protect, 2026-10-17):
 * the previous shape of [Contract] and [EscalationRule]
 * had no init validation. A blank `capability` would
 * silently miss the contract's selection (the resolver's
 * `isProductionPath` substring chain never matches
 * ""), and an `from == to` escalation was a silent
 * no-op (the catalogue's `requiredFidelityFor` adds
 * the same value to the set — a no-op). The carriers
 * now refuse both at construction.
 *
 * The three claims pinned here:
 *  1. A [Contract] with a blank `capability` is refused
 *     at construction.
 *  2. An [EscalationRule] with `from == to` is refused
 *     at construction.
 *  3. The catalogue's actual [ContractCatalogue.ESCALATIONS]
 *     list is non-empty and every rule has a meaningful
 *     escalation (no silent no-ops survived the carrier
 *     self-protect).
 */
class ContractCatalogueCarrierStrictnessTest {

    @Test
    fun `Contract refuses a blank capability at construction`() {
        val ex = assertThrows<IllegalArgumentException> {
            Contract(
                capability = "",
                owningModule = ":pipeline-credentials-api",
                sourcePaths = listOf("v2/pipeline-credentials-api/src/main/kotlin/Foo.kt"),
                minFidelity = dev.rubentxu.pipeline.v2.harness.catalog.ContractCatalogue.Fidelity.HF0,
            )
        }
        assertTrue(
            (ex.message ?: "").contains("capability must not be blank"),
            "refusal must name the field; got ${ex.message}",
        )
    }

    @Test
    fun `Contract refuses a blank sourcePaths entry at construction`() {
        val ex = assertThrows<IllegalArgumentException> {
            Contract(
                capability = "x",
                owningModule = ":pipeline-credentials-api",
                sourcePaths = listOf("v2/pipeline-credentials-api/src/main/kotlin/Foo.kt", ""),
                minFidelity = dev.rubentxu.pipeline.v2.harness.catalog.ContractCatalogue.Fidelity.HF0,
            )
        }
        assertTrue(
            (ex.message ?: "").contains("sourcePaths must not contain blank"),
            "refusal must name the field; got ${ex.message}",
        )
    }

    @Test
    fun `EscalationRule refuses from == to at construction (silent no-op detector)`() {
        // The catalogue's first escalation rule had
        // `from = HF2, to = HF2` before this fitness
        // caught it. The new shape refuses the no-op
        // at construction; the catalogue's static init
        // is now provably free of no-op escalations.
        val ex = assertThrows<IllegalArgumentException> {
            EscalationRule(
                capability = "x",
                from = dev.rubentxu.pipeline.v2.harness.catalog.ContractCatalogue.Fidelity.HF2,
                to = dev.rubentxu.pipeline.v2.harness.catalog.ContractCatalogue.Fidelity.HF2,
                reason = "r",
            )
        }
        assertTrue(
            (ex.message ?: "").contains("from and to must be different"),
            "refusal must name the no-op; got ${ex.message}",
        )
    }

    @Test
    fun `EscalationRule refuses a blank capability at construction`() {
        val ex = assertThrows<IllegalArgumentException> {
            EscalationRule(
                capability = "",
                from = dev.rubentxu.pipeline.v2.harness.catalog.ContractCatalogue.Fidelity.HF2,
                to = dev.rubentxu.pipeline.v2.harness.catalog.ContractCatalogue.Fidelity.HF3,
                reason = "r",
            )
        }
        assertTrue(
            (ex.message ?: "").contains("capability must not be blank"),
            "refusal must name the field; got ${ex.message}",
        )
    }

    @Test
    fun `the catalogue ESCALATIONS list is non-empty and every rule escalates`() {
        val escalations = ContractCatalogue.ESCALATIONS
        assertTrue(
            escalations.isNotEmpty(),
            "the catalogue must declare at least one escalation; an empty list " +
                "means a build-file change is treated as a no-op, the silent-green " +
                "this fitness prevents",
        )
        for (rule in escalations) {
            assertTrue(
                rule.from != rule.to,
                "every EscalationRule must change the fidelity (from != to); " +
                    "rule ${rule.capability} has from=${rule.from} to=${rule.to} " +
                    "which is a silent no-op",
            )
        }
    }
}
