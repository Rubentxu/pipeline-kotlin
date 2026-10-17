package dev.rubentxu.pipeline.v2.harness.plan

import dev.rubentxu.pipeline.v2.harness.catalog.ContractCatalogue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * H1 TODO 2: the [EvidenceManifest] sidecar is the
 * self-describing carrier for per-suite evidence. The
 * [EvidenceReader] reads the XML AND the sidecar; the
 * gate refuses on missing sidecars, mismatched
 * binaries, and insufficient fidelities. Each test
 * pins ONE property so a future regression breaks the
 * test, not the cycle.
 */
class EvidenceManifestTest {

    private val validBinarySha = "0".repeat(64)
    private val validSourceCommit = "0".repeat(40)
    private val validClassName = "FooTest"
    private val validGradlePath = ":test-harness"
    private val validInvocation = "gradle :test-harness:test --tests FooTest"
    private val validHf = "HF0"

    private fun writeSidecar(
        dir: Path,
        className: String,
        body: String = """
            {
              "class_name": "$className",
              "gradle_path": "$validGradlePath",
              "invocation": "$validInvocation",
              "binary_sha256": "$validBinarySha",
              "hf": "$validHf",
              "source_commit": "$validSourceCommit"
            }
        """.trimIndent(),
    ) {
        Files.writeString(dir.resolve("$className.json"), body)
    }

    private fun writeXml(dir: Path, className: String) {
        Files.writeString(
            dir.resolve("TEST-$className.xml"),
            """<testsuite name="$className" tests="3" skipped="0" failures="0" errors="0"/>""",
        )
    }

    @Test
    fun `a well-formed sidecar parses and exposes its fields`(@TempDir tmp: Path) {
        val dir = tmp.resolve("results")
        Files.createDirectories(dir)
        writeSidecar(dir, validClassName)
        val manifest = EvidenceManifest.load(dir.resolve("$validClassName.json"))
        assertEquals(validClassName, manifest.className)
        assertEquals(validGradlePath, manifest.gradlePath)
        assertEquals(validInvocation, manifest.invocation)
        assertEquals(validBinarySha, manifest.binarySha256)
        assertEquals(validHf, manifest.hf)
        assertEquals(validSourceCommit, manifest.sourceCommit)
    }

    @Test
    fun `a sidecar with a blank class name is rejected at construction`() {
        // The data class init is the first line of
        // defence; a sidecar with a blank field MUST
        // throw before the gate can read it.
        val ex = assertThrows<IllegalArgumentException> {
            EvidenceManifest(
                className = "",
                gradlePath = validGradlePath,
                invocation = validInvocation,
                binarySha256 = validBinarySha,
                hf = validHf,
                sourceCommit = validSourceCommit,
            )
        }
        assertNotNull(ex.message)
    }

    @Test
    fun `a sidecar with a non-hex binary sha is rejected`() {
        val ex = assertThrows<IllegalArgumentException> {
            EvidenceManifest(
                className = validClassName,
                gradlePath = validGradlePath,
                invocation = validInvocation,
                binarySha256 = "z".repeat(64),
                hf = validHf,
                sourceCommit = validSourceCommit,
            )
        }
        assertTrue(ex.message!!.contains("binarySha256"))
    }

    @Test
    fun `a sidecar with a short source commit is rejected`() {
        val ex = assertThrows<IllegalArgumentException> {
            EvidenceManifest(
                className = validClassName,
                gradlePath = validGradlePath,
                invocation = validInvocation,
                binarySha256 = validBinarySha,
                hf = validHf,
                sourceCommit = "abc123",
            )
        }
        assertTrue(ex.message!!.contains("sourceCommit"))
    }

    @Test
    fun `a sidecar with a 64-char but non-hex binary sha is rejected`() {
        val ex = assertThrows<IllegalArgumentException> {
            EvidenceManifest(
                className = validClassName,
                gradlePath = validGradlePath,
                invocation = validInvocation,
                binarySha256 = "g".repeat(64),
                hf = validHf,
                sourceCommit = validSourceCommit,
            )
        }
        assertTrue(ex.message!!.contains("lowercase hex"))
    }

    @Test
    fun `a missing sidecar file is not evidence (gate refuses on IncompleteEvidence)`(
        @TempDir tmp: Path,
    ) {
        val dir = tmp.resolve("results")
        Files.createDirectories(dir)
        writeXml(dir, validClassName)
        // The XML is present; the sidecar is NOT.
        val evidence = EvidenceReader.suitesWithEvidence(dir)
        val suite = evidence[validClassName]
        assertNotNull(suite, "the XML should be visible to the reader")
        assertTrue(
            suite is EvidenceReader.SuiteEvidence.MissingSidecar,
            "a missing sidecar MUST surface as MissingSidecar so the gate refuses " +
                "with IncompleteEvidence; got $suite",
        )
    }

    @Test
    fun `a malformed sidecar is not evidence (gate refuses on MalformedSidecar)`(
        @TempDir tmp: Path,
    ) {
        val dir = tmp.resolve("results")
        Files.createDirectories(dir)
        writeXml(dir, validClassName)
        Files.writeString(
            dir.resolve("$validClassName.json"),
            "{ this is not valid JSON",
        )
        val suite = EvidenceReader.suitesWithEvidence(dir)[validClassName]
        assertNotNull(suite)
        assertTrue(
            suite is EvidenceReader.SuiteEvidence.MalformedSidecar,
            "a malformed sidecar MUST surface as MalformedSidecar so the gate " +
                "refuses with MalformedSidecar and the reason is preserved; got $suite",
        )
    }

    @Test
    fun `a sidecar with a missing required field is rejected by the data class`(
        @TempDir tmp: Path,
    ) {
        val dir = tmp.resolve("results")
        Files.createDirectories(dir)
        writeXml(dir, validClassName)
        Files.writeString(
            dir.resolve("$validClassName.json"),
            """
            {
              "class_name": "FooTest",
              "gradle_path": "$validGradlePath",
              "invocation": "$validInvocation",
              "binary_sha256": "$validBinarySha",
              "hf": "$validHf"
            }
            """.trimIndent(),
        )
        val suite = EvidenceReader.suitesWithEvidence(dir)[validClassName]
        // The sidecar is malformed (missing source_commit)
        // and the loader MUST NOT silently default. The
        // gate's MalformedSidecar refusal handles this
        // case; the parser's message is preserved on the
        // typed carrier.
        assertNotNull(suite)
        assertTrue(
            suite is EvidenceReader.SuiteEvidence.MalformedSidecar,
            "a sidecar with a missing required field MUST surface as " +
                "MalformedSidecar with the parser's reason; got $suite",
        )
    }

    @Test
    fun `the gate refuses on a missing sidecar with IncompleteEvidence`(@TempDir tmp: Path) {
        // End-to-end: a plan selects a suite; the suite's
        // XML exists; the sidecar is missing. The gate
        // refuses on IncompleteEvidence naming the
        // missing field, NOT SelectedButNotRun (the XML
        // IS there). This is the H1 mandate's
        // "Datos incompletos -> Refused" half.
        val dir = tmp.resolve("results")
        Files.createDirectories(dir)
        writeXml(dir, validClassName)

        val plan = ValidationPlan.Selected(
            capabilities = setOf("x"),
            contracts = emptySet(),
            suites = setOf(TestSuiteId(validClassName, validGradlePath)),
            minimumFidelities = emptySet(),
            reasons = emptyList(),
        )
        val refusal = EvidenceReader.verify(plan, dir, validSourceCommit, validSourceCommit)
        assertNotNull(refusal)
        val reasons = refusal!!.reasons
        assertTrue(
            reasons.any { it is SelectionRefusal.IncompleteEvidence && it.suite == validClassName },
            "the refusal must be IncompleteEvidence, got $reasons",
        )
        assertTrue(
            !reasons.any { it is SelectionRefusal.SelectedButNotRun },
            "the XML IS there, so SelectedButNotRun is wrong; the sidecar is the issue, got $reasons",
        )
    }

    @Test
    fun `the gate refuses on a malformed sidecar with MalformedSidecar and the parser reason`(
        @TempDir tmp: Path,
    ) {
        // End-to-end: a plan selects a suite; the suite's
        // XML exists; the sidecar JSON is malformed. The
        // gate refuses on MalformedSidecar (NOT
        // IncompleteEvidence: the file IS there, the JSON
        // IS the issue). The parser's reason is preserved
        // on the refusal so the operator sees a precise
        // remediation.
        val dir = tmp.resolve("results")
        Files.createDirectories(dir)
        writeXml(dir, validClassName)
        Files.writeString(dir.resolve("$validClassName.json"), "{ not valid json")

        val plan = ValidationPlan.Selected(
            capabilities = setOf("x"),
            contracts = emptySet(),
            suites = setOf(TestSuiteId(validClassName, validGradlePath)),
            minimumFidelities = emptySet(),
            reasons = emptyList(),
        )
        val refusal = EvidenceReader.verify(plan, dir, validSourceCommit, validSourceCommit)
        assertNotNull(refusal)
        val reasons = refusal!!.reasons
        val malformed = reasons.filterIsInstance<SelectionRefusal.MalformedSidecar>()
        assertTrue(
            malformed.isNotEmpty(),
            "the refusal must be MalformedSidecar (not IncompleteEvidence), got $reasons",
        )
        val m = malformed.first { it.suite == validClassName }
        assertTrue(
            m.reason.isNotBlank(),
            "the parser's reason must be preserved on the refusal so the operator sees " +
                "what is malformed; got blank reason",
        )
        assertTrue(
            !reasons.any { it is SelectionRefusal.IncompleteEvidence },
            "the sidecar file IS there, so IncompleteEvidence is the wrong refusal; got $reasons",
        )
    }

    @Test
    fun `the gate refuses on a sidecar whose binary was built from a different commit`(
        @TempDir tmp: Path,
    ) {
        // The sidecar declares sourceCommit = "b"; the
        // expected commit is "a". The gate refuses on
        // StaleBinary naming the expected and observed.
        val dir = tmp.resolve("results")
        Files.createDirectories(dir)
        writeXml(dir, validClassName)
        val staleCommit = "b".repeat(40)
        writeSidecar(
            dir,
            validClassName,
            body = """
                {
                  "class_name": "FooTest",
                  "gradle_path": "$validGradlePath",
                  "invocation": "$validInvocation",
                  "binary_sha256": "$validBinarySha",
                  "hf": "$validHf",
                  "source_commit": "$staleCommit"
                }
            """.trimIndent(),
        )
        val plan = ValidationPlan.Selected(
            capabilities = setOf("x"),
            contracts = emptySet(),
            suites = setOf(TestSuiteId(validClassName, validGradlePath)),
            minimumFidelities = emptySet(),
            reasons = emptyList(),
        )
        val refusal = EvidenceReader.verify(plan, dir, validSourceCommit, validSourceCommit)
        assertNotNull(refusal)
        val reasons = refusal!!.reasons
        val stale = reasons.filterIsInstance<SelectionRefusal.StaleBinary>()
        assertTrue(stale.isNotEmpty(), "expected StaleBinary, got $reasons")
        assertEquals(validSourceCommit, stale.first().expected)
        assertEquals(staleCommit, stale.first().observed)
    }

    @Test
    fun `the gate refuses on a sidecar whose fidelity is below the plan minimum`(
        @TempDir tmp: Path,
    ) {
        // The plan requires HF2; the sidecar declares HF0.
        // The gate refuses on InsufficientFidelity naming
        // both the observed and the required set.
        val dir = tmp.resolve("results")
        Files.createDirectories(dir)
        writeXml(dir, validClassName)
        writeSidecar(
            dir,
            validClassName,
            body = """
                {
                  "class_name": "FooTest",
                  "gradle_path": "$validGradlePath",
                  "invocation": "$validInvocation",
                  "binary_sha256": "$validBinarySha",
                  "hf": "HF0",
                  "source_commit": "$validSourceCommit"
                }
            """.trimIndent(),
        )
        val plan = ValidationPlan.Selected(
            capabilities = setOf("x"),
            contracts = emptySet(),
            suites = setOf(TestSuiteId(validClassName, validGradlePath)),
            minimumFidelities = setOf(ContractCatalogue.Fidelity.HF2),
            reasons = emptyList(),
        )
        val refusal = EvidenceReader.verify(plan, dir, validSourceCommit, validSourceCommit)
        assertNotNull(refusal)
        val reasons = refusal!!.reasons
        val insufficient = reasons.filterIsInstance<SelectionRefusal.InsufficientFidelity>()
        assertTrue(insufficient.isNotEmpty(), "expected InsufficientFidelity, got $reasons")
        assertEquals(ContractCatalogue.Fidelity.HF0, insufficient.first().observed)
        assertTrue(insufficient.first().required.contains(ContractCatalogue.Fidelity.HF2))
    }

    @Test
    fun `the gate accepts when the sidecar's fidelity matches the plan minimum`(
        @TempDir tmp: Path,
    ) {
        // Sanity: a sidecar with HF2 and a plan with
        // minimumFidelities = {HF2} must NOT raise
        // InsufficientFidelity. The plan's other
        // properties (commit, sidecar presence) are
        // also satisfied; the gate accepts.
        val dir = tmp.resolve("results")
        Files.createDirectories(dir)
        writeXml(dir, validClassName)
        writeSidecar(
            dir,
            validClassName,
            body = """
                {
                  "class_name": "FooTest",
                  "gradle_path": "$validGradlePath",
                  "invocation": "$validInvocation",
                  "binary_sha256": "$validBinarySha",
                  "hf": "HF2",
                  "source_commit": "$validSourceCommit"
                }
            """.trimIndent(),
        )
        val plan = ValidationPlan.Selected(
            capabilities = setOf("x"),
            contracts = emptySet(),
            suites = setOf(TestSuiteId(validClassName, validGradlePath)),
            minimumFidelities = setOf(ContractCatalogue.Fidelity.HF2),
            reasons = emptyList(),
        )
        val refusal = EvidenceReader.verify(plan, dir, validSourceCommit, validSourceCommit)
        assertEquals(null, refusal, "a sidecar that matches every gate property must pass")
    }

    @Test
    fun `a sidecar whose hf is not a known Fidelity name is refused at load`(
        @TempDir tmp: Path,
    ) {
        // BQ4 v4 (typed sidecar fidelity, 2026-10-17): the
        // previous shape accepted any non-blank `hf` String
        // and let the gate's `m.hf !in requiredFidelities`
        // comparison fail silently. A malformed fidelity
        // (e.g. "HFX", "hf0", "") was structurally valid
        // but semantically unreachable. The init now
        // strict-validates `hf` against the closed
        // [Fidelity] enum's name set; a throw with a
        // precise message names the format requirement.
        val dir = tmp.resolve("results")
        Files.createDirectories(dir)
        writeXml(dir, validClassName)
        writeSidecar(
            dir,
            validClassName,
            body = """
                {
                  "class_name": "FooTest",
                  "gradle_path": "$validGradlePath",
                  "invocation": "$validInvocation",
                  "binary_sha256": "$validBinarySha",
                  "hf": "HFX",
                  "source_commit": "$validSourceCommit"
                }
            """.trimIndent(),
        )
        val file = dir.resolve("$validClassName.json")
        val ex = assertThrows<IllegalArgumentException> {
            EvidenceManifest.load(file)
        }
        assertTrue(
            (ex.message ?: "").contains("HF0, HF1, HF2"),
            "refusal must name the allowed-set requirement; got ${ex.message}",
        )
    }

    @Test
    fun `a sidecar whose hf is lowercase is refused at load`(
        @TempDir tmp: Path,
    ) {
        // The wire format is uppercase per the Fidelity
        // enum's `name` (HF0..HF6). A lowercase entry is
        // a different byte sequence; the typed view's
        // Fidelity.valueOf would throw, so the init
        // refuses it before any consumer sees it.
        val dir = tmp.resolve("results")
        Files.createDirectories(dir)
        writeXml(dir, validClassName)
        writeSidecar(
            dir,
            validClassName,
            body = """
                {
                  "class_name": "FooTest",
                  "gradle_path": "$validGradlePath",
                  "invocation": "$validInvocation",
                  "binary_sha256": "$validBinarySha",
                  "hf": "hf0",
                  "source_commit": "$validSourceCommit"
                }
            """.trimIndent(),
        )
        val file = dir.resolve("$validClassName.json")
        val ex = assertThrows<IllegalArgumentException> {
            EvidenceManifest.load(file)
        }
        assertTrue(
            (ex.message ?: "").contains("HF0, HF1, HF2"),
            "refusal must name the allowed-set requirement; got ${ex.message}",
        )
    }
}
