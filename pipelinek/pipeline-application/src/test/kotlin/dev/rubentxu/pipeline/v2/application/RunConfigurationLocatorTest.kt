package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The door that finds the configuration snapshot of a run from the run's own facts.
 *
 * ## The guarantees under trial
 *
 *  - the location of the snapshot is DERIVED from the correlation of the run: a snapshot on the
 *    disk and a correlation in the store are enough to answer, and nothing the service held in
 *    memory takes part;
 *  - a run the store does not know, and a run whose supervision directory is not in this
 *    service's own directory, are both unknown, and neither is an error;
 *  - a store or a snapshot that cannot be read is an unreadable answer whose text carries no
 *    path.
 *
 * ## Why the reader of correlations is a double
 *
 * The rows are about what the locator does with each answer of the reader. What the real reader
 * asks of the real store is a fact about the machine, and the integration lane observes it.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class RunConfigurationLocatorTest {

    @Test
    fun `a run the store does not know is unknown`(@TempDir root: Path) {
        val locator = RunConfigurationLocator(root, correlations(RunCorrelation.Absent))

        assertEquals(RunConfigurationLookup.Unknown, locator.describe("run-that-never-was"))
    }

    @Test
    fun `a run whose supervision directory is not here is unknown`(@TempDir root: Path) {
        val locator = RunConfigurationLocator(root, correlations(RunCorrelation.Found("corr-of-another-service")))

        assertEquals(
            RunConfigurationLookup.Unknown,
            locator.describe("run-1"),
            "a run another service started has its supervision in the other service's own directory",
        )
    }

    @Test
    fun `the snapshot is found in the directory named after the correlation`(@TempDir root: Path) {
        writeSnapshot(root, "corr-1", mapOf("BUILD_PROFILE" to "private-release"))
        val locator = RunConfigurationLocator(root, correlations(RunCorrelation.Found("corr-1")))

        val found = assertInstanceOf(RunConfigurationLookup.Found::class.java, locator.describe("run-1"))

        assertEquals(
            listOf(RunConfigurationEntry("BUILD_PROFILE", RunConfigurationReader.ORIGIN_REQUEST, "string")),
            found.description.entries,
        )
        assertEquals(configurationDigest(mapOf("BUILD_PROFILE" to "private-release")), found.description.digest)
        assertFalse(found.description.toString().contains("private-release"), "the description holds no value")
    }

    @Test
    fun `a run given nothing is described as holding nothing and is not unknown`(@TempDir root: Path) {
        writeSnapshot(root, "corr-1", emptyMap())
        val locator = RunConfigurationLocator(root, correlations(RunCorrelation.Found("corr-1")))

        val found = assertInstanceOf(RunConfigurationLookup.Found::class.java, locator.describe("run-1"))

        assertEquals(emptyList<RunConfigurationEntry>(), found.description.entries)
        assertEquals(configurationDigest(emptyMap()), found.description.digest)
    }

    @Test
    fun `a snapshot nobody can parse is unreadable and the text carries no path`(@TempDir root: Path) {
        val directory = Files.createDirectories(root.resolve(supervisionDirectoryName("corr-1")))
        Files.writeString(directory.resolve(ServiceRunSupervisor.RUN_CONFIG_NAME), "this is not a snapshot")
        val locator = RunConfigurationLocator(root, correlations(RunCorrelation.Found("corr-1")))

        val lookup = assertInstanceOf(RunConfigurationLookup.Unreadable::class.java, locator.describe("run-1"))

        assertFalse(lookup.detail.contains(root.toString()), "a path does not travel")
    }

    @Test
    fun `a directory without its snapshot is unreadable and not unknown`(@TempDir root: Path) {
        Files.createDirectories(root.resolve(supervisionDirectoryName("corr-1")))
        val locator = RunConfigurationLocator(root, correlations(RunCorrelation.Found("corr-1")))

        val lookup = assertInstanceOf(RunConfigurationLookup.Unreadable::class.java, locator.describe("run-1"))

        assertFalse(lookup.detail.contains(root.toString()), "a path does not travel")
    }

    @Test
    fun `a store that cannot be read is unreadable and the text carries no path`(@TempDir root: Path) {
        writeSnapshot(root, "corr-1", mapOf("BUILD_PROFILE" to "release"))
        val locator = RunConfigurationLocator(
            root,
            correlations(RunCorrelation.Unreadable("cannot open ${root.resolve("store.db")}")),
        )

        val lookup = assertInstanceOf(RunConfigurationLookup.Unreadable::class.java, locator.describe("run-1"))

        assertFalse(lookup.detail.contains(root.toString()), "a path does not travel")
    }

    @Test
    fun `the locator asks the store about the run it was asked about`(@TempDir root: Path) {
        val asked = mutableListOf<String>()
        val locator = RunConfigurationLocator(
            root,
            object : RunCorrelationReader {
                override fun correlationOf(runId: String): RunCorrelation {
                    asked += runId
                    return RunCorrelation.Absent
                }
            },
        )

        locator.describe("run-7")

        assertEquals(listOf("run-7"), asked)
    }

    @Test
    fun `the name of a supervision directory carries the correlation`() {
        assertEquals("sup-abc-123", supervisionDirectoryName("abc-123"))
    }

    private fun correlations(answer: RunCorrelation): RunCorrelationReader = object : RunCorrelationReader {
        override fun correlationOf(runId: String): RunCorrelation = answer
    }

    private fun writeSnapshot(root: Path, correlation: String, values: Map<String, String>) {
        val directory = Files.createDirectories(root.resolve(supervisionDirectoryName(correlation)))
        Files.writeString(
            directory.resolve(ServiceRunSupervisor.RUN_CONFIG_NAME),
            runConfigurationDocument(values).toString(),
        )
    }
}
