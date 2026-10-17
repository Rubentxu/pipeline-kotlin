package dev.rubentxu.pipeline.v2.harness.catalog

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * H0 — the catalogue must not be able to drift away from the repository it describes.
 *
 * ## Why this needs a fitness test at all
 *
 * The catalogue is data, and data has no compiler error when it becomes wrong. A row naming a
 * class that was renamed, a path that was moved, or a module that was deleted all stay perfectly
 * valid Kotlin and quietly select nothing. That is the failure mode the mandate calls out: the
 * harness falls behind the product and nobody notices, because nothing failed.
 *
 * So the catalogue is checked against the real filesystem and against itself, in both directions:
 * every path it names must exist, and every rule must reference a capability that exists.
 *
 * ## The direction that is easy to forget
 *
 * `everyNamedPathExists` catches a catalogue that drifts forward into a renamed file. The other
 * direction — a source file that changes a guarantee but is in no catalogue row — cannot be caught
 * by reading the catalogue, so it is stated here as an explicit coverage claim with a named set of
 * the Output Plane files it is known to cover. When PipelineK grows a new output route, that set is
 * what has to be extended, and this test is where extending it becomes visible.
 */
class ContractCatalogueFitnessTest {

    private fun repoRoot(): Path {
        var dir: Path? = Path.of("").toAbsolutePath()
        while (dir != null && !Files.exists(dir.resolve(".git"))) dir = dir.parent
        check(dir != null) { "could not locate the checkout root from ${Path.of("").toAbsolutePath()}" }
        return dir
    }

    @Test
    @DisplayName("every path the catalogue names is a real file")
    fun everyNamedPathExists() {
        val root = repoRoot()
        val missing = ContractCatalogue.CONTRACTS
            .flatMap { contract -> contract.sourcePaths.map { contract.capability to it } }
            .filter { (_, path) -> !Files.exists(root.resolve(path)) }
        assertTrue(
            missing.isEmpty(),
            "the catalogue names source paths that do not exist, so a change to the real file " +
                "would select nothing and the harness would silently under-cover it: $missing",
        )
    }

    @Test
    @DisplayName("no contract is a row no change can ever trigger")
    fun everyContractHasAtLeastOneTrigger() {
        val unreachable = ContractCatalogue.CONTRACTS.filter { it.sourcePaths.isEmpty() }
        assertTrue(
            unreachable.isEmpty(),
            "a contract with no sourcePaths is an aspiration, not a binding: " +
                "${unreachable.map { it.capability }}",
        )
    }

    @Test
    @DisplayName("capability names are unique, so one change cannot select two rows with the same meaning")
    fun capabilitiesAreUnique() {
        val duplicates = ContractCatalogue.CONTRACTS
            .groupBy { it.capability }
            .filterValues { it.size > 1 }
            .keys
        assertTrue(duplicates.isEmpty(), "duplicate capability names would make selection ambiguous: $duplicates")
    }

    @Test
    @DisplayName("every escalation names a capability that exists")
    fun escalationsReferenceRealCapabilities() {
        val known = ContractCatalogue.CONTRACTS.map { it.capability }.toSet()
        val orphans = ContractCatalogue.ESCALATIONS.filter { it.capability !in known }
        assertTrue(
            orphans.isEmpty(),
            "an escalation for an unknown capability silently never applies: " +
                "${orphans.map { it.capability }}",
        )
    }

    @Test
    @DisplayName("an escalation only ever raises the level")
    fun escalationsOnlyRaise() {
        val order = listOf(
            ContractCatalogue.Fidelity.HF0,
            ContractCatalogue.Fidelity.HF1,
            ContractCatalogue.Fidelity.HF2,
            ContractCatalogue.Fidelity.HF3,
            ContractCatalogue.Fidelity.HF4,
        )
        val backwards = ContractCatalogue.ESCALATIONS.filter { rule ->
            order.indexOf(rule.to) < order.indexOf(rule.from)
        }
        assertTrue(
            backwards.isEmpty(),
            "an escalation must not lower fidelity; doing so would let a change to a commit path " +
                "claim it is answerable by a cheaper test: " +
                "${backwards.map { "${it.capability} ${it.from}->${it.to}" }}",
        )
    }

    @Test
    @DisplayName("a change to the redaction path selects the HF2 case the mandate names")
    fun aRedactorChangeSelectsTheDistributionLevel() {
        // The worked example from H0: touching StreamingRedactor must select an HF2 requirement,
        // because the guarantee is that bytes are redacted on the way through a real process, and
        // no pure-contract test can observe that.
        val selected = ContractCatalogue.contractsFor(
            "v2/pipeline-credentials-api/src/main/kotlin/dev/rubentxu/pipeline/v2/credentials/api/StreamingRedactor.kt",
        )
        assertFalse(selected.isEmpty(), "a change to the redactor must select contracts")
        assertTrue(
            ContractCatalogue.Fidelity.HF2 in ContractCatalogue.requiredFidelityFor(
                "v2/pipeline-credentials-api/src/main/kotlin/dev/rubentxu/pipeline/v2/credentials/api/StreamingRedactor.kt",
            ),
            "the redactor guarantee is only observable at the distribution boundary",
        )
    }

    @Test
    @DisplayName("a change to the read path escalates to restart fidelity")
    fun aReadChangeEscalatesToHf3() {
        val path = "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/OutputPlaneProvider.kt"
        val required = ContractCatalogue.requiredFidelityFor(path)
        assertTrue(
            ContractCatalogue.Fidelity.HF3 in required,
            "a change to the read side can keep the bytes intact while breaking resume, so HF2 " +
                "alone would under-claim; required was $required",
        )
    }

    @Test
    @DisplayName("an unrelated path selects nothing, rather than everything")
    fun unrelatedChangesSelectNothing() {
        // The selector's value is discrimination. If every path matched, the catalogue would be a
        // constant and would cost a full run per commit.
        val selected = ContractCatalogue.contractsFor("docs/v2/04-adrs/ADR-0099-trunk-authority.md")
        assertTrue(
            selected.isEmpty(),
            "a documentation change must not drag in the Output Plane contracts: $selected",
        )
    }

    @Test
    @DisplayName("the Output Plane files the mandate names are all covered by some row")
    fun mandateNamedFilesAreCovered() {
        // The explicit coverage claim from the H0 KDoc. A new output route must be added here,
        // and this row is where its absence becomes a red test rather than a silent gap.
        val required = listOf(
            "StreamingRedactor",
            "DurableShellExecutor",
            "RedactingOutputIngress",
            "ShExecution",
            "OutputPlaneProvider",
            "MainConsoleCli",
        )
        val covered = ContractCatalogue.CONTRACTS
            .flatMap { it.sourcePaths }
            .map { it.substringAfterLast('/') }
            .toSet()
        val uncovered = required.filterNot { name -> covered.any { it.startsWith(name) } }
        assertTrue(
            uncovered.isEmpty(),
            "these Output Plane participants are named by the H0 mandate but appear in no " +
                "catalogue row, so a change to them would select nothing: $uncovered",
        )
    }

    @Test
    @DisplayName("no row names a test file as if it were product code")
    fun noTestFilesAreClassifiedAsProduction() {
        // Added after this slice catalogued AppBinSupport.kt, which lives in src/test. The path
        // fitness caught it as a missing file, but the real defect was sharper: a TEST helper was
        // registered as a product guarantee, so editing a helper would have demanded distribution
        // fidelity it cannot bear, and the product's own provenance boundary went uncovered.
        //
        // This pins the category, not the instance. Adding a row that names a helper must fail
        // here for the same reason the first one did.
        val testPaths = ContractCatalogue.CONTRACTS
            .flatMap { it.sourcePaths }
            .filter { it.contains("/src/test/") }
        assertTrue(
            testPaths.isEmpty(),
            "these catalogue rows name test sources as product code. A harness helper is not a " +
                "product guarantee: $testPaths",
        )
    }

    @Test
    @DisplayName("the catalogue records a distinct owner per capability")
    fun ownersAreRecorded() {
        val blank = ContractCatalogue.CONTRACTS.filter { it.owningModule.isBlank() }
        assertTrue(blank.isEmpty(), "a contract with no owning module cannot drive module selection: $blank")
        assertEquals(
            ContractCatalogue.CONTRACTS.size,
            ContractCatalogue.CONTRACTS.map { it.capability }.toSet().size,
        )
    }

    @Test
    @DisplayName("every pathPattern matches at least one file under the checkout")
    fun everyPathPatternMatchesAtLeastOneFile() {
        // The pathPatterns field (added in BQ1 RC2) lets a contract
        // claim broader coverage without enumerating every file. A
        // pattern that matches NOTHING is the dual-emitter defect
        // class: the resolver returns false forever and nobody
        // notices because the contract never fires. Pinning "at
        // least one file matches" closes that gap. The catalogue
        // owns the matcher's contract (ContractCatalogue.matchesPattern
        // is the implementation); the test reuses it so a future
        // glob-syntax change is reflected in the fitness without a
        // second copy of the matcher.
        val root = repoRoot()
        val unusedPatterns = mutableListOf<Pair<String, String>>()
        // Snapshot the file list ONCE per call: walking the whole
        // checkout for every pattern would multiply the I/O by the
        // number of patterns. The list is small enough to fit in
        // memory (one entry per file in the tree, kept as a
        // repository-relative string for the matcher).
        val allFiles: List<String> = Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .map { root.relativize(it).toString().replace('\\', '/') }
                .toList()
        }
        for (contract in ContractCatalogue.CONTRACTS) {
            for (pattern in contract.pathPatterns) {
                val anyMatch = allFiles.any { path ->
                    ContractCatalogue.matchesPattern(path, pattern)
                }
                if (!anyMatch) {
                    unusedPatterns += contract.capability to pattern
                }
            }
        }
        assertTrue(
            unusedPatterns.isEmpty(),
            "these pathPatterns match no file under the checkout. The contract's " +
                "broader-coverage claim is silently false: a change to a file the pattern " +
                "was supposed to cover would select nothing. Add a literal sourcePath, " +
                "fix the glob, or remove the pattern. Offending (capability, pattern): " +
                "$unusedPatterns",
        )
    }
}
