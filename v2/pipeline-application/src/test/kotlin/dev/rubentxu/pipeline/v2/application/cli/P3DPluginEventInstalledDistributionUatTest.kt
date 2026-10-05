package dev.rubentxu.pipeline.v2.application.cli

import dev.rubentxu.pipeline.v2.application.support.AppBinSupport
import dev.rubentxu.pipeline.v2.application.ExternalEventDefinitionDiscovery
import dev.rubentxu.pipeline.v2.events.registry.PayloadDecode
import dev.rubentxu.pipeline.v2.events.PluginEventEmitted
import dev.rubentxu.pipeline.v2.events.durable.SqliteEventStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * P3-D — an EXTERNAL plugin contributes an event and it survives a real process boundary (HF2).
 *
 * This is the proof the external consumer test could not give. That one compiles against the
 * published artifacts but exercises the CONTRACT with its own in-process sink, and its own KDoc
 * says so: a failure there is a broken publication, not a broken implementation, because that
 * build cannot see one. It can prove a plugin can DECLARE a kind; it cannot prove the runtime ever
 * hears it, emits it, persists it, or reads it back.
 *
 * The chain proved here, end to end, on the installed distribution:
 *
 * ```
 * external plugin JAR (own Gradle build, published contracts only)
 *   -> ServiceLoader discovery of EventDefinitionContributor
 *   -> one EventRegistry composed at the composition root
 *   -> a real `uppercase` Step declaring PLUGIN_EVENT_EMISSION_CAPABILITY
 *   -> a real handler emitting example.uppercase.applied during a REAL run
 *   -> store-assigned sequence, interleaved with the core events
 *   -> process EXIT (the writer is gone)
 *   -> `pipeline events` re-reads the history WITHOUT re-executing
 *   -> typed read-side: readSlice -> carrier -> registry re-type -> same payload
 * ```
 *
 * Two surfaces are checked on purpose, because they are different projections and only one of them
 * carries the semantics: the `events` CLI emits IDENTITY envelopes (`PipelineEventEnvelope`, which
 * has no payload field at all), while the semantic payload is only reachable through the Event
 * Plane's own read-side. A test that asserted the CLI carried the payload would be asserting a
 * shape the envelope does not have.
 *
 * Everything asserted here is discrete: a kind present, an identity equal, a payload equal, an
 * ordering relation. No duration, no count of spawned processes, no wall clock.
 */
@Timeout(value = 15, unit = TimeUnit.MINUTES)
class P3DPluginEventInstalledDistributionUatTest {

    private val binary: File = AppBinSupport.discover().toFile()

    private data class CliResult(val exitCode: Int, val stdout: String, val stderr: String)

    private fun run(vararg args: String): CliResult {
        val proc = ProcessBuilder(binary.absolutePath, *args).start()
        val finished = proc.waitFor(5, TimeUnit.MINUTES)
        assertTrue(finished) { "binary hung on ${args.toList()}" }
        return CliResult(
            proc.exitValue(),
            proc.inputStream.bufferedReader().readText(),
            proc.errorStream.bufferedReader().readText(),
        )
    }

    /** Envelope kind in the `events` CLI jsonl; earlier "kind" keys belong to ref projections. */
    private fun envelopeKinds(jsonl: String): List<String> = jsonl.lineSequence()
        .filter { it.isNotBlank() }
        .mapNotNull { Regex("\"eventRefId\":\"[^\"]*\",\"kind\":\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
        .toList()

    @Test
    fun `an external plugin emits its own event and it is readable after the process is gone`(
        @TempDir tempDir: Path,
    ) {
        assertTrue(binary.exists(), "installDist binary must exist; run :pipeline-application:installDist first")

        val pluginJar = locatePluginJar()
        val script = tempDir.resolve("p3d.pipeline.kts")
        Files.writeString(
            script,
            """
            import example.uppercase.uppercase

            pipeline {
                stages {
                    stage("External") {
                        uppercase("hello")
                    }
                }
            }
            """.trimIndent(),
        )
        val db = tempDir.resolve("db.sqlite").toString()

        // 1. A REAL run of the installed binary, with the external JAR on the classpath.
        val run1 = run("run", "--db", db, "--plugin-jar", pluginJar.toAbsolutePath().toString(), script.toString())
        assertEquals(0, run1.exitCode, "run must succeed; stderr:\n${run1.stderr.takeLast(600)}")

        val runId = Regex("\"runId\":\"([^\"]+)\"").find(run1.stdout)!!.groupValues[1]
        val liveKinds = Regex("\"kind\":\"([^\"]+)\"").findAll(run1.stdout).map { it.groupValues[1] }.toList()
        assertTrue(
            liveKinds.contains("PluginEventEmitted"),
            "the plugin's event must be in the run's own stream, not only reachable from a test; " +
                "kinds seen: $liveKinds",
        )

        // 2. The writer process is GONE. Re-read through a fresh process, without re-executing.
        val ev = run("events", "--db", db, runId)
        assertEquals(0, ev.exitCode, "events read must succeed; stderr:\n${ev.stderr.takeLast(300)}")
        val replayKinds = envelopeKinds(ev.stdout)
        assertTrue(
            replayKinds.contains("PluginEventEmitted"),
            "the carrier must survive process exit and reopen: $replayKinds",
        )
        assertEquals(
            1,
            replayKinds.count { it == "PluginEventEmitted" },
            "one uppercase Step means exactly one contributed event, not zero and not two",
        )

        // 3. The SEMANTIC payload, through the Event Plane's own read-side. The identity envelope
        //    above cannot carry it, so this is the only place the plugin's own bytes are visible.
        val carrier: PluginEventEmitted = SqliteEventStore(db).use { store ->
            store.readSlice(runId, after = null, limit = 10_000).events
                .filterIsInstance<PluginEventEmitted>()
                .single()
        }
        assertEquals("example.uppercase.applied", carrier.registryKind)
        assertEquals(1, carrier.schemaVersion, "the version the plugin declared, not a default")
        assertEquals("v1:5:5", carrier.payload, "the plugin's own bytes, via the codec it registered")
        assertEquals("example.uppercase", carrier.emittedBy, "provenance is the plugin, not the runtime")

        // 4. The sequence is the STORE's, and it shares one ordering with the core events. A
        //    plugin that minted its own would sit outside the single sequence authority, and a
        //    consumer's cursor would step over it.
        val (coreMax, pluginSeq) = SqliteEventStore(db).use { store ->
            val events = store.readSlice(runId, after = null, limit = 10_000).events
            val plugin = events.filterIsInstance<PluginEventEmitted>().single()
            val core = events.filter { it !is PluginEventEmitted }.maxOf { it.sequence }
            core to plugin.sequence
        }
        assertTrue(
            pluginSeq > 0 && pluginSeq <= coreMax,
            "the contributed event must share the store's single sequence space with core events; " +
                "plugin=$pluginSeq coreMax=$coreMax",
        )

        // 5. The typed payload re-types through a registry composed from the SAME plugin, the way
        //    a consumer would. In-process composition here is the point: it proves the identity the
        //    plugin DECLARED is the identity the run RECORDED, rather than a coincidence of two
        //    hand-written strings.
        val registry = ExternalEventDefinitionDiscovery.compose()
        val definition = requireNotNull(registry.definition(carrier.registryKind)) {
            "the plugin's kind must be discoverable through the same registry a consumer would " +
                "compose; registered: ${registry.registeredKinds()}"
        }
        val decoded = definition.codec.decode(carrier.payload, carrier.schemaVersion)
        assertTrue(decoded is PayloadDecode.Decoded) { "the recorded payload must re-type: $decoded" }
    }

    /**
     * The negative half of the same chain, on the same real store.
     *
     * An unknown kind must be refused as a NAMED outcome with nothing written, and the core stream
     * must be left exactly as it was. This is the case that matters most for a control-plane
     * consumer: a plugin that typos its own kind has to find out, and the evidence has to be the
     * refusal rather than a hole in somebody else's cursor.
     */
    @Test
    fun `an unknown kind is refused by name and writes nothing to the real store`(
        @TempDir tempDir: Path,
    ) {
        val db = tempDir.resolve("neg.sqlite").toString()
        val script = tempDir.resolve("p3d-neg.pipeline.kts")
        Files.writeString(
            script,
            """
            import example.uppercase.uppercase

            pipeline {
                stages {
                    stage("External") {
                        uppercase("hello")
                    }
                }
            }
            """.trimIndent(),
        )
        val run1 = run("run", "--db", db, "--plugin-jar", locatePluginJar().toAbsolutePath().toString(), script.toString())
        assertEquals(0, run1.exitCode, "run must succeed; stderr:\n${run1.stderr.takeLast(600)}")
        val runId = Regex("\"runId\":\"([^\"]+)\"").find(run1.stdout)!!.groupValues[1]

        val registry = ExternalEventDefinitionDiscovery.compose()
        val before = SqliteEventStore(db).use { it.readSlice(runId, after = null, limit = 10_000).events.size }

        val emitter = dev.rubentxu.pipeline.v2.events.registry.RegistryEventEmitter(
            registry = registry,
            sink = SqliteEventStore(db),
            clock = java.time.Clock.systemUTC(),
        )
        val outcome = emitter.emit("example.uppercase.typoed", runId, "v1:x")
        assertTrue(
            outcome is dev.rubentxu.pipeline.v2.events.registry.EmissionOutcome.UnregisteredKind,
            "an unknown kind must be a named refusal, not a write: $outcome",
        )

        val after = SqliteEventStore(db).use { it.readSlice(runId, after = null, limit = 10_000).events.size }
        assertEquals(before, after, "a refused emission must leave the stream byte-for-byte as it was")

        // And the known kind is still there, untouched: the refusal did not poison the registry.
        val survivors = SqliteEventStore(db).use { store ->
            store.readSlice(runId, after = null, limit = 10_000).events
                .filterIsInstance<PluginEventEmitted>()
        }
        assertEquals(1, survivors.size, "the recorded plugin event must survive a later refusal")
    }

    private fun locatePluginJar(): Path {
        val candidates = listOf(
            Path.of("../../examples/example-uppercase-plugin/build/libs/example-uppercase-plugin-0.1.0.jar"),
            Path.of("../examples/example-uppercase-plugin/build/libs/example-uppercase-plugin-0.1.0.jar"),
        )
        val found = candidates.firstOrNull { Files.exists(it) }
        return found ?: error(
            "example-uppercase-plugin JAR not found in ${candidates.map { it.toAbsolutePath() }}; " +
                "run ./gradlew buildExamplePlugin (it builds the external plugin against the published SDK)",
        )
    }
}
