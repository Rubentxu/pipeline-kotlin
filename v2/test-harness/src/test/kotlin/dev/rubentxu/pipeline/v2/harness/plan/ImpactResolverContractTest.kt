package dev.rubentxu.pipeline.v2.harness.plan

import dev.rubentxu.pipeline.v2.harness.catalog.CapabilityId
import dev.rubentxu.pipeline.v2.harness.catalog.ContractCatalogue
import dev.rubentxu.pipeline.v2.harness.catalog.ContractEntry
import dev.rubentxu.pipeline.v2.harness.catalog.ContractId
import dev.rubentxu.pipeline.v2.harness.catalog.ContractMetadata
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Contract tests for [ImpactResolver]. These are the decider's laws: a
 * mutation that flips any of these rows is a regression. The companion
 * `ImpactResolverPropertyTest` adds determinism / monotonicity laws.
 */
class ImpactResolverContractTest {

    private val caps = listOf(
        ContractEntry(
            id = ContractId("step.echo"),
            capability = CapabilityId("step.echo"),
            owner = "core",
            metadata = ContractMetadata(
                minimumFidelity = HarnessFidelity.HF0,
                requiresInstalledBinary = false,
                requiresExternalCertifier = false,
            ),
            suiteFqcn = "dev.rubentxu.pipeline.v2.harness.CoreEchoStepContractTest",
            sourcePatterns = listOf("v2/pipeline-step-sdk/runtime/src/main/kotlin/*EchoStep*.kt"),
        ),
        ContractEntry(
            id = ContractId("step.sh"),
            capability = CapabilityId("step.sh"),
            owner = "core",
            metadata = ContractMetadata(
                minimumFidelity = HarnessFidelity.HF1,
                requiresInstalledBinary = false,
                requiresExternalCertifier = false,
            ),
            suiteFqcn = "dev.rubentxu.pipeline.v2.harness.CoreShStepContractTest",
            sourcePatterns = listOf("v2/pipeline-step-sdk/runtime/src/main/kotlin/*ShStep*.kt"),
        ),
        ContractEntry(
            id = ContractId("plugin.discovery"),
            capability = CapabilityId("plugin.discovery"),
            owner = "sdk",
            metadata = ContractMetadata(
                minimumFidelity = HarnessFidelity.HF0,
                requiresInstalledBinary = false,
                requiresExternalCertifier = false,
            ),
            suiteFqcn = "dev.rubentxu.pipeline.v2.harness.PluginDiscoveryContractTest",
            sourcePatterns = listOf("v2/pipeline-sdk-bom/**"),
        ),
    )
    private val catalogue = ContractCatalogue.of(caps)

    @Nested
    inner class RefusedCases {
        @Test
        fun `unclassified production path refuses`() {
            val plan = ImpactResolver.resolve(
                listOf(ChangedInput.UnclassifiedProduction("v2/legacy/scripts/random.py")),
                catalogue,
            )
            assertTrue(plan is ImpactDecision.Refused) {
                "expected Refused, got $plan"
            }
            val refused = plan as ImpactDecision.Refused
            assertTrue(
                refused.reasons.any { it is Refusal.UnclassifiedInput },
            ) { "expected UnclassifiedInput reason, got ${refused.reasons}" }
        }

        @Test
        fun `unclassified alongside owned input still refuses (law 3)`() {
            val plan = ImpactResolver.resolve(
                listOf(
                    ChangedInput.KotlinSource("v2/pipeline-step-sdk/runtime/src/main/kotlin/EchoStep.kt"),
                    ChangedInput.UnclassifiedProduction("v2/legacy/scripts/random.py"),
                ),
                catalogue,
            )
            // Law 3: ANY UnclassifiedProduction refuses the whole plan, not just that line.
            assertTrue(plan is ImpactDecision.Refused) { "expected Refused, got $plan" }
        }

        @Test
        fun `production path with no catalogue owner refuses (CatalogueGap)`() {
            val plan = ImpactResolver.resolve(
                listOf(ChangedInput.KotlinSource("v2/pipeline-application/src/main/kotlin/UnownedFile.kt")),
                catalogue,
            )
            assertTrue(plan is ImpactDecision.Refused) { "expected Refused, got $plan" }
            val refused = plan as ImpactDecision.Refused
            assertTrue(
                refused.reasons.any { it is Refusal.CatalogueGap },
            ) { "expected CatalogueGap reason, got ${refused.reasons}" }
        }

        @Test
        fun `ambiguous path refuses (multiple matching contracts)`() {
            val dup = ContractCatalogue.of(
                caps + ContractEntry(
                    id = ContractId("step.echo.dup"),
                    capability = CapabilityId("step.echo.dup"),
                    owner = "core",
                    metadata = ContractMetadata(
                        minimumFidelity = HarnessFidelity.HF0,
                        requiresInstalledBinary = false,
                        requiresExternalCertifier = false,
                    ),
                    suiteFqcn = "dev.rubentxu.pipeline.v2.harness.EchoDupContractTest",
                    // Distinct pattern, but the path still matches both:
                    // catalogue rejects duplicate patterns but allows two
                    // contracts whose globs overlap.
                    sourcePatterns = listOf("v2/pipeline-step-sdk/runtime/src/main/kotlin/Echo*.kt"),
                ),
            )
            val plan = ImpactResolver.resolve(
                listOf(
                    ChangedInput.KotlinSource(
                        "v2/pipeline-step-sdk/runtime/src/main/kotlin/EchoStep.kt",
                    ),
                ),
                dup,
            )
            assertTrue(plan is ImpactDecision.Refused) { "expected Refused, got $plan" }
        }
    }

    @Nested
    inner class SelectedCases {
        @Test
        fun `single owned source selects its contract at HF0`() {
            val plan = ImpactResolver.resolve(
                listOf(
                    ChangedInput.KotlinSource(
                        "v2/pipeline-step-sdk/runtime/src/main/kotlin/EchoStep.kt",
                    ),
                ),
                catalogue,
            )
            val selected = plan as ImpactDecision.Selected
            assertEquals(HarnessFidelity.HF0, selected.minFidelity)
            assertEquals(setOf(ContractId("step.echo")), selected.contracts())
        }

        @Test
        fun `multiple sources with mixed fidelity select at the max`() {
            val plan = ImpactResolver.resolve(
                listOf(
                    ChangedInput.KotlinSource(
                        "v2/pipeline-step-sdk/runtime/src/main/kotlin/EchoStep.kt",
                    ),
                    ChangedInput.KotlinSource(
                        "v2/pipeline-step-sdk/runtime/src/main/kotlin/ShStep.kt",
                    ),
                ),
                catalogue,
            )
            val selected = plan as ImpactDecision.Selected
            assertEquals(HarnessFidelity.HF1, selected.minFidelity)
            assertEquals(
                setOf(ContractId("step.echo"), ContractId("step.sh")),
                selected.contracts(),
            )
        }

        @Test
        fun `NonProduction only produces empty plan at HF0`() {
            val plan = ImpactResolver.resolve(
                listOf(
                    ChangedInput.NonProduction("v2/pipeline-application/src/test/kotlin/SampleTest.kt"),
                    ChangedInput.NonProduction("docs/v2/05-roadmap/ROADMAP.md"),
                ),
                catalogue,
            )
            val selected = plan as ImpactDecision.Selected
            assertEquals(emptySet<ContractId>(), selected.contracts())
            assertEquals(HarnessFidelity.HF0, selected.minFidelity)
        }

        @Test
        fun `Mixed NonProduction and production selects production only`() {
            val plan = ImpactResolver.resolve(
                listOf(
                    ChangedInput.NonProduction("v2/pipeline-application/src/test/kotlin/SampleTest.kt"),
                    ChangedInput.KotlinSource(
                        "v2/pipeline-step-sdk/runtime/src/main/kotlin/EchoStep.kt",
                    ),
                ),
                catalogue,
            )
            assertTrue(plan is ImpactDecision.Selected) { "expected Selected, got $plan" }
            assertEquals(setOf(ContractId("step.echo")), plan.contracts())
        }

        @Test
        fun `glob pattern matches a kotlin source outside the literal pattern`() {
            val plan = ImpactResolver.resolve(
                listOf(
                    ChangedInput.KotlinSource(
                        "v2/pipeline-step-sdk/runtime/src/main/kotlin/CustomEchoStep.kt",
                    ),
                ),
                catalogue,
            )
            assertTrue(plan is ImpactDecision.Selected) { "expected Selected, got $plan" }
            assertEquals(setOf(ContractId("step.echo")), plan.contracts())
        }
    }
}
