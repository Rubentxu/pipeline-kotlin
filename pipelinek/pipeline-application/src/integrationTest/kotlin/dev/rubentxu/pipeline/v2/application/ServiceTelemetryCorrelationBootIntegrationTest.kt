package dev.rubentxu.pipeline.v2.application

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.events.durable.DurableStores
import dev.rubentxu.pipeline.v2.events.durable.EventStoreAdmission
import dev.rubentxu.pipeline.v2.events.durable.EventStorePurpose
import dev.rubentxu.pipeline.v2.events.durable.admitEventStore
import dev.rubentxu.pipeline.v2.runtime.LiveSpanAttributes
import dev.rubentxu.pipeline.v2.runtime.LiveSpanNames
import dev.rubentxu.pipeline.v2.runtime.PipelineTelemetryFactory
import dev.rubentxu.pipeline.v2.runtime.TelemetrySource
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeObservation
import dev.rubentxu.pipeline.v2.runtime.inspect.TerminalObservation
import dev.rubentxu.pipeline.v2.service.ServiceAnswer
import dev.rubentxu.pipeline.v2.service.ServiceClient
import dev.rubentxu.pipeline.v2.service.ServiceProtocol
import dev.rubentxu.pipeline.v2.service.ServiceRunStart
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.common.v1.KeyValue
import io.opentelemetry.proto.metrics.v1.Metric
import io.opentelemetry.proto.resource.v1.Resource
import io.opentelemetry.proto.trace.v1.Span
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * The live telemetry of a run the INSTALLED service starts, read by a consumer from its own
 * collector.
 *
 * ## The guarantee under trial
 *
 * That an operator who opens the service with the live telemetry of the product
 * (`PIPELINEK_OTEL_ENABLED=true` and the standard `OTEL_*`) gets the spans of every run the
 * service starts in the collector those variables name, and that a consumer finds them with
 * the identity the start already answered: the attribute `run.id` of the spans of the run is
 * the `runId` of [ServiceRunStart].
 *
 * The id, and not a trace, is what joins them. No parent is handed to the child, so the
 * compilation of the script and the run each start a trace of their own, with the stages and
 * the steps under the run; and the spans the service opens to answer about the run —its state,
 * asked for until the terminal— carry the same id, each in a trace of its own. A span of the
 * run hangs from nothing but a span of the same run, and shares no trace with the service.
 *
 * And, with the same export:
 *
 * - no span is exported twice: the service exports its own boundary spans and the child the
 *   spans of the run, and no span id is in both;
 * - no metric is split by a value that is unique to a run: no data point carries the run's id,
 *   the consumer's key or the attribute `run.id`;
 * - nothing the run carried leaves in the export: the line the step printed, the credential
 *   of the client and the key it started the run with are in no exported byte;
 * - a collector that is down does not change what the run comes to.
 *
 * ## Why these rows are NOT in the gate
 *
 * Because they start the INSTALLED product: the ZIP the installer publishes, its launcher and
 * the service it starts. The environment of a process is only given when the process starts,
 * and the guarantee is about the environment the OPERATOR gives the service and the child
 * inherits; a service composed inside the lane's own JVM would hand its child the lane's
 * environment, and the lane does not have the operator's. These rows are declared in
 * `scripts/check-product-boot-layer.py` and run on demand:
 *
 *     ./pipelinek/gradlew -p pipelinek :pipeline-application:integrationTest \
 *         --tests '*ServiceTelemetryCorrelationBoot*'
 *
 * ## Why the collector is read with the official OTLP model
 *
 * Because the consumer reads the export with a real collector, and the bytes the product
 * sends are protobuf of the OTLP schema. A decoder written here would be a second
 * understanding of the format, and a span this project's decoder accepted while a collector
 * rejected it would be a span nobody verified.
 *
 * ## What this does not freeze
 *
 * Which spans a run opens beyond the run, its stage and its step, the order the exporter
 * sends them in, and how many requests it takes.
 */
@Timeout(value = 10, unit = TimeUnit.MINUTES)
@Tag("boot-check")
class ServiceTelemetryCorrelationBootIntegrationTest {

    /** Where the measurement happens: the ZIP, the installation, the service and the collector. */
    @TempDir
    lateinit var root: Path

    @Test
    fun `a consumer finds the spans of a run the service started by the id the start answered`() {
        val collector = Collector.start()
        try {
            val launcher = launcher()
            val outcome = startOneRun(launcher, endpoint = collector.endpoint)
            assertInstanceOf(TerminalObservation.Succeeded::class.java, outcome.terminal, "the run did not succeed")

            val runId = outcome.start.runId
            val spans = collector.awaitSpans { all -> all.any { it.span.name == LiveSpanNames.RUN && it.runId() == runId } }
            val ofTheRun = spans.filter { it.runId() == runId }
            val (ofTheService, ofTheChild) = ofTheRun.partition { it.span.name.startsWith(SERVICE_SPAN_PREFIX) }
            val shape = shapeOf(ofTheRun)
            val names = ofTheChild.map { it.span.name }

            assertEquals(1, names.count { it == LiveSpanNames.RUN }, "the run span is not exported once:\n$shape")
            for (name in listOf(LiveSpanNames.COMPILE, LiveSpanNames.STAGE, LiveSpanNames.STEP_DISPATCH, LiveSpanNames.STEP_INVOKE)) {
                assertTrue(name in names, "the span $name of the run is not found by its run id:\n$shape")
            }

            val run = ofTheChild.single { it.span.name == LiveSpanNames.RUN }
            assertTrue(run.span.parentSpanId.isEmpty, "the run hangs from a parent nobody gave it:\n$shape")
            for (name in listOf(LiveSpanNames.STAGE, LiveSpanNames.STEP_DISPATCH, LiveSpanNames.STEP_INVOKE)) {
                assertTrue(
                    ofTheChild.filter { it.span.name == name }.all { it.span.traceId == run.span.traceId },
                    "the span $name is not in the trace of its run:\n$shape",
                )
            }
            val ownIds = ofTheChild.map { it.span.spanId }.toSet()
            assertTrue(
                ofTheChild.all { it.span.parentSpanId.isEmpty || it.span.parentSpanId in ownIds },
                "a span of the run hangs from a span that is not of the run:\n$shape",
            )
            val ofTheTrace = spans.filter { it.span.traceId == run.span.traceId }
            assertTrue(
                ofTheTrace.all { it.span.spanId in ownIds },
                "the trace of the run holds spans that are not of the run:\n${shapeOf(ofTheTrace)}",
            )

            assertTrue(ofTheService.isNotEmpty(), "the service answered about the run and no span of it says so:\n$shape")
            val childTraces = ofTheChild.map { it.span.traceId }.toSet()
            assertTrue(
                ofTheService.none { it.span.traceId in childTraces },
                "the service and the run share a trace:\n$shape",
            )
            assertEquals(
                setOf(TelemetrySource.LIVE),
                ofTheRun.map { it.resource.text(TelemetrySource.KEY) }.toSet(),
                "the spans of the run do not say they are the live telemetry",
            )

            val spanIds = spans.map { hex(it.span.spanId.toByteArray()) }
            assertEquals(spanIds.size, spanIds.toSet().size, "a span was exported twice")

            val dimensions = collector.metricDimensions()
            assertTrue(dimensions.isNotEmpty(), "no metric was exported, so no dimension was measured")
            assertTrue(
                dimensions.none { (key, value) ->
                    key == LiveSpanAttributes.RUN_ID || runId in value || IDEMPOTENCY_KEY in value
                },
                "a metric is split by a value that is unique to the run: $dimensions",
            )

            val exported = collector.everyByte()
            for ((name, secret) in listOf(
                "the line the step printed" to PAYLOAD,
                "the credential of the client" to TOKEN,
                "the key the client started the run with" to IDEMPOTENCY_KEY,
            )) {
                assertFalse(exported.contains(secret), "$name left in the export")
            }
            println(
                "telemetry-on run: ${outcome.elapsed.toMillis()} ms to its terminal, ${spans.size} spans, " +
                    "${ofTheChild.size} of the run and ${ofTheService.size} of the service about it",
            )
        } finally {
            collector.stop()
        }
    }

    @Test
    fun `a collector that is down leaves the outcome of the run as it was`() {
        val launcher = launcher()
        val outcome = startOneRun(launcher, endpoint = deadEndpoint())

        assertInstanceOf(
            TerminalObservation.Succeeded::class.java,
            outcome.terminal,
            "the run came to another outcome with its collector down",
        )
        println("telemetry-on, collector down: ${outcome.elapsed.toMillis()} ms to its terminal")
    }

    /** What one run of the service came to, and how long it took from the start to its terminal. */
    private class Outcome(val start: ServiceRunStart, val terminal: TerminalObservation, val elapsed: Duration)

    /**
     * Starts the installed service with the live telemetry pointed at [endpoint], starts one run
     * through it and waits for the run's terminal. The service is stopped before this returns.
     */
    private fun startOneRun(launcher: Path, endpoint: String): Outcome {
        val home = Files.createDirectories(root.resolve("service"))
        Files.setPosixFilePermissions(home, PosixFilePermissions.fromString("rwx------"))
        val control = Files.createDirectories(home.resolve("control"))
        val scripts = Files.createDirectories(root.resolve("scripts"))
        Files.writeString(scripts.resolve(SCRIPT), SCRIPT_TEXT)
        val database = root.resolve("store.db")
        createSubstrate(database, control)
        val authorizations = home.resolve("starter.authorizations")
        Files.writeString(
            authorizations,
            ServiceAuthorizations.format(
                listOf(
                    ServiceAuthorization(
                        TOKEN,
                        "starter",
                        ServiceScope.WithRights(ServiceScope.All, setOf(ServiceScope.Right.Start)),
                    ),
                ),
            ),
        )
        Files.setPosixFilePermissions(authorizations, PosixFilePermissions.fromString("rw-------"))
        val socket = home.resolve("service.sock")
        val log = root.resolve("serve.log")
        val builder = ProcessBuilder(
            launcher.toString(),
            "serve",
            "--db", database.toString(),
            "--control-root", control.toString(),
            "--socket", socket.toString(),
            "--authorization-file", authorizations.toString(),
            "--scripts", scripts.toString(),
        ).redirectErrorStream(true).redirectOutput(log.toFile())
        val environment = builder.environment()
        environment.keys.removeIf { it.startsWith("OTEL_") || it.startsWith("PIPELINEK_OTEL_") }
        environment["JAVA_HOME"] = System.getProperty("java.home")
        environment[PipelineTelemetryFactory.OTEL_ENABLED_ENV] = "true"
        environment["OTEL_EXPORTER_OTLP_ENDPOINT"] = endpoint
        environment["OTEL_EXPORTER_OTLP_PROTOCOL"] = "http/protobuf"
        environment["OTEL_EXPORTER_OTLP_TIMEOUT"] = EXPORT_TIMEOUT_MILLIS.toString()
        environment["OTEL_METRIC_EXPORT_INTERVAL"] = METRIC_INTERVAL_MILLIS.toString()
        environment["OTEL_BSP_SCHEDULE_DELAY"] = SPAN_DELAY_MILLIS.toString()
        val process = builder.start()
        try {
            awaitTheSocket(process, socket, log)
            val client = ServiceClient(socket, TOKEN, Duration.ofSeconds(CLIENT_TIMEOUT_SECONDS), ServiceProtocol.SCHEMA)
            val began = System.nanoTime()
            val answer = client.startRun(script = SCRIPT, idempotencyKey = IDEMPOTENCY_KEY)
            val start = assertInstanceOf(
                ServiceRunStart::class.java,
                assertInstanceOf(ServiceAnswer.Answered::class.java, answer, "the start was refused: $answer").value,
            )
            val terminal = awaitTerminal(client, start.runId, log)
            val elapsed = Duration.ofNanos(System.nanoTime() - began)
            assertInstanceOf(
                ServiceAnswer.Answered::class.java,
                client.version(),
                "the service stopped answering after the run",
            )
            return Outcome(start, terminal, elapsed)
        } finally {
            process.destroy()
            assertTrue(process.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS), "the service did not stop")
        }
    }

    /** The terminal of [runId], asked for until it arrives. */
    private fun awaitTerminal(client: ServiceClient, runId: String, log: Path): TerminalObservation {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TERMINAL_TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            val answer = client.runState(runId)
            if (answer is ServiceAnswer.Answered) {
                val observation = answer.value
                if (observation is RuntimeObservation.Terminal) return observation.terminal
            }
            Thread.sleep(POLL_MILLIS)
        }
        refuse("the run $runId did not finish in time:\n${Files.readString(log)}")
    }

    /** Waits until the service listens on [socket] and the socket is private. */
    private fun awaitTheSocket(process: Process, socket: Path, log: Path) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LISTEN_TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            if (!process.isAlive) refuse("the service stopped before it listened:\n${Files.readString(log)}")
            if (Files.exists(socket) && Files.getPosixFilePermissions(socket) == PRIVATE_SOCKET) return
            Thread.sleep(POLL_MILLIS)
        }
        refuse("the service did not listen on $socket:\n${Files.readString(log)}")
    }

    /**
     * An endpoint nothing listens on: a port the system handed out and that was closed again,
     * so a connection to it is refused.
     */
    private fun deadEndpoint(): String {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        return "http://127.0.0.1:$port"
    }

    /** The launcher of the unpacked distribution, made executable as the installer makes it. */
    private fun launcher(): Path {
        val unpacked = Files.createDirectories(root.resolve("distribution"))
        val zip = Path.of(
            requireNotNull(System.getProperty("pipelinek.distribution.zip")) {
                "the lane was run without the distribution: the build has to hand over the ZIP it measured"
            },
        )
        ZipFile(zip.toFile()).use { archive ->
            val entries = archive.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val destination = unpacked.resolve(entry.name).normalize()
                check(destination.startsWith(unpacked)) {
                    "the distribution carries an entry that leaves the directory it is unpacked in: ${entry.name}"
                }
                if (entry.isDirectory) {
                    Files.createDirectories(destination)
                    continue
                }
                Files.createDirectories(destination.parent)
                archive.getInputStream(entry).use { bytes -> Files.copy(bytes, destination) }
            }
        }
        val launcher = Files.newDirectoryStream(unpacked).use { directories ->
            directories.filter { Files.isDirectory(it) }
                .map { it.resolve("bin").resolve("pipelinek") }
                .single { Files.isRegularFile(it) }
        }
        Files.setPosixFilePermissions(launcher, Files.getPosixFilePermissions(launcher) + PosixFilePermission.OWNER_EXECUTE)
        return launcher
    }

    /** The substrate the service reads, created the way the product creates it. */
    private fun createSubstrate(database: Path, controlRoot: Path) {
        val admission = admitEventStore(database.toString(), EventStorePurpose.Produce)
        val admitted = admission as? EventStoreAdmission.Admitted
            ?: refuse("the substrate could not be created: $admission")
        DurableStores.open(admitted.store, controlRoot, FixedClock)
        admitted.store.close()
    }

    /** The spans as a reader of a trace sees them: the name, the trace, the span and its parent. */
    private fun shapeOf(spans: List<ReceivedSpan>): String = spans.joinToString("\n") {
        "  ${it.span.name} trace=${hex(it.span.traceId.toByteArray())} span=${hex(it.span.spanId.toByteArray())} " +
            "parent=${hex(it.span.parentSpanId.toByteArray()).ifEmpty { "-" }} start=${it.span.startTimeUnixNano}"
    }

    private fun hex(bytes: ByteArray): String = HexFormat.of().formatHex(bytes)

    /** The end of a measurement that could not be made. */
    private fun refuse(message: String): Nothing = throw AssertionError(message)

    /** A clock that does not move: the substrate is measured, not the time. */
    private object FixedClock : Clock {
        override fun now(): Instant = Instant.EPOCH
    }

    /** A span as the collector received it, with the resource of the producer that sent it. */
    private class ReceivedSpan(val resource: Resource, val span: Span) {

        /** The value of the attribute `run.id`, when the span carries it. */
        fun runId(): String? = span.attributesList.text(LiveSpanAttributes.RUN_ID)
    }

    /**
     * A collector of OTLP over HTTP: it answers every export with success and keeps the bytes
     * it was sent, by the path they were sent to.
     */
    private class Collector private constructor(private val server: HttpServer) {

        private val received = ConcurrentLinkedQueue<Pair<String, ByteArray>>()

        /** The endpoint the standard `OTEL_EXPORTER_OTLP_ENDPOINT` names this collector with. */
        val endpoint: String get() = "http://127.0.0.1:${server.address.port}"

        private fun receive(exchange: HttpExchange) {
            exchange.use {
                val raw = exchange.requestBody.readAllBytes()
                val gzipped = exchange.requestHeaders.getFirst("Content-Encoding").equals("gzip", ignoreCase = true)
                val body = if (gzipped) GZIPInputStream(raw.inputStream()).readAllBytes() else raw
                received += exchange.requestURI.path to body
                exchange.responseHeaders.add("Content-Type", "application/x-protobuf")
                exchange.sendResponseHeaders(SUCCESS, -1)
            }
        }

        /** Every span received, waited for until [enough] says the export arrived. */
        fun awaitSpans(enough: (List<ReceivedSpan>) -> Boolean): List<ReceivedSpan> {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(EXPORT_ARRIVAL_SECONDS)
            while (System.nanoTime() < deadline) {
                val spans = spans()
                if (enough(spans)) return spans
                Thread.sleep(POLL_MILLIS)
            }
            throw AssertionError("the export did not arrive; the collector holds ${spans().map { it.span.name }}")
        }

        private fun spans(): List<ReceivedSpan> = received.filter { it.first == TRACES_PATH }.flatMap { (_, body) ->
            ExportTraceServiceRequest.parseFrom(body).resourceSpansList.flatMap { resourceSpans ->
                resourceSpans.scopeSpansList.flatMap { scope ->
                    scope.spansList.map { ReceivedSpan(resourceSpans.resource, it) }
                }
            }
        }

        /** Every attribute a metric data point was exported with, as a key and the text of its value. */
        fun metricDimensions(): List<Pair<String, String>> =
            received.filter { it.first == METRICS_PATH }.flatMap { (_, body) ->
                ExportMetricsServiceRequest.parseFrom(body).resourceMetricsList.flatMap { resourceMetrics ->
                    resourceMetrics.scopeMetricsList.flatMap { scope -> scope.metricsList.flatMap(::dimensionsOf) }
                }
            }

        /** Every byte the collector received, whatever the path. */
        fun everyByte(): String {
            val all = ByteArrayOutputStream()
            received.forEach { all.write(it.second) }
            return all.toString(Charsets.ISO_8859_1)
        }

        fun stop() {
            server.stop(0)
        }

        companion object {
            fun start(): Collector {
                val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
                val collector = Collector(server)
                server.createContext("/") { collector.receive(it) }
                server.executor = Executors.newFixedThreadPool(2)
                server.start()
                return collector
            }

            private fun dimensionsOf(metric: Metric): List<Pair<String, String>> {
                val points: List<List<KeyValue>> = when {
                    metric.hasSum() -> metric.sum.dataPointsList.map { it.attributesList }
                    metric.hasGauge() -> metric.gauge.dataPointsList.map { it.attributesList }
                    metric.hasHistogram() -> metric.histogram.dataPointsList.map { it.attributesList }
                    metric.hasExponentialHistogram() -> metric.exponentialHistogram.dataPointsList.map { it.attributesList }
                    metric.hasSummary() -> metric.summary.dataPointsList.map { it.attributesList }
                    else -> emptyList()
                }
                return points.flatten().map { it.key to it.value.toString() }
            }

            const val SUCCESS: Int = 200
            const val TRACES_PATH: String = "/v1/traces"
            const val METRICS_PATH: String = "/v1/metrics"
        }
    }

    private companion object {
        const val TOKEN: String = "token-of-the-consumer-c4a1"
        const val IDEMPOTENCY_KEY: String = "key-of-the-consumer-7f3e"
        const val PAYLOAD: String = "the-line-the-step-printed-9d2b"
        const val SCRIPT: String = "correlated.pipeline.kts"
        const val SERVICE_SPAN_PREFIX: String = "pipelinek.service."

        /** A run with one stage and one step, whose step prints a line the export must not carry. */
        const val SCRIPT_TEXT: String =
            "pipeline {\n" +
                "    stages {\n" +
                "        stage(\"build\") {\n" +
                "            sh(\"echo $PAYLOAD\")\n" +
                "        }\n" +
                "    }\n" +
                "}\n"

        const val EXPORT_TIMEOUT_MILLIS: Long = 2_000L
        const val METRIC_INTERVAL_MILLIS: Long = 500L
        const val SPAN_DELAY_MILLIS: Long = 100L
        const val CLIENT_TIMEOUT_SECONDS: Long = 60L
        const val LISTEN_TIMEOUT_SECONDS: Long = 120L
        const val TERMINAL_TIMEOUT_SECONDS: Long = 240L
        const val EXPORT_ARRIVAL_SECONDS: Long = 60L
        const val STOP_TIMEOUT_SECONDS: Long = 30L
        const val POLL_MILLIS: Long = 200L

        val PRIVATE_SOCKET: Set<PosixFilePermission> = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        )

        fun List<KeyValue>.text(key: String): String? =
            firstOrNull { it.key == key }?.value?.takeIf { it.hasStringValue() }?.stringValue

        fun Resource.text(key: String): String? = attributesList.text(key)
    }
}
