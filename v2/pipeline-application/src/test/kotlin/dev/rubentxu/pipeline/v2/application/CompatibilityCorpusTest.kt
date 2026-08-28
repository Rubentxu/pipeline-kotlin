package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.support.AppBinSupport
import dev.rubentxu.pipeline.v2.events.JsonEventLog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Path

class CompatibilityCorpusTest {

    private fun discoverFixtures(): List<Path> {
        val userDir = File(System.getProperty("user.dir"))
        val candidate = generateSequence(userDir) { it.parentFile }
            .map { File(it, "v2/compatibility") }
            .firstOrNull { it.isDirectory }
            ?: error("Cannot locate v2/compatibility/ via directory walk from $userDir")
        return candidate.listFiles { f -> f.extension == "kts" }?.toList().orEmpty().sortedBy { it.name }.map { it.toPath() }
    }

    @Test
    fun corpusSmokeRunsAndProducesParseableEvents() {
        val fixtures = discoverFixtures()
        assertTrue(fixtures.isNotEmpty()) { "Expected at least 9 corpus fixtures, found ${fixtures.size}" }

        val appBin = AppBinSupport.discover()

        fixtures.forEach { fixture ->
            val pb = ProcessBuilder(appBin.toString(), "run", fixture.toString())
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.PIPE)

            val process = pb.start()
            val exitCode = process.waitFor()
            val stdout = process.inputStream.bufferedReader().readText().trim()

            assertEquals(0, exitCode) { "Fixture ${fixture.fileName} exited with code $exitCode. stderr: ${process.errorStream.bufferedReader().readText()}" }
            assertTrue(stdout.startsWith("[")) { "Fixture ${fixture.fileName} stdout must start with '['" }
            assertTrue(stdout.endsWith("]")) { "Fixture ${fixture.fileName} stdout must end with ']'" }

            val events = JsonEventLog.decode(stdout)
            assertTrue(events.isNotEmpty()) { "Fixture ${fixture.fileName} produced no events" }
        }
    }

    @Test
    fun allCorpusFixturesAreDiscoverable() {
        val fixtures = discoverFixtures()
        assertEquals(9, fixtures.size)

        val names = fixtures.map { it.fileName.toString() }.toSet()
        assertTrue(names.contains("01-basic.pipeline.kts"))
        assertTrue(names.contains("02-environment.pipeline.kts"))
        assertTrue(names.contains("03-stages.pipeline.kts"))
        assertTrue(names.contains("04-sh.pipeline.kts"))
        assertTrue(names.contains("05-scripted-if.pipeline.kts"))
        assertTrue(names.contains("06-loop.pipeline.kts"))
        assertTrue(names.contains("07-writeFile-readFile.pipeline.kts"))
        assertTrue(names.contains("08-withEnv-pipeline.pipeline.kts"))
        assertTrue(names.contains("09-archive-artefacts.pipeline.kts"))
    }
}
