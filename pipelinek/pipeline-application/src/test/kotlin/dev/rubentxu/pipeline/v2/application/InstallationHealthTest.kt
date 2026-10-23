package dev.rubentxu.pipeline.v2.application

import java.nio.file.Paths
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The guarantee: the health report's exit code says what the report MEASURED,
 * not what it happens to have written.
 *
 * The capacity this serves: a diagnosis an operator can trust. The report
 * prints a line per fact and answers with an exit code, and the operator
 * reads the two together. When the code was read off the printed text — when
 * the report searched its own sentences for a marker — the two could
 * contradict each other: an installation whose working directory was named
 * `NOT WRITABLE` printed "writable" and answered "broken".
 *
 * These rows are pure: a check in, a verdict out. No filesystem, no clock, no
 * process. What the directory is called is not the point; what the report
 * concludes is.
 */
class InstallationHealthTest {

    @Test
    fun `a report with no defect answers with the healthy code`() {
        val checks = listOf(
            InstallationHealth.fact("jdk", "21.0.8 (Eclipse Adoptium)"),
            InstallationHealth.fact("os", "Linux 6.1"),
            InstallationHealth.workdir(Paths.get("/workspace"), writable = true),
        )

        assertEquals(InstallationHealth.HEALTHY_EXIT, InstallationHealth.exitCode(checks))
    }

    @Test
    fun `a report with a defective check answers with the defect code`() {
        val checks = listOf(
            InstallationHealth.fact("jdk", "21.0.8 (Eclipse Adoptium)"),
            InstallationHealth.workdir(Paths.get("/workspace"), writable = false),
        )

        assertEquals(InstallationHealth.DEFECTIVE_EXIT, InstallationHealth.exitCode(checks))
    }

    @Test
    fun `the verdict is never read off the reading`() {
        // A directory whose NAME carries the marker word. The probe found it
        // writable, so the report says so, and the exit code has to agree with
        // what was measured: the sentence is not the judgement.
        val checks = listOf(
            InstallationHealth.workdir(Paths.get("/workspace/NOT WRITABLE"), writable = true),
        )

        assertEquals(
            InstallationHealth.HEALTHY_EXIT,
            InstallationHealth.exitCode(checks),
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
