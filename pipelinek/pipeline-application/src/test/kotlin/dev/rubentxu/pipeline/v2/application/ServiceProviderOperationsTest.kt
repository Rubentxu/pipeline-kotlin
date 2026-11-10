package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.AttemptId
import dev.rubentxu.pipeline.v2.runtime.inspect.OperationInspectionResult
import dev.rubentxu.pipeline.v2.runtime.inspect.RunStructureResult
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeIntrospectionPort
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeIntrospectionResult
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeObservation
import dev.rubentxu.pipeline.v2.runtime.inspect.StructureRunOptions
import dev.rubentxu.pipeline.v2.runtime.inspect.TerminalObservation
import dev.rubentxu.pipeline.v2.service.ServiceProtocol
import dev.rubentxu.pipeline.v2.service.ServiceProviderKey
import dev.rubentxu.pipeline.v2.service.ServiceRefusal
import dev.rubentxu.pipeline.v2.service.ServiceRequest
import dev.rubentxu.pipeline.v2.service.ServiceResponse
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * What the socket answers about configuration providers and about where a run got its
 * configuration from.
 *
 * ## The guarantees under trial
 *
 *  - every provider operation has a NAME for what it came to: a session registers, a poll finds
 *    the keys a run waits for or nothing, an answer is acknowledged, a close is acknowledged, and
 *    every refusal carries the word the session gave it;
 *  - a provider session belongs to the credential that registered it: an operation with no
 *    credential, or with another one, reaches nothing;
 *  - a key labelled as a secret never registers, so a secret is never accepted as configuration;
 *  - the description of a run says where each key came from and carries no value;
 *  - an operation that fails in a way nobody foresaw is a refusal with a fixed text, and the
 *    message of the failure does not travel.
 *
 * ## Why the sessions are real and the rest is a double
 *
 * The operations are a thin translation between the wire and the sessions. The sessions have
 * their own rows, so here they are the real ones, driven by an injected clock; the port that
 * answers about runs and the source of the descriptions are doubles.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ServiceProviderOperationsTest {

    private val now = AtomicLong(1_000L)
    private val sessions = ConfigurationProviderSessions(
        clock = { now.get() },
        limits = ConfigurationProviderLimits(
            leaseMillis = 1_000L,
            maxPollMillis = 200L,
            resolveDeadlineMillis = 500L,
            maxSessions = 3,
        ),
    )
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "provider-operations-test").apply { isDaemon = true }
    }

    @AfterEach
    fun tearDown() {
        executor.shutdownNow()
    }

    // ------------------------------------------------------------------------------ providers

    @Test
    fun `a provider registers and the answer carries the session and its lease`() {
        val response = operations().answer(register("ldap", key("REGION", "integer")), OWNER)

        val result = answeredOf(response)
        assertTrue(result["sessionId"]!!.jsonPrimitive.content.isNotBlank())
        assertEquals(1_000L, result["leaseMillis"]!!.jsonPrimitive.content.toLong())
        assertEquals(1, sessions.liveSessions())
    }

    @Test
    fun `a key labelled as a secret never registers`() {
        val response = operations().answer(register("ldap", key("DB_PASSWORD", "string", "secret")), OWNER)

        val refusal = providerRefusalOf(response)
        assertEquals("a-secret-is-not-configuration", refusal.reason)
        assertEquals(0, sessions.liveSessions(), "a refusal leaves no session behind")
    }

    @Test
    fun `a second session under the same name is refused by name`() {
        val operations = operations()
        operations.answer(register("ldap", key("REGION", "string")), OWNER)

        val second = operations.answer(register("ldap", key("REGION", "string")), OWNER)

        assertEquals("provider-name-taken", providerRefusalOf(second).reason)
    }

    @Test
    fun `a poll with nothing to ask answers idle`() {
        val operations = operations()
        val sessionId = registered(operations)

        val result = answeredOf(operations.answer(ServiceRequest.ProviderPoll(SCHEMA, sessionId, 0L), OWNER))

        assertEquals(ServiceOperations.POLL_IDLE, result["state"]!!.jsonPrimitive.content)
        assertFalse(result.containsKey("keys"))
    }

    @Test
    fun `a poll finds the keys a start waits for and the answer resolves the start`() {
        val operations = operations()
        val sessionId = registered(operations)
        val resolution = executor.submit<ProviderResolution> { sessions.resolve(OWNER, "ldap", listOf("REGION")) }

        val asked = waitForAsk(operations, sessionId)
        assertEquals(ServiceOperations.POLL_ASKED, asked["state"]!!.jsonPrimitive.content)
        assertEquals(listOf("REGION"), asked["keys"]!!.jsonArray.map { it.jsonPrimitive.content })
        val requestId = asked["requestId"]!!.jsonPrimitive.content

        val acknowledged = answeredOf(
            operations.answer(
                ServiceRequest.ProviderAnswer(SCHEMA, sessionId, requestId, "rev-3", mapOf("REGION" to "7")),
                OWNER,
            ),
        )

        assertEquals(ServiceOperations.OUTCOME_ANSWERED, acknowledged["outcome"]!!.jsonPrimitive.content)
        assertEquals(sessionId, acknowledged["sessionId"]!!.jsonPrimitive.content)
        val resolved = assertInstanceOf(ProviderResolution.Resolved::class.java, resolution.get(10, TimeUnit.SECONDS))
        assertEquals(mapOf("REGION" to "7"), resolved.values)
        assertEquals("rev-3", resolved.provenance.revision)
    }

    @Test
    fun `an answer for another request is refused by the name the session gives`() {
        val operations = operations()
        val sessionId = registered(operations)

        val refused = operations.answer(
            ServiceRequest.ProviderAnswer(SCHEMA, sessionId, "nobody-asked", "rev-1", mapOf("REGION" to "7")),
            OWNER,
        )

        assertTrue(providerRefusalOf(refused).reason.isNotBlank())
    }

    @Test
    fun `a close is acknowledged and the session is gone`() {
        val operations = operations()
        val sessionId = registered(operations)

        val closed = answeredOf(operations.answer(ServiceRequest.ProviderClose(SCHEMA, sessionId), OWNER))

        assertEquals(ServiceOperations.OUTCOME_CLOSED, closed["outcome"]!!.jsonPrimitive.content)
        assertEquals(0, sessions.liveSessions())
        val poll = operations.answer(ServiceRequest.ProviderPoll(SCHEMA, sessionId, 0L), OWNER)
        assertInstanceOf(ServiceRefusal.ConfigurationProviderRefused::class.java, refusalOf(poll))
    }

    @Test
    fun `another credential reaches nothing of a session it did not register`() {
        val operations = operations()
        val sessionId = registered(operations)

        val poll = operations.answer(ServiceRequest.ProviderPoll(SCHEMA, sessionId, 0L), "digest-of-someone-else")
        val close = operations.answer(ServiceRequest.ProviderClose(SCHEMA, sessionId), "digest-of-someone-else")

        assertInstanceOf(ServiceRefusal.ConfigurationProviderRefused::class.java, refusalOf(poll))
        assertInstanceOf(ServiceRefusal.ConfigurationProviderRefused::class.java, refusalOf(close))
        assertEquals(1, sessions.liveSessions(), "the session of the owner was not touched")
    }

    @Test
    fun `an operation with no credential registers and reaches nothing`() {
        val operations = operations()

        val none = operations.answer(register("ldap", key("REGION", "string")), null)
        val blank = operations.answer(register("ldap", key("REGION", "string")), "  ")

        assertEquals(ServiceOperations.REASON_PROVIDER_CREDENTIAL_MISSING, providerRefusalOf(none).reason)
        assertEquals(ServiceOperations.REASON_PROVIDER_CREDENTIAL_MISSING, providerRefusalOf(blank).reason)
        assertEquals(0, sessions.liveSessions())
    }

    @Test
    fun `a service composed without provider sessions refuses all four operations`() {
        val operations = operations(providers = null)
        val requests = listOf(
            register("ldap", key("REGION", "string")),
            ServiceRequest.ProviderPoll(SCHEMA, "s", 0L),
            ServiceRequest.ProviderAnswer(SCHEMA, "s", "r", "rev", emptyMap()),
            ServiceRequest.ProviderClose(SCHEMA, "s"),
        )

        for (request in requests) {
            assertEquals(
                ServiceOperations.REASON_PROVIDERS_NOT_COMPOSED,
                providerRefusalOf(operations.answer(request, OWNER)).reason,
                "$request",
            )
        }
    }

    @Test
    fun `a failure nobody foresaw is a refusal with a fixed text`() {
        val broken = ConfigurationProviderSessions(clock = { error("secret-path /home/someone/.vault") })

        val refusal = providerRefusalOf(
            operations(providers = broken).answer(register("ldap", key("REGION", "string")), OWNER),
        )

        assertEquals(ServiceOperations.REASON_PROVIDER_FAILED, refusal.reason)
        assertFalse(refusal.detail.contains("secret-path"), "the message of a failure does not travel")
    }

    // ------------------------------------------------------------------------ run configuration

    @Test
    fun `the description says where each key came from and carries the digests`() {
        val description = RunConfigurationDescription(
            digest = "digest-of-values",
            entries = listOf(
                RunConfigurationEntry("BUILD_PROFILE", RunConfigurationReader.ORIGIN_REQUEST, "string"),
                RunConfigurationEntry("REGION", RunConfigurationReader.ORIGIN_PROVIDER, "integer"),
            ),
            provider = "ldap",
            revision = "rev-9",
            sourceDigest = "digest-of-answer",
        )

        val result = answeredOf(
            operations(configurations = lookup(RunConfigurationLookup.Found(description)))
                .answer(ServiceRequest.RunConfigurationQuery(SCHEMA, "run-1"), OWNER),
        )

        assertEquals("run-1", result["runId"]!!.jsonPrimitive.content)
        assertEquals("digest-of-values", result["digest"]!!.jsonPrimitive.content)
        assertEquals(
            RunConfigurationReader.PRECEDENCE,
            result["precedence"]!!.jsonArray.map { it.jsonPrimitive.content },
            "the precedence is the one the service applies",
        )
        assertEquals(
            listOf(
                Triple("BUILD_PROFILE", "request", "string"),
                Triple("REGION", "provider", "integer"),
            ),
            (result["entries"] as JsonArray).map {
                val entry = it.jsonObject
                Triple(
                    entry["name"]!!.jsonPrimitive.content,
                    entry["origin"]!!.jsonPrimitive.content,
                    entry["type"]!!.jsonPrimitive.content,
                )
            },
        )
        assertEquals("ldap", result["provider"]!!.jsonPrimitive.content)
        assertEquals("rev-9", result["revision"]!!.jsonPrimitive.content)
        assertEquals("digest-of-answer", result["sourceDigest"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a run started without a provider has no provider fields in its description`() {
        val description = RunConfigurationDescription(
            digest = "digest-of-values",
            entries = listOf(RunConfigurationEntry("BUILD_PROFILE", "request", "string")),
            provider = null,
            revision = null,
            sourceDigest = null,
        )

        val result = answeredOf(
            operations(configurations = lookup(RunConfigurationLookup.Found(description)))
                .answer(ServiceRequest.RunConfigurationQuery(SCHEMA, "run-1"), OWNER),
        )

        assertNull(result["provider"])
        assertNull(result["revision"])
        assertNull(result["sourceDigest"])
    }

    @Test
    fun `a run the service did not start is refused by name`() {
        val refusal = configurationRefusalOf(
            operations(configurations = lookup(RunConfigurationLookup.Unknown))
                .answer(ServiceRequest.RunConfigurationQuery(SCHEMA, "run-x"), OWNER),
        )

        assertEquals("run-configuration-unknown", refusal.reason)
    }

    @Test
    fun `a snapshot that cannot be read is refused by name with the detail it gave`() {
        val refusal = configurationRefusalOf(
            operations(configurations = lookup(RunConfigurationLookup.Unreadable("the snapshot is not there")))
                .answer(ServiceRequest.RunConfigurationQuery(SCHEMA, "run-1"), OWNER),
        )

        assertEquals("run-configuration-unreadable", refusal.reason)
        assertEquals("the snapshot is not there", refusal.detail)
    }

    @Test
    fun `a source that throws is an unreadable configuration with a fixed text`() {
        val failing = object : ServiceRunConfigurations {
            override fun describe(runId: String): RunConfigurationLookup = error("secret-path /home/someone/.vault")
        }

        val refusal = configurationRefusalOf(
            operations(configurations = failing).answer(ServiceRequest.RunConfigurationQuery(SCHEMA, "run-1"), OWNER),
        )

        assertEquals("run-configuration-unreadable", refusal.reason)
        assertFalse(refusal.detail.contains("secret-path"))
    }

    @Test
    fun `a service composed without a source says it does not describe configurations`() {
        val refusal = configurationRefusalOf(
            operations(configurations = null).answer(ServiceRequest.RunConfigurationQuery(SCHEMA, "run-1"), OWNER),
        )

        assertEquals("run-configuration-unavailable", refusal.reason)
    }

    // ------------------------------------------------------------------------------ scaffolding

    private fun register(provider: String, vararg keys: ServiceProviderKey): ServiceRequest =
        ServiceRequest.ProviderRegister(SCHEMA, provider, keys.toList())

    private fun key(name: String, type: String, sensitivity: String = "non-secret") =
        ServiceProviderKey(name, type, sensitivity)

    private fun registered(operations: ServiceOperations): String =
        answeredOf(operations.answer(register("ldap", key("REGION", "integer")), OWNER))["sessionId"]!!
            .jsonPrimitive.content

    /** Polls until the session asks something: the resolution runs on another thread. */
    private fun waitForAsk(operations: ServiceOperations, sessionId: String): JsonObject {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val result = answeredOf(operations.answer(ServiceRequest.ProviderPoll(SCHEMA, sessionId, 50L), OWNER))
            if (result["state"]!!.jsonPrimitive.content == ServiceOperations.POLL_ASKED) return result
        }
        throw AssertionError("the session was never asked anything")
    }

    private fun answeredOf(response: ServiceResponse): JsonObject =
        assertInstanceOf(ServiceResponse.Answered::class.java, response).result

    private fun refusalOf(response: ServiceResponse): ServiceRefusal =
        assertInstanceOf(ServiceResponse.Refused::class.java, response).refusal

    private fun providerRefusalOf(response: ServiceResponse): ServiceRefusal.ConfigurationProviderRefused =
        assertInstanceOf(ServiceRefusal.ConfigurationProviderRefused::class.java, refusalOf(response))

    private fun configurationRefusalOf(response: ServiceResponse): ServiceRefusal.RunConfigurationRefused =
        assertInstanceOf(ServiceRefusal.RunConfigurationRefused::class.java, refusalOf(response))

    private fun lookup(answer: RunConfigurationLookup): ServiceRunConfigurations =
        object : ServiceRunConfigurations {
            override fun describe(runId: String): RunConfigurationLookup = answer
        }

    private fun operations(
        providers: ConfigurationProviderSessions? = sessions,
        configurations: ServiceRunConfigurations? = null,
    ): ServiceOperations = ServiceOperations(
        introspection = SilentPort,
        substrate = {
            ServiceSubstrate(
                eventStore = "/tmp/state/pipelinek.db",
                controlRoot = "/tmp/state/ctl",
                eventStorePresent = true,
                controlRootPresent = true,
            )
        },
        starts = ServiceTestStarter(),
        cancellations = null,
        providers = providers,
        configurations = configurations,
    )

    /** A port the provider operations never ask: a question that reached it would be a defect. */
    private object SilentPort : RuntimeIntrospectionPort {
        override fun inspect(runId: String): RuntimeIntrospectionResult = RuntimeIntrospectionResult.Observation(
            RuntimeObservation.Terminal(AttemptId(1), TerminalObservation.Succeeded(0L), 0L),
        )

        override fun runStructure(runId: String, options: StructureRunOptions): RunStructureResult =
            throw UnsupportedOperationException("not asked")

        override fun inspectOperation(operationId: String, attempt: Int): OperationInspectionResult =
            throw UnsupportedOperationException("not asked")
    }

    private companion object {
        const val SCHEMA: String = ServiceProtocol.SCHEMA
        const val OWNER: String = "digest-of-the-controller"
    }
}
