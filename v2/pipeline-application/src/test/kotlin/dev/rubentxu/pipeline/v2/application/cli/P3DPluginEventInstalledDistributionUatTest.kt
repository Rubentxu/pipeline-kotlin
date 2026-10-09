package dev.rubentxu.pipeline.v2.application.cli

import dev.rubentxu.pipeline.v2.application.support.AppBinSupport
import dev.rubentxu.pipeline.v2.application.support.CliRun
import dev.rubentxu.pipeline.v2.application.support.OwnedSubprocess
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
import java.time.Duration
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
 *   -> pass 1 admitted its manifest (no class loading)
 *   -> pass 2 composed ONE frozen EventRegistry from ADMITTED artifacts only
 *   -> the run's own composition report names this plugin's event kind
 *   -> a real `uppercaseObserved` Step declaring PLUGIN_EVENT_EMISSION_CAPABILITY (its zero-capability
 *      sibling `uppercase` stays untouched as the reference proof that a plugin can demand nothing)
 *   -> a real handler emitting example.uppercase.applied during a REAL run
 *   -> store-assigned sequence, interleaved with the core events
 *   -> process EXIT (the writer is gone)
 *   -> `pipeline events` re-reads the history WITHOUT re-executing
 *   -> typed read-side: readSlice -> carrier -> registry re-type -> same payload
 * ```
 *
 * BLOCK 2e added the two arrows that had never been drawn in a single run. Before, admission was
 * proven by [PluginAdmissionInstalledDistributionUatTest] and the durable event by this class, but
 * nothing asserted that the event came out of a composition that had been ADMITTED — two green tests
 * that did not meet. Step `1b` below closes that: the composition report is written only when at
 * least one artifact was admitted, and its event kinds come from the frozen registry pass 2 built
 * from admitted artifacts only, so this plugin's own kind appearing there is the arrow from
 * admission to emission, observed in the SAME process that emitted.
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
@Timeout(value = 30, unit = TimeUnit.MINUTES)
class P3DPluginEventInstalledDistributionUatTest {

    private val binary: File = AppBinSupport.discover().toFile()

    /**
     * S6-PRE: this used to fork the binary by hand, wait for it, and only THEN read stdout and
     * stderr — the exact shape the S6-PRE investigation removed.
     *
     * It was not hypothetical here. `Main.kt:431` prints a run's ENTIRE event log in one `println`,
     * so any run of a non-trivial pipeline can exceed the 64 KiB pipe buffer; a harness that waits
     * before it drains blocks forever, and when the class `@Timeout` finally fired the child
     * `pipelinek` JVM stayed alive and degraded every measurement after it. This file was the last
     * harness in the plugin-event chain still holding that shape, which is exactly why it sat in the
     * fitness allowlist. [OwnedSubprocess] drains BOTH pipes from the instant the child starts, gives
     * the child its own deadline, and reaps the tree on every path.
     *
     * The launch token is deliberately spelled nowhere in this comment: the fitness law scans test
     * sources as raw TEXT, so documenting the old shape here with its literal spelling would keep
     * this very file on the allowlist and make the migration unverifiable.
     */
    private fun run(vararg args: String): CliRun.Completed {
        val result = OwnedSubprocess.run(
            command = listOf(binary.absolutePath) + args,
            timeout = CLI_DEADLINE,
        )
        assertTrue(result is CliRun.Completed) {
            "the installed binary did not finish within ${CLI_DEADLINE.seconds}s on ${args.toList()}; " +
                "pid=${(result as? CliRun.TimedOut)?.diagnostics?.pid}. That is an ENVIRONMENT signal, " +
                "not a verdict about the plugin event chain."
        }
        return result as CliRun.Completed
    }

    /** Envelope kind in the `events` CLI jsonl; earlier "kind" keys belong to ref projections. */
    private fun envelopeKinds(jsonl: String): List<String> = jsonl.lineSequence()
        .filter { it.isNotBlank() }
        .mapNotNull { Regex("\"eventRefId\":\"[^\"]*\",\"kind\":\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
        .toList()

    /**
     * The external-event line of `PreResolvedComposition.reportTo`, verbatim, or null when absent.
     *
     * The string is not invented here: it is the production report's own prefix, so a rename upstream
     * breaks this test rather than silently turning it into an assertion that matches nothing.
     */
    private fun eventCompositionLine(stderr: String): String? =
        stderr.lineSequence()
            .firstOrNull { it.startsWith(EVENT_COMPOSITION_PREFIX) }

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
            import example.uppercase.uppercaseObserved

            pipeline {
                stages {
                    stage("External") {
                        uppercaseObserved("hello")
                    }
                }
            }
            """.trimIndent(),
        )
        val db = tempDir.resolve("db.sqlite").toString()

        // 1. A REAL run of the installed binary, with the external JAR on the classpath.
        val run1 = run("run", "--format", "json", "--db", db, "--plugin-jar", pluginJar.toAbsolutePath().toString(), script.toString())
        assertEquals(0, run1.exitCode, "run must succeed; stderr:\n${run1.stderr.takeLast(600)}")

        val runId = Regex("\"runId\":\"([^\"]+)\"").find(run1.stdout)!!.groupValues[1]
        val liveKinds = Regex("\"kind\":\"([^\"]+)\"").findAll(run1.stdout).map { it.groupValues[1] }.toList()
        assertTrue(
            liveKinds.contains("PluginEventEmitted"),
            "the plugin's event must be in the run's own stream, not only reachable from a test; " +
                "kinds seen: $liveKinds",
        )

        // 1b. BLOCK 2e — the arrow from ADMISSION to this emission, inside the same process.
        //
        // Everything above proves an event reached the store. It does not prove the registry that
        // emitted it was ever admitted, which is the property S6 exists for: an implementation can
        // emit perfectly while having bypassed the gate. The composition report closes that gap
        // because it is produced in exactly one place — `admitThenComposeOrExit`, which writes it
        // only when `admitted.isNotEmpty()`, and only from the frozen `EventRegistry` that pass 2
        // built from ADMITTED artifacts alone.
        //
        // So this plugin's OWN kind appearing on that line is the admission decision, reported by
        // the product, in the run that emitted. Asserting it separately from the emission would let
        // the two drift apart again, which is how the chain came to be unproven in the first place.
        val eventLine = eventCompositionLine(run1.stderr)
        assertTrue(
            eventLine != null && eventLine.contains(PLUGIN_EVENT_KIND),
            "the run's own composition report must name $PLUGIN_EVENT_KIND: that report is emitted " +
                "only after pass 1 admitted an artifact and pass 2 froze a registry from admitted " +
                "artifacts, so it is the observable proof that this event came out of an ADMITTED " +
                "composition rather than an ungated one. Report line was: $eventLine",
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
            "one observed Step means exactly one contributed event, not zero and not two",
        )

        // 3. The SEMANTIC payload, through the Event Plane's own read-side. The identity envelope
        //    above cannot carry it, so this is the only place the plugin's own bytes are visible.
        val carrier: PluginEventEmitted = SqliteEventStore(db).use { store ->
            store.readSlice(runId, after = null, limit = 10_000).events
                .filterIsInstance<PluginEventEmitted>()
                .single()
        }
        assertEquals(PLUGIN_EVENT_KIND, carrier.registryKind)
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
            import example.uppercase.uppercaseObserved

            pipeline {
                stages {
                    stage("External") {
                        uppercaseObserved("hello")
                    }
                }
            }
            """.trimIndent(),
        )
        val run1 = run("run", "--format", "json", "--db", db, "--plugin-jar", locatePluginJar().toAbsolutePath().toString(), script.toString())
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

    private companion object {
        /**
         * The subprocess's own contract. One `pipelinek run` composes five bundled plugins plus the
         * external one from a cold JVM and then executes a durable pipeline; the class-level
         * `@Timeout` stays as the outer "the whole test is broken" watchdog and is deliberately NOT
         * what bounds a normal run. Not an assertion: no row in this class reads a duration.
         */
        val CLI_DEADLINE: Duration = Duration.ofMinutes(10)

        /** The kind the external plugin's own `EventDefinition` declares. */
        const val PLUGIN_EVENT_KIND = "example.uppercase.applied"

        /**
         * Verbatim prefix of the external-event line written by `PreResolvedComposition.reportTo`.
         *
         * Copied, not invented: this file does not get to choose what the product says it admitted.
         */
        const val EVENT_COMPOSITION_PREFIX = "Discovered external event definitions:"
    }
}
