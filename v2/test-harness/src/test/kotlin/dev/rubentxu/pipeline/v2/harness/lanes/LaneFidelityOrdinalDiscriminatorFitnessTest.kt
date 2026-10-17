package dev.rubentxu.pipeline.v2.harness.lanes

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.readText

/**
 * BQ4 v5 (typed fidelity discriminator, 2026-10-17): the
 * [LaneSpec.minimumFidelity] field used to be `Int`, with the
 * planner dispatching on numeric ranges (`>= 3`, `== 2`, `>= 5`,
 * `>= 2`). The dual shape (typed source mapped to `Int` via
 * `.ordinal`, then numeric comparison) let a future HF7 silently
 * land in `RESTART` (`>= 3`) and a stray `Int = 99` (out of band)
 * silently land in `InstalledWithCertifier` (`>= 5`). The new
 * shape is a closed `Fidelity` enum: the compiler forces the
 * planner's `when` to be exhaustive and a future HF cannot be
 * added without a deliberate enum extension that the fitness
 * can pin.
 *
 * The three claims pinned here:
 *  1. `LaneSpec.minimumFidelity` is `Fidelity`, not `Int`.
 *  2. The planner's [LanePlanner.laneFor] dispatches on the
 *     enum, not on numeric comparisons on
 *     `spec.minimumFidelity`.
 *  3. The catalogue-to-spec mapper in
 *     [LanePlannerMain.realSpecsFromCatalogue] uses a typed
 *     `when` on the enum, not a numeric-range dispatch.
 *
 * Mutations that kill each claim:
 *  - reverting (1) to `Int` flips the rule-(1) check
 *  - re-introducing `spec.minimumFidelity >= 3` flips rule (2)
 *  - re-introducing `fidelity >= 5` in the mapper flips rule (3)
 *
 * The test is hermetic (no I/O, no clock, no network) and
 * runs in milliseconds: it walks the lanes package's source
 * files and greps for the forbidden tokens. A future
 * regression that re-introduces a stringly-typed / numeric
 * fidelity discriminator cannot pass CI.
 */
class LaneFidelityOrdinalDiscriminatorFitnessTest {

    private val lanesPackageRoot: Path by lazy {
        val cwd = Path.of("").toAbsolutePath()
        cwd.resolve("src/main/kotlin/dev/rubentxu/pipeline/v2/harness/lanes")
    }

    private fun kotlinSources(): List<Path> {
        if (!Files.isDirectory(lanesPackageRoot)) {
            error("lanes package source not found at $lanesPackageRoot")
        }
        return Files.walk(lanesPackageRoot).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.toList()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    private fun assertLaneSpecFidelityIsTyped(sources: List<Path>) {
        val offenders = mutableListOf<String>()
        for (path in sources) {
            val text = path.readText()
            // The new shape is `val minimumFidelity: Fidelity`.
            // The old shape was `val minimumFidelity: Int`.
            // The 0..6 range check (`require(minimumFidelity in 0..6)`)
            // is also forbidden: it was the previous shape's
            // range guard, replaced by the type system.
            if (text.contains(Regex("""\bval\s+minimumFidelity\s*:\s*Int\b""")) ||
                text.contains("minimumFidelity in 0..6")
            ) {
                offenders += "${path.fileName}: LaneSpec.minimumFidelity must be Fidelity, " +
                    "not Int. Found `val minimumFidelity: Int` or a 0..6 range check that " +
                    "the closed enum makes redundant."
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "BQ4 v5 fitness: LaneSpec.minimumFidelity MUST be typed as Fidelity, " +
                "not Int. The dual shape (typed source mapped to Int, then numeric compare) " +
                "is the defect class this rule closes. Offending files: $offenders",
        )
    }

    @OptIn(ExperimentalPathApi::class)
    private fun assertPlannerDispatchesOnEnum(sources: List<Path>) {
        val offenders = mutableListOf<String>()
        for (path in sources) {
            val text = path.readText()
            // The new shape dispatches on the enum:
            //   when (spec.minimumFidelity) { Fidelity.HF0 -> ... }
            // The old shape compared an Int field:
            //   spec.minimumFidelity >= 3
            //   spec.minimumFidelity == 2
            val numericCompare = Regex("""spec\.minimumFidelity\s*(>=|<=|==|!=|>|<)\s*\d+""")
            if (numericCompare.containsMatchIn(text)) {
                offenders += "${path.fileName}: LanePlanner MUST dispatch on the Fidelity " +
                    "enum (exhaustive when), not on numeric comparisons on " +
                    "`spec.minimumFidelity`. Found a forbidden numeric compare."
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "BQ4 v5 fitness: LanePlanner.laneFor MUST consume the Fidelity enum " +
                "via an exhaustive `when`, not via numeric comparisons on " +
                "`spec.minimumFidelity`. A numeric `>= 3` on an `Int` field is the " +
                "defect class this rule closes. Offending files: $offenders",
        )
    }

    @OptIn(ExperimentalPathApi::class)
    private fun assertCatalogueMapperUsesTypedWhen(sources: List<Path>) {
        val offenders = mutableListOf<String>()
        for (path in sources) {
            val text = path.readText()
            // The new shape is `access = when (fidelity) { Fidelity.HF0, ... -> ... }`.
            // The old shape was a numeric-range dispatch on the
            // `.ordinal` of the contract's minFidelity:
            //   when { fidelity >= 5 -> ...; fidelity >= 2 -> ...; else -> ... }
            val numericRange = Regex("""fidelity\s*(>=|<=|==|!=|>|<)\s*\d+""")
            if (numericRange.containsMatchIn(text)) {
                offenders += "${path.fileName}: LanePlannerMain.realSpecsFromCatalogue " +
                    "MUST map the Fidelity enum via a typed `when`, not via numeric " +
                    "comparisons. Found a forbidden `fidelity >= N` pattern."
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "BQ4 v5 fitness: LanePlannerMain.realSpecsFromCatalogue MUST consume " +
                "the contract's Fidelity enum via a typed `when`, not via numeric " +
                "comparisons. A numeric `fidelity >= 5` on a `.ordinal` is the defect " +
                "class this rule closes. Offending files: $offenders",
        )
    }

    @Test
    fun `LaneSpec minimumFidelity is typed as Fidelity, not Int`() {
        assertLaneSpecFidelityIsTyped(kotlinSources())
    }

    @Test
    fun `LanePlanner laneFor dispatches on the Fidelity enum, not numeric comparisons`() {
        assertPlannerDispatchesOnEnum(kotlinSources())
    }

    @Test
    fun `LanePlannerMain catalogue mapper uses a typed when, not numeric ranges`() {
        assertCatalogueMapperUsesTypedWhen(kotlinSources())
    }
}
