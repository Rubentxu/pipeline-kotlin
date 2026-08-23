package com.pipeline.v2.application

import com.pipeline.v2.application.support.AppBinSupport
import com.pipeline.v2.events.JsonEventLog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

class CompatibilityCorpusTest {

    private val compatibilityDir: Path by lazy {
        val userDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        val dir = if (userDir.fileName?.toString() == "pipeline-application") {
            userDir.resolve("v2").resolve("compatibility")
        } else {
            userDir.resolve("compatibility")
        }
        dir
    }

    private fun discoverFixtures(): List<Path> {
        val dir = compatibilityDir.toFile()
        if (!dir.exists()) {
            return emptyList()
        }
        return dir.listFiles()
            ?.filter { it.name.endsWith(".pipeline.kts") }
            ?.sortedBy { it.name }
            ?.map { it.toPath() }
            ?: emptyList()
    }

    @Test
    fun corpusSmokeRunsAndProducesParseableEvents() {
        val fixtures = discoverFixtures()
        assertTrue(fixtures.isNotEmpty()) { "Expected at least 6 corpus fixtures, found ${fixtures.size}" }

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
        assertEquals(6, fixtures.size)

        val names = fixtures.map { it.fileName.toString() }.toSet()
        assertTrue(names.contains("01-basic.pipeline.kts"))
        assertTrue(names.contains("02-environment.pipeline.kts"))
        assertTrue(names.contains("03-stages.pipeline.kts"))
        assertTrue(names.contains("04-sh.pipeline.kts"))
        assertTrue(names.contains("05-scripted-if.pipeline.kts"))
        assertTrue(names.contains("06-loop.pipeline.kts"))
    }
}
