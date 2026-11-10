package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.credentials.spi.CredentialProvider
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.credentials.Credential
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.events.durable.DurableStores
import dev.rubentxu.pipeline.v2.events.durable.EventStoreAdmission
import dev.rubentxu.pipeline.v2.events.durable.EventStorePurpose
import dev.rubentxu.pipeline.v2.events.durable.admitEventStore
import dev.rubentxu.pipeline.v2.runtime.NoopPipelineTelemetry
import dev.rubentxu.pipeline.v2.runtime.PipelineTelemetry
import dev.rubentxu.pipeline.v2.runtime.PipelineTelemetryFactory
import dev.rubentxu.pipeline.v2.service.ServiceAnswer
import dev.rubentxu.pipeline.v2.service.ServiceClient
import dev.rubentxu.pipeline.v2.service.ServiceProtocol
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.data.LongPointData
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The telemetry of the service, composed over a real store and a real socket.
 *
 * ## The guarantee under trial
 *
 * That a service composed with an enabled telemetry publishes what a client asked of it, through
 * the same telemetry the process opened; that it never publishes the credential of the client;
 * and that it closes that telemetry once, and only after it has stopped answering and has given
 * back everything else it opened.
 *
 * ## The capacity this serves
 *
 * An operator who points the service at a collector has to be able to read what the service did
 * in the last seconds before it stopped, and has to be able to stop it twice (the end of the verb
 * and the hook of the process both do) without the exporter reporting a loss that did not happen.
 *
 * ## Why this instrument is an INTEGRATION test
 *
 * The observer is a seam the composition fills, and the unit rows of the observer prove it with a
 * double of the server. Whether the composition fills it, with the telemetry it opened and in the
 * order it closes things, is a fact about the composition: a real store, a real socket and a real
 * client.
 *
 * ## What this does not freeze
 *
 * The words of the span names, which belong to the rows of the observer, and how long the
 * exporter takes to see a span end.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ServiceTelemetryIntegrationTest {

    @TempDir
    lateinit var root: Path

    private lateinit var db: Path
    private lateinit var control: Path
    private lateinit var scripts: Path
    private lateinit var socket: Path

    private val errors = ByteArrayOutputStream()
    private val spans = InMemorySpanExporter.create()
    private val reader = InMemoryMetricReader.create()

    @BeforeEach
    fun material() {
        db = root.resolve("store.db")
        control = Files.createDirectories(root.resolve("control"))
        scripts = Files.createDirectories(root.resolve("scripts"))
        socket = root.resolve("service.sock")
        // The service reads a substrate it is given and never creates one.
        val writable = (admitEventStore(db.toString(), EventStorePurpose.Produce) as EventStoreAdmission.Admitted).store
        DurableStores.open(writable, control, FixedClock)
        writable.close()
    }

    @Test
    fun `a service with an enabled telemetry publishes the question a client asked and never the credential`() {
        val telemetry = RecordingTelemetry(realTelemetry())
        val service = compose(telemetryOpening = { telemetry })
        val serving = serveInBackground(service)
        // Stopping the service closes the telemetry and the in-memory exporter forgets what it saw
        // when it is shut down, so what was published is read BEFORE the service stops.
        val exported: String
        val span: SpanData
        val points: List<LongPointData>
        try {
            val answer = client().version()

            assertInstanceOf(ServiceAnswer.Answered::class.java, answer)
            assertEquals(1, awaitPublished(), "the question left one span")
            span = spans.finishedSpanItems.single()
            points = reader.collectAllMetrics()
                .single { it.name == "pipelinek.boundary_total" }
                .longSumData.points.toList()
            exported = published()
        } finally {
            service.close()
            serving.join(JOIN_MILLIS)
        }

        assertEquals("pipelinek.service.query", span.name)
        assertTrue(
            span.attributes.isEmpty,
            "a question about the service names no run, so the span names none: ${span.attributes}",
        )
        assertEquals(1, points.size)
        assertEquals(
            mapOf("operation_kind" to "service.query", "status" to "success"),
            points.single().attributes.asMap().entries.associate { (key, value) -> key.key to value.toString() },
            "the count carries the closed dimensions and nothing the client chose",
        )
        assertEquals(1L, points.single().value)
        assertFalse(TOKEN in exported, "the credential never leaves the process")
        assertEquals(1, telemetry.closes.get(), "stopping the service closed the telemetry it was composed with")
    }

    @Test
    fun `a service with no telemetry published nothing and opens the exporter of nobody`() {
        val opened = AtomicInteger(0)
        val service = compose(telemetryOpening = {
            opened.incrementAndGet()
            NoopPipelineTelemetry
        })
        val serving = serveInBackground(service)
        try {
            assertInstanceOf(ServiceAnswer.Answered::class.java, client().version())
        } finally {
            service.close()
            serving.join(JOIN_MILLIS)
        }

        assertEquals(1, opened.get(), "the telemetry is opened once, from the environment of the operator")
        assertTrue(spans.finishedSpanItems.isEmpty(), "a service nobody asked to export exports nothing")
        assertTrue(reader.collectAllMetrics().none { it.name == "pipelinek.boundary_total" })
    }

    @Test
    fun `stopping the service twice closes the telemetry once and after everything else it opened`() {
        val provider = ProviderDouble()
        val telemetry = RecordingTelemetry(realTelemetry())
        telemetry.onClose = { telemetry.seen = SeenAtClose(listening(), provider.closed.get()) }
        val service = compose(
            authorizations = withGrant(),
            brokerOpening = { ServiceCredentialAccess.Opened(provider) },
            telemetryOpening = { telemetry },
        )
        val serving = serveInBackground(service)
        assertInstanceOf(ServiceAnswer.Answered::class.java, client().version())

        service.close()
        serving.join(JOIN_MILLIS)
        service.close()

        assertEquals(1, telemetry.closes.get(), "the exporter is closed once, however many times the service is")
        val seen = telemetry.seen
        assertFalse(seen.listening, "the telemetry is closed after the service stopped answering")
        assertTrue(seen.providerClosed, "the telemetry is closed after the credential provider was given back")
    }

    @Test
    fun `a service that cannot bind its socket opens no telemetry`() {
        val first = compose(telemetryOpening = { NoopPipelineTelemetry })
        try {
            val opened = AtomicInteger(0)

            val refused = compose(telemetryOpening = {
                opened.incrementAndGet()
                RecordingTelemetry(realTelemetry())
            })

            assertInstanceOf(ServeComposition.Refused::class.java, refused)
            assertEquals(0, opened.get(), "a refusal before the socket is held has nothing to flush")
        } finally {
            first.close()
        }
    }

    @Test
    fun `a service whose credential store cannot be opened opens no telemetry`() {
        val opened = AtomicInteger(0)

        val refused = compose(
            authorizations = withGrant(),
            brokerOpening = { ServiceCredentialAccess.Refused("the credential store could not be unlocked") },
            telemetryOpening = {
                opened.incrementAndGet()
                RecordingTelemetry(realTelemetry())
            },
        )

        assertInstanceOf(ServeComposition.Refused::class.java, refused)
        assertEquals(0, opened.get(), "a refusal before the socket is held has nothing to flush")
    }

    // --- fixtures ---

    private fun realTelemetry(): PipelineTelemetry {
        val sdk = OpenTelemetrySdk.builder()
            .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spans)).build())
            .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(reader).build())
            .build()
        return PipelineTelemetryFactory.sdkPipelineTelemetry(sdk, warning = { })
    }

    private fun authorizations(): ServiceAuthorizations =
        ServiceAuthorizations.of(listOf(ServiceAuthorization(TOKEN, "operator", ServiceScope.All)))

    private fun withGrant(): ServiceAuthorizations = ServiceAuthorizations.of(
        listOf(
            ServiceAuthorization(
                TOKEN,
                "operator",
                ServiceScope.All,
                setOf(CredentialGrant("deploy-key", BrokeredCredentialKind.STRING)),
            ),
        ),
    )

    private fun compose(
        authorizations: ServiceAuthorizations = authorizations(),
        brokerOpening: (Map<String, String>) -> ServiceCredentialAccess = {
            ServiceCredentialAccess.Refused("a service with no grant never opens the credential store")
        },
        telemetryOpening: (Map<String, String>) -> PipelineTelemetry,
    ): ServeComposition {
        val registry = when (val read = ServiceStartRegistry.read(root.resolve(ServiceStartRegistry.FILE_NAME))) {
            is ServiceStartRegistryReading.Read -> read.registry
            is ServiceStartRegistryReading.Refused -> error(read.detail)
        }
        return composeServiceToServe(
            substrate = ServeSubstrate(
                dbPath = db.toString(),
                dbAbsolute = db,
                controlRoot = control.toString(),
                controlAbsolute = control,
                controlRootPath = control,
                socketAbsolute = socket,
                socketPath = socket.toString(),
            ),
            policy = ServiceRunDeclaration(
                scriptsRoot = scripts,
                workspacesRoot = null,
                deadlineMillis = RUN_DEADLINE_MILLIS,
                startDeadlineMillis = START_DEADLINE_MILLIS,
                maxSupervisedRuns = 2,
                allowNetwork = false,
                registry = registry,
            ),
            authorizations = authorizations,
            clock = SystemClock(),
            err = PrintStream(errors, true),
            environment = emptyMap(),
            brokerOpening = brokerOpening,
            telemetryOpening = telemetryOpening,
        )
    }

    private fun serveInBackground(composed: ServeComposition): Thread {
        val service = assertInstanceOf(ServeComposition.Composed::class.java, composed, "$errors")
        return Thread({ service.server.serve() }, "service-under-test").apply {
            isDaemon = true
            start()
        }
    }

    private fun ServeComposition.close() {
        (this as? ServeComposition.Composed)?.close()
    }

    private fun client(): ServiceClient = ServiceClient(
        endpoint = socket,
        credential = TOKEN,
        deadline = Duration.ofSeconds(CLIENT_DEADLINE_SECONDS),
        schema = ServiceProtocol.SCHEMA,
    )

    /** Waits until the exporter has seen a span end: the answer is written before the span ends. */
    private fun awaitPublished(): Int {
        val limit = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PUBLISH_DEADLINE_MILLIS)
        while (spans.finishedSpanItems.isEmpty() && System.nanoTime() < limit) {
            Thread.sleep(POLL_MILLIS)
        }
        return spans.finishedSpanItems.size
    }

    /** Everything the process exported, as one text. */
    private fun published(): String = buildString {
        spans.finishedSpanItems.forEach { span ->
            append(span.name).append(' ')
            span.attributes.asMap().forEach { (key, value) -> append(key.key).append('=').append(value).append(' ') }
        }
        reader.collectAllMetrics().forEach { metric ->
            append(metric.name).append(' ')
            metric.longSumData.points.forEach { point ->
                point.attributes.asMap().forEach { (key, value) -> append(key.key).append('=').append(value).append(' ') }
            }
        }
    }

    /** Whether something still accepts connections on the socket of the service. */
    private fun listening(): Boolean = try {
        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.connect(UnixDomainSocketAddress.of(socket))
            true
        }
    } catch (_: IOException) {
        false
    }

    private class SeenAtClose(val listening: Boolean, val providerClosed: Boolean)

    /** The telemetry of the process, counting how many times it was closed and what was true then. */
    private class RecordingTelemetry(private val inner: PipelineTelemetry) : PipelineTelemetry by inner {

        val closes = AtomicInteger(0)

        @Volatile var seen: SeenAtClose = SeenAtClose(listening = true, providerClosed = false)

        @Volatile var onClose: () -> Unit = { }

        override fun close() {
            closes.incrementAndGet()
            onClose()
            inner.close()
        }
    }

    /** A provider that records whether it was closed. */
    private class ProviderDouble : CredentialProvider {
        val closed = AtomicBoolean(false)

        override val providerId: String = "provider-double"

        override fun resolve(id: CredentialsId): SecretHandle = error("not asked in these rows")

        override fun resolveToCredential(id: CredentialsId): Credential = error("not asked in these rows")

        override fun close() {
            closed.set(true)
        }
    }

    private object FixedClock : Clock {
        override fun now(): Instant = Instant.EPOCH
    }

    private companion object {
        const val TOKEN: String = "token-of-the-operator"
        const val RUN_DEADLINE_MILLIS: Long = 240_000L
        const val START_DEADLINE_MILLIS: Long = 120_000L
        const val CLIENT_DEADLINE_SECONDS: Long = 60L
        const val JOIN_MILLIS: Long = 10_000L
        const val PUBLISH_DEADLINE_MILLIS: Long = 5_000L
        const val POLL_MILLIS: Long = 10L
    }
}
