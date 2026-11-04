package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.CompilationFinished
import dev.rubentxu.pipeline.v2.events.CompilationStarted
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.runtime.NoopPipelineTelemetry
import dev.rubentxu.pipeline.v2.scripting.JarCompiledScriptCache
import dev.rubentxu.pipeline.v2.scripting.ScriptOrigin
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * What ONE generator-level run compiles, and what it leaves in the script cache.
 *
 * ## The guarantee
 *
 * A source in the generator-level form is ONE program: its body runs inside the
 * run, with its runtime calls rewritten onto the scripted facade. The program
 * that runs is the LOWERED program, so the program that runs and the program
 * that is compiled are the same one — and compiling the author's original as
 * well would compile a program nobody runs, evaluating the author's body at
 * compile time, a second time.
 *
 * The observable facts of that guarantee are three, and each is checked here
 * against the real host and the real store:
 *
 *  - the run performs ONE compilation, not two;
 *  - the artifact the entry point declares is the artifact the lowering
 *    published — one identity, one owner, one comparison, because the runtime
 *    compares them and a second derivation could only disagree;
 *  - the run leaves ONE entry in the script cache, because the second
 *    compilation would have left a second entry for a program nobody runs.
 *
 * ## Why the store that ships is the store under test
 *
 * A fake store that answered "hit" with an artifact it made up would prove
 * only that the host trusts its input. The store used here is the one the
 * product uses: the first compilation writes the jar, the second one finds it
 * and loads it. That is the sequence an operator runs.
 *
 * Nothing here reads a clock or asserts on a duration: the guarantee is about
 * WHAT the run did, not about how long it took.
 */
class DurableScriptCompilationTest {

    @TempDir
    lateinit var workDir: Path

    /**
     * A sink that remembers what it was given. The events are the fact under
     * test: a compilation is announced by a pair of events, and the pair is
     * what says how many compilations happened.
     */
    private class RecordingSink : EventSink {
        val events = mutableListOf<DomainEvent>()

        override fun append(event: DomainEvent) {
            events += event
        }

        override fun eventsFor(runId: String): Sequence<DomainEvent> = events.asSequence()

        fun started(): List<CompilationStarted> = events.filterIsInstance<CompilationStarted>()

        fun finished(): List<CompilationFinished> = events.filterIsInstance<CompilationFinished>()
    }

    @Test
    fun `a generator-level source is compiled once`() {
        val sink = RecordingSink()
        val script = writeScript("generator.pipeline.kts", GENERATOR_LEVEL_SOURCE)

        compileDurableScript(
            eventStore = sink,
            runId = RUN_ID,
            scriptPath = script,
            scriptContent = Files.readString(script),
            pluginJars = emptyList(),
            telemetry = NoopPipelineTelemetry,
            cache = JarCompiledScriptCache(cacheDir()),
        )

        assertEquals(
            1,
            sink.started().size,
            "the generator-level form compiled the source more than once: " +
                "the program that runs is the lowered one, and the original is a program nobody runs",
        )
        assertEquals(
            1,
            sink.finished().size,
            "the generator-level form opened a compilation it did not close",
        )
    }

    @Test
    fun `the compiled artifact declares the identity the lowering published`() {
        val sink = RecordingSink()
        val script = writeScript("generator.pipeline.kts", GENERATOR_LEVEL_SOURCE)

        val compiled = compileDurableScript(
            eventStore = sink,
            runId = RUN_ID,
            scriptPath = script,
            scriptContent = Files.readString(script),
            pluginJars = emptyList(),
            telemetry = NoopPipelineTelemetry,
            cache = JarCompiledScriptCache(cacheDir()),
        )

        val entryPoint = required(compiled.scripted.entryPoint, "the generator-level form produced no entry point")
        val artifact = required(compiled.scripted.artifact, "the generator-level form published no artifact")
        assertEquals(
            artifact.identity.fingerprintMaterial(),
            entryPoint.artifact.fingerprintMaterial(),
            "the compiled artifact declares another identity than the published one: " +
                "the runtime compares the two, so a second derivation of the same fact " +
                "could only make the comparison fail",
        )
    }

    @Test
    fun `a generator-level run leaves one entry in the script cache`() {
        val sink = RecordingSink()
        val script = writeScript("generator.pipeline.kts", GENERATOR_LEVEL_SOURCE)

        compileDurableScript(
            eventStore = sink,
            runId = RUN_ID,
            scriptPath = script,
            scriptContent = Files.readString(script),
            pluginJars = emptyList(),
            telemetry = NoopPipelineTelemetry,
            cache = JarCompiledScriptCache(cacheDir()),
        )

        assertEquals(
            1,
            storedArtifacts().size,
            "the run left more than one artifact in the script cache: one of them is " +
                "the compilation of a program nobody runs",
        )
    }

    @Test
    fun `the second compilation of the same source is served by the stored artifact`() {
        val cache = JarCompiledScriptCache(cacheDir())
        val script = writeScript("generator.pipeline.kts", GENERATOR_LEVEL_SOURCE)

        val cold = RecordingSink()
        val first = compileDurableScript(
            eventStore = cold,
            runId = RUN_ID,
            scriptPath = script,
            scriptContent = Files.readString(script),
            pluginJars = emptyList(),
            telemetry = NoopPipelineTelemetry,
            cache = cache,
        )
        assertEquals(
            ScriptOrigin.COMPILED,
            cold.started().single().origin,
            "the first compilation did not compile, so the store holds nothing to reuse",
        )

        val warm = RecordingSink()
        val second = compileDurableScript(
            eventStore = warm,
            runId = RUN_ID,
            scriptPath = script,
            scriptContent = Files.readString(script),
            pluginJars = emptyList(),
            telemetry = NoopPipelineTelemetry,
            cache = cache,
        )

        assertEquals(
            ScriptOrigin.REUSED,
            warm.started().single().origin,
            "the second compilation did not reuse the stored artifact",
        )
        assertEquals(
            1,
            storedArtifacts().size,
            "the second compilation stored another artifact instead of reusing the first one",
        )
        val firstEntryPoint = required(first.scripted.entryPoint, "the first compilation produced no entry point")
        val secondEntryPoint = required(second.scripted.entryPoint, "the second compilation produced no entry point")
        assertEquals(
            firstEntryPoint.artifact.fingerprintMaterial(),
            secondEntryPoint.artifact.fingerprintMaterial(),
            "the reused artifact declares another identity than the one it was stored with",
        )
    }

    /**
     * The case where a DOUBLE compilation would leave two artifacts behind.
     *
     * The author's own `pwd` makes the ORIGINAL source compilable on its own,
     * so a run that compiled the original as well as the lowered program would
     * store BOTH: the program nobody runs and the program that runs. The cache
     * would answer "hit" for the program nobody runs, and the reuse would
     * hand the run something it never asked for.
     */
    @Test
    fun `a source that also compiles on its own still leaves one entry in the cache`() {
        val sink = RecordingSink()
        val script = writeScript("generator-with-own-pwd.pipeline.kts", GENERATOR_LEVEL_WITH_OWN_PWD_SOURCE)

        compileDurableScript(
            eventStore = sink,
            runId = RUN_ID,
            scriptPath = script,
            scriptContent = Files.readString(script),
            pluginJars = emptyList(),
            telemetry = NoopPipelineTelemetry,
            cache = JarCompiledScriptCache(cacheDir()),
        )

        assertEquals(
            1,
            sink.started().size,
            "the source was compiled more than once: the original is a program nobody runs",
        )
        assertEquals(
            1,
            storedArtifacts().size,
            "the run left more than one artifact in the script cache: one of them is " +
                "the compilation of a program nobody runs",
        )
    }

    @Test
    fun `the eager form is compiled once and yields the spec`() {
        val sink = RecordingSink()
        val script = writeScript("eager.pipeline.kts", EAGER_SOURCE)

        val compiled = compileDurableScript(
            eventStore = sink,
            runId = RUN_ID,
            scriptPath = script,
            scriptContent = Files.readString(script),
            pluginJars = emptyList(),
            telemetry = NoopPipelineTelemetry,
            cache = JarCompiledScriptCache(cacheDir()),
        )

        assertEquals(
            1,
            sink.started().size,
            "the eager form compiled the source more than once",
        )
        assertNotNull(
            compiled.pipeline,
            "the eager form produced no pipeline to run",
        )
        assertTrue(
            compiled.scripted.entryPoint == null,
            "the eager form offered a scripted entry point as well: the two shapes are alternatives",
        )
    }

    private fun cacheDir(): Path = workDir.resolve("compiled")

    private fun storedArtifacts(): List<Path> {
        val root = cacheDir()
        if (!Files.isDirectory(root)) return emptyList()
        return Files.list(root).use { it.toList() }
    }

    private fun writeScript(name: String, content: String): Path {
        val script = workDir.resolve(name)
        Files.writeString(script, content)
        return script
    }

    private fun <T> required(value: T?, message: String): T {
        assertNotNull(value, message)
        return value!!
    }

    private companion object {
        const val RUN_ID = "run-generator-level"

        /**
         * The natural generator-level source: the call is a scope member of the
         * eager DSL, so it does not resolve at a script's top level — which is
         * exactly why the form needs the lowering to become a program at all.
         */
        const val GENERATOR_LEVEL_SOURCE = "val path = pwd()\n"

        /**
         * The same shape, with the author's OWN `pwd`: the original source
         * compiles on its own, so the double compilation would be able to
         * store TWO artifacts.
         */
        const val GENERATOR_LEVEL_WITH_OWN_PWD_SOURCE =
            "fun pwd(): String = \"/tmp\"\nval path = pwd()\n"

        /** The eager DSL: the source builds the spec while the script runs. */
        const val EAGER_SOURCE =
            "pipeline {\n    stages {\n        stage(\"eager\") {\n            echo(\"hello\")\n        }\n    }\n}\n"
    }
}
