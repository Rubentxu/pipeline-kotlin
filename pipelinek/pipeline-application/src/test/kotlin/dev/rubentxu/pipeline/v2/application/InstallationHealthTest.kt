package dev.rubentxu.pipeline.v2.application

import java.nio.file.Paths
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The guarantee: the health report answers with the decision that says what
 * the report MEASURED, and the code that decision carries is the one the
 * taxonomy declares.
 *
 * The capacity this serves: a diagnosis an operator can trust. The report
 * prints a line per fact and answers with a decision, and the operator reads
 * the two together. When the code was read off the printed text — when the
 * report searched its own sentences for a marker — the two could contradict
 * each other: an installation whose working directory was named
 * `NOT WRITABLE` printed "writable" and answered "broken".
 *
 * ## What changed, and what did not
 *
 * The verdict used to be a bare number named by two constants of this report.
 * It is now a [CliExit], the same closed taxonomy every other refusal in this
 * CLI answers with, so the code the process ends with comes from the case
 * instead of from a copy written next to the report.
 *
 * The NUMBER did not move: a defective installation still answers 2, because
 * the `doctor` example reads that code and a change would be a change of the
 * published contract, not of this report. The rows below pin both the case and
 * the number, so neither can drift on its own.
 *
 * These rows are pure: a check in, a verdict out. No filesystem, no clock, no
 * process. What the directory is called is not the point; what the report
 * concludes is.
 */
class InstallationHealthTest {

    @Test
    fun `a report with no defect answers with the success case`() {
        val checks = listOf(
            InstallationHealth.fact("jdk", "21.0.8 (Eclipse Adoptium)"),
            InstallationHealth.fact("os", "Linux 6.1"),
            InstallationHealth.workdir(Paths.get("/workspace"), writable = true),
        )

        val decision = InstallationHealth.decision(checks)

        assertInstanceOf(
            CliExit.Success::class.java,
            decision,
            "a report with no defect answers with the success case",
        )
        assertEquals(0, decision.code, "the success case carries the code the taxonomy declares")
    }

    @Test
    fun `a report with a defective check answers with the defect case`() {
        val checks = listOf(
            InstallationHealth.fact("jdk", "21.0.8 (Eclipse Adoptium)"),
            InstallationHealth.workdir(Paths.get("/workspace"), writable = false),
        )

        val decision = InstallationHealth.decision(checks)

        assertInstanceOf(
            CliExit.InvalidArgument::class.java,
            decision,
            "a defective check answers with the invalid-argument case",
        )
        assertEquals(
            2,
            decision.code,
            "the defective answer keeps the code the doctor example reads",
        )
    }

    @Test
    fun `the verdict is never read off the reading`() {
        // A directory whose NAME carries the marker word. The probe found it
        // writable, so the report says so, and the decision has to agree with
        // what was measured: the sentence is not the judgement.
        val checks = listOf(
            InstallationHealth.workdir(Paths.get("/workspace/NOT WRITABLE"), writable = true),
        )

        val decision = InstallationHealth.decision(checks)

        assertInstanceOf(
            CliExit.Success::class.java,
            decision,
            "the verdict comes from the probe, so a reading that happens to contain the " +
                "failure marker is still a healthy reading",
        )
    }

    @Test
    fun `the workdir reading and its verdict come from the same fact`() {
        val writable = InstallationHealth.workdir(Paths.get("/workspace"), writable = true)
        val unwritable = InstallationHealth.workdir(Paths.get("/workspace"), writable = false)

        assertTrue(
            writable.reading.contains("writable") && !writable.reading.contains("NOT WRITABLE"),
            "a writable directory is described as writable",
        )
        assertEquals(HealthVerdict.HEALTHY, writable.verdict)
        assertTrue(
            unwritable.reading.contains("NOT WRITABLE"),
            "a directory that cannot be written into is described as such",
        )
        assertEquals(HealthVerdict.DEFECTIVE, unwritable.verdict)
    }

    @Test
    fun `the report renders one line per check`() {
        val check = InstallationHealth.workdir(Paths.get("/workspace"), writable = true)

        assertEquals("workdir: /workspace (writable)", check.render())
    }
}
