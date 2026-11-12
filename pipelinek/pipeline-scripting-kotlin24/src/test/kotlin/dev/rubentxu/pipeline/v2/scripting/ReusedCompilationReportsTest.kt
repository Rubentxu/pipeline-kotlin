package dev.rubentxu.pipeline.v2.scripting

import dev.rubentxu.pipeline.v2.events.CompilationFinished
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.SourceCode

/**
 * A compilation served from the store publishes the diagnostics of the compilation that stored it.
 *
 * ## The guarantee
 *
 * A reuse skips the compiler, and the compiler is where the diagnostics come from. Before, a script
 * with a warning compiled "with 1 warning" the first time and, served from the store the next time,
 * with none, although nothing in the script had changed: the operator saw the warning once and
 * never again. The store keeps the reports of the compilation that produced the artifact, and a
 * reuse publishes them exactly as that compilation did: same severity, message, line and column.
 *
 * ## Why each row is here
 *
 * - The warm pass is the case the store used to drop: the second compilation of the same file is a
 *   reuse, and its closing event carries the same diagnostics as the first one.
 * - The second file is the case the key makes possible: the key is the text of the script and what
 *   it is compiled against, not the file it was read from, so two files with the same text share
 *   one artifact. The warning of the reuse names the file that was compiled, not the one that
 *   stored the artifact.
 * - The jar without reports is the artifact a store that did not keep them left behind. It is not
 *   served as a compilation the compiler said nothing about: it is a miss, the script is compiled
 *   again with its warning, and the jar that replaces it serves the next reuse with the warning.
 * - The jar whose reports do not decode is a damaged entry, and a damaged entry is a miss like any
 *   other unreadable artifact: the script is compiled again, and its warning is not lost.
 * - The truncated jar is the damage the host recovers from at evaluation. Reading the reports opens
 *   the jar's directory, at the end of the file, so the store refuses it at load, before the
 *   artifact reaches the evaluator.
 * - The encoding rows pin what the bytes keep: every field the console and the JSON document read,
 *   for a report about the script, about no file and about another file; and bytes that are not
 *   reports are refused instead of being read as some.
 *
 * ## The instrument
 *
 * A real store in a directory of the row's own, which the store creates private, and the host the
 * product compiles with. Nothing reads or writes the operator's cache. The old and the damaged jars
 * are the real jar the store wrote, edited in place through the JDK's zip file system.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ReusedCompilationReportsTest {

    @TempDir
    lateinit var dir: Path

    private class RecordingSink : EventSink {
        val events = mutableListOf<DomainEvent>()

        override fun append(event: DomainEvent) {
            events += event
        }

        override fun eventsFor(runId: String): Sequence<DomainEvent> = events.asSequence()
    }

    private val scriptClasspath: List<String> =
        listOfNotNull(ScriptDefinition.domainJar(), ScriptDefinition.dslApiJar())

    private val store: JarCompiledScriptCache by lazy { JarCompiledScriptCache(dir.resolve("cache")) }

    private fun write(relative: String, text: String): Path {
        val script = dir.resolve(relative)
        Files.createDirectories(script.parent)
        Files.writeString(script, text)
        return script
    }

    private fun compile(script: Path): CompilationFinished {
        val sink = RecordingSink()
        val host = Kotlin24ScriptingHost(eventSink = sink, cache = store)
        val result = host.compile(ScriptDefinition.file(script, classpath = scriptClasspath))
        val finished = sink.events.filterIsInstance<CompilationFinished>().single()
        assertEquals(result.diagnostics, finished.diagnostics, "the event and the result tell two stories")
        return finished
    }

    private fun warning(script: Path) =
        ScriptingDiagnostic(ScriptDiagnosticSeverity.WARNING, TO_INT, 1, 11, script.toString())

    /** Edit the jar the store keeps for [finished] in place: [edit] receives its reports entry. */
    private fun editStoredJar(finished: CompilationFinished, edit: (Path) -> Unit) {
        val jar = dir.resolve("cache").resolve(JarCompiledScriptCache.fileNameFor(finished.cacheKey))
        FileSystems.newFileSystem(jar).use { zip -> edit(zip.getPath(JarCompiledScriptCache.REPORTS_ENTRY)) }
    }

    @Test
    fun `a reuse publishes the diagnostics of the compilation that stored the artifact`() {
        val script = write("warn.pipeline.kts", WARNED)

        val cold = compile(script)
        val warm = compile(script)

        assertEquals(ScriptOrigin.COMPILED, cold.origin, "the first pass did not compile, so nothing was stored")
        assertEquals(listOf(warning(script)), cold.diagnostics, "the compiler did not raise the warning")
        assertEquals(ScriptOrigin.REUSED, warm.origin, "the second pass was not served by the stored artifact")
        assertEquals(cold.diagnostics, warm.diagnostics, "the reuse did not publish what the compilation said")
    }

    @Test
    fun `a reused warning names the file that was compiled, not the one that stored the artifact`() {
        val first = write("a/warn.pipeline.kts", WARNED)
        val second = write("b/warn.pipeline.kts", WARNED)

        compile(first)
        val reused = compile(second)

        assertEquals(ScriptOrigin.REUSED, reused.origin, "two files with the same text did not share the artifact")
        assertEquals(listOf(warning(second)), reused.diagnostics, "the reuse named another file than the one compiled")
    }

    @Test
    fun `an artifact stored without its reports is compiled again instead of being served as silent`() {
        val script = write("warn.pipeline.kts", WARNED)
        val cold = compile(script)
        // What a store that did not keep the reports left behind: the artifact, and nothing else.
        editStoredJar(cold) { entry ->
            Files.delete(entry)
            Files.delete(entry.parent)
        }

        val lookup = store.load(cold.cacheKey)
        val again = compile(script)
        val reused = compile(script)

        assertEquals(CacheLookup.Miss(CacheMissReason.INCOMPLETE), lookup, "a jar without reports was served")
        assertEquals(ScriptOrigin.COMPILED, again.origin, "the jar without reports was not compiled again")
        assertEquals(listOf(warning(script)), again.diagnostics, "the compilation again lost the warning")
        assertEquals(ScriptOrigin.REUSED, reused.origin, "the compilation again did not replace the old jar")
        assertEquals(listOf(warning(script)), reused.diagnostics, "the replaced jar did not keep the warning")
    }

    @Test
    fun `reports that do not decode are a damaged entry, and the script is compiled again`() {
        val script = write("warn.pipeline.kts", WARNED)
        val cold = compile(script)
        editStoredJar(cold) { entry -> Files.write(entry, byteArrayOf(0, 3, 'b'.code.toByte(), 'a'.code.toByte())) }

        val lookup = store.load(cold.cacheKey)
        val again = compile(script)

        assertEquals(CacheLookup.Miss(CacheMissReason.UNREADABLE), lookup, "a jar with damaged reports was served")
        assertEquals(ScriptOrigin.COMPILED, again.origin, "the damaged jar was not compiled again")
        assertEquals(listOf(warning(script)), again.diagnostics, "the compilation again lost the warning")
    }

    @Test
    fun `a truncated jar is refused at load, and the script is compiled again with its warning`() {
        val script = write("warn.pipeline.kts", WARNED)
        val cold = compile(script)
        val jar = dir.resolve("cache").resolve(JarCompiledScriptCache.fileNameFor(cold.cacheKey))
        val bytes = Files.readAllBytes(jar)
        Files.write(jar, bytes.copyOf(bytes.size / 4))

        val lookup = store.load(cold.cacheKey)
        val again = compile(script)

        assertEquals(CacheLookup.Miss(CacheMissReason.UNREADABLE), lookup, "a truncated jar was served")
        assertEquals(ScriptOrigin.COMPILED, again.origin, "the truncated jar was not compiled again")
        assertEquals(listOf(warning(script)), again.diagnostics, "the compilation again lost the warning")
    }

    @Test
    fun `the encoding keeps every field of a report and names the script it is read for`() {
        val aboutScript = ScriptDiagnostic(
            7,
            "about the script",
            ScriptDiagnostic.Severity.WARNING,
            "stored.kts",
            SourceCode.Location(SourceCode.Position(2, 11), null),
            IllegalStateException("not kept"),
        )
        val aboutNoFile = ScriptDiagnostic(0, "a note", ScriptDiagnostic.Severity.INFO, null, null, null)
        val aboutAnother = ScriptDiagnostic(
            -1,
            "é".repeat(40_000),
            ScriptDiagnostic.Severity.FATAL,
            "other.kts",
            SourceCode.Location(SourceCode.Position(3, 4, 50), SourceCode.Position(3, 9, 55)),
            null,
        )
        val reports = CompilationReports.of(listOf(aboutScript, aboutNoFile, aboutAnother), script = "stored.kts")

        val decoded = CompilationReports.decode(reports.encode())

        assertEquals(reports, decoded, "the bytes did not read back as the reports they were written from")
        assertEquals(
            listOf(aboutScript.copy(sourcePath = "compiled.kts", exception = null), aboutNoFile, aboutAnother),
            decoded.about("compiled.kts"),
        )
    }

    @Test
    fun `bytes that are not reports are refused`() {
        val bytes = CompilationReports.of(
            listOf(ScriptDiagnostic(0, "a warning", ScriptDiagnostic.Severity.WARNING, "s.kts", null, null)),
            script = "s.kts",
        ).encode()

        assertThrows(IOException::class.java) { CompilationReports.decode(byteArrayOf(0, 3, 'b'.code.toByte())) }
        assertThrows(IOException::class.java) { CompilationReports.decode(bytes.copyOf(bytes.size - 1)) }
        assertThrows(IOException::class.java) { CompilationReports.decode(bytes + 0) }
    }

    private companion object {
        const val TO_INT = "Use 'toInt' to give the type explicitly."

        val WARNED = """
            val n = 1 as Int

            pipeline {
                stages {
                    stage("build") {
                        sh("make")
                    }
                }
            }
        """.trimIndent()
    }
}
