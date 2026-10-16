package dev.rubentxu.pipeline.v2.harness.plan

import dev.rubentxu.pipeline.v2.harness.catalog.CapabilityId
import dev.rubentxu.pipeline.v2.harness.catalog.ContractCatalogue
import dev.rubentxu.pipeline.v2.harness.catalog.ContractEntry
import dev.rubentxu.pipeline.v2.harness.catalog.ContractId
import dev.rubentxu.pipeline.v2.harness.catalog.ContractMetadata
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Property / law tests for [ImpactResolver]. These prove the decider is total,
 * deterministic, and conservative — i.e. adding a real change never drops an
 * obligation.
 */
class ImpactResolverPropertyTest {

    private val catalogue = ContractCatalogue.of(
        listOf(
            ContractEntry(
                id = ContractId("a"),
                capability = CapabilityId("a"),
                owner = "x",
                metadata = ContractMetadata(
                    minimumFidelity = HarnessFidelity.HF0,
                    requiresInstalledBinary = false,
                    requiresExternalCertifier = false,
                ),
                suiteFqcn = "x.A",
                sourcePatterns = listOf("src/A.kt"),
            ),
            ContractEntry(
                id = ContractId("b"),
                capability = CapabilityId("b"),
                owner = "x",
                metadata = ContractMetadata(
                    minimumFidelity = HarnessFidelity.HF1,
                    requiresInstalledBinary = false,
                    requiresExternalCertifier = false,
                ),
                suiteFqcn = "x.B",
                sourcePatterns = listOf("src/B.kt"),
            ),
            ContractEntry(
                id = ContractId("c"),
                capability = CapabilityId("c"),
                owner = "x",
                metadata = ContractMetadata(
                    minimumFidelity = HarnessFidelity.HF2,
                    requiresInstalledBinary = false,
                    requiresExternalCertifier = false,
                ),
                suiteFqcn = "x.C",
                sourcePatterns = listOf("src/C.kt"),
            ),
        ),
    )

    @Test
    fun `determinism — permutations of the same set yield the same plan`() {
        val changes = listOf(
            ChangedInput.KotlinSource("src/A.kt"),
            ChangedInput.KotlinSource("src/B.kt"),
            ChangedInput.KotlinSource("src/C.kt"),
        )
        val first = ImpactResolver.resolve(changes, catalogue)
        val permutations = listOf(
            changes.reversed(),
            listOf(changes[2], changes[0], changes[1]),
            listOf(changes[1], changes[2], changes[0]),
        )
        for (permuted in permutations) {
            val plan = ImpactResolver.resolve(permuted, catalogue)
            assertEquals(
                ImpactResolver.fingerprint(first),
                ImpactResolver.fingerprint(plan),
                "plan fingerprint must not depend on input order; permutation=$permuted",
            )
            assertEquals(first.contracts(), plan.contracts())
        }
    }

    @Test
    fun `monotonicity — adding an owned source never drops an existing obligation`() {
        val initial = ImpactResolver.resolve(
            listOf(ChangedInput.KotlinSource("src/A.kt")),
            catalogue,
        )
        val grown = ImpactResolver.resolve(
            listOf(
                ChangedInput.KotlinSource("src/A.kt"),
                ChangedInput.KotlinSource("src/B.kt"),
            ),
            catalogue,
        )
        assertTrue(initial is ImpactDecision.Selected)
        assertTrue(grown is ImpactDecision.Selected)
        assertTrue(
            grown.contracts().containsAll(initial.contracts()),
        ) { "grown plan must contain every contract of the initial plan" }
    }

    @Test
    fun `monotonicity — adding NonProduction never grows the obligation set`() {
        val initial = ImpactResolver.resolve(
            listOf(ChangedInput.KotlinSource("src/A.kt")),
            catalogue,
        )
        val grown = ImpactResolver.resolve(
            listOf(
                ChangedInput.KotlinSource("src/A.kt"),
                ChangedInput.NonProduction("src/test/A_test.kt"),
            ),
            catalogue,
        )
        assertTrue(initial is ImpactDecision.Selected)
        assertTrue(grown is ImpactDecision.Selected)
        assertEquals(initial.contracts(), grown.contracts())
    }

    @Test
    fun `no vacuum — empty obligation set only when every input is NonProduction`() {
        val onlyTests = ImpactResolver.resolve(
            listOf(ChangedInput.NonProduction("v2/pipeline-application/src/test/kotlin/T.kt")),
            catalogue,
        )
        assertTrue(onlyTests is ImpactDecision.Selected) { "got $onlyTests" }
        assertEquals(emptySet<ContractId>(), onlyTests.contracts())

        val mixed = ImpactResolver.resolve(
            listOf(
                ChangedInput.NonProduction("v2/pipeline-application/src/test/kotlin/T.kt"),
                ChangedInput.KotlinSource("v2/legacy/Unowned.kt"),
            ),
            catalogue,
        )
        assertTrue(mixed is ImpactDecision.Refused) { "got $mixed" }
    }
}
