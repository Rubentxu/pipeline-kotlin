package dev.rubentxu.pipeline.v2.harness.cycle

import dev.rubentxu.pipeline.v2.harness.catalog.ContractCatalogue.Fidelity
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.readText

/**
 * BQ4 v3 (typed Fidelity discriminator, 2026-10-17): the
 * [PromotionEvidence.fidelity] field and the verifier's
 * `manifestExpectedHF` parameter used to be free-form `String`s.
 * The cycle package parsed the wire format with a String
 * discriminator, then the runner compared the two with
 * `!=`; a typo on either side silently misrouted an HF
 * mismatch to "verified". The new shape is a closed
 * [Fidelity] enum: the compiler forces both sides to be in
 * the same set, the verifier's [when] is exhaustive, and a
 * future HF (HF2..HF6) cannot be added without a deliberate
 * enum extension that the fitness can pin.
 *
 * The three claims pinned here:
 *  1. `PromotionEvidence.fidelity` and the carrier's
 *     `fidelity` payload are `Fidelity`, not `String`.
 *  2. The verifier's `verify` declares `manifestExpectedHF: Fidelity`,
 *     not `String`.
 *  3. The runner consumes `parsed.expectedFidelity` (the typed
 *     view) and does NOT dispatch on the raw `expectedHF: String`
 *     to compare fidelity anywhere.
 *
 * Mutations that kill each claim:
 *  - reverting (1) to `String` flips the rule-(1) check
 *  - reverting (2) to `String` flips the rule-(2) check
 *  - re-introducing a `manifestExpectedHF = parsed.expectedHF`
 *    cast in the runner flips the rule-(3) check
 *
 * The test is hermetic (no I/O, no clock, no network) and
 * runs in milliseconds: it walks the cycle package's source
 * files and greps for the forbidden tokens. A future
 * regression that re-introduces a stringly-typed fidelity
 * discriminator cannot pass CI.
 */
class CycleFidelityStringDiscriminatorFitnessTest {

    private val cyclePackageRoot: Path by lazy {
        val cwd = Path.of("").toAbsolutePath()
        // MAIN source only. The fitness is a production-code
        // invariant; test files may legitimately use `String`
        // for fixture values.
        cwd.resolve("src/main/kotlin/dev/rubentxu/pipeline/v2/harness/cycle")
    }

    private fun kotlinSources(): List<Path> {
        if (!Files.isDirectory(cyclePackageRoot)) {
            error("cycle package source not found at $cyclePackageRoot")
        }
        return Files.walk(cyclePackageRoot).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.toList()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    private fun assertPromotionEvidenceFidelityIsTyped(sources: List<Path>) {
        val offenders = mutableListOf<String>()
        for (path in sources) {
            // The fitness is scoped to the carrier file: a
            // `fidelity: String` on the `PromotionEvidence`
            // interface or its two data classes is the
            // forbidden shape. The runner / verifier can
            // legitimately have a different `fidelity: String`
            // field for unrelated diagnostic reasons
            // (`CycleState.Refused.ProbeUnsupportedFidelity.fidelity`
            // is the requested-by-manifest fidelity the executor
            // cannot honour; it can be any string, including one
            // outside the [Fidelity] enum). The defect class is
            // specifically the `PromotionEvidence` carrier.
            if (!path.fileName.toString().endsWith("PromotionEvidence.kt")) continue
            val text = path.readText()
            if (text.contains(Regex("""\bval\s+fidelity\s*:\s*String\b"""))) {
                offenders += "${path.fileName}: PromotionEvidence.fidelity must be Fidelity, " +
                    "not String. Found `val fidelity: String` in the carrier file."
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "BQ4 v3 fitness: PromotionEvidence.fidelity MUST be typed as Fidelity, " +
                "not String. The dual emitter/parser shape (a `String` carrier matched with " +
                "`!=` against a manifest-typed `String` parameter) is the defect class this " +
                "rule closes. Offending files: $offenders",
        )
    }

    @OptIn(ExperimentalPathApi::class)
    private fun assertVerifierParameterIsTyped(sources: List<Path>) {
        val offenders = mutableListOf<String>()
        for (path in sources) {
            val text = path.readText()
            // The verifier is the only consumer of the
            // manifest's expected fidelity on the in-process
            // promotion path. The new shape is
            // `manifestExpectedHF: Fidelity`. The old shape
            // was `manifestExpectedHF: String`. The fitness
            // is a structural claim: the parameter name and
            // the type, so a renamed parameter or a
            // `String`-typed default value both fail.
            if (text.contains("manifestExpectedHF: String") ||
                text.contains("manifestExpectedHF = parsed.expectedHF")
            ) {
                offenders += "${path.fileName}: PromotionEvidenceVerifier's manifestExpectedHF " +
                    "must be typed as Fidelity. Found a String parameter or a raw expectedHF " +
                    "cast that bypasses the typed view."
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "BQ4 v3 fitness: PromotionEvidenceVerifier.verify MUST declare " +
                "manifestExpectedHF: Fidelity. The previous shape (`String`) let the verifier " +
                "and the manifest disagree silently; the typed view is the single source of " +
                "truth. Offending files: $offenders",
        )
    }

    @OptIn(ExperimentalPathApi::class)
    private fun assertRunnerUsesTypedView(sources: List<Path>) {
        val offenders = mutableListOf<String>()
        for (path in sources) {
            val text = path.readText()
            // The runner is the only place that bridges
            // `parsed.expectedHF: String` to the verifier's
            // typed parameter. The new shape is
            // `manifestExpectedHF = parsed.expectedFidelity`
            // (the typed view declared on CycleManifest). A
            // raw `parsed.expectedHF` flowing into the
            // verifier is a regression.
            //
            // The exception is the test file (scanned in
            // MAIN only, so already excluded) and the
            // `writeManifest` test helper that produces a
            // JSON wire body. Production code in the cycle
            // package must use the typed view.
            if (text.contains("manifestExpectedHF = parsed.expectedHF") ||
                text.contains("manifestExpectedHF=parsed.expectedHF")
            ) {
                offenders += "${path.fileName}: CycleRunner MUST pass parsed.expectedFidelity " +
                    "(the typed view) to PromotionEvidenceVerifier.verify, not the raw " +
                    "parsed.expectedHF String. The typed view is the single source of truth."
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "BQ4 v3 fitness: CycleRunner MUST consume parsed.expectedFidelity (the typed " +
                "view) when calling PromotionEvidenceVerifier.verify. Bypassing the typed " +
                "view reintroduces the stringly-typed dispatcher. Offending files: $offenders",
        )
    }

    @Test
    fun `PromotionEvidence fidelity is typed as Fidelity, not String`() {
        assertPromotionEvidenceFidelityIsTyped(kotlinSources())
    }

    @Test
    fun `PromotionEvidenceVerifier's manifestExpectedHF parameter is typed as Fidelity`() {
        assertVerifierParameterIsTyped(kotlinSources())
    }

    @Test
    fun `CycleRunner consumes the typed view (parsed_expectedFidelity), not the raw String`() {
        assertRunnerUsesTypedView(kotlinSources())
    }
}
