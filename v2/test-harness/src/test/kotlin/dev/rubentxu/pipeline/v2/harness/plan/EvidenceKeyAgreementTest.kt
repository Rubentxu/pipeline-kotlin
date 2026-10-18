package dev.rubentxu.pipeline.v2.harness.plan

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The producer and the consumer must agree on the KEY, not merely on the concept.
 *
 * ## What this crosses
 *
 * [EvidenceReader.verify] — the production entry point. It calls
 * [EvidenceReader.suitesWithEvidence] (the producer) and hands its map straight to
 * [ImpactResolver.adjudicate] (the consumer), with nothing in between to reconcile the two.
 *
 * Before this class, nothing in the tree called the two in sequence. The producer had its own
 * test ([EvidenceReaderTest]) using synthetic package names nobody binds to, and the consumer had
 * its own tests ([ImpactResolverAdversarialTest]) fed by [adjudicateEvidence], a helper that keys
 * its map with `suite.filter` — the simple class name. Each side was pinned, and the seam between
 * them was untested, so the two halves could disagree and stay green. That is HARNESS FIDELITY
 * LAW §1: a harness that does not cross the productive authority certifies its own fixtures.
 *
 * ## The disagreement this found
 *
 * The producer keys by the FQCN the report declares. The consumer looks up
 * `TestSuiteId.fqcn`, and `TestSuiteId.fqcn` defaults to `filter`, which every production binding
 * in [SuitePolicy.BINDINGS] leaves at the SIMPLE class name — 16 construction sites,
 * none of which passes `fqcn`. So the lookup is
 * `executedSuites["ContractCatalogueFitnessTest"]` against a map whose only key is
 * `dev.rubentxu.pipeline.v2.harness.plan.ContractCatalogueFitnessTest`: a guaranteed miss.
 *
 * A guaranteed miss is fail-closed, so this was not a silent green — it was a gate that refuses
 * every selected suite forever, which cannot discharge any plan and therefore cannot certify any
 * run. The refusal reason (`SelectedButNotRun`) names a suite that demonstrably ran, so the
 * operator is sent to look for a runner that never failed.
 *
 * Two comments asserted the old behaviour and outlived the change: [EvidenceReader]'s own KDoc
 * claims "the lookup matches the plan's `TestSuiteId.fqcn` which is the FQCN the producer wrote",
 * and `Gate`'s block comment still says "the evidence map is keyed by simple class name (the
 * binding's filter)" directly above code that no longer does that. [FqcnIdentityFitnessTest]
 * scans the producer structurally and is green; it cannot see a consumer.
 *
 * ## What this does NOT assert
 *
 * Fidelity adequacy, commit binding, and the sidecar's presence are separate properties with their
 * own rows. This class holds exactly one: a report on disk for a bound suite discharges the plan
 * that selected it. Minimum fidelities are left empty so a failure here cannot be a fidelity
 * failure in disguise.
 *
 * ## Measured: this class alone does NOT certify the fix
 *
 * Two mutations, each applied to the production code and each restored afterwards with a verified
 * SHA-256. Both run the same two classes.
 *
 * | mutation | this class | [SuiteBindingIdentityFitnessTest] |
 * | --- | --- | --- |
 * | `EvidenceReader`: key the map by `fqcn.substringAfterLast(".")` (the pre-HITO-1 shape) | **RED** | green |
 * | `ValidationPlan`: drop one binding's `fqcn`, so it falls back to `filter` | **green** | **RED** (2/2) |
 *
 * The second row is the point. This class derives the FQCN from the binding and writes its report
 * from that same FQCN, so when the binding drifts to the simple name the two halves drift together
 * and the row agrees with itself. That is the declared limitation above, now measured rather than
 * asserted — and it is why the fix is certified by the pair, not by this row alone. Reporting the
 * green under the second mutation as evidence that the wedge is closed would be exactly the
 * "a green that survives its mutation is vacuous" failure: this row's green is only meaningful
 * beside the other class's red.
 */
class EvidenceKeyAgreementTest {

    /** A suite a production binding actually names, taken from the catalogue rather than retyped. */
    private val bound: TestSuiteId = SuitePolicy.BINDINGS
        .flatMap { it.suites }
        .first { it.filter == "ContractCatalogueFitnessTest" }

    private val commit = "a".repeat(40)
    private val binarySha = "0".repeat(64)

    @Test
    fun `a report on disk discharges the plan that selected the suite`(@TempDir resultsDir: Path) {
        writeReport(resultsDir)

        // The productive path, in one call: producer -> consumer, nothing in between.
        val refusal = EvidenceReader.verify(
            plan = planSelecting(bound),
            resultsDir = resultsDir,
            expectedCommit = commit,
            observedCommit = commit,
        )

        assertNull(
            refusal,
            "the suite ran and left a valid report, so it must discharge the plan. A refusal here " +
                "means the producer keyed the evidence by '${bound.fqcn}' while the consumer " +
                "looked up '${bound.filter}', so every selected suite in production is refused as " +
                "SelectedButNotRun forever.",
        )
    }

    /**
     * The plan under study, built from a real production binding so the test cannot drift away
     * from the catalogue it is about.
     */
    private fun planSelecting(suite: TestSuiteId): ValidationPlan = ValidationPlan.Selected(
        capabilities = emptySet(),
        contracts = emptySet(),
        suites = setOf(suite),
        minimumFidelities = emptySet(),
        reasons = emptyList(),
    )

    /**
     * A complete, honest report: the XML Gradle would emit (the FQCN in BOTH the `testsuite`
     * attribute and the file name) and the sidecar the manifest contract requires.
     *
     * The FQCN comes from the binding rather than a literal here. That is deliberate: this class
     * owns the KEY AGREEMENT property, and a retyped FQCN would make it a second place to fix
     * when a suite moves. Whether each binding's FQCN is CORRECT is a different property, owned
     * by `SuiteBindingIdentityFitnessTest`.
     */
    private fun writeReport(dir: Path) {
        Files.writeString(
            dir.resolve("TEST-${bound.fqcn}.xml"),
            """<testsuite name="${bound.fqcn}" tests="3" skipped="0" failures="0" errors="0"/>""",
        )
        Files.writeString(
            dir.resolve("${bound.fqcn}.json"),
            """
            {
              "class_name": "${bound.fqcn}",
              "gradle_path": "${bound.gradleProject}",
              "invocation": "gradle ${bound.gradleProject}:test --tests ${bound.filter}",
              "binary_sha256": "$binarySha",
              "hf": "HF0",
              "source_commit": "$commit"
            }
            """.trimIndent(),
        )
    }
}