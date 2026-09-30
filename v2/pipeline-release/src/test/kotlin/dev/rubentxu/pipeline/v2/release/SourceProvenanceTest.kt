package dev.rubentxu.pipeline.v2.release

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * P0.5 — the source provenance law, and the Gate Teeth canary for it.
 *
 * The law exists because of a real defect found while handing off the 0.44
 * candidate: the manifest recorded `sourceCommit = 002496ca`, but the ZIP had
 * been compiled from a working tree that already contained `0.44.0` while that
 * commit still declared `0.44.0-rc1`. The manifest named a commit that would
 * compile different bytes. Nothing refused it.
 *
 * Per P-UAT-09 (Gate Teeth Law) these tests are only evidence if disabling the
 * specific decision they claim to verify turns them RED. That is what
 * [provenance_law_is_the_decision_under_test] pins: the refusal is produced by
 * [SourceProvenance.evaluate], and the integration canary proves the refusal
 * actually reaches the build outcome.
 */
class SourceProvenanceTest {

    private val head = "a".repeat(40)

    // ---------------------------------------------------------------- pure law

    @Test
    @DisplayName("a clean tree at a known commit is verified")
    fun clean_tree_is_verified() {
        val verdict = SourceProvenance.evaluate(
            SourceProvenanceFacts(head, modifiedTrackedFiles = 0, stagedButUncommitted = 0),
        )
        assertEquals(SourceProvenanceVerdict.Clean(head), verdict)
    }

    @Test
    @DisplayName("uncommitted tracked modifications refuse the candidate")
    fun dirty_worktree_refuses() {
        val verdict = SourceProvenance.evaluate(
            SourceProvenanceFacts(head, modifiedTrackedFiles = 1, stagedButUncommitted = 0),
        )
        assertTrue(
            verdict is SourceProvenanceVerdict.Refused,
            "a dirty worktree must refuse, but got $verdict",
        )
        val refusal = verdict as SourceProvenanceVerdict.Refused
        assertTrue(
            refusal.reason.contains("uncommitted", ignoreCase = true),
            "the refusal must name the cause; got: ${refusal.reason}",
        )
    }

    @Test
    @DisplayName("staged-but-uncommitted changes refuse the candidate")
    fun staged_changes_refuse() {
        val verdict = SourceProvenance.evaluate(
            SourceProvenanceFacts(head, modifiedTrackedFiles = 0, stagedButUncommitted = 2),
        )
        assertTrue(
            verdict is SourceProvenanceVerdict.Refused,
            "staged changes are still uncommitted and must refuse, but got $verdict",
        )
    }

    @Test
    @DisplayName("bytes from a different commit than the recorded one refuse")
    fun stale_build_commit_refuses() {
        val verdict = SourceProvenance.evaluate(
            SourceProvenanceFacts(
                headCommit = head,
                modifiedTrackedFiles = 0,
                stagedButUncommitted = 0,
                builtFromCommit = "b".repeat(40),
            ),
        )
        assertTrue(
            verdict is SourceProvenanceVerdict.Refused,
            "a candidate whose bytes came from another commit must refuse, but got $verdict",
        )
    }

    @Test
    @DisplayName("unreadable version control is Unverifiable, never Clean")
    fun unknown_commit_is_not_clean() {
        val verdict = SourceProvenance.evaluate(
            SourceProvenanceFacts(
                headCommit = SourceProvenance.PROVENANCE_UNKNOWN,
                modifiedTrackedFiles = 0,
                stagedButUncommitted = 0,
            ),
        )
        assertTrue(
            verdict is SourceProvenanceVerdict.Unverifiable,
            "an unanswerable provenance question must not read as a pass, but got $verdict",
        )
    }

    @Test
    @DisplayName("Clean and Unverifiable are distinct verdicts, not one collapsed case")
    fun clean_and_unverifiable_are_distinct() {
        // If these ever collapse, a probe outage becomes a silent pass — the
        // exact failure mode this module already produced once.
        val clean = SourceProvenance.evaluate(SourceProvenanceFacts(head, 0, 0))
        val unknown = SourceProvenance.evaluate(
            SourceProvenanceFacts(SourceProvenance.PROVENANCE_UNKNOWN, 0, 0),
        )
        assertTrue(clean::class != unknown::class, "the two verdicts must not be the same case")
    }

    // ------------------------------------------------------------- gate teeth

    @Test
    @DisplayName("GATE TEETH: the refusal is produced by SourceProvenance, not by the caller")
    fun provenance_law_is_the_decision_under_test() {
        // If the law lived in the wiring instead of the decider, an integration
        // test could still go RED for an unrelated reason and this would pass
        // while the real authority was unguarded. Pinning the decider as the
        // sole source of the refusal is what makes the mutation meaningful.
        val facts = SourceProvenanceFacts(head, modifiedTrackedFiles = 3, stagedButUncommitted = 0)
        assertTrue(
            SourceProvenance.evaluate(facts) is SourceProvenanceVerdict.Refused,
            "the decider must refuse a dirty tree on its own, with no wiring involved",
        )
    }

    // ------------------------------------------------------------ integration

    @Test
    @DisplayName("admission refuses a dirty tree before writing any candidate material")
    fun admission_refuses_dirty_tree_without_emitting_material() {
        val dir = java.nio.file.Files.createTempDirectory("prov-refuse")
        try {
            val zip = dir.resolve("pipelinek-0.44.0.zip")
            writeMinimalZip(zip)

            val outcome = CandidateAdmission.admit(
                zip = zip,
                productVersion = "0.44.0",
                gitCommit = head,
                candidateRef = null,
                toolchain = "test",
                sbom = null,
                sha256sums = null,
                candidateSequence = 1,
                outDir = dir.resolve("out"),
                provenanceFacts = SourceProvenanceFacts(
                    headCommit = head,
                    modifiedTrackedFiles = 1,
                    stagedButUncommitted = 0,
                ),
            )

            assertTrue(
                outcome is AdmissionOutcome.Refused,
                "a dirty tree must fail admission, but got $outcome",
            )
            assertTrue(
                !java.nio.file.Files.exists(dir.resolve("out")),
                "no candidate material may be written for a refused candidate",
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    @DisplayName("a clean tree still admits and reports a verified provenance verdict")
    fun clean_tree_admits_with_verified_provenance() {
        val dir = java.nio.file.Files.createTempDirectory("prov-clean")
        try {
            val zip = dir.resolve("pipelinek-0.44.0.zip")
            writeMinimalZip(zip)

            val outcome = CandidateAdmission.admit(
                zip = zip,
                productVersion = "0.44.0",
                gitCommit = head,
                candidateRef = null,
                toolchain = "test",
                sbom = null,
                sha256sums = null,
                candidateSequence = 1,
                outDir = dir.resolve("out"),
                provenanceFacts = SourceProvenanceFacts(head, 0, 0),
            )

            assertTrue(outcome is AdmissionOutcome.Admitted, "clean tree must admit, got $outcome")
            assertTrue(
                (outcome as AdmissionOutcome.Admitted).provenanceVerdict.contains("VERIFIED"),
                "an admitted candidate must state its provenance was verified; " +
                    "got: ${outcome.provenanceVerdict}",
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Minimal but *identity-complete* candidate ZIP: one top-level directory
     * and a JAR carrying a real `Implementation-Version` manifest, so the
     * candidate satisfies the identity law for the reason under test instead of
     * being rejected earlier for an unrelated reason.
     *
     * A test that goes RED because a fake archive lacks a manifest proves
     * nothing about provenance; it only proves the fixture is fake.
     */
    private fun writeMinimalZip(zip: java.nio.file.Path, version: String = "0.44.0") {
        val jarBytes = java.io.ByteArrayOutputStream().use { out ->
            java.util.jar.JarOutputStream(out).use { jar ->
                jar.putNextEntry(
                    java.util.jar.JarEntry("META-INF/MANIFEST.MF"),
                )
                jar.write(
                    "Manifest-Version: 1.0\r\nImplementation-Version: $version\r\n\r\n"
                        .toByteArray(),
                )
                jar.closeEntry()
            }
            out.toByteArray()
        }

        java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(zip)).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("pipelinek-$version/"))
            zos.closeEntry()
            zos.putNextEntry(
                java.util.zip.ZipEntry("pipelinek-$version/lib/pipeline-application-$version.jar"),
            )
            zos.write(jarBytes)
            zos.closeEntry()
        }
    }
}
